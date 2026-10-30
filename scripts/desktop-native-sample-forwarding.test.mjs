import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { PassThrough } from 'node:stream';
import { test } from 'node:test';
import { MessageChannel, Worker } from 'node:worker_threads';
import { forwardOriginalDesktopWithNativeSampler } from '../packages/codex-adapter/dist/desktop-native-sample-forwarding.js';
import { DesktopNativeSampleFrames } from '../packages/codex-adapter/dist/desktop-native-sample-framing.js';
import { getWindowsProcessWorker } from '../packages/codex-adapter/dist/windows-process.js';

// Portable auxiliary boundary evidence only. No Original process, native Windows
// backend, Electron, model, HTTP, or performance attestation is involved.
const maximumBytes = 32 * 1024 * 1024;
const maximumMessages = 2048;
const digest = (bytes) => createHash('sha256').update(bytes).digest('hex');
const lineSummary = (direction, line) => ({
  direction,
  bytes: Buffer.byteLength(line),
  sha256: digest(line),
});
async function bounded(promise, label) {
  let timer;
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error(`Timed out: ${label}`)), 5000);
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}
function reports(port) {
  const history = [];
  const pending = new Set();
  port.on('message', (value) => {
    history.push(value);
    for (const waiter of pending) {
      if (!waiter.predicate(value)) continue;
      pending.delete(waiter);
      waiter.resolve(value);
    }
  });
  return {
    history,
    async wait(predicate, label) {
      const existing = history.find(predicate);
      if (existing) return existing;
      let waiter;
      try {
        return await bounded(
          new Promise((resolve) => {
            waiter = { predicate, resolve };
            pending.add(waiter);
          }),
          label,
        );
      } finally {
        pending.delete(waiter);
      }
    },
  };
}
function forwardingFixture(t, options = {}) {
  const shared = new SharedArrayBuffer(4);
  const gateBuffer = new SharedArrayBuffer(7 * 4);
  const gate = new Int32Array(gateBuffer);
  const { port1, port2 } = new MessageChannel();
  const received = reports(port1);
  const streams = Object.fromEntries(
    ['input', 'childInput', 'childOutput', 'output'].map((key) => [
      key,
      new PassThrough({ highWaterMark: maximumBytes + 1 }),
    ]),
  );
  const expected = {},
    actual = {},
    retained = [];
  for (const [direction, sink] of [
    ['outbound', streams.childInput],
    ['inbound', streams.output],
  ]) {
    expected[direction] = { bytes: 0, hash: createHash('sha256') };
    actual[direction] = { bytes: 0, hash: createHash('sha256') };
    sink.on('data', (bytes) => {
      actual[direction].bytes += bytes.byteLength;
      actual[direction].hash.update(bytes);
      if (direction === 'inbound') Atomics.store(gate, 4, 1);
    });
  }
  const write = (direction, value) => {
    const bytes = typeof value === 'string' ? Buffer.from(value) : value;
    const before = digest(bytes),
      size = bytes.byteLength;
    expected[direction].bytes += size;
    expected[direction].hash.update(bytes);
    retained.push({ bytes, size, before });
    (direction === 'outbound' ? streams.input : streams.childOutput).write(bytes);
    // This observes the actual downstream PassThrough, even while Worker blocks.
    assert.equal(actual[direction].bytes, expected[direction].bytes);
    assert.equal(bytes.byteLength, size, 'original buffer must never detach');
    assert.equal(digest(bytes), before);
  };
  let worker,
    exited,
    exitCode,
    factoryObserved = false;
  let factories = 0;
  const errors = [];
  const sampler = forwardOriginalDesktopWithNativeSampler(streams, () => {
    factories++;
    assert(streams.input.listenerCount('data') > 0, 'outbound pipe exists before factory');
    assert(streams.childOutput.listenerCount('data') > 0, 'inbound pipe exists before factory');
    write('outbound', 'factory-outbound\n');
    write('inbound', 'factory-inbound\n');
    factoryObserved = true;
    worker = new Worker(
      new URL('./fixtures/desktop-native-sample-forwarding-worker.mjs', import.meta.url),
      {
        workerData: {
          kind: 'forwarding-boundary-fixture',
          shared,
          gate: gateBuffer,
          report: port2,
          ack: options.ack,
          frames: options.frames ?? false,
        },
        transferList: [port2],
        execArgv: [],
      },
    );
    worker.on('error', (error) => errors.push(error));
    exited = new Promise((resolve) =>
      worker.once('exit', (code) => {
        exitCode = code;
        resolve(code);
      }),
    );
    return { worker, shared };
  });
  t.after(async () => {
    Atomics.store(gate, 0, 1);
    Atomics.notify(new Int32Array(shared), 0);
    try {
      if (worker && worker.threadId !== -1) {
        try {
          assert.equal(await bounded(sampler.close(), 'fixture cleanup retirement'), true);
        } finally {
          // Only a failed test's own Worker may need emergency cleanup.
          if (worker.threadId !== -1) await worker.terminate();
        }
      }
    } finally {
      port1.close();
      if (!worker) port2.close();
      streams.input.unpipe(streams.childInput);
      streams.childOutput.unpipe(streams.output);
      for (const stream of Object.values(streams)) stream.destroy();
    }
  });
  assert(factoryObserved, 'factory must verify real flowing pipes');
  assert(worker instanceof Worker);
  const ownerId = worker.threadId;
  assert.equal(getWindowsProcessWorker(ownerId), worker);
  const integrity = () => {
    for (const direction of ['outbound', 'inbound']) {
      assert.equal(actual[direction].bytes, expected[direction].bytes);
      assert.equal(
        actual[direction].hash.copy().digest('hex'),
        expected[direction].hash.copy().digest('hex'),
      );
    }
    for (const { bytes, size, before } of retained) {
      assert.equal(bytes.byteLength, size, 'Worker must not detach original storage');
      assert.equal(digest(bytes), before, 'Worker must not mutate original storage');
    }
    assert.equal(factories, 1, 'no replacement sampler or replay');
  };
  const wait = (kind, predicate = () => true) =>
    received.wait((value) => value.kind === kind && predicate(value), kind);
  return {
    write,
    wait,
    gate,
    shared,
    worker,
    received,
    sampler,
    integrity,
    async blocked() {
      assert.equal(Atomics.load(gate, 1), 0, 'factory bytes do not start the later tee');
      Atomics.store(gate, 4, 0); // Require new downstream output from the start-triggering write.
      write('outbound', '{"old-outbound":"');
      write('inbound', '{"old-inbound":"');
      const start = await wait('started');
      assert.equal(start.originalFirstData, 1, 'first original output precedes sampler start');
      await wait('blocked');
      assert.equal(Atomics.load(gate, 6), 1);
      assert.equal(Atomics.load(gate, 2), 0);
      assert.equal(
        received.history.some((value) => value.kind === 'bytes'),
        false,
      );
    },
    async ready() {
      Atomics.store(gate, 0, 1);
      Atomics.notify(new Int32Array(shared), 0);
      return await wait('begun');
    },
    async snapshot() {
      const id = received.history.length + 1;
      port1.postMessage({ kind: 'snapshot', id });
      return await wait('snapshot', (value) => value.id === id);
    },
    emit(value) {
      port1.postMessage({ kind: 'emit', value });
    },
    exit() {
      port1.postMessage({ kind: 'exit' });
    },
    async retired() {
      const first = sampler.close();
      assert.equal(sampler.close(), first, 'close is idempotent with the same retirement promise');
      assert.equal(await bounded(first, 'sampler confirmed retirement'), true);
      assert.equal(await bounded(exited, 'Worker exit'), 0);
      assert.equal(exitCode, 0);
      assert.equal(worker.threadId, -1);
      assert.equal(getWindowsProcessWorker(ownerId), undefined, 'actual exited owner is reaped');
      assert.deepEqual(errors, []);
      assert.equal(Atomics.load(new Int32Array(shared), 0), 1);
      const result = await wait('closed');
      assert.equal(result.sequenceFault, false);
      integrity();
      return result;
    },
  };
}

