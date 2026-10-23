import assert from 'node:assert/strict';
import { createServer as httpsServer } from 'node:https';
import { createServer as httpServer } from 'node:http';
import { spawn, execFile, execFileSync } from 'node:child_process';
import { promisify } from 'node:util';
import { createInterface } from 'node:readline';
import { mkdir, readFile, writeFile, chmod, readdir, rm } from 'node:fs/promises';
import { createHash, X509Certificate, randomUUID, randomBytes } from 'node:crypto';
import { resolve, join } from 'node:path';
import { DesktopRuntime, desktopTurns } from '../../packages/codex-adapter/dist/desktop-ipc.js';
import { CodexRuntime } from '../../packages/codex-adapter/dist/runtime.js';
import { replyIntentFingerprint } from '../../packages/codex-adapter/dist/runtime-session.js';
import { preparedAdapterDigest } from '../../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { installAndroidApkIfNeeded } from './android-install-if-needed.mjs';

const serial = process.env.ANDROID_SERIAL,
  executable = process.env.CODEX_EXECUTABLE;
assert(
  executable &&
    serial &&
    (serial.startsWith('emulator-') || process.env.ANDROID_ALLOW_PHYSICAL === '1'),
);
const physical = !serial.startsWith('emulator-');
const adb = resolve(
  JSON.parse(await readFile('.local/mobile-toolchain.json', 'utf8')).androidSdk,
  'platform-tools/adb',
);
const run = promisify(execFile);
const device = async (...args) =>
  (
    await run(adb, ['-s', serial, ...args], {
      encoding: 'utf8',
      timeout: 600_000,
      maxBuffer: 16 * 1024 * 1024,
    })
  ).stdout.trim();
assert.equal(await device('get-state'), 'device');
const root = resolve(
  '.local/android-development/upstream-migration/UM02/live-' + serial + '-' + Date.now(),
);
await mkdir(root, { recursive: true, mode: 0o700 });
const state = join(root, 'host');
await mkdir(state, { mode: 0o700 });
const nonce = randomUUID().slice(0, 8),
  hostId = randomUUID(),
  controlKey = randomBytes(32).toString('hex');
