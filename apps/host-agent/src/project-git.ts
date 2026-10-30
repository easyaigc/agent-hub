import { spawn, execFile } from 'node:child_process';
import { createHash, createHmac, randomUUID } from 'node:crypto';
import { lstat, opendir, realpath, stat } from 'node:fs/promises';
import { basename, isAbsolute, join, relative } from 'node:path';
import { RuntimeError } from '@codex-manage/codex-adapter';
import {
  projectGitCacheKey,
  type UsageTarget,
  type ProjectGitResponse,
  type ProjectGitReason,
  type GitComparison,
  type GitWorktree,
  type GitFileStatus,
  type ProjectGitFilesResponse,
} from '@codex-manage/protocol';

const excluded = new Set([
  '.git',
  'node_modules',
  '.build',
  'build',
  'dist',
  '.gradle',
  '.turbo',
  'Pods',
  'DerivedData',
]);
const inside = (root: string, value: string) => {
  const rel = relative(root, value);
  return (
    rel === '' ||
    (rel !== '..' &&
      !rel.startsWith('..' + (process.platform === 'win32' ? '\\' : '/')) &&
      !isAbsolute(rel))
  );
};
const display = (value: string) =>
  value
    .replace(
      // eslint-disable-next-line no-control-regex -- Escape C0/DEL in Git filenames for display.
      /[\u0000-\u001f\u007f]/gu,
      (c) => `\\u${c.charCodeAt(0).toString(16).padStart(4, '0')}`,
    )
    .slice(0, 1024);
class ReadFailure extends Error {
  constructor(readonly reason: ProjectGitReason) {
    super(reason);
  }
}
const unknown = (reason: ProjectGitReason, ref: string | null = null): GitComparison => ({
  ref,
  state: 'unknown',
  reason,
  ahead: null,
  behind: null,
});
type Context = { controller: AbortController; deadline: number };
type Sample = {
  response: ProjectGitResponse;
  files: Map<string, GitFileStatus[]>;
  signature: string;
  expires: number;
  filesExpire: number;
};

/** One Git status record is a path, even if it appears in both index and worktree categories. */
export function parseGitStatus(bytes: Buffer) {
  const records: Buffer[] = [];
  let start = 0;
  for (let index = 0; index < bytes.length; index++)
    if (bytes[index] === 0) {
      records.push(bytes.subarray(start, index));
      start = index + 1;
    }
  if (start !== bytes.length) throw new ReadFailure('output-limit');
  let branch: string | null = null,
    head: string | null = null,
    unborn = false;
  const files: GitFileStatus[] = [];
  const seen = new Set<string>();
  for (let index = 0; index < records.length; index++) {
    const raw = records[index]!;
    const header = raw.toString('utf8');
    if (header.startsWith('# branch.head ')) {
      branch = header.slice(14);
      if (branch === '(detached)') branch = null;
      continue;
    }
    if (header.startsWith('# branch.oid ')) {
      head = header.slice(13);
      unborn = head === '(initial)';
      if (unborn) head = null;
      continue;
    }
    const tag = header[0];
    if (!['1', '2', 'u', '?'].includes(tag ?? '')) continue;
    let offset = 0;
    const spaces = tag === '?' ? 1 : tag === '1' ? 8 : tag === '2' ? 9 : 10;
    for (let n = 0; n < spaces; n++) {
      const at = raw.indexOf(32, offset);
      if (at < 0) throw new ReadFailure('git-failed');
      offset = at + 1;
    }
    const path = raw.subarray(offset),
      id = createHash('sha256').update(path).digest('hex');
    const old = tag === '2' ? records[++index] : undefined;
    if (tag === '2' && !old) throw new ReadFailure('git-failed');
    if (seen.has(id)) continue;
    seen.add(id);
    const fields = header.split(' ', 3),
      xy = fields[1] ?? '..',
      sub = fields[2] ?? 'N...';
    const printable = (value: Buffer) =>
      Buffer.from(value.toString('utf8')).equals(value)
        ? display(value.toString('utf8'))
        : '[无法显示的文件名]';
    files.push({
      id,
      path: printable(path),
      oldPath: old ? printable(old) : null,
      staged: (tag === '1' || tag === '2') && xy[0] !== '.',
      unstaged: (tag === '1' || tag === '2') && xy[1] !== '.',
      untracked: tag === '?',
      conflict: tag === 'u',
      submodule: sub.startsWith('S') && sub !== 'S...',
    });
  }
  return {
    branch,
    head,
    unborn,
    files,
    changes: {
      changed: files.length,
      staged: files.filter((f) => f.staged).length,
      unstaged: files.filter((f) => f.unstaged).length,
      untracked: files.filter((f) => f.untracked).length,
      conflicts: files.filter((f) => f.conflict).length,
      submodules: files.filter((f) => f.submodule).length,
    },
  };
}

