import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { spawn, execFileSync } from 'node:child_process';
import { createInterface } from 'node:readline';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { DesktopRuntime, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { preparedAdapterDigest } from '../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import {
  protectWindowsStateDirectory,
  protectWindowsStateFile,
} from '../packages/codex-adapter/dist/desktop-platform.js';

// Actual HTTP Host restarts, original owner execution and archived-controller
// independence. Only a dedicated acceptance Host is ever terminated.
const executable = process.env.CODEX_EXECUTABLE;
const identityFile = process.env.PREPARED_ACCEPTANCE_IDENTITY_FILE;
const controllerId = process.env.PREPARED_ARCHIVED_CONTROLLER_ID;
const unloadedThreadId = process.env.PREPARED_UNLOADED_THREAD_ID;
assert.ok(
  executable && identityFile && controllerId && unloadedThreadId,
  'Select installed CLI, identity, archived controller and an existing unloaded acceptance chat',
);
const { hostId } = JSON.parse(await readFile(identityFile, 'utf8'));
const directory = resolve('.local/desktop-prepared-lifecycle', randomUUID());
await mkdir(directory, { recursive: true, mode: 0o700 });
protectWindowsStateDirectory(directory);
assert.notEqual(resolve(identityFile), join(directory, 'runtime.json'));
await writeFile(
  join(directory, 'runtime.json'),
  JSON.stringify({
    hostId,
    projects: [],
    threads: [],
    attachments: [],
    receipts: [],
    creations: {},
  }),
  { mode: 0o600 },
);
protectWindowsStateFile(join(directory, 'runtime.json'));
const config = join(directory, 'bridge.json');
const options = {
  mode: 'desktop-runtime',
  executable,
  ...(process.env.CODEX_DESKTOP_WINDOWS_CONFIG
    ? { windowsDesktopConfig: process.env.CODEX_DESKTOP_WINDOWS_CONFIG }
    : {}),
};
const witness = new DesktopRuntime(options, hostId, 'Lifecycle independent witness');
const report = {
  kind: 'real-desktop-prepared-lifecycle',
  hostId,
  adapterSha256: preparedAdapterDigest(),
  gates: {},
  hostPids: [],
  controllerId,
};
const reportPath = join(directory, 'report.json');
const save = () => writeFile(reportPath, JSON.stringify(report, null, 2), { mode: 0o600 });
const wait = async (probe, name, timeout = 120000) => {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const result = await probe();
    if (result) return result;
    await new Promise((done) => setTimeout(done, 250));
  }
  throw Error('Timed out: ' + name);
};
const archived = async () => {
  let cursor;
  do {
    const result = await witness.reader.read('thread/list', {
      limit: 100,
      sortKey: 'updated_at',
      modelProviders: [],
      useStateDbOnly: true,
      archived: true,
      ...(cursor ? { cursor } : {}),
    });
    if (result.data.some((thread) => thread.id === controllerId)) return true;
    cursor = result.nextCursor;
  } while (cursor);
  return false;
};
const originalProcesses = () =>
  process.platform === 'win32'
    ? { ...witness.ipc.windowsIdentity }
    : execFileSync('/bin/ps', ['-axo', 'pid,ppid,command'], { encoding: 'utf8' })
        .split('\n')
        .map((line) => line.trim().match(/^(\d+)\s+(\d+)\s+(.*)$/))
        .filter(Boolean)
        .filter(
          (match) =>
            /^\/Applications\/ChatGPT.app\/Contents\/MacOS\/ChatGPT(?: |$)/.test(match[3]) ||
            (match[3].includes('/codex-cli/') &&
              match[3].includes('app-server') &&
              Number(match[2]) === report.originalPid),
        )
        .map((match) => [Number(match[1]), Number(match[2])]);
