import { createHash } from 'node:crypto';
import {
  machineRuntimeTargetKey,
  type RuntimeTarget,
  type DesktopNextTurnSuggestionResponse,
} from '@codex-manage/protocol';
import {
  DesktopSuggestionStore,
  type RuntimeDesktopSuggestion,
  type RuntimeSuggestionProducerState,
} from './desktop-suggestion-store.js';
import {
  inspectNativeSuggestionProducer,
  nativeSuggestionImplementationFingerprint,
} from './desktop-native-sample-platform.js';

const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;
const hash = (value: unknown) => createHash('sha256').update(JSON.stringify(value)).digest('hex');
const unavailable = () =>
  Object.assign(new Error('Original desktop result channel unavailable'), { status: 503 });

/** Private same-machine result reads only; source ownership is fenced by the Host. */
export class DesktopNativeSuggestionChannel {
  private readonly store: DesktopSuggestionStore;
  private readonly targetKey: string;
  private readonly implementationSha256 = nativeSuggestionImplementationFingerprint();
  constructor(
    private readonly scope: { traceRoot: string; configPath: string; target: () => RuntimeTarget },
  ) {
    this.targetKey = machineRuntimeTargetKey(scope.target());
    this.store = new DesktopSuggestionStore(scope.traceRoot, this.targetKey);
  }
  private proof(): RuntimeSuggestionProducerState {
    if (machineRuntimeTargetKey(this.scope.target()) !== this.targetKey) throw unavailable();
    const state = this.store.readProducer(),
      now = Date.now();
    if (
      !state ||
      state.phase !== 'listening' ||
      state.implementationSha256 !== this.implementationSha256 ||
      now < state.checkedAt ||
      now - state.checkedAt > 6000 ||
      state.rootIdentity !== this.store.rootIdentity()
    )
      throw unavailable();
    const native = inspectNativeSuggestionProducer(state.producer.pid, this.scope.configPath);
    if (
      native.pid !== state.producer.pid ||
      native.desktopPid !== state.producer.desktopPid ||
      native.desktopStartedAt !== state.producer.desktopStartedAt ||
      native.wrapperStartedAt !== state.wrapperStartedAt
    )
      throw unavailable();
    // Native work can yield or take time. Never renew the producer lease with a read.
    const final = this.store.readProducer();
    if (
      Date.now() - state.checkedAt > 6000 ||
      !final ||
      final.phase !== 'listening' ||
      final.rootIdentity !== state.rootIdentity ||
      final.implementationSha256 !== state.implementationSha256 ||
      hash(final.producer) !== hash(state.producer)
    )
      throw unavailable();
    return state;
  }
  get available(): boolean {
    try {
      this.proof();
      return true;
    } catch {
      return false;
    }
  }
  private snapshot(threadId: string): {
    producer: RuntimeSuggestionProducerState;
    record: RuntimeDesktopSuggestion | undefined;
    stamp: string;
  } {
    const producer = this.proof(),
      record = this.store.readRuntime(threadId);
    if (record && JSON.stringify(record.producer) !== JSON.stringify(producer.producer))
      return { producer, record: undefined, stamp: hash([producer.producer, null]) };
    return { producer, record, stamp: hash([producer.producer, record ?? null]) };
  }
  prepare(
    target: RuntimeTarget,
    threadId: string,
    sourceTurnId: string,
    onFinalAvailability?: (available: boolean) => void,
  ): {
    commit(): DesktopNextTurnSuggestionResponse;
    dispose(): void;
  } {
    if (
      !uuid.test(threadId) ||
      !uuid.test(sourceTurnId) ||
      threadId === sourceTurnId ||
      machineRuntimeTargetKey(target) !== this.targetKey
    )
      throw Object.assign(new Error('Invalid original suggestion source'), { status: 400 });
    const first = this.snapshot(threadId);
    let disposed = false;
    onFinalAvailability?.(true);
    return {
      dispose: () => {
        disposed = true;
      },
      commit: () => {
        if (disposed) throw unavailable();
        disposed = true;
        try {
          const current = this.snapshot(threadId);
          if (current.stamp !== first.stamp)
            throw Object.assign(new Error('Original suggestion changed during read'), {
              status: 409,
            });
          const record = current.record;
          const body =
            record?.state === 'completed' && record.sourceTurnId === sourceTurnId
              ? record.result
              : null;
          // A retained latestExtracted body is historical storage only. It is
          // never projected over a newer pending, empty or suppressed attempt.
          const suggestion = body
            ? {
                id: hash([
                  this.targetKey,
                  threadId,
                  sourceTurnId,
                  record!.producer.epoch,
                  record!.requestSequence,
                  body.contentSha256,
                ]),
                text: body.text,
                contentSha256: body.contentSha256,
                validity: 'native-version' as const,
                expiresAt: new Date(Date.now() + 6000).toISOString(),
              }
            : null;
          onFinalAvailability?.(true);
          return {
            schemaVersion: 1,
            target: structuredClone(this.scope.target()),
            threadId,
            sourceTurnId,
            readOnly: true,
            source: 'native-trace',
            suggestion,
          };
        } catch (error) {
          onFinalAvailability?.(false);
          throw error;
        }
      },
    };
  }
  read(
    target: RuntimeTarget,
    threadId: string,
    sourceTurnId: string,
    onFinalAvailability?: (available: boolean) => void,
  ): DesktopNextTurnSuggestionResponse {
    const prepared = this.prepare(target, threadId, sourceTurnId, onFinalAvailability);
    try {
      return prepared.commit();
    } finally {
      prepared.dispose();
    }
  }
}