const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');
const fileSha = async (path) => sha(await readFile(path));
const sourceNames = [
  ...new Set(
    execFileSync(
      'git',
      [
        'ls-files',
        '-co',
        '--exclude-standard',
        'apps/android',
        'apps/host-agent',
        'packages/codex-adapter',
        'packages/mobile-core',
        'packages/protocol',
        'scripts/mobile/android-reply-recovery-live.mjs',
        'scripts/mobile/reply-recovery-live-host.mjs',
      ],
      { encoding: 'utf8' },
    )
      .trim()
      .split('\n')
      .filter(Boolean),
  ),
].sort();
const sourceFingerprint = async () => {
  const h = createHash('sha256');
  for (const name of sourceNames)
    h.update(name + '\0')
      .update(await readFile(name))
      .update('\0');
  return h.digest('hex');
};
const protectedConfig = join(
  process.env.CODEX_HOME ?? resolve(process.env.HOME, '.codex'),
  'config.toml',
);
const processes = () => {
  const rows = execFileSync('ps', ['-axo', 'pid,ppid,command'], { encoding: 'utf8' })
    .split('\n')
    .map((line) => line.trim().match(/^(\d+)\s+(\d+)\s+(.*)$/))
    .filter(Boolean);
  const main = rows.filter((row) =>
    /^\/Applications\/ChatGPT.app\/Contents\/MacOS\/ChatGPT(?: |$)/.test(row[3]),
  );
  assert.equal(main.length, 1, 'One unchanged original desktop');
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
const report = {
  status: 'running',
  kind: 'UM02-original-owner-native-recovery',
  serial,
  physical,
  nonce,
  hostId,
  scope:
    'Isolated DEV preflight fault and operator-audited non-forwarded reply handoff; actual original Electron/model/native TLS, not public deployment.',
  sourceSha256: await sourceFingerprint(),
  adapterSha256: preparedAdapterDigest(),
  configBefore: await fileSha(protectedConfig),
  processesBefore: processes(),
  hostPids: [],
  requests: [],
  observations: [],
  realModelTasks: 0,
  api: Number(await device('shell', 'getprop', 'ro.build.version.sdk')),
  screenshots: [],
};
const save = () =>
  writeFile(join(root, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
await save();
await writeFile(
  join(state, 'runtime.json'),
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
const witness = new DesktopRuntime(
  { mode: 'desktop-runtime', executable },
  hostId,
  'UM02 independent original owner witness',
);
const wait = async (probe, label, timeout = 240_000) => {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const value = await probe();
    if (value) return value;
    await new Promise((done) => setTimeout(done, 400));
  }
  throw Error('Unknown result; no replay: ' + label);
};
let host,
  endpoint,
  token,
  proxy,
  control,
  target,
  projectId,
  droppedReply,
  droppedTarget,
  firstIntent;
let proxyPort,
  controlPort,
  retryForwarded = false,
  restartingHost = false,
  closing = false;
const creations = async () =>
  Object.values(
    JSON.parse(await readFile(join(state, 'desktop-creations/creations.json'), 'utf8')),
  );
const request = async (route) => {
  const response = await fetch(endpoint + route, {
    headers: { Authorization: 'Bearer ' + token },
    redirect: 'error',
    signal: AbortSignal.timeout(45_000),
  });
  assert.equal(response.status, 200, 'Exact isolated Host read');
  return response.json();
};
const terminate = async () => {
  const owned = host;
  host = undefined;
  if (!owned || owned.exitCode !== null) return;
  await new Promise((done, reject) => {
    owned.once('close', done);
    owned.once('error', reject);
    owned.kill('SIGTERM');
  });
};
const launch = async (checkpointBase) => {
  host = spawn(process.execPath, [resolve('scripts/mobile/reply-recovery-live-host.mjs')], {
    env: {
      ...process.env,
      CODEX_RUNTIME_MODE: 'desktop-runtime',
      CODEX_EXECUTABLE: executable,
      CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: join(state, 'bridge.json'),
      HOST_AGENT_STATE_DIR: state,
      HOST_AGENT_ROOT: join(root, 'project'),
      HOST_AGENT_BIND: '127.0.0.1',
      HOST_AGENT_PORT: '0',
      HOST_AGENT_DISCOVERY_BONJOUR: '0',
      UM02_ACCEPTANCE_NONCE: nonce,
      UM02_ACCEPTANCE_FAIL_PREFLIGHT: checkpointBase ? '0' : '1',
      ...(checkpointBase
        ? {
            UM02_ACCEPTANCE_CHECKPOINT_BASE: checkpointBase,
            HOST_AGENT_REPLY_CHECKPOINT_FILE: join(root, 'successor-checkpoint.json'),
          }
        : {}),
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const owned = host;
  report.hostPids.push(owned.pid);
  let address,
    diagnostics = '';
  owned.stderr.on('data', (bytes) => (diagnostics += bytes.toString()));
  owned.once(
    'close',
    () =>
      void writeFile(join(root, 'host-' + owned.pid + '-diagnostics.log'), diagnostics, {
        mode: 0o600,
      }),
  );
  createInterface({ input: host.stdout }).on('line', (line) => {
    try {
      const value = JSON.parse(line);
      if (value.service === 'host-agent') address = value.address;
    } catch {
      /* Read only structured startup. */
    }
  });
  await wait(
    () => {
      if (host.exitCode !== null) throw Error('Owned Host exited: ' + diagnostics.slice(-500));
      return address;
    },
    'owned Host startup',
    30_000,
  );
  endpoint = 'http://127.0.0.1:' + address.port;
  token = (await readFile(join(state, 'access-token'), 'utf8')).trim();
  await save();
};
async function observe(threadId, operationId, marker, policy) {
  await witness.ipc.follow(threadId, 15_000, true);
  const turn = await wait(async () => {
    const turns = desktopTurns(witness.ipc.state(threadId) ?? {});
    const matches = turns.filter((t) =>
      (t.items ?? []).some((i) => i.type === 'userMessage' && i.clientId === operationId),
    );
    assert(matches.length <= 1, 'No duplicate actual Turn for exact operation');
    const value = matches[0];
    if (value?.status === 'completed') return value;
    const history = await request('/v1/history?' + new URLSearchParams({ hostId, threadId }));
    assert(!history.runtime?.approvals?.length, 'Preserve real approval; never auto-approve');
    assert(!history.runtime?.error, 'Real owner failed');
    return null;
  }, 'original owner completion ' + marker);
  assert(witness.ipc.hasOwner(threadId));
  assert.equal(turn.params.sandboxPolicy.type, policy);
  assert.equal(turn.params.approvalPolicy, policy === 'dangerFullAccess' ? 'never' : 'on-request');
  assert.equal(turn.params.model ?? turn.params.collaborationMode?.settings?.model, 'gpt-6.1-sol');
  assert.equal(
    turn.params.effort ?? turn.params.collaborationMode?.settings?.reasoning_effort,
    'xhigh',
  );
  assert.equal(
    turn.items.filter((i) => i.type === 'userMessage' && i.clientId === operationId).length,
    1,
  );
  assert(turn.items.some((i) => i.type === 'agentMessage' && i.text?.trim() === marker));
  if (policy === 'readOnly')
    assert(
      turn.items.some(
        (i) => i.type === 'mcpToolCall' && i.tool === 'list_projects' && i.status === 'completed',
      ),
      'Actual original desktop read tool',
    );
  const observation = {
    threadId,
    operationId,
    marker,
    turnId: turn.turnId,
    owner: true,
    actualPolicy: turn.params.sandboxPolicy,
    actualApproval: turn.params.approvalPolicy,
    model: 'gpt-6.1-sol',
    effort: 'xhigh',
    actualTools: turn.items.filter((i) =>
      ['functionCall', 'mcpToolCall', 'commandExecution', 'fileChange'].includes(i.type),
    ).length,
  };
  report.observations.push(observation);
  report.realModelTasks++;
  await save();
  return turn;
}
try {
  await witness.connect();
  report.desktopVersion = witness.version;
  await mkdir(join(root, 'project'), { mode: 0o700 });
  const metadata = new CodexRuntime({ mode: 'managed-runtime', executable });
  try {
    await metadata.connect();
    const value = await metadata.call('project/create', {
      name: 'UM02 native recovery ' + nonce,
      roots: [{ path: join(root, 'project') }],
      idempotencyKey: randomUUID(),
    });
    projectId = value.project.id;
  } finally {
    metadata.close();
  }
  report.projectId = projectId;
  await writeFile(
    join(state, 'bridge.json'),
    JSON.stringify({ hostId, desktopVersion: witness.version }),
    { mode: 0o600 },
  );
  await launch();
  const workspace = await request('/v1/workspace');
  target = workspace.readTarget;
  assert.equal(workspace.connectionMode, 'desktop-runtime');
  assert.equal(target.hostId, hostId);
  assert(
    workspace.capabilities.includes('desktop:creations') &&
      workspace.capabilities.includes('commands:target-bound:v1'),
  );
  const keyPath = join(root, 'key.pem'),
    certPath = join(root, 'certificate.pem');
  execFileSync(
    'openssl',
    [
      'req',
      '-x509',
      '-newkey',
      'rsa:2048',
      '-nodes',
      '-days',
      '1',
      '-subj',
      '/CN=localhost',
      '-addext',
      'subjectAltName=IP:127.0.0.1,DNS:localhost',
      '-keyout',
      keyPath,
      '-out',
      certPath,
    ],
    { stdio: 'ignore' },
  );
  await chmod(keyPath, 0o600);
  await chmod(certPath, 0o600);
  const cert = await readFile(certPath);
  const certFingerprint = sha(new X509Certificate(cert).raw);
  const reads = new Set([
    '/v1/workspace',
    '/v1/history',
    '/v1/models',
    '/v1/events',
    '/v1/receipts',
    '/v1/replies/status',
    '/v1/desktop/creation-receipts',
    '/v1/desktop/open-receipts',
  ]);
  const writes = new Set([
    '/v1/replies',
    '/v1/desktop/open',
    '/v1/desktop/creations',
    '/v1/desktop/creations/submit',
    '/v1/desktop/creations/detach',
  ]);
  proxy = httpsServer(
    { key: await readFile(keyPath), cert },
    (req, res) =>
      void (async () => {
        const generation = report.hostPids.length;
        try {
          const url = new URL(req.url, 'https://localhost');
          assert(!req.headers.origin);
          if (req.method === 'GET' && url.pathname === '/host') {
            res
              .writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
              .end(
                JSON.stringify({
                  version: 1,
                  agentId: 'codex',
                  name: 'UM02 isolated DEV',
                  target,
                  fingerprint: certFingerprint,
                  endpoints: ['https://127.0.0.1:' + proxyPort],
                  endpointHints: [
                    { endpoint: 'https://127.0.0.1:' + proxyPort, networkKind: 'lan' },
                  ],
                }),
              );
            return;
          }
          assert(
            req.headers.authorization === 'Bearer ' + token,
            'Isolated Host authorization required',
          );
          assert(
            req.method === 'GET'
              ? reads.has(url.pathname)
              : req.method === 'POST' && writes.has(url.pathname),
          );
          const chunks = [];
          let length = 0;
          for await (const chunk of req) {
            length += chunk.length;
            assert(length < 1_000_000);
            chunks.push(chunk);
          }
          const body = Buffer.concat(chunks);
          const log = {
            method: req.method,
            path: url.pathname,
            bodySha256: sha(body),
            generation: report.hostPids.length,
            forwarded: false,
          };
          report.requests.push(log);
          if (req.method === 'POST' && url.pathname === '/v1/desktop/creations') {
            const value = JSON.parse(body);
            if (firstIntent) assert.deepEqual(value, firstIntent);
            else firstIntent = value;
          }
          if (req.method === 'POST' && url.pathname === '/v1/replies') {
            const value = JSON.parse(body),
              capturedTarget = JSON.parse(url.searchParams.get('target'));
            if (!droppedReply) {
              droppedReply = value;
              droppedTarget = capturedTarget;
              log.controlledDropBeforeForward = true;
              await writeFile(
                join(root, 'captured-reply.json'),
                JSON.stringify({ reply: value, target: capturedTarget }),
                { mode: 0o600 },
              );
              await save();
              res.destroy();
              return;
            }
            assert(!retryForwarded, 'No second replay');
            assert.deepEqual(value, droppedReply);
            assert.deepEqual(capturedTarget, droppedTarget);
            retryForwarded = true;
          }
          log.forwarded = true;
          const response = await fetch(endpoint + url.pathname + url.search, {
            method: req.method,
            headers: {
              Authorization: 'Bearer ' + token,
              ...(req.method === 'POST' ? { 'Content-Type': 'application/json' } : {}),
            },
            ...(req.method === 'POST' ? { body } : {}),
            redirect: 'error',
            signal: AbortSignal.timeout(90_000),
          });
          const bytes = Buffer.from(await response.arrayBuffer());
          log.status = response.status;
          if (url.pathname === '/v1/replies/status' && response.ok) {
            const value = JSON.parse(bytes);
            log.inspectionStatus = value.status;
            log.fingerprint = value.fingerprint;
            log.evidenceKind = value.evidence?.kind;
            log.inspectionArtifact =
              'reply-inspection-' + generation + '-' + report.requests.length + '.json';
            await writeFile(join(root, log.inspectionArtifact), bytes, { mode: 0o600 });
          }
          res
            .writeHead(response.status, {
              'Content-Type': response.headers.get('content-type') ?? 'application/json',
            })
            .end(bytes);
        } catch (error) {
          if (
            req.method === 'GET' &&
            (restartingHost ||
              closing ||
              generation < report.hostPids.length ||
              req.aborted ||
              res.destroyed)
          )
            report.expectedReadInterruptions = (report.expectedReadInterruptions ?? 0) + 1;
          else report.proxyError = error.message;
          await save();
          if (!res.headersSent) res.writeHead(502);
          res.end('{"error":"Owned acceptance proxy unavailable"}');
        }
      })(),
  );
  await new Promise((done) => proxy.listen(0, '127.0.0.1', done));
  proxyPort = proxy.address().port;
  control = httpServer(
    (req, res) =>
      void (async () => {
        try {
          assert.equal(req.method, 'POST');
          assert(
            req.headers.authorization === 'Bearer ' + controlKey,
            'Isolated control authorization required',
          );
          assert(!req.headers.origin);
          const chunks = [];
          let size = 0;
          for await (const chunk of req) {
            size += chunk.length;
            assert(size < 8192);
            chunks.push(chunk);
          }
          const body = JSON.parse(Buffer.concat(chunks));
          assert.equal(body.nonce, nonce);
          const path = new URL(req.url, 'http://localhost').pathname;
          if (path === '/preflight') {
            const record = (await creations()).find(
              (v) => v.clientOperationId === body.operationId,
            );
            assert(record);
            const prepared = JSON.parse(
              await readFile(
                join(state, 'desktop-prepared-bridge', body.operationId + '.json'),
                'utf8',
              ),
            );
            assert.equal(prepared.phase, 'input-retryable');
            assert.equal(prepared.inputBoundary.state, 'not-written');
            assert.equal(prepared.diagnostic.disposition, 'retry-original-preflight');
            assert.throws(() => process.kill(prepared.pid, 0));
            await witness.ipc.follow(prepared.threadId, 15_000, true);
            assert(witness.ipc.hasOwner(prepared.threadId));
            assert.equal(desktopTurns(witness.ipc.state(prepared.threadId) ?? {}).length, 0);
            report.preflight = {
              operationId: body.operationId,
              threadId: prepared.threadId,
              originalOwnerTurns: 0,
              preparationExited: true,
              checkpoint: prepared.inputBoundary.checkpoint,
              diagnostic: prepared.diagnostic,
            };
          } else if (path === '/first-completed') {
            assert.equal(body.threadId, report.preflight.threadId);
            await observe(
              body.threadId,
              'first-' + body.operationId,
              'UM02_FIRST_' + nonce,
              'readOnly',
            );
            const prepared = JSON.parse(
              await readFile(
                join(state, 'desktop-prepared-bridge', body.operationId + '.json'),
                'utf8',
              ),
            );
            assert.equal(prepared.phase, 'submitted');
            assert.equal(prepared.exitCode, 0);
            assert.equal(
              report.requests.filter(
                (v) => v.path === '/v1/desktop/creations/submit' && v.method === 'POST',
              ).length,
              2,
            );
          } else if (path === '/fence-reply') {
            assert.equal(body.operationId, droppedReply.clientMessageId);
            assert.equal(body.threadId, droppedReply.threadId);
            await witness.ipc.follow(body.threadId, 15_000, true);
            assert(witness.ipc.hasOwner(body.threadId));
            const turns = desktopTurns(witness.ipc.state(body.threadId) ?? {});
            assert.equal(turns.length, 1);
            assert.equal(
              turns
                .flatMap((v) => v.items ?? [])
                .filter((v) => v.type === 'userMessage' && v.clientId === body.operationId).length,
              0,
            );
            const oldPid = host.pid;
            restartingHost = true;
            await terminate();
            assert.throws(() => process.kill(oldPid, 0));
            const ledgerBytes = await readFile(join(state, 'runtime.json')),
              ledger = JSON.parse(ledgerBytes).replyLedger;
            assert.equal(ledger.writerPid, oldPid);
            assert(
              !JSON.parse(ledgerBytes).receipts.some((v) => v.clientMessageId === body.operationId),
            );
            const closed = JSON.parse(
              await readFile(join(state, 'reply-writer-closed-v2.json'), 'utf8'),
            );
            assert.equal(closed.oldPid, oldPid);
            assert.equal(closed.ledgerSha256, sha(ledgerBytes));
            const operations = [
              {
                threadId: body.threadId,
                clientMessageId: body.operationId,
                fingerprint: replyIntentFingerprint(droppedReply, droppedTarget),
              },
            ];
            const auditPath = join(root, 'operator-boundary-audit.json');
            await writeFile(
              auditPath,
              JSON.stringify({
                kind: 'actual-closed-writer-non-forwarded-native-request-audit',
                oldPid,
                originalOwnerTurns: 1,
                exactReplyInputs: 0,
                exactReplyForwards: 0,
                closedFenceSha256: await fileSha(join(state, 'reply-writer-closed-v2.json')),
                requests: report.requests,
              }),
              { mode: 0o600, flag: 'wx' },
            );
            const sourceFiles = await Promise.all(
              [
                'packages/codex-adapter/dist/runtime-session.js',
                'packages/codex-adapter/dist/runtime-persistence.js',
                'scripts/mobile/android-reply-recovery-live.mjs',
              ].map(async (repositorySource) => {
                const bytes = await readFile(repositorySource),
                  file = join(root, 'audited-' + repositorySource.replaceAll('/', '__'));
                await writeFile(file, bytes, { mode: 0o600, flag: 'wx' });
                return { file, sha256: sha(bytes), repositorySource };
              }),
            );
            const fenceFile = join(root, 'operator-audited-fence.json');
            await writeFile(
              fenceFile,
              JSON.stringify({
                version: 2,
                kind: 'operator-audited-legacy-fence-v2',
                oldPid,
                target: droppedTarget,
                ledgerSha256: sha(ledgerBytes),
                writerNonce: ledger.writerNonce,
                ledgerId: ledger.ledgerId,
                boundary: 'receipt-before-model-write-no-deletion',
                singleWriter: true,
                operations,
                sourceFiles,
                logFiles: [
                  { file: auditPath, sha256: await fileSha(auditPath) },
                  {
                    file: join(state, 'reply-writer-closed-v2.json'),
                    sha256: await fileSha(join(state, 'reply-writer-closed-v2.json')),
                  },
                ],
              }),
              { mode: 0o600, flag: 'wx' },
            );
            const checkpointBase = join(root, 'checkpoint-base.json');
            await writeFile(
              checkpointBase,
              JSON.stringify({
                version: 2,
                kind: 'reply-ledger-fenced-handoff-v2',
                target: droppedTarget,
                oldPid,
                ledgerSha256: sha(ledgerBytes),
                fenceFile,
                fenceSha256: await fileSha(fenceFile),
                operations,
              }),
              { mode: 0o600, flag: 'wx' },
            );
            await launch(checkpointBase);
            const restored = await request('/v1/workspace');
            assert.deepEqual(restored.readTarget, target);
            restartingHost = false;
            report.handoff = {
              oldPid,
              newPid: host.pid,
              closedOriginalWriter: true,
              operatorAuditSha256: await fileSha(auditPath),
              ledgerSha256: sha(ledgerBytes),
              fenceSha256: await fileSha(fenceFile),
              operation: operations[0],
              scope:
                'Explicit operator audit of known non-forwarded native request; ordinary 404/restart never grants retry.',
            };
          } else if (path === '/reply-completed') {
            assert.equal(body.operationId, droppedReply.clientMessageId);
            assert(retryForwarded);
            const turn = await observe(
              body.threadId,
              body.operationId,
              'UM02_REPLY_' + nonce,
              'dangerFullAccess',
            );
            assert.equal(
              await readFile(join(root, 'owned-permission.txt'), 'utf8'),
              'UM02_FILE_' + nonce + '\n',
            );
            assert(
              turn.items.some(
                (v) =>
                  (v.type === 'fileChange' && v.status === 'completed') ||
                  (v.type === 'functionCallOutput' && JSON.stringify(v).includes('Success')),
              ),
              'Actual tool must write the owned outside-project file',
            );
            const receipt = await request(
              '/v1/receipts?' +
                new URLSearchParams({ hostId, threadId: body.threadId, id: body.operationId }),
            );
            assert.equal(receipt.clientMessageId, body.operationId);
            assert.equal(receipt.state, 'completed');
            assert.equal(desktopTurns(witness.ipc.state(body.threadId) ?? {}).length, 2);
            report.actualPermissionFileSha256 = await fileSha(join(root, 'owned-permission.txt'));
          } else throw Error('Unknown named acceptance control');
          await save();
          res.writeHead(200, { 'Content-Type': 'application/json' }).end('{"ok":true}');
        } catch (error) {
          report.controlError = String(error.stack);
          await save();
          res
            .writeHead(500, { 'Content-Type': 'application/json' })
            .end('{"error":"Original owner acceptance gate failed"}');
        }
      })(),
  );
  await new Promise((done) => control.listen(0, '127.0.0.1', done));
  controlPort = control.address().port;
  for (const port of [proxyPort, controlPort])
    await device('reverse', 'tcp:' + port, 'tcp:' + port);
  const app = '.build/native/android/outputs/apk/debug/android-debug.apk',
    test = '.build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk';
  report.apkSha256 = await fileSha(app);
  report.testApkSha256 = await fileSha(test);
  report.installations = [];
  for (const [packageName, apk] of [
    ['com.easyai.agenthub.android.debug', app],
    ['com.easyai.agenthub.android.debug.test', test],
  ])
    report.installations.push(
      await installAndroidApkIfNeeded({ adb, serial, packageName, apk, allowPhysical: physical }),
    );
  const nativeConfig = join(root, 'native-config.json');
  await writeFile(
    nativeConfig,
    JSON.stringify({
      nonce,
      hostId,
      projectId,
      model: 'gpt-6.1-sol',
      control: 'http://127.0.0.1:' + controlPort,
      controlKey,
      pairing:
        'codex-host://pair?descriptor=' +
        Buffer.from(
          JSON.stringify({
            version: 1,
            agentId: 'codex',
            name: 'UM02 isolated DEV',
            target,
            fingerprint: certFingerprint,
            endpoints: ['https://127.0.0.1:' + proxyPort],
            endpointHints: [{ endpoint: 'https://127.0.0.1:' + proxyPort, networkKind: 'lan' }],
            token,
          }),
        ).toString('base64url'),
      firstText:
        '专用恢复验收：调用 list_projects 只读工具，不修改文件或其他任务。最后只回复 UM02_FIRST_' +
        nonce +
        '。',
      replyText:
        '本轮专用权限接管验收：使用 apply_patch 工具仅新增文件 ' +
        join(root, 'owned-permission.txt') +
        '，内容为 UM02_FILE_' +
        nonce +
        '，末尾换行；不要修改其他文件或聊天。完成后只回复 UM02_REPLY_' +
        nonce +
        '。',
    }),
    { mode: 0o600 },
  );
  const temporary = '/data/local/tmp/um02-live-' + nonce + '.json';
  await device('push', nativeConfig, temporary);
  await device(
    'shell',
    'run-as',
    'com.easyai.agenthub.android.debug',
    'cp',
    temporary,
    'files/reply-recovery-live.json',
  );
  await device(
    'shell',
    'run-as',
    'com.easyai.agenthub.android.debug',
    'chmod',
    '600',
    'files/reply-recovery-live.json',
  );
  await device('shell', 'rm', temporary);
  if (physical) {
    await device('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP');
    const started = await device(
      'shell',
      'am',
      'start',
      '-W',
      '-n',
      'com.easyai.agenthub.android.debug/androidx.activity.ComponentActivity',
    );
    assert(started.includes('Status: ok'));
  }
  await save();
  console.log(JSON.stringify({ status: 'running', serial, root }));
  const output = await device(
    'shell',
    'am',
    'instrument',
    '-w',
    '-r',
    '-e',
    'class',
    'com.agenthub.android.MobileReplyRecoveryLiveTest',
    '-e',
    'recoveryForeground',
    physical ? '1' : '0',
    'com.easyai.agenthub.android.debug.test/androidx.test.runner.AndroidJUnitRunner',
  );
  await writeFile(
    join(root, 'instrumentation.log'),
    output.replaceAll(token, '[REDACTED]').replaceAll(controlKey, '[REDACTED]'),
    { mode: 0o600 },
  );
  assert(
    output.includes('OK (1 test)') &&
      !/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed/.test(output),
  );
  assert.equal(report.observations.length, 2);
  assert.equal(report.realModelTasks, 2);
  assert(!report.controlError && !report.proxyError);
  const posts = report.requests.filter((v) => v.method === 'POST' && v.path === '/v1/replies');
  assert.equal(posts.length, 2);
  assert.equal(posts.filter((v) => v.forwarded).length, 1);
  assert.equal(new Set(posts.map((v) => v.bodySha256)).size, 1);
  assert(
    report.requests.some(
      (v) =>
        v.path === '/v1/replies/status' &&
        v.inspectionStatus === 'not-accepted' &&
        v.evidenceKind === 'operator-fenced-checkpoint-v2',
    ),
  );
  assert.equal(await sourceFingerprint(), report.sourceSha256);
  assert.equal(await fileSha(app), report.apkSha256);
  assert.equal(await fileSha(test), report.testApkSha256);
  report.status = 'passed';
} catch (error) {
  report.status = 'failed';
  report.error = String(error.stack);
  process.exitCode = 1;
} finally {
  closing = true;
  try {
    await device(
      'pull',
      '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/UM02-live/' + nonce,
      join(root, 'screenshots'),
    );
    report.screenshots = await Promise.all(
      (await readdir(join(root, 'screenshots')))
        .filter((n) => n.endsWith('.png'))
        .sort()
        .map(async (name) => ({
          name,
          sha256: await fileSha(join(root, 'screenshots', name)),
          reviewed: false,
        })),
    );
  } catch (error) {
    report.captureError = error.message;
  }
  await device(
    'shell',
    'run-as',
    'com.easyai.agenthub.android.debug',
    'rm',
    '-f',
    'files/reply-recovery-live.json',
  ).catch(() => {});
  for (const port of [proxyPort, controlPort].filter(Boolean))
    await device('reverse', '--remove', 'tcp:' + port).catch(() => {});
  for (const server of [proxy, control].filter(Boolean)) {
    server.closeAllConnections();
    await new Promise((done) => server.close(done));
  }
  await terminate();
  witness.close();
  if (report.status === 'passed' || !droppedReply) await rm(join(root, 'key.pem'), { force: true });
  else report.pendingTlsKeyPreserved = true;
  report.configAfter = await fileSha(protectedConfig);
  report.processesAfter = processes();
  if (
    report.configAfter !== report.configBefore ||
    JSON.stringify(report.processesAfter) !== JSON.stringify(report.processesBefore)
  ) {
    report.status = 'failed';
    report.environmentPreservationFailed = true;
    process.exitCode = 1;
  }
  report.finishedAt = new Date().toISOString();
  await save();
  console.log(JSON.stringify({ status: report.status, serial, root, error: report.error }));
}
