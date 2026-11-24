import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { lstatSync, readFileSync, realpathSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { homedir } from 'node:os';
import { createHash } from 'node:crypto';
import { CodexRuntime, object, list, string, type WireObject } from './runtime.js';
import { DesktopCommandNotSubmitted } from './desktop-ipc.js';
import type { DesktopLiveObservation } from './desktop-live-window.js';
import type { WindowsDesktopIdentity } from './desktop-platform.js';

const execute = promisify(execFile);

function privatePath(path: string, directory = false): void {
  for (let cursor = path; cursor !== dirname(cursor); cursor = dirname(cursor)) {
    const info = lstatSync(cursor);
    if (info.isSymbolicLink()) throw new Error('Shared desktop path ownership unavailable');
  }
  const info = lstatSync(path);
  if (
    (directory ? !info.isDirectory() : !info.isFile()) ||
    info.uid !== process.getuid?.() ||
    info.mode & 0o077
  )
    throw new Error('Shared desktop state must be private');
}

async function processIdentity(pid: number) {
  if (!Number.isSafeInteger(pid) || pid <= 1) throw new Error('Invalid desktop process');
  const { stdout } = await execute(
    '/bin/ps',
    ['-p', String(pid), '-o', 'uid=,ppid=,lstart=,comm='],
    {
      encoding: 'utf8',
      timeout: 2000,
      maxBuffer: 8192,
    },
  );
  const output = stdout.trim();
  const match = /^(\d+)\s+(\d+)\s+(.{24})\s+(.+)$/.exec(output);
  if (!match || Number(match[1]) !== process.getuid?.())
    throw new Error('Original desktop process ownership unavailable');
  return { pid, parent: Number(match[2]), started: match[3], executable: realpathSync(match[4]!) };
}

/** Verify the existing launcher and listening executor, never start or replace either. */
export async function verifyDesktopSharedService(
  urlValue: string,
  executable: string,
): Promise<string> {
  if (process.platform !== 'darwin') throw new Error('Shared desktop control requires macOS');
  const url = new URL(urlValue);
  if (
    url.protocol !== 'ws:' ||
    !['127.0.0.1', '[::1]'].includes(url.hostname) ||
    !url.port ||
    url.username ||
    url.password ||
    url.pathname !== '/' ||
    url.search ||
    url.hash
  )
    throw new Error('Explicit loopback desktop service required');
  const directory = join(homedir(), '.config/agent-hub/shared-desktop');
  const ownerPath = join(directory, 'service/desktop-service-owner.json');
  privatePath(directory, true);
  privatePath(join(directory, 'service'), true);
  privatePath(join(directory, 'environment.json'));
  privatePath(ownerPath);
  if (
    lstatSync(ownerPath).size > 8192 ||
    lstatSync(join(directory, 'environment.json')).size > 16384
  )
    throw new Error('Invalid shared desktop identity record');
  const environment = object(JSON.parse(readFileSync(join(directory, 'environment.json'), 'utf8')));
  const owner = object(JSON.parse(readFileSync(ownerPath, 'utf8')));
  if (
    environment.schemaVersion !== 2 ||
    environment.url !== url.origin ||
    environment.state !== directory ||
    environment.executable !== realpathSync(executable) ||
    environment.wrapper !== join(directory, 'codex-shared.mjs') ||
    !/^[a-f0-9]{64}$/.test(string(owner.fingerprint)) ||
    owner.url !== url.origin ||
    (owner.home !== undefined && owner.home !== join(homedir(), '.codex'))
  )
    throw new Error('Original desktop shared target changed');
  privatePath(join(directory, 'codex-shared.mjs'));
  if (
    createHash('sha256')
      .update(readFileSync(join(directory, 'codex-shared.mjs')))
      .digest('hex') !== environment.wrapperSha256
  )
    throw new Error('Original desktop launcher changed');
  const [launcher, utility] = await Promise.all([
    processIdentity(Number(owner.ownerPid)),
    processIdentity(Number(owner.electronPid)),
  ]);
  const [desktop, sockets] = await Promise.all([
    processIdentity(utility.parent),
    execute('/usr/sbin/lsof', ['-nP', '-a', '-iTCP:' + url.port, '-sTCP:LISTEN', '-Fp'], {
      encoding: 'utf8',
      timeout: 2000,
      maxBuffer: 8192,
    }),
  ]);
  if (
    launcher.parent !== utility.pid ||
    desktop.executable !== realpathSync('/Applications/ChatGPT.app/Contents/MacOS/ChatGPT') ||
    !utility.executable.startsWith('/Applications/ChatGPT.app/Contents/Frameworks/') ||
    !launcher.executable.startsWith('/Applications/ChatGPT.app/Contents/Resources/')
  )
    throw new Error('Original desktop launcher lineage unavailable');
  const listeners = sockets.stdout
    .trim()
    .split('\n')
    .filter((v) => /^p\d+$/.test(v));
  if (listeners.length !== 1) throw new Error('Original desktop listener ambiguous');
  const executor = await processIdentity(Number(listeners[0]!.slice(1)));
  if (executor.parent !== launcher.pid || executor.executable !== realpathSync(executable))
    throw new Error('Original desktop execution service ownership unavailable');
  return createHash('sha256')
    .update(JSON.stringify([owner, launcher, utility, desktop, executor]))
    .digest('hex');
}

/** Compatibility transport for desktops without the former follower IPC.
 * Reads never resume a thread. Explicit commands attach only to a positively
 * loaded thread in the same, independently verified original execution service.
 */
export class DesktopSharedControl {
  windowsIdentity?: WindowsDesktopIdentity;
  ready = false;
  onSnapshot: (id: string, state: WireObject) => void = () => {};
  onUnavailable: (id: string) => void = () => {};
  onDiscovered: (id: string) => void = () => {};
  onObservation: (id: string, observation: DesktopLiveObservation) => void = () => {};
  onModelSettings: (id: string) => void = () => {};
  onDisconnect: () => void = () => {};
  private client: Pick<
    CodexRuntime,
    'connect' | 'call' | 'respond' | 'close' | 'onEvent' | 'onRequest' | 'onDisconnect'
  >;
  private readonly inspectService: () => string | Promise<string>;
  private verifying: Promise<string> | undefined;
  private verifiedAt = 0;
  private loadedSnapshot: { ids: Set<string>; at: number } | undefined;
  private loading: Promise<Set<string>> | undefined;
  private generation = 0;
  private identity = '';
  private states = new Map<string, WireObject>();
  private checked = new Map<string, number>();
  private pending = new Map<string, Promise<WireObject | undefined>>();
  private observed = new Map<string, number>();
  private settings = new Map<string, WireObject>();
  private requests = new Map<string | number, WireObject>();
  private resolutions = new Map<
    string | number,
    { resolve: () => void; reject: (e: Error) => void; timer: NodeJS.Timeout }
  >();
  private attached = new Set<string>();
  private timer: NodeJS.Timeout | undefined;
  constructor(
    url: string,
    executable: string,
    dependencies: {
      inspectService?: () => string | Promise<string>;
      client?: DesktopSharedControl['client'];
    } = {},
  ) {
    this.inspectService =
      dependencies.inspectService ?? (() => verifyDesktopSharedService(url, executable));
    this.client =
      dependencies.client ?? new CodexRuntime({ mode: 'shared-runtime', sharedWebsocketUrl: url });
    this.client.onDisconnect = () => this.disconnected();
    this.client.onEvent = (method, params) => {
      const id = string(params.threadId) || string(object(params.thread).id);
      if (method === 'serverRequest/resolved') {
        const requestId = params.requestId as string | number;
        const pending = this.requests.get(requestId);
        if (object(pending?.params).threadId === id) {
          this.requests.delete(requestId);
          const waiting = this.resolutions.get(requestId);
          if (waiting) {
            clearTimeout(waiting.timer);
            this.resolutions.delete(requestId);
            waiting.resolve();
          }
          this.publishRequests(id);
        }
      }
      if (method === 'thread/settings/updated' && id)
        this.settings.set(id, object(params.threadSettings));
      if (
        id &&
        Date.now() - (this.observed.get(id) ?? 0) < 30_000 &&
        Date.now() - (this.checked.get(id) ?? 0) >= 500
      )
        void this.readOnce(id).catch(() => this.onUnavailable(id));
    };
    this.client.onRequest = (id, method, params) => {
      const threadId = string(params.threadId);
      // No request from another service/thread can become an approval here.
      if (!this.hasOwner(threadId) || !this.attached.has(threadId)) return;
      this.requests.set(id, { id, method, params, completed: false });
      this.publishRequests(threadId);
    };
  }
  private async verify(fresh = false): Promise<string> {
    if (!fresh && this.ready && Date.now() - this.verifiedAt < 1000) return this.identity;
    if (!fresh && this.verifying) return this.verifying;
    const generation = this.generation;
    const expected = this.identity;
    const work = (async () => {
      try {
        const current = await this.inspectService();
        if (generation !== this.generation) throw new Error('Desktop verification retired');
        if (expected && expected !== current) throw new Error('Original desktop owner changed');
        this.verifiedAt = Date.now();
        return current;
      } catch (error) {
        if (generation === this.generation) {
          this.disconnected();
          this.client.close();
        }
        throw error;
      }
    })().finally(() => {
      if (this.verifying === work) this.verifying = undefined;
    });
    if (!fresh) this.verifying = work;
    return work;
  }
  async connect(): Promise<void> {
    if (this.ready) return;
    const generation = ++this.generation;
    this.identity = await this.inspectService();
    if (generation !== this.generation) throw new Error('Desktop connection closed');
    await this.client.connect();
    if (generation !== this.generation) {
      this.client.close();
      throw new Error('Desktop connection closed');
    }
    await this.verify(true);
    this.ready = true;
    this.timer = setInterval(() => {
      const id = [...this.observed].findLast(([, at]) => Date.now() - at < 30_000)?.[0];
      if (id && !this.pending.has(id)) void this.readOnce(id).catch(() => this.onUnavailable(id));
    }, 1500);
    this.timer.unref();
  }
  private async loaded(id: string, fresh = false): Promise<boolean> {
    if (!fresh && this.loadedSnapshot && Date.now() - this.loadedSnapshot.at < 1000)
      return this.loadedSnapshot.ids.has(id);
    if (!fresh && this.loading) return (await this.loading).has(id);
    const generation = this.generation;
    const work = this.readLoaded()
      .then((ids) => {
        if (generation === this.generation && this.ready)
          this.loadedSnapshot = { ids, at: Date.now() };
        return ids;
      })
      .finally(() => {
        if (this.loading === work) this.loading = undefined;
      });
    if (!fresh) this.loading = work;
    return (await work).has(id);
  }
  private async readLoaded(): Promise<Set<string>> {
    let cursor: string | undefined;
    const seen = new Set<string>();
    const ids = new Set<string>();
    for (let page = 0; page < 32; page++) {
      const value = await this.client.call('thread/loaded/list', {
        limit: 100,
        ...(cursor ? { cursor } : {}),
      });
      if (!Array.isArray(value.data)) throw new Error('Invalid loaded-thread response');
      for (const id of value.data) {
        if (typeof id !== 'string' || !/^[a-f0-9-]{36}$/i.test(id))
          throw new Error('Invalid loaded-thread identity');
        ids.add(id);
      }
      const next = string(value.nextCursor);
      if (!next) return ids;
      if (seen.has(next)) throw new Error('Repeated loaded-thread cursor');
      seen.add(next);
      cursor = next;
    }
    throw new Error('Loaded-thread catalog limit exceeded');
  }
  hasOwner(id: string): boolean {
    return this.ready && this.states.has(id) && Date.now() - (this.checked.get(id) ?? 0) < 10_000;
  }
  state(id: string): WireObject | undefined {
    return this.states.get(id);
  }
  modelSettings(id: string): { model: string; effort: string | null } | undefined {
    const settings = object(this.state(id)?.latestThreadSettings);
    if (!this.hasOwner(id) || !string(settings.model)) return;
    return {
      model: string(settings.model),
      effort: typeof settings.effort === 'string' ? settings.effort : null,
    };
  }
  async refreshModelSettings(id: string): Promise<void> {
    await this.follow(id);
  }
  observation(_id: string): DesktopLiveObservation | undefined {
    return undefined;
  }
  bindObservation(_id: string, _messages: { id: string; runId?: string }[]): void {}
  async refreshObservation(id: string): Promise<void> {
    await this.follow(id);
  }
  refreshSnapshot(id: string, force = true): void {
    void this.follow(id, 1500, force).catch(() => this.onUnavailable(id));
  }
  refreshUnarchivedThread(id: string): void {
    this.checked.delete(id);
  }
  async completeHistory(id: string): Promise<WireObject> {
    const state = await this.follow(id, 1500, true);
    // A bounded latest window must never be presented as complete history.
    if (!state || state.historyMode === 'paginated')
      throw new Error('Use the dedicated paginated history reader');
    return state;
  }
  async follow(id: string, _timeoutMs = 1500, force = false): Promise<WireObject | undefined> {
    if (!this.ready || !/^[a-f0-9-]{36}$/i.test(id)) return;
    this.observed.delete(id);
    this.observed.set(id, Date.now());
    while (this.observed.size > 32) this.observed.delete(this.observed.keys().next().value!);
    if (!force && this.hasOwner(id) && Date.now() - (this.checked.get(id) ?? 0) < 1000)
      return this.state(id);
    return this.readOnce(id);
  }
  private readOnce(id: string): Promise<WireObject | undefined> {
    const prior = this.pending.get(id);
    if (prior) return prior;
    const generation = this.generation;
    const work = this.read(id, generation).finally(() => {
      if (this.pending.get(id) === work) this.pending.delete(id);
    });
    this.pending.set(id, work);
    return work;
  }
  private async read(id: string, generation: number): Promise<WireObject | undefined> {
    await this.verify();
    if (!(await this.loaded(id))) {
      this.states.delete(id);
      this.checked.delete(id);
      this.onUnavailable(id);
      return;
    }
    const thread = object(
      (await this.client.call('thread/read', { threadId: id, includeTurns: false })).thread,
    );
    if (thread.id !== id || !string(object(thread.status).type))
      throw new Error('Original thread target mismatch');
    let turns: WireObject[];
    if (thread.historyMode === 'paginated') {
      const [summaries, items] = await Promise.all([
        this.client.call('thread/turns/list', {
          threadId: id,
          limit: 2,
          sortDirection: 'desc',
          itemsView: 'notLoaded',
        }),
        this.client.call('thread/items/list', { threadId: id, limit: 30, sortDirection: 'desc' }),
      ]);
      if (!Array.isArray(summaries.data) || !Array.isArray(items.data))
        throw new Error('Invalid shared history window');
      turns = list(summaries.data)
        .map(object)
        .reverse()
        .map((turn) => ({
          ...turn,
          items: list(items.data)
            .map(object)
            .filter((row) => row.turnId === turn.id)
            .reverse()
            .map((row) => object(row.item)),
        }));
    } else {
      const full = object(
        (await this.client.call('thread/read', { threadId: id, includeTurns: true })).thread,
      );
      if (full.id !== id) throw new Error('Original thread target mismatch');
      turns = list(full.turns).map(object);
    }
    await this.verify();
    if (!this.ready || generation !== this.generation) return;
    const settings = {
      model: thread.model,
      effort: thread.reasoningEffort,
      ...this.settings.get(id),
    };
    const state = {
      ...thread,
      id,
      hostId: 'local',
      resumeState: 'resumed',
      threadRuntimeStatus: thread.status,
      latestModel: settings.model,
      latestReasoningEffort: settings.effort,
      latestThreadSettings: settings,
      turns: turns.map((turn) => ({ ...turn, turnId: turn.id })),
      requests: this.threadRequests(id),
    };
    this.states.set(id, state);
    this.checked.set(id, Date.now());
    this.onSnapshot(id, state);
    return state;
  }
  private threadRequests(id: string) {
    return [...this.requests.values()].filter((r) => object(r.params).threadId === id);
  }
  private publishRequests(id: string) {
    const prior = this.state(id);
    if (!prior) return;
    const next = { ...prior, requests: this.threadRequests(id) };
    this.states.set(id, next);
    this.onSnapshot(id, next);
  }
  async command(
    id: string,
    method: string,
    params: WireObject,
    beforeExecutionWrite?: () => void,
  ): Promise<WireObject> {
    const state = await this.follow(id, 1500, true);
    if (!state || !this.hasOwner(id))
      throw new DesktopCommandNotSubmitted('原桌面尚未加载此对话', true);
    if (
      state.canAcceptDirectInput === false &&
      ['thread-follower-start-turn', 'thread-follower-steer-turn'].includes(method)
    )
      throw new DesktopCommandNotSubmitted('此对话不接受直接输入');
    if (!(await this.loaded(id, true)))
      throw new DesktopCommandNotSubmitted('原桌面尚未加载此对话', true);
    await this.verify(true);
    if (!this.attached.has(id)) {
      // Same already-loaded executor; no new service, no history replacement,
      // no settings/speed override and no model input during attachment.
      const result = await this.client.call('thread/resume', { threadId: id, excludeTurns: true });
      if (object(result.thread).id !== id)
        throw new Error('Shared desktop attachment target mismatch');
      await this.verify(true);
      this.attached.add(id);
    }
    let operation: string, request: WireObject;
    if (method === 'thread-follower-start-turn') {
      operation = 'turn/start';
      request = { ...object(object(params.turnStart).request), threadId: id };
      delete request.attachments;
      delete request.commentAttachments;
    } else if (method === 'thread-follower-steer-turn') {
      operation = 'turn/steer';
      const active = list(state.turns)
        .map(object)
        .findLast((turn) => turn.status === 'inProgress');
      if (!active?.turnId) throw new DesktopCommandNotSubmitted('当前任务已变化');
      request = {
        threadId: id,
        expectedTurnId: active.turnId,
        input: params.input,
        clientUserMessageId: params.clientUserMessageId,
      };
    } else if (method === 'thread-follower-interrupt-turn') {
      operation = 'turn/interrupt';
      request = { threadId: id, turnId: params.expectedTurnId };
      if (
        !list(state.turns).some(
          (turn) =>
            object(turn).turnId === params.expectedTurnId && object(turn).status === 'inProgress',
        )
      )
        throw new DesktopCommandNotSubmitted('指定任务已结束或已变化');
    } else if (method === 'thread-follower-update-thread-settings') {
      operation = 'thread/settings/update';
      request = { ...object(params.settings), threadId: id };
    } else {
      const pending = this.requests.get(params.requestId as string | number);
      const allowed: Record<string, string> = {
        'thread-follower-command-approval-decision': 'item/commandExecution/requestApproval',
        'thread-follower-file-approval-decision': 'item/fileChange/requestApproval',
        'thread-follower-submit-user-input': 'item/tool/requestUserInput',
        'thread-follower-submit-mcp-server-elicitation-response': 'mcpServer/elicitation/request',
        'thread-follower-permissions-request-approval-response': 'item/permissions/requestApproval',
      };
      if (
        !pending ||
        !allowed[method] ||
        pending.method !== allowed[method] ||
        object(pending.params).threadId !== id
      )
        throw new DesktopCommandNotSubmitted('审批已处理、过期或不属于此对话');
      const { requestId, ...response } = params;
      await this.verify(true);
      await new Promise<void>((resolve, reject) => {
        const key = requestId as string | number;
        if (this.resolutions.has(key)) {
          reject(new Error('Approval response already pending'));
          return;
        }
        const timer = setTimeout(() => {
          this.resolutions.delete(key);
          reject(new Error('Approval outcome uncertain'));
        }, 15_000);
        this.resolutions.set(key, { resolve, reject, timer });
        try {
          beforeExecutionWrite?.();
          this.client.respond(
            key,
            Object.hasOwn(response, 'response') ? object(response.response) : response,
          );
        } catch (error) {
          clearTimeout(timer);
          this.resolutions.delete(key);
          reject(error);
        }
      });
      return {};
    }
    // Legacy presets carried both a profile hint and a concrete sandbox.
    // Current App Server requires one representation. Preserve the concrete
    // policy only when the known hint agrees; reject conflicts/custom hints.
    if (request.permissions !== undefined && request.sandboxPolicy !== undefined) {
      const presets: Record<string, string> = {
        ':read-only': 'readOnly',
        ':workspace': 'workspaceWrite',
        ':danger-full-access': 'dangerFullAccess',
      };
      const profile = string(request.permissions);
      if (
        !Object.hasOwn(presets, profile) ||
        object(request.sandboxPolicy).type !== presets[profile]
      )
        throw new DesktopCommandNotSubmitted('权限配置冲突，输入未提交');
      delete request.permissions;
    }
    await this.verify(true);
    beforeExecutionWrite?.();
    const result = await this.client.call(operation, request);
    this.checked.delete(id);
    void this.follow(id, 1500, true).catch(() => this.onUnavailable(id));
    return method === 'thread-follower-interrupt-turn' ? result : { result };
  }
  private disconnected(): void {
    const wasReady = this.ready;
    this.ready = false;
    this.verifiedAt = 0;
    this.loadedSnapshot = undefined;
    this.loading = undefined;
    this.verifying = undefined;
    this.pending.clear();
    this.observed.clear();
    this.settings.clear();
    if (this.timer) clearInterval(this.timer);
    this.timer = undefined;
    this.states.clear();
    this.checked.clear();
    this.requests.clear();
    this.attached.clear();
    for (const waiting of this.resolutions.values()) {
      clearTimeout(waiting.timer);
      waiting.reject(new Error('Desktop disconnected; approval outcome uncertain'));
    }
    this.resolutions.clear();
    if (wasReady) this.onDisconnect();
  }
  close(): void {
    this.generation++;
    this.disconnected();
    this.client.close();
  }
}
