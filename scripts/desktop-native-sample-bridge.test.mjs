import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import fs from 'node:fs';
import {
  appendFileSync,
  chmodSync,
  existsSync,
  linkSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  realpathSync,
  renameSync,
  rmSync,
  symlinkSync,
  truncateSync,
  unlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { syncBuiltinESMExports } from 'node:module';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { machineRuntimeTargetKey } from '../packages/protocol/dist/index.js';
import {
  createDesktopNativeSampleBridge,
  desktopNativeSampleBridgeTesting as testing,
} from '../packages/codex-adapter/dist/desktop-native-sample-bridge.js';
import { DesktopNativeSampleCapture } from '../packages/codex-adapter/dist/desktop-native-sample-capture.js';
import { DesktopSuggestionStore } from '../packages/codex-adapter/dist/desktop-suggestion-store.js';
import {
  protectWindowsStateDirectory,
  protectWindowsStateFile,
} from '../packages/codex-adapter/dist/desktop-platform.js';

// Synthetic local fixtures only. These do not attest the original launcher's
// byte forwarding, native parent/start validation, or real Electron/iPhone use.
const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
const windows = process.platform === 'win32';
const implementationSha256 = 'a'.repeat(64);
function directory(path) {
  mkdirSync(path, { recursive: true, mode: 0o700 });
  if (!windows) chmodSync(path, 0o700);
  protectWindowsStateDirectory(path);
  return path;
}
function privateFile(path, text) {
  writeFileSync(path, text, { mode: 0o600 });
  if (!windows) chmodSync(path, 0o600);
  protectWindowsStateFile(path);
  return path;
}
function fixture(t) {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'native-bridge-fixture-')));
  directory(root);
  const traceRoot = directory(join(root, 'trace'));
  const logDirectory = directory(join(root, 'logs'));
  const app = directory(join(root, 'Original.app'));
  const configPath = join(root, 'native.json');
  let at = Date.parse('2026-10-30T12:00:00.000Z');
  const identity = {
    pid: process.pid,
    desktopPid: 812345,
    desktopStartedAt: at - 60000,
    wrapperStartedAt: at - 10000,
  };
  const target = {
    agentId: 'codex',
    hostId: 'fixture-host',
    workspaceId: 'fixture-workspace',
    runtimeId: 'desktop-runtime',
    account: { authority: 'fixture', subject: 'historical-account' },
  };
  const config = {
    version: 1,
    target,
    targetKey: machineRuntimeTargetKey(target),
    traceRoot,
    applicationPath: app,
    executable: privateFile(join(app, 'original-cli'), 'fixture CLI'),
    node: privateFile(join(root, 'original-node'), 'fixture Node'),
    entry: privateFile(join(root, 'bridge-entry.mjs'), '// synthetic fixture'),
    logDirectory,
  };
  privateFile(configPath, JSON.stringify(config));
  const store = new DesktopSuggestionStore(traceRoot, machineRuntimeTargetKey(target));
  const codes = [],
    instances = [];
  const nativeIdentity = (pid) => {
    if (pid === process.pid) return { ...identity };
    throw new Error('Synthetic unknown identity, not proof of exit');
  };
  const f = {
    root,
    traceRoot,
    logDirectory,
    configPath,
    config,
    identity,
    target,
    store,
    codes,
    ownerPath: join(store.directory, 'producer-owner.json'),
    now: () => at,
    advance: (delta = 1) => (at += delta),
    saveConfig: (value = config) => privateFile(configPath, JSON.stringify(value)),
    start(hooks = {}) {
      const bridge = testing.create(configPath, {
        identity: nativeIdentity,
        clock: () => at,
        loggerPaths: { root: logDirectory },
        status: (code) => codes.push(code),
        manual: true,
        ...hooks,
        implementationSha256: hooks.implementationSha256 ?? implementationSha256,
      });
      if (bridge) instances.push(bridge);
      f.bridge = bridge;
      return bridge;
    },
    tick(delta = 250, bridge = f.bridge) {
      at += delta;
      assert.ok(bridge, 'fixture bridge must have started');
      assert.doesNotThrow(() => testing.tick(bridge));
    },
    log(name = '01', pid = identity.desktopPid, when = at) {
      const date = new Date(when).toISOString().slice(0, 10).split('-');
      let path = logDirectory;
      for (const segment of date) path = directory(join(path, segment));
      return join(path, `codex-desktop-fixture-${pid}-${name}.log`);
    },
    row(req) {
      return store.readRuntime(req.params.threadId);
    },
    owner(
      producer = {
        epoch: randomUUID(),
        pid: 712345,
        desktopPid: identity.desktopPid,
        desktopStartedAt: identity.desktopStartedAt,
      },
      wrapperStartedAt = identity.wrapperStartedAt - 1000,
    ) {
      store.prepare();
      return {
        version: 1,
        kind: 'native-sample-owner',
        targetKey: store.targetKey,
        producer,
        wrapperStartedAt,
        rootIdentity: store.rootIdentity(),
      };
    },
    decision(req, fields = {}) {
      return (
        `${new Date(fields.at ?? at).toISOString()} info [cumberbatch] cumberbatch_safety_decision ` +
        `sourceThreadId=${fields.sourceThreadId ?? req.params.threadId} ` +
        `sourceTurnId=${fields.sourceTurnId ?? req.params.expectedTurnId} suppressed=${fields.suppressed ?? false}`
      );
    },
    send(req, body = 'Synthetic suggestion', bridge = f.bridge) {
      bridge.observeOutbound(JSON.stringify(req));
      at++;
      bridge.observeInbound(
        JSON.stringify({ id: req.id, result: { text: JSON.stringify({ suggestion: body }) } }),
      );
      at++;
    },
  };
  t.after(() => {
    for (const bridge of instances) bridge.close();
    // This exact fresh fixture directory contains synthetic files only.
    rmSync(root, { recursive: true });
  });
  return f;
}
function request(fields = {}) {
  return {
    id: `thread/sample:${randomUUID()}`,
    method: 'thread/sample',
    params: {
      threadId: randomUUID(),
      expectedTurnId: randomUUID(),
      prompt: 'Synthetic composer prompt',
      turnTrigger: 'composer_predictions',
      ...fields,
    },
  };
}
function append(path, text) {
  if (!existsSync(path)) privateFile(path, text);
  else appendFileSync(path, text);
}

test('no configured private file means no bridge and no status output', () => {
  const codes = [];
  assert.equal(testing.create(undefined, { status: (code) => codes.push(code) }), undefined);
  assert.deepEqual(codes, []);
  assert.equal(typeof createDesktopNativeSampleBridge, 'function');
});

test('ready producer publishes the exact state and exposes only a passive tap', (t) => {
  const f = fixture(t),
    bridge = f.start();
  assert.ok(bridge);
  for (const method of ['observeOutbound', 'observeInbound', 'close'])
    assert.equal(typeof bridge[method], 'function');
  assert.equal(bridge.send, undefined);
  const state = f.store.readProducer();
  assert.deepEqual(
    Object.keys(state).sort(),
    [
      'version',
      'kind',
      'targetKey',
      'producer',
      'wrapperStartedAt',
      'rootIdentity',
      'implementationSha256',
      'checkedAt',
      'phase',
    ].sort(),
  );
  assert.deepEqual(state, {
    version: 1,
    kind: 'native-sample-producer',
    targetKey: machineRuntimeTargetKey(f.target),
    producer: {
      epoch: state.producer.epoch,
      pid: process.pid,
      desktopPid: f.identity.desktopPid,
      desktopStartedAt: f.identity.desktopStartedAt,
    },
    wrapperStartedAt: f.identity.wrapperStartedAt,
    rootIdentity: f.store.rootIdentity(),
    implementationSha256,
    checkedAt: f.now(),
    phase: 'listening',
  });
  assert.ok(/^[a-f0-9-]{36}$/.test(state.producer.epoch));
  if (!windows) assert.equal(lstatSync(f.ownerPath).mode & 0o777, 0o600);
  bridge.close();
  assert.equal(f.store.readProducer().phase, 'unavailable');
  assert.equal(existsSync(f.ownerPath), false);
  assert.doesNotThrow(() => bridge.close());
});

