import { withWindowsProcessCreation } from './windows-process.js';
import { spawn } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { constants, type Stats } from 'node:fs';
import {
  chmod,
  copyFile,
  lstat,
  mkdir,
  mkdtemp,
  open,
  readlink,
  realpath,
  rename,
  rm,
  symlink,
  unlink,
  type FileHandle,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, isAbsolute, join, relative, resolve, sep, win32 } from 'node:path';

// Deliberately local Git only: no fetch, branch writes, stash, source index refresh,
// hooks, filters, external diff/textconv, fsmonitor or user-supplied commands.
const limits = {
  metadata: 16 << 20,
  patch: 256 << 20,
  file: 128 << 20,
  total: 512 << 20,
  files: 50_000,
};
const digest = (value: Buffer | string) => createHash('sha256').update(value).digest('hex');
const errorText = (error: unknown) => (error instanceof Error ? error.message : String(error));
const missing = (error: unknown) => (error as NodeJS.ErrnoException).code === 'ENOENT';
const inside = (root: string, path: string) => {
  const part = relative(root, path);
  return part === '' || (!isAbsolute(part) && part !== '..' && !part.startsWith('..' + sep));
};

interface GitContext {
  scratch: string;
  options: string[];
  env: NodeJS.ProcessEnv;
}
interface Source {
  cwd: string;
  root: string;
  head: string;
  common: string;
  device: number;
  inode: number;
}
interface Entry {
  path: string;
  kind: 'absent' | 'directory' | 'file' | 'symlink';
  mode: number;
  hash: string;
  target?: string;
  capture?: string;
}
interface Snapshot {
  head: string;
  fingerprint: string;
  patch: string;
  tracked: Entry[];
  untracked: Entry[];
  headPaths: string[];
}

async function context(): Promise<GitContext> {
  const scratch = await mkdtemp(join(tmpdir(), 'codex-fork-capture-'));
  await chmod(scratch, 0o700);
  await mkdir(join(scratch, 'hooks'), { mode: 0o700 });
  // Do not inherit GIT_DIR/WORK_TREE/INDEX_FILE/CONFIG_COUNT/etc. Global ignores
  // still participate in --exclude-standard; dangerous config is overridden.
  const env = Object.fromEntries(
    Object.entries(process.env).filter(([key]) => !/^GIT_/i.test(key)),
  );
  Object.assign(env, { GIT_OPTIONAL_LOCKS: '0', GIT_TERMINAL_PROMPT: '0', LC_ALL: 'C' });
  const settings = [
    `core.hooksPath=${join(scratch, 'hooks')}`,
    'core.fsmonitor=false',
    'core.untrackedCache=false',
    'core.autocrlf=false',
    'core.safecrlf=false',
    'core.symlinks=true',
    'core.sparseCheckout=false',
    'core.sparseCheckoutCone=false',
    'core.protectHFS=true',
    'core.protectNTFS=true',
    'maintenance.auto=false',
    'gc.auto=0',
    'color.ui=false',
    'diff.external=',
    'diff.ignoreSubmodules=none',
    'apply.ignoreWhitespace=no',
  ];
  return { scratch, env, options: ['--no-optional-locks', ...settings.flatMap((v) => ['-c', v])] };
}

