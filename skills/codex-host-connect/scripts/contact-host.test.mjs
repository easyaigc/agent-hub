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
import { main } from './contact-host.mjs';

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
    capabilities: ['desktop:open', 'desktop:creations', 'desktop:creations:projectless'],
    projects: [],
    threads: [{ id: threadId, hostId, title: '协调', projectId: null }],
  };
  let failNextReply = false;
  const transport = async (path, body, authenticated = true) => {
    calls.push({ path, body });
    if (path === '/healthz')
      return { http: authenticated ? 200 : 401, value: { service: 'host-agent', status: 'ok' } };
    if (path === '/v1/workspace') return { http: 200, value: catalog };
    if (path.startsWith('/v1/history?'))
      return {
        http: 200,
        value: { hostId, threadId, ready: true, runtime: { canControl: true, state: 'idle' } },
      };
    if (path === '/v1/replies') {
      receipts.set(body.clientMessageId, { ...body, state: 'submitted' });
      if (failNextReply) {
        failNextReply = false;
        throw Error('Simulated response loss after acceptance');
      }
      return { http: 200, value: receipts.get(body.clientMessageId) };
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
          for (let i = 0; i < wire.length; i += 7) parts.push(wire.subarray(i, i + 7));
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
