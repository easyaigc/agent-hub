import { spawn } from 'node:child_process';
import { once } from 'node:events';
import assert from 'node:assert/strict';
import { setTimeout as sleep } from 'node:timers/promises';
const server = spawn(
  process.execPath,
  ['scripts/fixtures/codex-control-deadline/reply-wait-fixture.mjs'],
  {
    env: {
      ...process.env,
      REPLY_WAIT_PORT: '18817',
      REPLY_WAIT_AUTOMATIC: '1',
      REPLY_WAIT_TRICKLE: '1',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  },
);
let err = '';
server.stderr.on('data', (b) => (err += b));
try {
  await once(server.stdout, 'data');
  const base = 'http://127.0.0.1:18817';
  const f = await (await fetch(base)).json();
  const h = { authorization: 'Bearer ' + f.token, 'content-type': 'application/json' };
  const abort = new AbortController();
  const response = await fetch(base + '/v1/replies', {
    method: 'POST',
    headers: h,
    body: JSON.stringify({
      hostId: f.hostId,
      threadId: f.threadId,
      clientMessageId: 'SELFTEST-REPLY',
      mode: 'next-turn',
      text: 'synthetic',
    }),
    signal: abort.signal,
  });
  assert.equal(response.status, 200);
  await sleep(250);
  abort.abort();
  await sleep(100);
  let s = await (await fetch(base + '/wait/status', { headers: h })).json();
  assert.equal(s.sends.length, 1);
  assert(s.trickleChunks >= 2);
  assert(s.trickleClosed);
  await fetch(base + '/wait/confirm?index=0', { headers: h });
  const target = encodeURIComponent(JSON.stringify({ hostId: f.hostId, threadId: f.threadId }));
  const out = await (
    await fetch(
      `${base}/v1/replies/status?hostId=${f.hostId}&threadId=${f.threadId}&id=SELFTEST-REPLY&target=${target}`,
      { headers: h },
    )
  ).json();
  assert.equal(out.status, 'accepted');
  assert.equal(out.receipt.clientMessageId, 'SELFTEST-REPLY');
  s = await (await fetch(base + '/wait/status', { headers: h })).json();
  assert.deepEqual(s.statusQueries, ['SELFTEST-REPLY']);
  assert.equal(s.sends.length, 1);
  console.log('PASS reply fixture trickle, same-ID acceptance, one POST');
} finally {
  server.kill('SIGTERM');
  await Promise.race([once(server, 'exit'), sleep(2000)]);
  if (err) process.stderr.write(err);
}