test('only an exact real-format decision releases one body through the store', (t) => {
  const f = fixture(t),
    path = f.log();
  privateFile(path, 'old non-safety record\n');
  const bridge = f.start(),
    req = request();
  const outbound = JSON.stringify(req) + '\r\n',
    original = Buffer.from(outbound);
  bridge.observeOutbound(outbound);
  f.advance();
  const inbound = JSON.stringify({
    id: req.id,
    result: { text: JSON.stringify({ suggestion: '  body\n' }) },
  });
  bridge.observeInbound(inbound);
  f.advance();
  assert.deepEqual(Buffer.from(outbound), original);
  assert.equal(f.row(req).state, 'pending');
  const line = f.decision(req);
  append(path, line + '\n');
  f.tick();
  const row = f.row(req);
  assert.equal(row.state, 'completed');
  assert.equal(row.result.text, 'body');
  assert.equal(row.result.safety.evidenceSha256, hash(line));
  assert.equal(row.latestExtracted, undefined);
  const files = readdirSync(f.store.directory).sort();
  assert.deepEqual(
    files,
    [req.params.threadId + '.json', 'producer-owner.json', 'producer.json'].sort(),
  );
  // Original launcher byte-for-byte forwarding is deliberately a separate integration assertion.
});

test('existing valid safety records are placed at EOF, never replayed', (t) => {
  const f = fixture(t),
    req = request(),
    path = f.log();
  privateFile(path, f.decision(req) + '\n');
  f.start();
  f.send(req);
  f.tick();
  assert.equal(f.row(req).state, 'pending');
  append(path, f.decision(req) + '\n');
  f.tick();
  assert.equal(f.row(req).state, 'completed');
});

test('an existing partial last line is discarded through its first newline', (t) => {
  const f = fixture(t),
    req = request(),
    path = f.log();
  const old = f.decision(req);
  privateFile(path, old.slice(0, -5));
  f.start();
  f.send(req);
  append(path, old.slice(-5) + '\n');
  f.tick();
  assert.equal(f.row(req).state, 'pending');
  append(path, f.decision(req) + '\n');
  f.tick();
  assert.equal(f.row(req).state, 'completed');
});

test('new rotation starts at zero; pre-producer timestamps never approve', (t) => {
  const f = fixture(t),
    path = f.log(),
    req = request();
  privateFile(path, 'old log\n');
  f.start();
  f.send(req);
  renameSync(path, join(dirname(path), 'unselected-old-log.bak'));
  privateFile(path, f.decision(req, { at: f.identity.wrapperStartedAt - 1 }) + '\n');
  f.tick();
  assert.equal(f.row(req).state, 'pending');
  append(path, f.decision(req) + '\n');
  f.tick();
  assert.equal(f.row(req).state, 'completed');
});

test('a new current-PID rotation filename is read from offset zero', (t) => {
  const f = fixture(t);
  f.start();
  const req = request();
  f.send(req);
  privateFile(f.log('02'), f.decision(req) + '\n');
  f.tick();
  assert.equal(f.row(req).state, 'completed');
});

test('only the exact current PID token in an allowed day directory is selected', (t) => {
  const f = fixture(t);
  f.start();
  const req = request();
  f.send(req);
  privateFile(f.log('wrong', Number(`${f.identity.desktopPid}1`)), f.decision(req) + '\n');
  privateFile(
    f.log('old-day', f.identity.desktopPid, f.now() - 3 * 86400000),
    f.decision(req) + '\n',
  );
  privateFile(
    join(f.logDirectory, `codex-desktop-fixture-${f.identity.desktopPid}-flat.log`),
    f.decision(req) + '\n',
  );
  f.tick();
  assert.equal(f.row(req).state, 'pending');
  privateFile(
    f.log('yesterday', f.identity.desktopPid, f.now() - 86400000),
    f.decision(req) + '\n',
  );
  f.tick();
  assert.equal(f.row(req).state, 'completed');
});

test('suppressed and cross-source decisions cannot release a candidate', (t) => {
  const f = fixture(t);
  f.start();
  const a = request(),
    b = request();
  f.send(a);
  f.send(b);
  const path = f.log();
  privateFile(
    path,
    f.decision(a, { sourceTurnId: b.params.expectedTurnId }) +
      '\n' +
      f.decision(b, { sourceThreadId: a.params.threadId }) +
      '\n',
  );
  f.tick();
  assert.equal(f.row(a).state, 'pending');
  assert.equal(f.row(b).state, 'pending');
  append(path, f.decision(a, { suppressed: true }) + '\n');
  f.tick();
  assert.equal(f.row(a).state, 'suppressed');
  assert.equal(f.row(a).result, null);
  append(path, f.decision(a) + '\n');
  f.tick();
  assert.equal(f.row(a).state, 'suppressed');
  assert.equal(f.row(b).state, 'pending');
});

test('a decision preceding the observed response cannot approve it', (t) => {
  const f = fixture(t);
  f.start();
  const req = request();
  f.bridge.observeOutbound(JSON.stringify(req));
  f.advance();
  const decision = f.decision(req);
  f.advance();
  f.bridge.observeInbound(
    JSON.stringify({ id: req.id, result: { text: '{"suggestion":"body"}' } }),
  );
  privateFile(f.log(), decision + '\n');
  f.tick();
  assert.equal(f.row(req).state, 'unverified');
  assert.equal(f.row(req).result, null);
});

test('unknown, duplicate, truncated, and non-exact safety structures never approve', (t) => {
  const f = fixture(t);
  f.start();
  const req = request();
  f.send(req);
  const line = f.decision(req),
    path = f.log();
  const invalid = [
    line.replace(' info ', ' warn '),
    line.replace('[cumberbatch]', '[other]'),
    line + ` sourceThreadId=${req.params.threadId}`,
    line + ` sourceTurnId=${req.params.expectedTurnId}`,
    line + ' suppressed=false',
    line.replace('suppressed=false', 'suppressed=unknown'),
    line.replace('suppressed=false', 'suppressed=False'),
    line + ' extra=value',
    'prefix ' + line,
    line.replace('sourceThreadId=', 'sourceThreadId=invalid-'),
    line.replace('2026-10-30', '2026-02-30'),
    line.replace('sourceTurnId', 'sourceTurnID'),
    line.slice(0, -1),
  ];
  privateFile(path, invalid.join('\n') + '\n' + line.slice(0, 70));
  f.tick();
  assert.equal(f.row(req).state, 'pending');
  append(path, '\n');
  f.tick();
  assert.equal(f.row(req).state, 'pending');
  append(path, line + '\n');
  f.tick();
  assert.equal(f.row(req).state, 'completed');
});

test('partial UTF-8 log data waits for a complete newline; CRLF hashes the complete record', (t) => {
  const f = fixture(t);
  f.start();
  const req = request();
  f.send(req);
  const line = f.decision(req),
    path = f.log();
  privateFile(
    path,
    Buffer.concat([Buffer.from([0xff]), Buffer.from(line + '\n' + line.slice(0, 30))]),
  );
  f.tick();
  assert.equal(f.row(req).state, 'pending');
  append(path, line.slice(30) + '\r\n');
  f.tick();
  assert.equal(f.row(req).state, 'completed');
  assert.equal(f.row(req).result.safety.evidenceSha256, hash(line + '\r'));
});

