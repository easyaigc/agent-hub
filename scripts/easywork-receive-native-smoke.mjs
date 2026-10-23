import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { createHash, createCipheriv } from 'node:crypto';
import { createServer, request } from 'node:http';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

if (process.platform !== 'darwin') {
  console.log('Native EasyWork receive checks require macOS Swift; not verified here.');
  process.exit(0);
}
const exec = promisify(execFile);
const environment = {
  ...process.env,
  DEVELOPER_DIR: process.env.DEVELOPER_DIR ?? '/Applications/Xcode.app/Contents/Developer',
};
const directory = resolve(
  '.local/easywork-receive-performance',
  new Date().toISOString().replaceAll(':', '-'),
);
await mkdir(directory, { recursive: true, mode: 0o700 });
const argument = (name) => {
  const at = process.argv.indexOf(name);
  return at < 0 ? undefined : process.argv[at + 1];
};
const sha = (text) => createHash('sha256').update(text).digest('hex');
// Stable protocol fixture. --easywork-root substitutes the actual desktop producer unchanged.
let producer = (messages, known, reset) => {
  const previous = new Map(known.map((entry) => [entry.id, entry]));
  const head = [],
    changed = [],
    patches = [];
  for (const message of messages) {
    const text =
      message.content.length === 1 && message.content[0].type === 'text'
        ? message.content[0].text
        : undefined;
    const version = {
      id: message.id,
      hash: sha(JSON.stringify(message)),
      ...(text === undefined
        ? {}
        : {
            textLength: text.length,
            textHash: sha(text),
            shapeHash: sha(
              JSON.stringify({
                ...message,
                status: undefined,
                content: [{ ...message.content[0], text: '' }],
              }),
            ),
          }),
    };
    head.push(version);
    const old = previous.get(message.id);
    if (!reset && old?.hash === version.hash) continue;
    if (
      !reset &&
      text !== undefined &&
      old?.shapeHash === version.shapeHash &&
      old.textLength <= text.length &&
      sha(text.slice(0, old.textLength)) === old.textHash
    )
      patches.push({
        id: message.id,
        baseHash: old.hash,
        append: text.slice(old.textLength),
        status: message.status,
      });
    else changed.push(message);
  }
  return { head, messages: changed, patches };
};
const desktop = argument('--easywork-root');
let producerSha256;
if (desktop) {
  const source = resolve(desktop, 'apps/desktop/src/main/remote-access/remote-thread-delta.ts');
  producer = (await import(pathToFileURL(source))).buildRemoteMessageDelta;
  producerSha256 = sha(await readFile(source));
}
const clone = (value) => structuredClone(value);
const base = [
  {
    id: 'assistant',
    role: 'assistant',
    status: 'partial',
    content: [{ type: 'text', text: '中文 😀 é\n首段' }],
  },
  { id: 'tool', role: 'tool', content: [{ type: 'tool-call', data: { result: 'output' } }] },
];
let previous;
const fixtures = [];
const result = (messages, reset = false, revision = 1, known = previous?.head ?? []) => ({
  snapshot: { thread: { id: 'thread' }, activeRun: null },
  branchRevision: revision,
  reset,
  ...producer(messages, known, reset),
  hasEarlier: false,
});
const step = (name, messages, reset = false, revision = 1, known = previous?.head ?? []) => {
  const value = result(messages, reset, revision, known);
  fixtures.push({ name, result: clone(value), expectedMessages: clone(messages) });
  previous = value;
  return value;
};
step('cold', base);
step('unchanged', base);
base[0].content[0].text += '\n追加 🚀 中文';
step('unicode append', base);
base[0].status = 'complete';
step('status only', base);
base[0].content[0].text = '编辑后的完整正文';
step('full replacement', base);
const beforeReset = previous;
const broken = result([
  { ...base[0], content: [{ type: 'text', text: base[0].content[0].text + 'tail' }] },
  base[1],
]);
broken.patches[0].baseHash = 'wrong-base';
fixtures.push({ name: 'stale patch', result: broken, shouldFail: true });
fixtures.push({
  name: 'unknown cache base',
  scope: 'other-target',
  result: result(base),
  shouldFail: true,
});
fixtures.push({
  name: 'wrong thread',
  result: { ...result(base), snapshot: { thread: { id: 'another' } } },
  shouldFail: true,
});
step('full repair', base, false, 1, []);
base.splice(1, 1);
step('head removal and branch reset', base, true, 2);
fixtures.push({
  name: 'branch changed without reset',
  result: { ...result(base), branchRevision: 3 },
  shouldFail: true,
});
const badText = result(base, false, 2);
badText.head[0].textLength++;
fixtures.push({ name: 'invalid UTF16 length', result: badText, shouldFail: true });
fixtures.push({
  name: 'legacy full peer',
  scope: 'legacy',
  result: { snapshot: { thread: { id: 'thread' } }, messages: clone(base) },
  expectedMessages: clone(base),
});
base.push({
  id: 'active-run:run',
  role: 'assistant',
  status: 'partial',
  content: [{ type: 'text', text: '正在生成' }],
});
step('ephemeral draft appears', base, false, 2);
base[1] = {
  ...base[1],
  id: 'persisted-final',
  status: 'complete',
  content: [{ type: 'text', text: '真实完整终态和文件链接' }],
};
step('persisted message replaces draft without duplicate', base, false, 2);
assert(beforeReset.head.length === 2);
const sharedVectorBytes = await readFile(
  'packages/protocol/fixtures/mobile/easywork-thread-sync-v1.json',
);
const sharedVectors = JSON.parse(sharedVectorBytes);
assert.equal(sharedVectors.version, 1);
if (desktop) {
  assert.equal(
    sharedVectors.producerSha256,
    producerSha256,
    'Freeze a new vector version when the actual desktop producer changes',
  );
  assert.deepEqual(
    fixtures,
    sharedVectors.cases,
    'Both native consumers must use the same frozen actual-producer vectors',
  );
}
await writeFile(join(directory, 'fixtures.json'), JSON.stringify(sharedVectors.cases), {
  mode: 0o600,
});
function declaration(source, marker) {
  const start = source.indexOf(marker);
  assert(start >= 0, marker);
  let depth = 0,
    opened = false;
  for (let i = start; i < source.length; i++) {
    if (source[i] === '{') {
      depth++;
      opened = true;
    }
    if (source[i] === '}' && --depth === 0 && opened) return source.slice(start, i + 1);
  }
  throw Error('Unclosed ' + marker);
}
const transport = await readFile('apps/ios/Sources/EasyWorkTransport.swift', 'utf8');
const deviceAuthorization = await readFile(
  'apps/ios/Sources/EasyWorkDeviceAuthorization.swift',
  'utf8',
);
const deviceHTTPDelegate = declaration(
  deviceAuthorization,
  'final class EasyWorkDeviceHTTPDelegate:',
);
const build = async (source, suffix) => {
  const file = join(directory, 'Relay-' + suffix + '.swift');
  await writeFile(
    file,
    'import Foundation\n' +
      deviceHTTPDelegate +
      '\n' +
      declaration(source, 'protocol AgentTransport:') +
      '\n' +
      declaration(source, 'actor RelayAgentTransport:'),
  );
  const binary = join(directory, 'receive-' + suffix);
  await exec(
    'xcrun',
    [
      'swiftc',
      '-parse-as-library',
      'apps/ios/Sources/EasyWorkThreadSync.swift',
      'scripts/easywork-receive-native-smoke.swift',
      file,
      '-o',
      binary,
    ],
    { env: environment, maxBuffer: 4 * 1024 * 1024 },
  );
  return binary;
};
const binary = await build(transport, 'after');
console.log((await exec(binary, [join(directory, 'fixtures.json')])).stdout.trim());
const report = {
  kind: 'actual-swift-delta-and-relay-http',
  producer: desktop ? 'actual-desktop-source' : 'protocol-fixture',
  producerSha256,
  nativeSourceSha256: sha(await readFile('apps/ios/Sources/EasyWorkThreadSync.swift')),
  redirectDelegateSha256: sha(deviceHTTPDelegate),
  authenticationScope:
    'Synthetic legacy Relay token only; device-grant and real account refresh require separate native DEV App proof',
  deltaCases: sharedVectors.cases.length,
  deltaVectorSource: 'frozen-actual-desktop-producer',
  deltaVectorsSha256: sha(sharedVectorBytes),
  deltaProducerSha256: sharedVectors.producerSha256,
  relay: [],
  wire: [],
};
let failAcknowledgements = false;
let rttMs = 0,
  acknowledged = new Set(),
  ackRequests = 0;
