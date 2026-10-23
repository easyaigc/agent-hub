import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

// A separate emulator feeds an actual Android camera with a synthetic image.
// No App test hooks inject the decoded value or bypass CameraX/ImageAnalysis.
const local = JSON.parse(await readFile('.local/mobile-toolchain.json', 'utf8'));
const adb = resolve(local.androidSdk, 'platform-tools/adb');
const serial = process.env.ANDROID_SERIAL;
assert(serial?.startsWith('emulator-'));
const kind = process.env.ANDROID_CAMERA_KIND;
assert(['foreign', 'invalid-codex', 'valid-pairing', 'easywork-device'].includes(kind));
const apk = process.env.ANDROID_CAMERA_APK;
const cameraImage = process.env.ANDROID_CAMERA_IMAGE;
assert(apk && cameraImage);
const call = (...args) =>
  execFileSync(adb, ['-s', serial, ...args], { encoding: 'utf8', timeout: 20_000 }).trim();
assert.equal(call('shell', 'getprop', 'sys.boot_completed'), '1');
const easyWorkDevice = kind === 'easywork-device';
const host = easyWorkDevice
  ? JSON.parse(await readFile(process.env.EASYWORK_HOST_CONFIG, 'utf8'))
  : null;
assert(!host || host.identityScope === 'explicit-loopback-DEV-only');
const witness = async () => {
  const response = await fetch(`http://127.0.0.1:${host.observerPort}/evidence?native=1`, {
    signal: AbortSignal.timeout(10000),
  });
  assert.equal(response.status, 200);
  const value = await response.json();
  assert(value.desktopOwnerAlive);
  return value;
};
const before = easyWorkDevice ? await witness() : null;
const bindingStates = async () => {
  const response = await fetch(`http://127.0.0.1:${host.observerPort}/evidence`, {
    signal: AbortSignal.timeout(30000),
  });
  assert.equal(response.status, 200);
  return (await response.json()).bindingStates;
};
const beforeBindings = easyWorkDevice ? await bindingStates() : null;
const output =
  '.local/mobile-acceptance/' + (easyWorkDevice ? 'A5' : 'A3') + '/camera-' + Date.now();
await mkdir(output, { recursive: true });
const hash = async (path) =>
  createHash('sha256')
    .update(await readFile(path))
    .digest('hex');
const report = {
  phase: easyWorkDevice ? 'A5' : 'A3',
  scope: 'actual-emulator-camera-read',
  kind,
  serial,
  api: call('shell', 'getprop', 'ro.build.version.sdk'),
  build: call('shell', 'getprop', 'ro.build.fingerprint'),
  apkSha256: await hash(apk),
  cameraImageSha256: await hash(cameraImage),
  toolSha256: await hash('scripts/mobile/android-camera-acceptance.mjs'),
  status: 'running',
};
await writeFile(output + '/report.json', JSON.stringify(report, null, 2) + '\n');
const pkg = 'com.easyai.agenthub.android.debug';
const xmlPath = '/sdcard/agenthub-camera-acceptance.xml';
const decode = (text) =>
  text.replace(
    /&(amp|quot|apos|lt|gt);/g,
    (_, v) => ({ amp: '&', quot: '"', apos: "'", lt: '<', gt: '>' })[v],
  );
