import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { chmod, mkdir, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import http from 'node:http';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { test } from 'node:test';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';

// Real HTTP/runtime/storage; only the Codex CLI JSON-RPC boundary is a fixture.
test(
  '64 MiB resumable HTTP upload survives interruption, response loss and runtime restart',
  { timeout: 180_000 },
  async () => {
    const base = join(process.cwd(), '.local/upload-resume-20261115/http-testing');
    await mkdir(base, { recursive: true, mode: 0o700 });
    const root = await mkdtemp(join(base, 'run-'));
    const fixture = join(root, 'codex-fixture.cjs');
    const calls = join(root, 'calls');
    const token = randomUUID() + randomUUID();
    const started = Date.now();
    let service;
    let endpoint;
    const hash = (data) => createHash('sha256').update(data).digest('hex');
    try {
      await writeFile(
        fixture,
        `#!${process.execPath}\nconst fs=require('node:fs');require('node:readline').createInterface({input:process.stdin}).on('line',line=>{const m=JSON.parse(line),p=m.params||{};fs.appendFileSync(${JSON.stringify(calls)},(m.method||'response')+'\\n');let result={};if(m.method==='initialize')result={userAgent:'http-test-fixture'};else if(m.method==='thread/start'||m.method==='thread/resume')result={thread:{id:p.threadId||'test-thread',turns:[]}};else if(m.method==='thread/list')result={data:[],nextCursor:null};if(m.id!=null)process.stdout.write(JSON.stringify({id:m.id,result})+'\\n')});`,
      );
      await chmod(fixture, 0o700);
      Object.assign(process.env, {
        CODEX_RUNTIME_MODE: 'managed-runtime',
        CODEX_EXECUTABLE: fixture,
        HOST_AGENT_BIND: '127.0.0.1',
        HOST_AGENT_PORT: '0',
        HOST_AGENT_STATE_DIR: join(root, 'state'),
        HOST_AGENT_ROOT: root,
        HOST_AGENT_TOKEN: token,
        HOST_AGENT_TRANSFER_DIR: join(root, 'downloads'),
        HOST_AGENT_DISCOVERY: '0',
        HOST_AGENT_DISCOVERY_BONJOUR: '0',
      });
      const start = async () => {
        service = await startRuntimeServer();
        endpoint = `http://127.0.0.1:${service.server.address().port}`;
      };
      await start();
      const target = service.session.readingTarget();
      const hostId = service.session.hostId;
      const query = (extra = {}, t = target) =>
        '?' + new URLSearchParams({ hostId, target: JSON.stringify(t), ...extra });
      const api = async (path, { body, status = 200, auth = token, headers = {} } = {}) => {
        const response = await fetch(endpoint + path, {
          method: body === undefined ? 'GET' : 'POST',
          headers: {
            ...(auth ? { Authorization: 'Bearer ' + auth } : {}),
            ...headers,
            ...(body === undefined
              ? {}
              : {
                  'Content-Type': Buffer.isBuffer(body)
                    ? 'application/octet-stream'
                    : 'application/json',
                }),
          },
          body:
            body === undefined ? undefined : Buffer.isBuffer(body) ? body : JSON.stringify(body),
        });
        const data = await response.json();
        assert.equal(response.status, status, `${path}: ${JSON.stringify(data)}`);
        return data;
      };
      const workspace = await api('/v1/workspace');
      const thread = await api('/v1/threads', {
        body: { hostId, projectId: workspace.projects[0].id, clientOperationId: randomUUID() },
      });
      const history = () =>
        api('/v1/history?' + new URLSearchParams({ hostId, threadId: thread.id }));
      await history();
      const chunk = Buffer.alloc(256 * 1024, 0x5a);
      const sizeBytes = 64 * 1024 * 1024;
      const digest = createHash('sha256');
      for (let i = 0; i < sizeBytes / chunk.length; i++) digest.update(chunk);
      const manifest = {
        version: 1,
        purpose: 'file-transfer',
        hostId,
        id: randomUUID(),
        name: 'http-64m.bin',
        mediaType: 'application/octet-stream',
        sizeBytes,
        sha256: digest.digest('hex'),
        threadId: null,
        creationId: null,
        attachmentId: null,
      };
      const q = (extra = {}) => query({ id: manifest.id, ...extra });
      const status = () => api('/v1/uploads' + q());
      const sendChunk = (offset, data = chunk) =>
        api('/v1/uploads/chunk' + q({ offset: String(offset) }), {
          body: data,
          headers: { 'x-content-sha256': hash(data) },
        });
      await api('/v1/uploads' + query(), { body: { ...manifest, hostId: 'wrong' }, status: 403 });
      await api('/v1/uploads' + query({ hostId: 'wrong' }), { body: manifest, status: 400 });
      await api('/v1/uploads' + query({}, { ...target, runtimeId: 'wrong' }), {
        body: manifest,
        status: 409,
      });
      await api('/v1/uploads' + query(), { body: manifest, auth: null, status: 401 });
      await api('/v1/uploads' + query(), { body: manifest });
      await sendChunk(chunk.length).then(
        () => assert.fail('wrong offset accepted'),
        (e) => {
          if (e.code === 'ERR_ASSERTION' && /wrong offset accepted/.test(e.message)) throw e;
          assert.match(e.message, /409/);
        },
      );
      // Close before transmitting the advertised body: no bytes may be committed.
      await new Promise((resolve) => {
        const req = http.request(endpoint + '/v1/uploads/chunk' + q({ offset: '0' }), {
          method: 'POST',
          headers: {
            Authorization: 'Bearer ' + token,
            'Content-Type': 'application/octet-stream',
            'Content-Length': chunk.length,
            'x-content-sha256': hash(chunk),
          },
        });
        req.on('error', () => resolve());
        req.write(chunk.subarray(0, 8192), () => req.destroy());
      });
      assert.equal((await status()).committedBytes, 0);
      // Receive headers then deliberately discard the successful body: status is authoritative.
      const loseResponse = (path, data, headers = {}) =>
        new Promise((resolve, reject) => {
          const req = http.request(
            endpoint + path,
            {
              method: 'POST',
              headers: {
                Authorization: 'Bearer ' + token,
                'Content-Type': 'application/octet-stream',
                'Content-Length': data.length,
                ...headers,
              },
            },
            (res) => {
              if (res.statusCode !== 200)
                return reject(new Error(`lost-response request returned ${res.statusCode}`));
              res.destroy();
              resolve();
            },
          );
          req.on('error', reject);
          req.end(data);
        });
      await loseResponse('/v1/uploads/chunk' + q({ offset: '0' }), chunk, {
        'x-content-sha256': hash(chunk),
      });
      assert.equal((await status()).committedBytes, chunk.length);
      const uploadStart = Date.now();
      for (let offset = chunk.length; offset < sizeBytes; offset += chunk.length) {
        if (offset === 32 * 1024 * 1024) {
          service.close();
          service = undefined;
          await start();
          assert.deepEqual(
            service.session.readingTarget(),
            target,
            'restart changed runtime target',
          );
          assert.equal((await status()).committedBytes, offset);
        }
        assert.equal((await sendChunk(offset)).committedBytes, offset + chunk.length);
        if (offset % (8 * 1024 * 1024) === 0) {
          await api('/healthz');
          await history();
        }
        await delay(170);
      }
      const uploadMs = Date.now() - uploadStart;
      assert(uploadMs > 40_000, `transfer was too fast: ${uploadMs}ms`);
      const before = await readFile(calls, 'utf8');
      await loseResponse('/v1/uploads/complete' + q(), Buffer.alloc(0));
      const complete = await status();
      assert.equal(complete.phase, 'completed');
      const after = await readFile(calls, 'utf8');
      assert.equal(
        after.split('\n').filter((x) => x === 'turn/start').length,
        before.split('\n').filter((x) => x === 'turn/start').length,
      );
      assert(
        !after.split('\n').some((x) => x === 'turn/start'),
        'upload/query must not submit model input',
      );
      const files = (await readdir(join(root, 'downloads'))).filter((x) => x !== '.incoming');
      assert.equal(files.length, 1, 'exactly one destination file');
      assert.equal(join(root, 'downloads', files[0]), complete.result.destinationPath);
      const output = createHash('sha256');
      for await (const part of createReadStream(join(root, 'downloads', files[0])))
        output.update(part);
      assert.equal(output.digest('hex'), manifest.sha256);
      console.log(
        JSON.stringify({
          uploadMs,
          elapsedMs: Date.now() - started,
          bytes: sizeBytes,
          restarted: true,
          modelInputCalls: 0,
          runtime: 'managed-runtime with Codex CLI fixture; not Electron',
        }),
      );
    } finally {
      service?.close();
      await rm(root, { recursive: true, force: true });
    }
  },
);
