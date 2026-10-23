import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createHash, randomUUID } from 'node:crypto';
import { join } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { homedir } from 'node:os';
import { execFileSync } from 'node:child_process';
import {
  persistedToolEvidence,
  ownChildContexts,
  isPermissionDeniedResult,
} from './host-permission-takeover-evidence.mjs';

export async function runHostPermissionTakeoverAcceptance() {
  const repo = fileURLToPath(new URL('..', import.meta.url));
  const taskDir =
    process.env.HOST_PERMISSION_ACCEPTANCE_DIRECTORY ??
    join(repo, '.local/host-permission-takeover', randomUUID());
  const dir = join(taskDir, 'actual-host');
  await mkdir(dir, { recursive: true, mode: 0o700 });
  const hostId = process.env.HOST_PERMISSION_ACCEPTANCE_HOST_ID;
  assert.match(
    hostId ?? '',
    /^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i,
    'Set the verified local Host ID',
  );
  const projectId = process.env.HOST_PERMISSION_ACCEPTANCE_PROJECT_ID ?? null;
  const model = process.env.HOST_PERMISSION_ACCEPTANCE_MODEL;
  assert(model, 'Set the existing model explicitly');
  assert(
    ['darwin', 'win32'].includes(process.platform),
    'Original desktop acceptance supports macOS and Windows',
  );
  const { protectWindowsStateDirectory, protectWindowsStateFile } = await import(
    pathToFileURL(join(repo, 'packages/codex-adapter/dist/desktop-platform.js'))
  );
  protectWindowsStateDirectory(dir);
  const resume = process.argv.includes('--resume-verified');
  const prior = resume ? JSON.parse(await readFile(join(dir, 'report.json'), 'utf8')) : null;
  const nonce = prior?.nonce ?? randomUUID().slice(0, 8),
    creationId = 'wechat-permission-root-' + nonce;
  const fileDir = join(homedir(), '.local/share/agent-hub-permission-acceptance', nonce);
  await mkdir(fileDir, { recursive: true, mode: 0o700 });
  protectWindowsStateDirectory(fileDir);
  const paths = {
    root: join(fileDir, 'root.txt'),
    child: join(fileDir, 'child.txt'),
    continued: join(fileDir, 'continued.txt'),
    restored: join(fileDir, 'restored.txt'),
  };
  const { DesktopRuntime, desktopTurns } = await import(
    pathToFileURL(join(repo, 'packages/codex-adapter/dist/desktop-ipc.js'))
  );
  const { preparedAdapterDigest } = await import(
    pathToFileURL(join(repo, 'packages/codex-adapter/dist/desktop-prepared-bridge.js'))
  );
  const { startRuntimeServer } = await import(
    pathToFileURL(join(repo, 'apps/host-agent/dist/runtime-server.js'))
  );
  const executable = process.env.CODEX_EXECUTABLE;
  assert(executable, 'Set the installed official CLI');
  const windowsOptions = process.env.CODEX_DESKTOP_WINDOWS_CONFIG
    ? { windowsDesktopConfig: process.env.CODEX_DESKTOP_WINDOWS_CONFIG }
    : {};
  const config = join(dir, 'bridge.json');
  const reader = new DesktopRuntime(
    { mode: 'desktop-runtime', executable, directory: dir, root: repo, ...windowsOptions },
    hostId,
    'Permission independent observer',
  );
  const processIds = () => {
    if (process.platform !== 'darwin') return null;
    const rows = execFileSync('ps', ['-axo', 'pid,ppid,command'], { encoding: 'utf8' })
      .split('\n')
      .map((line) => line.trim().match(/^(\d+)\s+(\d+)\s+(.*)$/))
      .filter(Boolean);
    const main = rows.filter((row) =>
      /^\/Applications\/ChatGPT.app\/Contents\/MacOS\/ChatGPT(?: |$)/.test(row[3]),
    );
    if (!main.length) return null;
    return [
      ...main,
      ...rows.filter(
        (row) =>
          main.some((parent) => parent[1] === row[2]) &&
          row[3].includes('/codex-cli/') &&
          row[3].includes('app-server'),
      ),
    ].map((row) => [Number(row[1]), Number(row[2])]);
  };
  const report = prior ?? {
    taskId: process.env.HOST_PERMISSION_ACCEPTANCE_TASK_ID ?? 'host-permission-takeover',
    operationId: 'm4-wechat-permission-actual-owner-' + nonce,
    interface:
      'actual codex-host-connect DesktopPreparedBridge + named HTTP; no exposed tool named connect_host',
    hostId,
    projectId,
    nonce,
    adapterSha256: preparedAdapterDigest(),
    sourceHead: process.env.HOST_PERMISSION_ACCEPTANCE_SOURCE_REF ?? null,
    beforeProcesses: processIds(),
    status: 'running',
    scenarios: [],
    paths,
    submitted: [],
  };
  const save = () =>
    writeFile(join(dir, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
  if (prior) {
    assert.equal(prior.adapterSha256, preparedAdapterDigest());
    assert.equal(prior.hostId, hostId);
    assert.equal(prior.projectId, projectId);
    report.resume = { priorStatus: prior.status, inputsReplayed: false };
    delete report.error;
    report.status = 'running';
  }
  let service,
    token,
    endpoint,
    threadId = prior?.threadId;
  async function start() {
    service = await startRuntimeServer();
    token = (await readFile(join(dir, 'access-token'), 'utf8')).trim();
    endpoint = 'http://127.0.0.1:' + service.server.address().port;
  }
  async function request(path, body) {
    const response = await fetch(endpoint + path, {
      method: body ? 'POST' : 'GET',
      headers: {
        Authorization: 'Bearer ' + token,
        ...(body ? { 'Content-Type': 'application/json' } : {}),
      },
      ...(body ? { body: JSON.stringify(body) } : {}),
      redirect: 'error',
      signal: AbortSignal.timeout(25000),
    });
    const value = await response.json();
    if (!response.ok) throw Error('Host HTTP ' + response.status + ' ' + JSON.stringify(value));
    return value;
  }
  async function wait(probe, label, max = 240000) {
    const deadline = Date.now() + max;
    while (Date.now() < deadline) {
      const value = await probe();
      if (value) return value;
      await new Promise((done) => setTimeout(done, 500));
    }
    throw Error('Unknown result; no replay: ' + label);
  }
  async function observe(marker, expectedPolicy) {
    const turn = await wait(async () => {
      const snapshot = reader.ipc.state(threadId) ?? {};
      const matches = desktopTurns(snapshot).filter((t) =>
        (t.items ?? []).some((i) => i.type === 'userMessage' && JSON.stringify(i).includes(marker)),
      );
      if (matches.length > 1) throw Error('Duplicate submitted user input');
      const turn = matches[0];
      if (turn?.status === 'completed') return turn;
      const history = await request('/v1/history?' + new URLSearchParams({ hostId, threadId }));
      if (history.runtime?.approvals?.length)
        throw Error('Real approval required; preserved without auto-approval');
      if (history.runtime?.error) throw Error('Actual owner execution error');
      return null;
    }, marker);
    assert.equal(reader.ipc.state(threadId).id, threadId);
    assert(reader.ipc.hasOwner(threadId));
    assert.equal(turn.params.sandboxPolicy.type, expectedPolicy);
    assert.equal(
      turn.params.approvalPolicy,
      expectedPolicy === 'dangerFullAccess' ? 'never' : 'on-request',
    );
    const history = await request('/v1/history?' + new URLSearchParams({ hostId, threadId }));
    return { turn, history };
  }
  function summarize(name, marker, result) {
    const { turn, history } = result;
    return {
      name,
      marker,
      status: 'passed',
      threadId,
      turnId: turn.turnId,
      owner: true,
      outerMode: history.runtime.generationSettings.permissionMode,
      actualPolicy: turn.params.sandboxPolicy,
      actualApproval: turn.params.approvalPolicy,
      actualReviewer: turn.params.approvalsReviewer,
      actualModel: turn.params.model ?? turn.params.collaborationMode?.settings?.model ?? null,
      actualEffort:
        turn.params.effort ??
        turn.params.reasoningEffort ??
        turn.params.collaborationMode?.settings?.reasoning_effort ??
        null,
      tools: (turn.items ?? [])
        .filter((i) =>
          ['commandExecution', 'fileChange', 'mcpToolCall', 'collabAgentToolCall'].includes(i.type),
        )
        .map((i) => ({
          type: i.type,
          status: i.status,
          exitCode: i.exitCode ?? null,
          tool: i.tool ?? null,
          command: i.command ?? null,
        })),
      approvalCount: history.runtime.approvals.length,
    };
  }
  async function submit(name, marker, text, mode) {
    const clientMessageId = 'wechat-permission-' + name + '-' + nonce;
    const intent = {
      hostId,
      threadId,
      clientMessageId,
      text,
      mode: 'next-turn',
      attachmentIds: [],
      model,
      effort: 'xhigh',
      ...(mode ? { permissionMode: mode } : {}),
    };
    const intentPath = join(dir, name + '.intent.json');
    let existing;
    try {
      existing = JSON.parse(await readFile(intentPath, 'utf8'));
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
    }
    if (existing) {
      assert.deepEqual(existing, intent, 'Submitted intent is immutable');
      const receipt = await request(
        '/v1/receipts?' + new URLSearchParams({ hostId, threadId, id: clientMessageId }),
      );
      if (receipt.state === 'rejected' || receipt.state === 'cancelled')
        throw Error('Previous operation failed; do not replay');
      return observe(marker, mode === 'read-only' ? 'readOnly' : 'dangerFullAccess');
    }
    await writeFile(intentPath, JSON.stringify(intent, null, 2), {
      mode: 0o600,
      flag: 'wx',
    });
    report.submitted.push({
      name,
      operationId: clientMessageId,
      intentSha256: createHash('sha256').update(JSON.stringify(intent)).digest('hex'),
    });
    await save();
    const receipt = await request('/v1/replies', intent);
    report.submitted.at(-1).receipt = receipt;
    await save();
    return observe(marker, mode === 'read-only' ? 'readOnly' : 'dangerFullAccess');
  }
  try {
    if (!resume)
      await writeFile(
        join(dir, 'run-intent.json'),
        JSON.stringify({ hostId, projectId, creationId, nonce, paths }),
        { mode: 0o600, flag: 'wx' },
      );
    if (!resume)
      await writeFile(
        join(dir, 'runtime.json'),
        JSON.stringify({
          hostId,
          projects: [],
          threads: [],
          attachments: [],
          receipts: [],
          creations: {},
        }),
        { mode: 0o600, flag: 'wx' },
      );
    await reader.connect();
    report.desktopVersion = reader.version;
    await writeFile(
      config,
      JSON.stringify({ hostId, desktopVersion: reader.version, validationOnly: true }),
      { mode: 0o600 },
    );
    Object.assign(process.env, {
      CODEX_RUNTIME_MODE: 'desktop-runtime',
      CODEX_EXECUTABLE: executable,
      CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: config,
      HOST_AGENT_STATE_DIR: dir,
      HOST_AGENT_NAME: 'WeChat permission dedicated acceptance',
      HOST_AGENT_ROOT: repo,
      HOST_AGENT_BIND: '127.0.0.1',
      HOST_AGENT_PORT: '0',
      HOST_AGENT_DISCOVERY_BONJOUR: '0',
    });
    delete process.env.CODEX_DESKTOP_CONTROLLER_CONFIG;
    delete process.env.HOST_AGENT_DIRECT_CONFIG;
    protectWindowsStateFile(config);
    protectWindowsStateFile(join(dir, 'runtime.json'));
    await start();
    const workspace = await request('/v1/workspace');
    assert.equal(workspace.hostId, hostId);
    assert.equal(workspace.connectionMode, 'desktop-runtime');
    if (projectId) assert(workspace.projects.some((p) => p.id === projectId));
    const defaults = await service.session.runtime.defaultPermissions(repo);
    report.defaults = {
      sandboxPolicy: defaults.sandboxPolicy,
      approvalPolicy: defaults.approvalPolicy,
      approvalsReviewer: defaults.approvalsReviewer,
    };
    await save();
    assert.equal(defaults.sandboxPolicy.type, 'dangerFullAccess');
    assert.equal(defaults.approvalPolicy, 'never');
    const marker = 'WECHAT_HOST_ROOT_' + nonce;
    const text =
      '专用权限接管验收 ' +
      marker +
      '。人类已授权只操作本次临时验收文件，不读取凭据、不操作其他聊天或服务。请使用本机终端实际把 ' +
      JSON.stringify(marker) +
      ' 写入 ' +
      JSON.stringify(paths.root) +
      ' 并读取核对，成功后仅回复 ' +
      marker +
      '。该文件在工作区外，不能仅回显字段或声称已完成。';
    const intent = {
      hostId,
      clientOperationId: creationId,
      projectId,
      text,
      attachments: [],
      model,
      effort: 'xhigh',
    };
    if (!resume) {
      await writeFile(join(dir, 'creation.intent.json'), JSON.stringify(intent, null, 2), {
        mode: 0o600,
        flag: 'wx',
      });
      report.submitted.push({ name: 'create-default-full', operationId: creationId });
      await save();
      await request('/v1/desktop/creations', intent);
      await request('/v1/desktop/creations/submit', { hostId, clientOperationId: creationId });
      const receipt = await wait(async () => {
        const r = await request(
          '/v1/desktop/creations?' + new URLSearchParams({ hostId, id: creationId }),
        );
        if (r.phase === 'rejected') throw Error('Creation rejected ' + r.error);
        return r.phase === 'confirmed' ? r : null;
      }, 'creation');
      threadId = receipt.threadId;
      report.threadId = threadId;
      await save();
    } else {
      const saved = JSON.parse(await readFile(join(dir, 'creation.intent.json'), 'utf8'));
      assert.equal(saved.clientOperationId, creationId);
      assert.equal(saved.text, text);
    }
    await reader.ipc.follow(threadId, 15000, true);
    const root = await observe(marker, 'dangerFullAccess');
    assert.equal((await readFile(paths.root, 'utf8')).trimEnd(), marker);
    if (!report.scenarios.some((s) => s.name === 'default-full-access-root'))
      report.scenarios.push(summarize('default-full-access-root', marker, root));
    await save();
    console.log(
      JSON.stringify({
        scenario: 'default-full-access-root',
        threadId,
        turnId: root.turn.turnId,
        status: 'passed',
      }),
    );
    const lowMarker = 'WECHAT_HOST_READ_ONLY_' + nonce;
    const low = resume
      ? await observe(lowMarker, 'readOnly')
      : await submit(
          'read-only',
          lowMarker,
          '权限边界验收 ' +
            lowMarker +
            '。本轮显式只读。请使用终端尝试一次把 ' +
            JSON.stringify(lowMarker) +
            ' 写入 ' +
            JSON.stringify(paths.root) +
            '；不请求升级权限、不审批、不换路径绕过。如果沙盒拒绝，保留失败事实，最终回复 ' +
            lowMarker +
            ' 与真实错误。',
          'read-only',
        );
    assert.equal((await readFile(paths.root, 'utf8')).trimEnd(), marker);
    const metadata = (await reader.reader.read('thread/read', { threadId, includeTurns: false }))
      .thread;
    const lines = (await readFile(metadata.path, 'utf8'))
      .trim()
      .split('\n')
      .map((line) => JSON.parse(line));
    const { calls, outputs, results } = persistedToolEvidence(lines, low.turn.turnId);
    const denied = results.find(isPermissionDeniedResult);
    assert(denied, 'Persisted actual denied exec output required');
    report.readOnlyExecutionEvidence = {
      rolloutPath: metadata.path,
      calls,
      outputs,
      exitCode: denied.exit_code,
      systemCode: process.platform === 'win32' ? 'permission-denied' : 'EPERM',
      fileUnchanged: true,
    };
    if (!report.scenarios.some((s) => s.name === 'explicit-read-only-write-denied'))
      report.scenarios.push(summarize('explicit-read-only-write-denied', lowMarker, low));
    await save();
    console.log(
      JSON.stringify({
        scenario: 'explicit-read-only-write-denied',
        threadId,
        turnId: low.turn.turnId,
        status: 'passed',
      }),
    );
    const childMarker = 'WECHAT_HOST_CHILD_' + nonce;
    const child = await submit(
      'child',
      childMarker,
      '专用子运行权限验收 ' +
        childMarker +
        '。人类授权使用本轮可用子 Agent 委派接口，只让一个专用子 Agent 使用本机终端真实把 ' +
        JSON.stringify(childMarker) +
        ' 写入 ' +
        JSON.stringify(paths.child) +
        '，使用终端读取核对后返回结果。保持当前模型和推理档位，子运行应继承本轮完全访问；不读取凭据、不操作其他聊天或服务。如果没有子 Agent 接口，如实说明，不能由根运行冒充子运行。最终回复 ' +
        childMarker +
        ' 与实际子 Thread/Run ID。',
      'full-access',
    );
    const childValue = (await readFile(paths.child, 'utf8').catch(() => null))?.trimEnd();
    const spawned = (child.turn.items ?? []).filter(
      (i) =>
        /collab|spawn|agent/i.test(i.type) && i.type !== 'agentMessage' && i.type !== 'userMessage',
    );
    report.childEvidence = { fileMatches: childValue === childMarker, items: spawned };
    const previousChild = report.scenarios.findIndex(
      (scenario) => scenario.name === 'full-access-child',
    );
    if (previousChild >= 0) report.scenarios.splice(previousChild, 1);
    report.scenarios.push({
      ...summarize('full-access-child', childMarker, child),
      status:
        childValue === childMarker && spawned.length
          ? 'needs-independent-child-policy-check'
          : 'uncovered',
    });
    await save();
    console.log(
      JSON.stringify({
        scenario: 'full-access-child',
        threadId,
        turnId: child.turn.turnId,
        status: report.scenarios.at(-1).status,
      }),
    );
    const continuedMarker = 'WECHAT_HOST_CONTINUED_' + nonce;
    const continued = await submit(
      'continuation',
      continuedMarker,
      '接续权限验收 ' +
        continuedMarker +
        '。请用本机终端真实把 ' +
        JSON.stringify(continuedMarker) +
        ' 写入 ' +
        JSON.stringify(paths.continued) +
        '，读取核对后仅回复 ' +
        continuedMarker +
        '。仅操作本次验收文件，不读取凭据、不操作其他聊天。',
    );
    assert.equal((await readFile(paths.continued, 'utf8')).trimEnd(), continuedMarker);
    if (!report.scenarios.some((scenario) => scenario.name === 'default-continuation'))
      report.scenarios.push(summarize('default-continuation', continuedMarker, continued));
    await save();
    service.close();
    service = undefined;
    await start();
    const openId = 'wechat-permission-restore-open-' + nonce;
    await request('/v1/desktop/open', { hostId, threadId, clientOperationId: openId });
    const restoredMarker = 'WECHAT_HOST_RESTORED_' + nonce;
    const restored = await submit(
      'restored',
      restoredMarker,
      'Host 状态恢复后的专用权限验收 ' +
        restoredMarker +
        '。请用本机终端真实把 ' +
        JSON.stringify(restoredMarker) +
        ' 写入 ' +
        JSON.stringify(paths.restored) +
        '，读取核对后仅回复 ' +
        restoredMarker +
        '。仅操作本次验收文件，不读取凭据、不操作其他聊天。',
    );
    assert.equal((await readFile(paths.restored, 'utf8')).trimEnd(), restoredMarker);
    if (!report.scenarios.some((scenario) => scenario.name === 'host-restore-default-continuation'))
      report.scenarios.push(
        summarize('host-restore-default-continuation', restoredMarker, restored),
      );
    const rootMetadata = (await reader.reader.read('thread/read', { threadId, includeTurns: true }))
      .thread;
    const rootRows = (await readFile(rootMetadata.path, 'utf8'))
      .trim()
      .split('\n')
      .map((line) => JSON.parse(line));
    const rootContexts = rootRows.filter((r) => r.type === 'turn_context').map((r) => r.payload);
    for (const scenario of report.scenarios) {
      const context = rootContexts.find((c) => c.turn_id === scenario.turnId);
      assert(context);
      assert.equal(context.model, model);
      assert.equal(context.effort, 'xhigh');
      scenario.actualModel = context.model;
      scenario.actualEffort = context.effort;
      scenario.persistedPolicy = context.sandbox_policy;
      scenario.persistedApproval = context.approval_policy;
    }
    const childIds = [...new Set(spawned.flatMap((i) => i.receiverThreadIds ?? []))];
    assert.equal(childIds.length, 1, 'One actual child is required');
    const childMetadata = (
      await reader.reader.read('thread/read', { threadId: childIds[0], includeTurns: true })
    ).thread;
    assert.equal(childMetadata.parentThreadId, threadId);
    const childRows = (await readFile(childMetadata.path, 'utf8'))
      .trim()
      .split('\n')
      .map((line) => JSON.parse(line));
    const contexts = ownChildContexts(childRows, new Set(rootContexts.map((c) => c.turn_id)));
    assert(contexts.length);
    for (const context of contexts) {
      assert.equal(context.sandbox_policy.type, 'danger-full-access');
      assert.equal(context.approval_policy, 'never');
      assert.equal(context.model, model);
      assert.equal(context.effort, 'xhigh');
    }
    const evidence = contexts.flatMap(
      (context) => persistedToolEvidence(childRows, context.turn_id).results,
    );
    assert(
      evidence.some((r) => r.exit_code === 0 && r.output?.includes(childMarker)),
      'Actual child successful tool output required',
    );
    assert.equal((await readFile(paths.child, 'utf8')).trimEnd(), childMarker);
    const childScenario = report.scenarios.find((s) => s.name === 'full-access-child');
    childScenario.status = 'passed';
    childScenario.childThreadId = childIds[0];
    childScenario.childTurnIds = contexts.map((c) => c.turn_id);
    report.childEvidence.independent = {
      threadId: childIds[0],
      parentThreadId: childMetadata.parentThreadId,
      contexts,
      successfulToolResults: evidence.filter((r) => r.exit_code === 0),
    };
    report.status = 'passed';
  } catch (error) {
    report.status = 'failed-or-unknown';
    report.error = String(error.message);
    process.exitCode = 1;
    console.log(JSON.stringify({ status: report.status, error: report.error, threadId }));
  } finally {
    report.afterProcesses = processIds();
    report.officialProcessesPreserved =
      report.beforeProcesses && report.afterProcesses
        ? JSON.stringify(report.beforeProcesses) === JSON.stringify(report.afterProcesses)
        : null;
    report.windowsOfficialPidCheck =
      process.platform === 'win32'
        ? 'Target owner must independently check the verified official desktop PID before and after'
        : null;
    await save();
    service?.close();
    reader.close();
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href)
  await runHostPermissionTakeoverAcceptance();
