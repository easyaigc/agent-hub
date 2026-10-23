import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { startRuntimeServer } from '../../apps/host-agent/dist/runtime-server.js';

// Only the owned acceptance parent can launch this process. No Codex execution process is created.
assert(process.send && process.env.HOST_AGENT_STATE_DIR?.includes('/UM04/live-'));
const host = await startRuntimeServer();
assert(host.direct);
host.direct.server.on('tlsClientError', (error, socket) => {
  process.send?.({
    type: 'tls-error',
    code: error.code,
    message: error.message,
    remoteAddress: socket.remoteAddress,
  });
});
const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
host.direct.server.on('request', (req, res) => {
  const id = randomUUID(),
    url = new URL(req.url, 'http://localhost');
  const record = {
    id,
    method: req.method,
    path: url.pathname,
    remoteAddress: req.socket.remoteAddress,
    authorizationPresent: !!req.headers.authorization,
    ...(req.headers.authorization
      ? { authorizationSha256: hash(req.headers.authorization.replace(/^Bearer /, '')) }
      : {}),
  };
  const chunks = [];
  let count = 0;
  req.on('data', (chunk) => {
    count += chunk.length;
    if (count <= 16384) chunks.push(chunk);
  });
  req.on('end', () => {
    if (chunks.length && ['/connection/claim', '/v1/replies'].includes(url.pathname)) {
      const body = Buffer.concat(chunks);
      record.bodySha256 = hash(body);
      record.body = JSON.parse(body.toString());
    }
  });
  res.once('finish', () =>
    process.send?.({ type: 'http', record: { ...record, status: res.statusCode } }),
  );
});
process.send({
  type: 'ready',
  pid: process.pid,
  port: host.direct.server.address().port,
  descriptor: host.direct.descriptor(),
});
let closing = false;
const close = () => {
  if (closing) return;
  closing = true;
  host.close();
  if (process.connected) process.disconnect();
};
process.once('disconnect', close);
process.removeListener('SIGTERM', host.close);
process.once('SIGTERM', close);
