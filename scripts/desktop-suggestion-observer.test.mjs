import assert from 'node:assert/strict';
import { execFile, execFileSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import {
  chmod,
  cp,
  mkdtemp,
  readFile,
  realpath,
  readdir,
  rename,
  mkdir,
  rm,
  symlink,
  unlink,
  writeFile,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Duplex } from 'node:stream';
import { test } from 'node:test';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { promisify } from 'node:util';
import { machineRuntimeTargetKey } from '../packages/protocol/dist/index.js';
// Resolve the Host's actual ESM import condition without executing dependencies
// in the resolver process. Flat bundles may also contain a byte-identical but
// distinct packages/.../dist copy; its constructors cannot patch the Host.
const hostRuntimeURL = pathToFileURL(
  await realpath(new URL('../apps/host-agent/dist/runtime-server.js', import.meta.url)),
);
const hostAdapterURL = new URL(
  execFileSync(
    process.execPath,
    [
      '--experimental-import-meta-resolve',
      '--input-type=module',
      '--eval',
      'process.stdout.write(import.meta.resolve("@codex-manage/codex-adapter",process.argv[1]));',
      hostRuntimeURL.href,
    ],
    { encoding: 'utf8', timeout: 5000, maxBuffer: 1024 * 1024, windowsHide: true },
  ).trim(),
);
assert.equal(hostAdapterURL.protocol, 'file:');
assert(hostAdapterURL.pathname.endsWith('/dist/index.js'), 'Resolve the actual compiled Adapter');
const hostAdapter = await import(hostAdapterURL.href);
const {
  DesktopSuggestionObserver,
  RuntimeSession,
  DesktopTranscription,
  DesktopWorkspaceView,
  DesktopDomBridge,
  assertPrivateFile,
  protectWindowsStateDirectory,
  protectWindowsStateFile,
} = hostAdapter;
// This non-exported environment helper follows the same resolved package too.
const { windowsPowerShellEnvironment } = await import(
  new URL('./desktop-platform.js', hostAdapterURL).href
);
const { DesktopFrameDecoder } = await import(new URL('./desktop-framing.js', hostAdapterURL).href);
const { startRuntimeServer } = await import(hostRuntimeURL.href);

async function protectRoot(root) {
  await chmod(root, 0o700);
  protectWindowsStateDirectory(root);
}

async function privateRoot(prefix) {
  const root = await realpath(await mkdtemp(join(tmpdir(), prefix)));
  try {
    await protectRoot(root);
    return root;
  } catch (error) {
    await rm(root, { recursive: true, force: true });
    throw error;
  }
}

async function publicReadRoot(root) {
  if (process.platform !== 'win32') return chmod(root, 0o755);
  // Change this fixture's actual DACL, as the native trace tests do. chmod
  // alone cannot exercise rejection of a public Windows read grant.
  await promisify(execFile)(
    join(process.env.SystemRoot, 'System32/WindowsPowerShell/v1.0/powershell.exe'),
    [
      '-NoProfile',
      '-NonInteractive',
      '-Command',
      "$ErrorActionPreference='Stop';$p=$env:TRACE_TEST_PATH;$a=Get-Acl -LiteralPath $p;$sid=[System.Security.Principal.SecurityIdentifier]::new('S-1-1-0');$a.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($sid,'Read','Allow'));Set-Acl -LiteralPath $p -AclObject $a",
    ],
    {
      windowsHide: true,
      timeout: 30_000,
      env: { ...windowsPowerShellEnvironment(), TRACE_TEST_PATH: root },
    },
  );
}

test(
  'macOS notifier rejects process identity races and refreshes changed executors',
  {
    skip: process.platform !== 'darwin',
  },
  async () => {
    await promisify(execFile)('/usr/bin/python3', [
      '-B',
      fileURLToPath(new URL('./desktop-completion-notify-observer-test.py', import.meta.url)),
    ]);
  },
);

const target = {
  agentId: 'codex',
  hostId: 'host-a',
  workspaceId: 'workspace-a',
  runtimeId: 'desktop-runtime',
};

test(
  'an installed node_modules adapter starts its package-local helper without a repository scripts directory',
  { skip: process.platform !== 'darwin' },
  async () => {
    const root = await privateRoot('suggestion-installed-');
    const adapter = join(root, 'node_modules/@codex-manage/codex-adapter');
    let observer;
    try {
      await mkdir(adapter, { recursive: true });
      await cp(fileURLToPath(new URL('.', hostAdapterURL)), join(adapter, 'dist'), {
        recursive: true,
      });
      await cp(
        fileURLToPath(new URL('../package.json', hostAdapterURL)),
        join(adapter, 'package.json'),
      );
      await symlink(
        fileURLToPath(new URL('../packages/protocol', import.meta.url)),
        join(root, 'node_modules/@codex-manage/protocol'),
        'dir',
      );
      await mkdir(join(adapter, 'resources'));
      // Exercise the actual worker launch and readiness protocol. This fixture
      // only stands in for Python's notifier APIs, not an original prediction.
      await writeFile(
        join(adapter, 'resources/desktop-completion-notify-observer.py'),
        'import json,time\nprint(json.dumps({"phase":"kqueue-listener-started"}),flush=True)\ntime.sleep(10)\n',
      );
      const installed = await import(
        pathToFileURL(join(adapter, 'dist/desktop-suggestion-observer.js'))
      );
      observer = new installed.DesktopSuggestionObserver({
        directory: root,
        root,
        enabled: true,
        target: () => target,
      });
      observer.start();
      for (let i = 0; i < 100 && observer.read(target).collector !== 'listening'; i++)
        await new Promise((done) => setTimeout(done, 20));
      assert.equal(observer.read(target).collector, 'listening');
    } finally {
      observer?.close();
      await rm(root, { recursive: true, force: true });
    }
  },
);
const event = (root, overrides = {}) => ({
  appGeneratedPredictionRequest: true,
  client: 'Codex Desktop',
  sourceBindingVerified: false,
  threadId: randomUUID(),
  turnId: randomUUID(),
  text: 'A captured prediction',
  observedAt: new Date().toISOString(),
  cwd: root,
  ...overrides,
});

function syntheticReaderObserver(options) {
  // Unit-only internal reader transport: no Worker launch or readiness claim.
  // Keep the real storage, native DACL and root/trace identity guards intact.
  const observer = new DesktopSuggestionObserver({ ...options, nativeWorker: true });
  assert.equal(observer.workerClient, undefined);
  if (process.platform === 'win32') {
    // Only the original-process probe is synthetic; native instance/Worker
    // behavior belongs to windows-prediction-trace and windows-trace-worker tests.
    observer.windowsInstanceStable = () => true;
  }
  return observer;
}

async function setup(run) {
  const root = await privateRoot('suggestion-observer-');
  let current = { ...target };
  const options = { directory: root, root, enabled: true, target: () => current };
  const observer = syntheticReaderObserver(options);
  try {
    await run({
      root,
      observer,
      options,
      switchTarget: (value) => {
        current = value;
      },
    });
  } finally {
    observer.close();
    await rm(root, { recursive: true, force: true });
  }
}

test('interleaved predictions retain execution IDs and never infer a source from timing or supplied hints', () =>
  setup(async ({ root, observer, options }) => {
    const a = event(root, { sourceThreadId: randomUUID(), sourceTurnId: randomUUID() });
    const b = event(root, { observedAt: a.observedAt, text: 'Another conversation' });
    observer.accept(b, target, randomUUID());
    observer.accept(a, target, randomUUID());
    observer.accept(a, target, randomUUID());
    const result = observer.read(target);
    assert.equal(result.observations.length, 2);
    assert.equal(result.sourceBinding, 'unavailable');
    assert.deepEqual(
      new Set(result.observations.map((v) => v.predictionThreadId)),
      new Set([a.threadId, b.threadId]),
    );
    assert(result.observations.every((v) => v.source === null && v.bindingStatus === 'unbound'));
    const restored = syntheticReaderObserver(options);
    try {
      assert.deepEqual(restored.read(target).observations, result.observations);
    } finally {
      restored.close();
    }
  }));

test('conflicting same-execution content stays quarantined across restart', () =>
  setup(async ({ root, observer, options }) => {
    const a = event(root);
    observer.accept(a, target, randomUUID());
    observer.accept({ ...a, text: 'Changed content' }, target, randomUUID());
    observer.accept(a, target, randomUUID());
    assert.equal(observer.read(target).observations.length, 0);
    const restored = syntheticReaderObserver(options);
    try {
      restored.accept(a, target, randomUUID());
      assert.equal(restored.read(target).observations.length, 0);
    } finally {
      restored.close();
    }
  }));

test('ordinary output, untrusted request markers, old timestamps and out-of-root observations are rejected', () =>
  setup(async ({ root, observer }) => {
    for (const changes of [
      { appGeneratedPredictionRequest: false },
      { client: 'Other client' },
      { sourceBindingVerified: true },
      { threadId: 'not-an-id' },
      { observedAt: new Date(0).toISOString() },
      { cwd: tmpdir() },
    ])
      observer.accept(event(root, changes), target, randomUUID());
    assert.equal(observer.read(target).observations.length, 0);
  }));

test('complete target and authorized-root changes cannot expose earlier observations', () =>
  setup(async ({ root, observer, options, switchTarget }) => {
    observer.accept(event(root), target, randomUUID());
    const changed = { ...target, hostId: 'host-b' };
    assert.throws(
      () => observer.read(changed),
      (error) => error.status === 409,
    );
    switchTarget(changed);
    assert.equal(observer.read(changed).observations.length, 0);
    assert.throws(
      () => observer.read(target),
      (error) => error.status === 409,
    );
    const otherRoot = await privateRoot('suggestion-root-');
    const other = syntheticReaderObserver({ ...options, root: otherRoot });
    try {
      assert.equal(other.read(changed).observations.length, 0);
    } finally {
      other.close();
      await rm(otherRoot, { recursive: true, force: true });
    }
  }));

test('corrupt private records disable only this collector and are not overwritten', () =>
  setup(async ({ root, observer, options }) => {
    observer.accept(event(root), target, randomUUID());
    const directory = join(root, 'desktop-suggestion-observations');
    const state = join(
      directory,
      (await readdir(directory)).find((v) => v.endsWith('.json')),
    );
    await writeFile(state, '{broken', { mode: 0o600 });
    await chmod(state, 0o600);
    protectWindowsStateFile(state);
    const damaged = syntheticReaderObserver(options);
    try {
      damaged.start();
      assert.equal(damaged.read(target).collector, 'unavailable');
      assert.equal(damaged.read(target).observations.length, 0);
      assert.equal(await readFile(state, 'utf8'), '{broken');
      assert.deepEqual(damaged.capabilities, []);
    } finally {
      damaged.close();
    }
  }));

test('disabled observation does not require a root; symlink retargeting cannot expand a live scope', () =>
  setup(async ({ root, options }) => {
    const disabled = syntheticReaderObserver({
      ...options,
      enabled: false,
      root: join(root, 'missing'),
    });
    assert.equal(disabled.read(target).collector, 'disabled');
    assert.throws(
      () => disabled.readBoundSource(target, randomUUID(), randomUUID()),
      (error) => error.status === 503,
    );
    disabled.close();
    const outside = await privateRoot('suggestion-outside-');
    const link = join(root, 'scope');
    const linkType = process.platform === 'win32' ? 'junction' : 'dir';
    await symlink(root, link, linkType);
    const scoped = syntheticReaderObserver({ ...options, root: link });
    try {
      await unlink(link);
      await symlink(outside, link, linkType);
      scoped.accept(event(link), target, randomUUID());
      assert.equal(scoped.read(target).observations.length, 0);
    } finally {
      scoped.close();
      await unlink(link);
      await rm(outside, { recursive: true, force: true });
    }
  }));

// Synthetic reader transport here isolates the observer/source/HTTP boundaries.
// The native file decoder has its own fixtures and separate real desktop evidence.
function nativePrediction(observation, source = {}) {
  return {
    binding: {
      predictionThreadId: observation.threadId,
      predictionTurnId: observation.turnId,
      sourceThreadId: source.threadId ?? randomUUID(),
      sourceTurnId: source.turnId ?? randomUUID(),
      evidence: { kind: 'native-rollout-trace', schemaVersion: 1, requests: [] },
    },
    text: observation.text,
    completedAt: observation.observedAt,
    responsePayloadSha256: 'a'.repeat(64),
  };
}

async function boundSetup(run) {
  return setup(async ({ root, options, switchTarget }) => {
    const traceRoot = await realpath(root);
    const observer = syntheticReaderObserver({ ...options, traceRoot });
    const predictions = new Map();
    const reads = [];
    observer.phase = 'listening';
    observer.desktopStartedAtUnixMs = 1;
    observer.predictionReader.readSourcePredictions = (threadId, turnId) =>
      [...predictions.values()].filter(
        (v) => v.binding.sourceThreadId === threadId && v.binding.sourceTurnId === turnId,
      );
    observer.predictionReader.readPrediction = (threadId, turnId) => {
      reads.push([threadId, turnId]);
      return predictions.get(JSON.stringify([threadId, turnId])) ?? null;
    };
    const add = (observation, prediction = nativePrediction(observation)) => {
      observer.accept(observation, target, randomUUID());
      predictions.set(JSON.stringify([observation.threadId, observation.turnId]), prediction);
      return prediction;
    };
    try {
      await run({ root, traceRoot, options, observer, predictions, reads, add, switchTarget });
    } finally {
      observer.close();
    }
  });
}

test('bound projection joins only the observed execution IDs and preserves exact native text', () =>
  boundSetup(async ({ root, observer, reads, add, options, traceRoot }) => {
    const first = event(root, { text: '  原建议\n保持字节，不生成。  ' });
    const second = event(root, { text: '另一个来源' });
    const a = add(first);
    const b = add(second);
    assert(observer.capabilities.includes('desktop:suggestions:read:v1'));
    assert.equal(
      observer.capabilities.includes('desktop:suggestion-observations'),
      process.platform !== 'win32',
      'Windows source recovery must not advertise macOS notification capability',
    );
    const result = observer.readBoundSource(
      target,
      a.binding.sourceThreadId,
      a.binding.sourceTurnId,
    );
    assert.equal(result.suggestion.text, first.text);
    assert.equal(
      result.suggestion.contentSha256,
      createHash('sha256').update(first.text).digest('hex'),
    );
    assert.deepEqual(reads, [[first.threadId, first.turnId]]);
    assert.equal(
      observer.readBoundSource(target, a.binding.sourceThreadId, b.binding.sourceTurnId).suggestion,
      null,
    );
    assert.equal(observer.readBoundSource(target, randomUUID(), randomUUID()).suggestion, null);
    const restored = syntheticReaderObserver({ ...options, traceRoot });
    try {
      assert.equal(restored.read(target).observations.length, 2);
      assert(
        restored
          .read(target)
          .observations.every(
            (value) => value.source === null && value.bindingStatus === 'unbound',
          ),
      );
      assert.deepEqual(restored.capabilities, []);
    } finally {
      restored.close();
    }
  }));

test('desktop restart excludes old trace output even when source turn is unchanged', () =>
  boundSetup(async ({ root, observer, add }) => {
    const old = add(event(root));
    const source = [target, old.binding.sourceThreadId, old.binding.sourceTurnId];
    assert(observer.readBoundSource(...source).suggestion);
    observer.desktopStartedAtUnixMs = Date.parse(old.completedAt) + 1;
    assert.equal(observer.readBoundSource(...source).suggestion, null);
    const next = nativePrediction(event(root, { text: 'current process prediction' }), {
      threadId: source[1],
      turnId: source[2],
    });
    next.completedAt = new Date(observer.desktopStartedAtUnixMs + 1).toISOString();
    observer.predictionReader.readSourcePredictions = (_thread, _turn, _root, notBefore) => {
      assert.equal(notBefore, observer.desktopStartedAtUnixMs);
      return [old, next];
    };
    assert.equal(observer.readBoundSource(...source).suggestion.text, next.text);
    observer.desktopStartedAtUnixMs = undefined;
    assert.throws(
      () => observer.readBoundSource(...source),
      (error) => error.status === 503,
    );
  }));

test('bound evidence is re-read and unavailable, conflicting, changed or future predictions cannot survive', () =>
  boundSetup(async ({ root, observer, predictions, add }) => {
    const observation = event(root);
    const prediction = add(observation);
    const source = [target, prediction.binding.sourceThreadId, prediction.binding.sourceTurnId];
    assert(observer.readBoundSource(...source).suggestion);
    predictions.clear();
    assert.equal(observer.readBoundSource(...source).suggestion, null);
    add(observation, { ...prediction, text: observation.text + ' ' });
    assert.equal(observer.readBoundSource(...source).suggestion, null);
    add(observation, { ...prediction, completedAt: new Date(Date.now() + 120_000).toISOString() });
    assert.equal(observer.readBoundSource(...source).suggestion, null);
    add(observation, prediction);
    const duplicate = event(root, { text: '第二个不同建议' });
    add(
      duplicate,
      nativePrediction(duplicate, {
        threadId: prediction.binding.sourceThreadId,
        turnId: prediction.binding.sourceTurnId,
      }),
    );
    assert.equal(observer.readBoundSource(...source).suggestion, null);
    observer.accept({ ...observation, text: '冲突通知' }, target, randomUUID());
    assert.equal(observer.read(target).observations.length, 1);
  }));

test('the final availability sample reuses the existing guard for present, empty and lost readiness', () =>
  boundSetup(async ({ root, observer, predictions, add, switchTarget }) => {
    const prediction = add(event(root));
    const source = [target, prediction.binding.sourceThreadId, prediction.binding.sourceTurnId];
    const guard = observer.boundReadAvailable.bind(observer);
    let probes = 0;
    observer.boundReadAvailable = (...args) => {
      probes++;
      return guard(...args);
    };
    const sample = (expected, present) => {
      probes = 0;
      const samples = [];
      const result = observer.readBoundSource(...source, (value) => samples.push(value));
      assert.deepEqual(samples, [expected]);
      assert.equal(
        probes,
        2,
        'Use the existing ingress/final checks, without another native probe',
      );
      assert.equal(result.suggestion !== null, present);
    };
    sample(true, true);
    predictions.clear();
    sample(true, false);
    const read = observer.predictionReader.readSourcePredictions;
    try {
      observer.predictionReader.readSourcePredictions = (...args) => {
        const result = read(...args);
        observer.phase = 'unavailable';
        return result;
      };
      sample(false, false);
      observer.phase = 'listening';
      const samples = [];
      observer.predictionReader.readSourcePredictions = (...args) => {
        const result = read(...args);
        switchTarget({ ...target, runtimeId: 'changed-during-read' });
        return result;
      };
      assert.throws(
        () => observer.readBoundSource(...source, (value) => samples.push(value)),
        (error) => error.status === 409,
      );
      assert.deepEqual(samples, [], 'A failed read must never deliver a successful final sample');
    } finally {
      observer.predictionReader.readSourcePredictions = read;
      switchTarget(target);
      observer.phase = 'listening';
    }
  }));

test('native version lease renews current evidence without changing prediction identity', () =>
  boundSetup(async ({ root, observer }) => {
    const native = nativePrediction(event(root));
    native.completedAt = new Date(Date.now() - 2 * 3600_000).toISOString();
    let recovered = [native];
    observer.predictionReader.readSourcePredictions = () => recovered;
    const source = [target, native.binding.sourceThreadId, native.binding.sourceTurnId];
    const first = observer.readBoundSource(...source).suggestion;
    assert(first, 'Current source evidence must not expire merely because an hour passed');
    assert.equal(first.validity, 'native-version');
    assert.equal(observer.readBoundSource(...source).source, 'native-trace');
    assert.equal(first.text, native.text);
    assert(Date.parse(first.expiresAt) > Date.now());
    assert(Date.parse(first.expiresAt) <= Date.now() + 6000);
    assert.equal(observer.readBoundSource(...source).suggestion.id, first.id);
    recovered = [];
    assert.equal(
      observer.readBoundSource(...source).suggestion,
      null,
      'Missing evidence still invalidates',
    );
    recovered = [
      native,
      nativePrediction(event(root), {
        threadId: native.binding.sourceThreadId,
        turnId: native.binding.sourceTurnId,
      }),
    ];
    assert.equal(
      observer.readBoundSource(...source).suggestion,
      null,
      'Ambiguous evidence still invalidates',
    );
  }));

test('missed notifications recover with a finite lease without bypassing conflicts and ambiguity', () =>
  boundSetup(async ({ root, observer, predictions }) => {
    const notice = event(root);
    const prediction = nativePrediction(notice);
    let recovered = [prediction];
    observer.predictionReader.readSourcePredictions = () => recovered;
    const source = [target, prediction.binding.sourceThreadId, prediction.binding.sourceTurnId];
    const first = observer.readBoundSource(...source).suggestion;
    assert(first);
    assert.equal(first.text, prediction.text);
    assert(Date.parse(first.expiresAt) > Date.now());
    assert.equal(
      observer.read(target).observations.length,
      0,
      'Recovery does not fabricate notifier records',
    );
    observer.accept(notice, target, randomUUID());
    predictions.set(JSON.stringify([notice.threadId, notice.turnId]), prediction);
    assert.equal(observer.readBoundSource(...source).suggestion.id, first.id);
    recovered = [
      prediction,
      nativePrediction(event(root), {
        threadId: source[1],
        turnId: source[2],
      }),
    ];
    assert.equal(observer.readBoundSource(...source).suggestion, null);
    recovered = null;
    assert.equal(
      observer.readBoundSource(...source).suggestion,
      null,
      'An incomplete scan cannot use a partial match',
    );
    recovered = [prediction];
    observer.accept({ ...notice, text: 'Conflicting original notification' }, target, randomUUID());
    assert.equal(
      observer.readBoundSource(...source).suggestion,
      null,
      'Recovery cannot bypass quarantine',
    );
  }));

test('bound capability requires a live listener, readable private trace root and unchanged scope', () =>
  boundSetup(async ({ root, observer, add, traceRoot, switchTarget }) => {
    const prediction = add(event(root));
    const source = [target, prediction.binding.sourceThreadId, prediction.binding.sourceTurnId];
    assert(observer.readBoundSource(...source).suggestion);
    observer.phase = 'unavailable';
    assert.deepEqual(observer.capabilities, []);
    assert.throws(
      () => observer.readBoundSource(...source),
      (error) => error.status === 503,
    );
    observer.phase = 'listening';
    await publicReadRoot(traceRoot);
    assert(!observer.capabilities.includes('desktop:suggestions:read:v1'));
    await protectRoot(traceRoot);
    switchTarget({ ...target, runtimeId: 'other-runtime' });
    assert.throws(
      () => observer.readBoundSource(...source),
      (error) => error.status === 409,
    );
    switchTarget(target);
    const oldRoot = traceRoot + '-original';
    await rename(traceRoot, oldRoot);
    await mkdir(traceRoot, { mode: 0o700 });
    try {
      await protectRoot(traceRoot);
      assert(!observer.capabilities.includes('desktop:suggestions:read:v1'));
      assert.throws(
        () => observer.readBoundSource(...source),
        (error) => error.status === 409,
      );
    } finally {
      await rm(traceRoot, { recursive: true });
      await rename(oldRoot, traceRoot);
    }
  }));

test(
  'a nonempty read-only window cannot restore owner control or a cached suggestion source',
  { timeout: 30_000 },
  async () => {
    const root = await privateRoot('suggestion-live-window-');
    let session, transport;
    try {
      const threadId = randomUUID(),
        sourceTurnId = randomUUID(),
        itemId = randomUUID();
      const snapshot = {
        id: threadId,
        hostId: 'local',
        cwd: root,
        requests: [],
        threadRuntimeStatus: { type: 'idle' },
        turnHistory: {
          kind: 'canonical',
          history: {
            generation: 0,
            isComplete: true,
            entitiesByKey: {
              [sourceTurnId]: {
                turnId: sourceTurnId,
                status: 'completed',
                items: [
                  { id: itemId, type: 'agentMessage', text: 'baseline', status: 'completed' },
                ],
              },
            },
            islands: [{ entries: [{ value: sourceTurnId }] }],
          },
        },
      };
      session = new RuntimeSession({
        directory: join(root, 'state'),
        root,
        name: 'Synthetic live window',
        mode: 'desktop-runtime',
        socket: join(root, 'unused.sock'),
        executable: join(root, 'no-real-codex'),
      });
      const { runtime } = session,
        { ipc } = runtime;
      session.state.threads = [
        {
          id: threadId,
          hostId: session.hostId,
          projectId: null,
          title: 'Synthetic live window',
          state: 'unknown',
          turnId: null,
          messages: [],
          plan: [],
          error: null,
        },
      ];
      // Only the connection boundary and unrelated metadata are synthetic.
      // Never connect to the real desktop, its native pipe, history or queue.
      Object.defineProperty(runtime.reader, 'ready', { value: true, configurable: true });
      session.nativeQueue.list = async () => [];
      let publishSnapshot = true;
      const methods = [];
      const frame = (value) => {
        const body = Buffer.from(JSON.stringify(value)),
          header = Buffer.alloc(4);
        header.writeUInt32LE(body.length);
        return Buffer.concat([header, body]);
      };
      const send = (value) =>
        queueMicrotask(() => {
          if (!transport.destroyed) transport.push(frame(value));
        });
      const broadcast = (change) =>
        send({
          type: 'broadcast',
          method: 'thread-stream-state-changed',
          version: 11,
          sourceClientId: 'synthetic-owner',
          params: { hostId: 'local', conversationId: threadId, change },
        });
      const ownerFrames = new DesktopFrameDecoder((body) => {
        const message = JSON.parse(body.toString());
        methods.push(message.method);
        if (message.type === 'request') {
          const result =
            message.method === 'initialize'
              ? { clientId: 'synthetic-reader' }
              : message.method === 'thread-owner-discovery'
                ? { supportsUntrustedAppInput: true }
                : message.method === 'thread-follower-read-model-settings'
                  ? { settings: { model: 'fixture', reasoningEffort: 'xhigh' } }
                  : assert.fail('Unexpected execution request: ' + message.method);
          send({
            type: 'response',
            requestId: message.requestId,
            resultType: 'success',
            handledByClientId: 'synthetic-owner',
            method: message.method,
            result,
          });
        } else {
          assert.equal(message.method, 'thread-stream-following-changed');
          if (publishSnapshot)
            broadcast({ type: 'snapshot', revision: 10, conversationState: snapshot });
        }
      });
      const readerFrames = new DesktopFrameDecoder((body) =>
        ipc.receive(JSON.parse(body.toString())),
      );
      // In-memory framed transport works on macOS and Windows without changing
      // production connect guards. Owner/state/follow/source decisions stay real.
      transport = new Duplex({
        read() {},
        write(bytes, _encoding, done) {
          ownerFrames.push(bytes);
          done();
        },
      });
      transport.on('data', (bytes) => readerFrames.push(bytes));
      ipc.socket = transport;
      const initialized = await ipc.request('initialize', { clientType: 'codex-manage-fixture' });
      ipc.clientId = initialized.result.clientId;
      ipc.ready = true;
      await session.open(threadId);
      assert.deepEqual(await session.desktopSuggestionSource(threadId), {
        threadId,
        sourceTurnId,
      });
      assert.equal(session.history(threadId).runtime.suggestionSourceTurnId, sourceTurnId);
      const baseline = structuredClone(session.history(threadId).messages);
      assert.equal(baseline[0].id, itemId);

      // Lose only the full snapshot, retaining its verified owner and seeded
      // bounded window, as in the missing-base stream-generation regression.
      ipc.states.delete(threadId);
      ipc.deferSnapshot(threadId, 30_000);
      publishSnapshot = false;
      await ipc.refreshObservation(threadId);
      broadcast({
        type: 'patches',
        baseRevision: 10,
        revision: 11,
        patches: [
          {
            op: 'replace',
            path: ['turnHistory', 'history', 'entitiesByKey', sourceTurnId, 'items', 0, 'text'],
            value: 'baseline + read-only progress',
          },
        ],
      });
      await new Promise((done) => setImmediate(done));
      assert.equal(ipc.observation(threadId).items[0].item.text, 'baseline + read-only progress');
      runtime.reader.history = async (requested) => {
        assert.equal(requested, threadId);
        return {
          hostId: session.hostId,
          threadId,
          messages: structuredClone(baseline),
          turns: [],
          historyMode: 'legacy',
          nextCursor: null,
        };
      };
      const history = await session.refreshHistory(threadId, undefined, true);
      assert.equal(history.synchronization.state, 'live-window');
      assert.equal(ipc.observation(threadId).seeded, true);
      assert.equal(ipc.observation(threadId).items.length, 1);
      assert.equal(history.messages[0].text, 'baseline + read-only progress');
      assert.equal(ipc.state(threadId), undefined);
      assert.equal(ipc.hasOwner(threadId), false);
      assert.equal(
        session.desktopSuggestionSources.get(threadId),
        sourceTurnId,
        'A previously confirmed source is still cached, exercising the publication guard',
      );
      assert.equal(history.runtime.canControl, false);
      assert.equal(history.runtime.suggestionSourceTurnId, null);
      const events = await session.poll(
        0,
        'fixture-reset',
        new AbortController().signal,
        threadId,
        true,
      );
      assert.equal(events.events[0].snapshot.messages[0].text, 'baseline + read-only progress');
      assert.equal(events.events[0].snapshot.runtime.canControl, false);
      assert.equal(events.events[0].snapshot.runtime.suggestionSourceTurnId, null);
      await assert.rejects(
        session.desktopSuggestionSource(threadId),
        (error) => error.status === 503,
      );
      assert.equal(ipc.hasOwner(threadId), false);
      assert.equal(session.desktopSuggestionSources.has(threadId), false);

      // Only a fresh complete original-owner snapshot restores authority.
      publishSnapshot = true;
      assert.deepEqual(await session.desktopSuggestionSource(threadId), { threadId, sourceTurnId });
      assert.equal(ipc.hasOwner(threadId), true);
      assert.equal(session.history(threadId).runtime.canControl, true);
      assert.equal(session.history(threadId).runtime.suggestionSourceTurnId, sourceTurnId);
      assert(
        methods.every((method) =>
          [
            'initialize',
            'thread-owner-discovery',
            'thread-stream-following-changed',
            'thread-follower-read-model-settings',
          ].includes(method),
        ),
      );
    } finally {
      try {
        session?.close();
      } finally {
        transport?.destroy();
        await rm(root, { recursive: true, force: true });
      }
    }
  },
);

test('pending source revalidation preserves confirmed snapshots without retaining an invalid owner', async () => {
  const root = await privateRoot('suggestion-revalidation-');
  const session = new RuntimeSession({
    directory: join(root, 'state'),
    root,
    name: 'Revalidation fixture',
    mode: 'desktop-runtime',
    socket: join(root, 'unused.sock'),
  });
  const threadId = randomUUID(),
    sourceTurnId = randomUUID();
  const snapshot = {
    id: threadId,
    hostId: 'local',
    threadRuntimeStatus: { type: 'idle' },
    turns: [{ turnId: sourceTurnId, status: 'completed', items: [] }],
    requests: [],
  };
  const { runtime } = session;
  Object.defineProperty(runtime, 'ready', { value: true, writable: true });
  let owner = true;
  runtime.ipc.hasOwner = () => owner;
  runtime.ipc.follow = async () => structuredClone(snapshot);
  session.state.threads = [
    {
      id: threadId,
      hostId: session.hostId,
      projectId: null,
      title: 'Revalidation',
      state: 'idle',
      turnId: null,
      messages: [],
      plan: [],
      error: null,
    },
  ];
  try {
    const gate = Promise.withResolvers();
    runtime.ipc.follow = () => gate.promise;
    const initial = session.desktopSuggestionSource(threadId);
    assert.equal(session.history(threadId).runtime.suggestionSourceTurnId, null);
    gate.resolve(structuredClone(snapshot));
    await initial;

    const pending = Promise.withResolvers();
    runtime.ipc.follow = () => pending.promise;
    const read = session.desktopSuggestionSource(threadId);
    try {
      assert.equal(session.history(threadId).runtime.suggestionSourceTurnId, sourceTurnId);
      const batch = await session.poll(0, 'new-reset', new AbortController().signal);
      assert.equal(batch.events[0].snapshot.runtime.suggestionSourceTurnId, sourceTurnId);
    } finally {
      pending.resolve(structuredClone(snapshot));
      await read;
    }

    const invalid = Promise.withResolvers();
    runtime.ipc.follow = () => invalid.promise;
    const revalidating = session.desktopSuggestionSource(threadId);
    session.runtime.onEvent('turn/started', { threadId, turn: { id: 'new-turn' } });
    assert.equal(session.history(threadId).runtime.suggestionSourceTurnId, null);
    invalid.resolve({ ...snapshot, threadRuntimeStatus: { type: 'active' } });
    await assert.rejects(revalidating, /最新的已完成轮次/);
    assert.equal(session.history(threadId).runtime.suggestionSourceTurnId, null);

    runtime.ipc.follow = async () => structuredClone(snapshot);
    session.runtime.onEvent('desktop/snapshot', { threadId, state: snapshot });
    await session.desktopSuggestionSource(threadId);
    runtime.ipc.follow = async () => {
      throw new Error('Owner read failed');
    };
    await assert.rejects(session.desktopSuggestionSource(threadId), /Owner read failed/);
    assert.equal(session.history(threadId).runtime.suggestionSourceTurnId, null);

    runtime.ipc.follow = async () => structuredClone(snapshot);
    await session.desktopSuggestionSource(threadId);
    owner = false;
    await assert.rejects(session.desktopSuggestionSource(threadId), /原 owner 不可用/);
    assert.equal(session.history(threadId).runtime.suggestionSourceTurnId, null);
  } finally {
    session.close();
    await rm(root, { recursive: true, force: true });
  }
});

test('production HTTP suggestion read fences both owner snapshots and target; runtime events clear the source', async (t) => {
  const root = await privateRoot('suggestion-host-');
  const originals = {
    domRead: DesktopDomBridge.prototype.readExisting,
    connect: RuntimeSession.prototype.connect,
    start: DesktopSuggestionObserver.prototype.start,
    readiness: DesktopTranscription.prototype.readiness,
    workspace: DesktopWorkspaceView.prototype.workspace,
  };
  const environment = { ...process.env };
  const threadId = randomUUID();
  const sourceTurnId = randomUUID();
  const predictionEvent = event(root, { text: '真实路由的合成边界测试' });
  const prediction = nativePrediction(predictionEvent, { threadId, turnId: sourceTurnId });
  const snapshot = {
    id: threadId,
    hostId: 'local',
    cwd: root,
    threadRuntimeStatus: { type: 'idle' },
    turns: [{ turnId: sourceTurnId, status: 'completed', items: [] }],
    requests: [],
  };
  let owner = true;
  let readCount = 0;
  let onFollow = () => {};
  let onPrediction = () => prediction;
  const collector = { current: null };
  const mockedConstructors = new Set();
  const assertHostConstructor = (instance, name) => {
    assert.equal(instance.constructor, hostAdapter[name], 'Host/fixture ESM identity: ' + name);
    mockedConstructors.add(name);
  };
  let service;
  const displayedText = '桌面重启后实际显示的另一版建议，逐字返回。';
  try {
    const bridgeConfig = join(root, 'display-bridge.json');
    await writeFile(
      bridgeConfig,
      JSON.stringify({ endpoint: 'http://127.0.0.1:9222', targetId: 'fixture', origin: 'app://-' }),
      { mode: 0o600 },
    );
    protectWindowsStateFile(bridgeConfig);
    assertPrivateFile(bridgeConfig);
    DesktopDomBridge.prototype.readExisting = async function (requested) {
      assertHostConstructor(this, 'DesktopDomBridge');
      assert.equal(requested, threadId);
      return {
        status: onPrediction() ? 'present' : 'empty',
        source: 'desktop-dom',
        representation: 'rendered-text',
        threadId,
        observedAt: new Date().toISOString(),
        ...(onPrediction()
          ? {
              text: displayedText,
              contentSha256: createHash('sha256').update(displayedText).digest('hex'),
            }
          : {}),
      };
    };
    RuntimeSession.prototype.connect = async function () {
      assertHostConstructor(this, 'RuntimeSession');
      Object.defineProperty(this.runtime, 'ready', { value: true, writable: true });
      this.runtime.ipc.hasOwner = () => owner;
      this.runtime.ipc.follow = async () => {
        await onFollow(++readCount);
        return structuredClone(snapshot);
      };
      this.state.threads = [
        {
          id: threadId,
          hostId: this.hostId,
          projectId: null,
          title: 'Synthetic source',
          state: 'idle',
          turnId: null,
          messages: [],
          plan: [],
          error: null,
        },
      ];
    };
    DesktopSuggestionObserver.prototype.start = function () {
      assertHostConstructor(this, 'DesktopSuggestionObserver');
      if (this.workerClient) {
        assert.equal(process.platform, 'win32');
        assert.equal(
          this.workerClient.worker,
          undefined,
          'Production Worker must remain unstarted',
        );
        assert.equal(this.workerClient.pending, undefined);
        this.workerClient.close();
        assert.equal(this.workerClient.closed, true);
      }
      // Host closures already reference this production instance. Replace its
      // state with the explicit synthetic internal reader, preserving that identity.
      Object.assign(this, syntheticReaderObserver(this.options));
      assert.equal(this.workerClient, undefined);
      // Check the real native root guard before timing the pending-voice reads,
      // so cold helper compilation is not confused with waiting on voice metadata.
      assert.equal(this.predictionReader.isAvailable(), true);
      collector.current = this;
      this.phase = 'listening';
      this.desktopStartedAtUnixMs = 1;
      this.predictionReader.readSourcePredictions = () => (onPrediction() ? [onPrediction()] : []);
      this.predictionReader.readPrediction = (predictionThreadId, predictionTurnId) => {
        assert.equal(predictionThreadId, predictionEvent.threadId);
        assert.equal(predictionTurnId, predictionEvent.turnId);
        return onPrediction();
      };
      this.accept(predictionEvent, this.options.target(), randomUUID());
    };
    DesktopTranscription.prototype.readiness = async function () {
      assertHostConstructor(this, 'DesktopTranscription');
      return { available: false, reason: 'Fixture', principal: null };
    };
    DesktopWorkspaceView.prototype.workspace = async function () {
      assertHostConstructor(this, 'DesktopWorkspaceView');
      return {
        hostId: this.session.hostId,
        ready: this.session.ready,
        capabilities: this.session.capabilities,
        projects: [],
        threads: [],
        nextProjectCursor: null,
        nextThreadCursor: null,
      };
    };
    const token = randomUUID() + randomUUID();
    Object.assign(process.env, {
      CODEX_RUNTIME_MODE: 'desktop-runtime',
      HOST_AGENT_STATE_DIR: join(root, 'state'),
      HOST_AGENT_ROOT: root,
      HOST_AGENT_TOKEN: token,
      HOST_AGENT_PORT: '0',
      HOST_AGENT_BIND: '127.0.0.1',
      HOST_AGENT_DISCOVERY: '0',
      CODEX_DESKTOP_SUGGESTION_OBSERVER: '1',
      CODEX_DESKTOP_SUGGESTION_TRACE_ROOT: root,
      CODEX_DESKTOP_DOM_BRIDGE_CONFIG: bridgeConfig,
    });
    for (const name of [
      'CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG',
      'CODEX_DESKTOP_CONTROLLER_CONFIG',
      'CODEX_DESKTOP_WINDOWS_CONFIG',
      'HOST_AGENT_TLS_CERT',
      'HOST_AGENT_TLS_KEY',
      'HOST_NOTIFICATION_RELAY_URL',
      'HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE',
    ])
      delete process.env[name];
    service = await startRuntimeServer();
    const { session } = service;
    assert.equal(session.constructor, RuntimeSession, 'Host must use the resolved Adapter session');
    const current = session.readingTarget();
    const assertCapabilityProjection = async (available, displayed = available) => {
      const values = await Promise.all(
        ['/v1/workspace', '/v1/events'].map(async (path) => {
          // A reset epoch makes the production poll return immediately, with
          // actual runtime snapshots and without requesting native queue history.
          const query = new URLSearchParams({ hostId: session.hostId, epoch: 'fixture-reset' });
          const response = await fetch(
            `http://127.0.0.1:${service.server.address().port}${path}?${query}`,
            {
              headers: { Authorization: `Bearer ${token}` },
            },
          );
          assert.equal(response.status, 200);
          const value = await response.json();
          if (process.platform === 'win32')
            assert.equal(value.capabilities.includes('desktop:suggestion-observations'), false);
          assert.equal(value.capabilities.includes('desktop:suggestions:read:v1'), available, path);
          assert.equal(
            value.capabilities.includes('desktop:suggestions:displayed:v1'),
            displayed,
            path,
          );
          return value.capabilities;
        }),
      );
      assert.deepEqual(values[0], values[1]);
    };
    let finishVoice;
    let voiceReads = 0;
    DesktopTranscription.prototype.readiness = function () {
      assertHostConstructor(this, 'DesktopTranscription');
      voiceReads++;
      return new Promise((done) => {
        finishVoice = done;
      });
    };
    try {
      for (const path of ['/healthz', '/v1/workspace']) {
        const response = await fetch(`http://127.0.0.1:${service.server.address().port}${path}`, {
          headers: { Authorization: `Bearer ${token}` },
          signal: AbortSignal.timeout(2000),
        });
        assert.equal(response.status, 200);
        assert.equal((await response.json()).capabilities.includes('voice:transcribe'), false);
      }
      assert.equal(voiceReads, 1, 'Core reads must not await or duplicate pending voice metadata');
    } finally {
      finishVoice?.({ available: false, reason: null, principal: null });
      await new Promise((done) => setImmediate(done));
      DesktopTranscription.prototype.readiness = async function () {
        assertHostConstructor(this, 'DesktopTranscription');
        return { available: false, reason: null, principal: null };
      };
    }
    await assertCapabilityProjection(true);
    for (const phase of ['starting', 'unavailable']) {
      collector.current.phase = phase;
      await assertCapabilityProjection(true);
    }
    collector.current.phase = 'listening';
    await assertCapabilityProjection(true);
    await publicReadRoot(root);
    await assertCapabilityProjection(true);
    await protectRoot(root);
    await assertCapabilityProjection(true);
    const parameters = () =>
      new URLSearchParams({
        hostId: session.hostId,
        target: JSON.stringify(current),
        threadId,
        sourceTurnId,
      });
    let nativeRoute = false;
    const request = async (query = parameters(), authorization = token, method = 'GET') => {
      readCount = 0;
      const before = Date.now();
      const response = await fetch(
        `http://127.0.0.1:${service.server.address().port}/v1/threads/next-turn-suggestion?${query}`,
        {
          method,
          headers: { Authorization: `Bearer ${authorization}` },
          ...(method === 'POST' ? { body: JSON.stringify({ hostId: session.hostId }) } : {}),
        },
      );
      const body = await response.json();
      const after = Date.now();
      const stamp = response.headers.get('X-Agent-Hub-Suggestion-Response-Unix-Ms');
      let responseUnixMs = null;
      if (nativeRoute && response.status === 200) {
        assert.match(stamp ?? '', /^[1-9]\d*$/, 'Native 200 needs an integer millisecond stamp');
        responseUnixMs = Number(stamp);
        assert(Number.isSafeInteger(responseUnixMs) && responseUnixMs > 0);
        assert(
          responseUnixMs >= before && responseUnixMs <= after,
          'Response time must be independently sampled during this localhost request',
        );
        assert.equal(body.source, 'native-trace');
        if (body.suggestion) {
          const remaining = Date.parse(body.suggestion.expiresAt) - responseUnixMs;
          assert(
            remaining > 0 && remaining <= 6000,
            'Same-clock native lease must be in (0, 6000]',
          );
        }
      } else
        assert.equal(stamp, null, 'DOM and error routes must not carry a native success stamp');
      return { status: response.status, body, responseUnixMs };
    };
    assert.equal((await request(undefined, 'wrong-token')).status, 401);
    assert.equal((await request(undefined, token, 'POST')).status, 405);
    for (const field of ['target', 'threadId', 'sourceTurnId']) {
      const query = parameters();
      query.delete(field);
      assert.equal((await request(query)).status, 400);
    }
    for (const field of ['hostId', 'target', 'threadId', 'sourceTurnId']) {
      const query = parameters();
      query.append(field, query.get(field));
      assert.equal((await request(query)).status, 400);
    }
    const extra = parameters();
    extra.set('generate', '1');
    assert.equal((await request(extra)).status, 400);
    const account = parameters();
    account.set(
      'target',
      JSON.stringify({ ...current, account: { authority: 'legacy', subject: 'metadata' } }),
    );
    assert.equal((await request(account)).status, 200);
    for (const field of ['agentId', 'hostId', 'workspaceId', 'runtimeId']) {
      const query = parameters();
      query.set('target', JSON.stringify({ ...current, [field]: 'other' }));
      assert.equal((await request(query)).status, 409);
    }
    let result = await request();
    assert.equal(result.status, 200);
    assert.equal(result.body.suggestion.text, displayedText);
    assert.equal(readCount, 4);
    assert.equal(session.history(threadId).runtime.suggestionSourceTurnId, sourceTurnId);
    session.runtime.onEvent('desktop/snapshot', {
      threadId,
      state: { ...snapshot, threadRuntimeStatus: { type: 'active' } },
    });
    assert.equal(session.history(threadId).runtime.suggestionSourceTurnId, null);
    session.runtime.onEvent('desktop/snapshot', { threadId, state: snapshot });
    assert.equal(session.history(threadId).runtime.suggestionSourceTurnId, sourceTurnId);
    session.runtime.onEvent('desktop/unavailable', { threadId });
    assert.equal(session.history(threadId).runtime.suggestionSourceTurnId, null);
    session.runtime.onEvent('desktop/snapshot', { threadId, state: snapshot });
    owner = false;
    assert.equal(session.history(threadId).runtime.suggestionSourceTurnId, null);
    assert.equal((await request()).status, 503);
    owner = true;
    onFollow = (count) => {
      if (count === 2) snapshot.turns[0].turnId = randomUUID();
    };
    assert.equal((await request()).status, 409);
    snapshot.turns[0].turnId = sourceTurnId;
    onFollow = (count) => {
      if (count === 2) snapshot.threadRuntimeStatus.type = 'active';
    };
    assert.equal((await request()).status, 409);
    snapshot.threadRuntimeStatus.type = 'idle';
    onFollow = () => {};
    onPrediction = () => null;
    result = await request();
    assert.equal(result.status, 200);
    assert.equal(result.body.suggestion, null);
    onPrediction = () => prediction;
    onFollow = (count) => {
      if (count === 2) onPrediction = () => null;
    };
    assert.equal((await request()).status, 409);
    onFollow = () => {};
    collector.current.phase = 'unavailable';
    assert.equal((await request()).status, 200);
    session.runtime.ready = false;
    session.runtime.onDisconnect();
    assert.equal(session.history(threadId).runtime.suggestionSourceTurnId, null);
    service.close();
    delete process.env.CODEX_DESKTOP_DOM_BRIDGE_CONFIG;
    nativeRoute = true;
    onFollow = () => {};
    onPrediction = () => prediction;
    service = await startRuntimeServer();
    await assertCapabilityProjection(true, false);
    const nativeResult = await request();
    assert.equal(nativeResult.status, 200);
    assert.equal(nativeResult.body.source, 'native-trace');
    assert.equal(nativeResult.body.suggestion.validity, 'native-version');
    assert.equal(nativeResult.body.suggestion.text, prediction.text);
    assert.equal(readCount, 3);
    const observer = collector.current;
    const nativeRead = observer.readBoundSourceAsync.bind(observer);
    // Use the real clock to cross the first lease; the binding and native text
    // stay unchanged, and the fresh response still has a strict six-second cap.
    await new Promise((done) => setTimeout(done, 6100));
    let renewalReads = 0;
    let confirmedProjectionReturnedAt;
    observer.readBoundSourceAsync = async (...args) => {
      const response = await nativeRead(...args);
      if (++renewalReads === 2) {
        // Separate projection/expiry time from the later response timestamp.
        // Deriving the header from expiresAt, or sampling it before this final
        // asynchronous read, must fail even when the lease happens to be 6s.
        await new Promise((done) => setTimeout(done, 25));
        confirmedProjectionReturnedAt = Date.now();
      }
      return response;
    };
    try {
      const renewed = await request();
      assert.equal(renewed.status, 200);
      assert.equal(renewalReads, 2);
      assert.equal(readCount, 3);
      assert(renewed.responseUnixMs - nativeResult.responseUnixMs >= 6000);
      assert(renewed.responseUnixMs >= confirmedProjectionReturnedAt);
      assert.deepEqual(
        { ...renewed.body.suggestion, expiresAt: null },
        { ...nativeResult.body.suggestion, expiresAt: null },
        'Renewal retains the same exact native suggestion identity and content',
      );
      assert(
        Date.parse(renewed.body.suggestion.expiresAt) >
          Date.parse(nativeResult.body.suggestion.expiresAt),
      );
    } finally {
      observer.readBoundSourceAsync = nativeRead;
    }
    for (const field of ['agentId', 'hostId', 'workspaceId', 'runtimeId']) {
      const query = parameters();
      query.set('target', JSON.stringify({ ...current, [field]: 'other' }));
      assert.equal((await request(query)).status, 409);
      assert.equal(readCount, 0, 'A wrong target is rejected before any native source read');
    }
    const wrongSource = parameters();
    wrongSource.set('sourceTurnId', randomUUID());
    assert.equal((await request(wrongSource)).status, 409);
    for (const change of ['turn', 'active', 'owner']) {
      let reads = 0;
      observer.readBoundSourceAsync = async (...args) => {
        const response = await nativeRead(...args);
        if (++reads === 2) {
          // A real async Worker yields after the previous owner snapshot.
          await new Promise((done) => setImmediate(done));
          if (change === 'turn') snapshot.turns[0].turnId = randomUUID();
          if (change === 'active') snapshot.threadRuntimeStatus.type = 'active';
          if (change === 'owner') owner = false;
        }
        return response;
      };
      assert.equal(
        (await request()).status,
        change === 'owner' ? 503 : 409,
        'A source/owner change during the final async projection must reject old evidence',
      );
      snapshot.turns[0].turnId = sourceTurnId;
      snapshot.threadRuntimeStatus.type = 'idle';
      owner = true;
    }
    observer.readBoundSourceAsync = nativeRead;
    let clock;
    let leaseReads = 0;
    observer.readBoundSourceAsync = async (...args) => {
      const response = await nativeRead(...args);
      if (++leaseReads === 2) {
        assert(response.suggestion);
        await new Promise((done) => setImmediate(done));
        const expiresAt = Date.parse(response.suggestion.expiresAt);
        assert(expiresAt > Date.now());
        // Advance only the unit test clock to the actual lease boundary; never
        // edit the response's expiry or wait six seconds on every platform.
        clock = t.mock.method(Date, 'now', () => expiresAt);
      }
      return response;
    };
    try {
      const expired = await request();
      assert.equal(leaseReads, 2);
      assert.equal(expired.status, 200);
      assert.equal(
        expired.body.suggestion,
        null,
        'Final async owner check cannot return an expired lease',
      );
    } finally {
      clock?.mock.restore();
      observer.readBoundSourceAsync = nativeRead;
    }
    onFollow = (count) => {
      if (count === 2) onPrediction = () => null;
    };
    assert.equal(
      (await request()).body.suggestion,
      null,
      'Native version changing during owner check clears the response',
    );
    onFollow = () => {};
    onPrediction = () => prediction;
    // Fixed synthetic transport boundary only; real Host/source orchestration remains intact.
    const priorPrepare = observer.prepareBoundSourceAsync;
    Object.defineProperty(observer, 'usesFencedSourceRead', {
      configurable: true,
      get: () => true,
    });
    let phases = [];
    observer.prepareBoundSourceAsync = async () => {
      phases.push('prepare');
      return {
        commit: async () => {
          phases.push('commit');
          return nativeRead(current, threadId, sourceTurnId);
        },
        dispose: () => phases.push('dispose'),
      };
    };
    try {
      onFollow = (count) => phases.push('source' + count);
      assert.equal((await request()).status, 200);
      assert.deepEqual(phases, ['source1', 'prepare', 'source2', 'commit', 'source3', 'dispose']);
      for (const boundary of [2, 3]) {
        phases = [];
        onFollow = (count) => {
          phases.push('source' + count);
          if (count === boundary) snapshot.turns[0].turnId = randomUUID();
        };
        assert.equal((await request()).status, 409);
        assert.equal(phases.includes('commit'), boundary === 3);
        assert.equal(phases.at(-1), 'dispose');
        snapshot.turns[0].turnId = sourceTurnId;
      }
      for (const boundary of [1, 2, 3]) {
        phases = [];
        const originalNow = performance.now.bind(performance);
        let forcedNow;
        const monotonic = t.mock.method(performance, 'now', () => forcedNow ?? originalNow());
        try {
          onFollow = (count) => {
            if (count === boundary) forcedNow = originalNow() + 8001;
          };
          assert.equal(
            (await request()).status,
            503,
            'Every source await consumes the original HTTP deadline',
          );
          assert.equal(phases.includes('prepare'), boundary !== 1);
          assert.equal(phases.includes('commit'), boundary === 3);
        } finally {
          monotonic.mock.restore();
        }
      }
      // A bottom-level owner read may ignore cancellation. Its late success must never commit.
      phases = [];
      let release, entered;
      const waiting = new Promise((done) => {
        entered = done;
      });
      onFollow = (count) =>
        count === 2
          ? new Promise((done) => {
              release = done;
              entered();
            })
          : undefined;
      const pendingHttp = request();
      // Preserve the first assertion failure if fixture cleanup closes this pending request.
      void pendingHttp.catch(() => {});
      await waiting;
      const busyStarted = performance.now();
      assert.equal((await request()).status, 503);
      assert.equal(readCount, 0, 'Busy request cannot start another source guard');
      assert(performance.now() - busyStarted < 500, 'Host rejects busy without a queue');
      const healthStarted = performance.now();
      const health = await fetch(`http://127.0.0.1:${service.server.address().port}/healthz`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      assert.equal(health.status, 200);
      await health.arrayBuffer();
      assert(
        performance.now() - healthStarted < 500,
        'An awaited owner fence must not block health',
      );
      const expired = await pendingHttp;
      assert.equal(expired.status, 503);
      assert(!phases.includes('commit'));
      release();
      await new Promise((done) => setImmediate(done));
      assert(!phases.includes('commit'), 'Late uncancellable owner success cannot issue commit');
      assert.equal(phases.filter((p) => p === 'dispose').length, 1);
    } finally {
      delete observer.usesFencedSourceRead;
      observer.prepareBoundSourceAsync = priorPrepare;
      onFollow = () => {};
    }
    collector.current.phase = 'unavailable';
    await assertCapabilityProjection(false);
    assert.equal((await request()).status, 503);
    assert.deepEqual(
      [...mockedConstructors].sort(),
      [
        'DesktopDomBridge',
        'DesktopSuggestionObserver',
        'DesktopTranscription',
        'DesktopWorkspaceView',
        'RuntimeSession',
      ].sort(),
      'All five mocks must intercept the constructors used by the actual Host closure',
    );
  } finally {
    service?.close();
    DesktopDomBridge.prototype.readExisting = originals.domRead;
    RuntimeSession.prototype.connect = originals.connect;
    DesktopSuggestionObserver.prototype.start = originals.start;
    DesktopTranscription.prototype.readiness = originals.readiness;
    DesktopWorkspaceView.prototype.workspace = originals.workspace;
    for (const key of Object.keys(process.env)) if (!(key in environment)) delete process.env[key];
    Object.assign(process.env, environment);
    await rm(root, { recursive: true, force: true });
  }
});

function syntheticPreparedReceipt(observer, values, duringCommit = () => {}) {
  const calls = { prepare: 0, commit: 0, dispose: 0 };
  observer.predictionReader.prepareSourcePredictions = () => {
    calls.prepare++;
    const projection = structuredClone(values);
    let consumed = false;
    return {
      commit() {
        assert.equal(consumed, false);
        consumed = true;
        calls.commit++;
        duringCommit();
        return projection;
      },
      dispose() {
        if (!consumed) {
          consumed = true;
          calls.dispose++;
        }
      },
    };
  };
  return calls;
}

test('Worker-local prepared observer delays its projection and six-second lease until receipt and final guard', () =>
  boundSetup(async ({ root, observer, add }) => {
    const p = add(event(root));
    const source = [target, p.binding.sourceThreadId, p.binding.sourceTurnId];
    const calls = syntheticPreparedReceipt(observer, [p]);
    const samples = [];
    const prepared = observer.prepareBoundSource(...source, (v) => samples.push(v));
    assert.deepEqual(Object.keys(prepared).sort(), ['commit', 'dispose']);
    assert.deepEqual(calls, { prepare: 1, commit: 0, dispose: 0 });
    assert.deepEqual(samples, [true]);
    const before = Date.now();
    const value = prepared.commit((v) => samples.push(v));
    assert.equal(value.suggestion.text, p.text);
    assert(Date.parse(value.suggestion.expiresAt) > before);
    assert(Date.parse(value.suggestion.expiresAt) <= Date.now() + 6000);
    assert.deepEqual(samples, [true, true]);
    assert.equal(calls.commit, 1);
    assert.throws(
      () => prepared.commit(() => {}),
      (e) => e.status === 409,
    );
    prepared.dispose();
  }));
for (const change of ['epoch', 'instance', 'start', 'target', 'trace', 'closed', 'conflict'])
  test(`prepared observer fences ${change} changes without substituting a later projection`, () =>
    boundSetup(async ({ root, observer, add, switchTarget }) => {
      const p = add(event(root));
      const calls = syntheticPreparedReceipt(observer, [p]);
      const source = [target, p.binding.sourceThreadId, p.binding.sourceTurnId];
      const prepared = observer.prepareBoundSource(...source, () => {});
      if (change === 'epoch') observer.collectorEpoch++;
      if (change === 'instance') observer.windowsInstanceIdentity = 'different-instance';
      if (change === 'start') observer.desktopStartedAtUnixMs = 2;
      if (change === 'target') switchTarget({ ...target, hostId: 'changed' });
      if (change === 'trace') observer.traceIdentity = 'changed-root';
      if (change === 'closed') observer.close();
      if (change === 'conflict')
        observer.conflicts.add(
          createHash('sha256')
            .update(
              JSON.stringify([
                machineRuntimeTargetKey(target),
                p.binding.predictionThreadId,
                p.binding.predictionTurnId,
              ]),
            )
            .digest('hex'),
        );
      if (change === 'conflict') assert.equal(prepared.commit(() => {}).suggestion, null);
      else {
        assert.throws(
          () => prepared.commit(() => {}),
          (e) => e.status === 409,
        );
        assert.equal(calls.commit, 0);
        assert.equal(calls.dispose, 1);
      }
      prepared.dispose();
    }));
test('prepared observer refuses failed receipt and final readiness loss, including valid empty receipts', async (t) => {
  for (const mode of ['integrity', 'final-unavailable', 'empty'])
    await t.test(mode, () =>
      boundSetup(async ({ root, observer, add }) => {
        const p = add(event(root));
        syntheticPreparedReceipt(
          observer,
          mode === 'integrity' ? null : mode === 'empty' ? [] : [p],
          () => {
            if (mode === 'final-unavailable') observer.boundReadAvailable = () => false;
          },
        );
        const prepared = observer.prepareBoundSource(
          target,
          p.binding.sourceThreadId,
          p.binding.sourceTurnId,
          () => {},
        );
        if (mode === 'empty') assert.equal(prepared.commit(() => {}).suggestion, null);
        else
          assert.throws(
            () => prepared.commit(() => {}),
            (e) => e.status === 503,
          );
      }),
    );
});

test('prepared native candidate is blocked by a first contradictory observation received during the owner fence', () =>
  boundSetup(async ({ root, observer }) => {
    const observation = event(root);
    const prediction = nativePrediction(observation);
    assert.equal(observer.observations.size, 0, 'No selected notification exists before prepare');
    syntheticPreparedReceipt(observer, [prediction]);
    const p = observer.prepareBoundSource(
      target,
      prediction.binding.sourceThreadId,
      prediction.binding.sourceTurnId,
      () => {},
    );
    observer.accept(
      { ...observation, text: 'different first synthetic observation' },
      target,
      randomUUID(),
    );
    assert.equal(observer.observations.size, 1);
    assert.equal(
      observer.conflicts.size,
      0,
      'First notification does not create the existing conflict marker',
    );
    assert.equal(
      p.commit(() => {}).suggestion,
      null,
      'Current contradictory notification can only veto the native candidate',
    );
  }));

test('periodic Windows resampling preserves healthy prepared epochs and fences changed or failed initialization', async (t) => {
  const root = await privateRoot('suggestion-periodic-epoch-');
  // Freeze the actual compiled observer, replacing only native evidence in a
  // private module. The real start/prepare/commit methods execute on both OSes.
  // No Windows process, native helper, Host, model or actual Trace is involved.
  const sourceURL = new URL('./desktop-suggestion-observer.js', hostAdapterURL);
  let compiled = await readFile(sourceURL, 'utf8');
  const platformURL = new URL('./desktop-platform.js', hostAdapterURL).href;
  const stub = join(root, 'platform.mjs');
  await writeFile(
    stub,
    `
    export {assertPrivateFile,protectWindowsStateDirectory,protectWindowsStateFile,windowsTraceInstanceReady} from ${JSON.stringify(platformURL)};
    export const evidence={instance:undefined,probeFailed:false};
    export const inspectWindowsTraceInstance=()=>{if(evidence.probeFailed)throw Error('synthetic unavailable P');return evidence.instance;};
  `,
    { mode: 0o600 },
  );
  for (const [from, to] of [
    ['@codex-manage/protocol', new URL('../packages/protocol/dist/index.js', import.meta.url).href],
    ['./desktop-platform.js', pathToFileURL(stub).href],
    [
      './desktop-prediction-trace.js',
      new URL('./desktop-prediction-trace.js', hostAdapterURL).href,
    ],
    [
      './desktop-suggestion-worker-client.js',
      new URL('./desktop-suggestion-worker-client.js', hostAdapterURL).href,
    ],
  ]) {
    assert(compiled.includes(from));
    compiled = compiled.replaceAll(from, to);
  }
  const copied = join(root, 'observer.mjs');
  await writeFile(copied, compiled, { mode: 0o600 });
  const { DesktopSuggestionObserver: FrozenObserver } = await import(pathToFileURL(copied).href);
  const { evidence } = await import(pathToFileURL(stub).href);
  try {
    for (const mode of ['healthy', 'instance', 'start', 'root-unavailable', 'probe-unavailable'])
      await t.test(mode, () => {
        evidence.instance = {
          pid: 1,
          startedAtUnixMs: 100,
          traceRoot: 'synthetic-trace',
          executors: [
            {
              pid: 2,
              parentPid: 1,
              startedAtUnixMs: 100,
              traceRoot: 'synthetic-trace',
            },
          ],
        };
        evidence.probeFailed = false;
        let rootReady = true,
          commits = 0,
          disposes = 0;
        const observer = Object.create(FrozenObserver.prototype);
        Object.assign(observer, {
          closed: false,
          storageReady: true,
          phase: 'listening',
          collectorEpoch: 1,
          windowsInstanceIdentity: JSON.stringify(evidence.instance),
          desktopStartedAtUnixMs: 100,
          rootIdentity: 'fixed-root',
          traceIdentity: 'fixed-trace',
          authorizedRoot: 'synthetic-root',
          observations: new Map(),
          conflicts: new Set(),
          options: { nativeWorker: true, target: () => target, traceRoot: 'synthetic-trace' },
          predictionReader: {
            isAvailable: () => true,
            prepareSourcePredictions: () => ({
              commit() {
                commits++;
                return [];
              },
              dispose() {
                disposes++;
              },
            }),
          },
          assertBoundReadAvailable: () => {},
          rootStable: () => rootReady,
          readTraceIdentity: () => 'fixed-trace',
          boundReadAvailable: () => true,
        });
        const prepared = observer.prepareBoundSource(target, randomUUID(), randomUUID(), () => {});
        const descriptor = Object.getOwnPropertyDescriptor(process, 'platform');
        const resample = () => {
          clearTimeout(observer.retry);
          observer.retry = undefined;
          observer.start();
        };
        try {
          Object.defineProperty(process, 'platform', { value: 'win32' });
          if (mode === 'instance') evidence.instance.pid = 3;
          if (mode === 'start') evidence.instance.executors[0].startedAtUnixMs = 200;
          if (mode === 'root-unavailable') rootReady = false;
          if (mode === 'probe-unavailable') evidence.probeFailed = true;
          resample(); // Exactly the method called after the unchanged 30s timer.
          if (mode === 'root-unavailable' || mode === 'probe-unavailable') {
            assert.equal(observer.phase, 'unavailable');
            rootReady = true;
            evidence.probeFailed = false;
            resample(); // Recovery to the same identity cannot revive the old receipt.
          }
          if (mode === 'healthy') {
            assert.equal(
              observer.collectorEpoch,
              1,
              'Healthy sampling cannot create a new generation',
            );
            assert.equal(prepared.commit(() => {}).suggestion, null);
            assert.equal(commits, 1);
          } else {
            assert(observer.collectorEpoch > 1);
            assert.throws(
              () => prepared.commit(() => {}),
              (e) => e.status === 409,
            );
            assert.equal(commits, 0, 'Epoch fencing occurs before parser commit');
            assert.equal(disposes, 1);
          }
        } finally {
          clearTimeout(observer.retry);
          Object.defineProperty(process, 'platform', descriptor);
          prepared.dispose();
        }
      });
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