// Output is either bounded in memory or streamed to an exclusive private file.
async function git(
  ctx: GitContext,
  cwd: string,
  args: string[],
  output?: string,
  accepted = [0],
): Promise<Buffer> {
  const file = output ? await open(output, 'wx', 0o600) : undefined;
  try {
    let processTimeout = 60_000;
    const child = withWindowsProcessCreation(60000, (remaining) => {
      processTimeout = remaining;
      return spawn('git', [...ctx.options, ...args], {
        cwd,
        env: ctx.env,
        shell: false,
        windowsHide: true,
        stdio: ['ignore', 'pipe', 'pipe'],
      });
    });
    let problem: Error | undefined;
    const stop = (error: Error) => {
      problem ??= error;
      child.kill('SIGKILL');
    };
    const closed = new Promise<number | null>((done) => {
      child.once('error', (error) => stop(error));
      child.once('close', (code) => done(code));
    });
    const timer = setTimeout(
      () => stop(new Error('Git snapshot command timed out')),
      processTimeout,
    );
    const chunks: Buffer[] = [];
    const hash = createHash('sha256');
    let stderr = '';
    try {
      const drain = async () => {
        let bytes = 0;
        for await (const chunk of child.stdout) {
          bytes += chunk.length;
          if (bytes > (output ? limits.patch : limits.metadata)) {
            stop(new Error('Git snapshot output exceeds the size limit'));
            continue;
          }
          if (problem) continue;
          if (file) {
            hash.update(chunk);
            await writeAll(file, chunk);
          } else chunks.push(chunk);
        }
      };
      const errors = async () => {
        let bytes = 0;
        for await (const chunk of child.stderr) {
          bytes += chunk.length;
          if (bytes > 64 << 10) stop(new Error('Git diagnostic output exceeds the size limit'));
          else stderr += chunk.toString('utf8');
        }
      };
      const guarded = (run: () => Promise<void>) =>
        run().catch((error) => stop(new Error(errorText(error))));
      const drained = await Promise.allSettled([guarded(drain), guarded(errors)]);
      for (const result of drained)
        if (result.status === 'rejected') stop(new Error(errorText(result.reason)));
      const code = await closed;
      if (problem) throw problem;
      if (code === null || !accepted.includes(code))
        throw new Error(`Git ${args[0]} failed (${code}): ${stderr.trim()}`);
      return output ? Buffer.from(hash.digest('hex')) : Buffer.concat(chunks);
    } finally {
      clearTimeout(timer);
    }
  } finally {
    await file?.close();
  }
}

async function writeAll(file: FileHandle, data: Buffer): Promise<void> {
  let offset = 0;
  while (offset < data.length) {
    const { bytesWritten } = await file.write(data, offset, data.length - offset);
    if (!bytesWritten) throw new Error('Snapshot file write made no progress');
    offset += bytesWritten;
  }
}

