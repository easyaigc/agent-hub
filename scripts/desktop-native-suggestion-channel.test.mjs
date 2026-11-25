import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire, syncBuiltinESMExports } from 'node:module';
import { createHash, randomUUID } from 'node:crypto';
import { mkdtempSync, readFileSync, writeFileSync, rmSync, realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { test } from 'node:test';
import { DesktopSuggestionStore } from '../packages/codex-adapter/dist/desktop-suggestion-store.js';
import { machineRuntimeTargetKey } from '../packages/protocol/dist/index.js';
import { protectWindowsStateDirectory } from '../packages/codex-adapter/dist/desktop-platform.js';
import { compatibleNativeSuggestionImplementation } from '../packages/codex-adapter/dist/desktop-native-sample-consumer.js';
import { nativeSuggestionImplementationFingerprint } from '../packages/codex-adapter/dist/desktop-native-sample-platform.js';
import { now as syntheticNow, advance } from './fixtures/desktop-native-sample-synthetic-clock.mjs';

const digest = (value) => createHash('sha256').update(value).digest('hex');
const target = {
  agentId: 'codex',
  hostId: 'host-a',
  workspaceId: 'workspace-a',
  runtimeId: 'desktop-runtime',
};
const fixture = mkdtempSync(join(tmpdir(), 'native-channel-boundary-'));
test.after(() => rmSync(fixture, { recursive: true }));
const platform = join(fixture, 'platform.mjs');
// Synthetic producer proof and clock only; Store, native DACL/snapshots and
// native process deadlines remain real. This is not original-desktop evidence.
writeFileSync(
  platform,
  `export let identity; export function setIdentity(v){identity=v;}
let afterInspect; export function setAfterInspect(v){afterInspect=v;}
export function inspectNativeSuggestionProducer(){if(!identity)throw Error('dead');const value=identity;afterInspect?.();return value;}
export function nativeSuggestionImplementationFingerprint(){return '${digest('implementation')}';}`,
);
let source = readFileSync(
  new URL('../packages/codex-adapter/dist/desktop-native-suggestion-channel.js', import.meta.url),
  'utf8',
);
function replaceExactly(input, from, to, count = 1) {
  assert.equal(input.split(from).length - 1, count, `exact synthetic channel replacement: ${from}`);
  return input.replaceAll(from, to);
}
for (const [from, to] of [
  [
    "'@codex-manage/protocol'",
    JSON.stringify(new URL('../packages/protocol/dist/index.js', import.meta.url).href),
  ],
  [
    "'./desktop-suggestion-store.js'",
    JSON.stringify(
      new URL('../packages/codex-adapter/dist/desktop-suggestion-store.js', import.meta.url).href,
    ),
  ],
  ["'./desktop-native-sample-platform.js'", JSON.stringify(pathToFileURL(platform).href)],
  [
    "'./desktop-native-sample-consumer.js'",
    JSON.stringify(
      new URL('../packages/codex-adapter/dist/desktop-native-sample-consumer.js', import.meta.url)
        .href,
    ),
  ],
])
  source = replaceExactly(source, from, to);
source = replaceExactly(source, 'Date.now()', 'syntheticNow()', 3);
source = `import { now as syntheticNow } from ${JSON.stringify(
  new URL('./fixtures/desktop-native-sample-synthetic-clock.mjs', import.meta.url).href,
)};\n${source}`;
writeFileSync(join(fixture, 'channel.mjs'), source);
const { DesktopNativeSuggestionChannel } = await import(
  pathToFileURL(join(fixture, 'channel.mjs'))
);
const { setIdentity, setAfterInspect } = await import(pathToFileURL(platform));

test('native channel fences source, producer, implementation, lease and two-stage reads', async (t) => {
  const traceRoot = realpathSync(mkdtempSync(join(tmpdir(), 'native-channel-store-')));
  t.after(() => rmSync(traceRoot, { recursive: true }));
  protectWindowsStateDirectory(traceRoot);
  const key = machineRuntimeTargetKey(target);
  const store = new DesktopSuggestionStore(traceRoot, key);
  const now = syntheticNow();
  const producer = {
    epoch: randomUUID(),
    pid: process.pid,
    desktopPid: process.ppid,
    desktopStartedAt: now - 20000,
  };
  const state = {
    version: 1,
    kind: 'native-sample-producer',
    targetKey: key,
    producer,
    wrapperStartedAt: now - 15000,
    rootIdentity: store.rootIdentity(),
    implementationSha256: digest('implementation'),
    checkedAt: now,
    phase: 'listening',
  };
  const thread = randomUUID(),
    turn = randomUUID(),
    request = `thread/sample:${randomUUID()}`;
  const text = 'Exact Unicode suggestion\n保留完整正文';
  const record = {
    version: 2,
    kind: 'native-sample',
    targetKey: key,
    sourceThreadId: thread,
    sourceTurnId: turn,
    requestId: request,
    requestSequence: 1,
    requestedAt: now - 3000,
    recordedAt: now - 1000,
    producer,
    state: 'completed',
    result: {
      requestId: request,
      sourceTurnId: turn,
      text,
      contentSha256: digest(text),
      resultSha256: digest('result'),
      responseId: null,
      completedAt: now - 2000,
      safety: { decidedAt: now - 1000, evidenceSha256: digest('safety') },
    },
  };
  setIdentity({
    pid: producer.pid,
    desktopPid: producer.desktopPid,
    desktopStartedAt: producer.desktopStartedAt,
    wrapperStartedAt: state.wrapperStartedAt,
  });
  const originalRename = fs.renameSync;
  let failures = 2;
  fs.renameSync = (...args) => {
    if (failures-- > 0)
      throw Object.assign(new Error('synthetic sharing overlap'), { code: 'EPERM' });
    return originalRename(...args);
  };
  syncBuiltinESMExports();
  try {
    store.saveProducer(state);
  } finally {
    fs.renameSync = originalRename;
    syncBuiltinESMExports();
  }
  assert(failures < 0, 'same atomic publication eventually succeeded');
  const previousProducer = store.readProducer();
  fs.renameSync = () => {
    throw Object.assign(new Error('synthetic persistent denial'), { code: 'EACCES' });
  };
  syncBuiltinESMExports();
  try {
    assert.throws(
      () => store.saveProducer({ ...state, phase: 'unavailable' }),
      /synthetic persistent denial/,
    );
  } finally {
    fs.renameSync = originalRename;
    syncBuiltinESMExports();
  }
  assert.deepEqual(
    store.readProducer(),
    previousProducer,
    'permanent failure cannot replace the old durable result',
  );
  store.prepare();
  const lock = join(store.directory, thread + '.json.write-lock');
  writeFileSync(lock, JSON.stringify({ version: 1, pid: process.pid, token: 'live-owner' }), {
    mode: 0o600,
  });
  assert.equal(store.saveRuntime(record), false, 'live writer must retain the body slot');
  rmSync(lock);
  writeFileSync(lock, JSON.stringify({ version: 1, pid: 2147483647, token: 'dead-owner' }), {
    mode: 0o600,
  });
  assert.equal(store.saveRuntime(record), true, 'confirmed dead process lock is recoverable');
  const channel = new DesktopNativeSuggestionChannel({
    traceRoot,
    configPath: join(traceRoot, 'config.json'),
    target: () => target,
  });
  assert.equal(channel.available, true);
  assert.equal(store.acknowledgeRuntimeResult(thread, turn, now - 4000, now - 2000), true);
  assert.equal(store.acknowledgeRuntimeResult(thread, randomUUID(), now - 4000, now - 2000), false);
  assert.equal(store.acknowledgeRuntimeResult(thread, turn, now, now + 1), false);
  // Latest-only supersession is strict; equality still needs the original same-turn overlap.
  assert.equal(store.acknowledgeRuntimeResult(thread, turn, now - 8000, now - 4000), true);
  assert.equal(store.acknowledgeRuntimeResult(thread, randomUUID(), now - 8000, now - 4000), true);
  assert.equal(store.acknowledgeRuntimeResult(thread, randomUUID(), now - 4000, now - 3000), false);
  assert.equal(store.acknowledgeRuntimeResult(thread, turn, now - 4000, now - 3000), true);
  const response = channel.read(target, thread, turn);
  assert.equal(response.source, 'native-trace');
  assert.equal(response.suggestion.text, text);
  assert.equal(response.suggestion.validity, 'native-version');
  assert.equal(response.suggestion.expiresAt, new Date(syntheticNow() + 6000).toISOString());
  assert.equal(channel.read(target, thread, randomUUID()).suggestion, null);
  assert.throws(() => channel.read({ ...target, hostId: 'other' }, thread, turn), { status: 400 });
  const pending = channel.prepare(target, thread, turn);
  assert.equal(
    store.saveRuntime({
      ...record,
      requestId: `thread/sample:${randomUUID()}`,
      requestSequence: 2,
      requestedAt: now,
      recordedAt: now,
      state: 'pending',
      result: null,
    }),
    true,
  );
  assert.throws(() => pending.commit(), { status: 409 });
  assert.equal(channel.read(target, thread, turn).suggestion, null);
  assert.equal(store.readRuntime(thread).latestExtracted.result.text, text);
  store.saveProducer({ ...state, phase: 'unavailable' });
  assert.equal(channel.available, false);
  store.saveProducer({ ...state, checkedAt: now - 7000 });
  assert.equal(channel.available, false);
  store.saveProducer(state);
  const expiredBetweenStages = channel.prepare(target, thread, turn);
  advance(6001);
  assert.throws(() => expiredBetweenStages.commit(), { status: 503 });
  assert.equal(channel.available, false, 'two-stage reads cannot renew an expired lease');

  state.checkedAt = syntheticNow();
  store.saveProducer(state);
  const expiredDuringNativeProof = channel.prepare(target, thread, turn);
  let nativeChecks = 0;
  setAfterInspect(() => {
    nativeChecks++;
    advance(6001);
  });
  try {
    assert.throws(() => expiredDuringNativeProof.commit(), { status: 503 });
    assert.equal(nativeChecks, 1, 'commit reached native identity before the final lease check');
  } finally {
    setAfterInspect(undefined);
  }
  state.checkedAt = syntheticNow();
  store.saveProducer(state);
  assert.equal(channel.available, true, 'only a new producer publication restores availability');
  store.saveProducer({ ...state, implementationSha256: digest('old') });
  assert.equal(channel.available, false);
  store.saveProducer(state);
  setIdentity({
    pid: producer.pid,
    desktopPid: producer.desktopPid + 1,
    desktopStartedAt: producer.desktopStartedAt,
    wrapperStartedAt: state.wrapperStartedAt,
  });
  assert.equal(channel.available, false);
  setIdentity(undefined);
  assert.equal(channel.available, false);
});

function runtimeStore(t) {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'native-ack-store-')));
  t.after(() => rmSync(root, { recursive: true }));
  protectWindowsStateDirectory(root);
  return new DesktopSuggestionStore(root, machineRuntimeTargetKey(target));
}

