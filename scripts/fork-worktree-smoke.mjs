import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { constants } from 'node:fs';
import {
  access,
  chmod,
  lstat,
  mkdir,
  mkdtemp,
  open,
  readFile,
  readdir,
  readlink,
  realpath,
  rm,
  symlink,
  writeFile,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { delimiter, dirname, join } from 'node:path';
import {
  inspectForkWorktree,
  prepareForkWorktree,
} from '../packages/codex-adapter/dist/fork-worktree.js';

// Real, private temporary Git repositories only. No desktop, Host or device.
// Run after building the adapter: pnpm --filter @codex-manage/codex-adapter build
// then pnpm exec node scripts/fork-worktree-smoke.mjs.
const root = await realpath(await mkdtemp(join(tmpdir(), 'codex-fork-smoke-')));
const hash = (value) => createHash('sha256').update(value).digest('hex');
const git = (cwd, args, input) =>
  execFileSync('git', ['--no-optional-locks', '-c', 'core.fsmonitor=false', ...args], {
    cwd,
    input,
    env: Object.fromEntries(Object.entries(process.env).filter(([key]) => !/^GIT_/i.test(key))),
    maxBuffer: 16 << 20,
    stdio: ['pipe', 'pipe', 'pipe'],
  });
let passed = 0;
let sequence = 0;
async function test(name, run) {
  await run();
  passed++;
  console.log(JSON.stringify({ test: name, status: 'passed', scope: 'real-temporary-git' }));
}
async function repo(name, commit = true) {
  const path = join(root, `${++sequence}-${name}`);
  await mkdir(path);
  git(path, ['init', '--initial-branch=main']);
  git(path, ['config', 'user.name', 'Fork Fixture']);
  git(path, ['config', 'user.email', 'fork-fixture@example.invalid']);
  git(path, ['config', 'commit.gpgSign', 'false']);
  git(path, ['config', 'core.autocrlf', 'false']);
  if (commit) {
    await mkdir(join(path, 'nested'));
    await writeFile(join(path, 'nested', 'tracked.txt'), 'nested base\n');
    await writeFile(join(path, 'tracked.txt'), 'base\n');
    await writeFile(join(path, 'deleted.txt'), 'delete me\n');
    await writeFile(join(path, 'binary.bin'), Buffer.from([0, 1, 255, 2, 0, 3]));
    await writeFile(join(path, '.gitignore'), 'ignored/\n*.ignored\n');
    git(path, ['add', '--all']);
    git(path, ['commit', '--quiet', '-m', 'fixture baseline']);
  }
  return path;
}
const destination = (name) => join(root, `${++sequence}-destination-${name}`);
async function absent(path) {
  await assert.rejects(lstat(path), { code: 'ENOENT' });
}
async function sourceProof(path) {
  const files = [];
  async function scan(directory, prefix = '') {
    for (const name of (await readdir(directory)).sort()) {
      if (!prefix && name === '.git') continue;
      const item = join(directory, name);
      const part = prefix + name;
      const stat = await lstat(item);
      if (stat.isSymbolicLink()) files.push([part, 'link', await readlink(item)]);
      else if (stat.isDirectory()) {
        files.push([part, 'directory', stat.mode & 0o777]);
        await scan(item, part + '/');
      } else files.push([part, stat.mode & 0o777, hash(await readFile(item))]);
    }
  }
  await scan(path);
  const indexPath = git(path, ['rev-parse', '--path-format=absolute', '--git-path', 'index'])
    .toString()
    .trim();
  return {
    files,
    index: hash(await readFile(indexPath)),
    head: git(path, ['rev-parse', 'HEAD']).toString(),
    ref: git(path, ['symbolic-ref', '-q', 'HEAD']).toString(),
    refs: git(path, ['for-each-ref', '--format=%(refname) %(objectname)']).toString(),
    status: git(path, ['status', '--porcelain=v1', '-z', '--untracked-files=all']).toString('hex'),
    diff: hash(git(path, ['diff', '--binary', '--full-index', 'HEAD', '--'])),
    staged: hash(git(path, ['diff', '--cached', '--binary', '--full-index', 'HEAD', '--'])),
  };
}
async function receipt(dest) {
  const found = [];
  for (const name of await readdir(dirname(dest))) {
    if (!/^\.codex-fork-[a-f0-9-]+\.json$/.test(name)) continue;
    const path = join(dirname(dest), name);
    const value = JSON.parse(await readFile(path, 'utf8'));
    if (value.destination === dest) found.push({ path, value });
  }
  assert.equal(found.length, 1);
  if (process.platform !== 'win32') assert.equal((await lstat(found[0].path)).mode & 0o777, 0o600);
  return found[0];
}

let realGit;
for (const folder of (process.env.PATH ?? '').split(delimiter)) {
  const candidate = join(folder, process.platform === 'win32' ? 'git.exe' : 'git');
  try {
    await access(candidate, constants.X_OK);
    realGit = await realpath(candidate);
    break;
  } catch {
    /* Look for the next installed Git executable. */
  }
}
assert.ok(realGit, 'Git is required for the real repository smoke tests');

// The shim still runs the installed real Git. It injects controlled changes at
// exact command boundaries, so drift and uncertain outcomes are reproducible.
async function shim(spec, run) {
  if (process.platform === 'win32')
    throw new Error(
      'Deterministic drift shim requires POSIX; Windows is not verified by this smoke',
    );
  const bin = join(root, `shim-${++sequence}`);
  await mkdir(bin);
  const log = join(bin, 'commands.jsonl');
  const marker = join(bin, 'mutated');
  const program = `#!${process.execPath}
const fs = require('node:fs');
const cp = require('node:child_process');
const args = process.argv.slice(2);
const spec = ${JSON.stringify(spec)};
const executable = ${JSON.stringify(realGit)};
const marker = ${JSON.stringify(marker)};
fs.appendFileSync(${JSON.stringify(log)}, JSON.stringify(args) + '\\n');
const trigger = args.includes(spec.on) && !fs.existsSync(marker);
function mutate() {
  fs.writeFileSync(marker, 'done');
  if (spec.action === 'write') fs.writeFileSync(spec.path, spec.content);
  if (spec.action === 'head') cp.execFileSync(executable, ['-C', spec.repo, 'update-ref', 'HEAD', spec.head]);
  if (spec.action === 'stage') cp.execFileSync(executable, ['-C', spec.repo, 'add', '--', 'tracked.txt']);
  if (spec.action === 'reserve') { fs.mkdirSync(spec.path); fs.writeFileSync(spec.path + '/owned-by-other', 'retain'); }
}
if (trigger && spec.before) mutate();
const result = cp.spawnSync(executable, args, {cwd: process.cwd(), env: process.env, encoding: null, maxBuffer: 32 << 20});
if (result.stdout) process.stdout.write(result.stdout);
if (result.stderr) process.stderr.write(result.stderr);
if (trigger && !spec.before) mutate();
process.exit(trigger && spec.action === 'uncertain' ? 73 : (result.status ?? 74));
`;
  await writeFile(join(bin, 'git'), program, { mode: 0o700 });
  const previous = process.env.PATH;
  process.env.PATH = bin + delimiter + previous;
  try {
    await run(log);
  } finally {
    if (previous === undefined) delete process.env.PATH;
    else process.env.PATH = previous;
  }
}

try {
  await test('dirty staged + unstaged + binary + added/deleted/renamed files, ignored files, source byte-for-byte unchanged', async () => {
    const path = await repo('dirty repo with spaces');
    await writeFile(join(path, 'tracked.txt'), 'staged\n');
    await writeFile(join(path, 'binary.bin'), Buffer.from([0, 255, 0, 9, 10, 128]));
    await writeFile(join(path, 'added.txt'), 'staged added\n');
    git(path, ['add', '--', 'tracked.txt', 'binary.bin', 'added.txt']);
    await writeFile(join(path, 'tracked.txt'), 'combined final\n');
    const binary = Buffer.alloc(256 << 10, 173);
    binary[0] = 0;
    await writeFile(join(path, 'binary.bin'), binary);
    await writeFile(join(path, 'added.txt'), 'combined added final\n');
    git(path, ['mv', '--', 'nested/tracked.txt', 'nested/renamed.txt']);
    await writeFile(join(path, 'nested', 'renamed.txt'), 'renamed and dirty\n');
    git(path, ['rm', '--', 'deleted.txt']);
    await mkdir(join(path, 'loose'));
    await writeFile(join(path, 'loose', 'file.txt'), 'untracked\n');
    await writeFile(join(path, 'new\nquoted" name.txt'), 'nul-delimited names\n');
    await mkdir(join(path, 'ignored'));
    await writeFile(join(path, 'ignored', 'secret'), 'must not copy\n');
    await writeFile(join(path, 'secret.ignored'), 'must not copy either\n');
    await writeFile(join(path, 'runner'), 'run\n');
    await chmod(join(path, 'runner'), 0o755);
    const before = await sourceProof(path);
    const info = await inspectForkWorktree(path);
    assert.deepEqual(info, { available: true, reason: null, root: path, head: before.head.trim() });
    const dest = destination('dirty');
    const prepared = await prepareForkWorktree(path, dest);
    assert.deepEqual(prepared, { cwd: dest, root: dest, head: before.head.trim() });
    assert.equal(await readFile(join(dest, 'tracked.txt'), 'utf8'), 'combined final\n');
    assert.deepEqual(await readFile(join(dest, 'binary.bin')), binary);
    assert.equal(await readFile(join(dest, 'added.txt'), 'utf8'), 'combined added final\n');
    assert.equal(
      await readFile(join(dest, 'nested', 'renamed.txt'), 'utf8'),
      'renamed and dirty\n',
    );
    assert.equal(await readFile(join(dest, 'loose', 'file.txt'), 'utf8'), 'untracked\n');
    assert.equal(
      await readFile(join(dest, 'new\nquoted" name.txt'), 'utf8'),
      'nul-delimited names\n',
    );
    await absent(join(dest, 'deleted.txt'));
    await absent(join(dest, 'nested', 'tracked.txt'));
    await absent(join(dest, 'ignored'));
    await absent(join(dest, 'secret.ignored'));
    if (process.platform !== 'win32')
      assert.equal((await lstat(join(dest, 'runner'))).mode & 0o777, 0o755);
    assert.equal(
      git(dest, ['rev-parse', '--symbolic-full-name', 'HEAD']).toString().trim(),
      'HEAD',
    );
    assert.equal(
      git(dest, ['diff', '--cached', '--name-only']).length,
      0,
      'destination index stays at HEAD',
    );
    assert.deepEqual(await sourceProof(path), before);
    const saved = await receipt(dest);
    assert.equal(saved.value.phase, 'prepared');
    assert.equal(saved.value.sourceRoot, path);
    assert.equal(saved.value.head, prepared.head);
    assert.match(saved.value.fingerprint, /^[a-f0-9]{64}$/);
    await assert.rejects(prepareForkWorktree(path, dest), /already exists/);
    assert.equal((await receipt(dest)).path, saved.path, 'helper does not replay/deduplicate');
  });

  await test('preserves nested cwd including an empty ignored directory', async () => {
    const path = await repo('subdir');
    await mkdir(join(path, 'ignored', 'empty'), { recursive: true });
    const dest = destination('subdir');
    const prepared = await prepareForkWorktree(join(path, 'ignored', 'empty'), dest);
    assert.equal(prepared.cwd, join(dest, 'ignored', 'empty'));
    assert.ok((await lstat(prepared.cwd)).isDirectory());
    assert.equal(git(prepared.cwd, ['rev-parse', '--show-toplevel']).toString().trim(), dest);
  });

  await test('forks from the current linked detached worktree HEAD without altering branches', async () => {
    const path = await repo('linked');
    await writeFile(join(path, 'tracked.txt'), 'second commit\n');
    git(path, ['add', '--', 'tracked.txt']);
    git(path, ['commit', '--quiet', '-m', 'second']);
    const previous = git(path, ['rev-parse', 'HEAD^']).toString().trim();
    const source = destination('linked-source');
    git(path, ['worktree', 'add', '--detach', '--', source, previous]);
    await writeFile(join(source, 'tracked.txt'), 'dirty old HEAD\n');
    const refs = git(path, ['for-each-ref']).toString();
    const dest = destination('linked-fork');
    const prepared = await prepareForkWorktree(join(source, 'nested'), dest);
    assert.equal(prepared.head, previous);
    assert.equal(prepared.cwd, join(dest, 'nested'));
    assert.equal(await readFile(join(dest, 'tracked.txt'), 'utf8'), 'dirty old HEAD\n');
    assert.equal(git(source, ['rev-parse', 'HEAD']).toString().trim(), previous);
    assert.equal(git(path, ['for-each-ref']).toString(), refs);
  });

  await test('non-Git, nonexistent, file cwd, unborn HEAD and bare repos are unavailable with no destination', async () => {
    const ordinary = destination('ordinary');
    await mkdir(ordinary);
    const unborn = await repo('unborn', false);
    const bare = destination('bare');
    git(root, ['init', '--bare', '--', bare]);
    const file = destination('source-file');
    await writeFile(file, 'file');
    for (const cwd of [ordinary, unborn, bare, file, join(root, 'missing-source')]) {
      const info = await inspectForkWorktree(cwd);
      assert.equal(info.available, false);
      assert.ok(info.reason);
      const dest = destination('unavailable');
      await assert.rejects(prepareForkWorktree(cwd, dest));
      await absent(dest);
    }
  });

  await test('unavailable Git is reported accurately', async () => {
    const path = await repo('no-executable');
    const previous = process.env.PATH;
    process.env.PATH = join(root, 'no-git-executable');
    try {
      const info = await inspectForkWorktree(path);
      assert.equal(info.available, false);
      assert.match(info.reason, /ENOENT/);
      const dest = destination('no-git');
      await assert.rejects(prepareForkWorktree(path, dest), /ENOENT/);
      await absent(dest);
    } finally {
      process.env.PATH = previous;
    }
  });

  await test('rejects preexisting/conflicting/relative/traversing/missing-parent/source/metadata destinations', async () => {
    const path = await repo('bad-destinations');
    const existing = destination('existing');
    await mkdir(existing);
    await writeFile(join(existing, 'retain'), 'untouched');
    const file = destination('existing-file');
    await writeFile(file, 'untouched');
    const link = destination('existing-link');
    await symlink(existing, link, 'dir');
    const bad = [
      existing,
      file,
      link,
      'relative',
      `${root}/x/../normalized`,
      join(root, 'missing-parent', 'fork'),
      join(path, 'inside'),
      join(path, '.git', 'inside'),
      path,
      root,
      '/',
    ];
    for (const dest of bad) await assert.rejects(prepareForkWorktree(path, dest));
    assert.equal(await readFile(join(existing, 'retain'), 'utf8'), 'untouched');
    assert.equal(await readFile(file, 'utf8'), 'untouched');
    assert.equal(await readlink(link), existing);
    await absent(join(path, 'inside'));
    await absent(join(path, '.git', 'inside'));
  });

  await test('copies safe relative and dangling symlinks without following them', async () => {
    const path = await repo('safe-links');
    await symlink('tracked.txt', join(path, 'tracked-link'));
    git(path, ['add', '--', 'tracked-link']);
    git(path, ['commit', '--quiet', '-m', 'safe tracked link']);
    await symlink('tracked.txt', join(path, 'loose-link'));
    await symlink('../tracked.txt', join(path, 'nested', 'up-link'));
    await symlink('missing/inside.txt', join(path, 'dangling-link'));
    const before = await sourceProof(path);
    const dest = destination('safe-links');
    await prepareForkWorktree(path, dest);
    for (const part of ['tracked-link', 'loose-link', 'nested/up-link', 'dangling-link'])
      assert.equal(await readlink(join(dest, part)), await readlink(join(path, part)));
    assert.deepEqual(await sourceProof(path), before);
  });

  await test('rejects absolute/escaping/metadata/cyclic and dangling escaping symlinks before worktree creation', async () => {
    const path = await repo('unsafe-links');
    const outside = destination('outside');
    await mkdir(outside);
    await writeFile(join(outside, 'retain'), 'untouched');
    for (const target of [
      outside,
      '../outside',
      '.git/config',
      'unsafe-link',
      '../missing-outside',
    ]) {
      await symlink(target, join(path, 'unsafe-link'));
      const dest = destination('unsafe-link');
      await assert.rejects(prepareForkWorktree(path, dest), /Unsafe|symlink|ELOOP/);
      await absent(dest);
      await rm(join(path, 'unsafe-link'));
    }
    await symlink('../missing-outside', join(path, 'escape-parent'));
    await symlink('escape-parent/child', join(path, 'unsafe-link'));
    const dest = destination('unsafe-chain');
    await assert.rejects(prepareForkWorktree(path, dest), /symlink/);
    await absent(dest);
    assert.equal(await readFile(join(outside, 'retain'), 'utf8'), 'untouched');
  });

  await test('rejects traversal-like Git filenames and a replaced tracked parent symlink', async () => {
    const path = await repo('unsafe-paths');
    if (process.platform !== 'win32') {
      for (const name of ['..\\escape', 'C:escape', '.git ']) {
        await writeFile(join(path, name), 'unsafe');
        const dest = destination('unsafe-path');
        await assert.rejects(prepareForkWorktree(path, dest), /Unsafe/);
        await absent(dest);
        await rm(join(path, name));
      }
    }
    await rm(join(path, 'nested'), { recursive: true });
    await symlink(root, join(path, 'nested'), 'dir');
    const dest = destination('unsafe-parent');
    await assert.rejects(prepareForkWorktree(path, dest), /symlink/);
    await absent(dest);
  });

  await test('source staged deletion followed by an untracked recreation and directory replacement is reproduced', async () => {
    const path = await repo('recreation');
    git(path, ['rm', '--', 'tracked.txt']);
    await writeFile(join(path, 'tracked.txt'), 'untracked recreation\n');
    await rm(join(path, 'deleted.txt'));
    await mkdir(join(path, 'deleted.txt'));
    await writeFile(join(path, 'deleted.txt', 'child'), 'directory replacement\n');
    await rm(join(path, 'nested'), { recursive: true });
    const before = await sourceProof(path);
    const dest = destination('recreation');
    await prepareForkWorktree(path, dest);
    assert.equal(await readFile(join(dest, 'tracked.txt'), 'utf8'), 'untracked recreation\n');
    assert.equal(
      await readFile(join(dest, 'deleted.txt', 'child'), 'utf8'),
      'directory replacement\n',
    );
    await absent(join(dest, 'nested', 'tracked.txt'));
    assert.deepEqual(await sourceProof(path), before);
  });

  await test('rejects unmerged, sparse/assume-unchanged and submodule index state', async () => {
    const path = await repo('unsupported-state');
    git(path, ['checkout', '--quiet', '-b', 'other']);
    await writeFile(join(path, 'tracked.txt'), 'other\n');
    git(path, ['commit', '--quiet', '-am', 'other']);
    git(path, ['checkout', '--quiet', 'main']);
    await writeFile(join(path, 'tracked.txt'), 'main\n');
    git(path, ['commit', '--quiet', '-am', 'main']);
    assert.throws(() => git(path, ['merge', '--no-edit', 'other']));
    assert.equal((await inspectForkWorktree(path)).available, false);
    await assert.rejects(prepareForkWorktree(path, destination('conflict')), /conflict/);
    git(path, ['merge', '--abort']);
    git(path, ['update-index', '--assume-unchanged', '--', 'tracked.txt']);
    await assert.rejects(prepareForkWorktree(path, destination('assumed')), /assume-unchanged/);
    git(path, ['update-index', '--no-assume-unchanged', '--', 'tracked.txt']);
    git(path, ['update-index', '--skip-worktree', '--', 'tracked.txt']);
    await assert.rejects(prepareForkWorktree(path, destination('sparse')), /Sparse/);
    git(path, ['update-index', '--no-skip-worktree', '--', 'tracked.txt']);
    const head = git(path, ['rev-parse', 'HEAD']).toString().trim();
    git(path, ['update-index', '--add', '--cacheinfo', `160000,${head},submodule`]);
    assert.equal((await inspectForkWorktree(path)).available, false);
    await assert.rejects(prepareForkWorktree(path, destination('submodule')), /Submodule/);
  });

  await test('bounds file capture before creating a destination', async () => {
    const path = await repo('bounded');
    const file = await open(join(path, 'too-large.bin'), 'wx');
    await file.truncate((128 << 20) + 1);
    await file.close();
    const dest = destination('bounded');
    await assert.rejects(prepareForkWorktree(path, dest), /size limit/);
    await absent(dest);
  });

  await test('preserves raw CRLF bytes and respects configured excludes', async () => {
    const path = await repo('eol-excludes');
    await writeFile(join(path, '.gitattributes'), '*.txt text eol=crlf\n');
    await writeFile(join(path, 'tracked.txt'), 'first\r\nsecond\r\n');
    git(path, ['add', '--', '.gitattributes', 'tracked.txt']);
    git(path, ['commit', '--quiet', '-m', 'crlf attributes']);
    await writeFile(join(path, 'tracked.txt'), 'raw changed\r\nbytes\r\n');
    const excludes = join(root, `excludes-${++sequence}`);
    await writeFile(excludes, 'private.local\n');
    git(path, ['config', 'core.excludesFile', excludes]);
    await writeFile(join(path, 'private.local'), 'ignored via configured excludes\n');
    const before = await sourceProof(path);
    const dest = destination('eol');
    await prepareForkWorktree(path, dest);
    assert.deepEqual(
      await readFile(join(dest, 'tracked.txt')),
      await readFile(join(path, 'tracked.txt')),
    );
    await absent(join(dest, 'private.local'));
    assert.deepEqual(await sourceProof(path), before);
  });

  await test('does not run Git hooks, clean/smudge filters, fsmonitor, external diff or textconv', async () => {
    const path = await repo('config-surprises');
    const marker = join(root, `unexpected-execution-${++sequence}`);
    const executable = join(root, `unexpected-command-${sequence}`);
    await writeFile(
      executable,
      `#!${process.execPath}\nrequire('node:fs').writeFileSync(${JSON.stringify(marker)}, 'ran');\nprocess.exit(91);\n`,
      { mode: 0o700 },
    );
    await writeFile(join(path, '.gitattributes'), 'tracked.txt filter=fixture diff=fixture\n');
    git(path, ['add', '--', '.gitattributes']);
    git(path, ['commit', '--quiet', '-m', 'attributes']);
    await writeFile(join(path, 'tracked.txt'), 'dirty\n');
    const hooks = join(root, `hooks-${sequence}`);
    await mkdir(hooks);
    await copyExecutable(executable, join(hooks, 'post-checkout'));
    for (const [key, value] of [
      ['core.hooksPath', hooks],
      ['core.fsmonitor', executable],
      ['filter.fixture.clean', executable],
      ['filter.fixture.smudge', executable],
      ['filter.fixture.process', executable],
      ['filter.fixture.required', 'true'],
      ['diff.external', executable],
      ['diff.fixture.command', executable],
      ['diff.fixture.textconv', executable],
    ])
      git(path, ['config', key, value]);
    const dest = destination('safe-config');
    await prepareForkWorktree(path, dest);
    await absent(marker);
    assert.equal(await readFile(join(dest, 'tracked.txt'), 'utf8'), 'dirty\n');
  });

  await test('ignores inherited GIT_DIR/WORK_TREE/INDEX_FILE/CONFIG injection', async () => {
    const path = await repo('environment');
    const other = await repo('wrong-environment');
    const changes = {
      GIT_DIR: join(other, '.git'),
      GIT_WORK_TREE: other,
      GIT_INDEX_FILE: join(root, 'wrong-index'),
      GIT_CONFIG_COUNT: '1',
      GIT_CONFIG_KEY_0: 'core.bare',
      GIT_CONFIG_VALUE_0: 'true',
    };
    const previous = Object.fromEntries(Object.keys(changes).map((key) => [key, process.env[key]]));
    Object.assign(process.env, changes);
    try {
      const info = await inspectForkWorktree(path);
      assert.equal(info.root, path);
      const dest = destination('environment');
      await prepareForkWorktree(path, dest);
      assert.equal(await readFile(join(dest, 'tracked.txt'), 'utf8'), 'base\n');
      await absent(join(root, 'wrong-index'));
    } finally {
      for (const [key, value] of Object.entries(previous)) {
        if (value === undefined) delete process.env[key];
        else process.env[key] = value;
      }
    }
  });

  await test('explicit source core.worktree cannot redirect writes out of the destination', async () => {
    const path = await repo('core-worktree');
    git(path, ['config', 'core.worktree', path]);
    await writeFile(join(path, 'tracked.txt'), 'source must stay dirty\n');
    const before = await sourceProof(path);
    const dest = destination('core-worktree');
    await prepareForkWorktree(path, dest);
    assert.equal(await readFile(join(dest, 'tracked.txt'), 'utf8'), 'source must stay dirty\n');
    assert.deepEqual(await sourceProof(path), before);
  });

  await test('fails before worktree creation for content, staging or HEAD drift during capture', async () => {
    for (const action of ['write', 'stage', 'head']) {
      const path = await repo(`capture-drift-${action}`);
      await writeFile(join(path, 'tracked.txt'), 'original dirty\n');
      const spec = {
        on: 'diff',
        action,
        repo: path,
        path: join(path, 'tracked.txt'),
        content: 'changed during capture\n',
      };
      if (action === 'head')
        spec.head = git(path, ['commit-tree', '-m', 'other head', 'HEAD^{tree}', '-p', 'HEAD'])
          .toString()
          .trim();
      const dest = destination('capture-drift');
      const worktrees = git(path, ['worktree', 'list', '--porcelain']).toString();
      await shim(spec, async (log) => {
        await assert.rejects(prepareForkWorktree(path, dest), /changed/);
        await absent(dest);
        const commands = (await readFile(log, 'utf8')).trim().split('\n').map(JSON.parse);
        assert.equal(
          commands.some((args) => args.includes('worktree') && args.includes('add')),
          false,
        );
      });
      if (action !== 'head')
        assert.equal(git(path, ['worktree', 'list', '--porcelain']).toString(), worktrees);
    }
  });

  await test('source drift during materialization retains the identifiable failed worktree and receipt', async () => {
    const path = await repo('materialization-drift');
    await writeFile(join(path, 'tracked.txt'), 'captured dirty\n');
    const dest = destination('materialization-drift');
    let failure;
    await shim(
      {
        on: 'read-tree',
        action: 'write',
        path: join(path, 'tracked.txt'),
        content: 'late source change\n',
      },
      async () => {
        try {
          await prepareForkWorktree(path, dest);
        } catch (error) {
          failure = error;
        }
      },
    );
    assert.ok(failure);
    assert.match(failure.message, /changed/);
    assert.equal(failure.destination, dest);
    const saved = await receipt(dest);
    assert.equal(failure.recordPath, saved.path);
    assert.equal(saved.value.phase, 'failed');
    assert.equal(await readFile(join(dest, 'tracked.txt'), 'utf8'), 'captured dirty\n');
    assert.equal(await readFile(join(path, 'tracked.txt'), 'utf8'), 'late source change\n');
    await assert.rejects(prepareForkWorktree(path, dest), /already exists/);
    assert.equal((await receipt(dest)).value.fingerprint, saved.value.fingerprint);
  });

  await test('unknown worktree-add result is retained without retry or cleanup', async () => {
    const path = await repo('unknown-add');
    const dest = destination('unknown-add');
    const before = await sourceProof(path);
    await shim({ on: 'worktree', action: 'uncertain' }, async (log) => {
      await assert.rejects(prepareForkWorktree(path, dest), /failed \(73\)/);
      const commands = (await readFile(log, 'utf8')).trim().split('\n').map(JSON.parse);
      assert.equal(
        commands.filter((args) => args.includes('worktree') && args.includes('add')).length,
        1,
      );
      assert.equal(
        commands.some(
          (args) =>
            args.includes('remove') ||
            args.includes('prune') ||
            args.includes('stash') ||
            args.includes('fetch') ||
            args.includes('checkout'),
        ),
        false,
      );
    });
    assert.ok((await lstat(join(dest, '.git'))).isFile());
    assert.equal((await receipt(dest)).value.phase, 'outcome-unknown');
    assert.deepEqual(await sourceProof(path), before);
    await assert.rejects(prepareForkWorktree(path, dest), /already exists/);
  });

  await test('destination reservation race leaves the competing directory unchanged', async () => {
    const path = await repo('reservation-race');
    const dest = destination('reservation-race');
    await shim({ on: 'diff', action: 'reserve', path: dest }, async () => {
      await assert.rejects(prepareForkWorktree(path, dest), /already exists/);
    });
    assert.equal(await readFile(join(dest, 'owned-by-other'), 'utf8'), 'retain');
    await absent(join(dest, '.git'));
  });
  console.log(
    JSON.stringify({
      status: 'passed',
      passed,
      scope: 'real-temporary-git',
      platform: process.platform,
      node: process.version,
    }),
  );
} finally {
  // All fixture artifacts (including intentionally retained failed worktrees)
  // are ours, isolated under this one test root. Production helper never removes them.
  await rm(root, { recursive: true, force: true });
}

async function copyExecutable(source, destination) {
  await writeFile(destination, await readFile(source), { mode: 0o700 });
}
