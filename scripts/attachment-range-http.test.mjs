import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { closeSync, openSync, writeSync } from 'node:fs';
import { mkdtemp, open, realpath, rm, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { setTimeout as delay } from 'node:timers/promises';
import { test } from 'node:test';
import { RuntimeSession } from '../packages/codex-adapter/dist/runtime-session.js';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';
import { attachmentReadRange } from '../packages/protocol/dist/attachment-reads.js';

test('single range profile handles suffixes, overflow and malformed input', () => {
  assert.deepEqual(attachmentReadRange('bytes=0-1023', 100), { start: 0, end: 99 });
  assert.deepEqual(attachmentReadRange('bytes=90-', 100), { start: 90, end: 99 });
  assert.deepEqual(attachmentReadRange('bytes=-7', 100), { start: 93, end: 99 });
  assert.deepEqual(attachmentReadRange('bytes=-999999999999999999999', 100), { start: 0, end: 99 });
  for (const value of ['bytes=100-', 'bytes=-0', 'bytes=9-2', 'bytes=999999999999999999999-'])
    assert.throws(() => attachmentReadRange(value, 100), { status: 416 });
  for (const value of [undefined, 'bytes=-', 'items=0-1', 'bytes=0-1,3-4', 'bytes=+1-2'])
    assert.throws(() => attachmentReadRange(value, 100), { status: 400 });
  assert.throws(() => attachmentReadRange('bytes=0-', 0), { status: 416 });
});

// Production HTTP, source authorization, snapshot copy, SHA and disk reads.
// Only runtime connection is stubbed: no real history, desktop or model inputs.
test(
  'protected fixed-version reads preserve legacy downloads and isolate cancellation',
  { timeout: 90_000 },
  async (t) => {
    const root = await realpath(await mkdtemp(join(tmpdir(), 'agent-hub-media-http-')));
    const previousConnect = RuntimeSession.prototype.connect;
    const previousEnvironment = { ...process.env };
    RuntimeSession.prototype.connect = async function () {};
    let service;
    let snapshotRoot;
    t.after(async () => {
      if (service) {
        await service.attachmentReads.close();
        service.close();
      }
      RuntimeSession.prototype.connect = previousConnect;
      for (const key of Object.keys(process.env))
        if (!(key in previousEnvironment)) delete process.env[key];
      Object.assign(process.env, previousEnvironment);
      if (snapshotRoot) await assert.rejects(stat(snapshotRoot), { code: 'ENOENT' });
      await rm(root, { recursive: true });
    });
    const source = join(root, '视频 中文.mp4');
    const output = await open(source, 'wx', 0o600);
    const chunk = Buffer.alloc(256 * 1024, 0x5a);
    const digest = createHash('sha256');
    const size = 64 * 1024 * 1024;
    try {
      for (let offset = 0; offset < size; offset += chunk.length) {
        await output.write(chunk);
        digest.update(chunk);
      }
    } finally {
      await output.close();
    }
    const originalSha = digest.digest('hex');
    const token = randomUUID() + randomUUID();
    Object.assign(process.env, {
      CODEX_RUNTIME_MODE: 'managed-runtime',
      HOST_AGENT_BIND: '127.0.0.1',
      HOST_AGENT_PORT: '0',
      HOST_AGENT_STATE_DIR: join(root, 'state'),
      HOST_AGENT_ROOT: root,
      HOST_AGENT_TOKEN: token,
      HOST_AGENT_TRANSFER_DIR: join(root, 'downloads'),
      HOST_AGENT_DISCOVERY: '0',
      HOST_AGENT_DISCOVERY_BONJOUR: '0',
      HOST_AGENT_ATTACHMENT_RANGE_READ: '1',
    });
    service = await startRuntimeServer();
    const session = service.session;
    session.state.threads.push({
      id: 'thread',
      title: 'Synthetic',
      projectId: null,
      messages: [],
      plan: [],
      turnId: null,
      error: null,
    });
    session.state.attachments.push({
      id: 'resource',
      hostId: session.hostId,
      threadId: 'thread',
      path: source,
      name: '视频 中文.mp4',
      mediaType: 'video/mp4',
      sizeBytes: size,
      sha256: originalSha,
    });
    const endpoint = `http://127.0.0.1:${service.server.address().port}`;
    const target = session.readingTarget();
    const parameters = (readId, extra = {}) =>
      new URLSearchParams({
        hostId: session.hostId,
        target: JSON.stringify(target),
        threadId: 'thread',
        id: 'resource',
        read: 'range-v1',
        readId,
        ...extra,
      });
    const request = (path, query, { auth = token, headers = {}, ...options } = {}) =>
      fetch(endpoint + path + '?' + query, {
        ...options,
        headers: { ...(auth ? { Authorization: 'Bearer ' + auth } : {}), ...headers },
        signal: AbortSignal.timeout(15_000),
      });
    const state = async (id, extra = {}) => {
      const response = await request('/v1/attachments/resolve', parameters(id, extra));
      const value = await response.json();
      return { response, value };
    };
    let copies = 0;
    const capture = session.attachmentFiles.snapshot.bind(session.attachmentFiles);
    session.attachmentFiles.snapshot = (...args) => {
      copies++;
      return capture(...args);
    };
    const prepare = async (id = randomUUID(), extra = {}) => {
      for (let i = 0; i < 500; i++) {
        const { response, value } = await state(id, extra);
        assert.equal(response.status, 200, JSON.stringify(value));
        if (value.phase === 'ready') return value;
        assert.equal(value.phase, 'preparing');
        await delay(10);
      }
      assert.fail('snapshot preparation did not finish');
    };
    const ready = await prepare();
    const query = parameters(ready.readId);
    const headers = { 'If-Match': ready.etag, Range: 'bytes=0-1023' };
    snapshotRoot = await service.attachmentReads.directory;
    await t.test(
      'prepare is idempotent and source is scanned once into a private independent snapshot',
      async () => {
        assert.equal(copies, 1);
        assert.equal(ready.attachment.sha256, originalSha);
        assert.equal(ready.attachment.sizeBytes, size);
        assert(!('path' in ready.attachment));
        await prepare(ready.readId);
        await prepare(ready.readId.toUpperCase());
        assert.equal(copies, 1);
        const snapshot = join(snapshotRoot, ready.readId + '.snapshot');
        assert.notEqual((await stat(snapshot)).ino, (await stat(source)).ino);
        assert.equal((await stat(snapshot)).mode & 0o222, 0);
      },
    );
    await t.test(
      'concurrent startup reserves eight slots before asynchronous source checks',
      async () => {
        const entry = service.attachmentReads.entries.get(ready.readId);
        const assertSource = entry.prepared.assertSource;
        let release;
        const barrier = new Promise((resolve) => {
          release = resolve;
        });
        let entered = 0,
          rejected = 0,
          peak = 0;
        entry.prepared.assertSource = async () => {
          entered++;
          peak = Math.max(
            peak,
            [...service.attachmentReads.entries.values()].reduce((sum, e) => sum + e.active, 0),
          );
          await barrier;
          await assertSource();
        };
        const attempts = Array.from({ length: 12 }, async () => {
          const response = await request('/v1/attachments', query, { headers });
          if (response.status === 503) rejected++;
          await response.arrayBuffer();
          return response.status;
        });
        let statuses;
        try {
          const deadline = Date.now() + 3000;
          while (rejected < 4 && Date.now() < deadline) await delay(10);
        } finally {
          release();
          statuses = await Promise.all(attempts);
          entry.prepared.assertSource = assertSource;
        }
        assert.equal(entered, 8);
        assert.equal(peak, 8);
        assert.equal(statuses.filter((s) => s === 206).length, 8);
        assert.equal(statuses.filter((s) => s === 503).length, 4);
        // Client completion can precede the server file-descriptor close.
        await Promise.all([...entry.reads]);
        assert.equal(entry.active, 0);
        const retry = await request('/v1/attachments', query, { headers });
        assert.equal(retry.status, 206);
        await retry.arrayBuffer();
      },
    );
    await t.test(
      '206 supports prefix, suffix and direct seek without another whole-file scan',
      async () => {
        for (const [range, length, expected] of [
          ['bytes=0-1023', 1024, `bytes 0-1023/${size}`],
          ['bytes=-16', 16, `bytes ${size - 16}-${size - 1}/${size}`],
          [`bytes=${size - 2048}-`, 2048, `bytes ${size - 2048}-${size - 1}/${size}`],
        ]) {
          const response = await request('/v1/attachments', query, {
            headers: { ...headers, Range: range },
          });
          assert.equal(response.status, 206);
          assert.equal(response.headers.get('content-range'), expected);
          assert.equal(response.headers.get('etag'), ready.etag);
          assert.deepEqual(Buffer.from(await response.arrayBuffer()), Buffer.alloc(length, 0x5a));
        }
        assert.equal(copies, 1);
      },
    );
    await t.test('HEAD returns full metadata and ignores Range without a body', async () => {
      const response = await request('/v1/attachments', query, { method: 'HEAD', headers });
      assert.equal(response.status, 200);
      assert.equal(response.headers.get('content-length'), String(size));
      assert.equal(response.headers.get('content-range'), null);
      assert.equal((await response.arrayBuffer()).byteLength, 0);
    });
    await t.test('auth, full target, session scope and version remain strict', async () => {
      const cases = [
        [query, { auth: '' }, 401],
        [query, { headers: { Range: 'bytes=0-1' } }, 428],
        [query, { headers: { ...headers, 'If-Match': '"wrong"' } }, 412],
        [query, { headers: { ...headers, 'If-Range': ready.etag } }, 400],
        [
          parameters(ready.readId, { target: JSON.stringify({ ...target, runtimeId: 'wrong' }) }),
          { headers },
          409,
        ],
        [parameters(ready.readId, { target: '' }), { headers }, 400],
        [parameters(ready.readId, { threadId: 'other' }), { headers }, 403],
        [parameters(ready.readId, { id: 'other' }), { headers }, 403],
        [new URLSearchParams(query.toString() + '&id=resource'), { headers }, 400],
      ];
      for (const [q, options, status] of cases) {
        const response = await request('/v1/attachments', q, options);
        await response.arrayBuffer();
        assert.equal(response.status, status);
      }
    });
    await t.test('invalid and unsatisfiable ranges do not become full downloads', async () => {
      for (const [range, status] of [
        ['bytes=0-1,3-4', 400],
        [`bytes=${size}-`, 416],
      ]) {
        const response = await request('/v1/attachments', query, {
          headers: { ...headers, Range: range },
        });
        assert.equal(response.status, status);
        if (status === 416) assert.equal(response.headers.get('content-range'), `bytes */${size}`);
        assert((await response.arrayBuffer()).byteLength < 2048);
      }
    });
    await t.test(
      'old complete GET still returns 200 and the exact full SHA even with Range',
      async () => {
        const legacy = new URLSearchParams({
          hostId: session.hostId,
          threadId: 'thread',
          id: 'resource',
        });
        const response = await request('/v1/attachments', legacy, {
          headers: { Range: 'bytes=0-15' },
        });
        assert.equal(response.status, 200);
        assert.equal(response.headers.get('content-length'), String(size));
        const hash = createHash('sha256');
        let received = 0;
        for await (const bytes of response.body) {
          hash.update(bytes);
          received += bytes.length;
        }
        assert.equal(received, size);
        assert.equal(hash.digest('hex'), originalSha);
      },
    );
    await t.test(
      'cancelled IDs cannot be restarted by late polls; another play session survives',
      async () => {
        const another = await prepare();
        const response = await request('/v1/attachments/resolve', query, { method: 'DELETE' });
        assert.equal(response.status, 200);
        await response.arrayBuffer();
        const count = copies;
        assert.equal((await state(ready.readId)).response.status, 410);
        assert.equal(copies, count);
        const survivor = await request('/v1/attachments', parameters(another.readId), {
          headers: { 'If-Match': another.etag, Range: 'bytes=0-15' },
        });
        assert.equal(survivor.status, 206);
        assert.equal((await survivor.arrayBuffer()).byteLength, 16);
      },
    );
    await t.test(
      'cancelling preparation is bounded and does not restart from a late poll',
      async () => {
        const original = session.prepareAttachmentRead.bind(session);
        session.prepareAttachmentRead = async (...args) => {
          await delay(1000, undefined, { signal: args[3] });
          return original(...args);
        };
        try {
          const id = randomUUID();
          assert.equal((await state(id)).value.phase, 'preparing');
          const started = performance.now();
          const cancelled = await request('/v1/attachments/resolve', parameters(id), {
            method: 'DELETE',
          });
          assert.equal(cancelled.status, 200);
          await cancelled.arrayBuffer();
          assert(performance.now() - started < 2000);
          assert.equal((await state(id)).response.status, 410);
        } finally {
          session.prepareAttachmentRead = original;
        }
      },
    );
    await t.test(
      'failed and expired reads can be cancelled without weakening target checks',
      async () => {
        const original = session.prepareAttachmentRead;
        const reserved = service.attachmentReads.reserved;
        session.prepareAttachmentRead = async (...args) => {
          await args[4](size);
          throw new Error('synthetic preparation failure');
        };
        try {
          const id = randomUUID();
          await state(id);
          const entry = service.attachmentReads.entries.get(id);
          await entry.work;
          assert(entry.error);
          assert.equal(service.attachmentReads.reserved, reserved);
          await assert.rejects(stat(join(snapshotRoot, id + '.snapshot')), { code: 'ENOENT' });
          for (let attempt = 0; attempt < 2; attempt++) {
            const response = await request('/v1/attachments/resolve', parameters(id), {
              method: 'DELETE',
            });
            assert.equal(response.status, 200);
            await response.arrayBuffer();
          }
          entry.expires = Date.now() - 1;
          const wrong = await request('/v1/attachments/resolve', parameters(id, { id: 'other' }), {
            method: 'DELETE',
          });
          assert.equal(wrong.status, 403);
          await wrong.arrayBuffer();
          const expired = await request('/v1/attachments/resolve', parameters(id), {
            method: 'DELETE',
          });
          assert.equal(expired.status, 200);
          await expired.arrayBuffer();
          assert.equal(service.attachmentReads.entries.has(id), false);
          const unknown = await request('/v1/attachments/resolve', parameters(id), {
            method: 'DELETE',
          });
          assert.equal(unknown.status, 410);
          await unknown.arrayBuffer();
          assert.equal(service.attachmentReads.reserved, reserved);
        } finally {
          session.prepareAttachmentRead = original;
        }
      },
    );
    await t.test('active stream cancellation releases its reader and private file', async () => {
      const playing = await prepare();
      const response = await request('/v1/attachments', parameters(playing.readId), {
        headers: { 'If-Match': playing.etag, Range: 'bytes=0-' },
      });
      assert.equal(response.status, 206);
      const reader = response.body.getReader();
      let received = (await reader.read()).value.byteLength;
      const cancelled = await request('/v1/attachments/resolve', parameters(playing.readId), {
        method: 'DELETE',
      });
      assert.equal(cancelled.status, 200);
      await cancelled.arrayBuffer();
      try {
        for (;;) {
          const next = await reader.read();
          if (next.done) break;
          received += next.value.byteLength;
        }
      } catch (error) {
        assert(error instanceof Error);
      }
      assert(received < size, 'slow consumer must not cause a whole-file prefetch');
      for (
        let attempt = 0;
        attempt < 100 && service.attachmentReads.entries.get(playing.readId).active;
        attempt++
      )
        await delay(10);
      assert.equal(service.attachmentReads.entries.get(playing.readId).active, 0);
      await assert.rejects(stat(join(snapshotRoot, playing.readId + '.snapshot')), {
        code: 'ENOENT',
      });
    });
    await t.test(
      'empty files and over-budget sparse sources have explicit bounded outcomes',
      async () => {
        for (const [id, length] of [
          ['empty', 0],
          ['large', 2 * 1024 ** 3 + 1],
        ]) {
          const path = join(root, id);
          const handle = await open(path, 'wx', 0o600);
          try {
            await handle.truncate(length);
          } finally {
            await handle.close();
          }
          session.state.attachments.push({
            id,
            hostId: session.hostId,
            threadId: 'thread',
            path,
            name: id,
            mediaType: 'video/mp4',
            sizeBytes: length,
            sha256: createHash('sha256').digest('hex'),
          });
        }
        const empty = await prepare(randomUUID(), { id: 'empty' });
        const response = await request(
          '/v1/attachments',
          parameters(empty.readId, { id: 'empty' }),
          {
            headers: { 'If-Match': empty.etag, Range: 'bytes=0-' },
          },
        );
        assert.equal(response.status, 416);
        await response.arrayBuffer();
        const id = randomUUID();
        let outcome;
        for (let attempt = 0; attempt < 100; attempt++) {
          outcome = await state(id, { id: 'large' });
          if (outcome.response.status !== 200) break;
          await delay(10);
        }
        assert.equal(outcome.response.status, 507);
      },
    );
    await t.test('an in-place mutation during capture publishes no snapshot bytes', async () => {
      const original = session.attachmentFiles.snapshot.bind(session.attachmentFiles);
      let changed = false;
      session.attachmentFiles.snapshot = (...args) => {
        const report = args[8];
        args[8] = (bytes, total) => {
          report(bytes, total);
          if (bytes > 0 && !changed) {
            changed = true;
            const descriptor = openSync(source, 'r+');
            try {
              writeSync(descriptor, Buffer.from([1]), 0, 1, 0);
            } finally {
              closeSync(descriptor);
            }
          }
        };
        return original(...args);
      };
      const id = randomUUID();
      try {
        let outcome;
        for (let attempt = 0; attempt < 500; attempt++) {
          outcome = await state(id);
          if (outcome.response.status !== 200) break;
          await delay(10);
        }
        assert(changed);
        assert.equal(outcome.response.status, 409);
        await assert.rejects(stat(join(snapshotRoot, id + '.snapshot')), { code: 'ENOENT' });
      } finally {
        session.attachmentFiles.snapshot = original;
        const descriptor = openSync(source, 'r+');
        try {
          writeSync(descriptor, Buffer.from([0x5a]), 0, 1, 0);
        } finally {
          closeSync(descriptor);
        }
      }
    });
    await t.test(
      'source replacement refuses old sessions, while a new session preserves old metadata',
      async () => {
        const old = await prepare();
        const handle = await open(source, 'r+');
        try {
          await handle.write(Buffer.from([1]), 0, 1, 0);
        } finally {
          await handle.close();
        }
        const response = await request('/v1/attachments', parameters(old.readId), {
          headers: { 'If-Match': old.etag, Range: 'bytes=0-15' },
        });
        assert.equal(response.status, 409);
        await response.arrayBuffer();
        const current = await prepare();
        assert.notEqual(current.etag, old.etag);
        assert.equal(
          session.state.attachments.find((entry) => entry.id === 'resource').sha256,
          originalSha,
        );
        const fresh = await request('/v1/attachments', parameters(current.readId), {
          headers: { 'If-Match': current.etag, Range: 'bytes=0-0' },
        });
        assert.equal(fresh.status, 206);
        assert.deepEqual(Buffer.from(await fresh.arrayBuffer()), Buffer.from([1]));
      },
    );
  },
);