test('line and partial-tail capacity discards the complete overlong record', (t) => {
  const f = fixture(t);
  f.start();
  const req = request();
  f.send(req);
  const path = f.log(),
    line = f.decision(req);
  privateFile(path, 'x'.repeat(testing.limits.lineBytes));
  f.tick();
  append(path, line + '\n');
  f.tick();
  assert.equal(f.row(req).state, 'pending');
  assert.ok(f.codes.includes('native_bridge_log_line_limit'));
  append(path, f.decision(req) + '\n');
  f.tick();
  assert.equal(f.row(req).state, 'completed');
});

test('same-inode truncation disables the producer without using an old cursor', (t) => {
  const f = fixture(t),
    path = f.log();
  privateFile(path, 'x'.repeat(8192) + '\n');
  f.start();
  const req = request();
  f.send(req);
  truncateSync(path, 0);
  append(path, f.decision(req) + '\n');
  f.tick();
  assert.notEqual(f.row(req).state, 'completed');
  assert.equal(f.store.readProducer().phase, 'unavailable');
  assert.ok(f.codes.includes('native_bridge_log_truncated'));
  assert.equal(existsSync(f.ownerPath), false);
});

test('truncate-and-regrow cannot reuse a previously saved prefix guard', (t) => {
  const f = fixture(t),
    path = f.log();
  privateFile(path, 'original prefix\n');
  f.start();
  const req = request();
  f.send(req);
  truncateSync(path, 0);
  append(path, 'replaced prefix\n' + f.decision(req) + '\n');
  f.tick();
  assert.notEqual(f.row(req).state, 'completed');
  assert.equal(f.store.readProducer().phase, 'unavailable');
});

test('log hard links and current-PID symlink aliases fail closed', (t) => {
  const f = fixture(t);
  f.start();
  const req = request();
  f.send(req);
  const external = privateFile(join(f.root, 'synthetic.log'), f.decision(req) + '\n');
  linkSync(external, f.log());
  f.tick();
  assert.notEqual(f.row(req).state, 'completed');
  assert.equal(f.store.readProducer().phase, 'unavailable');
  const g = fixture(t);
  g.start();
  const b = request();
  g.send(b);
  const other = privateFile(join(g.root, 'other.log'), g.decision(b) + '\n');
  try {
    symlinkSync(other, g.log(), 'file');
  } catch (error) {
    if (windows && error.code === 'EPERM') return;
    throw error;
  }
  g.tick();
  assert.notEqual(g.row(b).state, 'completed');
  assert.equal(g.store.readProducer().phase, 'unavailable');
});

test('replacing a dated directory with a symlink/junction never exposes its log', (t) => {
  const f = fixture(t),
    path = f.log();
  privateFile(path, 'initial\n');
  f.start();
  const req = request();
  f.send(req);
  const day = dirname(path),
    moved = join(f.root, 'moved-date');
  renameSync(day, moved);
  append(join(moved, path.slice(day.length + 1)), f.decision(req) + '\n');
  symlinkSync(moved, day, windows ? 'junction' : 'dir');
  f.tick();
  assert.notEqual(f.row(req).state, 'completed');
  assert.equal(f.store.readProducer().phase, 'unavailable');
});

test('config is strict, small, duplicate-free, machine-target bound and private', (t) => {
  for (const modify of [
    (f) => f.saveConfig({ ...f.config, version: 2 }),
    (f) => f.saveConfig({ ...f.config, extra: 'not accepted' }),
    (f) => f.saveConfig({ ...f.config, target: { ...f.target, runtimeId: '' } }),
    (f) => f.saveConfig({ ...f.config, targetKey: 'account-dependent-key' }),
    (f) => privateFile(f.configPath, '{"version":1,"vers\\u0069on":1}'),
    (f) => privateFile(f.configPath, ' '.repeat(testing.limits.configBytes + 1)),
    (f) => f.saveConfig({ ...f.config, shimPath: f.config.entry }),
    (f) => f.saveConfig({ ...f.config, logDirectory: f.root }),
  ]) {
    const f = fixture(t);
    modify(f);
    assert.equal(f.start(), undefined);
    assert.equal(existsSync(f.ownerPath), false);
    assert.equal(existsSync(join(f.store.directory, 'producer.json')), false);
    assert.ok(f.codes.length > 0);
    assert.ok(f.codes.every((code) => /^native_bridge_[a-z_]+$/.test(code)));
  }
});

test('config symlinks, hardlinks and directory aliases are refused before listening', (t) => {
  const f = fixture(t),
    saved = join(f.root, 'saved-config.json');
  renameSync(f.configPath, saved);
  linkSync(saved, f.configPath);
  assert.equal(f.start(), undefined);
  unlinkSync(f.configPath);
  try {
    symlinkSync(saved, f.configPath, 'file');
  } catch (error) {
    if (windows && error.code === 'EPERM') return;
    throw error;
  }
  assert.equal(f.start(), undefined);
  const g = fixture(t),
    alias = join(g.root, 'trace-alias');
  symlinkSync(g.traceRoot, alias, windows ? 'junction' : 'dir');
  g.saveConfig({ ...g.config, traceRoot: alias });
  assert.equal(g.start(), undefined);
});

test('optional shim identity is canonical, bounded and hash-verified', (t) => {
  const f = fixture(t),
    shimPath = privateFile(join(f.root, 'shim.mjs'), '// synthetic shim');
  f.saveConfig({ ...f.config, shimPath, shimSha256: hash(readFileSync(shimPath)) });
  assert.ok(f.start());
  const g = fixture(t),
    other = privateFile(join(g.root, 'shim.mjs'), '// different shim');
  g.saveConfig({ ...g.config, shimPath: other, shimSha256: '0'.repeat(64) });
  assert.equal(g.start(), undefined);
  assert.equal(existsSync(g.ownerPath), false);
});

test('POSIX 0600 config and 0700 trace root are exact requirements', { skip: windows }, (t) => {
  const f = fixture(t);
  chmodSync(f.configPath, 0o644);
  assert.equal(f.start(), undefined);
  const g = fixture(t);
  chmodSync(g.traceRoot, 0o750);
  assert.equal(g.start(), undefined);
  const h = fixture(t);
  chmodSync(h.configPath, 0o400);
  assert.equal(h.start(), undefined);
});

test(
  'Windows config DACL is verified natively instead of POSIX permission emulation',
  { skip: !windows },
  (t) => {
    const f = fixture(t);
    execFileSync('icacls.exe', [f.configPath, '/grant', '*S-1-1-0:(R)'], {
      stdio: 'pipe',
      windowsHide: true,
    });
    assert.equal(f.start(), undefined);
    assert.equal(existsSync(f.ownerPath), false);
  },
);

test('historical account changes leave the machine target key unchanged', (t) => {
  const f = fixture(t);
  f.saveConfig({
    ...f.config,
    target: { ...f.target, account: { authority: 'other', subject: 'other' } },
  });
  assert.ok(f.start());
  assert.equal(f.store.readProducer().targetKey, machineRuntimeTargetKey(f.target));
});

test('implementation fingerprint is captured once and remains fixed across heartbeats', (t) => {
  const f = fixture(t);
  let reads = 0,
    current = implementationSha256;
  const bridge = testing.create(f.configPath, {
    identity: () => ({ ...f.identity }),
    clock: f.now,
    loggerPaths: { root: f.logDirectory },
    status: (code) => f.codes.push(code),
    manual: true,
    get implementationSha256() {
      reads++;
      return current;
    },
  });
  assert.ok(bridge);
  t.after(() => bridge.close());
  assert.equal(reads, 1);
  current = 'b'.repeat(64);
  f.tick(2000, bridge);
  assert.equal(reads, 1);
  assert.equal(f.store.readProducer().implementationSha256, implementationSha256);
  bridge.close();
  assert.equal(f.store.readProducer().implementationSha256, implementationSha256);
});

