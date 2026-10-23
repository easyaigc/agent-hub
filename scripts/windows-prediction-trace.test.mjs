import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import { syncBuiltinESMExports } from 'node:module';
import { join } from 'node:path';
import { test } from 'node:test';
import { DesktopPredictionTraceReader } from '../packages/codex-adapter/dist/desktop-prediction-trace.js';
import { DesktopSuggestionObserver } from '../packages/codex-adapter/dist/desktop-suggestion-observer.js';
import { withWindowsProcessCreation } from '../packages/codex-adapter/dist/windows-process.js';
import {
  protectWindowsStateFile,
  windowsPowerShellEnvironment,
  windowsTraceSnapshot,
  windowsTraceInstanceReady,
} from '../packages/codex-adapter/dist/desktop-platform.js';
import { windowsTraceEnvironment } from './windows-suggestion-trace-environment.mjs';
import { setup, execution, recoverableFixture } from './fixtures/desktop-trace.mjs';

const windows = { skip: process.platform !== 'win32' };
const read = (root, f) =>
  new DesktopPredictionTraceReader(root).readBinding(f.predictionThreadId, f.predictionTurnId);

function largeRequest(request) {
  const padded = { ...request.request, padding: '' };
  const size = 22 * 1024 * 1024;
  padded.padding = 'x'.repeat(size - Buffer.byteLength(JSON.stringify(padded)));
  fs.writeFileSync(request.requestPath, JSON.stringify(padded));
  assert.equal(fs.statSync(request.requestPath).size, size);
}

function largeSourceHistory(root) {
  const { sourceThreadId } = execution();
  return Array.from({ length: 3 }, (_, index) => {
    const value = recoverableFixture(root, execution({ sourceThreadId }));
    largeRequest(value.request);
    for (const event of value.fixture.events) event.wall_time_unix_ms += index * 10;
    value.fixture.saveEvents();
    return value;
  });
}

function recoverSource(root, fixture) {
  return new DesktopPredictionTraceReader(root).readSourcePredictions(
    fixture.sourceThreadId,
    fixture.sourceTurnId,
    root,
    1,
    true,
  );
}

// Common parser cases also exercise POSIX regression when this file runs on macOS.
test('source recovery gives historical bindings and the latest candidate bounded byte budgets', () =>
  setup((root) => {
    const history = largeSourceHistory(root);
    const latest = history.at(-1).fixture;
    assert.equal(new Set(history.map(({ fixture }) => fixture.sourceTurnId)).size, 3);
    const result = recoverSource(root, latest);
    assert.equal(result?.length, 1);
    assert.equal(result[0].binding.predictionThreadId, latest.predictionThreadId);
    assert.equal(result[0].binding.sourceTurnId, latest.sourceTurnId);
  }));

test('one binding still rejects more than 64 MiB of distinct request bodies', () =>
  setup((root) => {
    const { fixture, request } = recoverableFixture(root);
    largeRequest(request);
    largeRequest(fixture.addRequest({ recovery: true }));
    largeRequest(fixture.addRequest({ recovery: true }));
    assert.equal(read(root, fixture), null);
    assert.equal(recoverSource(root, fixture), null);
  }));

