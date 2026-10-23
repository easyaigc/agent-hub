import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFile, readdir, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { DesktopRuntime, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { preparedAdapterDigest } from '../packages/codex-adapter/dist/desktop-prepared-bridge.js';

// Resume independent, read-only verification after phone actions. Never submit or reopen a chat.
const directory = resolve(process.argv[2] ?? '');
assert.ok(directory.startsWith(resolve('.local/thread-fork-dev') + '/live-'));
const fixture = JSON.parse(await readFile(join(directory, 'fixture.json'), 'utf8'));
const prior = JSON.parse(await readFile(join(directory, 'report.json'), 'utf8'));
assert.equal(prior.physical, true);
assert.equal(fixture.threadId, prior.source.threadId);
assert.equal(fixture.turnId, prior.source.turnId);
const state = resolve('.local/desktop-runtime');
const runtime = JSON.parse(await readFile(join(state, 'runtime.json'), 'utf8'));
assert.equal(runtime.hostId, fixture.hostId);
const digest = preparedAdapterDigest();
assert.equal(digest, prior.adapterSha256);
const witness = new DesktopRuntime(
  {
    mode: 'desktop-runtime',
    executable:
      process.env.CODEX_EXECUTABLE ??
      '/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex',
  },
  fixture.hostId,
  'Phone fork read-only witness',
);
const report = {
  kind: 'physical-fork-independent-evidence',
  nonce: prior.nonce,
  adapterSha256: digest,
  observedAt: new Date().toISOString(),
  priorHarnessError: prior.error ?? null,
  scenarios: [],
};
const owner = async (id) => {
  const deadline = Date.now() + 45000;
  do {
    const value = await witness.ipc.follow(id, 3000, true);
    if (value && witness.ipc.hasOwner(id)) {
      assert.equal(value.id, id);
      assert.equal(value.hostId, 'local');
      return value;
    }
  } while (Date.now() < deadline);
  throw Error('Exact original desktop owner unavailable: ' + id);
};
const repo = join(directory, 'repository');
const git = (...args) => execFileSync('git', ['-C', repo, ...args], { encoding: 'utf8' }).trim();
const sourceSnapshot = () => ({
  head: git('rev-parse', 'HEAD'),
  diff: git('diff', 'HEAD', '--binary'),
  stagedDiff: git('diff', '--cached', '--binary'),
});
const save = () =>
  writeFile(join(directory, 'physical-evidence.json'), JSON.stringify(report, null, 2), {
    mode: 0o600,
  });
try {
  const baseline = sourceSnapshot();
  assert.equal(baseline.head, prior.source.head);
  if (fixture.sourceBaseline) assert.deepEqual(baseline, fixture.sourceBaseline);
  // Older fixture files did not save diffs. Verify their exact synthetic HEAD/index/content instead.
  assert.equal(git('show', 'HEAD:tracked.txt'), 'base');
  assert.equal(git('show', ':tracked.txt'), 'base\nstaged');
  assert.equal(await readFile(join(repo, 'tracked.txt'), 'utf8'), 'base\nstaged\nunstaged\n');
  assert.equal(
    await readFile(join(repo, 'untracked.txt'), 'utf8'),
    'FORK_UNTRACKED_' + prior.nonce,
  );
  await witness.connect();
  report.desktopVersion = witness.version;
  const files = (await readdir(join(state, 'desktop-forks'))).filter((f) => f.endsWith('.json'));
  const records = (
    await Promise.all(
      files.map(async (file) =>
        JSON.parse(await readFile(join(state, 'desktop-forks', file), 'utf8')),
      ),
    )
  ).filter((r) => r.threadId === fixture.threadId);
  assert.equal(records.length, 2);
  for (const environment of ['same-directory', 'worktree']) {
    const record = records.find((r) => r.environment === environment);
    assert.ok(record);
    assert.equal(record.hostId, fixture.hostId);
    assert.equal(record.turnId, fixture.turnId);
    assert.equal(record.phase, 'confirmed');
    assert.equal(record.preparationExitCode, 0);
    const value = await owner(record.forkedThreadId);
    const turns = desktopTurns(value);
    assert.equal(turns.length, 2);
    assert.equal(turns[0].turnId, fixture.turnId);
    assert.equal(turns[1].status, 'completed');
    assert.ok(!JSON.stringify(turns).includes(fixture.laterMarker));
    const marker = environment === 'worktree' ? fixture.worktreeMarker : fixture.sameMarker;
    const inputs = turns[1].items.filter((item) => item.type === 'userMessage');
    assert.equal(inputs.length, 1);
    assert.ok(JSON.stringify(inputs[0]).includes(marker));
    assert.ok(
      turns[1].items.some(
        (item) =>
          item.type === 'agentMessage' &&
          item.text?.includes(fixture.firstMarker) &&
          item.text?.includes(marker),
      ),
    );
    if (environment === 'same-directory') assert.equal(resolve(value.cwd), repo);
    else {
      assert.notEqual(resolve(value.cwd), repo);
      assert.equal(
        execFileSync('git', ['-C', value.cwd, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).trim(),
        prior.source.head,
      );
      assert.equal(
        await readFile(join(value.cwd, 'tracked.txt'), 'utf8'),
        'base\nstaged\nunstaged\n',
      );
      assert.equal(
        await readFile(join(value.cwd, 'untracked.txt'), 'utf8'),
        'FORK_UNTRACKED_' + prior.nonce,
      );
    }
    report.scenarios.push({
      environment,
      clientOperationId: record.clientOperationId,
      threadId: record.forkedThreadId,
      cwd: value.cwd,
      originalOwner: true,
      preparationExitCode: 0,
      historyPrefixAndRealModel: true,
      oneNativeInput: true,
    });
  }
  const source = desktopTurns(await owner(fixture.threadId));
  assert.equal(source.length, 2);
  assert.equal(source[0].turnId, fixture.turnId);
  assert.ok(JSON.stringify(source[1]).includes(fixture.laterMarker));
  assert.deepEqual(sourceSnapshot(), baseline);
  report.sourceHeadIndexAndContentsUnchanged = true;
  report.readOnlyVerification = true;
  report.passed = true;
  await save();
  console.log(JSON.stringify({ passed: true, report: join(directory, 'physical-evidence.json') }));
} catch (error) {
  report.error = error.message;
  await save();
  throw error;
} finally {
  witness.close();
}