function completedSample(requestedAt = 100) {
  const requestId = `thread/sample:${randomUUID()}`;
  const sourceTurnId = randomUUID();
  const text = 'Synthetic retained native body';
  return {
    version: 2,
    kind: 'native-sample',
    targetKey: machineRuntimeTargetKey(target),
    sourceThreadId: randomUUID(),
    sourceTurnId,
    requestId,
    requestSequence: 1,
    requestedAt,
    recordedAt: requestedAt + 3,
    producer: {
      epoch: randomUUID(),
      pid: process.pid,
      desktopPid: process.ppid,
      desktopStartedAt: 1,
    },
    state: 'completed',
    result: {
      requestId,
      sourceTurnId,
      text,
      contentSha256: digest(text),
      resultSha256: digest('result'),
      responseId: null,
      completedAt: requestedAt + 1,
      safety: { decidedAt: requestedAt + 2, evidenceSha256: digest('safety') },
    },
  };
}

test('native acknowledgement rejects invalid raw ranges and cross-target/thread bodies', async (t) => {
  const store = runtimeStore(t);
  const record = completedSample();
  assert.equal(store.saveRuntime(record), true);
  const thread = record.sourceThreadId,
    turn = record.sourceTurnId;
  const slot = join(store.directory, thread + '.json');
  const bytes = readFileSync(slot);
  for (const [name, start, end] of [
    ['negative start', -1, 101],
    ['negative end', 0, -1],
    ['fractional start', 99.5, 101],
    ['fractional end', 99, 100.5],
    ['unsafe start', Number.MAX_SAFE_INTEGER + 1, 101],
    ['unsafe end', 99, Number.MAX_SAFE_INTEGER + 1],
    ['NaN start', NaN, 101],
    ['NaN end', 99, NaN],
    ['infinite start', -Infinity, 101],
    ['infinite end', 99, Infinity],
    ['missing start', undefined, 101],
    ['missing end', 99, undefined],
    ['coerced start', '99', 101],
    ['coerced end', 99, '101'],
    ['inverted overlapping range', 101, 100],
  ])
    await t.test(name, () => {
      assert.equal(store.acknowledgeRuntimeResult(thread, turn, start, end), false);
      assert.deepEqual(readFileSync(slot), bytes);
    });
  assert.equal(store.acknowledgeRuntimeResult(thread, 'not-a-turn', 1, 99), false);
  assert.equal(store.acknowledgeRuntimeResult(thread, thread, 1, 99), false);
  const otherTarget = new DesktopSuggestionStore(
    store.traceRoot,
    machineRuntimeTargetKey({ ...target, hostId: 'host-b' }),
  );
  assert.equal(otherTarget.acknowledgeRuntimeResult(thread, turn, 1, 99), false);
  assert.equal(store.acknowledgeRuntimeResult(randomUUID(), turn, 1, 99), false);
  for (const mismatch of ['target', 'thread'])
    await t.test('a valid foreign ' + mismatch + ' envelope in the slot is rejected', () => {
      const foreignStore = mismatch === 'target' ? otherTarget : store;
      const foreign = {
        ...record,
        targetKey:
          mismatch === 'target'
            ? machineRuntimeTargetKey({ ...target, hostId: 'host-b' })
            : record.targetKey,
        sourceThreadId: mismatch === 'thread' ? randomUUID() : thread,
      };
      assert.equal(foreignStore.saveRuntime(foreign), true);
      const foreignBytes = readFileSync(
        join(foreignStore.directory, foreign.sourceThreadId + '.json'),
      );
      try {
        writeFileSync(slot, foreignBytes);
        assert.throws(
          () => store.acknowledgeRuntimeResult(thread, turn, 1, 99),
          /Invalid runtime suggestion source/,
        );
        assert.deepEqual(readFileSync(slot), foreignBytes);
      } finally {
        writeFileSync(slot, bytes);
      }
    });
});

