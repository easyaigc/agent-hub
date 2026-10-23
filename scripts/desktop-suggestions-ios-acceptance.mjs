import assert from 'node:assert/strict';
import { spawn, execFileSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { createWriteStream } from 'node:fs';
import { cp, mkdir, readFile, readdir, symlink, writeFile } from 'node:fs/promises';
import { createServer, request } from 'node:http';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';
import { createInterface } from 'node:readline';
import { setTimeout as delay } from 'node:timers/promises';
import { pathToFileURL } from 'node:url';

// Existing real predictions only. An explicit adoption run exercises local
// drafts through the ordinary App; model/input commands remain blocked.
assert(process.env.SUGGESTION_ACCEPTANCE_DIRECTORY, 'Existing private binding evidence required');
assert(process.env.SUGGESTION_IOS_DEVICE_ID, 'Explicit paired physical device required');
const evidence = resolve(process.env.SUGGESTION_ACCEPTANCE_DIRECTORY);
const device = process.env.SUGGESTION_IOS_DEVICE_ID;
const verifyAdoption = process.env.SUGGESTION_VERIFY_ADOPTION === '1';
const existingConnection = process.env.SUGGESTION_EXISTING_CONNECTION === '1';
const explicitNonce = process.env.SUGGESTION_ACCEPTANCE_NONCE;
if (explicitNonce) assert(/^[a-f0-9]{8,32}$/.test(explicitNonce));
const proof = JSON.parse(await readFile(join(evidence, 'binding-result.json'), 'utf8'));
const expected = JSON.parse(await readFile(join(evidence, 'expected-sources.json'), 'utf8'));
if (!verifyAdoption) assert.equal(proof.concurrentBindingVerified, true);
assert.equal(proof.exactSourceBindingsVerified, true);
assert.equal(proof.actualSourceBindings.length, 2);
const directory = join(evidence, 'phone', 'attempt-' + randomUUID().slice(0, 8));
await mkdir(directory, { recursive: true, mode: 0o700 });
const report = {
  status: 'running',
  startedAt: new Date().toISOString(),
  device,
  readOnly: true,
  modelInputsSubmitted: 0,
  blockedCommands: [],
  suggestionResponses: [],
  nativeMatches: [],
  localDraftAdoption: verifyAdoption,
  existingConnection,
  commandBlockingProxyUsed: !existingConnection,
  nativeAdoptionChecks: [],
  ownerBefore: {},
  ownerAfter: {},
};
const save = () =>
  writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
await save();
await writeFile(join(evidence, 'phone', 'latest-attempt.json'), JSON.stringify({ directory }), {
  mode: 0o600,
});
const frozen = join(directory, 'frozen');
const native = join(directory, 'native');
await mkdir(frozen, { recursive: true });
await cp('packages', join(frozen, 'packages'), { recursive: true, verbatimSymlinks: true });
await cp('apps/host-agent', join(frozen, 'apps/host-agent'), {
  recursive: true,
  verbatimSymlinks: true,
});
await cp('scripts', join(frozen, 'scripts'), { recursive: true, verbatimSymlinks: true });
await cp('apps/ios', native, { recursive: true });
await symlink(resolve('node_modules'), join(frozen, 'node_modules'), 'dir');
const digestFiles = async (root, files) => {
  const digest = createHash('sha256');
  for (const file of files.sort()) digest.update(file).update(await readFile(join(root, file)));
  return digest.digest('hex');
};
report.adapterSha256 = await digestFiles(
  join(frozen, 'packages/codex-adapter/dist'),
  (await readdir(join(frozen, 'packages/codex-adapter/dist'))).filter((v) => v.endsWith('.js')),
);
report.hostSha256 = await digestFiles(
  join(frozen, 'apps/host-agent/dist'),
  (await readdir(join(frozen, 'apps/host-agent/dist'))).filter((v) => v.endsWith('.js')),
);
for (const [source, expectedHash] of [
  ['packages/codex-adapter/dist', report.adapterSha256],
  ['apps/host-agent/dist', report.hostSha256],
]) {
  assert.equal(
    await digestFiles(
      source,
      (await readdir(source)).filter((v) => v.endsWith('.js')),
    ),
    expectedHash,
    'The frozen runtime must match the current build; preserve a mismatched attempt as failed',
  );
}
report.nativeSha256 = await digestFiles(native, [
  'CodexManage.xcodeproj/project.pbxproj',
  'Tests/CodexManageUITests.swift',
  'Resources/en.lproj/Localizable.strings',
  'Resources/zh-Hans.lproj/Localizable.strings',
  ...(await readdir(join(native, 'Sources')))
    .filter((v) => v.endsWith('.swift'))
    .map((v) => 'Sources/' + v),
]);
const env = { ...process.env, DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer' };
const tailscale = '/Applications/Tailscale.app/Contents/MacOS/Tailscale';
const run = (command, args) =>
  execFileSync(command, args, { encoding: 'utf8', env, stdio: ['ignore', 'pipe', 'pipe'] });
const { startRuntimeServer } = await import(
  pathToFileURL(join(frozen, 'apps/host-agent/dist/runtime-server.js'))
);
const { DesktopIpc, desktopTurns } = await import(
  pathToFileURL(join(frozen, 'packages/codex-adapter/dist/desktop-ipc.js'))
);
const witness = new DesktopIpc(join(homedir(), '.codex/ipc/ipc.sock'));
const state = resolve(process.env.SUGGESTION_HOST_STATE_DIRECTORY ?? join(evidence, 'host-state'));
const traceRoot = resolve(process.env.SUGGESTION_TRACE_ROOT ?? join(evidence, 'traces'));
Object.assign(process.env, {
  CODEX_RUNTIME_MODE: 'desktop-runtime',
  CODEX_EXECUTABLE:
    '/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex',
  HOST_AGENT_STATE_DIR: state,
  HOST_AGENT_ROOT: resolve('.'),
  HOST_AGENT_NAME: 'Suggestion read-only DEV',
  HOST_AGENT_BIND: '127.0.0.1',
  HOST_AGENT_PORT: '0',
  HOST_AGENT_DISCOVERY: '0',
  HOST_AGENT_DISCOVERY_BONJOUR: '0',
  CODEX_DESKTOP_SUGGESTION_OBSERVER: '1',
  CODEX_DESKTOP_SUGGESTION_TRACE_ROOT: traceRoot,
});
for (const key of [
  'HOST_AGENT_TOKEN',
  'HOST_NOTIFICATION_RELAY_URL',
  'HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE',
  'CODEX_DESKTOP_CONTROLLER_CONFIG',
  'CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG',
  'CODEX_DESKTOP_CREATION_HELPER',
  'CODEX_DESKTOP_CREATION_PROFILE',
  'CODEX_DESKTOP_CREATION_VERIFICATION',
])
  delete process.env[key];
let host,
  proxy,
  child,
  servePort,
  token,
  phase = 'preflight';
try {
  let target, address;
  if (existingConnection) {
    const registry = JSON.parse(
      await readFile(join(homedir(), '.config/agent-hub/hosts.json'), 'utf8'),
    );
    const own = registry.hosts.filter((entry) => entry.hostId === proof.target.hostId);
    assert.equal(own.length, 1);
    assert.equal(own[0].connectionMode, 'desktop-runtime');
    target = proof.target;
    address = own[0].endpoint;
    assert(address.startsWith('https://'));
    const deployed = resolve(process.env.SUGGESTION_DEPLOYED_PROGRAM_ROOT ?? '');
    assert(process.env.SUGGESTION_DEPLOYED_PROGRAM_ROOT, 'Verify the actual deployed program');
    for (const [part, hash] of [
      ['packages/codex-adapter/dist', report.adapterSha256],
      ['apps/host-agent/dist', report.hostSha256],
    ])
      assert.equal(
        await digestFiles(
          join(deployed, part),
          (await readdir(join(deployed, part))).filter((v) => v.endsWith('.js')),
        ),
        hash,
      );
    report.deployedRuntimeMatchesFrozen = true;
  } else {
    host = await startRuntimeServer();
    assert.equal(
      host.session.hostId,
      proof.target.hostId,
      'Never transplant observations to a different Host',
    );
    target = host.session.readingTarget();
    address = 'http://127.0.0.1:' + host.server.address().port;
  }
  token = (await readFile(join(state, 'access-token'), 'utf8')).trim();
  const headers = { Authorization: 'Bearer ' + token };
  for (let attempt = 0; attempt < 60; attempt++) {
    const response = await fetch(address + '/healthz', { headers });
    const health = await response.json();
    if (JSON.stringify(health).includes('desktop:suggestions:read:v1')) break;
    if (attempt === 59) throw Error('Bound suggestion capability did not become ready');
    await delay(500);
  }
  if (host) await host.session.workspace();
  await witness.connect();
  for (const row of proof.actualSourceBindings) {
    const { binding, observation } = row;
    assert(
      Date.parse(observation.expiresAt) > Date.now() + 300000,
      'Predictions must remain valid for the native test; never extend their lifetime',
    );
    const snapshot = await witness.follow(binding.sourceThreadId, 5000, true, true);
    const turns = desktopTurns(snapshot);
    assert.equal(turns.length, 1);
    assert.equal(turns[0].id ?? turns[0].turnId, binding.sourceTurnId);
    assert.equal(turns[0].status, 'completed');
    report.ownerBefore[binding.sourceThreadId] = {
      turns: turns.length,
      turnId: binding.sourceTurnId,
    };
    const route =
      '/v1/threads/next-turn-suggestion?' +
      new URLSearchParams({
        hostId: target.hostId,
        target: JSON.stringify(target),
        threadId: binding.sourceThreadId,
        sourceTurnId: binding.sourceTurnId,
      });
    const response = await fetch(address + route, { headers });
    assert.equal(response.status, 200);
    const value = await response.json();
    assert.equal(value.suggestion?.text, observation.text);
    assert.equal(value.sourceTurnId, binding.sourceTurnId);
    const history = host
      ? await host.session.open(binding.sourceThreadId, undefined, target)
      : await (
          await fetch(
            address +
              '/v1/history?' +
              new URLSearchParams({
                hostId: target.hostId,
                threadId: binding.sourceThreadId,
                target: JSON.stringify(target),
              }),
            { headers, redirect: 'error' },
          )
        ).json();
    assert.equal(history.runtime.suggestionSourceTurnId, binding.sourceTurnId);
  }
  let endpoint = address;
  if (!existingConnection) {
    const serveBefore = JSON.parse(run(tailscale, ['serve', 'status', '--json']));
    servePort = String(process.env.SUGGESTION_HTTPS_PORT ?? '8457');
    assert(/^[0-9]{4,5}$/.test(servePort));
    assert(!serveBefore.TCP?.[servePort], 'Only an unused HTTPS port may be used');
    const dnsName = Object.keys(serveBefore.Web ?? {})[0]?.split(':')[0];
    assert(dnsName?.endsWith('.ts.net'), 'Use the existing own-host Tailscale domain');
    endpoint = 'https://' + dnsName + ':' + servePort;
    const allowedPosts = new Set([
      '/v1/threads/read',
      '/v1/notifications/register',
      '/v1/notifications/preferences',
      '/v1/notifications/unregister',
    ]);
    proxy = createServer((req, res) => {
      const parsed = new URL(req.url, 'http://localhost');
      if (!['GET', 'HEAD', 'OPTIONS'].includes(req.method) && !allowedPosts.has(parsed.pathname)) {
        report.blockedCommands.push({ method: req.method, path: parsed.pathname });
        res.writeHead(405).end();
        return;
      }
      const upstream = request(
        address + req.url,
        { method: req.method, headers: req.headers },
        (response) => {
          if (parsed.pathname === '/v1/threads/next-turn-suggestion') {
            const chunks = [];
            response.on('data', (chunk) => chunks.push(chunk));
            response.once('end', () => {
              try {
                const bytes = Buffer.concat(chunks),
                  value = JSON.parse(bytes.toString('utf8'));
                if (phase === 'native')
                  report.suggestionResponses.push({
                    threadId: parsed.searchParams.get('threadId'),
                    sourceTurnId: parsed.searchParams.get('sourceTurnId'),
                    status: response.statusCode,
                    target: value.target,
                    contentSha256: value.suggestion?.contentSha256 ?? null,
                  });
                res.writeHead(response.statusCode, response.headers).end(bytes);
              } catch {
                res.writeHead(502).end();
              }
            });
          } else {
            res.writeHead(response.statusCode, response.headers);
            response.pipe(res);
          }
        },
      );
      upstream.once('error', () => {
        if (!res.headersSent) res.writeHead(502).end();
        else res.destroy();
      });
      res.once('close', () => upstream.destroy());
      req.pipe(upstream);
    });
    await new Promise((done) => proxy.listen(0, '127.0.0.1', done));
    run(tailscale, [
      'serve',
      '--bg',
      '--https=' + servePort,
      'http://127.0.0.1:' + proxy.address().port,
    ]);
    report.temporaryHttpsPort = servePort;
    const httpsCheck = await fetch(endpoint + '/v1/workspace?hostId=' + target.hostId, {
      headers,
      signal: AbortSignal.timeout(15000),
    });
    assert.equal(httpsCheck.status, 200);
    assert.equal((await httpsCheck.json()).hostId, target.hostId);
  }
  const fixture = {
    endpoint,
    token: existingConnection ? '' : token,
    existingConnection,
    hostId: target.hostId,
    nonce: explicitNonce ?? randomUUID().slice(0, 8),
    adoptionVerification: verifyAdoption,
  };
  for (const [index, row] of proof.actualSourceBindings.entries()) {
    const letter = index === 0 ? 'A' : 'B',
      b = row.binding;
    fixture['thread' + letter + 'Id'] = b.sourceThreadId;
    fixture['thread' + letter + 'Title'] = expected.find(
      (v) => v.threadId === b.sourceThreadId,
    ).title;
    fixture['source' + letter + 'TurnId'] = b.sourceTurnId;
    fixture['text' + letter] = row.observation.text;
  }
  if (process.env.SUGGESTION_ORIGINAL_CLIENT_ID)
    fixture.originalClientId = process.env.SUGGESTION_ORIGINAL_CLIENT_ID;
  // A failed physical run can leave its own adopted draft. Recovery is opt-in,
  // tied to that exact failed run and source, and still checks full bytes in UI.
  const recoveryAttempt = process.env.SUGGESTION_RECOVER_ADOPTED_DRAFT_FROM;
  if (recoveryAttempt) {
    assert(verifyAdoption && /^attempt-[a-f0-9]{8}$/.test(recoveryAttempt));
    const priorDirectory = join(evidence, 'phone', recoveryAttempt);
    const priorReport = JSON.parse(await readFile(join(priorDirectory, 'report.json'), 'utf8'));
    const priorFixture = JSON.parse(
      await readFile(join(priorDirectory, 'runner-fixture.json'), 'utf8'),
    );
    const priorLog = await readFile(join(priorDirectory, 'test-without-building.log'), 'utf8');
    assert.equal(priorReport.status, 'failed');
    assert.equal(priorReport.localDraftAdoption, true);
    for (const field of ['hostId', 'threadAId', 'sourceATurnId', 'textA', 'nonce'])
      assert.equal(priorFixture[field], fixture[field]);
    assert(priorLog.includes('SUGGESTION_DEV_CENTER_TAP suggestion-adopt-0'));
    if (priorLog.includes('SUGGESTION_DEV_UI_FAILURE suggestion-undo-not-ready')) {
      fixture.recoverFirstDraftText = fixture.textA;
    } else {
      assert.equal(priorReport.nativeAdoptionChecks.length, 2);
      assert.equal(priorReport.nativeMatches.length, 3);
      assert(priorLog.includes('SUGGESTION_DEV_CENTER_TAP suggestion-adopt-2'));
      assert(priorLog.includes("Type ' PHONE_EDIT'"));
      assert(priorLog.includes('SUGGESTION_DEV_CENTER_TAP suggestion-draft-menu'));
      fixture.recoverFirstDraftText = fixture.textA + ' PHONE_EDIT';
    }
    report.recoveredDraftFromAttempt = recoveryAttempt;
  }
  await writeFile(join(directory, 'runner-fixture.json'), JSON.stringify(fixture), { mode: 0o600 });
  report.resultBundle = join(directory, 'result.xcresult');
  await save();
  console.log(
    JSON.stringify({ phase: 'real-phone-ready', directory, device, hostId: target.hostId }),
  );
  const xcode = async (action) => {
    child = spawn(
      '/Applications/Xcode.app/Contents/Developer/usr/bin/xcodebuild',
      [
        '-project',
        join(native, 'CodexManage.xcodeproj'),
        '-scheme',
        'CodexManage',
        '-destination',
        'platform=iOS,id=' + device,
        '-derivedDataPath',
        join(directory, 'build'),
        ...(action === 'test-without-building' ? ['-resultBundlePath', report.resultBundle] : []),
        '-parallel-testing-enabled',
        'NO',
        '-collect-test-diagnostics',
        'never',
        '-only-testing:CodexManageUITests/CodexManageUITests/testOriginalDesktopReadOnlySuggestionsDEV',
        'ONLY_ACTIVE_ARCH=YES',
        'CURRENT_PROJECT_VERSION=20',
        '-allowProvisioningUpdates',
        action,
      ],
      {
        env: { ...env, TEST_RUNNER_SUGGESTION_DEV_FIXTURE_JSON: JSON.stringify(fixture) },
        stdio: ['ignore', 'pipe', 'pipe'],
      },
    );
    const log = createWriteStream(join(directory, action + '.log'), { mode: 0o600 });
    for (const stream of [child.stdout, child.stderr])
      createInterface({ input: stream }).on('line', (line) => {
        const safe = line
          .replaceAll(token, '[REDACTED]')
          .replace(
            /Type '.*' into "client-token" SecureTextField/,
            'Type [REDACTED] into "client-token" SecureTextField',
          );
        log.write(safe + '\n');
        if (safe.includes('SUGGESTION_DEV_MATCH')) report.nativeMatches.push(safe.trim());
        if (safe.includes('SUGGESTION_DEV_ADOPTION_PASS'))
          report.nativeAdoptionChecks.push(safe.trim());
        if (safe.includes('SUGGESTION_DEV_ORIGINAL_CLIENT'))
          report.originalClientId = safe.trim().split(/\s+/).at(-1);
        if (safe.includes('SUGGESTION_DEV_CLEANUP')) report.originalClientRestored = true;
        if (safe.includes('SUGGESTION_DEV_EXISTING_CONNECTION_PRESERVED')) {
          report.originalClientRestored = true;
          report.allOriginalConnectionsPreserved = true;
        }
        if (/^Test Case|: error:|\*\* TEST|SUGGESTION_DEV_/.test(safe)) console.log(safe);
      });
    const code = await new Promise((done, reject) => {
      child.once('error', reject);
      child.once('close', done);
    });
    await new Promise((done) => log.end(done));
    return code;
  };
  report.buildExitCode = await xcode('build-for-testing');
  assert.equal(report.buildExitCode, 0, 'Keep failed build evidence');
  const builtApp = join(directory, 'build/Build/Products/Debug-iphoneos/CodexManage.app');
  assert.equal(
    run('/usr/libexec/PlistBuddy', [
      '-c',
      'Print CFBundleIdentifier',
      join(builtApp, 'Info.plist'),
    ]).trim(),
    'com.easyai.agenthub',
  );
  report.installedExecutableSha256 = createHash('sha256')
    .update(await readFile(join(builtApp, 'CodexManage')))
    .digest('hex');
  run('xcrun', [
    'devicectl',
    'device',
    'install',
    'app',
    '--device',
    device,
    '--json-output',
    join(directory, 'install.json'),
    builtApp,
  ]);
  report.appInstalled = true;
  await save();
  phase = 'native';
  report.xcodeExitCode = await xcode('test-without-building');
  assert.equal(report.xcodeExitCode, 0, 'Keep failed native result, do not label it passed');
  assert.equal(report.nativeMatches.length, 4);
  if (verifyAdoption) assert.equal(report.nativeAdoptionChecks.length, 4);
  assert.equal(report.blockedCommands.length, 0, 'No model/input command may be attempted');
  assert.equal(report.originalClientRestored, true);
  for (const row of proof.actualSourceBindings) {
    const b = row.binding;
    const snapshot = await witness.follow(b.sourceThreadId, 5000, true, true),
      turns = desktopTurns(snapshot);
    assert.equal(turns.length, 1);
    assert.equal(turns[0].id ?? turns[0].turnId, b.sourceTurnId);
    assert.equal(turns[0].status, 'completed');
    report.ownerAfter[b.sourceThreadId] = { turns: turns.length, turnId: b.sourceTurnId };
    if (!existingConnection)
      assert(
        report.suggestionResponses.some(
          (v) =>
            v.threadId === b.sourceThreadId &&
            v.sourceTurnId === b.sourceTurnId &&
            v.status === 200 &&
            v.contentSha256 === row.textSha256,
        ),
      );
  }
  report.status = 'passed';
  report.physicalNativeVerified = true;
} catch (error) {
  report.status = 'failed';
  report.error = String(error.message).replaceAll(token ?? '__no_token__', '[REDACTED]');
  console.error(report.error);
  process.exitCode = 1;
} finally {
  witness.close();
  host?.close();
  proxy?.closeAllConnections();
  proxy?.close();
  if (report.temporaryHttpsPort) {
    try {
      run(tailscale, ['serve', '--https=' + servePort, 'off']);
      report.temporaryHttpsRemoved = true;
    } catch {
      report.temporaryHttpsRemoved = false;
    }
  }
  report.finishedAt = new Date().toISOString();
  await save();
  console.log(
    JSON.stringify({
      directory,
      status: report.status,
      physicalNativeVerified: report.physicalNativeVerified ?? false,
      temporaryHttpsRemoved: report.temporaryHttpsRemoved ?? null,
    }),
  );
}