for (const changed of ['request body', 'discovery prefix']) {
  test(
    `source recovery retains old ${changed} guards after releasing historical body caches`,
    changed === 'request body' ? windows : {},
    () =>
      setup((root) => {
        const history = largeSourceHistory(root);
        const oldest = history[0];
        const latest = history.at(-1);
        const saved = fs.lstatSync;
        let mutated = false;
        fs.lstatSync = (path, ...args) => {
          const value = saved(path, ...args);
          // Response projection starts only after all historical bindings were read.
          if (path === latest.completion.responsePath && !mutated) {
            mutated = true;
            if (changed === 'request body') fs.appendFileSync(oldest.request.requestPath, ' ');
            else {
              const log = fs.readFileSync(oldest.fixture.logPath, 'utf8');
              const marker = '"wall_time_unix_ms":';
              const offset = log.indexOf(marker) + marker.length;
              assert(offset >= marker.length && log[offset] === '1');
              const fd = fs.openSync(oldest.fixture.logPath, 'r+');
              try {
                fs.writeSync(fd, Buffer.from('2'), 0, 1, Buffer.byteLength(log.slice(0, offset)));
              } finally {
                fs.closeSync(fd);
              }
            }
          }
          return value;
        };
        syncBuiltinESMExports();
        try {
          assert.equal(recoverSource(root, latest.fixture), null);
          assert.equal(mutated, true);
        } finally {
          fs.lstatSync = saved;
          syncBuiltinESMExports();
        }
        // No stale guard or body cache survives the next independent API read.
        assert.equal(recoverSource(root, latest.fixture)?.length, 1);
      }),
  );
}

for (const failure of ['failed', 'empty', 'incomplete']) {
  test(`latest ${failure} prediction never falls back after historical byte budgets are released`, () =>
    setup((root) => {
      const older = recoverableFixture(root);
      const latest = recoverableFixture(
        root,
        execution({
          sourceThreadId: older.fixture.sourceThreadId,
          sourceTurnId: older.fixture.sourceTurnId,
        }),
      );
      for (const event of latest.fixture.events) event.wall_time_unix_ms += 10;
      latest.fixture.saveEvents();
      assert.equal(
        recoverSource(root, older.fixture)?.[0].binding.predictionThreadId,
        latest.fixture.predictionThreadId,
      );
      if (failure === 'failed')
        latest.fixture.events.find(
          (event) => event.payload.type === 'codex_turn_ended',
        ).payload.status = 'failed';
      else if (failure === 'empty') {
        const text = JSON.stringify({ suggestion: '' });
        latest.completion.response.output_items[1].content[0].text = text;
        latest.terminal.value.last_agent_message = text;
        fs.writeFileSync(
          latest.completion.responsePath,
          JSON.stringify(latest.completion.response),
        );
        fs.writeFileSync(latest.terminal.path, JSON.stringify(latest.terminal.value));
      } else latest.fixture.events.splice(3);
      latest.fixture.saveEvents();
      assert.equal(recoverSource(root, older.fixture), null);
    }));
}

function publicRead(path) {
  withWindowsProcessCreation(30000, (timeout) =>
    execFileSync(
      join(process.env.SystemRoot, 'System32/WindowsPowerShell/v1.0/powershell.exe'),
      [
        '-NoProfile',
        '-NonInteractive',
        '-Command',
        "$p=$env:TRACE_TEST_PATH;$a=Get-Acl -LiteralPath $p;$sid=[System.Security.Principal.SecurityIdentifier]::new('S-1-1-0');$a.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($sid,'Read','Allow'));Set-Acl -LiteralPath $p -AclObject $a",
      ],
      {
        stdio: 'pipe',
        windowsHide: true,
        timeout,
        env: { ...windowsPowerShellEnvironment(), TRACE_TEST_PATH: path },
      },
    ),
  );
}

test('native Windows root and payload DACLs reject even public read grants', windows, () =>
  setup((root) => {
    const { fixture: f, request } = recoverableFixture(root);
    assert(read(root, f));
    publicRead(request.requestPath);
    assert.equal(read(root, f), null);
    protectWindowsStateFile(request.requestPath);
    assert(read(root, f));
    publicRead(root);
    assert.equal(new DesktopPredictionTraceReader(root).isAvailable(), false);
  }),
);