test('real blocked Worker cannot delay raw pipes; begin discards both old half-frames without replay', async (t) => {
  const f = forwardingFixture(t, { frames: true });
  await f.blocked();
  const history = Buffer.from('历史🙂\r\n'.repeat(160_000));
  const binary = new Uint8Array(new ArrayBuffer(65536));
  for (let i = 0; i < binary.length; i++) binary[i] = i % 256;
  binary[binary.length - 1] = 0xff;
  f.write('outbound', history);
  f.write('inbound', history);
  f.write('outbound', binary);
  f.write('inbound', binary);
  f.integrity();
  assert.equal(Atomics.load(f.gate, 2), 0, 'all history and binary already flowed before ready');
  assert.equal(
    f.received.history.some((value) => value.kind === 'bytes'),
    false,
  );
  const begin = await f.ready();
  assert.deepEqual(begin.boundary, { outbound: false, inbound: false });
  const outboundLine = '{"fresh":"界🙂"}',
    inboundLine = '{"reply":"é好"}';
  const out = Buffer.from('old-suffix\r\n' + outboundLine + '\r\n');
  const incoming = Buffer.from('old-suffix\n' + inboundLine + '\r\n');
  const outSplit = out.indexOf(Buffer.from('界')) + 1;
  const inSplit = incoming.indexOf(Buffer.from('é')) + 1;
  const chunks = [
    ['outbound', out.subarray(0, outSplit)],
    ['inbound', incoming.subarray(0, inSplit)],
    ['outbound', out.subarray(outSplit, out.length - 1)],
    ['inbound', incoming.subarray(inSplit, incoming.length - 1)],
    ['outbound', out.subarray(out.length - 1)],
    ['inbound', incoming.subarray(incoming.length - 1)],
  ];
  for (const [direction, bytes] of chunks) f.write(direction, bytes);
  await f.wait('bytes', (value) => value.sequence === chunks.length);
  const state = await f.snapshot();
  assert.deepEqual(state.frames, [
    lineSummary('outbound', outboundLine),
    lineSummary('inbound', inboundLine),
  ]);
  assert.deepEqual(state.channels, {
    outbound: { bytes: out.length, sha256: digest(out) },
    inbound: { bytes: incoming.length, sha256: digest(incoming) },
  });
  const observed = f.received.history.filter((value) => value.kind === 'bytes');
  assert.deepEqual(
    observed.map(({ sequence, direction, size, sha256 }) => ({
      sequence,
      direction,
      size,
      sha256,
    })),
    chunks.map(([direction, bytes], index) => ({
      sequence: index + 1,
      direction,
      size: bytes.length,
      sha256: digest(bytes),
    })),
  );
  await f.retired();
  f.write('outbound', 'original still runs after observer close\n');
  f.write('inbound', Buffer.from([0, 0xff, 13, 10]));
  f.integrity();
});