test('invalid implementation digests fail construction; a different live digest is never overwritten', (t) => {
  const f = fixture(t);
  assert.equal(f.start({ implementationSha256: 'SECRET invalid digest' }), undefined);
  assert.equal(existsSync(f.ownerPath), false);
  assert.ok(f.codes.every((code) => !code.includes('SECRET')));
  const g = fixture(t);
  g.start();
  const replacement = { ...g.store.readProducer(), implementationSha256: 'b'.repeat(64) };
  g.store.saveProducer(replacement);
  g.tick(2000);
  assert.deepEqual(g.store.readProducer(), replacement);
  assert.ok(g.codes.includes('native_bridge_owner_changed'));
});

test('a live owner blocks contention without touching the existing epoch or lock', (t) => {
  const f = fixture(t),
    first = f.start(),
    before = readFileSync(f.ownerPath),
    state = f.store.readProducer();
  assert.ok(first);
  assert.equal(f.start(), undefined);
  assert.deepEqual(readFileSync(f.ownerPath), before);
  assert.deepEqual(f.store.readProducer(), state);
  assert.ok(f.codes.includes('native_bridge_owner_busy'));
  first.close();
  assert.equal(f.store.readProducer().phase, 'unavailable');
});

test('unknown old PID state is preserved; helper failure and ESRCH are not positive exit evidence', (t) => {
  for (const code of ['EPERM', 'ESRCH', undefined]) {
    const f = fixture(t),
      owner = f.owner();
    privateFile(f.ownerPath, JSON.stringify(owner) + '\n');
    const before = readFileSync(f.ownerPath);
    assert.equal(
      f.start({
        identity: (pid) => {
          if (pid === process.pid) return { ...f.identity };
          throw Object.assign(new Error('Unclassified native failure'), { code });
        },
      }),
      undefined,
    );
    assert.deepEqual(readFileSync(f.ownerPath), before);
    assert.ok(f.codes.includes('native_bridge_owner_unknown'));
    assert.equal(existsSync(join(f.store.directory, 'producer.json')), false);
  }
});

test('a proven different PID start identity reclaims an old owner', (t) => {
  const f = fixture(t),
    owner = f.owner();
  privateFile(f.ownerPath, JSON.stringify(owner) + '\n');
  assert.ok(
    f.start({
      identity: (pid) => {
        if (pid === process.pid) return { ...f.identity };
        assert.equal(pid, owner.producer.pid);
        return { ...f.identity, pid, wrapperStartedAt: owner.wrapperStartedAt + 1 };
      },
    }),
  );
  assert.notEqual(f.store.readProducer().producer.epoch, owner.producer.epoch);
  assert.equal(f.store.readProducer().phase, 'listening');
});

test('invalid or changed owner identities are retained instead of guessed away', (t) => {
  const f = fixture(t),
    owner = f.owner();
  privateFile(f.ownerPath, JSON.stringify({ ...owner, extra: true }));
  assert.equal(f.start(), undefined);
  assert.equal(existsSync(f.ownerPath), true);
  const g = fixture(t),
    other = g.owner();
  privateFile(g.ownerPath, JSON.stringify(other));
  assert.equal(
    g.start({
      identity: (pid) =>
        pid === process.pid
          ? { ...g.identity }
          : {
              ...g.identity,
              pid: other.producer.pid,
              wrapperStartedAt: other.wrapperStartedAt,
              desktopPid: other.producer.desktopPid + 1,
            },
    }),
    undefined,
  );
  assert.equal(existsSync(g.ownerPath), true);
});

test('close and stale callbacks cannot overwrite or unlink a replacement epoch', (t) => {
  const f = fixture(t),
    first = f.start(),
    req = request();
  f.send(req);
  const pending = f.row(req),
    old = f.store.readProducer();
  const producer = { ...old.producer, epoch: randomUUID() };
  const owner = {
    version: 1,
    kind: 'native-sample-owner',
    targetKey: old.targetKey,
    producer,
    wrapperStartedAt: old.wrapperStartedAt,
    rootIdentity: old.rootIdentity,
  };
  privateFile(f.ownerPath, JSON.stringify(owner) + '\n');
  f.store.saveProducer({ ...old, producer });
  const nextState = f.store.readProducer(),
    nextLock = readFileSync(f.ownerPath);
  assert.doesNotThrow(() => first.close());
  assert.deepEqual(f.store.readProducer(), nextState);
  assert.deepEqual(readFileSync(f.ownerPath), nextLock);
  assert.deepEqual(f.row(req), pending);
  assert.doesNotThrow(() => first.observeInbound('{"not":"replayed"}'));
  assert.deepEqual(f.store.readProducer(), nextState);
});

test('heartbeat refreshes every two seconds and never revives an expired six-second lease', (t) => {
  const f = fixture(t);
  f.start();
  const initial = f.store.readProducer();
  f.tick(1999);
  assert.equal(f.store.readProducer().checkedAt, initial.checkedAt);
  f.tick(1);
  assert.equal(f.store.readProducer().checkedAt, f.now());
  f.tick(6000);
  assert.equal(f.store.readProducer().phase, 'unavailable');
  assert.ok(f.codes.includes('native_bridge_lease_expired'));
  assert.equal(existsSync(f.ownerPath), false);
});

test('heartbeat checks capture.available before renewing listening', (t) => {
  const f = fixture(t);
  f.start();
  const descriptor = Object.getOwnPropertyDescriptor(
    DesktopNativeSampleCapture.prototype,
    'available',
  );
  assert.equal(typeof descriptor.get, 'function');
  try {
    Object.defineProperty(DesktopNativeSampleCapture.prototype, 'available', {
      ...descriptor,
      get: () => false,
    });
    f.tick(2000);
    assert.equal(f.store.readProducer().phase, 'unavailable');
    assert.ok(f.codes.includes('native_bridge_capture_unavailable'));
  } finally {
    Object.defineProperty(DesktopNativeSampleCapture.prototype, 'available', descriptor);
  }
});

test('fatal storage failure stops listening; exceptions and paths do not reach logging', (t) => {
  const f = fixture(t);
  f.start();
  const req = request();
  directory(join(f.store.directory, req.params.threadId + '.json'));
  assert.doesNotThrow(() => f.bridge.observeOutbound(JSON.stringify(req)));
  assert.equal(f.store.readProducer().phase, 'unavailable');
  assert.ok(f.codes.includes('native_bridge_capture_unavailable'));
  assert.ok(
    f.codes.every((code) => /^native_bridge_[a-z_]+$/.test(code) && !code.includes(f.root)),
  );
});

test('unknown and oversized frames are nonfatal; normal later work still completes', (t) => {
  const f = fixture(t);
  f.start();
  f.bridge.observeInbound(
    JSON.stringify({ id: `thread/sample:${randomUUID()}`, result: { text: 'unknown' } }),
  );
  f.bridge.observeOutbound('x'.repeat(128 * 1024 + 1));
  f.tick(2000);
  assert.equal(f.store.readProducer().phase, 'listening');
  const req = request();
  f.send(req);
  privateFile(f.log(), f.decision(req) + '\n');
  f.tick();
  assert.equal(f.row(req).state, 'completed');
});