test('native acknowledgement uses the retained body time, never a pending request time', async (t) => {
  for (const kind of ['no-body', 'older-body', 'later-body'])
    await t.test(kind, (t) => {
      const store = runtimeStore(t);
      const body = completedSample(kind === 'later-body' ? 201 : 100);
      if (kind !== 'no-body') assert.equal(store.saveRuntime(body), true);
      const rawTurn = randomUUID();
      const pending = {
        ...body,
        sourceTurnId: rawTurn,
        requestId: `thread/sample:${randomUUID()}`,
        requestSequence: 2,
        requestedAt: 300,
        recordedAt: 300,
        state: 'pending',
        result: null,
      };
      assert.equal(store.saveRuntime(pending), true);
      const saved = store.readRuntime(body.sourceThreadId);
      assert.equal(saved.state, 'pending');
      assert.equal(saved.result, null);
      assert.equal(
        saved.latestExtracted?.result.text,
        kind === 'no-body' ? undefined : body.result.text,
      );
      const slot = join(store.directory, body.sourceThreadId + '.json');
      const bytes = readFileSync(slot);
      assert.equal(
        store.acknowledgeRuntimeResult(body.sourceThreadId, rawTurn, 150, 200),
        kind === 'later-body',
      );
      assert.deepEqual(readFileSync(slot), bytes);
    });
});

