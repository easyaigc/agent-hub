import { withWindowsProcessCreation } from './windows-process.js';
import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createInterface } from 'node:readline';
import { dirname, isAbsolute, normalize, sep } from 'node:path';
import type { ClientType, WorkspaceSidebar } from '@codex-manage/protocol';
import { renderItem, type RenderItem } from './runtime.js';
import { DesktopSidebar, desktopPinnedSection } from './desktop-sidebar.js';
import { historyIdentity } from './desktop-history-identity.js';

export const codexClientType: ClientType = { id: 'codex', name: 'Codex' };

type RecordValue = Record<string, unknown>;
const object = (value: unknown): RecordValue => {
  if (!value || typeof value !== 'object' || Array.isArray(value))
    throw new Error('Invalid Codex response');
  return value as RecordValue;
};
const string = (value: unknown): string => {
  if (typeof value !== 'string') throw new Error('Invalid Codex string');
  return value;
};
const array = (value: unknown): unknown[] => {
  if (!Array.isArray(value)) throw new Error('Invalid Codex list');
  return value;
};
const cursor = (value: unknown): string | null => (value == null ? null : string(value));
const directoryKey = (path: string): string => {
  const key = normalize(path).replace(process.platform === 'win32' ? /[\\/]+$/ : /\/+$/, '') || sep;
  return process.platform === 'win32' ? key.toLowerCase() : key;
};

export interface ReadProject {
  id: string;
  hostId: string;
  name: string;
  roots: string[];
}
export interface ReadThread {
  id: string;
  hostId: string;
  projectId: string | null;
  projectIds?: string[];
  title: string;
  state: 'unknown';
  archived?: boolean;
}
export type ReadMessage = Omit<RenderItem, 'paths'>;
export interface ReadTurnEvidence {
  id: string;
  status: string;
  acceptedMessageIds: string[];
  acceptedMessageHashes: Record<string, string>;
}
export interface ReadWorkspace {
  hostId: string;
  name: string;
  connectionMode: 'history-only';
  clientType: ClientType;
  projects: ReadProject[];
  threads: ReadThread[];
  pinnedThreadIds?: string[];
  sidebar?: WorkspaceSidebar;
  nextProjectCursor: string | null;
  nextThreadCursor: string | null;
}

/** Dedicated metadata reader. Never loads, resumes, starts or submits a turn. */
export class CodexHistoryReader {
  private process: ChildProcessWithoutNullStreams | undefined;
  private processClosed: Promise<void> = Promise.resolve();
  private nextId = 0;
  private initialized = false;
  private startupTimeout = 15000;
  private pending = new Map<
    number,
    { resolve: (value: unknown) => void; reject: (error: Error) => void; timer: NodeJS.Timeout }
  >();
  private knownThreads = new Set<string>();
  private knownProjects = new Map<string, ReadProject>();
  private sidebar: DesktopSidebar | undefined;
  onDisconnect: () => void = () => {};

  constructor(
    private readonly hostId: string,
    private readonly name: string,
    private executable = 'codex',
    private readonly desktopStatePath?: string,
  ) {}
  get ready(): boolean {
    return this.initialized && this.process?.exitCode === null && !this.process.killed;
  }

  /** Never replace a metadata subprocess until its exit and stdio close are confirmed. */
  async configureExecutable(executable: string): Promise<void> {
    if (executable === this.executable) return;
    await this.closeAndWait();
    this.executable = executable;
  }

