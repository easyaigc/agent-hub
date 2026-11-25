import assert from 'node:assert/strict';
import { test } from 'node:test';
import { execFileSync, spawn } from 'node:child_process';
import { createRequire, syncBuiltinESMExports } from 'node:module';
import { createServer } from 'node:http';
import fs from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { nativeDesktopChildEnvironment } from './desktop-native-trace-policy.mjs';
import { nativeSuggestionsEnvironment } from './desktop-native-suggestions-environment.mjs';
import { DesktopTraceStorageInventory } from '../packages/codex-adapter/dist/desktop-trace-storage-inventory.js';
import { desktopTraceLifecycle } from '../packages/codex-adapter/dist/desktop-trace-lifecycle.js';
import {
  nativeSuggestionImplementationFingerprint,
  nativeSuggestionProcessInstanceExited,
} from '../packages/codex-adapter/dist/desktop-native-sample-platform.js';

test(
  'shared launcher retries only passive observation while one executor and original frames survive',
  { skip: process.platform !== 'darwin', timeout: 20000 },
  async () => {
    const root = fs.realpathSync(fs.mkdtempSync(join(tmpdir(), 'shared-observer-retry-')));
    fs.chmodSync(root, 0o700);
    const events = join(root, 'events.jsonl'),
      children = join(root, 'children.jsonl');
    fs.writeFileSync(events, '', { mode: 0o600 });
    fs.writeFileSync(children, '', { mode: 0o600 });
    const stub = join(root, 'bridge.mjs');
    fs.writeFileSync(
      stub,
      `import fs from 'node:fs';let attempts=0;const note=v=>fs.appendFileSync(process.env.OWNED_RETRY_EVENTS,JSON.stringify(v)+'\\n');export function createDesktopNativeSampleBridge(){note({kind:'attempt',n:++attempts});if(attempts<3)return undefined;let closed=false;return {get available(){return !closed},observeOutbound(){note({kind:'outbound'})},observeInbound(){note({kind:'inbound'})},close(){if(!closed){closed=true;note({kind:'closed'})}}};}`,
      { mode: 0o600 },
    );
    const original = new URL(
      '../packages/codex-adapter/dist/desktop-shared-launcher.js',
      import.meta.url,
    );
    let source = fs.readFileSync(original, 'utf8');
    source = source.replace(
      /from (['"])(\.[^'"]+)\1/g,
      (_m, _q, spec) =>
        'from ' +
        JSON.stringify(
          spec === './desktop-native-sample-bridge.js'
            ? pathToFileURL(stub).href
            : new URL(spec, original).href,
        ),
    );
    const launcher = join(root, 'launcher.mjs');
    fs.writeFileSync(launcher, source, { mode: 0o600 });
    const { WebSocketServer } = createRequire(
      new URL('../apps/host-agent/package.json', import.meta.url),
    )('ws');
    const server = createServer((_req, res) => {
      res.writeHead(200);
      res.end('ready');
    });
    const websocket = new WebSocketServer({ server });
    const received = [];
    websocket.on('connection', (socket) =>
      socket.on('message', (bytes) => {
        received.push(bytes.toString());
        socket.send(bytes.toString());
      }),
    );
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
    let child, closed;
    let output = '';
    let errors = '';
    const wait = async (predicate) => {
      const end = Date.now() + 10000;
      while (!predicate()) {
        assert(Date.now() < end, 'Owned fixture deadline: ' + errors);
        await new Promise((resolve) => setTimeout(resolve, 25));
      }
    };
    try {
      const fake = `require('node:fs').appendFileSync(process.env.OWNED_RETRY_CHILDREN,JSON.stringify({pid:process.pid})+'\\n');process.on('SIGTERM',()=>process.exit(0));setInterval(()=>{},1000);`;
      const args = ['-e', fake, '--', 'app-server', '--analytics-default-enabled'];
      const run = `import {runDesktopSharedLauncher} from ${JSON.stringify(pathToFileURL(launcher).href)};await runDesktopSharedLauncher(process.execPath,${JSON.stringify(args)});`;
      child = spawn(process.execPath, ['--input-type=module', '-e', run], {
        env: {
          ...process.env,
          AGENT_HUB_NATIVE_SUGGESTIONS_CONFIG: join(root, 'unused-native.json'),
          AGENT_HUB_SHARED_APP_SERVER_DIRECTORY: join(root, 'service'),
          AGENT_HUB_SHARED_APP_SERVER_URL: 'ws://127.0.0.1:' + server.address().port,
          CODEX_APP_TOOLS_PIPE_PATH: join(root, 'owned-pipe'),
          OWNED_RETRY_EVENTS: events,
          OWNED_RETRY_CHILDREN: children,
        },
        stdio: ['pipe', 'pipe', 'pipe'],
      });
      closed = new Promise((resolve, reject) => {
        child.once('close', resolve);
        child.once('error', reject);
      });
      child.stdout.on('data', (bytes) => (output += bytes));
      child.stderr.on('data', (bytes) => (errors += bytes));
      const before = '{"id":1,"owned":"before-observer-recovery"}';
      const after = '{"id":2,"owned":"after-observer-recovery"}';
      child.stdin.write(before + '\n');
      await wait(() => output.includes(before + '\n'));
      await wait(() => fs.readFileSync(events, 'utf8').includes('"n":3'));
      child.stdin.write(after + '\n');
      await wait(() => output.includes(after + '\n'));
      child.stdin.end();
      assert.equal(await closed, 0, errors);
      assert.equal(output, before + '\n' + after + '\n');
      assert.deepEqual(received, [before, after]);
      assert.equal(fs.readFileSync(children, 'utf8').trim().split('\n').length, 1);
      const rows = fs
        .readFileSync(events, 'utf8')
        .trim()
        .split('\n')
        .map((line) => JSON.parse(line));
      assert.equal(rows.filter((r) => r.kind === 'attempt').length, 3);
      assert.equal(rows.filter((r) => r.kind === 'outbound').length, 1);
      assert.equal(rows.filter((r) => r.kind === 'inbound').length, 1);
      assert.equal(rows.filter((r) => r.kind === 'closed').length, 1);
    } finally {
      child?.stdin.end();
      if (closed) await closed;
      for (const client of websocket.clients) client.terminate();
      await new Promise((resolve) => websocket.close(resolve));
      server.closeAllConnections();
      await new Promise((resolve) => server.close(resolve));
      fs.rmSync(root, { recursive: true });
    }
  },
);

test(
  'actual macOS kernel distinguishes a live task child from its confirmed exit',
  { skip: process.platform !== 'darwin', timeout: 10000 },
  async () => {
    const child = spawn(
      process.execPath,
      [
        '-e',
        "process.stdin.resume(); process.stdin.on('end',()=>process.exit(0)); process.stdout.write('ready\\n');",
      ],
      { stdio: ['pipe', 'pipe', 'pipe'] },
    );
    const closed = new Promise((resolve) => child.once('close', resolve));
    try {
      await new Promise((resolve, reject) => {
        child.stdout.once('data', resolve);
        child.once('error', reject);
      });
      const helper = fileURLToPath(
        new URL('./desktop-completion-notify-observer.py', import.meta.url),
      );
      const first = JSON.parse(
        execFileSync(
          '/usr/bin/python3',
          [
            '-I',
            helper,
            '--inspect-process-instance',
            String(child.pid),
            '--expected-started-at',
            '1',
          ],
          { encoding: 'utf8', timeout: 3000 },
        ),
      );
      assert.equal(first.state, 'replaced');
      assert(Number.isSafeInteger(first.actualStartedAt));
      assert.equal(nativeSuggestionProcessInstanceExited(child.pid, first.actualStartedAt), false);
      child.stdin.end();
      assert.equal(await closed, 0);
      assert.equal(nativeSuggestionProcessInstanceExited(child.pid, first.actualStartedAt), true);
      assert.equal(nativeSuggestionProcessInstanceExited(0, first.actualStartedAt), false);
      assert.equal(nativeSuggestionProcessInstanceExited(child.pid, Date.now() + 60000), false);
    } finally {
      child.stdin.end();
      await closed;
    }
  },
);

test('native fingerprint covers identical scripts in source and physical deployed package layouts', () => {
  const repository = fileURLToPath(new URL('..', import.meta.url));
  const parent = join(repository, '.local');
  fs.mkdirSync(parent, { recursive: true });
  const root = fs.mkdtempSync(join(parent, 'native-fingerprint-deploy-'));
  const adapter = join(root, 'node_modules/@codex-manage/codex-adapter');
  try {
    fs.mkdirSync(adapter, { recursive: true });
    fs.cpSync(join(repository, 'packages/codex-adapter/dist'), join(adapter, 'dist'), {
      recursive: true,
    });
    fs.copyFileSync(
      join(repository, 'packages/codex-adapter/package.json'),
      join(adapter, 'package.json'),
    );
    fs.mkdirSync(join(adapter, 'resources'));
    fs.copyFileSync(
      join(repository, 'scripts/desktop-completion-notify-observer.py'),
      join(adapter, 'resources/desktop-completion-notify-observer.py'),
    );
    fs.mkdirSync(join(root, 'scripts'));
    for (const name of [
      'desktop-native-suggestions-runtime.mjs',
      'desktop-native-trace-policy.mjs',
    ])
      fs.copyFileSync(join(repository, 'scripts', name), join(root, 'scripts', name));
    const entry = pathToFileURL(join(adapter, 'dist/desktop-native-sample-platform.js')).href;
    const run = () =>
      execFileSync(
        process.execPath,
        [
          '--input-type=module',
          '-e',
          `import {nativeSuggestionImplementationFingerprint as fingerprint} from ${JSON.stringify(entry)}; console.log(fingerprint());`,
        ],
        { encoding: 'utf8', timeout: 10000 },
      ).trim();
    assert.equal(run(), nativeSuggestionImplementationFingerprint());
    fs.appendFileSync(
      join(root, 'scripts/desktop-native-trace-policy.mjs'),
      '\n// changed deployment input\n',
    );
    assert.notEqual(run(), nativeSuggestionImplementationFingerprint());
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('normal native execution cannot inherit the full-session recorder while original bytes and settings survive', async () => {
  const root = fs.realpathSync(fs.mkdtempSync(join(tmpdir(), 'native-trace-policy-')));
  const sentinel = join(root, 'unexpected-recording');
  const environment = {
    ...process.env,
    CODEX_ROLLOUT_TRACE_ROOT: sentinel,
    OWNED_TRACE_TEST_SETTING: 'keep',
  };
  const program = `
    const fs = require('node:fs');
    if (process.env.CODEX_ROLLOUT_TRACE_ROOT) fs.writeFileSync(process.env.CODEX_ROLLOUT_TRACE_ROOT, 'recorded');
    if (process.env.OWNED_TRACE_TEST_SETTING !== 'keep') process.exit(8);
    process.stdin.pipe(process.stdout);
  `;
  try {
    const child = spawn(process.execPath, ['-e', program], {
      env: nativeDesktopChildEnvironment(environment),
    });
    const bytes = Buffer.from('{"id":1,"method":"thread/sample"}\n原始协议字节\r\n');
    const chunks = [];
    child.stdout.on('data', (b) => chunks.push(b));
    const result = new Promise((resolve, reject) => {
      child.once('error', reject);
      child.once('close', resolve);
    });
    child.stdin.end(bytes);
    assert.equal(await result, 0);
    assert.deepEqual(Buffer.concat(chunks), bytes);
    assert(!fs.existsSync(sentinel));
    assert.equal(
      environment.CODEX_ROLLOUT_TRACE_ROOT,
      sentinel,
      'parent process environment stays intact',
    );
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

for (const shared of [false, true])
  for (const native of [false, true])
    test(
      `actual desktop launcher child applies native policy (shared=${shared}, native=${native})`,
      { skip: process.platform !== 'darwin' },
      async () => {
        const root = fs.realpathSync(fs.mkdtempSync(join(tmpdir(), 'shared-trace-policy-')));
        fs.chmodSync(root, 0o700);
        const output = join(root, 'child-observation.json');
        const config = join(root, 'native.json');
        fs.writeFileSync(config, '{}', { mode: 0o600 });
        const environment = {
          ...process.env,
          CODEX_ROLLOUT_TRACE_ROOT: join(root, 'raw'),
          OWNED_TRACE_CHILD_OUTPUT: output,
          OWNED_TRACE_SETTING: 'retained',
        };
        delete environment.AGENT_HUB_NATIVE_SUGGESTIONS_CONFIG;
        delete environment.CODEX_APP_TOOLS_PIPE_PATH;
        if (native) environment.AGENT_HUB_NATIVE_SUGGESTIONS_CONFIG = config;
        if (shared) {
          environment.CODEX_APP_TOOLS_PIPE_PATH = join(root, 'task-owned-pipe-hint');
          environment.AGENT_HUB_SHARED_APP_SERVER_URL = 'ws://127.0.0.1:1';
          environment.AGENT_HUB_SHARED_APP_SERVER_DIRECTORY = join(root, 'service');
        }
        const childSource = `require('node:fs').writeFileSync(process.env.OWNED_TRACE_CHILD_OUTPUT, JSON.stringify({recording:!!process.env.CODEX_ROLLOUT_TRACE_ROOT, setting:process.env.OWNED_TRACE_SETTING, shared:process.argv.includes('app-server')}));`;
        const childArgs = [
          '-e',
          childSource,
          '--',
          ...(shared ? ['app-server', '--analytics-default-enabled'] : []),
        ];
        const entry = new URL(
          '../packages/codex-adapter/dist/desktop-shared-launcher.js',
          import.meta.url,
        ).href;
        const outerSource = `import {runDesktopSharedLauncher} from ${JSON.stringify(entry)}; await runDesktopSharedLauncher(process.execPath, ${JSON.stringify(childArgs)});`;
        const child = spawn(process.execPath, ['--input-type=module', '-e', outerSource], {
          env: environment,
          stdio: ['pipe', 'ignore', 'ignore'],
        });
        const exited = new Promise((resolve, reject) => {
          child.once('error', reject);
          child.once('close', resolve);
        });
        try {
          assert.equal(await exited, 0);
          assert.deepEqual(JSON.parse(fs.readFileSync(output, 'utf8')), {
            recording: !native,
            setting: 'retained',
            shared,
          });
          assert.equal(environment.CODEX_ROLLOUT_TRACE_ROOT, join(root, 'raw'));
        } finally {
          fs.rmSync(root, { recursive: true, force: true });
        }
      },
    );

test('incremental accounting includes quarantines and compact state without following external links', async () => {
  const root = fs.realpathSync(fs.mkdtempSync(join(tmpdir(), 'trace-inventory-')));
  const outside = fs.realpathSync(fs.mkdtempSync(join(tmpdir(), 'trace-inventory-outside-')));
  fs.chmodSync(root, 0o700);
  const inventory = new DesktopTraceStorageInventory(root);
  try {
    for (const [folder, bytes] of [
      ['trace-synthetic', 7],
      ['.agent-hub-retired-synthetic', 11],
      ['.agent-hub-suggestions', 13],
    ]) {
      fs.mkdirSync(join(root, folder), { mode: 0o700 });
      fs.writeFileSync(join(root, folder, 'owned'), Buffer.alloc(bytes));
    }
    fs.writeFileSync(join(outside, 'private'), Buffer.alloc(1024));
    fs.symlinkSync(outside, join(root, 'external'));
    for (let i = 0; i < 30; i++) {
      await inventory.slice(1, 1000);
      if (!inventory.snapshot.scanning) break;
    }
    const result = inventory.snapshot;
    assert.equal(result.files, 3);
    assert.equal(result.bytes, 31);
    assert.equal(result.categories.quarantine.bytes, 11);
    assert.equal(result.categories.suggestions.bytes, 13);
    assert.equal(result.skipped, 1);
    assert.equal(result.complete, false);
    assert(fs.existsSync(join(outside, 'private')));
  } finally {
    await inventory.close();
    fs.rmSync(root, { recursive: true, force: true });
    fs.rmSync(outside, { recursive: true, force: true });
  }
});

test('a recording marker from a different rollout or time range cannot close this writer', () => {
  const event = (seq, type, overrides = {}) => ({
    schema_version: 2,
    rollout_id: 'owned',
    seq,
    wall_time_unix_ms: seq,
    payload: { type, status: 'completed' },
    ...overrides,
  });
  for (const marker of [
    event(2, 'recording_completed', { rollout_id: 'other' }),
    event(2, 'recording_completed', { wall_time_unix_ms: 0 }),
  ]) {
    const bytes = Buffer.from(
      [event(1, 'rollout_ended'), marker].map(JSON.stringify).join('\n') + '\n',
    );
    assert.notEqual(desktopTraceLifecycle(bytes, 2, 'owned', 10).state, 'sealed');
  }
});

for (const owned of [true, false])
  test(`native setup disables only its own managed Trace login environment (owned: ${owned})`, () => {
    const root = fs.realpathSync(fs.mkdtempSync(join(tmpdir(), 'native-trace-setup-')));
    fs.chmodSync(root, 0o700);
    try {
      const traceState = join(root, 'trace');
      const traceRoot = join(traceState, 'traces');
      const program = join(root, 'program');
      const applicationPath = join(root, 'Original.app');
      for (const path of [
        traceState,
        traceRoot,
        program,
        join(program, 'scripts'),
        applicationPath,
      ])
        fs.mkdirSync(path, { mode: 0o700 });
      fs.writeFileSync(join(program, 'scripts/desktop-native-suggestions-runtime.mjs'), '', {
        mode: 0o600,
      });
      fs.writeFileSync(
        join(traceState, 'environment.json'),
        JSON.stringify({ traceRoot: owned ? traceRoot : '/unrelated/root' }),
        { mode: 0o600 },
      );
      const targetFile = join(root, 'target.json');
      fs.writeFileSync(
        targetFile,
        JSON.stringify({
          agentId: 'codex',
          hostId: '11111111-1111-4111-8111-111111111111',
          workspaceId: '11111111-1111-4111-8111-111111111111',
          runtimeId: 'desktop-runtime',
        }),
        { mode: 0o600 },
      );
      let disabled = 0,
        configured = 0;
      const api = nativeSuggestionsEnvironment({
        state: join(root, 'native'),
        shared: {
          status: () => ({ url: 'ws://127.0.0.1:1234' }),
          install: () => {
            configured++;
          },
        },
        trace: {
          disable: () => {
            disabled++;
          },
        },
      });
      const status = api.install({
        program,
        executable: process.execPath,
        traceRoot,
        targetFile,
        applicationPath,
      });
      assert.equal(configured, 1);
      assert.equal(disabled, owned ? 1 : 0);
      assert.equal(status.futureTraceRecording, 'disabled');
      assert.equal(status.currentTraceRecording, 'requires-independent-process-check');
      assert.equal(status.currentProducerReady, false);
      assert.equal(status.normalDesktopReopenRequired, true);
    } finally {
      fs.rmSync(root, { recursive: true, force: true });
    }
  });

test('closing during a native directory open closes its late-arriving handle', async () => {
  const root = fs.realpathSync(fs.mkdtempSync(join(tmpdir(), 'trace-inventory-close-')));
  fs.chmodSync(root, 0o700);
  const inventory = new DesktopTraceStorageInventory(root);
  const original = fs.promises.opendir;
  let captured, release, opened;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  const ready = new Promise((resolve) => {
    opened = resolve;
  });
  fs.promises.opendir = async (...args) => {
    const directory = await original(...args);
    if (args[0] === root) {
      captured = directory;
      opened();
      await gate;
    }
    return directory;
  };
  syncBuiltinESMExports();
  try {
    const scanning = inventory.slice();
    await ready;
    await inventory.close();
    release();
    await scanning;
    await assert.rejects(async () => captured.read(), { code: 'ERR_DIR_CLOSED' });
    assert.equal(inventory.snapshot.scanning, false);
  } finally {
    release();
    fs.promises.opendir = original;
    syncBuiltinESMExports();
    await inventory.close();
    await Promise.allSettled([Promise.resolve().then(() => captured?.close())]);
    fs.rmSync(root, { recursive: true, force: true });
  }
});
