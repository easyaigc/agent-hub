import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { DesktopStdioRouter } from '../packages/codex-adapter/dist/desktop-stdio-router.js';
import { receiveJsonLines } from '../packages/codex-adapter/dist/desktop-stdio-bridge.js';

let passed = 0;
const check = (body) => {
  body();
  passed++;
};
const upstream = [],
  desktop = [],
  probe = [];
const router = new DesktopStdioRouter(
  (v) => upstream.push(v),
  (v) => desktop.push(v),
);
check(() => {
  assert.throws(() => router.probe({ id: 1, method: 'thread/start' }, (v) => probe.push(v)));
  router.fromDesktop({
    id: 0,
    method: 'initialize',
    params: { clientInfo: { name: 'original-desktop' } },
  });
  assert.equal(upstream.length, 1);
  router.fromUpstream({ id: upstream[0].id, result: { userAgent: 'fixture' } });
  assert.equal(router.ready, false);
  router.fromDesktop({ method: 'initialized' });
  assert.equal(router.ready, true);
  assert.equal(upstream.filter((m) => m.method === 'initialize').length, 1);
});
check(() => {
  router.fromDesktop({ id: 1, method: 'thread/read', params: { threadId: 'original' } });
  router.probe({ id: 1, method: 'thread/start', params: { projectId: 'test-project' } }, (v) =>
    probe.push(v),
  );
  const [a, b] = upstream.slice(-2);
  assert.notEqual(a.id, b.id);
  router.fromUpstream({ id: b.id, result: { thread: { id: 'new' } } });
  router.fromUpstream({ id: a.id, result: { thread: { id: 'original' } } });
  assert.equal(probe.at(-1).id, 1);
  assert.equal(probe.at(-1).result.thread.id, 'new');
  assert.equal(desktop.at(-1).id, 1);
  assert.equal(desktop.at(-1).result.thread.id, 'original');
});
check(() => {
  router.fromDesktop({ id: 'human-id', method: 'thread/read' });
  router.fromUpstream({ id: upstream.at(-1).id, error: { code: -1, message: 'fixture refusal' } });
  assert.equal(desktop.at(-1).id, 'human-id');
  assert.equal(desktop.at(-1).error.message, 'fixture refusal');
});
check(() => {
  const prior = probe.length;
  const request = {
    id: 'approval-id',
    method: 'item/commandExecution/requestApproval',
    params: { threadId: 'new' },
  };
  router.fromUpstream(request);
  assert.deepEqual(desktop.at(-1), request);
  assert.equal(probe.length, prior);
  router.fromDesktop({ id: 'approval-id', result: { decision: 'decline' } });
  assert.deepEqual(upstream.at(-1), { id: 'approval-id', result: { decision: 'decline' } });
  assert.throws(() => router.fromDesktop({ id: 'approval-id', result: {} }));
});
check(() => {
  const notification = {
    method: 'thread/started',
    params: { thread: { id: 'new', projectId: 'test-project' } },
  };
  router.fromUpstream(notification);
  assert.deepEqual(desktop.at(-1), notification);
  assert.throws(() => router.probe({ id: 2, method: 'initialize' }, () => {}));
  assert.throws(() => router.fromDesktop({ id: 0, method: 'initialize' }));
});
check(() => {
  const got = [];
  const read = receiveJsonLines(200, (v) => got.push(v));
  const bytes = Buffer.from(
    JSON.stringify({ text: '手机创建🍀' }) + '\n' + JSON.stringify({ id: 1 }) + '\n',
  );
  for (const byte of bytes) read(Buffer.from([byte]));
  assert.deepEqual(got, [{ text: '手机创建🍀' }, { id: 1 }]);
  assert.throws(() => receiveJsonLines(10, () => {})(Buffer.from('a'.repeat(11))));
  assert.throws(() => receiveJsonLines(10, () => {})(Buffer.from('a'.repeat(11) + '\n')));
});
check(() => {
  router.fromDesktop({ id: 77, method: 'thread/read' });
  router.probe({ id: 'uncertain', method: 'thread/start' }, (v) => probe.push(v));
  router.close();
  router.close();
  assert.equal(router.ready, false);
  assert.equal(probe.at(-1).error.code, -32001);
  assert.equal(desktop.at(-1).id, 77);
  assert.equal(router.status().pending, 0);
});

// Exercise actual stdio IO with one fixture process, rather than only function calls.
const fixture = spawn(
  process.execPath,
  [
    '-e',
    `
  const r = require('node:readline').createInterface({input:process.stdin});
  const out = m => process.stdout.write(JSON.stringify(m)+'\\n');
  r.on('line', line => { const m=JSON.parse(line); if (m.id == null) return;
    out({id:m.id,result:m.method==='initialize'?{userAgent:'fixture'}:{executorPid:process.pid,echo:m.params}});
    if(m.method==='thread/start')out({method:'thread/started',params:{thread:{id:'fixture-new'}}});
  });
`,
  ],
  { stdio: ['pipe', 'pipe', 'pipe'] },
);
const results = new Map();
const notifications = [];
const io = new DesktopStdioRouter(
  (m) => fixture.stdin.write(JSON.stringify(m) + '\n'),
  (m) => {
    if (m.method) {
      notifications.push(m);
      results.get('notification:' + m.method)?.(m);
    } else results.get('desktop:' + m.id)?.(m);
  },
);
const wait = (key) =>
  new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Fixture IO timeout')), 5000);
    results.set(key, (m) => {
      clearTimeout(timer);
      resolve(m);
    });
  });
createInterface({ input: fixture.stdout }).on('line', (line) => io.fromUpstream(JSON.parse(line)));
try {
  const init = wait('desktop:0');
  io.fromDesktop({ id: 0, method: 'initialize' });
  await init;
  io.fromDesktop({ method: 'initialized' });
  const a = wait('desktop:1'),
    b = wait('probe:1'),
    started = wait('notification:thread/started');
  io.fromDesktop({ id: 1, method: 'thread/read', params: { threadId: 'original' } });
  io.probe({ id: 1, method: 'thread/start', params: { projectId: 'test-project' } }, (m) =>
    results.get('probe:' + m.id)?.(m),
  );
  const [x, y] = await Promise.all([a, b, started]);
  assert.equal(x.result.executorPid, fixture.pid);
  assert.equal(y.result.executorPid, fixture.pid);
  assert.equal(notifications.filter((m) => m.method === 'thread/started').length, 1);
  passed++;
} finally {
  io.close();
  fixture.kill();
}
process.stdout.write(
  JSON.stringify({
    suite: 'desktop-stdio-transport-fixture',
    passed,
    failed: 0,
    spawnedFixtureExecutors: 1,
    realDesktopCreationVerified: false,
  }) + '\n',
);
