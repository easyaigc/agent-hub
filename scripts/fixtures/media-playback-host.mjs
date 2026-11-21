import { createServer, request as httpRequest } from 'node:http';
import { createServer as createSecureServer } from 'node:https';
import { randomBytes, createHash, X509Certificate } from 'node:crypto';
import { createSecureContext } from 'node:tls';
import { isIP } from 'node:net';
import { createReadStream } from 'node:fs';
import { stat, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { RuntimeSession } from '../../packages/codex-adapter/dist/runtime-session.js';
import { startRuntimeServer } from '../../apps/host-agent/dist/runtime-server.js';

// History is synthetic; attachment bytes, authorization, preparation and Range are production.
export async function startPlaybackHost(
  dir,
  video,
  { bindAddress = '127.0.0.1', advertisedAddress = bindAddress, tls } = {},
) {
  if (isIP(bindAddress) !== 4 || bindAddress === '0.0.0.0' || bindAddress !== advertisedAddress)
    throw new Error('Fixture requires an explicit matching IPv4 bind and advertised address');
  if (tls && (!Buffer.isBuffer(tls.key) || !Buffer.isBuffer(tls.cert)))
    throw new Error('Fixture TLS requires task-owned key and certificate buffers');
  const tlsSettings = tls ? { key: tls.key, cert: tls.cert, minVersion: 'TLSv1.2' } : undefined;
  // Reject invalid material before starting the production upstream.
  if (tlsSettings) createSecureContext(tlsSettings);
  const fingerprint = tlsSettings
    ? createHash('sha256').update(new X509Certificate(tlsSettings.cert).raw).digest('hex')
    : undefined;
  const token = randomBytes(32).toString('base64url');
  Object.assign(process.env, {
    CODEX_RUNTIME_MODE: 'managed-runtime',
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: '0',
    HOST_AGENT_STATE_DIR: join(dir, 'host-state'),
    HOST_AGENT_ROOT: dir,
    HOST_AGENT_TOKEN: token,
    HOST_AGENT_TRANSFER_DIR: join(dir, 'transfers'),
    HOST_AGENT_DISCOVERY: '0',
    HOST_AGENT_DISCOVERY_BONJOUR: '0',
    HOST_AGENT_ATTACHMENT_RANGE_READ: '1',
  });
  const previous = RuntimeSession.prototype.connect;
  RuntimeSession.prototype.connect = async function () {};
  let production;
  try {
    production = await startRuntimeServer();
  } finally {
    RuntimeSession.prototype.connect = previous;
  }
  const session = production.session,
    hostId = session.hostId,
    threadId = 'playback-thread',
    messageId = 'playback-message';
  const target = session.readingTarget();
  const hash = createHash('sha256');
  for await (const b of createReadStream(video)) hash.update(b);
  const sha256 = hash.digest('hex'),
    size = (await stat(video)).size;
  const attachment = {
    id: 'video.mp4',
    hostId,
    threadId,
    name: 'video.mp4',
    mediaType: 'video/mp4',
    sizeBytes: size,
    sha256,
  };
  const documentBytes = Buffer.from(
    '# Synthetic document\n\n[Document video](/fixture/video.mp4)\n',
  );
  const documentPath = join(dir, 'document.md');
  await writeFile(documentPath, documentBytes, { mode: 0o600 });
  const documentAttachment = {
    id: 'document.md',
    hostId,
    threadId,
    name: 'document.md',
    mediaType: 'text/markdown',
    sizeBytes: documentBytes.length,
    sha256: createHash('sha256').update(documentBytes).digest('hex'),
  };
  session.state.threads.push({
    id: threadId,
    title: 'Playback Fixture',
    projectId: null,
    messages: [],
    plan: [],
    turnId: null,
    error: null,
  });
  session.state.attachments.push({ ...attachment, path: video });
  session.state.attachments.push({ ...documentAttachment, path: documentPath });
  const thread = {
    id: threadId,
    hostId,
    projectId: 'playback-project',
    title: 'Playback Fixture',
    state: 'idle',
  };
  const otherThreadId = 'playback-other-thread';
  const otherThread = {
    id: otherThreadId,
    hostId,
    projectId: 'playback-project',
    title: 'Playback Other',
    state: 'idle',
  };
  const capabilities = ['history:read', 'events'];
  const history = {
    readTarget: target,
    hostId,
    threadId,
    thread,
    nextCursor: null,
    messages: [
      {
        id: messageId,
        role: 'assistant',
        text: '![视频](/fixture/video.mp4)',
        status: 'completed',
        files: [],
      },
    ],
    runtime: {
      state: 'idle',
      turnId: null,
      plan: [],
      receipts: [],
      canControl: false,
      approvals: [],
    },
  };
  const otherHistory = {
    ...history,
    threadId: otherThreadId,
    thread: otherThread,
    messages: [
      {
        id: 'other-message',
        role: 'assistant',
        text: 'PLAYBACK_OTHER_CONTEXT',
        status: 'completed',
        files: [],
      },
    ],
  };
  const counts = {
    mediaRequests: 0,
    mediaBytes: 0,
    mediaSize: size,
    seekRanges: 0,
    cancellations: 0,
    activeMediaResponses: 0,
    preparations: 0,
    rangeRequests: 0,
    fullRequests: 0,
    forbiddenResponses: 0,
  };
  const requests = [];
  const readyReadIds = new Set();
  let legacy = false,
    forbidMedia = false,
    leaseMode = 'off',
    entry = 'inline';
  const json = (res, value, status = 200) =>
    res
      .writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' })
      .end(JSON.stringify(value));
  let endpoint;
  const descriptor = () => ({
    version: 1,
    agentId: 'codex',
    name: 'Playback fixture',
    target,
    fingerprint,
    endpoints: [endpoint],
  });
  const serve = (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    if (req.headers.authorization !== 'Bearer ' + token)
      return json(res, { error: 'Unauthorized' }, 401);
    if (url.pathname === '/host' && tlsSettings) return json(res, descriptor());
    if (url.pathname === '/counts') return json(res, counts);
    if (url.pathname === '/fixture-requests') return json(res, requests);
    if (url.pathname === '/fixture-lease' && req.method === 'POST') {
      const mode = url.searchParams.get('mode');
      if (!['short', 'renew-forbidden', 'renew-version'].includes(mode))
        return json(res, { error: 'invalid lease mode' }, 400);
      leaseMode = mode;
      return json(res, { ok: true, mode });
    }
    if (url.pathname === '/fixture-legacy' && req.method === 'POST') {
      legacy = true;
      return json(res, { ok: true });
    }
    if (url.pathname === '/fixture-forbidden' && req.method === 'POST') {
      forbidMedia = url.searchParams.get('enabled') === '1';
      if (forbidMedia) legacy = false;
      return json(res, { ok: true });
    }
    if (url.pathname === '/fixture-entry' && req.method === 'POST') {
      const next = url.searchParams.get('case');
      if (!['inline', 'link', 'attachment', 'document'].includes(next))
        return json(res, { error: 'invalid entry' }, 400);
      entry = next;
      legacy = false;
      forbidMedia = false;
      if (entry === 'attachment') {
        attachment.mediaType = 'application/octet-stream';
        const registered = session.state.attachments.find(
          (item) => item.id === attachment.id && item.threadId === threadId,
        );
        if (!registered) return json(res, { error: 'missing registered attachment' }, 500);
        registered.mediaType = attachment.mediaType;
      }
      history.messages = [
        {
          id: messageId,
          role: 'assistant',
          text:
            entry === 'inline'
              ? '![视频](/fixture/video.mp4)'
              : entry === 'link'
                ? '[Ordinary video link](/fixture/video.mp4)'
                : 'Synthetic attachment',
          status: 'completed',
          files: [],
          attachments:
            entry === 'attachment'
              ? [attachment]
              : entry === 'document'
                ? [documentAttachment]
                : [],
        },
      ];
      return json(res, {
        ok: true,
        entry,
        mediaType: entry === 'attachment' ? attachment.mediaType : null,
      });
    }
    if (url.pathname === '/healthz' && legacy) return json(res, { capabilities: [] });
    if (url.pathname === '/v1/workspace')
      return json(res, {
        hostId,
        name: 'Playback fixture',
        connectionMode: 'desktop-runtime',
        readTarget: target,
        clientType: { id: 'codex', name: 'Codex' },
        capabilities,
        projects: [{ id: 'playback-project', hostId, name: 'Fixture', roots: [] }],
        threads: [thread, otherThread],
        nextProjectCursor: null,
        nextThreadCursor: null,
      });
    if (url.pathname === '/v1/models') return json(res, { models: [] });
    if (url.pathname === '/v1/history')
      return json(res, url.searchParams.get('threadId') === otherThreadId ? otherHistory : history);
    if (url.pathname === '/v1/events')
      return setTimeout(
        () =>
          json(res, {
            hostId,
            epoch: 'playback-fixture',
            cursor: 1,
            reset: false,
            ready: true,
            capabilities,
            events:
              Number(url.searchParams.get('after') ?? -1) < 1
                ? [{ threadId, snapshot: history }]
                : [],
          }),
        200,
      );
    if (url.pathname === '/v1/threads/read') return json(res, { ok: true });
    if (url.pathname === '/v1/attachments/resolve-link') {
      if (
        url.searchParams.get('hostId') !== hostId ||
        url.searchParams.get('threadId') !== threadId ||
        url.searchParams.get('messageId') !== messageId ||
        url.searchParams.get('reference') !== '/fixture/video.mp4'
      )
        return json(res, { error: 'invalid fixture scope' }, 403);
      return json(res, {
        hostId,
        threadId,
        messageId,
        reference: '/fixture/video.mp4',
        readTarget: target,
        attachment,
      });
    }
    if (url.pathname === '/v1/attachments/resolve-document-link') {
      const reference = url.searchParams.get('reference');
      if (
        url.searchParams.get('hostId') !== hostId ||
        url.searchParams.get('threadId') !== threadId ||
        url.searchParams.get('sourceAttachmentId') !== documentAttachment.id ||
        url.searchParams.get('sourceSha256') !== documentAttachment.sha256 ||
        reference !== '/fixture/video.mp4'
      )
        return json(res, { error: 'invalid document scope' }, 403);
      return json(res, {
        hostId,
        threadId,
        sourceAttachmentId: documentAttachment.id,
        reference,
        readTarget: target,
        attachment,
      });
    }
    if (url.pathname === '/v1/attachments/resolve' && req.method === 'DELETE')
      counts.cancellations++;
    if (url.pathname === '/v1/attachments/resolve' && req.method === 'GET') counts.preparations++;
    const isMedia = url.pathname === '/v1/attachments' && req.method === 'GET';
    const row = {
      at: new Date().toISOString(),
      method: req.method,
      path: url.pathname,
      range: req.headers.range ?? null,
      status: null,
      bytes: 0,
      readId: url.searchParams.get('readId'),
    };
    const isPrepare =
      url.pathname === '/v1/attachments/resolve' &&
      req.method === 'GET' &&
      url.searchParams.get('read') === 'range-v1';
    if (
      isPrepare &&
      leaseMode === 'renew-forbidden' &&
      readyReadIds.size > 0 &&
      !readyReadIds.has(row.readId)
    ) {
      row.status = 403;
      row.injected = 'renew-forbidden';
      requests.push(row);
      return json(res, { error: 'synthetic renewal forbidden' }, 403);
    }
    if (isMedia) {
      counts.mediaRequests++;
      if (req.headers.range) counts.rangeRequests++;
      else counts.fullRequests++;
      const start = /^bytes=(\d+)-/.exec(req.headers.range ?? '');
      if (start && Number(start[1]) >= size * 0.65) counts.seekRanges++;
      if (forbidMedia) {
        row.status = 403;
        counts.forbiddenResponses++;
        requests.push(row);
        return json(res, { error: 'synthetic forbidden' }, 403);
      }
      counts.activeMediaResponses++;
    }
    requests.push(row);
    const upstream = httpRequest(
      {
        host: '127.0.0.1',
        port: production.server.address().port,
        path: req.url,
        method: req.method,
        headers: { ...req.headers, host: '127.0.0.1' },
      },
      (response) => {
        row.status = response.statusCode;
        row.contentRange = response.headers['content-range'] ?? null;
        if (isPrepare && leaseMode !== 'off' && response.statusCode === 200) {
          const chunks = [];
          response.on('data', (chunk) => chunks.push(chunk));
          response.on('end', () => {
            try {
              const value = JSON.parse(Buffer.concat(chunks).toString('utf8'));
              row.phase = value.phase;
              if (value.phase === 'ready') {
                const isRenewal = readyReadIds.size > 0 && !readyReadIds.has(row.readId);
                if (leaseMode === 'renew-version' && isRenewal) {
                  const changed =
                    value.attachment.sha256 === '0'.repeat(64) ? '1'.repeat(64) : '0'.repeat(64);
                  value.attachment.sha256 = changed;
                  value.etag = '"' + changed + '"';
                  row.injected = 'renew-version';
                }
                value.remainingSeconds = 6;
                value.expiresAt = new Date(Date.now() + 6000).toISOString();
                row.etag = value.etag;
                row.fixtureRemainingSeconds = value.remainingSeconds;
                readyReadIds.add(row.readId);
              }
              json(res, value);
            } catch (error) {
              row.error = error.message;
              json(res, { error: 'invalid fixture preparation response' }, 502);
            }
          });
          return;
        }
        res.writeHead(response.statusCode, response.headers);
        const fullHash =
          isMedia && !req.headers.range && response.statusCode === 200
            ? createHash('sha256')
            : null;
        response.on('data', (chunk) => {
          if (isMedia) {
            row.bytes += chunk.length;
            counts.mediaBytes += chunk.length;
            fullHash?.update(chunk);
          }
        });
        response.on('end', () => {
          if (fullHash) row.sha256 = fullHash.digest('hex');
        });
        response.pipe(res);
      },
    );
    let finished = false;
    const finish = () => {
      if (finished) return;
      finished = true;
      if (isMedia) counts.activeMediaResponses--;
    };
    res.on('close', () => {
      finish();
      if (!res.writableEnded) upstream.destroy();
    });
    upstream.on('error', (e) => {
      row.error = e.message;
      finish();
      if (!res.headersSent) json(res, { error: e.message }, 502);
      else res.destroy();
    });
    req.pipe(upstream);
  };
  const server = tlsSettings ? createSecureServer(tlsSettings, serve) : createServer(serve);
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, bindAddress, resolve);
  });
  endpoint =
    (tlsSettings ? 'https://' : 'http://') + advertisedAddress + ':' + server.address().port;
  return {
    fixture: {
      endpoint,
      token,
      hostId,
      threadId,
      threadTitle: thread.title,
      nonce: 'synthetic',
      secondEndpoint: endpoint,
      secondToken: token,
      secondHostId: hostId,
      delayedEndpoint: endpoint,
      expectedMediaSha256: sha256,
      expectedMediaSize: size,
      ...(tlsSettings
        ? {
            certificateFingerprint: fingerprint,
            pairingURI:
              'codex-host://pair?descriptor=' +
              Buffer.from(JSON.stringify({ ...descriptor(), token })).toString('base64url'),
          }
        : {}),
    },
    counts,
    requests,
    sha256,
    size,
    production,
    async close() {
      await new Promise((r) => server.close(r));
      await production.attachmentReads.close();
      production.close();
    },
  };
}
