import assert from 'node:assert/strict';
import { createServer } from 'node:net';
import { mkdtemp, chmod, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  DesktopFrameDecoder,
  desktopFrameLimits,
} from '../packages/codex-adapter/dist/desktop-framing.js';
import { DesktopIpc, desktopProfile } from '../packages/codex-adapter/dist/desktop-ipc.js';

const maximumBody = desktopFrameLimits.materializedBytes;
const reproductionBody = 7_800_000;
const reproductionChunk = 8192;
let activeCopies;
let concatDepth = 0;
const originals = {
  concat: Buffer.concat,
  copy: Buffer.prototype.copy,
  set: Uint8Array.prototype.set,
  alloc: Buffer.alloc,
  allocUnsafe: Buffer.allocUnsafe,
};

function counters() {
  return { copiedBytes: 0, concatBytes: 0, copyBytes: 0, setBytes: 0, allocatedBytes: 0 };
}

// Count the actual lengths returned by copying operations. The old algorithm
// below is accounting only; it never allocates its cumulative multi-GB buffer.
Buffer.concat = function (...args) {
  concatDepth++;
  let output;
  try {
    output = originals.concat.apply(this, args);
  } finally {
    concatDepth--;
  }
  if (activeCopies && !concatDepth) {
    activeCopies.concatBytes += output.length;
    activeCopies.copiedBytes += output.length;
  }
  return output;
};
Buffer.prototype.copy = function (...args) {
  const copied = originals.copy.apply(this, args);
  if (activeCopies && !concatDepth) {
    activeCopies.copyBytes += copied;
    activeCopies.copiedBytes += copied;
  }
  return copied;
};
Uint8Array.prototype.set = function (...args) {
  const result = originals.set.apply(this, args);
  if (activeCopies && !concatDepth && Buffer.isBuffer(this)) {
    const copied = args[0].length;
    activeCopies.setBytes += copied;
    activeCopies.copiedBytes += copied;
  }
  return result;
};
for (const name of ['alloc', 'allocUnsafe']) {
  Buffer[name] = function (...args) {
    const output = originals[name].apply(this, args);
    if (activeCopies) activeCopies.allocatedBytes += output.length;
    return output;
  };
}

function measure(action) {
  assert.equal(activeCopies, undefined, 'Copy measurement scopes must not overlap');
  const copies = counters();
  activeCopies = copies;
  try {
    action();
  } finally {
    activeCopies = undefined;
  }
  return copies;
}

function frame(body) {
  const bytes = Buffer.isBuffer(body) ? body : Buffer.from(JSON.stringify(body));
  const output = Buffer.allocUnsafe(bytes.length + 4);
  output.writeUInt32LE(bytes.length);
  bytes.copy(output, 4);
  return output;
}

function partition(bytes, lengths) {
  const pieces = [];
  let offset = 0;
  for (const length of lengths) {
    pieces.push(bytes.subarray(offset, Math.min(bytes.length, offset + length)));
    offset += length;
    if (offset >= bytes.length) return pieces;
  }
  if (offset < bytes.length) pieces.push(bytes.subarray(offset));
  return pieces;
}

function fixedPieces(bytes, size) {
  const pieces = [];
  for (let offset = 0; offset < bytes.length; offset += size)
    pieces.push(bytes.subarray(offset, offset + size));
  return pieces;
}

function assertDecoded(pieces, expected) {
  const received = [];
  const decoder = new DesktopFrameDecoder((body) => received.push(body));
  const copies = measure(() => {
    decoder.push(Buffer.alloc(0));
    for (const piece of pieces) decoder.push(piece);
    decoder.push(Buffer.alloc(0));
  });
  assert.equal(received.length, expected.length, 'Each frame must be delivered exactly once');
  for (let index = 0; index < expected.length; index++)
    assert.ok(
      received[index].equals(expected[index]),
      'Decoded bytes must preserve order and UTF-8',
    );
  assert.ok(
    copies.copiedBytes <= expected.reduce((total, body) => total + body.length + 4, 0),
    'Fragmented bodies may be copied once; fragmented headers may copy at most four bytes',
  );
  assert.equal(copies.concatBytes, 0, 'Receive framing must not concatenate accumulated prefixes');
  return copies;
}