test(
  'native Windows rejects junction ancestry, hardlinks, ADS and namespace aliases',
  windows,
  () =>
    setup((root) => {
      const { fixture: f, request } = recoverableFixture(root);
      fs.linkSync(request.requestPath, join(root, 'duplicate.json'));
      assert.equal(read(root, f), null);
      fs.unlinkSync(join(root, 'duplicate.json'));
      assert(read(root, f));
      const junction = join(root, 'linked');
      fs.symlinkSync(f.directory, junction, 'junction');
      assert.throws(() => windowsTraceSnapshot(root, join(junction, 'manifest.json'), 16384));
      fs.unlinkSync(junction);
      assert.throws(() => windowsTraceSnapshot(root, request.requestPath + ':stream', 16384));
      assert.throws(() => windowsTraceSnapshot('\\\\?\\' + root, root));
      assert.throws(() => windowsTraceSnapshot(root, root + '\\..\\outside'));
    }),
);

test('native Windows rejects replacement between Node stat and guarded handle read', windows, () =>
  setup((root) => {
    const { fixture: f, request } = recoverableFixture(root);
    const saved = fs.lstatSync;
    let changed = false;
    fs.lstatSync = (path, ...args) => {
      const value = saved(path, ...args);
      if (path === request.requestPath && !changed) {
        changed = true;
        const alternate = request.requestPath + '.new';
        fs.copyFileSync(request.requestPath, alternate);
        protectWindowsStateFile(alternate);
        fs.renameSync(alternate, request.requestPath);
      }
      return value;
    };
    syncBuiltinESMExports();
    try {
      assert.equal(read(root, f), null);
      assert.equal(changed, true);
    } finally {
      fs.lstatSync = saved;
      syncBuiltinESMExports();
    }
  }),
);

test(
  'Windows readiness requires the original main and every direct executor to share the trace root',
  windows,
  () => {
    const instance = {
      pid: 1,
      startedAtUnixMs: 100,
      traceRoot: 'C:\\Private\\Traces',
      executors: [{ pid: 2, parentPid: 1, startedAtUnixMs: 101, traceRoot: 'c:\\private\\traces' }],
    };
    assert.equal(windowsTraceInstanceReady(instance, 'C:\\Private\\Traces'), true);
    assert.equal(
      windowsTraceInstanceReady({ ...instance, traceRoot: null }, instance.traceRoot),
      false,
    );
    assert.equal(
      windowsTraceInstanceReady({ ...instance, executors: [] }, instance.traceRoot),
      false,
    );
    instance.executors[0].traceRoot = 'C:\\Other';
    assert.equal(windowsTraceInstanceReady(instance, instance.traceRoot), false);
  },
);

test(
  'Windows persistent environment preserves later changes and restores only its own key',
  windows,
  () =>
    setup((root) => {
      let current = { value: null, kind: null };
      const calls = [];
      const run = (action, value, kind) => {
        calls.push(action);
        if (action === 'write') current = { value, kind };
        if (action === 'remove') current = { value: null, kind: null };
        return { ...current };
      };
      const tool = windowsTraceEnvironment({
        state: join(root, 'environment'),
        run,
        inspect: () => {
          throw Error('No desktop restart in this test');
        },
      });
      assert.equal(tool.install().configured, true);
      assert.equal(tool.status().currentInstanceReady, false);
      const original = { ...current };
      current = { value: 'C:\\UserLaterChoice', kind: 'ExpandString' };
      assert.throws(() => tool.disable(), /Preserve later/);
      assert.throws(() => tool.install(), /Preserve later/);
      current = original;
      assert.equal(tool.disable().configured, false);
      assert.equal(current.value, null);
      assert.equal(tool.install().configured, true);
      assert.equal(calls.includes('remove'), true);
    }),
);

test(
  'Windows persistent defaults use the native user profile outside MSIX LocalAppData',
  windows,
  () =>
    setup((root) => {
      const previous = process.env.USERPROFILE;
      process.env.USERPROFILE = root;
      let current = { value: null, kind: null };
      const run = (action, value, kind) => {
        if (action === 'write') current = { value, kind };
        return { ...current };
      };
      try {
        const tool = windowsTraceEnvironment({
          run,
          inspect: () => {
            throw Error('No desktop');
          },
        });
        const status = tool.install();
        assert.equal(status.configured, true);
        assert.equal(
          status.traceRoot,
          join(root, '.config', 'agent-hub', 'desktop-trace', 'traces'),
        );
        assert.equal(tool.install().traceRoot, status.traceRoot);
        assert.equal(tool.status().configured, true);
        for (const value of [undefined, 'relative-profile']) {
          if (value === undefined) delete process.env.USERPROFILE;
          else process.env.USERPROFILE = value;
          assert.throws(() => windowsTraceEnvironment({ run }), /Native Windows user profile/);
        }
      } finally {
        if (previous === undefined) delete process.env.USERPROFILE;
        else process.env.USERPROFILE = previous;
      }
    }),
);

