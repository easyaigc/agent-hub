// Runs the full product candidate against an already-started local production Host and Vite control server.
// DEV_PREVIEW_CONTROL_URL must expose /control/{config,change,closed} for DevPreviewUITests.
import assert from 'node:assert/strict';
import { randomUUID, randomBytes, createHash } from 'node:crypto';
import { spawn, execFileSync } from 'node:child_process';
import {
  mkdir,
  cp,
  rm,
  readFile,
  writeFile,
  copyFile,
  readdir,
  statfs,
  lstat,
  readlink,
} from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import { join, resolve } from 'node:path';
import { createServer } from 'node:http';
import { pathToFileURL } from 'node:url';
import { isolateMainUUID } from './mobile/isolated-ios-runner.mjs';

let control;
const root = resolve('.local/dev-preview-acceptance');
const run = join(root, `run-${randomUUID()}`);
const env = { ...process.env, DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer' };
const xcrun = (...args) => execFileSync('xcrun', args, { env, encoding: 'utf8' }).trim();
const invoke = (bin, args) => execFileSync(bin, args, { env, encoding: 'utf8' });
async function exec(command, args, log) {
  return new Promise((done, reject) => {
    const out = createWriteStream(log);
    const child = spawn(command, args, { env, stdio: ['ignore', 'pipe', 'pipe'] });
    let outputError;
    out.on('error', (error) => {
      outputError = error;
      child.kill('SIGTERM');
    });
    child.stdout.pipe(out);
    child.stderr.pipe(out);
    child.on('error', reject);
    child.on('close', (code) => {
      if (outputError) reject(outputError);
      else out.end(() => done(code));
    });
  });
}
async function fingerprint() {
  const files = [];
  async function walk(dir) {
    for (const e of await readdir(dir, { withFileTypes: true })) {
      const p = join(dir, e.name);
      if (e.isDirectory()) await walk(p);
      else if (e.isFile()) files.push(p);
    }
  }
  for (const d of [
    'apps/ios/Sources',
    'apps/ios/Resources',
    'apps/ios/CodexManage.xcodeproj',
    'apps/ios/FilePreviewKit',
  ])
    await walk(d);
  files.push('apps/ios/Tests/DevPreviewUITests.swift', 'scripts/dev-preview-ios-acceptance.mjs');
  const hash = createHash('sha256');
  for (const f of files.sort()) {
    hash.update(f);
    hash.update(await readFile(f));
  }
  return hash.digest('hex');
}
// Hash the signed bundle tree with relative paths, entry types, modes and file bytes.
async function bundleTreeHash(base) {
  const hash = createHash('sha256');
  async function walk(relative) {
    const path = join(base, relative);
    const info = await lstat(path);
    const type = info.isSymbolicLink() ? 'link' : info.isDirectory() ? 'dir' : 'file';
    hash.update(JSON.stringify([relative, type, info.mode & 0o7777]) + '\n');
    if (type === 'link') hash.update(await readlink(path));
    else if (type === 'file') hash.update(await readFile(path));
    else for (const name of (await readdir(path)).sort()) await walk(join(relative, name));
  }
  await walk('');
  return hash.digest('hex');
}
await mkdir(run, { recursive: true, mode: 0o700 });
let simulator, host, vite, previews;
const result = {
  startedAt: new Date().toISOString(),
  run,
  execution: 'complete product build; production Host and real Vite are externally supplied',
  modelSettings: 'entry point does not expose actual model or speed settings',
};
try {
  let stats;
  if (process.env.DEV_PREVIEW_CONTROL_URL) {
    control = process.env.DEV_PREVIEW_CONTROL_URL;
    assert(new URL(control).hostname === '127.0.0.1', 'External test control must be loopback');
    result.services = {
      host: 'external real Electron EasyWork; controller validates service evidence',
      control: 'private loopback test control',
    };
  } else {
    const { DevPreviews } = await import('../apps/host-agent/dist/dev-previews.js');
    const viteEntry = process.env.DEV_PREVIEW_VITE_ENTRY;
    assert(viteEntry, 'DEV_PREVIEW_VITE_ENTRY must point to an existing Vite node entry');
    const { createServer: createVite, version: viteVersion } = await import(
      pathToFileURL(viteEntry)
    );
    const site = join(run, 'vite-site');
    await mkdir(site);
    const nonce = randomBytes(8).toString('hex');
    const token = randomBytes(32).toString('hex');
    const target = {
      agentId: 'codex',
      hostId: 'dev-preview-acceptance',
      workspaceId: 'dev-preview-workspace',
      runtimeId: 'dev-preview-runtime',
    };
    const threadId = 'dev-preview-thread';
    const { default: WebSocket } = await import('../apps/host-agent/node_modules/ws/wrapper.mjs');
    stats = { html: 0, api: 0, viteUpgrades: 0, cookiesLeaked: false, createId: null };
    const source = (suffix) =>
      `document.getElementById('page').textContent='PAGE-${nonce}-${suffix}';\nif (import.meta.hot) import.meta.hot.accept();\n`;
    await writeFile(
      join(site, 'index.html'),
      `<html><body><div id="page"></div><button id="api">Call API</button><div id="response"></div><script type="module" src="/main.js"></script><script>document.querySelector('#api').onclick=async()=>document.querySelector('#response').textContent=await (await fetch('/api')).text()</script></body></html>`,
    );
    await writeFile(join(site, 'main.js'), source('A'));
    vite = await createVite({
      root: site,
      configFile: false,
      server: { host: '127.0.0.1', port: 0, strictPort: false },
      plugins: [
        {
          name: 'acceptance-api',
          configureServer(server) {
            server.middlewares.use((req, res, next) => {
              if (req.headers.cookie?.includes('__agent_hub_preview=')) stats.cookiesLeaked = true;
              if (req.url === '/') stats.html++;
              if (req.url === '/api') {
                stats.api++;
                res.end(`API-${nonce}`);
              } else next();
            });
          },
        },
      ],
    });
    await vite.listen();
    vite.httpServer.on('upgrade', () => stats.viteUpgrades++);
    const vitePort = vite.httpServer.address().port;
    previews = new DevPreviews({
      target: () => target,
      authorize(req) {
        if (req.headers.authorization !== `Bearer ${token}` || req.headers.origin)
          throw new Error('unauthorized');
        return req.headers.authorization;
      },
      async scope(id) {
        if (id !== threadId) throw new Error('wrong thread');
      },
    });
    host = createServer((req, res) => {
      void (async () => {
        const url = new URL(req.url, 'http://127.0.0.1');
        const send = (code, value) => {
          res.writeHead(code, { 'content-type': 'application/json' });
          res.end(JSON.stringify(value));
        };
        if (url.pathname === '/health')
          return send(200, { capabilities: ['health', 'runtime:read', 'preview:http:v1'] });
        if (url.pathname === '/control/config')
          return send(200, {
            nonce,
            appConfig: {
              endpoint: control,
              token,
              target,
              threadId,
              url: `http://127.0.0.1:${vitePort}/`,
            },
          });
        if (url.pathname === '/control/change' && req.method === 'POST') {
          const before = { html: stats.html, api: stats.api, upgrades: stats.viteUpgrades };
          const query = new URLSearchParams({
            hostId: target.hostId,
            target: JSON.stringify(target),
            id: stats.createId,
          });
          stats.originRejected = await new Promise((resolvePromise) => {
            const socket = new WebSocket(
              `ws://127.0.0.1:${host.address().port}/v1/dev-previews/connect?${query}`,
              { headers: { Authorization: `Bearer ${token}`, Origin: 'http://evil.invalid' } },
            );
            socket.on('open', () => {
              socket.terminate();
              resolvePromise(false);
            });
            socket.on('error', () => resolvePromise(true));
          });
          stats.originNoUpstream =
            before.html === stats.html &&
            before.api === stats.api &&
            before.upgrades === stats.viteUpgrades;
          stats.htmlBeforeHMR = stats.html;
          await writeFile(join(site, 'main.js'), source('B'));
          return send(200, { ok: true });
        }
        if (url.pathname === '/control/closed' && req.method === 'POST') {
          await new Promise((resolvePromise) => setTimeout(resolvePromise, 1000));
          const before = { html: stats.html, api: stats.api, upgrades: stats.viteUpgrades };
          const query = new URLSearchParams({
            hostId: target.hostId,
            target: JSON.stringify(target),
            id: stats.createId,
          });
          stats.revoked = await new Promise((resolvePromise) => {
            const socket = new WebSocket(
              `ws://127.0.0.1:${host.address().port}/v1/dev-previews/connect?${query}`,
              { headers: { Authorization: `Bearer ${token}` } },
            );
            socket.on('open', () => {
              socket.terminate();
              resolvePromise(false);
            });
            socket.on('error', () => resolvePromise(true));
          });
          stats.revokedNoUpstream =
            before.html === stats.html &&
            before.api === stats.api &&
            before.upgrades === stats.viteUpgrades;
          stats.afterClose = { ...previews.counters };
          return send(200, { ok: true });
        }
        if (url.pathname === '/v1/dev-previews' && req.method === 'POST') {
          let text = '';
          req.on('data', (chunk) => {
            text += chunk;
          });
          req.on('end', () => {
            try {
              stats.createId = JSON.parse(text).id;
            } catch {
              // Invalid diagnostic input does not affect request handling.
            }
          });
        }
        if (await previews.control(req, res, url)) return;
        send(404, { error: 'not found' });
      })().catch((error) => {
        if (!res.headersSent) {
          res.writeHead(error.status ?? 500);
          res.end();
        }
      });
    });
    previews.attach(host);
    await new Promise((ok, fail) => {
      host.once('error', fail);
      host.listen(0, '127.0.0.1', ok);
    });
    control = `http://127.0.0.1:${host.address().port}`;
    result.services = {
      host: 'thin HTTP routing; production DevPreviews/control/attach and production devListenerIdentity; fixed test thread scope; no RuntimeSession or Electron',
      vite: viteVersion,
      vitePort,
    };
  }
  const disk = await statfs(root);
  result.freeBytesBefore = disk.bavail * disk.bsize;
  const minFree = process.env.DEV_PREVIEW_DERIVED_PATH ? 1024 ** 3 : 3 * 1024 ** 3;
  assert(
    result.freeBytesBefore >= minFree,
    `At least ${minFree} bytes free are required for the selected build`,
  );
  result.fingerprintBefore = await fingerprint();
  const derived = process.env.DEV_PREVIEW_DERIVED_PATH ?? join(run, 'DerivedData');
  result.derivedData = process.env.DEV_PREVIEW_DERIVED_PATH
    ? 'user-authorized shared build cache; retained'
    : 'task-owned';
  const buildArgs = [
    '-project',
    'apps/ios/CodexManage.xcodeproj',
    '-scheme',
    'CodexManage',
    '-sdk',
    'iphonesimulator',
    '-destination',
    'generic/platform=iOS Simulator',
    '-derivedDataPath',
    derived,
    'CODE_SIGNING_ALLOWED=YES',
    'CODE_SIGN_IDENTITY=-',
    'ARCHS=arm64',
    'ONLY_ACTIVE_ARCH=YES',
    'build',
  ];
  result.buildExitCode = await exec('xcodebuild', buildArgs, join(run, 'build.log'));
  assert.equal(result.buildExitCode, 0, 'product build failed');
  const sourceApp = join(derived, 'Build/Products/Debug-iphonesimulator/CodexManage.app');
  const app = join(run, 'Acceptance.app');
  const runner = join(run, 'Runner.app');
  const test = join(runner, 'PlugIns/DevPreviewUITests.xctest');
  await cp(sourceApp, app, { recursive: true });
  result.productBinarySHA256 = createHash('sha256')
    .update(await readFile(join(app, 'CodexManage')))
    .digest('hex');
  await rm(join(app, 'PlugIns'), { recursive: true, force: true });
  invoke('/usr/libexec/PlistBuddy', [
    '-c',
    'Set :CFBundleIdentifier com.easyai.agenthub.devpreview.acceptance',
    join(app, 'Info.plist'),
  ]);
  async function thin(path, identity) {
    const archs = invoke('lipo', ['-archs', path]).trim().split(/\s+/);
    assert(archs.includes('arm64'));
    if (archs.length > 1) {
      invoke('lipo', [path, '-thin', 'arm64', '-output', path + '.thin']);
      await copyFile(path + '.thin', path);
      await rm(path + '.thin');
    }
    await writeFile(path, isolateMainUUID(await readFile(path), identity).buffer);
  }
  await thin(
    join(app, 'CodexManage'),
    'com.easyai.agenthub.devpreview.acceptance.uitests.xctrunner',
  );
  invoke('codesign', ['--force', '--sign', '-', app]);
  result.acceptanceBinarySHA256 = createHash('sha256')
    .update(await readFile(join(app, 'CodexManage')))
    .digest('hex');
  result.acceptanceBundleTreeSHA256 = await bundleTreeHash(app);
  await cp(
    '/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/Library/Xcode/Agents/XCTRunner.app',
    runner,
    { recursive: true },
  );
  for (const [key, value] of Object.entries({
    CFBundleIdentifier: 'com.easyai.agenthub.devpreview.acceptance.uitests.xctrunner',
    CFBundleExecutable: 'XCTRunner',
    CFBundleName: 'DevPreviewRunner',
  }))
    invoke('/usr/libexec/PlistBuddy', ['-c', `Set :${key} ${value}`, join(runner, 'Info.plist')]);
  await thin(
    join(runner, 'XCTRunner'),
    'com.easyai.agenthub.devpreview.acceptance.uitests.xctrunner',
  );
  const platformDeveloper =
    '/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer';
  const frameworks = join(runner, 'Frameworks');
  await mkdir(frameworks, { recursive: true });
  for (const name of ['XCTest', 'XCUIAutomation', 'Testing', '_Testing_Foundation'])
    await cp(
      join(platformDeveloper, 'Library/Frameworks', `${name}.framework`),
      join(frameworks, `${name}.framework`),
      { recursive: true },
    );
  for (const name of ['XCTestCore', 'XCTestSupport', 'XCTAutomationSupport', 'ResultDataPublisher'])
    await cp(
      join(platformDeveloper, 'Library/PrivateFrameworks', `${name}.framework`),
      join(frameworks, `${name}.framework`),
      { recursive: true },
    );
  for (const name of ['lib_TestingInterop.dylib', 'libXCTestSwiftSupport.dylib'])
    await cp(join(platformDeveloper, 'usr/lib', name), join(frameworks, name));
  for (const name of await readdir(frameworks))
    invoke('codesign', ['--force', '--sign', '-', join(frameworks, name)]);
  await mkdir(test, { recursive: true });
  await writeFile(
    join(test, 'Info.plist'),
    JSON.stringify({
      CFBundleIdentifier: 'com.easyai.agenthub.devpreview.acceptance.uitests',
      CFBundleExecutable: 'DevPreviewUITests',
      CFBundleName: 'DevPreviewUITests',
      CFBundlePackageType: 'BNDL',
      CFBundleVersion: '1',
    }),
  );
  invoke('plutil', ['-convert', 'xml1', join(test, 'Info.plist')]);
  const platform =
    '/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer';
  result.compileExitCode = await exec(
    'xcrun',
    [
      'swiftc',
      '-emit-library',
      '-module-name',
      'DevPreviewUITests',
      '-target',
      'arm64-apple-ios17.0-simulator',
      '-sdk',
      xcrun('--sdk', 'iphonesimulator', '--show-sdk-path'),
      '-F',
      join(platform, 'Library/Frameworks'),
      '-I',
      join(platform, 'usr/lib'),
      '-L',
      join(platform, 'usr/lib'),
      '-lXCTestSwiftSupport',
      '-Xlinker',
      '-rpath',
      '-Xlinker',
      '@loader_path/Frameworks',
      'apps/ios/Tests/DevPreviewUITests.swift',
      '-o',
      join(test, 'DevPreviewUITests'),
    ],
    join(run, 'compile.log'),
  );
  assert.equal(result.compileExitCode, 0, 'test compilation failed');
  invoke('codesign', ['--force', '--sign', '-', test]);
  invoke('codesign', ['--force', '--sign', '-', runner]);
  const config = {
    DevPreviewUITests: {
      IsUITestBundle: true,
      IsXCTRunnerHostedTestBundle: true,
      DependentProductPaths: [app, runner, test],
      ProductModuleName: 'DevPreviewUITests',
      TestBundlePath: test,
      TestHostPath: runner,
      TestHostBundleIdentifier: 'com.easyai.agenthub.devpreview.acceptance.uitests.xctrunner',
      UITargetAppPath: app,
      UITargetAppBundleIdentifier: 'com.easyai.agenthub.devpreview.acceptance',
      EnvironmentVariables: { DEV_PREVIEW_CONTROL_URL: control },
      TestingEnvironmentVariables: { DEV_PREVIEW_CONTROL_URL: control },
      OnlyTestIdentifiers: ['DevPreviewUITests/testPreviewPageApiHMRAndClose'],
    },
    __xctestrun_metadata__: { FormatVersion: 1 },
  };
  const xctestrun = join(run, 'DevPreview.xctestrun');
  await writeFile(xctestrun, JSON.stringify(config));
  invoke('plutil', ['-convert', 'xml1', xctestrun]);
  const runtime = JSON.parse(xcrun('simctl', 'list', 'runtimes', '--json')).runtimes.find(
    (x) => x.isAvailable && x.identifier.startsWith('com.apple.CoreSimulator.SimRuntime.iOS-'),
  );
  assert(runtime, 'No available iOS simulator runtime');
  simulator = xcrun(
    'simctl',
    'create',
    `Dev Preview Acceptance ${randomUUID()}`,
    'com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro',
    runtime.identifier,
  );
  result.simulator = simulator;
  xcrun('simctl', 'boot', simulator);
  xcrun('simctl', 'bootstatus', simulator, '-b');
  result.testExitCode = await exec(
    'xcodebuild',
    [
      'test-without-building',
      '-xctestrun',
      xctestrun,
      '-destination',
      `platform=iOS Simulator,id=${simulator}`,
      '-resultBundlePath',
      join(run, 'result.xcresult'),
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
    ],
    join(run, 'xcodebuild.log'),
  );
  result.fingerprintAfter = await fingerprint();
  result.fingerprintStable = result.fingerprintBefore === result.fingerprintAfter;
  result.stats = stats ? { ...stats, host: { ...previews.counters } } : { externalServices: true };
  result.testPassed =
    result.testExitCode === 0 &&
    result.fingerprintStable &&
    (!stats ||
      (stats.api > 0 &&
        stats.viteUpgrades > 0 &&
        previews.counters.http > 0 &&
        previews.counters.upgrades > 0 &&
        stats.html === stats.htmlBeforeHMR &&
        !stats.cookiesLeaked &&
        stats.originRejected &&
        stats.originNoUpstream &&
        stats.revoked &&
        stats.revokedNoUpstream));
  try {
    result.xcresultSummary = JSON.parse(
      xcrun(
        'xcresulttool',
        'get',
        'test-results',
        'summary',
        '--path',
        join(run, 'result.xcresult'),
        '--format',
        'json',
      ),
    );
  } catch {
    // A missing result summary is reported by the enclosing test result.
  }
} catch (error) {
  result.error = String(error.stack || error);
} finally {
  if (previews) previews.close();
  if (host) {
    host.closeAllConnections();
    await new Promise((resolvePromise) => host.close(resolvePromise));
  }
  if (vite) await vite.close();
  if (simulator) {
    try {
      xcrun('simctl', 'shutdown', simulator);
    } catch {
      // Deletion below also handles an already stopped simulator.
    }
    try {
      xcrun('simctl', 'delete', simulator);
      result.simulatorDeleted = true;
    } catch (error) {
      result.simulatorDeleted = false;
      result.simulatorCleanupError = String(error);
    }
  }
  // Keep failures for diagnosis; the owner must remove this task-specific directory after saving redacted evidence.
  await writeFile(join(root, 'latest-report.json'), JSON.stringify(result, null, 2), {
    mode: 0o600,
  });
  console.log(JSON.stringify(result));
}
if (!result.testPassed) process.exitCode = 1;
