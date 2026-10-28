import { connect } from 'node:net';
import type { Duplex } from 'node:stream';
import { lstatSync, readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { dirname, join } from 'node:path';
import { homedir } from 'node:os';
import { execFileSync } from 'node:child_process';
import { CodexHistoryReader } from './history-reader.js';
import { DesktopFrameDecoder, desktopFrameLimits } from './desktop-framing.js';
import { DesktopLiveWindow, type DesktopLiveObservation } from './desktop-live-window.js';
import { configuredPermissionSettings, permissionSettings } from './generation-settings.js';
import {
  loadWindowsDesktop,
  openWindowsDesktopPipe,
  windowsDesktopPipe,
  readMacDesktopVersion,
  type WindowsDesktopIdentity,
} from './desktop-platform.js';
import {
  CodexRejection,
  object,
  list,
  string,
  type WireObject,
  type RuntimeOptions,
} from './runtime.js';

// Experimental desktop coordination protocol, isolated from the public app-server RPC.
// This profile was inspected and verified against this desktop build, not promised by OpenAI.
export const desktopProfile = { version: '26.1002.52244', streamVersion: 11 };
export { desktopBuilds } from './desktop-platform.js';
const versions: Record<string, number> = {
  'thread-owner-discovery': 1,
  'thread-follower-start-turn': 2,
  'thread-follower-steer-turn': 1,
  'thread-follower-load-complete-history': 1,
  'thread-follower-interrupt-turn': 4,
  'thread-follower-command-approval-decision': 1,
  'thread-follower-file-approval-decision': 1,
  'thread-follower-permissions-request-approval-response': 1,
  'thread-follower-submit-mcp-server-elicitation-response': 1,
  'thread-follower-submit-user-input': 1,
  'thread-follower-update-thread-settings': 2,
  'thread-follower-read-model-settings': 1,
};

class DesktopRequestTimeout extends Error {
  constructor() {
    super('Desktop request outcome uncertain');
  }
}

/** No execution request has been written. Never used for response timeouts. */
export class DesktopCommandNotSubmitted extends CodexRejection {
  constructor(
    message: string,
    readonly ownerUnavailable = false,
  ) {
    super(message);
  }
}

/** Immer array-path patches; validate paths before touching cached desktop state. */
export function applyDesktopPatches(state: WireObject, patches: unknown[]): WireObject {
  // Clone only changed branches. A streamed token must not copy every old turn,
  // tool result and attachment in a multi-megabyte conversation.
  const next = { ...state };
  const copies = new WeakSet<object>([next]);
  for (const value of patches) {
    const patch = object(value);
    const path = list(patch.path);
    if (
      !path.length ||
      path.length > 128 ||
      path.some(
        (p) =>
          !(
            typeof p === 'string' ||
            (typeof p === 'number' && Number.isSafeInteger(p) && p >= 0)
          ) || ['__proto__', 'prototype', 'constructor'].includes(String(p)),
      )
    )
      throw new Error('Invalid desktop patch path');
    if (!['add', 'replace', 'remove'].includes(string(patch.op)))
      throw new Error('Invalid desktop patch operation');
    let parent: WireObject | unknown[] = next;
    for (const key of path.slice(0, -1)) {
      if (!Object.hasOwn(parent, String(key))) throw new Error('Missing desktop patch parent');
      let child: unknown = (parent as WireObject)[String(key)];
      if (!child || typeof child !== 'object') throw new Error('Invalid desktop patch parent');
      if (!copies.has(child)) {
        child = Array.isArray(child) ? child.slice() : { ...child };
        (parent as WireObject)[String(key)] = child;
        copies.add(child as object);
      }
      parent = child as WireObject | unknown[];
    }
    const key = path.at(-1)!;
    if (Array.isArray(parent)) {
      if (
        typeof key !== 'number' ||
        key > parent.length ||
        (patch.op !== 'add' && key === parent.length)
      )
        throw new Error('Invalid desktop array index');
      if (patch.op === 'add') parent.splice(key, 0, patch.value);
      else if (patch.op === 'remove') parent.splice(key, 1);
      else parent[key] = patch.value;
    } else if (patch.op === 'remove') delete parent[String(key)];
    else parent[String(key)] = patch.value;
  }
  return next;
}

export function desktopTurns(state: WireObject): WireObject[] {
  const history = object(object(state.turnHistory).history);
  if (object(state.turnHistory).kind !== 'canonical') return list(state.turns).map(object);
  const entities = object(history.entitiesByKey);
  return list(history.islands).flatMap((island) =>
    list(object(island).entries).map((entry) => object(entities[string(object(entry).value)])),
  );
}

function desktopStreamHead(state: WireObject) {
  const turnId = string(desktopTurns(state).at(-1)?.turnId);
  const inputId = string(state.latestTurnStartMessageId);
  return turnId && inputId ? { turnId, key: JSON.stringify([inputId, turnId]) } : undefined;
}

function continuedDesktopStream(previous: WireObject, next: WireObject) {
  const prior = desktopStreamHead(previous);
  const head = desktopStreamHead(next);
  if (
    !prior ||
    !head ||
    prior.key === head.key ||
    previous.latestTurnStartMessageId === next.latestTurnStartMessageId
  )
    return;
  const turns = desktopTurns(next);
  const anchor = turns.findIndex((turn) => turn.turnId === prior.turnId);
  if (
    anchor >= 0 &&
    anchor < turns.length - 1 &&
    ['completed', 'failed', 'interrupted'].includes(string(turns[anchor]?.status))
  )
    return prior;
}

function terminalDesktopHead(state: WireObject) {
  const head = desktopStreamHead(state);
  const turns = desktopTurns(state);
  const status = string(turns.at(-1)?.status);
  return head &&
    ['completed', 'failed', 'interrupted'].includes(status) &&
    ['idle', 'systemError'].includes(string(object(state.threadRuntimeStatus).type)) &&
    !turns.some((turn) => turn.status === 'inProgress')
    ? { ...head, status }
    : undefined;
}

function terminalDesktopReset(previous: WireObject, next: WireObject) {
  const prior = desktopStreamHead(previous);
  const head = terminalDesktopHead(next);
  return prior &&
    head &&
    prior.key === head.key &&
    desktopTurns(previous).at(-1)?.status === 'inProgress'
    ? head
    : undefined;
}

interface TerminalDesktopReset {
  status: string;
  retiredRevisions: Set<number>;
}

function retireRevision(reset: TerminalDesktopReset, revision: number): void {
  reset.retiredRevisions.add(revision);
  while (reset.retiredRevisions.size > 64)
    reset.retiredRevisions.delete(reset.retiredRevisions.values().next().value!);
}

export interface ProposedPlan {
  id: string;
  turnId: string;
  text: string;
}

// Plan confirmation is a synthetic desktop item, separate from blocking tool questions.
export function desktopProposedPlan(state: WireObject): ProposedPlan | undefined {
  const turn = desktopTurns(state).at(-1);
  if (!turn || turn.status !== 'completed' || object(state.threadRuntimeStatus).type === 'active')
    return;
  const item = list(turn.items)
    .map(object)
    .findLast((v) => v.type === 'planImplementation');
  if (item) {
    if (item.isCompleted !== false || item.turnId !== turn.turnId) return;
    return { id: string(item.id), turnId: string(item.turnId), text: string(item.planContent) };
  }
  const request = list(state.requests)
    .map(object)
    .findLast(
      (v) =>
        v.method === 'item/plan/requestImplementation' &&
        object(v.params).threadId === state.id &&
        object(v.params).turnId === turn.turnId,
    );
  if (request)
    return {
      id: string(request.id),
      turnId: string(turn.turnId),
      text: string(object(request.params).planContent),
    };
}

// The desktop keeps the submitted input in turn.params before the app-server
// defaults it. Its renderer reads text_elements.some() directly from that cache.
function desktopInput(value: unknown): WireObject[] {
  return list(value).map((value) => {
    const input = object(value);
    if (input.type !== 'text') return input;
    if (input.text_elements !== undefined && !Array.isArray(input.text_elements))
      throw new DesktopCommandNotSubmitted('Invalid desktop text elements');
    return { ...input, text_elements: input.text_elements ?? [] };
  });
}

export class DesktopIpc {
  private socket?: Duplex;
  windowsIdentity?: WindowsDesktopIdentity;
  private clientId = '';
  private pending = new Map<
    string,
    { resolve: (v: WireObject) => void; reject: (e: Error) => void; timer: NodeJS.Timeout }
  >();
  private owners = new Map<string, string>();
  private states = new Map<string, { revision: number; state: WireObject }>();
  // One owner can publish both a retired cached stream and its replacement.
  // Revisions are comparable within a stream, not across those two histories.
  private retiredStreams = new Map<string, Map<string, number>>();
  // A stop can rebuild the same input/turn at revision 2. Its terminal
  // evidence must also fence the old running stream, whose head ID is equal.
  private terminalResets = new Map<string, Map<string, TerminalDesktopReset>>();
  private waiters = new Map<string, Set<() => void>>();
  private follows = new Map<
    string,
    { work: Promise<WireObject | undefined>; timeoutMs: number; revalidateOwner: boolean }
  >();
  private skippedFrames = 0;
  private snapshotReads = new Map<string, number>();
  private limitedReads = new Map<string, number>();
  private snapshotReceivedAt = new Map<string, number>();
  private snapshotRetryAt = new Map<string, number>();
  private liveWindows = new Map<string, DesktopLiveWindow>();
  private observedOwners = new Map<string, number>();
  private currentModels = new Map<
    string,
    { owner: string; checkedAt: number; model: string; effort: string | null }
  >();
  private modelReads = new Map<string, Promise<void>>();
  private modelEpoch = 0;
  ready = false;
  onSnapshot: (id: string, state: WireObject) => void = () => {};
  onUnavailable: (id: string) => void = () => {};
  onDiscovered: (id: string) => void = () => {};
  onObservation: (id: string, observation: DesktopLiveObservation) => void = () => {};
  onModelSettings: (id: string) => void = () => {};
  onDisconnect: () => void = () => {};

  constructor(
    private readonly path: string,
    private readonly frameOptions: { drainTimeoutMs?: number } = {},
  ) {}
  async connect(): Promise<void> {
    if (process.platform !== 'win32') {
      const uid = process.getuid?.();
      const dir = lstatSync(dirname(this.path)),
        file = lstatSync(this.path);
      if (
        uid === undefined ||
        dir.uid !== uid ||
        file.uid !== uid ||
        !dir.isDirectory() ||
        !file.isSocket() ||
        dir.mode & 0o022 ||
        file.mode & 0o077
      )
        throw new Error('Desktop IPC must be a private socket owned by this user');
    } else if (this.path !== windowsDesktopPipe || !this.windowsIdentity) {
      throw new Error('Verified Windows desktop named pipe required');
    }
    const socket =
      process.platform === 'win32'
        ? await openWindowsDesktopPipe(this.windowsIdentity!)
        : connect(this.path);
    this.socket = socket;
    const frames = new DesktopFrameDecoder(
      (body) => {
        this.receive(object(JSON.parse(body.toString('utf8'))));
      },
      {
        ...this.frameOptions,
        onOversized: () => {
          // This is an opaque transport observation, never a target or acceptance proof.
          this.skippedFrames++;
        },
        onError: () => socket.destroy(),
      },
    );
    socket.on('data', (chunk: Buffer) => {
      if (socket !== this.socket || socket.destroyed) return;
      try {
        frames.push(chunk);
      } catch {
        frames.reset();
        socket.destroy();
      }
    });
    socket.on('error', () => {});
    socket.on('close', () => {
      frames.reset();
      if (socket !== this.socket) return;
      this.ready = false;
      for (const pending of this.pending.values()) {
        clearTimeout(pending.timer);
        pending.reject(new Error('Desktop disconnected; outcome uncertain'));
      }
      this.pending.clear();
      this.owners.clear();
      this.states.clear();
      this.retiredStreams.clear();
      this.terminalResets.clear();
      this.snapshotReads.clear();
      this.snapshotRetryAt.clear();
      this.liveWindows.clear();
      this.observedOwners.clear();
      this.currentModels.clear();
      this.modelEpoch++;
      for (const waiters of this.waiters.values()) for (const done of waiters) done();
      this.onDisconnect();
    });
    if (process.platform !== 'win32')
      await new Promise<void>((resolve, reject) => {
        socket.once('connect', resolve);
        socket.once('error', reject);
      });
    const initialized = await this.request('initialize', { clientType: 'codex-manage' });
    this.clientId = string(object(initialized.result).clientId);
    if (!this.clientId) throw new Error('Desktop IPC initialization failed');
    this.ready = true;
  }
  private write(message: WireObject, beforeExecutionWrite?: () => void): void {
    if (!this.socket || this.socket.destroyed)
      throw new DesktopCommandNotSubmitted('Desktop IPC unavailable');
    const bytes = Buffer.from(JSON.stringify(message));
    const header = Buffer.alloc(4);
    header.writeUInt32LE(bytes.length);
    const frame = Buffer.concat([header, bytes]);
    // No await or other request can interleave between the durable execution fence and write.
    beforeExecutionWrite?.();
    this.socket.write(frame);
  }
  private request(
    method: string,
    params: WireObject,
    targetClientId?: string,
    timeoutMs = 15_000,
    beforeExecutionWrite?: () => void,
  ): Promise<WireObject> {
    const requestId = randomUUID();
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(requestId);
        reject(new DesktopRequestTimeout());
      }, timeoutMs);
      this.pending.set(requestId, { resolve, reject, timer });
      try {
        this.write(
          {
            type: 'request',
            requestId,
            sourceClientId: this.clientId,
            version: versions[method] ?? 0,
            method,
            params,
            timeoutMs,
            ...(targetClientId ? { targetClientId } : {}),
          },
          beforeExecutionWrite,
        );
      } catch (error) {
        clearTimeout(timer);
        this.pending.delete(requestId);
        reject(error instanceof Error ? error : new Error('IPC write failed'));
      }
    });
  }
  private receive(message: WireObject): void {
    if (message.type === 'client-discovery-request') {
      // We never claim ownership or handle other clients' commands.
      this.write({
        type: 'client-discovery-response',
        requestId: message.requestId,
        response: { canHandle: false },
      });
    } else if (message.type === 'response') {
      const call = this.pending.get(string(message.requestId));
      if (!call) return;
      clearTimeout(call.timer);
      this.pending.delete(string(message.requestId));
      if (message.resultType === 'success') call.resolve(message);
      else call.reject(new CodexRejection(string(message.error) || 'Desktop rejected request'));
    } else if (message.type === 'broadcast') {
      const params = object(message.params);
      if (message.method === 'thread-read-state-changed') {
        // Read broadcasts carry account context we do not own. Treat them only
        // as a refresh hint; the verified original owner supplies the value.
        const id = string(params.conversationId);
        if (
          message.version === 3 &&
          params.hostId === 'local' &&
          id &&
          typeof params.hasUnreadTurn === 'boolean'
        ) {
          this.refreshSnapshot(id);
        }
        return;
      }
      if (message.method === 'client-status-changed' && params.status === 'disconnected') {
        this.modelEpoch++;
        for (const [id, settings] of this.currentModels)
          if (settings.owner === params.clientId) {
            this.currentModels.delete(id);
            this.onModelSettings(id);
          }
        for (const [id, owner] of this.owners)
          if (owner === params.clientId) {
            this.owners.delete(id);
            this.states.delete(id);
            this.retiredStreams.delete(id);
            this.terminalResets.delete(id);
            this.liveWindows.delete(id);
            this.observedOwners.delete(id);
            this.onUnavailable(id);
          }
      }
      if (message.method !== 'thread-stream-state-changed' || params.hostId !== 'local') return;
      const id = string(params.conversationId),
        change = object(params.change);
      if (this.owners.get(id) !== message.sourceClientId) {
        if (
          message.version === desktopProfile.streamVersion &&
          /^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(id)
        )
          // A different source is only a discovery hint. The original desktop
          // may have transferred ownership without disconnecting its old peer.
          this.onDiscovered(id);
        return;
      }
      if (message.version !== desktopProfile.streamVersion) {
        this.invalidate(id);
        return;
      }
      const revision = change.revision;
      if (typeof revision !== 'number' || !Number.isSafeInteger(revision)) {
        this.invalidate(id);
        return;
      }
      const old = this.states.get(id);
      let terminalReset: ReturnType<typeof terminalDesktopReset>;
      if (change.type === 'patches' && !old) {
        const window = this.liveWindows.get(id) ?? new DesktopLiveWindow();
        this.liveWindows.set(id, window);
        while (this.liveWindows.size > 32) {
          const oldest = this.liveWindows.keys().next().value!;
          this.liveWindows.delete(oldest);
          this.observedOwners.delete(oldest);
        }
        const observation = window.apply(change);
        if (observation) this.onObservation(id, observation);
        this.following(id);
        return;
      }
      if (change.type === 'snapshot') {
        const state = object(change.conversationState);
        if (state.id !== id || state.hostId !== 'local') {
          this.invalidate(id);
          return;
        }
        const head = desktopStreamHead(state);
        const reset = head && this.terminalResets.get(id)?.get(head.key);
        if (reset && terminalDesktopHead(state)?.status !== reset.status) {
          retireRevision(reset, revision);
          return;
        }
        const retired = this.retiredStreams.get(id);
        if (head && retired?.has(head.key)) {
          retired.set(head.key, Math.max(retired.get(head.key)!, revision));
          return;
        }
        if (old) {
          // A replacement may have evicted old pages. Exact continuity through
          // the previous tip, now terminal and followed by a new input/turn,
          // proves forward progress without comparing timestamps or text.
          if (revision < old.revision) {
            terminalReset = terminalDesktopReset(old.state, state);
            if (!terminalReset && !continuedDesktopStream(old.state, state)) return;
          }
        }
      } else if (change.type === 'patches' && old?.revision !== change.baseRevision) {
        const reset = [...(this.terminalResets.get(id)?.values() ?? [])].find((value) =>
          value.retiredRevisions.has(change.baseRevision as number),
        );
        if (reset && typeof change.baseRevision === 'number' && revision > change.baseRevision) {
          retireRevision(reset, revision);
          return;
        }
        const retired = this.retiredStreams.get(id);
        const stream = [...(retired ?? [])].find(([, value]) => value === change.baseRevision);
        if (stream && typeof change.baseRevision === 'number' && revision > change.baseRevision) {
          retired!.set(stream[0], revision);
          return;
        }
      }
      // Following refreshes can overlap an already delivered stream batch.
      // Ignore stale/duplicate batches, without destroying a verified owner.
      if (old && change.type !== 'snapshot' && revision < old.revision) return;
      if (old && change.type === 'patches' && revision === old.revision) return;
      let state: WireObject;
      if (change.type === 'snapshot') state = object(change.conversationState);
      else if (
        change.type === 'patches' &&
        old !== undefined &&
        old?.revision === change.baseRevision &&
        revision > old.revision
      ) {
        try {
          state = applyDesktopPatches(old.state, list(change.patches));
        } catch {
          this.invalidate(id);
          this.following(id);
          return;
        }
      } else {
        this.invalidate(id);
        this.following(id);
        return;
      }
      if (state.id !== id || state.hostId !== 'local') {
        this.invalidate(id);
        return;
      }
      const resets = this.terminalResets.get(id);
      const head = resets && desktopStreamHead(state);
      const reset = head && resets.get(head.key);
      if (reset && terminalDesktopHead(state)?.status !== reset.status) {
        retireRevision(reset, revision);
        return;
      }
      if (terminalReset && old) {
        const resets = this.terminalResets.get(id) ?? new Map<string, TerminalDesktopReset>();
        resets.set(terminalReset.key, {
          status: terminalReset.status,
          retiredRevisions: new Set([old.revision]),
        });
        while (resets.size > 32) resets.delete(resets.keys().next().value!);
        this.terminalResets.set(id, resets);
      }
      const continued = old && continuedDesktopStream(old.state, state);
      if (continued) {
        const streams = this.retiredStreams.get(id) ?? new Map<string, number>();
        streams.set(continued.key, old.revision);
        this.retiredStreams.set(id, streams);
      }
      this.states.set(id, { revision, state });
      // A newer owner stream supersedes a prior small settings read.
      this.currentModels.delete(id);
      const existingWindow = this.liveWindows.get(id);
      const window = existingWindow ?? new DesktopLiveWindow();
      if (change.type === 'snapshot' || !existingWindow) window.seed(state, revision);
      else window.apply(change);
      this.liveWindows.delete(id);
      this.liveWindows.set(id, window);
      while (this.liveWindows.size > 32) {
        const oldest = this.liveWindows.keys().next().value!;
        this.liveWindows.delete(oldest);
        this.observedOwners.delete(oldest);
      }
      this.snapshotReceivedAt.set(id, Date.now());
      this.snapshotRetryAt.delete(id);
      this.limitedReads.delete(id);
      this.onSnapshot(id, state);
      for (const done of this.waiters.get(id) ?? []) done();
    }
  }
  private invalidate(id: string): void {
    this.states.delete(id);
    this.onUnavailable(id);
  }
  private deferSnapshot(id: string, milliseconds: number): void {
    this.snapshotRetryAt.set(id, Date.now() + milliseconds);
    while (this.snapshotRetryAt.size > 2048)
      this.snapshotRetryAt.delete(this.snapshotRetryAt.keys().next().value!);
  }
  private following(id: string, force = false): void {
    const owner = this.owners.get(id);
    if (!owner || !this.ready) return;
    if ((this.limitedReads.get(id) ?? 0) > Date.now()) return;
    if (!force && !this.states.has(id) && (this.snapshotRetryAt.get(id) ?? 0) > Date.now()) return;
    const started = this.snapshotReads.get(id);
    if (started !== undefined && started !== this.skippedFrames && !this.states.has(id)) return;
    // A missing base plus every arriving patch must not request another giant
    // snapshot. Explicit command revalidation still gets its own fresh read.
    if (!this.states.has(id)) this.deferSnapshot(id, 5000);
    this.write({
      type: 'broadcast',
      method: 'thread-stream-following-changed',
      version: 1,
      sourceClientId: this.clientId,
      targetClientIds: [owner],
      params: { hostId: 'local', conversationId: id, following: true },
    });
  }
  hasOwner(id: string): boolean {
    return this.ready && this.states.has(id) && this.owners.has(id);
  }
  /** Refresh original desktop metadata after confirming an explicitly opened thread is unarchived.
   * This versioned lifecycle hint neither claims ownership nor submits a model turn.
   */
  refreshUnarchivedThread(id: string): void {
    if (!this.ready || !/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(id))
      throw new DesktopCommandNotSubmitted('原桌面元数据同步目标不可用');
    this.write({
      type: 'broadcast',
      method: 'thread-unarchived',
      version: 1,
      sourceClientId: this.clientId,
      params: { hostId: 'local', conversationId: id },
    });
  }
  state(id: string): WireObject | undefined {
    return this.states.get(id)?.state;
  }
  modelSettings(id: string): { model: string; effort: string | null } | undefined {
    const value = this.currentModels.get(id);
    if (!this.ready || !value || Date.now() - value.checkedAt >= 10_000) return;
    if (this.owners.has(id) && this.owners.get(id) !== value.owner) return;
    return { model: value.model, effort: value.effort };
  }
  /** Current owner settings are readable even when its full transcript is too large.
   * This read never opens a thread, claims execution ownership, or grants control.
   */
  async refreshModelSettings(id: string): Promise<void> {
    if (!this.ready) return;
    if (Date.now() - (this.currentModels.get(id)?.checkedAt ?? 0) < 5000) return;
    const pending = this.modelReads.get(id);
    if (pending) return pending;
    const socket = this.socket;
    const epoch = this.modelEpoch;
    const work = (async () => {
      try {
        const discovery = await this.request(
          'thread-owner-discovery',
          { hostId: 'local', conversationId: id },
          undefined,
          2000,
        );
        const owner = string(discovery.handledByClientId);
        if (!owner || object(discovery.result).supportsUntrustedAppInput !== true)
          throw new Error('Model settings owner unavailable');
        if (this.owners.has(id) && this.owners.get(id) !== owner)
          throw new Error('Model settings owner changed');
        const state = this.state(id);
        const result = await this.request(
          'thread-follower-read-model-settings',
          { conversationId: id },
          owner,
          2000,
        );
        if (
          !this.ready ||
          this.socket !== socket ||
          this.modelEpoch !== epoch ||
          result.handledByClientId !== owner ||
          result.method !== 'thread-follower-read-model-settings' ||
          (this.owners.has(id) && this.owners.get(id) !== owner)
        )
          throw new Error('Model settings response mismatch');
        // Do not overwrite a stream that changed while the read was pending.
        if (this.state(id) !== state) return;
        this.recordModelSettings(id, owner, object(object(result.result).settings));
      } catch {
        if (this.currentModels.delete(id)) this.onModelSettings(id);
      }
    })();
    this.modelReads.set(id, work);
    try {
      await work;
    } finally {
      if (this.modelReads.get(id) === work) this.modelReads.delete(id);
    }
  }
  private recordModelSettings(id: string, owner: string, settings: WireObject): void {
    if (
      !string(settings.model) ||
      !(typeof settings.reasoningEffort === 'string' || settings.reasoningEffort === null)
    )
      throw new Error('Incomplete model settings');
    const previous = this.modelSettings(id);
    this.currentModels.delete(id);
    this.currentModels.set(id, {
      owner,
      checkedAt: Date.now(),
      model: string(settings.model),
      effort: settings.reasoningEffort as string | null,
    });
    while (this.currentModels.size > 128)
      this.currentModels.delete(this.currentModels.keys().next().value!);
    if (previous?.model !== settings.model || previous?.effort !== settings.reasoningEffort)
      this.onModelSettings(id);
  }
  observation(id: string): DesktopLiveObservation | undefined {
    if (!this.ready || Date.now() - (this.observedOwners.get(id) ?? 0) >= 10_000) return;
    return this.liveWindows.get(id)?.observation();
  }
  bindObservation(id: string, messages: { id: string; runId?: string }[]): void {
    this.liveWindows.get(id)?.bind(messages);
  }
  /** Small, read-only owner heartbeat. Does not grant hasOwner/control status. */
  async refreshObservation(id: string): Promise<void> {
    if (!this.liveWindows.has(id) || !this.ready || this.hasOwner(id)) return;
    if (Date.now() - (this.observedOwners.get(id) ?? 0) < 5000) return;
    const previous = this.owners.get(id);
    try {
      const discovery = await this.request(
        'thread-owner-discovery',
        { hostId: 'local', conversationId: id },
        undefined,
        2000,
      );
      const owner = string(discovery.handledByClientId);
      if (
        !owner ||
        owner !== previous ||
        object(discovery.result).supportsUntrustedAppInput !== true
      )
        throw new Error('Observation owner changed');
      const result = await this.request(
        'thread-follower-read-model-settings',
        { conversationId: id },
        owner,
        2000,
      );
      if (
        this.owners.get(id) !== owner ||
        result.handledByClientId !== owner ||
        result.method !== 'thread-follower-read-model-settings' ||
        !object(result.result).settings
      )
        throw new Error('Observation owner response mismatch');
      this.observedOwners.set(id, Date.now());
      this.recordModelSettings(id, owner, object(object(result.result).settings));
    } catch {
      this.observedOwners.delete(id);
      this.liveWindows.delete(id);
    }
  }
  refreshSnapshot(id: string, force = true): void {
    // Display read state can change without a stream patch reaching followers.
    // Re-request the original owner's snapshot; this never opens or resumes it.
    if (
      this.hasOwner(id) &&
      (force || Date.now() - (this.snapshotReceivedAt.get(id) ?? 0) >= 10_000)
    )
      this.following(id);
  }
  /** Read through the exact owner; loading old pages never submits model input. */
  async completeHistory(id: string): Promise<WireObject> {
    const result = await this.command(id, 'thread-follower-load-complete-history', {});
    const revision = result.revision ?? object(result.result).revision;
    if (typeof revision !== 'number' || !Number.isSafeInteger(revision))
      throw new Error('Desktop history revision unavailable');
    if ((this.states.get(id)?.revision ?? -1) < revision) {
      await new Promise<void>((resolve, reject) => {
        const waiting = this.waiters.get(id) ?? new Set();
        const done = () => {
          if ((this.states.get(id)?.revision ?? -1) < revision) return;
          clearTimeout(timer);
          waiting.delete(done);
          resolve();
        };
        const timer = setTimeout(() => {
          waiting.delete(done);
          reject(new Error('Desktop complete history snapshot unavailable'));
        }, 5000);
        waiting.add(done);
        this.waiters.set(id, waiting);
        done();
      });
    }
    const state = this.state(id);
    if (!this.hasOwner(id) || state?.id !== id || state.hostId !== 'local')
      throw new Error('Desktop history owner unavailable');
    return state;
  }
  async follow(
    id: string,
    discoveryTimeoutMs = 15_000,
    revalidateOwner = false,
  ): Promise<WireObject | undefined> {
    if ((this.limitedReads.get(id) ?? 0) > Date.now() && !this.hasOwner(id))
      return this.snapshotUnavailable();
    if (!revalidateOwner && !this.hasOwner(id) && (this.snapshotRetryAt.get(id) ?? 0) > Date.now())
      return undefined;
    if (!revalidateOwner && this.hasOwner(id)) return this.state(id);
    const pending = this.follows.get(id);
    if (pending) {
      const state = await pending.work;
      if (revalidateOwner && !pending.revalidateOwner)
        return this.follow(id, discoveryTimeoutMs, true);
      if (state || pending.timeoutMs >= discoveryTimeoutMs) return state;
      // A catalog probe has a shorter budget than an explicit open. After that
      // read-only probe settles, give the selected chat its own discovery budget.
      return this.follow(id, discoveryTimeoutMs, revalidateOwner);
    }
    const work = this.followNow(id, discoveryTimeoutMs, revalidateOwner);
    this.follows.set(id, { work, timeoutMs: discoveryTimeoutMs, revalidateOwner });
    try {
      return await work;
    } finally {
      if (this.follows.get(id)?.work === work) this.follows.delete(id);
    }
  }
  private async followNow(
    id: string,
    discoveryTimeoutMs: number,
    freshSnapshot: boolean,
  ): Promise<WireObject | undefined> {
    let owner: WireObject;
    try {
      owner = await this.request(
        'thread-owner-discovery',
        { hostId: 'local', conversationId: id },
        undefined,
        discoveryTimeoutMs,
      );
    } catch (error) {
      this.owners.delete(id);
      this.liveWindows.delete(id);
      this.observedOwners.delete(id);
      this.retiredStreams.delete(id);
      this.terminalResets.delete(id);
      this.invalidate(id);
      if (error instanceof CodexRejection && error.message === 'no-client-found') return undefined;
      // Discovery does not execute a command. No reply means no verified owner;
      // persisted history can still be read, without granting runtime control.
      if (error instanceof DesktopRequestTimeout) return undefined;
      throw error;
    }
    const ownerId = string(owner.handledByClientId);
    if (!ownerId || object(owner.result).supportsUntrustedAppInput !== true) {
      this.owners.delete(id);
      this.retiredStreams.delete(id);
      this.terminalResets.delete(id);
      this.invalidate(id);
      throw new CodexRejection('Desktop owner profile unsupported');
    }
    // Archiving can unload a single thread while its desktop client stays
    // connected. A cached snapshot is usable only after fresh owner discovery.
    if (!freshSnapshot && this.owners.get(id) === ownerId && this.hasOwner(id))
      return this.state(id);
    // The inspected desktop protocol republishes its full current snapshot
    // for following=true even when this client is already subscribed. An owner
    // discovery reply alone cannot refresh a stale running/completed state.
    if (this.owners.get(id) !== ownerId) {
      this.states.delete(id);
      this.retiredStreams.delete(id);
      this.terminalResets.delete(id);
      this.liveWindows.delete(id);
      this.observedOwners.delete(id);
    }
    this.owners.set(id, ownerId);
    let received = false;
    const skippedBefore = this.skippedFrames;
    this.snapshotReads.set(id, skippedBefore);
    try {
      await new Promise<void>((resolve, reject) => {
        const done = () => {
          clearTimeout(timer);
          this.waiters.get(id)?.delete(snapshotReceived);
          resolve();
        };
        const timer = setTimeout(done, 5000);
        const waiting = this.waiters.get(id) ?? new Set();
        const snapshotReceived = () => {
          received = true;
          waiting.delete(snapshotReceived);
          done();
        };
        waiting.add(snapshotReceived);
        this.waiters.set(id, waiting);
        try {
          this.following(id, true);
        } catch (error) {
          clearTimeout(timer);
          waiting.delete(snapshotReceived);
          reject(error);
        }
      });
    } finally {
      this.snapshotReads.delete(id);
    }
    if (!received) {
      this.invalidate(id);
      this.deferSnapshot(id, 30_000);
      if (this.skippedFrames !== skippedBefore) {
        this.limitedReads.delete(id);
        this.limitedReads.set(id, Date.now() + 30_000);
        while (this.limitedReads.size > 2048)
          this.limitedReads.delete(this.limitedReads.keys().next().value!);
        return this.snapshotUnavailable();
      }
    }
    return this.state(id);
  }
  private async snapshotUnavailable(): Promise<never> {
    // Lazy access avoids adding execution/session initialization to an IPC-only import.
    const { RuntimeError } = await import('./runtime-session.js');
    throw new RuntimeError(
      503,
      `此对话未取得可用桌面快照，期间跳过了超过 ${desktopFrameLimits.materializedBytes / 1024 / 1024} MiB 的帧；运行状态暂不可读取，请稍后重试`,
    );
  }
  async command(
    id: string,
    method: string,
    params: WireObject,
    beforeExecutionWrite?: () => void,
  ): Promise<WireObject> {
    let state: WireObject | undefined;
    try {
      state = await this.follow(id, 15_000, true);
    } catch (error) {
      throw new DesktopCommandNotSubmitted(
        error instanceof Error ? error.message : 'Desktop owner discovery failed',
        true,
      );
    }
    if (!state) throw new DesktopCommandNotSubmitted('请先在 Codex 中打开此对话，再刷新手机', true);
    const owner = this.owners.get(id)!;
    let result: WireObject;
    try {
      result = await this.request(
        method,
        { ...params, conversationId: id },
        owner,
        15_000,
        beforeExecutionWrite,
      );
    } catch (error) {
      if (
        error instanceof CodexRejection &&
        ['no-client-found', 'client-disconnected', 'client-not-found'].includes(error.message)
      ) {
        this.owners.delete(id);
        this.retiredStreams.delete(id);
        this.terminalResets.delete(id);
        this.invalidate(id);
      }
      throw error;
    }
    if (result.handledByClientId !== owner || result.method !== method)
      throw new Error('Desktop owner response mismatch; outcome uncertain');
    return object(result.result);
  }
  close(): void {
    this.socket?.destroy();
  }
}