  private async closeAndWait(): Promise<void> {
    const child = this.process;
    if (!child) return;
    this.close();
    let timer: NodeJS.Timeout | undefined;
    try {
      await Promise.race([
        this.processClosed,
        new Promise<never>((_, reject) => {
          timer = setTimeout(
            () =>
              reject(
                Object.assign(new Error('Previous metadata reader exit unconfirmed'), {
                  code: 'CODEX_READER_EXIT_UNCONFIRMED',
                }),
              ),
            10_000,
          );
        }),
      ]);
      if (this.process === child) this.process = undefined;
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  async connect(): Promise<void> {
    if (this.ready) return;
    await this.closeAndWait();
    const child = withWindowsProcessCreation(15000, (remaining) => {
      this.startupTimeout = remaining;
      return spawn(this.executable, ['app-server', '--stdio'], {
        windowsHide: true,
        stdio: 'pipe',
        env: {
          ...process.env,
          ...(this.desktopStatePath ? { CODEX_HOME: dirname(this.desktopStatePath) } : {}),
        },
      });
    });
    this.process = child;
    this.processClosed = new Promise((resolve) => child.once('close', resolve));
    // Stderr may contain machine-specific data; consume it without publishing it.
    child.stderr.resume();
    const transportClosed = () => {
      if (this.process !== child) return;
      this.fail(new Error('Reader transport closed'));
      child.kill('SIGTERM');
    };
    // A live metadata process can close only its input. A write callback alone
    // does not consume the stream's subsequent error event (for example EPIPE).
    child.stdin.on('error', transportClosed);
    child.stdout.on('error', transportClosed);
    child.stdout.once('end', transportClosed);
    child.stderr.on('error', transportClosed);
    const lines = createInterface({ input: child.stdout });
    lines.on('error', transportClosed);
    lines.on('line', (line) => {
      try {
        const message = object(JSON.parse(line));
        if (typeof message.id !== 'number') return;
        const request = this.pending.get(message.id);
        if (!request) return;
        clearTimeout(request.timer);
        this.pending.delete(message.id);
        if (message.error) request.reject(new Error('Codex rejected the read request'));
        else request.resolve(message.result);
      } catch {
        this.fail(new Error('Invalid Codex transport'));
      }
    });
    child.once('error', () => this.fail(new Error('Codex executable unavailable')));
    child.once('exit', () => this.fail(new Error('Codex reader disconnected')));
    await this.rpc('initialize', {
      clientInfo: { name: 'codex_manage_history', title: 'Codex Manage History', version: '0.1.0' },
      capabilities: { experimentalApi: true },
    });
    child.stdin.write(JSON.stringify({ method: 'initialized' }) + '\n');
    this.initialized = true;
  }

  private fail(error: Error): void {
    const wasReady = this.initialized;
    this.initialized = false;
    for (const item of this.pending.values()) {
      clearTimeout(item.timer);
      item.reject(error);
    }
    this.pending.clear();
    if (wasReady) this.onDisconnect();
  }

  private rpc(method: string, params: RecordValue): Promise<unknown> {
    if (
      ![
        'initialize',
        'project/list',
        'thread/list',
        'thread/read',
        'thread/items/list',
        'thread/turns/list',
        'model/list',
        'config/read',
      ].includes(method)
    )
      throw new Error('Read-only method required');
    const child = this.process;
    if (!child || child.killed || child.exitCode !== null)
      return Promise.reject(new Error('Reader unavailable'));
    const id = ++this.nextId;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(
        () => {
          const error = Object.assign(new Error('Codex read timed out'), {
            code: 'CODEX_READER_TIMEOUT',
          });
          this.pending.delete(id);
          reject(error);
          // A live but unresponsive metadata process must not keep desktop
          // readiness true forever. Only read requests exist on this channel.
          if (this.process === child) {
            this.fail(error);
            child.kill('SIGTERM');
          }
        },
        method === 'initialize' ? this.startupTimeout : 15000,
      );
      this.pending.set(id, { resolve, reject, timer });
      child.stdin.write(JSON.stringify({ id, method, params }) + '\n', (error) => {
        if (error) {
          clearTimeout(timer);
          const failure = Object.assign(new Error('Reader write failed'), {
            code: 'CODEX_READER_WRITE_FAILED',
          });
          this.pending.delete(id);
          reject(failure);
          if (this.process === child) {
            this.fail(failure);
            child.kill('SIGTERM');
          }
        }
      });
    });
  }

  /** Metadata only; callers cannot use this reader to execute or resume a task. */
  async read(method: string, params: RecordValue): Promise<RecordValue> {
    if (!this.ready || method === 'initialize') throw new Error('Reader unavailable');
    return object(await this.rpc(method, params));
  }
  /** Called only after the desktop IPC validates an exact live owner snapshot. */
  registerOwnerThread(threadId: string): void {
    this.knownThreads.add(threadId);
  }

