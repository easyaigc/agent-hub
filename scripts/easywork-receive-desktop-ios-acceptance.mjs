import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomUUID, randomInt, createHash } from 'node:crypto';
import { spawn, execFileSync } from 'node:child_process';
import { createInterface } from 'node:readline';
import { createWriteStream } from 'node:fs';
import { mkdir, readFile, writeFile, readdir } from 'node:fs/promises';
import { resolve, join, dirname } from 'node:path';
import { pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import {
  verifyReceiveEvidence,
  verifyNativeQueueEvidence,
  verifyClientQueueEvidence,
} from './easywork-receive-evidence.mjs';

// Run with the desktop repository's Bun runtime. The Agent Hub workspace remains pnpm-only.
assert(process.versions.bun, 'Use the Bun runtime required by the external EasyWork DEV harness');
const argument = (name) => {
  const at = process.argv.indexOf(name);
  assert(at >= 0 && process.argv[at + 1], name);
  return process.argv[at + 1];
};
const desktop = resolve(argument('--easywork-root'));
const clientOwnedQueue = process.argv.includes('--client-owned-queue');
const nativeQueue = clientOwnedQueue || process.argv.includes('--native-queue');
const queueRoutes = process.argv.includes('--routes')
  ? argument('--routes').split(',')
  : ['lan', 'relay'];
assert(
  queueRoutes.length &&
    new Set(queueRoutes).size === queueRoutes.length &&
    queueRoutes.every((route) => ['lan', 'relay'].includes(route)),
);
const device = argument('--device');
const root = process.cwd();
const nativeProject = resolve(process.env.IOS_PROJECT_PATH ?? 'apps/ios/CodexManage.xcodeproj');
const nativeRoot = dirname(nativeProject);
const nonce = randomUUID().slice(0, 8);
const directory = join(desktop, 'release-e2e/codex-plus-receive', nonce);
await mkdir(directory, { recursive: true, mode: 0o700 });
const local = resolve('.local/easywork-receive-performance', 'desktop-' + nonce);
await mkdir(local, { recursive: true, mode: 0o700 });
const imported = async (name) => import(pathToFileURL(join(desktop, 'scripts', name)));
const { prepareDevAcceptanceProfile } = await imported('dev-acceptance-profile.ts');
const { CodexPlusRealSupervisor, allocateRealDevPort } = await imported(
  'codex-plus-real-supervisor.ts',
);
const {
  resolveAuthenticatedDevSourceDatabase,
  parseRealDevAcceptanceDatabase,
  REAL_DEV_AUTH_SOURCE_DATABASE_ENV,
  REAL_DEV_MODEL_REF_ENV,
  scrubRealDevAcceptanceAuth,
} = await imported('real-dev-model-acceptance.ts');
const { DEV_ACCEPTANCE_IMPORT_REAL_DEV_AUTH_ENV } = await import(
  pathToFileURL(
    join(desktop, 'apps/desktop/src/main/acceptance/context-compression-dev-auth-seed.ts'),
  )
);
const preparingLogin = process.argv.includes('--prepare-login');
const source = resolveAuthenticatedDevSourceDatabase(desktop, {
  requireAuthentication: !preparingLogin,
});
const configuredModel = JSON.parse(
  execFileSync(
    '/usr/bin/sqlite3',
    [
      '-readonly',
      source,
      "select value_json from settings where scope='global' and key='model.defaults.llm'",
    ],
    { encoding: 'utf8' },
  ),
);
const model =
  process.env[REAL_DEV_MODEL_REF_ENV] ?? `${configuredModel.providerId}/${configuredModel.modelId}`;
parseRealDevAcceptanceDatabase(source, { modelRef: model, requireAuthentication: !preparingLogin });
const profile = await prepareDevAcceptanceProfile({
  repositoryRoot: desktop,
  mode: 'persistent',
  persistentName: 'receive-' + nonce,
  reportDirectory: directory,
});
// Keep acceptance listeners outside the OS ephemeral client port range. A
// released port-0 reservation can otherwise be taken by startup's own sockets.
const allocateQueuePort = async () => {
  for (let attempt = 0; attempt < 20; attempt++) {
    const port = randomInt(20000, 30000),
      probe = createServer();
    try {
      await new Promise((done, reject) => {
        probe.once('error', reject);
        probe.listen(port, '::', done);
      });
      await new Promise((done) => probe.close(done));
      return port;
    } catch (error) {
      if (error.code !== 'EADDRINUSE') throw error;
    }
  }
  throw new Error('No isolated queue DEV listener port is available');
};
const ports = [];
while (ports.length < 3) {
  const port = await (nativeQueue ? allocateQueuePort() : allocateRealDevPort());
  if (!ports.includes(port)) ports.push(port);
}
const relayURL = 'http://127.0.0.1:' + ports[0];
const environment = {
  ...process.env,
  DEVELOPER_DIR: process.env.DEVELOPER_DIR ?? '/Applications/Xcode.app/Contents/Developer',
};
const desktopReport = join(directory, 'desktop.json');
const queueControlPath = join(directory, 'native-queue-control.json');
const queueControlAuthorization = randomUUID();
const report = {
  kind: nativeQueue
    ? 'real-easywork-electron-native-queue'
    : 'real-easywork-electron-native-receive',
  nonce,
  device,
  clientOwnedQueue,
  model,
  desktopReport,
  profile: profile.root,
  ui: {},
  status: 'running',
};
const nativeDigest = createHash('sha256');
const nativeSourceFiles = (await readdir(join(nativeRoot, 'Sources')))
  .filter((name) => name.endsWith('.swift'))
  .sort();
for (const name of nativeSourceFiles)
  nativeDigest.update(name).update(await readFile(join(nativeRoot, 'Sources', name)));
report.nativeSourceSha256 = nativeDigest
  .update(await readFile(join(nativeProject, 'project.pbxproj')))
  .digest('hex');
report.producerSha256 = createHash('sha256')
  .update(
    await readFile(join(desktop, 'apps/desktop/src/main/remote-access/remote-thread-delta.ts')),
  )
  .digest('hex');
if (nativeQueue) {
  const digest = createHash('sha256');
  for (const file of [
    'apps/desktop/src/main/remote-access/remote-prompt-queue.ts',
    ...(clientOwnedQueue
      ? [
          'apps/desktop/src/main/remote-access/client-prompt-queue-port.ts',
          'apps/desktop/src/renderer/src/features/chat/store/client-prompt-queue.ts',
          'packages/shared/src/ipc/client-prompt-queue.ts',
        ]
      : ['apps/desktop/src/main/remote-access/remote-queue-operations.ts']),
    'apps/desktop/src/main/remote-access/remote-runtime-bridge.ts',
    'apps/desktop/src/main/remote-access/remote-runtime-capabilities.ts',
    'apps/desktop/src/main/remote-access/desktop-remote-access-controller.ts',
    'apps/desktop/src/main/ipc/remote-access/handlers.ts',
    'apps/desktop/src/preload/api/remote-access.ts',
    ...(!clientOwnedQueue
      ? ['apps/desktop/src/renderer/src/features/chat/components/HostPromptQueueList.tsx']
      : []),
    'apps/desktop/src/renderer/src/features/chat/components/PromptQueueList.tsx',
    'packages/agent-protocol/src/v2.ts',
    'packages/shared/src/ipc/remote-access.ts',
  ])
    digest.update(file).update(await readFile(join(desktop, file)));
  report.nativeQueueImplementationSha256 = digest.digest('hex');
}
if (nativeQueue && process.argv.includes('--reuse-native-queue')) {
  const sourcePath = resolve(argument('--reuse-native-queue'));
  const previous = JSON.parse(await readFile(sourcePath, 'utf8'));
  for (const key of [
    'nativeSourceSha256',
    'producerSha256',
    'nativeQueueImplementationSha256',
    'model',
  ])
    assert.equal(previous[key], report[key], 'Cannot reuse changed queue input: ' + key);
  const witness = JSON.parse(await readFile(previous.desktopReport, 'utf8'));
  const log = await readFile(join(dirname(sourcePath), 'xcode.log'), 'utf8');
  for (const route of ['lan', 'relay'].filter((route) => !queueRoutes.includes(route))) {
    const verified = (clientOwnedQueue ? verifyClientQueueEvidence : verifyNativeQueueEvidence)(
      witness,
      previous.nonce,
      log,
      [route],
    );
    report[route] = { ...verified[route], outcome: 'reused-pass', sourceReport: sourcePath };
  }
}
const save = () =>
  writeFile(join(local, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
const supervisor = new CodexPlusRealSupervisor({
  repositoryRoot: desktop,
  profile,
  relayEnvironment: {
    ...environment,
    PORT: String(ports[0]),
    EASYAI_REMOTE_LOCAL_DEVELOPMENT: '1',
    EASYAI_REMOTE_LOCAL_STATE_PATH: join(profile.root, 'relay.json'),
  },
  desktopEnvironment: {
    ...(nativeQueue
      ? {
          EASYAI_CODEX_PLUS_CONTROL_PATH: queueControlPath,
          EASYAI_CODEX_PLUS_CONTROL_AUTHORIZATION: queueControlAuthorization,
        }
      : {}),
    EASYAI_BROWSER_AGENT_DEV_ACCEPTANCE_REPORT: desktopReport,
    EASYAI_BROWSER_AGENT_DEV_ACCEPTANCE_SCENARIO: 'codex-plus-real',
    EASYAI_REMOTE_ACCESS_URL: relayURL,
    EASYAI_EMBEDDED_HOST_PORT: String(ports[1]),
    EASYAI_DESKTOP_SKIP_STARTUP_RUNTIME_WARMUP: '1',
    EASYAI_DESKTOP_DEV_RENDERER_PORT: String(ports[2]),
    [DEV_ACCEPTANCE_IMPORT_REAL_DEV_AUTH_ENV]: '1',
    [REAL_DEV_AUTH_SOURCE_DATABASE_ENV]: source,
    [REAL_DEV_MODEL_REF_ENV]: model,
  },
});
let server, child;
try {
  await save();
  console.log('EasyWork receive DEV: ' + local);
  supervisor.start();
  const deadline = Date.now() + (preparingLogin ? 600000 : 300000);
  let ready;
  while (Date.now() < deadline) {
    try {
      const value = JSON.parse(await readFile(desktopReport, 'utf8'));
      if (value.status === 'ready' && value.lan?.pairingUri && value.relayOffer?.pairingCode) {
        ready = value;
        break;
      }
    } catch {
      /* DEV is still starting. */
    }
    await delay(500);
  }
  assert(ready, 'The real isolated Electron Host must be ready');
  // Bootstrap may open the official login UI, but no model test runs before the strict gate passes.
  parseRealDevAcceptanceDatabase(
    join(profile.workspacePath, '.easyai-desktop/easyai-desktop.sqlite'),
    { modelRef: model },
  );
  report.target = ready.target;
  server = createServer(async (req, res) => {
    if (
      !(
        (req.method === 'GET' && req.url === '/evidence') ||
        (nativeQueue && req.method === 'POST' && req.url === '/control')
      ) ||
      req.headers.origin ||
      !['127.0.0.1:8809', 'localhost:8809'].includes(req.headers.host)
    ) {
      res.writeHead(403).end();
      return;
    }
    try {
      if (req.method === 'POST') {
        let body = '';
        for await (const chunk of req) {
          body += chunk;
          assert(body.length <= 4096);
        }
        const command = JSON.parse(body);
        assert(
          ['desktop.queue-capture', 'desktop.queue-cancel', 'desktop.queue-execute'].includes(
            command.operation,
          ),
        );
        assert(typeof command.threadId === 'string');
        const id = randomUUID();
        await writeFile(
          queueControlPath,
          JSON.stringify({ ...command, id, authorization: queueControlAuthorization }),
          { mode: 0o600 },
        );
        res
          .writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
          .end(JSON.stringify({ id }));
        return;
      }
      const value = JSON.parse(await readFile(desktopReport, 'utf8'));
      res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      res.end(
        JSON.stringify({
          ...value,
          relayURL,
          receiveNonce: nonce,
          nativeQueue,
          clientOwnedQueue,
          queueRoutes,
        }),
      );
    } catch {
      res.writeHead(503).end();
    }
  });
  await new Promise((done, reject) => {
    server.once('error', reject);
    server.listen(8809, '127.0.0.1', done);
  });
  report.resultBundle = join(local, 'result.xcresult');
  child = spawn(
    'xcodebuild',
    [
      '-project',
      nativeProject,
      '-scheme',
      'CodexManage',
      '-destination',
      'platform=iOS Simulator,id=' + device,
      '-derivedDataPath',
      resolve(process.env.NATIVE_QUEUE_DERIVED_DATA ?? join(local, 'build')),
      '-resultBundlePath',
      report.resultBundle,
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
      '-only-testing:CodexManageUITests/CodexManageUITests/testEasyWorkReceivePerformanceDEV',
      'CODE_SIGNING_ALLOWED=YES',
      'CODE_SIGN_IDENTITY=-',
      'test',
    ],
    { cwd: root, env: environment, stdio: ['ignore', 'pipe', 'pipe'] },
  );
  const log = createWriteStream(join(local, 'xcode.log'), { mode: 0o600 });
  for (const stream of [child.stdout, child.stderr])
    createInterface({ input: stream }).on('line', (line) => {
      // Offers are only in the private native log; never echo keyboard text or environment dumps.
      log.write(line + '\n');
      const stamp = line.match(
        /EASYWORK_RECEIVE_UI (lan|relay) (send|first|final) (\d+(?:\.\d+)?)/,
      );
      if (stamp) {
        (report.ui[stamp[1]] ??= {})[stamp[2] + 'At'] = Number(stamp[3]) * 1000;
      }
      if (
        /^Test Case|: error:|^\*\* TEST|EASYWORK_RECEIVE_UI|EASYWORK_NATIVE_QUEUE|EASYWORK_CLIENT_QUEUE/.test(
          line,
        )
      )
        console.log(line);
    });
  const code = await new Promise((done, reject) => {
    child.once('exit', done);
    child.once('error', reject);
  });
  await new Promise((done) => log.end(done));
  if (nativeQueue) {
    const metricsResponse = await fetch(new URL('/metrics', relayURL));
    assert(metricsResponse.ok);
    const metrics = await metricsResponse.text();
    await writeFile(join(local, 'relay-metrics.txt'), metrics, { mode: 0o600 });
    report.relayRateLimitedRequests = Number(
      metrics.match(/remote_access_denied_total\{reason="rate_limited"\} (\d+)/)?.[1] ?? 0,
    );
    report.relayReplayRejections = Number(
      metrics.match(/remote_access_errors_total\{code="relay_replay"\} (\d+)/)?.[1] ?? 0,
    );
  }
  assert.equal(code, 0, 'Native UI verification failed; original evidence is preserved');
  if (nativeQueue) {
    assert.equal(report.relayRateLimitedRequests, 0, 'Relay request budget exhausted');
    assert.equal(report.relayReplayRejections, 0, 'Relay rejected reordered envelope sequences');
  }
  const witness = JSON.parse(await readFile(desktopReport, 'utf8'));
  const finalNativeDigest = createHash('sha256');
  for (const name of nativeSourceFiles)
    finalNativeDigest.update(name).update(await readFile(join(nativeRoot, 'Sources', name)));
  assert.equal(
    finalNativeDigest.update(await readFile(join(nativeProject, 'project.pbxproj'))).digest('hex'),
    report.nativeSourceSha256,
    'Native product sources changed during this scene',
  );
  Object.assign(
    report,
    (clientOwnedQueue
      ? verifyClientQueueEvidence
      : nativeQueue
        ? verifyNativeQueueEvidence
        : verifyReceiveEvidence)(
      witness,
      nonce,
      await readFile(join(local, 'xcode.log'), 'utf8'),
      ...(nativeQueue ? [queueRoutes] : []),
    ),
  );
  if (nativeQueue) for (const route of queueRoutes) report[route].outcome = 'current-pass';
  report.status =
    nativeQueue && !['lan', 'relay'].every((route) => report[route]) ? 'partial' : 'passed';
  console.log(
    JSON.stringify({
      status: report.status,
      nativeSourceSha256: report.nativeSourceSha256,
      ui: report.ui,
      lan: report.lan,
      relay: report.relay,
    }),
  );
} catch (error) {
  report.status = 'failed';
  report.failure = error.message;
  if (nativeQueue) {
    try {
      const witness = JSON.parse(await readFile(desktopReport, 'utf8'));
      const log = await readFile(join(local, 'xcode.log'), 'utf8');
      for (const route of queueRoutes) {
        if (!log.includes('EASYWORK_NATIVE_QUEUE_PASSED ' + route)) continue;
        Object.assign(report, verifyNativeQueueEvidence(witness, nonce, log, [route]));
        report[route].outcome = 'current-pass';
      }
    } catch (verificationError) {
      report.partialVerificationError = verificationError.message;
    }
  }
  throw error;
} finally {
  await save();
  if (child && child.exitCode === null) child.kill('SIGTERM');
  if (server) {
    server.closeAllConnections();
    await new Promise((done) => server.close(done));
  }
  await supervisor.stop();
  await scrubRealDevAcceptanceAuth(profile.workspacePath);
}
