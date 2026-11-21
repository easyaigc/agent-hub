import assert from 'node:assert/strict';
import { execFileSync, fork, spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { createHash, randomUUID } from 'node:crypto';
import { createWriteStream } from 'node:fs';
import { cp, mkdir, readFile, writeFile, statfs, readdir, unlink } from 'node:fs/promises';
import { basename, dirname, join, resolve } from 'node:path';
import { homedir } from 'node:os';
import { setTimeout as delay } from 'node:timers/promises';
import { DesktopIpc, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { DesktopThreadPreparer } from '../packages/codex-adapter/dist/desktop-thread-preparer.js';
import { openOriginalDesktopThread } from '../packages/codex-adapter/dist/desktop-prepared-bridge.js';

// Real original-owner execution, a separate Host process, and native XCTest.
// Only the test follower's projection omits the old turn; the witness is unmodified.
const device = process.env.IOS_SIMULATOR_ID;
const executable = process.env.CODEX_EXECUTABLE;
assert(device && executable, 'Require a task-owned simulator and the installed official CLI');
const emptyRecovery = process.env.RECEIPT_RECOVERY_EMPTY;
assert(!(emptyRecovery && process.env.RECEIPT_RECOVERY_RESUME));
const resumed = process.env.RECEIPT_RECOVERY_RESUME ?? emptyRecovery;
const directory = resumed
  ? resolve(resumed)
  : resolve('.local/accepted-receipts-recovery-20261120/live-' + randomUUID().slice(0, 8));
await mkdir(directory, { recursive: true, mode: 0o700 });
const reportFile = join(directory, 'report.json');
const prior = resumed ? JSON.parse(await readFile(reportFile, 'utf8')) : null;
const candidatePath = process.env.RECEIPT_RECOVERY_CANDIDATE;
const candidate = candidatePath ? JSON.parse(await readFile(resolve(candidatePath), 'utf8')) : null;
if (candidate) {
  assert(
    prior &&
      !emptyRecovery &&
      prior.phase === 'native-ready' &&
      basename(directory) === 'live-a2773738' &&
      prior.operations?.length === 9 &&
      prior.newTurnId,
    'Candidate is restricted to the original native-only recovery',
  );
  assert.deepEqual(
    prior.sources,
    candidate.previousSources,
    'Candidate must replace the recorded original source',
  );
  assert(!globalThis.__acceptedReceiptsFrozenBuild, 'Candidate must not use the old frozen loader');
}
const reuseBuild = process.env.RECEIPT_RECOVERY_REUSE_BUILD;
assert(!(reuseBuild && resumed), 'Native-only resume and a fresh repeated case are distinct');
const reused = reuseBuild
  ? JSON.parse(await readFile(join(resolve(reuseBuild), 'report.json'), 'utf8'))
  : null;
const precheckPath = process.env.RECEIPT_RECOVERY_PRECHECK;
const precheck = precheckPath ? JSON.parse(await readFile(resolve(precheckPath), 'utf8')) : null;
if (precheck) {
  assert.equal(precheck.passed, true);
  const secondProof = JSON.parse(
    await readFile(join(dirname(resolve(precheckPath)), 'owner-verification.json'), 'utf8'),
  );
  assert.equal(secondProof.threadId, precheck.threadId);
  assert(
    secondProof.matchesOriginal &&
      secondProof.uniqueIds === 9 &&
      secondProof.replayedInputs === 0 &&
      secondProof.turns.length === 2 &&
      secondProof.turns.every((turn) => turn.status === 'completed'),
  );
}
if (reused) {
  const proof = JSON.parse(
    await readFile(join(resolve(reuseBuild), 'owner-verification.json'), 'utf8'),
  );
  assert.equal(proof.threadId, reused.threadId);
  assert.equal(proof.uniqueIds, 9);
  assert.equal(proof.matchesOriginal, true);
  assert.equal(proof.replayedInputs, 0);
  assert(
    proof.turns.length === 2 && proof.turns.every((turn) => turn.status === 'completed'),
    'Reuse only after prior operations are independently settled',
  );
}
const buildRoot = reuseBuild ? resolve(reuseBuild) : (prior?.reusedBuildFrom ?? directory);
if (prior) {
  if (emptyRecovery) {
    assert(
      prior.threadId &&
        prior.preparerExit === 0 &&
        prior.operations?.length === 0 &&
        !prior.oldTurnId &&
        !prior.newTurnId,
      'Empty recovery requires a prepared thread and no attempted submissions',
    );
    assert.deepEqual(
      JSON.parse(await readFile(join(directory, 'host/task-receipts.json'), 'utf8')),
      [],
    );
  } else
    assert(
      prior.phase === 'native-ready' && prior.newTurnId && prior.operations?.length === 9,
      'Resume only the known native phase; never replay unknown setup',
    );
}
const nonce = prior?.nonce ?? reused?.nonce ?? randomUUID().slice(0, 8);
const attempt = randomUUID().slice(0, 8);
const report = {
  ...prior,
  nonce,
  device,
  directory,
  status: 'preparing',
  kind: 'real-desktop-native-accepted-receipts',
  fault:
    'Only the isolated follower socket is disconnected; its restarted projection omits the exact old turn. Original owner/witness unchanged.',
  unknownControl:
    'Synthetic presentation-only receipt; no corresponding model input or ledger entry.',
  operations: prior?.operations ?? [],
  attempts: prior?.attempts ?? [],
  ...(reused
    ? {
        reusedBuildFrom: buildRoot,
        priorCompletedThreadId: reused.threadId,
        priorCompletedInputs: 9,
        ...(precheck
          ? {
              priorCompletedInputsTotal: 18,
              projectionPrecheck: resolve(precheckPath),
              secondCompletedThreadId: precheck.threadId,
            }
          : {}),
        snapshotSHA256: reused.snapshotSHA256,
      }
    : {}),
};
const save = () => writeFile(reportFile, JSON.stringify(report, null, 2), { mode: 0o600 });
if (prior)
  await writeFile(
    join(directory, 'before-resume-' + attempt + '.json'),
    JSON.stringify(prior, null, 2),
    { mode: 0o600 },
  );
const hashFile = async (path) =>
  createHash('sha256')
    .update(await readFile(path))
    .digest('hex');
report.sources = Object.fromEntries(
  await Promise.all(
    [
      'packages/codex-adapter/src/runtime-session.ts',
      'packages/codex-adapter/src/history-reader.ts',
    ].map(async (path) => [path, await hashFile(path)]),
  ),
);
const sourceReference = candidate ? null : (prior ?? reused);
if (
  sourceReference &&
  report.sources['packages/codex-adapter/src/runtime-session.ts'] !==
    sourceReference.sources['packages/codex-adapter/src/runtime-session.ts']
) {
  const frozenPath = join(buildRoot, 'frozen-source/runtime-session.ts');
  assert.equal(
    await hashFile(frozenPath),
    sourceReference.sources['packages/codex-adapter/src/runtime-session.ts'],
    'Require exact frozen source',
  );
  const frozenDist = join(buildRoot, 'frozen-dist');
  assert.equal(
    globalThis.__acceptedReceiptsFrozenBuild,
    frozenDist,
    'Require the isolated frozen build loader',
  );
  assert.equal(
    await hashFile(join(frozenDist, 'runtime-session.js')),
    'c8cf30398b48ebbde50c71861d62b3521a1049c436ec32b3e0d6c9201ea70b78',
    'Require the previously verified frozen runtime build',
  );
  assert.equal(
    await hashFile(join(frozenDist, 'history-reader.js')),
    '380bd7913e65a5c08631e53e0f4789a6c82613a98f99ae1f624fa325c0ae27b8',
    'Require the previously verified frozen reader build',
  );
  report.frozenDist = frozenDist;
  report.concurrentSourceSHA256 = report.sources['packages/codex-adapter/src/runtime-session.ts'];
  report.frozenSourcePath = frozenPath;
  report.sources['packages/codex-adapter/src/runtime-session.ts'] = await hashFile(frozenPath);
}
if (candidate) {
  assert.deepEqual(report.sources, candidate.sources, 'Candidate source fingerprints must match');
  const persisted = JSON.parse(await readFile(join(directory, 'host/task-receipts.json'), 'utf8'));
  assert.equal(
    persisted.filter(
      (receipt) => receipt.acceptedTurnId === prior.oldTurnId && receipt.state === 'submitted',
    ).length,
    8,
    'Candidate recovery requires the original eight submitted receipts',
  );
  assert.deepEqual(
    new Set(persisted.map((receipt) => receipt.clientMessageId)),
    new Set(prior.operations.map((operation) => operation.clientMessageId)),
  );
  report.candidateTransition = {
    candidatePath: resolve(candidatePath),
    candidateSHA256: await hashFile(candidatePath),
    previousSources: prior.sources,
    sources: report.sources,
    reason: candidate.reason,
    priorDisconnectEvidence:
      'matched receipt implementation; original runtime source differs only in eight MIME mappings',
    attempt,
  };
  delete report.frozenDist;
} else if (prior)
  assert.deepEqual(
    report.sources,
    prior.sources,
    'Recovery cannot silently change the production candidate',
  );
if (reused)
  assert.deepEqual(
    report.sources,
    reused.sources,
    'Build reuse requires identical production inputs',
  );
report.attemptBuilds ??= [];
if (candidate && report.attemptBuilds.length === 0)
  report.attemptBuilds.push(
    ...prior.attempts.map(({ attempt }) => ({
      attempt,
      sources: { ...candidate.previousSources },
      runtimeSHA256: 'c8cf30398b48ebbde50c71861d62b3521a1049c436ec32b3e0d6c9201ea70b78',
      readerSHA256: '380bd7913e65a5c08631e53e0f4789a6c82613a98f99ae1f624fa325c0ae27b8',
      evidence: 'previously verified frozen build; second attempt loaded by checked private loader',
    })),
  );
report.attemptBuilds.push({
  attempt,
  sources: { ...report.sources },
  runtimeSHA256: await hashFile('packages/codex-adapter/dist/runtime-session.js'),
  readerSHA256: await hashFile('packages/codex-adapter/dist/history-reader.js'),
  ...(candidate ? { candidate: true } : {}),
});
const witness = new DesktopIpc(join(homedir(), '.codex/ipc/ipc.sock'));
let host, backend, hostKey, proxy, nativeChild;
let nativeComplete = false;
const pending = new Map();
let nextRequest = 0;
const call = (operation, extra = {}) =>
  new Promise((resolveCall, reject) => {
    const requestId = ++nextRequest;
    const timer = setTimeout(() => {
      pending.delete(requestId);
      reject(Error('Task control timed out: ' + operation));
    }, 45000);
    pending.set(requestId, { resolve: resolveCall, reject, timer });
    host.send({ requestId, operation, ...extra });
  });
const until = async (read, predicate, description, timeout = 180000) => {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const value = await read();
    if (predicate(value)) return value;
    await delay(500);
  }
  throw Error('Timed out: ' + description);
};
const launchHost = async (hiddenTurn, held) => {
  const env = {
    ...process.env,
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    HOST_AGENT_STATE_DIR: join(directory, 'host'),
    HOST_AGENT_ROOT: process.cwd(),
    HOST_AGENT_NAME: 'Accepted receipts isolated DEV',
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: '0',
    HOST_AGENT_DISCOVERY: '0',
    HOST_AGENT_DISCOVERY_BONJOUR: '0',
    CODEX_DESKTOP_SUGGESTION_OBSERVER: '0',
    RECEIPT_TEST_THREAD: report.threadId,
    RECEIPT_TEST_HIDDEN_TURN: hiddenTurn ?? '',
    RECEIPT_TEST_HOLD: held ? '1' : '0',
  };
  for (const key of [
    'HOST_AGENT_TOKEN',
    'HOST_AGENT_TLS_CERT',
    'HOST_AGENT_TLS_KEY',
    'HOST_AGENT_REPLY_CHECKPOINT_FILE',
    'CODEX_DESKTOP_CONTROLLER_CONFIG',
    'CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG',
    'HOST_NOTIFICATION_RELAY_URL',
    'HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE',
  ])
    delete env[key];
  const log = createWriteStream(
    join(directory, 'host-' + attempt + '-' + (hiddenTurn ? 'restarted' : 'initial') + '.log'),
    { mode: 0o600 },
  );
  host = fork(resolve('scripts/fixtures/accepted-receipts-host.mjs'), [], {
    env,
    stdio: ['ignore', 'pipe', 'pipe', 'ipc'],
  });
  host.stdout.pipe(log);
  host.stderr.pipe(log, { end: false });
  const child = host;
  const ready = await new Promise((done, reject) => {
    const timer = setTimeout(() => reject(Error('Isolated Host startup timed out')), 90000);
    child.once('error', reject);
    child.on('message', (message) => {
      if (message.type === 'ready') {
        clearTimeout(timer);
        done(message);
        return;
      }
      const request = pending.get(message.requestId);
      if (!request) return;
      pending.delete(message.requestId);
      clearTimeout(request.timer);
      if (message.error) request.reject(Error(message.error));
      else request.resolve(message.result);
    });
    child.once('exit', (code) => {
      clearTimeout(timer);
      log.end();
      for (const request of pending.values()) {
        clearTimeout(request.timer);
        request.reject(Error('Isolated Host exited'));
      }
      pending.clear();
      if (code !== 0) reject(Error('Isolated Host exited before ready: ' + code));
    });
  });
  if (report.frozenDist) {
    assert.equal(ready.frozenBuild, report.frozenDist);
    assert(ready.frozenLoaded.some((path) => path.endsWith('/runtime-session.js')));
    assert(ready.frozenLoaded.some((path) => path.endsWith('/history-reader.js')));
  }
  backend = 'http://127.0.0.1:' + ready.port;
  hostKey = (await readFile(join(directory, 'host/access-token'), 'utf8')).trim();
  report.hostId = ready.hostId;
  report.hostPids ??= [];
  report.hostPids.push(ready.pid);
  await save();
  return ready;
};
const stopHost = async () => {
  if (!host || host.exitCode !== null) return;
  const child = host;
  const exited = new Promise((done) => child.once('exit', done));
  await call('close');
  assert.equal(await exited, 0);
  host = undefined;
};
const runNative = async (verb, resultPath) => {
  const args = [
    '-project',
    join(buildRoot, 'snapshot/apps/ios/CodexManage.xcodeproj'),
    '-scheme',
    'CodexManage',
    '-destination',
    'platform=iOS Simulator,id=' + device,
    '-derivedDataPath',
    join(buildRoot, 'build'),
    '-parallel-testing-enabled',
    'NO',
    '-collect-test-diagnostics',
    'never',
    '-only-testing:CodexManageUITests/CodexManageUITests/testAcceptedReceiptsRecoveryDEV',
    'NOTIFICATION_APP_BUNDLE_ID=com.easyai.agenthub.receiptrecovery' + nonce.replaceAll('-', ''),
    'CODE_SIGNING_ALLOWED=YES',
    'CODE_SIGN_IDENTITY=-',
    'ARCHS=arm64',
    'ONLY_ACTIVE_ARCH=YES',
  ];
  if (resultPath) args.push('-resultBundlePath', resultPath);
  args.push(verb);
  const log = createWriteStream(join(directory, verb + '-' + attempt + '.log'), { mode: 0o600 });
  nativeChild = spawn('xcodebuild', args, { env: process.env, stdio: ['ignore', 'pipe', 'pipe'] });
  nativeChild.stdout.pipe(log);
  nativeChild.stderr.pipe(log, { end: false });
  const code = await new Promise((done, reject) => {
    nativeChild.once('exit', done);
    nativeChild.once('error', reject);
  });
  log.end();
  nativeChild = undefined;
  return code;
};
const owner = () => witness.completeHistory(report.threadId);
const idsOf = (turn) =>
  new Set(
    (turn?.items ?? []).flatMap((item) =>
      item.type === 'userMessage' && item.clientId
        ? [item.clientId]
        : item.type === 'steeringUserMessage' && item.status === 'accepted'
          ? [item.clientUserMessageId]
          : [],
    ),
  );
const submit = async (text, mode, expectedTurnId) => {
  const reply = {
    hostId: report.hostId,
    threadId: report.threadId,
    clientMessageId: randomUUID(),
    text,
    attachmentIds: [],
    mode,
    ...(expectedTurnId
      ? { expectedTurnId }
      : { effort: 'xhigh', permissionMode: 'guardian-approvals', collaborationMode: 'default' }),
  };
  report.operations.push({ ...reply, submission: 'may-have-written' });
  await save();
  const result = await call('submit', { reply });
  report.operations.at(-1).receiptState = result.state;
  await save();
  assert(
    ['submitted', 'completed'].includes(result.state),
    'Unknown submit is retained for read-only recovery, never replayed',
  );
  return result;
};

try {
  const space = await statfs(directory);
  report.freeBytesBefore = space.bavail * space.bsize;
  let minimumFree = 6 * 1024 ** 3;
  if (prior?.phase === 'native-ready' && prior.nativeBinary && prior.reusedBuildFrom) {
    const products = join(buildRoot, 'build/Build/Products');
    const app = join(products, 'Debug-iphonesimulator/CodexManage.app');
    const info = join(app, 'Info.plist');
    const id = execFileSync('/usr/libexec/PlistBuddy', ['-c', 'Print :CFBundleIdentifier', info], {
      encoding: 'utf8',
    }).trim();
    const binary = execFileSync(
      '/usr/libexec/PlistBuddy',
      ['-c', 'Print :CFBundleExecutable', info],
      { encoding: 'utf8' },
    ).trim();
    assert.equal(id, prior.nativeBinary.bundleId);
    assert.equal(
      await hashFile(join(app, binary)),
      prior.nativeBinary.sha256,
      'Resume requires the verified native App',
    );
    assert(
      (await readdir(products)).some((name) => name.endsWith('.xctestrun')),
      'Resume requires existing test products',
    );
    const measuredPaths = [
      products,
      join(buildRoot, 'build/Build/Intermediates.noindex'),
      join(buildRoot, 'build/ModuleCache.noindex'),
      join(buildRoot, 'build/SDKExplicitPrecompiledModules'),
      prior.attempts.at(-1).resultPath,
    ];
    const measuredBytes = measuredPaths.reduce(
      (sum, path) =>
        sum +
        Number(execFileSync('du', ['-sk', path], { encoding: 'utf8' }).trim().split(/\s+/)[0]) *
          1024,
      0,
    );
    const estimatedIncrement = Math.max(1024 ** 3, 2 * measuredBytes);
    minimumFree = estimatedIncrement + 1024 ** 3;
    report.spaceBudget = {
      phase: 'native-ui-incremental-resume',
      measuredPaths,
      measuredBytes,
      estimatedIncrement,
      safetyReserve: 1024 ** 3,
      minimumFree,
      note: 'Two times the measured existing products, intermediates, caches and prior xcresult, at least 1 GiB, plus 1 GiB reserve; full builds retain 6 GiB.',
    };
  } else report.spaceBudget = { phase: 'full-native-build', minimumFree };
  await save();
  assert(
    report.freeBytesBefore >= minimumFree,
    'Insufficient free space for the recorded native build budget',
  );
  const route = '/' + nonce;
  proxy = createServer(async (request, response) => {
    try {
      const url = new URL(request.url, 'http://127.0.0.1');
      const json = (value) =>
        response
          .writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' })
          .end(JSON.stringify(value));
      if (url.pathname.startsWith(route)) {
        const action = url.pathname.slice(route.length);
        if (!action || action === '/')
          return json({
            endpoint: report.endpoint,
            token: hostKey,
            hostId: report.hostId,
            threadId: report.threadId,
            threadTitle: report.title,
            nonce,
            secondEndpoint: '',
            secondToken: '',
            secondHostId: '',
            delayedEndpoint: '',
          });
        if (action === '/settle') {
          await call('reconcile');
          const status = await until(
            () => call('status'),
            (s) =>
              s.receipts.filter((r) => r.turnId === report.oldTurnId && r.state === 'completed')
                .length === 8,
            'eight terminal receipts',
          );
          report.settled = status;
          await save();
          return json({ settled: 8 });
        }
        if (action === '/unknown-on' || action === '/unknown-off') {
          await call('unknown', { enabled: action === '/unknown-on' });
          return json({ ok: true });
        }
        if (action === '/native-complete') {
          nativeComplete = true;
          return json({ ok: true });
        }
        response.writeHead(404).end();
        return;
      }
      // Pairing and read acknowledgements use the actual Host. Model mutations
      // from the native reader are explicitly forbidden by this test transport.
      if (
        request.method !== 'GET' &&
        !['/connection/claim', '/v1/threads/read'].includes(url.pathname)
      ) {
        report.blockedNativeWrites = (report.blockedNativeWrites ?? 0) + 1;
        response.writeHead(403).end();
        return;
      }
      if (!backend) {
        response.writeHead(503).end();
        return;
      }
      const chunks = [];
      for await (const chunk of request) chunks.push(chunk);
      const headers = { ...request.headers };
      delete headers.host;
      delete headers['content-length'];
      const result = await fetch(backend + request.url, {
        method: request.method,
        headers,
        ...(chunks.length ? { body: Buffer.concat(chunks) } : {}),
        signal: AbortSignal.timeout(25000),
      });
      const body = await result.json();
      if (url.pathname === '/v1/workspace') {
        body.threads = (body.threads ?? []).filter((t) => t.id === report.threadId);
        body.projects = [];
        body.pinnedThreadIds = [];
      }
      if (url.pathname === '/v1/events')
        body.events = (body.events ?? []).filter((e) => e.threadId === report.threadId);
      response
        .writeHead(result.status, {
          'content-type': 'application/json',
          'cache-control': 'no-store',
        })
        .end(JSON.stringify(body));
    } catch (error) {
      if (!response.destroyed)
        response.writeHead(500).end(JSON.stringify({ error: error.message }));
    }
  });
  await new Promise((done) => proxy.listen(0, '127.0.0.1', done));
  report.endpoint = 'http://127.0.0.1:' + proxy.address().port;
  const snapshot = join(buildRoot, 'snapshot');
  const native = join(snapshot, 'apps/ios');
  if (!resumed && !reused) {
    const inputs = [
      'apps/ios',
      'apps/android',
      'packages/mobile-core',
      'packages/protocol',
      'scripts/mobile',
      'gradle',
      'gradlew',
      'gradlew.bat',
      'gradle.properties',
      'build.gradle.kts',
      'settings.gradle.kts',
    ];
    const files = execFileSync(
      'git',
      ['ls-files', '-co', '--exclude-standard', '-z', '--', ...inputs],
      { encoding: 'utf8' },
    )
      .split('\0')
      .filter(Boolean)
      .sort();
    const fingerprint = createHash('sha256');
    for (const file of files) {
      const bytes = await readFile(file);
      fingerprint
        .update(file + '\0')
        .update(bytes)
        .update('\0');
      await mkdir(dirname(join(snapshot, file)), { recursive: true });
      await cp(file, join(snapshot, file));
    }
    report.snapshotSHA256 = fingerprint.digest('hex');
    await mkdir(join(snapshot, '.local'), { recursive: true, mode: 0o700 });
    await cp('.local/mobile-toolchain.json', join(snapshot, '.local/mobile-toolchain.json')).catch(
      (error) => {
        if (error.code !== 'ENOENT') throw error;
      },
    );
  }
  const tests = join(native, 'Tests/CodexManageUITests.swift');
  let testSource = await readFile(tests, 'utf8');
  if (resumed || reused)
    testSource = testSource
      .replace(/http:\/\/127\.0\.0\.1:\d+\/[0-9a-f-]+(?="\)!)/, report.endpoint + route)
      .replace(
        'let composer = app.textViews["composer-input"]',
        'let composer = app.descendants(matching: .any).matching(identifier: "composer-input").firstMatch',
      );
  else {
    const snippet = (
      await readFile('scripts/fixtures/accepted-receipts-native.swift', 'utf8')
    ).replace('__RECEIPT_CONTROL__', report.endpoint + route);
    const anchor = '  @MainActor func testOriginalDesktopPinnedSidebarDEV()';
    assert(testSource.includes(anchor));
    testSource = testSource.replace(anchor, snippet + anchor);
  }
  await writeFile(tests, testSource);
  const entitlement = join(native, 'CodexManage-Simulator.entitlements');
  await writeFile(
    entitlement,
    (await readFile(entitlement, 'utf8')).replaceAll(
      'com.codexmanage.ios',
      'com.easyai.agenthub.receiptrecovery' + nonce.replaceAll('-', ''),
    ),
  );
  report.nativeTestSHA256 = await hashFile(tests);
  await save();
  assert.equal(
    await runNative('build-for-testing'),
    0,
    'Native build failed before any new model input',
  );
  const bundleId = 'com.easyai.agenthub.receiptrecovery' + nonce.replaceAll('-', '');
  const products = join(buildRoot, 'build/Build/Products/Debug-iphonesimulator');
  for (const entry of await readdir(products)) {
    if (!entry.endsWith('.app')) continue;
    const info = join(products, entry, 'Info.plist');
    const id = execFileSync('/usr/libexec/PlistBuddy', ['-c', 'Print :CFBundleIdentifier', info], {
      encoding: 'utf8',
    }).trim();
    if (id !== bundleId) continue;
    const binary = execFileSync(
      '/usr/libexec/PlistBuddy',
      ['-c', 'Print :CFBundleExecutable', info],
      { encoding: 'utf8' },
    ).trim();
    report.nativeBinary = { bundleId, sha256: await hashFile(join(products, entry, binary)) };
  }
  assert(report.nativeBinary, 'Require the exact independently named native binary');
  await save();
  if (!prior || emptyRecovery) {
    if (reused) {
      await witness.connect();
      await witness.follow(reused.threadId, 1000, true);
      const previousTurns = desktopTurns(await witness.completeHistory(reused.threadId));
      assert(
        previousTurns.length === 2 && previousTurns.every((turn) => turn.status === 'completed'),
      );
      assert.deepEqual(
        new Set(previousTurns.flatMap((turn) => [...idsOf(turn)])),
        new Set(reused.operations.map((operation) => operation.clientMessageId)),
      );
      report.priorOperationsReverified = true;
      await save();
    }
    if (!prior) {
      report.title = '接收回执恢复 DEV ' + nonce;
      report.threadId = await new DesktopThreadPreparer(
        executable,
        join(homedir(), '.codex'),
      ).prepare(
        { cwd: process.cwd(), projectId: null, title: report.title },
        {
          starting() {},
          identified() {},
          materialized() {},
          exited(code) {
            report.preparerExit = code;
          },
        },
      );
      assert.equal(report.preparerExit, 0);
      await save();
      await openOriginalDesktopThread(report.threadId);
    }
    await witness.connect();
    await until(
      () => witness.follow(report.threadId, 1000, true),
      Boolean,
      'original owner',
      60000,
    );
    assert.equal(desktopTurns(witness.state(report.threadId)).length, 0);
    if (emptyRecovery) {
      for (const gate of ['release-a', 'release-b'])
        await unlink(join(directory, gate)).catch((error) => {
          if (error.code !== 'ENOENT') throw error;
        });
      report.emptyRecoveryVerified = true;
      await save();
    }
    await launchHost();
    const phaseA = join(directory, 'release-a'),
      phaseB = join(directory, 'release-b');
    const enteredB = join(directory, 'entered-b');
    const waitCommand = (releasePath, enteredPath) => {
      const code = `import pathlib,time; pathlib.Path(${JSON.stringify(enteredPath)}).write_text('entered'); deadline=time.time()+150\nwhile not pathlib.Path(${JSON.stringify(releasePath)}).exists() and time.time()<deadline: time.sleep(0.25)\nassert pathlib.Path(${JSON.stringify(releasePath)}).exists(), 'test gate expired'`;
      return "python3 -c '" + code.replaceAll("'", "'\\''") + "'";
    };
    const first = await submit(
      '这是专属恢复验收。只操作下列任务闸门文件，不修改仓库或联系其他Agent。先运行第一段等待命令（工具yield不超过一秒，后续轮询不超过30秒）：\n' +
        waitCommand(phaseA, join(directory, 'entered-a')) +
        '\n第一段完成后处理本轮所有追加消息，再运行第二段等待：\n' +
        waitCommand(phaseB, enteredB) +
        '\n第二段完成后仅回复 RECEIPT_OLD_' +
        nonce +
        '。不要提前结束，也不要为追加消息新开轮次。',
      'next-turn',
    );
    const firstStatus = await until(
      () => call('status'),
      (s) =>
        s.receipts.some((r) => r.id === report.operations[0].clientMessageId && r.acceptedTurnId),
      'initial acceptance',
    );
    report.oldTurnId = firstStatus.receipts.find(
      (r) => r.id === report.operations[0].clientMessageId,
    ).acceptedTurnId;
    assert(first.turnId === report.oldTurnId);
    await save();
    for (let i = 1; i <= 7; i++)
      await submit(
        '追加验收 ' +
          i +
          ' / ' +
          nonce +
          '：继续原两段闸门等待；不要提前结束。最终只回复原 RECEIPT_OLD 标记。',
        'steer',
        report.oldTurnId,
      );
    await writeFile(phaseA, 'release', { mode: 0o600 });
    const accepted = await until(
      () => call('status'),
      (s) =>
        s.receipts.filter((r) => r.acceptedTurnId === report.oldTurnId && r.state === 'submitted')
          .length === 8,
      'all eight real owner acceptances',
    );
    await until(
      async () => {
        try {
          await readFile(enteredB);
          return true;
        } catch {
          return false;
        }
      },
      Boolean,
      'second real model gate',
    );
    const before = desktopTurns(await owner()).find((t) => t.turnId === report.oldTurnId);
    assert.equal(before.status, 'inProgress');
    report.acceptedBeforeDisconnect = accepted;
    await save();
    report.disconnected = await call('disconnect');
    assert.equal(report.disconnected.ready, false);
    assert.equal(
      report.disconnected.receipts.filter(
        (r) => r.acceptedTurnId === report.oldTurnId && r.state === 'submitted',
      ).length,
      8,
    );
    await stopHost();
    await writeFile(phaseB, 'release', { mode: 0o600 });
    await until(
      owner,
      (state) =>
        desktopTurns(state).find((t) => t.turnId === report.oldTurnId)?.status === 'completed',
      'original turn completion',
    );
    const restarted = await launchHost(report.oldTurnId, true);
    assert.notEqual(restarted.pid, report.disconnected.pid, 'Require a real Host process restart');
    await submit('只回复 RECEIPT_NEW_' + nonce + '，不使用工具。', 'next-turn');
    const newState = await until(
      owner,
      (state) =>
        desktopTurns(state).length === 2 && desktopTurns(state).at(-1).status === 'completed',
      'new turn completion',
    );
    report.newTurnId = desktopTurns(newState).at(-1).turnId;
    report.heldAfterRestart = await call('status');
    assert.equal(
      report.heldAfterRestart.receipts.filter(
        (r) => r.acceptedTurnId === report.oldTurnId && r.state === 'submitted',
      ).length,
      8,
    );
    report.phase = 'native-ready';
    await save();
  } else {
    await witness.connect();
    await until(
      () => witness.follow(report.threadId, 1000, true),
      Boolean,
      'resume original owner',
      60000,
    );
    await launchHost(report.oldTurnId, true);
  }
  const resultPath = join(directory, 'native-' + attempt + '.xcresult');
  const beforeTest = await statfs(directory);
  report.freeBytesBeforeTest = beforeTest.bavail * beforeTest.bsize;
  await save();
  assert(report.freeBytesBeforeTest >= 1024 ** 3, 'Need one GiB reserve for the native test');
  const exitCode = await runNative('test-without-building', resultPath);
  report.attempts.push({ attempt, resultPath, exitCode, nativeComplete });
  await save();
  assert.equal(exitCode, 0);
  assert(nativeComplete);
  const summary = JSON.parse(
    execFileSync(
      'xcrun',
      ['xcresulttool', 'get', 'test-results', 'summary', '--path', resultPath, '--format', 'json'],
      { encoding: 'utf8' },
    ),
  );
  assert.equal(summary.passedTests, 1);
  assert.equal(summary.failedTests, 0);
  assert.equal(summary.skippedTests, 0);
  report.nativeSummary = {
    passed: summary.passedTests,
    failed: summary.failedTests,
    skipped: summary.skippedTests,
  };
  const complete = desktopTurns(await owner());
  assert.equal(complete.length, 2, 'No duplicate model turns');
  const expected = new Set(report.operations.map((operation) => operation.clientMessageId));
  const observed = new Set(complete.flatMap((turn) => [...idsOf(turn)]));
  assert.equal(observed.size, 9);
  assert.deepEqual(observed, expected);
  for (const id of expected) {
    const messages = complete
      .flatMap((turn) => turn.items)
      .filter((item) => item.type === 'userMessage' && item.clientId === id);
    if (messages.length)
      assert.equal(messages.length, 1, 'Canonical input must occur exactly once');
  }
  const final = await call('status');
  assert.equal(
    final.receipts.filter((r) => r.turnId === report.oldTurnId && r.state === 'completed').length,
    8,
  );
  assert(final.summaryReads > 0, 'Require the actual production metadata summary reader');
  report.final = final;
  report.owner = complete.map((turn) => ({
    turnId: turn.turnId,
    status: turn.status,
    inputIds: [...idsOf(turn)],
    model: turn.params?.model,
    effort: turn.params?.effort ?? turn.params?.collaborationMode?.settings?.reasoning_effort,
  }));
  report.status = 'passed';
  delete report.error;
  report.uniqueInputs = 9;
  report.replayedInputs = 0;
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  throw error;
} finally {
  // Release only this task's gates even if native setup fails; do not leave a model waiting.
  for (const gate of ['release-a', 'release-b'])
    await writeFile(join(directory, gate), 'release', { mode: 0o600 });
  if (nativeChild && nativeChild.exitCode === null) nativeChild.kill('SIGTERM');
  await stopHost().catch((error) => {
    report.closeError = error.message;
  });
  witness.close();
  if (proxy) {
    proxy.closeAllConnections();
    await new Promise((done) => proxy.close(done));
  }
  report.finishedAt = new Date().toISOString();
  await save();
  console.log(
    JSON.stringify({
      directory,
      status: report.status,
      threadId: report.threadId,
      phase: report.phase,
    }),
  );
}
