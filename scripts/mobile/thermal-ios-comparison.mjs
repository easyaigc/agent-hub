import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync, spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { createWriteStream } from 'node:fs';
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { homedir } from 'node:os';
import { prepareIsolatedIOSRunner } from './isolated-ios-runner.mjs';
import { verifyIOSResultDevice } from './ios-result-device.mjs';

const argument = (name) => {
  const index = process.argv.indexOf(name);
  assert(index >= 0 && process.argv[index + 1], name);
  return process.argv[index + 1];
};
const directory = resolve(argument('--directory'));
const device = argument('--device');
const variant = argument('--variant');
assert(['before', 'after'].includes(variant));
const cpuProfile = process.argv.includes('--cpu-profile');
const workProfile = process.argv.includes('--work-profile');
const nativeOnly = process.argv.includes('--native-only');
assert(!nativeOnly || workProfile, 'Native-only measurements require scoped frame evidence');
const source = resolve(argument('--source'));
const test = argument('--test');
assert(/^testThermal\w+DEV$/.test(test));
const nativeFrameTests = new Set([
  'testThermalMixedInteractionDEV',
  'testThermalStreamingHistorySwitchDEV',
]);
assert(
  !nativeOnly || nativeFrameTests.has(test),
  'Native-only capture is supported only by tests that explicitly enable scoped frame evidence; other tests require Instruments launch',
);
const bundleId = 'com.easyai.agenthub.thermal.acceptance';
await mkdir(directory, { recursive: true, mode: 0o700 });
const run = (name, args) =>
  execFileSync(name, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
const hash = createHash('sha256');
const digestDirectory = async (relative) => {
  for (const entry of (await readdir(join(source, relative), { withFileTypes: true })).sort(
    (a, b) => a.name.localeCompare(b.name),
  )) {
    const path = join(relative, entry.name);
    if (entry.isDirectory()) await digestDirectory(path);
    else if (entry.isFile())
      hash
        .update(path + '\0')
        .update(await readFile(join(source, path)))
        .update('\0');
  }
};
await digestDirectory('apps/ios/Sources');
await digestDirectory('packages/mobile-core/src/commonMain');
hash.update(await readFile(join(source, 'apps/ios/Tests/CodexManageUITests.swift')));
hash.update(await readFile(join(source, 'apps/ios/CodexManage.xcodeproj/project.pbxproj')));
const report = { variant, test, bundleId, sourceSha256: hash.digest('hex'), status: 'running' };
report.captureMode = nativeOnly ? 'native-telemetry' : 'instruments';
const save = () =>
  writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
await writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2), {
  mode: 0o600,
  flag: 'wx',
});
const fixture = JSON.parse(await readFile(argument('--fixture'), 'utf8'));
const mixed = test.includes('MixedInteraction');
let mixedClients;
if (mixed) {
  const selections = JSON.parse(await readFile(argument('--mixed-targets'), 'utf8'));
  assert.equal(selections.length, 2);
  const inventory = JSON.parse(
    await readFile(join(homedir(), '.config/agent-hub/hosts.json'), 'utf8'),
  );
  mixedClients = [fixture];
  for (const selection of selections) {
    assert.notEqual(selection.hostId, fixture.hostId);
    const host = inventory.hosts.find((value) => value.hostId === selection.hostId);
    assert(host && !host.temporaryTaskConnection);
    assert.equal(selection.threads.length, 2);
    mixedClients.push({
      ...fixture,
      endpoint: host.endpoint,
      token: host.clientKey,
      hostId: host.hostId,
      threadId: selection.threads[0].id,
      threadTitle: selection.threads[0].title,
      historyThreadArchived: false,
      switchThreads: selection.threads,
    });
  }
  report.mixedWorkload = {
    clients: 3,
    existingRemoteChats: 4,
    newLocalChats: 2,
    originalModelInputs: 3,
    scrollCommands: 22,
    remoteHistoryBulkExported: false,
    remoteCredentialsPersistedByDriver: false,
    privateXCTestUIEvidenceMayContainViewedText: true,
  };
  report.mixedSelectionSha256 = createHash('sha256')
    .update(JSON.stringify(selections))
    .digest('hex');
  report.modelNonce = fixture.nonce;
  report.mixedModelTargets = [];
}
if (test.includes('SustainedReplies')) report.modelNonce = fixture.nonce;
assert(
  fixture.endpoint?.startsWith('https:') && fixture.token && fixture.hostId && fixture.threadId,
);
report.measurementDriverSha256 = createHash('sha256')
  .update(await readFile(new URL(import.meta.url)))
  .digest('hex');
