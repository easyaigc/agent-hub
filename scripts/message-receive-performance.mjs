import assert from 'node:assert/strict';
import { createServer, request } from 'node:http';
import { createHash } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { gunzipSync } from 'node:zlib';
import { sendReadJSON } from '../apps/host-agent/dist/read-json.js';

// Synthetic text only. Pace actual HTTP response bytes, not a calculated download estimate.
// RTT is applied once per request; loss, radio wake-up, TLS and model reasoning are excluded.
const directory = resolve(
  '.local/message-receive-performance',
  new Date().toISOString().replaceAll(':', '-'),
);
await mkdir(directory, { recursive: true, mode: 0o700 });
const words = [
  '消息',
  '连接',
  '状态',
  '缓存',
  '协议',
  '网络',
  '响应',
  '历史',
  '线程',
  '工具',
  '文件',
  '输入',
  '检查',
  '结果',
  '请求',
  '更新',
];
const snapshots = Object.fromEntries(
  [20, 160].map((count) => {
    const messages = Array.from({ length: count }, (_, index) => {
      const digest = createHash('sha256')
        .update('receive-fixture-' + index)
        .digest();
      const text = Array.from(
        { length: 96 },
        (_, word) =>
          words[digest[word % digest.length] % words.length] +
          ':' +
          ((index * 97 + word * 31) % 99991),
      ).join(' ');
      return {
        id: 'message-' + index,
        role: index % 3 === 0 ? 'tool' : 'assistant',
        text,
        kind: index % 3 === 0 ? 'commandExecution' : 'agentMessage',
        status: 'completed',
      };
    });
    return [
      String(count),
      {
        hostId: 'synthetic-host',
        epoch: 'synthetic-epoch',
        cursor: 100,
        reset: false,
        ready: true,
        events: [
          {
            threadId: 'synthetic-thread',
            snapshot: {
              hostId: 'synthetic-host',
              threadId: 'synthetic-thread',
              messages,
              nextCursor: null,
              runtime: {
                state: 'running',
                turnId: 'synthetic-turn',
                plan: [],
                approvals: [],
                receipts: [],
                canControl: true,
              },
            },
          },
        ],
      },
    ];
  }),
);
const profiles = [
  { name: 'broadband', kbps: 10000, rttMs: 80 },
  { name: 'slow', kbps: 512, rttMs: 250 },
  { name: 'very-slow', kbps: 128, rttMs: 600 },
];
const server = createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const value = snapshots[url.searchParams.get('messages')];
  if (!value) {
    res.writeHead(404).end();
    return;
  }
  void sendReadJSON(req, res, value);
});
await new Promise((done) => server.listen(0, '127.0.0.1', done));
const raw = (path, encoding) =>
  new Promise((done, reject) => {
    const req = request(
      {
        host: '127.0.0.1',
        port: server.address().port,
        path,
        headers: { 'Accept-Encoding': encoding },
      },
      (res) => {
        const chunks = [];
        res.on('data', (chunk) => chunks.push(chunk));
        res.on('end', () => done({ bytes: Buffer.concat(chunks), headers: res.headers }));
        res.on('error', reject);
      },
    );
    req.on('error', reject);
    req.end();
  });
const proxy = createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const profile = profiles.find((entry) => entry.name === url.searchParams.get('profile'));
  await delay(profile.rttMs / 2);
  const upstream = await raw(
    '/?messages=' + url.searchParams.get('messages'),
    req.headers['accept-encoding'],
  );
  await delay(profile.rttMs / 2);
  res.writeHead(200, upstream.headers);
  const started = performance.now();
  const bytesPerSecond = (profile.kbps * 1000) / 8;
  const chunkSize = Math.max(1, Math.floor(bytesPerSecond * 0.02));
  for (let offset = 0; offset < upstream.bytes.length; offset += chunkSize) {
    if (res.destroyed) return;
    const end = Math.min(offset + chunkSize, upstream.bytes.length);
    await delay(Math.max(0, (end / bytesPerSecond) * 1000 - (performance.now() - started)));
    res.write(upstream.bytes.subarray(offset, end));
  }
  res.end();
});
await new Promise((done) => proxy.listen(0, '127.0.0.1', done));
const report = {
  kind: 'synthetic-paced-http',
  excludes: ['model reasoning', 'TLS handshake', 'packet loss', 'iPhone UI rendering'],
  rows: [],
};
try {
  for (const count of [20, 160])
    for (const profile of profiles)
      for (const encoding of ['identity', 'gzip']) {
        const started = performance.now();
        const result = await new Promise((done, reject) => {
          const req = request(
            {
              host: '127.0.0.1',
              port: proxy.address().port,
              path: '/?messages=' + count + '&profile=' + profile.name,
              headers: { 'Accept-Encoding': encoding },
            },
            (res) => {
              const chunks = [];
              let firstByteMs;
              res.on('data', (chunk) => {
                firstByteMs ??= performance.now() - started;
                chunks.push(chunk);
              });
              res.on('end', () =>
                done({ bytes: Buffer.concat(chunks), headers: res.headers, firstByteMs }),
              );
              res.on('error', reject);
            },
          );
          req.on('error', reject);
          req.end();
        });
        const downloadedMs = performance.now() - started;
        const decoded =
          result.headers['content-encoding'] === 'gzip' ? gunzipSync(result.bytes) : result.bytes;
        assert.deepEqual(JSON.parse(decoded), snapshots[String(count)]);
        const row = {
          messages: count,
          profile: profile.name,
          kbps: profile.kbps,
          rttMs: profile.rttMs,
          encoding,
          wireBytes: result.bytes.length,
          firstByteMs: Math.round(result.firstByteMs),
          receivedMs: Math.round(downloadedMs),
          decodeMs: +(performance.now() - started - downloadedMs).toFixed(2),
        };
        report.rows.push(row);
        console.log(JSON.stringify(row));
      }
  await writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
  console.log('Report: ' + join(directory, 'report.json'));
} finally {
  await Promise.all([
    new Promise((done) => proxy.close(done)),
    new Promise((done) => server.close(done)),
  ]);
}
