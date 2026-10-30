import type { UsageTarget } from './usage.js';
import { parseUsageTarget, usageTargetKey } from './usage.js';

export const PROJECT_GIT_CAPABILITY = 'project-git.status.v1' as const;
export const PROJECT_GIT_FILES_CAPABILITY = 'project-git.files.v1' as const;
export const PROJECT_GIT_MAX_BYTES = 128 * 1024;
export type ProjectGitReason =
  | 'unconfigured'
  | 'missing-ref'
  | 'shallow-history'
  | 'unborn'
  | 'unrelated-history'
  | 'timeout'
  | 'output-limit'
  | 'git-unavailable'
  | 'git-failed'
  | 'directory-unavailable'
  | 'outside-project'
  | 'scan-limit'
  | 'changed-during-read'
  | 'unsupported';
export interface GitComparison {
  ref: string | null;
  state: 'ready' | 'unknown';
  reason: ProjectGitReason | null;
  ahead: number | null;
  behind: number | null;
}
export interface GitWorktree {
  id: string;
  displayPath: string;
  state: 'ready' | 'unavailable';
  reason: ProjectGitReason | null;
  branch: string | null;
  head: string | null;
  unborn: boolean;
  shallow: boolean;
  changes: {
    changed: number;
    staged: number;
    unstaged: number;
    untracked: number;
    conflicts: number;
    submodules: number;
  } | null;
  upstream: GitComparison;
  publish: GitComparison;
  baseline: GitComparison & { source: 'configured' | 'remote-head' | 'unknown' };
  operation: 'merge' | 'rebase' | 'cherry-pick' | null;
  consistency: 'best-effort' | 'changed-during-read';
}
export interface GitRepository {
  id: string;
  name: string;
  worktrees: GitWorktree[];
}
export interface ProjectGitResponse {
  schemaVersion: 1;
  target: UsageTarget;
  projectId: string;
  snapshotId: string;
  observedAt: string;
  kind: 'git' | 'non-git' | 'unknown';
  complete: boolean;
  issues: { reason: ProjectGitReason; displayPath: string | null }[];
  remoteKnowledge: 'local-refs';
  repositories: GitRepository[];
}
export interface GitFileStatus {
  id: string;
  path: string;
  oldPath: string | null;
  staged: boolean;
  unstaged: boolean;
  untracked: boolean;
  conflict: boolean;
  submodule: boolean;
}
export interface ProjectGitFilesResponse {
  schemaVersion: 1;
  target: UsageTarget;
  projectId: string;
  snapshotId: string;
  worktreeId: string;
  files: GitFileStatus[];
  nextCursor: string | null;
  truncated: boolean;
}

export function parseProjectGitId(value: unknown): string {
  if (
    typeof value !== 'string' ||
    !value.trim() ||
    BufferFreeUTF8Length(value) > 512 ||
    // eslint-disable-next-line no-control-regex -- Machine/project identifiers must reject C0/DEL.
    /[\u0000-\u001f\u007f]/u.test(value)
  )
    throw new Error('Invalid project Git identifier');
  return value;
}
const BufferFreeUTF8Length = (value: string) =>
  [...value].reduce(
    (size, c) =>
      size +
      (c.codePointAt(0)! <= 0x7f
        ? 1
        : c.codePointAt(0)! <= 0x7ff
          ? 2
          : c.codePointAt(0)! <= 0xffff
            ? 3
            : 4),
    0,
  );
export function projectGitCacheKey(target: UsageTarget, projectId: string): string {
  return JSON.stringify([usageTargetKey(target), parseProjectGitId(projectId)]);
}
/** Reject cross-target or malformed snapshots before the native clients publish a cached value. */
export function parseProjectGitResponse(
  value: unknown,
  expected?: UsageTarget,
  projectId?: string,
): ProjectGitResponse {
  if (!value || typeof value !== 'object' || Array.isArray(value))
    throw new Error('Invalid project Git response');
  const row = value as ProjectGitResponse;
  const target = parseUsageTarget(row.target);
  parseProjectGitId(row.projectId);
  parseProjectGitId(row.snapshotId);
  if (
    row.schemaVersion !== 1 ||
    !['git', 'non-git', 'unknown'].includes(row.kind) ||
    typeof row.complete !== 'boolean' ||
    !Number.isFinite(Date.parse(row.observedAt)) ||
    row.remoteKnowledge !== 'local-refs' ||
    !Array.isArray(row.issues) ||
    row.issues.length > 64 ||
    !Array.isArray(row.repositories) ||
    row.repositories.length > 32 ||
    (expected && usageTargetKey(expected) !== usageTargetKey(target)) ||
    (projectId !== undefined && projectId !== row.projectId)
  )
    throw new Error('Invalid project Git response');
  const seen = new Set<string>();
  let worktrees = 0;
  for (const repo of row.repositories) {
    parseProjectGitId(repo.id);
    parseProjectGitId(repo.name);
    if (!Array.isArray(repo.worktrees) || !repo.worktrees.length)
      throw new Error('Invalid Git repository');
    for (const tree of repo.worktrees) {
      parseProjectGitId(tree.id);
      worktrees++;
      if (
        seen.has(tree.id) ||
        worktrees > 32 ||
        !['ready', 'unavailable'].includes(tree.state) ||
        typeof tree.displayPath !== 'string' ||
        tree.displayPath.length > 2048
      )
        throw new Error('Invalid Git worktree');
      seen.add(tree.id);
      if (tree.state === 'ready' && !tree.changes) throw new Error('Missing Git changes');
      if (
        tree.changes &&
        Object.values(tree.changes).some((n) => !Number.isSafeInteger(n) || n < 0)
      )
        throw new Error('Invalid Git count');
      for (const comparison of [tree.upstream, tree.publish, tree.baseline]) {
        if (!comparison || !['ready', 'unknown'].includes(comparison.state))
          throw new Error('Invalid Git comparison');
        if (comparison.state === 'ready') {
          if (
            comparison.reason !== null ||
            !comparison.ref ||
            !Number.isSafeInteger(comparison.ahead) ||
            comparison.ahead! < 0 ||
            !Number.isSafeInteger(comparison.behind) ||
            comparison.behind! < 0
          )
            throw new Error('Invalid Git comparison');
        } else if (comparison.ahead !== null || comparison.behind !== null || !comparison.reason)
          throw new Error('Unknown Git comparison is not zero');
      }
    }
  }
  if (row.kind === 'non-git' && (!row.complete || row.repositories.length || row.issues.length))
    throw new Error('Unproven non-Git project');
  return row;
}