let child, endpoint, token;
const request = async (path, body) => {
  const result = await fetch(endpoint + path, {
    headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' },
    ...(body ? { method: 'POST', body: JSON.stringify(body) } : {}),
    signal: AbortSignal.timeout(35000),
  });
  if (!result.ok) {
    const failure = await result.json();
    throw Error('Host HTTP ' + result.status + ' ' + path.split('?')[0] + ': ' + failure.error);
  }
  return result.json();
};
const launch = async () => {
  const env = {};
  for (const name of [
    'HOME',
    'USER',
    'LOGNAME',
    'PATH',
    'TMPDIR',
    'TEMP',
    'TMP',
    'SystemRoot',
    'USERPROFILE',
    'LOCALAPPDATA',
    'APPDATA',
    'COMSPEC',
    'CODEX_HOME',
  ])
    if (process.env[name]) env[name] = process.env[name];
  Object.assign(env, {
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    CODEX_EXECUTABLE: executable,
    CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: config,
    HOST_AGENT_STATE_DIR: directory,
    HOST_AGENT_ROOT: process.cwd(),
    HOST_AGENT_NAME: 'Independent bridge lifecycle acceptance',
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: '0',
  });
  if (process.env.CODEX_DESKTOP_WINDOWS_CONFIG)
    env.CODEX_DESKTOP_WINDOWS_CONFIG = process.env.CODEX_DESKTOP_WINDOWS_CONFIG;
  child = spawn(process.execPath, [resolve('apps/host-agent/dist/index.js')], {
    env,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  child.stderr.resume();
  child.on('error', (error) => {
    report.launchError = error.message;
  });
  report.hostPids.push(child.pid);
  let address;
  const lines = createInterface({ input: child.stdout });
  lines.on('line', (line) => {
    try {
      const data = JSON.parse(line);
      if (data.service === 'host-agent' && data.address?.port) address = data.address;
    } catch {
      // Ignore non-JSON service diagnostics; never publish them as evidence.
    }
  });
  await wait(() => {
    if (child.exitCode !== null || report.launchError)
      throw Error('Dedicated Host failed to start');
    return address;
  }, 'dedicated HTTP Host');
  endpoint = 'http://127.0.0.1:' + address.port;
  token = (await readFile(join(directory, 'access-token'), 'utf8')).trim();
  assert.equal((await request('/v1/workspace')).hostId, hostId);
  assert.equal((await request('/v1/desktop/creation-status?hostId=' + hostId)).available, true);
  await save();
};
const terminate = async (signal) => {
  const target = child;
  child = undefined;
  await new Promise((done) => {
    target.once('close', done);
    target.kill(signal);
  });
};
try {
  await witness.connect();
  report.desktopVersion = witness.version;
  const initial = originalProcesses();
  if (process.platform !== 'win32') report.originalPid = initial[0]?.[0];
  report.originalProcessesBefore = originalProcesses();
  assert.equal(
    await archived(),
    true,
    'The original controller fixture must genuinely be archived',
  );
  report.controllerArchivedBefore = true;
  await writeFile(
    config,
    JSON.stringify({ hostId, desktopVersion: witness.version, validationOnly: true }),
    { mode: 0o600 },
  );
  protectWindowsStateFile(config);
  await launch();

  // Select only a dedicated historical acceptance chat. Empty zero-user
  // metadata is omitted from the official list and is covered by the handoff probe.
  report.unloadedThreadId = unloadedThreadId;
  await witness.ipc.follow(report.unloadedThreadId, 1000, true);
  assert.equal(witness.ipc.hasOwner(report.unloadedThreadId), false);
  report.unloadedBefore = true;
  report.ownerProbeBefore = 'no-verified-owner';
  const workspace = await request('/v1/workspace');
  assert.ok(workspace.threads.some((thread) => thread.id === report.unloadedThreadId));
  const openId = randomUUID();
  await request('/v1/desktop/open', {
    hostId,
    threadId: report.unloadedThreadId,
    clientOperationId: openId,
  });
  await wait(
    async () =>
      (
        await request(
          '/v1/desktop/open-status?' +
            new URLSearchParams({ hostId, threadId: report.unloadedThreadId, id: openId }),
        )
      ).phase === 'opened',
    'public unloaded open',
  );
  await witness.ipc.follow(report.unloadedThreadId, 15000, true);
  assert.equal(witness.ipc.hasOwner(report.unloadedThreadId), true);
  const openNonce = randomUUID();
  report.openReplyId = randomUUID();
  await save();
  await request('/v1/replies', {
    hostId,
    threadId: report.unloadedThreadId,
    clientMessageId: report.openReplyId,
    text:
      '未加载会话打开验收：调用原桌面的 list_projects 只读工具，然后仅回复 OPEN_OK_' +
      openNonce +
      '。不要改文件或其他聊天。',
    attachmentIds: [],
    mode: 'next-turn',
    effort: 'xhigh',
    permissionMode: 'read-only',
  });
  const opened = await wait(
    () =>
      desktopTurns(witness.ipc.state(report.unloadedThreadId) ?? {}).find(
        (turn) =>
          turn.status === 'completed' &&
          turn.items.some(
            (item) => item.type === 'agentMessage' && item.text?.includes('OPEN_OK_' + openNonce),
          ),
      ),
    'opened original tools and reply',
  );
  assert.ok(
    opened.items.some(
      (item) =>
        item.type === 'mcpToolCall' && item.tool === 'list_projects' && item.status === 'completed',
    ),
  );
  report.unloadedOpen = true;
  await save();

  const id = randomUUID(),
    nonce = randomUUID();
  report.recoveryOperationId = id;
  report.recoveryNonce = nonce;
  await save();
  const input = {
    hostId,
    clientOperationId: id,
    projectId: null,
    text:
      'Host 重启恢复验收：调用原桌面的 list_projects 只读工具，然后仅回复 RECOVERY_OK_' +
      nonce +
      '。禁止更改其他任务。',
    attachments: [],
    effort: 'xhigh',
    permissionMode: 'read-only',
  };
  await request('/v1/desktop/creations', input);
  await request('/v1/desktop/creations/submit', { hostId, clientOperationId: id });
  const journalPath = join(directory, 'desktop-prepared-bridge', id + '.json');
  const released = await wait(async () => {
    try {
      const r = JSON.parse(await readFile(journalPath, 'utf8'));
      return r.phase === 'submitted' && r.exitCode === 0 && r.threadId ? r : null;
    } catch {
      return null;
    }
  }, 'durable first input submitted');
  report.recoveryThreadId = released.threadId;
  report.preparationPid = released.pid;
  assert.throws(
    () => process.kill(released.pid, 0),
    'Preparation process must already have exited',
  );
  await save();
  await terminate('SIGKILL');
  await launch();
  const queryPath = '/v1/desktop/creations?' + new URLSearchParams({ hostId, id });
  const confirmed = await wait(async () => {
    const result = await request(queryPath);
    assert.notEqual(result.phase, 'rejected');
    return result.phase === 'confirmed' ? result : null;
  }, 'same operation after Host crash');
  assert.equal(confirmed.threadId, released.threadId);
  await witness.ipc.follow(released.threadId, 15000, true);
  await wait(
    () =>
      desktopTurns(witness.ipc.state(released.threadId) ?? {}).find(
        (turn) =>
          turn.status === 'completed' &&
          turn.items.some(
            (item) => item.type === 'agentMessage' && item.text?.includes('RECOVERY_OK_' + nonce),
          ),
      ),
    'original recovered model',
  );
  await terminate('SIGTERM');
  await launch();
  assert.equal((await request(queryPath)).threadId, released.threadId);
  assert.equal(
    (await request('/v1/desktop/creations/submit', { hostId, clientOperationId: id })).threadId,
    released.threadId,
  );
  const after = JSON.parse(await readFile(journalPath, 'utf8'));
  assert.equal(after.pid, released.pid);
  assert.equal(after.threadId, released.threadId);
  const turns = desktopTurns(witness.ipc.state(released.threadId));
  assert.equal(turns.length, 1);
  assert.equal(turns[0].items.filter((item) => item.type === 'userMessage').length, 1);
  assert.ok(
    turns[0].items.some(
      (item) =>
        item.type === 'mcpToolCall' && item.tool === 'list_projects' && item.status === 'completed',
    ),
  );
  // The restarted Host must recover the existing owner's live subscription
  // itself; the phone must not need a desktop click to make the next reply work.
  const replyNonce = randomUUID();
  report.recoveredReplyNonce = replyNonce;
  report.recoveredReplyMessageId = randomUUID();
  await save();
  report.recoveredReplyReceipt = await request('/v1/replies', {
    hostId,
    threadId: released.threadId,
    clientMessageId: report.recoveredReplyMessageId,
    text: '重启后原会话回复验收：只回复 FOLLOWUP_OK_' + replyNonce + '，不调用工具。',
    attachmentIds: [],
    mode: 'next-turn',
    effort: 'xhigh',
  });
  await save();
  assert.ok(
    ['submitted', 'completed'].includes(report.recoveredReplyReceipt.state),
    report.recoveredReplyReceipt.error ?? 'Restarted Host did not submit the new reply',
  );
  await witness.ipc.follow(released.threadId, 15000, true);
  await wait(
    () =>
      desktopTurns(witness.ipc.state(released.threadId) ?? {}).find(
        (turn) =>
          turn.status === 'completed' &&
          turn.items.some(
            (item) =>
              item.type === 'agentMessage' && item.text?.includes('FOLLOWUP_OK_' + replyNonce),
          ),
      ),
    'existing owner reply after Host restart',
  );
  assert.equal(desktopTurns(witness.ipc.state(released.threadId)).length, 2);
  report.recoveredReply = true;
  report.gates.recovery = true;
  assert.equal(await archived(), true);
  report.controllerArchive = true;
  report.originalProcessesAfter = originalProcesses();
  assert.deepEqual(report.originalProcessesAfter, report.originalProcessesBefore);
  report.passed = true;
  report.verifiedAt = new Date().toISOString();
  await save();
  console.log(
    JSON.stringify({
      passed: true,
      recovery: true,
      unloadedOpen: true,
      controllerArchive: true,
      report: reportPath,
    }),
  );
} catch (error) {
  report.passed = false;
  report.error = error.message;
  await save();
  console.log(JSON.stringify({ passed: false, error: error.message, report: reportPath }));
  process.exitCode = 1;
} finally {
  if (child && child.exitCode === null) await terminate('SIGTERM');
  witness.close();
}