export class DesktopRuntime {
  readonly ipc: DesktopIpc;
  readonly reader: CodexHistoryReader;
  version = '';
  onEvent: (method: string, params: WireObject) => void = () => {};
  onRequest: (id: string | number, method: string, params: WireObject) => void = () => {};
  onDisconnect: () => void = () => {};
  private requests = new Map<
    string,
    { threadId: string; method: string; requestId: unknown; fingerprint: string }
  >();
  constructor(
    private readonly options: RuntimeOptions,
    private readonly hostId: string,
    name: string,
  ) {
    this.ipc = new DesktopIpc(
      options.socket ??
        (process.platform === 'win32'
          ? windowsDesktopPipe
          : join(homedir(), '.codex', 'ipc', 'ipc.sock')),
    );
    this.reader = new CodexHistoryReader(hostId, name, options.executable);
    this.ipc.onSnapshot = (threadId, state) =>
      this.onEvent('desktop/snapshot', { threadId, state });
    this.ipc.onUnavailable = (threadId) => this.onEvent('desktop/unavailable', { threadId });
    this.ipc.onDiscovered = (threadId) => this.onEvent('desktop/discovered', { threadId });
    this.ipc.onObservation = (threadId, observation) =>
      this.onEvent('desktop/observation', { threadId, observation });
    this.ipc.onModelSettings = (threadId) => this.onEvent('desktop/model-settings', { threadId });
    this.ipc.onDisconnect = () => this.onDisconnect();
    this.reader.onDisconnect = () => this.ipc.close();
  }
  get ready(): boolean {
    return this.ipc.ready && this.reader.ready;
  }
  /** Read this computer's desktop choice fresh; never copy another Host's settings. */
  async defaultPermissions(cwd: string): Promise<WireObject> {
    const home =
      process.platform === 'win32'
        ? (process.env.CODEX_HOME ?? join(homedir(), '.codex'))
        : dirname(dirname(this.options.socket ?? join(homedir(), '.codex', 'ipc', 'ipc.sock')));
    let atoms: WireObject = {};
    try {
      const state = object(
        JSON.parse(readFileSync(join(home, '.codex-global-state.json'), 'utf8')),
      );
      atoms = object(state['electron-persisted-atom-state']);
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT')
        throw new DesktopCommandNotSubmitted('目标电脑的默认权限配置无法读取');
    }
    const selection = object(atoms['permission-selection-by-host-id:local']);
    const mode =
      selection.kind === 'agent-mode'
        ? string(selection.agentMode)
        : selection.kind
          ? ''
          : string(object(atoms['agent-mode-by-host-id']).local);
    if (['read-only', 'guardian-approvals', 'full-access'].includes(mode))
      return permissionSettings(mode, cwd);
    if (mode === 'auto')
      return { ...permissionSettings('guardian-approvals', cwd), approvalsReviewer: 'user' };
    if (selection.kind === 'profile')
      throw new DesktopCommandNotSubmitted('电脑使用自定义权限配置，请先在手机明确选择权限模式');
    try {
      const result = await this.reader.read('config/read', { cwd, includeLayers: false });
      return configuredPermissionSettings(object(result.config), cwd);
    } catch {
      throw new DesktopCommandNotSubmitted('目标电脑的默认权限配置无法确认');
    }
  }
  /** Service readiness without selecting any ordinary chat as a controller. */
  async desktopAvailable(): Promise<boolean> {
    if (!this.ready) return false;
    try {
      if (process.platform === 'win32') {
        const identity = this.ipc.windowsIdentity;
        if (!identity) return false;
        process.kill(identity.pid, 0);
        return true;
      }
      if (process.platform !== 'darwin') return false;
      const processes = execFileSync('/bin/ps', ['-axo', 'comm='], {
        encoding: 'utf8',
        timeout: 3000,
        maxBuffer: 2 * 1024 * 1024,
      });
      return processes
        .split('\n')
        .some((value) => value.trim() === '/Applications/ChatGPT.app/Contents/MacOS/ChatGPT');
    } catch {
      return false;
    }
  }
  async connect(): Promise<void> {
    // Collect optional acceptance metadata without checking build compatibility.
    let actual: string;
    if (process.platform === 'win32') {
      if (!this.options.windowsDesktopConfig)
        throw new Error('CODEX_DESKTOP_WINDOWS_CONFIG required');
      const windows = loadWindowsDesktop(this.options.windowsDesktopConfig, this.hostId, {
        executable: this.options.executable,
      });
      await this.reader.configureExecutable(windows.executable!);
      // Other metadata helpers share this options object, but resolve it only
      // when opening their next connection. Private startup files stay intact.
      this.options.executable = windows.executable!;
      actual = windows.archive.version;
      this.ipc.windowsIdentity = windows.identity;
    } else {
      actual = readMacDesktopVersion();
    }
    this.version = actual;
    if (!this.reader.ready) await this.reader.connect();
    try {
      await this.ipc.connect();
    } catch (error) {
      this.reader.close();
      throw error;
    }
  }
  async call(
    method: string,
    params: WireObject = {},
    beforeExecutionWrite?: () => void,
  ): Promise<WireObject> {
    const threadId = string(params.threadId);
    if (method === 'thread/resume') {
      const state = await this.ipc.follow(threadId);
      if (!state) throw new CodexRejection('请先在 Codex 中打开此对话，再刷新手机');
      return {
        thread: {
          id: threadId,
          turns: desktopTurns(state).map((turn) => ({ ...turn, id: turn.turnId })),
        },
      };
    }
    if (['thread/items/list', 'model/list'].includes(method))
      return this.reader.read(method, params);
    if (method === 'turn/start') {
      const state = this.ipc.state(threadId);
      const last = desktopTurns(state ?? {}).at(-1);
      if (last?.status === 'inProgress' || object(state?.threadRuntimeStatus).type === 'active')
        throw new DesktopCommandNotSubmitted('当前桌面任务进行中');
      const { collaborationMode: explicitMode, expectedPlanId, ...request } = params;
      if (
        !['permissions', 'sandboxPolicy', 'approvalPolicy', 'approvalsReviewer'].some(
          (key) => request[key] !== undefined,
        )
      )
        Object.assign(request, await this.defaultPermissions(string(state?.cwd)));
      const inheritedMode = string(
        object(
          object(state?.latestThreadSettings).collaborationMode ?? state?.latestCollaborationMode,
        ).mode,
      );
      const requestedMode =
        explicitMode ??
        ((params.model || params.effort) && ['plan', 'default'].includes(inheritedMode)
          ? inheritedMode
          : undefined);
      if (expectedPlanId && desktopProposedPlan(state ?? {})?.id !== expectedPlanId)
        throw new DesktopCommandNotSubmitted('计划已变化或已在桌面处理，请刷新后重试');
      let collaborationMode: WireObject | undefined;
      if (typeof requestedMode === 'string') {
        if (!['plan', 'default'].includes(requestedMode))
          throw new DesktopCommandNotSubmitted('无效计划模式');
        const settings = object(state?.latestThreadSettings);
        const prior = object(settings.collaborationMode ?? state?.latestCollaborationMode);
        const model =
          string(params.model) ||
          string(object(prior.settings).model) ||
          string(settings.model) ||
          string(state?.latestModel) ||
          string(object(last?.params).model);
        if (!model) throw new DesktopCommandNotSubmitted('无法确认桌面模型，请刷新后重试');
        collaborationMode = {
          mode: requestedMode,
          settings: {
            model,
            reasoning_effort:
              params.effort !== undefined
                ? params.effort
                : settings.effort !== undefined
                  ? settings.effort
                  : state?.latestReasoningEffort !== undefined
                    ? state.latestReasoningEffort
                    : (object(prior.settings).reasoning_effort ?? null),
            developer_instructions: null,
          },
        };
      }
      const result = await this.ipc.command(
        threadId,
        'thread-follower-start-turn',
        {
          turnStart: {
            request: {
              ...request,
              ...(collaborationMode ? { collaborationMode } : {}),
              input: desktopInput(params.input),
              attachments: [],
              commentAttachments: [],
            },
            context: { inheritThreadSettings: true },
          },
        },
        beforeExecutionWrite,
      );
      const value = object(result.result);
      return object(value.turn).id
        ? value
        : {
            ...value,
            turnId: value.turnId ?? desktopTurns(this.ipc.state(threadId) ?? {}).at(-1)?.turnId,
          };
    }
    if (method === 'turn/steer') {
      const state = this.ipc.state(threadId);
      const active = desktopTurns(state ?? {}).findLast((turn) => turn.status === 'inProgress');
      if (!params.expectedTurnId || active?.turnId !== params.expectedTurnId)
        throw new CodexRejection('当前任务已变化，追加指令未提交');
      const input = desktopInput(params.input);
      return object(
        (
          await this.ipc.command(
            threadId,
            'thread-follower-steer-turn',
            {
              input,
              clientUserMessageId: params.clientUserMessageId,
              attachments: [],
              // The renderer keeps this draft for presentation/recovery before the
              // server's canonical userMessage arrives. Its context is required.
              restoreMessage: {
                id: params.clientUserMessageId,
                text: input
                  .map((item) => string(object(item).text))
                  .filter(Boolean)
                  .join('\n'),
                cwd: state?.cwd,
                context: {
                  workspaceRoots: list(object(active.params).runtimeWorkspaceRoots),
                  collaborationMode: state?.latestCollaborationMode,
                  commentAttachments: [],
                },
              },
            },
            beforeExecutionWrite,
          )
        ).result,
      );
    }
    if (method === 'turn/interrupt')
      return this.ipc.command(threadId, 'thread-follower-interrupt-turn', {
        mode: 'user-stop',
        expectedTurnId: params.turnId,
      });
    throw new CodexRejection('当前桌面接入不支持此操作');
  }
  registerApproval(
    id: string,
    threadId: string,
    method: string,
    requestId: unknown,
    params: WireObject,
  ): void {
    this.requests.set(id, { threadId, method, requestId, fingerprint: JSON.stringify(params) });
  }
  async respond(id: string | number, response: WireObject): Promise<void> {
    const request = this.requests.get(String(id));
    if (!request) throw new CodexRejection('审批已处理或已过期');
    const validate = () => {
      if (
        !list(this.ipc.state(request.threadId)?.requests).some(
          (value) =>
            object(value).id === request.requestId &&
            object(value).completed !== true &&
            JSON.stringify(object(value).params) === request.fingerprint,
        )
      )
        throw new CodexRejection('审批已在桌面处理或已过期');
    };
    validate();
    await this.ipc.command(
      request.threadId,
      request.method,
      {
        requestId: request.requestId,
        ...([
          'thread-follower-submit-user-input',
          'thread-follower-submit-mcp-server-elicitation-response',
          'thread-follower-permissions-request-approval-response',
        ].includes(request.method)
          ? { response }
          : response),
      },
      validate,
    );
    const current = this.requests.get(String(id));
    if (
      current?.threadId === request.threadId &&
      current.method === request.method &&
      current.requestId === request.requestId &&
      current.fingerprint === request.fingerprint
    )
      this.requests.delete(String(id));
  }
  rejectRequest(): void {
    /* Desktop retains all unsupported approvals. */
  }
  close(): void {
    this.ipc.close();
    this.reader.close();
  }
}