function traceEnvironmentOptions(state, run) {
  return {
    state,
    run,
    inspect: () => {
      throw Error('No desktop access in installer fault tests');
    },
  };
}

for (const fault of ['before traces mkdir', 'ready manifest rename', 'registry write']) {
  test(`Windows persistent installation resumes after ${fault} failure`, windows, () =>
    setup((root) => {
      const state = join(root, 'environment');
      const traces = join(state, 'traces');
      const manifest = join(state, 'environment.json');
      let current = { value: null, kind: null };
      let injected = false;
      const calls = [];
      const run = (action, value, kind) => {
        calls.push(action);
        if (action === 'write' && fault === 'registry write' && !injected) {
          injected = true;
          throw Error('INJECTED_INSTALL_FAILURE');
        }
        if (action === 'write') current = { value, kind };
        if (action === 'remove') current = { value: null, kind: null };
        return { ...current };
      };
      const options = traceEnvironmentOptions(state, run);
      const tool = windowsTraceEnvironment(options);
      const savedMkdir = fs.mkdirSync;
      const savedRename = fs.renameSync;
      fs.mkdirSync = (path, ...args) => {
        if (path === traces && fault === 'before traces mkdir' && !injected) {
          injected = true;
          throw Error('INJECTED_INSTALL_FAILURE');
        }
        return savedMkdir(path, ...args);
      };
      fs.renameSync = (from, to) => {
        if (
          to === manifest &&
          fault === 'ready manifest rename' &&
          !injected &&
          JSON.parse(fs.readFileSync(from, 'utf8')).phase === 'ready'
        ) {
          injected = true;
          throw Error('INJECTED_INSTALL_FAILURE');
        }
        return savedRename(from, to);
      };
      syncBuiltinESMExports();
      try {
        assert.throws(() => tool.install(), /INJECTED_INSTALL_FAILURE/);
      } finally {
        fs.mkdirSync = savedMkdir;
        fs.renameSync = savedRename;
        syncBuiltinESMExports();
      }
      assert.equal(injected, true);
      assert.deepEqual(current, { value: null, kind: null });
      const marker = JSON.parse(windowsTraceSnapshot(state, manifest, 16384).bytes);
      const phase = fault === 'registry write' ? 'ready' : 'preparing';
      assert.equal(marker.phase, phase);
      assert.equal(marker.enabled, phase === 'ready');
      assert.deepEqual(marker.previous, { value: null, kind: null });
      assert.equal(fs.existsSync(traces), fault !== 'before traces mkdir');
      assert.deepEqual(
        fs.readdirSync(state).filter((name) => name.endsWith('.tmp')),
        [],
      );
      assert.equal(tool.status().phase, phase);
      assert.equal(tool.status().configured, false);
      if (phase === 'preparing') {
        assert.equal(tool.disable().configured, false);
        assert.equal(calls.includes('write'), false);
        assert.equal(calls.includes('remove'), false);
      }
      current = { value: 'C:\\UserLaterChoice', kind: 'ExpandString' };
      assert.throws(() => tool.install(), /Preserve later/);
      if (phase === 'preparing') assert.equal(tool.disable().configured, false);
      else assert.throws(() => tool.disable(), /Preserve later/);
      assert.deepEqual(current, { value: 'C:\\UserLaterChoice', kind: 'ExpandString' });
      current = { value: null, kind: null };

      // A retained old fixed-PID name is neither reused nor removed on retry.
      const retained = manifest + '.' + process.pid + '.tmp';
      fs.writeFileSync(retained, 'retain unrelated temporary data', { flag: 'wx', mode: 0o600 });
      const resumed = windowsTraceEnvironment(options);
      assert.equal(resumed.install().configured, true);
      assert.equal(resumed.status().phase, 'ready');
      assert.equal(fs.readFileSync(retained, 'utf8'), 'retain unrelated temporary data');
      assert.deepEqual(
        fs.readdirSync(state).filter((name) => name.endsWith('.tmp')),
        ['environment.json.' + process.pid + '.tmp'],
      );
      const owned = { ...current };
      current = { value: 'C:\\UserLaterChoice', kind: 'ExpandString' };
      assert.throws(() => resumed.install(), /Preserve later/);
      assert.throws(() => resumed.disable(), /Preserve later/);
      assert.deepEqual(current, { value: 'C:\\UserLaterChoice', kind: 'ExpandString' });
      current = owned;
      assert.equal(resumed.disable().configured, false);
      assert.deepEqual(current, { value: null, kind: null });
      assert.equal(calls.filter((action) => action === 'remove').length, 1);
      assert.equal(fs.readFileSync(retained, 'utf8'), 'retain unrelated temporary data');
    }),
  );
}