test('native acknowledgement rejects invalid retained-body ranges even in a valid pending envelope', async (t) => {
  for (const kind of ['completed-before-request', 'safety-after-record'])
    await t.test(kind, (t) => {
      const store = runtimeStore(t);
      const body = completedSample();
      const latestExtracted = {
        kind: 'native-sample',
        requestedAt: kind === 'completed-before-request' ? 102 : body.requestedAt,
        result: {
          ...body.result,
          safety: {
            ...body.result.safety,
            decidedAt: kind === 'safety-after-record' ? 301 : body.result.safety.decidedAt,
          },
        },
      };
      assert.equal(
        store.saveRuntime({
          ...body,
          sourceTurnId: randomUUID(),
          requestId: `thread/sample:${randomUUID()}`,
          requestSequence: 2,
          requestedAt: 300,
          recordedAt: 300,
          state: 'pending',
          result: null,
          latestExtracted,
        }),
        true,
        'the public store accepts this pending envelope; ack must validate its retained time proof',
      );
      assert.deepEqual(store.readRuntime(body.sourceThreadId).latestExtracted, latestExtracted);
      const slot = join(store.directory, body.sourceThreadId + '.json');
      const bytes = readFileSync(slot);
      assert.equal(
        store.acknowledgeRuntimeResult(body.sourceThreadId, body.sourceTurnId, 100, 200),
        false,
      );
      assert.deepEqual(readFileSync(slot), bytes);
    });
});

