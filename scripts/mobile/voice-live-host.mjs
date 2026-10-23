import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { startRuntimeServer } from '../../apps/host-agent/dist/runtime-server.js';

assert(process.send && process.env.HOST_AGENT_STATE_DIR?.includes('/UM04/live-'));
const root = resolve(process.env.VOICE_LIVE_EVIDENCE ?? '');
assert(root.includes('/.codex/acceptance-archives/agent-hub/UM05/voice-live-'));
await mkdir(join(root, 'http'), { recursive: true, mode: 0o700 });
const host = await startRuntimeServer();
assert(host.direct);
host.direct.server.on('request', (req, res) => {
  const id = randomUUID(),
    url = new URL(req.url, 'http://localhost');
  const record = {
    id,
    method: req.method,
    path: url.pathname,
    query: url.search,
    receivedAtUTC: new Date().toISOString(),
    authorizationPresent: !!req.headers.authorization,
    authorizationSha256: req.headers.authorization
      ? createHash('sha256')
          .update(req.headers.authorization.replace(/^Bearer /, ''))
          .digest('hex')
      : null,
  };
  process.send?.({ type: 'http', record });
  const requestHash = createHash('sha256'),
    responseHash = createHash('sha256');
  let requestBytes = 0,
    responseBytes = 0,
    requestEnded = false,
    saved = false;
  const response = [],
    voice = url.pathname.startsWith('/v1/voice/');
  // Observe delivery to the existing consumer; adding a data listener would drain an authenticated upload too early.
  const emit = req.emit;
  req.emit = function (event, ...args) {
    if (event === 'data') {
      requestBytes += args[0].length;
      requestHash.update(args[0]);
    }
    if (event === 'end') requestEnded = true;
    return emit.call(this, event, ...args);
  };
  const capture = (value, encoding) => {
    if (value == null || typeof value === 'function') return;
    const bytes =
      typeof value === 'string'
        ? Buffer.from(value, typeof encoding === 'string' ? encoding : undefined)
        : Buffer.from(value);
    responseBytes += bytes.length;
    responseHash.update(bytes);
    if (voice && responseBytes <= 2 * 1024 * 1024) response.push(bytes);
  };
  const write = res.write,
    end = res.end;
  res.write = function (...args) {
    capture(args[0], args[1]);
    return write.apply(this, args);
  };
  res.end = function (...args) {
    capture(args[0], args[1]);
    return end.apply(this, args);
  };
  const finish = async (completed) => {
    if (saved) return;
    saved = true;
    const value = {
      ...record,
      completed,
      requestEnded,
      requestBytes,
      requestSha256: requestHash.digest('hex'),
      status: res.statusCode,
      responseBytes,
      responseSha256: responseHash.digest('hex'),
      finishedAtUTC: new Date().toISOString(),
      fullResponseCaptured: voice && responseBytes <= 2 * 1024 * 1024,
    };
    if (value.fullResponseCaptured)
      await writeFile(join(root, 'http', id + '.response'), Buffer.concat(response), {
        flag: 'wx',
        mode: 0o600,
      });
    await writeFile(join(root, 'http', id + '.json'), JSON.stringify(value, null, 2), {
      flag: 'wx',
      mode: 0o600,
    });
    process.send?.({ type: 'http-completed', record: value });
  };
  res.once('finish', () => {
    void finish(true);
  });
  res.once('close', () => {
    void finish(false);
  });
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
