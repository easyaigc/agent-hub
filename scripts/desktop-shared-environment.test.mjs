import assert from 'node:assert/strict';
import {
  mkdtempSync,
  mkdirSync,
  writeFileSync,
  readFileSync,
  rmSync,
  statSync,
  realpathSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { execFileSync } from 'node:child_process';
import { sharedDesktopEnvironment } from './desktop-shared-environment.mjs';

function fixture(t) {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'shared-desktop-env-')));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const program = join(root, 'program'),
    state = join(root, 'state'),
    agents = join(root, 'agents');
  const dist = join(program, 'packages/codex-adapter/dist');
  mkdirSync(dist, { recursive: true });
  writeFileSync(
    join(dist, 'desktop-shared-launcher.js'),
    'export async function runDesktopSharedLauncher(executable, args) { console.log(JSON.stringify({executable,args,url:process.env.AGENT_HUB_SHARED_APP_SERVER_URL,owner:process.env.AGENT_HUB_SHARED_APP_SERVER_DIRECTORY})); }\n',
  );
  writeFileSync(join(program, 'package.json'), '{"type":"module"}');
  const environment = new Map(),
    calls = [];
  let registered = false;
  const run = (args) => {
    calls.push(args);
    const [action, key, value] = args;
    if (action === 'getenv') return environment.get(key) ?? '';
    if (action === 'setenv') environment.set(key, value);
    if (action === 'unsetenv') environment.delete(key);
    if (action === 'bootstrap') registered = true;
    if (action === 'bootout') registered = false;
    if (action === 'print' && !registered) throw Error('not registered');
    return '';
  };
  const api = sharedDesktopEnvironment({ state, agents, run });
  const options = { program, executable: process.execPath, url: 'ws://127.0.0.1:57443' };
  return { root, program, state, agents, environment, calls, api, options };
}

test('original-app configuration preserves cloud routing and never launches an app', (t) => {
  const f = fixture(t);
  assert.equal(f.api.status().installed, false);
  const result = f.api.install(f.options);
  assert.equal(result.registered, true);
  assert.equal(result.futureLaunchEnvironmentReady, true);
  assert.equal(result.desktopProcessesStarted, 0);
  assert.deepEqual([...f.environment.keys()], ['CODEX_CLI_PATH']);
  assert.equal(f.environment.has('CODEX_APP_SERVER_FORCE_CLI'), false);
  const wrapper = f.environment.get('CODEX_CLI_PATH');
  assert.equal(statSync(wrapper).mode & 0o777, 0o700);
  const output = JSON.parse(execFileSync(wrapper, ['--version'], { encoding: 'utf8' }));
  assert.deepEqual(output.args, ['--version']);
  assert.equal(output.url, f.options.url);
  assert.equal(output.owner, join(f.state, 'service'));
  assert.equal(
    f.calls.some((args) => args.some((arg) => ['open', 'kill', 'CODEX_HOME'].includes(arg))),
    false,
  );
  assert.equal(f.api.install(f.options).futureLaunchEnvironmentReady, true);
  const plist = readFileSync(
    join(f.agents, 'com.codexmanage.shared-desktop-environment.plist'),
    'utf8',
  );
  assert.match(plist, /RunAtLoad/);
  assert.doesNotMatch(plist, /<key>KeepAlive<\/key>/);
  const argumentsXML = plist.match(/<key>ProgramArguments<\/key><array>(.*?)<\/array>/s)?.[1];
  assert.notEqual(argumentsXML, undefined);
  const argumentsList = [...argumentsXML.matchAll(/<string>(.*?)<\/string>/gs)].map(([, value]) =>
    value
      .replaceAll('&quot;', '"')
      .replaceAll('&apos;', "'")
      .replaceAll('&lt;', '<')
      .replaceAll('&gt;', '>')
      .replaceAll('&amp;', '&'),
  );
  // The bundled Node may live inside an .app. Its exact argv, rather than a
  // substring in an ancestor directory, establishes this environment-only action.
  assert.deepEqual(argumentsList, [
    realpathSync(process.execPath),
    join(f.state, 'shared-environment.mjs'),
    'apply',
    '--state',
    f.state,
  ]);
});