  /** Only for IDs returned by the official desktop catalog, including archived history. */
  registerCatalogThread(threadId: string): void {
    this.knownThreads.add(threadId);
  }

  hasDesktopAssignment(threadId: string): boolean {
    return this.sidebar?.assignment(threadId) !== undefined;
  }

  /** Complete metadata snapshot so sparse projects and removed chats stay accurate. */
  async desktopWorkspace(): Promise<ReadWorkspace> {
    const sidebar = this.desktopStatePath ? new DesktopSidebar(this.desktopStatePath) : undefined;
    const pages = async (method: string, params: RecordValue): Promise<unknown[]> => {
      const rows: unknown[] = [];
      const seen = new Set<string>();
      let next: string | null = null;
      do {
        const page = object(
          await this.rpc(method, { ...params, ...(next ? { cursor: next } : {}) }),
        );
        rows.push(...array(page.data));
        next = cursor(page.nextCursor);
        if (next && seen.has(next)) throw new Error('Repeated Codex metadata cursor');
        if (next) seen.add(next);
      } while (next);
      return rows;
    };
    const [projects, threads, archived, pinned] = await Promise.all([
      pages('project/list', { limit: 100 }),
      pages('thread/list', {
        limit: 100,
        sortKey: 'recency_at',
        modelProviders: [],
        useStateDbOnly: true,
      }).catch((error: unknown) => {
        // Older official servers sort by updated_at when recency is unsupported.
        // A rejected read is safe to query again; transport failures still fail.
        if (!(error instanceof Error) || error.message !== 'Codex rejected the read request')
          throw error;
        return pages('thread/list', {
          limit: 100,
          sortKey: 'updated_at',
          modelProviders: [],
          useStateDbOnly: true,
        });
      }),
      pages('thread/list', {
        limit: 100,
        modelProviders: [],
        useStateDbOnly: true,
        archived: true,
      }),
      pages('thread/list', {
        limit: 100,
        modelProviders: [],
        useStateDbOnly: true,
        sectionId: desktopPinnedSection,
        sortKey: 'section_position',
        sortDirection: 'asc',
      }),
    ]);
    this.sidebar = sidebar;
    this.knownProjects.clear();
    const result = this.mapWorkspace({ data: projects }, { data: [...threads, ...archived] });
    const archivedIds = new Set(archived.map((v) => string(object(v).id)));
    result.threads = result.threads.map((v) => ({ ...v, archived: archivedIds.has(v.id) }));
    result.pinnedThreadIds = pinned.map((v) => string(object(v).id));
    if (sidebar)
      result.sidebar = sidebar.presentation(
        result.projects,
        result.threads,
        result.pinnedThreadIds,
        threads.map(object),
      );
    return result;
  }

  async workspace(projectCursor?: string, threadCursor?: string): Promise<ReadWorkspace> {
    if (!projectCursor && !threadCursor) this.knownProjects.clear();
    const [projectResult, threadResult] = await Promise.all([
      this.rpc('project/list', { limit: 100, ...(projectCursor ? { cursor: projectCursor } : {}) }),
      this.rpc('thread/list', {
        limit: 100,
        sortKey: 'updated_at',
        modelProviders: [],
        useStateDbOnly: true,
        ...(threadCursor ? { cursor: threadCursor } : {}),
      }),
    ]);
    return this.mapWorkspace(object(projectResult), object(threadResult));
  }

  private mapWorkspace(p: RecordValue, t: RecordValue): ReadWorkspace {
    const sourceProjects = array(p.data).map((value) => {
      const row = object(value);
      return {
        id: string(row.id),
        hostId: this.hostId,
        name: string(row.name),
        roots: array(row.roots).map((root) => string(object(root).path)),
      };
    });
    const projects = this.sidebar?.projects(sourceProjects) ?? sourceProjects;
    for (const project of projects) this.knownProjects.set(project.id, project);
    const threads: ReadThread[] = array(t.data).map((value) => {
      const row = object(value);
      const id = string(row.id);
      this.knownThreads.add(id);
      return {
        id,
        hostId: this.hostId,
        ...this.projectsForThread(row),
        title:
          typeof row.name === 'string' && row.name
            ? row.name
            : string(row.preview).slice(0, 120) || '未命名对话',
        state: 'unknown',
      };
    });
    return {
      hostId: this.hostId,
      name: this.name,
      connectionMode: 'history-only',
      clientType: codexClientType,
      projects,
      threads,
      ...(this.sidebar ? { pinnedThreadIds: this.sidebar.pinnedThreadIds() } : {}),
      nextProjectCursor: cursor(p.nextCursor),
      nextThreadCursor: cursor(t.nextCursor),
    };
  }