const frames = Array.from({ length: 12 }, (_, index) => ({
  bindingId: 'receive-binding',
  senderDeviceId: 'desktop',
  senderSessionId: 'session',
  sequence: index + 1,
}));
const server = createServer(async (req, res) => {
  const path = new URL(req.url, 'http://localhost').pathname;
  if (path === '/stats') {
    res.end(JSON.stringify({ acknowledged: acknowledged.size, ackRequests }));
    return;
  }
  if (req.headers.authorization !== 'Bearer receive-synthetic-token') {
    res.writeHead(401).end();
    return;
  }
  await delay(rttMs);
  res.setHeader('Content-Type', 'application/json');
  if (path.endsWith('/relay/pending'))
    res.end(
      JSON.stringify({
        envelopes: [
          ...frames.filter((value) => !acknowledged.has(value.sequence)),
          { bindingId: 'other-binding', sequence: 999 },
        ],
      }),
    );
  else if (path.endsWith('/relay/acknowledgements')) {
    let text = '';
    for await (const part of req) text += part;
    const values = JSON.parse(text).envelopes;
    ackRequests++;
    if (failAcknowledgements) {
      res.writeHead(502).end('{}');
      return;
    }
    for (const value of values) {
      assert.equal(value.bindingId, 'receive-binding');
      acknowledged.add(value.sequence);
    }
    res.end('{}');
  } else res.writeHead(404).end();
});
await new Promise((done) => server.listen(0, '127.0.0.1', done));
try {
  const candidates = [['after', binary]];
  const baseline = argument('--baseline-transport');
  if (baseline)
    candidates.unshift(['before', await build(await readFile(baseline, 'utf8'), 'before')]);
  for (const rtt of baseline ? [250, 600] : [100])
    for (const [version, executable] of candidates) {
      rttMs = rtt;
      acknowledged = new Set();
      ackRequests = 0;
      const value = JSON.parse(
        (
          await exec(executable, ['relay', 'http://127.0.0.1:' + server.address().port], {
            maxBuffer: 1024 * 1024,
          })
        ).stdout,
      );
      assert.equal(value.stats.acknowledged, 12);
      if (version === 'after') assert(value.stats.ackRequests <= 2);
      const row = { version, rttMs, ...value };
      report.relay.push(row);
      console.log(JSON.stringify(row));
    }
  rttMs = 100;
  acknowledged = new Set();
  ackRequests = 0;
  failAcknowledgements = true;
  console.log(
    (
      await exec(binary, ['relay-boundaries', 'http://127.0.0.1:' + server.address().port])
    ).stdout.trim(),
  );
  assert.equal(
    acknowledged.size,
    0,
    'Failed acknowledgements leave the mailbox available for replay',
  );
  report.relayFailureAndIsolation = 'passed';
} finally {
  server.closeAllConnections();
  await new Promise((done) => server.close(done));
}

