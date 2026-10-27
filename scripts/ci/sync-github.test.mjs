import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, chmodSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const script = fileURLToPath(new URL('./sync-github.mjs', import.meta.url));
function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'agent-hub-github-sync-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const source = join(root, 'source');
  const destination = join(root, 'public');
  const git = (cwd, ...args) =>
    execFileSync('git', args, { cwd, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] }).trim();
  for (const cwd of [source, destination]) {
    git(root, 'init', '-b', 'main', cwd);
    git(cwd, 'config', 'user.name', 'Fixture');
    git(cwd, 'config', 'user.email', 'fixture@example.invalid');
  }
  writeFileSync(join(source, 'internal-only.txt'), 'private historical content');
  git(source, 'add', '.');
  git(source, 'commit', '-m', 'internal history');
  const privateCommit = git(source, 'rev-parse', 'HEAD');
  git(source, 'rm', 'internal-only.txt');
  writeFileSync(join(source, 'app.bin'), Buffer.from([0, 1, 255, 3]));
  writeFileSync(join(source, 'run.sh'), '#!/bin/sh\nexit 0\n');
  chmodSync(join(source, 'run.sh'), 0o755);
  git(source, 'add', '.');
  git(source, 'commit', '-m', 'current source');
  writeFileSync(join(destination, 'README.md'), 'public baseline');
  git(destination, 'add', '.');
  git(destination, 'commit', '-m', 'independent public history');
  git(destination, 'tag', 'existing-release');
  const publicBase = git(destination, 'rev-parse', 'HEAD');
  // A bare remote behaves like GitHub and allows updating its main branch.
  const remote = join(root, 'github.git');
  git(root, 'clone', '--bare', destination, remote);
  const run = (...args) =>
    spawnSync(process.execPath, [script, '--target-url', remote, '--source-ref', 'HEAD', ...args], {
      cwd: source,
      encoding: 'utf8',
    });
  return { source, remote, git, run, privateCommit, publicBase };
}

test('publishes the exact source tree with only the existing public parent', (t) => {
  const f = fixture(t);
  const result = f.run();
  assert.equal(result.status, 0, result.stderr);
  const report = JSON.parse(result.stdout);
  assert.equal(report.status, 'published');
  assert.equal(
    f.git(f.remote, 'rev-parse', 'main^{tree}'),
    f.git(f.source, 'rev-parse', 'HEAD^{tree}'),
  );
  assert.equal(f.git(f.remote, 'rev-parse', 'main^'), f.publicBase);
  assert.equal(f.git(f.remote, 'rev-list', '--count', 'main'), '2');
  assert.equal(f.git(f.remote, 'rev-parse', 'refs/tags/existing-release'), f.publicBase);
  assert.equal(f.git(f.source, 'status', '--porcelain'), '');
  const history = f.git(f.remote, 'rev-list', 'main').split('\n');
  assert.ok(!history.includes(f.privateCommit));
  assert.match(
    f.git(f.remote, 'show', '-s', '--format=%B', 'main'),
    /Gitea-Source-Commit: [a-f0-9]{40}/,
  );
});

test('unchanged trees do not produce duplicate commits', (t) => {
  const f = fixture(t);
  assert.equal(f.run().status, 0);
  const before = f.git(f.remote, 'rev-parse', 'main');
  const result = f.run();
  assert.equal(result.status, 0, result.stderr);
  assert.equal(JSON.parse(result.stdout).status, 'unchanged');
  assert.equal(f.git(f.remote, 'rev-parse', 'main'), before);
});

test('preview leaves the remote and source index unchanged', (t) => {
  const f = fixture(t);
  writeFileSync(join(f.source, 'unsaved.txt'), 'keep local work');
  const before = f.git(f.source, 'status', '--porcelain');
  const result = f.run('--dry-run');
  assert.equal(result.status, 0, result.stderr);
  assert.equal(JSON.parse(result.stdout).status, 'preview');
  assert.equal(f.git(f.remote, 'rev-parse', 'main'), f.publicBase);
  assert.equal(f.git(f.source, 'status', '--porcelain'), before);
});

test('rejects stale source revisions after a newer source was published', (t) => {
  const f = fixture(t);
  assert.equal(f.run().status, 0);
  const published = f.git(f.remote, 'rev-parse', 'main');
  f.git(f.source, 'checkout', '--detach', f.privateCommit);
  const result = f.run();
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /older or unrelated source revision/);
  assert.equal(f.git(f.remote, 'rev-parse', 'main'), published);
});

test('a concurrent destination update is preserved when push is rejected', (t) => {
  const f = fixture(t);
  const tree = f.git(f.remote, 'rev-parse', 'main^{tree}');
  const competing = execFileSync(
    'git',
    [
      '-c',
      'user.name=Fixture',
      '-c',
      'user.email=fixture@example.invalid',
      'commit-tree',
      tree,
      '-p',
      f.publicBase,
    ],
    { cwd: f.remote, encoding: 'utf8', input: 'concurrent public change\n' },
  ).trim();
  const hook = join(f.source, '.git', 'hooks', 'pre-push');
  writeFileSync(
    hook,
    `#!/bin/sh\ngit --git-dir='${f.remote}' update-ref refs/heads/main ${competing}\n`,
  );
  chmodSync(hook, 0o755);
  const result = f.run();
  assert.notEqual(result.status, 0);
  assert.equal(f.git(f.remote, 'rev-parse', 'main'), competing);
  assert.ok(!result.stdout.includes('published'));
});
