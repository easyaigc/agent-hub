// Synthetic auxiliary evidence only: real Node children, no real Codex or Host.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import childProcess from 'node:child_process';
import { syncBuiltinESMExports } from 'node:module';
import { mkdtemp, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const originalSpawn = childProcess.spawn;
const originalTimeout = globalThis.setTimeout;
const delay = (ms) => new Promise((resolve) => originalTimeout(resolve, ms));
const { CodexHistoryReader } = await import('../packages/codex-adapter/dist/history-reader.js');
const { MetadataConnection } =
  await import('../packages/codex-adapter/dist/desktop-thread-preparer.js');
const { DesktopNativeQueue } =
  await import('../packages/codex-adapter/dist/desktop-native-queue.js');

async function fixture(mode, run) {
  const dir = await mkdtemp(join(tmpdir(), 'metadata-lifecycle-'));
  const cli = join(dir, 'cli.mjs');
  const log = join(dir, 'events');
  await writeFile(
    cli,
    `#!/usr/bin/env node
import {createInterface} from 'node:readline';
import {appendFileSync} from 'node:fs';
import {spawn} from 'node:child_process';
const log=${JSON.stringify(log)}, mode=${JSON.stringify(mode)};
const record=x=>appendFileSync(log, JSON.stringify({pid:process.pid,...x})+'\\n');
record({event:'start'});
if(mode==='late-close'){const holder=spawn(process.execPath,['-e','setTimeout(()=>process.exit(0),1400)'],{stdio:['ignore',process.stdout,'ignore']});record({event:'holder',holderPid:holder.pid});}
process.on('SIGTERM',()=>{record({event:'term'}); if(mode==='ignore' || mode==='bad-list' || mode==='silent-init') return; setTimeout(()=>process.exit(0),150)});
process.stdin.on('end',()=>{if(mode!=='ignore' && mode!=='silent-init')process.exit(0)});
createInterface({input:process.stdin}).on('line',line=>{const r=JSON.parse(line);record({event:'request',method:r.method});if(r.id===undefined)return;
if(r.method==='initialize' && mode==='silent-init')return;
if(r.method==='thread/queue/list' && mode==='bad-list'){process.stdout.write('not-json\\n');return}
if(r.method==='thread/queue/add')return;
if(r.method==='model/list' && mode==='timeout')return;
const result=r.method==='thread/queue/list'||r.method==='project/list'||r.method==='thread/list'?{data:[],nextCursor:null}:{};
process.stdout.write(JSON.stringify({id:r.id,result})+'\\n');});
setInterval(()=>{},1000);
`,
    { mode: 0o700 },
  );
  const children = [];
  childProcess.spawn = (command, args, options) => {
    if (command !== cli) return originalSpawn(command, args, options);
    const child = originalSpawn(process.execPath, [cli, ...args], options);
    const entry = { child, closed: false, overlap: children.some((x) => !x.closed) };
    children.push(entry);
    child.once('close', () => {
      entry.closed = true;
    });
    return child;
  };
  syncBuiltinESMExports();
  globalThis.setTimeout = (fn, ms, ...args) =>
    originalTimeout(
      fn,
      mode === 'late-close' && ms === 5000
        ? 200
        : [10000, 12000, 15000, 20000].includes(ms)
          ? 350
          : ms,
      ...args,
    );
  const events = async () =>
    (await readFile(log, 'utf8').catch(() => ''))
      .trim()
      .split('\n')
      .filter(Boolean)
      .map(JSON.parse);
  const waitFor = async (condition, label, ms = 1800) => {
    const until = Date.now() + ms;
    while (Date.now() < until) {
      if (await condition()) return;
      await delay(20);
    }
    assert.fail(label);
  };
  try {
    await run({ dir, cli, children, events, waitFor });
  } finally {
    globalThis.setTimeout = originalTimeout;
    for (const { child, closed } of children) if (!closed) child.kill('SIGKILL');
    await Promise.all(
      children.map(({ child, closed }) =>
        closed ? undefined : new Promise((resolve) => child.once('close', resolve)),
      ),
    );
    childProcess.spawn = originalSpawn;
    syncBuiltinESMExports();
    await rm(dir, { recursive: true, force: true });
  }
}

test('synthetic: concurrent history connects and queue lists share one child', async () => {
  await fixture('normal', async ({ cli, dir, children }) => {
    const reader = new CodexHistoryReader('synthetic', 'synthetic', cli);
    await Promise.all([reader.connect(), reader.connect(), reader.connect()]);
    assert.equal(children.length, 1);
    await Promise.all([reader.workspace(), reader.workspace()]);
    reader.close();
    await new Promise((resolve) => children[0].child.once('close', resolve));
    const queue = new DesktopNativeQueue(cli, dir);
    await Promise.all([queue.list('t'), queue.list('t'), queue.list('t')]);
    assert.equal(children.length, 2);
    queue.close();
  });
});

test('synthetic: queue close during initialization retires child and prevents later spawn', async () => {
  await fixture('silent-init', async ({ cli, dir, children, events, waitFor }) => {
    const queue = new DesktopNativeQueue(cli, dir);
    let settled = false;
    const pending = queue.list('t').then(
      () => {
        settled = true;
      },
      () => {
        settled = true;
      },
    );
    await waitFor(
      async () => (await events()).some((e) => e.method === 'initialize'),
      'initialize was not received',
    );
    queue.close();
    await delay(250);
    assert.equal(
      settled,
      true,
      'pending list should reject promptly, without waiting for child exit',
    );
    await waitFor(() => children[0].closed, 'initializing child survived close');
    await pending;
    await assert.rejects(queue.list('t'));
    assert.equal(children.length, 1);
  });
});

test('synthetic: malformed queue transport cannot overlap old child on reconnect', async () => {
  await fixture('bad-list', async ({ cli, dir, children }) => {
    const queue = new DesktopNativeQueue(cli, dir);
    await assert.rejects(queue.list('t'));
    await assert.rejects(queue.list('t'));
    assert.equal(
      children.some((x) => x.overlap),
      false,
      'replacement spawned before old child closed',
    );
    queue.close();
  });
});

test('synthetic: metadata abort reaps a child ignoring SIGTERM within a bound', async () => {
  await fixture('ignore', async ({ cli, dir, children, waitFor }) => {
    const client = new MetadataConnection(cli, dir, dir, 'queue');
    await client.initialize();
    client.abort();
    await waitFor(() => children[0].closed, 'SIGTERM-resistant child was not reaped');
  });
});

test('synthetic: history timeout reconnect waits for old exit', async () => {
  await fixture('timeout', async ({ cli, children }) => {
    const reader = new CodexHistoryReader('synthetic', 'synthetic', cli);
    await reader.connect();
    await assert.rejects(reader.read('model/list', {}));
    await reader.connect();
    assert.equal(
      children.some((x) => x.overlap),
      false,
    );
    reader.close();
  });
});

test('synthetic: unknown queue add is not replayed during recovery', async () => {
  await fixture('normal', async ({ cli, dir, events, children }) => {
    const queue = new DesktopNativeQueue(cli, dir);
    let writes = 0;
    await assert.rejects(
      queue.add('t', 'id', [{ type: 'text', text: 'synthetic' }], () => writes++),
    );
    await queue.list('t');
    assert.equal(writes, 1);
    assert.equal(children.length, 2, 'recovery must use a fresh connection');
    assert.equal(children[0].closed, true);
    assert.equal(children[1].overlap, false);
    assert.equal((await events()).filter((e) => e.method === 'thread/queue/add').length, 1);
    queue.close();
  });
});

test('synthetic: history close during initialization retires child', async () => {
  await fixture('silent-init', async ({ cli, children, events, waitFor }) => {
    const reader = new CodexHistoryReader('synthetic', 'synthetic', cli);
    const pending = reader.connect().catch(() => {});
    await waitFor(
      async () => (await events()).some((e) => e.method === 'initialize'),
      'initialize was not received',
    );
    reader.close();
    await waitFor(() => children[0].closed, 'history child survived close');
    await pending;
  });
});

test('synthetic: queue timeout does not overlap an unexited child', async () => {
  await fixture('normal', async ({ cli, dir, children }) => {
    const queue = new DesktopNativeQueue(cli, dir);
    await assert.rejects(queue.add('t', 'id', [{ type: 'text', text: 'synthetic' }], () => {}));
    await queue.list('t').catch(() => {});
    assert.equal(
      children.some((x) => x.overlap),
      false,
    );
    queue.close();
  });
});

test('synthetic: closed queue never starts a child', async () => {
  await fixture('normal', async ({ cli, dir, children }) => {
    const queue = new DesktopNativeQueue(cli, dir);
    queue.close();
    await assert.rejects(queue.list('t'));
    assert.equal(children.length, 0);
  });
});

test('synthetic: reader close reaps resistant child before explicit reconnect', async () => {
  await fixture('ignore', async ({ cli, children, waitFor }) => {
    const reader = new CodexHistoryReader('synthetic', 'synthetic', cli);
    await reader.connect();
    reader.close();
    await waitFor(() => children[0].closed, 'closed reader child survived SIGTERM');
    await reader.connect();
    assert.equal(children.length, 2);
    assert.equal(children[1].overlap, false);
    assert.equal(reader.ready, true);
    reader.close();
  });
});

test('synthetic: history Host SIGTERM and restart reap the exact spawned children', async () => {
  await fixture('ignore', async ({ cli, dir, events, waitFor }) => {
    const entry = new URL('../apps/host-agent/dist/history-entry.js', import.meta.url);
    const hosts = [];
    const recorded = new Set();
    const cleanupErrors = [];
    try {
      for (let cycle = 0; cycle < 2; cycle++) {
        const host = originalSpawn(process.execPath, [entry.pathname], {
          env: {
            ...process.env,
            CODEX_EXECUTABLE: cli,
            HOST_AGENT_PORT: '0',
            HOST_AGENT_STATE_DIR: dir,
          },
          stdio: ['ignore', 'pipe', 'pipe'],
        });
        hosts.push(host);
        let ready = false;
        let errors = '';
        host.stdout.on('data', (chunk) => {
          if (String(chunk).includes('history-only')) ready = true;
        });
        host.stderr.on('data', (chunk) => {
          errors += chunk;
        });
        const exited = new Promise((resolve) =>
          host.once('close', (code, signal) => resolve({ code, signal })),
        );
        await waitFor(() => ready || host.exitCode !== null, 'fixture Host did not start', 4000);
        assert.equal(ready, true, `fixture Host failed: ${errors}`);
        const starts = (await events()).filter((event) => event.event === 'start');
        assert.equal(starts.length, cycle + 1);
        const pid = starts[cycle].pid;
        recorded.add(pid);
        assert.equal(host.kill('SIGTERM'), true);
        const outcome = await Promise.race([exited, delay(5000).then(() => null)]);
        assert.ok(outcome, 'fixture Host did not exit after SIGTERM');
        assert.equal(outcome.code, 0);
        await waitFor(
          () => {
            try {
              process.kill(pid, 0);
              return false;
            } catch (error) {
              return error.code === 'ESRCH';
            }
          },
          'fixture metadata child survived Host shutdown',
          3000,
        );
        recorded.delete(pid);
      }
    } finally {
      for (const host of hosts)
        if (host.exitCode === null && host.signalCode === null) host.kill('SIGKILL');
      for (const pid of recorded) {
        try {
          process.kill(pid, 'SIGKILL');
        } catch (error) {
          if (error.code !== 'ESRCH') cleanupErrors.push(error);
        }
      }
    }
    assert.deepEqual(cleanupErrors, []);
  });
});

test('synthetic: late stdout close blocks reader replacement then permits recovery', async () => {
  await fixture('late-close', async ({ cli, children, events, waitFor }) => {
    const reader = new CodexHistoryReader('synthetic', 'synthetic', cli);
    await reader.connect();
    await waitFor(
      async () => (await events()).some((event) => event.event === 'holder'),
      'stdout holder was not started',
    );
    reader.close();
    await assert.rejects(reader.connect());
    assert.equal(children.length, 1, 'must not spawn while close remains unconfirmed');
    assert.equal(children[0].closed, false);
    await waitFor(() => children[0].closed, 'late stdio close not observed', 4000);
    await reader.connect();
    assert.equal(children.length, 2);
    assert.equal(children[1].overlap, false);
    assert.equal(reader.ready, true);
    reader.close();
  });
});

test('synthetic: late queue close fences replacement and never replays unknown add', async () => {
  await fixture('late-close', async ({ cli, dir, children, events, waitFor }) => {
    const queue = new DesktopNativeQueue(cli, dir);
    let writes = 0;
    await assert.rejects(
      queue.add('t', 'late', [{ type: 'text', text: 'synthetic' }], () => writes++),
    );
    await assert.rejects(queue.list('t'));
    assert.equal(children.length, 1, 'unknown exit must fence replacement');
    assert.equal(children[0].closed, false);
    await waitFor(() => children[0].closed, 'late queue close not observed', 4000);
    assert.deepEqual(await queue.list('t'), []);
    assert.equal(children.length, 2);
    assert.equal(children[1].overlap, false);
    assert.equal(writes, 1);
    assert.equal((await events()).filter((event) => event.method === 'thread/queue/add').length, 1);
    queue.close();
  });
});
