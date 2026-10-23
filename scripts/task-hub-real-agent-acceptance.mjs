import assert from 'node:assert/strict';
import { createHash, randomUUID, X509Certificate } from 'node:crypto';
import { execFile } from 'node:child_process';
import {
  chmod,
  mkdir,
  readFile,
  readdir,
  realpath,
  rename,
  stat,
  writeFile,
} from 'node:fs/promises';
import { request } from 'node:https';
import { createRequire } from 'node:module';
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import {
  verifyBundle,
  verifyRegistration,
  sameIdentity,
} from '../skills/codex-host-connect/scripts/task-hub-core.mjs';

// Real packaged HTTPS/API/Worker -> named Host -> original desktop owner.
// This runner never writes Host configuration, database rows or Agent reports.
const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const expectedPayload = '580ce1c1a066b6722d6034c31d44df512a27f593449306983af2dbae92506575';
const taskID = 'agent-hub-ios-task-management-20261014';
const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');
const parse = async (path) => JSON.parse(await readFile(path, 'utf8'));
const executeFile = promisify(execFile);
const argument = (name, fallback) => {
  const at = process.argv.indexOf(name);
  return at < 0 ? fallback : process.argv[at + 1];
};
const action = argument('--action', 'prepare');
const state = resolve(argument('--state', join(root, '.local/task-management/service')));
const runDirectory = resolve(
  argument('--run', join(root, '.local/task-management/real-agent-' + randomUUID())),
);
assert.ok(runDirectory.startsWith(join(root, '.local/task-management/real-agent-')));
await mkdir(runDirectory, { recursive: true, mode: 0o700 });
await chmod(runDirectory, 0o700);
async function save(name, value) {
  const destination = join(runDirectory, name);
  await mkdir(dirname(destination), { recursive: true, mode: 0o700 });
  const temporary = destination + '.tmp-' + randomUUID();
  await writeFile(temporary, JSON.stringify(value, null, 2) + '\n', { mode: 0o600 });
  await rename(temporary, destination);
  await chmod(destination, 0o600);
}
async function exists(path) {
  return stat(path).then(
    () => true,
    (error) => {
      if (error.code === 'ENOENT') return false;
      throw error;
    },
  );
}
const runPath = join(runDirectory, 'run.json');
const run = (await exists(runPath))
  ? await parse(runPath)
  : {
      taskID,
      responsible: 'real-agent-backend-DEV',
      nonce: randomUUID().slice(0, 8),
      deviceId: randomUUID(),
      startedAt: new Date().toISOString(),
      status: 'not_run',
      checks: [],
      observedSettings: {},
      limits: [
        'Native human UI and MacBook independent device QA belong to the parent task.',
        'The independent Agent reviewer uses a separate original desktop conversation on this Host.',
        'The revision scenario is a genuine V2 requirement change, not an intentionally defective V1 implementation.',
      ],
    };
