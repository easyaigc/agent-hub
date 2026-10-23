import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createServer } from 'node:http';
import { startNativeReadFixture } from './android-read-fixture.mjs';

// Operate the installed native App. No instrumentation hooks call refresh directly.
const local = JSON.parse(await readFile('.local/mobile-toolchain.json', 'utf8'));
const adb = resolve(local.androidSdk, 'platform-tools/adb');
const serial = process.env.ANDROID_SERIAL;
assert(serial?.startsWith('emulator-'), 'Use a dedicated synthetic-data emulator');
const apk = '.build/native/android/outputs/apk/debug/android-debug.apk';
// Parallel emulators may start in the same millisecond. Keep their evidence separate.
const output = '.local/mobile-acceptance/A3/workspace-ui-' + serial + '-' + Date.now();
await mkdir(output, { recursive: true });
const hash = async (path) =>
  createHash('sha256')
    .update(await readFile(path))
    .digest('hex');
const call = (...args) =>
  execFileSync(adb, ['-s', serial, ...args], {
    encoding: 'utf8',
    timeout: 20_000,
  }).trim();
assert.equal(call('shell', 'getprop', 'sys.boot_completed'), '1');
const report = {
  phase: 'A3',
  scope: 'native-workspace-button-and-pull',
  serial,
  api: call('shell', 'getprop', 'ro.build.version.sdk'),
  build: call('shell', 'getprop', 'ro.build.fingerprint'),
  apkSha256: await hash(apk),
  toolSha256: await hash('scripts/mobile/android-workspace-ui-acceptance.mjs'),
  fixtureSha256: await hash('scripts/mobile/android-read-fixture.mjs'),
  status: 'running',
};
const pkg = 'com.easyai.agenthub.android.debug';
const xmlPath = '/sdcard/agenthub-workspace-acceptance.xml';
function dump() {
  call('shell', 'uiautomator', 'dump', xmlPath);
  const xml = call('shell', 'cat', xmlPath);
  return {
    xml,
    nodes: (xml.match(/<node\b[^>]*>/g) ?? []).map((node) =>
      Object.fromEntries(
        [...node.matchAll(/([\w-]+)="([^"]*)"/g)].map((m) => [
          m[1],
          m[2].replace(
            /&(amp|quot|apos|lt|gt);/g,
            (_, value) => ({ amp: '&', quot: '"', apos: "'", lt: '<', gt: '>' })[value],
          ),
        ]),
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
  throw new Error('Expected native workspace state was not reached');
}
async function click(predicate) {
  const state = await wait((s) => s.nodes.some(predicate));
  const [, x1, y1, x2, y2] = state.nodes
    .find(predicate)
    .bounds.match(/^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/)
    .map(Number);
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
const baseFixture = await startNativeReadFixture();
let workspaceUnavailable = false;
const requests = [];
// Keep this fault proxy separate from the shared Android/iOS reading fixture.
// Availability changes only the HTTP response; every App action is real UI input.
const proxy = createServer(async (request, response) => {
  const url = new URL(request.url, 'http://fixture.invalid');
  const record = {
    method: request.method,
    path: url.pathname,
    query: Object.fromEntries(url.searchParams),
    status: null,
  };
  requests.push(record);
  const send = (status, body) => {
    record.status = status;
    response.writeHead(status, { 'Content-Type': 'application/json' });
    response.end(body);
  };
  if (request.method !== 'GET') {
    send(405, JSON.stringify({ error: 'readonly_only' }));
    return;
  }
  if (workspaceUnavailable && url.pathname === '/v1/workspace') {
    send(503, JSON.stringify({ error: 'Fixture refresh unavailable' }));
    return;
  }
  try {
    const result = await fetch(baseFixture.endpoint + request.url, { redirect: 'manual' });
    send(result.status, Buffer.from(await result.arrayBuffer()));
  } catch {
    send(502, JSON.stringify({ error: 'Fixture upstream unavailable' }));
  }
});
await new Promise((resolve, reject) => {
  proxy.once('error', reject);
  proxy.listen(0, '127.0.0.1', resolve);
});
const proxyPort = proxy.address().port;
call('reverse', 'tcp:' + proxyPort, 'tcp:' + proxyPort);
const fixture = {
  endpoint: 'http://127.0.0.1:' + proxyPort,
  requests,
  async close() {
    call('reverse', '--remove', 'tcp:' + proxyPort);
    proxy.closeAllConnections();
    await new Promise((resolve) => proxy.close(resolve));
    await baseFixture.close();
  },
};
const workspaceRequests = () => fixture.requests.filter((r) => r.path === '/v1/workspace');
try {
  call('shell', 'settings', 'put', 'system', 'font_scale', '1.0');
  call('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0');
  call('shell', 'settings', 'put', 'system', 'user_rotation', '0');
  call('install', '-r', '-t', apk);
  call('shell', 'pm', 'clear', pkg);
  call('shell', 'am', 'start', '-W', '-n', pkg + '/com.agenthub.android.MainActivity');
  await click((n) => n.text === '添加客户端');
  await click((n) => n.text === '连接方式');
  await click((n) => n.text === '手动连接');
  await click((n) => n.class === 'android.widget.EditText' && n.text.startsWith('http'));
  // keycombination is unavailable on API26. Native cursor/delete keys work on both.
  call('shell', 'input', 'keyevent', '123', ...Array(80).fill('67'));
  call('shell', 'input', 'text', fixture.endpoint);
  await wait((s) =>
    s.nodes.some((n) => n.class === 'android.widget.EditText' && n.text === fixture.endpoint),
  );
  call('shell', 'input', 'keyevent', '4');
  await click((n) => n.text === '连接客户端');
  await wait((s) => s.nodes.some((n) => n.text === '已连接'));
  if (report.api === '26') {
    await wait(() => /mInputShown=false/.test(call('shell', 'dumpsys', 'input_method')));
    report.formKeyboardDismissed = true;
  }
  await click((n) => n.text === '完成');
  await click((n) => n['content-desc'] === '打开侧栏');
  let state = await wait((s) => s.nodes.some((n) => n.text === '只读界面验收'));
  await capture('workspace-native-initial', state);
  const size = call('shell', 'wm', 'size').match(/Physical size: (\d+)x(\d+)/);
  assert(size);
  const width = Number(size[1]),
    height = Number(size[2]);
  const swipe = (start, end) =>
    call(
      'shell',
      'input',
      'swipe',
      String(Math.round(width * 0.4)),
      String(Math.round(height * start)),
      String(Math.round(width * 0.4)),
      String(Math.round(height * end)),
      '450',
    );
  for (let count = 0; count < 8 && !state.nodes.some((n) => n.text === '加载更多'); count++) {
    swipe(0.72, 0.3);
    state = dump();
  }
  assert(
    state.nodes.some((n) => n.text === '加载更多'),
    'Native pagination button is visible',
  );
  const beforeMore = workspaceRequests().length;
  await click((n) => n.text === '加载更多');
  state = await wait(() =>
    workspaceRequests()
      .slice(beforeMore)
      .some((r) => r.query.projectCursor === 'projects-next'),
  );
  // LazyColumn only exposes visible rows; smaller API26 screens need a real scroll.
  for (let count = 0; count < 8 && !state.nodes.some((n) => n.text === '项目 5'); count++) {
    swipe(0.72, 0.3);
    state = dump();
  }
  assert(
    state.nodes.some((n) => n.text === '项目 5'),
    'Last native paged project is visible',
  );
  assert(
    workspaceRequests()
      .slice(beforeMore)
      .some(
        (r) => r.query.projectCursor === 'projects-next' && r.query.threadCursor === 'threads-next',
      ),
  );
  assert(!state.nodes.some((n) => n.text === '加载更多'), 'Exhausted workspace hides pagination');
  await capture('workspace-native-paged', state);
  for (let count = 0; count < 8 && !state.nodes.some((n) => n.text === '最近'); count++) {
    swipe(0.35, 0.78);
    state = dump();
  }
  assert(
    state.nodes.some((n) => n.text === '最近'),
    'Pull starts at the native list top',
  );
  const beforePull = workspaceRequests().length;
  swipe(0.28, 0.75);
  state = await wait(() => workspaceRequests().length > beforePull);
  for (let count = 0; count < 8 && !state.nodes.some((n) => n.text === '加载更多'); count++) {
    swipe(0.72, 0.3);
    state = dump();
  }
  assert(
    state.nodes.some((n) => n.text === '加载更多'),
    'Refreshed native workspace has a next page',
  );
  const afterPull = workspaceRequests().slice(beforePull);
  assert(afterPull.some((r) => !r.query.projectCursor && !r.query.threadCursor));
  assert(!state.nodes.some((n) => n.text === '项目 5'), 'Refresh replaces the paged workspace');
  await capture('workspace-native-pull-refreshed', state);
  for (let count = 0; count < 8 && !state.nodes.some((n) => n.text === '最近'); count++) {
    swipe(0.35, 0.78);
    state = dump();
  }
  assert(
    state.nodes.some((n) => n.text === '最近'),
    'Failure pull starts at the native list top',
  );
  workspaceUnavailable = true;
  const beforeFailure = workspaceRequests().length;
  swipe(0.28, 0.75);
  state = await wait(
    (s) =>
      s.nodes.some((n) => n.text === '无法完成操作') &&
      s.nodes.some((n) => n.text.startsWith('刷新失败，请重试。')),
  );
  assert(
    workspaceRequests()
      .slice(beforeFailure)
      .some((r) => r.status === 503),
    'Native pull actually received HTTP 503',
  );
  await capture('workspace-native-pull-error', state);
  await click((n) => n.text === '好');
  state = await wait(
    (s) =>
      s.nodes.some((n) => n.text === '只读界面验收') &&
      !s.nodes.some((n) => n.text === '无法完成操作'),
  );
  assert(
    !state.nodes.some((n) => n.text === '项目 5'),
    'Failed refresh retains the original workspace',
  );
  await wait(() => /mInputShown=false/.test(call('shell', 'dumpsys', 'input_method')));
  report.errorKeyboardDismissed = true;
  await capture('workspace-native-error-cache-retained', state);
  workspaceUnavailable = false;
  const beforeRetry = workspaceRequests().length;
  swipe(0.28, 0.75);
  state = await wait(
    (s) =>
      workspaceRequests()
        .slice(beforeRetry)
        .some((r) => r.status === 200) &&
      s.nodes.some((n) => n.text === '只读界面验收') &&
      !s.nodes.some((n) => n.text === '无法完成操作'),
  );
  await capture('workspace-native-pull-retried', state);
  report.status = 'passed';
  report.cases = 4;
  report.actualNativeButton = true;
  report.actualNativePull = true;
  report.actualNativeError = true;
  report.actualNativeRetry = true;
  report.injectedSessionCalls = false;
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  try {
    await capture('failure');
  } catch {
    /* Keep the original failure. */
  }
} finally {
  report.requests = fixture.requests;
  report.nonGetRequests = fixture.requests.filter((r) => r.method !== 'GET').length;
  call('shell', 'am', 'force-stop', pkg);
  await fixture.close();
  await writeFile(output + '/report.json', JSON.stringify(report, null, 2) + '\n');
}
console.log('Native workspace UI evidence:', output, report.status);
assert.equal(report.nonGetRequests, 0);
assert.equal(report.status, 'passed');