test('ready boundary flags remain independent for outbound and inbound', async (t) => {
  const f = forwardingFixture(t, { frames: true });
  await f.blocked();
  f.write('outbound', 'old-outbound-completed\r\n');
  const begin = await f.ready();
  assert.deepEqual(begin.boundary, { outbound: true, inbound: false });
  f.write('outbound', 'first fresh outbound\n');
  f.write('inbound', 'old suffix\nfirst fresh inbound\n');
  await f.wait('bytes', (value) => value.sequence === 2);
  const state = await f.snapshot();
  assert.deepEqual(state.frames, [
    lineSummary('outbound', 'first fresh outbound'),
    lineSummary('inbound', 'first fresh inbound'),
  ]);
  await f.retired();
});

test('ready raw tee copies split UTF-8/CRLF, large history and standalone binary storage without detaching originals', async (t) => {
  const f = forwardingFixture(t);
  await f.blocked();
  await f.ready();
  const binary = new Uint8Array(new ArrayBuffer(131_079), 7, 131_072);
  for (let i = 0; i < binary.length; i++) binary[i] = i % 256;
  const history = Buffer.from(
    '{"history":"' + 'Quoted 界🙂 model history '.repeat(100_000) + '"}\r\n',
  );
  const text = Buffer.from('界🙂\r\n');
  const chunks = [
    text.subarray(0, 1),
    text.subarray(1, text.length - 1),
    text.subarray(text.length - 1),
    history,
    binary,
  ];
  const copies = {};
  let sequence = 0;
  for (const direction of ['outbound', 'inbound']) {
    const hash = createHash('sha256');
    let bytes = 0;
    for (const chunk of chunks) {
      hash.update(chunk);
      bytes += chunk.byteLength;
      f.write(direction, chunk);
      sequence++;
    }
    copies[direction] = { bytes, sha256: hash.digest('hex') };
  }
  await f.wait('bytes', (value) => value.sequence === sequence);
  const state = await f.snapshot();
  assert.deepEqual(state.channels, copies);
  assert.deepEqual(state.frames, [], 'raw fixture does not interpret binary as native JSON');
  await f.retired();
  assert.equal(
    binary.buffer.byteLength,
    131_079,
    'standalone input backing store survives structured clone',
  );
  f.integrity();
});

