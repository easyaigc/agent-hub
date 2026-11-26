import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync, realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { nativeSuggestionsEnvironment } from './desktop-native-suggestions-environment.mjs';
import { sharedDesktopEnvironment } from './desktop-shared-environment.mjs';
import { upgradeDesktopRuntime, desktopRuntimeBundleStatus } from './desktop-runtime-upgrade.mjs';

function fixture(t) {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'desktop-runtime-upgrade-')));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const program = (name) => {
    const directory = join(root, name);
    for (const path of [
      'apps/host-agent/dist/runtime-entry.js',
      'packages/codex-adapter/dist/desktop-shared-launcher.js',
      'packages/codex-adapter/resources/desktop-completion-notify-observer.py',
      'scripts/desktop-native-suggestions-runtime.mjs',
      'scripts/desktop-native-trace-policy.mjs',
    ]) {
      mkdirSync(join(directory, path, '..'), { recursive: true });
      writeFileSync(join(directory, path), '', { mode: 0o600 });
    }
    return directory;
  };
  const oldProgram = program('old'),
    nextProgram = program('next');
  const state = join(root, 'native'),
    hostState = join(root, 'host');
  const traceRoot = join(root, 'results'),
    applicationPath = join(root, 'original.app');
  for (const path of [hostState, traceRoot, applicationPath]) mkdirSync(path, { mode: 0o700 });
  const target = {
    agentId: 'codex',
    hostId: '11111111-1111-4111-8111-111111111111',
    workspaceId: '33333333-3333-4333-8333-333333333333',
    runtimeId: 'desktop-runtime',
  };
  const targetFile = join(root, 'target.json');
  writeFileSync(targetFile, JSON.stringify(target), { mode: 0o600 });
  writeFileSync(join(hostState, 'runtime.json'), JSON.stringify({ hostId: target.hostId }), {
    mode: 0o600,
  });
  const environment = new Map(),
    calls = [];
  let registered = false;
  const sharedOptions = {
    state: join(root, 'shared'),
    agents: join(root, 'agents'),
    run: (args) => {
      calls.push(args);
      const [action, key, value] = args;
      if (action === 'getenv') return environment.get(key) ?? '';
      if (action === 'setenv') environment.set(key, value);
      if (action === 'unsetenv') environment.delete(key);
      if (action === 'bootstrap') registered = true;
      if (action === 'bootout') registered = false;
      if (action === 'print' && !registered) throw Error('not registered');
      return '';
    },
  };
  const shared = sharedDesktopEnvironment(sharedOptions);
  const nativeApi = nativeSuggestionsEnvironment({ state, shared });
  nativeApi.install({
    program: oldProgram,
    executable: process.execPath,
    traceRoot,
    targetFile,
    applicationPath,
    url: 'ws://127.0.0.1:57443',
  });
  const configPath = join(state, 'config.json');
  const hostEnvironment = {
    HOST_AGENT_STATE_DIR: hostState,
    CODEX_DESKTOP_NATIVE_SUGGESTIONS_CONFIG: configPath,
    CODEX_DESKTOP_SUGGESTION_TRACE_ROOT: traceRoot,
    CODEX_SHARED_SERVER_URL: shared.status().url,
    CODEX_DESKTOP_SUGGESTION_OBSERVER: '0',
  };
  const options = {
    program: nextProgram,
    environment: hostEnvironment,
    shared,
    native: () => nativeApi,
  };
  const entry = (p) => join(p, 'apps/host-agent/dist/runtime-entry.js');
  const audit = (p) => desktopRuntimeBundleStatus({ ...options, entryPath: entry(p) });
  return {
    oldProgram,
    nextProgram,
    configPath,
    hostState,
    hostEnvironment,
    options,
    audit,
    entry,
    calls,
    shared,
    sharedOptions,
    state,
  };
}

test('upgrade moves Host, shared launcher and producer together while retaining machine and private configuration', (t) => {
  const f = fixture(t);
  const before = JSON.parse(readFileSync(f.configPath, 'utf8'));
  assert.equal(f.audit(f.oldProgram).componentsConfiguredTogether, true);
  const environment = { ...f.hostEnvironment };
  const result = upgradeDesktopRuntime(f.options);
  assert.equal(result.entryPath, f.entry(f.nextProgram));
  assert.equal(result.workingDirectory, f.nextProgram);
  assert.equal(f.shared.status().program, f.nextProgram);
  const after = JSON.parse(readFileSync(f.configPath, 'utf8'));
  assert.deepEqual(after, {
    ...before,
    entry: join(f.nextProgram, 'scripts/desktop-native-suggestions-runtime.mjs'),
  });
  assert.deepEqual(f.hostEnvironment, environment);
  assert.equal(f.audit(f.nextProgram).componentsConfiguredTogether, true);
  assert.equal(result.currentProducerReady, false);
  assert.equal(result.normalDesktopReopenRequired, true);
  assert.equal(result.desktopProcessesStarted, 0);
  assert.equal(
    f.calls.some((args) => args.some((arg) => ['open', 'kill'].includes(arg))),
    false,
  );
});

test('Host-only replacement is reported as mixed rather than treating Host health as a complete upgrade', (t) => {
  const f = fixture(t);
  const result = f.audit(f.nextProgram);
  assert.equal(result.componentsConfiguredTogether, false);
  assert.equal(result.normalDesktopReopenRequired, true);
});

test('upgrading from another invoking Node retains the original shared launcher interpreter', (t) => {
  const f = fixture(t);
  const wrapper = join(f.sharedOptions.state, 'codex-shared.mjs');
  const interpreter = readFileSync(wrapper, 'utf8').split('\n')[0];
  const shared = sharedDesktopEnvironment({
    ...f.sharedOptions,
    node: '/unavailable-invoking-node',
  });
  const nativeApi = nativeSuggestionsEnvironment({ state: f.state, shared });
  upgradeDesktopRuntime({ ...f.options, shared, native: () => nativeApi });
  assert.equal(readFileSync(wrapper, 'utf8').split('\n')[0], interpreter);
});

test('an incomplete program fails before changing the existing producer or shared launcher', (t) => {
  const f = fixture(t);
  const before = readFileSync(f.configPath);
  rmSync(join(f.nextProgram, 'scripts/desktop-native-trace-policy.mjs'));
  assert.throws(() => upgradeDesktopRuntime(f.options), /Incomplete desktop runtime/);
  assert.deepEqual(readFileSync(f.configPath), before);
  assert.equal(f.shared.status().program, f.oldProgram);
});

for (const [name, change] of [
  [
    'another Host identity',
    (f) =>
      writeFileSync(
        join(f.hostState, 'runtime.json'),
        JSON.stringify({ hostId: '22222222-2222-4222-8222-222222222222' }),
      ),
  ],
  [
    'a different shared service URL',
    (f) => {
      f.hostEnvironment.CODEX_SHARED_SERVER_URL = 'ws://127.0.0.1:59999';
    },
  ],
])
  test(`upgrade preserves existing configuration when preflight finds ${name}`, (t) => {
    const f = fixture(t);
    const before = readFileSync(f.configPath);
    change(f);
    assert.throws(() => upgradeDesktopRuntime(f.options), /Preserve mismatched/);
    assert.deepEqual(readFileSync(f.configPath), before);
    assert.equal(f.shared.status().program, f.oldProgram);
  });