test('Windows persistent save preserves a temporary file it did not create', windows, () =>
  setup((root) => {
    const state = join(root, 'environment');
    const manifest = join(state, 'environment.json');
    let collision;
    const saved = fs.openSync;
    const tool = windowsTraceEnvironment(
      traceEnvironmentOptions(state, (action) => {
        assert.equal(action, 'read');
        return { value: null, kind: null };
      }),
    );
    fs.openSync = (path, flags, ...args) => {
      if (typeof path === 'string' && path.startsWith(manifest + '.') && flags === 'wx') {
        collision = path;
        const fd = saved(path, flags, ...args);
        try {
          fs.writeFileSync(fd, 'retain pre-existing collision');
        } finally {
          fs.closeSync(fd);
        }
      }
      return saved(path, flags, ...args);
    };
    syncBuiltinESMExports();
    try {
      assert.throws(
        () => tool.install(),
        (error) => error.code === 'EEXIST',
      );
    } finally {
      fs.openSync = saved;
      syncBuiltinESMExports();
    }
    assert(collision);
    assert.equal(fs.readFileSync(collision, 'utf8'), 'retain pre-existing collision');
    assert.equal(fs.existsSync(manifest), false);
    assert.throws(() => tool.install(), /Preserve existing directory/);
  }),
);

test(
  'Windows persistent save reports retained cleanup failures and retries with a fresh name',
  windows,
  () =>
    setup((root) => {
      const state = join(root, 'environment');
      const manifest = join(state, 'environment.json');
      let current = { value: null, kind: null };
      const tool = windowsTraceEnvironment(
        traceEnvironmentOptions(state, (action, value, kind) => {
          if (action === 'write') current = { value, kind };
          if (action === 'remove') current = { value: null, kind: null };
          return { ...current };
        }),
      );
      let retained;
      const savedRename = fs.renameSync;
      const savedUnlink = fs.unlinkSync;
      fs.renameSync = (from, to) => {
        if (to === manifest && JSON.parse(fs.readFileSync(from, 'utf8')).phase === 'ready') {
          retained = from;
          throw Error('INJECTED_RENAME_FAILURE');
        }
        return savedRename(from, to);
      };
      fs.unlinkSync = (path) => {
        if (path === retained)
          throw Object.assign(Error('INJECTED_CLEANUP_FAILURE'), { code: 'EPERM' });
        return savedUnlink(path);
      };
      syncBuiltinESMExports();
      try {
        assert.throws(
          () => tool.install(),
          (error) => error instanceof AggregateError && error.message.includes(retained),
        );
      } finally {
        fs.renameSync = savedRename;
        fs.unlinkSync = savedUnlink;
        syncBuiltinESMExports();
      }
      const bytes = fs.readFileSync(retained);
      assert.equal(tool.status().phase, 'preparing');
      assert.equal(tool.install().configured, true);
      assert.deepEqual(fs.readFileSync(retained), bytes);
      assert.equal(tool.disable().configured, false);
      assert.deepEqual(current, { value: null, kind: null });
      assert.deepEqual(fs.readFileSync(retained), bytes);
    }),
);