for (const limit of ['bytes', 'messages']) {
  test(`unacknowledged ${limit} credits close only observer at the exact limit`, async (t) => {
    const f = forwardingFixture(t, { ack: 'none' });
    await f.blocked();
    f.write('outbound', '\n');
    f.write('inbound', '\n');
    await f.ready();
    const chunk = limit === 'bytes' ? Buffer.alloc(1024 * 1024, 0xa5) : Buffer.from([0x81]);
    const count = limit === 'bytes' ? maximumBytes / chunk.length : maximumMessages;
    // Both directions consume one shared credit pool, not a per-direction cap.
    for (let i = 0; i < count; i++) f.write(i % 2 ? 'inbound' : 'outbound', chunk);
    await f.wait('bytes', (value) => value.sequence === count);
    const atLimit = await f.snapshot();
    assert.equal(atLimit.sequence, count);
    assert.equal(Atomics.load(new Int32Array(f.shared), 0), 0, 'exact capacity remains available');
    assert.equal(
      atLimit.channels.outbound.bytes + atLimit.channels.inbound.bytes,
      limit === 'bytes' ? maximumBytes : maximumMessages,
    );
    f.write('outbound', Buffer.from([0x7f]));
    assert.equal(
      Atomics.load(new Int32Array(f.shared), 0),
      1,
      'first over-limit byte retires observer',
    );
    f.write('inbound', 'raw flow survives overflow\r\n');
    const closed = await f.retired();
    assert.equal(closed.sequence, count, 'overflow byte must not reach observer');
    assert.deepEqual(closed.channels, atLimit.channels);
    f.write('outbound', 'raw flow survives retirement\n');
    f.integrity();
  });
}

test('valid ordered ACKs replenish credits beyond one queue window', async (t) => {
  const f = forwardingFixture(t);
  await f.blocked();
  await f.ready();
  const chunk = Buffer.alloc(1024 * 1024, 0x37);
  const rounds = maximumBytes / chunk.length + 2;
  for (let i = 1; i <= rounds; i++) {
    f.write(i % 2 ? 'outbound' : 'inbound', chunk);
    await f.wait('bytes', (value) => value.sequence === i);
    // Snapshot roundtrip follows ack() in the Worker handler, ensuring the
    // preceding ACK was posted before the next test-side write.
    await f.snapshot();
  }
  const state = await f.snapshot();
  assert.equal(state.sequence, rounds);
  assert.equal(state.channels.outbound.bytes + state.channels.inbound.bytes, rounds * chunk.length);
  assert.equal(Atomics.load(new Int32Array(f.shared), 0), 0);
  await f.retired();
});

for (const ack of [
  'gap',
  'zero-sequence',
  'string-sequence',
  'unsafe-sequence',
  'duplicate',
  'wrong-size',
  'zero-size',
  'fractional-size',
  'string-size',
  'extra',
  'missing-size',
]) {
  test(`malformed ACK (${ack}) retires observer while original pipes keep forwarding`, async (t) => {
    const f = forwardingFixture(t, { ack });
    await f.blocked();
    await f.ready();
    f.write('outbound', Buffer.from([0x00, 0xff, 0xc3]));
    const closed = await f.wait('closed');
    assert.equal(closed.sequence, 1);
    f.write('outbound', 'after rejection\n');
    f.write('inbound', Buffer.from([0xff, 0x00, 13, 10]));
    await f.retired();
    assert.equal(f.received.history.filter((value) => value.kind === 'bytes').length, 1);
    f.integrity();
  });
}

test('an unsolicited ACK with no pending sequence is rejected without stopping raw flow', async (t) => {
  const f = forwardingFixture(t, { ack: 'none' });
  await f.blocked();
  await f.ready();
  f.emit({ kind: 'ack', sequence: 1, size: 1 });
  const closed = await f.wait('closed');
  assert.equal(closed.sequence, 0);
  f.write('outbound', 'after unsolicited ACK\n');
  f.write('inbound', 'still original\n');
  await f.retired();
});

