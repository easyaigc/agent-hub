import { createServer } from 'node:http';
import { readFile, writeFile } from 'node:fs/promises';
import { readFileSync } from 'node:fs';
import { once } from 'node:events';
const [planPath] = process.argv.slice(2);
const plan = JSON.parse(await readFile(planPath, 'utf8'));
const upstream = new URL(plan.upstreamBaseUrl);
if (
  upstream.protocol !== 'https:' ||
  !['chatgpt.com', 'api.openai.com'].includes(upstream.hostname) ||
  upstream.username ||
  upstream.password
)
  throw Error('Only a fixed official model origin is allowed');
let dropped = 0,
  forwarded = 0;
const mode = () => readFileSync(plan.modeFile, 'utf8').trim();
const report = () =>
  writeFile(
    plan.privateCounterFile,
    JSON.stringify({ droppedConnections: dropped, forwardedRequests: forwarded }),
    { mode: 0o600 },
  );
const server = createServer(async (req, res) => {
  try {
    if (mode() !== 'recover') {
      req.socket.destroy();
      return;
    }
    const path = new URL(req.url, 'http://localhost').pathname;
    if (!(path === '/v1/responses' && req.method === 'POST')) {
      res.writeHead(404).end();
      return;
    }
    const chunks = [];
    let size = 0;
    for await (const bytes of req) {
      size += bytes.length;
      if (size > 8 * 1024 * 1024) throw Error('Acceptance request size limit');
      chunks.push(bytes);
    }
    const body = Buffer.concat(chunks);
    if (!body.includes(Buffer.from(plan.nonce))) {
      res.writeHead(403).end();
      return;
    }
    const headers = {};
    for (const [key, value] of Object.entries(req.headers)) {
      if (
        !['host', 'connection', 'content-length', 'transfer-encoding'].includes(key) &&
        typeof value === 'string'
      )
        headers[key] = value;
    }
    // Authentication headers remain in memory and are never included in diagnostics.
    const result = await fetch(upstream.href.replace(/\/$/, '') + '/responses', {
      method: 'POST',
      headers,
      body,
      redirect: 'error',
      signal: AbortSignal.timeout(90000),
    });
    forwarded++;
    await report();
    const outputHeaders = {};
    for (const [key, value] of result.headers) {
      if (
        ![
          'content-length',
          'content-encoding',
          'transfer-encoding',
          'connection',
          'set-cookie',
        ].includes(key)
      )
        outputHeaders[key] = value;
    }
    res.writeHead(result.status, outputHeaders);
    for await (const chunk of result.body) {
      if (!res.write(chunk)) await once(res, 'drain');
    }
    res.end();
  } catch {
    if (!res.headersSent) res.writeHead(502).end();
    else res.end();
  }
});
server.on('connection', (socket) => {
  if (mode() !== 'recover') {
    dropped++;
    socket.destroy();
    void report();
  }
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
await writeFile(plan.privatePortFile, JSON.stringify({ port: server.address().port }), {
  mode: 0o600,
});
process.on('SIGTERM', () => server.close(() => process.exit(0)));
