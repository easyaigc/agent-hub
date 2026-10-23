import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createServer as portServer } from 'node:net';
import { randomUUID, createHash } from 'node:crypto';
import { readFile, writeFile, mkdir, stat } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { basename, dirname } from 'node:path';
import { pathToFileURL } from 'node:url';

// Run with EasyWork's Bun toolchain. Android source and evidence remain in this checkout.
const argument = (name) => {
  const index = process.argv.indexOf(name);
  assert(index >= 0 && process.argv[index + 1], `Missing ${name}`);
  return resolve(process.argv[index + 1]);
};
const root = argument('--desktop-root');
const authRoot = argument('--auth-source-root');
const output = argument('--evidence');
const resumeIndex = process.argv.indexOf('--resume-from');
const previous =
  resumeIndex < 0
    ? undefined
    : JSON.parse(await readFile(resolve(process.argv[resumeIndex + 1]), 'utf8'));
if (previous) {
  assert.equal(previous.identityScope, 'explicit-loopback-DEV-only');
  assert.equal(previous.desktopRoot, root, 'Resume only the same isolated source');
  assert(dirname(previous.userData).startsWith(root + '/.devenv-acceptance/'));
  assert.match(basename(dirname(previous.userData)), /^android-a5-[a-f0-9-]+$/);
  const ports = [
    previous.relayPort,
    previous.lanPort,
    previous.rendererPort,
    previous.observerPort,
  ];
  assert(ports.every((value) => Number.isInteger(value) && value > 1024 && value <= 65535));
  assert.equal(new Set(ports).size, 4);
}
assert(root !== authRoot, 'Real DEV must use its isolated source and dependency checkout');
const moduleAt = (file) => import(pathToFileURL(join(root, file)).href);
const { prepareDevAcceptanceProfile } = await moduleAt('scripts/dev-acceptance-profile.ts');
const { resolveAuthenticatedDevSourceDatabase, scrubRealDevAcceptanceAuth } = await moduleAt(
  'scripts/real-dev-model-acceptance.ts',
);
const { CodexPlusRealSupervisor } = await moduleAt('scripts/codex-plus-real-supervisor.ts');
const source = resolveAuthenticatedDevSourceDatabase(authRoot, { modelRef: 'easyai/GLM-5.2' });
await mkdir(output, { recursive: true, mode: 0o700 });
const toolSource = await readFile(new URL(import.meta.url));
const hostToolSha256 = createHash('sha256').update(toolSource).digest('hex');
await writeFile(join(output, 'harness-' + hostToolSha256 + '.mjs'), toolSource, { mode: 0o600 });
const port = async () => {
  const server = portServer();
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const value = server.address().port;
  await new Promise((r) => server.close(r));
  return value;
};
const [relayPort, lanPort, rendererPort, observerPort] = previous
  ? [previous.relayPort, previous.lanPort, previous.rendererPort, previous.observerPort]
  : await Promise.all([port(), port(), port(), port()]);
