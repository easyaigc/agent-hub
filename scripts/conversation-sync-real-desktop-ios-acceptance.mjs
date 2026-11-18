import assert from 'node:assert/strict';
import { createServer, request } from 'node:http';
import { spawn, execFileSync } from 'node:child_process';
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { registerHooks } from 'node:module';
import { createHash } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';

// Native UI + a real, independent Host follower of an already-running desktop.
// The gate controls projection duration, never the owner's data or model input.
const output = process.env.SYNC_UI_OUTPUT && resolve(process.env.SYNC_UI_OUTPUT);
const device = process.env.IOS_SIMULATOR_ID;
const threadId = process.env.SYNC_UI_THREAD_ID;
const phase = process.env.SYNC_UI_PHASE;
assert(output && device && threadId && ['baseline', 'fixed'].includes(phase));
assert(process.env.CODEX_EXECUTABLE, 'Require the real installed Codex executable');
assert(process.env.SYNC_UI_DERIVED_DATA, 'Require task-owned native build products');
await mkdir(output, { mode: 0o700 });
if (phase === 'baseline') {
  const original = process.env.SYNC_UI_BASELINE_MODULE;
  assert(original, 'Require the frozen original module');
  const adapter = pathToFileURL(resolve('packages/codex-adapter/dist/index.js')).href;
  const serverModule = pathToFileURL(resolve('apps/host-agent/dist/runtime-server.js')).href;
  const shim = `export * from ${JSON.stringify(adapter)};export {RuntimeSession} from ${JSON.stringify(pathToFileURL(resolve(original)).href)};`;
  registerHooks({
    resolve(specifier, context, next) {
      if (specifier === '@codex-manage/codex-adapter' && context.parentURL === serverModule)
        return { url: 'data:text/javascript,' + encodeURIComponent(shim), shortCircuit: true };
      return next(specifier, context);
    },
  });
}
const { startRuntimeServer } = await import('../apps/host-agent/dist/runtime-server.js');
const state = join(output, 'host');
Object.assign(process.env, {
  CODEX_RUNTIME_MODE: 'desktop-runtime',
  HOST_AGENT_STATE_DIR: state,
  HOST_AGENT_ROOT: process.cwd(),
  HOST_AGENT_NAME: 'Conversation sync independent DEV',
  HOST_AGENT_BIND: '127.0.0.1',
  HOST_AGENT_PORT: '0',
  HOST_AGENT_DISCOVERY: '0',
  HOST_AGENT_DISCOVERY_BONJOUR: '0',
  CODEX_DESKTOP_SUGGESTION_OBSERVER: '0',
});
for (const key of [
  'HOST_AGENT_TOKEN',
  'HOST_AGENT_TLS_CERT',
  'HOST_AGENT_TLS_KEY',
  'CODEX_DESKTOP_CONTROLLER_CONFIG',
  'CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG',
  'HOST_NOTIFICATION_RELAY_URL',
  'HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE',
])
  delete process.env[key];
