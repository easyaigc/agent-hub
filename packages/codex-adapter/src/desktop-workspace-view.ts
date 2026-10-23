import { CodexHistoryReader } from './history-reader.js';
import { RuntimeSession } from './runtime-session.js';
import type { WorkspaceSidebar } from '@codex-manage/protocol';

type Workspace = Awaited<ReturnType<RuntimeSession['workspace']>> & { sidebar?: WorkspaceSidebar };
type Summary = Workspace['threads'][number];

/** Read-only presentation catalog, separate from verified desktop execution. */
export class DesktopWorkspaceView {
  private readonly reader: CodexHistoryReader;
  private summaries = new Map<string, Summary>();
  private refreshing: Promise<Workspace> | undefined;
  private closed = false;
  private deleted = new Set<string>();
  removed(threadId: string): void {
    this.deleted.add(threadId);
  }

  constructor(
    private readonly session: RuntimeSession,
    name: string,
    executable: string | undefined,
    statePath: string,
  ) {
    this.reader = new CodexHistoryReader(session.hostId, name, executable, statePath);
  }

  workspace(): Promise<Workspace> {
    if (this.refreshing) return this.refreshing;
    const work = this.refresh().finally(() => {
      if (this.refreshing === work) this.refreshing = undefined;
    });
    this.refreshing = work;
    return work;
  }

  private async refresh(): Promise<Workspace> {
    if (this.closed) throw new Error('Desktop workspace view closed');
    if (!this.reader.ready) await this.reader.connect();
    const catalog = await this.reader.desktopWorkspace();
    // Populate the existing session's read-only catalog through its public
    // pagination API, so every displayed ID remains openable by that session.
    let workspace = await this.session.workspace();
    const seen = new Set<string>();
    while (workspace.nextProjectCursor || workspace.nextThreadCursor) {
      const key = JSON.stringify([workspace.nextProjectCursor, workspace.nextThreadCursor]);
      if (seen.has(key)) throw new Error('Repeated desktop workspace cursor');
      seen.add(key);
      workspace = await this.session.workspace(
        workspace.nextProjectCursor ?? undefined,
        workspace.nextThreadCursor ?? undefined,
      );
    }
    const existing = new Map(workspace.threads.map((thread) => [thread.id, thread]));
    const threads: Summary[] = catalog.threads.map((metadata) => {
      const current = existing.get(metadata.id);
      const execution = this.session.rememberDesktopThread(metadata);
      // Losing a desktop owner invalidates live control, not a confirmed
      // archived task's terminal outcome. Active or unfinished tasks stay unknown.
      const archivedOutcome =
        metadata.archived &&
        execution.state === 'unknown' &&
        ['completed', 'interrupted', 'failed'].includes(execution.lastTurnStatus ?? '');
      // Keep a confirmed mobile creation's chosen project only when the desktop
      // has no explicit assignment and directory grouping is ambiguous.
      const chosen =
        current &&
        !catalog.sidebar &&
        !this.reader.hasDesktopAssignment(metadata.id) &&
        (((metadata.projectIds?.length ?? 0) > 1 && (current.projectIds?.length ?? 0) <= 1) ||
          (current.projectId === null && current.projectIds?.length === 0));
      return {
        ...current,
        ...metadata,
        ...(chosen ? { projectId: current.projectId, projectIds: current.projectIds ?? [] } : {}),
        turnId: execution.turnId,
        lastTurnStatus: execution.lastTurnStatus,
        pendingAsyncQuestionCount: current?.pendingAsyncQuestionCount ?? 0,
        // Archival changes catalog membership, independently of the observed task outcome.
        state: archivedOutcome
          ? execution.lastTurnStatus === 'failed'
            ? 'failed'
            : 'idle'
          : execution.state,
      };
    });
    const listed = new Set(threads.map((thread) => thread.id));
    for (const thread of workspace.threads) {
      if (
        !listed.has(thread.id) &&
        !this.deleted.has(thread.id) &&
        !this.session.isThreadArchived(thread.id) &&
        this.session.history(thread.id).runtime.canControl === true
      )
        threads.push(thread);
    }
    this.summaries = new Map(threads.map((thread) => [thread.id, thread]));
    return {
      ...workspace,
      projects: catalog.projects,
      threads,
      ...(catalog.sidebar ? { sidebar: catalog.sidebar } : {}),
      pinnedThreadIds: (catalog.pinnedThreadIds ?? []).filter((id) =>
        threads.some((thread) => thread.id === id),
      ),
      nextProjectCursor: null,
      nextThreadCursor: null,
    };
  }

  /** Project only display summaries; IDs, runtime, messages and receipts stay intact. */
  project<T>(value: T): T {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return value;
    const row = value as Record<string, unknown>;
    let result = row;
    const thread = row.thread as Summary | undefined;
    const summary =
      thread && thread.hostId === this.session.hostId ? this.summaries.get(thread.id) : undefined;
    if (summary)
      result = {
        ...result,
        thread: {
          ...thread,
          projectId: summary.projectId,
          projectIds: summary.projectIds ?? [],
          title: summary.title,
          // Runtime history can retain an older active summary. The official
          // catalog owns lifecycle state for both history and event snapshots.
          archived: summary.archived ?? false,
        },
      };
    if (Array.isArray(row.events))
      result = {
        ...result,
        events: row.events.map((event: Record<string, unknown>) => ({
          ...event,
          snapshot: this.project(event.snapshot),
        })),
      };
    return result as T;
  }

  close(): void {
    this.closed = true;
    this.reader.close();
  }
}