test('explicit close wakes and reaps an actually blocked, never-ready Worker', async (t) => {
  const f = forwardingFixture(t);
  await f.blocked();
  const closed = await f.retired();
  assert.equal(Atomics.load(f.gate, 2), 0);
  assert.equal(closed.reason, 'closed-before-ready');
  assert.equal(closed.sequence, 0);
  f.write('inbound', 'no wait for Native\n');
  f.write('outbound', 'original continues\n');
  f.integrity();
});

test('normal Worker self-exit is observed and reaped without replacing pipes or replaying bytes', async (t) => {
  const f = forwardingFixture(t);
  await f.blocked();
  await f.ready();
  f.write('inbound', 'one observed response\n');
  await f.wait('bytes', (value) => value.sequence === 1);
  f.exit();
  const closed = await f.wait('closed');
  assert.equal(closed.reason, 'fixture-exit');
  await f.retired();
  f.write('outbound', 'after Worker exit\n');
  f.write('inbound', 'still forwarded\n');
  f.integrity();
});

function framingFixture(boundary = { outbound: true, inbound: true }, accept) {
  const lines = [];
  const frames = new DesktopNativeSampleFrames((direction, line) => {
    lines.push([direction, line]);
    accept?.(direction, line);
  });
  frames.begin(boundary);
  return { frames, lines };
}
function assertUnusable(frames, lines) {
  const before = lines.slice();
  for (const direction of ['outbound', 'inbound'])
    assert.throws(
      () => frames.push(direction, Buffer.from('\nprivate tail must not revive\n')),
      /unavailable/,
    );
  assert.throws(() => frames.begin({ outbound: true, inbound: true }));
  assert.deepEqual(lines, before, 'failure must emit no later line from either direction');
}

test('actual framer isolates directions, split UTF-8/CRLF, whitespace and empty lines', () => {
  const { frames, lines } = framingFixture();
  const out = Buffer.from('{"text":"界🙂"}\r\n\r\n \t\r\n{"next":1}\n');
  const incoming = Buffer.from('{"reply":"é好"}\r\n\n');
  const outSplit = out.indexOf(Buffer.from('界')) + 1;
  const inSplit = incoming.indexOf(Buffer.from('é')) + 1;
  // Non-zero byte offsets exercise views without copying an alternate framer.
  const backing = Buffer.concat([Buffer.from('unused'), out, Buffer.from('unused')]);
  frames.push('outbound', new Uint8Array(backing.buffer, backing.byteOffset + 6, outSplit));
  frames.push('inbound', incoming.subarray(0, inSplit));
  assert.deepEqual(lines, []);
  frames.push('outbound', out.subarray(outSplit, out.indexOf(10)));
  assert.deepEqual(lines, [], 'CR alone is not a line terminator');
  frames.push('inbound', incoming.subarray(inSplit));
  frames.push('outbound', out.subarray(out.indexOf(10)));
  assert.deepEqual(lines, [
    ['inbound', '{"reply":"é好"}'],
    ['outbound', '{"text":"界🙂"}'],
    ['outbound', '{"next":1}'],
  ]);
  frames.clear();
  assertUnusable(frames, lines);
});

for (const boundary of [
  { outbound: false, inbound: false },
  { outbound: true, inbound: false },
  { outbound: false, inbound: true },
]) {
  test(`actual framer discards pre-ready partials only where boundary is false (${JSON.stringify(boundary)})`, () => {
    const { frames, lines } = framingFixture(boundary);
    for (const direction of ['outbound', 'inbound']) {
      frames.push(
        direction,
        boundary[direction] ? Buffer.from('whole line') : Buffer.from([0xff, 0xc3, 13]),
      );
      assert.deepEqual(
        lines.filter(([value]) => value === direction),
        [],
      );
      frames.push(direction, Buffer.from('\n\nfresh ' + direction + '\r'));
      frames.push(direction, Buffer.from('\n'));
      assert.deepEqual(
        lines.filter(([value]) => value === direction),
        [
          ...(boundary[direction] ? [[direction, 'whole line']] : []),
          [direction, 'fresh ' + direction],
        ],
      );
    }
  });
}

