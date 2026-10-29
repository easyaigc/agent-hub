import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createServer as netServer } from 'node:net';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import { resolve, join, basename } from 'node:path';
import { pathToFileURL } from 'node:url';
assert(process.versions.bun, 'Run the external EasyWork harness with Bun');
const arg = (name) => {
  const i = process.argv.indexOf(name);
  assert(i >= 0);
  return resolve(process.argv[i + 1]);
};
const desktop = arg('--desktop-root'),
  output = arg('--evidence');
await mkdir(output, { recursive: true, mode: 0o700 });
// The native test wrapper may leave the binding on the Node ABI; prepare the real Electron owner explicitly.
const native = spawnSync(
  process.execPath,
  [join(desktop, 'scripts/ensure-electron-native-deps.ts')],
  { cwd: desktop, env: process.env, stdio: 'inherit' },
);
assert.equal(native.status, 0, 'Electron native dependency preparation failed');
const load = (file) => import(pathToFileURL(join(desktop, file)).href);
const { prepareDevAcceptanceProfile, launchElectronDevAcceptance } = await load(
  'scripts/dev-acceptance-profile.ts',
);
async function port() {
  const s = netServer();
  await new Promise((r) => s.listen(0, '127.0.0.1', r));
  const p = s.address().port;
  await new Promise((r) => s.close(r));
  return p;
}
const [relayPort, lanPort, rendererPort, observerPort] = await Promise.all([
  port(),
  port(),
  port(),
  port(),
]);
const resumeIndex = process.argv.indexOf('--resume-from');
const previous =
  resumeIndex < 0
    ? undefined
    : JSON.parse(await readFile(resolve(process.argv[resumeIndex + 1]), 'utf8'));
if (previous) {
  assert.equal(previous.desktop, desktop);
  assert.equal(previous.identityScope, 'explicit-loopback-DEV-only');
  assert.equal(
    resolve(previous.profileRoot),
    join(desktop, '.devenv-acceptance', basename(previous.profileRoot)),
  );
  assert.match(basename(previous.profileRoot), /^usage-[a-f0-9-]+$/);
}
const profile = await prepareDevAcceptanceProfile({
  repositoryRoot: desktop,
  mode: 'persistent',
  persistentName: previous ? basename(previous.profileRoot) : 'usage-' + randomUUID().slice(0, 8),
  reportDirectory: join(desktop, 'release-e2e', 'usage'),
});
const authorization = randomUUID(),
  report = join(output, 'desktop.json'),
  controlPath = join(output, 'control.json');
const supervisorConfiguration = {
  repositoryRoot: desktop,
  profile,
  relayEnvironment: {
    ...process.env,
    PORT: String(relayPort),
    EASYAI_REMOTE_LOCAL_DEVELOPMENT: '1',
    EASYAI_REMOTE_LOCAL_STATE_PATH: join(profile.root, 'relay.json'),
  },
  desktopEnvironment: {
    EASYAI_BROWSER_AGENT_DEV_ACCEPTANCE_REPORT: report,
    EASYAI_BROWSER_AGENT_DEV_ACCEPTANCE_SCENARIO: 'codex-plus-real',
    EASYAI_REMOTE_ACCESS_URL: `http://127.0.0.1:${relayPort}`,
    EASYAI_EMBEDDED_HOST_PORT: String(lanPort),
    EASYAI_DESKTOP_DEV_RENDERER_PORT: String(rendererPort),
    EASYAI_DESKTOP_SKIP_STARTUP_RUNTIME_WARMUP: '1',
    EASYAI_CODEX_PLUS_CONTROL_PATH: controlPath,
    EASYAI_CODEX_PLUS_CONTROL_AUTHORIZATION: authorization,
  },
};
let relay, desktopProcess;
const supervisor = {
  start() {
    relay = spawn(process.execPath, ['apps/remote-access-service/src/server.ts'], {
      cwd: desktop,
      env: supervisorConfiguration.relayEnvironment,
      stdio: 'inherit',
    });
    desktopProcess = launchElectronDevAcceptance({
      repositoryRoot: desktop,
      profile,
      arguments: ['node_modules/electron-vite/bin/electron-vite.js', 'dev'],
      environment: supervisorConfiguration.desktopEnvironment,
      detached: true,
    });
  },
  async stop() {
    for (const child of [relay, desktopProcess]) {
      if (!child?.pid || child.exitCode !== null) continue;
      const done = new Promise((r) => child.once('exit', r));
      try {
        if (child === desktopProcess) process.kill(-child.pid, 'SIGTERM');
        else child.kill('SIGTERM');
      } catch (error) {
        if (error.code !== 'ESRCH') throw error;
      }
      await Promise.race([done, new Promise((r) => setTimeout(r, 5000))]);
    }
  },
};
const allowed = new Set([
  'desktop.usage-auth-login',
  'desktop.usage-auth-state',
  'desktop.usage-read',
  'lan.refresh-offer',
  'relay.refresh-offer',
  'lan.enable',
  'lan.disable',
]);
const server = createServer(async (req, res) => {
  const send = (status, value) => {
    res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' });
    res.end(JSON.stringify(value));
  };
  if (req.headers['x-easywork-acceptance'] !== authorization)
    return send(401, { error: 'unauthorized' });
  try {
    if (req.method === 'GET' && req.url === '/evidence')
      return send(200, JSON.parse(await readFile(report, 'utf8')));
    if (req.method === 'POST' && req.url === '/control') {
      let text = '';
      for await (const chunk of req) {
        text += chunk;
        if (text.length > 2048) throw Error('request-too-large');
      }
      const body = JSON.parse(text);
      assert(Object.keys(body).length === 1 && allowed.has(body.operation));
      const id = randomUUID();
      await writeFile(
        controlPath,
        JSON.stringify({ id, operation: body.operation, authorization }),
        { mode: 0o600 },
      );
      return send(200, { id, accepted: true });
    }
    return send(404, { error: 'not-found' });
  } catch (error) {
    return send(503, {
      error: error.code === 'ENOENT' ? 'desktop-starting' : 'acceptance-request-failed',
    });
  }
});
await new Promise((r) => server.listen(observerPort, '127.0.0.1', r));
await writeFile(
  join(output, 'context.json'),
  JSON.stringify(
    {
      version: 1,
      desktop,
      profileRoot: profile.root,
      userData: profile.userDataPath,
      workspace: profile.workspacePath,
      relayPort,
      lanPort,
      rendererPort,
      observerPort,
      authorization,
      identityScope: 'explicit-loopback-DEV-only',
      authFlow: 'official-desktop-auth-service; harness-does-not-extract-credentials',
      pid: process.pid,
    },
    null,
    2,
  ),
  { mode: 0o600 },
);
let closing = false;
const stop = async () => {
  if (closing) return;
  closing = true;
  await supervisor.stop();
  await new Promise((r) => server.close(r));
  await profile.release();
  process.exit(0);
};
process.once('SIGINT', () => void stop());
process.once('SIGTERM', () => void stop());
supervisor.start();
console.log(
  JSON.stringify({
    status: 'starting',
    ownedPorts: [relayPort, lanPort, rendererPort, observerPort],
  }),
);