let service, server, child, release;
const report = {
  phase,
  device,
  threadId,
  environment:
    'real Electron + independent real Host + native simulator; controlled projection delay',
  startedAt: new Date().toISOString(),
  samples: [],
  writes: 0,
  readAcknowledgments: 0,
  pendingTargetPolls: 0,
  pendingTargetUnavailable: 0,
};
const save = () =>
  writeFile(join(output, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
try {
  service = await startRuntimeServer();
  await service.session.workspace();
  const initial = await service.session.refreshHistory(threadId, undefined, true);
  assert.equal(initial.threadId, threadId);
  assert.equal(initial.synchronization.state, 'live');
  assert.equal(initial.runtime.canControl, true);
  const token = (await readFile(join(state, 'access-token'), 'utf8')).trim();
  const port = service.server.address().port;
  const originalProjection = service.session.projectDesktopMessages.bind(service.session);
  let entered,
    gate,
    armed = false,
    cycle = 0;
  service.session.projectDesktopMessages = async (thread, ...args) => {
    if (thread.id === threadId && armed) {
      entered?.();
      await gate;
    }
    return originalProjection(thread, ...args);
  };
  const originalPoll = service.session.poll.bind(service.session);
  service.session.poll = async (...args) => {
    const result = await originalPoll(...args);
    const pending = armed;
    const owner = service.session.runtime.ipc.hasOwner(threadId);
    const events = result.events.map((event) => ({
      threadId: event.threadId,
      state: event.snapshot.synchronization?.state,
    }));
    if (pending && owner && args[3] === threadId) {
      report.pendingTargetPolls++;
      report.pendingTargetUnavailable += events.filter(
        (event) => event.threadId === threadId && event.state === 'unavailable',
      ).length;
    }
    report.samples.push({
      at: new Date().toISOString(),
      cursor: result.cursor,
      reset: result.reset,
      requestedThreadId: args[3] ?? null,
      pending,
      owner,
      cycle,
      events,
      states: events.map((event) => event.state),
    });
    return result;
  };
  const reads = new Set([
    '/healthz',
    '/v1/workspace',
    '/v1/history',
    '/v1/events',
    '/v1/models',
    '/v1/usage',
    '/v1/threads/next-turn-suggestion',
    '/v1/notifications/status',
  ]);
  server = createServer(async (req, res) => {
    try {
      const url = new URL(req.url, 'http://127.0.0.1');
      const readAcknowledgment = req.method === 'POST' && url.pathname === '/v1/threads/read';
      if (req.headers.origin || (req.method !== 'GET' && !readAcknowledgment)) {
        report.writes++;
        return res.writeHead(403).end();
      }
      if (url.pathname === '/') {
        const fixture = {
          endpoint: 'http://127.0.0.1:18943',
          token,
          hostId: service.session.hostId,
          threadId,
          threadTitle: initial.thread.title,
          nonce: phase,
          secondEndpoint: '',
          secondToken: '',
          secondHostId: '',
          delayedEndpoint: '',
        };
        return res
          .writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' })
          .end(JSON.stringify(fixture));
      }
      if (url.pathname === '/arm') {
        assert(!armed);
        armed = true;
        cycle++;
        report.armedAt = new Date().toISOString();
        gate = new Promise((done) => {
          release = done;
        });
        const active = new Promise((done) => {
          entered = done;
        });
        service.session.runtime.ipc.refreshSnapshot(threadId, true);
        await Promise.race([
          active,
          delay(8000).then(() => {
            throw Error('Real owner refresh did not arrive');
          }),
        ]);
        return res.writeHead(200).end(JSON.stringify({ armed: true, cycle }));
      }
      if (url.pathname === '/release') {
        armed = false;
        report.releasedAt = new Date().toISOString();
        release?.();
        return res.writeHead(200).end(JSON.stringify({ released: true, cycle }));
      }
      if (!reads.has(url.pathname) && !readAcknowledgment) return res.writeHead(403).end();
      if (readAcknowledgment) report.readAcknowledgments++;
      // A read-only transport proxy; production Host responses pass unchanged.
      const forwarded = request(
        {
          hostname: '127.0.0.1',
          port,
          path: req.url,
          method: req.method,
          headers: { ...req.headers, host: `127.0.0.1:${port}` },
        },
        (upstream) => {
          res.writeHead(upstream.statusCode, upstream.headers);
          upstream.pipe(res);
        },
      );
      forwarded.on('error', () => {
        if (!res.headersSent) res.writeHead(502);
        res.end();
      });
      res.on('close', () => forwarded.destroy());
      req.pipe(forwarded);
    } catch (error) {
      if (!res.headersSent) res.writeHead(500);
      res.end(JSON.stringify({ error: error.message }));
    }
  });
  await new Promise((done, reject) => {
    server.once('error', reject);
    server.listen(18943, '127.0.0.1', done);
  });
  report.hostId = service.session.hostId;
  report.moduleSha256 = createHash('sha256')
    .update(
      await readFile(
        phase === 'baseline'
          ? process.env.SYNC_UI_BASELINE_MODULE
          : 'packages/codex-adapter/dist/runtime-session.js',
      ),
    )
    .digest('hex');
  await save();
  const result = join(output, 'result.xcresult');
  const args = [
    '-project',
    'apps/ios/CodexManage.xcodeproj',
    '-scheme',
    'CodexManage',
    '-destination',
    `platform=iOS Simulator,id=${device}`,
    '-derivedDataPath',
    process.env.SYNC_UI_DERIVED_DATA,
    '-resultBundlePath',
    result,
    '-parallel-testing-enabled',
    'NO',
    '-collect-test-diagnostics',
    'never',
    '-only-testing:CodexManageUITests/CodexManageUITests/testConversationSyncRealDesktopProjection',
    'ARCHS=arm64',
    'ONLY_ACTIVE_ARCH=YES',
    'NOTIFICATION_APP_BUNDLE_ID=com.easyai.agenthub.macbook128sync',
    'CODE_SIGNING_ALLOWED=YES',
    'CODE_SIGN_IDENTITY=-',
    'test',
  ];
  child = spawn('xcodebuild', args, { env: process.env, stdio: ['ignore', 'pipe', 'pipe'] });
  let log = '';
  for (const stream of [child.stdout, child.stderr])
    stream.on('data', (chunk) => {
      log += chunk.toString();
    });
  report.exitCode = await new Promise((done, reject) => {
    child.once('exit', done);
    child.once('error', reject);
  });
  const fragments = [token, token.slice(0, 18), token.slice(0, 10), token.slice(0, 6)];
  log = log
    .split('\n')
    .map((line) =>
      /Type .*SecureTextField/.test(line) || fragments.some((value) => line.includes(value))
        ? '[REDACTED credential activity]'
        : line,
    )
    .join('\n');
  await writeFile(join(output, 'xcode.log'), log, { mode: 0o600 });
  report.native = JSON.parse(
    execFileSync(
      'xcrun',
      ['xcresulttool', 'get', 'test-results', 'summary', '--path', result, '--format', 'json'],
      { encoding: 'utf8' },
    ),
  );
  await writeFile(
    join(output, 'xcresult-summary.json'),
    JSON.stringify(report.native, null, 2) + '\n',
    { mode: 0o600 },
  );
  // XCTest can include truncated secure typing in its activity database. Keep
  // only named post-connection screenshots, then remove the sensitive bundle.
  const screenshots = join(output, 'screenshots');
  await mkdir(screenshots, { mode: 0o700 });
  try {
    execFileSync(
      'xcrun',
      [
        'xcresulttool',
        'export',
        'attachments',
        '--path',
        result,
        '--output-path',
        screenshots,
        '--filter',
        'sync-real-desktop-*',
      ],
      { stdio: 'ignore' },
    );
  } finally {
    await rm(result, { recursive: true, force: true });
  }
  assert.equal(report.exitCode, 0);
  assert.equal(report.native.passedTests, 1);
  assert.equal(report.native.failedTests, 0);
  assert.equal(report.native.skippedTests, 0);
  assert.equal(report.writes, 0, 'No model/action write is permitted');
  assert(report.pendingTargetPolls > 0, 'Require actual polling of the gated target');
  if (phase === 'fixed')
    assert.equal(report.pendingTargetUnavailable, 0, 'Pending target owner must stay available');
  report.status = 'passed';
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  throw error;
} finally {
  release?.();
  if (child?.exitCode == null) child?.kill('SIGTERM');
  server?.closeAllConnections();
  if (server) await new Promise((done) => server.close(done));
  if (service) await service.close();
  report.finishedAt = new Date().toISOString();
  await save();
}