function compatibleProducerFixture(t) {
  const program = realpathSync(mkdtempSync(join(tmpdir(), 'native-compatible-producer-')));
  t.after(() => rmSync(program, { recursive: true }));
  const directory = join(program, 'packages/codex-adapter/dist');
  fs.mkdirSync(directory, { recursive: true });
  fs.mkdirSync(join(program, 'packages/codex-adapter/resources'));
  fs.mkdirSync(join(program, 'scripts'));
  const actual = new URL('../packages/codex-adapter/dist/', import.meta.url);
  for (const name of fs.readdirSync(actual).filter((name) => name.endsWith('.js')))
    if (
      ![
        'desktop-native-sample-consumer.js',
        'desktop-shared-control.js',
        'desktop-title.js',
      ].includes(name)
    )
      fs.copyFileSync(new URL(name, actual), join(directory, name));
  for (const name of ['desktop-native-suggestions-runtime.mjs', 'desktop-native-trace-policy.mjs'])
    fs.copyFileSync(new URL(name, import.meta.url), join(program, 'scripts', name));
  fs.copyFileSync(
    new URL(
      '../packages/codex-adapter/resources/desktop-completion-notify-observer.py',
      import.meta.url,
    ),
    join(program, 'packages/codex-adapter/resources/desktop-completion-notify-observer.py'),
  );
  // Consumer-only bytes are never executed by this fixture or the compatibility reader.
  for (const name of [
    'desktop-ipc.js',
    'desktop-original-session.js',
    'desktop-prepared-bridge.js',
    'desktop-thread-preparer.js',
    'desktop-native-suggestion-channel.js',
    'history-reader.js',
    'runtime-persistence.js',
    'runtime-session.js',
  ])
    writeFileSync(join(directory, name), 'export const olderConsumerOnly = true;\n');
  const configPath = join(program, 'config.json');
  const config = {
    entry: join(program, 'scripts/desktop-native-suggestions-runtime.mjs'),
    target,
    traceRoot: join(program, 'trace'),
  };
  const save = () => writeFileSync(configPath, JSON.stringify(config), { mode: 0o600 });
  save();
  const fingerprint = () => {
    const files = {};
    for (const name of fs
      .readdirSync(directory)
      .filter((name) => name.endsWith('.js'))
      .sort())
      files[name] = digest(readFileSync(join(directory, name)));
    for (const name of [
      'desktop-native-suggestions-runtime.mjs',
      'desktop-native-trace-policy.mjs',
    ])
      files['scripts/' + name] = digest(readFileSync(join(program, 'scripts', name)));
    files['resources/desktop-completion-notify-observer.py'] = digest(
      readFileSync(
        join(program, 'packages/codex-adapter/resources/desktop-completion-notify-observer.py'),
      ),
    );
    return digest(JSON.stringify(files));
  };
  const check = (overrides = {}) =>
    compatibleNativeSuggestionImplementation({
      configPath,
      traceRoot: config.traceRoot,
      targetKey: machineRuntimeTargetKey(target),
      producerSha256: fingerprint(),
      consumerSha256: nativeSuggestionImplementationFingerprint(),
      ...overrides,
    });
  return { program, directory, config, configPath, save, fingerprint, check };
}

test(
  'Host-only upgrade can read the unchanged original native producer without executing its code',
  { skip: process.platform !== 'darwin' },
  (t) => {
    const f = compatibleProducerFixture(t);
    assert.equal(f.check(), true);
    assert.equal(f.check({ producerSha256: 'a'.repeat(64) }), false);
    assert.equal(f.check({ consumerSha256: 'b'.repeat(64) }), false);
    assert.equal(
      f.check({ targetKey: machineRuntimeTargetKey({ ...target, hostId: 'other-host' }) }),
      false,
    );
    assert.equal(f.check({ traceRoot: join(f.program, 'other-root') }), false);
  },
);