  private projectsForThread(row: RecordValue): { projectId: string | null; projectIds: string[] } {
    const desktopAssignment = this.sidebar?.assignment(string(row.id));
    if (desktopAssignment !== undefined) {
      const projectId =
        desktopAssignment && this.knownProjects.has(desktopAssignment) ? desktopAssignment : null;
      return { projectId, projectIds: projectId ? [projectId] : [] };
    }
    const assignment = cursor(row.projectId);
    if (assignment) return { projectId: assignment, projectIds: [assignment] };
    if (this.sidebar?.hasProjectCatalog) {
      const projectId = this.sidebar.projectForDirectory(string(row.id), row.cwd, [
        ...this.knownProjects.values(),
      ]);
      return { projectId, projectIds: projectId ? [projectId] : [] };
    }
    // Legacy desktop threads may only have cwd. Infer display grouping without
    // writing a canonical assignment or treating descendants as the same project.
    if (typeof row.cwd !== 'string' || !isAbsolute(row.cwd))
      return { projectId: null, projectIds: [] };
    const directory = directoryKey(row.cwd);
    const matches = [...this.knownProjects.values()].filter((project) =>
      project.roots.some((root) => isAbsolute(root) && directoryKey(root) === directory),
    );
    return {
      projectId: matches.length === 1 ? matches[0]!.id : null,
      projectIds: matches.map((project) => project.id),
    };
  }

  private turnEvidence(rawTurns: unknown[]): ReadTurnEvidence[] {
    return rawTurns.flatMap((value) => {
      const turn = object(value);
      if (typeof turn.id !== 'string' || typeof turn.status !== 'string') return [];
      return [
        {
          id: string(turn.id),
          status: string(turn.status),
          acceptedMessageIds: array(turn.items).flatMap((value) => {
            const item = object(value);
            const id =
              item.type === 'userMessage'
                ? item.clientId
                : item.type === 'steeringUserMessage' && item.status === 'accepted'
                  ? item.clientUserMessageId
                  : null;
            return typeof id === 'string' && id ? [id] : [];
          }),
          acceptedMessageHashes: Object.fromEntries(
            array(turn.items).flatMap((value) => {
              const item = object(value);
              if (item.type !== 'userMessage' || typeof item.clientId !== 'string') return [];
              const content = Array.isArray(item.content) ? item.content.map(object) : [];
              if (content.some((v) => v.type !== 'text' || typeof v.text !== 'string')) return [];
              return [
                [
                  item.clientId,
                  createHash('sha256')
                    .update(content.map((v) => v.text).join('\n\n'))
                    .digest('hex'),
                ],
              ];
            }),
          ),
        },
      ];
    });
  }
  /** Persisted positive input evidence only; never resumes the original owner. */
  async receiptEvidence(threadId: string): Promise<ReadTurnEvidence[]> {
    if (!this.knownThreads.has(threadId)) throw new Error('Thread not in this host workspace');
    const response = object(await this.rpc('thread/read', { threadId, includeTurns: true }));
    const thread = object(response.thread);
    if (thread.id !== threadId) throw new Error('Receipt evidence target mismatch');
    return this.turnEvidence(array(thread.turns));
  }