function decoderBoundaries() {
  const bodies = [Buffer.from('A汉🙂é文Z'), Buffer.from('second frame'), Buffer.from('{}')];
  const combined = Buffer.concat(bodies.map(frame));
  let partitions = 0;
  for (let first = 0; first <= combined.length; first++) {
    for (let second = first; second <= combined.length; second++) {
      assertDecoded(
        [combined.subarray(0, first), combined.subarray(first, second), combined.subarray(second)],
        bodies,
      );
      partitions++;
    }
  }
  for (const size of [1, 2, 3, 4, 5, 7, 11, 16, 8192])
    assertDecoded(fixedPieces(combined, size), bodies);
  assert.equal(assertDecoded([combined], bodies).copiedBytes, 0, 'Coalesced frames need no copies');

  for (const size of [0, maximumBody + 1, 0xffffffff]) {
    const header = Buffer.alloc(4);
    header.writeUInt32LE(size);
    for (const split of [1, 2, 3, 4]) {
      let accepted = 0;
      const decoder = new DesktopFrameDecoder(() => accepted++);
      const copies = measure(() => {
        if (split < 4) decoder.push(header.subarray(0, split));
        assert.throws(
          () => decoder.push(header.subarray(split < 4 ? split : 0)),
          /^Error: Invalid IPC frame$/,
        );
      });
      assert.equal(accepted, 0);
      assert.equal(copies.allocatedBytes, 0, 'Invalid lengths must fail before body allocation');
      decoder.reset();
      decoder.push(frame(Buffer.from('recovered')));
      assert.equal(accepted, 1, 'Reset must allow a fresh frame after an invalid header');
    }
  }

  // Exact upper bound is valid and contiguous payloads retain the input storage.
  const boundary = Buffer.allocUnsafe(maximumBody + 4);
  boundary.writeUInt32LE(maximumBody);
  boundary.fill(0x61, 4);
  let boundaryReceived = false;
  const boundaryDecoder = new DesktopFrameDecoder((body) => {
    boundaryReceived = true;
    assert.equal(body.length, maximumBody);
    assert.equal(body.buffer, boundary.buffer);
    assert.equal(body.byteOffset, boundary.byteOffset + 4);
  });
  const boundaryCopies = measure(() => boundaryDecoder.push(boundary));
  assert.ok(boundaryReceived);
  assert.equal(boundaryCopies.copiedBytes, 0);
  assert.equal(boundaryCopies.allocatedBytes, 0);

  // Reset drops both a partial header and allocated partial body.
  for (const length of [1, 2, 3, 4, 5, 9]) {
    const received = [];
    const decoder = new DesktopFrameDecoder((body) => received.push(body.toString('utf8')));
    decoder.push(frame(Buffer.from('abandoned body')).subarray(0, length));
    decoder.reset();
    decoder.push(frame(Buffer.from('fresh🙂')));
    assert.deepEqual(received, ['fresh🙂']);
  }

  // Delivery must clear state before user code can reenter or throw.
  for (const fragmented of [false, true]) {
    const delivered = [];
    let decoder;
    decoder = new DesktopFrameDecoder((body) => {
      delivered.push(body.toString());
      if (delivered.length === 1) decoder.push(frame(Buffer.from('nested')));
    });
    const bytes = Buffer.concat([frame(Buffer.from('outer')), frame(Buffer.from('tail'))]);
    if (fragmented) decoder.push(bytes.subarray(0, 5));
    decoder.push(bytes.subarray(fragmented ? 5 : 0));
    assert.deepEqual(delivered, ['outer', 'nested', 'tail']);
    let shouldThrow = true;
    const throwing = new DesktopFrameDecoder(() => {
      if (shouldThrow) throw new Error('accept fixture failure');
    });
    const input = frame(Buffer.from('callback'));
    if (fragmented) throwing.push(input.subarray(0, 5));
    assert.throws(
      () => throwing.push(input.subarray(fragmented ? 5 : 0)),
      /accept fixture failure/,
    );
    shouldThrow = false;
    throwing.push(frame(Buffer.from('next')));
  }
  return {
    partitions,
    maximumBodyBytes: maximumBody,
    maximumBodyCopies: boundaryCopies.copiedBytes,
  };
}

