import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { DesktopIpc, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';

// Read-only collector for explicitly named synthetic phone trials. UI sends
// remain real native actions. This tool never creates or retries an operation.
const [mode, ...args] = process.argv.slice(2);
const options = Object.fromEntries(
  args.reduce((pairs, value, i) => {
    if (i % 2 === 0) pairs.push([value.replace(/^--/, ''), args[i + 1]]);
    return pairs;
  }, []),
);
const read = (file) => JSON.parse(fs.readFileSync(file, 'utf8'));
const write = (file, data) => {
  fs.mkdirSync(path.dirname(path.resolve(file)), { recursive: true, mode: 0o700 });
  fs.writeFileSync(file, JSON.stringify(data, null, 2), { mode: 0o600 });
};
const runtime = options.runtime;
const prefix = options.prefix ?? 'PHONE-LATENCY-B';
assert(/^[A-Z0-9-]{1,80}$/.test(prefix), 'Use a synthetic uppercase nonce prefix');
const matches = (record) =>
  typeof record.text === 'string' && new RegExp(`${prefix}([1-6])(?:[^0-9]|$)`).test(record.text);
const sourceFiles = [
  'apps/ios/Sources/SessionStore.swift',
  'apps/ios/Sources/SessionPersistence.swift',
  'apps/ios/Sources/Models.swift',
  'apps/ios/Sources/RootView.swift',
  'apps/ios/Sources/EasyWorkPairing.swift',
  'packages/codex-adapter/dist/runtime-session.js',
  'packages/codex-adapter/dist/attachment-files.js',
  'packages/codex-adapter/dist/desktop-prepared-bridge.js',
];
const fingerprints = () =>
  Object.fromEntries(
    sourceFiles
      .filter(fs.existsSync)
      .map((file) => [file, createHash('sha256').update(fs.readFileSync(file)).digest('hex')]),
  );

if (mode === 'observe') {
  assert(runtime && options.output);
  fs.mkdirSync(options.output, { recursive: true, mode: 0o700 });
  const file = path.join(options.output, 'events.jsonl');
  fs.writeFileSync(file, '', { flag: 'wx', mode: 0o600 });
  const seen = new Map(),
    included = new Set();
  const cachedFiles = new Map();
  const readChanged = (file) => {
    const mtime = fs.statSync(file).mtimeMs;
    const cached = cachedFiles.get(file);
    if (cached?.mtime === mtime) return cached.value;
    const value = read(file);
    cachedFiles.set(file, { mtime, value });
    return value;
  };
  const metadata = {
    kind: 'physical-creation-benchmark-observer',
    prefix,
    sampleIntervalMs: 75,
    startedAt: new Date().toISOString(),
    fingerprints: fingerprints(),
  };
  write(path.join(options.output, 'observer.json'), metadata);
  const observe = () => {
    try {
      const records = Object.values(
        readChanged(path.join(runtime, 'desktop-creations/creations.json')),
      ).filter(matches);
      for (const record of records) included.add(record.clientOperationId);
      const entries = records.map((record) => ({ record, layer: 'desktop-creations' }));
      for (const id of included) {
        const bridge = path.join(runtime, 'desktop-prepared-bridge', id + '.json');
        if (fs.existsSync(bridge))
          entries.push({ record: readChanged(bridge), layer: 'desktop-prepared-bridge' });
      }
      for (const { record, layer } of entries) {
        const event = {
          operationId: record.clientOperationId,
          layer,
          phase: record.phase,
          threadId: record.threadId,
          uploaded: record.uploaded?.length,
          error: record.error,
          exitCode: record.exitCode,
        };
        const key = layer + ':' + event.operationId,
          digest = JSON.stringify(event);
        if (seen.get(key) === digest) continue;
        seen.set(key, digest);
        const value = { at: new Date().toISOString(), ...event };
        fs.appendFileSync(file, JSON.stringify(value) + '\n', { mode: 0o600 });
        console.log(JSON.stringify(value));
      }
    } catch (error) {
      if (error.code !== 'ENOENT' && !(error instanceof SyntaxError)) throw error;
    }
  };
  observe();
  const interval = setInterval(observe, 75);
  process.once('SIGINT', () => {
    clearInterval(interval);
    observe();
    metadata.finishedAt = new Date().toISOString();
    write(path.join(options.output, 'observer.json'), metadata);
    process.exit(0);
  });
} else if (mode === 'summarize') {
  assert(runtime && options.clicks && options.events && options.output);
  const clicks = read(options.clicks);
  const events = fs
    .readFileSync(options.events, 'utf8')
    .trim()
    .split('\n')
    .filter(Boolean)
    .map(JSON.parse);
  const records = Object.values(
    read(path.join(runtime, 'desktop-creations/creations.json')),
  ).filter(matches);
  const ipc = new DesktopIpc(path.join(os.homedir(), '.codex/ipc/ipc.sock'));
  const trials = [];
  await ipc.connect();
  try {
    for (const click of clicks) {
      const nonce = prefix + click.trial;
      const record = records.find((record) => record.text.includes(nonce));
      assert(record, `No Host record for ${nonce}`);
      const clickedMs = click.clickedEpochMs ?? Date.parse(click.clickedAt);
      const rows = events.filter(
        (e) => e.operationId === record.clientOperationId && Date.parse(e.at) >= clickedMs,
      );
      const stage = (layer, phase) => {
        const entry = rows.find((e) => e.layer === layer && e.phase === phase);
        return entry ? Date.parse(entry.at) : null;
      };
      const prepared = stage('desktop-creations', 'prepared');
      const submitted = stage('desktop-creations', 'submitted');
      const confirmed = stage('desktop-creations', 'confirmed');
      const released = stage('desktop-prepared-bridge', 'released');
      const input = stage('desktop-prepared-bridge', 'input-submitting');
      const inputSubmitted = stage('desktop-prepared-bridge', 'submitted');
      const subtract = (a, b) => (a !== null && b !== null ? a - b : null);
      await ipc.follow(record.threadId, 15000, true);
      const turns = desktopTurns(ipc.state(record.threadId) ?? {});
      const users = turns.flatMap((t) => t.items ?? []).filter((i) => i.type === 'userMessage');
      const nativeHostId = ipc.state(record.threadId)?.hostId;
      const imagePaths = users
        .flatMap((i) => i.content ?? [])
        .filter((p) => p.type === 'localImage')
        .map((p) => p.path);
      const imageDigests = imagePaths.map((file) => ({
        sha256: createHash('sha256').update(fs.readFileSync(file)).digest('hex'),
        sizeBytes: fs.statSync(file).size,
      }));
      const replyTimes = turns.flatMap((t) => Object.values(t.agentMessageCompletedAtMsById ?? {}));
      trials.push({
        trial: click.trial,
        kind:
          (record.projectId === null ? 'projectless' : 'project') +
          (record.attachments.length ? '-image' : '-text'),
        operationId: record.clientOperationId,
        threadId: record.threadId,
        phase: record.phase,
        effort: record.effort ?? 'desktop-default',
        generationSettings: Object.fromEntries(
          ['model', 'effort', 'speed', 'permissionMode', 'collaborationMode'].map((key) => [
            key,
            record[key] ?? null,
          ]),
        ),
        clickedAt: new Date(clickedMs).toISOString(),
        clickToHostPreparedMs: subtract(prepared, clickedMs),
        clickToHostConfirmedMs: subtract(confirmed, clickedMs),
        clickToNativeReplyCompletedMs: replyTimes.length
          ? Math.max(...replyTimes) - clickedMs
          : null,
        stagesMs: {
          clickToPrepare: subtract(prepared, clickedMs),
          prepareToSubmit: subtract(submitted, prepared),
          zeroTurnPreparation: subtract(released, submitted),
          desktopOpenAndOwner: subtract(input, released),
          firstInputSubmission: subtract(inputSubmitted, input),
          inputToConfirmation: subtract(confirmed, inputSubmitted),
        },
        owner: ipc.hasOwner(record.threadId),
        nativeHostId,
        turns: turns.length,
        userMessages: users.length,
        exactInput: users.some((i) =>
          (i.content ?? []).some((p) => p.type === 'text' && p.text === record.text),
        ),
        nativeImageInputs: users
          .flatMap((i) => i.content ?? [])
          .filter((p) => ['image', 'localImage'].includes(p.type)).length,
        attachments: record.attachments.map((a) => ({ sizeBytes: a.sizeBytes, sha256: a.sha256 })),
        nativeImageDigests: imageDigests,
        completedNonceReply: turns.some(
          (t) =>
            t.status === 'completed' &&
            (t.items ?? []).some(
              (i) => i.type === 'agentMessage' && JSON.stringify(i).includes(nonce),
            ),
        ),
        nativeModelDurationMs: turns[0]?.durationMs,
        nativeModelRecoveryErrors: turns
          .flatMap((t) => t.items ?? [])
          .filter((i) => i.type === 'error')
          .map((i) => ({
            message: i.message,
            willRetry: i.willRetry,
            additionalDetails: i.additionalDetails,
          })),
      });
    }
  } finally {
    ipc.close();
  }
  write(options.output, {
    kind: 'physical-phone-creation-benchmark',
    prefix,
    recordedAt: new Date().toISOString(),
    fingerprints: fingerprints(),
    measurementNotes: [
      'Host phases sampled at 75 ms; scheduling jitter is included.',
      'Model completion is the original desktop timestamp, not exact phone rendering latency.',
    ],
    trials,
  });
  console.log(
    JSON.stringify(
      trials.map((t) => ({
        trial: t.trial,
        preparedMs: t.clickToHostPreparedMs,
        confirmedMs: t.clickToHostConfirmedMs,
        completedMs: t.clickToNativeReplyCompletedMs,
        verified:
          t.owner && t.turns === 1 && t.userMessages === 1 && t.exactInput && t.completedNonceReply,
      })),
    ),
  );
} else if (mode === 'compare') {
  assert(options.before && options.after && options.output);
  const before = read(options.before),
    after = read(options.after);
  const original = before.trials ?? before.physicalRepeatedCreates.trials;
  const current = after.trials ?? after.physicalRepeatedCreates.trials;
  assert.equal(original.length, current.length);
  const stats = (values) => {
    assert(values.every((v) => typeof v === 'number' && v >= 0));
    const sorted = [...values].sort((a, b) => a - b),
      n = sorted.length;
    return {
      count: n,
      meanMs: values.reduce((sum, v) => sum + v, 0) / n,
      medianMs: (sorted[Math.floor((n - 1) / 2)] + sorted[Math.floor(n / 2)]) / 2,
      minMs: sorted[0],
      maxMs: sorted.at(-1),
    };
  };
  const metrics = {};
  for (const key of [
    'clickToHostPreparedMs',
    'clickToHostConfirmedMs',
    'clickToNativeReplyCompletedMs',
  ]) {
    const baseline = stats(original.map((t) => t[key])),
      optimized = stats(current.map((t) => t[key]));
    metrics[key] = {
      before: baseline,
      after: optimized,
      observedMeanReductionPercent: 100 * (1 - optimized.meanMs / baseline.meanMs),
    };
  }
  const comparison = {
    kind: 'paired-physical-creation-benchmark-comparison',
    recordedAt: new Date().toISOString(),
    metrics,
    trials: original.map((b, i) => {
      const a = current[i];
      assert.equal(b.kind, a.kind, 'Scenario order must match');
      return {
        trial: i + 1,
        kind: b.kind,
        beforeConfirmedMs: b.clickToHostConfirmedMs,
        afterConfirmedMs: a.clickToHostConfirmedMs,
        beforeModelCompletedMs: b.clickToNativeReplyCompletedMs,
        afterModelCompletedMs: a.clickToNativeReplyCompletedMs,
        ownerAndInputVerified:
          a.owner && a.turns === 1 && a.userMessages === 1 && a.exactInput && a.completedNonceReply,
      };
    }),
    limitations: [
      'Six paired trials are scenario coverage, not a statistically controlled performance claim.',
      'Model network retries are independent of creation persistence. Model-time changes cannot be attributed to this optimization.',
    ],
  };
  write(options.output, comparison);
  console.log(JSON.stringify(comparison));
} else {
  throw new Error('Use observe, summarize or compare with explicit input/output paths');
}