test('known owned legacy force flag migrates once; later human settings are preserved', (t) => {
  const f = fixture(t);
  f.api.install(f.options);
  const path = join(f.state, 'environment.json');
  const old = JSON.parse(readFileSync(path, 'utf8'));
  old.schemaVersion = 1;
  old.environment.CODEX_APP_SERVER_FORCE_CLI = '1';
  writeFileSync(path, JSON.stringify(old), { mode: 0o600 });
  f.environment.set('CODEX_APP_SERVER_FORCE_CLI', '1');
  assert.equal(f.api.status().futureLaunchEnvironmentReady, false);
  assert.equal(f.api.status().migrationRequired, true);
  assert.throws(() => f.api.apply(), /Upgrade.*cloud routing/);
  assert.equal(f.api.install(f.options).futureLaunchEnvironmentReady, true);
  assert.equal(f.environment.has('CODEX_APP_SERVER_FORCE_CLI'), false);
  assert.equal(JSON.parse(readFileSync(path, 'utf8')).schemaVersion, 2);
  f.environment.set('CODEX_APP_SERVER_FORCE_CLI', '1');
  assert.throws(() => f.api.install(f.options), /another desktop/);
  f.api.disable();
  assert.equal(f.environment.get('CODEX_APP_SERVER_FORCE_CLI'), '1');
});

test('an unmanaged global force flag is never removed by installation', (t) => {
  const f = fixture(t);
  f.environment.set('CODEX_APP_SERVER_FORCE_CLI', '1');
  assert.throws(() => f.api.install(f.options), /another desktop/);
  assert.equal(f.environment.get('CODEX_APP_SERVER_FORCE_CLI'), '1');
  assert.equal(
    f.calls.some((args) => args[0] === 'unsetenv' || args[0] === 'setenv'),
    false,
  );
});

test('apply restores login settings; disable preserves later environment edits', (t) => {
  const f = fixture(t);
  f.api.install(f.options);
  f.environment.clear();
  assert.equal(f.api.apply().futureLaunchEnvironmentReady, true);
  f.environment.set('CODEX_CLI_PATH', '/other/executable');
  assert.throws(() => f.api.apply(), /another desktop/);
  f.api.disable();
  assert.equal(f.environment.get('CODEX_CLI_PATH'), '/other/executable');
  assert.equal(f.environment.has('CODEX_APP_SERVER_FORCE_CLI'), false);
});

test('install refuses another executable configuration before writing files', (t) => {
  const f = fixture(t);
  f.environment.set('CODEX_CLI_PATH', '/other/executable');
  assert.throws(() => f.api.install(f.options), /another desktop/);
  assert.equal(
    f.calls.some((args) => args[0] === 'setenv'),
    false,
  );
});

test('edited runtime and unmanaged LaunchAgent are preserved', (t) => {
  const f = fixture(t);
  f.api.install(f.options);
  writeFileSync(join(f.state, 'codex-shared.mjs'), 'edited');
  assert.throws(() => f.api.install(f.options), /Preserve edited/);
  assert.equal(readFileSync(join(f.state, 'codex-shared.mjs'), 'utf8'), 'edited');
});

test('non-loopback and ambiguous endpoints cannot be persisted', (t) => {
  const f = fixture(t);
  for (const url of [
    'ws://example.com:57443',
    'ws://127.0.0.1:57443/other',
    'wss://127.0.0.1:57443',
    'ws://user@127.0.0.1:57443',
    'ws://127.0.0.1:57443/?x=1',
  ])
    assert.throws(() => f.api.install({ ...f.options, url }), /loopback/);
  assert.equal(
    f.calls.some((args) => args[0] === 'setenv'),
    false,
  );
});