function linearScaling() {
  return [64 * 1024, 256 * 1024, 1024 * 1024, 4 * 1024 * 1024, reproductionBody].map((size) => {
    const body = Buffer.alloc(size, 0x61);
    const bytes = frame(body);
    let accepted = 0;
    const decoder = new DesktopFrameDecoder((received) => {
      accepted++;
      assert.ok(received.equals(body), 'Large fragmented body must match exactly');
    });
    const pieces = fixedPieces(bytes, reproductionChunk);
    const copies = measure(() => pieces.forEach((piece) => decoder.push(piece)));
    assert.equal(accepted, 1);
    assert.equal(
      copies.copiedBytes,
      size,
      'Every fragmented body byte must be copied exactly once',
    );
    assert.equal(
      copies.allocatedBytes,
      size,
      'Fragmented frame must allocate its body exactly once',
    );
    assert.equal(copies.concatBytes, 0);
    let prefixBytes = 0;
    let oldCopiedBytes = 0;
    for (const piece of pieces) {
      prefixBytes += piece.length;
      oldCopiedBytes += prefixBytes;
    }
    let oldMeasuredBytes;
    if (size === 64 * 1024) {
      // Validate accounting against real old-algorithm Buffer.concat calls on
      // a small sample. Never run that copying algorithm on the 7.8 MB case.
      let accumulated = Buffer.alloc(0);
      const oldCopies = measure(() => {
        for (const piece of pieces) accumulated = Buffer.concat([accumulated, piece]);
      });
      assert.ok(accumulated.equals(bytes));
      assert.equal(oldCopies.concatBytes, oldCopiedBytes);
      assert.equal(oldCopies.copiedBytes, oldCopiedBytes);
      oldMeasuredBytes = oldCopies.copiedBytes;
    }
    return {
      bodyBytes: size,
      chunks: pieces.length,
      copiedBytes: copies.copiedBytes,
      copyAmplification: copies.copiedBytes / size,
      oldAlgorithmAccountedBytes: oldCopiedBytes,
      ...(oldMeasuredBytes === undefined ? {} : { oldAlgorithmMeasuredBytes: oldMeasuredBytes }),
      oldAlgorithmAmplification: Number((oldCopiedBytes / size).toFixed(3)),
    };
  });
}