if (test.includes('RealHistory') || mixed || test === 'testThermalStreamingHistorySwitchDEV') {
  const url = new URL('/v1/history', fixture.endpoint);
  url.searchParams.set('hostId', fixture.hostId);
  url.searchParams.set('threadId', fixture.threadId);
  const response = await fetch(url, {
    headers: { authorization: 'Bearer ' + fixture.token },
    signal: AbortSignal.timeout(30_000),
  });
  assert.equal(response.status, 200);
  const history = await response.json();
  assert.equal(history.hostId, fixture.hostId);
  assert.equal(history.threadId, fixture.threadId);
  assert(Array.isArray(history.messages) && history.messages.length > 0);
  const stable = (value) =>
    Array.isArray(value)
      ? value.map(stable)
      : value && typeof value === 'object'
        ? Object.fromEntries(
            Object.keys(value)
              .sort()
              .map((key) => [key, stable(value[key])]),
          )
        : value;
  report.readOnlyWorkload = {
    targetMatched: true,
    messages: history.messages.length,
    messageBytesUTF8: history.messages.reduce(
      (sum, message) => sum + Buffer.byteLength(message.text ?? '', 'utf8'),
      0,
    ),
    messagesSha256: createHash('sha256')
      .update(JSON.stringify(stable(history.messages)))
      .digest('hex'),
  };
}
const isolated = await prepareIsolatedIOSRunner({
  template: argument('--template'),
  directory: join(directory, 'runner'),
  expectedAppBundleId: bundleId,
});
report.runner = isolated.receipt;
const configuration = JSON.parse(
  run('/usr/bin/plutil', ['-convert', 'json', '-o', '-', isolated.template]),
);
const target = Object.values(configuration).find((value) => value?.IsUITestBundle);
const appPath = target.UITargetAppPath.replaceAll(
  '__TESTROOT__',
  resolve(argument('--template'), '..'),
);
report.mainExecutableUUID = run('xcrun', [
  'dwarfdump',
  '--uuid',
  join(appPath, 'CodexManage'),
]).trim();
assert(/UUID: [A-F0-9-]+ \(arm64\)/.test(report.mainExecutableUUID));
assert.equal(
  JSON.parse(run('/usr/bin/plutil', ['-convert', 'json', '-o', '-', join(appPath, 'Info.plist')]))
    .CFBundleIdentifier,
  bundleId,
);
report.appExecutableSha256 = createHash('sha256')
  .update(await readFile(join(appPath, 'CodexManage')))
  .digest('hex');
