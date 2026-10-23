import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { randomUUID, createHash } from 'node:crypto';
import { deflateSync } from 'node:zlib';
import { resolve, join } from 'node:path';
import { RuntimeSession } from '../packages/codex-adapter/dist/runtime-session.js';
import { DesktopRuntime, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { preparedAdapterDigest } from '../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import {
  protectWindowsStateDirectory,
  protectWindowsStateFile,
} from '../packages/codex-adapter/dist/desktop-platform.js';

// Real original-desktop execution on isolated Host state. Never promotes production proof.
const executable = process.env.CODEX_EXECUTABLE;
if (!executable) throw Error('Set the installed official CLI');
const directory = resolve(
  process.env.PREPARED_ACCEPTANCE_DIRECTORY ??
    join('.local/desktop-prepared-acceptance', randomUUID()),
);
await mkdir(directory, { recursive: true, mode: 0o700 });
protectWindowsStateDirectory(directory);
const config = join(directory, 'bridge.json');
const options = {
  mode: 'desktop-runtime',
  executable,
  directory,
  root: process.cwd(),
  name: '独立桥接验收',
  desktopPreparedBridge: config,
  ...(process.env.CODEX_DESKTOP_WINDOWS_CONFIG
    ? { windowsDesktopConfig: process.env.CODEX_DESKTOP_WINDOWS_CONFIG }
    : {}),
};
// Reuse only the local installed Host identity for acceptance; no history,
// credentials or pending operations are copied into the isolated state.
if (process.env.PREPARED_ACCEPTANCE_IDENTITY_FILE) {
  const identityFile = resolve(process.env.PREPARED_ACCEPTANCE_IDENTITY_FILE);
  assert.notEqual(identityFile, join(directory, 'runtime.json'));
  const { hostId } = JSON.parse(await readFile(identityFile, 'utf8'));
  assert.match(hostId, /^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i);
  let existing;
  try {
    existing = JSON.parse(await readFile(join(directory, 'runtime.json'), 'utf8'));
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  if (existing) assert.equal(existing.hostId, hostId);
  else
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
}
const session = new RuntimeSession(options);
const reader = new DesktopRuntime(options, session.hostId, '独立核对');
let previous;
try {
  previous = JSON.parse(await readFile(join(directory, 'report.json'), 'utf8'));
} catch {
  /* A fresh dedicated acceptance state. */
}
if (previous && previous.adapterSha256 !== preparedAdapterDigest()) {
  throw Error('Keep the old-build report unchanged; use fresh acceptance state for the new build');
}
if (previous?.passed === true) {
  assert.equal(previous.hostId, session.hostId);
  assert.equal(previous.adapterSha256, preparedAdapterDigest());
  console.log(
    JSON.stringify({
      passed: true,
      reusedExistingReport: true,
      report: join(directory, 'report.json'),
    }),
  );
  session.close();
  reader.close();
  process.exit(0);
}
if (previous?.approvalIntent || previous?.stopMessageId) {
  throw Error('Reconcile the existing control receipt; never rerun control submissions blindly');
}
const nonce = previous?.nonce ?? randomUUID().slice(0, 8);
const scenario = process.env.PREPARED_ACCEPTANCE_SCENARIO ?? 'basic';
const report = {
  kind: 'real-desktop-prepared-acceptance',
  hostId: session.hostId,
  adapterSha256: preparedAdapterDigest(),
  nonce,
  scenario,
  gates: {},
  operations: [],
};
const path = join(directory, 'report.json');
const save = () => writeFile(path, JSON.stringify(report, null, 2), { mode: 0o600 });
const wait = async (probe, description) => {
  const deadline = Date.now() + 120_000;
  while (Date.now() < deadline) {
    const result = await probe();
    if (result) return result;
    await new Promise((done) => setTimeout(done, 350));
  }
  throw Error('Timed out: ' + description);
};
try {
  await reader.connect();
  report.desktopVersion = reader.version;
  await writeFile(
    config,
    JSON.stringify({
      hostId: session.hostId,
      desktopVersion: reader.version,
      validationOnly: true,
    }),
    { mode: 0o600 },
  );
  protectWindowsStateFile(config);
  await session.connect();
  let workspace = await session.workspace();
  const project = process.env.DESKTOP_CREATION_PROJECT_ID ?? null;
  if (project && !workspace.projects.some((p) => p.id === project))
    throw Error('Selected exact project missing');
  if (scenario === 'empty-project' && !previous) {
    const catalog = await reader.reader.desktopWorkspace();
    assert.ok(
      project &&
        !catalog.threads.some((t) => t.projectId === project || t.projectIds?.includes(project)),
    );
    report.emptyBefore = true;
  }
  const operation = previous?.operations?.[0]?.id ?? randomUUID();
  report.operations.push({ id: operation, projectId: project });
  await save();
  let files = [];
  let expected = 'BRIDGE_OK_' + nonce;
  let text =
    '独立桥接根因验收 ' +
    nonce +
    '：请调用原 Codex 桌面的 list_projects 工具，只读取项目清单，不创建或更改任何任务；成功后仅回复 ' +
    expected +
    '。';
  if (scenario === 'attachments') {
    const hidden = previous?.hidden ?? randomUUID();
    report.hidden = hidden;
    const crc = (bytes) => {
      let value = 0xffffffff;
      for (const byte of bytes) {
        value ^= byte;
        for (let i = 0; i < 8; i++) value = (value >>> 1) ^ (value & 1 ? 0xedb88320 : 0);
      }
      return (value ^ 0xffffffff) >>> 0;
    };
    const chunk = (type, bytes) => {
      const name = Buffer.from(type),
        size = Buffer.alloc(4),
        checksum = Buffer.alloc(4);
      size.writeUInt32BE(bytes.length);
      checksum.writeUInt32BE(crc(Buffer.concat([name, bytes])));
      return Buffer.concat([size, name, bytes, checksum]);
    };
    const header = Buffer.alloc(13);
    header.writeUInt32BE(16);
    header.writeUInt32BE(16, 4);
    header[8] = 8;
    header[9] = 2;
    const pixels = Buffer.alloc(16 * 49);
    for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) pixels[y * 49 + 1 + x * 3] = 255;
    files = [
      {
        id: 'red-image-' + nonce,
        name: 'red.png',
        mediaType: 'image/png',
        bytes: Buffer.concat([
          Buffer.from('89504e470d0a1a0a', 'hex'),
          chunk('IHDR', header),
          chunk('IDAT', deflateSync(pixels)),
          chunk('IEND', Buffer.alloc(0)),
        ]),
      },
      {
        id: 'hidden-text-' + nonce,
        name: 'hidden.txt',
        mediaType: 'text/plain',
        bytes: Buffer.from('附件校验码：' + hidden),
      },
    ];
    expected = hidden;
    text =
      '独立桥接混合首条验收 ' +
      nonce +
      '：调用原桌面 list_projects 只读工具，然后识别所附图片的主色，并用工具读取所附 TXT；仅回复“颜色：<主色>；校验码：<TXT 内的完整随机码>”。不得修改文件或任务。随机码没有写在本条正文中。';
  }
  session.prepareDesktopCreation({
    hostId: session.hostId,
    clientOperationId: operation,
    projectId: project,
    text,
    attachments: files.map(({ id, name, mediaType, bytes }) => ({
      id,
      name,
      mediaType,
      sizeBytes: bytes.length,
      sha256: createHash('sha256').update(bytes).digest('hex'),
    })),
    effort: 'xhigh',
    permissionMode: 'read-only',
  });
  const preparedStatus = await session.queryDesktopCreation(operation);
  if (preparedStatus.phase === 'prepared')
    for (const file of files) session.uploadDesktopCreation(operation, file.id, file.bytes);
  await save();
  await session.submitDesktopCreation(operation);
  const result = await wait(async () => {
    const r = await session.queryDesktopCreation(operation);
    if (r.phase === 'rejected') throw Error(r.error);
    return r.phase === 'confirmed' ? r : null;
  }, 'exact creation receipt');
  report.operations[0].threadId = result.threadId;
  await save();
  await reader.ipc.follow(result.threadId, 15000, true);
  const completed = await wait(
    () =>
      desktopTurns(reader.ipc.state(result.threadId) ?? {}).find(
        (t) =>
          t.status === 'completed' &&
          t.items.some((i) => i.type === 'agentMessage' && i.text?.includes(expected)),
      ),
    'original model and tools',
  );
  const tool = completed.items.find(
    (i) => i.type === 'mcpToolCall' && i.tool === 'list_projects' && i.status === 'completed',
  );
  assert.ok(tool, 'Original Codex App tools must actually be restored');
  assert.equal(
    desktopTurns(reader.ipc.state(result.threadId)).length,
    1,
    'No initializer model turn',
  );
  assert.equal(result.threadId, reader.ipc.state(result.threadId).id);
  assert.equal(
    reader.ipc.state(result.threadId).workspaceKind,
    project ? 'project' : 'projectless',
  );
  assert.equal(completed.items.filter((i) => i.type === 'userMessage').length, 1);
  const metadata = (
    await reader.reader.read('thread/read', { threadId: result.threadId, includeTurns: false })
  ).thread;
  assert.equal(metadata.projectId ?? null, project);
  if (scenario === 'attachments') {
    assert.ok(
      completed.items.some((i) => i.type === 'agentMessage' && /红|red/i.test(i.text ?? '')),
    );
    report.gates['first-attachments'] = true;
  }
  report.gates[project ? 'project' : 'projectless'] = true;
  if (scenario === 'empty-project') report.gates['empty-project'] = true;
  report.originalTools = true;
  report.noInitializerTurn = true;
  report.firstReply = 'BRIDGE_OK_' + nonce;
  await save();
  // Completion was independently witnessed above. The ordinary submit boundary
  // must refresh its own exact owner snapshot before accepting the next input;
  // a missed Host broadcast must not require the user to refresh or reopen.
  const messageId = previous?.replyMessageId ?? 'reply-' + randomUUID();
  report.replyMessageId = messageId;
  await save();
  await session.submit({
    hostId: session.hostId,
    threadId: result.threadId,
    clientMessageId: messageId,
    text: '只回复 REPLY_OK_' + nonce + '，不调用工具。',
    attachmentIds: [],
    mode: 'next-turn',
    effort: 'xhigh',
  });
  await wait(
    () =>
      desktopTurns(reader.ipc.state(result.threadId) ?? {}).find(
        (t) =>
          t.status === 'completed' &&
          t.items.some((i) => i.type === 'agentMessage' && i.text === 'REPLY_OK_' + nonce),
      ),
    'original reply',
  );
  if (scenario === 'controls') {
    const stopMessage = 'stop-' + randomUUID();
    report.stopMessageId = stopMessage;
    await save();
    await session.submit({
      hostId: session.hostId,
      threadId: result.threadId,
      clientMessageId: stopMessage,
      text: '持续输出至少 1000 行编号文字，不调用工具，直到我停止。',
      attachmentIds: [],
      mode: 'next-turn',
      effort: 'xhigh',
    });
    const active = await wait(
      () =>
        desktopTurns(reader.ipc.state(result.threadId) ?? {}).find(
          (t) => t.status === 'inProgress' && t.params?.clientUserMessageId === stopMessage,
        ),
      'specific running turn',
    );
    await session.stop(result.threadId, active.turnId);
    await wait(
      () =>
        desktopTurns(reader.ipc.state(result.threadId) ?? {}).find(
          (t) => t.turnId === active.turnId && t.status === 'interrupted',
        ),
      'specific original turn stopped',
    );
    report.stopTurnId = active.turnId;
    await save();
    const approvalNonce = randomUUID();
    const command =
      process.platform === 'win32'
        ? "Write-Output '" + approvalNonce + "'"
        : "printf '%s\\n' '" + approvalNonce + "'";
    const approvalMessage = 'approval-' + randomUUID();
    report.approvalIntent = { nonce: approvalNonce, command, messageId: approvalMessage };
    await save();
    await session.submit({
      hostId: session.hostId,
      threadId: result.threadId,
      clientMessageId: approvalMessage,
      text:
        '在本验收聊天中核对真实命令审批。请使用 exec_command 显式请求 sandbox_permissions=require_escalated，运行这个仅输出随机码的无害命令：' +
        command +
        '。不读写文件，不自行批准，不执行其他命令。',
      attachmentIds: [],
      mode: 'next-turn',
      effort: 'xhigh',
      permissionMode: 'read-only',
    });
    const request = await wait(
      () =>
        reader.ipc
          .state(result.threadId)
          ?.requests?.find(
            (q) =>
              q.method === 'item/commandExecution/requestApproval' &&
              q.params?.threadId === result.threadId,
          ),
      'real harmless command approval',
    );
    const approval = session
      .history(result.threadId)
      .runtime.approvals.find((a) => a.turnId === request.params.turnId);
    assert.ok(approval, 'Session must expose the actual original request');
    assert.deepEqual(
      request.params.commandActions,
      [{ type: 'unknown', command }],
      'Only the exact no-op command may be accepted',
    );
    assert.ok(approval.decisions.includes('accept'));
    report.approval = {
      turnId: approval.turnId,
      requestId: approval.id,
      nonce: approvalNonce,
      command,
    };
    await save();
    await session.answer(result.threadId, approval.id, approval.turnId, 'accept', {});
    const approved = await wait(
      () =>
        desktopTurns(reader.ipc.state(result.threadId) ?? {}).find(
          (t) => t.turnId === approval.turnId && t.status === 'completed',
        ),
      'approved original turn',
    );
    assert.ok(
      approved.items.some(
        (i) =>
          i.type === 'commandExecution' &&
          i.exitCode === 0 &&
          i.aggregatedOutput?.includes(approvalNonce),
      ),
    );
    assert.ok(
      !session.history(result.threadId).runtime.approvals.some((a) => a.id === approval.id),
    );
    await assert.rejects(() =>
      session.answer(result.threadId, approval.id, approval.turnId, 'accept', {}),
    );
    report.gates['owner-controls'] = true;
  }
  report.reply = true;
  report.passed = true;
  report.verifiedAt = new Date().toISOString();
  await save();
  console.log(
    JSON.stringify({
      kind: report.kind,
      passed: true,
      gates: report.gates,
      originalTools: true,
      noInitializerTurn: true,
      reply: true,
      threadId: result.threadId,
      report: path,
    }),
  );
} catch (error) {
  report.passed = false;
  report.error = error.message;
  await save();
  console.log(JSON.stringify({ passed: false, error: report.error, report: path }));
  process.exitCode = 1;
} finally {
  session.close();
  reader.close();
}