  async history(
    threadId: string,
    pageCursor?: string,
    fullDetails = false,
    // Private adapter side channel. Source paths never enter a history response.
    localImages?: (messageId: string, paths: readonly string[]) => void,
  ): Promise<{
    hostId: string;
    threadId: string;
    messages: ReadMessage[];
    nextCursor: string | null;
    turns: ReadTurnEvidence[];
    latestTurn?: { id: string; status: string };
    directory?: string;
    historyMode?: string;
  }> {
    if (!this.knownThreads.has(threadId)) throw new Error('Thread not in this host workspace');
    const metadata = object(await this.rpc('thread/read', { threadId, includeTurns: false }));
    const thread = object(metadata.thread);
    if (thread.id !== threadId) throw new Error('History target mismatch');
    let latestTurn: { id: string; status: string } | undefined;
    let items: unknown[],
      turns: ReadTurnEvidence[] = [],
      nextCursor: string | null = null;
    if (thread.historyMode === 'paginated') {
      const result = object(
        await this.rpc('thread/items/list', {
          threadId,
          sortDirection: 'desc',
          limit: 100,
          ...(pageCursor ? { cursor: pageCursor } : {}),
        }),
      );
      items = array(result.data)
        .reverse()
        .map((entry) => ({ ...object(object(entry).item), runId: object(entry).turnId }));
      nextCursor = cursor(result.nextCursor);
      if (!pageCursor) {
        try {
          // Summary excludes tool bodies. A persisted terminal turn is useful
          // read evidence even when this chat has no complete desktop stream.
          const page = object(
            await this.rpc('thread/turns/list', {
              threadId,
              limit: 1,
              sortDirection: 'desc',
              itemsView: 'notLoaded',
            }),
          );
          const last = object(array(page.data)[0] ?? {});
          if (typeof last.id === 'string' && typeof last.status === 'string')
            latestTurn = { id: last.id, status: last.status };
        } catch {
          // Older readers may lack turn summaries. History remains readable;
          // without positive evidence its runtime status remains unknown.
        }
      }
    } else {
      const result = object(await this.rpc('thread/read', { threadId, includeTurns: true }));
      const rawTurns = array(object(result.thread).turns);
      turns = this.turnEvidence(rawTurns);
      const last = turns.at(-1);
      if (!pageCursor && last) latestTurn = { id: last.id, status: last.status };
      items = rawTurns.flatMap((turn) =>
        array(object(turn).items).map((item) => ({ ...object(item), runId: object(turn).id })),
      );
    }
    const messages: ReadMessage[] = [];
    for (const value of items) {
      const item = object(value);
      if (['todo-list', 'planImplementation'].includes(string(item.type))) continue;
      const { paths, ...message } = renderItem(item, fullDetails ? Infinity : 200_000);
      message.files = paths
        .map((path) => path.replace(/\\/g, '/').split('/').at(-1)!)
        .filter(Boolean);
      const sourceId = message.id;
      message.id = historyIdentity(sourceId, message.runId);
      if (message.kind === 'reasoning' && !message.text) continue;
      if (item.type === 'userMessage') {
        const content = array(item.content).map(object);
        const imagePaths = content.flatMap((input) =>
          input.type === 'localImage' &&
          typeof input.path === 'string' &&
          input.path.length <= 8192 &&
          isAbsolute(input.path)
            ? [input.path]
            : [],
        );
        if (imagePaths.length) localImages?.(message.id, imagePaths);
        message.files = [
          ...new Set([
            ...message.files,
            ...content
              .filter((input) => input.type !== 'text')
              .map((input) =>
                typeof input.name === 'string'
                  ? input.name
                  : input.type === 'localImage' && typeof input.path === 'string'
                    ? input.path.replace(/\\/g, '/').split('/').at(-1) || '历史附件'
                    : typeof input.url === 'string' && !input.url.startsWith('data:')
                      ? input.url.split('/').at(-1)!
                      : '历史附件',
              ),
          ]),
        ];
      }
      messages.push(message);
    }
    return {
      hostId: this.hostId,
      threadId,
      messages,
      nextCursor,
      turns,
      ...(latestTurn ? { latestTurn } : {}),
      ...(typeof thread.cwd === 'string' && isAbsolute(thread.cwd)
        ? { directory: thread.cwd }
        : {}),
      ...(typeof thread.historyMode === 'string' ? { historyMode: thread.historyMode } : {}),
    };
  }

  close(): void {
    this.fail(new Error('Reader closed'));
    this.process?.kill('SIGTERM');
  }
}
