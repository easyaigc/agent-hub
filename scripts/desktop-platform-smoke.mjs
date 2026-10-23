import assert from 'node:assert/strict';
import { cp, mkdir, mkdtemp, readFile, realpath, rm, stat, writeFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { inspectDesktopArchive } from '../packages/codex-adapter/dist/desktop-platform.js';

const directory = await realpath(await mkdtemp(join(tmpdir(), 'desktop-platform-')));
const path = join(directory, 'fixture.asar');
const archive = (profile, mutate = () => {}, version = '26.1002.52244') => {
  const packageBytes = Buffer.from(JSON.stringify({ version }));
  const moduleBytes = Buffer.from(profile);
  const entries = {
    files: {
      'package.json': { offset: '0', size: packageBytes.length },
      '.vite': {
        files: {
          build: {
            files: {
              'profile.js': { offset: String(packageBytes.length), size: moduleBytes.length },
            },
          },
        },
      },
    },
  };
  mutate(entries);
  const json = Buffer.from(JSON.stringify(entries));
  const headerSize = 8 + Math.ceil(json.length / 4) * 4;
  const header = Buffer.alloc(8 + headerSize);
  header.writeUInt32LE(4, 0);
  header.writeUInt32LE(headerSize, 4);
  header.writeUInt32LE(headerSize - 4, 8);
  header.writeUInt32LE(json.length, 12);
  json.copy(header, 16);
  return Buffer.concat([header, packageBytes, moduleBytes]);
};
const profile =
  'win32 codex-ipc ipc.sock {"thread-stream-state-changed":11,"thread-follower-start-turn":2,"thread-follower-interrupt-turn":4}';
try {
  await writeFile(path, archive(profile));
  const result = inspectDesktopArchive(path);
  assert.equal(result.version, '26.1002.52244');
  assert.match(result.asarSha256, /^[a-f0-9]{64}$/);
  await writeFile(path, archive(profile, undefined, '26.928.20755'));
  assert.equal(inspectDesktopArchive(path).version, '26.928.20755');
  await writeFile(path, archive(profile, undefined, null));
  assert.equal(inspectDesktopArchive(path).version, '');
  await writeFile(path, archive(profile.replace(':11', ':12')));
  assert.throws(() => inspectDesktopArchive(path), /profile not recognized/);
  await writeFile(
    path,
    archive(profile, (tree) => {
      tree.files['package.json'].offset = '-100';
    }),
  );
  assert.throws(() => inspectDesktopArchive(path), /Invalid archive entry/);
  await writeFile(
    path,
    archive(profile, (tree) => {
      tree.files['package.json'].link = 'outside';
    }),
  );
  assert.throws(() => inspectDesktopArchive(path), /Unsupported archive entry/);
  await writeFile(path, archive(profile).subarray(0, 20));
  assert.throws(() => inspectDesktopArchive(path), /archive header/);
  if (process.platform === 'darwin') {
    // Exercise the real startup script in an isolated copied repository.
    // Mock launchctl and version discovery: never touch a user's actual service.
    await mkdir(join(directory, 'scripts'));
    await mkdir(join(directory, 'packages/codex-adapter/dist'), { recursive: true });
    await writeFile(join(directory, 'package.json'), '{"type":"module"}');
    await cp(
      new URL('./desktop-service.mjs', import.meta.url),
      join(directory, 'scripts/desktop-service.mjs'),
    );
    for (const name of [
      'desktop-platform',
      'desktop-private-state-native',
      'desktop-private-state-native-worker',
      'windows-process',
    ])
      await cp(
        new URL('../packages/codex-adapter/dist/' + name + '.js', import.meta.url),
        join(directory, 'packages/codex-adapter/dist', name + '.js'),
      );
    const mock = join(directory, 'service-fixture.mjs');
    await writeFile(
      mock,
      `import childProcess from 'node:child_process';
import os from 'node:os';
import { existsSync, readFileSync, writeFileSync, unlinkSync } from 'node:fs';
import { syncBuiltinESMExports } from 'node:module';
const fixtureHome = ${JSON.stringify(join(directory, 'home'))};
const loaded = ${JSON.stringify(join(directory, 'fixture-loaded'))};
const disabled = ${JSON.stringify(join(directory, 'fixture-disabled'))};
os.homedir = () => fixtureHome;
const originalExec = childProcess.execFileSync;
childProcess.execFileSync = (file, args) => {
  if (file === '/usr/bin/plutil') return originalExec(file, args);
  if (file === '/usr/libexec/PlistBuddy') {
    if (process.env.FIXTURE_MISSING_VERSION) throw Error('Version metadata unavailable');
    return '26.928.20755\\n';
  }
  if (file === '/bin/launchctl' && args[0] === 'print') {
    if (!existsSync(loaded)) throw Error('Fixture service absent');
    return 'state = running\\npid = 12345';
  }
  if (file === '/bin/launchctl' && args[0] === 'bootstrap') {
    if (existsSync(disabled)) throw Error('Fixture service disabled');
    if (!args[2].startsWith(fixtureHome + '/Library/LaunchAgents/'))
      throw Error('Bootstrap must use the persistent login registration');
    if (!readFileSync(args[2], 'utf8').includes('<key>KeepAlive</key><true/>'))
      throw Error('Missing crash recovery');
    writeFileSync(loaded, args[2]);
    return '';
  }
  if (file === '/bin/launchctl' && args[0] === 'enable') {
    if (args[1] !== 'gui/' + process.getuid() + '/com.codexmanage.host.desktop')
      throw Error('Must only enable this product service');
    if (existsSync(disabled)) unlinkSync(disabled);
    return '';
  }
  if (file === '/bin/launchctl' && args[0] === 'bootout') {
    unlinkSync(loaded);
    return '';
  }
  throw Error('Unexpected fixture child process');
};
syncBuiltinESMExports();
`,
    );
    const startup = spawnSync(
      process.execPath,
      ['--import', mock, join(directory, 'scripts/desktop-service.mjs'), 'start'],
      { encoding: 'utf8', env: { CODEX_DESKTOP_VERSION: 'old-build' }, timeout: 10_000 },
    );
    assert.equal(startup.status, 0, startup.stderr);
    assert.equal(JSON.parse(startup.stdout).running, true);
    assert.equal(JSON.parse(startup.stdout).loginAutostart, true);
    assert.equal(startup.stderr, '');
    const plist = await readFile(
      join(directory, '.local/desktop-runtime/host-agent.plist'),
      'utf8',
    );
    const loginPlist = join(
      directory,
      'home/Library/LaunchAgents/com.codexmanage.host.desktop.plist',
    );
    assert.equal(await readFile(loginPlist, 'utf8'), plist);
    assert.equal((await stat(loginPlist)).mode & 0o777, 0o600);
    assert.match(plist, /<key>CODEX_DESKTOP_VERSION<\/key><string>26\.928\.20755<\/string>/);
    const customEntry = join(directory, 'previous-private-entry.mjs');
    const prepared = join(directory, 'previous-private-bridge.json');
    await writeFile(customEntry, '');
    await writeFile(prepared, '{}', { mode: 0o600 });
    const savedPlist = plist
      .replace(
        /(<key>ProgramArguments<\/key><array><string>[^<]*<\/string><string>)[^<]*(<\/string>)/,
        '$1' + customEntry + '$2',
      )
      .replace(
        '<key>EnvironmentVariables</key><dict>',
        '<key>EnvironmentVariables</key><dict><key>CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG</key><string>' +
          prepared +
          '</string><key>CUSTOM_PRIVATE_SETTING</key><string>preserve-me</string>',
      )
      .replace(/(<key>HOST_AGENT_PORT<\/key><string>)[^<]*(<\/string>)/, '$18443$2');
    await writeFile(join(directory, '.local/desktop-runtime/host-agent.plist'), savedPlist);
    const restart = spawnSync(
      process.execPath,
      ['--import', mock, join(directory, 'scripts/desktop-service.mjs'), 'restart'],
      { encoding: 'utf8', env: {}, timeout: 10000 },
    );
    assert.equal(restart.status, 0, restart.stderr);
    const preserved = await readFile(
      join(directory, '.local/desktop-runtime/host-agent.plist'),
      'utf8',
    );
    assert.ok(preserved.includes('<string>' + customEntry + '</string>'));
    assert.ok(
      preserved.includes(
        '<key>CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG</key><string>' + prepared + '</string>',
      ),
    );
    assert.match(preserved, /<key>CUSTOM_PRIVATE_SETTING<\/key><string>preserve-me<\/string>/);
    assert.match(preserved, /<key>HOST_AGENT_PORT<\/key><string>8443<\/string>/);
    assert.equal(await readFile(loginPlist, 'utf8'), preserved);
    const loaded = join(directory, 'fixture-loaded');
    await rm(loaded); // Simulate a completed login session; registration must survive.
    const service = (action, environment = {}) =>
      spawnSync(
        process.execPath,
        ['--import', mock, join(directory, 'scripts/desktop-service.mjs'), action],
        { encoding: 'utf8', env: environment, cwd: directory, timeout: 10000 },
      );
    const afterLogout = service('status');
    assert.equal(afterLogout.status, 0, afterLogout.stderr);
    assert.deepEqual(JSON.parse(afterLogout.stdout), {
      service: 'com.codexmanage.host.desktop',
      installed: false,
      running: false,
      pid: null,
      loginAutostart: true,
    });
    await writeFile(join(directory, 'fixture-disabled'), 'disabled');
    const resumed = service('start');
    assert.equal(resumed.status, 0, resumed.stderr);
    assert.equal(JSON.parse(resumed.stdout).running, true);
    await assert.rejects(stat(join(directory, 'fixture-disabled')), { code: 'ENOENT' });
    const resumedPlist = await readFile(loginPlist, 'utf8');
    const decode = (xml) => {
      const converted = spawnSync('/usr/bin/plutil', ['-convert', 'json', '-o', '-', '-'], {
        input: xml,
        encoding: 'utf8',
      });
      assert.equal(converted.status, 0, converted.stderr);
      return JSON.parse(converted.stdout);
    };
    assert.deepEqual(decode(resumedPlist), decode(preserved));
    assert.equal(decode(resumedPlist).EnvironmentVariables.HOST_NOTIFICATION_RELAY_URL, undefined);
    assert.equal(
      decode(resumedPlist).EnvironmentVariables.HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE,
      undefined,
    );
    // Only a file reference is persisted; the service must not open the token file.
    const relayURL = 'https://notifications.example.invalid/push?name="fixture"&host=mac';
    const tokenFile = 'missing publisher token & fixture.txt';
    const rawToken = 'fixture-raw-publisher-token-must-not-be-copied';
    const notificationEnvironment = () => readFile(loginPlist, 'utf8').then(decode);
    const configure = service('restart', {
      HOST_NOTIFICATION_RELAY_URL: relayURL,
      HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE: tokenFile,
      HOST_NOTIFICATION_PUBLISHER_TOKEN: rawToken,
    });
    assert.equal(configure.status, 0, configure.stderr);
    const configured = await notificationEnvironment();
    assert.equal(configured.EnvironmentVariables.HOST_NOTIFICATION_RELAY_URL, relayURL);
    assert.equal(
      configured.EnvironmentVariables.HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE,
      join(directory, tokenFile),
    );
    assert.equal(configured.EnvironmentVariables.HOST_NOTIFICATION_PUBLISHER_TOKEN, undefined);
    assert.ok(!JSON.stringify(configured).includes(rawToken));
    assert.ok(!configure.stdout.includes(rawToken) && !configure.stderr.includes(rawToken));
    assert.ok(!configure.stdout.includes(relayURL) && !configure.stdout.includes(tokenFile));
    assert.equal(
      await readFile(join(directory, '.local/desktop-runtime/host-agent.plist'), 'utf8'),
      await readFile(loginPlist, 'utf8'),
    );
    assert.equal((await stat(loginPlist)).mode & 0o777, 0o600);
    const notificationRestart = service('restart');
    assert.equal(notificationRestart.status, 0, notificationRestart.stderr);
    assert.deepEqual(await notificationEnvironment(), configured);
    const updateURL = service('restart', { HOST_NOTIFICATION_RELAY_URL: relayURL + '&updated=1' });
    assert.equal(updateURL.status, 0, updateURL.stderr);
    const updated = (await notificationEnvironment()).EnvironmentVariables;
    assert.equal(updated.HOST_NOTIFICATION_RELAY_URL, relayURL + '&updated=1');
    assert.equal(updated.HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE, join(directory, tokenFile));
    assert.equal(updated.HOST_AGENT_PORT, '8443');
    assert.equal(updated.CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG, prepared);
    const updateFile = service('restart', {
      HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE: tokenFile + '.new',
    });
    assert.equal(updateFile.status, 0, updateFile.stderr);
    const updatedFile = (await notificationEnvironment()).EnvironmentVariables;
    assert.equal(updatedFile.HOST_NOTIFICATION_RELAY_URL, updated.HOST_NOTIFICATION_RELAY_URL);
    assert.equal(
      updatedFile.HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE,
      join(directory, tokenFile + '.new'),
    );
    const clearNotifications = service('restart', {
      HOST_NOTIFICATION_RELAY_URL: '',
      HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE: '',
    });
    assert.equal(clearNotifications.status, 0, clearNotifications.stderr);
    assert.equal(service('restart').status, 0);
    const cleared = (await notificationEnvironment()).EnvironmentVariables;
    assert.equal(cleared.HOST_NOTIFICATION_RELAY_URL, '');
    assert.equal(cleared.HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE, '');
    const withoutVersion = service('restart', { FIXTURE_MISSING_VERSION: '1' });
    assert.equal(withoutVersion.status, 0, withoutVersion.stderr);
    assert.equal(withoutVersion.stderr, '');
    assert.equal(JSON.parse(withoutVersion.stdout).running, true);
    assert.equal((await notificationEnvironment()).EnvironmentVariables.CODEX_DESKTOP_VERSION, '');
    assert.equal(service('restart').status, 0);
    const finalPlist = await readFile(loginPlist, 'utf8');
    const stopped = service('stop');
    assert.equal(stopped.status, 0, stopped.stderr);
    assert.equal(JSON.parse(stopped.stdout).loginAutostart, false);
    await assert.rejects(stat(loginPlist), { code: 'ENOENT' });
    assert.equal(
      await readFile(join(directory, '.local/desktop-runtime/host-agent.plist'), 'utf8'),
      finalPlist,
    );
    const stoppedAgain = service('stop');
    assert.equal(stoppedAgain.status, 0, stoppedAgain.stderr);
    assert.equal(JSON.parse(stoppedAgain.stdout).installed, false);
  }
  console.log(
    'Desktop platform boundaries passed: login registration, restart/config preservation, persistent stop, unknown-build startup and archive bounds/profile. Synthetic fixtures only.',
  );
} finally {
  await rm(directory, { recursive: true, force: true });
}
