import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { DesktopRuntime, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import {
  assertPrivateFile,
  loadWindowsDesktop,
  windowsAdapterDigest,
  protectWindowsStateFile,
} from '../packages/codex-adapter/dist/desktop-platform.js';

if (process.platform !== 'win32') throw new Error('Windows hardware acceptance required');
const repository = dirname(dirname(fileURLToPath(import.meta.url)));
const directory = join(repository, '.local/desktop-runtime');
const configPath = join(directory, 'windows-desktop.json');
const pendingPath = join(directory, 'windows-acceptance-pending.json');
const runtimeFile = join(directory, 'runtime.json');
const jobFile = join(directory, 'windows-host.json');
for (const path of [runtimeFile, jobFile]) assertPrivateFile(path);
const { hostId } = JSON.parse(readFileSync(runtimeFile, 'utf8'));
const environment = JSON.parse(readFileSync(jobFile, 'utf8')).environment;
const original = loadWindowsDesktop(configPath, hostId);
const threadId = process.env.DESKTOP_WINDOWS_TEST_THREAD_ID ?? original.config.validationThreadId;
assert.match(threadId ?? '', /^[a-f0-9-]{36}$/i, 'Select a dedicated loaded idle test chat');
const runtime = new DesktopRuntime(
  {
    mode: 'desktop-runtime',
    desktopVersion: original.archive.version,
    windowsDesktopConfig: configPath,
    executable: environment.CODEX_EXECUTABLE,
  },
  hostId,
  'Windows original owner acceptance',
);
const consent = process.argv[2];
if (consent && !/^--approve-once=[a-f0-9-]{36}$/.test(consent))
  throw new Error('Use no argument to prepare, or --approve-once=<human-approved nonce>');
const state = () => runtime.ipc.state(threadId);
const turns = () => desktopTurns(state() ?? {});
let activeTurn;
let awaitingHuman = false;
const wait = async (predicate, label) => {
  const deadline = Date.now() + 180_000;
  while (Date.now() < deadline) {
    const value = predicate();
    if (value) return value;
    if (!runtime.ipc.hasOwner(threadId)) throw new Error('Original owner lost during ' + label);
    await new Promise((done) => setTimeout(done, 350));
  }
  throw new Error('Timed out: ' + label);
};
const send = async (text) => {
  const clientId = randomUUID();
  await runtime.call('turn/start', {
    threadId,
    clientUserMessageId: clientId,
    approvalPolicy: 'on-request',
    sandboxPolicy: { type: 'readOnly', networkAccess: false },
    input: [{ type: 'text', text }],
  });
  const turn = await wait(
    () => turns().find((turn) => turn.params?.clientUserMessageId === clientId),
    'submitted original turn',
  );
  activeTurn = turn.turnId;
  return activeTurn;
};
const savePrivate = (path, value) => {
  writeFileSync(path, JSON.stringify(value, null, 2) + '\n', { mode: 0o600 });
  protectWindowsStateFile(path);
  assertPrivateFile(path);
};
const normalize = (value) => value.replaceAll('\\"', '"').replaceAll('\\\\', '\\');
const initial = consent
  ? (() => {
      assertPrivateFile(pendingPath);
      return JSON.parse(readFileSync(pendingPath, 'utf8'));
    })()
  : undefined;
const nonce = initial?.nonce ?? randomUUID();
const reportPath = join(directory, 'windows-acceptance-' + nonce + '.json');
const report = initial
  ? (() => {
      assertPrivateFile(reportPath);
      return JSON.parse(readFileSync(reportPath, 'utf8'));
    })()
  : {
      kind: 'real-windows-desktop-acceptance',
      hostId,
      threadId,
      desktopVersion: original.archive.version,
      executableSha256: original.identity.executableSha256,
      asarSha256: original.archive.asarSha256,
      adapterSha256: windowsAdapterDigest(),
      startedAt: new Date().toISOString(),
      status: 'running',
      gates: { reply: false, stop: false, approval: false },
    };
const saveReport = () => savePrivate(reportPath, report);
saveReport();

try {
  await runtime.connect();
  await runtime.call('thread/resume', { threadId });
  assert.equal(state()?.id, threadId);
  assert.equal(state()?.hostId, 'local');
  if (!consent) {
    assert.ok(!turns().some((turn) => turn.status === 'inProgress'), 'Test chat must be idle');
    const adapterSha256 = windowsAdapterDigest();
    const replyTurnId = await send('仅回复这个随机码，不调用工具：' + nonce);
    await wait(
      () =>
        turns().some(
          (turn) =>
            turn.turnId === replyTurnId &&
            turn.status === 'completed' &&
            turn.items?.some((item) => item.type === 'agentMessage' && item.text === nonce),
        ),
      'original desktop reply',
    );
    report.gates.reply = true;
    saveReport();
    console.log(JSON.stringify({ gate: 'reply', status: 'passed', turnId: replyTurnId }));
    const stopTurnId = await send(
      '这是一轮停止验收。持续输出至少 500 行编号文字，不调用工具，直到我停止。',
    );
    await wait(
      () => turns().some((turn) => turn.turnId === stopTurnId && turn.status === 'inProgress'),
      'running original turn',
    );
    await runtime.call('turn/interrupt', { threadId, turnId: stopTurnId });
    await wait(
      () => turns().some((turn) => turn.turnId === stopTurnId && turn.status === 'interrupted'),
      'original turn stopped',
    );
    report.gates.stop = true;
    saveReport();
    console.log(JSON.stringify({ gate: 'stop', status: 'passed', turnId: stopTurnId }));
    const approvalTurnId = await send(
      `这是一轮无害命令审批验收。只通过 exec_command 显式请求运行 PowerShell 命令 Write-Output '${nonce}' 的审批，sandbox_permissions=require_escalated；用于核对 Windows 原桌面与手机连接器的审批链路。不读写文件、不运行其他命令，不自行批准或绕过审批。`,
    );
    const request = await wait(
      () =>
        state()?.requests?.find(
          (request) =>
            request.method === 'item/commandExecution/requestApproval' &&
            request.params?.threadId === threadId &&
            request.params?.turnId === approvalTurnId,
        ),
      'real command approval',
    );
    const after = loadWindowsDesktop(configPath, hostId);
    assert.equal(after.identity.pid, original.identity.pid);
    assert.equal(after.archive.asarSha256, original.archive.asarSha256);
    assert.equal(windowsAdapterDigest(), adapterSha256);
    savePrivate(pendingPath, {
      kind: 'pending-windows-acceptance',
      hostId,
      threadId,
      nonce,
      replyTurnId,
      stopTurnId,
      approvalTurnId,
      requestId: request.id,
      requestParams: request.params,
      desktopPid: original.identity.pid,
      desktopVersion: original.archive.version,
      executableSha256: original.identity.executableSha256,
      asarSha256: original.archive.asarSha256,
      adapterSha256,
      preparedAt: new Date().toISOString(),
    });
    awaitingHuman = true;
    report.status = 'awaiting-human-approval';
    saveReport();
    console.log(
      JSON.stringify({
        status: 'awaiting-human-approval',
        threadId,
        turnId: approvalTurnId,
        requestId: request.id,
        nonce,
        command: request.params.command,
        gates: { reply: true, stop: true, approval: false },
      }),
    );
  } else {
    assertPrivateFile(pendingPath);
    const record = JSON.parse(readFileSync(pendingPath, 'utf8'));
    assert.equal(consent, '--approve-once=' + record.nonce, 'Consent must match this request');
    for (const [key, expected] of Object.entries({
      kind: 'pending-windows-acceptance',
      hostId,
      threadId,
      desktopPid: original.identity.pid,
      desktopVersion: original.archive.version,
      executableSha256: original.identity.executableSha256,
      asarSha256: original.archive.asarSha256,
      adapterSha256: windowsAdapterDigest(),
    }))
      assert.equal(record[key], expected);
    const reply = turns().find((turn) => turn.turnId === record.replyTurnId);
    assert.equal(reply?.status, 'completed');
    assert.equal(reply.params?.input?.[0]?.text, '仅回复这个随机码，不调用工具：' + record.nonce);
    assert.ok(
      reply.items?.some((item) => item.type === 'agentMessage' && item.text === record.nonce),
    );
    assert.equal(turns().find((turn) => turn.turnId === record.stopTurnId)?.status, 'interrupted');
    const request = state()?.requests?.find((request) => request.id === record.requestId);
    assert.equal(
      request?.method,
      'item/commandExecution/requestApproval',
      'Approval expired or already handled on desktop',
    );
    assert.deepEqual(request.params, record.requestParams);
    assert.equal(request.params.threadId, threadId);
    assert.equal(request.params.turnId, record.approvalTurnId);
    const expected = `Write-Output '${record.nonce}'`;
    assert.deepEqual(request.params.commandActions, [{ type: 'unknown', command: expected }]);
    const command = normalize(request.params.command);
    const shells = [
      join(
        process.env.USERPROFILE,
        '.cache/codex-runtimes/codex-primary-runtime/dependencies/native/powershell/pwsh.exe',
      ),
      'C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe',
    ];
    assert.ok(
      command === expected ||
        shells.some((shell) => command === `"${shell}" -NoProfile -Command "${expected}"`),
      'Reject any unrelated command',
    );
    assert.equal(normalize(request.params.cwd), state().cwd);
    assert.ok(request.params.availableDecisions.includes('accept'));
    runtime.registerApproval(
      record.nonce,
      threadId,
      'thread-follower-command-approval-decision',
      request.id,
    );
    await runtime.respond(record.nonce, { decision: 'accept' });
    activeTurn = record.approvalTurnId;
    const turn = await wait(
      () => turns().find((turn) => turn.turnId === activeTurn && turn.status === 'completed'),
      'approved original turn completed',
    );
    const tool = turn.items?.find(
      (item) => item.type === 'commandExecution' && item.id === request.params.itemId,
    );
    assert.equal(tool?.exitCode, 0);
    assert.ok(tool?.aggregatedOutput?.includes(record.nonce));
    assert.ok(!state()?.requests?.some((value) => value.id === request.id));
    const after = loadWindowsDesktop(configPath, hostId);
    assert.equal(after.identity.pid, original.identity.pid);
    assert.equal(after.archive.asarSha256, original.archive.asarSha256);
    assert.equal(windowsAdapterDigest(), record.adapterSha256);
    const proof = {
      kind: 'real-windows-desktop',
      hostId,
      threadId,
      desktopVersion: original.archive.version,
      executableSha256: original.identity.executableSha256,
      asarSha256: original.archive.asarSha256,
      adapterSha256: record.adapterSha256,
      verifiedAt: new Date().toISOString(),
      gates: { reply: true, stop: true, approval: true },
      evidence: {
        replyTurnId: record.replyTurnId,
        stopTurnId: record.stopTurnId,
        approvalTurnId: record.approvalTurnId,
        requestId: request.id,
        nonce: record.nonce,
        humanAuthorized: true,
        desktopPid: original.identity.pid,
      },
    };
    const proofPath = join(directory, 'windows-verification-' + randomUUID() + '.json');
    savePrivate(proofPath, proof);
    savePrivate(configPath, { ...original.config, validationOnly: false, verification: proofPath });
    Object.assign(report, { status: 'passed', finishedAt: proof.verifiedAt, gates: proof.gates });
    saveReport();
    console.log(
      JSON.stringify({
        status: 'passed',
        gates: proof.gates,
        acceptanceRecorded: true,
        replyPolicy: 'actual-owner',
        creationAndOpen: 'not-tested',
        iPhoneCrossNetwork: 'not-tested',
      }),
    );
  }
} catch (error) {
  Object.assign(report, { status: 'failed', finishedAt: new Date().toISOString() });
  saveReport();
  console.error(
    JSON.stringify({ status: report.status, gates: report.gates, replyPolicy: 'actual-owner' }),
  );
  throw error;
} finally {
  if (
    !awaitingHuman &&
    activeTurn &&
    turns().some((turn) => turn.turnId === activeTurn && turn.status === 'inProgress')
  )
    await runtime.call('turn/interrupt', { threadId, turnId: activeTurn }).catch(() => {});
  runtime.close();
}
