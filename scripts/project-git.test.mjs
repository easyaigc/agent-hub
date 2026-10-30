import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtemp, mkdir, writeFile, rm, symlink, readFile, chmod } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ProjectGitService, parseGitStatus } from '../apps/host-agent/dist/project-git.js';
import {
  parseProjectGitResponse,
  projectGitCacheKey,
} from '../packages/protocol/dist/project-git.js';

const target = { agentId: 'codex', hostId: 'host', workspaceId: 'workspace', runtimeId: 'runtime' };
const other = { ...target, runtimeId: 'other' };
const git = (dir, ...args) =>
  execFileSync('git', ['-C', dir, ...args], {
    encoding: 'utf8',
    env: {
      ...process.env,
      GIT_CONFIG_NOSYSTEM: '1',
      GIT_CONFIG_GLOBAL: '/dev/null',
      GIT_AUTHOR_NAME: 'Fixture',
      GIT_AUTHOR_EMAIL: 'fixture@example.invalid',
      GIT_COMMITTER_NAME: 'Fixture',
      GIT_COMMITTER_EMAIL: 'fixture@example.invalid',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  }).trim();
async function fixture(t) {
  const dir = await mkdtemp(join(tmpdir(), 'project-git-test-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  return dir;
}
async function repo(dir) {
  await mkdir(dir, { recursive: true });
  git(dir, 'init', '-b', 'main');
  await writeFile(join(dir, 'tracked'), 'one\n');
  git(dir, 'add', '.');
  git(dir, 'commit', '-m', 'initial');
}
function service(t, options = {}) {
  const s = new ProjectGitService({ secret: 'fixture-secret', timeoutMs: 15000, ...options });
  t.after(() => s.close());
  return s;
}
const trees = (r) => r.repositories.flatMap((v) => v.worktrees);

test('real status preserves rename source and newline names, counts one path across index and worktree', async (t) => {
  const dir = await fixture(t);
  await repo(dir);
  git(dir, 'mv', 'tracked', 'renamed');
  await writeFile(join(dir, 'renamed'), 'changed again\n');
  await writeFile(join(dir, 'line\nname'), 'new');
  const raw = execFileSync('git', [
    '-C',
    dir,
    'status',
    '--porcelain=v2',
    '--branch',
    '-z',
    '--untracked-files=all',
  ]);
  const parsed = parseGitStatus(raw);
  assert.equal(parsed.changes.changed, 2);
  assert.equal(parsed.changes.staged, 1);
  assert.equal(parsed.changes.unstaged, 1);
  assert.equal(parsed.files.find((f) => f.path === 'renamed').oldPath, 'tracked');
  assert.ok(parsed.files.some((f) => f.path === 'line\\u000aname' && f.untracked));
  const s = service(t);
  const result = await s.read('project', [dir], target);
  assert.deepEqual(trees(result)[0].changes, parsed.changes);
  assert.equal(parseProjectGitResponse(result, target, 'project'), result);
  assert.throws(() => parseProjectGitResponse(result, other, 'project'));
});

test('nested repositories and linked worktree share repository identity', async (t) => {
  const root = await fixture(t);
  const a = join(root, 'a');
  const nested = join(a, 'nested');
  const linked = join(root, 'linked');
  await repo(a);
  await repo(nested);
  git(a, 'worktree', 'add', '-b', 'linked', linked);
  const s = service(t);
  const r = await s.read('p', [root], target);
  assert.equal(r.kind, 'git');
  assert.equal(r.repositories.length, 2);
  assert.deepEqual(r.repositories.map((v) => v.worktrees.length).sort(), [1, 2]);
});

test('upstream, push remote and remote HEAD remain separate', async (t) => {
  const root = await fixture(t);
  const a = join(root, 'a');
  const bare1 = join(root, 'one.git');
  const bare2 = join(root, 'two.git');
  await repo(a);
  git(root, 'init', '--bare', bare1);
  git(root, 'init', '--bare', bare2);
  git(a, 'remote', 'add', 'origin', bare1);
  git(a, 'remote', 'add', 'publish', bare2);
  git(a, 'push', '-u', 'origin', 'main');
  git(a, 'push', 'publish', 'main');
  git(a, 'config', 'branch.main.pushRemote', 'publish');
  git(a, 'config', 'push.default', 'current');
  git(a, 'symbolic-ref', 'refs/remotes/origin/HEAD', 'refs/remotes/origin/main');
  await writeFile(join(a, 'tracked'), 'two\n');
  git(a, 'commit', '-am', 'next');
  const r = await service(t).read('p', [a], target);
  const tree = trees(r)[0];
  assert.equal(tree.upstream.ref, 'refs/remotes/origin/main');
  assert.equal(tree.publish.ref, 'refs/remotes/publish/main');
  assert.equal(tree.baseline.source, 'remote-head');
  assert.equal(tree.baseline.ref, 'refs/remotes/origin/main');
  assert.equal(tree.upstream.ahead, 1);
  assert.equal(tree.publish.ahead, 1);
});

test('missing upstream and shallow history are unknown', async (t) => {
  const root = await fixture(t);
  const a = join(root, 'a');
  const clone = join(root, 'clone');
  await repo(a);
  const s = service(t);
  assert.equal(trees(await s.read('p', [a], target))[0].upstream.reason, 'unconfigured');
  git(root, 'clone', '--depth', '1', `file://${a}`, clone);
  const tree = trees(await s.read('q', [clone], target))[0];
  assert.equal(tree.shallow, true);
  assert.equal(tree.upstream.reason, 'shallow-history');
  assert.equal(tree.upstream.ahead, null);
});

test('missing promisor object cannot start a transport with legacy lazy-fetch behavior', async (t) => {
  const root = await fixture(t);
  const a = join(root, 'a');
  const bare = join(root, 'remote.git');
  const bin = join(root, 'bin');
  const marker = join(root, 'helper-started');
  await repo(a);
  const missing = git(a, 'rev-parse', 'HEAD');
  await writeFile(join(a, 'tracked'), 'next\n');
  git(a, 'commit', '-am', 'next');
  git(root, 'init', '--bare', bare);
  git(a, 'remote', 'add', 'origin', bare);
  git(a, 'push', '-u', 'origin', 'main');
  git(a, 'update-ref', 'refs/remotes/origin/main', missing);
  git(a, 'remote', 'set-url', 'origin', 'fixture::local');
  git(a, 'config', 'remote.origin.promisor', 'true');
  git(a, 'config', 'remote.origin.partialclonefilter', 'blob:none');
  const object = join(a, '.git', 'objects', missing.slice(0, 2), missing.slice(2));
  await rm(object);
  await mkdir(bin);
  const actualGit = execFileSync('which', ['git'], { encoding: 'utf8' }).trim();
  const shim = join(bin, 'git');
  await writeFile(
    shim,
    `#!${process.execPath}\nconst {spawnSync} = require('node:child_process');\nconst env = {...process.env}; delete env.GIT_NO_LAZY_FETCH;\nconst result = spawnSync(${JSON.stringify(actualGit)}, process.argv.slice(2), {env, stdio:'inherit'});\nprocess.exit(result.status ?? 1);\n`,
  );
  await writeFile(
    join(bin, 'git-remote-fixture'),
    `#!${process.execPath}\nrequire('node:fs').writeFileSync(${JSON.stringify(marker)}, 'started');\nprocess.exit(1);\n`,
  );
  await chmod(shim, 0o700);
  await chmod(join(bin, 'git-remote-fixture'), 0o700);
  const oldPath = process.env.PATH;
  process.env.PATH = `${bin}:${oldPath}`;
  try {
    // Positive control: the missing object really causes Git to invoke the local helper.
    assert.throws(() =>
      execFileSync(
        shim,
        ['-C', a, 'rev-parse', '--verify', '--end-of-options', 'refs/remotes/origin/main^{commit}'],
        {
          env: { ...process.env, GIT_ALLOW_PROTOCOL: 'fixture' },
          stdio: 'ignore',
        },
      ),
    );
    assert.equal(await readFile(marker, 'utf8'), 'started');
    await rm(marker);
    const tree = trees(await service(t).read('promisor', [a], target))[0];
    assert.equal(tree.upstream.state, 'unknown');
    assert.equal(tree.upstream.ahead, null);
    assert.equal(tree.upstream.behind, null);
    await assert.rejects(readFile(marker), { code: 'ENOENT' });
    await assert.rejects(readFile(object), { code: 'ENOENT' });
  } finally {
    if (oldPath === undefined) delete process.env.PATH;
    else process.env.PATH = oldPath;
  }
});

test('merge conflict is counted and operation detected', async (t) => {
  const dir = await fixture(t);
  await repo(dir);
  git(dir, 'checkout', '-b', 'other');
  await writeFile(join(dir, 'tracked'), 'other\n');
  git(dir, 'commit', '-am', 'other');
  git(dir, 'checkout', 'main');
  await writeFile(join(dir, 'tracked'), 'main\n');
  git(dir, 'commit', '-am', 'main');
  assert.throws(() => git(dir, 'merge', 'other'));
  const tree = trees(await service(t).read('p', [dir], target))[0];
  assert.equal(tree.changes.conflicts, 1);
  assert.equal(tree.operation, 'merge');
});

test('boundaries, scan budget and GIT_DIR isolation', async (t) => {
  const root = await fixture(t);
  const a = join(root, 'a');
  const outside = join(root, 'outside');
  await repo(a);
  await repo(outside);
  await mkdir(join(a, 'child'));
  await symlink(outside, join(a, 'escape'));
  const s = service(t, { allowedRoots: [a] });
  assert.equal((await s.read('p', [join(a, 'child')], target)).issues[0].reason, 'outside-project');
  assert.equal(
    (await s.read('q', [join(root, 'missing')], target)).issues[0].reason,
    'directory-unavailable',
  );
  assert.equal(
    (await s.read('r', [join(a, 'escape')], target)).issues[0].reason,
    'outside-project',
  );
  await writeFile(join(outside, 'tracked'), 'different\n');
  git(outside, 'commit', '-am', 'different');
  const expectedHead = git(a, 'rev-parse', 'HEAD');
  const old = process.env.GIT_DIR;
  process.env.GIT_DIR = join(outside, '.git');
  try {
    const r = await s.read('s', [a], target);
    assert.equal(trees(r).length, 1);
    assert.equal(trees(r)[0].head, expectedHead);
  } finally {
    if (old === undefined) delete process.env.GIT_DIR;
    else process.env.GIT_DIR = old;
  }
  const limited = service(t, { maxDirectories: 1 });
  assert.ok((await limited.read('x', [a], target)).issues.some((i) => i.reason === 'scan-limit'));
  const explicitRoots = await limited.read('explicit', [a, outside], target);
  assert.equal(
    trees(explicitRoots).length,
    2,
    'A busy first root must not hide a second explicit root',
  );
  assert.deepEqual(
    new Set(trees(explicitRoots).map((tree) => tree.displayPath)),
    new Set(['a', 'outside']),
  );
  const blank = join(root, 'blank');
  await mkdir(blank);
  assert.equal((await s.read('blank', [blank], target)).kind, 'unknown');
  assert.equal((await service(t).read('blank', [blank], target)).kind, 'non-git');
  const crowded = join(root, 'crowded');
  await mkdir(crowded);
  for (let start = 0; start < 4097; start += 128) {
    await Promise.all(
      Array.from({ length: Math.min(128, 4097 - start) }, (_, i) =>
        writeFile(join(crowded, `file-${start + i}`), ''),
      ),
    );
  }
  const crowdedResult = await service(t).read('crowded', [crowded], target);
  assert.equal(crowdedResult.kind, 'unknown');
  assert.ok(crowdedResult.issues.some((i) => i.reason === 'scan-limit'));
  const deepRoot = join(root, 'deep-only');
  await Promise.all(
    Array.from({ length: 10 }, (_, i) =>
      mkdir(join(deepRoot, 'l1', 'l2', 'l3', String(i)), { recursive: true }),
    ),
  );
  const deepResult = await service(t).read('deep', [deepRoot], target);
  assert.equal(deepResult.complete, false);
  assert.equal(
    deepResult.issues.filter((i) => i.reason === 'scan-limit').length,
    1,
    'Repeated global limits must not bury repository details in duplicate rows',
  );
});

test('cache signatures, snapshot, target and cursor isolate file pages; close rejects further reads', async (t) => {
  const root = await fixture(t);
  const a = join(root, 'a');
  const b = join(root, 'b');
  await repo(a);
  await repo(b);
  for (let i = 0; i < 105; i++) await writeFile(join(a, `file-${i}`), 'x');
  const s = service(t, { cacheMs: 1 });
  const r = await s.read('p', [a], target);
  await new Promise((done) => setTimeout(done, 5));
  // Opening a file list outlives summary freshness; keep its bound snapshot readable.
  const id = trees(r)[0].id;
  const first = s.files('p', [a], target, id, r.snapshotId, null);
  assert.equal(first.files.length, 100);
  assert.equal(first.nextCursor, '100');
  assert.equal(s.files('p', [a], target, id, r.snapshotId, first.nextCursor).files.length, 5);
  assert.throws(() => s.files('p', [a], other, id, r.snapshotId, null));
  assert.throws(() => s.files('p', [a], target, id, 'wrong', null));
  assert.throws(() => s.files('p', [a], target, id, r.snapshotId, '-1'));
  assert.throws(() => s.files('p', [b], target, id, r.snapshotId, null));
  const next = await s.read('p', [b], target);
  assert.notEqual(next.snapshotId, r.snapshotId);
  assert.throws(() => s.files('p', [a], target, id, r.snapshotId, null));
  assert.notEqual(projectGitCacheKey(target, 'p'), projectGitCacheKey(other, 'p'));
  s.close();
  await assert.rejects(s.read('p', [a], target));
});

test('closing during an active scan settles pending work and rejects later reads', async (t) => {
  const root = await fixture(t);
  const a = join(root, 'a');
  await repo(a);
  const s = service(t);
  const pending = s.read('p', [a], target);
  s.close();
  const result = await pending;
  assert.ok(result.issues.some((issue) => issue.reason === 'timeout'));
  await assert.rejects(s.read('p', [a], target));
});

test('runtime HTTP route enforces authentication, target, project, method and byte limit', async (t) => {
  const root = await fixture(t);
  const a = join(root, 'repo');
  await repo(a);
  const smokeSource = await readFile(new URL('./runtime-smoke.mjs', import.meta.url), 'utf8');
  const start = smokeSource.indexOf('  `#!${process.execPath}');
  const end = smokeSource.indexOf('\n`,\n);', start);
  assert.ok(start >= 0 && end > start, 'runtime-smoke fixture template available');
  const template = smokeSource.slice(start, end + 2).trim();
  const executable = join(root, 'fixture.cjs');
  const directory = root;
  await writeFile(
    executable,
    Function('directory', 'root', 'join', `return ${template}`)(directory, root, join),
  );
  await chmod(executable, 0o700);
  const token = 'fixture-token-012345678901234567890123456789';
  const changes = {
    CODEX_RUNTIME_MODE: 'managed-runtime',
    CODEX_EXECUTABLE: executable,
    HOST_AGENT_TOKEN: token,
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: '0',
    HOST_AGENT_STATE_DIR: join(root, 'state'),
    HOST_AGENT_ROOT: root,
    HOST_AGENT_DISCOVERY: '0',
    HOST_AGENT_DISCOVERY_BONJOUR: '0',
  };
  const previous = Object.fromEntries(Object.keys(changes).map((key) => [key, process.env[key]]));
  Object.assign(process.env, changes);
  let runtime;
  try {
    const { startRuntimeServer } = await import('../apps/host-agent/dist/runtime-server.js');
    runtime = await startRuntimeServer();
    const current = runtime.session.readingTarget();
    const selected = Object.fromEntries(
      ['agentId', 'hostId', 'workspaceId', 'runtimeId'].map((key) => [key, current[key]]),
    );
    const originalWorkspace = runtime.session.workspace.bind(runtime.session);
    runtime.session.workspace = async () => ({
      ...(await originalWorkspace()),
      projects: [{ id: 'fixture-project', hostId: current.hostId, roots: [a] }],
    });
    const endpoint = `http://127.0.0.1:${runtime.server.address().port}`;
    const query = (value, project = 'fixture-project') =>
      new URLSearchParams({ target: JSON.stringify(value), projectId: project });
    const get = (path, options = {}) =>
      fetch(endpoint + path, {
        ...options,
        headers: { Authorization: `Bearer ${token}`, ...options.headers },
      });
    assert.equal(
      (await get('/v1/project-git', { headers: { Authorization: 'Bearer wrong' } })).status,
      401,
    );
    assert.equal((await get('/v1/project-git?projectId=fixture-project')).status, 400);
    assert.equal(
      (await get('/v1/project-git?' + query({ ...selected, runtimeId: 'wrong' }))).status,
      409,
    );
    assert.equal((await get('/v1/project-git?' + query(selected, 'unknown'))).status, 404);
    assert.equal((await get('/v1/project-git?' + query(selected), { method: 'POST' })).status, 405);
    const response = await get('/v1/project-git?' + query(selected));
    assert.equal(response.status, 200);
    const bytes = await response.arrayBuffer();
    assert.ok(bytes.byteLength <= 128 * 1024);
    const value = JSON.parse(Buffer.from(bytes).toString());
    assert.equal(value.projectId, 'fixture-project');
    assert.equal(value.kind, 'git');
  } finally {
    runtime?.close();
    for (const [key, value] of Object.entries(previous)) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
  }
});
