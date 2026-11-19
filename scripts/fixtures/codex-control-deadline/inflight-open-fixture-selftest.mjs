import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { once } from 'node:events';
import { setTimeout as sleep } from 'node:timers/promises';
const finder = createServer();
finder.listen(0, '127.0.0.1');
await once(finder, 'listening');
const port = finder.address().port;
await new Promise((done) => finder.close(done));
const child = spawn(
  process.execPath,
  [new URL('./inflight-open-fixture.mjs', import.meta.url).pathname],
  {
    env: { ...process.env, INFLIGHT_PHASE: 'baseline', INFLIGHT_PORT: String(port) },
    stdio: ['ignore', 'pipe', 'pipe'],
  },
);
let stderr = '';
child.stderr.on('data', (b) => (stderr += b));
try {
  await once(child.stdout, 'data');
  const base = `http://127.0.0.1:${port}`;
  const fixture = await (await fetch(base + '/fixture')).json();
  const headers = { authorization: 'Bearer ' + fixture.token, 'content-type': 'application/json' };
  const id = 'SELFTEST-OPEN-ID';
  const cancel = new AbortController();
  const openResponse = await fetch(base + '/v1/desktop/open', {
    method: 'POST',
    headers,
    body: JSON.stringify({
      hostId: fixture.hostId,
      threadId: fixture.threadId,
      clientOperationId: id,
    }),
    signal: cancel.signal,
  });
  assert.equal(openResponse.status, 200);
  await sleep(300);
  assert.equal(
    (await (await fetch(base + '/recover', { method: 'POST' })).json()).originalOpenId,
    id,
  );
  const status = await (
    await fetch(
      `${base}/v1/desktop/open-status?hostId=${fixture.hostId}&threadId=${fixture.threadId}&id=${id}`,
      { headers },
    )
  ).json();
  assert.equal(status.phase, 'opened');
  assert.equal(status.clientOperationId, id);
  const reply = await (
    await fetch(base + '/v1/replies', {
      method: 'POST',
      headers,
      body: JSON.stringify({
        hostId: fixture.hostId,
        threadId: fixture.threadId,
        clientMessageId: 'SELFTEST-REPLY',
        text: 'synthetic',
      }),
    })
  ).json();
  assert.equal(reply.state, 'completed');
  const result = await (await fetch(base + '/results')).json();
  assert.equal(result.openPosts, 1);
  assert.equal(result.replies, 1);
  assert.deepEqual(result.statusIds, [id]);
  assert.equal(result.streamClosed, false);
  assert(result.streamChunks >= 4);
  assert.deepEqual(result.errors, []);
  cancel.abort();
  console.log(
    'PASS synthetic fixture: trickle still open, same-ID recovery, one open and one reply',
  );
} finally {
  child.kill('SIGTERM');
  await Promise.race([once(child, 'exit'), sleep(2000)]);
  if (child.exitCode === null && child.signalCode === null) {
    child.kill('SIGKILL');
    await once(child, 'exit');
  }
  if (stderr) process.stderr.write(stderr);
}
