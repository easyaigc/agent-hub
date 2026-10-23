import { createServer } from 'node:http';
import { createHash } from 'node:crypto';
import { spawn, execFileSync } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { homedir } from 'node:os';
import { createInterface } from 'node:readline';
import { DesktopIpc, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { controllerAdapterDigest } from '../packages/codex-adapter/dist/desktop-controller.js';
import { prepareNativeMedia } from './prepare-native-media.mjs';

// Real original-desktop acceptance. This starts only the fixture metadata HTTP server;
// Codex and Host Agent must already be running through their ordinary launch paths.
const directory = resolve('.local/controller-ios-acceptance');
const nativeProject = resolve(process.env.IOS_PROJECT_PATH ?? 'apps/ios/CodexManage.xcodeproj');
const nativeDirectory = dirname(nativeProject);
await mkdir(directory, { recursive: true, mode: 0o700 });
const stateDirectory = resolve(process.env.HOST_AGENT_STATE_DIR ?? '.local/desktop-runtime');
const endpoint = process.env.DESKTOP_ACCEPTANCE_ENDPOINT ?? 'http://127.0.0.1:8794';
const hostServiceLabel =
  process.env.DESKTOP_ACCEPTANCE_HOST_SERVICE_LABEL ?? 'com.codexmanage.host.desktop';
if (!/^com\.codexmanage\.host\.[a-z0-9.-]+$/.test(hostServiceLabel))
  throw Error('Invalid dedicated Host service label');
const token = (await readFile(join(stateDirectory, 'access-token'), 'utf8')).trim();
const nonce = crypto.randomUUID().slice(0, 8);
const attachmentMarker = crypto.randomUUID().slice(0, 8);
const get = async (path) => {
  const result = await fetch(endpoint + path, { headers: { Authorization: 'Bearer ' + token } });
  if (!result.ok) throw Error('Host request failed: ' + result.status);
  return result.json();
};
const workspace = await get('/v1/workspace');
if (workspace.connectionMode !== 'desktop-runtime') throw Error('Original desktop required');
const config = JSON.parse(await readFile(join(stateDirectory, 'controller.json'), 'utf8'));
const project = workspace.projects.find((v) => v.id === process.env.DESKTOP_CREATION_PROJECT_ID);
const empty = workspace.projects.find((v) => v.id === process.env.DESKTOP_EMPTY_PROJECT_ID);
if (!project || !empty) throw Error('Select real saved creation and empty projects by ID');
const fixture = {
  endpoint,
  token,
  hostId: workspace.hostId,
  threadId: process.env.DESKTOP_ACCEPTANCE_THREAD_ID ?? '',
  threadTitle:
    process.env.DESKTOP_ACCEPTANCE_THREAD_TITLE ??
    workspace.threads.find((thread) => thread.id === process.env.DESKTOP_ACCEPTANCE_THREAD_ID)
      ?.title ??
    '',
  nonce,
  creationProjectId: project.id,
  creationProjectName: project.name,
  attachmentMarker,
  emptyProjectId: empty.id,
  unloadedThreadId: process.env.DESKTOP_UNLOADED_THREAD_ID,
  unloadedThreadTitle: process.env.DESKTOP_UNLOADED_THREAD_TITLE,
  secondEndpoint: '',
  secondToken: '',
  secondHostId: '',
  delayedEndpoint: '',
  switchThreads: workspace.threads
    .filter((v) => /IOS_PROJECT_|IOS_RECOVERY_|混合附件原生验收/.test(v.title))
    .slice(0, 3)
    .map((v) => ({ id: v.id, title: v.title })),
};
const testNames = (process.env.DESKTOP_IOS_TESTS ?? 'testOriginalDesktopProjectCreation').split(
  ',',
);
const ipc = new DesktopIpc(join(homedir(), '.codex/ipc/ipc.sock'));
await ipc.connect();
if (!(await ipc.follow(config.threadId)) || !ipc.hasOwner(config.threadId))
  throw Error('Open the original controller chat before acceptance');
let unloadedBefore;
const commands = async () =>
  Promise.all(
    (await readdir(join(stateDirectory, 'desktop-controller')))
      .filter((name) => name.endsWith('.json'))
      .map(async (name) =>
        JSON.parse(await readFile(join(stateDirectory, 'desktop-controller', name), 'utf8')),
      ),
  );
const beforeOpenIds = new Set((await commands()).map((command) => command.id));
if (testNames.includes('testOriginalDesktopUnloadedOpenAndReply')) {
  await ipc.follow(fixture.unloadedThreadId);
  unloadedBefore = !ipc.hasOwner(fixture.unloadedThreadId);
  if (!unloadedBefore) throw Error('Unloaded acceptance target already has an original owner');
}
const processIds = () => {
  const processes = execFileSync('ps', ['-axo', 'pid,ppid,command'], { encoding: 'utf8' })
    .split('\n')
    .map((line) => line.trim().match(/^(\d+)\s+(\d+)\s+(.*)$/))
    .filter(Boolean)
    .map((match) => ({ pid: Number(match[1]), ppid: Number(match[2]), command: match[3] }));
  const main = processes.filter((v) =>
    /^\/Applications\/ChatGPT.app\/Contents\/MacOS\/ChatGPT(?: |$)/.test(v.command),
  );
  return [
    ...main,
    ...processes.filter(
      (v) =>
        main.some((m) => m.pid === v.ppid) &&
        v.command.includes('/codex-cli/') &&
        v.command.includes('app-server'),
    ),
  ].map((v) => [v.pid, v.ppid]);
};
const beforeProcesses = processIds();
const fixtureServer = createServer((request, response) => {
  if (
    request.headers.origin ||
    request.method !== 'GET' ||
    !['/', '/restart-host', '/creation-status'].includes(request.url) ||
    !['localhost:8795', '127.0.0.1:8795'].includes(request.headers.host)
  ) {
    response.writeHead(403);
    response.end();
    return;
  }
  if (request.url === '/creation-status') {
    if (!testNames.includes('testOriginalDesktopCreationRestartRecovery')) {
      response.writeHead(403);
      response.end();
      return;
    }
    void (async () => {
      try {
        const records = Object.values(
          JSON.parse(
            await readFile(join(stateDirectory, 'desktop-creations/creations.json'), 'utf8'),
          ),
        );
        const record = records.find((value) => value.text.includes('IOS_RECOVERY_' + nonce));
        response.writeHead(200, {
          'Content-Type': 'application/json',
          'Cache-Control': 'no-store',
        });
        response.end(
          JSON.stringify({
            submitted: Boolean(
              record && ['submitted', 'outcome-unknown', 'confirmed'].includes(record.phase),
            ),
          }),
        );
      } catch {
        response.writeHead(500);
        response.end('{}');
      }
    })();
    return;
  }
  if (request.url === '/restart-host') {
    if (!testNames.includes('testOriginalDesktopHostRestartRecovery')) {
      response.writeHead(403);
      response.end();
      return;
    }
    void (async () => {
      try {
        // Wait until intent crossed the durable sent boundary before interrupting only our Host.
        const deadline = Date.now() + 15_000;
        let sent = false;
        while (Date.now() < deadline) {
          const records = Object.values(
            JSON.parse(
              await readFile(join(stateDirectory, 'desktop-creations/creations.json'), 'utf8'),
            ),
          );
          const record = records.find((v) => v.text.includes('IOS_HOST_RECOVERY_' + nonce));
          if (record) {
            try {
              const command = JSON.parse(
                await readFile(
                  join(stateDirectory, 'desktop-controller', record.clientOperationId + '.json'),
                  'utf8',
                ),
              );
              if (command.phase === 'sent') {
                sent = true;
                break;
              }
            } catch {
              /* Not yet dispatched. */
            }
          }
          await new Promise((done) => setTimeout(done, 200));
        }
        if (!sent) throw Error('No in-flight durable desktop intent to test');
        // Restart the installed bridge without rewriting its deployment paths,
        // Node runtime, name, port or controller configuration.
        execFileSync(
          '/bin/launchctl',
          ['kickstart', '-k', `gui/${process.getuid()}/${hostServiceLabel}`],
          { stdio: 'pipe' },
        );
        const ready = Date.now() + 15_000;
        let connected = false;
        while (Date.now() < ready) {
          try {
            if ((await get('/v1/workspace')).hostId === fixture.hostId) {
              connected = true;
              break;
            }
          } catch {
            /* Restarting. */
          }
          await new Promise((done) => setTimeout(done, 300));
        }
        if (!connected) throw Error('Host did not recover');
        response.writeHead(200);
        response.end('{}');
      } catch {
        response.writeHead(500);
        response.end('{}');
      }
    })();
    return;
  }
  response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  response.end(JSON.stringify(fixture));
});
let child;
try {
  await new Promise((done, reject) => {
    fixtureServer.once('error', reject);
    fixtureServer.listen(8795, '127.0.0.1', done);
  });
  const device = process.env.IOS_SIMULATOR_ID;
  if (!device) throw Error('Set IOS_SIMULATOR_ID to the booted acceptance simulator');
  await prepareNativeMedia(device, directory, nonce);
  const resultBundle = join(directory, `result-${nonce}.xcresult`);
  const run = {
    nonce,
    testNames,
    resultBundle,
    status: 'running',
    adapterSha256: controllerAdapterDigest(),
    nativeSourceSha256: createHash('sha256')
      .update(await readFile(join(nativeProject, 'project.pbxproj')))
      .update(
        Buffer.concat(
          await Promise.all(
            (await readdir(join(nativeDirectory, 'Sources')))
              .filter((name) => name.endsWith('.swift'))
              .sort()
              .map((name) => readFile(join(nativeDirectory, 'Sources', name))),
          ),
        ),
      )
      .digest('hex'),
    nativeTestSha256: createHash('sha256')
      .update(await readFile(join(nativeDirectory, 'Tests/CodexManageUITests.swift')))
      .digest('hex'),
    nativeProjectIsolated: Boolean(process.env.IOS_PROJECT_PATH),
    hostServiceLabel,
    hostStateIsolated: Boolean(process.env.HOST_AGENT_STATE_DIR),
    controllerThreadId: config.threadId,
    hostId: fixture.hostId,
    desktopVersion: config.desktopVersion,
    unloadedBefore,
    emptyProjectInitialThreadCount: workspace.threads.filter(
      (v) => v.projectId === empty.id || v.projectIds?.includes(empty.id),
    ).length,
    beforeProcesses,
  };
  const save = () =>
    writeFile(join(directory, `run-${nonce}.json`), JSON.stringify(run, null, 2), { mode: 0o600 });
  await save();
  child = spawn(
    'xcodebuild',
    [
      '-project',
      nativeProject,
      '-scheme',
      'CodexManage',
      '-destination',
      `platform=iOS Simulator,id=${device}`,
      '-derivedDataPath',
      process.env.IOS_DERIVED_DATA_PATH ?? '.build/ios',
      '-resultBundlePath',
      resultBundle,
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
      ...testNames.map((name) => '-only-testing:CodexManageUITests/CodexManageUITests/' + name),
      'CODE_SIGNING_ALLOWED=YES',
      'CODE_SIGN_IDENTITY=-',
      'test',
    ],
    { env: process.env, stdio: ['ignore', 'pipe', 'pipe'] },
  );
  const log = createWriteStream(join(directory, `xcode-${nonce}.log`), { mode: 0o600 });
  for (const stream of [child.stdout, child.stderr])
    createInterface({ input: stream }).on('line', (line) => {
      const safe = line
        .replaceAll(token, '[REDACTED]')
        .replaceAll(token.slice(0, 16), '[REDACTED]');
      log.write(safe + '\n');
      if (/^Test Case|: error:|^\s*Executed |^\*\* TEST (SUCCEEDED|FAILED)/.test(safe))
        console.log(safe);
    });
  const code = await new Promise((done, reject) => {
    child.once('error', reject);
    child.once('exit', done);
  });
  await new Promise((done) => log.end(done));
  const native = JSON.parse(
    execFileSync(
      'xcrun',
      [
        'xcresulttool',
        'get',
        'test-results',
        'summary',
        '--path',
        resultBundle,
        '--format',
        'json',
      ],
      { encoding: 'utf8' },
    ),
  );
  Object.assign(run, {
    passedTests: native.passedTests,
    failedTests: native.failedTests,
    skippedTests: native.skippedTests,
  });
  await save();
  if (
    code !== 0 ||
    native.failedTests ||
    native.skippedTests ||
    native.passedTests !== testNames.length
  )
    throw Error('All selected native tests must pass without skips');
  const records = Object.values(
    JSON.parse(await readFile(join(stateDirectory, 'desktop-creations/creations.json'), 'utf8')),
  ).filter((record) => record.text?.includes(nonce) || record.text?.includes(attachmentMarker));
  run.creations = [];
  for (const record of records) {
    await get(`/v1/desktop/creations?hostId=${fixture.hostId}&id=${record.clientOperationId}`);
    const state = await ipc.follow(record.threadId);
    const turns = desktopTurns(state ?? {});
    const messages = turns
      .flatMap((turn) => turn.items ?? [])
      .filter((item) => item.type === 'userMessage');
    const first = messages.filter((item) => item.clientId === 'first-' + record.clientOperationId);
    const command = JSON.parse(
      await readFile(
        join(stateDirectory, 'desktop-controller', record.clientOperationId + '.json'),
        'utf8',
      ),
    );
    const control = await ipc.follow(config.threadId);
    const calls = desktopTurns(control)
      .filter((turn) => turn.turnId === command.turnId)
      .flatMap((turn) => turn.items ?? [])
      .filter((item) => item.type === 'mcpToolCall' && item.tool === 'create_thread');
    const evidence = {
      operationId: record.clientOperationId,
      threadId: record.threadId,
      projectId: record.projectId,
      nativeProjectId: record.nativeProjectId,
      actualNativeProjectId: calls[0]?.arguments?.target?.projectId,
      directory: record.directory,
      owner: ipc.hasOwner(record.threadId),
      cwd: state?.cwd,
      workspaceKind: state?.workspaceKind,
      confirmed: record.phase === 'confirmed',
      firstNativeMessages: first.length,
      createCalls: calls.length,
      rendererInputValid: turns.every((turn) =>
        (turn.params?.input ?? []).every(
          (item) => item.type !== 'text' || Array.isArray(item.text_elements),
        ),
      ),
      firstText: first[0]?.content
        ?.filter((item) => item.type === 'text')
        .map((item) => item.text)
        .join('\n'),
      assistant: turns
        .flatMap((turn) => turn.items ?? [])
        .filter((item) => item.type === 'agentMessage')
        .map((item) => item.text),
      nativeContent: first[0]?.content,
    };
    run.creations.push(evidence);
    if (
      !evidence.owner ||
      !evidence.confirmed ||
      first.length !== 1 ||
      calls.length !== 1 ||
      !evidence.rendererInputValid ||
      !evidence.firstText?.startsWith(record.text) ||
      (record.directory !== null &&
        (!record.nativeProjectId || evidence.actualNativeProjectId !== record.nativeProjectId)) ||
      (record.directory !== null && evidence.cwd !== record.directory) ||
      (record.directory === null && evidence.workspaceKind !== 'projectless')
    )
      throw Error('Independent original-desktop creation verification failed');
    if (record.attachments.length) {
      if (
        record.text.includes(nonce) ||
        first[0].content.filter((v) => v.type === 'localImage').length !== 1 ||
        record.attachments.length !== 2
      )
        throw Error('First mixed attachments must include a native image and a hidden file nonce');
      const history = await get(`/v1/history?hostId=${fixture.hostId}&threadId=${record.threadId}`);
      const outputs = history.messages.flatMap((v) => v.attachments);
      const report = outputs.findLast((v) => v.name === 'desktop-native-report.txt');
      const image = outputs.findLast((v) => v.name === 'desktop-native-output.png');
      const download = async (file) => {
        if (!file) throw Error('Missing returned file');
        const response = await fetch(
          endpoint +
            `/v1/attachments?hostId=${fixture.hostId}&threadId=${record.threadId}&id=${file.id}`,
          { headers: { Authorization: 'Bearer ' + token } },
        );
        if (!response.ok) throw Error('Returned file could not be downloaded');
        return Buffer.from(await response.arrayBuffer());
      };
      evidence.fileNonceMatched = (await download(report)).toString().includes(nonce);
      const originalImage = first[0].content.find((v) => v.type === 'localImage');
      const imageBytes = await download(image);
      const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
      evidence.outputImageMatched =
        imageBytes.subarray(0, 8).equals(Buffer.from('89504e470d0a1a0a', 'hex')) &&
        hash(imageBytes) === hash(await readFile(originalImage.path));
      evidence.colorMatched = history.messages.some(
        (v) => v.role === 'assistant' && /红色/.test(v.text),
      );
      if (!evidence.fileNonceMatched || !evidence.outputImageMatched || !evidence.colorMatched)
        throw Error('First attachment output verification failed');
    }
  }
  if (testNames.includes('testOriginalDesktopReplyProgressStopAndApproval')) {
    const history = await get(`/v1/history?hostId=${fixture.hostId}&threadId=${fixture.threadId}`);
    run.ownerControls =
      history.runtime.canControl === true &&
      history.runtime.turnId === null &&
      history.runtime.approvals.length === 0 &&
      history.runtime.receipts.some((v) => v.state === 'interrupted') &&
      ['DESKTOP_IOS_' + nonce, 'DESKTOP_APPROVAL_' + nonce].every((text) =>
        history.messages.some((v) => v.role === 'assistant' && v.text === text),
      );
    if (!run.ownerControls)
      throw Error('Independent original owner stop/approval verification failed');
  }
  if (unloadedBefore) {
    const opened = await ipc.follow(fixture.unloadedThreadId);
    const navigation = (await commands()).filter(
      (command) =>
        !beforeOpenIds.has(command.id) &&
        command.kind === 'open' &&
        command.targetId === fixture.unloadedThreadId,
    );
    const control = await ipc.follow(config.threadId);
    const navigationCalls = desktopTurns(control)
      .filter((turn) => turn.turnId === navigation[0]?.turnId)
      .flatMap((turn) => turn.items ?? [])
      .filter(
        (item) =>
          item.type === 'mcpToolCall' &&
          item.tool === 'navigate_to_codex_page' &&
          item.arguments?.threadId === fixture.unloadedThreadId,
      );
    run.unloadedNavigation = {
      operations: navigation.length,
      appOperation: /^[a-fA-F0-9-]{36}$/.test(navigation[0]?.id ?? ''),
      finished: navigation[0]?.phase === 'finished',
      calls: navigationCalls.length,
    };
    run.unloadedOpen =
      ipc.hasOwner(fixture.unloadedThreadId) &&
      navigation.length === 1 &&
      run.unloadedNavigation.appOperation &&
      run.unloadedNavigation.finished &&
      navigationCalls.length === 1 &&
      desktopTurns(opened ?? {}).some((turn) =>
        (turn.items ?? []).some(
          (item) => item.type === 'agentMessage' && item.text === 'IOS_OPENED_' + nonce,
        ),
      );
    if (!run.unloadedOpen) throw Error('Unloaded original desktop reply was not verified');
  }
  run.afterProcesses = processIds();
  if (JSON.stringify(run.beforeProcesses) !== JSON.stringify(run.afterProcesses))
    throw Error('Original desktop processes changed');
  run.status = 'passed';
  run.verifiedAt = new Date().toISOString();
  await save();
  console.log(
    JSON.stringify({
      nonce,
      status: run.status,
      passedTests: run.passedTests,
      creations: run.creations.length,
      unloadedOpen: run.unloadedOpen,
      resultBundle,
    }),
  );
} catch (error) {
  const file = join(directory, `run-${nonce}.json`);
  try {
    const run = JSON.parse(await readFile(file, 'utf8'));
    run.status = 'failed';
    run.error = error.message;
    run.finishedAt = new Date().toISOString();
    await writeFile(file, JSON.stringify(run, null, 2), { mode: 0o600 });
  } catch {
    /* Failure before a run record was created. */
  }
  throw error;
} finally {
  child?.kill('SIGTERM');
  fixtureServer.close();
  ipc.close();
}