// Real AES-GCM envelope bytes followed by paced HTTP delivery of the same full/delta response.
if (desktop && !process.argv.includes('--no-wire')) {
  const richWire = process.argv.includes('--rich-wire');
  report.wireShape = richWire ? 'mixed-reasoning-text-tool' : 'single-text';
  const long = Array.from({ length: 50 }, (_, index) => ({
    id: 'message-' + index,
    conversationId: 'thread',
    seq: index,
    role: 'assistant',
    createdAt: '2026-10-15T00:00:00Z',
    status: 'complete',
    content: [{ type: 'text', text: ('消息' + index + '正文、代码和工具结果。').repeat(160) }],
  }));
  long.at(-1).status = 'partial';
  if (richWire)
    long.at(-1).content = [
      { type: 'reasoning', text: '正在核对消息状态和工具结果。'.repeat(100) },
      long.at(-1).content[0],
      { type: 'tool-call', data: { toolCallId: 'synthetic-tool', result: '合成工具完成结果' } },
    ];
  const full = result(long, false, 1, []);
  long.at(-1).content.find((part) => part.type === 'text').text += '\n新到达的流式后缀 😀';
  const delta = result(long, false, 1, full.head);
  const fullLatest = result(long, false, 1, []);
  const encrypted = (value) => {
    const cipher = createCipheriv('aes-256-gcm', Buffer.alloc(32, 3), Buffer.alloc(12, 7));
    const bytes = Buffer.concat([
      cipher.update(JSON.stringify(value)),
      cipher.final(),
      cipher.getAuthTag(),
    ]);
    return Buffer.from(
      JSON.stringify({
        version: 1,
        bindingId: 'synthetic',
        senderDeviceId: 'desktop',
        sequence: 1,
        nonce: Buffer.alloc(12, 7).toString('base64url'),
        ciphertext: bytes.toString('base64url'),
      }),
    );
  };
  for (const profile of [
    { kbps: 512, rttMs: 250 },
    { kbps: 128, rttMs: 600 },
  ])
    for (const [version, data] of [
      ['full', encrypted(fullLatest)],
      ['delta', encrypted(delta)],
    ]) {
      const paced = createServer(async (_, res) => {
        await delay(profile.rttMs);
        res.setHeader('Content-Length', data.length);
        const began = performance.now(),
          rate = profile.kbps * 125,
          size = Math.floor(rate * 0.02);
        for (let offset = 0; offset < data.length; offset += size) {
          const end = Math.min(offset + size, data.length);
          await delay(Math.max(0, (end / rate) * 1000 - (performance.now() - began)));
          res.write(data.subarray(offset, end));
        }
        res.end();
      });
      await new Promise((done) => paced.listen(0, '127.0.0.1', done));
      const began = performance.now();
      await new Promise((done, reject) => {
        const req = request({ host: '127.0.0.1', port: paced.address().port }, (res) => {
          res.resume();
          res.on('end', done);
        });
        req.on('error', reject);
        req.end();
      });
      const row = {
        version,
        ...profile,
        bytes: data.length,
        receivedMs: Math.round(performance.now() - began),
      };
      report.wire.push(row);
      console.log(JSON.stringify(row));
      await new Promise((done) => paced.close(done));
    }
}
await writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
console.log('Report: ' + join(directory, 'report.json'));
