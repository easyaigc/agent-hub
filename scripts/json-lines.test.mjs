import { test } from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { PassThrough } from 'node:stream';
import childProcess from 'node:child_process';
import { syncBuiltinESMExports } from 'node:module';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createJsonLineReader } from '../packages/codex-adapter/dist/json-lines.js';
import { CodexHistoryReader } from '../packages/codex-adapter/dist/history-reader.js';
import { MetadataConnection } from '../packages/codex-adapter/dist/desktop-thread-preparer.js';
import { CodexRuntime } from '../packages/codex-adapter/dist/runtime.js';

const text = '原文\u2028下一行\u2029下一段🙂\\路径\r\n保留';

test('JSONL preserves Unicode separators and split UTF-8 bytes across LF and CRLF records', async () => {
  const input = new PassThrough();
  const lines = createJsonLineReader({ input });
  const received = [];
  lines.on('line', (line) => received.push(JSON.parse(line)));
  const closed = once(lines, 'close');
  const values = [{ text }, { text: text + '\n末尾' }];
  const bytes = Buffer.from(values.map((value) => JSON.stringify(value)).join('\r\n') + '\n');
  for (const byte of bytes) input.write(Buffer.from([byte]));
  input.end();
  await closed;
  assert.deepEqual(received, values);
});

test('large JSONL response stays one record until its actual LF', async () => {
  const input = new PassThrough();
  const lines = createJsonLineReader({ input });
  const received = [];
  lines.on('line', (line) => received.push(JSON.parse(line)));
  const value = { id: 42, result: { text: 'a'.repeat(3_399_100) + text + 'z'.repeat(443_183) } };
  const bytes = Buffer.from(JSON.stringify(value));
  for (let start = 0; start < bytes.length; start += 8191)
    input.write(bytes.subarray(start, start + 8191));
  assert.equal(received.length, 0);
  const closed = once(lines, 'close');
  input.end('\n');
  await closed;
  assert.deepEqual(received, [value]);
});

test('normal EOF delivers the last record; explicit close discards unfinished input', async () => {
  const input = new PassThrough();
  const lines = createJsonLineReader({ input });
  const received = [];
  lines.on('line', (line) => received.push(line));
  const closed = once(lines, 'close');
  input.end(JSON.stringify({ text }));
  await closed;
  assert.deepEqual(received, [JSON.stringify({ text })]);
  assert.equal(input.listenerCount('data'), 0);

  const stoppedInput = new PassThrough();
  const stopped = createJsonLineReader({ input: stoppedInput });
  stopped.on('line', () => assert.fail('closed reader consumed a partial frame'));
  stoppedInput.write('{"unfinished":');
  stopped.close();
  stoppedInput.end('true}\n');
  assert.equal(stoppedInput.destroyed, false, 'reader close does not own the subprocess');
  assert.equal(stoppedInput.listenerCount('data'), 0);
});

test('real stdio readers preserve separators in history, queue metadata and managed runtime', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'jsonl-boundary-'));
  const cli = join(directory, 'fixture.mjs');
  const originalSpawn = childProcess.spawn;
  const children = [];
  const readers = [];
  await writeFile(
    cli,
    `import { createInterface } from 'node:readline';
const text = ${JSON.stringify(text)};
createInterface({input:process.stdin}).on('line', line => {
  const request = JSON.parse(line);
  if(request.id === undefined) return;
  const result = request.method === 'thread/read'
    ? {thread:{id:request.params.threadId,turns:[{id:'turn',status:'completed',items:[{type:'agentMessage',text}]}]}}
    : request.method === 'thread/queue/list' ? {data:[{text}]} : {text};
  const bytes = Buffer.from(JSON.stringify({id:request.id,result})+'\\n');
  for(let start=0;start<bytes.length;start+=7)process.stdout.write(bytes.subarray(start,start+7));
});
`,
    { mode: 0o700 },
  );
  childProcess.spawn = (command, args, options) => {
    const child =
      command === cli
        ? originalSpawn(process.execPath, [cli, ...args], options)
        : originalSpawn(command, args, options);
    const closed = once(child, 'close');
    children.push({ child, closed });
    return child;
  };
  syncBuiltinESMExports();
  try {
    const history = new CodexHistoryReader('fixture-host', 'test', cli);
    readers.push(history);
    await history.connect();
    const result = await history.read('thread/read', {
      threadId: 'fixture-thread',
      includeTurns: true,
    });
    assert.equal(result.thread.turns[0].items[0].text, text);
    assert.equal(history.ready, true);

    const metadata = new MetadataConnection(cli, directory, directory, 'queue');
    readers.push({ close: () => metadata.abort() });
    await metadata.initialize();
    const queue = await metadata.rpc('thread/queue/list', { threadId: 'fixture-thread' });
    assert.equal(queue.data[0].text, text);

    const runtime = new CodexRuntime({ mode: 'managed-runtime', executable: cli });
    readers.push(runtime);
    await runtime.connect();
    assert.equal((await runtime.call('model/list')).text, text);
    assert.equal(runtime.ready, true);
  } finally {
    for (const reader of readers) reader.close();
    childProcess.spawn = originalSpawn;
    syncBuiltinESMExports();
    await Promise.all(children.map(({ closed }) => closed));
    await rm(directory, { recursive: true });
  }
});
