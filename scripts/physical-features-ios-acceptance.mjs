import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
const root = resolve(process.env.PHYSICAL_ACCEPTANCE_DIRECTORY);
const phase = process.argv[2];
const cases = {
  cleanup: 'Cleanup',
  'cleanup-restore-original': 'CleanupRestoreOriginal',
  model: 'ChatActions',
  pin: 'ChatActions',
  search: 'ChatActions',
  archive: 'UnloadedLifecycle',
  restored: 'UnloadedLifecycle',
  'bulk-restored': 'UnloadedLifecycle',
  'delete-cancelled': 'UnloadedLifecycle',
  deleted: 'UnloadedLifecycle',
  'bulk-archive': 'WorkspaceManagement',
  'projects-deleted': 'WorkspaceManagement',
  'notification-settings': 'NotificationSettings',
  notification: 'Notification',
  'notification-resolve': 'NotificationResolve',
  'completion-notification': 'CompletionNotification',
  'completion-notification-recheck': 'CompletionNotification',
  'restore-preferences': 'NotificationSettings',
};
assert(cases[phase], 'Choose one fixed physical acceptance phase');
assert(process.env.PHYSICAL_DEVICE_UDID, 'Explicit paired physical device required');
const environment = JSON.parse(await readFile(join(root, 'runner-environment.json'), 'utf8'));
const control = async (path, method = 'GET', body) => {
  const response = await fetch('http://127.0.0.1:18988/__physical/' + path, {
    method,
    headers: {
      authorization: 'Bearer ' + environment.PHYSICAL_ACCEPTANCE_CONTROL_TOKEN,
      ...(body ? { 'content-type': 'application/json' } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  const value = await response.json();
  if (!response.ok) throw Error(JSON.stringify(value));
  return value;
};
const fixture = await control('config');
if (phase === 'completion-notification-recheck')
  fixture.completionMarker = 'PHONE_COMPLETE_' + fixture.connection.nonce + '_RECHECK';
if (phase === 'model') {
  assert(!fixture.connection.threadId, 'Never replay an existing model input');
  let intents = [];
  try {
    intents = Object.values(
      JSON.parse(await readFile(join(root, 'host/desktop-creations/creations.json'), 'utf8')),
    );
  } catch (e) {
    if (e.code !== 'ENOENT') throw e;
  }
  assert(
    !intents.some((v) => v.text === fixture.prompt),
    'Reconcile existing model intent; do not replay',
  );
}
const dir = join(root, 'native', phase + '-' + randomUUID().slice(0, 8));
await mkdir(dir, { recursive: true, mode: 0o700 });
const bundle = join(dir, 'result.xcresult'),
  logfile = join(dir, 'xcodebuild.log');
const products = resolve('.build/ios-physical/Build/Products');
const runner = join(products, 'PhysicalFeaturesPhase.xctestrun');
const input = join(dir, 'fixture.json');
await writeFile(
  input,
  JSON.stringify({ PHYSICAL_FIXTURE_JSON: JSON.stringify(fixture), PHYSICAL_PHASE: phase }),
  { mode: 0o600 },
);
const command = async (cmd, args, options = {}) =>
  await new Promise((done, reject) => {
    const p = spawn(cmd, args, {
      env: { ...process.env, DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer' },
      ...options,
    });
    let data = '';
    p.stdout?.on('data', (v) => (data += v));
    p.stderr?.on('data', (v) => (data += v));
    p.once('error', reject);
    p.once('close', (code) =>
      code === 0 ? done(data) : reject(Error(cmd + ' exited ' + code + ' ' + data.slice(-1000))),
    );
  });
await command('python3', [
  '-c',
  `import plistlib,json,sys,os
p=plistlib.load(open(sys.argv[1],'rb'))
e=json.load(open(sys.argv[2]))
for key,target in p.items():
 if key!='__xctestrun_metadata__' and isinstance(target,dict): target.setdefault('EnvironmentVariables',{}).update(e)
with open(sys.argv[3],'wb') as f:plistlib.dump(p,f)
os.chmod(sys.argv[3],0o600)
`,
  join(products, 'CodexManage_iphoneos27.0-arm64.xctestrun'),
  input,
  runner,
]);
let output = '',
  notificationStart;
const fd = await import('node:fs').then((m) => m.openSync(logfile, 'wx', 0o600));
const { writeSync, closeSync } = await import('node:fs');
const result = await new Promise((done, reject) => {
  const child = spawn(
    '/Applications/Xcode.app/Contents/Developer/usr/bin/xcodebuild',
    [
      'test-without-building',
      '-xctestrun',
      runner,
      '-destination',
      'platform=iOS,id=' + process.env.PHYSICAL_DEVICE_UDID,
      '-only-testing:CodexManageUITests/CodexManageUITests/testPhysicalAgentHubFeature' +
        cases[phase],
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
      '-resultBundlePath',
      bundle,
    ],
    { env: { ...process.env, DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer' } },
  );
  const collect = (v) => {
    writeSync(fd, v);
    output += v.toString();
    if (
      ['notification', 'completion-notification', 'completion-notification-recheck'].includes(
        phase,
      ) &&
      !notificationStart &&
      output.includes('PHYSICAL_NOTIFICATION_READY')
    ) {
      notificationStart = control(
        phase === 'notification'
          ? 'start-notification'
          : phase === 'completion-notification-recheck'
            ? 'start-completion-notification-recheck'
            : 'start-completion-notification',
        'POST',
      ).then(async (submission) => {
        if (phase !== 'notification') return submission;
        const deadline = Date.now() + 180000;
        while (true) {
          try {
            const approval = await control('verify/approval');
            await writeFile(
              join(dir, 'original-approval.json'),
              JSON.stringify(approval, null, 2),
              { mode: 0o600 },
            );
            return submission;
          } catch (error) {
            if (Date.now() > deadline) throw error;
            await new Promise((done) => setTimeout(done, 1000));
          }
        }
      });
      notificationStart.catch(() => {});
    }
  };
  child.stdout.on('data', collect);
  child.stderr.on('data', collect);
  child.once('error', reject);
  child.once('close', done);
});
closeSync(fd);
await writeFile(
  join(dir, 'execution.json'),
  JSON.stringify(
    { phase, exitCode: result, device: process.env.PHYSICAL_DEVICE_UDID, bundle },
    null,
    2,
  ),
  { mode: 0o600 },
);
await command('/usr/bin/xcrun', [
  'xcresulttool',
  'export',
  'attachments',
  '--path',
  bundle,
  '--output-path',
  join(dir, 'attachments'),
]).catch(() => {});
if (phase === 'notification-settings') {
  const manifest = JSON.parse(await readFile(join(dir, 'attachments/manifest.json'), 'utf8'));
  const candidates = manifest
    .flatMap((v) => v.attachments ?? [])
    .filter(
      (v) =>
        v.suggestedHumanReadableName?.includes('physical-original-notification-preferences') ||
        v.exportedFileName?.includes('physical-original-notification-preferences'),
    );
  assert.equal(
    candidates.length,
    1,
    'Original phone preferences must be retained before acceptance can pass',
  );
  if (candidates.length === 1) {
    const values = JSON.parse(
      await readFile(join(dir, 'attachments', candidates[0].exportedFileName), 'utf8'),
    );
    await control('preferences', 'POST', values);
  }
}
assert.equal(result, 0, 'Real native phase failed; see ' + logfile);
assert(/Executed 1 test, with 0 failures/.test(output), 'Exactly one physical test must have run');
const verifyName = {
  cleanup: 'cleanup',
  'cleanup-restore-original': 'cleanup',
  model: 'model',
  pin: 'pin',
  search: 'unpin',
  archive: 'archive',
  restored: 'restored',
  'bulk-restored': 'bulk-restored',
  'delete-cancelled': 'delete-cancelled',
  deleted: 'deleted',
  'bulk-archive': 'bulk-archive',
  'projects-deleted': 'projects-deleted',
  'notification-settings': 'apns-ready',
  notification: 'notification-resolved',
  'notification-resolve': 'notification-resolved',
  'completion-notification': 'completion-notice',
  'completion-notification-recheck': 'completion-notice-recheck',
}[phase];
let proof;
if (verifyName) {
  const deadline = Date.now() + 120000;
  while (true) {
    try {
      proof = await control('verify/' + verifyName);
      break;
    } catch (e) {
      if (Date.now() > deadline) throw e;
      await new Promise((r) => setTimeout(r, 1000));
    }
  }
}
if (notificationStart) await notificationStart;
await writeFile(
  join(dir, 'verified.json'),
  JSON.stringify(
    {
      phase,
      scope: 'real-physical-iPhone-and-independent-original-Electron',
      proof,
      at: new Date().toISOString(),
    },
    null,
    2,
  ),
  { mode: 0o600 },
);
console.log(JSON.stringify({ phase, status: 'passed', directory: dir }));
