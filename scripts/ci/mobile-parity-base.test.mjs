import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { selectMobileParityBase } from './mobile-parity-base.mjs';

const script = fileURLToPath(new URL('./mobile-parity-base.mjs', import.meta.url));
const missingPrivateCommit = 'a'.repeat(40);
async function fixture(run) {
  const cwd = await mkdtemp(join(tmpdir(), 'mobile-parity-ci-'));
  const git = (...args) => execFileSync('git', args, { cwd, encoding: 'utf8' }).trim();
  const commit = async (file) => {
    await writeFile(join(cwd, file), file);
    git('add', file);
    git(
      '-c',
      'user.name=Fixture',
      '-c',
      'user.email=fixture@example.invalid',
      '-c',
      'commit.gpgSign=false',
      'commit',
      '--quiet',
      '-m',
      file,
    );
    return git('rev-parse', 'HEAD');
  };
  try {
    git('init', '--quiet', '-b', 'main');
    const root = await commit('public-root');
    const base = await commit('public-base');
    git('update-ref', 'refs/remotes/origin/main', base);
    const first = await commit('first-feature-change');
    const head = await commit('second-feature-change');
    const select = (options) =>
      selectMobileParityBase({ cwd, manifestBase: missingPrivateCommit, ...options });
    await run({ cwd, git, root, base, first, head, select });
  } finally {
    await rm(cwd, { recursive: true, force: true });
  }
}

test('a public PR checks every feature commit against its actual public base', async () => {
  await fixture(({ git, base, first, select }) => {
    const selected = select({
      eventName: 'pull_request',
      event: { pull_request: { base: { sha: base } } },
    });
    assert.equal(selected, base);
    assert.notEqual(selected, first);
    assert.deepEqual(git('diff', '--name-only', selected).split('\n'), [
      'first-feature-change',
      'second-feature-change',
    ]);
  });
});

test('a push uses its real previous revision rather than a private manifest commit', async () => {
  await fixture(({ root, select }) => {
    assert.equal(select({ eventName: 'push', event: { before: root } }), root);
  });
});

test('explicit missing or malformed event bases never fall back to a smaller range', async () => {
  await fixture(({ base, select }) => {
    assert.throws(
      () =>
        select({
          eventName: 'pull_request',
          manifestBase: base,
          event: { pull_request: { base: { sha: missingPrivateCommit } } },
        }),
      /Missing pull request base/,
    );
    assert.throws(
      () =>
        select({ eventName: 'push', manifestBase: base, event: { before: missingPrivateCommit } }),
      /Missing previous push revision/,
    );
    assert.throws(
      () =>
        select({
          eventName: 'workflow_dispatch',
          manifestBase: base,
          override: missingPrivateCommit,
        }),
      /Missing explicit/,
    );
    assert.throws(
      () =>
        select({
          eventName: 'pull_request',
          event: { pull_request: { base: { sha: 'HEAD\nINJECTED=1' } } },
        }),
      /Invalid pull request base/,
    );
  });
});

test('manual audits preserve the full manifest or branch range when it is available', async () => {
  await fixture(({ root, base, first, select }) => {
    assert.equal(select({ eventName: 'workflow_dispatch', manifestBase: root }), root);
    assert.equal(select({ eventName: 'workflow_dispatch' }), base);
    assert.equal(
      select({ eventName: 'workflow_dispatch', event: { inputs: { mobile_parity_base: first } } }),
      first,
    );
  });
});

test('a public main manual audit compares its previous snapshot and rejects no-history roots', async () => {
  await fixture(({ git, root, head, first, select }) => {
    git('update-ref', 'refs/remotes/origin/main', head);
    assert.equal(select({ eventName: 'workflow_dispatch' }), first);
    git('checkout', '--quiet', '--detach', root);
    git('update-ref', 'refs/remotes/origin/main', root);
    assert.throws(() => select({ eventName: 'workflow_dispatch' }), /No comparison history/);
  });
});

test('the workflow exports the verified public base into the following pnpm check step', async () => {
  await fixture(async ({ cwd, base }) => {
    await mkdir(join(cwd, 'docs/product'), { recursive: true });
    await writeFile(
      join(cwd, 'docs/product/mobile-parity.manifest.json'),
      JSON.stringify({ baselineCommit: missingPrivateCommit }),
    );
    const payload = join(cwd, 'event.json');
    const output = join(cwd, 'job.env');
    await writeFile(payload, JSON.stringify({ pull_request: { base: { sha: base } } }));
    const env = {
      ...process.env,
      GITHUB_EVENT_NAME: 'pull_request',
      GITHUB_EVENT_PATH: payload,
      GITHUB_ENV: output,
    };
    delete env.MOBILE_PARITY_BASE_SHA;
    execFileSync(process.execPath, [script], { cwd, env, stdio: 'pipe' });
    assert.equal(await readFile(output, 'utf8'), `MOBILE_PARITY_BASE_SHA=${base}\n`);
  });
});
