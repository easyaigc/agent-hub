import { createHash, randomUUID } from 'node:crypto';
import { readFileSync, lstatSync } from 'node:fs';
import { rename, unlink } from 'node:fs/promises';
import { join } from 'node:path';
import {
  machineRuntimeTargetKey,
  type RuntimeTarget,
  type DesktopNextTurnSuggestionResponse,
  type DesktopSuggestionObservations,
} from '@codex-manage/protocol';
import {
  assertPrivateFile,
  assertPrivateFileAsync,
  makePrivateDirectorySync,
  openPrivateFile,
} from './desktop-platform.js';
import { startDesktopResponseForwarder } from './desktop-response-forwarder.js';
import type {
  DesktopResponseForwarderEvent,
  DesktopResponseForwarderOptions,
  PredictionIdentity,
  ResponseSuggestionState,
} from './desktop-response-forwarder-types.js';
import {
  responseDesktopKey,
  type ResponseDesktopIdentity,
  type ResponsePeerIdentity,
} from './desktop-response-peer.js';
import { DesktopResponsePeerClient } from './desktop-response-peer-client.js';
import { SuggestionObservationError } from './desktop-suggestion-observer.js';
import type { PreparedSuggestionRead } from './desktop-suggestion-worker-client.js';

const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;
const sha = (value: string) => createHash('sha256').update(value).digest('hex');
const failure = (status = 503) =>
  new SuggestionObservationError(status, '原桌面 Responses 建议采集暂不可用');
export interface DesktopResponseSuggestionsConfig {
  version: 1;
  port: number;
  bindingId: string;
}
export function readDesktopResponseSuggestionsConfig(
  path: string,
): DesktopResponseSuggestionsConfig {
  assertPrivateFile(path);
  if (lstatSync(path).size > 4096) throw failure();
  const value = JSON.parse(readFileSync(path, 'utf8')) as DesktopResponseSuggestionsConfig;
  if (
    Object.keys(value).sort().join(',') !== 'bindingId,port,version' ||
    value.version !== 1 ||
    !Number.isInteger(value.port) ||
    value.port < 1024 ||
    value.port > 65535 ||
    !uuid.test(value.bindingId)
  )
    throw failure();
  return value;
}
interface CapturedSuggestion extends PredictionIdentity {
  version: 1;
  kind: 'desktop-response';
  targetKey: string;
  epoch: string;
  sequence: number;
  peer: ResponsePeerIdentity;
  state: 'pending' | ResponseSuggestionState;
  responseId: string | null;
  text: string | null;
  completedAt: number | null;
  persisted: boolean;
}
type Connection = {
  peer: Promise<ResponsePeerIdentity>;
  queue: Promise<void>;
  pending: number;
  rejected: boolean;
};
type Options = {
  directory: string;
  configPath: string;
  target: () => RuntimeTarget;
  /** In-process fixture injection only; never parsed from Host configuration. */
  fixture?: {
    desktop: () => Promise<ResponseDesktopIdentity>;
    peer: (ports: { clientPort: number; listenerPort: number }) => Promise<ResponsePeerIdentity>;
    forwarder: NonNullable<DesktopResponseForwarderOptions['fixture']>;
  };
};

/** Windows-only additive collector. The Mac native-sample/safety channel is unchanged.
 * Reads are passive. Host IPC fences the exact latest completed source before and
 * after every prepared read. No UI, OCR, runtime getter or generation RPC is used.
 */