test('Windows persistent installation does not adopt unmarked nonempty state', windows, () =>
  setup((root) => {
    const state = join(root, 'environment');
    const traces = join(state, 'traces');
    fs.mkdirSync(traces, { recursive: true });
    const tool = windowsTraceEnvironment(
      traceEnvironmentOptions(state, (action) => {
        assert.equal(action, 'read');
        return { value: null, kind: null };
      }),
    );
    assert.throws(() => tool.install(), /Preserve existing directory/);
    assert(fs.statSync(traces).isDirectory());
    assert.equal(fs.existsSync(join(state, 'environment.json')), false);
  }),
);

for (const phase of ['ready', 'legacy']) {
  test(
    `Windows persistent ${phase} state still requires the private traces directory`,
    windows,
    () =>
      setup((root) => {
        const state = join(root, 'environment');
        const manifest = join(state, 'environment.json');
        let current = { value: null, kind: null };
        const tool = windowsTraceEnvironment(
          traceEnvironmentOptions(state, (action, value, kind) => {
            if (action === 'write') current = { value, kind };
            return { ...current };
          }),
        );
        assert.equal(tool.install().configured, true);
        if (phase === 'legacy') {
          const value = JSON.parse(fs.readFileSync(manifest, 'utf8'));
          delete value.phase;
          value.previous = { value: '%USERPROFILE%\\LegacyTrace', kind: 'ExpandString' };
          fs.writeFileSync(manifest, JSON.stringify(value));
          assert.equal(tool.status().phase, 'ready');
          assert.equal(tool.disable().configured, false);
          assert.deepEqual(current, value.previous);
          assert.equal(tool.install().configured, true);
        }
        fs.rmdirSync(join(state, 'traces'));
        assert.throws(() => tool.status());
        assert.throws(() => tool.install());
        assert.equal(fs.existsSync(join(state, 'traces')), false);
      }),
  );
}

test(
  'Windows native-only projection has a finite lease and loses capability on instance change',
  windows,
  () =>
    setup((root) => {
      const { fixture: f } = recoverableFixture(root);
      const target = {
        agentId: 'codex',
        hostId: 'synthetic-host',
        workspaceId: 'synthetic-workspace',
        runtimeId: 'desktop-runtime',
      };
      const observer = new DesktopSuggestionObserver({
        nativeWorker: true,
        directory: root,
        root,
        enabled: true,
        traceRoot: root,
        target: () => target,
      });
      // The filesystem/parser are real Windows operations; only desktop identity is
      // controlled here. This is explicitly not live original-desktop acceptance.
      observer.phase = 'listening';
      observer.desktopStartedAtUnixMs = 1;
      observer.windowsInstanceStable = () => true;
      try {
        assert.equal(observer.capabilities.includes('desktop:suggestion-observations'), false);
        const before = Date.now();
        const response = observer.readBoundSource(target, f.sourceThreadId, f.sourceTurnId);
        assert.equal(response.source, 'native-trace');
        assert.equal(response.suggestion.validity, 'native-version');
        assert(Date.parse(response.suggestion.expiresAt) >= before + 6000);
        assert(Date.parse(response.suggestion.expiresAt) <= Date.now() + 6000);
        observer.windowsInstanceStable = () => false;
        assert.deepEqual(observer.capabilities, []);
        assert.throws(
          () => observer.readBoundSource(target, f.sourceThreadId, f.sourceTurnId),
          (e) => e.status === 503,
        );
      } finally {
        observer.close();
      }
    }),
);