test('260 sequential requests retain availability and one body slot per source', (t) => {
  const f = fixture(t);
  f.start();
  const threadId = randomUUID(),
    path = f.log();
  for (let i = 0; i < 260; i++) {
    const req = request({ threadId });
    f.send(req, `Synthetic body ${i}`);
    append(path, f.decision(req) + '\n');
    f.tick(5);
    assert.equal(f.row(req).state, 'completed', `request ${i}`);
    assert.equal(f.row(req).result.text, `Synthetic body ${i}`);
  }
  assert.equal(f.store.readProducer().phase, 'listening');
  assert.deepEqual(
    readdirSync(f.store.directory).sort(),
    [threadId + '.json', 'producer-owner.json', 'producer.json'].sort(),
  );
});

test('16000 Unicode characters are supported without a second body cache', (t) => {
  const f = fixture(t);
  f.start();
  const req = request();
  f.send(req, '界'.repeat(16000));
  privateFile(f.log(), f.decision(req) + '\n');
  f.tick();
  assert.equal(f.row(req).state, 'completed');
  assert.equal(f.row(req).result.text.length, 16000);
  assert.equal(f.row(req).latestExtracted, undefined);
});

test('clock regression and config replacement revoke availability without throwing', (t) => {
  const f = fixture(t);
  f.start();
  f.tick(-1);
  assert.equal(f.store.readProducer().phase, 'unavailable');
  assert.ok(f.codes.includes('native_bridge_clock_invalid'));
  const g = fixture(t);
  g.start();
  g.saveConfig({
    ...g.config,
    target: { ...g.target, hostId: 'different-host' },
    targetKey: undefined,
  });
  g.tick(2000);
  assert.equal(g.store.readProducer().phase, 'unavailable');
  assert.ok(g.codes.includes('native_bridge_config_changed'));
});

test('identity change stops heartbeat; exception payloads never become status codes', (t) => {
  const f = fixture(t);
  let initial = true;
  f.start({
    identity: () => {
      if (initial) {
        initial = false;
        return { ...f.identity };
      }
      throw Object.assign(new Error('SECRET synthetic exception'), {
        bridgeCode: 'SECRET payload',
        env: { secret: 'SECRET environment' },
      });
    },
  });
  f.tick(2000);
  assert.equal(f.store.readProducer().phase, 'unavailable');
  assert.ok(
    f.codes.every((code) => !code.includes('SECRET') && /^native_bridge_[a-z_]+$/.test(code)),
  );
});

test('fixed capacity is eight files per tick, sixteen selected files and 1 MiB bytes', (t) => {
  assert.equal(testing.limits.filesPerTick, 8);
  assert.equal(testing.limits.files, 16);
  assert.equal(testing.limits.bytesPerTick, 1024 * 1024);
  assert.equal(testing.limits.lineBytes, 8192);
  assert.equal(testing.limits.directories, 4);
  const f = fixture(t);
  f.start();
  const req = request();
  f.send(req);
  for (let i = 0; i < 8; i++) privateFile(f.log(String(i).padStart(2, '0')), 'not a decision\n');
  privateFile(f.log('99'), f.decision(req) + '\n');
  f.tick();
  assert.equal(f.row(req).state, 'pending');
  f.tick();
  assert.equal(f.row(req).state, 'completed');
  const g = fixture(t);
  for (let i = 0; i < 17; i++) privateFile(g.log(String(i)), '');
  assert.equal(g.start(), undefined);
  assert.ok(g.codes.includes('native_bridge_log_file_limit'));
});

test(
  'POSIX tick never reads past its byte budget and resumes safely next tick',
  { skip: windows },
  (t) => {
    const f = fixture(t);
    f.start();
    const req = request();
    f.send(req);
    const filler = ('x'.repeat(8192) + '\n').repeat(128);
    privateFile(f.log(), filler + f.decision(req) + '\n');
    f.tick();
    assert.equal(f.row(req).state, 'pending');
    f.tick();
    assert.equal(f.row(req).state, 'completed');
  },
);

test(
  'Windows cannot bypass the native prefix-only helper with unchecked large-file reads',
  { skip: !windows },
  (t) => {
    const f = fixture(t),
      path = f.log();
    privateFile(path, Buffer.alloc(testing.limits.bytesPerTick + 1, 120));
    assert.equal(f.start(), undefined);
    assert.ok(f.codes.includes('native_bridge_windows_log_range_unavailable'));
    assert.equal(existsSync(f.ownerPath), false);
  },
);

test('directory enumeration and logger failures stay bounded and nonthrowing', (t) => {
  const f = fixture(t);
  f.start();
  const path = f.log(),
    parent = dirname(path);
  for (let i = 0; i <= testing.limits.directoryEntries; i++)
    privateFile(join(parent, `unselected-${i}`), '');
  f.tick();
  assert.equal(f.store.readProducer().phase, 'unavailable');
  assert.ok(f.codes.includes('native_bridge_log_entry_limit'));
  const g = fixture(t);
  assert.equal(
    g.start({
      status: () => {
        throw new Error('Synthetic logger failure');
      },
      identity: () => {
        throw new Error('SECRET body and error');
      },
    }),
    undefined,
  );
});

test('large unrelated history envelopes do not revoke a pending native suggestion', (t) => {
  const f = fixture(t);
  const bridge = f.start();
  const log = f.log('large-history-routing');
  privateFile(log, '');
  const req = request();
  bridge.observeOutbound(JSON.stringify(req));
  assert.equal(f.row(req).state, 'pending');
  // Real history can include this very code and old sample requests. Only
  // top-level RPC routing fields identify a response to our pending request.
  const history = JSON.stringify({
    result: {
      messages: [
        {
          text: 'history '.repeat(30000) + 'thread/sample',
          nested: { id: req.id, method: 'thread/sample/cancel' },
        },
      ],
    },
    id: 'unrelated-history-reader',
  });
  assert(Buffer.byteLength(history) > 128 * 1024);
  bridge.observeInbound(history);
  assert.equal(f.row(req).state, 'pending', 'unrelated large history must not invalidate a source');
  f.advance();
  bridge.observeInbound(
    JSON.stringify({
      id: req.id,
      result: { text: JSON.stringify({ suggestion: 'Original native result after history' }) },
    }),
  );
  f.advance();
  append(log, f.decision(req) + '\n');
  f.tick();
  assert.equal(f.row(req).state, 'completed');
  assert.equal(f.row(req).result.text, 'Original native result after history');
});

test('large quoted sample content cannot select a source before or after its real result', async (t) => {
  for (const direction of ['observeInbound', 'observeOutbound']) {
    for (const phase of ['request', 'candidate']) {
      await t.test(direction + '/' + phase, (st) => {
        const f = fixture(st),
          bridge = f.start(),
          req = request();
        const log = f.log('large-envelope-' + phase);
        privateFile(log, '');
        bridge.observeOutbound(JSON.stringify(req));
        const result = JSON.stringify({
          id: req.id,
          result: { text: JSON.stringify({ suggestion: 'Keep the actual source result' }) },
        });
        if (phase === 'candidate') {
          f.advance();
          bridge.observeInbound(result);
        }
        const body = {
          text: ('thread/sample ' + req.id + ' ').repeat(4000),
          nested: { method: 'thread/sample/cancel', id: req.id },
        };
        const noise =
          direction === 'observeInbound'
            ? JSON.stringify({ result: body, id: 'history-reader' }).replace(
                '"id":"history-reader"',
                '"\\u0069d":"history-reader"',
              )
            : JSON.stringify({ params: body, method: 'thread/read' }).replace(
                '"method":',
                '"meth\\u006fd":',
              );
        assert(Buffer.byteLength(noise) > 128 * 1024);
        bridge[direction](noise);
        assert.equal(f.row(req).state, 'pending');
        if (phase === 'request') {
          f.advance();
          bridge.observeInbound(result);
        }
        f.advance();
        append(log, f.decision(req) + '\n');
        f.tick();
        assert.equal(f.row(req).state, 'completed');
        assert.equal(f.row(req).result.text, 'Keep the actual source result');
        assert.equal(f.codes.includes('native_bridge_sample_frame_limit'), false);
      });
    }
  }
});

