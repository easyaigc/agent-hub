import assert from 'node:assert/strict';
import { createServer, request } from 'node:http';
import { gunzipSync } from 'node:zlib';
import { sendReadJSON } from '../apps/host-agent/dist/read-json.js';

const large = { messages: [{ id: 'message', text: '慢网正文与工具输出\n'.repeat(4000) }] };
const server = createServer((req, res) => {
  void sendReadJSON(req, res, req.url === '/small' ? { ready: true } : large);
});
await new Promise((done) => server.listen(0, '127.0.0.1', done));
const read = (encoding, path = '/') =>
  new Promise((done, reject) => {
    const req = request(
      {
        host: '127.0.0.1',
        port: server.address().port,
        path,
        headers: encoding === undefined ? {} : { 'Accept-Encoding': encoding },
      },
      (res) => {
        const chunks = [];
        res.on('data', (chunk) => chunks.push(chunk));
        res.on('end', () => done({ headers: res.headers, bytes: Buffer.concat(chunks) }));
        res.on('error', reject);
      },
    );
    req.on('error', reject);
    req.end();
  });
try {
  for (const header of [undefined, 'identity', 'br', 'gzip;q=0', '*;q=1, gzip;q=0', 'gzip;q=bad']) {
    const result = await read(header);
    assert.equal(result.headers['content-encoding'], undefined, String(header));
    assert.deepEqual(JSON.parse(result.bytes), large);
  }
  for (const header of ['gzip', 'br, GZip; q=0.5', 'gzip ; q=1', '*;q=0.7']) {
    const result = await read(header);
    assert.equal(result.headers['content-encoding'], 'gzip');
    assert.equal(Number(result.headers['content-length']), result.bytes.length);
    assert.equal(result.headers.vary, 'Accept-Encoding');
    assert.equal(result.headers['cache-control'], 'no-store');
    assert.deepEqual(JSON.parse(gunzipSync(result.bytes)), large);
    assert(result.bytes.length < Buffer.byteLength(JSON.stringify(large)) / 10);
  }
  const small = await read('gzip', '/small');
  assert.equal(small.headers['content-encoding'], undefined, 'tiny heartbeats avoid compression');
  assert.deepEqual(JSON.parse(small.bytes), { ready: true });
  // Disconnecting while asynchronous compression runs must not write a late response.
  await new Promise((done) => {
    const req = request({
      host: '127.0.0.1',
      port: server.address().port,
      headers: { 'Accept-Encoding': 'gzip' },
    });
    req.on('error', () => {});
    req.on('close', done);
    server.once('request', () => req.destroy());
    req.end();
  });
  console.log(
    'Read JSON negotiation, exact decoded content, small responses and disconnect passed.',
  );
} finally {
  await new Promise((done) => server.close(done));
}