// External Instruments launch does not make XCTest install the target App.
// Install the exact signed candidate explicitly before starting the Runner.
run('xcrun', [
  'devicectl',
  'device',
  'install',
  'app',
  '--device',
  device,
  appPath,
  '--json-output',
  join(directory, 'app-install.json'),
]);
const installation = JSON.parse(await readFile(join(directory, 'app-install.json'), 'utf8'));
assert.equal(installation.info.outcome, 'success');
const installed = installation.result.installedApplications.filter(
  (value) => value.bundleID === bundleId,
);
assert.equal(installed.length, 1);
report.installation = {
  deviceIdentifier: installation.result.deviceIdentifier,
  bundleId,
  installationURL: installed[0].installationURL,
  outcome: 'success',
};
await save();
report.cpuProfile = cpuProfile;
report.workProfile = workProfile;
target.EnvironmentVariables = {
  ...target.EnvironmentVariables,
  ...(nativeOnly ? {} : { THERMAL_EXTERNAL_LAUNCH: '1' }),
};
if (nativeOnly) delete target.EnvironmentVariables.THERMAL_EXTERNAL_LAUNCH;
// xcodebuild(1) forwards TEST_RUNNER_<VAR> into the Runner. Keep private
// connection inputs in memory rather than duplicating keys in xctestrun files.
delete target.EnvironmentVariables.HISTORY_SCROLL_LIVE_JSON;
await writeFile(isolated.template, JSON.stringify(configuration), { mode: 0o600 });
run('/usr/bin/plutil', ['-convert', 'xml1', isolated.template]);
const result = join(directory, 'result.xcresult');
const log = createWriteStream(join(directory, 'xcodebuild.log'), { mode: 0o600 });
let power;
let powerDone;
let traceStarted = false;
const startPower = () => {
  if (traceStarted) return;
  traceStarted = true;
  try {
    const apps = JSON.parse(
      run('xcrun', [
        'devicectl',
        'device',
        'info',
        'apps',
        '--device',
        device,
        '--json-output',
        '-',
      ]),
    ).result.apps;
    const app = apps.find((value) => value.bundleIdentifier === bundleId);
    assert(app?.url);
    assert.equal(
      app.url,
      report.installation.installationURL,
      'The running installation must be the candidate just installed',
    );
    const processes = JSON.parse(
      run('xcrun', [
        'devicectl',
        'device',
        'info',
        'processes',
        '--device',
        device,
        '--json-output',
        '-',
      ]),
    ).result.runningProcesses;
    const matches = processes.filter((value) => value.executable === app.url + 'CodexManage');
    assert(matches.length <= 1, 'Require the exact independent App installation path');
    if (matches.length)
      run('xcrun', [
        'devicectl',
        'device',
        'process',
        'terminate',
        '--device',
        device,
        '--pid',
        String(matches[0].processIdentifier),
      ]);
    report.traceTarget = {
      bundleId,
      installationPathMatched: true,
      mode: 'launch',
      expectedExecutable: new URL(app.url).pathname.replace(/^\/private/, '') + 'CodexManage',
    };
    const appArguments = [
      '-thermal-performance-probe',
      '-app-language',
      'zh-Hans',
      '-AppleLanguages',
      '(zh-Hans)',
      '-AppleLocale',
      'zh_CN',
    ];
    if (test.includes('RealHistory') || mixed || test === 'testThermalStreamingHistorySwitchDEV')
      appArguments.push('-history-scroll-probe');
    if (workProfile) appArguments.push('-history-work-probe');
    const powerLog = createWriteStream(join(directory, 'power.log'), { mode: 0o600 });
    power = spawn(
      'xcrun',
      [
        'xctrace',
        'record',
        '--template',
        'Blank',
        '--instrument',
        'Power Profiler',
        ...(cpuProfile ? ['--instrument', 'Time Profiler'] : []),
        '--device',
        device,
        '--time-limit',
        mixed ? '20m' : '10m',
        '--output',
        join(directory, 'power.trace'),
        '--env',
        'HISTORY_SCROLL_EXPECTED_HOST=' + fixture.hostId,
        '--env',
        'HISTORY_SCROLL_EXPECTED_THREAD=' + fixture.threadId,
        '--launch',
        '--',
        bundleId,
        ...appArguments,
      ],
      { stdio: ['ignore', 'pipe', 'pipe'] },
    );
    for (const stream of [power.stdout, power.stderr]) stream.pipe(powerLog, { end: false });
    powerDone = new Promise((done) =>
      power.once('close', (code, signal) => {
        report.powerExit = { code, signal };
        powerLog.end(done);
      }),
    );
  } catch (error) {
    report.powerError = error.message;
  }
};
const verifyNativeTarget = () => {
  try {
    // XCTest may reinstall the target after our explicit installation. Resolve
    // its current Bundle ID before comparing the live executable path.
    const applications = JSON.parse(
      run('xcrun', [
        'devicectl',
        'device',
        'info',
        'apps',
        '--device',
        device,
        '--json-output',
        '-',
      ]),
    ).result.apps.filter((value) => value.bundleIdentifier === bundleId);
    assert.equal(applications.length, 1, 'Require one exact acceptance Bundle ID');
    const processes = JSON.parse(
      run('xcrun', [
        'devicectl',
        'device',
        'info',
        'processes',
        '--device',
        device,
        '--json-output',
        '-',
      ]),
    ).result.runningProcesses;
    const matches = processes.filter(
      (value) => value.executable === applications[0].url + 'CodexManage',
    );
    assert.equal(matches.length, 1, 'Require the exact normally launched acceptance installation');
    report.traceTarget = {
      bundleId,
      mode: 'native-telemetry',
      installationPathMatched: true,
      installationURLChangedByXCTest: applications[0].url !== report.installation.installationURL,
      pid: matches[0].processIdentifier,
    };
  } catch (error) {
    report.nativeTargetError = error.message;
  }
};
const child = spawn(
  'xcodebuild',
  [
    'test-without-building',
    '-xctestrun',
    isolated.template,
    '-destination',
    'id=' + device,
    '-resultBundlePath',
    result,
    '-parallel-testing-enabled',
    'NO',
    '-collect-test-diagnostics',
    'never',
    '-only-testing:CodexManageUITests/CodexManageUITests/' + test,
  ],
  {
    stdio: ['ignore', 'pipe', 'pipe'],
    env: {
      ...process.env,
      TEST_RUNNER_HISTORY_SCROLL_LIVE_JSON: JSON.stringify(fixture),
      ...(nativeOnly ? { TEST_RUNNER_THERMAL_NATIVE_ONLY: '1' } : {}),
      ...(process.argv.includes('--idle-baseline')
        ? { TEST_RUNNER_THERMAL_IDLE_BASELINE: '1' }
        : {}),
      ...(mixedClients
        ? { TEST_RUNNER_THERMAL_MIXED_CLIENTS_JSON: JSON.stringify(mixedClients) }
        : {}),
    },
  },
);
for (const stream of [child.stdout, child.stderr])
  createInterface({ input: stream }).on('line', (line) => {
    let safeLine = line;
    for (const value of mixedClients ?? [fixture])
      safeLine = safeLine.replaceAll(value.token, '[REDACTED]');
    log.write(safeLine + '\n');
    if (mixed && line.startsWith('THERMAL_MIXED_CREATED ')) {
      const [, marker, threadId] = line.trim().split(' ');
      assert(marker.endsWith(fixture.nonce) && /^[a-f0-9-]{36}$/.test(threadId));
      report.mixedModelTargets.push({ marker, threadId });
    }
    if (line.startsWith('THERMAL_CREATED_THREAD ')) {
      const id = line.slice('THERMAL_CREATED_THREAD '.length).trim();
      assert(/^[a-f0-9-]{36}$/.test(id));
      report.modelThreadId = id;
    }
    if (line.startsWith('THERMAL_LAUNCH_REQUEST')) {
      assert(!nativeOnly);
      startPower();
      console.log('THERMAL_EXTERNAL_APP_LAUNCHED');
    }
    if (
      line.startsWith('THERMAL_PROFILE_REQUEST') ||
      line.startsWith('THERMAL_MEASUREMENT_BEGIN')
    ) {
      if (nativeOnly) verifyNativeTarget();
      else startPower();
      console.log(line);
    }
    if (line.startsWith('THERMAL_MEASUREMENT_END') || line.startsWith('THERMAL_SCROLL_METRICS'))
      console.log(line);
  });