test('oversized native results and ambiguous escaped routing aliases still fail closed', async (t) => {
  for (const mode of ['native-result', 'duplicate-routing']) {
    await t.test(mode, (st) => {
      const f = fixture(st),
        bridge = f.start(),
        req = request();
      bridge.observeOutbound(JSON.stringify(req));
      const text = 'Private fixture text must never enter a diagnostic '.repeat(4000);
      const line =
        mode === 'native-result'
          ? JSON.stringify({ id: req.id, result: { text } })
          : '{"id":"history-reader","result":' +
            JSON.stringify({ text }) +
            ',"\\u0069d":' +
            JSON.stringify(req.id) +
            '}';
      assert(Buffer.byteLength(line) > 128 * 1024);
      bridge.observeInbound(line);
      assert.equal(f.row(req).state, 'unverified');
      assert.equal(f.row(req).result, null);
      assert.deepEqual(f.codes, ['native_bridge_sample_frame_limit']);
      assert(!JSON.stringify(f.codes).includes('Private fixture'));
    });
  }
});

// R11 phase-budget cases exercise this actual Bridge and real private fixture
// files. Identity and wall-clock hooks remain explicitly synthetic; neither
// these cases nor a short Atomics.wait attest Original/Windows performance.
function r11PhaseBudget(overrides = {}) {
  const grants = [];
  let ends = 0;
  return {
    grants,
    get ends() {
      return ends;
    },
    hooks: {
      active: () => true,
      startupRemaining: () => 30_000,
      begin: (milliseconds) => grants.push(milliseconds),
      end: () => {
        ends++;
      },
      ...overrides,
    },
  };
}
function r11ShortMonotonicBlock(milliseconds) {
  const word = new Int32Array(new SharedArrayBuffer(4));
  const until = performance.now() + milliseconds;
  while (performance.now() < until)
    Atomics.wait(word, 0, 0, Math.max(0, until - performance.now()));
}
function r11NoRevival(f, bridge, req, log) {
  assert.equal(bridge.available, false);
  const state = f.store.readProducer(),
    row = f.row(req);
  assert.equal(state.phase, 'unavailable');
  assert.equal(row.result, null);
  assert.notEqual(row.state, 'completed');
  const producerFile = join(f.store.directory, 'producer.json');
  const runtimeFile = join(f.store.directory, req.params.threadId + '.json');
  const producerBytes = readFileSync(producerFile),
    runtimeBytes = readFileSync(runtimeFile);
  const next = request();
  f.send(req, 'Late original candidate must never revive', bridge);
  f.send(next, 'New input cannot reopen retired epoch', bridge);
  append(log, f.decision(req) + '\n' + f.decision(next) + '\n');
  f.tick(1, bridge);
  assert.equal(bridge.available, false);
  assert.deepEqual(readFileSync(producerFile), producerBytes);
  assert.deepEqual(readFileSync(runtimeFile), runtimeBytes);
  assert.deepEqual(f.row(req), row);
  assert.equal(f.row(next), undefined);
  assert.equal(runtimeBytes.includes(Buffer.from('Private R11 candidate')), false);
  assert.equal(runtimeBytes.includes(Buffer.from('Late original candidate')), false);
  assert.equal(existsSync(f.ownerPath), false);
}

test('R11 exhausted total startup budget never inspects identity, acquires an owner or publishes listening', (t) => {
  const f = fixture(t);
  let inspections = 0;
  // Controlled elapsed monotonic startup time, independent of fixture wall time.
  const total = 30_000,
    alreadySpent = 30_000;
  const budget = r11PhaseBudget({ startupRemaining: () => total - alreadySpent });
  const bridge = f.start({
    phaseBudget: budget.hooks,
    identity: () => {
      inspections++;
      return { ...f.identity };
    },
  });
  assert.equal(bridge, undefined);
  assert.equal(inspections, 0);
  assert.equal(f.store.readProducer(), undefined);
  assert.equal(existsSync(f.ownerPath), false);
  assert.equal(f.row(request()), undefined);
  assert.ok(f.codes.includes('native_bridge_phase_deadline'));
  assert.deepEqual(
    budget.grants,
    [1000],
    'only bounded exact-owner retirement is allowed after expiry',
  );
});

test('R11 one remaining startup millisecond includes time spent in begin and retires an overrun', (t) => {
  const f = fixture(t);
  const grants = [];
  let first = true;
  const budget = r11PhaseBudget({
    startupRemaining: () => 30_000 - 29_999,
    begin(milliseconds) {
      grants.push(milliseconds);
      if (first) {
        first = false;
        r11ShortMonotonicBlock(3);
      }
    },
  });
  assert.equal(f.start({ phaseBudget: budget.hooks }), undefined);
  assert.equal(grants[0], 1, 'remaining startup budget cannot reset to 30 seconds');
  assert.ok(f.codes.includes('native_bridge_phase_deadline'));
  assert.equal(
    f.store.readProducer(),
    undefined,
    'expired startup cannot atomically publish even its first listening record',
  );
  assert.equal(existsSync(f.ownerPath), false);
  assert.equal(f.row(request()), undefined);
  assert.deepEqual(
    readdirSync(f.store.directory),
    [],
    'rejected publication also removes its temporary file',
  );
  f.advance(6000);
  assert.deepEqual(readdirSync(f.store.directory), []);
});

test('R11 listening uses the original six-second remainder across a nested heartbeat fence', (t) => {
  const f = fixture(t),
    log = f.log();
  privateFile(log, 'synthetic seed\n');
  let armed = false,
    inspections = 0;
  const budget = r11PhaseBudget();
  const bridge = f.start({
    phaseBudget: budget.hooks,
    identity: () => {
      inspections++;
      // Heartbeat inspection is inside the phase, before its second fence.
      // Crossing the old deadline here must not create a fresh six-second lease.
      if (armed) f.advance(1);
      return { ...f.identity };
    },
  });
  assert.ok(bridge);
  const initial = f.store.readProducer(),
    req = request();
  f.send(req, 'Private R11 candidate');
  append(log, f.decision(req) + '\n');
  f.advance(initial.checkedAt + 5999 - f.now());
  const beforeGrants = budget.grants.length;
  armed = true;
  f.tick(0, bridge);
  assert.equal(inspections, 2, 'real heartbeat reached its nested identity fence');
  assert.deepEqual(budget.grants.slice(beforeGrants), [1, 1000]);
  assert.ok(f.codes.includes('native_bridge_lease_expired'));
  assert.equal(f.store.readProducer().producer.epoch, initial.producer.epoch);
  assert.equal(f.store.readProducer().phase, 'unavailable');
  assert.equal(bridge.available, false);
  assert.equal(
    f.row(req).result,
    null,
    'queued exact safety decision cannot release an expired candidate',
  );
  r11NoRevival(f, bridge, req, log);
});

test('R11 already expired listening budget cannot enter work or renew through heartbeat', (t) => {
  const f = fixture(t),
    log = f.log();
  privateFile(log, 'synthetic seed\n');
  let inspections = 0;
  const budget = r11PhaseBudget();
  const bridge = f.start({
    phaseBudget: budget.hooks,
    identity: () => {
      inspections++;
      return { ...f.identity };
    },
  });
  assert.ok(bridge);
  const initial = f.store.readProducer(),
    req = request();
  f.send(req, 'Private R11 candidate');
  append(log, f.decision(req) + '\n');
  f.advance(initial.checkedAt + 6000 - f.now());
  const beforeGrants = budget.grants.length;
  f.tick(0, bridge);
  assert.equal(inspections, 1, 'expired phase never invokes heartbeat identity inspection');
  assert.deepEqual(
    budget.grants.slice(beforeGrants),
    [1000],
    'expiry allows retirement but no renewed work grant',
  );
  assert.ok(f.codes.includes('native_bridge_lease_expired'));
  assert.equal(f.store.readProducer().producer.epoch, initial.producer.epoch);
  r11NoRevival(f, bridge, req, log);
});

