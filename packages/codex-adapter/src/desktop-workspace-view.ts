import { CodexHistoryReader } from './history-reader.js';
import { RuntimeError, RuntimeSession } from './runtime-session.js';
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
  private retryAt = 0;
  private retryDelay = 1000;
  private readFailure?: unknown;
  private catalogRead = false;
  private recoveryTimer: NodeJS.Timeout | undefined;
  get status() {
    return {
      state:
        this.readFailure || (this.catalogRead && !this.reader.ready)
          ? 'unavailable'
          : this.catalogRead && this.reader.ready
            ? 'ready'
            : 'not-read',
      retryAfterMs: Math.max(0, this.retryAt - Date.now()),
    };
  }
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
    this.reader.onDisconnect = () => {
      if (this.closed) return;
      this.readFailure ??= new RuntimeError(
        503,
        '原桌面目录读取器已断开',
        'CODEX_READER_DISCONNECTED',
      );
      this.scheduleRecovery();
    };
  }

  private scheduleRecovery(): void {
    if (this.closed || this.recoveryTimer) return;
    this.recoveryTimer = setTimeout(
      () => {
        this.recoveryTimer = undefined;
        if (!this.closed) void this.workspace().catch(() => {});
      },
      Math.max(1000, this.retryAt - Date.now()),
    );
    this.recoveryTimer.unref();
  }

  workspace(): Promise<Workspace> {
    if (this.refreshing) return this.refreshing;
    const work = this.refresh()
      .then((workspace) => {
        if (this.closed) throw new Error('Desktop workspace view closed');
        if (this.recoveryTimer) clearTimeout(this.recoveryTimer);
        this.recoveryTimer = undefined;
        this.catalogRead = true;
        this.retryDelay = 1000;
        this.readFailure = undefined;
        return workspace;
      })
      .catch((error: unknown) => {
        if (this.closed) throw error;
        if (error !== this.readFailure) {
          const nativeCode = (error as { code?: unknown })?.code;
          const code =
            typeof nativeCode === 'string' &&
            /^(?:CODEX_READER_|WINDOWS_)[A-Z0-9_]+$/.test(nativeCode)
              ? nativeCode
              : 'DESKTOP_CATALOG_UNAVAILABLE';
          this.readFailure =
            error instanceof RuntimeError
              ? error
              : new RuntimeError(503, '原桌面目录暂不可用，保留原连接并稍后重试', code);
          this.retryAt = Date.now() + this.retryDelay;
          this.retryDelay = Math.min(15_000, this.retryDelay * 2);
          // A disconnect may have scheduled before this attempt failed. Move
          // that deadline forward; a premature timer must not lose the loop.
          if (this.recoveryTimer) clearTimeout(this.recoveryTimer);
          this.recoveryTimer = undefined;
        }
        this.scheduleRecovery();
        throw this.readFailure;
      })
      .finally(() => {
        if (this.refreshing === work) this.refreshing = undefined;
      });
    this.refreshing = work;
    return work;
  }

  private async refresh(): Promise<Workspace> {
    if (this.closed) throw new Error('Desktop workspace view closed');
    if (Date.now() < this.retryAt) throw this.readFailure;
    let catalog;
    try {
      await this.session.connectDesktopReader(this.reader, false, () => !this.closed);
      catalog = await this.reader.desktopWorkspace();
    } catch (error) {
      // Only transport failures can retry a complete idempotent catalog once.
      // Protocol rejections, exit barriers and write operations never enter it.
      const code = (error as { code?: string })?.code;
      if (
        ![
          'CODEX_READER_DISCONNECTED',
          'CODEX_READER_WRITE_FAILED',
          'CODEX_READER_TIMEOUT',
        ].includes(code ?? '')
      )
        throw error;
      await this.session.connectDesktopReader(this.reader, true, () => !this.closed);
      catalog = await this.reader.desktopWorkspace();
    }
    if (this.closed) {
      this.reader.close();
      throw new Error('Desktop workspace view closed');
    }
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
    if (this.recoveryTimer) clearTimeout(this.recoveryTimer);
    this.recoveryTimer = undefined;
    this.reader.close();
  }
}