const persist = () => save('run.json', run);
await persist();
let env, runtime, registration, hostToken, hostCertificate, hostFingerprint, sessions;
function requestJSON(url, options = {}, data) {
  return new Promise((resolveResponse, reject) => {
    const bytes = data === undefined ? undefined : Buffer.from(JSON.stringify(data));
    const req = request(
      url,
      {
        timeout: 30000,
        rejectUnauthorized: true,
        ...options,
        headers: {
          ...(bytes ? { 'content-type': 'application/json', 'content-length': bytes.length } : {}),
          ...options.headers,
        },
      },
      (response) => {
        const chunks = [];
        let size = 0;
        response.on('data', (chunk) => {
          size += chunk.length;
          if (size > 24 * 1024 * 1024) req.destroy(Error('Response exceeds acceptance boundary'));
          else chunks.push(chunk);
        });
        response.on('error', reject);
        response.on('end', () => {
          const body = Buffer.concat(chunks).toString('utf8');
          let value;
          try {
            value = JSON.parse(body);
          } catch {
            reject(Error('Expected JSON response'));
            return;
          }
          resolveResponse({ status: response.statusCode, value });
        });
      },
    );
    req.on('error', reject);
    req.on('timeout', () => req.destroy(Error('HTTPS response unknown after timeout')));
    req.end(bytes);
  });
}
async function hub(
  path,
  { method = 'GET', actor = 'admin', operationId, data, unauthenticated = false } = {},
) {
  const url = new URL(path, registration.endpoints[0]);
  const response = await requestJSON(
    url,
    {
      hostname: '127.0.0.1',
      servername: registration.domain,
      ca: registration.certificatePem,
      method,
      headers: {
        host: url.host,
        origin: env.WEB_ORIGIN,
        ...(unauthenticated
          ? {}
          : {
              authorization: 'Bearer ' + sessions[actor].token,
              'X-TaskHub-Device-Id': run.deviceId,
            }),
        ...(operationId ? { 'X-TaskHub-Operation-Id': operationId } : {}),
      },
    },
    data,
  );
  return response;
}
async function get(path, actor = 'admin') {
  const response = await hub(path, { actor });
  assert.equal(response.status, 200, 'Read route must return 200: ' + path.split('?')[0]);
  return response.value;
}
// Every business write has a durable, immutable intent. Unknown means query only.
async function mutation(name, path, method, data, actor = 'admin') {
  const file = join(runDirectory, 'operations', name + '.json');
  let intent = (await exists(file)) ? await parse(file) : undefined;
  const inputHash = sha(JSON.stringify({ path, method, data, actor }));
  if (intent) {
    assert.equal(intent.inputHash, inputHash, 'A stable operation cannot change its input');
    if (intent.phase === 'confirmed') return intent.result;
    if (intent.attempted) {
      const receipt = await get('/api/v1/operations/' + intent.operationId, actor);
      await save('operations/' + name + '-lookup.json', receipt);
      if (receipt.phase === 'confirmed') {
        intent.phase = 'confirmed';
        intent.result = receipt.result;
        await save('operations/' + name + '.json', intent);
        return intent.result;
      }
      throw Error(
        'Original operation remains ' + receipt.phase + '; input is not replayed: ' + name,
      );
    }
  } else {
    intent = {
      operationId: randomUUID(),
      taskID,
      name,
      inputHash,
      path,
      method,
      actor,
      data,
      phase: 'prepared',
      attempted: false,
    };
    await save('operations/' + name + '.json', intent);
  }
  intent.attempted = true;
  intent.phase = 'unknown';
  await save('operations/' + name + '.json', intent);
  const response = await hub(path, { method, data, actor, operationId: intent.operationId });
  intent.httpStatus = response.status;
  intent.result = response.value;
  intent.phase = response.status >= 200 && response.status < 300 ? 'confirmed' : 'rejected';
  await save('operations/' + name + '.json', intent);
  assert.equal(
    intent.phase,
    'confirmed',
    'Business write rejected: ' + name + ' (HTTP ' + response.status + ')',
  );
  return response.value;
}
async function host(path, { method = 'GET', data } = {}) {
  // Exact local private certificate, not a blanket certificate/hostname bypass.
  const response = await requestJSON(
    new URL(path, 'https://127.0.0.1:8796'),
    {
      ca: hostCertificate,
      method,
      checkServerIdentity: (_name, certificate) =>
        sha(certificate.raw) === hostFingerprint ? undefined : Error('Host certificate changed'),
      headers: { authorization: 'Bearer ' + hostToken },
    },
    data,
  );
  assert.equal(response.status, 200, 'Named Host route must succeed: ' + path.split('?')[0]);
  if (response.value.hostId) assert.equal(response.value.hostId, registration.primaryHostId);
  return response.value;
}
async function verifyEnvironment() {
  runtime = await parse(join(state, 'runtime.json'));
  const trust = await parse(join(state, 'trust.json'));
  registration = verifyRegistration(
    await parse(join(state, 'deployment.json')),
    trust.registryKeySha256,
  );
  env = await parse(join(state, 'env.json'));
  const manifest = await verifyBundle(runtime.bundle);
  assert.equal(manifest.payloadSha256, expectedPayload);
  assert.equal(runtime.payloadSha256, expectedPayload);
  assert.match(registration.deploymentId, /dev/i);
  assert.equal(env.HOST_BACKGROUND_JOBS, '1');
  const descriptor = (await hub('/api/v1/service', { unauthenticated: true })).value;
  assert.ok(sameIdentity(descriptor, registration));
  assert.equal(descriptor.contractVersion, '1');
  hostToken = (await readFile(join(dirname(runtime.hostRuntime), 'access-token'), 'utf8')).trim();
  hostCertificate = await readFile(join(dirname(runtime.hostRuntime), 'direct-discovery/cert.pem'));
  hostFingerprint = sha(new X509Certificate(hostCertificate).raw);
  const workspace = await host('/v1/workspace?hostId=' + registration.primaryHostId);
  assert.equal(workspace.connectionMode, 'desktop-runtime');
  assert.equal(workspace.hostId, registration.primaryHostId);
  await save('workspace-current.json', workspace);
  const creationStatus = await host(
    '/v1/desktop/creation-status?hostId=' + registration.primaryHostId,
  );
  assert.equal(creationStatus.available, true);
  await save('creation-status.json', creationStatus);
  run.service = {
    deploymentId: registration.deploymentId,
    serverInstanceId: registration.serverInstanceId,
    primaryHostId: registration.primaryHostId,
    endpoint: registration.endpoints[0],
    payloadSha256: manifest.payloadSha256,
    sourceSha256: manifest.sourceSha256,
  };
  run.workerFingerprint = {};
  for (const path of [
    'runtime/src/hosts/host-executions.ts',
    'runtime/src/hosts/task-reports.ts',
    'runtime/src/tasks/tasks.service.ts',
  ]) {
    const candidate = join(runtime.bundle, path);
    if (await exists(candidate)) run.workerFingerprint[path] = sha(await readFile(candidate));
  }
  await persist();
  return workspace;
}
async function login() {
  const sessionPath = join(runDirectory, 'sessions.json');
  sessions = (await exists(sessionPath)) ? await parse(sessionPath) : {};
  for (const username of ['admin', 'reviewer']) {
    if (sessions[username] && Date.parse(sessions[username].expiresAt) > Date.now() + 60000)
      continue;
    const response = await hub('/api/v1/auth/mobile/login', {
      unauthenticated: true,
      method: 'POST',
      data: {
        username,
        password: env.BOOTSTRAP_PASSWORD,
        deviceId: run.deviceId,
        deviceName: 'Real Agent DEV ' + run.nonce,
      },
    });
    assert.equal(response.status, 200, 'Actual human mobile login');
    sessions[username] = response.value;
    await save('sessions.json', sessions);
  }
  assert.notEqual(sessions.admin.actor.id, sessions.reviewer.actor.id);
  run.humanActors = { admin: sessions.admin.actor, reviewer: sessions.reviewer.actor };
  await persist();
}
async function prepare(workspace) {
  if (
    run.preparationComplete ||
    (run.projectId &&
      run.bindings?.executor &&
      run.bindings?.reviewer &&
      run.status !== 'needs_attention')
  )
    return;
  let projects = [...workspace.projects];
  let cursor = workspace.nextProjectCursor ?? workspace.projectNextCursor;
  const seen = new Set();
  while (cursor) {
    assert.ok(!seen.has(cursor));
    seen.add(cursor);
    const page = await host(
      '/v1/workspace?' +
        new URLSearchParams({ hostId: registration.primaryHostId, projectCursor: cursor }),
    );
    projects.push(...page.projects);
    cursor = page.nextProjectCursor ?? page.projectNextCursor;
  }
  const remote = projects.find(
    (project) => project.name === 'codex-manage' && project.roots.includes(root),
  );
  assert.ok(remote, 'Use the actual saved codex-manage project, never infer from another root');
  run.remoteProject = remote;
  const sourceRoot = join(runDirectory, 'scenario');
  await mkdir(sourceRoot, { recursive: true, mode: 0o700 });
  run.sourceRoot = sourceRoot;
  // Seed requirements only. The model writes the implementation, tests and artifacts.
  if (!(await exists(join(sourceRoot, 'SPEC.md'))))
    await writeFile(
      join(sourceRoot, 'SPEC.md'),
      [
        '# Task dashboard metrics DEV V1',
        'This isolated directory is the authoritative source. The parent saved project is only the execution envelope.',
        'Write only inside this directory. Do not change the parent repo, service, credentials, other tasks or chats.',
        'Build dependency-free ESM metrics.mjs exporting summarize(rows). statuses: todo, doing, review, done.',
        'V1 counts every input row once, even when ids repeat. Return {todo,doing,review,done,total} in that order.',
        'Reject a non-array or a row with an unknown status using TypeError. Empty input returns five zero counts.',
        'Also support node metrics.mjs <input.json>, output exactly one JSON object line. Do not execute CLI on import.',
        'Create genuine node:test coverage and README. Run actual tests and record exact command/output/exit status.',
        'For each task round copy the actual implementation, tests, README and test evidence into candidates/round-N/.',
        'Candidate artifactDigest is SHA256 of frozen metrics.mjs. sourceRevision records the actual source file SHA.',
        'materials reference exact absolute files with actual SHA256. Do not hash a manifest into itself.',
        'Use candidate.reference containing sha256:<artifactDigest> and exact frozen directory.',
        'This private directory is not a Git repository. Finalize may use an explicit truthful noCommitReason.',
        'No external accounts, network, installs, screenshots or callbacks are needed for this pure computation task.',
        '',
      ].join('\n'),
      { flag: 'wx', mode: 0o600 },
    );
  await persist();
  const project = await mutation('create-project', '/api/v1/projects', 'POST', {
    key: 'RA' + run.nonce.toUpperCase(),
    name: '真实 Agent DEV ' + run.nonce,
    description: 'Isolated model execution and independent review; taskID ' + taskID,
  });
  run.projectId = project.id;
  await persist();
  await mutation('discover-hosts', '/api/v1/host-connections/discover', 'POST', {});
  const hosts = await get('/api/v1/host-connections');
  const primary = hosts.find((value) => value.id === registration.primaryHostId);
  assert.equal(primary?.status, 'online');
  assert.equal(primary?.connectionMode, 'desktop-runtime');
  await save('connected-primary.json', primary);
  const catalog = await get('/api/v1/host-connections/' + primary.id + '/projects');
  assert.ok(catalog.some((project) => project.id === remote.id && project.roots.includes(root)));
  await mutation('project-source', '/api/v1/projects/' + project.id + '/source', 'PUT', {
    kind: 'directory',
    reference: sourceRoot,
    defaultBranch: 'isolated-dev',
  });
  run.bindings = {};
  for (const role of ['executor', 'reviewer']) {
    run.bindings[role] = await mutation(
      'bind-' + role,
      '/api/v1/projects/' + project.id + '/host-bindings',
      'POST',
      {
        hostId: primary.id,
        remoteProjectId: remote.id,
        root,
        role,
      },
    );
    assert.equal(run.bindings[role].status, 'verified');
    await persist();
  }
  assert.notEqual(run.bindings.executor.actorId, run.bindings.reviewer.actorId);
  const policy = {
    ...project.workflowPolicy,
    requiredAcceptances: [
      { mode: 'agent_host', reviewerId: run.bindings.reviewer.actorId },
      { mode: 'human', reviewerId: sessions.reviewer.actor.id },
    ],
    requireSelfCheck: true,
    requireFinalCommit: false,
    requireMaterials: true,
    requiredIndexes: [],
    requireIndependentHost: false,
  };
  await mutation('project-policy', '/api/v1/projects/' + project.id, 'PATCH', {
    workflowPolicy: policy,
  });
  run.preparationComplete = true;
  run.status = 'prepared';
  delete run.error;
  await persist();
}
function taskInput() {
  return {
    projectId: run.projectId,
    title: '实现任务仪表盘统计内核 ' + run.nonce,
    description: [
      '本次已获授权的真实 DEV 编程任务。唯一可写范围：' + run.sourceRoot + '。',
      '保存的 Host 项目 root 只是原桌面执行入口；权威目录源是 source.reference，实际源码就在上述独立目录。',
      '先读取 SPEC.md，核对当前目录初始只有需求，没有实现；保留父仓库既有改动，不运行父仓库测试或构建。',
      '实现 dependency-free ESM metrics.mjs 导出 summarize(rows)，并支持 node metrics.mjs <input.json>。',
      'V1 每行计数一次，即便 id 重复也须分别计数。合法 status 为 todo/doing/review/done，输出固定顺序五项 todo/doing/review/done/total。',
      '编写并实际运行 node --test metrics.test.mjs，校验空数组、四状态、重复ID仍全部计数、非法输入与 CLI。',
      '本轮冻结 candidates/round-' +
        (run.taskId ? 'N（按 context.currentRound）' : '1') +
        '/ 中完整实际源码/测试/README/测试证据。',
      'artifactDigest 为该冻结 metrics.mjs 的 SHA256，sourceRevision 为实际源码SHA256；materials 每项实际绝对文件路径与SHA256。',
      '本包相应 runId/taskId/roundId 只用于原 Host taskhub-report。只输出实际完成事件，Worker负责业务写入。',
      '当前为私有非Git目录验收，最终无需提交父仓库，finalize 用真实 noCommitReason；禁止修改本目录之外或生成伪造自验。',
    ].join('\n'),
    acceptanceCriteria:
      '真实模型编写可运行实现和必要测试；V1 每行计数、空数组五项零、非法输入 TypeError、CLI 单行JSON。冻结每轮原始文件，真实可复算SHA、实际命令退出0。独立Agent在不同原桌面对话核对精确候选并执行测试，指定人工复核保留需求变更返修后最终关闭。',
    type: 'feature',
    priority: 'P2',
    ownerId: run.bindings.executor.actorId,
    hostId: registration.primaryHostId,
    acceptanceMode: 'agent_host',
    reviewerId: run.bindings.reviewer.actorId,
  };
}
async function startTask() {
  if (!run.taskId) {
    const task = await mutation('create-task', '/api/v1/tasks', 'POST', taskInput());
    run.taskId = task.id;
    await persist();
  }
  if (!run.initialDispatch) {
    const task = await get('/api/v1/tasks/' + run.taskId);
    run.initialDispatch = await mutation(
      'initial-dispatch',
      '/api/v1/tasks/' + run.taskId + '/dispatches',
      'POST',
      {
        revision: task.revision,
        bindingId: run.bindings.executor.id,
        role: 'executor',
      },
    );
    run.status = 'running';
    await persist();
  }
}
async function capture() {
  const detail = await get('/api/v1/tasks/' + run.taskId);
  const runs = await get('/api/v1/tasks/' + run.taskId + '/runs');
  await save('task-current.json', detail);
  await save('runs-current.json', runs);
  for (const execution of runs) {
    if (!execution.threadId) continue;
    assert.equal(execution.hostId, registration.primaryHostId);
    assert.equal(execution.remoteProjectId, run.remoteProject.id);
    assert.equal(execution.requestedEffort, 'xhigh');
    assert.ok(execution.target && execution.target.hostId === execution.hostId);
    const history = await host(
      '/v1/history?' +
        new URLSearchParams({ hostId: execution.hostId, threadId: execution.threadId }),
    );
    assert.equal(history.thread?.projectId, run.remoteProject.id);
    assert.deepEqual(
      history.readTarget,
      execution.target,
      'Original owner full target must match the execution snapshot',
    );
    await save('histories/' + execution.threadId + '-latest.json', history);
    const settings = history.runtime?.generationSettings;
    const latestInput = history.messages.filter((message) => message.role === 'user').at(-1);
    const ownerInputTurn = history.runtime?.turnId ?? latestInput?.runId;
    if (
      history.runtime?.canControl &&
      settings?.effort === 'xhigh' &&
      execution.turnId &&
      ownerInputTurn === execution.turnId &&
      latestInput?.text?.includes(execution.id) &&
      run.observedSettings[execution.id]?.turnId !== execution.turnId
    ) {
      run.observedSettings[execution.id] = {
        threadId: execution.threadId,
        turnId: ownerInputTurn,
        runtimeState: history.runtime.state,
        canControl: history.runtime.canControl,
        settings,
        recordedAt: new Date().toISOString(),
        target: execution.target,
      };
      await save('histories/' + execution.threadId + '-' + execution.id + '-owner.json', history);
    }
  }
  run.latest = {
    revision: detail.revision,
    status: detail.status,
    stage: detail.workflowStage,
    round: detail.currentRound,
    runs: runs.map((value) => ({
      id: value.id,
      state: value.state,
      role: value.role,
      threadId: value.threadId,
      operationId: value.operationId,
      requestedEffort: value.requestedEffort,
      actualEffort: value.actualEffort,
      lastError: value.lastError,
    })),
  };
  await persist();
  return { detail, runs };
}
async function actualCandidate(round) {
  assert.ok(
    round.delivery?.candidate && round.selfCheck,
    'Actual structured candidate and self-check',
  );
  const candidate = round.delivery.candidate;
  assert.equal(candidate.artifactDigest.length, 64);
  assert.ok(candidate.reference.includes(candidate.artifactDigest));
  assert.equal(round.delivery.candidateRef, candidate.reference);
  assert.equal(round.selfCheck.candidate.artifactDigest, candidate.artifactDigest);
  const base = await realpath(run.sourceRoot);
  const materialEvidence = [];
  for (const material of candidate.materials) {
    assert.ok(isAbsolute(material.reference), 'Scenario materials are real local files');
    const path = await realpath(material.reference);
    const inside = relative(base, path);
    assert.ok(inside !== '..' && !inside.startsWith('..' + sep) && !isAbsolute(inside));
    const bytes = await readFile(path);
    assert.equal(sha(bytes), material.sha256, 'Verify actual material SHA: ' + material.name);
    materialEvidence.push({ ...material, verifiedBytes: bytes.length });
  }
  const implementation = materialEvidence.find((value) => value.reference.endsWith('/metrics.mjs'));
  assert.ok(implementation, 'Candidate provides exact implementation bytes');
  assert.equal(implementation.sha256, candidate.artifactDigest);
  assert.ok(
    candidate.sourceRevision.includes(candidate.artifactDigest),
    'Source revision identifies actual frozen source bytes',
  );
  const cwd = dirname(implementation.reference);
  const result = await executeFile(process.execPath, ['--test', 'metrics.test.mjs'], {
    cwd,
    timeout: 60000,
    maxBuffer: 1024 * 1024,
  });
  await save('round-' + round.number + '-independent-tests.json', {
    responsible: 'real-agent-backend-DEV',
    command: [process.execPath, '--test', 'metrics.test.mjs'],
    cwd,
    exitCode: 0,
    stdout: result.stdout,
    stderr: result.stderr,
    materials: materialEvidence,
    candidateRef: candidate.reference,
    artifactDigest: candidate.artifactDigest,
    sourceRevision: candidate.sourceRevision,
    checkedAt: new Date().toISOString(),
  });
  return { candidate, implementation, cwd };
}
async function semanticCheck(path, version) {
  // Independently execute candidate computation and CLI; do not rely on model assertions.
  const program =
    `import assert from 'node:assert/strict';import{summarize}from ${JSON.stringify('file://' + path)};` +
    `assert.deepEqual(summarize([]),{todo:0,doing:0,review:0,done:0,total:0});` +
    `assert.throws(()=>summarize({}),TypeError);assert.throws(()=>summarize([{id:'bad',status:'bogus'}]),TypeError);` +
    `let actual=summarize([{id:'same',status:'todo'},{id:'same',status:'done'},{id:'other',status:'doing'}]);` +
    `console.log(JSON.stringify({version:${version},actual}));`;
  const result = await executeFile(process.execPath, ['--input-type=module', '-e', program], {
    timeout: 30000,
    maxBuffer: 1024 * 1024,
  });
  const actual = JSON.parse(result.stdout.trim()).actual;
  const expected =
    version === 1
      ? { todo: 1, doing: 1, review: 0, done: 1, total: 3 }
      : { todo: 0, doing: 1, review: 0, done: 1, total: 2 };
  const inputPath = join(runDirectory, 'round-' + version + '-cli-input.json');
  await save('round-' + version + '-cli-input.json', [
    { id: 'same', status: 'todo' },
    { id: 'same', status: 'done' },
    { id: 'other', status: 'doing' },
  ]);
  const cli = await executeFile(process.execPath, [path, inputPath], {
    timeout: 30000,
    maxBuffer: 1024 * 1024,
  });
  assert.equal(cli.stdout.trim().split('\n').length, 1, 'Actual CLI outputs one JSON line');
  assert.deepEqual(JSON.parse(cli.stdout), actual, 'CLI matches the actual exported computation');
  const matches = JSON.stringify(actual) === JSON.stringify(expected);
  let invalidIdEvidence;
  if (version === 2 && matches) {
    const invalidIdProgram =
      `import assert from 'node:assert/strict';import{summarize}from ${JSON.stringify('file://' + path)};` +
      `for(const id of [undefined,null,'',42])assert.throws(()=>summarize([{id,status:'todo'}]),TypeError);console.log('actual invalid-id checks passed');`;
    invalidIdEvidence = await executeFile(
      process.execPath,
      ['--input-type=module', '-e', invalidIdProgram],
      { timeout: 30000 },
    );
  }
  return {
    actual,
    expected,
    matches,
    stdout: result.stdout,
    stderr: result.stderr,
    cli: {
      command: [process.execPath, path, inputPath],
      exitCode: 0,
      stdout: cli.stdout,
      stderr: cli.stderr,
    },
    ...(invalidIdEvidence ? { invalidIdEvidence } : {}),
  };
}
async function processCandidate(detail) {
  const round = detail.rounds.find((value) => value.number === detail.currentRound);
  if (!round.delivery || detail.status !== 'review') return;
  const agent = round.acceptances.find(
    (value) => value.reviewerId === run.bindings.reviewer.actorId,
  );
  if (agent?.status !== 'passed') return;
  const human = round.acceptances.find((value) => value.reviewerId === sessions.reviewer.actor.id);
  if (human?.status !== 'pending') return;
  const { candidate, implementation } = await actualCandidate(round);
  const semantic = await semanticCheck(implementation.reference, round.number === 1 ? 1 : 2);
  await save('round-' + round.number + '-semantics.json', semantic);
  assert.ok(semantic.matches, 'Actual candidate must satisfy this round semantics');
  const latest = await get('/api/v1/tasks/' + run.taskId);
  if (round.number === 1) {
    const changedRequirement = await semanticCheck(implementation.reference, 2);
    assert.equal(
      changedRequirement.matches,
      false,
      'The actual V1 candidate cannot satisfy new V2 requirement',
    );
    await save('round-1-v2-requirement-failure.json', {
      ...changedRequirement,
      candidateRef: candidate.reference,
      explanation:
        'V1 fulfilled its original row-count requirement. Product review now requires last-row-wins identity deduplication.',
      testedAt: new Date().toISOString(),
    });
    const note = [
      'V1原始需求的真实模型实现及独立Agent验收通过；现在业务提出V2变更，需按任务id去重并采用同id最后一行状态。',
      '真实核验同id的todo→done加other doing，当前旧候选实际=' +
        JSON.stringify(changedRequirement.actual) +
        '，V2预期=' +
        JSON.stringify(changedRequirement.expected) +
        '，不满足新需求。',
      '请返修：summarize先按id保留最后一行，再统计；id必须为非空字符串；缺失id或非字符串须TypeError。',
      '保留四状态、空数组、未知状态与CLI要求。更新测试和README，实际运行 node --test metrics.test.mjs。',
      '唯一写入范围仍为 ' +
        run.sourceRoot +
        '；保留 candidates/round-1 原字节，在 candidates/round-2 冻结新候选并用真实SHA self_check/deliver。',
      '这是产品需求变更导致旧候选失败，不把正确V1宣称为原始缺陷。父仓库、服务和其他任务禁止修改。',
    ].join('\n');
    await mutation(
      'v1-human-change-failure',
      '/api/v1/tasks/' + run.taskId + '/acceptances/' + human.id + '/decision',
      'POST',
      {
        revision: latest.revision,
        result: 'failed',
        note,
      },
      'reviewer',
    );
    run.checks.push({
      scenario: 'real V1 programming, independent Agent review and genuine V2 failure',
      status: 'passed',
      candidateRef: candidate.reference,
    });
    run.status = 'repair_running';
    await persist();
  } else {
    assert.equal(round.number, 2, 'Only the explicitly authorized two-round scenario');
    await mutation(
      'v2-human-independent-pass',
      '/api/v1/tasks/' + run.taskId + '/acceptances/' + human.id + '/decision',
      'POST',
      {
        revision: latest.revision,
        result: 'passed',
        note:
          '已读取并复算冻结候选全部真实材料SHA，独立运行原始node:test与额外行为检查退出0；同id最后一行去重统计实际=' +
          JSON.stringify(semantic.actual) +
          '，与V2预期一致。独立Agent已在专用原桌面对话验收此同一候选。仍须Worker开发身份核对最终材料，不能将passed直接当done。',
      },
      'reviewer',
    );
    run.checks.push({
      scenario: 'real repair and independent V2 candidate tests',
      status: 'passed',
      candidateRef: candidate.reference,
    });
    const closing = await get('/api/v1/tasks/' + run.taskId);
    assert.equal(closing.status, 'review');
    assert.equal(closing.workflowStage, 'closing');
    await save('closing-before-finalize.json', closing);
    run.status = 'closing';
    await persist();
  }
}
async function finalProof(detail, executions) {
  assert.equal(detail.status, 'done');
  assert.equal(detail.workflowStage, 'completed');
  const ownerSnapshots = [];
  for (const threadId of new Set(executions.map((execution) => execution.threadId))) {
    const history = await host(
      '/v1/history?' + new URLSearchParams({ hostId: registration.primaryHostId, threadId }),
    );
    ownerSnapshots.push({
      threadId,
      state: history.runtime?.state,
      canControl: history.runtime?.canControl,
      queued:
        history.runtime?.receipts?.filter((receipt) => receipt.state === 'queued').length ?? 0,
      approvals: history.runtime?.approvals?.length ?? 0,
      pendingOrUnknown:
        history.runtime?.receipts?.filter((receipt) =>
          ['queued', 'submitting', 'submitted', 'uncertain'].includes(receipt.state),
        ).length ?? 0,
    });
    if (
      history.runtime?.state !== 'idle' ||
      history.runtime?.canControl !== true ||
      history.runtime?.receipts?.some((receipt) =>
        ['queued', 'submitting', 'submitted', 'uncertain'].includes(receipt.state),
      ) ||
      history.runtime?.approvals?.length
    ) {
      await save('ready-not-yet.json', {
        recordedAt: new Date().toISOString(),
        reason: 'Task is done but original owner is not fully settled',
        ownerSnapshots,
      });
      return false;
    }
    const expectedInputs = executions.filter((execution) => execution.threadId === threadId);
    const actualInputs = history.messages.filter((message) => message.role === 'user');
    assert.equal(
      actualInputs.length,
      expectedInputs.length,
      'One actual original model input per durable operation',
    );
    assert.deepEqual(
      [...new Set(actualInputs.map((message) => message.runId))].sort(),
      expectedInputs.map((execution) => execution.turnId).sort(),
    );
    await save('histories/' + threadId + '-settled-owner.json', history);
  }
  assert.ok(
    executions.every((execution) => execution.state === 'completed'),
    'Every Worker operation is terminal',
  );
  const seenThreads = new Set();
  const hostReceipts = [];
  for (const execution of executions) {
    const first = !seenThreads.has(execution.threadId);
    seenThreads.add(execution.threadId);
    const receipt = await host(
      (first ? '/v1/desktop/creations?' : '/v1/receipts?') +
        new URLSearchParams({
          hostId: execution.hostId,
          id: execution.operationId,
        }),
    );
    assert.equal(receipt.threadId, execution.threadId);
    if (first)
      assert.equal(receipt.phase, 'confirmed', 'Original creation uncertainty is resolved');
    else {
      assert.equal(receipt.clientMessageId, execution.operationId);
      assert.equal(
        receipt.state,
        'completed',
        'Original reply finished in the original owner, neither pending nor unknown',
      );
    }
    hostReceipts.push({ executionId: execution.id, operationId: execution.operationId, receipt });
  }
  await save('settled-host-operation-receipts.json', hostReceipts);
  const businessReceipts = [];
  for (const name of await readdir(join(runDirectory, 'operations'))) {
    if (!name.endsWith('.json')) continue;
    const intent = await parse(join(runDirectory, 'operations', name));
    if (!intent.actor || !intent.path) continue;
    const receipt = await get('/api/v1/operations/' + intent.operationId, intent.actor);
    assert.equal(
      receipt.phase,
      'confirmed',
      'Business intent is acknowledged, not unknown: ' + intent.name,
    );
    assert.deepEqual(
      receipt.result,
      intent.result,
      'Original business receipt preserves the exact result',
    );
    businessReceipts.push({
      name: intent.name,
      actor: intent.actor,
      operationId: intent.operationId,
      phase: receipt.phase,
    });
  }
  await save('settled-business-operation-receipts.json', businessReceipts);
  assert.equal(detail.currentRound, 2);
  assert.equal(
    detail.rounds[0].acceptances.find((value) => value.reviewerId === sessions.reviewer.actor.id)
      .status,
    'failed',
  );
  assert.ok(detail.rounds[1].acceptances.every((value) => value.status === 'passed'));
  for (const round of detail.rounds) await actualCandidate(round);
  const developerThreads = new Set(
    executions.filter((value) => value.role === 'executor').map((value) => value.threadId),
  );
  const reviewerThreads = new Set(
    executions.filter((value) => value.role === 'reviewer').map((value) => value.threadId),
  );
  assert.equal(developerThreads.size, 1);
  assert.equal(reviewerThreads.size, 1);
  assert.notEqual([...developerThreads][0], [...reviewerThreads][0]);
  assert.ok(
    executions.every((value) => value.actualEffort === 'xhigh'),
    'Worker observed actual xhigh for every input',
  );
  assert.ok(
    executions.every((value) => run.observedSettings[value.id]?.settings?.model),
    'Independent original owner settings recorded',
  );
  const models = new Set(Object.values(run.observedSettings).map((value) => value.settings.model));
  assert.equal(models.size, 1, 'Preserved one actual existing model across executor and reviewer');
  const { Client } = createRequire(join(runtime.bundle, 'runtime/package.json'))('pg');
  const database = new Client({ connectionString: env.DATABASE_URL });
  await database.connect();
  try {
    await database.query('BEGIN READ ONLY');
    const reports = (
      await database.query(
        'SELECT h.run_id,h.event_id,h.action,h.content_hash,h.report FROM host_report_receipts h JOIN execution_runs r ON r.id=h.run_id WHERE r.task_id=$1 ORDER BY r.created_at,h.event_id',
        [run.taskId],
      )
    ).rows;
    const durable = (
      await database.query(
        'SELECT id,operation_id,operation_kind,thread_id,turn_id,requested_effort,actual_effort,submit_attempts,submit_attempted,workflow_phase,state,receipt,target_snapshot FROM execution_runs WHERE task_id=$1 ORDER BY created_at',
        [run.taskId],
      )
    ).rows;
    await database.query('ROLLBACK');
    assert.ok(
      ['respond', 'claim', 'progress', 'self_check', 'deliver', 'decide', 'finalize'].every(
        (actionName) => reports.some((value) => value.action === actionName),
      ),
    );
    assert.equal(new Set(durable.map((value) => value.operation_id)).size, durable.length);
    assert.ok(
      durable.every((value) => value.submit_attempts === 1),
      'No duplicate model submission',
    );
    await save('durable-original-operations.json', durable);
    await save('actual-authenticated-model-reports.json', reports);
  } finally {
    await database.end();
  }
  run.checks.push({
    scenario:
      'original owner real xhigh, separate reviewer, immutable operations and model finalization',
    status: 'passed',
  });
  run.status = 'passed';
  delete run.error;
  run.completedAt = new Date().toISOString();
  run.actualModels = [...models];
  run.threads = { executor: [...developerThreads][0], reviewer: [...reviewerThreads][0] };
  run.readyToUpgrade = true;
  run.upgradeBoundary = {
    checkedAt: new Date().toISOString(),
    ownerSnapshots,
    hostReceiptCount: hostReceipts.length,
    businessReceiptCount: businessReceipts.length,
    pendingOrUnknown: 0,
    originalModelInputs: executions.length,
  };
  await persist();
  await save('ready-to-upgrade.json', {
    taskID,
    projectId: run.projectId,
    taskId: run.taskId,
    payloadSha256: run.service.payloadSha256,
    readyToUpgrade: true,
    ...run.upgradeBoundary,
  });
  return true;
}
try {
  const workspace = await verifyEnvironment();
  await login();
  await prepare(workspace);
  if (action !== 'prepare') await startTask();
  if (action === 'run' || action === 'poll') {
    const deadline =
      Date.now() + Number(argument('--seconds', action === 'poll' ? '45' : '1800')) * 1000;
    let previous = '';
    while (Date.now() < deadline) {
      const { detail, runs } = await capture();
      const fingerprint = JSON.stringify(run.latest);
      if (fingerprint !== previous) {
        console.log(
          JSON.stringify({
            runDirectory,
            taskId: run.taskId,
            status: detail.status,
            stage: detail.workflowStage,
            round: detail.currentRound,
            runs: runs.map((value) => ({
              role: value.role,
              state: value.state,
              threadId: value.threadId,
              actualEffort: value.actualEffort,
              lastError: value.lastError,
            })),
          }),
        );
        previous = fingerprint;
      }
      if (detail.status === 'done') {
        if (await finalProof(detail, runs)) break;
        await new Promise((resolveWait) => setTimeout(resolveWait, 5000));
        continue;
      }
      await processCandidate(detail);
      await new Promise((resolveWait) => setTimeout(resolveWait, 5000));
    }
  }
  console.log(
    JSON.stringify({
      runDirectory,
      status: run.status,
      projectId: run.projectId,
      taskId: run.taskId ?? null,
      sourceRoot: run.sourceRoot,
      evidence: join(runDirectory, 'run.json'),
      readyToUpgrade: run.readyToUpgrade ?? false,
    }),
  );
} catch (error) {
  run.status = 'needs_attention';
  // Errors from lower transports may contain sensitive strings; retain only safe runner assertions.
  run.error =
    error instanceof assert.AssertionError
      ? error.message
      : 'Execution stopped at a real boundary; private error record available';
  await save('failure-private.json', {
    name: error.name,
    message: error.message,
    stack: error.stack,
    time: new Date().toISOString(),
  });
  await persist();
  console.error(
    JSON.stringify({ runDirectory, status: run.status, error: run.error, evidence: runPath }),
  );
  process.exitCode = 1;
}