function utf8(value: Buffer): string {
  const text = value.toString('utf8');
  if (!Buffer.from(text).equals(value)) throw new Error('Non-UTF-8 Git paths are not supported');
  return text;
}
function paths(value: Buffer): string[] {
  if (value.length === 0) return [];
  if (value[value.length - 1] !== 0) throw new Error('Incomplete Git path listing');
  const result = utf8(value).slice(0, -1).split('\0');
  if (result.length > limits.files) throw new Error('Git snapshot has too many paths');
  return result;
}
function safePath(path: string): void {
  if (
    path.length === 0 ||
    path.length > 4096 ||
    isAbsolute(path) ||
    win32.isAbsolute(path) ||
    /[\\:\0]/.test(path) ||
    path
      .split('/')
      .some((part) => !part || part === '.' || part === '..' || /^\.git[ .]*$/i.test(part))
  )
    throw new Error('Unsafe Git snapshot path');
}
async function parents(root: string, path: string, create = false): Promise<void> {
  safePath(path);
  const base = await lstat(root);
  if (!base.isDirectory() || base.isSymbolicLink())
    throw new Error('Snapshot root is no longer a real directory');
  let current = root;
  for (const part of path.split('/').slice(0, -1)) {
    current = join(current, part);
    if (create) {
      try {
        await mkdir(current, { mode: 0o700 });
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== 'EEXIST') throw error;
      }
    }
    const stat = await lstat(current);
    if (!stat.isDirectory() || stat.isSymbolicLink())
      throw new Error('Snapshot path crosses a symlink or non-directory');
  }
}
async function safeLink(root: string, path: string, target: string): Promise<void> {
  if (!target || isAbsolute(target) || win32.isAbsolute(target) || /[\\:\0]/.test(target))
    throw new Error('Unsafe snapshot symlink target');
  const resolved = resolve(dirname(join(root, path)), target);
  if (!inside(root, resolved)) throw new Error('Snapshot symlink escapes the repository');
  const part = relative(root, resolved).split(sep).join('/');
  if (part) safePath(part);
  // A dangling relative link is safe only when its nearest existing ancestor
  // resolves inside the repository. Existing chains and cycles are checked too.
  let ancestor = resolved;
  for (;;) {
    try {
      if (!inside(root, await realpath(ancestor)))
        throw new Error('Snapshot symlink escapes the repository');
      return;
    } catch (error) {
      if (!missing(error)) throw error;
      if (ancestor === root) throw error;
      // realpath reports ENOENT for a dangling *escaping* ancestor symlink too.
      try {
        const stat = await lstat(ancestor);
        if (stat.isSymbolicLink())
          throw new Error('Cannot verify dangling snapshot symlink chain', { cause: error });
      } catch (error) {
        if (!missing(error)) throw error;
      }
      ancestor = dirname(ancestor);
    }
  }
}
function stable(before: Stats, after: Stats): boolean {
  return (
    before.dev === after.dev &&
    before.ino === after.ino &&
    before.size === after.size &&
    before.mode === after.mode &&
    before.mtimeMs === after.mtimeMs &&
    before.ctimeMs === after.ctimeMs
  );
}
async function entry(
  root: string,
  path: string,
  budget: { bytes: number },
  capture?: string,
  allowMissing = false,
): Promise<Entry> {
  try {
    await parents(root, path);
  } catch (error) {
    if (allowMissing && missing(error)) return { path, kind: 'absent', mode: 0, hash: '' };
    throw error;
  }
  const full = join(root, path);
  let stat: Stats;
  try {
    stat = await lstat(full);
  } catch (error) {
    if (allowMissing && missing(error)) return { path, kind: 'absent', mode: 0, hash: '' };
    throw error;
  }
  const mode = stat.mode & 0o777;
  if (stat.isSymbolicLink()) {
    const target = utf8(await readlink(full, { encoding: 'buffer' }));
    await safeLink(root, path, target);
    if (!stable(stat, await lstat(full)))
      throw new Error('Source changed while capturing a symlink');
    return { path, kind: 'symlink', mode, hash: digest(target), target };
  }
  if (stat.isDirectory() && allowMissing) return { path, kind: 'directory', mode, hash: '' };
  if (!stat.isFile()) throw new Error('Snapshot contains a non-regular file');
  if (stat.size > limits.file || budget.bytes + stat.size > limits.total)
    throw new Error('Git snapshot file data exceeds the size limit');
  const input = await open(full, constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0));
  let output: FileHandle | undefined;
  try {
    output = capture ? await open(capture, 'wx', 0o600) : undefined;
    if (!stable(stat, await input.stat())) throw new Error('Source changed before file capture');
    const hash = createHash('sha256');
    const buffer = Buffer.allocUnsafe(128 << 10);
    let bytes = 0;
    for (;;) {
      const { bytesRead } = await input.read(buffer, 0, buffer.length, null);
      if (!bytesRead) break;
      bytes += bytesRead;
      budget.bytes += bytesRead;
      if (bytes > limits.file || budget.bytes > limits.total)
        throw new Error('Git snapshot file data exceeds the size limit');
      hash.update(buffer.subarray(0, bytesRead));
      if (output) await writeAll(output, buffer.subarray(0, bytesRead));
    }
    if (
      bytes !== stat.size ||
      !stable(stat, await input.stat()) ||
      !stable(stat, await lstat(full))
    )
      throw new Error('Source changed while capturing a file');
    return { path, kind: 'file', mode, hash: hash.digest('hex'), ...(capture ? { capture } : {}) };
  } finally {
    await input.close();
    await output?.close();
  }
}
function records(value: Buffer, tree: boolean): string[] {
  return paths(value).map((line) => {
    const tab = line.indexOf('\t');
    if (tab < 0) throw new Error('Invalid Git index/tree listing');
    const header = line.slice(0, tab).split(' ');
    if (header[0] === '160000') throw new Error('Submodule snapshots are not supported');
    if (!tree && header[2] !== '0') throw new Error('Resolve index conflicts before forking');
    const path = line.slice(tab + 1);
    safePath(path);
    return path;
  });
}
async function disableFilters(ctx: GitContext, cwd: string): Promise<void> {
  const config = await git(
    ctx,
    cwd,
    ['config', '--null', '--get-regexp', '^filter\\.'],
    undefined,
    [0, 1],
  );
  const names = new Set<string>();
  for (const item of paths(config)) {
    const key = item.slice(0, item.indexOf('\n'));
    const match = /^filter\.(.+)\.(clean|smudge|process|required)$/.exec(key);
    if (match?.[1]) names.add(match[1]);
    else if (/^filter\./.test(key) && /[\r\n]/.test(key))
      throw new Error('Unsafe Git filter configuration');
  }
  for (const name of names)
    for (const key of ['clean', 'smudge', 'process', 'required'])
      ctx.options.push('-c', `filter.${name}.${key}=${key === 'required' ? 'false' : ''}`);
}
async function source(ctx: GitContext, cwd: string): Promise<Source> {
  if (!cwd || cwd.includes('\0')) throw new Error('A valid source directory is required');
  const canonical = await realpath(cwd);
  if (!(await lstat(canonical)).isDirectory()) throw new Error('Source is not a directory');
  if (
    (await git(ctx, canonical, ['rev-parse', '--is-inside-work-tree'])).toString().trim() !== 'true'
  )
    throw new Error('Source is not a Git working tree');
  const root = await realpath(
    (await git(ctx, canonical, ['rev-parse', '--show-toplevel'])).toString().trimEnd(),
  );
  if (!inside(root, canonical)) throw new Error('Source directory is outside the repository');
  const head = (await git(ctx, root, ['rev-parse', '--verify', 'HEAD^{commit}'])).toString().trim();
  if (!/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(head))
    throw new Error('Source has no committed HEAD');
  const commonPath = (
    await git(ctx, root, ['rev-parse', '--path-format=absolute', '--git-common-dir'])
  )
    .toString()
    .trimEnd();
  const common = await realpath(commonPath);
  await disableFilters(ctx, root);
  const identity = await lstat(root);
  return { cwd: canonical, root, head, common, device: identity.dev, inode: identity.ino };
}
async function assertSourceRoot(src: Source): Promise<void> {
  const current = await lstat(src.root);
  if (
    !current.isDirectory() ||
    current.isSymbolicLink() ||
    current.dev !== src.device ||
    current.ino !== src.inode
  )
    throw new Error('Source root changed during snapshot');
}
async function snapshot(ctx: GitContext, src: Source, capture: boolean): Promise<Snapshot> {
  await assertSourceRoot(src);
  const directory = await mkdtemp(join(ctx.scratch, 'state-'));
  const head = (await git(ctx, src.root, ['rev-parse', '--verify', 'HEAD^{commit}']))
    .toString()
    .trim();
  if (head !== src.head) throw new Error('Source HEAD changed before capture');
  const branch = await git(ctx, src.root, ['rev-parse', '--symbolic-full-name', 'HEAD']);
  const index = await git(ctx, src.root, ['ls-files', '--stage', '-z']);
  const indexed = records(index, false);
  const flags = await git(ctx, src.root, ['ls-files', '-v', '-z']);
  for (const flag of paths(flags))
    if (!flag.startsWith('H '))
      throw new Error('Sparse/assume-unchanged index entries are not supported');
  const headPaths = records(await git(ctx, src.root, ['ls-tree', '-r', '-z', head]), true);
  const others = paths(
    await git(ctx, src.root, ['ls-files', '--others', '--exclude-standard', '-z']),
  );
  for (const path of others) safePath(path);
  if (indexed.length + others.length > limits.files)
    throw new Error('Git snapshot has too many files');
  const budget = { bytes: 0 };
  const tracked: Entry[] = [];
  const untracked: Entry[] = [];
  for (const path of indexed)
    tracked.push(
      await entry(
        src.root,
        path,
        budget,
        capture ? join(directory, String(tracked.length)) : undefined,
        true,
      ),
    );
  for (const path of others)
    untracked.push(
      await entry(
        src.root,
        path,
        budget,
        capture ? join(directory, 'u' + untracked.length) : undefined,
      ),
    );
  const status = await git(ctx, src.root, [
    'status',
    '--porcelain=v1',
    '-z',
    '--untracked-files=all',
  ]);
  const patch = join(directory, 'tracked.patch');
  const patchHash = await git(
    ctx,
    src.root,
    [
      'diff',
      '--binary',
      '--full-index',
      '--no-ext-diff',
      '--no-textconv',
      '--no-renames',
      '--src-prefix=a/',
      '--dst-prefix=b/',
      head,
      '--',
    ],
    patch,
  );
  const end = (await git(ctx, src.root, ['rev-parse', '--verify', 'HEAD^{commit}']))
    .toString()
    .trim();
  if (end !== head) throw new Error('Source HEAD changed during capture');
  await assertSourceRoot(src);
  const fingerprint = digest(
    JSON.stringify({
      head,
      branch: digest(branch),
      index: digest(index),
      flags: digest(flags),
      status: digest(status),
      patch: patchHash.toString(),
      tracked: tracked.map(({ capture: _capture, ...value }) => value),
      untracked: untracked.map(({ capture: _capture, ...value }) => value),
    }),
  );
  return { head, fingerprint, patch, tracked, untracked, headPaths };
}
async function assertSnapshot(ctx: GitContext, src: Source, expected: Snapshot): Promise<void> {
  const actual = await snapshot(ctx, src, false);
  if (actual.fingerprint !== expected.fingerprint)
    throw new Error('Source working state changed; fork must not proceed');
}

