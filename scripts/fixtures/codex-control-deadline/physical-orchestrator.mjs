import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { writeFile, mkdir, lstat } from 'node:fs/promises';
import { setTimeout as sleep } from 'node:timers/promises';
import { resolve, join } from 'node:path';
const kind = process.argv[2];
assert(['open', 'reply'].includes(kind));
assert(process.env.CODEX_DEADLINE_OUTPUT, 'Set CODEX_DEADLINE_OUTPUT to a private directory');
const root = resolve(process.env.CODEX_DEADLINE_OUTPUT);
await mkdir(root, { recursive: true, mode: 0o700 });
const outputInfo = await lstat(root);
assert(outputInfo.isDirectory() && !outputInfo.isSymbolicLink());
if (process.platform !== 'win32') {
  assert.equal(outputInfo.uid, process.getuid());
  assert.equal(outputInfo.mode & 0o077, 0, 'Use a private task output directory');
}
const port = kind === 'open' ? 18802 : 18816,
  control = kind === 'open' ? 18812 : 18826;
const script = `scripts/fixtures/codex-control-deadline/${kind === 'open' ? 'inflight-open' : 'reply-wait'}-fixture.mjs`;
const env = {
  ...process.env,
  FIXTURE_BIND: process.env.FIXTURE_BIND || '127.0.0.1',
  FIXTURE_CONTROL_PORT: String(control),
  INFLIGHT_PHASE: 'fixed',
  INFLIGHT_PORT: String(port),
  REPLY_WAIT_PORT: String(port),
  REPLY_WAIT_AUTOMATIC: '1',
  REPLY_WAIT_TRICKLE: '1',
  FIXTURE_CONNECTION_MODE: 'desktop-runtime',
};
const child = spawn(process.execPath, [script], { env, stdio: ['ignore', 'pipe', 'pipe'] });
let stderr = '';
child.stderr.on('data', (b) => (stderr += b));
let stopRequested = false;
process.on('SIGTERM', () => {
  stopRequested = true;
});
process.on('SIGINT', () => {
  stopRequested = true;
});
const base = `http://127.0.0.1:${control}`;
let finished = false;
try {
  await new Promise((ready, fail) => {
    const cleanup = () => {
      clearTimeout(timer);
      child.stdout.off('data', onReady);
      child.off('error', onError);
      child.off('exit', onExit);
    };
    const onReady = () => {
      cleanup();
      ready();
    };
    const onError = (error) => {
      cleanup();
      fail(error);
    };
    const onExit = (code, signal) => {
      cleanup();
      fail(new Error(`Synthetic fixture exited before readiness: ${code ?? signal}`));
    };
    const timer = setTimeout(() => {
      cleanup();
      fail(new Error('Synthetic fixture startup timed out'));
    }, 5000);
    child.stdout.once('data', onReady);
    child.once('error', onError);
    child.once('exit', onExit);
  });
  const get = async (path, headers = {}) => {
    const r = await fetch(base + path, { headers, signal: AbortSignal.timeout(5000) });
    assert.equal(r.status, 200);
    return r.json();
  };
  const f = await get(kind === 'open' ? '/fixture' : '/');
  const headers = { authorization: 'Bearer ' + f.token };
  await writeFile(join(root, `physical-${kind}-fixture.json`), JSON.stringify(f), {
    mode: 0o600,
    flag: 'wx',
  });
  console.log(JSON.stringify({ controllerPid: process.pid, kind, ready: true }));
  let sentAt,
    handled = false,
    final;
  const expiry = Date.now() + 300000;
  while (Date.now() < expiry && !stopRequested) {
    const s = await get(kind === 'open' ? '/results' : '/wait/status', headers);
    if (kind === 'open') {
      if (s.openPosts === 1 && !sentAt) sentAt = s.startedAt;
      if (sentAt && !handled && Date.now() - sentAt >= 5000) {
        const r = await fetch(base + '/recover', {
          method: 'POST',
          signal: AbortSignal.timeout(5000),
        });
        assert.equal(r.status, 200);
        assert.equal((await r.json()).originalOpenId, s.openId);
        handled = true;
      }
      if (s.replies === 1) {
        assert(handled);
        assert.equal(s.openPosts, 1);
        assert.equal(s.statusGets, 1);
        assert.deepEqual(s.statusIds, [s.openId]);
        assert.deepEqual(s.sequence, ['open', 'status', 'reply']);
        assert.deepEqual(s.errors, []);
        assert(s.streamClosed);
        assert(s.streamChunks >= 500, 'continuous trickle required');
        assert(s.streamClosedAt - s.startedAt >= 30000 && s.streamClosedAt - s.startedAt <= 37500);
        final = s;
        break;
      }
    } else {
      if (s.sends.length === 1 && !sentAt) sentAt = s.trickleStart;
      if (sentAt && !handled && Date.now() - sentAt >= 40000) {
        const r = await fetch(base + '/wait/confirm?index=0', {
          headers,
          signal: AbortSignal.timeout(5000),
        });
        assert.equal(r.status, 200);
        handled = true;
      }
      if (handled && s.trickleClosed && s.statusQueries.includes(s.sends[0]?.clientMessageId)) {
        assert.equal(s.sends.length, 1);
        assert(s.trickleChunks >= 500, 'continuous trickle required');
        assert(s.queries.includes(s.sends[0].clientMessageId));
        assert(
          s.trickleClosed - s.trickleStart >= 30000 && s.trickleClosed - s.trickleStart <= 37500,
        );
        final = s;
        break;
      }
    }
    await sleep(250);
  }
  assert(final, 'fixture controller timed out');
  await sleep(3000);
  const last = await get(kind === 'open' ? '/results' : '/wait/status', headers);
  if (kind === 'open') {
    assert.equal(last.openPosts, 1);
    assert.equal(last.replies, 1);
    assert.deepEqual(last.statusIds, [last.openId]);
  } else {
    assert.equal(last.sends.length, 1);
    assert(last.queries.includes(last.sends[0].clientMessageId));
  }
  await writeFile(
    join(root, `physical-${kind}-controller-result.json`),
    JSON.stringify({ kind, result: 'passed', phase: 'count-observed', state: last }, null, 2),
    { mode: 0o600 },
  );
  finished = true;
  console.log(
    `PASS ${kind}: original ID, bounded stream and one submission; awaiting parent shutdown`,
  );
  const shutdownDeadline = Date.now() + 300000;
  while (!stopRequested && Date.now() < shutdownDeadline) await sleep(250);
  assert(stopRequested, 'parent did not finish UI within 5 minutes');
  const end = await get(kind === 'open' ? '/results' : '/wait/status', headers);
  if (kind === 'open') {
    assert.equal(end.openPosts, 1);
    assert.equal(end.replies, 1);
    assert.deepEqual(end.statusIds, [end.openId]);
    assert.deepEqual(end.errors, []);
  } else {
    assert.equal(end.sends.length, 1);
    assert(end.queries.includes(end.sends[0].clientMessageId));
  }
  await writeFile(
    join(root, `physical-${kind}-controller-result.json`),
    JSON.stringify({ kind, result: 'passed', phase: 'ui-finished', state: end }, null, 2),
    { mode: 0o600 },
  );
} finally {
  child.kill('SIGTERM');
  if (child.exitCode === null && child.signalCode === null) {
    const exited = once(child, 'exit');
    await Promise.race([exited, sleep(2000)]);
    if (child.exitCode === null && child.signalCode === null) {
      child.kill('SIGKILL');
      await exited;
    }
  }
  if (stderr) process.stderr.write(stderr);
  if (!finished) console.error(`${kind} controller did not complete`);
}
