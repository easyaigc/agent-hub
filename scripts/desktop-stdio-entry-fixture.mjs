import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { connect } from 'node:net';
import { createInterface } from 'node:readline';
import { mkdtemp, mkdir, writeFile, symlink, chmod, rm, readFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { randomUUID } from 'node:crypto';

// macOS ps comm reports argv0. Match a desktop's absolute executable invocation.
if (process.argv0 !== process.execPath) {
  const parent = spawn(process.execPath, process.argv.slice(1), {
    argv0: process.execPath,
    stdio: 'inherit',
  });
  const code = await new Promise((resolveExit, reject) => {
    parent.once('error', reject);
    parent.once('exit', (value) => resolveExit(value ?? 1));
  });
  process.exit(code);
}

// A fixture desktop and fixture executor only; no real Codex process, account or model.
const directory = await mkdtemp(join(tmpdir(), 'stdio-poc-'));
await chmod(directory, 0o700);
const macos = join(directory, 'Fixture.app', 'Contents', 'MacOS');
await mkdir(macos, { recursive: true });
await symlink(process.execPath, join(macos, 'Fixture'));
await writeFile(
  join(macos, '..', 'Info.plist'),
  '<?xml version="1.0"?><plist version="1.0"><dict><key>CFBundleShortVersionString</key><string>0.0.0-fixture</string></dict></plist>',
);
const cli = join(directory, 'fixture-cli');
await writeFile(
  cli,
  `#!${process.execPath}
const readline=require('node:readline'),fs=require('node:fs');
if(process.env.CODEX_CLI_PATH!==${JSON.stringify(cli)})process.exit(41);
const out=m=>process.stdout.write(JSON.stringify(m)+'\\n');
const log=m=>fs.appendFileSync(${JSON.stringify(join(directory, 'calls.jsonl'))},JSON.stringify({method:m.method??'response',pid:process.pid})+'\\n');
let held;
readline.createInterface({input:process.stdin}).on('line',line=>{
 const m=JSON.parse(line);log(m);
 if(m.method==='initialize')out({id:m.id,result:{userAgent:'fixture'}});
 else if(m.method==='thread/start'){
  if(fs.existsSync(${JSON.stringify(join(directory, 'disconnect-on-start'))}))process.exit(23);
  const thread={id:'fixture-thread-'+process.pid,projectId:m.params.projectId,cwd:m.params.cwd};
  out({id:m.id,result:{thread}});out({method:'thread/started',params:{thread}});
 } else if(m.method==='turn/start'){
  held=m;out({id:'fixture-approval',method:'item/commandExecution/requestApproval',params:{threadId:m.params.threadId,turnId:'fixture-turn'}});
 } else if(m.id==='fixture-approval'){
  out({id:held.id,result:{turn:{id:'fixture-turn'}}});held=null;
 }
});
`,
  { mode: 0o700 },
);
const profilePath = join(directory, 'profile.json'),
  transport = join(directory, 'transport');
await writeFile(
  profilePath,
  JSON.stringify({
    kind: 'transport-poc',
    codexExecutable: cli,
    desktopExecutable: join(macos, 'Fixture'),
    desktopVersion: '0.0.0-fixture',
    directory: transport,
    projectId: 'fixture-project',
    projectDirectory: directory,
  }),
  { mode: 0o600 },
);
let child,
  client,
  passed = 0;
const originalMessages = [];
async function boot(writeEnabled) {
  child = spawn(
    process.execPath,
    [resolve('apps/host-agent/dist/desktop-stdio-entry.js'), 'app-server'],
    {
      env: {
        ...process.env,
        CODEX_MANAGE_STDIO_PROFILE: profilePath,
        CODEX_INTERNAL_ORIGINATOR_OVERRIDE: 'Codex Desktop',
        CODEX_MANAGE_STDIO_CREATE_POC: writeEnabled ? '1' : '0',
      },
      stdio: ['pipe', 'pipe', 'pipe'],
    },
  );
  let diagnostics = '';
  child.stderr.on('data', (bytes) => {
    diagnostics += bytes.toString('utf8');
  });
  let initializedResolve;
  const initialized = new Promise((resolveReady, reject) => {
    const timer = setTimeout(
      () => reject(new Error('Fixture desktop initialization timeout: ' + diagnostics)),
      5000,
    );
    initializedResolve = () => {
      clearTimeout(timer);
      resolveReady();
    };
  });
  createInterface({ input: child.stdout }).on('line', (line) => {
    const message = JSON.parse(line);
    originalMessages.push(message);
    if (message.id === 0 && !message.method) {
      child.stdin.write(JSON.stringify({ method: 'initialized' }) + '\n');
      initializedResolve();
    }
    if (message.method === 'item/commandExecution/requestApproval')
      child.stdin.write(JSON.stringify({ id: message.id, result: { decision: 'decline' } }) + '\n');
  });
  child.stdin.write(
    JSON.stringify({
      id: 0,
      method: 'initialize',
      params: { clientInfo: { name: 'fixture-desktop' } },
    }) + '\n',
  );
  await initialized;
  const token = (await readFile(join(transport, 'probe-token'), 'utf8')).trim();
  client = connect(join(transport, 'probe.sock'));
  await new Promise((resolveReady, reject) => {
    client.once('connect', resolveReady);
    client.once('error', reject);
  });
  const pending = new Map();
  client.on('error', () => {});
  client.once('close', () => {
    for (const call of pending.values()) {
      clearTimeout(call.timer);
      call.reject(new Error('Fixture transport disconnected'));
    }
    pending.clear();
  });
  createInterface({ input: client }).on('line', (line) => {
    const m = JSON.parse(line),
      call = pending.get(m.id);
    if (call) {
      pending.delete(m.id);
      clearTimeout(call.timer);
      call.resolve(m);
    }
  });
  return (method, params = {}, id = randomUUID()) =>
    new Promise((resolveResult, reject) => {
      const timer = setTimeout(() => {
        pending.delete(id);
        reject(new Error('Fixture probe timeout'));
      }, 5000);
      pending.set(id, { resolve: resolveResult, reject, timer });
      client.write(JSON.stringify({ id, method, params, token }) + '\n');
    });
}
async function stop() {
  client?.destroy();
  if (child?.exitCode === null) {
    const closed = new Promise((resolveClosed) => child.once('exit', resolveClosed));
    child.stdin.end();
    await closed;
  }
}
try {
  let rpc = await boot(false);
  let status = (await rpc('bridge/status')).result;
  assert.equal(status.ready, true);
  assert.equal(status.writeProbeEnabled, false);
  passed++;
  assert.match(
    (await rpc('probe/start', { projectId: 'fixture-project' })).error.message,
    /disabled/,
  );
  passed++;
  const bad = connect(join(transport, 'probe.sock'));
  bad.on('error', () => {});
  const badClosed = new Promise((resolveClosed) => bad.once('close', resolveClosed));
  bad.write(JSON.stringify({ id: 'bad-token', method: 'bridge/status', token: 'invalid' }) + '\n');
  await badClosed;
  passed++;
  await stop();
  rpc = await boot(true);
  status = (await rpc('bridge/status')).result;
  assert.match(
    (await rpc('probe/start', { projectId: 'different-project' })).error.message,
    /authorized/,
  );
  passed++;
  assert.ok((await rpc('turn/start', { threadId: 'original-existing' })).error);
  passed++;
  assert.ok(
    (await rpc('probe/first-text', { threadId: 'original-existing', text: 'fixture' })).error,
  );
  passed++;
  const operation = randomUUID();
  const thread = (await rpc('probe/start', { projectId: 'fixture-project' }, operation)).result
    .thread;
  assert.equal(thread.projectId, 'fixture-project');
  assert.equal(thread.id, 'fixture-thread-' + status.executorPid);
  passed++;
  assert.equal(
    (await rpc('probe/status', { operationId: operation })).result.operation.threadId,
    thread.id,
  );
  passed++;
  assert.ok((await rpc('probe/start', { projectId: 'fixture-project' }, operation)).error);
  passed++;
  const turn = (await rpc('probe/first-text', { threadId: thread.id, text: 'fixture first input' }))
    .result.turn;
  assert.equal(turn.id, 'fixture-turn');
  assert.ok(
    originalMessages.some((m) => m.method === 'thread/started' && m.params.thread.id === thread.id),
  );
  assert.ok(originalMessages.some((m) => m.method === 'item/commandExecution/requestApproval'));
  passed++;
  assert.ok((await rpc('probe/first-text', { threadId: thread.id, text: 'duplicate' })).error);
  passed++;
  await stop();
  rpc = await boot(true);
  assert.ok((await rpc('probe/start', { projectId: 'fixture-project' }, operation)).error);
  assert.equal(
    (await rpc('probe/status', { operationId: operation })).result.operation.threadId,
    thread.id,
  );
  passed++;
  const calls = (await readFile(join(directory, 'calls.jsonl'), 'utf8'))
    .trim()
    .split('\n')
    .map((line) => JSON.parse(line));
  assert.equal(calls.filter((m) => m.method === 'thread/start').length, 1);
  assert.equal(calls.filter((m) => m.method === 'turn/start').length, 1);
  assert.equal(calls.filter((m) => m.method === 'response').length, 1);
  passed++;
  await writeFile(join(directory, 'disconnect-on-start'), 'fixture only');
  const uncertainOperation = randomUUID();
  const response = await rpc(
    'probe/start',
    { projectId: 'fixture-project' },
    uncertainOperation,
  ).catch(() => ({ error: { code: -32001 } }));
  assert.equal(response.error.code, -32001);
  const intents = JSON.parse(await readFile(join(transport, 'probe-intents.json'), 'utf8'));
  assert.equal(intents[uncertainOperation].phase, 'outcome-unknown');
  passed++;
  await stop();
  await rm(join(directory, 'disconnect-on-start'));
  rpc = await boot(true);
  assert.ok((await rpc('probe/start', { projectId: 'fixture-project' }, uncertainOperation)).error);
  assert.equal(
    (await rpc('probe/status', { operationId: uncertainOperation })).result.operation.phase,
    'outcome-unknown',
  );
  const after = (await readFile(join(directory, 'calls.jsonl'), 'utf8'))
    .trim()
    .split('\n')
    .map((line) => JSON.parse(line));
  assert.equal(after.filter((m) => m.method === 'thread/start').length, 2);
  passed++;
} finally {
  await stop();
  await rm(directory, { recursive: true, force: true });
}
process.stdout.write(
  JSON.stringify({
    suite: 'desktop-stdio-entry-fixture',
    passed,
    failed: 0,
    realDesktopCreationVerified: false,
  }) + '\n',
);