/** Availability only; prepare always takes a fresh snapshot and may still fail. */
export async function inspectForkWorktree(cwd: string): Promise<{
  available: boolean;
  reason: string | null;
  root?: string;
  head?: string;
}> {
  let ctx: GitContext | undefined;
  try {
    ctx = await context();
    const src = await source(ctx, cwd);
    records(await git(ctx, src.root, ['ls-files', '--stage', '-z']), false);
    for (const flag of paths(await git(ctx, src.root, ['ls-files', '-v', '-z'])))
      if (!flag.startsWith('H '))
        throw new Error('Sparse/assume-unchanged index entries are not supported');
    return { available: true, reason: null, root: src.root, head: src.head };
  } catch (error) {
    return { available: false, reason: errorText(error) };
  } finally {
    if (ctx) await rm(ctx.scratch, { recursive: true, force: true });
  }
}

async function destinationPath(src: Source, destination: string): Promise<string> {
  if (!destination || !isAbsolute(destination) || destination.includes('\0'))
    throw new Error('Destination must be an absolute path with an existing parent');
  const path = resolve(destination);
  if (path !== destination || path === dirname(path))
    throw new Error('Destination path must be normalized');
  const parent = await realpath(dirname(path));
  const canonical = join(parent, relative(dirname(path), path));
  if (inside(src.root, canonical) || inside(src.common, canonical))
    throw new Error('Destination must be outside the source repository and Git metadata');
  try {
    await lstat(canonical);
  } catch (error) {
    if (missing(error)) return canonical;
    throw error;
  }
  throw new Error(
    'Destination already exists; inspect the original operation instead of replaying',
  );
}
async function restore(root: string, value: Entry): Promise<void> {
  if (value.kind === 'absent') return;
  await parents(root, value.path, true);
  const path = join(root, value.path);
  if (value.kind === 'directory') {
    try {
      await mkdir(path, { mode: 0o700 });
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'EEXIST') throw error;
    }
    if (!(await lstat(path)).isDirectory())
      throw new Error('Destination directory conflicts with a file');
    return;
  }
  try {
    const current = await lstat(path);
    if (!current.isFile() && !current.isSymbolicLink())
      throw new Error('Destination file conflicts with a directory or special file');
    await unlink(path);
  } catch (error) {
    if (!missing(error)) throw error;
  }
  if (value.kind === 'symlink') {
    await safeLink(root, value.path, value.target!);
    await symlink(value.target!, path);
  } else {
    if (!value.capture) throw new Error('Missing captured file data');
    await copyFile(value.capture, path, constants.COPYFILE_EXCL);
    await chmod(path, value.mode);
  }
}
async function verifyDestination(
  ctx: GitContext,
  src: Source,
  dest: string,
  state: Snapshot,
): Promise<void> {
  const head = (await git(ctx, dest, ['rev-parse', '--verify', 'HEAD^{commit}'])).toString().trim();
  const ref = (await git(ctx, dest, ['rev-parse', '--symbolic-full-name', 'HEAD']))
    .toString()
    .trim();
  if (head !== src.head || ref !== 'HEAD')
    throw new Error('Destination is not detached at the captured HEAD');
  const expected = new Map(
    [...state.tracked, ...state.untracked].map((value) => [value.path, value]),
  );
  for (const path of state.headPaths)
    if (!expected.has(path)) expected.set(path, { path, kind: 'absent', mode: 0, hash: '' });
  const budget = { bytes: 0 };
  for (const value of expected.values()) {
    const actual = await entry(dest, value.path, budget, undefined, true);
    if (
      actual.kind !== value.kind ||
      actual.hash !== value.hash ||
      (actual.kind === 'file' && actual.mode !== value.mode)
    )
      throw new Error('Destination does not match the captured working state');
  }
}

