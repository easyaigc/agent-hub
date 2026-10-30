import { createHash } from 'node:crypto';
import {
  DesktopSuggestionStore,
  type RuntimeDesktopSuggestion,
} from './desktop-suggestion-store.js';

const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
const sampleId = /^thread\/sample:[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$/;
const sha256 = /^[a-f0-9]{64}$/;
const maximumRequests = 64;
const maximumFrameBytes = 128 * 1024;
const maximumPromptBytes = 64 * 1024;
const maximumBodyBytes = 64 * 1024;
const maximumBodyLength = 16_000;
const requestTimeout = 60_000;
const digest = (value: string) => createHash('sha256').update(value).digest('hex');

export interface DesktopNativeSampleProducer {
  epoch: string;
  pid: number;
  desktopPid: number;
  desktopStartedAt: number;
}

export interface DesktopNativeSampleCaptureOptions {
  store: DesktopSuggestionStore;
  targetKey: string;
  producer: DesktopNativeSampleProducer;
  now?: () => number;
  onError?: (code: string) => void;
}

/** The caller must bind its trusted native log reader to this producer epoch. */
export interface DesktopNativeSampleSafetyDecision {
  sourceThreadId: string;
  sourceTurnId: string;
  suppressed: boolean;
  at: number;
  evidenceSha256: string;
}

type JsonObject = Record<string, unknown>;
type RecordBase = Omit<RuntimeDesktopSuggestion, 'state' | 'result' | 'latestExtracted'>;
type InvalidState = Exclude<RuntimeDesktopSuggestion['state'], 'completed'>;
interface Candidate {
  text: string;
  contentSha256: string;
  resultSha256: string;
  responseId: string | null;
  receivedAt: number;
}
interface Request {
  base: RecordBase;
  terminal: boolean;
  responseSeen: boolean;
  candidate?: Candidate;
}

function object(value: unknown): value is JsonObject {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
function identifier(value: unknown): value is string {
  return typeof value === 'string' && value.length === 36 && uuid.test(value);
}
function requestIdentifier(value: unknown): value is string {
  return typeof value === 'string' && value.length === 50 && sampleId.test(value);
}
function timestamp(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}
function sameProducer(a: DesktopNativeSampleProducer, b: DesktopNativeSampleProducer): boolean {
  return (
    a.epoch === b.epoch &&
    a.pid === b.pid &&
    a.desktopPid === b.desktopPid &&
    a.desktopStartedAt === b.desktopStartedAt
  );
}

/** JSON.parse alone silently accepts duplicate keys, including escaped aliases. */
function uniqueJson(text: string): unknown {
  const value: unknown = JSON.parse(text);
  const stack: ({ keys: Set<string>; expectingKey: boolean } | null)[] = [];
  for (let index = 0; index < text.length; index++) {
    const character = text[index];
    if (character === '"') {
      const start = index++;
      while (index < text.length) {
        if (text[index] === '\\') index += 2;
        else if (text[index] === '"') break;
        else index++;
      }
      const frame = stack[stack.length - 1];
      if (frame?.expectingKey) {
        const key: string = JSON.parse(text.slice(start, index + 1));
        if (frame.keys.has(key)) throw new Error('duplicate_json_key');
        frame.keys.add(key);
        frame.expectingKey = false;
      }
    } else if (character === '{' || character === '[') {
      if (stack.length >= 64) throw new Error('json_depth_limit');
      stack.push(character === '{' ? { keys: new Set(), expectingKey: true } : null);
    } else if (character === '}' || character === ']') {
      stack.pop();
    } else if (character === ',') {
      const frame = stack[stack.length - 1];
      if (frame) frame.expectingKey = true;
    }
  }
  return value;
}

/**
 * A side tap on the original desktop's already-owned app-server byte stream.
 * No transport, RPC generation, Trace, executor, or renderer authority lives here.
 *
 * Retain at most 64 request identities for the whole producer epoch, including
 * terminal tombstones. Capacity exhaustion disables capture until a NEW epoch;
 * dropping tombstones would allow old responses/decisions to bind to new bodies.
 * A source/turn pair is therefore admitted only once per epoch, even sequentially.
 */
export class DesktopNativeSampleCapture {
  private readonly store: DesktopSuggestionStore;
  private readonly targetKey: string;
  private readonly producer: DesktopNativeSampleProducer;
  private readonly now: () => number;
  private readonly onError: ((code: string) => void) | undefined;
  private readonly requests = new Map<string, Request>();
  private readonly current = new Map<string, Request>();
  private sequence = 0;
  private epochObservedAt = 0;
  private lastObservedAt = 0;
  private disabled = false;
  private closed = false;
  private timer: ReturnType<typeof setTimeout> | undefined;

  get available(): boolean {
    return !this.closed && !this.disabled;
  }
  get hasPendingRequests(): boolean {
    return [...this.requests.values()].some((r) => !r.terminal);
  }

  constructor(options: DesktopNativeSampleCaptureOptions) {
    this.store = options.store;
    this.targetKey = options.targetKey;
    this.producer = Object.freeze({ ...options.producer });
    this.now = options.now ?? Date.now;
    this.onError = options.onError;
    try {
      const at = this.now();
      if (
        !timestamp(at) ||
        typeof this.targetKey !== 'string' ||
        !this.targetKey.trim() ||
        this.targetKey.length > 4096 ||
        typeof this.producer.epoch !== 'string' ||
        !this.producer.epoch.trim() ||
        this.producer.epoch.length > 512 ||
        !Number.isSafeInteger(this.producer.pid) ||
        this.producer.pid <= 0 ||
        !Number.isSafeInteger(this.producer.desktopPid) ||
        this.producer.desktopPid <= 0 ||
        !timestamp(this.producer.desktopStartedAt) ||
        this.producer.desktopStartedAt > at
      ) {
        this.disable('native_sample_configuration_invalid');
        return;
      }
      this.epochObservedAt = this.lastObservedAt = at;
    } catch {
      this.disable('native_sample_configuration_invalid');
    }
  }

  observeOutbound(line: string): void {
    this.observe((at) => {
      const frame = this.frame(line, at);
      if (!frame) return;
      if (frame.method === 'thread/sample/cancel') {
        const id = object(frame.params) ? frame.params.requestId : undefined;
        if (!requestIdentifier(id)) return;
        const request = this.requests.get(id);
        if (request) this.finish(request, 'cancelled', at, true);
        // A cancellation for another native request cannot invalidate this source.
        return;
      }
      if (frame.method !== 'thread/sample') return;
      const params = object(frame.params) ? frame.params : undefined;
      if (!params || params.turnTrigger !== 'composer_predictions') return;
      const threadId = params.threadId;
      const turnId = params.expectedTurnId;
      if (
        !identifier(threadId) ||
        !identifier(turnId) ||
        threadId.toLowerCase() === turnId.toLowerCase() ||
        !requestIdentifier(frame.id)
      ) {
        if (identifier(threadId)) this.revokeThread(threadId.toLowerCase(), at);
        else this.revokePending(at);
        return;
      }
      this.begin(frame.id, threadId.toLowerCase(), turnId.toLowerCase(), params, frame, at);
    });
  }

  observeInbound(line: string): void {
    this.observe((at) => {
      const frame = this.frame(line, at);
      if (!frame || Object.hasOwn(frame, 'method') || !requestIdentifier(frame.id)) return;
      const request = this.requests.get(frame.id);
      if (!request) {
        // An expired/evicted request can return late. It never selects another
        // source's current candidate and must not revoke independent requests.
        this.report('native_sample_unknown_response');
        return;
      }
      if (request.terminal) return;
      if (request.responseSeen) {
        this.finish(request, 'unverified', at);
        return;
      }
      if (this.currentRecord(request)?.state !== 'pending') {
        this.discard(request);
        return;
      }
      request.responseSeen = true;
      if (Object.hasOwn(frame, 'error')) {
        this.finish(request, 'failed', at);
        return;
      }
      const result = frame.result;
      if (
        (Object.hasOwn(frame, 'jsonrpc') && frame.jsonrpc !== '2.0') ||
        !object(result) ||
        typeof result.text !== 'string' ||
        (result.responseId !== undefined &&
          result.responseId !== null &&
          (typeof result.responseId !== 'string' || result.responseId.length > 512))
      ) {
        this.finish(request, 'unverified', at);
        return;
      }
      let parsed: unknown;
      try {
        parsed = uniqueJson(result.text);
      } catch {
        this.finish(request, 'unverified', at);
        return;
      }
      if (
        !object(parsed) ||
        Object.keys(parsed).length !== 1 ||
        !Object.hasOwn(parsed, 'suggestion') ||
        (parsed.suggestion !== null && typeof parsed.suggestion !== 'string')
      ) {
        this.finish(request, 'unverified', at);
        return;
      }
      if (parsed.suggestion === null || !parsed.suggestion.trim()) {
        this.finish(request, 'empty', at);
        return;
      }
      if (
        parsed.suggestion.length > maximumBodyLength ||
        Buffer.byteLength(parsed.suggestion, 'utf8') > maximumBodyBytes
      ) {
        this.finish(request, 'unverified', at);
        return;
      }
      const text = parsed.suggestion.trim();
      request.candidate = {
        text,
        contentSha256: digest(text),
        // Bind the original RPC result.text, before decoding or trimming it.
        resultSha256: digest(result.text),
        responseId: typeof result.responseId === 'string' ? result.responseId : null,
        receivedAt: at,
      };
    });
  }

  observeSafetyDecision(decision: DesktopNativeSampleSafetyDecision): void {
    this.observe((at) => {
      if (
        !object(decision) ||
        !identifier(decision.sourceThreadId) ||
        !identifier(decision.sourceTurnId)
      )
        return;
      const request = this.current.get(decision.sourceThreadId.toLowerCase());
      if (
        !request ||
        request.terminal ||
        request.base.sourceTurnId !== decision.sourceTurnId.toLowerCase()
      )
        return;
      // Evidence dated before this epoch/request belongs to an older observation.
      if (
        timestamp(decision.at) &&
        (decision.at < this.epochObservedAt || decision.at < request.base.requestedAt)
      )
        return;
      const candidate = request.candidate;
      if (
        !timestamp(decision.at) ||
        decision.at > at ||
        typeof decision.suppressed !== 'boolean' ||
        typeof decision.evidenceSha256 !== 'string' ||
        decision.evidenceSha256.length !== 64 ||
        !sha256.test(decision.evidenceSha256) ||
        !candidate ||
        candidate.receivedAt > decision.at
      ) {
        this.finish(request, 'unverified', at);
        return;
      }
      if (this.currentRecord(request)?.state !== 'pending') {
        this.discard(request);
        return;
      }
      if (decision.suppressed) {
        this.finish(request, 'suppressed', at);
        return;
      }
      const result: NonNullable<RuntimeDesktopSuggestion['result']> = {
        requestId: request.base.requestId,
        sourceTurnId: request.base.sourceTurnId,
        text: candidate.text,
        contentSha256: candidate.contentSha256,
        resultSha256: candidate.resultSha256,
        responseId: candidate.responseId,
        completedAt: candidate.receivedAt,
        safety: { decidedAt: decision.at, evidenceSha256: decision.evidenceSha256 },
      };
      this.discard(request);
      this.save(request.base, 'completed', at, result);
    });
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    this.clearTimer();
    try {
      if (!this.disabled) {
        const observed = this.now();
        const at = timestamp(observed)
          ? Math.max(this.lastObservedAt, observed)
          : this.lastObservedAt;
        // The runtime contract spells invalid leases as unverified, never completed.
        this.revokePending(at);
      }
    } catch {
      this.disable('native_sample_close_failed');
    } finally {
      this.requests.clear();
      this.current.clear();
    }
  }

  private begin(
    id: string,
    sourceThreadId: string,
    sourceTurnId: string,
    params: JsonObject,
    frame: JsonObject,
    at: number,
  ): void {
    const previous = this.read(sourceThreadId);
    if (this.disabled) return;
    if (
      previous &&
      (previous.targetKey !== this.targetKey ||
        previous.sourceThreadId !== sourceThreadId ||
        (previous.producer.epoch === this.producer.epoch &&
          !sameProducer(previous.producer, this.producer)))
    ) {
      this.revokePending(at);
      this.disable('native_sample_source_conflict');
      return;
    }
    if (previous?.producer.epoch === this.producer.epoch) {
      if (!Number.isSafeInteger(previous.requestSequence) || previous.requestSequence <= 0) {
        this.disable('native_sample_sequence_invalid');
        return;
      }
      this.sequence = Math.max(this.sequence, previous.requestSequence);
    }
    if (this.sequence >= Number.MAX_SAFE_INTEGER) {
      this.revokePending(at);
      this.disable('native_sample_sequence_limit');
      return;
    }
    const base: RecordBase = {
      version: 2,
      kind: 'native-sample',
      targetKey: this.targetKey,
      sourceThreadId,
      sourceTurnId,
      requestId: id,
      requestSequence: ++this.sequence,
      requestedAt: at,
      recordedAt: at,
      producer: { ...this.producer },
    };
    const previousCurrent = this.current.get(sourceThreadId);
    const repeatedPair = Boolean(
      previousCurrent &&
      !previousCurrent.terminal &&
      previousCurrent.base.sourceTurnId === sourceTurnId,
    );
    if (previousCurrent) this.discard(previousCurrent);
    // Supersede the source's current lease before retaining any response body.
    if (!this.save(base, 'pending', at)) return;
    if (
      [...this.requests.values()].filter((r) => !r.terminal).length >= maximumRequests ||
      this.requests.has(id)
    ) {
      if (!this.save(base, 'unverified', at)) return;
      this.revokePending(at);
      this.disable(
        this.requests.has(id) ? 'native_sample_request_conflict' : 'native_sample_request_limit',
      );
      return;
    }
    const request: Request = { base, terminal: false, responseSeen: false };
    this.requests.set(id, request);
    this.current.set(sourceThreadId, request);
    this.trimTerminalRequests();
    if (
      repeatedPair ||
      (Object.hasOwn(frame, 'jsonrpc') && frame.jsonrpc !== '2.0') ||
      Object.hasOwn(frame, 'result') ||
      Object.hasOwn(frame, 'error') ||
      typeof params.prompt !== 'string' ||
      !params.prompt.trim() ||
      params.prompt.length > maximumPromptBytes ||
      Buffer.byteLength(params.prompt, 'utf8') > maximumPromptBytes
    ) {
      this.finish(request, 'unverified', at);
    }
  }

  private observe(action: (at: number) => void): void {
    if (this.closed || this.disabled) return;
    try {
      const at = this.now();
      if (!timestamp(at) || at < this.lastObservedAt) {
        this.revokePending(this.lastObservedAt);
        this.disable('native_sample_clock_invalid');
        return;
      }
      this.lastObservedAt = at;
      for (const request of this.requests.values()) {
        if (!request.terminal && at - request.base.requestedAt >= requestTimeout) {
          this.finish(request, 'unverified', at);
        }
        if (this.disabled) return;
      }
      action(at);
      this.schedule(at);
    } catch {
      // Never surface frames, exception messages, prompts, text, or credentials.
      this.disable('native_sample_observation_failed');
    }
  }

  private frame(line: string, at: number): JsonObject | undefined {
    let value: unknown;
    let code = 'native_sample_frame_invalid';
    try {
      if (
        typeof line !== 'string' ||
        line.length > maximumFrameBytes ||
        Buffer.byteLength(line, 'utf8') > maximumFrameBytes
      ) {
        code = 'native_sample_frame_limit';
        throw new Error(code);
      }
      value = uniqueJson(line);
      if (!object(value)) throw new Error(code);
    } catch {
      this.revokePending(at);
      if (!this.disabled) this.report(code);
      return undefined;
    }
    return value;
  }

  private read(threadId: string): RuntimeDesktopSuggestion | undefined {
    try {
      return this.store.readRuntime(threadId);
    } catch {
      this.disable('native_sample_store_read_failed');
      return undefined;
    }
  }

  private currentRecord(request: Request): RuntimeDesktopSuggestion | undefined {
    if (this.disabled || this.current.get(request.base.sourceThreadId) !== request)
      return undefined;
    const current = this.read(request.base.sourceThreadId);
    const base = request.base;
    if (
      !current ||
      current.version !== 2 ||
      current.kind !== 'native-sample' ||
      current.targetKey !== base.targetKey ||
      current.sourceThreadId !== base.sourceThreadId ||
      current.sourceTurnId !== base.sourceTurnId ||
      current.requestId !== base.requestId ||
      current.requestSequence !== base.requestSequence ||
      current.requestedAt !== base.requestedAt ||
      !sameProducer(current.producer, this.producer)
    )
      return undefined;
    return current;
  }

  private save(
    base: RecordBase,
    state: RuntimeDesktopSuggestion['state'],
    at: number,
    result: RuntimeDesktopSuggestion['result'] = null,
  ): boolean {
    if (this.disabled) return false;
    try {
      if (
        this.store.saveRuntime({
          ...base,
          producer: { ...this.producer },
          recordedAt: Math.max(at, base.requestedAt),
          state,
          result,
        }) === true
      )
        return true;
    } catch {
      /* Only a stable error code crosses the observer boundary. */
    }
    this.disable('native_sample_store_save_failed');
    return false;
  }

  private finish(request: Request, state: InvalidState, at: number, revokeCompleted = false): void {
    if (request.terminal && !revokeCompleted) return;
    const current = this.currentRecord(request);
    this.discard(request);
    if (
      current &&
      (current.state === 'pending' || (revokeCompleted && current.state === 'completed'))
    )
      this.save(request.base, state, at);
  }

  private revokeThread(threadId: string, at: number): void {
    const request = this.current.get(threadId);
    if (request) this.finish(request, 'unverified', at, true);
  }

  private revokePending(at: number): void {
    for (const request of this.requests.values()) {
      if (!request.terminal) this.finish(request, 'unverified', at);
      if (this.disabled) return;
    }
  }

  private discard(request: Request): void {
    request.terminal = true;
    delete request.candidate;
  }

  private trimTerminalRequests(): void {
    for (const [id, request] of this.requests) {
      if (this.requests.size <= maximumRequests * 2) break;
      if (!request.terminal) continue;
      this.requests.delete(id);
      if (this.current.get(request.base.sourceThreadId) === request)
        this.current.delete(request.base.sourceThreadId);
    }
  }

  private clearTimer(): void {
    if (this.timer !== undefined) clearTimeout(this.timer);
    this.timer = undefined;
  }

  private schedule(at: number): void {
    this.clearTimer();
    if (this.closed || this.disabled) return;
    let delay = Infinity;
    for (const request of this.requests.values()) {
      if (!request.terminal)
        delay = Math.min(delay, requestTimeout - (at - request.base.requestedAt));
    }
    if (delay !== Infinity) {
      this.timer = setTimeout(
        () => {
          this.timer = undefined;
          this.observe(() => {});
        },
        Math.max(1, delay),
      );
      this.timer.unref?.();
    }
  }

  private report(code: string): void {
    try {
      this.onError?.(code);
    } catch {
      /* Logging cannot affect original stdio forwarding. */
    }
  }

  private disable(code: string): void {
    if (this.disabled) return;
    // Block reentry from onError before reporting, then discard the entire epoch.
    this.disabled = true;
    this.report(code);
    this.clearTimer();
    this.requests.clear();
    this.current.clear();
  }
}