/** Host-owned, read-only Git access. No model, shell, network fetch, checkout or credentials. */
export class ProjectGitService {
  static supported(): Promise<boolean> {
    return new Promise((resolve) =>
      execFile('git', ['--version'], { timeout: 1500, windowsHide: true }, (error, stdout) => {
        const version = /git version (\d+)\.(\d+)/.exec(stdout);
        resolve(
          !error &&
            !!version &&
            (Number(version[1]) > 2 || (Number(version[1]) === 2 && Number(version[2]) >= 31)),
        );
      }),
    );
  }
  private closed = false;
  private processes = 0;
  private waiters: (() => void)[] = [];
  private contexts = new Set<Context>();
  private cache = new Map<string, Sample>();
  private pending = new Map<string, Promise<Sample>>();
  constructor(
    private readonly options: {
      secret: string;
      allowedRoots?: readonly string[];
      baselines?: Readonly<Record<string, string>>;
      timeoutMs?: number;
      cacheMs?: number;
      maxDirectories?: number;
    },
  ) {}
  close() {
    this.closed = true;
    for (const ctx of this.contexts) ctx.controller.abort();
    this.cache.clear();
    for (const wake of this.waiters.splice(0)) wake();
  }
  private id(kind: string, value: string) {
    return createHmac('sha256', this.options.secret)
      .update(kind + '\0' + value)
      .digest('hex')
      .slice(0, 32);
  }
  private check(ctx: Context) {
    if (this.closed || ctx.controller.signal.aborted || performance.now() >= ctx.deadline)
      throw new ReadFailure('timeout');
  }
  private async command(ctx: Context, root: string, args: string[]): Promise<Buffer> {
    this.check(ctx);
    while (this.processes >= 2) {
      if (this.waiters.length >= 16) throw new ReadFailure('timeout');
      await new Promise<void>((done) => {
        const wake = () => {
          ctx.controller.signal.removeEventListener('abort', wake);
          const index = this.waiters.indexOf(wake);
          if (index >= 0) this.waiters.splice(index, 1);
          done();
        };
        this.waiters.push(wake);
        ctx.controller.signal.addEventListener('abort', wake, { once: true });
      });
      this.check(ctx);
    }
    this.processes++;
    try {
      return await new Promise<Buffer>((resolve, reject) => {
        const env = { ...process.env };
        for (const key of Object.keys(env)) if (key.startsWith('GIT_')) delete env[key];
        Object.assign(env, {
          GIT_OPTIONAL_LOCKS: '0',
          GIT_TERMINAL_PROMPT: '0',
          GIT_NO_LAZY_FETCH: '1',
          // Also deny transports on older supported Git versions without lazy-fetch suppression.
          GIT_ALLOW_PROTOCOL: '',
          GIT_PAGER: 'cat',
        });
        const child = spawn(
          'git',
          ['--no-optional-locks', '-c', 'core.fsmonitor=false', '-C', root, ...args],
          { env, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true },
        );
        const chunks: Buffer[] = [];
        let size = 0,
          stderr = 0,
          failure: ProjectGitReason | null = null,
          hard: ReturnType<typeof setTimeout> | undefined;
        const stop = (reason: ProjectGitReason) => {
          if (failure) return;
          failure = reason;
          child.kill('SIGTERM');
          hard = setTimeout(() => child.kill('SIGKILL'), 250);
          hard.unref();
        };
        const abort = () => stop('timeout');
        ctx.controller.signal.addEventListener('abort', abort, { once: true });
        const timer = setTimeout(
          abort,
          Math.max(1, Math.min(1500, ctx.deadline - performance.now())),
        );
        child.stdout.on('data', (part: Buffer) => {
          size += part.length;
          if (size > 2 * 1024 * 1024) stop('output-limit');
          else chunks.push(part);
        });
        child.stderr.on('data', (part: Buffer) => {
          stderr += part.length;
          if (stderr > 65536) stop('output-limit');
        });
        child.once('error', (error: NodeJS.ErrnoException) => {
          failure = error.code === 'ENOENT' ? 'git-unavailable' : 'git-failed';
        });
        child.once('close', (code) => {
          clearTimeout(timer);
          if (hard) clearTimeout(hard);
          ctx.controller.signal.removeEventListener('abort', abort);
          if (failure) reject(new ReadFailure(failure));
          else if (code !== 0) reject(new ReadFailure('git-failed'));
          else resolve(Buffer.concat(chunks));
        });
      });
    } finally {
      this.processes--;
      this.waiters.shift()?.();
    }
  }
  private async optional(ctx: Context, root: string, args: string[]): Promise<string | null> {
    try {
      return (await this.command(ctx, root, args)).toString('utf8').trim() || null;
    } catch (error) {
      if (error instanceof ReadFailure && error.reason === 'git-failed') return null;
      throw error;
    }
  }
  private async oid(ctx: Context, root: string, ref: string | null) {
    return ref
      ? this.optional(ctx, root, ['rev-parse', '--verify', '--end-of-options', ref + '^{commit}'])
      : null;
  }
  private async compare(
    ctx: Context,
    root: string,
    head: string | null,
    ref: string | null,
    shallow: boolean,
  ): Promise<GitComparison> {
    if (!ref) return unknown('unconfigured');
    const oid = await this.oid(ctx, root, ref);
    if (!oid) return unknown('missing-ref', ref);
    if (!head) return unknown('unborn', ref);
    if (shallow) return unknown('shallow-history', ref);
    if (!(await this.optional(ctx, root, ['merge-base', head, oid])))
      return unknown('unrelated-history', ref);
    const counts = (
      await this.command(ctx, root, ['rev-list', '--left-right', '--count', head + '...' + oid])
    )
      .toString('utf8')
      .trim()
      .split(/\s+/)
      .map(Number);
    if (counts.length !== 2 || counts.some((n) => !Number.isSafeInteger(n) || n < 0))
      throw new ReadFailure('git-failed');
    return { ref, state: 'ready', reason: null, ahead: counts[0]!, behind: counts[1]! };
  }
  private async inspect(ctx: Context, root: string, displayPath: string) {
    const top = await this.optional(ctx, root, ['rev-parse', '--show-toplevel']);
    if (!top || (await realpath(top)) !== root) throw new ReadFailure('outside-project');
    const commonText = await this.optional(ctx, root, [
      'rev-parse',
      '--path-format=absolute',
      '--git-common-dir',
    ]);
    if (!commonText) throw new ReadFailure('git-failed');
    const common = await realpath(commonText);
    const gitdir = await this.optional(ctx, root, ['rev-parse', '--absolute-git-dir']);
    if (!gitdir) throw new ReadFailure('git-failed');
    const indexBefore = await stat(join(gitdir, 'index')).then(
      (s) => `${s.ino}:${s.size}:${s.mtimeMs}:${s.ctimeMs}`,
      () => null,
    );
    const before = await this.optional(ctx, root, ['rev-parse', '--verify', 'HEAD']);
    const parsed = parseGitStatus(
      await this.command(ctx, root, [
        'status',
        '--porcelain=v2',
        '--branch',
        '-z',
        '--untracked-files=all',
        '--ignore-submodules=none',
      ]),
    );
    const shallow =
      (await this.optional(ctx, root, ['rev-parse', '--is-shallow-repository'])) === 'true';
    const upstreamRef = await this.optional(ctx, root, [
      'rev-parse',
      '--symbolic-full-name',
      '@{upstream}',
    ]);
    const pushRef = await this.optional(ctx, root, [
      'rev-parse',
      '--symbolic-full-name',
      '@{push}',
    ]);
    const repoId = this.id('repository', common),
      treeId = this.id('worktree', root);
    const configured = this.options.baselines?.[repoId];
    if (configured && !/^refs\/(heads|remotes)\/[\w./-]+$/.test(configured))
      throw new ReadFailure('unconfigured');
    const remotes = (await this.optional(ctx, root, ['remote']))?.split('\n') ?? [];
    const remote = remotes.includes('origin') ? 'origin' : remotes.length === 1 ? remotes[0] : null;
    const baseRef =
      configured ??
      (remote
        ? await this.optional(ctx, root, ['symbolic-ref', `refs/remotes/${remote}/HEAD`])
        : null);
    const refs = [upstreamRef, pushRef, baseRef];
    const refBefore = await Promise.all(refs.map((ref) => this.oid(ctx, root, ref)));
    const upstream = await this.compare(ctx, root, parsed.head, upstreamRef, shallow);
    const publish =
      pushRef === upstreamRef
        ? { ...upstream }
        : await this.compare(ctx, root, parsed.head, pushRef, shallow);
    const baseline = {
      ...(await this.compare(ctx, root, parsed.head, baseRef, shallow)),
      source: configured
        ? ('configured' as const)
        : baseRef
          ? ('remote-head' as const)
          : ('unknown' as const),
    };
    const after = await this.optional(ctx, root, ['rev-parse', '--verify', 'HEAD']);
    const indexAfter = await stat(join(gitdir, 'index')).then(
      (s) => `${s.ino}:${s.size}:${s.mtimeMs}:${s.ctimeMs}`,
      () => null,
    );
    const refAfter = await Promise.all(refs.map((ref) => this.oid(ctx, root, ref)));
    if (
      before !== parsed.head ||
      after !== before ||
      indexAfter !== indexBefore ||
      JSON.stringify(refBefore) !== JSON.stringify(refAfter)
    )
      throw new ReadFailure('changed-during-read');
    let operation: GitWorktree['operation'] = null;
    if (await lstat(join(gitdir, 'MERGE_HEAD')).catch(() => null)) operation = 'merge';
    else if (
      (await lstat(join(gitdir, 'rebase-merge')).catch(() => null)) ||
      (await lstat(join(gitdir, 'rebase-apply')).catch(() => null))
    )
      operation = 'rebase';
    else if (await lstat(join(gitdir, 'CHERRY_PICK_HEAD')).catch(() => null))
      operation = 'cherry-pick';
    const tree: GitWorktree = {
      id: treeId,
      displayPath,
      state: 'ready',
      reason: null,
      branch: parsed.branch,
      head: parsed.head,
      unborn: parsed.unborn,
      shallow,
      changes: parsed.changes,
      upstream,
      publish,
      baseline,
      operation,
      consistency: 'best-effort',
    };
    return { repoId, tree, files: parsed.files };
  }
  private signature(roots: readonly string[]) {
    return JSON.stringify([roots, this.options.baselines ?? {}]);
  }
  async read(
    projectId: string,
    roots: readonly string[],
    target: UsageTarget,
    force = false,
  ): Promise<ProjectGitResponse> {
    if (this.closed) throw new RuntimeError(503, 'Git 状态服务已关闭');
    const key = projectGitCacheKey(target, projectId),
      signature = this.signature(roots),
      existing = this.cache.get(key);
    if (
      existing &&
      existing.signature === signature &&
      existing.expires > Date.now() &&
      (!force || Date.now() - Date.parse(existing.response.observedAt) < 1000)
    )
      return existing.response;
    const pending = this.pending.get(key);
    if (pending) {
      const result = await pending;
      if (result.signature === signature) return result.response;
      return this.read(projectId, roots, target);
    }
    if (this.pending.size >= 4) throw new RuntimeError(429, 'Git 查询繁忙，请稍后重试');
    const work = this.collect(projectId, roots, target, signature).then((sample) => {
      if (!this.closed) {
        this.cache.delete(key);
        this.cache.set(key, sample);
        while (this.cache.size > 16) this.cache.delete(this.cache.keys().next().value!);
      }
      return sample;
    });
    this.pending.set(key, work);
    try {
      return (await work).response;
    } finally {
      if (this.pending.get(key) === work) this.pending.delete(key);
    }
  }
  private async collect(
    projectId: string,
    roots: readonly string[],
    target: UsageTarget,
    signature: string,
  ): Promise<Sample> {
    const ctx: Context = {
      controller: new AbortController(),
      deadline: performance.now() + (this.options.timeoutMs ?? 6000),
    };
    this.contexts.add(ctx);
    const timer = setTimeout(() => ctx.controller.abort(), this.options.timeoutMs ?? 6000);
    const response: ProjectGitResponse = {
      schemaVersion: 1,
      target,
      projectId,
      snapshotId: randomUUID(),
      observedAt: new Date().toISOString(),
      kind: 'unknown',
      complete: true,
      issues: [],
      remoteKnowledge: 'local-refs',
      repositories: [],
    };
    const files = new Map<string, GitFileStatus[]>(),
      candidates = new Map<string, string>(),
      visited = new Set<string>();
    let directories = 0,
      entriesRead = 0;
    const issue = (reason: ProjectGitReason, path: string | null = null) => {
      response.complete = false;
      if (response.issues.some((item) => item.reason === reason && item.displayPath === path))
        return;
      if (response.issues.length < 64) response.issues.push({ reason, displayPath: path });
    };
    const discover = async (directory: string, grant: string): Promise<boolean> => {
      if (candidates.has(directory)) return true;
      const label = display(relative(grant, directory)) || display(basename(directory)) || '.';
      const marker = await lstat(join(directory, '.git')).catch(() => null);
      if (!marker) return true;
      if (marker.isSymbolicLink()) {
        issue('outside-project', label);
        return false;
      }
      if (candidates.size >= 32) {
        issue('scan-limit');
        return false;
      }
      candidates.set(directory, label);
      return true;
    };
    const walk = async (directory: string, grant: string, depth: number): Promise<void> => {
      this.check(ctx);
      if (visited.has(directory)) return;
      visited.add(directory);
      if (++directories > (this.options.maxDirectories ?? 300)) {
        issue('scan-limit');
        return;
      }
      if (!(await discover(directory, grant))) return;
      // Stream directory entries so a single huge directory cannot allocate an unbounded array.
      const entries = await opendir(directory).catch(() => null);
      if (!entries) {
        issue('directory-unavailable');
        return;
      }
      for await (const entry of entries) {
        this.check(ctx);
        if (++entriesRead > 4096) {
          issue('scan-limit');
          break;
        }
        if (!entry.isDirectory() || excluded.has(entry.name)) continue;
        if (depth >= 3) {
          issue('scan-limit');
          continue;
        }
        if (directories >= (this.options.maxDirectories ?? 300)) {
          issue('scan-limit');
          break;
        }
        await walk(join(directory, entry.name), grant, depth + 1);
      }
    };
    try {
      if (!roots.length) issue('unconfigured');
      if (roots.length > 16) issue('scan-limit');
      const grants = this.options.allowedRoots
        ? await Promise.all(this.options.allowedRoots.map((p) => realpath(p)))
        : null;
      const authorized: string[] = [];
      for (const raw of roots.slice(0, 16)) {
        try {
          const root = await realpath(raw);
          if (grants && !grants.some((grant) => inside(grant, root))) {
            issue('outside-project');
            continue;
          }
          const top = await this.optional(ctx, root, ['rev-parse', '--show-toplevel']);
          if (top && !inside(root, await realpath(top))) issue('outside-project');
          if ((await this.optional(ctx, root, ['rev-parse', '--is-bare-repository'])) === 'true') {
            issue('unsupported');
            continue;
          }
          // Give every explicit authorized root a chance before a large sibling spends the
          // recursive discovery budget. This does not grant access to external worktrees.
          if (await discover(root, root)) authorized.push(root);
        } catch (error) {
          issue(error instanceof ReadFailure ? error.reason : 'directory-unavailable');
        }
      }
      for (const root of authorized) {
        try {
          await walk(root, root, 0);
        } catch (error) {
          issue(error instanceof ReadFailure ? error.reason : 'directory-unavailable');
        }
      }
      for (const [root, label] of candidates) {
        try {
          let result;
          try {
            result = await this.inspect(ctx, root, label);
          } catch (error) {
            if (error instanceof ReadFailure && error.reason === 'changed-during-read')
              result = await this.inspect(ctx, root, label);
            else throw error;
          }
          let repo = response.repositories.find((r) => r.id === result.repoId);
          if (!repo) {
            repo = {
              id: result.repoId,
              name: display(basename(root)).trim().slice(0, 128) || '仓库',
              worktrees: [],
            };
            response.repositories.push(repo);
          }
          repo.worktrees.push(result.tree);
          files.set(result.tree.id, result.files);
        } catch (error) {
          const reason = error instanceof ReadFailure ? error.reason : 'directory-unavailable';
          issue(reason, label);
          response.repositories.push({
            id: this.id('unavailable', root),
            name: display(basename(root)).trim().slice(0, 128) || '仓库',
            worktrees: [
              {
                id: this.id('worktree', root),
                displayPath: label,
                state: 'unavailable',
                reason,
                branch: null,
                head: null,
                unborn: false,
                shallow: false,
                changes: null,
                upstream: unknown(reason),
                publish: unknown(reason),
                baseline: { ...unknown(reason), source: 'unknown' },
                operation: null,
                consistency:
                  reason === 'changed-during-read' ? 'changed-during-read' : 'best-effort',
              },
            ],
          });
        }
      }
      response.kind = response.repositories.length
        ? 'git'
        : response.complete
          ? 'non-git'
          : 'unknown';
      response.observedAt = new Date().toISOString();
      return {
        response,
        files,
        signature,
        expires: Date.now() + (this.options.cacheMs ?? 15000),
        filesExpire: Date.now() + 120000,
      };
    } finally {
      clearTimeout(timer);
      ctx.controller.abort();
      this.contexts.delete(ctx);
    }
  }
  files(
    projectId: string,
    roots: readonly string[],
    target: UsageTarget,
    worktreeId: string,
    snapshotId: string,
    cursor: string | null,
  ): ProjectGitFilesResponse {
    const sample = this.cache.get(projectGitCacheKey(target, projectId));
    if (
      !sample ||
      sample.filesExpire <= Date.now() ||
      sample.signature !== this.signature(roots) ||
      sample.response.snapshotId !== snapshotId
    )
      throw new RuntimeError(409, '仓库状态已变化，请刷新');
    const all = sample.files.get(worktreeId);
    if (!all) throw new RuntimeError(404, '没有该工作区的文件快照');
    const at = cursor === null ? 0 : /^(0|[1-9]\d{0,5})$/.test(cursor) ? Number(cursor) : -1;
    if (at < 0 || at > all.length) throw new RuntimeError(400, '无效文件游标');
    let end = at,
      bytes = 0;
    while (end < all.length && end < at + 100) {
      const size = Buffer.byteLength(JSON.stringify(all[end]));
      if (bytes + size > 100 * 1024) break;
      bytes += size;
      end++;
    }
    return {
      schemaVersion: 1,
      target,
      projectId,
      snapshotId,
      worktreeId,
      files: all.slice(at, end),
      nextCursor: end < all.length ? String(end) : null,
      truncated: false,
    };
  }
}