test('many one-byte chunks merge into exact Unicode lines across directions', () => {
  const { frames, lines } = framingFixture();
  const outboundLine = '{"text":"' + '界🙂'.repeat(4096) + '"}';
  const inboundLine = '{"text":"' + 'é'.repeat(3000) + '"}';
  const out = Buffer.from(outboundLine + '\r\n'),
    incoming = Buffer.from(inboundLine + '\r\n');
  for (let i = 0; i < Math.max(out.length, incoming.length); i++) {
    if (i < out.length) frames.push('outbound', out.subarray(i, i + 1));
    if (i < incoming.length) frames.push('inbound', incoming.subarray(i, i + 1));
  }
  assert.deepEqual(lines, [
    ['inbound', inboundLine],
    ['outbound', outboundLine],
  ]);
});

test('32 MiB of combined tails is accepted exactly and released by real line delivery', () => {
  const delivered = [];
  const frames = new DesktopNativeSampleFrames((direction, line) =>
    delivered.push(lineSummary(direction, line)),
  );
  frames.begin({ outbound: true, inbound: true });
  const out = Buffer.alloc(maximumBytes / 2, 0x61),
    incoming = Buffer.alloc(maximumBytes / 2, 0x62);
  frames.push('outbound', out);
  frames.push('inbound', incoming);
  assert.deepEqual(delivered, []);
  frames.push('outbound', Buffer.from('\n'));
  frames.push('inbound', Buffer.from('\r\n'));
  assert.deepEqual(delivered, [
    { direction: 'outbound', bytes: out.length, sha256: digest(out) },
    { direction: 'inbound', bytes: incoming.length, sha256: digest(incoming) },
  ]);
  frames.push('outbound', Buffer.from('after capacity release\n'));
  assert.deepEqual(delivered[2], lineSummary('outbound', 'after capacity release'));
  assert.equal(delivered.length, 3);
  frames.clear();
});

for (const splitDirections of [false, true]) {
  test(`aggregate frame overflow closes both directions (${splitDirections ? 'shared directions' : 'merged chunks'})`, () => {
    const { frames, lines } = framingFixture();
    frames.push('outbound', Buffer.alloc(maximumBytes / 2, 0x61));
    frames.push(splitDirections ? 'inbound' : 'outbound', Buffer.alloc(maximumBytes / 2, 0x62));
    assert.throws(() => frames.push('inbound', Buffer.from('x')), /frame limit/);
    assert.deepEqual(lines, []);
    assertUnusable(frames, lines);
  });
}

for (const bad of [
  [0xff],
  [0x80],
  [0xc0, 0xaf],
  [0xe2, 0x82],
  [0xed, 0xa0, 0x80],
  [0xf4, 0x90, 0x80, 0x80],
]) {
  for (const direction of ['outbound', 'inbound']) {
    test(`malformed UTF-8 ${bad.map((value) => value.toString(16)).join('-')} in ${direction} permanently closes actual framer`, () => {
      const { frames, lines } = framingFixture();
      const other = direction === 'outbound' ? 'inbound' : 'outbound';
      frames.push(other, Buffer.from('private buffered tail'));
      for (const byte of bad) frames.push(direction, Buffer.from([byte]));
      assert.deepEqual(lines, []);
      assert.throws(
        () => frames.push(direction, Buffer.from('\nvalid but unreachable\n')),
        /encoded data|encoding|UTF-8/i,
      );
      assert.deepEqual(lines, []);
      assertUnusable(frames, lines);
    });
  }
}

test('accept callback failure clears both private tails and prevents later same-chunk delivery or reuse', () => {
  const failure = new Error('synthetic accept failure');
  const { frames, lines } = framingFixture(undefined, () => {
    throw failure;
  });
  frames.push('inbound', Buffer.from('other private tail'));
  frames.push('outbound', Buffer.from('first '));
  assert.throws(
    () => frames.push('outbound', Buffer.from('line\nsecond line\n')),
    (error) => error === failure,
  );
  assert.deepEqual(lines, [['outbound', 'first line']]);
  assertUnusable(frames, lines);
});

test('framing requires begin, rejects repeated begin, and clear cannot be reversed', () => {
  const lines = [];
  const frames = new DesktopNativeSampleFrames((...line) => lines.push(line));
  assert.throws(() => frames.push('outbound', Buffer.from('before begin\n')), /unavailable/);
  frames.begin({ outbound: true, inbound: true });
  assert.throws(() => frames.begin({ outbound: true, inbound: true }));
  frames.push('outbound', Buffer.from('only valid line\n'));
  assert.deepEqual(lines, [['outbound', 'only valid line']]);
  frames.clear();
  frames.clear();
  assertUnusable(frames, lines);
});
