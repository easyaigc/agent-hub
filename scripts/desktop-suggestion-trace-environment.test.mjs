import assert from 'node:assert/strict';
import {
  existsSync,
  mkdtempSync,
  readFileSync,
  realpathSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { traceEnvironment } from './desktop-suggestion-trace-environment.mjs';

function fixture(runTest) {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'desktop-trace-env-')));
  let environment = '',
    registered = false,
    disabled = false;
  const calls = [];
  const run = (args) => {
    calls.push(args);
    switch (args[0]) {
      case 'getenv':
        return environment;
      case 'setenv':
        assert.equal(args[1], 'CODEX_ROLLOUT_TRACE_ROOT');
        environment = args[2];
        return '';
      case 'unsetenv':
        assert.equal(args[1], 'CODEX_ROLLOUT_TRACE_ROOT');
        environment = '';
        return '';
      case 'enable':
        disabled = false;
        return '';
      case 'disable':
        disabled = true;
        return '';
      case 'bootstrap':
        assert.equal(disabled, false);
        registered = true;
        return '';
      case 'bootout':
        registered = false;
        return '';
      case 'print':
        if (!registered) throw Error('not registered');
        return '';
      default:
        throw Error('Unexpected operation');
    }
  };
  const state = join(root, 'private');
  const manager = traceEnvironment({ state, agents: join(root, 'LaunchAgents'), run });
  try {
    return runTest({
      root,
      state,
      manager,
      calls,
      env: (v) => {
        environment = v;
      },
      current: () => environment,
      disabled: () => disabled,
    });
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

test('persistent environment uses exactly one key and does not claim current desktop activation', () =>
  fixture(({ state, manager, calls }) => {
    const result = manager.install();
    assert.equal(result.registered, true);
    assert.equal(result.futureLaunchEnvironmentReady, true);
    assert.equal(result.currentDesktopActivated, 'requires-independent-process-check');
    const manifest = JSON.parse(readFileSync(join(state, 'environment.json'), 'utf8'));
    const plist = readFileSync(manifest.plist, 'utf8');
    assert(plist.includes('<key>RunAtLoad</key><true/>'));
    assert(plist.includes('trace-environment.mjs'));
    assert(!plist.includes('/Contents/MacOS/ChatGPT'));
    assert(
      calls.every(([operation]) =>
        ['getenv', 'setenv', 'enable', 'bootstrap', 'print'].includes(operation),
      ),
    );
  }));

test('login application restores the key without an app launch; disable is persistent and preserves files', () =>
  fixture(({ state, manager, env, current, disabled }) => {
    manager.install();
    env('');
    assert.equal(manager.status().futureLaunchEnvironmentReady, false);
    assert.equal(manager.apply().futureLaunchEnvironmentReady, true);
    assert.equal(current(), join(state, 'traces'));
    manager.disable();
    assert.equal(current(), '');
    assert.equal(disabled(), true);
    assert.equal(manager.status().registered, false);
    const manifest = JSON.parse(readFileSync(join(state, 'environment.json'), 'utf8'));
    assert(readFileSync(manifest.plist).length > 0);
    assert(readFileSync(manifest.runtime).length > 0);
    manager.install();
    assert.equal(disabled(), false);
  }));

test('unrelated existing environment and later user changes are preserved', () =>
  fixture(({ manager, state, env, current }) => {
    env('/another/trace/root');
    assert.throws(() => manager.install(), /belongs to another/);
    assert.equal(current(), '/another/trace/root');
    assert.equal(existsSync(state), false);
    env('');
    manager.install();
    env('/later/user/change');
    manager.disable();
    assert.equal(current(), '/later/user/change');
  }));

test('a conflicting reinstall preserves registration, files and the later environment', () =>
  fixture(({ manager, state, calls, env, current, disabled }) => {
    manager.install();
    const manifestPath = join(state, 'environment.json');
    const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
    const files = [manifestPath, manifest.runtime, manifest.plist];
    const before = files.map((path) => readFileSync(path));
    env('/later/user/change');
    const callCount = calls.length;

    assert.throws(() => manager.install(), /Preserve a later or unrelated trace environment/);
    assert.deepEqual(calls.slice(callCount), [['getenv', 'CODEX_ROLLOUT_TRACE_ROOT']]);
    assert.equal(current(), '/later/user/change');
    assert.equal(manager.status().registered, true);
    assert.equal(disabled(), false);
    for (const [index, path] of files.entries())
      assert.deepEqual(readFileSync(path), before[index], path);

    env(manifest.traceRoot);
    assert.equal(manager.install().registered, true);
    assert.equal(current(), manifest.traceRoot);
  }));

test('edited managed files block upgrade rather than overwrite unique work', () =>
  fixture(({ manager, state }) => {
    manager.install();
    const manifest = JSON.parse(readFileSync(join(state, 'environment.json'), 'utf8'));
    writeFileSync(manifest.runtime, 'local change', { mode: 0o600 });
    assert.throws(() => manager.install(), /edited runtime/);
    assert.equal(readFileSync(manifest.runtime, 'utf8'), 'local change');
    writeFileSync(manifest.plist, 'local plist change', { mode: 0o600 });
    assert.throws(() => manager.disable(), /edited LaunchAgent/);
  }));