const profile = await prepareDevAcceptanceProfile({
  repositoryRoot: root,
  mode: 'persistent',
  persistentName: previous ? basename(dirname(previous.userData)) : 'android-a5-' + randomUUID(),
  reportDirectory: join(root, 'release-e2e', 'android-A5'),
});
const desktopReport = join(output, 'desktop.json');
const controlPath = join(output, 'host-control.json');
const authorization = randomUUID();
const supervisor = new CodexPlusRealSupervisor({
  repositoryRoot: root,
  profile,
  relayEnvironment: {
    ...process.env,
    PORT: String(relayPort),
    EASYAI_REMOTE_LOCAL_DEVELOPMENT: '1',
    EASYAI_REMOTE_LOCAL_STATE_PATH: join(profile.root, 'relay.json'),
    EASYAI_EWDAP_DEV_ACCESS_LIFETIME_MS: '120000',
  },
  desktopEnvironment: {
    EASYAI_BROWSER_AGENT_DEV_ACCEPTANCE_REPORT: desktopReport,
    EASYAI_BROWSER_AGENT_DEV_ACCEPTANCE_SCENARIO: 'codex-plus-real',
    EASYAI_REMOTE_ACCESS_URL: `http://127.0.0.1:${relayPort}`,
    EASYAI_EMBEDDED_HOST_PORT: String(lanPort),
    EASYAI_DESKTOP_DEV_RENDERER_PORT: String(rendererPort),
    EASYAI_DESKTOP_SKIP_STARTUP_RUNTIME_WARMUP: '1',
    EASYAI_DEV_ACCEPTANCE_IMPORT_REAL_DEV_AUTH: '1',
    EASYAI_CONTEXT_COMPRESSION_DEV_AUTH_SOURCE_DATABASE: source,
    EASYAI_CONTEXT_COMPRESSION_DEV_MODEL_REF: 'easyai/GLM-5.2',
    EASYAI_CODEX_PLUS_CONTROL_PATH: controlPath,
    EASYAI_CODEX_PLUS_CONTROL_AUTHORIZATION: authorization,
  },
});
const allowed = new Set([
  'lan.enable',
  'lan.disable',
  'lan.switch-next-turn',
  'response.delay-next-create',
  'response.delay-next-turn',
  'response.delay-next-sync',
  'permissions.confirm-next-turn',
  'desktop.approval-race-next-response',
  'desktop.approval-approve',
  'desktop.filesystem-approve',
  'device.revoke',
  'desktop.device-offer-create',
  'desktop.device-claim-approve',
  'desktop.device-claim-reject',
  'desktop.device-binding-revoke',
  'desktop.observability-policy-enable',
  'desktop.observability-policy-restore',
]);
const controls = [];
let cachedEvidence;
let cachedMtime = 0;
async function nativeEvidence(threadId) {
  const modified = (await stat(desktopReport)).mtimeMs;
  if (modified !== cachedMtime) {
    cachedEvidence = JSON.parse(await readFile(desktopReport, 'utf8'));
    cachedMtime = modified;
  }
  const source = cachedEvidence;
  // The owned child is the DEV launcher; its Electron may exit while the launcher
  // remains alive. Fresh canonical observations are also required for readiness.
  const ownerAlive =
    supervisor.desktop?.exitCode === null &&
    supervisor.desktop?.signalCode === null &&
    Date.now() - Date.parse(source.timestamp) < 120_000;
  const pick = (value, keys) =>
    Object.fromEntries(
      keys.filter((key) => value?.[key] !== undefined).map((key) => [key, value[key]]),
    );
  const snapshot = source.independentDesktop.snapshots.find((item) => item.id === threadId);
  const retainedMethods = new Set([
    'project/create',
    'thread/create',
    'turn/start',
    'turn/steer',
    'turn/cancel',
    'queue/cancel',
    'plan/action',
    'approval/respond',
    'serverRequest/resolve',
    'command/status',
  ]);
  const latestReads = new Set(source.requests.slice(-512));
  // Keep canonical writes and receipts across the full owned trial. Omitted older reads
  // cannot make an earlier model input disappear from the deduplication witness.
  const requests = source.requests.filter(
    (entry) => retainedMethods.has(entry.request.method) || latestReads.has(entry),
  );
  const value = {
    evidenceProjection: 'native-counter-v1',
    sourceTimestamp: source.timestamp,
    status: ownerAlive ? source.status : 'desktop-disconnected',
    desktopOwnerAlive: ownerAlive,
    deviceOffer: source.deviceOffer,
    hostEpoch: source.hostEpoch,
    target: source.target,
    lan: source.lan,
    relayOffer: source.relayOffer,
    controls: source.controls,
    faults: source.faults,
    requests: requests.map((entry) => ({
      transportId: entry.transportId,
      hostEpoch: entry.hostEpoch,
      bindingId: entry.bindingId,
      request: {
        requestId: entry.request.requestId,
        method: entry.request.method,
        target: entry.request.target,
        params: pick(entry.request.params, [
          'threadId',
          'clientRequestId',
          'guidanceId',
          'commandId',
          'turnId',
          'runId',
          'serverRequestId',
          'decision',
          'messageId',
          'action',
          'planId',
          'expectedVersion',
          'expectedPlanVersion',
          'revisionOfPlanId',
        ]),
      },
      response: {
        ok: entry.response?.ok,
        result: pick(entry.response?.result, [
          'state',
          'runId',
          'turnId',
          'accepted',
          'denied',
          'cancelled',
        ]),
        error: entry.response?.error,
      },
    })),
    independentDesktop: {
      executions: source.independentDesktop.executions.map((entry) => ({
        id: entry.id,
        run: pick(entry.run, ['id', 'threadId', 'status']),
        providerUsage: entry.providerUsage.map((usage) =>
          pick(usage, [
            'usageId',
            'usageSource',
            'providerReportedTokens',
            'inputTokens',
            'outputTokens',
            'totalTokens',
            'requestFingerprint',
          ]),
        ),
      })),
      snapshots: snapshot
        ? [
            {
              id: snapshot.id,
              messages: snapshot.messages
                .filter((message) => message.role === 'user')
                .map((message) => ({
                  id: message.id,
                  role: message.role,
                  content: message.content
                    .filter((part) => part.type === 'text')
                    .map((part) => ({
                      type: part.type,
                      data: { text: String(part.text ?? part.data?.text ?? '').slice(0, 4096) },
                    })),
                })),
            },
          ]
        : [],
    },
  };
  const text = JSON.stringify(value);
  assert(Buffer.byteLength(text) <= 2 * 1024 * 1024, 'Native witness summary must remain bounded');
  return text;
}
const observer = createServer((request, response) => {
  void (async () => {
    const url = new URL(request.url, 'http://127.0.0.1');
    if (request.method === 'GET' && url.pathname === '/evidence') {
      response.setHeader('Content-Type', 'application/json');
      response.setHeader('Cache-Control', 'no-store');
      response.end(
        url.searchParams.get('native') === '1'
          ? await nativeEvidence(url.searchParams.get('threadId'))
          : await readFile(desktopReport),
      );
      return;
    }
    if (
      request.method !== 'POST' ||
      request.url !== '/control' ||
      request.headers['x-easywork-acceptance'] !== authorization
    ) {
      response.writeHead(403).end();
      return;
    }
    let body = '';
    for await (const chunk of request) {
      body += chunk;
      assert(body.length <= 2048);
    }
    const input = JSON.parse(body);
    assert(
      typeof input.operation === 'string' &&
        Object.keys(input).every((key) => ['operation', 'threadId', 'bindingId'].includes(key)),
    );
    assert(input.threadId === undefined || typeof input.threadId === 'string');
    assert(input.bindingId === undefined || /^[a-f0-9-]{36}$/i.test(input.bindingId));
    assert(
      (input.operation === 'desktop.device-binding-revoke') === (input.bindingId !== undefined),
    );
    if (['relay.restart', 'host.restart', 'relay.offline'].includes(input.operation)) {
      await supervisor.restart(input.operation);
      await writeFile(
        join(output, 'process-restarts.json'),
        JSON.stringify(supervisor.audit, null, 2),
      );
      response.end(JSON.stringify({ accepted: true }));
      return;
    }
    assert(allowed.has(input.operation));
    const id = randomUUID();
    await writeFile(
      controlPath,
      JSON.stringify({
        id,
        operation: input.operation,
        authorization,
        ...(input.threadId ? { threadId: input.threadId } : {}),
        ...(input.bindingId ? { bindingId: input.bindingId } : {}),
      }),
      { mode: 0o600 },
    );
    const deadline = Date.now() + 30000;
    while (Date.now() < deadline) {
      const state = JSON.parse(await readFile(desktopReport, 'utf8'));
      const done = state.controls.find((c) => c.id === id);
      if (done) {
        controls.push(done);
        response.end(JSON.stringify(done));
        return;
      }
      await new Promise((r) => setTimeout(r, 150));
    }
    throw new Error('Host control not confirmed');
  })().catch((error) => {
    response.writeHead(500);
    response.end(JSON.stringify({ error: error.message }));
  });
});
let closed = false;
async function cleanup() {
  if (closed) return;
  closed = true;
  observer.close();
  await supervisor.stop();
  scrubRealDevAcceptanceAuth(profile.workspacePath);
  await profile.release();
  await writeFile(
    join(output, 'cleanup.json'),
    JSON.stringify(
      {
        stoppedOwnedProcesses: true,
        authScrubbed: true,
        originalCodexUntouched: true,
        controls,
        completedAt: new Date().toISOString(),
      },
      null,
      2,
    ),
  );
}
process.once('SIGTERM', () => {
  void cleanup().then(() => process.exit(0));
});
process.once('SIGINT', () => {
  void cleanup().then(() => process.exit(0));
});
try {
  supervisor.start();
  await new Promise((r) => observer.listen(observerPort, '127.0.0.1', r));
  await writeFile(
    join(output, 'host-config.json'),
    JSON.stringify(
      {
        version: 1,
        relayPort,
        lanPort,
        rendererPort,
        observerPort,
        authorization,
        workspace: profile.workspacePath,
        userData: profile.userDataPath,
        model: 'easyai/GLM-5.2',
        identityScope: 'explicit-loopback-DEV-only',
        grantAccessLifetimeMs: 120000,
        pid: process.pid,
        desktopRoot: root,
        hostToolSha256,
      },
      null,
      2,
    ),
    { mode: 0o600 },
  );
  console.log(JSON.stringify({ started: true, output, relayPort, lanPort, observerPort }));
} catch (error) {
  await cleanup();
  throw error;
}