async function oversizedBoundaries() {
  const declared = desktopFrameLimits.materializedBytes + 1;
  const header = Buffer.alloc(4);
  header.writeUInt32LE(declared);
  const filler = Buffer.alloc(8192, 0x61);
  const normal = frame(Buffer.from('normal after skipped frame🙂'));
  const received = [],
    skipped = [];
  const decoder = new DesktopFrameDecoder((body) => received.push(body.toString()), {
    onOversized: (size) => skipped.push(size),
  });
  const remainder = declared % filler.length;
  const tail = Buffer.concat([filler.subarray(0, remainder), normal]);
  const copies = measure(() => {
    for (const piece of fixedPieces(header, 1)) decoder.push(piece);
    for (let offset = 0; offset < declared - remainder; offset += filler.length)
      decoder.push(filler);
    decoder.push(tail);
  });
  assert.deepEqual(skipped, [declared]);
  assert.deepEqual(received, ['normal after skipped frame🙂']);
  assert.equal(copies.allocatedBytes, 0, 'Skipped body must never be allocated/materialized');
  assert.equal(copies.copiedBytes, 4, 'Only the fragmented header is copied');
  assert.equal(decoder.body, undefined);
  assert.equal(decoder.drainTimer, undefined, 'Complete discard must clear its deadline');

  for (const size of [0, desktopFrameLimits.drainedBytes + 1, 0xffffffff]) {
    const bytes = Buffer.alloc(4);
    bytes.writeUInt32LE(size);
    const invalid = new DesktopFrameDecoder(() => assert.fail('Invalid body delivered'), {
      onOversized: () => assert.fail('Invalid length admitted for discard'),
    });
    assert.throws(() => invalid.push(bytes), /Invalid IPC frame/);
  }
  for (const timeout of [0, -1, Infinity, desktopFrameLimits.drainTimeoutMs + 1])
    assert.throws(
      () => new DesktopFrameDecoder(() => {}, { drainTimeoutMs: timeout }),
      /Invalid IPC frame drain timeout/,
    );

  const atLimit = new DesktopFrameDecoder(() => {}, { onOversized: () => {} });
  const largest = Buffer.alloc(4);
  largest.writeUInt32LE(desktopFrameLimits.drainedBytes);
  atLimit.push(largest);
  assert.equal(atLimit.body, undefined);
  atLimit.reset();
  assert.equal(atLimit.drainTimer, undefined, 'Reset/close must cancel partial discard');

  let timeoutError,
    progress = 0;
  const expired = deferred();
  const incomplete = new DesktopFrameDecoder(() => assert.fail('Unfinished frame delivered'), {
    drainTimeoutMs: 30,
    onOversized: () => {},
    onError: (error) => {
      timeoutError = error;
      expired.resolve();
    },
  });
  incomplete.push(header);
  const trickle = setInterval(() => {
    if (!timeoutError) {
      incomplete.push(filler);
      progress++;
    }
  }, 5);
  try {
    await bounded(expired.promise, 'absolute oversized-frame deadline');
  } finally {
    clearInterval(trickle);
  }
  assert(progress > 0, 'Deadline must expire even while payload bytes keep arriving');
  assert.match(timeoutError.message, /drain timed out/);
  assert.throws(() => incomplete.push(normal), /drain timed out/);
  incomplete.reset();
  return {
    skippedDeclaredBytes: declared,
    skippedBodyAllocatedBytes: copies.allocatedBytes,
    materializedLimitBytes: desktopFrameLimits.materializedBytes,
    discardLimitBytes: desktopFrameLimits.drainedBytes,
    absoluteDeadline: 'passed',
    followingNormalFrame: 'passed',
  };
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

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

function socketCapture(socket) {
  let copies = counters();
  let bytes = 0;
  let chunks = 0;
  const waiting = new Set();
  socket.prependListener('data', () => {
    assert.equal(activeCopies, undefined);
    activeCopies = copies;
  });
  socket.on('data', (chunk) => {
    activeCopies = undefined;
    bytes += chunk.length;
    chunks++;
    for (const waiter of waiting) {
      if (bytes >= waiter.bytes) {
        waiting.delete(waiter);
        waiter.resolve();
      }
    }
  });
  return {
    reset() {
      assert.equal(waiting.size, 0);
      copies = counters();
      bytes = 0;
      chunks = 0;
    },
    totals: () => ({ ...copies, receivedBytes: bytes, chunks }),
    received: (target) => {
      if (bytes >= target) return Promise.resolve();
      const ready = deferred();
      waiting.add({ bytes: target, resolve: ready.resolve });
      return ready.promise;
    },
  };
}

async function socketIntegration() {
  if (process.platform === 'win32')
    return {
      status: 'skipped',
      reason: 'Unix-socket fixture; Windows desktop bridge is verified separately',
    };
  const directory = await mkdtemp(join(tmpdir(), 'codex-framing-'));
  await chmod(directory, 0o700);
  const path = join(directory, 'desktop.sock');
  const thread = '11111111-1111-4111-8111-111111111111';
  const connections = new Set();
  const failures = [];
  let peer;
  let revision = 0;
  let holdDiscovery = false;
  function snapshot(text) {
    return {
      type: 'broadcast',
      method: 'thread-stream-state-changed',
      version: desktopProfile.streamVersion,
      sourceClientId: 'fixture-owner',
      params: {
        hostId: 'local',
        conversationId: thread,
        change: {
          type: 'snapshot',
          revision: ++revision,
          conversationState: { id: thread, hostId: 'local', text },
        },
      },
    };
  }
  const server = createServer((socket) => {
    peer = socket;
    connections.add(socket);
    socket.on('close', () => connections.delete(socket));
    socket.on('error', () => {});
    // Independent, small-request-only fixture parser. Its copies are outside
    // the production receiver's synchronous data-event measurement scope.
    let buffered = Buffer.alloc(0);
    socket.on('data', (chunk) => {
      try {
        buffered = Buffer.concat([buffered, chunk]);
        while (buffered.length >= 4 && buffered.length >= buffered.readUInt32LE(0) + 4) {
          const size = buffered.readUInt32LE(0);
          const request = JSON.parse(buffered.subarray(4, size + 4).toString('utf8'));
          buffered = buffered.subarray(size + 4);
          if (request.method === 'initialize')
            socket.write(
              frame({
                type: 'response',
                requestId: request.requestId,
                resultType: 'success',
                result: { clientId: 'fixture-phone' },
              }),
            );
          else if (request.method === 'thread-owner-discovery' && !holdDiscovery)
            socket.write(
              frame({
                type: 'response',
                requestId: request.requestId,
                resultType: 'success',
                handledByClientId: 'fixture-owner',
                result: { supportsUntrustedAppInput: true },
              }),
            );
          else if (request.method === 'thread-stream-following-changed')
            socket.write(frame(snapshot('initial')));
        }
      } catch (error) {
        failures.push(error);
        socket.destroy();
      }
    });
  });
  const ipc = new DesktopIpc(path);
  let disconnects = 0;
  let disconnected;
  ipc.onDisconnect = () => {
    disconnects++;
    disconnected?.resolve();
  };
  async function connect() {
    const work = ipc.connect();
    // TypeScript private fields remain ordinary properties in this runtime;
    // attach observers to the real net.Socket, without replacing its handler.
    const capture = socketCapture(ipc.socket);
    await bounded(work, 'DesktopIpc initialize');
    assert.equal(ipc.ready, true);
    assert.ok(await bounded(ipc.follow(thread, 1000, true), 'owner snapshot'));
    assert.equal(ipc.hasOwner(thread), true);
    capture.reset();
    return capture;
  }
  async function send(pieces, capture) {
    let total = capture.totals().receivedBytes;
    for (const piece of pieces) {
      if (!piece.length) continue;
      total += piece.length;
      peer.write(piece);
      await bounded(capture.received(total), 'socket fragment delivery');
    }
  }
  async function expectDisconnect(action) {
    disconnected = deferred();
    action();
    await bounded(disconnected.promise, 'DesktopIpc disconnect');
    disconnected = undefined;
    assert.equal(ipc.ready, false);
    assert.equal(ipc.hasOwner(thread), false);
    assert.equal(ipc.state(thread), undefined);
  }
  try {
    await bounded(
      new Promise((resolve, reject) => {
        server.once('error', reject);
        server.listen(path, resolve);
      }),
      'fixture listen',
    );
    await chmod(path, 0o600);
    let capture = await connect();
    const unicode = '接收缓存🙂跨字节分片é完毕';
    const utf8Frame = frame(snapshot(unicode));
    const scalarOffset = utf8Frame.indexOf(Buffer.from('🙂'));
    assert.ok(scalarOffset > 4);
    await send(partition(utf8Frame, [1, 1, 1, 1, scalarOffset - 4 + 1, 1, 1, 1]), capture);
    assert.ok(ipc.state(thread)?.text === unicode, 'Real socket must preserve split UTF-8');
    const unicodeCopies = capture.totals();
    assert.ok(
      unicodeCopies.copiedBytes <= utf8Frame.length,
      'UTF-8 frame copy count must remain linear',
    );

    capture.reset();
    const value = snapshot('前🙂后');
    value.params.change.conversationState.text += 'x'.repeat(
      reproductionBody - Buffer.byteLength(JSON.stringify(value)),
    );
    const large = frame(value);
    assert.equal(large.length, reproductionBody + 4);
    const largePieces = fixedPieces(large, reproductionChunk);
    await send(largePieces, capture);
    assert.ok(
      ipc.state(thread)?.text === value.params.change.conversationState.text,
      'Large real-socket snapshot must preserve its complete payload',
    );
    const largeCopies = capture.totals();
    assert.equal(largeCopies.receivedBytes, large.length);
    assert.equal(
      largeCopies.chunks,
      largePieces.length,
      'Fixture must exercise each actual receive fragment',
    );
    assert.equal(largeCopies.concatBytes, 0);
    assert.ok(
      largeCopies.copiedBytes <= reproductionBody,
      'Actual DesktopIpc receive copies must be at most one body',
    );

    capture.reset();
    await send(
      [Buffer.concat([frame(snapshot('coalesced one')), frame(snapshot('coalesced two🙂'))])],
      capture,
    );
    assert.ok(
      ipc.state(thread)?.text === 'coalesced two🙂',
      'Coalesced snapshots must remain ordered',
    );
    assert.equal(
      capture.totals().copiedBytes,
      0,
      'Complete coalesced socket frames need no receive copies',
    );

    // A replacement connection can become current before an older socket
    // closes. Late bytes and close from that older socket must stay isolated.
    capture.reset();
    const stale = frame(snapshot('late bytes from replaced socket'));
    await send([stale.subarray(0, 11)], capture);
    const oldPeer = peer;
    const oldSocket = ipc.socket;
    const oldCapture = capture;
    capture = await connect();
    assert.notEqual(ipc.socket, oldSocket);
    oldCapture.reset();
    oldPeer.write(stale.subarray(11));
    await bounded(oldCapture.received(stale.length - 11), 'replaced socket late data');
    assert.ok(
      ipc.state(thread)?.text === 'initial',
      'Replaced socket must not overwrite the current snapshot',
    );
    assert.equal(
      oldCapture.totals().copiedBytes,
      0,
      'Replaced socket data must not enter a decoder',
    );
    const previousDisconnects = disconnects;
    const oldClosed = new Promise((resolve) => oldSocket.once('close', resolve));
    oldPeer.end();
    await bounded(oldClosed, 'replaced socket close');
    assert.equal(
      disconnects,
      previousDisconnects,
      'Old close must not disconnect the current client',
    );
    assert.equal(ipc.ready, true);
    await send([frame(snapshot('current socket still receives'))], capture);
    assert.ok(ipc.state(thread)?.text === 'current socket still receives');

    // Disconnect halfway through either header or body; the same DesktopIpc
    // instance reconnects with clean frame state and a fresh owner snapshot.
    for (const length of [2, 11]) {
      capture.reset();
      await send([frame(snapshot('abandoned')).subarray(0, length)], capture);
      await expectDisconnect(() => peer.end());
      capture = await connect();
      assert.ok(
        ipc.state(thread)?.text === 'initial',
        'Reconnect must discard the abandoned prefix',
      );
    }
    for (const size of [0, desktopFrameLimits.drainedBytes + 1, 0xffffffff]) {
      const invalid = Buffer.alloc(4);
      invalid.writeUInt32LE(size);
      await send([invalid.subarray(0, 3)], capture);
      await expectDisconnect(() => peer.write(invalid.subarray(3)));
      capture = await connect();
    }
    await expectDisconnect(() => peer.write(frame(Buffer.from('{invalid json'))));
    capture = await connect();

    holdDiscovery = true;
    const pending = ipc.follow('22222222-2222-4222-8222-222222222222', 1000);
    const rejected = assert.rejects(pending, /Desktop disconnected; outcome uncertain/);
    await expectDisconnect(() => peer.end());
    await rejected;
    holdDiscovery = false;
    await connect();
    assert.equal(failures.length, 0, 'Fixture server must have no parse failures');
    return {
      status: 'passed',
      transport: 'real private net.Socket fixture',
      utf8ReceiveChunks: unicodeCopies.chunks,
      bodyBytes: reproductionBody,
      receivedChunks: largeCopies.chunks,
      receiveCopiedBytes: largeCopies.copiedBytes,
      receiveCopyAmplification: largeCopies.copiedBytes / reproductionBody,
      reconnectsVerified: disconnects,
      overlappingSocketIsolation: 'passed',
    };
  } finally {
    ipc.close();
    for (const socket of connections) socket.destroy();
    await new Promise((resolve) => server.close(resolve));
    await rm(directory, { recursive: true, force: true });
  }
}

try {
  const boundaries = decoderBoundaries();
  const oversized = await oversizedBoundaries();
  const scales = linearScaling();
  const reproduction = scales.at(-1);
  assert.ok(
    reproduction.oldAlgorithmAmplification > 400,
    'The previous growing-prefix algorithm must reproduce >400x copying',
  );
  const integration = await socketIntegration();
  console.log(
    JSON.stringify({
      test: 'desktop-framing-smoke',
      status: 'passed',
      evidence:
        'Synthetic framing and real net.Socket fixture; not an Electron/iPhone acceptance result',
      boundaries,
      oversized,
      scales,
      integration,
    }),
  );
} finally {
  Buffer.concat = originals.concat;
  Buffer.prototype.copy = originals.copy;
  Uint8Array.prototype.set = originals.set;
  Buffer.alloc = originals.alloc;
  Buffer.allocUnsafe = originals.allocUnsafe;
}
