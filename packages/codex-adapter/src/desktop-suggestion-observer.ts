import { spawn, type ChildProcess } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import {
  existsSync,
  mkdirSync,
  readFileSync,
  realpathSync,
  renameSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { isAbsolute, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  machineRuntimeTargetKey,
  parseNotificationTarget,
  type DesktopSuggestionObservation,
  type DesktopSuggestionObservations,
  type DesktopNextTurnSuggestionResponse,
  type RuntimeTarget,
} from '@codex-manage/protocol';
import {
  assertPrivateFile,
  inspectWindowsTraceInstance,
  type WindowsTraceInstance,
  windowsTraceInstanceReady,
  protectWindowsStateDirectory,
  protectWindowsStateFile,
} from './desktop-platform.js';
import { DesktopPredictionTraceReader } from './desktop-prediction-trace.js';
import {
  DesktopSuggestionWorkerClient,
  type PreparedSuggestionRead,
} from './desktop-suggestion-worker-client.js';

const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
const lifetime = 3600_000;
const limit = 64;
const maximumStateBytes = 2_000_000;

export class SuggestionObservationError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

/** No generation, source guessing, arbitrary RPC, adoption or submission methods. */
export class DesktopSuggestionObserver {
  private worker: ChildProcess | undefined;
  private stoppingWorker: ChildProcess | undefined;
  private retry: NodeJS.Timeout | undefined;
  private killTimer: NodeJS.Timeout | undefined;
  private heartbeatTimer: NodeJS.Timeout | undefined;
  private storageReady = true;
  private closed = false;
  private attempts = 0;
  private observations = new Map<string, DesktopSuggestionObservation>();
  private conflicts = new Set<string>();
  private phase: DesktopSuggestionObservations['collector'];
  private readonly stateFile: string;
  private readonly predictionReader?: DesktopPredictionTraceReader;
  private traceIdentity?: string;
  private desktopStartedAtUnixMs: number | undefined;
  private windowsInstanceIdentity: string | undefined;
  private workerClient?: DesktopSuggestionWorkerClient;
  private collectorEpoch = 0;
  constructor(
    private readonly options: {
      directory: string;
      target: () => RuntimeTarget;
      enabled: boolean;
      codexHome?: string;
      applicationPath?: string;
      traceRoot?: string;
      sharedDesktopState?: string;
      /** Internal worker construction only; not a Host configuration field. */
      nativeWorker?: boolean;
    },
  ) {
    this.phase = options.enabled ? 'starting' : 'disabled';
    const directory = join(options.directory, 'desktop-suggestion-observations');
    this.stateFile = join(directory, 'observations.json');
    if (!options.enabled) return;
    if (process.platform === 'win32' && !options.nativeWorker) {
      this.workerClient = this.createWindowsReader();
      return;
    }
    try {
      if (options.traceRoot)
        this.predictionReader = new DesktopPredictionTraceReader(options.traceRoot);
      const scope = createHash('sha256')
        .update(machineRuntimeTargetKey(options.target()))
        .digest('hex');
      this.stateFile = join(directory, 'observations-' + scope + '.json');
      mkdirSync(directory, { recursive: true, mode: 0o700 });
      protectWindowsStateDirectory(directory);
      const info = statSync(directory);
      if (
        process.platform !== 'win32' &&
        (info.mode & 0o077 || (process.getuid && info.uid !== process.getuid()))
      )
        throw new Error('Invalid private observation directory');
      this.restore();
    } catch {
      this.failStorage();
    }
  }

  get capabilities(): string[] {
    if (this.workerClient)
      return this.workerClient.available
        ? ['desktop:suggestions:read:v1', 'desktop:suggestions:native-version:v1']
        : [];
    return this.phase === 'listening'
      ? [
          ...(process.platform === 'win32' ? [] : ['desktop:suggestion-observations']),
          ...(this.boundReadAvailable()
            ? ['desktop:suggestions:read:v1', 'desktop:suggestions:native-version:v1']
            : []),
        ]
      : [];
  }

  private createWindowsReader(): DesktopSuggestionWorkerClient {
    return new DesktopSuggestionWorkerClient({
      directory: this.options.directory,
      traceRoot: this.options.traceRoot,
      target: structuredClone(this.options.target()),
    });
  }

  private boundReadAvailable(instance?: WindowsTraceInstance): boolean {
    if (process.platform === 'win32' && !this.windowsInstanceStable(instance)) return false;
    if (
      this.phase !== 'listening' ||
      this.desktopStartedAtUnixMs === undefined ||
      this.predictionReader?.isAvailable() !== true
    )
      return false;
    try {
      const identity = this.readTraceIdentity();
      this.traceIdentity ??= identity;
      return this.traceIdentity === identity;
    } catch {
      return false;
    }
  }

  /** Worker heartbeat only; data reads never supply a reused probe. */
  workerReadiness(instance: WindowsTraceInstance): boolean {
    return this.boundReadAvailable(instance);
  }

  private windowsInstanceStable(knownInstance?: WindowsTraceInstance): boolean {
    try {
      const instance = knownInstance ?? inspectWindowsTraceInstance();
      const identity = JSON.stringify(instance);
      return (
        this.windowsInstanceIdentity === identity &&
        windowsTraceInstanceReady(instance, this.options.traceRoot ?? '')
      );
    } catch {
      return false;
    }
  }

  private readTraceIdentity(): string {
    const path = realpathSync(this.options.traceRoot!);
    const info = statSync(path);
    if (path !== this.options.traceRoot || !info.isDirectory())
      throw new SuggestionObservationError(409, '原桌面建议记录目录已变化');
    return JSON.stringify([path, info.dev, info.ino]);
  }

  assertBoundReadAvailable(target: RuntimeTarget): void {
    if (machineRuntimeTargetKey(target) !== machineRuntimeTargetKey(this.options.target()))
      throw new SuggestionObservationError(409, '建议采集目标已变化');
    if (this.workerClient) {
      if (!this.workerClient.available)
        throw new SuggestionObservationError(503, '原桌面建议读取暂不可用');
      return;
    }
    if (this.phase !== 'listening')
      throw new SuggestionObservationError(503, '原桌面建议读取暂不可用');
    if (this.traceIdentity) {
      let identity;
      try {
        identity = this.readTraceIdentity();
      } catch {
        throw new SuggestionObservationError(409, '原桌面建议记录目录已变化');
      }
      if (identity !== this.traceIdentity)
        throw new SuggestionObservationError(409, '原桌面建议记录目录已变化');
    }
    if (!this.boundReadAvailable())
      throw new SuggestionObservationError(503, '原桌面建议读取暂不可用');
  }

  readBoundSource(
    target: RuntimeTarget,
    threadId: string,
    sourceTurnId: string,
    onFinalAvailability?: (available: boolean) => void,
  ): DesktopNextTurnSuggestionResponse {
    if (this.workerClient) throw new SuggestionObservationError(503, 'Windows 建议必须异步读取');
    if (!uuid.test(threadId) || !uuid.test(sourceTurnId) || threadId === sourceTurnId)
      throw new SuggestionObservationError(400, '建议来源标识无效');
    const key = machineRuntimeTargetKey(target);
    const current = structuredClone(this.options.target());
    this.assertBoundReadAvailable(target);
    this.prune();
    let suggestion: DesktopNextTurnSuggestionResponse['suggestion'] = null;
    let matches = 0;
    const recovered = this.predictionReader!.readSourcePredictions(
      threadId,
      sourceTurnId,
      this.desktopStartedAtUnixMs!,
      true,
    );
    // A failed integrity/bounds check must not fall back to less complete evidence.
    const candidates = new Map(
      (recovered ?? []).map((prediction) => [
        JSON.stringify([
          prediction.binding.predictionThreadId,
          prediction.binding.predictionTurnId,
        ]),
        { prediction, observation: undefined as DesktopSuggestionObservation | undefined },
      ]),
    );
    for (const observation of this.observations.values()) {
      if (machineRuntimeTargetKey(observation.target) !== key) continue;
      // Notifications may outlive the selected native execution. They can
      // corroborate that execution, never reintroduce a superseded candidate.
      if (
        !candidates.has(
          JSON.stringify([observation.predictionThreadId, observation.predictionTurnId]),
        )
      )
        continue;
      const prediction = this.predictionReader!.readPrediction(
        observation.predictionThreadId,
        observation.predictionTurnId,
      );
      if (
        !prediction ||
        Date.parse(prediction.completedAt) < this.desktopStartedAtUnixMs! ||
        prediction.binding.sourceThreadId !== threadId ||
        prediction.binding.sourceTurnId !== sourceTurnId
      )
        continue;
      candidates.set(
        JSON.stringify([observation.predictionThreadId, observation.predictionTurnId]),
        { prediction, observation },
      );
    }
    for (const { prediction, observation } of candidates.values()) {
      const completedAt = Date.parse(prediction.completedAt);
      if (completedAt < this.desktopStartedAtUnixMs!) continue;
      matches++;
      const expiresAt = Date.now() + 6000;
      const observationId = createHash('sha256')
        .update(
          JSON.stringify([
            key,
            prediction.binding.predictionThreadId,
            prediction.binding.predictionTurnId,
          ]),
        )
        .digest('hex');
      if (
        this.conflicts.has(observationId) ||
        (observation &&
          (prediction.binding.predictionThreadId !== observation.predictionThreadId ||
            prediction.binding.predictionTurnId !== observation.predictionTurnId ||
            prediction.text !== observation.text)) ||
        !Number.isFinite(completedAt) ||
        completedAt > Date.now() + 60_000
      )
        continue;
      const contentSha256 = createHash('sha256').update(prediction.text).digest('hex');
      suggestion = {
        id: createHash('sha256')
          .update(JSON.stringify([key, threadId, sourceTurnId, observationId, contentSha256]))
          .digest('hex'),
        text: prediction.text,
        contentSha256,
        // Renew a short read lease without changing native execution identity.
        expiresAt: new Date(expiresAt).toISOString(),
        validity: 'native-version',
      };
    }
    if (key !== machineRuntimeTargetKey(this.options.target()))
      throw new SuggestionObservationError(409, '建议读取期间运行目标已变化');
    const available = this.boundReadAvailable();
    if (!available) suggestion = null;
    // Internal reader delivery reuses this exact fresh guard, without another
    // probe or inferring readiness from whether a prediction was found.
    onFinalAvailability?.(available);
    // Native Windows instance revalidation may take time; the finite read lease
    // begins after that final check, rather than while the response is pending.
    if (suggestion && process.platform === 'win32')
      suggestion.expiresAt = new Date(Date.now() + 6000).toISOString();
    return {
      schemaVersion: 1,
      target: current,
      threadId,
      sourceTurnId,
      readOnly: true,
      source: 'native-trace',
      suggestion: recovered !== null && matches === 1 ? suggestion : null,
    };
  }

  async readBoundSourceAsync(
    target: RuntimeTarget,
    threadId: string,
    sourceTurnId: string,
  ): Promise<DesktopNextTurnSuggestionResponse> {
    if (!this.workerClient) return this.readBoundSource(target, threadId, sourceTurnId);
    if (!uuid.test(threadId) || !uuid.test(sourceTurnId) || threadId === sourceTurnId)
      throw new SuggestionObservationError(400, '建议来源标识无效');
    this.assertBoundReadAvailable(target);
    try {
      const result = await this.workerClient.read(threadId, sourceTurnId);
      if (
        this.closed ||
        result.schemaVersion !== 1 ||
        !result.readOnly ||
        result.source !== 'native-trace' ||
        result.threadId !== threadId ||
        result.sourceTurnId !== sourceTurnId ||
        machineRuntimeTargetKey(target) !== machineRuntimeTargetKey(this.options.target()) ||
        machineRuntimeTargetKey(result.target) !== machineRuntimeTargetKey(target)
      )
        throw new SuggestionObservationError(409, '建议采集目标已变化');
      return result;
    } catch (error) {
      if (error instanceof SuggestionObservationError) throw error;
      throw new SuggestionObservationError(
        (error as { status?: number }).status ?? 503,
        '原桌面建议读取暂不可用',
      );
    }
  }

  get usesFencedSourceRead(): boolean {
    return this.workerClient !== undefined;
  }

  /** Host-owned middle fence stays outside the transport. Never returns a body here. */
  async prepareBoundSourceAsync(
    target: RuntimeTarget,
    threadId: string,
    sourceTurnId: string,
    deadlineAt: number,
    signal?: AbortSignal,
  ): Promise<PreparedSuggestionRead> {
    if (!this.workerClient) throw new SuggestionObservationError(503, 'Windows 建议读取未就绪');
    if (!uuid.test(threadId) || !uuid.test(sourceTurnId) || threadId === sourceTurnId)
      throw new SuggestionObservationError(400, '建议来源标识无效');
    this.assertBoundReadAvailable(target);
    const key = machineRuntimeTargetKey(target);
    if (key !== this.workerClient.targetKey)
      throw new SuggestionObservationError(409, '建议读取 Worker 目标已变化');
    const current = () => {
      if (this.closed || key !== machineRuntimeTargetKey(this.options.target()))
        throw new SuggestionObservationError(409, '建议采集目标已变化');
    };
    const observationError = (error: unknown) =>
      error instanceof SuggestionObservationError
        ? error
        : new SuggestionObservationError(
            (error as { status?: number })?.status === 409
              ? 409
              : (error as { status?: number })?.status === 400
                ? 400
                : 503,
            '原桌面建议读取暂不可用',
          );
    let prepared;
    try {
      prepared = await this.workerClient.prepare(threadId, sourceTurnId, deadlineAt, signal);
    } catch (error) {
      throw observationError(error);
    }
    try {
      current();
    } catch (error) {
      prepared.dispose();
      throw error;
    }
    return {
      commit: async () => {
        try {
          current();
          const result = await prepared.commit();
          current();
          if (
            result.threadId !== threadId ||
            result.sourceTurnId !== sourceTurnId ||
            machineRuntimeTargetKey(result.target) !== key
          )
            throw new SuggestionObservationError(409, '建议采集目标已变化');
          return result;
        } catch (error) {
          prepared.dispose();
          throw observationError(error);
        }
      },
      dispose: () => prepared.dispose(),
    };
  }

  /** Worker-local parser receipt and frozen observer eligibility, consumed once. */
  prepareBoundSource(
    target: RuntimeTarget,
    threadId: string,
    sourceTurnId: string,
    onPreparedAvailability: (available: boolean) => void,
  ): {
    commit(onFinalAvailability: (available: boolean) => void): DesktopNextTurnSuggestionResponse;
    dispose(): void;
  } {
    if (
      this.workerClient ||
      !this.options.nativeWorker ||
      !uuid.test(threadId) ||
      !uuid.test(sourceTurnId) ||
      threadId === sourceTurnId
    )
      throw new SuggestionObservationError(400, '建议准备来源无效');
    this.assertBoundReadAvailable(target);
    this.prune();
    const current = structuredClone(this.options.target());
    const key = machineRuntimeTargetKey(current);
    const epoch = this.collectorEpoch;
    const instance = this.windowsInstanceIdentity;
    const startedAt = this.desktopStartedAtUnixMs!;
    const trace = this.traceIdentity;
    const preparedAt = Date.now();
    const observations = structuredClone([...this.observations.values()]);
    const conflicts = new Set(this.conflicts);
    const receipt = this.predictionReader!.prepareSourcePredictions(
      threadId,
      sourceTurnId,
      startedAt,
      true,
    );
    if (!receipt) throw new SuggestionObservationError(503, '原桌面建议准备失败');
    let consumed = false;
    const dispose = () => {
      if (consumed) return;
      consumed = true;
      receipt.dispose();
    };
    const stable = () => {
      if (
        this.closed ||
        !this.storageReady ||
        this.phase !== 'listening' ||
        epoch !== this.collectorEpoch ||
        instance !== this.windowsInstanceIdentity ||
        startedAt !== this.desktopStartedAtUnixMs ||
        trace !== this.traceIdentity ||
        key !== machineRuntimeTargetKey(this.options.target())
      )
        throw new SuggestionObservationError(409, '建议准备期间运行实例或目录已变化');
    };
    try {
      stable();
      const available = this.boundReadAvailable();
      onPreparedAvailability(available);
      if (!available) throw new SuggestionObservationError(503, '原桌面建议准备失去就绪状态');
      stable();
    } catch (error) {
      dispose();
      throw error;
    }
    return {
      dispose,
      commit: (onFinalAvailability) => {
        if (consumed) throw new SuggestionObservationError(409, '建议准备已消费');
        consumed = true;
        try {
          stable();
          const predictions = receipt.commit();
          if (predictions === null)
            throw new SuggestionObservationError(503, '原桌面建议收据已变化');
          let suggestion: DesktopNextTurnSuggestionResponse['suggestion'] = null;
          let matches = 0;
          for (const prediction of new Map(
            predictions.map((p) => [
              JSON.stringify([p.binding.predictionThreadId, p.binding.predictionTurnId]),
              p,
            ]),
          ).values()) {
            const completedAt = Date.parse(prediction.completedAt);
            if (completedAt < startedAt) continue;
            matches++;
            const observationId = createHash('sha256')
              .update(
                JSON.stringify([
                  key,
                  prediction.binding.predictionThreadId,
                  prediction.binding.predictionTurnId,
                ]),
              )
              .digest('hex');
            const observation = observations.find(
              (o) =>
                machineRuntimeTargetKey(o.target) === key &&
                o.predictionThreadId === prediction.binding.predictionThreadId &&
                o.predictionTurnId === prediction.binding.predictionTurnId,
            );
            const currentObservation = [...this.observations.values()].find(
              (o) =>
                machineRuntimeTargetKey(o.target) === key &&
                o.predictionThreadId === prediction.binding.predictionThreadId &&
                o.predictionTurnId === prediction.binding.predictionTurnId,
            );
            if (
              conflicts.has(observationId) ||
              this.conflicts.has(observationId) ||
              (observation && observation.text !== prediction.text) ||
              (currentObservation && currentObservation.text !== prediction.text) ||
              !Number.isFinite(completedAt) ||
              completedAt > preparedAt + 60_000 ||
              completedAt > Date.now() + 60_000 ||
              prediction.binding.sourceThreadId !== threadId ||
              prediction.binding.sourceTurnId !== sourceTurnId
            )
              continue;
            const contentSha256 = createHash('sha256').update(prediction.text).digest('hex');
            suggestion = {
              id: createHash('sha256')
                .update(JSON.stringify([key, threadId, sourceTurnId, observationId, contentSha256]))
                .digest('hex'),
              text: prediction.text,
              contentSha256,
              validity: 'native-version',
              expiresAt: '',
            };
          }
          stable();
          const available = this.boundReadAvailable();
          onFinalAvailability(available);
          stable();
          if (!available) throw new SuggestionObservationError(503, '原桌面建议提交失去就绪状态');
          // Only a committed parser receipt and the final real runtime/Trace guard can start a lease.
          if (suggestion) suggestion.expiresAt = new Date(Date.now() + 6000).toISOString();
          return {
            schemaVersion: 1,
            target: current,
            threadId,
            sourceTurnId,
            readOnly: true,
            source: 'native-trace',
            suggestion: matches === 1 ? suggestion : null,
          };
        } finally {
          receipt.dispose();
        }
      },
    };
  }

  start(): void {
    if (this.workerClient) {
      this.workerClient.start();
      return;
    }
    if (this.closed || !this.storageReady || this.phase === 'disabled' || this.worker || this.retry)
      return;
    if (process.platform === 'win32') {
      // No fabricated completion notification. Native source recovery performs
      // the complete binding and returns native-trace/native-version only.
      const previousPhase = this.phase;
      let unchanged = false;
      this.phase = 'unavailable';
      try {
        const instance = inspectWindowsTraceInstance();
        if (
          windowsTraceInstanceReady(instance, this.options.traceRoot ?? '') &&
          this.predictionReader?.isAvailable() &&
          (this.traceIdentity === undefined || this.traceIdentity === this.readTraceIdentity())
        ) {
          const identity = JSON.stringify(instance);
          const startedAt = Math.max(
            instance.startedAtUnixMs,
            ...instance.executors.map((e) => e.startedAtUnixMs),
          );
          unchanged =
            previousPhase === 'listening' &&
            identity === this.windowsInstanceIdentity &&
            startedAt === this.desktopStartedAtUnixMs;
          this.windowsInstanceIdentity = identity;
          this.desktopStartedAtUnixMs = startedAt;
          this.phase = 'listening';
        }
      } catch {
        /* A normal desktop reopen is user-controlled, never forced here. */
      }
      // A healthy periodic sample is not a new collector generation. A changed
      // identity/start/root or any failed initialization still fences old receipts,
      // including failure followed by recovery during a Host middle-source await.
      if (!unchanged) this.collectorEpoch++;
      this.retry = setTimeout(() => {
        this.retry = undefined;
        this.start();
      }, 30_000);
      this.retry.unref();
      return;
    }
    if (process.platform !== 'darwin') {
      this.phase = 'unavailable';
      return;
    }
    this.phase = 'starting';
    this.desktopStartedAtUnixMs = undefined;
    const epoch = randomUUID();
    const target = structuredClone(this.options.target());
    const targetKey = machineRuntimeTargetKey(target);
    // A deployed dependency lives under node_modules/@codex-manage, rather
    // than packages/. Keep its helper inside the package as well.
    const packagedHelper = fileURLToPath(
      new URL('../resources/desktop-completion-notify-observer.py', import.meta.url),
    );
    const helper = existsSync(packagedHelper)
      ? packagedHelper
      : fileURLToPath(
          new URL('../../../scripts/desktop-completion-notify-observer.py', import.meta.url),
        );
    const args = [
      helper,
      '--stream-only',
      '--output-dir',
      join(this.options.directory, 'desktop-suggestion-observations'),
      '--duration-seconds',
      '900',
    ];
    if (this.options.codexHome) args.push('--codex-home', this.options.codexHome);
    if (this.options.applicationPath) args.push('--application-path', this.options.applicationPath);
    if (this.options.sharedDesktopState)
      args.push('--shared-desktop-state', this.options.sharedDesktopState);
    const worker = spawn('/usr/bin/python3', args, {
      stdio: ['ignore', 'pipe', 'ignore'],
      shell: false,
    });
    this.worker = worker;
    let buffer = '';
    let finished = false;
    const finish = () => {
      if (finished) return;
      finished = true;
      clearTimeout(this.heartbeatTimer);
      clearTimeout(this.killTimer);
      this.killTimer = undefined;
      if (this.stoppingWorker === worker) this.stoppingWorker = undefined;
      if (this.worker === worker) this.worker = undefined;
      if (this.closed || !this.storageReady) return;
      this.phase = 'unavailable';
      this.retry = setTimeout(
        () => {
          this.retry = undefined;
          this.start();
        },
        Math.min(30_000, 1000 * 2 ** Math.min(this.attempts++, 5)),
      );
      this.retry.unref();
    };
    worker.once('error', finish);
    worker.once('exit', finish);
    const expectHeartbeat = () => {
      clearTimeout(this.heartbeatTimer);
      this.heartbeatTimer = setTimeout(() => this.stopWorker(worker), 45_000);
      this.heartbeatTimer.unref();
    };
    expectHeartbeat();
    worker.stdout?.setEncoding('utf8');
    worker.stdout?.on('data', (chunk: string) => {
      if (
        this.closed ||
        !this.storageReady ||
        this.worker !== worker ||
        this.stoppingWorker === worker
      )
        return;
      // A target change fences all late events from the old worker.
      if (targetKey !== machineRuntimeTargetKey(this.options.target())) {
        this.stopWorker(worker);
        return;
      }
      buffer += chunk;
      if (Buffer.byteLength(buffer) > 100_000) {
        this.stopWorker(worker);
        return;
      }
      let newline;
      while ((newline = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, newline);
        buffer = buffer.slice(newline + 1);
        try {
          const event = JSON.parse(line);
          if (event.phase === 'kqueue-listener-started' || event.phase === 'listening') {
            if (event.phase === 'kqueue-listener-started') {
              const startedAt = event.desktopStartedAtUnixMs;
              this.desktopStartedAtUnixMs =
                typeof startedAt === 'number' &&
                Number.isFinite(startedAt) &&
                startedAt > 0 &&
                startedAt <= Date.now()
                  ? startedAt
                  : undefined;
            }
            this.phase = 'listening';
            this.attempts = 0;
            expectHeartbeat();
          } else if (event.phase === 'finished') {
            this.phase = 'unavailable';
          } else if (event.phase === 'suggestion-observed') {
            this.accept(event.observation, target, epoch);
          }
        } catch {
          /* Reject malformed events; neither logs nor API responses include raw output. */
        }
      }
    });
  }

  private accept(value: unknown, target: RuntimeTarget, epoch: string): void {
    if (!value || typeof value !== 'object') return;
    const v = value as Record<string, unknown>;
    if (
      v.appGeneratedPredictionRequest !== true ||
      v.client !== 'Codex Desktop' ||
      v.sourceBindingVerified !== false ||
      typeof v.threadId !== 'string' ||
      !uuid.test(v.threadId) ||
      typeof v.turnId !== 'string' ||
      !uuid.test(v.turnId) ||
      typeof v.text !== 'string' ||
      !v.text.length ||
      v.text.length > 16000 ||
      typeof v.observedAt !== 'string' ||
      typeof v.cwd !== 'string'
    )
      return;
    if (!isAbsolute(v.cwd)) return;
    const time = Date.parse(v.observedAt);
    if (!Number.isFinite(time) || Math.abs(Date.now() - time) > 60_000) return;
    const id = createHash('sha256')
      .update(JSON.stringify([machineRuntimeTargetKey(target), v.threadId, v.turnId]))
      .digest('hex');
    if (this.conflicts.has(id)) return;
    const prior = this.observations.get(id);
    if (prior?.text === v.text) return;
    if (prior) {
      this.observations.delete(id);
      this.conflicts.add(id);
    } else {
      this.observations.set(id, {
        id,
        target,
        predictionThreadId: v.threadId,
        predictionTurnId: v.turnId,
        text: v.text,
        observedAt: v.observedAt,
        expiresAt: new Date(time + lifetime).toISOString(),
        collectorEpoch: epoch,
        source: null,
        bindingStatus: 'unbound',
      });
    }
    this.prune();
    try {
      this.persist();
    } catch {
      this.failStorage();
    }
  }

  read(target: RuntimeTarget): DesktopSuggestionObservations {
    const current = this.options.target();
    if (machineRuntimeTargetKey(target) !== machineRuntimeTargetKey(current))
      throw new SuggestionObservationError(409, '建议采集目标已变化');
    this.prune();
    return {
      schemaVersion: 1,
      target: current,
      readOnly: true,
      collector: this.workerClient
        ? this.workerClient.available
          ? 'listening'
          : 'unavailable'
        : this.phase,
      sourceBinding: 'unavailable',
      observations: [...this.observations.values()]
        .filter(
          (value) => machineRuntimeTargetKey(value.target) === machineRuntimeTargetKey(current),
        )
        .map((value) => structuredClone(value)),
    };
  }

  private prune(): void {
    for (const [id, value] of this.observations) {
      if (Date.parse(value.expiresAt) <= Date.now()) this.observations.delete(id);
    }
    while (this.observations.size > limit)
      this.observations.delete(this.observations.keys().next().value!);
    while (this.conflicts.size > limit)
      this.conflicts.delete(this.conflicts.values().next().value!);
  }

  private persist(): void {
    const data = () =>
      JSON.stringify({
        version: 1,
        observations: [...this.observations.values()],
        conflicts: [...this.conflicts],
      });
    while (Buffer.byteLength(data()) > maximumStateBytes && this.observations.size)
      this.observations.delete(this.observations.keys().next().value!);
    const tmp = this.stateFile + '.tmp';
    writeFileSync(tmp, data(), { mode: 0o600 });
    protectWindowsStateFile(tmp);
    renameSync(tmp, this.stateFile);
  }

  private failStorage(): void {
    this.storageReady = false;
    this.phase = 'unavailable';
    this.observations.clear();
    this.conflicts.clear();
    if (this.worker) this.stopWorker(this.worker);
  }

  private stopWorker(worker: ChildProcess): void {
    if (this.worker !== worker) return;
    this.stoppingWorker = worker;
    this.phase = 'unavailable';
    clearTimeout(this.heartbeatTimer);
    if (this.killTimer || worker.exitCode !== null || worker.signalCode !== null) return;
    worker.kill('SIGINT');
    this.killTimer = setTimeout(() => {
      if (this.worker === worker && worker.exitCode === null && worker.signalCode === null)
        worker.kill('SIGKILL');
    }, 2000);
    this.killTimer.unref();
    worker.once('exit', () => {
      clearTimeout(this.killTimer);
      this.killTimer = undefined;
    });
  }

  private restore(): void {
    if (!existsSync(this.stateFile)) return;
    assertPrivateFile(this.stateFile);
    if (statSync(this.stateFile).size > maximumStateBytes)
      throw new SuggestionObservationError(503, '建议采集记录过大');
    const data = JSON.parse(readFileSync(this.stateFile, 'utf8'));
    if (
      data.version !== 1 ||
      !Array.isArray(data.observations) ||
      data.observations.length > limit ||
      !Array.isArray(data.conflicts) ||
      data.conflicts.length > limit
    )
      throw new SuggestionObservationError(503, '建议采集记录无效');
    for (const value of data.observations) {
      const target = parseNotificationTarget(value.target);
      const id = createHash('sha256')
        .update(
          JSON.stringify([
            machineRuntimeTargetKey(target),
            value.predictionThreadId,
            value.predictionTurnId,
          ]),
        )
        .digest('hex');
      if (
        value.id !== id ||
        !uuid.test(value.predictionThreadId) ||
        !uuid.test(value.predictionTurnId) ||
        value.source !== null ||
        value.bindingStatus !== 'unbound' ||
        typeof value.text !== 'string' ||
        !value.text.length ||
        value.text.length > 16000 ||
        !Number.isFinite(Date.parse(value.observedAt)) ||
        Date.parse(value.expiresAt) !== Date.parse(value.observedAt) + lifetime ||
        !uuid.test(value.collectorEpoch)
      )
        throw new SuggestionObservationError(503, '建议采集记录无效');
      this.observations.set(id, value);
    }
    for (const id of data.conflicts) {
      if (typeof id !== 'string' || !/^[0-9a-f]{64}$/.test(id))
        throw new SuggestionObservationError(503, '建议采集记录无效');
      this.conflicts.add(id);
      this.observations.delete(id);
    }
    this.prune();
  }

  close(): void {
    this.workerClient?.close();
    this.closed = true;
    this.phase = 'unavailable';
    clearTimeout(this.retry);
    this.retry = undefined;
    clearTimeout(this.heartbeatTimer);
    const worker = this.worker;
    if (worker) this.stopWorker(worker);
  }
}
