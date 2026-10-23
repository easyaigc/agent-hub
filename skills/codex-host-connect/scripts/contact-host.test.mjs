import assert from 'node:assert/strict';
import { mkdtemp, writeFile, readFile, readdir, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import test from 'node:test';
import https from 'node:https';
import { EventEmitter } from 'node:events';
import { Readable } from 'node:stream';
import { gzipSync } from 'node:zlib';
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
