import assert from 'node:assert/strict';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import child from 'node:child_process';
import { syncBuiltinESMExports } from 'node:module';
import { promisify } from 'node:util';
import { tmpdir } from 'node:os';
import { basename, join } from 'node:path';
import { createHash } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { fileURLToPath } from 'node:url';
import { bundle } from './fixtures/desktop-trace.mjs';

if (process.platform !== 'darwin') {
  console.log('SKIP host trace inventory: requires macOS lsof');
  process.exit(0);
}
const mode = process.argv[2] ?? 'fixed';
assert(['baseline', 'fixed'].includes(mode));
const evidence = process.env.HOST_TRACE_INVENTORY_EVIDENCE_DIR;
const root = fs.realpathSync(fs.mkdtempSync(join(tmpdir(), 'host-trace-inventory-')));
fs.chmodSync(root, 0o700);
const descriptors = [];
const targetSize = 32 * 1024 * 1024;
const originals = {};
let handlePrototype;
try {
  const traces = [];
  let invalidSource;
  for (let i = 0; i < 225; i++) {
    const f = bundle(root);
    if (i < 4) {
      f.protocolEvent('session_configured', {
        type: 'session_configured',
        thread_id: f.predictionThreadId,
        thread_source: 'user',
        cwd: root,
      });
      const req = f.addRequest({ metadata: { thread_source: 'user', turn_trigger: 'user' } });
      delete req.metadata.prediction_source_thread_id;
      delete req.metadata.prediction_source_turn_id;
      delete req.metadata.forked_from_thread_id;
      req.request.client_metadata['x-codex-turn-metadata'] = JSON.stringify(req.metadata);
      fs.writeFileSync(req.requestPath, JSON.stringify(req.request));
      f.addResponse(req);
      f.endTurn();
      f.events.push({
        schema_version: 1,
        seq: f.events.length + 1,
        wall_time_unix_ms: 5,
        rollout_id: f.predictionThreadId,
        thread_id: null,
        codex_turn_id: null,
        payload: { type: 'rollout_ended', status: 'completed' },
      });
      f.saveEvents();
      const payloadDir = join(f.directory, 'payloads');
      for (let n = 1000; n < 1500; n++)
        fs.writeFileSync(join(payloadDir, `${n}.json`), Buffer.alloc(128, n % 251), {
          mode: 0o600,
        });
      const smallBytes = fs
        .readdirSync(payloadDir)
        .reduce((sum, n) => sum + fs.statSync(join(payloadDir, n)).size, 0);
      const big = join(payloadDir, '999999.json');
      const fd = fs.openSync(big, 'w', 0o600);
      const chunk = Buffer.alloc(1024 * 1024, 0x5a);
      let remaining = targetSize - smallBytes;
      while (remaining) {
        const count = Math.min(remaining, chunk.length);
        fs.writeSync(fd, chunk, 0, count);
        remaining -= count;
      }
      fs.closeSync(fd);
      const old = new Date(Date.now() - 10000);
      fs.utimesSync(f.logPath, old, old);
      traces.push({ f, big });
    } else if (i === 4) {
      f.protocolEvent('session_configured', {
        type: 'session_configured',
        thread_id: f.predictionThreadId,
        thread_source: 'unknown-source',
        cwd: root,
      });
      f.endTurn();
      f.events.push({
        schema_version: 1,
        seq: f.events.length + 1,
        wall_time_unix_ms: 5,
        rollout_id: f.predictionThreadId,
        thread_id: null,
        codex_turn_id: null,
        payload: { type: 'rollout_ended', status: 'completed' },
      });
      f.saveEvents();
      const old = new Date(Date.now() - 10000);
      fs.utimesSync(f.logPath, old, old);
      invalidSource = f;
    }
  }
  const fingerprint = (f) => {
    const hash = createHash('sha256');
    for (const name of [
      'manifest.json',
      'trace.jsonl',
      ...fs
        .readdirSync(join(f.directory, 'payloads'))
        .sort()
        .map((n) => `payloads/${n}`),
    ]) {
      hash.update(name);
      hash.update(fs.readFileSync(join(f.directory, name)));
    }
    return hash.digest('hex');
  };
  const before = traces.map(({ f }) => fingerprint(f));
  for (const { big } of traces) descriptors.push(fs.openSync(big, 'r'));
  const probe = await fsp.open(traces[0].big, 'r');
  handlePrototype = Object.getPrototypeOf(probe);
  await probe.close();
  let sampleRss = () => {};
  let lsofHook = null;
  let lsofSequence = 0;
  const counters = {
    readSyncCalls: 0,
    readSyncBytes: 0,
    readFileSyncCalls: 0,
    readFileSyncBytes: 0,
    asyncReadCalls: 0,
    asyncReadBytes: 0,
    lstatSyncCalls: 0,
    children: 0,
    lsof: 0,
  };
  for (const key of ['readSync', 'readFileSync', 'lstatSync']) originals[key] = fs[key];
  originals.read = handlePrototype.read;
  originals.execFile = child.execFile;
  fs.readSync = function (...args) {
    const n = originals.readSync.apply(this, args);
    counters.readSyncCalls++;
    counters.readSyncBytes += n;
    sampleRss();
    return n;
  };
  fs.readFileSync = function (...args) {
    const data = originals.readFileSync.apply(this, args);
    counters.readFileSyncCalls++;
    counters.readFileSyncBytes += Buffer.byteLength(data);
    return data;
  };
  fs.lstatSync = function (...args) {
    counters.lstatSyncCalls++;
    return originals.lstatSync.apply(this, args);
  };
  handlePrototype.read = async function (...args) {
    const result = await originals.read.apply(this, args);
    counters.asyncReadCalls++;
    counters.asyncReadBytes += result.bytesRead;
    sampleRss();
    return result;
  };
  function execFile(...args) {
    counters.children++;
    if (args[0] === '/usr/sbin/lsof') counters.lsof++;
    return originals.execFile.apply(this, args);
  }
  execFile[promisify.custom] = async (...args) => {
    counters.children++;
    const isLsof = args[0] === '/usr/sbin/lsof';
    if (isLsof) counters.lsof++;
    try {
      return await originals.execFile[promisify.custom](...args);
    } finally {
      sampleRss();
      if (isLsof && lsofHook) await lsofHook(++lsofSequence);
    }
  };
  child.execFile = execFile;
  syncBuiltinESMExports();
  const modulePath =
    mode === 'baseline'
      ? '../packages/codex-adapter/dist/desktop-trace-retention.baseline.js'
      : '../packages/codex-adapter/dist/desktop-trace-retention.js';
  const { DesktopTraceRetention } = await import(modulePath);
  const retention = new DesktopTraceRetention(root, 'host-a');
  Object.keys(counters).forEach((k) => (counters[k] = 0));
  const rssStartBytes = process.memoryUsage.rss();
  let rssPeakBytes = rssStartBytes;
  sampleRss = () => {
    rssPeakBytes = Math.max(rssPeakBytes, process.memoryUsage.rss());
  };
  const rssTimer = setInterval(sampleRss, 5);
  const cpu = process.cpuUsage();
  const started = performance.now();
  const results = [];
  for (const { f } of traces) results.push(await retention.process(basename(f.directory)));
  const wallMs = performance.now() - started;
  const usage = process.cpuUsage(cpu);
  sampleRss();
  clearInterval(rssTimer);
  sampleRss = () => {};
  const maxRssBytes = process.resourceUsage().maxRSS * 1024;
  const measuredCounters = { ...counters };
  const moduleSha256 = createHash('sha256')
    .update(fs.readFileSync(fileURLToPath(new URL(modulePath, import.meta.url))))
    .digest('hex');
  for (const key of ['readSync', 'readFileSync', 'lstatSync']) fs[key] = originals[key];
  handlePrototype.read = originals.read;
  syncBuiltinESMExports();
  assert.deepEqual(
    results.map((r) => r.state),
    Array(4).fill('occupied'),
  );
  assert.deepEqual(
    traces.map(({ f }) => fingerprint(f)),
    before,
  );
  if (mode === 'baseline') assert(measuredCounters.asyncReadBytes >= 4 * targetSize);
  else
    assert.equal(
      measuredCounters.asyncReadBytes,
      0,
      'occupied traces must not perform payload inventory',
    );
  const guards = {};
  if (mode === 'fixed') {
    while (descriptors.length) fs.closeSync(descriptors.pop());
    const closed = await retention.process(basename(traces[0].f.directory));
    assert.equal(closed.state, 'retired');
    assert.equal(fs.existsSync(traces[0].f.directory), false);
    guards.closed = closed.state;
    const invalidResult = await retention.process(basename(invalidSource.directory));
    assert.equal(invalidResult.state, 'source-unverified');
    assert(fs.existsSync(invalidSource.directory));
    guards.invalidSource = invalidResult.state;
    for (const [index, trigger, kind] of [
      [1, 1, 'openedBeforePreRenameCheck'],
      [2, 2, 'openedBeforePostRenameCheck'],
      [3, 2, 'contentChangedAfterInventory'],
    ]) {
      const { f, big } = traces[index];
      let injectedFd;
      let changedFingerprint;
      lsofSequence = 0;
      lsofHook = async (sequence) => {
        if (sequence !== trigger) return;
        if (kind === 'contentChangedAfterInventory') {
          fs.appendFileSync(big, Buffer.from('changed'));
          changedFingerprint = fingerprint(f);
        } else injectedFd = fs.openSync(big, 'r');
      };
      let result;
      try {
        result = await retention.process(basename(f.directory));
      } finally {
        lsofHook = null;
        if (injectedFd !== undefined) fs.closeSync(injectedFd);
      }
      assert.equal(result.state, trigger === 1 ? 'occupied' : 'occupied-or-changed');
      assert(fs.existsSync(f.directory));
      if (kind !== 'contentChangedAfterInventory') assert.equal(fingerprint(f), before[index]);
      else {
        assert(fs.readFileSync(big).subarray(-7).equals(Buffer.from('changed')));
        assert.equal(fingerprint(f), changedFingerprint);
      }
      guards[kind] = result.state;
    }
  }
  const report = {
    mode,
    traceDirectories: 225,
    occupiedTraces: 4,
    payloadBytesEach: targetSize,
    extraSmallPayloadsEach: 500,
    results: results.map((r) => r.state),
    bytesPreserved: true,
    counters: measuredCounters,
    moduleSha256,
    nodeVersion: process.version,
    rssStartBytes,
    rssPeakBytes,
    rssPeakIncreaseBytes: rssPeakBytes - rssStartBytes,
    rssMeasurement:
      'sampled at each instrumented read and child completion and every 5ms; may miss transient peaks',
    guards,
    wallMs,
    cpuUserMs: usage.user / 1000,
    cpuSystemMs: usage.system / 1000,
    processLifetimeMaxRssBytes: maxRssBytes,
    fixture: 'synthetic; live process holds one payload fd per occupied trace',
  };
  if (evidence) {
    fs.mkdirSync(evidence, { recursive: true, mode: 0o700 });
    fs.writeFileSync(join(evidence, `${mode}.json`), JSON.stringify(report, null, 2) + '\n', {
      mode: 0o600,
    });
  }
  console.log(JSON.stringify(report, null, 2));
} finally {
  for (const key of ['readSync', 'readFileSync', 'lstatSync'])
    if (originals[key]) fs[key] = originals[key];
  if (handlePrototype && originals.read) handlePrototype.read = originals.read;
  if (originals.execFile) child.execFile = originals.execFile;
  syncBuiltinESMExports();
  for (const fd of descriptors) fs.closeSync(fd);
  fs.rmSync(root, { recursive: true, force: true });
  assert.equal(fs.existsSync(root), false);
}