report.testExit = await new Promise((done, reject) => {
  child.once('close', done);
  child.once('error', reject);
});
await new Promise((done) => log.end(done));
if (power) {
  power.kill('SIGINT');
  await powerDone;
}
if (test.includes('SustainedReplies')) {
  // Resolve the original submitted target independently. No model input or
  // retry is sent here, including after a late UI assertion failure.
  try {
    const matches = new Set();
    let cursor;
    let pages = 0;
    do {
      const url = new URL('/v1/workspace', fixture.endpoint);
      if (cursor) url.searchParams.set('threadCursor', cursor);
      const response = await fetch(url, {
        headers: { authorization: 'Bearer ' + fixture.token },
        signal: AbortSignal.timeout(20_000),
      });
      assert.equal(response.status, 200);
      const workspace = await response.json();
      assert.equal(workspace.hostId, fixture.hostId);
      for (const thread of workspace.threads)
        if (thread.title.includes('THERMAL_START_' + fixture.nonce)) matches.add(thread.id);
      cursor = workspace.nextThreadCursor;
      pages += 1;
    } while (cursor && pages < 50);
    assert.equal(matches.size, 1, 'One original submitted model target is required');
    report.modelThreadId = [...matches][0];
  } catch (error) {
    report.modelTargetError = error.message;
  }
}
const summary = JSON.parse(
  run('xcrun', ['xcresulttool', 'get', 'test-results', 'summary', '--path', result]),
);
await writeFile(
  join(directory, 'xct-metrics.json'),
  run('xcrun', ['xcresulttool', 'get', 'test-results', 'metrics', '--path', result]),
  { mode: 0o600 },
);
report.devices = verifyIOSResultDevice(summary, device);
report.summary = summary;
try {
  run('xcrun', [
    'devicectl',
    'device',
    'copy',
    'from',
    '--device',
    device,
    '--domain-type',
    'appDataContainer',
    '--domain-identifier',
    bundleId,
    '--source',
    'Library/Application Support/thermal-acceptance.jsonl',
    '--destination',
    join(directory, 'thermal.jsonl'),
  ]);
  report.telemetryCopied = true;
} catch (error) {
  report.telemetryError = error.message;
}
if (workProfile) {
  try {
    run('xcrun', [
      'devicectl',
      'device',
      'copy',
      'from',
      '--device',
      device,
      '--domain-type',
      'appDataContainer',
      '--domain-identifier',
      bundleId,
      '--source',
      'Library/Application Support/history-work-metrics.json',
      '--destination',
      join(directory, 'history-work-metrics.json'),
    ]);
    report.workTraceCopied = true;
  } catch (error) {
    report.workTraceError = error.message;
  }
}
if (power) {
  try {
    const toc = run('xcrun', [
      'xctrace',
      'export',
      '--input',
      join(directory, 'power.trace'),
      '--toc',
    ]);
    assert(
      toc.includes('uuid="' + device + '"'),
      'Power trace must identify the same physical device',
    );
    const processes = [...toc.matchAll(/<process\b([^>]+)>/g)].map((match) => match[1]);
    const matches = processes.filter((value) => {
      const path = value.match(/path="([^"]+)"/)?.[1].replace(/^\/private/, '');
      return (
        path === report.traceTarget.expectedExecutable ||
        path === report.traceTarget.expectedExecutable.replace(/\/CodexManage$/, '')
      );
    });
    assert.equal(
      matches.length,
      1,
      'Power trace must identify the exact independent App installation',
    );
    report.traceTarget.pid = Number(matches[0].match(/pid="(\d+)"/)[1]);
    await writeFile(join(directory, 'power-toc.xml'), toc);
    for (const schema of [
      'ProcessSubsystemPowerImpact',
      'SystemThermalState',
      'DeviceChargingState',
      'DisplayBrightnessLevel',
      'SystemPowerLevel',
      ...(cpuProfile ? ['time-profile'] : []),
    ]) {
      run('xcrun', [
        'xctrace',
        'export',
        '--input',
        join(directory, 'power.trace'),
        '--xpath',
        '/trace-toc/run[@number="1"]/data/table[@schema="' + schema + '"]',
        '--output',
        join(directory, schema + '.xml'),
      ]);
    }
    report.powerExported = true;
  } catch (error) {
    report.powerExportError = error.message;
  }
}
report.nativeStatus =
  report.testExit === 0 &&
  summary.passedTests === 1 &&
  summary.failedTests === 0 &&
  summary.skippedTests === 0
    ? 'this_run_pass'
    : 'this_run_fail';
report.status =
  report.nativeStatus === 'this_run_pass' &&
  report.telemetryCopied &&
  (nativeOnly ? report.traceTarget?.installationPathMatched : report.powerExported) &&
  report.installation?.outcome === 'success' &&
  (!workProfile || report.workTraceCopied) &&
  (!mixed || report.mixedModelTargets.length === 2) &&
  (!test.includes('SustainedReplies') || report.modelThreadId)
    ? 'this_run_pass'
    : 'this_run_fail';
await save();
console.log(
  JSON.stringify({
    directory,
    variant,
    status: report.status,
    powerExported: report.powerExported,
    telemetryCopied: report.telemetryCopied,
  }),
);
assert.equal(report.status, 'this_run_pass');
