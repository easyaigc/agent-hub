import assert from 'node:assert/strict';
import { mkdtemp, writeFile, readFile, readdir, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import test from 'node:test';
import https from 'node:https';
import { EventEmitter } from 'node:events';
import { Readable } from 'node:stream';
import { gzipSync } from 'node:zlib';
import { createHash, generateKeyPairSync, randomBytes, sign, X509Certificate } from 'node:crypto';
import { main, CONTEXT_LIMITS } from './contact-host.mjs';

const hostId = '00000000-0000-4000-8000-000000000001';
const threadId = '00000000-0000-4000-8000-000000000002';
async function fixture(t, defaultTransport = false) {
  const directory = await mkdtemp(join(tmpdir(), 'agent-hub-contact-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  await writeFile(
    join(directory, 'hosts.json'),
    JSON.stringify({
      schemaVersion: 1,
      hosts: [
        {
          hostId,
          name: 'Fixture',
          endpoint: 'https://fixture.invalid',
          clientKey: 'A'.repeat(48),
          coordinationThreadId: threadId,
        },
      ],
    }),
    { mode: 0o600 },
  );
  const taskFile = join(directory, 'task.txt');
  await writeFile(taskFile, '已授权的具名协调任务 task-fixture。', { mode: 0o600 });
  const calls = [],
    outputs = [],
    receipts = new Map(),
    creations = new Map();
  const catalog = {
    hostId,
    connectionMode: 'desktop-runtime',
    capabilities: [
      'desktop:open',
      'desktop:creations',
      'desktop:creations:projectless',
      'reply',
      'queue',
      'steer',
      'queue:execute',
    ],
    projects: [],
    threads: [{ id: threadId, hostId, title: '协调', projectId: null }],
  };
  const runtime = { canControl: true, state: 'idle', turnId: null, receipts: [] };
  let failNextReply = false;
  const transport = async (path, body, authenticated = true) => {
    calls.push({ path, body });
    if (path === '/healthz')
      return { http: authenticated ? 200 : 401, value: { service: 'host-agent', status: 'ok' } };
    if (path === '/v1/workspace') return { http: 200, value: catalog };
    if (path.startsWith('/v1/history?'))
      return {
        http: 200,
        value: { hostId, threadId, ready: true, runtime },
      };
    if (path === '/v1/replies') {
      receipts.set(body.clientMessageId, { ...body, state: 'submitted' });
      if (failNextReply) {
        failNextReply = false;
        throw Error('Simulated response loss after acceptance');
      }
      return { http: 200, value: receipts.get(body.clientMessageId) };
    }
    if (path === '/v1/queue/execute') {
      const item = runtime.receipts.find((item) => item.clientMessageId === body.clientMessageId);
      receipts.set(body.clientMessageId, {
        ...item,
        state: 'submitted',
        turnId: runtime.turnId ?? 'fixture-new-turn',
        queuePresent: false,
        canExecuteNow: false,
      });
      return { http: 200, value: { ok: true } };
    }
    const id = new URL(path, 'https://fixture.invalid').searchParams.get('id');
    if (path.startsWith('/v1/receipts?'))
      return { http: receipts.has(id) ? 200 : 404, value: receipts.get(id) };
    if (path === '/v1/desktop/creations') {
      const value = { ...body, phase: 'prepared', threadId: null };
      creations.set(body.clientOperationId, value);
      return { http: 200, value };
    }
    if (path === '/v1/desktop/creations/submit') {
      const value = creations.get(body.clientOperationId);
      value.phase = 'confirmed';
      value.threadId = threadId;
      return { http: 200, value };
    }
    if (path.startsWith('/v1/desktop/creation-receipts?'))
      return { http: creations.has(id) ? 200 : 404, value: creations.get(id) };
    throw Error('Unexpected route ' + path);
  };
  const run = (command, op, extra = []) =>
    main([command, '--host-id', hostId, ...(op ? ['--operation-id', op] : []), ...extra], {
      directory,
      ...(defaultTransport ? {} : { transport }),
      output: (value) => outputs.push(JSON.parse(value)),
    });
  return {
    directory,
    taskFile,
    calls,
    outputs,
    catalog,
    runtime,
    receipts,
    creations,
    transport,
    run,
    loseNextReply: () => {
      failNextReply = true;
    },
  };
}

test('fresh catalog excludes archived and stale registered targets before any write', async (t) => {
  const f = await fixture(t);
  f.catalog.threads[0].archived = true;
  await assert.rejects(f.run('send', 'archived-test-001', ['--text-file', f.taskFile]), {
    code: 'SELECT_CURRENT_UNARCHIVED_THREAD_OR_CREATE',
  });
  f.catalog.threads = [];
  await assert.rejects(f.run('open', 'stale-open-test-001'), {
    code: 'SELECT_CURRENT_UNARCHIVED_THREAD_OR_CREATE',
  });
  assert.equal(f.calls.filter((c) => c.body).length, 0);
});

test('send selects auto, explicit next-turn and explicit queue without silently changing modes', async (t) => {
  const f = await fixture(t);
  await f.run('send', 'mode-auto-idle', ['--text-file', f.taskFile]);
  assert.equal(f.receipts.get('mode-auto-idle').mode, 'next-turn');
  await f.run('send', 'mode-explicit-queue', ['--text-file', f.taskFile, '--mode', 'queue']);
  assert.equal(f.receipts.get('mode-explicit-queue').mode, 'queue');
  f.runtime.state = 'running';
  f.runtime.turnId = 'turn-current';
  await f.run('send', 'mode-auto-running', ['--text-file', f.taskFile]);
  assert.equal(f.receipts.get('mode-auto-running').mode, 'queue');
  await assert.rejects(
    f.run('send', 'mode-reject-next', ['--text-file', f.taskFile, '--mode', 'next-turn']),
    { code: 'IDLE_THREAD_REQUIRED' },
  );
  f.runtime.state = 'idle';
  f.runtime.turnId = null;
  await f.run('send', 'mode-explicit-next', ['--text-file', f.taskFile, '--mode', 'next-turn']);
  assert.equal(f.receipts.get('mode-explicit-next').mode, 'next-turn');
  f.catalog.capabilities = f.catalog.capabilities.filter((capability) => capability !== 'queue');
  await assert.rejects(
    f.run('send', 'mode-missing-queue', ['--text-file', f.taskFile, '--mode', 'queue']),
    { code: 'NATIVE_QUEUE_UNAVAILABLE' },
  );
  assert.equal(f.calls.filter((call) => call.body).length, 4);
});

test('steer binds the observed Turn, omits generation settings and keeps retries query-only', async (t) => {
  const f = await fixture(t);
  f.runtime.state = 'running';
  f.runtime.turnId = 'turn-current';
  const args = ['--text-file', f.taskFile, '--mode', 'steer', '--expected-turn-id', 'turn-current'];
  await f.run('send', 'steer-current-001', args);
  assert.deepEqual(f.receipts.get('steer-current-001'), {
    hostId,
    threadId,
    clientMessageId: 'steer-current-001',
    text: await readFile(f.taskFile, 'utf8'),
    attachmentIds: [],
    mode: 'steer',
    expectedTurnId: 'turn-current',
    state: 'submitted',
  });
  f.runtime.turnId = 'turn-later';
  await f.run('send', 'steer-current-001', args);
  assert.equal(f.outputs.at(-1).queriedExistingOperation, true);
  await assert.rejects(f.run('send', 'steer-current-001', [...args.slice(0, -1), 'turn-later']), {
    code: 'OPERATION_ID_REUSED_WITH_DIFFERENT_INPUT',
  });
  await assert.rejects(
    f.run('send', 'steer-current-001', ['--text-file', f.taskFile, '--mode', 'queue']),
    { code: 'OPERATION_ID_REUSED_WITH_DIFFERENT_INPUT' },
  );
  assert.equal(f.calls.filter((call) => call.body).length, 1);
});

test('steer rejects stale or missing Turns, settings changes, approvals and unavailable capability before POST', async (t) => {
  const f = await fixture(t);
  const base = ['--text-file', f.taskFile, '--mode', 'steer'];
  await assert.rejects(f.run('send', 'steer-no-turn-001', base), {
    code: 'EXPECTED_TURN_ID_REQUIRED',
  });
  const args = [...base, '--expected-turn-id', 'turn-current'];
  await assert.rejects(f.run('send', 'steer-settings-001', [...args, '--effort', 'xhigh']), {
    code: 'STEER_CANNOT_CHANGE_GENERATION_SETTINGS',
  });
  for (const state of ['idle', 'waiting-for-approval', 'running']) {
    f.runtime.state = state;
    f.runtime.turnId = 'turn-other';
    await assert.rejects(f.run('send', 'steer-stale-' + state, args), {
      code: 'CURRENT_TURN_MISMATCH',
    });
  }
  f.runtime.state = 'running';
  f.runtime.turnId = 'turn-current';
  f.catalog.capabilities = f.catalog.capabilities.filter((capability) => capability !== 'steer');
  await assert.rejects(f.run('send', 'steer-no-cap-001', args), { code: 'STEER_UNAVAILABLE' });
  assert.equal(f.calls.filter((call) => call.body).length, 0);
});

const queuedItem = (id = 'queued-original-001') => ({
  hostId,
  threadId,
  clientMessageId: id,
  text: 'Original queued input',
  state: 'queued',
  mode: 'queue',
  queueSource: 'native',
  queuePresent: true,
  canExecuteNow: true,
});

test('queue listing preserves native order and legacy visibility with bounded metadata and no writes', async (t) => {
  const f = await fixture(t);
  f.runtime.receipts = [
    queuedItem('queued-first'),
    queuedItem('queued-second'),
    {
      ...queuedItem('legacy-queue'),
      queueSource: 'host',
      queuePresent: false,
      canExecuteNow: false,
      queueDisposition: 'legacy-review',
    },
    { ...queuedItem('completed-recent'), state: 'completed', queuePresent: false },
  ];
  await f.run('queue', undefined, ['--limit', '2']);
  assert.deepEqual(
    f.outputs.at(-1).items.map((item) => item.clientMessageId),
    ['queued-first', 'queued-second'],
  );
  assert.equal(f.outputs.at(-1).nextOffset, 2);
  assert.equal(f.outputs.at(-1).total, 3);
  assert(!JSON.stringify(f.outputs).includes('Original queued input'));
  await f.run('queue', undefined, ['--offset', '2']);
  assert.equal(f.outputs.at(-1).items[0].canExecuteNow, false);
  f.runtime.receipts[0].threadId = 'different-thread';
  await assert.rejects(f.run('queue'), { code: 'QUEUE_TARGET_MISMATCH' });
  assert.equal(f.calls.filter((call) => call.body).length, 0);
});

test('queue-execute promotes the original ID and never posts another reply or mutates the original intent', async (t) => {
  const f = await fixture(t);
  await f.run('send', 'queued-original-001', ['--text-file', f.taskFile, '--mode', 'queue']);
  const originalPath = join(f.directory, 'operations', 'queued-original-001.json');
  const original = await readFile(originalPath, 'utf8');
  f.runtime.state = 'running';
  f.runtime.turnId = 'turn-current';
  f.runtime.receipts = [queuedItem()];
  const args = [
    '--thread-id',
    threadId,
    '--queued-message-id',
    'queued-original-001',
    '--expected-turn-id',
    'turn-current',
  ];
  await f.run('queue-execute', 'promote-action-001', args);
  assert.deepEqual(f.calls.find((call) => call.path === '/v1/queue/execute').body, {
    hostId,
    threadId,
    clientMessageId: 'queued-original-001',
    expectedTurnId: 'turn-current',
  });
  assert.equal(await readFile(originalPath, 'utf8'), original);
  f.runtime.turnId = 'turn-later';
  await f.run('queue-execute', 'promote-action-001', args);
  assert.equal(f.outputs.at(-1).actionOutcome, 'query-only');
  assert.equal(f.outputs.at(-1).result.clientMessageId, 'queued-original-001');
  await assert.rejects(
    f.run('queue-execute', 'promote-action-001', [...args.slice(0, -1), 'turn-later']),
    {
      code: 'OPERATION_ID_REUSED_WITH_DIFFERENT_INPUT',
    },
  );
  assert.equal(f.calls.filter((call) => call.path === '/v1/queue/execute').length, 1);
  assert.equal(f.calls.filter((call) => call.path === '/v1/replies').length, 1);
});

test('idle queue-execute uses the existing Host execution contract without inventing a Turn', async (t) => {
  const f = await fixture(t);
  f.runtime.receipts = [queuedItem()];
  await f.run('queue-execute', 'promote-idle-001', [
    '--thread-id',
    threadId,
    '--queued-message-id',
    'queued-original-001',
  ]);
  assert.deepEqual(f.calls.find((call) => call.path === '/v1/queue/execute').body, {
    hostId,
    threadId,
    clientMessageId: 'queued-original-001',
  });
});

test('queue-execute refuses legacy, absent, changed, unready or blocked queue items before POST', async (t) => {
  const f = await fixture(t);
  const args = ['--thread-id', threadId, '--queued-message-id', 'queued-original-001'];
  for (const override of [
    { queueSource: 'host' },
    { queuePresent: false },
    { canExecuteNow: false },
    { queueSyncError: 'sync failed' },
    { state: 'uncertain' },
    { threadId: 'other-thread' },
  ]) {
    f.runtime.receipts = [{ ...queuedItem(), ...override }];
    await assert.rejects(f.run('queue-execute', 'promote-rejected-001', args), {
      code: 'EXECUTABLE_ORIGINAL_QUEUE_ITEM_REQUIRED',
    });
  }
  f.runtime.receipts = [queuedItem()];
  f.runtime.state = 'waiting-for-approval';
  await assert.rejects(f.run('queue-execute', 'promote-blocked-001', args), {
    code: 'QUEUE_EXECUTE_REQUIRES_IDLE_OR_RUNNING',
  });
  f.runtime.state = 'running';
  f.runtime.turnId = 'turn-current';
  await assert.rejects(f.run('queue-execute', 'promote-no-turn-001', args), {
    code: 'CURRENT_TURN_MISMATCH',
  });
  await assert.rejects(
    f.run('queue-execute', 'promote-stale-001', [...args, '--expected-turn-id', 'turn-other']),
    { code: 'CURRENT_TURN_MISMATCH' },
  );
  await assert.rejects(
    f.run('queue-execute', 'promote-settings-001', [...args, '--effort', 'xhigh']),
    { code: 'QUEUE_EXECUTE_REUSES_ORIGINAL_INPUT' },
  );
  assert.equal(f.calls.filter((call) => call.body).length, 0);
});

test('lost queue-execute response and a missing original receipt never trigger action replay', async (t) => {
  const f = await fixture(t);
  f.runtime.receipts = [queuedItem()];
  const args = [
    'queue-execute',
    '--host-id',
    hostId,
    '--thread-id',
    threadId,
    '--operation-id',
    'promote-lost-001',
    '--queued-message-id',
    'queued-original-001',
  ];
  const run = () =>
    main(args, {
      directory: f.directory,
      output: (text) => f.outputs.push(JSON.parse(text)),
      transport: async (path, body, authenticated) => {
        const response = await f.transport(path, body, authenticated);
        if (path === '/v1/queue/execute') throw Error('response lost');
        return response;
      },
    });
  await run();
  assert.equal(f.outputs.at(-1).status, 'uncertain-query-required');
  f.receipts.clear();
  await run();
  assert.equal(f.outputs.at(-1).http, 404);
  assert.equal(f.outputs.at(-1).replayed, false);
  assert.equal(f.calls.filter((call) => call.path === '/v1/queue/execute').length, 1);
});

// Exercise the production default HTTPS transport; only the socket boundary is mocked.
// All payloads, credentials and ledgers below belong to this synthetic fixture.
async function defaultHttpsFixture(t, configure = () => ({})) {
  const f = await fixture(t, true);
  const requests = [];
  const deadlines = [];
  const timers = globalThis.setTimeout;
  t.mock.method(globalThis, 'setTimeout', (callback, delay, ...args) => {
    if (delay === 55000) deadlines.push({ callback, delay });
    return timers(callback, delay, ...args);
  });
  t.mock.method(https, 'request', (url, options, onResponse) => {
    const request = new EventEmitter();
    const record = { url, options, ledger: null, response: null, destroyed: false };
    requests.push(record);
    request.destroy = (error) => {
      record.destroyed = true;
      record.response?.destroy();
      if (error) queueMicrotask(() => request.emit('error', error));
      return request;
    };
    request.end = (bytes) => {
      queueMicrotask(async () => {
        try {
          const body = bytes === undefined ? undefined : JSON.parse(bytes.toString());
          const path = url.pathname + url.search;
          if (body) {
            record.ledger = JSON.parse(
              await readFile(
                join(f.directory, 'operations', body.clientMessageId + '.json'),
                'utf8',
              ),
            );
          }
          const result = await f.transport(path, body, !!options.headers.Authorization);
          const spec = configure({ path, body, result, record });
          const value = spec.value ?? result.value;
          const plain = Buffer.from(spec.text ?? JSON.stringify(value));
          const encoding = spec.encoding ?? 'gzip';
          const wire = spec.wire ?? (encoding === 'gzip' ? gzipSync(plain) : plain);
          const parts = [];
          const chunkSize = spec.chunkSize ?? 7;
          for (let i = 0; i < wire.length; i += chunkSize)
            parts.push(wire.subarray(i, i + chunkSize));
          const response = spec.hold ? new Readable({ read() {} }) : Readable.from(parts);
          record.response = response;
          response.headers = {
            ...(encoding === 'none' ? {} : { 'content-encoding': encoding }),
            'content-length': String(wire.length),
            ...spec.headers,
          };
          response.statusCode = spec.http ?? result.http;
          onResponse(response);
          if (spec.streamError)
            queueMicrotask(() => response.destroy(new Error('synthetic stream failure')));
          spec.started?.();
        } catch (error) {
          request.emit('error', error);
        }
      });
      return request;
    };
    return request;
  });
  return { ...f, requests, deadlines };
}

async function noSubmission(f) {
  assert.equal(f.calls.filter((call) => call.body).length, 0);
  assert.deepEqual(await readdir(join(f.directory, 'operations')), []);
}

// Ephemeral, synthetic X.509 fixtures built with Node crypto; no stored private keys,
// openssl executable or external server is needed. No SAN is deliberately present.
const der = (tag, ...parts) => {
  const body = Buffer.concat(parts);
  const size = body.length;
  const length =
    size < 128
      ? Buffer.from([size])
      : Buffer.from(size.toString(16).padStart(size < 256 ? 2 : 4, '0'), 'hex');
  return Buffer.concat([
    Buffer.from([tag]),
    ...(size < 128 ? [length] : [Buffer.from([0x80 | length.length]), length]),
    body,
  ]);
};
const sequence = (...parts) => der(0x30, ...parts);
function certificateFixture({ ca = false, issuer, badSignature = false, from, to } = {}) {
  const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const name = sequence(
    der(
      0x31,
      sequence(
        der(0x06, Buffer.from('550403', 'hex')),
        der(0x0c, Buffer.from('contact-host-fixture.invalid')),
      ),
    ),
  );
  const algorithm = sequence(der(0x06, Buffer.from('2a8648ce3d040302', 'hex')));
  const timestamp = (date) =>
    der(
      0x18,
      Buffer.from(
        date
          .toISOString()
          .replace(/[-:T]/g, '')
          .replace(/\.\d{3}/, ''),
      ),
    );
  const now = Date.now();
  const serial = randomBytes(16);
  serial[0] &= 0x7f;
  serial[0] |= 1;
  const body = sequence(
    der(0xa0, der(0x02, Buffer.from([2]))),
    der(0x02, serial),
    algorithm,
    issuer?.name ?? name,
    sequence(
      timestamp(from ?? new Date(now - 60_000)),
      timestamp(to ?? new Date(now + 86_400_000)),
    ),
    name,
    publicKey.export({ type: 'spki', format: 'der' }),
    der(
      0xa3,
      sequence(
        sequence(
          der(0x06, Buffer.from('551d13', 'hex')),
          der(0x01, Buffer.from([0xff])),
          der(0x04, ca ? sequence(der(0x01, Buffer.from([0xff]))) : sequence()),
        ),
      ),
    ),
  );
  const signingKey = badSignature
    ? generateKeyPairSync('ec', { namedCurve: 'prime256v1' }).privateKey
    : (issuer?.privateKey ?? privateKey);
  const raw = sequence(
    body,
    algorithm,
    der(0x03, Buffer.from([0]), sign('sha256', body, signingKey)),
  );
  const pem =
    '-----BEGIN CERTIFICATE-----\n' +
    raw
      .toString('base64')
      .match(/.{1,64}/g)
      .join('\n') +
    '\n-----END CERTIFICATE-----\n';
  return { pem, raw, privateKey, name, sha256: createHash('sha256').update(raw).digest('hex') };
}

async function realPinnedHttpsFixture(t, certificate = certificateFixture(), trust = certificate) {
  const f = await fixture(t, true);
  const certificatePath = join(f.directory, 'public-host-certificate.pem');
  await writeFile(certificatePath, trust.pem, { mode: 0o644 });
  const requests = [];
  const server = https.createServer(
    { key: certificate.privateKey.export({ type: 'pkcs8', format: 'pem' }), cert: certificate.pem },
    async (request, response) => {
      const chunks = [];
      for await (const chunk of request) chunks.push(chunk);
      const bytes = Buffer.concat(chunks);
      const body = bytes.length ? JSON.parse(bytes.toString()) : undefined;
      requests.push({ path: request.url, authorization: request.headers.authorization, body });
      try {
        const result = await f.transport(request.url, body, !!request.headers.authorization);
        response.writeHead(result.http, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify(result.value));
      } catch {
        request.socket.destroy();
      }
    },
  );
  await new Promise((done, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', done);
  });
  t.after(async () => {
    server.closeAllConnections();
    await new Promise((done) => server.close(done));
  });
  const registryPath = join(f.directory, 'hosts.json');
  const registry = JSON.parse(await readFile(registryPath, 'utf8'));
  registry.hosts[0].endpoint = 'https://127.0.0.1:' + server.address().port;
  registry.hosts[0].tlsPin = { sha256: trust.sha256, certificatePath };
  const saveRegistry = () => writeFile(registryPath, JSON.stringify(registry), { mode: 0o600 });
  await saveRegistry();
  return { ...f, requests, registry, saveRegistry, certificatePath };
}

async function noTlsBusiness(f) {
  assert.equal(f.requests.length, 0, 'TLS rejection sends no HTTP authentication or operation');
  assert.equal(f.calls.length, 0);
  const files = await readdir(join(f.directory, 'operations')).catch((error) => {
    if (error.code === 'ENOENT') return [];
    throw error;
  });
  assert.deepEqual(files, []);
}

test('real HTTPS accepts only explicit per-Host public leaf CA plus exact DER pin without SAN', async (t) => {
  const certificate = certificateFixture();
  const x509 = new X509Certificate(certificate.pem);
  assert.equal(x509.subjectAltName, undefined);
  assert.equal(x509.checkIP('127.0.0.1'), undefined);
  const f = await realPinnedHttpsFixture(t, certificate);
  await f.run('probe');
  assert.equal(f.outputs.at(-1).hostIdentityMatched, true);
  assert.deepEqual(
    f.requests.map((request) => [request.path, !!request.authorization]),
    [
      ['/healthz', true],
      ['/v1/workspace', true],
      ['/healthz', false],
    ],
  );
  await f.run('send', 'real-pinned-send-001', ['--text-file', f.taskFile]);
  assert.equal(f.calls.filter((call) => call.body).length, 1);
  const accepted = f.requests.length;
  // A different unpinned Host never inherits this CA or the pinned Host's TLS session.
  const otherId = '00000000-0000-4000-8000-000000000003';
  const other = { ...f.registry.hosts[0], hostId: otherId };
  delete other.tlsPin;
  f.registry.hosts.push(other);
  await f.saveRegistry();
  await assert.rejects(
    main(['probe', '--host-id', otherId], { directory: f.directory, output: () => {} }),
    { code: 'NETWORK_OUTCOME_UNKNOWN' },
  );
  assert.equal(f.requests.length, accepted);
});

test('real HTTPS rejects a different leaf even when its chain is signed by the explicit CA', async (t) => {
  const anchor = certificateFixture({ ca: true });
  const different = certificateFixture({ issuer: anchor });
  assert.equal(
    new X509Certificate(different.pem).verify(new X509Certificate(anchor.pem).publicKey),
    true,
  );
  const f = await realPinnedHttpsFixture(t, different, anchor);
  await assert.rejects(f.run('send', 'real-wrong-leaf-001', ['--text-file', f.taskFile]), {
    code: 'NETWORK_OUTCOME_UNKNOWN',
  });
  await noTlsBusiness(f);
});

test('real HTTPS rejects an unrelated CA before any authentication or business write', async (t) => {
  const f = await realPinnedHttpsFixture(t, certificateFixture(), certificateFixture());
  await assert.rejects(f.run('send', 'real-wrong-ca-001', ['--text-file', f.taskFile]), {
    code: 'NETWORK_OUTCOME_UNKNOWN',
  });
  await noTlsBusiness(f);
});

for (const defect of ['pin', 'signature', 'expired', 'not-yet-valid', 'bundle', 'relative-path']) {
  test(`explicit TLS ${defect} is rejected before authentication or business write`, async (t) => {
    const now = Date.now();
    const certificate = certificateFixture(
      defect === 'signature'
        ? { badSignature: true }
        : defect === 'expired'
          ? { from: new Date(now - 172_800_000), to: new Date(now - 86_400_000) }
          : defect === 'not-yet-valid'
            ? { from: new Date(now + 86_400_000), to: new Date(now + 172_800_000) }
            : {},
    );
    const f = await realPinnedHttpsFixture(t, certificate);
    if (defect === 'pin') f.registry.hosts[0].tlsPin.sha256 = '0'.repeat(64);
    if (defect === 'bundle')
      await writeFile(f.certificatePath, certificate.pem + certificateFixture().pem);
    if (defect === 'relative-path')
      f.registry.hosts[0].tlsPin.certificatePath = 'public-host-certificate.pem';
    await f.saveRegistry();
    await assert.rejects(f.run('send', 'real-invalid-pin-001', ['--text-file', f.taskFile]), {
      code:
        defect === 'pin'
          ? 'TLS_PIN_CERTIFICATE_MISMATCH'
          : defect === 'relative-path'
            ? 'INVALID_TLS_PIN'
            : 'INVALID_TLS_PIN_CERTIFICATE',
    });
    await noTlsBusiness(f);
  });
}

test('real pinned HTTPS preserves an uncertain original operation and only queries on reentry', async (t) => {
  const f = await realPinnedHttpsFixture(t);
  f.loseNextReply();
  const args = ['--text-file', f.taskFile];
  await f.run('send', 'real-pinned-unknown-001', args);
  assert.equal(f.outputs.at(-1).status, 'uncertain-query-required');
  const saved = JSON.parse(
    await readFile(join(f.directory, 'operations', 'real-pinned-unknown-001.json'), 'utf8'),
  );
  const before = f.requests.length;
  await f.run('send', 'real-pinned-unknown-001', args);
  assert.equal(f.requests.length, before + 1);
  assert.equal(f.requests.at(-1).path.startsWith('/v1/receipts?'), true);
  assert.equal(
    f.calls.filter((call) => call.body?.clientMessageId === 'real-pinned-unknown-001').length,
    1,
  );
  assert.equal(f.outputs.at(-1).replayed, false);
  assert.deepEqual(
    JSON.parse(
      await readFile(join(f.directory, 'operations', 'real-pinned-unknown-001.json'), 'utf8'),
    ).payload,
    saved.payload,
  );
});

for (const encoding of ['gzip', 'identity', 'none']) {
  test(`default HTTPS ${encoding} fragmented response preserves target, ledger and metadata checks`, async (t) => {
    const f = await defaultHttpsFixture(t, () => ({ encoding }));
    await f.run('history');
    await f.run('send', `native-${encoding}-001`, ['--text-file', f.taskFile]);
    assert.equal(f.outputs[0].hostId, hostId);
    assert.equal(f.outputs[0].threadId, threadId);
    assert.equal(f.outputs[0].canControl, true);
    assert.equal(f.outputs.at(-1).status, 'submitted');
    const histories = f.requests.filter((item) => item.url.pathname === '/v1/history');
    assert.equal(histories.length, 2);
    for (const { url } of histories) {
      assert.equal(url.searchParams.get('hostId'), hostId);
      assert.equal(url.searchParams.get('threadId'), threadId);
      assert.equal(url.searchParams.get('window'), 'latest-v1');
    }
    assert.equal(f.requests.length, f.deadlines.length);
    for (const { url, options } of f.requests) {
      assert.equal(url.protocol, 'https:');
      assert.equal(url.hostname, 'fixture.invalid');
      assert.equal(options.headers['Accept-Encoding'], 'gzip');
      assert.equal(options.headers.Authorization, 'Bearer ' + 'A'.repeat(48));
      assert.notEqual(options.rejectUnauthorized, false);
      assert.equal(options.agent, undefined);
    }
    const post = f.requests.find((item) => item.options.method === 'POST');
    assert.equal(post.url.pathname, '/v1/replies');
    assert.equal(post.ledger.status, 'intent-saved');
    assert.equal(post.ledger.payload.clientMessageId, `native-${encoding}-001`);
    assert.equal(f.calls.filter((call) => call.body).length, 1);
  });
}

for (const encoding of ['gzip', 'identity']) {
  for (const mismatch of ['workspace-host', 'history-host', 'history-thread', 'owner']) {
    test(`default HTTPS ${encoding} rejects ${mismatch} before ledger or POST`, async (t) => {
      const f = await defaultHttpsFixture(t, ({ path, result }) => {
        const value = structuredClone(result.value);
        if (mismatch === 'workspace-host' && path === '/v1/workspace') value.hostId = 'foreign';
        if (path.startsWith('/v1/history?')) {
          if (mismatch === 'history-host') value.hostId = 'foreign';
          if (mismatch === 'history-thread') value.threadId = 'foreign';
          if (mismatch === 'owner') value.runtime.canControl = false;
        }
        return { encoding, value };
      });
      await assert.rejects(f.run('send', 'mismatch-native-001', ['--text-file', f.taskFile]), {
        code:
          mismatch === 'workspace-host'
            ? 'HOST_ID_OR_RUNTIME_MISMATCH'
            : mismatch === 'owner'
              ? 'VERIFIED_ORIGINAL_OWNER_REQUIRED'
              : 'THREAD_ID_OR_HISTORY_MISMATCH',
      });
      await noSubmission(f);
    });
  }
}

for (const failure of ['corrupt-gzip', 'truncated-gzip', 'stream-error']) {
  test(`default HTTPS ${failure} fails preflight with no ledger or POST`, async (t) => {
    const f = await defaultHttpsFixture(t, ({ path }) => {
      if (!path.startsWith('/v1/history?')) return {};
      if (failure === 'stream-error') return { hold: true, streamError: true };
      const compressed = gzipSync(Buffer.from('{"synthetic":true}'));
      return {
        wire: failure === 'corrupt-gzip' ? Buffer.from('not gzip') : compressed.subarray(0, -5),
      };
    });
    await assert.rejects(f.run('send', 'bad-gzip-native-001', ['--text-file', f.taskFile]), {
      code: 'NETWORK_OUTCOME_UNKNOWN',
    });
    await noSubmission(f);
    assert.equal(f.requests.at(-1).destroyed, true);
  });
}

test('default HTTPS uses the existing 55s deadline while waiting for compressed body', async (t) => {
  let started;
  const ready = new Promise((resolve) => {
    started = resolve;
  });
  const f = await defaultHttpsFixture(t, ({ path }) =>
    path.startsWith('/v1/history?') ? { hold: true, started } : {},
  );
  const pending = f.run('send', 'deadline-native-001', ['--text-file', f.taskFile]);
  const rejected = assert.rejects(pending, { code: 'NETWORK_OUTCOME_UNKNOWN' });
  await ready;
  assert.equal(f.deadlines.length, 3);
  f.deadlines.at(-1).callback();
  await rejected;
  await noSubmission(f);
  assert.equal(f.requests.at(-1).destroyed, true);
});

test('default HTTPS does not follow redirect or infer gzip for an identity response', async (t) => {
  const f = await defaultHttpsFixture(t, ({ path }) =>
    path === '/v1/workspace'
      ? { http: 302, headers: { location: 'https://foreign.invalid/never-follow' } }
      : {},
  );
  await assert.rejects(f.run('send', 'redirect-native-001', ['--text-file', f.taskFile]), {
    code: 'WORKSPACE_RUNTIME_UNAVAILABLE',
  });
  assert.deepEqual(
    f.requests.map((item) => item.url.pathname),
    ['/healthz', '/v1/workspace'],
  );
  await noSubmission(f);
  const f2 = await defaultHttpsFixture(t, () => ({
    encoding: 'identity',
    wire: gzipSync(Buffer.from('{}')),
  }));
  await assert.rejects(f2.run('send', 'identity-wire-native-001', ['--text-file', f2.taskFile]), {
    code: 'AUTHENTICATED_HEALTH_UNAVAILABLE',
  });
  await noSubmission(f2);
});

test('default HTTPS bad gzip after POST preserves intent and reentry only queries receipt', async (t) => {
  const f = await defaultHttpsFixture(t, ({ path }) =>
    path === '/v1/replies' ? { wire: Buffer.from('corrupt accepted response') } : {},
  );
  const args = ['--text-file', f.taskFile];
  await f.run('send', 'unknown-gzip-native-001', args);
  assert.equal(f.outputs.at(-1).status, 'uncertain-query-required');
  const saved = JSON.parse(
    await readFile(join(f.directory, 'operations', 'unknown-gzip-native-001.json'), 'utf8'),
  );
  assert.equal(saved.status, 'uncertain-query-required');
  assert.equal(saved.payload.text, await readFile(f.taskFile, 'utf8'));
  const previousRequests = f.requests.length;
  await f.run('send', 'unknown-gzip-native-001', args);
  assert.equal(f.requests.length, previousRequests + 1);
  assert.equal(f.requests.at(-1).options.method, 'GET');
  assert.equal(f.requests.at(-1).url.pathname, '/v1/receipts');
  assert.equal(f.calls.filter((call) => call.path === '/v1/replies').length, 1);
  assert.equal(f.outputs.at(-1).queriedExistingOperation, true);
  assert.equal(f.outputs.at(-1).replayed, false);
});

test('create binds current project list and sends first input only once across reentry', async (t) => {
  const f = await fixture(t);
  const args = ['--text-file', f.taskFile, '--no-suitable-reason', '无相关会话'];
  await assert.rejects(
    f.run('create', 'wrong-project-001', [...args, '--project-id', 'foreign-project']),
    { code: 'EXACT_PROJECT_UNAVAILABLE' },
  );
  await f.run('create', 'create-coordination-001', args);
  await f.run('create', 'create-coordination-001', args);
  assert.equal(f.calls.filter((c) => c.path === '/v1/desktop/creations').length, 1);
  assert.equal(f.calls.filter((c) => c.path === '/v1/desktop/creations/submit').length, 1);
  const firstWrite = f.calls.findIndex((c) => c.body);
  assert.ok(f.calls.slice(0, firstWrite).some((c) => c.path === '/v1/workspace'));
  assert.equal(f.outputs.at(-1).result.phase, 'confirmed');
});

test('lost response keeps old intent queryable while allowing a new scoped communication', async (t) => {
  const f = await fixture(t);
  const args = ['--text-file', f.taskFile];
  f.loseNextReply();
  await f.run('send', 'unknown-message-001', args);
  await f.run('send', 'unknown-message-001', args);
  const followup = join(f.directory, 'followup.txt');
  await writeFile(followup, 'task-fixture 状态核对，关联原 unknown-message-001；已完成项跳过。', {
    mode: 0o600,
  });
  await f.run('send', 'reconcile-message-001', ['--text-file', followup]);
  assert.equal(f.calls.filter((c) => c.path === '/v1/replies').length, 2);
  assert.equal(f.calls.filter((c) => c.body?.clientMessageId === 'unknown-message-001').length, 1);
  assert.equal(f.outputs.at(-1).status, 'submitted');
});

test('send and create reject file payloads before network or immutable intent creation', async (t) => {
  const encoded = Buffer.from('private synthetic patch content\n'.repeat(40)).toString('base64');
  const cases = [
    ['oversize', '测 '.repeat(2048) + 'x', 'TASK_TEXT_TOO_LARGE_USE_FILE_TRANSFER'],
    ['base64', '补丁如下：' + encoded, 'ENCODED_PAYLOAD_USE_FILE_TRANSFER'],
    ['wrapped', encoded.match(/.{1,64}/g).join('\n'), 'ENCODED_PAYLOAD_USE_FILE_TRANSFER'],
    ['base64url', '-_ab12'.repeat(60), 'ENCODED_PAYLOAD_USE_FILE_TRANSFER'],
    ['hex', '0123456789abcdef'.repeat(20), 'ENCODED_PAYLOAD_USE_FILE_TRANSFER'],
    ['data-uri', '图片 data:image/png;base64,YWJj', 'ENCODED_PAYLOAD_USE_FILE_TRANSFER'],
    ['binary', Buffer.from([65, 0, 66]), 'BINARY_TASK_TEXT'],
    ['invalid-utf8', Buffer.from([0xc3, 0x28]), 'INVALID_TASK_TEXT_UTF8'],
  ];
  for (const command of ['send', 'create']) {
    for (const [label, content, code] of cases) {
      const f = await fixture(t);
      await writeFile(f.taskFile, content);
      await assert.rejects(
        f.run(command, 'guard-' + command + '-' + label, ['--text-file', f.taskFile]),
        { code },
      );
      assert.equal(f.calls.length, 0, command + ': ' + label);
      assert.deepEqual(await readdir(join(f.directory, 'operations')), []);
      assert.deepEqual(f.outputs, []);
    }
  }
});

test('complete UTF-8 budget includes the header; short fingerprints and file references survive unchanged', async (t) => {
  const f = await fixture(t);
  const text = '测 '.repeat(CONTEXT_LIMITS.messageBytes / 4);
  await writeFile(f.taskFile, text);
  await f.run('send', 'byte-boundary-001', ['--text-file', f.taskFile]);
  assert.equal(f.calls.find((call) => call.path === '/v1/replies').body.text, text);
  const reference =
    '**来自：测试电脑 → 项目 → 会话**\n任务：读取已传文件；传输 ID：file-fixture；路径：<目标接收路径>；大小：61062；SHA256：' +
    '0123456789abcdef'.repeat(4);
  await writeFile(f.taskFile, reference);
  await f.run('create', 'reference-create-001', [
    '--text-file',
    f.taskFile,
    '--no-suitable-reason',
    '独立任务',
  ]);
  assert.equal(f.calls.find((call) => call.path === '/v1/desktop/creations').body.text, reference);
  assert(!JSON.stringify(f.outputs).includes(reference));
});

test('legacy oversized encoded intents stay queryable without replay; prepared first submission is guarded', async (t) => {
  const f = await fixture(t);
  const text = 'bW9jay1wYXRjaA=='.repeat(600);
  const textSha256 = createHash('sha256').update(text).digest('hex');
  // Establish the private ledger directory through a read-only-to-runtime rejection.
  await writeFile(f.taskFile, '');
  await assert.rejects(f.run('send', 'empty-intent-001', ['--text-file', f.taskFile]));
  await writeFile(f.taskFile, text);
  for (const kind of ['send', 'create']) {
    const id = 'legacy-' + kind + '-001';
    const payload = { hostId, text, effort: 'xhigh', projectId: null };
    const record = {
      kind,
      hostId,
      threadId,
      operationId: id,
      payload,
      textSha256,
      status: 'unknown',
    };
    await writeFile(join(f.directory, 'operations', id + '.json'), JSON.stringify(record), {
      mode: 0o600,
    });
    if (kind === 'send')
      f.receipts.set(id, { hostId, threadId, clientMessageId: id, state: 'submitted', text });
    else f.creations.set(id, { hostId, clientOperationId: id, phase: 'prepared' });
    await f.run(kind, id, ['--text-file', f.taskFile]);
    assert.equal(f.outputs.at(-1).queriedExistingOperation, true);
    assert.equal(f.outputs.at(-1).replayed, false);
    const saved = JSON.parse(await readFile(join(f.directory, 'operations', id + '.json'), 'utf8'));
    assert.equal(saved.payload.text, text);
    assert.equal(saved.textSha256, textSha256);
  }
  await assert.rejects(f.run('create-submit', 'legacy-create-001'), {
    code: 'TASK_TEXT_TOO_LARGE_USE_FILE_TRANSFER',
  });
  assert.equal(f.calls.filter((call) => call.body).length, 0);
  assert(!JSON.stringify(f.outputs).includes(text));
});

test('workspace pages obey the full JSON output budget and preserve exact IDs across pages', async (t) => {
  const f = await fixture(t);
  f.catalog.threads = Array.from({ length: 125 }, (_, i) => ({
    id: 'thread-fixture-' + i,
    title: '测'.repeat(160),
    projectId: i % 2 ? 'project-one' : 'project-two',
    state: 'idle',
  }));
  const ids = [];
  let offset = 0;
  do {
    await f.run('workspace', undefined, ['--limit', '50', '--offset', String(offset)]);
    const page = f.outputs.at(-1);
    assert(Buffer.byteLength(JSON.stringify(page)) + 1 <= CONTEXT_LIMITS.outputBytes);
    assert.equal(page.totalThreads, 125);
    assert(page.threads.length > 0);
    ids.push(...page.threads.map((thread) => thread.id));
    assert(page.nextThreadOffset === null || page.nextThreadOffset > offset);
    offset = page.nextThreadOffset;
  } while (offset !== null);
  assert.deepEqual(
    ids,
    f.catalog.threads.map((thread) => thread.id),
  );
  await f.run('workspace', undefined, ['--project-id', 'project-one']);
  assert.equal(f.outputs.at(-1).totalThreads, 62);
  assert(f.outputs.at(-1).threads.every((thread) => thread.projectId === 'project-one'));
  await assert.rejects(f.run('workspace', undefined, ['--limit', '51']), {
    code: 'INVALID_CATALOG_PAGE',
  });
});

test('remote receipts and catalog fields cannot echo encoded blobs, credentials or nested payloads', async (t) => {
  const f = await fixture(t);
  const blob = Buffer.from('synthetic output bytes '.repeat(20)).toString('base64');
  f.catalog.threads[0].title = blob;
  f.catalog.threads.push({ id: 'credential-fixture', title: 'prefix ' + 'A'.repeat(48) });
  await f.run('workspace');
  f.receipts.set('unsafe-receipt-001', {
    hostId,
    threadId,
    clientMessageId: 'unsafe-receipt-001',
    error: { text: blob },
    text: blob,
  });
  await f.run('receipt', 'unsafe-receipt-001');
  const output = JSON.stringify(f.outputs);
  assert(!output.includes(blob));
  assert(!output.includes('A'.repeat(48)));
  assert.equal(f.outputs.at(-1).result.error, 'REMOTE_ERROR_DETAILS_OMITTED');
  f.runtime.generationSettings = {
    model: 'fixture-model',
    effort: 'xhigh',
    speed: 'fast',
    permissionMode: 'read-only',
    providerResponse: { text: 'private remote content', bytes: blob },
  };
  await f.run('history');
  assert.deepEqual(f.outputs.at(-1).generationSettings, {
    model: 'fixture-model',
    effort: 'xhigh',
    speed: 'fast',
    permissionMode: 'read-only',
  });
  assert(!JSON.stringify(f.outputs).includes('private remote content'));
});

test('default HTTPS bounds identity and decompressed gzip responses before any submission', async (t) => {
  for (const encoding of ['identity', 'gzip']) {
    const f = await defaultHttpsFixture(t, ({ path }) =>
      path === '/v1/workspace'
        ? {
            encoding,
            chunkSize: 65536,
            text: JSON.stringify({ padding: ' '.repeat(CONTEXT_LIMITS.responseBytes) }),
          }
        : {},
    );
    await assert.rejects(f.run('send', 'response-bound-' + encoding, ['--text-file', f.taskFile]), {
      code: 'HOST_RESPONSE_TOO_LARGE',
    });
    await noSubmission(f);
    assert.equal(f.requests.at(-1).destroyed, true);
  }
});

test('oversized response after POST preserves unknown outcome and only queries on reentry', async (t) => {
  const f = await defaultHttpsFixture(t, ({ path }) =>
    path === '/v1/replies'
      ? {
          text: JSON.stringify({ padding: ' '.repeat(CONTEXT_LIMITS.responseBytes) }),
          chunkSize: 65536,
        }
      : {},
  );
  const id = 'large-post-response-001';
  await f.run('send', id, ['--text-file', f.taskFile]);
  assert.equal(f.outputs.at(-1).status, 'uncertain-query-required');
  const saved = JSON.parse(await readFile(join(f.directory, 'operations', id + '.json'), 'utf8'));
  assert.equal(saved.payload.text, await readFile(f.taskFile, 'utf8'));
  await f.run('send', id, ['--text-file', f.taskFile]);
  assert.equal(f.outputs.at(-1).queriedExistingOperation, true);
  assert.equal(f.outputs.at(-1).replayed, false);
  assert.equal(f.calls.filter((call) => call.path === '/v1/replies').length, 1);
});