export class DesktopResponseSuggestions {
  readonly usesFencedSourceRead = true;
  private readonly config: DesktopResponseSuggestionsConfig;
  private readonly targetKey: string;
  private readonly epoch = randomUUID();
  private readonly directory: string;
  private readonly nativePeer: DesktopResponsePeerClient | undefined;
  private bridge: Awaited<ReturnType<typeof startDesktopResponseForwarder>> | undefined;
  private starting: Promise<void> | undefined;
  private closed = false;
  private retry: NodeJS.Timeout | undefined;
  private identity: ResponseDesktopIdentity | undefined;
  private identityCheckedAt = 0;
  private identityPending: Promise<ResponseDesktopIdentity> | undefined;
  private identityTimer: NodeJS.Timeout | undefined;
  private sequence = 0;
  private revision = 0;
  private storageReady = true;
  private connections = new Map<string, Connection>();
  private unobservableConnections = new Set<string>();
  private records = new Map<string, CapturedSuggestion>();
  private tickets = new Map<string, number>();
  private sourceSequences = new Map<string, number>();
  private persistence = Promise.resolve();
  private writes = 0;
  private counters = {
    peerAccepted: 0,
    peerRejected: 0,
    predictionStarts: 0,
    predictionResults: 0,
    persistedResults: 0,
  };
  constructor(private readonly options: Options) {
    if (process.platform !== 'win32' && !options.fixture) throw failure();
    this.config = readDesktopResponseSuggestionsConfig(options.configPath);
    this.targetKey = machineRuntimeTargetKey(options.target());
    this.directory = join(options.directory, 'desktop-response-suggestions', sha(this.targetKey));
    makePrivateDirectorySync(this.directory);
    this.nativePeer = options.fixture ? undefined : new DesktopResponsePeerClient();
    // Persisted bodies are recovery evidence only. A new Host epoch never
    // republishes a previous process's result as a fresh native response.
  }
  private targetCurrent(): boolean {
    return machineRuntimeTargetKey(this.options.target()) === this.targetKey;
  }
  get capabilities(): string[] {
    return this.available
      ? ['desktop:suggestions:read:v1', 'desktop:suggestions:native-version:v1']
      : [];
  }
  private get available(): boolean {
    return (
      !this.closed &&
      this.targetCurrent() &&
      this.storageReady &&
      this.unobservableConnections.size === 0 &&
      !!this.bridge?.stats().observerAlive &&
      !!this.identity &&
      Date.now() >= this.identityCheckedAt &&
      Date.now() - this.identityCheckedAt < 15000
    );
  }
  private refreshIdentity(): Promise<ResponseDesktopIdentity> {
    if (this.identityPending) return this.identityPending;
    const read = this.options.fixture?.desktop ?? (() => this.nativePeer!.inspect());
    const pending = read()
      .then((identity) => {
        if (this.closed || !this.targetCurrent()) throw failure();
        if (this.identity && responseDesktopKey(identity) !== responseDesktopKey(this.identity)) {
          this.records.clear();
          this.revision++;
        }
        this.identity = identity;
        this.identityCheckedAt = Date.now();
        return identity;
      })
      .catch(() => {
        this.identityCheckedAt = 0;
        throw failure();
      })
      .finally(() => {
        if (this.identityPending === pending) this.identityPending = undefined;
      });
    this.identityPending = pending;
    return pending;
  }
  start(): void {
    if (this.closed || this.starting || this.bridge || this.retry) return;
    this.starting = startDesktopResponseForwarder({
      port: this.config.port,
      basePath: '/_agent_hub/' + this.config.bindingId + '/backend-api',
      onEvent: (event) => this.event(event),
      captureStatus: () => ({
        ready: this.available,
        identityAgeMs: this.identityCheckedAt ? Date.now() - this.identityCheckedAt : null,
        unobservableConnections: this.unobservableConnections.size,
        storageReady: this.storageReady,
        ...this.counters,
      }),
      ...(this.options.fixture ? { fixture: this.options.fixture.forwarder } : {}),
    })
      .then(async (bridge) => {
        if (this.closed) {
          await bridge.close();
          return;
        }
        this.bridge = bridge;
        const heartbeat = () => {
          if (this.closed) return;
          void this.refreshIdentity()
            .catch(() => {})
            .finally(() => {
              if (!this.closed) {
                this.identityTimer = setTimeout(heartbeat, 5000);
                this.identityTimer.unref();
              }
            });
        };
        heartbeat();
      })
      .catch(() => {
        if (!this.closed) {
          this.retry = setTimeout(() => {
            this.retry = undefined;
            this.start();
          }, 5000);
          this.retry.unref();
        }
      })
      .finally(() => {
        this.starting = undefined;
      });
  }
  private invalidate(connectionId: string | null): void {
    for (const [threadId, record] of this.records) {
      if (connectionId === null || record.connectionId === connectionId) {
        this.records.set(threadId, {
          ...record,
          state: 'unverified',
          text: null,
          persisted: false,
        });
        this.revision++;
      }
    }
  }
  private event(event: DesktopResponseForwarderEvent): void {
    if (this.closed) return;
    if (event.kind === 'connection') {
      if (this.connections.size >= 64) return;
      const read = this.options.fixture?.peer ?? ((ports) => this.nativePeer!.inspect(ports));
      // Verification never holds up the original HTTP/WebSocket traffic.
      const peer = read({ clientPort: event.clientPort, listenerPort: event.listenerPort });
      void peer.then(
        () => this.counters.peerAccepted++,
        () => this.counters.peerRejected++,
      );
      void peer.catch(() => {});
      this.connections.set(event.connectionId, {
        peer,
        queue: Promise.resolve(),
        pending: 0,
        rejected: false,
      });
      return;
    }
    if (event.kind === 'observation-unavailable') {
      // A lost body may contain a new prediction for any source. Suppress reads
      // until that stream ends; forwarding remains independent and untouched.
      if (event.connectionId) this.unobservableConnections.add(event.connectionId);
      this.invalidate(null);
      return;
    }
    if (
      event.kind === 'connection-closed' &&
      this.unobservableConnections.delete(event.connectionId)
    )
      this.invalidate(null);
    const connection = this.connections.get(event.connectionId);
    // A new observed request invalidates its source synchronously, before slow
    // native peer authentication. Untrusted or failed requests cannot revive
    // the previous body, and older queued starts cannot cross this barrier.
    if (event.kind === 'prediction-start') {
      this.counters.predictionStarts++;
      if (!uuid.test(event.sourceThreadId) || !uuid.test(event.sourceTurnId)) return;
      const sequence = ++this.sequence;
      if (connection && !connection.rejected && this.tickets.size < 4096)
        this.tickets.set(event.requestId, sequence);
      else if (connection) connection.rejected = true;
      this.sourceSequences.delete(event.sourceThreadId);
      this.sourceSequences.set(event.sourceThreadId, sequence);
      this.records.delete(event.sourceThreadId);
      this.revision++;
      if (this.sourceSequences.size > 512) {
        const oldest = this.sourceSequences.keys().next().value!;
        this.sourceSequences.delete(oldest);
        this.records.delete(oldest);
      }
    }
    if (event.kind === 'prediction-result') this.counters.predictionResults++;
    if (!connection) return;
    if (event.kind === 'connection-closed') {
      void connection.queue.finally(() => {
        this.connections.delete(event.connectionId);
        for (const [key] of this.tickets)
          if (key.startsWith(event.connectionId + ':')) this.tickets.delete(key);
      });
      return;
    }
    if (connection.rejected) return;
    if (++connection.pending > 64 || this.tickets.size > 4096) {
      connection.rejected = true;
      this.invalidate(event.connectionId);
      return;
    }
    connection.queue = connection.queue
      .then(async () => {
        const peer = await connection.peer;
        if (this.closed || connection.rejected || !this.targetCurrent()) return;
        const sequence = this.tickets.get(event.requestId);
        if (
          !sequence ||
          sequence !== this.sourceSequences.get(event.sourceThreadId) ||
          !uuid.test(event.sourceThreadId) ||
          !uuid.test(event.sourceTurnId) ||
          event.sourceThreadId === event.sourceTurnId
        )
          return;
        const current = this.records.get(event.sourceThreadId);
        if (current && current.sequence > sequence) return;
        if (event.kind === 'prediction-start') {
          const record: CapturedSuggestion = {
            ...event,
            version: 1,
            kind: 'desktop-response',
            targetKey: this.targetKey,
            epoch: this.epoch,
            sequence,
            peer,
            state: 'pending',
            responseId: null,
            text: null,
            completedAt: null,
            persisted: false,
          };
          this.records.delete(record.sourceThreadId);
          this.records.set(record.sourceThreadId, record);
          this.revision++;
          if (this.records.size > 512) {
            const oldest = this.records.keys().next().value!;
            this.records.delete(oldest);
          }
          return;
        }
        if (
          !current ||
          current.requestId !== event.requestId ||
          current.sourceTurnId !== event.sourceTurnId ||
          current.state !== 'pending'
        )
          return;
        const record: CapturedSuggestion = {
          ...current,
          state: event.state,
          text: event.text,
          responseId: event.responseId,
          completedAt: event.completedAt,
          persisted: false,
        };
        if (
          record.state === 'completed' &&
          (typeof record.text !== 'string' ||
            !record.text.trim() ||
            record.text.length > 16000 ||
            !record.responseId)
        ) {
          record.state = 'unverified';
          record.text = null;
        }
        if (record.state !== 'completed') record.text = null;
        this.records.set(record.sourceThreadId, record);
        this.revision++;
        this.persist(record);
      })
      .catch(() => {
        connection.rejected = true;
        this.invalidate(event.connectionId);
      })
      .finally(() => {
        connection.pending--;
      });
  }
  private persist(record: CapturedSuggestion): void {
    if (++this.writes > 64) {
      this.writes--;
      this.storageReady = false;
      return;
    }
    this.persistence = this.persistence
      .then(async () => {
        if (this.closed) return;
        const temporary = join(this.directory, record.sourceThreadId + '.' + randomUUID() + '.tmp');
        const destination = join(this.directory, record.sourceThreadId + '.json');
        try {
          const file = await openPrivateFile(temporary, 'wx');
          try {
            const payload = JSON.stringify({ ...record, persisted: true });
            await file.writeFile(JSON.stringify({ payload, sha256: sha(payload) }));
            await file.sync();
          } finally {
            await file.close();
          }
          await assertPrivateFileAsync(temporary);
          try {
            await assertPrivateFileAsync(destination);
          } catch (error) {
            if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error;
          }
          await rename(temporary, destination);
          this.counters.persistedResults++;
          if (this.records.get(record.sourceThreadId) === record) {
            record.persisted = true;
            this.revision++;
          }
        } finally {
          await unlink(temporary).catch(() => {});
        }
      })
      .catch(() => {
        this.storageReady = false;
      })
      .finally(() => {
        this.writes--;
      });
  }
  assertBoundReadAvailable(target: RuntimeTarget): void {
    if (machineRuntimeTargetKey(target) !== this.targetKey) throw failure(409);
    if (!this.available) throw failure();
  }
  async prepareBoundSourceAsync(
    target: RuntimeTarget,
    threadId: string,
    sourceTurnId: string,
    deadlineAt: number,
    signal?: AbortSignal,
  ): Promise<PreparedSuggestionRead> {
    if (!uuid.test(threadId) || !uuid.test(sourceTurnId) || threadId === sourceTurnId)
      throw failure(400);
    const active = () => {
      if (signal?.aborted || performance.now() >= deadlineAt) throw failure();
      this.assertBoundReadAvailable(target);
    };
    active();
    const owner = await this.refreshIdentity();
    active();
    const first = this.records.get(threadId),
      stamp = sha(JSON.stringify(first ?? null));
    let disposed = false;
    return {
      dispose: () => {
        disposed = true;
      },
      commit: async () => {
        if (disposed) throw failure();
        disposed = true;
        const currentOwner = await this.refreshIdentity();
        active();
        if (
          stamp !== sha(JSON.stringify(this.records.get(threadId) ?? null)) ||
          responseDesktopKey(owner) !== responseDesktopKey(currentOwner)
        )
          throw failure(409);
        const record = first;
        const usable =
          record?.persisted &&
          record.state === 'completed' &&
          record.sourceTurnId === sourceTurnId &&
          responseDesktopKey(record.peer) === responseDesktopKey(currentOwner) &&
          record.epoch === this.epoch &&
          record.text &&
          record.responseId;
        return {
          schemaVersion: 1,
          target: structuredClone(this.options.target()),
          threadId,
          sourceTurnId,
          readOnly: true,
          source: 'native-trace',
          ...(usable
            ? {
                provenance: {
                  kind: 'responses-forwarder' as const,
                  responseId: record.responseId!,
                  desktopDisplay: 'unobserved' as const,
                },
              }
            : {}),
          suggestion: usable
            ? {
                id: sha(
                  JSON.stringify([
                    this.targetKey,
                    this.epoch,
                    threadId,
                    sourceTurnId,
                    record.requestId,
                    record.responseId,
                    record.text,
                  ]),
                ),
                text: record.text!,
                contentSha256: sha(record.text!),
                validity: 'native-version',
                expiresAt: new Date(Date.now() + 6000).toISOString(),
              }
            : null,
        };
      },
    };
  }
  async readBoundSourceAsync(
    target: RuntimeTarget,
    threadId: string,
    sourceTurnId: string,
  ): Promise<DesktopNextTurnSuggestionResponse> {
    const prepared = await this.prepareBoundSourceAsync(
      target,
      threadId,
      sourceTurnId,
      performance.now() + 8000,
    );
    try {
      return await prepared.commit();
    } finally {
      prepared.dispose();
    }
  }
  read(target: RuntimeTarget): DesktopSuggestionObservations {
    if (machineRuntimeTargetKey(target) !== this.targetKey) throw failure(409);
    return {
      schemaVersion: 1,
      target: structuredClone(target),
      readOnly: true,
      collector: this.available ? 'listening' : 'unavailable',
      sourceBinding: 'unavailable',
      observations: [],
    };
  }
  close(): void {
    this.closed = true;
    clearTimeout(this.retry);
    clearTimeout(this.identityTimer);
    this.records.clear();
    this.sourceSequences.clear();
    this.unobservableConnections.clear();
    this.revision++;
    this.nativePeer?.close();
    void this.bridge?.close().catch(() => {});
    this.bridge = undefined;
  }
}
