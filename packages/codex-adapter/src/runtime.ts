import { withWindowsProcessCreation } from './windows-process.js';
import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { createInterface } from 'node:readline';
import type { DesktopCreationConfiguration } from './desktop-creation-driver.js';
import { localMessagePath, messageLinks } from './message-links.js';

// Version-sensitive wire access stays in this package. No history file is edited.
export type WireObject = Record<string, unknown>;
export const object = (value: unknown): WireObject =>
  value && typeof value === 'object' && !Array.isArray(value) ? (value as WireObject) : {};
export const list = (value: unknown): unknown[] => (Array.isArray(value) ? value : []);
export const string = (value: unknown): string => (typeof value === 'string' ? value : '');

export interface RuntimeOptions {
  executable?: string;
  mode: 'managed-runtime' | 'shared-runtime' | 'desktop-runtime';
  socket?: string;
  sharedWebsocketUrl?: string;
  desktopVersion?: string;
  desktopCreation?: DesktopCreationConfiguration;
  desktopController?: string;
  desktopPreparedBridge?: string;
  windowsDesktopConfig?: string;
}
export class CodexRejection extends Error {
  constructor(
    message: string,
    readonly code?: number,
  ) {
    super(message);
  }
}

export class CodexRuntime {
  private child?: ChildProcessWithoutNullStreams;
  private websocket?: WebSocket;
  private counter = 0;
  private disconnected = false;
  private generation = 0;
  private startupTimeout = 30_000;
  private pending = new Map<
    number,
    { resolve: (value: WireObject) => void; reject: (error: Error) => void; timer: NodeJS.Timeout }
  >();
  ready = false;
  version = '';
  onEvent: (method: string, params: WireObject) => void = () => {};
  onRequest: (id: string | number, method: string, params: WireObject) => void = () => {};
  onDisconnect: () => void = () => {};

  constructor(readonly options: RuntimeOptions) {}

  async connect(): Promise<void> {
    if (this.options.mode === 'desktop-runtime') throw new Error('Desktop IPC transport required');
    const generation = ++this.generation;
    this.disconnected = false;
    this.ready = false;
    if (this.options.mode === 'shared-runtime') {
      await this.openSharedWebsocket(generation);
    } else {
      const child = withWindowsProcessCreation(30000, (remaining) => {
        this.startupTimeout = remaining;
        return spawn(
          this.options.executable ?? 'codex',
          ['app-server', '--stdio', '-c', 'tools.update_plan.enabled=true'],
          {
            windowsHide: true,
            stdio: 'pipe',
          },
        );
      });
      this.child = child;
      // Upstream stderr can contain private data. Do not forward it to remote clients or logs.
      child.stderr.resume();
      child.on('error', () => {
        if (generation === this.generation) this.fail();
      });
      child.on('exit', () => {
        if (generation === this.generation) this.fail();
      });
      child.stdin.on('error', () => {
        if (generation === this.generation) this.fail();
      });
      createInterface({ input: child.stdout }).on('line', (line) => {
        if (generation !== this.generation) return;
        this.receive(line);
      });
    }
    try {
      const result = await this.call('initialize', {
        clientInfo: { name: 'codex_manage', title: 'Codex Manage', version: '0.2.0' },
        capabilities: { experimentalApi: true },
      });
      if (generation !== this.generation || this.disconnected)
        throw new Error('Codex connection closed during initialization');
      this.version = string(result.userAgent);
      this.write({ method: 'initialized' });
      this.ready = true;
    } catch (error) {
      if (generation === this.generation) this.fail();
      this.child?.kill('SIGTERM');
      this.websocket?.close();
      throw error;
    }
  }

  /** Explicit local shared service; never spawns or replaces an execution service. */
  private async openSharedWebsocket(generation: number): Promise<void> {
    if (!this.options.sharedWebsocketUrl) throw new Error('Shared app-server URL required');
    const url = new URL(this.options.sharedWebsocketUrl);
    if (
      url.protocol !== 'ws:' ||
      !['127.0.0.1', '[::1]', 'localhost'].includes(url.hostname) ||
      !url.port ||
      url.username ||
      url.password ||
      url.search ||
      url.hash
    )
      throw new Error('Shared app-server requires an explicit local WebSocket endpoint');
    const websocket = new WebSocket(url);
    this.websocket = websocket;
    websocket.addEventListener('message', (event) => {
      if (generation !== this.generation) return;
      if (typeof event.data !== 'string' || Buffer.byteLength(event.data) > 32 * 1024 * 1024) {
        this.fail();
        websocket.close(1009);
        return;
      }
      this.receive(event.data);
    });
    const fail = () => {
      if (generation === this.generation) this.fail();
    };
    websocket.addEventListener('close', fail);
    websocket.addEventListener('error', fail);
    await new Promise<void>((resolve, reject) => {
      const finish = (error?: Error) => {
        clearTimeout(timer);
        websocket.removeEventListener('open', opened);
        websocket.removeEventListener('error', unavailable);
        websocket.removeEventListener('close', unavailable);
        if (error) {
          websocket.close();
          reject(error);
        } else resolve();
      };
      const opened = () => finish();
      const unavailable = () => finish(new Error('Shared app-server connection unavailable'));
      const timer = setTimeout(
        () => finish(new Error('Shared app-server connection timed out')),
        this.startupTimeout,
      );
      websocket.addEventListener('open', opened, { once: true });
      websocket.addEventListener('error', unavailable, { once: true });
      websocket.addEventListener('close', unavailable, { once: true });
    });
  }

