import assert from 'node:assert/strict';
import { randomUUID, randomBytes } from 'node:crypto';
import { chmod, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import http from 'node:http';
import https from 'node:https';
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { once } from 'node:events';
import { test } from 'node:test';
import WebSocket, {
  WebSocketServer,
  createWebSocketStream,
} from '../apps/host-agent/node_modules/ws/wrapper.mjs';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';
import { DevPreviews, devListenerIdentity } from '../apps/host-agent/dist/dev-previews.js';

const nativeOwnershipSupported = process.platform === 'darwin';

test(
  nativeOwnershipSupported
    ? 'production Host preview HTTP and WebSocket boundaries with native ownership'
    : 'production Host preview fails closed without platform ownership support',
  { timeout: 60000 },
  async () => {
    const root = await mkdtemp(join(process.cwd(), '.local/dev-preview-test-'));
    const fixture = join(root, 'fixture.cjs');
    const token = randomBytes(32).toString('hex');
    const secret = randomBytes(32).toString('hex');
    let service, upstream, wss;
    const streams = new Set();
    let requests = 0,
      lastCookie;
    try {
      await writeFile(
        fixture,
        `#!${process.execPath}\nrequire('node:readline').createInterface({input:process.stdin}).on('line',line=>{const m=JSON.parse(line),p=m.params||{};let result={};if(m.method==='initialize')result={userAgent:'fixture'};else if(m.method==='thread/start'||m.method==='thread/resume')result={thread:{id:p.threadId||'test-thread',turns:[]}};else if(m.method==='thread/list')result={data:[],nextCursor:null};if(m.id!=null)process.stdout.write(JSON.stringify({id:m.id,result})+'\\n')});`,
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
      upstream = http.createServer((req, res) => {
        requests++;
        lastCookie = req.headers.cookie;
        if (req.url === '/sse') {
          res.writeHead(200, { 'content-type': 'text/event-stream' });
          res.write('data: first\n\n');
          setTimeout(() => res.end('data: last\n\n'), 300);
          return;
        }
        const chunks = [];
        req.on('data', (c) => chunks.push(c));
        req.on('end', () => {
          res.writeHead(200, { 'content-type': 'application/octet-stream' });
          res.end(req.method === 'POST' ? Buffer.concat(chunks) : Buffer.from('local page'));
        });
      });
      wss = new WebSocketServer({ server: upstream });
      wss.on('connection', (ws) => ws.on('message', (data) => ws.send(data)));
      upstream.listen(0, '127.0.0.1');
      await once(upstream, 'listening');
      const port = upstream.address().port;
      if (nativeOwnershipSupported) assert.ok(await devListenerIdentity(port));
      else
        await assert.rejects(devListenerIdentity(port), { name: 'DevPreviewError', status: 501 });
      service = await startRuntimeServer();
      const hostPort = service.server.address().port,
        endpoint = `http://127.0.0.1:${hostPort}`,
        target = service.session.readingTarget(),
        hostId = service.session.hostId;
      const api = async (path, method = 'GET', body) => {
        const r = await fetch(endpoint + path, {
          method,
          headers: {
            authorization: `Bearer ${token}`,
            ...(body ? { 'content-type': 'application/json' } : {}),
          },
          body: body ? JSON.stringify(body) : undefined,
        });
        const text = await r.text();
        return { status: r.status, data: JSON.parse(text) };
      };
      const workspace = (await api('/v1/workspace')).data;
      const health = (await api('/healthz')).data;
      for (const capability of [
        'preview:http:v1',
        'preview:websocket:v1',
        'preview:streaming:v1',
      ]) {
        assert.equal(workspace.capabilities.includes(capability), nativeOwnershipSupported);
        assert.equal(health.capabilities.includes(capability), nativeOwnershipSupported);
      }
      const thread = (
        await api('/v1/threads', 'POST', {
          hostId,
          projectId: workspace.projects[0].id,
          clientOperationId: randomUUID(),
        })
      ).data;
      const intent = {
        id: randomUUID(),
        target,
        threadId: thread.id,
        url: `http://127.0.0.1:${port}`,
        browserPort: 45678,
        browserSecret: secret,
      };
      if (!nativeOwnershipSupported) {
        const rejected = await api('/v1/dev-previews', 'POST', intent);
        assert.equal(rejected.status, 503, JSON.stringify(rejected));
        assert.equal(requests, 0, 'unsupported ownership must not contact the DEV service');
        return;
      }
      const [a, b] = await Promise.all([
        api('/v1/dev-previews', 'POST', intent),
        api('/v1/dev-previews', 'POST', intent),
      ]);
      assert.equal(a.status, 200, JSON.stringify(a));
      assert.deepEqual(a, b);
      assert.equal(
        (await api('/v1/dev-previews', 'POST', { ...intent, url: 'http://example.com:8080' }))
          .status,
        400,
      );
      assert.equal(
        (await api('/v1/dev-previews', 'POST', { ...intent, browserPort: 45679 })).status,
        409,
      );
      assert.equal(
        (
          await api('/v1/dev-previews', 'POST', {
            ...intent,
            id: randomUUID(),
            url: `http://127.0.0.1:${hostPort}`,
          })
        ).status,
        403,
      );
      assert.notEqual(
        (
          await api('/v1/dev-previews', 'POST', {
            ...intent,
            id: randomUUID(),
            target: { ...target, runtimeId: 'wrong' },
          })
        ).status,
        200,
      );
      const connect = async (extra = {}, headers = {}) => {
        const q = new URLSearchParams({
          hostId,
          id: intent.id,
          target: JSON.stringify(target),
          ...extra,
        });
        const ws = new WebSocket(`ws://127.0.0.1:${hostPort}/v1/dev-previews/connect?${q}`, {
          headers: { authorization: `Bearer ${token}`, ...headers },
        });
        await once(ws, 'open');
        const s = createWebSocketStream(ws);
        streams.add(s);
        s.on('close', () => streams.delete(s));
        s.on('error', () => {});
        return s;
      };
      const raw = async (request) => {
        const s = await connect();
        let data = Buffer.alloc(0);
        s.on('data', (c) => (data = Buffer.concat([data, c])));
        s.write(request);
        await once(s, 'end');
        return data;
      };
      const header = `Host: 127.0.0.1:45678\r\nCookie: __agent_hub_preview=${secret}; other=ok\r\nConnection: close\r\n`;
      assert.match((await raw(`GET / HTTP/1.1\r\n${header}\r\n`)).toString(), /local page/);
      assert.equal(lastCookie, 'other=ok');
      const body = Buffer.from([0, 1, 2, 255]);
      const post = await raw(
        Buffer.concat([
          Buffer.from(`POST /api HTTP/1.1\r\n${header}Content-Length: 4\r\n\r\n`),
          body,
        ]),
      );
      assert.ok(post.includes(body));
      await assert.rejects(connect({}, { origin: 'http://evil' }));
      await assert.rejects(connect({ hostId: 'wrong' }));
      await assert.rejects(connect({ target: JSON.stringify({ ...target, runtimeId: 'wrong' }) }));
      const before = requests;
      assert.match(
        (
          await raw(
            `GET / HTTP/1.1\r\nHost: evil\r\nCookie: __agent_hub_preview=${secret}\r\nConnection: close\r\n\r\n`,
          )
        ).toString(),
        /403/,
      );
      assert.match(
        (await raw(`GET / HTTP/1.1\r\n${header}Origin: http://evil\r\n\r\n`)).toString(),
        /403/,
      );
      assert.match((await raw(`GET http://evil/ HTTP/1.1\r\n${header}\r\n`)).toString(), /403/);
      const connectRaw = await raw(`CONNECT evil:443 HTTP/1.1\r\n${header}\r\n`);
      assert.equal(connectRaw.length, 0);
      assert.equal(requests, before);
      const s = await connect();
      let response = '';
      let early = false;
      s.on('data', (c) => {
        response += c;
        if (response.includes('first') && !response.includes('last')) early = true;
      });
      s.write(`GET /sse HTTP/1.1\r\n${header}\r\n`);
      await once(s, 'end');
      assert.ok(early && response.includes('last'));
      const inner = await connect();
      let bytes = Buffer.alloc(0);
      inner.on('data', (c) => (bytes = Buffer.concat([bytes, c])));
      inner.write(
        `GET /ws HTTP/1.1\r\n${header.replace('Connection: close', 'Connection: Upgrade')}Upgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: ${randomBytes(16).toString('base64')}\r\nOrigin: http://127.0.0.1:45678\r\n\r\n`,
      );
      while (!bytes.includes(Buffer.from('\r\n\r\n'))) await once(inner, 'data');
      assert.match(bytes.toString(), /101 Switching Protocols/);
      bytes = Buffer.alloc(0);
      const mask = Buffer.from([1, 2, 3, 4]),
        payload = Buffer.from('hello');
      inner.write(
        Buffer.concat([
          Buffer.from([0x81, 0x85]),
          mask,
          Buffer.from(payload.map((x, i) => x ^ mask[i % 4])),
        ]),
      );
      while (bytes.length < 7) await once(inner, 'data');
      assert.equal(bytes.subarray(2, 7).toString(), 'hello');
      inner.destroy();
      assert.equal(
        (await api('/v1/dev-previews', 'DELETE', { id: intent.id, target, threadId: 'wrong' }))
          .status,
        404,
      );
      const beforeClose = requests;
      assert.equal(
        (await api('/v1/dev-previews', 'DELETE', { id: intent.id, target, threadId: thread.id }))
          .status,
        200,
      );
      assert.equal((await api('/v1/dev-previews', 'POST', intent)).status, 410);
      await assert.rejects(connect());
      assert.equal(requests, beforeClose);
    } finally {
      for (const s of streams) s.destroy();
      service?.close();
      wss?.close();
      upstream?.closeAllConnections();
      upstream?.close();
      await rm(root, { recursive: true, force: true });
    }
  },
);

test(
  'shared preview engine revokes on expiry and target change with owned-listener fixture',
  { timeout: 10000 },
  async () => {
    const upstream = http.createServer((_req, res) => res.end('ok'));
    upstream.listen(0, '127.0.0.1');
    await once(upstream, 'listening');
    const server = http.createServer();
    let target = {
      hostId: 'host',
      agentId: 'agent',
      workspaceId: 'workspace',
      runtimeId: 'runtime',
    };
    const previews = new DevPreviews({
      target: () => target,
      authorize: () => 'Bearer test',
      scope: async () => {},
      // Exercise shared transport/session rules independently of OS ownership discovery.
      identity: async (port) => `owned-listener-fixture:${port}`,
    });
    previews.attach(server);
    server.listen(0, '127.0.0.1');
    await once(server, 'listening');
    const intent = {
      id: randomUUID(),
      target,
      threadId: 'test-thread',
      url: `http://127.0.0.1:${upstream.address().port}`,
      browserPort: 45678,
      browserSecret: 'a'.repeat(64),
    };
    const connect = async (id) => {
      const q = new URLSearchParams({ hostId: 'host', id, target: JSON.stringify(intent.target) });
      const ws = new WebSocket(
        `ws://127.0.0.1:${server.address().port}/v1/dev-previews/connect?${q}`,
        { headers: { authorization: 'Bearer test' } },
      );
      await once(ws, 'open');
      return ws;
    };
    try {
      const receipt = await previews.create(intent, 'Bearer test');
      const ws = await connect(intent.id);
      const closed = once(ws, 'close');
      target = { ...target, runtimeId: 'changed' };
      await closed;
      await assert.rejects(connect(intent.id));
      target = intent.target;
      const second = { ...intent, id: randomUUID() };
      const r = await previews.create(second, 'Bearer test');
      const ws2 = await connect(second.id);
      const closed2 = once(ws2, 'close');
      r.expiresAt = new Date(0).toISOString();
      await closed2;
      await assert.rejects(connect(second.id));
      assert.ok(receipt.id);
    } finally {
      previews.close();
      server.closeAllConnections();
      server.close();
      upstream.closeAllConnections();
      upstream.close();
    }
  },
);

test(
  'shared HTTPS WSS tunnel forwards wildcard and IPv6 with owned-listener fixture',
  { timeout: 15000 },
  async () => {
    const root = await mkdtemp(join(process.cwd(), '.local/dev-preview-tls-'));
    const servers = [];
    const streams = new Set();
    let previews;
    try {
      const key = join(root, 'key.pem'),
        cert = join(root, 'cert.pem');
      execFileSync(
        'openssl',
        [
          'req',
          '-x509',
          '-newkey',
          'rsa:2048',
          '-nodes',
          '-keyout',
          key,
          '-out',
          cert,
          '-days',
          '1',
          '-subj',
          '/CN=localhost',
          '-addext',
          'subjectAltName=DNS:localhost,IP:127.0.0.1',
        ],
        { stdio: 'ignore' },
      );
      const ca = await readFile(cert);
      const outer = https.createServer({ key: await readFile(key), cert: ca });
      servers.push(outer);
      const target = {
        hostId: 'host',
        agentId: 'agent',
        workspaceId: 'workspace',
        runtimeId: 'runtime',
      };
      previews = new DevPreviews({
        target: () => target,
        authorize: (req) => {
          assert.equal(req.headers.authorization, 'Bearer fixture');
          return 'Bearer fixture';
        },
        scope: async () => {},
        // Native PID/UID ownership is covered by the production Host test on macOS.
        identity: async (port) => `owned-listener-fixture:${port}`,
      });
      previews.attach(outer);
      outer.listen(0, '127.0.0.1');
      await once(outer, 'listening');
      const denied = https.createServer({ key: await readFile(key), cert: ca });
      servers.push(denied);
      previews.attach(denied, () => false);
      denied.listen(0, '127.0.0.1');
      await once(denied, 'listening');
      for (const [bind, advertised] of [
        ['0.0.0.0', '0.0.0.0'],
        ['::1', '[::1]'],
        ['::1', 'localhost'],
      ]) {
        const upstream = http.createServer((_req, res) => res.end(`bound ${bind}`));
        try {
          upstream.listen(0, bind);
          await once(upstream, 'listening');
        } catch (error) {
          if (bind === '::1' && ['EADDRNOTAVAIL', 'EAFNOSUPPORT'].includes(error.code)) {
            console.log('IPv6 loopback unavailable:', error.code);
            continue;
          }
          throw error;
        }
        servers.push(upstream);
        const intent = {
          id: randomUUID(),
          target,
          threadId: 'fixture-thread',
          url: `http://${advertised}:${upstream.address().port}`,
          browserPort: 45678,
          browserSecret: 'b'.repeat(64),
        };
        await previews.create(intent, 'Bearer fixture');
        const query = new URLSearchParams({
          hostId: target.hostId,
          id: intent.id,
          target: JSON.stringify(target),
        });
        const deniedWs = new WebSocket(
          `wss://127.0.0.1:${denied.address().port}/v1/dev-previews/connect?${query}`,
          { ca, headers: { authorization: 'Bearer fixture' } },
        );
        const [deniedResponse] = await once(deniedWs, 'unexpected-response').then((args) => [
          args[1],
        ]);
        assert.equal(deniedResponse.statusCode, 403);
        deniedResponse.resume();
        deniedWs.on('error', () => {});
        const deniedClosed = once(deniedWs, 'close').catch(() => {});
        deniedWs.terminate();
        await deniedClosed;
        const ws = new WebSocket(
          `wss://127.0.0.1:${outer.address().port}/v1/dev-previews/connect?${query}`,
          { ca, headers: { authorization: 'Bearer fixture' } },
        );
        await once(ws, 'open');
        const stream = createWebSocketStream(ws);
        streams.add(stream);
        stream.on('error', () => {});
        let data = '';
        stream.on('data', (chunk) => {
          data += chunk;
        });
        stream.write(
          `GET / HTTP/1.1\r\nHost: 127.0.0.1:45678\r\nCookie: __agent_hub_preview=${intent.browserSecret}\r\nConnection: close\r\n\r\n`,
        );
        await once(stream, 'end');
        assert.match(data, new RegExp(`bound ${bind.replaceAll('.', '\\.')}`));
        console.log(`TLS WSS forwarding passed: ${advertised} -> ${bind}`);
      }
    } finally {
      for (const stream of streams) stream.destroy();
      previews?.close();
      for (const server of servers) {
        server.closeAllConnections();
        server.close();
      }
      await rm(root, { recursive: true, force: true });
    }
  },
);
