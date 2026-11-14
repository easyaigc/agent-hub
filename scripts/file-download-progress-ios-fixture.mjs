import { createServer } from 'node:http';
import { createHash, randomBytes } from 'node:crypto';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';
import Ajv from 'ajv';
import { execFile } from 'node:child_process';

// Dedicated loopback fixture; never connects to a real desktop or account.
const token = randomBytes(24).toString('hex');
const hostId = 'file-progress-fixture',
  threadId = 'file-progress-thread';
const objects = [
  '<< /Type /Catalog /Pages 2 0 R >>',
  '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
  '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>',
  '<< /Length 48 >>\nstream\nBT /F1 18 Tf 30 150 Td (Progress fixture) Tj ET\nendstream',
  '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
];
let pdf = '%PDF-1.4\n';
const offsets = [0];
for (const [index, object] of objects.entries()) {
  offsets.push(Buffer.byteLength(pdf));
  pdf += `${index + 1} 0 obj\n${object}\nendobj\n`;
}
const xref = Buffer.byteLength(pdf);
pdf += `xref\n0 6\n0000000000 65535 f \n${offsets
  .slice(1)
  .map((offset) => String(offset).padStart(10, '0') + ' 00000 n \n')
  .join('')}trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n%`;
const content = Buffer.concat([
  Buffer.from(pdf),
  Buffer.alloc(5_500_000 - Buffer.byteLength(pdf), 65),
]);
const file = {
  id: 'file-progress',
  hostId,
  threadId,
  name: 'progress.pdf',
  mediaType: 'application/pdf',
  sizeBytes: content.length,
  sha256: createHash('sha256').update(content).digest('hex'),
};
const thread = {
  id: threadId,
  hostId,
  projectId: 'progress-project',
  title: 'Progress fixture',
  state: 'idle',
};
const history = {
  hostId,
  threadId,
  thread,
  messages: [
    {
      id: 'progress-message',
      role: 'assistant',
      text: 'PROGRESS_FIXTURE',
      files: [],
      attachments: [file],
    },
  ],
  nextCursor: null,
  runtime: { state: 'idle', plan: [], approvals: [], receipts: [], canControl: false },
};
const schema = JSON.parse(
  readFileSync('packages/protocol/schemas/codex-mobile-http-v1.schema.json', 'utf8'),
);
const validate = new Ajv({ strict: false }).compile({ ...schema, $ref: '#/definitions/history' });
assert(validate(history), JSON.stringify(validate.errors));
let attempts = 0;
const json = (res, status, value) =>
  res.writeHead(status, { 'Content-Type': 'application/json' }).end(JSON.stringify(value));
const server = createServer((req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1:18814');
  if (url.pathname === '/')
    return json(res, 200, {
      endpoint: 'http://127.0.0.1:18814',
      token,
      hostId,
      threadId,
      threadTitle: thread.title,
      nonce: 'progress',
      secondEndpoint: '',
      secondToken: '',
      secondHostId: '',
      delayedEndpoint: '',
    });
  if (req.headers.authorization !== `Bearer ${token}`)
    return json(res, 401, { error: 'unauthorized' });
  if (url.pathname === '/v1/workspace')
    return json(res, 200, {
      hostId,
      name: 'Progress fixture',
      connectionMode: 'history-only',
      capabilities: ['projects:read', 'threads:read', 'history:read'],
      projects: [{ id: 'progress-project', hostId, name: 'Progress project', roots: [] }],
      threads: [thread],
      nextProjectCursor: null,
      nextThreadCursor: null,
    });
  if (url.pathname === '/v1/history') return json(res, 200, history);
  if (url.pathname === '/v1/attachments/resolve') return json(res, 404, { error: 'legacy' });
  if (url.pathname === '/v1/attachments') {
    if (++attempts === 1) return json(res, 403, { error: 'fixture-denied' });
    res.writeHead(200, { 'Content-Length': content.length });
    setTimeout(
      () =>
        execFile(
          'xcrun',
          [
            'simctl',
            'io',
            '2FECBBE8-3CFE-4238-8DD5-945E0C0E8979',
            'screenshot',
            '.local/file-download-progress-20261114/intermediate-card.png',
          ],
          () => {},
        ),
      4000,
    );
    let offset = 0;
    const timer = setInterval(() => {
      if (res.destroyed || offset >= content.length) {
        clearInterval(timer);
        if (!res.destroyed) res.end();
        return;
      }
      res.write(content.subarray(offset, offset + 65536));
      offset += 65536;
    }, 350);
    return;
  }
  return json(res, 404, { error: 'unsupported' });
});
server.listen(18814, '127.0.0.1', () => console.log('fixture ready'));
process.on('SIGTERM', () => {
  server.closeAllConnections();
  server.close();
});