  private receive(line: string): void {
    try {
      const message = object(JSON.parse(line));
      const method = string(message.method);
      if (method && message.id !== undefined) {
        this.onRequest(message.id as string | number, method, object(message.params));
      } else if (method) {
        this.onEvent(method, object(message.params));
      } else if (typeof message.id === 'number') {
        const call = this.pending.get(message.id);
        if (!call) return;
        clearTimeout(call.timer);
        this.pending.delete(message.id);
        if (message.error) {
          // Errors are surfaced locally with no raw JSON / credentials.
          call.reject(
            new CodexRejection(
              string(object(message.error).message) || 'Codex rejected request',
              Number(object(message.error).code),
            ),
          );
        } else call.resolve(object(message.result));
      }
    } catch {
      // Non-protocol diagnostics must not become user-facing messages.
    }
  }

  private write(message: WireObject): void {
    if (this.options.mode === 'shared-runtime') {
      if (this.websocket?.readyState !== WebSocket.OPEN)
        throw new Error('Shared app-server connection unavailable');
      this.websocket.send(JSON.stringify(message));
      return;
    }
    if (!this.child || this.child.exitCode !== null || this.child.stdin.destroyed)
      throw new Error('Codex connection unavailable');
    this.child.stdin.write(JSON.stringify(message) + '\n');
  }

  async call(method: string, params: WireObject = {}): Promise<WireObject> {
    const id = ++this.counter;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(
        () => {
          this.pending.delete(id);
          reject(new Error('Codex request outcome uncertain'));
        },
        method === 'initialize' ? this.startupTimeout : 30_000,
      );
      this.pending.set(id, { resolve, reject, timer });
      try {
        this.write({ id, method, params });
      } catch (error) {
        clearTimeout(timer);
        this.pending.delete(id);
        reject(error);
      }
    });
  }

  respond(id: string | number, result: WireObject): void {
    this.write({ id, result });
  }
  rejectRequest(id: string | number): void {
    this.write({ id, error: { code: -32601, message: 'Unsupported client request' } });
  }

  private fail(): void {
    if (this.disconnected) return;
    this.disconnected = true;
    this.ready = false;
    for (const call of this.pending.values()) {
      clearTimeout(call.timer);
      call.reject(new Error('Codex connection closed; outcome uncertain'));
    }
    this.pending.clear();
    this.onDisconnect();
  }

  close(): void {
    this.generation++;
    this.fail();
    // In shared mode this only closes our proxy, never the shared server.
    this.child?.kill('SIGTERM');
    this.websocket?.close();
  }
}

export interface RenderItem {
  id: string;
  role: 'user' | 'assistant' | 'tool';
  text: string;
  files: string[];
  kind: string;
  status: string;
  detail: string;
  paths: string[];
  runId?: string;
  replyPhase?: 'commentary' | 'final_answer';
}

export function renderItem(value: unknown, detailLimit = 200_000): RenderItem {
  const item = object(value);
  const kind = string(item.type);
  const content = list(item.content).map(object);
  const changes = list(item.changes).map(object);
  let text = string(item.text);
  let detail = '';
  if (kind === 'userMessage')
    text = content
      .map((entry) => string(entry.text))
      .filter(Boolean)
      .join('\n');
  if (kind === 'commandExecution') {
    text = string(item.command);
    detail = string(item.aggregatedOutput);
    if (item.exitCode !== null && item.exitCode !== undefined) detail += `\nExit ${item.exitCode}`;
  } else if (kind === 'fileChange') {
    text = changes.map((change) => string(change.path)).join('\n');
    detail = changes.map((change) => string(change.diff)).join('\n');
  } else if (kind === 'reasoning') {
    text = list(item.summary).map(string).join('\n');
  } else if (kind === 'contextCompaction') {
    text = '压缩上下文';
  } else if (kind === 'mcpToolCall' || kind === 'dynamicToolCall') {
    text = [string(item.server), string(item.tool)].filter(Boolean).join(' · ');
    const result = object(item.result);
    detail = list(result.content)
      .map((entry) => string(object(entry).text))
      .join('\n');
    if (!detail && item.contentItems)
      detail = list(item.contentItems)
        .map((v) => string(object(v).text))
        .join('\n');
    if (item.error) detail = string(object(item.error).message);
  } else if (kind === 'webSearch') text = string(item.query);
  else if (kind === 'imageView' || kind === 'imageGeneration') text = '图像';
  else if (kind === 'functionCallOutput') {
    text = string(item.name);
    detail =
      typeof item.output === 'string'
        ? item.output
        : list(item.output)
            .map((v) => string(object(v).text))
            .join('\n');
  } else if (!text && !['agentMessage', 'userMessage', 'plan', 'reasoning'].includes(kind))
    text = kind;
  const paths = [
    ...content.map((entry) => string(entry.path)),
    ...changes.map((entry) => string(entry.path)),
    string(item.path),
    string(item.savedPath),
  ].filter(Boolean);
  // Register only local links actually returned in this thread. The host enforces root / size checks.
  for (const link of messageLinks(text)) {
    const path = localMessagePath(link.reference);
    if (path) paths.push(path);
  }
  return {
    id: string(item.id),
    role:
      kind === 'userMessage'
        ? 'user'
        : ['agentMessage', 'plan'].includes(kind)
          ? 'assistant'
          : 'tool',
    text,
    files: [],
    kind,
    status:
      string(item.status) ||
      (kind === 'contextCompaction' && item.completed === false ? 'inProgress' : 'completed'),
    detail: detail.slice(-detailLimit),
    paths,
    ...(string(item.runId) ? { runId: string(item.runId) } : {}),
    ...(['commentary', 'final_answer'].includes(string(item.phase))
      ? { replyPhase: item.phase as 'commentary' | 'final_answer' }
      : {}),
  };
}