function dump() {
  call('shell', 'uiautomator', 'dump', xmlPath);
  const xml = call('shell', 'cat', xmlPath);
  return {
    xml,
    nodes: (xml.match(/<node\b[^>]*>/g) ?? []).map((node) =>
      Object.fromEntries(
        [...node.matchAll(/([\w-]+)="([^"]*)"/g)].map((m) => [m[1], decode(m[2])]),
      ),
    ),
  };
}
async function wait(predicate) {
  const deadline = Date.now() + 45_000;
  while (Date.now() < deadline) {
    const state = dump();
    if (predicate(state)) return state;
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error('The expected native camera UI was not reached');
}
async function click(predicate) {
  const state = await wait((s) => s.nodes.some(predicate));
  const node = state.nodes.find(predicate);
  const bounds = node.bounds.match(/^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/);
  assert(bounds);
  const [, x1, y1, x2, y2] = bounds.map(Number);
  assert(x2 > x1 && y2 > y1);
  call(
    'shell',
    'input',
    'tap',
    String(Math.round((x1 + x2) / 2)),
    String(Math.round((y1 + y2) / 2)),
  );
}
async function capture(name, state = dump()) {
  await writeFile(output + '/' + name + '.xml', state.xml);
  await writeFile(
    output + '/' + name + '.png',
    execFileSync(adb, ['-s', serial, 'exec-out', 'screencap', '-p'], { timeout: 20_000 }),
  );
}
try {
  call('install', '-r', '-t', apk);
  call('shell', 'pm', 'clear', pkg);
  call('shell', 'pm', 'revoke', pkg, 'android.permission.CAMERA');
  call(
    'shell',
    'pm',
    'clear-permission-flags',
    pkg,
    'android.permission.CAMERA',
    'user-set',
    'user-fixed',
  );
  call('shell', 'am', 'start', '-W', '-n', pkg + '/com.agenthub.android.MainActivity');
  await click((n) => n.text === '添加客户端');
  if (easyWorkDevice) await click((n) => n.text === 'EasyWork');
  await click((n) => n.text === '连接方式');
  await click((n) => n.text === '扫码连接');
  await click((n) => n.text === '扫描电脑二维码');
  const prompt = await wait((s) =>
    s.nodes.some((n) => n['resource-id']?.endsWith('/permission_allow_foreground_only_button')),
  );
  await capture('camera-system-prompt', prompt);
  await click((n) => n['resource-id']?.endsWith('/permission_allow_foreground_only_button'));
  if (easyWorkDevice) {
    const pending = await wait((s) =>
      s.nodes.some((n) => n.text.startsWith('请在电脑上确认此设备')),
    );
    assert(!pending.nodes.some((n) => n.text === '登录' || n.text === '登录 EasyAI'));
    await capture('camera-device-awaits-desktop', pending);
    const response = await fetch(`http://127.0.0.1:${host.observerPort}/control`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-easywork-acceptance': host.authorization },
      body: JSON.stringify({ operation: 'desktop.device-claim-approve' }),
      signal: AbortSignal.timeout(40000),
    });
    assert.equal(response.status, 200);
    assert((await response.json()).accepted);
    const clients = await wait(
      (s) => s.nodes.some((n) => n.text === 'EasyWork') && s.nodes.some((n) => n.text === '已连接'),
    );
    await capture('camera-device-approved-client', clients);
    await click((n) => n.text === '完成');
    const after = await witness();
    const old = new Set(beforeBindings.map((b) => b.bindingId));
    const added = (await bindingStates()).filter(
      (b) =>
        !old.has(b.bindingId) && b.status === 'active' && b.authorizationProtocol === 'EWDAP/1',
    );
    assert.equal(
      added.length,
      1,
      'The real Desktop must create exactly one new scoped device binding',
    );
    const runs = (v) => v.independentDesktop.executions.map((r) => r.id).sort();
    assert.deepEqual(runs(after), runs(before), 'Camera pairing must not start any model input');
    report.phoneAccountLoginRequired = false;
    report.desktopConfirmedBinding = true;
    report.pairingProtocol = 'EWDAP/1';
    report.nativeModelInputs = 0;
    report.authScope =
      'Real anonymous camera claim and original Desktop DEV identity approval; public OIDC not covered';
  } else if (kind === 'foreign') {
    const preview = await wait((s) => s.nodes.some((n) => n.text === '请扫描电脑提供的连接二维码'));
    await capture('camera-rejects-foreign-qr', preview);
    await click((n) => n.text === '取消');
    const form = await wait((s) => s.nodes.some((n) => n.text === '或粘贴连接二维码内容'));
    await capture('camera-cancel-restores-form', form);
  } else if (kind === 'invalid-codex') {
    const form = await wait(
      (s) =>
        s.nodes.some((n) => n.text === 'codex-host://pair?descriptor=e30') &&
        s.nodes.some((n) => n.text === '电脑连接信息格式无效') &&
        !s.nodes.some((n) => n.text === '将电脑上的连接二维码放在画面中。'),
    );
    await capture('camera-read-reaches-descriptor-validation', form);
    assert(
      form.nodes.some((n) => n.text === '电脑连接信息格式无效'),
      'Malformed scanned descriptors must be rejected',
    );
  } else {
    const clients = await wait(
      (s) => s.nodes.some((n) => n.text === '本机 Mac') && s.nodes.some((n) => n.text === '已连接'),
    );
    await capture('camera-paired-client', clients);
    await click((n) => n.text === '完成');
    await click((n) => n['content-desc'] === '打开侧栏');
    await click((n) => n.text === '合成配对历史');
    const connected = await wait(
      (s) =>
        s.nodes.some((n) => n.text === '已验证合成配对与只读历史。') &&
        s.nodes.some((n) => n.text === '本机 Mac') &&
        !s.nodes.some((n) => n.text === '将电脑上的连接二维码放在画面中。'),
    );
    await capture('camera-paired-and-read-history', connected);
  }
  const camera = call('shell', 'dumpsys', 'media.camera');
  const active = camera.split('Active Camera Clients:')[1]?.split('Allowed user IDs:')[0];
  assert(active && !active.includes(pkg), 'Closing the scanner must release this App camera');
  report.status = 'passed';
  report.cases = 1;
  report.actualCameraFrames = true;
  report.injectedDecoderResult = false;
  report.cameraReleased = true;
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  try {
    await capture('failure');
  } catch {
    // Preserve the original failure when best-effort cleanup or diagnostic capture fails.
  }
} finally {
  call('shell', 'am', 'force-stop', pkg);
  await writeFile(output + '/report.json', JSON.stringify(report, null, 2) + '\n');
}
console.log('Native camera evidence:', output, report.status);
assert.equal(report.status, 'passed');