/**
 * Creates a detached worktree at the source HEAD. The returned root is the new
 * worktree root; cwd preserves the source's nested directory. Combined tracked
 * changes are applied without retaining their original staging partition.
 *
 * This is a bounded, optimistic snapshot, not a source lock or an idempotency
 * service. The caller must persist its operation intent and only fork the chat
 * after success. Once reserved, destination and its private sibling receipt are
 * retained on failure/unknown outcome. Errors expose destination and recordPath;
 * never automatically replay or remove those artifacts.
 */
export async function prepareForkWorktree(
  cwd: string,
  destination: string,
): Promise<{
  cwd: string;
  root: string;
  head: string;
}> {
  const ctx = await context();
  let recordPath: string | undefined;
  let dest: string | undefined;
  let record: Record<string, unknown> | undefined;
  let recordOwned = false;
  const save = async (phase: string, failure?: string) => {
    if (!recordPath || !record || !recordOwned) return;
    record.phase = phase;
    if (failure) record.error = failure;
    const temporary = recordPath + '.' + randomUUID() + '.tmp';
    const file = await open(temporary, 'wx', 0o600);
    try {
      await writeAll(file, Buffer.from(JSON.stringify(record) + '\n'));
      await file.sync();
    } finally {
      await file.close();
    }
    await rename(temporary, recordPath);
    if (process.platform !== 'win32') {
      const parent = await open(dirname(recordPath), 'r');
      try {
        await parent.sync();
      } finally {
        await parent.close();
      }
    }
  };
  const assertDestination = async () => {
    const current = await lstat(dest!);
    if (
      !current.isDirectory() ||
      current.isSymbolicLink() ||
      current.dev !== record?.destinationDevice ||
      current.ino !== record?.destinationInode
    )
      throw new Error('Reserved destination changed; inspect its original receipt');
  };
  try {
    const src = await source(ctx, cwd);
    dest = await destinationPath(src, destination);
    const state = await snapshot(ctx, src, true);
    await assertSnapshot(ctx, src, state);
    // Recheck and reserve atomically: Git may accept an existing *empty* folder,
    // but this helper never accepts a caller's preexisting destination.
    dest = await destinationPath(src, dest);
    const operationId = randomUUID();
    recordPath = join(dirname(dest), '.codex-fork-' + operationId + '.json');
    record = {
      version: 1,
      operationId,
      sourceRoot: src.root,
      sourceCwd: src.cwd,
      destination: dest,
      head: src.head,
      fingerprint: state.fingerprint,
      ownsDestination: false,
      phase: 'reserving',
    };
    const initial = await open(recordPath, 'wx', 0o600);
    recordOwned = true;
    try {
      await writeAll(initial, Buffer.from(JSON.stringify(record) + '\n'));
      await initial.sync();
    } finally {
      await initial.close();
    }
    await mkdir(dest, { mode: 0o700 });
    const identity = await lstat(dest);
    Object.assign(record, {
      ownsDestination: true,
      destinationDevice: identity.dev,
      destinationInode: identity.ino,
    });
    await save('reserved');
    await assertSnapshot(ctx, src, state);
    await assertDestination();
    await save('adding-worktree');
    await git(ctx, src.root, [
      'worktree',
      'add',
      '--detach',
      '--no-checkout',
      '--',
      dest,
      src.head,
    ]);
    await assertDestination();
    const gitDirectory = (await git(ctx, dest, ['rev-parse', '--absolute-git-dir']))
      .toString()
      .trimEnd();
    const administrationRoot = join(src.common, 'worktrees');
    const canonicalGitDirectory = await realpath(gitDirectory);
    if (
      !inside(administrationRoot, canonicalGitDirectory) ||
      canonicalGitDirectory === administrationRoot
    )
      throw new Error('New worktree administration directory is outside the source registry');
    record.gitDirectory = canonicalGitDirectory;
    // Explicit destination paths keep core.worktree/core.bare and worktree-local
    // config from redirecting index/checkout writes back into the source.
    const destinationContext: GitContext = {
      ...ctx,
      options: [
        ...ctx.options,
        `--git-dir=${canonicalGitDirectory}`,
        `--work-tree=${dest}`,
        '-c',
        'core.bare=false',
      ],
    };
    await save('materializing');
    await git(destinationContext, dest, ['read-tree', '--reset', '-u', src.head]);
    if ((await lstat(state.patch)).size > 0)
      await git(destinationContext, dest, [
        'apply',
        '--binary',
        '--whitespace=nowarn',
        '--',
        state.patch,
      ]);
    // Restore captured raw bytes as well, so EOL/attributes cannot silently
    // change dirty binary/text content. The source is never opened for writing.
    for (const value of [...state.tracked, ...state.untracked]) await restore(dest, value);
    await assertDestination();
    const nested = relative(src.root, src.cwd);
    if (nested) await parents(dest, nested.split(sep).join('/') + '/.cwd-check', true);
    const newCwd = join(dest, nested);
    await mkdir(newCwd, { recursive: true, mode: 0o700 });
    await verifyDestination(destinationContext, src, dest, state);
    if (
      (await git(ctx, dest, ['rev-parse', '--absolute-git-dir'])).toString().trimEnd() !==
      gitDirectory
    )
      throw new Error('Destination Git registration changed during materialization');
    await assertSnapshot(ctx, src, state);
    await assertDestination();
    await save('prepared');
    return { cwd: newCwd, root: dest, head: src.head };
  } catch (error) {
    const failure = new Error(errorText(error), { cause: error });
    Object.assign(failure, {
      ...(dest ? { destination: dest } : {}),
      ...(recordPath ? { recordPath } : {}),
    });
    try {
      const phase = record?.phase;
      if (record) record.failedDuring = phase;
      await save(phase === 'adding-worktree' ? 'outcome-unknown' : 'failed', failure.message);
    } catch {
      /* Preserve the original error and any partially written receipt. */
    }
    throw failure;
  } finally {
    // Only private capture scratch is disposable. Never clean a worktree or its
    // registration here, even if Git failed or its outcome is uncertain.
    await rm(ctx.scratch, { recursive: true, force: true });
  }
}
