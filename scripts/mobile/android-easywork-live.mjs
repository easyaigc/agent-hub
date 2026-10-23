import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { readFile, writeFile, mkdir, copyFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { promisify } from 'node:util';
import { installAndroidApkIfNeeded } from './android-install-if-needed.mjs';
import { easyWorkAcceptanceFingerprint } from './easywork-acceptance-fingerprint.mjs';

const run = promisify(execFile);
const serial = process.env.ANDROID_SERIAL;
assert(serial && (serial.startsWith('emulator-') || process.env.EASYWORK_ALLOW_PHYSICAL === '1'));
const path = process.env.EASYWORK_TEST_PATH ?? 'lan';
assert(['lan', 'relay'].includes(path));
const scenario = process.env.EASYWORK_TEST_SCENARIO ?? 'baseline';
const deviceGrant = process.env.EASYWORK_DEVICE_GRANT === '1';
assert(
  [
    'baseline',
    'receive',
    'resources',
    'observability',
    'observability-read',
    'questions',
    'controls',
    'plans',
    'recovery',
    'approvals',
    'history',
    'history-read',
    'device-grants',
    'device-security',
    'full',
  ].includes(scenario),
);
assert(!['full', 'device-security', 'device-grants'].includes(scenario) || deviceGrant);
if (['history-read', 'full'].includes(scenario)) {
  assert.match(process.env.EASYWORK_HISTORY_THREAD ?? '', /^[a-f0-9-]{36}$/);
  assert.match(process.env.EASYWORK_HISTORY_FIRST_MARKER ?? '', /^HISTORY_[a-f0-9]{8}_1$/);
}
const host = JSON.parse(await readFile(process.env.EASYWORK_HOST_CONFIG, 'utf8'));
assert.equal(host.identityScope, 'explicit-loopback-DEV-only');
const local = JSON.parse(await readFile('.local/mobile-toolchain.json', 'utf8'));
const adb = resolve(local.androidSdk, 'platform-tools/adb');
const call = (...args) =>
  run(adb, ['-s', serial, ...args], {
    timeout: scenario === 'full' ? 2400000 : 1200000,
    maxBuffer: 4 * 1024 * 1024,
  });
const output = resolve(process.env.EASYWORK_NATIVE_OUTPUT);
await mkdir(output, { recursive: true, mode: 0o700 });
assert.equal(
  host.hostToolSha256,
  createHash('sha256')
    .update(await readFile('scripts/mobile/easywork-live-host.mjs'))
    .digest('hex'),
  'Restart the owned witness after changing its source',
);
const build = await run(
  process.execPath,
  ['scripts/mobile/gradle.mjs', ':android:assembleDebug', ':android:assembleDebugAndroidTest'],
  { timeout: 600000, maxBuffer: 8 * 1024 * 1024 },
);
await writeFile(output + '/build.log', build.stdout + build.stderr);
const fingerprint = await easyWorkAcceptanceFingerprint({
  desktopRoot: host.desktopRoot ?? resolve('.local/android-development/a5/easywork-source'),
});
await writeFile(output + '/source-fingerprint.json', JSON.stringify(fingerprint, null, 2));
const apk = '.build/native/android/outputs/apk/debug/android-debug.apk';
const examiner =
  '.build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk';
const sha = async (file) =>
  createHash('sha256')
    .update(await readFile(file))
    .digest('hex');
const report = {
  phase: 'A5',
  path,
  scenario,
  serial,
  api: (await call('shell', 'getprop', 'ro.build.version.sdk')).stdout.trim(),
  apkSha256: await sha(apk),
  examinerSha256: await sha(examiner),
  status: 'running',
  productionSourceSha256: fingerprint.productionSha256,
  examinerSourceSha256: fingerprint.examinerSha256,
  authScope: deviceGrant
    ? 'EWDAP/1 anonymous Android claim, original Desktop DEV identity confirmation, scoped device credentials; public Desktop OIDC not covered'
    : 'existing loopback DEV mobile verifier (legacy iOS platform tag), real Android app; OIDC not covered',
};
await mkdir(output + '/artifacts', { recursive: true });
await copyFile(apk, output + '/artifacts/app.apk');
await copyFile(examiner, output + '/artifacts/examiner.apk');
await writeFile(output + '/report.json', JSON.stringify(report, null, 2));
const physicalLan = !serial.startsWith('emulator-') && path === 'lan';
const forwards = [host.lanPort, host.relayPort, host.observerPort].filter(
  (port) => !physicalLan || port !== host.lanPort,
);
report.networkScope = physicalLan
  ? 'physical private LAN TLS/WSS; no USB reverse for the LAN listener; Relay and witness remain isolated DEV'
  : 'isolated DEV ports through emulator/USB reverse; no public Relay or VPN topology claim';
const animations = [
  'window_animation_scale',
  'transition_animation_scale',
  'animator_duration_scale',
];
const original = new Map();
const permissionSuffix = ['approvals', 'full'].includes(scenario)
  ? randomUUID().slice(0, 8)
  : undefined;
let permissionFileHash;
try {
  report.installation = [];
  for (const [packageName, file] of [
    ['com.easyai.agenthub.android.debug', output + '/artifacts/app.apk'],
    ['com.easyai.agenthub.android.debug.test', output + '/artifacts/examiner.apk'],
  ])
    report.installation.push(
      await installAndroidApkIfNeeded({
        adb,
        serial,
        packageName,
        apk: file,
        allowPhysical: process.env.EASYWORK_ALLOW_PHYSICAL === '1',
      }),
    );
  if ((path === 'relay' && !deviceGrant) || scenario === 'recovery') {
    const previous = await fetch(`http://127.0.0.1:${host.observerPort}/evidence`).then((r) =>
      r.json(),
    );
    const restarted = await fetch(`http://127.0.0.1:${host.observerPort}/control`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-easywork-acceptance': host.authorization },
      body: JSON.stringify({ operation: 'host.restart' }),
      signal: AbortSignal.timeout(35000),
    });
    assert(restarted.ok && (await restarted.json()).accepted === true);
    const deadline = Date.now() + 120000;
    let ready = false;
    while (Date.now() < deadline) {
      const current = await fetch(`http://127.0.0.1:${host.observerPort}/evidence`).then((r) =>
        r.json(),
      );
      if (
        current.hostEpoch !== previous.hostEpoch &&
        current.status === 'ready' &&
        Date.parse(current.relayOffer?.expiresAt) > Date.now() + 60000
      ) {
        ready = true;
        break;
      }
      await new Promise((r) => setTimeout(r, 500));
    }
    assert(ready, 'The newly started isolated Host must publish a fresh Relay offer');
    report.relayPairingSetup =
      'fresh offer from an owned isolated Host restart; not recovery acceptance';
  }
  // The real Desktop renews its five-minute QR before each dedicated scenario.
  const refreshed = await fetch(`http://127.0.0.1:${host.observerPort}/control`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-easywork-acceptance': host.authorization },
    body: JSON.stringify({ operation: 'lan.enable' }),
    signal: AbortSignal.timeout(35000),
  });
  assert(
    refreshed.ok && (await refreshed.json()).accepted === true,
    'Desktop QR refresh must be confirmed',
  );
  if (deviceGrant) {
    const created = await fetch(`http://127.0.0.1:${host.observerPort}/control`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-easywork-acceptance': host.authorization },
      body: JSON.stringify({ operation: 'desktop.device-offer-create' }),
      signal: AbortSignal.timeout(35000),
    });
    assert(
      created.ok && (await created.json()).accepted === true,
      'The real Desktop must generate its device offer',
    );
  }
  await writeFile(
    output + '/desktop-before.json',
    await fetch(`http://127.0.0.1:${host.observerPort}/evidence`).then((r) => r.text()),
    { mode: 0o600 },
  );
  const foreignBindingId =
    scenario === 'device-security'
      ? JSON.parse(await readFile(output + '/desktop-before.json', 'utf8')).bindingStates.find(
          (binding) => binding.status === 'active' && binding.authorizationProtocol === 'EWDAP/1',
        )?.bindingId
      : undefined;
  assert(
    scenario !== 'device-security' || foreignBindingId,
    'An independent active binding is required',
  );
  for (const port of forwards) await call('reverse', `tcp:${port}`, `tcp:${port}`);
  if (physicalLan) await call('reverse', '--remove', `tcp:${host.lanPort}`).catch(() => {});
  for (const name of animations) {
    original.set(name, (await call('shell', 'settings', 'get', 'global', name)).stdout.trim());
    await call('shell', 'settings', 'put', 'global', name, '0');
  }
  if (Number(report.api) >= 37)
    await call(
      'shell',
      'pm',
      'grant',
      'com.easyai.agenthub.android.debug',
      'android.permission.ACCESS_LOCAL_NETWORK',
    );
  if (permissionSuffix) {
    report.fixturePreconditions = [];
    for (const decision of ['approve', 'deny', 'race']) {
      const fileName = `permission-${decision}-${permissionSuffix}.txt`;
      // Appending requires an existing file. Unique, empty owned fixtures prevent
      // the model's create-then-append recovery from contaminating approval proof.
      await writeFile(join(host.workspace, fileName), Buffer.alloc(0), { flag: 'wx', mode: 0o600 });
      const inspected = await run(
        join(
          host.desktopRoot,
          'build/plugins/file-operations-cli/bin',
          process.platform,
          process.arch,
          'read_file',
        ),
        [join(host.workspace, fileName)],
      );
      const hash = /^file_hash:([a-f0-9]{16})$/m.exec(inspected.stdout)?.[1];
      assert(hash, 'Actual file CLI must independently identify the empty fixture');
      assert(permissionFileHash === undefined || permissionFileHash === hash);
      permissionFileHash = hash;
      report.fixturePreconditions.push({ fileName, bytes: 0, fileHash: hash });
    }
  }
  if (scenario === 'full') {
    const result = await call(
      'shell',
      'am',
      'instrument',
      '-w',
      '-e',
      'class',
      'com.agenthub.android.MobileEasyWorkBoundaryTest,com.agenthub.android.MobileEasyWorkDPoPTest',
      'com.easyai.agenthub.android.debug.test/androidx.test.runner.AndroidJUnitRunner',
    );
    await writeFile(output + '/native-boundaries.log', result.stdout + result.stderr);
    assert(
      result.stdout.includes('OK (4 tests)'),
      'All four native boundary tests must pass without skips',
    );
    await call(
      'pull',
      '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/A5-boundary',
      output + '/boundary',
    );
    await call(
      'pull',
      '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/A5-dpop',
      output + '/dpop',
    );
    const crypto = await run(process.execPath, [
      'scripts/mobile/easywork-crypto-vector.mjs',
      'verify',
      output + '/boundary/node-interoperability.json',
    ]);
    const signing = await run(process.execPath, [
      'scripts/mobile/easywork-dpop-verify.mjs',
      output + '/dpop/public-proofs.json',
    ]);
    report.nativeAuxiliary = {
      passedTests: 4,
      skips: 0,
      independentCrypto: JSON.parse(crypto.stdout),
      independentSigning: JSON.parse(signing.stdout),
    };
  }
  await writeFile(
    output + '/input.json',
    JSON.stringify({
      observerPort: host.observerPort,
      relayPort: host.relayPort,
      authorization: host.authorization,
      scenario,
      testPath: path,
      requireMdnsDiscovery: physicalLan && ['observability-read', 'full'].includes(scenario),
      pairingPresentation:
        process.env.EASYWORK_PAIRING_PRESENTATION ?? (scenario === 'observability' ? 'code' : 'qr'),
      ...(scenario === 'observability-read'
        ? { observabilityThreadId: process.env.EASYWORK_OBSERVABILITY_THREAD }
        : {}),
      ...(deviceGrant ? { pairingProtocol: 'EWDAP/1' } : {}),
      ...(foreignBindingId ? { foreignBindingId } : {}),
      ...(permissionSuffix ? { permissionSuffix, permissionFileHash } : {}),
      ...(['history-read', 'full'].includes(scenario)
        ? {
            historyThreadId: process.env.EASYWORK_HISTORY_THREAD,
            historyFirstMarker: process.env.EASYWORK_HISTORY_FIRST_MARKER,
          }
        : {}),
    }),
  );
  await call('push', output + '/input.json', '/data/local/tmp/a5-live-input.json');
  await call(
    'shell',
    'rm',
    '-rf',
    `/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/A5-live-${path}`,
  );
  const result = await call(
    'shell',
    'am',
    'instrument',
    '-w',
    '-e',
    'easyWorkConfig',
    '/data/local/tmp/a5-live-input.json',
    '-e',
    'class',
    `com.agenthub.android.MobileEasyWorkLiveTest#${path === 'lan' ? 'realLanPairingCreateAndModel' : 'realRelayPairingCreateAndModel'}`,
    'com.easyai.agenthub.android.debug.test/androidx.test.runner.AndroidJUnitRunner',
  );
  await writeFile(output + '/instrumentation.log', result.stdout + result.stderr);
  let restored;
  if (
    ['recovery', 'device-grants', 'full'].includes(scenario) &&
    result.stdout.includes('OK (1 test)')
  ) {
    await call('shell', 'am', 'force-stop', 'com.easyai.agenthub.android.debug');
    if (scenario === 'device-grants') {
      const saved = await call(
        'shell',
        'cat',
        `/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/A5-live-${path}/restart-input.json`,
      );
      const expiry = Date.parse(JSON.parse(saved.stdout).accessExpiresAt);
      assert(
        Number.isFinite(expiry) && expiry - Date.now() <= 130000,
        'Real DEV grant must have the bounded two-minute lifetime',
      );
      const start = Date.now();
      while (Date.now() < expiry + 5000)
        await new Promise((resolve) =>
          setTimeout(resolve, Math.min(5000, expiry + 5000 - Date.now())),
        );
      report.expiryWait = {
        realServerAccessExpiryReached: true,
        elapsedMs: Date.now() - start,
        originalAppProcessStopped: true,
      };
    }
    restored = await call(
      'shell',
      'am',
      'instrument',
      '-w',
      '-e',
      'easyWorkConfig',
      '/data/local/tmp/a5-live-input.json',
      '-e',
      'class',
      'com.agenthub.android.MobileEasyWorkRestoreLiveTest',
      'com.easyai.agenthub.android.debug.test/androidx.test.runner.AndroidJUnitRunner',
    );
    await writeFile(output + '/restore-instrumentation.log', restored.stdout + restored.stderr);
  }
  const pulled = await call(
    'pull',
    `/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/A5-live-${path}`,
    output + '/native',
  ).catch((error) => ({ error: error.message }));
  assert(result.stdout.includes('OK (1 test)'), 'Actual native scenario did not pass');
  assert(!pulled.error, 'Passing native evidence must be retrievable');
  if (restored) {
    assert(
      restored.stdout.includes('OK (1 test)'),
      'The new native process must restore encrypted state and a real connection',
    );
    report.processRestart = 'actual am force-stop followed by a newly launched native examiner';
    report.restore = JSON.parse(await readFile(output + '/native/restore-proof.json', 'utf8'));
    assert.equal(report.restore.status, 'passed');
  }
  report.native = JSON.parse(await readFile(output + '/native/native-proof.json', 'utf8'));
  assert(report.native.modelUsageVerified && report.native.independentDesktopVerified);
  if (scenario === 'receive') {
    const original = await fetch(`http://127.0.0.1:${host.observerPort}/evidence`, {
      signal: AbortSignal.timeout(30000),
    }).then((response) => response.json());
    const threadId = report.native.threadId;
    const syncs = original.requests.filter(
      (entry) =>
        entry.request.method === 'thread/sync' &&
        entry.request.params.threadId === threadId &&
        entry.transportId === (path === 'lan' ? 'lan-wss' : 'relay-http'),
    );
    const reduced = syncs.filter(
      (entry) =>
        entry.response.ok &&
        entry.request.params.known.length > 0 &&
        entry.response.result.messageCount < entry.request.params.known.length,
    );
    assert(reduced.length > 0, 'Actual Desktop must omit unchanged known messages');
    const writes = original.requests.filter(
      (entry) =>
        entry.request.method === 'turn/start' &&
        entry.request.params.clientRequestId === report.native.operationId,
    );
    assert.equal(writes.length, 1, 'Refresh must not replay the original model input');
    const run = original.independentDesktop.executions.find(
      (entry) => entry.id === report.native.runId,
    );
    assert.equal(run.run.status, 'completed');
    assert(run.providerUsage.length > 0);
    report.independentReceive = {
      threadId,
      syncs: syncs.length,
      reducedMessageResponses: reduced.length,
      modelInputs: 1,
      refreshModelInputs: 0,
    };
  }
  if (['observability', 'observability-read'].includes(scenario)) {
    const threadId = report.native.scenarios.at(-1).proof.threadId;
    const original = await fetch(`http://127.0.0.1:${host.observerPort}/evidence`, {
      signal: AbortSignal.timeout(30000),
    }).then((response) => response.json());
    const snapshot = original.independentDesktop.snapshots.find((entry) => entry.id === threadId);
    assert(snapshot, 'Original Desktop must retain the examined compression conversation');
    const projections = snapshot.messages
      .flatMap((message) => message.content)
      .filter((part) => part.type === 'agent-debug')
      .flatMap((part) => part.contextCompressionSampling ?? [])
      .filter((entry) => ['created', 'regenerated', 'repaired'].includes(entry.projectionStatus));
    assert(
      projections.length > 0,
      'Native compression must match a real canonical runtime projection',
    );
    const runs = original.independentDesktop.executions.filter(
      (entry) => entry.run?.threadId === threadId,
    );
    assert.equal(runs.length, 3);
    assert(
      runs.every((entry) => entry.run.status === 'completed' && entry.providerUsage.length > 0),
    );
    if (scenario === 'observability') {
      const controls = original.controls
        .filter(
          (entry) => entry.accepted && entry.operation.startsWith('desktop.observability-policy-'),
        )
        .slice(-2);
      assert.deepEqual(
        controls.map((entry) => entry.operation),
        ['desktop.observability-policy-enable', 'desktop.observability-policy-restore'],
      );
    }
    report.independentCompaction = {
      threadId,
      completedRealRuns: 3,
      canonicalProjections: projections.length,
      diagnosticPolicyRestored: true,
    };
  }
  if (['approvals', 'full'].includes(scenario)) {
    report.independentFiles = [];
    for (const entry of report.native.scenarios.filter((entry) =>
      entry.scenario.startsWith('tool-permission-'),
    )) {
      const scene = entry.proof;
      assert(/^permission-(approve|deny|race)-[a-f0-9]{8}\.txt$/.test(scene.fileName));
      const file = join(host.workspace, scene.fileName);
      const bytes = await readFile(file).catch((error) => {
        if (error.code === 'ENOENT') return null;
        throw error;
      });
      if (scene.expectedLineCount === 0)
        assert.equal(bytes?.length, 0, 'Denied tool must leave the empty fixture unchanged');
      else {
        assert(bytes, 'Approved tool must append to its real fixture');
        assert.equal(
          bytes.toString('utf8'),
          scene.expectedLine + '\n',
          'Canonical approval must append one line only',
        );
      }
      report.independentFiles.push({
        fileName: scene.fileName,
        exists: bytes !== null,
        sha256: bytes ? createHash('sha256').update(bytes).digest('hex') : null,
      });
    }
  }
  if (['plans', 'full'].includes(scenario)) {
    const scene = report.native.scenarios.find((entry) =>
      entry.proof.fileName?.startsWith('plan-'),
    ).proof;
    assert.match(scene.fileName, /^plan-[a-f0-9]{8}\.txt$/);
    const bytes = await readFile(join(host.workspace, scene.fileName));
    assert.equal(
      bytes.toString('utf8').trim(),
      scene.expectedLine,
      'Implemented file must use the revised plan',
    );
    report.independentPlanFile = {
      fileName: scene.fileName,
      sha256: createHash('sha256').update(bytes).digest('hex'),
      revisedContentVerified: true,
    };
  }
  report.status = 'passed';
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  throw error;
} finally {
  if (scenario === 'observability') {
    try {
      const desktop = await fetch(`http://127.0.0.1:${host.observerPort}/evidence`, {
        signal: AbortSignal.timeout(30000),
      }).then((response) => response.json());
      const last = desktop.controls
        .filter(
          (entry) => entry.accepted && entry.operation.startsWith('desktop.observability-policy-'),
        )
        .at(-1);
      if (last?.operation === 'desktop.observability-policy-enable') {
        const response = await fetch(`http://127.0.0.1:${host.observerPort}/control`, {
          method: 'POST',
          headers: {
            'content-type': 'application/json',
            'x-easywork-acceptance': host.authorization,
          },
          body: JSON.stringify({ operation: 'desktop.observability-policy-restore' }),
          signal: AbortSignal.timeout(40000),
        });
        assert.equal(response.status, 200);
        assert((await response.json()).accepted);
      }
      report.diagnosticPolicyRestored = true;
    } catch (error) {
      report.diagnosticPolicyRestoreError = error.message;
      report.status = 'failed';
      process.exitCode = 1;
    }
  }
  await fetch(`http://127.0.0.1:${host.observerPort}/evidence`)
    .then((r) => r.text())
    .then((body) => writeFile(output + '/desktop-after.json', body, { mode: 0o600 }))
    .catch((error) => {
      report.desktopEvidenceError = error.message;
    });
  for (const [name, value] of original)
    await call('shell', 'settings', 'put', 'global', name, value);
  for (const port of forwards) await call('reverse', '--remove', `tcp:${port}`);
  await call('shell', 'rm', '-f', '/data/local/tmp/a5-live-input.json');
  await writeFile(output + '/report.json', JSON.stringify(report, null, 2) + '\n');
  console.log(
    JSON.stringify({ status: report.status, output, path, serial, apkSha256: report.apkSha256 }),
  );
}