test(
  'native compatibility rejects changed producer dependencies and unknown modules',
  { skip: process.platform !== 'darwin' },
  (t) => {
    const f = compatibleProducerFixture(t);
    const path = join(f.directory, 'desktop-native-sample-capture.js');
    const original = readFileSync(path);
    writeFileSync(path, Buffer.concat([original, Buffer.from('\n// changed native parser\n')]));
    assert.equal(
      f.check(),
      false,
      'Even a matching advertised full hash cannot admit a changed parser',
    );
    writeFileSync(path, original);
    assert.equal(f.check(), true);
    writeFileSync(join(f.directory, 'unknown-module.js'), 'export const unknown = true;\n');
    assert.equal(f.check(), false);
  },
);

test(
  'native compatibility requires a private canonical config and owned unchanged launch inputs',
  { skip: process.platform !== 'darwin' },
  (t) => {
    const f = compatibleProducerFixture(t);
    const script = join(f.program, 'scripts/desktop-native-trace-policy.mjs');
    const original = readFileSync(script);
    writeFileSync(
      script,
      Buffer.concat([original, Buffer.from('\n// changed recording policy\n')]),
    );
    assert.equal(f.check(), false);
    writeFileSync(script, original);
    fs.chmodSync(f.configPath, 0o644);
    assert.equal(f.check(), false);
    fs.chmodSync(f.configPath, 0o600);
    f.config.entry = join(f.program, 'scripts/arbitrary-entry.mjs');
    f.save();
    assert.equal(f.check(), false);
    f.config.entry = join(f.program, 'scripts/desktop-native-suggestions-runtime.mjs');
    f.save();
    const alias = join(f.program, 'config-alias.json');
    fs.symlinkSync(f.configPath, alias);
    assert.equal(f.check({ configPath: alias }), false);
    assert.equal(f.check(), true);
  },
);

test('native startup dependency graph cannot import the consumer compatibility exceptions', () => {
  const ts = createRequire(new URL('../packages/codex-adapter/package.json', import.meta.url))(
    'typescript',
  );
  const pending = [
    new URL('./desktop-native-suggestions-runtime.mjs', import.meta.url),
    new URL('../packages/codex-adapter/dist/desktop-shared-launcher.js', import.meta.url),
  ];
  const visited = new Set();
  while (pending.length) {
    const url = pending.pop();
    if (visited.has(url.href)) continue;
    visited.add(url.href);
    assert(
      !/\/(?:desktop-ipc|desktop-original-session|desktop-prepared-bridge|desktop-thread-preparer|desktop-native-suggestion-channel|desktop-native-sample-consumer|desktop-shared-control|desktop-title|history-reader|runtime-persistence|runtime-session)\.js$/.test(
        url.pathname,
      ),
    );
    const source = ts.createSourceFile(
      fileURLToPath(url),
      readFileSync(url, 'utf8'),
      ts.ScriptTarget.Latest,
      true,
      ts.ScriptKind.JS,
    );
    const add = (name) => {
      if (name.startsWith('.')) pending.push(new URL(name, url));
    };
    const walk = (node) => {
      if (
        (ts.isImportDeclaration(node) || ts.isExportDeclaration(node)) &&
        node.moduleSpecifier &&
        ts.isStringLiteral(node.moduleSpecifier)
      )
        add(node.moduleSpecifier.text);
      if (ts.isCallExpression(node) && node.expression.kind === ts.SyntaxKind.ImportKeyword) {
        assert.equal(node.arguments.length, 1);
        assert(
          ts.isStringLiteral(node.arguments[0]),
          'Unknown dynamic imports cannot establish producer/consumer separation',
        );
        add(node.arguments[0].text);
      }
      if (
        ts.isNewExpression(node) &&
        ts.isIdentifier(node.expression) &&
        node.expression.text === 'URL' &&
        node.arguments?.length &&
        ts.isStringLiteral(node.arguments[0]) &&
        /\.(?:m?js)$/.test(node.arguments[0].text)
      )
        add(node.arguments[0].text);
      ts.forEachChild(node, walk);
    };
    walk(source);
  }
  assert(visited.size > 10);
});
