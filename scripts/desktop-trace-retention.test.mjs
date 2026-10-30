import assert from 'node:assert/strict';
import fs from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, join } from 'node:path';
import { test } from 'node:test';
import { execFileSync } from 'node:child_process';
import { syncBuiltinESMExports } from 'node:module';
import { Worker } from 'node:worker_threads';
import { DesktopSuggestionObserver } from '../packages/codex-adapter/dist/desktop-suggestion-observer.js';
import { createHash, randomUUID } from 'node:crypto';
import { DesktopSuggestionStore } from '../packages/codex-adapter/dist/desktop-suggestion-store.js';
import { machineRuntimeTargetKey } from '../packages/protocol/dist/index.js';
import { recoverableFixture, execution, bundle } from './fixtures/desktop-trace.mjs';
import { DesktopTraceRetention } from '../packages/codex-adapter/dist/desktop-trace-retention.js';
import { DesktopPredictionTraceReader } from '../packages/codex-adapter/dist/desktop-prediction-trace.js';
import {
  protectWindowsStateDirectory,
  windowsPowerShellEnvironment,
} from '../packages/codex-adapter/dist/desktop-platform.js';

async function withRoot(run) {
  const root = fs.realpathSync(fs.mkdtempSync(join(tmpdir(), 'agent-hub-retention-test-')));
  fs.chmodSync(root, 0o700);
  protectWindowsStateDirectory(root);
  try {
    await run(root);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}
function fixture(root, ids, requestTime = 10, status = 'completed', text) {
  const value = recoverableFixture(root, ids);
  const { fixture: f } = value;
  for (const event of f.events) event.wall_time_unix_ms += requestTime;
  if (status !== 'completed')
    f.events.find((e) => e.payload.type === 'codex_turn_ended').payload.status = status;
  if (text !== undefined) {
    value.completion.response.output_items[1].content[0].text = JSON.stringify({
      suggestion: text,
    });
    fs.writeFileSync(value.completion.responsePath, JSON.stringify(value.completion.response));
    value.terminal.value.last_agent_message = JSON.stringify({ suggestion: text });
    fs.writeFileSync(value.terminal.path, JSON.stringify(value.terminal.value));
  }
  f.events.push({
    schema_version: 1,
    seq: f.events.length + 1,
    wall_time_unix_ms: requestTime + 5,
    rollout_id: f.predictionThreadId,
    thread_id: null,
    codex_turn_id: null,
    payload: { type: 'rollout_ended', status: 'completed' },
  });
  f.saveEvents();
  const old = new Date(Date.now() - 10000);
  fs.utimesSync(f.logPath, old, old);
  return f;
}
function unverifiedPrediction(root, withoutRequest = false) {
  const ids = execution(),
    f = bundle(root, ids);
  f.protocolEvent('session_configured', {
    type: 'session_configured',
    thread_id: ids.predictionThreadId,
    thread_source: 'composer_predictions',
    forked_from_id: ids.sourceThreadId,
    cwd: root,
  });
  if (!withoutRequest) f.addRequest({ recovery: true });
  f.endTurn('completed');
  f.events.push({
    schema_version: 1,
    seq: f.events.length + 1,
    wall_time_unix_ms: 5,
    rollout_id: ids.predictionThreadId,
    thread_id: null,
    codex_turn_id: null,
    payload: { type: 'rollout_ended', status: 'completed' },
  });
  // The native producer must predate the original request in this fixture.
  for (const event of f.events) event.wall_time_unix_ms += 10;
  f.saveEvents();
  const old = new Date(Date.now() - 10000);
  fs.utimesSync(f.logPath, old, old);
  return f;
}
for (const withoutRequest of [false, true])
  test(`unverified completed prediction stays after diagnostic expiry (missing request: ${withoutRequest})`, () =>
    withRoot(async (root) => {
      const f = unverifiedPrediction(root, withoutRequest);
      const reader = new DesktopPredictionTraceReader(root, () => 'host-a');
      if (!withoutRequest) assert(reader.readBinding(f.predictionThreadId, f.predictionTurnId));
      assert.equal(
        reader.readCompletedPredictionForRetention(f.predictionThreadId, f.predictionTurnId),
        null,
      );
      const retention = new DesktopTraceRetention(root, 'host-a');
      for (const hours of [25, 72]) {
        const result = await processTrace(
          retention,
          f,
          fs.statSync(f.logPath).mtimeMs + hours * 60 * 60 * 1000,
        );
        assert.equal(result.state, 'prediction-unverified');
        assert(fs.existsSync(f.directory), 'Unknown raw evidence is retained regardless of age');
      }
    }));

const read = (root, f, target = 'host-a') =>
  new DesktopPredictionTraceReader(root, () => target).readSourcePredictions(
    f.sourceThreadId,
    f.sourceTurnId,
    0,
    true,
  );
const processTrace = (retention, f, now) => retention.process(basename(f.directory), now);

test(
  'sealed prediction persists through retirement and a fresh reader',
  { skip: process.platform !== 'darwin' && process.platform !== 'win32' },
  () =>
    withRoot(async (root) => {
      const f = fixture(root);
      const retention = new DesktopTraceRetention(root, 'host-a');
      assert.equal((await processTrace(retention, f)).state, 'retired');
      assert.equal(fs.existsSync(f.directory), false);
      assert.equal(read(root, f)[0].text, 'Synthetic follow-up suggestion.');
      assert.deepEqual(read(root, f, 'host-b'), []);
    }),
);

test('newer request wins over later processed old result and different source is isolated', () =>
  withRoot(async (root) => {
    const old = fixture(root, undefined, 10);
    const newer = fixture(
      root,
      execution({ sourceThreadId: old.sourceThreadId, sourceTurnId: old.sourceTurnId }),
      100,
      'completed',
      'newest',
    );
    const other = fixture(root);
    const retention = new DesktopTraceRetention(root, 'host-a');
    await processTrace(retention, newer);
    await processTrace(retention, old);
    await processTrace(retention, other);
    assert.equal(read(root, old)[0].text, 'newest');
    assert.equal(read(root, other)[0].text, 'Synthetic follow-up suggestion.');
  }));

for (const status of ['cancelled', 'failed', 'empty'])
  test(`${status} newer request prevents old suggestion revival and retains diagnostics`, () =>
    withRoot(async (root) => {
      const old = fixture(root, undefined, 10);
      const newer = fixture(
        root,
        execution({ sourceThreadId: old.sourceThreadId, sourceTurnId: old.sourceTurnId }),
        100,
        status === 'empty' ? 'completed' : status,
        status === 'empty' ? '' : undefined,
      );
      const retention = new DesktopTraceRetention(root, 'host-a');
      assert.equal((await processTrace(retention, newer)).state, 'diagnostic-retention');
      await processTrace(retention, old);
      const result = read(root, old);
      assert(!result?.some((p) => p.binding.predictionThreadId === old.predictionThreadId));
      const saved = JSON.parse(
        JSON.parse(
          fs.readFileSync(join(retention.store.directory, old.sourceThreadId + '.json'), 'utf8'),
        ).body,
      );
      assert.equal(saved.prediction, null);
      assert.equal(saved.latestExtracted.prediction.text, 'Synthetic follow-up suggestion.');
      assert(fs.existsSync(newer.directory));
    }));

test('root ownership, abort and unsealed input fail closed', () =>
  withRoot(async (root) => {
    const f = fixture(root);
    const controller = new AbortController();
    const retention = new DesktopTraceRetention(root, 'host-a', controller.signal);
    assert.throws(() => new DesktopTraceRetention(root, 'host-b'), /TRACE_OWNED_BY_OTHER_RUNTIME/);
    f.events.pop();
    f.saveEvents();
    assert.equal((await processTrace(retention, f)).state, 'active-or-unsealed');
    controller.abort();
    await assert.rejects(processTrace(retention, f), /TRACE_RETENTION_CLOSED/);
    assert(fs.existsSync(f.directory));
    await assert.rejects(retention.process('../outside'), /TRACE_RETENTION_CLOSED/);
  }));

for (const kind of ['unknown', 'symlink', 'hardlink', 'save-failure'])
  test(`${kind} preserves the only trace`, () =>
    withRoot(async (root) => {
      const f = fixture(root);
      const retention = new DesktopTraceRetention(root, 'host-a');
      if (kind === 'unknown') fs.writeFileSync(join(f.directory, 'unknown'), 'x');
      if (kind === 'symlink') {
        fs.renameSync(join(f.directory, 'manifest.json'), join(root, 'original'));
        fs.symlinkSync(join(root, 'original'), join(f.directory, 'manifest.json'));
      }
      if (kind === 'hardlink') fs.linkSync(join(f.directory, 'manifest.json'), join(root, 'alias'));
      if (kind === 'save-failure') {
        const directory = retention.store?.directory ?? join(root, '.agent-hub-suggestions');
        if (process.platform === 'win32')
          execFileSync('icacls', [directory, '/grant', '*S-1-1-0:(OI)(CI)R'], { stdio: 'pipe' });
        else fs.chmodSync(directory, 0o755);
      }
      await assert.rejects(processTrace(retention, f));
      assert(fs.existsSync(f.directory));
    }));

test('open file descriptor prevents retirement', { skip: process.platform !== 'darwin' }, () =>
  withRoot(async (root) => {
    const f = fixture(root);
    const retention = new DesktopTraceRetention(root, 'host-a');
    const fd = fs.openSync(f.logPath, 'r');
    try {
      assert.match((await processTrace(retention, f)).state, /occupied/);
      assert(fs.existsSync(f.directory));
    } finally {
      fs.closeSync(fd);
    }
  }),
);

test(
  'native Windows retirement requires a Windows host',
  { skip: process.platform !== 'win32' },
  () =>
    withRoot(async (root) => {
      const f = fixture(root);
      assert.equal(
        (await processTrace(new DesktopTraceRetention(root, 'host-a'), f)).state,
        'retired',
      );
    }),
);

test(
  'Windows accepts an Administrators-owned original trace child and durably retains one suggestion',
  { skip: process.platform !== 'win32', timeout: 120000 },
  () =>
    withRoot(async (root) => {
      const f = fixture(root);
      const child = join(f.directory, 'manifest.json');
      // Only this synthetic trace is changed; its DACL grants the current user.
      const ps = join(
        process.env.SystemRoot,
        'System32',
        'WindowsPowerShell',
        'v1.0',
        'powershell.exe',
      );
      const setup = String.raw`$s=[Security.Principal.WindowsIdentity]::GetCurrent().User
$a=[Security.AccessControl.FileSecurity]::new();$a.SetOwner($s);$a.SetAccessRuleProtection($true,$false)
$a.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($s,'FullControl','Allow'))
Set-Acl -LiteralPath $env:AH_OWNER_TEST_PATH -AclObject $a`;
      execFileSync(
        ps,
        [
          '-NoProfile',
          '-NonInteractive',
          '-EncodedCommand',
          Buffer.from(setup, 'utf16le').toString('base64'),
        ],
        { env: { ...windowsPowerShellEnvironment(), AH_OWNER_TEST_PATH: child }, stdio: 'pipe' },
      );
      execFileSync('icacls', [child, '/setowner', '*S-1-5-32-544'], { stdio: 'pipe' });
      const verify = String.raw`$a=Get-Acl -LiteralPath $env:AH_OWNER_TEST_PATH
$s=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
if($a.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne 'S-1-5-32-544'){exit 1}
foreach($r in $a.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])) {
 if($r.AccessControlType -eq 'Allow' -and @($s,'S-1-5-18','S-1-5-32-544') -notcontains $r.IdentityReference.Value){exit 2}
}`;
      execFileSync(
        ps,
        [
          '-NoProfile',
          '-NonInteractive',
          '-EncodedCommand',
          Buffer.from(verify, 'utf16le').toString('base64'),
        ],
        {
          env: { ...windowsPowerShellEnvironment(), AH_OWNER_TEST_PATH: child },
          stdio: 'pipe',
        },
      );
      const retention = new DesktopTraceRetention(root, 'host-a');
      assert.equal((await processTrace(retention, f)).state, 'retired');
      assert.equal(fs.existsSync(f.directory), false);
      assert.equal(read(root, f).length, 1);
      assert.equal(read(root, f)[0].text, 'Synthetic follow-up suggestion.');
      assert.equal(
        fs
          .readdirSync(retention.store.directory)
          .filter((name) => /^[a-f0-9-]{36}\.json$/.test(name)).length,
        1,
      );
    }),
);

test(
  'Windows rejects an original trace child granting an untrusted principal',
  { skip: process.platform !== 'win32', timeout: 120000 },
  () =>
    withRoot(async (root) => {
      const f = fixture(root);
      execFileSync('icacls', [join(f.directory, 'manifest.json'), '/grant', '*S-1-1-0:R'], {
        stdio: 'pipe',
      });
      await assert.rejects(processTrace(new DesktopTraceRetention(root, 'host-a'), f));
      assert(fs.existsSync(f.directory));
    }),
);

test(
  'cancelled trace remains during 24-hour diagnostic window and can retire after it',
  { skip: process.platform !== 'darwin' && process.platform !== 'win32' },
  () =>
    withRoot(async (root) => {
      const f = fixture(root, undefined, 10, 'cancelled');
      const retention = new DesktopTraceRetention(root, 'host-a');
      const mtime = fs.statSync(f.logPath).mtimeMs;
      assert.equal(
        (await processTrace(retention, f, Math.ceil(mtime + 23 * 3600_000))).state,
        'diagnostic-retention',
      );
      assert(fs.existsSync(f.directory));
      assert.equal(
        (await processTrace(retention, f, Math.ceil(mtime + 25 * 3600_000))).state,
        'retired',
      );
    }),
);

test('paths outside trace root are rejected without touching external data', () =>
  withRoot(async (root) => {
    const f = fixture(root);
    const retention = new DesktopTraceRetention(root, 'host-a');
    await assert.rejects(
      retention.process('../' + basename(f.directory)),
      /EXACT_TRACE_BUNDLE_REQUIRED/,
    );
    assert(fs.existsSync(f.directory));
  }));

test(
  'after confirmed retirement, latest request in another turn suppresses older suggestion',
  { skip: !['darwin', 'win32'].includes(process.platform) },
  () =>
    withRoot(async (root) => {
      const old = fixture(root, undefined, 10);
      const newer = fixture(
        root,
        execution({ sourceThreadId: old.sourceThreadId }),
        100,
        'completed',
        'other turn',
      );
      const retention = new DesktopTraceRetention(root, 'host-a');
      assert.equal((await processTrace(retention, old)).state, 'retired');
      assert.equal((await processTrace(retention, newer)).state, 'retired');
      assert(
        !read(root, old)?.some((p) => p.binding.predictionThreadId === old.predictionThreadId),
      );
      assert.equal(read(root, newer)[0].text, 'other turn');
    }),
);

test(
  'unsupported retirement preserves raw traces while compact storage advances to the latest turn',
  { skip: ['darwin', 'win32'].includes(process.platform) },
  () =>
    withRoot(async (root) => {
      const old = fixture(root, undefined, 10);
      const newer = fixture(
        root,
        execution({ sourceThreadId: old.sourceThreadId }),
        100,
        'completed',
        'other turn',
      );
      const inputs = [old, newer].flatMap((f) => [
        join(f.directory, 'manifest.json'),
        f.logPath,
        ...fs
          .readdirSync(join(f.directory, 'payloads'))
          .map((name) => join(f.directory, 'payloads', name)),
      ]);
      const before = inputs.map((path) => fs.readFileSync(path));
      const retention = new DesktopTraceRetention(root, 'host-a');
      assert.equal((await processTrace(retention, old)).state, 'unsupported-platform');
      assert.equal((await processTrace(retention, newer)).state, 'unsupported-platform');
      assert.deepEqual(
        inputs.map((path) => fs.readFileSync(path)),
        before,
      );
      const saved = JSON.parse(
        JSON.parse(
          fs.readFileSync(join(retention.store.directory, old.sourceThreadId + '.json'), 'utf8'),
        ).body,
      );
      assert.equal(saved.sourceTurnId, newer.sourceTurnId);
      assert.equal(saved.prediction.binding.predictionThreadId, newer.predictionThreadId);
      assert.equal(saved.prediction.text, 'other turn');
      assert.equal(read(root, newer)[0].text, 'other turn');
    }),
);

for (const phase of ['file', 'directory'])
  test(
    `failed ${phase} flush cannot be acknowledged by a later equal record`,
    {
      skip:
        !['darwin', 'win32'].includes(process.platform) ||
        (phase === 'directory' && process.platform === 'win32'),
    },
    () =>
      withRoot(async (root) => {
        const f = fixture(root),
          retention = new DesktopTraceRetention(root, 'host-a');
        const original = fs.fsyncSync;
        fs.fsyncSync = (fd) => {
          const directory = fs.fstatSync(fd).isDirectory();
          if ((phase === 'directory') === directory)
            throw new Error('INJECTED_RETENTION_FLUSH_FAILURE');
          return original(fd);
        };
        syncBuiltinESMExports();
        try {
          await assert.rejects(processTrace(retention, f), /INJECTED_RETENTION_FLUSH_FAILURE/);
          assert(fs.existsSync(f.directory));
          await assert.rejects(processTrace(retention, f), /INJECTED_RETENTION_FLUSH_FAILURE/);
          assert(fs.existsSync(f.directory));
        } finally {
          fs.fsyncSync = original;
          syncBuiltinESMExports();
        }
        assert.equal((await processTrace(retention, f)).state, 'retired');
        assert.equal(read(root, f)[0].text, 'Synthetic follow-up suggestion.');
      }),
  );

test(
  'a sealed successful ordinary trace retires without publishing its suggestion-shaped response',
  { skip: !['darwin', 'win32'].includes(process.platform) },
  () =>
    withRoot(async (root) => {
      const f = bundle(root);
      f.protocolEvent('session_configured', {
        type: 'session_configured',
        thread_id: f.predictionThreadId,
        thread_source: 'user',
        cwd: root,
      });
      const request = f.addRequest({ metadata: { thread_source: 'user', turn_trigger: 'user' } });
      delete request.metadata.prediction_source_thread_id;
      delete request.metadata.prediction_source_turn_id;
      delete request.metadata.forked_from_thread_id;
      request.request.client_metadata['x-codex-turn-metadata'] = JSON.stringify(request.metadata);
      fs.writeFileSync(request.requestPath, JSON.stringify(request.request));
      f.addResponse(request);
      f.endTurn();
      f.events.push({
        schema_version: 1,
        seq: f.events.length + 1,
        wall_time_unix_ms: 5,
        rollout_id: f.predictionThreadId,
        thread_id: null,
        codex_turn_id: null,
        payload: { type: 'rollout_ended', status: 'completed' },
      });
      f.saveEvents();
      const old = new Date(Date.now() - 10000);
      fs.utimesSync(f.logPath, old, old);
      assert.equal(
        (await processTrace(new DesktopTraceRetention(root, 'host-a'), f)).state,
        'retired',
      );
      assert.deepEqual(
        new DesktopPredictionTraceReader(root, () => 'host-a').readSourcePredictions(
          f.predictionThreadId,
          f.predictionTurnId,
          0,
          true,
        ),
        [],
      );
    }),
);

test('unknown retirement outcome is never retried with a new intent', () =>
  withRoot(async (root) => {
    const f = fixture(root);
    const name = basename(f.directory);
    const retention = new DesktopTraceRetention(root, 'host-a');
    const receipt = retention.retirementIntent(name, []);
    const before = fs.readFileSync(receipt);
    assert.equal((await retention.process(name)).state, 'pending-retirement');
    assert.equal(
      (await new DesktopTraceRetention(root, 'host-a').process(name)).state,
      'pending-retirement',
    );
    assert.deepEqual(fs.readFileSync(receipt), before);
    assert(fs.existsSync(f.directory));
  }));

test(
  'sealed ordinary trace with more than twenty thousand payloads can retire',
  { skip: process.platform !== 'darwin' },
  () =>
    withRoot(async (root) => {
      const f = fixture(root);
      const configEvent = f.events.find((e) => e.payload.event_type === 'session_configured');
      const configPath = join(f.directory, configEvent.payload.event_payload.path);
      const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
      config.thread_source = 'user';
      delete config.forked_from_id;
      fs.writeFileSync(configPath, JSON.stringify(config));
      for (let i = 1000000; i < 1020001; i++)
        fs.writeFileSync(join(f.directory, 'payloads', i + '.json'), '{}', { mode: 0o600 });
      const result = await new DesktopTraceRetention(root, 'host-a').process(basename(f.directory));
      assert.equal(result.state, 'retired');
      assert(result.bytes >= 40002);
      assert(!fs.existsSync(f.directory));
    }),
);

test('oversized prediction log cannot expire without a durable extracted result', () =>
  withRoot(async (root) => {
    const f = fixture(root);
    const end = f.events.at(-1);
    const large =
      f.events
        .slice(0, -1)
        .map((e) => JSON.stringify(e))
        .join('\n') +
      '\n' +
      ' '.repeat(8 * 1024 * 1024) +
      '\n' +
      JSON.stringify(end) +
      '\n';
    fs.writeFileSync(f.logPath, large);
    const old = new Date(Date.now() - 25 * 3600_000);
    fs.utimesSync(f.logPath, old, old);
    const result = await new DesktopTraceRetention(root, 'host-a').process(basename(f.directory));
    assert.equal(result.state, 'large-prediction-or-source-unverified');
    assert(fs.existsSync(f.directory));
  }));

test(
  'Windows confirmed busy refusal releases only its unstarted intent and retries after close',
  { skip: process.platform !== 'win32' },
  () =>
    withRoot(async (root) => {
      const f = fixture(root);
      const retention = new DesktopTraceRetention(root, 'host-a');
      const name = basename(f.directory);
      const fd = fs.openSync(f.logPath, 'r');
      try {
        assert.equal((await retention.process(name)).state, 'native-refused-before-deletion');
        assert(fs.existsSync(f.directory));
        assert(!fs.existsSync(retention.retirementPath(name)));
      } finally {
        fs.closeSync(fd);
      }
      assert.equal((await retention.process(name)).state, 'retired');
      assert.equal(read(root, f)[0].text, 'Synthetic follow-up suggestion.');
    }),
);

test('later retry of an older failed execution cannot suppress a newer successful request', () =>
  withRoot(async (root) => {
    const old = fixture(root, undefined, 10, 'failed');
    const newer = fixture(
      root,
      execution({ sourceThreadId: old.sourceThreadId, sourceTurnId: old.sourceTurnId }),
      100,
      'completed',
      'newest',
    );
    const rollout = old.events.pop();
    const retry = old.addRequest({ recovery: true });
    retry.event.wall_time_unix_ms = 1000;
    old.events.pop();
    const end = old.events.findIndex((e) => e.payload.type === 'codex_turn_ended');
    old.events.splice(end, 0, retry.event);
    old.events.slice(end + 1).forEach((e, i) => (e.wall_time_unix_ms = 1001 + i));
    rollout.wall_time_unix_ms = 1010;
    old.events.push(rollout);
    old.events.forEach((e, i) => (e.seq = i + 1));
    old.saveEvents();
    const quiet = new Date(Date.now() - 10000);
    fs.utimesSync(old.logPath, quiet, quiet);
    assert.equal(read(root, old)[0].text, 'newest');
    const retention = new DesktopTraceRetention(root, 'host-a');
    await processTrace(retention, newer);
    await processTrace(retention, old);
    assert.equal(read(root, old)[0].text, 'newest');
  }));

test(
  'payload ancestor substitution during async quarantine hashing preserves external files and intent',
  { skip: process.platform !== 'darwin' },
  () =>
    withRoot(async (root) => {
      const f = fixture(root);
      const external = fs.realpathSync(
        fs.mkdtempSync(join(tmpdir(), 'agent-hub-retention-external-')),
      );
      const moved = join(external, 'payloads');
      const originalOpen = fs.promises.open;
      let swapped = false;
      fs.promises.open = async function (path, ...args) {
        if (!swapped && String(path).includes('.agent-hub-retired-')) {
          const retired = fs.readdirSync(root).find((n) => n.startsWith('.agent-hub-retired-'));
          const payloads = join(root, retired, 'payloads');
          fs.renameSync(payloads, moved);
          fs.symlinkSync(moved, payloads, 'dir');
          swapped = true;
        }
        return originalOpen.call(this, path, ...args);
      };
      syncBuiltinESMExports();
      const expected = fs.readdirSync(join(f.directory, 'payloads')).sort();
      try {
        const retention = new DesktopTraceRetention(root, 'host-a');
        await assert.rejects(retention.process(basename(f.directory)));
        assert(swapped);
        assert.deepEqual(fs.readdirSync(moved).sort(), expected);
        assert(fs.existsSync(retention.retirementPath(basename(f.directory))));
        assert(fs.readdirSync(root).some((n) => n.startsWith('.agent-hub-retired-')));
      } finally {
        fs.promises.open = originalOpen;
        syncBuiltinESMExports();
        fs.rmSync(external, { recursive: true, force: true });
      }
    }),
);

test(
  'real retention Worker retires a fixture, preserves a preexisting temp link and exits on close',
  { skip: process.platform !== 'darwin' },
  () =>
    withRoot(async (root) => {
      const f = fixture(root);
      const target = {
        agentId: 'codex',
        hostId: '11111111-1111-4111-8111-111111111111',
        workspaceId: '22222222-2222-4222-8222-222222222222',
        runtimeId: 'desktop-runtime',
      };
      const directory = join(root, 'host-state');
      fs.mkdirSync(directory, { mode: 0o700 });
      const digest = createHash('sha256').update(machineRuntimeTargetKey(target)).digest('hex');
      const statusPath = join(directory, 'trace-retention-' + digest + '.json');
      const protectedFile = join(root, 'protected-status-sentinel');
      fs.writeFileSync(protectedFile, 'must remain unchanged', { mode: 0o600 });
      fs.symlinkSync(protectedFile, statusPath + '.tmp');
      const worker = new Worker(
        new URL(
          '../packages/codex-adapter/dist/desktop-trace-retention-worker.js',
          import.meta.url,
        ),
        { workerData: { traceRoot: root, target, directory } },
      );
      const exited = new Promise((resolve) => worker.once('exit', resolve));
      let timer;
      try {
        const result = await Promise.race([
          new Promise((resolve, reject) => {
            worker.on('error', reject);
            worker.on('message', (value) => {
              if (value.kind === 'retention' && value.retired >= 1) resolve(value);
            });
          }),
          exited.then((code) => {
            throw new Error('worker exited before retirement: ' + code);
          }),
          new Promise((_, reject) => {
            timer = setTimeout(() => reject(new Error('retention Worker timeout')), 30000);
          }),
        ]);
        assert(result.releasedBytes > 0);
        assert(!fs.existsSync(f.directory));
        assert.equal(JSON.parse(fs.readFileSync(statusPath, 'utf8')).retired, 1);
        assert.equal(fs.readFileSync(protectedFile, 'utf8'), 'must remain unchanged');
        assert(fs.lstatSync(statusPath + '.tmp').isSymbolicLink());
        assert.equal(
          read(root, f, machineRuntimeTargetKey(target))[0].text,
          'Synthetic follow-up suggestion.',
        );
      } finally {
        clearTimeout(timer);
        worker.postMessage({ kind: 'close' });
        await exited;
      }
    }),
);

for (const mismatch of ['parent', 'source'])
  test('contradictory prediction ' + mismatch + ' cannot be promoted through compact storage', () =>
    withRoot(async (root) => {
      const f = fixture(root);
      const configuredEvent = f.events.find((e) => e.payload.event_type === 'session_configured');
      const configPath = join(f.directory, configuredEvent.payload.event_payload.path);
      const configured = JSON.parse(fs.readFileSync(configPath, 'utf8'));
      if (mismatch === 'parent') configured.forked_from_id = execution().sourceThreadId;
      else configured.thread_source = 'thread_sample';
      fs.writeFileSync(configPath, JSON.stringify(configured));
      const retention = new DesktopTraceRetention(root, 'host-a');
      assert.equal((await processTrace(retention, f)).state, 'prediction-unverified');
      assert(fs.existsSync(f.directory));
      assert(!fs.existsSync(join(retention.store.directory, f.sourceThreadId + '.json')));
    }),
  );

for (const kind of ['ordinary', 'prediction'])
  test(
    'large legacy vscode trace classifies ' +
      kind +
      ' without assuming a missing source is ordinary',
    { skip: process.platform !== 'darwin' },
    () =>
      withRoot(async (root) => {
        const f = fixture(root);
        const configuredEvent = f.events.find((e) => e.payload.event_type === 'session_configured');
        const configPath = join(f.directory, configuredEvent.payload.event_payload.path);
        const configured = JSON.parse(fs.readFileSync(configPath, 'utf8'));
        delete configured.thread_source;
        fs.writeFileSync(configPath, JSON.stringify(configured));
        if (kind === 'ordinary') {
          const started = f.events.find((e) => e.payload.type === 'inference_started');
          const requestPath = join(f.directory, started.payload.request_payload.path);
          const request = JSON.parse(fs.readFileSync(requestPath, 'utf8'));
          const metadata = JSON.parse(request.client_metadata['x-codex-turn-metadata']);
          for (const key of [
            'thread_source',
            'turn_trigger',
            'prediction_source_thread_id',
            'prediction_source_turn_id',
            'forked_from_thread_id',
          ])
            delete metadata[key];
          request.client_metadata['x-codex-turn-metadata'] = JSON.stringify(metadata);
          request.input = [
            {
              type: 'message',
              role: 'user',
              content: [{ type: 'input_text', text: 'Synthetic legacy desktop user message.' }],
            },
          ];
          fs.writeFileSync(requestPath, JSON.stringify(request));
        }
        fs.writeFileSync(
          join(f.directory, 'payloads', '999999.json'),
          JSON.stringify({ thread_id: f.predictionThreadId, session_source: 'vscode' }),
          { mode: 0o600 },
        );
        fs.writeFileSync(
          join(f.directory, 'payloads', '999998.json'),
          JSON.stringify({ type: 'token_count', info: null, rate_limits: null }),
          { mode: 0o600 },
        );
        const end = f.events.pop();
        f.events.unshift({
          schema_version: 1,
          seq: 0,
          wall_time_unix_ms: 1,
          rollout_id: f.predictionThreadId,
          thread_id: f.predictionThreadId,
          codex_turn_id: null,
          payload: {
            type: 'thread_started',
            metadata_payload: {
              raw_payload_id: 'raw_payload:999999',
              kind: { type: 'thread_metadata' },
              path: 'payloads/999999.json',
            },
          },
        });
        const rows = f.events.map((e, i) => JSON.stringify({ ...e, seq: i + 1 }));
        for (let i = 0; i < 42000; i++)
          rows.push(
            JSON.stringify({
              schema_version: 1,
              seq: rows.length + 1,
              wall_time_unix_ms: 1000 + i,
              rollout_id: f.predictionThreadId,
              thread_id: f.predictionThreadId,
              codex_turn_id: f.predictionTurnId,
              payload: {
                type: 'protocol_event_observed',
                event_type: 'token_count',
                event_payload: {
                  raw_payload_id: 'raw_payload:999998',
                  kind: { type: 'protocol_event' },
                  path: 'payloads/999998.json',
                },
              },
            }),
          );
        rows.push(JSON.stringify({ ...end, seq: rows.length + 1, wall_time_unix_ms: 50000 }));
        const log = rows.join('\n') + '\n';
        assert(Buffer.byteLength(log) > 8 * 1024 * 1024);
        fs.writeFileSync(f.logPath, log);
        const old = new Date(Date.now() - 25 * 3600_000);
        fs.utimesSync(f.logPath, old, old);
        fs.chmodSync(f.directory, 0o755);
        fs.chmodSync(join(f.directory, 'payloads'), 0o755);
        for (const name of fs.readdirSync(join(f.directory, 'payloads')))
          fs.chmodSync(join(f.directory, 'payloads', name), 0o644);
        fs.chmodSync(f.logPath, 0o644);
        fs.chmodSync(join(f.directory, 'manifest.json'), 0o644);
        const result = await processTrace(new DesktopTraceRetention(root, 'host-a'), f);
        assert.equal(
          result.state,
          kind === 'ordinary' ? 'retired' : 'large-prediction-or-source-unverified',
        );
        assert.equal(fs.existsSync(f.directory), kind === 'prediction');
      }),
  );

test(
  'formal retention supervisor retries a missing root after confirmed exit and cancels retry on close',
  { skip: process.platform !== 'darwin' },
  () =>
    withRoot(async (root) => {
      const traceRoot = join(root, 'delayed-traces');
      const target = {
        agentId: 'codex',
        hostId: '11111111-1111-4111-8111-111111111111',
        workspaceId: '22222222-2222-4222-8222-222222222222',
        runtimeId: 'desktop-runtime',
      };
      const directory = join(root, 'host-state');
      const observer = new DesktopSuggestionObserver({
        directory,
        target: () => target,
        enabled: true,
        traceRoot,
        traceRetention: true,
      });
      // Keep the real GC start/restart/close path; suppress the unrelated desktop observer channel.
      observer.workerClient = { start() {}, close() {} };
      observer.start();
      const first = observer.retentionWorker;
      assert(first);
      await new Promise((resolve) => first.once('exit', resolve));
      fs.mkdirSync(traceRoot, { mode: 0o700 });
      const f = fixture(traceRoot);
      const digest = createHash('sha256').update(machineRuntimeTargetKey(target)).digest('hex');
      const status = join(directory, 'trace-retention-' + digest + '.json');
      const until = Date.now() + 15000;
      try {
        while (!fs.existsSync(status) && Date.now() < until)
          await new Promise((resolve) => setTimeout(resolve, 20));
        assert(fs.existsSync(status));
        assert.equal(JSON.parse(fs.readFileSync(status, 'utf8')).retired, 1);
        assert.notEqual(observer.retentionWorker, first);
        assert(!fs.existsSync(f.directory));
      } finally {
        const worker = observer.retentionWorker;
        const exited = worker
          ? new Promise((resolve) => worker.once('exit', resolve))
          : Promise.resolve();
        observer.close();
        await exited;
      }
      assert.equal(observer.retentionRetry, undefined);
    }),
);

test('a later failed attempt keeps one extracted body without exposing it and a new success replaces it', () =>
  withRoot(async (root) => {
    const old = fixture(root, undefined, 10, 'completed', 'previous extracted body');
    const failed = fixture(
      root,
      execution({ sourceThreadId: old.sourceThreadId, sourceTurnId: old.sourceTurnId }),
      100,
      'failed',
    );
    const current = fixture(
      root,
      execution({ sourceThreadId: old.sourceThreadId, sourceTurnId: old.sourceTurnId }),
      200,
      'completed',
      'current extracted body',
    );
    const retention = new DesktopTraceRetention(root, 'host-a');
    await processTrace(retention, old);
    await processTrace(retention, failed);
    const path = join(retention.store.directory, old.sourceThreadId + '.json');
    let saved = JSON.parse(JSON.parse(fs.readFileSync(path, 'utf8')).body);
    assert.equal(saved.prediction, null);
    assert.equal(saved.latestExtracted.prediction.text, 'previous extracted body');
    // A still newer real native request is already present; remove no evidence
    // to make the current failed selection observable, and inspect the cache directly.
    await processTrace(retention, current);
    saved = JSON.parse(JSON.parse(fs.readFileSync(path, 'utf8')).body);
    assert.equal(saved.prediction.text, 'current extracted body');
    assert.equal(saved.latestExtracted, undefined);
    assert(!fs.readFileSync(path, 'utf8').includes('previous extracted body'));
    assert.equal(read(root, current)[0].text, 'current extracted body');
    assert.equal(
      fs.readdirSync(retention.store.directory).filter((name) => /^[a-f0-9-]{36}\.json$/.test(name))
        .length,
      1,
    );
  }));

function nativeReplacement(root, f) {
  const started = f.events.find((e) => e.payload.type === 'inference_started').wall_time_unix_ms;
  const ended = f.events.find((e) => e.payload.type === 'codex_turn_ended').wall_time_unix_ms;
  const producer = {
    epoch: randomUUID(),
    pid: process.pid,
    desktopPid: process.ppid,
    desktopStartedAt: 1,
  };
  const complete = (sourceTurnId, requestSequence, requestedAt, text) => {
    const requestId = `thread/sample:${randomUUID()}`;
    return {
      version: 2,
      kind: 'native-sample',
      targetKey: 'host-a',
      sourceThreadId: f.sourceThreadId,
      sourceTurnId,
      requestId,
      requestSequence,
      requestedAt,
      recordedAt: requestedAt + 3,
      producer,
      state: 'completed',
      result: {
        requestId,
        sourceTurnId,
        text,
        contentSha256: createHash('sha256').update(text).digest('hex'),
        resultSha256: 'a'.repeat(64),
        responseId: null,
        completedAt: requestedAt + 1,
        safety: { decidedAt: requestedAt + 2, evidenceSha256: 'b'.repeat(64) },
      },
    };
  };
  const first = complete(f.sourceTurnId, 1, started - 1, 'obsolete extracted body');
  const latest = complete(randomUUID(), 2, ended + 1, '最新提取正文\nLatest extracted body');
  const store = new DesktopSuggestionStore(root, 'host-a');
  assert.notEqual(first.sourceTurnId, latest.sourceTurnId);
  assert.equal(store.saveRuntime(first), true);
  assert.equal(
    new DesktopSuggestionStore(root, 'host-a').readRuntime(f.sourceThreadId).result.text,
    first.result.text,
  );
  assert.equal(store.saveRuntime(latest), true);
  const slot = join(store.directory, f.sourceThreadId + '.json');
  assert.equal(
    new DesktopSuggestionStore(root, 'host-a').readRuntime(f.sourceThreadId).result.text,
    latest.result.text,
  );
  assert(!fs.readFileSync(slot, 'utf8').includes(first.result.text));
  assert.deepEqual(fs.readdirSync(store.directory), [f.sourceThreadId + '.json']);
  return { store, slot, latest, started, ended };
}

function saveQuietEvents(f) {
  f.saveEvents();
  const quiet = new Date(Date.now() - 10000);
  fs.utimesSync(f.logPath, quiet, quiet);
}

for (const [status, pending] of [
  ['completed', false],
  ['cancelled', false],
  ['completed', true],
])
  test(
    `first GC after two native completions retires missing-body ${status} raw (pending=${pending})`,
    { skip: !['darwin', 'win32'].includes(process.platform) },
    () =>
      withRoot(async (root) => {
        const f = unverifiedPrediction(root);
        f.events.find((e) => e.payload.type === 'codex_turn_ended').payload.status = status;
        saveQuietEvents(f);
        const { store, slot, latest } = nativeReplacement(root, f);
        if (pending) {
          assert.equal(
            store.saveRuntime({
              ...latest,
              sourceTurnId: randomUUID(),
              requestId: `thread/sample:${randomUUID()}`,
              requestSequence: 3,
              requestedAt: latest.recordedAt + 1,
              recordedAt: latest.recordedAt + 1,
              state: 'pending',
              result: null,
            }),
            true,
          );
          assert.equal(
            store.readRuntime(f.sourceThreadId).latestExtracted.result.text,
            latest.result.text,
          );
        }
        const reader = new DesktopPredictionTraceReader(root, () => 'host-a');
        const binding = reader.readBinding(f.predictionThreadId, f.predictionTurnId);
        assert(binding);
        assert.equal(binding.sourceThreadId, f.sourceThreadId);
        assert.equal(binding.sourceTurnId, f.sourceTurnId);
        assert.equal(
          reader.readCompletedPredictionForRetention(f.predictionThreadId, f.predictionTurnId),
          null,
          'the old raw has no recoverable suggestion body',
        );
        const before = fs.readFileSync(slot);
        assert(Date.now() - fs.statSync(f.logPath).mtimeMs < 24 * 60 * 60 * 1000);
        // No earlier GC: A has already lost the sole body slot to B on its first scan.
        const retention = new DesktopTraceRetention(root, 'host-a');
        assert.equal((await processTrace(retention, f)).state, 'retired');
        assert.equal(fs.existsSync(f.directory), false);
        assert.deepEqual(fs.readFileSync(slot), before);
        assert.deepEqual(fs.readdirSync(store.directory), [f.sourceThreadId + '.json']);
        const saved = new DesktopSuggestionStore(root, 'host-a').readRuntime(f.sourceThreadId);
        assert.equal(saved.state, pending ? 'pending' : 'completed');
        assert.equal((saved.result ?? saved.latestExtracted.result).text, latest.result.text);
      }),
  );

for (const failure of ['write-lock', 'flush', 'readback'])
  test(
    `native supersession ${failure} failure preserves raw and the new body until durable retry`,
    { skip: !['darwin', 'win32'].includes(process.platform) },
    () =>
      withRoot(async (root) => {
        const f = unverifiedPrediction(root);
        const { store, slot, started, ended } = nativeReplacement(root, f);
        const retention = new DesktopTraceRetention(root, 'host-a');
        const paths = [
          join(f.directory, 'manifest.json'),
          f.logPath,
          ...fs
            .readdirSync(join(f.directory, 'payloads'))
            .map((name) => join(f.directory, 'payloads', name)),
        ];
        const raw = paths.map((path) => fs.readFileSync(path));
        const body = fs.readFileSync(slot),
          identity = fs.statSync(slot);
        const originalFsync = fs.fsyncSync,
          originalRead = fs.readFileSync;
        const lock = slot + '.write-lock';
        const lockBytes = JSON.stringify({ version: 1, pid: process.pid, token: randomUUID() });
        let injected = 0;
        if (failure === 'write-lock') fs.writeFileSync(lock, lockBytes, { mode: 0o600 });
        if (failure === 'flush')
          fs.fsyncSync = (fd) => {
            const stat = fs.fstatSync(fd);
            if (stat.isFile() && stat.dev === identity.dev && stat.ino === identity.ino) {
              injected++;
              throw new Error('INJECTED_NATIVE_SUPERSESSION_FLUSH_FAILURE');
            }
            return originalFsync(fd);
          };
        if (failure === 'readback')
          fs.readFileSync = (path, options) => {
            const bytes = originalRead(path, options);
            if (path === slot && options === 'utf8') {
              injected++;
              return bytes + '\n';
            }
            return bytes;
          };
        syncBuiltinESMExports();
        try {
          for (let attempt = 0; attempt < 2; attempt++) {
            if (failure === 'write-lock') {
              assert.equal(
                store.acknowledgeRuntimeResult(f.sourceThreadId, f.sourceTurnId, started, ended),
                false,
              );
              assert.equal((await processTrace(retention, f)).state, 'superseded-or-conflicted');
              assert.equal(originalRead(lock, 'utf8'), lockBytes);
            } else {
              await assert.rejects(
                processTrace(retention, f),
                failure === 'flush'
                  ? /INJECTED_NATIVE_SUPERSESSION_FLUSH_FAILURE/
                  : /Compact suggestion readback failed/,
              );
              assert(injected > attempt, 'the real durability fence must be reached on every scan');
            }
            assert(fs.existsSync(f.directory));
            assert.deepEqual(
              paths.map((path) => originalRead(path)),
              raw,
            );
            assert.deepEqual(originalRead(slot), body);
          }
        } finally {
          fs.fsyncSync = originalFsync;
          fs.readFileSync = originalRead;
          syncBuiltinESMExports();
          if (failure === 'write-lock') {
            assert.equal(originalRead(lock, 'utf8'), lockBytes);
            fs.rmSync(lock);
          }
        }
        assert.equal((await processTrace(retention, f)).state, 'retired');
        assert(!fs.existsSync(f.directory));
        assert.deepEqual(fs.readFileSync(slot), body);
      }),
  );

for (const range of ['negative-start', 'negative-end', 'inverted', 'late-retry'])
  test(`invalid ${range} raw range cannot expire a cancellation despite a later native body`, () =>
    withRoot(async (root) => {
      const f = unverifiedPrediction(root);
      const { slot } = nativeReplacement(root, f);
      const start = f.events.find((e) => e.payload.type === 'inference_started');
      const end = f.events.find((e) => e.payload.type === 'codex_turn_ended');
      end.payload.status = 'cancelled';
      if (range === 'negative-start') start.wall_time_unix_ms = -1;
      if (range === 'negative-end') end.wall_time_unix_ms = -1;
      if (range === 'inverted') start.wall_time_unix_ms = end.wall_time_unix_ms + 1;
      if (range === 'late-retry') {
        const retry = structuredClone(start);
        retry.wall_time_unix_ms = end.wall_time_unix_ms + 1;
        retry.payload.inference_call_id = randomUUID();
        f.events.splice(f.events.indexOf(end), 0, retry);
        f.events.forEach((event, index) => (event.seq = index + 1));
      }
      saveQuietEvents(f);
      assert(
        new DesktopPredictionTraceReader(root, () => 'host-a').readBinding(
          f.predictionThreadId,
          f.predictionTurnId,
        ),
        'source identity is still provable',
      );
      const raw = fs.readFileSync(f.logPath),
        body = fs.readFileSync(slot);
      const retention = new DesktopTraceRetention(root, 'host-a');
      assert.equal(
        (await processTrace(retention, f, fs.statSync(f.logPath).mtimeMs + 72 * 60 * 60 * 1000))
          .state,
        'prediction-unverified',
      );
      assert.deepEqual(fs.readFileSync(f.logPath), raw);
      assert.deepEqual(fs.readFileSync(slot), body);
    }));

for (const guard of ['unsealed', 'not-quiet', 'open-handle', 'source-conflict'])
  test(
    `a newer native body does not bypass the ${guard} guard`,
    {
      skip:
        guard === 'open-handle'
          ? process.platform !== 'darwin'
          : !['darwin', 'win32'].includes(process.platform),
    },
    () =>
      withRoot(async (root) => {
        const f = unverifiedPrediction(root);
        const { slot } = nativeReplacement(root, f);
        if (guard === 'unsealed') {
          f.events.pop();
          saveQuietEvents(f);
        }
        if (guard === 'source-conflict') {
          const event = f.events.find((e) => e.payload.event_type === 'session_configured');
          const path = join(f.directory, event.payload.event_payload.path);
          const configured = JSON.parse(fs.readFileSync(path, 'utf8'));
          configured.forked_from_id = randomUUID();
          fs.writeFileSync(path, JSON.stringify(configured));
        }
        const body = fs.readFileSync(slot);
        const fd = guard === 'open-handle' ? fs.openSync(f.logPath, 'r') : undefined;
        try {
          const retention = new DesktopTraceRetention(root, 'host-a');
          if (guard === 'not-quiet') fs.utimesSync(f.logPath, new Date(), new Date());
          const result = await processTrace(retention, f);
          assert.equal(
            result.state,
            guard === 'open-handle'
              ? 'occupied'
              : guard === 'source-conflict'
                ? 'prediction-unverified'
                : 'active-or-unsealed',
          );
          assert(fs.existsSync(f.directory));
          assert.deepEqual(fs.readFileSync(slot), body);
        } finally {
          if (fd !== undefined) fs.closeSync(fd);
        }
      }),
  );

test(
  'a sealed internal cancellation retires only after its matching native result is durable',
  { skip: process.platform !== 'darwin' && process.platform !== 'win32' },
  async () => {
    for (const status of ['cancelled', 'unverified'])
      for (const matching of [true, false])
        await withRoot(async (root) => {
          const f =
            status === 'unverified'
              ? unverifiedPrediction(root)
              : fixture(root, undefined, 10, 'cancelled');
          const started = f.events.find(
            (e) => e.payload.type === 'inference_started',
          ).wall_time_unix_ms;
          const ended = f.events.find(
            (e) => e.payload.type === 'codex_turn_ended',
          ).wall_time_unix_ms;
          const sourceTurnId = matching ? f.sourceTurnId : randomUUID();
          const requestId = 'thread/sample:' + randomUUID(),
            text = 'Durable native result';
          const store = new DesktopSuggestionStore(root, 'host-a');
          assert.equal(
            store.saveRuntime({
              version: 2,
              kind: 'native-sample',
              targetKey: 'host-a',
              sourceThreadId: f.sourceThreadId,
              sourceTurnId,
              requestId,
              requestSequence: 1,
              requestedAt: started - 1,
              recordedAt: ended + 3,
              producer: {
                epoch: randomUUID(),
                pid: process.pid,
                desktopPid: process.ppid,
                desktopStartedAt: 1,
              },
              state: 'completed',
              result: {
                requestId,
                sourceTurnId,
                text,
                contentSha256: createHash('sha256').update(text).digest('hex'),
                resultSha256: 'a'.repeat(64),
                responseId: null,
                completedAt: ended + 1,
                safety: { decidedAt: ended + 2, evidenceSha256: 'b'.repeat(64) },
              },
            }),
            true,
          );
          const retention = new DesktopTraceRetention(root, 'host-a');
          const result = await processTrace(retention, f);
          assert.equal(
            result.state,
            matching
              ? 'retired'
              : status === 'unverified'
                ? 'prediction-unverified'
                : 'diagnostic-retention',
          );
          assert.equal(fs.existsSync(f.directory), !matching);
          assert.equal(store.readRuntime(f.sourceThreadId).result.text, text);
        });
  },
);

test(
  'Windows retirement ignores an inherited incompatible PowerShell module path without changing its parent',
  { skip: process.platform !== 'win32' },
  () =>
    withRoot(async (root) => {
      const poison = join(root, 'incompatible-ps7-modules');
      const module = join(poison, 'Microsoft.PowerShell.Security');
      fs.mkdirSync(module, { recursive: true, mode: 0o700 });
      protectWindowsStateDirectory(poison);
      protectWindowsStateDirectory(module);
      fs.writeFileSync(
        join(module, 'Microsoft.PowerShell.Security.psd1'),
        "@{ RootModule='Fixture.psm1'; ModuleVersion='99.0'; PowerShellVersion='99.0'; GUID='fb5ebba4-13c8-432a-91d5-e03f10ba87ad'; FunctionsToExport=@('Get-Acl'); CmdletsToExport=@(); AliasesToExport=@() }",
        { mode: 0o600 },
      );
      fs.writeFileSync(
        join(module, 'Fixture.psm1'),
        "throw 'SYNTHETIC_INCOMPATIBLE_MODULE_MUST_NOT_LOAD'",
        { mode: 0o600 },
      );
      const previous = process.env.PSModulePath;
      process.env.PSModulePath = poison;
      try {
        const f = fixture(root);
        const result = await processTrace(new DesktopTraceRetention(root, 'host-a'), f);
        assert.equal(result.state, 'retired');
        assert.equal(fs.existsSync(f.directory), false);
        assert.equal(process.env.PSModulePath, poison, 'Only the helper environment is normalized');
      } finally {
        if (previous === undefined) delete process.env.PSModulePath;
        else process.env.PSModulePath = previous;
      }
    }),
);