test('R11 inactive sampler forbids candidate-body writes while retiring only its exact owner unavailable', (t) => {
  const f = fixture(t),
    log = f.log();
  privateFile(log, 'synthetic seed\n');
  let active = true;
  const budget = r11PhaseBudget({ active: () => active });
  const bridge = f.start({ phaseBudget: budget.hooks });
  assert.ok(bridge);
  const initial = f.store.readProducer(),
    req = request();
  f.send(req, 'Private R11 candidate');
  assert.equal(f.row(req).state, 'pending');
  const pending = f.row(req);
  append(log, f.decision(req) + '\n');
  active = false;
  const beforeGrants = budget.grants.length;
  f.tick(0, bridge);
  assert.deepEqual(budget.grants.slice(beforeGrants), [1000]);
  assert.equal(bridge.available, false);
  assert.ok(f.codes.includes('native_bridge_observation_failed'));
  const retired = f.store.readProducer();
  assert.equal(retired.phase, 'unavailable');
  for (const field of [
    'producer',
    'targetKey',
    'rootIdentity',
    'implementationSha256',
    'wrapperStartedAt',
  ])
    assert.deepEqual(retired[field], initial[field], `retirement preserves exact ${field}`);
  assert.deepEqual(f.row(req), pending, 'active=false also fences capture.close runtime writes');
  assert.equal(existsSync(f.ownerPath), false);
  active = true;
  r11NoRevival(f, bridge, req, log);
});

test('R11 inactive old sampler cannot retire a replacement owner or publish its private candidate', (t) => {
  const f = fixture(t),
    log = f.log();
  privateFile(log, 'synthetic seed\n');
  let active = true;
  const budget = r11PhaseBudget({ active: () => active });
  const bridge = f.start({ phaseBudget: budget.hooks });
  assert.ok(bridge);
  const req = request();
  f.send(req, 'Private R11 candidate');
  const pending = f.row(req),
    old = f.store.readProducer();
  const replacement = { ...old, producer: { ...old.producer, epoch: randomUUID() } };
  privateFile(
    f.ownerPath,
    JSON.stringify(f.owner(replacement.producer, old.wrapperStartedAt)) + '\n',
  );
  f.store.saveProducer(replacement);
  const ownerBytes = readFileSync(f.ownerPath);
  const producerBytes = readFileSync(join(f.store.directory, 'producer.json'));
  append(log, f.decision(req) + '\n');
  active = false;
  f.tick(0, bridge);
  assert.equal(bridge.available, false);
  assert.deepEqual(f.row(req), pending);
  assert.deepEqual(f.store.readProducer(), replacement);
  assert.deepEqual(readFileSync(f.ownerPath), ownerBytes);
  assert.deepEqual(readFileSync(join(f.store.directory, 'producer.json')), producerBytes);
  active = true;
  f.send(req, 'Late original candidate must never revive', bridge);
  f.tick(1, bridge);
  assert.deepEqual(f.row(req), pending);
  assert.deepEqual(readFileSync(f.ownerPath), ownerBytes);
  assert.deepEqual(readFileSync(join(f.store.directory, 'producer.json')), producerBytes);
});

function r11ReadTemporary(path) {
  return JSON.parse(JSON.parse(readFileSync(path, 'utf8')).body);
}
function r11PublicationProbe(t, f, kind, { prepared = () => {}, beforeRename = () => {} } = {}) {
  const name = kind === 'producer' ? 'producer.json' : kind + '.json';
  const path = join(f.store.directory, name);
  const selected = (record) =>
    name === 'producer.json' ? record.phase === 'listening' : record.state === 'completed';
  const probe = { preparations: [], guards: [], rejections: [], renames: [], retirementGuards: [] };
  const method = name === 'producer.json' ? 'saveProducer' : 'saveRuntime';
  const originalSave = DesktopSuggestionStore.prototype[method];
  const saveMock = t.mock.method(
    DesktopSuggestionStore.prototype,
    method,
    function (record, guard) {
      if (
        this.directory === f.store.directory &&
        method === 'saveProducer' &&
        record.phase === 'unavailable'
      )
        probe.retirementGuards.push(typeof guard);
      if (this.directory !== f.store.directory || !selected(record))
        return originalSave.call(this, record, guard);
      const guarded =
        typeof guard === 'function'
          ? () => {
              probe.guards.push({ prepared: probe.preparations.length });
              return guard();
            }
          : guard;
      try {
        return originalSave.call(this, record, guarded);
      } catch (error) {
        // Observe the original file immediately after Store unwinds, before
        // Bridge's separately permitted unavailable retirement can replace it.
        probe.rejections.push({
          error,
          bytes: readFileSync(path),
          files: readdirSync(f.store.directory).sort(),
        });
        throw error;
      }
    },
  );
  const originalFsync = fs.fsyncSync,
    originalRename = fs.renameSync;
  const fsyncMock = t.mock.method(fs, 'fsyncSync', function (fd) {
    const value = originalFsync.call(this, fd);
    if (!existsSync(f.store.directory)) return value;
    const stat = fs.fstatSync(fd, { bigint: true });
    if (!stat.isFile()) return value;
    for (const entry of readdirSync(f.store.directory)) {
      if (!entry.startsWith(name + '.') || !entry.endsWith('.tmp')) continue;
      const temporary = join(f.store.directory, entry),
        info = lstatSync(temporary, { bigint: true });
      if (!info.isFile() || info.isSymbolicLink() || info.ino !== stat.ino || info.dev !== stat.dev)
        continue;
      const record = r11ReadTemporary(temporary);
      if (selected(record)) {
        probe.preparations.push({ temporary, record, previous: readFileSync(path) });
        // Actual private temp bytes have been written, protected and fsynced.
        // Only this exact test-owned descriptor may consume the phase budget.
        prepared(record);
      }
    }
    return value;
  });
  const renameMock = t.mock.method(fs, 'renameSync', function (source, destination) {
    if (
      typeof source === 'string' &&
      dirname(source) === f.store.directory &&
      source.endsWith('.tmp')
    ) {
      const record = r11ReadTemporary(source);
      probe.renames.push({ source, destination, phase: record.phase, state: record.state });
      if (destination === path && selected(record)) beforeRename(record);
    }
    return originalRename.call(this, source, destination);
  });
  // Product imports are real ESM builtin bindings. Update and restore them, not
  // a separate imitation Store or copied publication implementation.
  syncBuiltinESMExports();
  t.after(() => {
    saveMock.mock.restore();
    fsyncMock.mock.restore();
    renameMock.mock.restore();
    syncBuiltinESMExports();
  });
  return probe;
}

function r11PendingAndCompletedProposal(f) {
  const req = request(),
    log = f.log();
  privateFile(log, 'synthetic seed\n');
  // Seed before start so this actual Bridge owns the log's initial EOF.
  f.start({ phaseBudget: r11PhaseBudget().hooks });
  assert.ok(f.bridge);
  f.send(req, 'Private guard proposal');
  const path = join(f.store.directory, req.params.threadId + '.json');
  const pending = readFileSync(path);
  append(log, f.decision(req) + '\n');
  f.tick();
  const completed = f.row(req);
  assert.equal(completed.state, 'completed');
  // Reinstall only this fresh synthetic fixture's exact earlier snapshot. This
  // supplies a genuinely Bridge-produced proposal without duplicating hashes,
  // schemas or publication rules, and never edits any Original history.
  privateFile(path, pending);
  assert.equal(f.row(req).state, 'pending');
  assert.equal(f.row(req).result, null);
  return { req, path, pending, completed };
}

for (const kind of ['producer', 'runtime']) {
  test(`R11 Store ${kind} guard rejection preserves exact old bytes and reclaims its fsynced temp`, (t) => {
    const f = fixture(t);
    let path, proposal, oldValue, save, probeKind;
    if (kind === 'runtime') {
      const seeded = r11PendingAndCompletedProposal(f);
      path = seeded.path;
      proposal = seeded.completed;
      oldValue = f.row(seeded.req);
      probeKind = seeded.req.params.threadId;
      save = (guard) => f.store.saveRuntime(proposal, guard);
    } else {
      assert.ok(f.start({ phaseBudget: r11PhaseBudget().hooks }));
      path = join(f.store.directory, 'producer.json');
      oldValue = f.store.readProducer();
      proposal = { ...oldValue, checkedAt: oldValue.checkedAt + 1 };
      probeKind = 'producer';
      save = (guard) => f.store.saveProducer(proposal, guard);
    }
    const oldBytes = readFileSync(path),
      oldFiles = readdirSync(f.store.directory).sort();
    const probe = r11PublicationProbe(t, f, probeKind);
    const refusal = new Error('synthetic publication guard refusal');
    let calls = 0;
    assert.throws(
      () =>
        save(() => {
          calls++;
          throw refusal;
        }),
      (error) => error === refusal,
    );
    assert.equal(calls, 1);
    assert.equal(probe.preparations.length, 1, 'real temp file reached fsync before refusal');
    assert.deepEqual(probe.preparations[0].previous, oldBytes);
    assert.deepEqual(probe.guards, [{ prepared: 1 }]);
    assert.equal(probe.renames.length, 0, 'throwing guard prevents atomic publication');
    assert.deepEqual(readFileSync(path), oldBytes);
    assert.deepEqual(
      readdirSync(f.store.directory).sort(),
      oldFiles,
      'no temporary file or runtime write-lock remains',
    );
    assert.equal(existsSync(probe.preparations[0].temporary), false);
    assert.deepEqual(
      kind === 'producer' ? f.store.readProducer() : f.store.readRuntime(proposal.sourceThreadId),
      oldValue,
    );
  });
}

for (const scenario of ['producer-lease', 'runtime-lease', 'producer-phase']) {
  test(`R11 slow real temporary preparation cannot publish beyond the original deadline (${scenario})`, (t) => {
    const f = fixture(t),
      log = f.log();
    privateFile(log, 'synthetic seed\n');
    assert.ok(f.start({ phaseBudget: r11PhaseBudget().hooks }));
    const bridge = f.bridge,
      initial = f.store.readProducer(),
      req = request();
    f.send(req, 'Private R11 candidate');
    append(log, f.decision(req) + '\n');
    const runtime = scenario === 'runtime-lease';
    if (!runtime)
      f.advance(initial.checkedAt + (scenario === 'producer-phase' ? 5999 : 2000) - f.now());
    const path = join(f.store.directory, runtime ? req.params.threadId + '.json' : 'producer.json');
    const before = readFileSync(path);
    let consumed = false;
    const probe = r11PublicationProbe(t, f, runtime ? req.params.threadId : 'producer', {
      prepared() {
        if (consumed) return;
        consumed = true;
        if (scenario === 'producer-phase') r11ShortMonotonicBlock(3);
        else f.advance(initial.checkedAt + 6000 - f.now());
      },
    });
    f.tick(0, bridge);
    assert.equal(consumed, true, 'the selected real temp preparation was reached');
    assert.equal(probe.preparations.length, 1);
    assert.deepEqual(probe.preparations[0].previous, before);
    assert.deepEqual(
      probe.guards,
      [{ prepared: 1 }],
      'actual Bridge passes its guard through Store to the rename boundary',
    );
    assert.equal(probe.rejections.length, 1);
    assert.deepEqual(
      probe.rejections[0].bytes,
      before,
      'rejected publication preserves the previous atomic file',
    );
    assert.equal(
      probe.rejections[0].error.bridgeCode,
      scenario === 'producer-phase'
        ? 'native_bridge_phase_deadline'
        : 'native_bridge_lease_expired',
    );
    assert.equal(existsSync(probe.preparations[0].temporary), false);
    assert.equal(
      probe.rejections[0].files.some(
        (name) => name.endsWith('.tmp') || name.endsWith('.write-lock'),
      ),
      false,
    );
    assert.equal(
      probe.renames.some((value) =>
        runtime ? value.state === 'completed' : value.phase === 'listening',
      ),
      false,
    );
    assert.equal(
      probe.renames.filter((value) => value.phase === 'unavailable').length,
      1,
      'unavailable retirement still publishes without renewing the rejected phase',
    );
    if (!runtime) assert.deepEqual(probe.retirementGuards, ['undefined']);
    assert.equal(f.store.readProducer().producer.epoch, initial.producer.epoch);
    assert.equal(f.store.readProducer().phase, 'unavailable');
    assert.equal(f.row(req).result, null);
    r11NoRevival(f, bridge, req, log);
  });
}

for (const kind of ['producer', 'runtime']) {
  test(`R11 ${kind} publication retry rechecks its guard after a synthetic busy rename`, (t) => {
    const f = fixture(t);
    let path, proposal, save, probeKind;
    if (kind === 'runtime') {
      const seeded = r11PendingAndCompletedProposal(f);
      path = seeded.path;
      proposal = seeded.completed;
      probeKind = seeded.req.params.threadId;
      save = (guard) => f.store.saveRuntime(proposal, guard);
    } else {
      assert.ok(f.start({ phaseBudget: r11PhaseBudget().hooks }));
      path = join(f.store.directory, 'producer.json');
      proposal = { ...f.store.readProducer(), checkedAt: f.now() + 1 };
      probeKind = 'producer';
      save = (guard) => f.store.saveProducer(proposal, guard);
    }
    const oldBytes = readFileSync(path),
      oldFiles = readdirSync(f.store.directory).sort();
    let attempts = 0,
      expired = false,
      calls = 0;
    const probe = r11PublicationProbe(t, f, probeKind, {
      beforeRename() {
        attempts++;
        expired = true;
        // Portable fault injection, not a claim about Win32 sharing behavior.
        throw Object.assign(new Error('synthetic sharing overlap'), { code: 'EBUSY' });
      },
    });
    const refusal = new Error('synthetic original deadline expired');
    assert.throws(
      () =>
        save(() => {
          calls++;
          if (expired) throw refusal;
        }),
      (error) => error === refusal,
    );
    assert.equal(attempts, 1, 'expired retry cannot reach a second rename');
    assert.equal(calls, 2, 'guard executes for both the first attempt and its retry');
    assert.deepEqual(probe.guards, [{ prepared: 1 }, { prepared: 1 }]);
    assert.equal(probe.preparations.length, 1, 'retry uses the same immutable prepared file');
    assert.deepEqual(readFileSync(path), oldBytes);
    assert.deepEqual(readdirSync(f.store.directory).sort(), oldFiles);
    assert.equal(existsSync(probe.preparations[0].temporary), false);
    if (kind === 'runtime') assert.equal(f.store.readRuntime(proposal.sourceThreadId).result, null);
    else assert.notEqual(f.store.readProducer().checkedAt, proposal.checkedAt);
  });
}
