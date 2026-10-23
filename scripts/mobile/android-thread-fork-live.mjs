import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createHash, randomUUID, randomBytes } from 'node:crypto';
import { spawn, execFile, execFileSync } from 'node:child_process';
import { mkdir, readFile, writeFile, readdir } from 'node:fs/promises';
import { resolve, join, dirname } from 'node:path';
import { homedir } from 'node:os';
import { createInterface } from 'node:readline';
import { promisify } from 'node:util';
import { DesktopRuntime, desktopTurns } from '../../packages/codex-adapter/dist/desktop-ipc.js';
import { CodexRuntime } from '../../packages/codex-adapter/dist/runtime.js';
import { DesktopThreadPreparer } from '../../packages/codex-adapter/dist/desktop-thread-preparer.js';
import {
  openOriginalDesktopThread,
  preparedAdapterDigest,
} from '../../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { installAndroidApkIfNeeded } from './android-install-if-needed.mjs';

// Own isolated Host and Git repo; original desktop alone executes. No user config or formal Host writes.
const executable = process.env.CODEX_EXECUTABLE;
const serials = (process.env.ANDROID_SERIALS ?? process.env.ANDROID_SERIAL ?? '').split(',');
assert(
  executable &&
    serials.length &&
    serials.every(
      (s) =>
        /^emulator-\d+$/.test(s) ||
        (process.env.ANDROID_ALLOW_PHYSICAL === '1' && /^[a-zA-Z0-9]+$/.test(s)),
    ),
);
const sdk = JSON.parse(await readFile('.local/mobile-toolchain.json', 'utf8')).androidSdk;
const adb = resolve(process.env.ANDROID_HOME ?? sdk, 'platform-tools/adb');
const run = promisify(execFile);
const android = async (serial, ...args) =>
  (
    await run(adb, ['-s', serial, ...args], {
      encoding: 'utf8',
      timeout: 900_000,
      maxBuffer: 32 * 1024 * 1024,
    })
  ).stdout;
const root = resolve('.local/android-development/upstream-migration/UM03/live-' + Date.now());
await mkdir(root, { recursive: true, mode: 0o700 });
const reusePath = process.env.THREAD_FORK_SOURCE_REPORT;
if (reusePath)
  assert(
    resolve(reusePath).startsWith(
      resolve('.local/android-development/upstream-migration/UM03/live-'),
    ),
  );
const prior = reusePath ? JSON.parse(await readFile(reusePath, 'utf8')) : null;
const state = join(root, 'host'),
  repo = prior ? join(dirname(resolve(reusePath)), 'repository') : join(root, 'repository');
await mkdir(state, { mode: 0o700 });
if (!prior) await mkdir(repo, { mode: 0o700 });
const hostId = randomUUID(),
  nonce = randomUUID().slice(0, 8),
  controlKey = randomBytes(32).toString('base64url');
const home = process.env.CODEX_HOME ?? join(homedir(), '.codex');
const git = (...args) => execFileSync('git', ['-C', repo, ...args], { encoding: 'utf8' });
if (!prior) {
  git('init', '--quiet');
  await writeFile(join(repo, 'tracked.txt'), 'base\n');
  git('add', 'tracked.txt');
  git(
    '-c',
    'user.name=Agent Hub DEV',
    '-c',
    'user.email=dev@example.invalid',
    'commit',
    '--quiet',
    '-m',
    'Synthetic fork base',
  );
}
const witness = new DesktopRuntime(
  { mode: 'desktop-runtime', executable },
  hostId,
  'UM03 independent original owner witness',
);
const report = {
  step: 'UM03',
  status: 'running',
  hostId,
  nonce,
  serials,
  adapterSha256: preparedAdapterDigest(),
  commands: [],
  hosts: [],
  devices: [],
  scenarios: [],
};
const save = () =>
  writeFile(join(root, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
const fingerprint = async () => {
  const files = [
    ...new Set(
      execFileSync(
        'git',
        [
          'ls-files',
          '-co',
          '--exclude-standard',
          'apps/android',
          'apps/host-agent',
          'packages/mobile-core',
          'packages/protocol',
          'packages/codex-adapter',
          'scripts/mobile/android-thread-fork-live.mjs',
        ],
        { encoding: 'utf8' },
      )
        .trim()
        .split('\n')
        .filter(Boolean),
    ),
  ].sort();
  const h = createHash('sha256');
  for (const file of files)
    h.update(file + '\0')
      .update(await readFile(file))
      .update('\0');
  return h.digest('hex');
};
report.sourceSha256 = await fingerprint();
await save();
const wait = async (probe, label, ms = 180_000) => {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    const value = await probe();
    if (value) return value;
    await new Promise((r) => setTimeout(r, 400));
  }
  throw Error('Timed out: ' + label);
};
let host,
  endpoint,
  token,
  proxy,
  control,
  active,
  blockQueries = false,
  dropFork = false;
const closeHost = async () => {
  if (host && host.exitCode === null) {
    const closed = new Promise((r) => host.once('close', r));
    host.kill('SIGTERM');
    await closed;
  }
};
const launchHost = async () => {
  let address;
  host = spawn(process.execPath, ['apps/host-agent/dist/index.js'], {
    env: {
      ...process.env,
      CODEX_RUNTIME_MODE: 'desktop-runtime',
      CODEX_EXECUTABLE: executable,
      HOST_AGENT_STATE_DIR: state,
      HOST_AGENT_ROOT: repo,
      HOST_AGENT_NAME: 'UM03 isolated ' + nonce,
      HOST_AGENT_BIND: '127.0.0.1',
      HOST_AGENT_PORT: '0',
      HOST_AGENT_DISCOVERY: '0',
      CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: join(state, 'bridge.json'),
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  host.stderr.resume();
  createInterface({ input: host.stdout }).on('line', (line) => {
    try {
      const value = JSON.parse(line);
      if (value.service === 'host-agent') address = value.address;
    } catch {
      /* startup requires structured evidence */
    }
  });
  await wait(
    () => {
      if (host.exitCode !== null) throw Error('Owned Host exited');
      return address;
    },
    'owned Host startup',
    30_000,
  );
  endpoint = 'http://127.0.0.1:' + address.port;
  token = (await readFile(join(state, 'access-token'), 'utf8')).trim();
  report.hosts.push({ pid: host.pid, startedAt: new Date().toISOString() });
  await save();
};
const request = async (path, body) => {
  const response = await fetch(endpoint + path, {
    method: body ? 'POST' : 'GET',
    headers: {
      Authorization: 'Bearer ' + token,
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    ...(body ? { body: JSON.stringify({ hostId, ...body }) } : {}),
    signal: AbortSignal.timeout(40_000),
  });
  const value = await response.json();
  assert(response.ok, 'Owned Host HTTP ' + response.status);
  return value;
};
const owner = async (id) =>
  wait(
    async () => {
      const value = await witness.ipc.follow(id, 3000, true);
      if (!value || !witness.ipc.hasOwner(id)) return;
      assert.equal(value.id, id);
      assert.equal(value.hostId, 'local');
      return value;
    },
    'exact original owner ' + id,
    45_000,
  );
const completed = async (id, marker) =>
  wait(async () => {
    const value = await owner(id);
    const turn = desktopTurns(value).at(-1);
    return turn?.status === 'completed' &&
      turn.items?.some((i) => i.type === 'agentMessage' && i.text?.includes(marker))
      ? { value, turn }
      : null;
  }, 'original model result ' + marker);
const reply = (res, code, value) => {
  res.writeHead(code, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(value));
};
const listen = (server) =>
  new Promise((done, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => done(server.address().port));
  });
let sourceId, turnId, original, baseline;
try {
  await witness.connect();
  report.desktopVersion = witness.version;
  await writeFile(
    join(state, 'runtime.json'),
    JSON.stringify({
      hostId,
      projects: [],
      threads: [],
      receipts: [],
      attachments: [],
      creations: {},
    }),
    { mode: 0o600 },
  );
  await writeFile(
    join(state, 'bridge.json'),
    JSON.stringify({ hostId, desktopVersion: witness.version }),
    { mode: 0o600 },
  );
  let firstMarker, laterMarker;
  if (!prior) {
    const metadata = new CodexRuntime({ mode: 'managed-runtime', executable });
    let project;
    try {
      await metadata.connect();
      project = (
        await metadata.call('project/create', {
          name: 'UM03 fork ' + nonce,
          roots: [{ path: repo }],
          idempotencyKey: randomUUID(),
        })
      ).project;
    } finally {
      metadata.close();
    }
    assert(project?.id);
    const preparer = new DesktopThreadPreparer(executable, home);
    let preparerPid, preparerExit;
    try {
      sourceId = await preparer.prepare(
        { cwd: repo, projectId: project.id, title: 'UM03_FORK_' + nonce },
        {
          starting: (pid) => (preparerPid = pid),
          identified: () => {},
          materialized: () => {},
          exited: (code) => (preparerExit = code),
        },
      );
    } finally {
      preparer.close();
    }
    assert.equal(preparerExit, 0);
    assert.throws(() => process.kill(preparerPid, 0));
    await openOriginalDesktopThread(sourceId);
    assert.equal(desktopTurns(await owner(sourceId)).length, 0);
    await launchHost();
    const initial = await request('/v1/history?hostId=' + hostId + '&threadId=' + sourceId);
    assert.equal(initial.runtime.canControl, true);
    firstMarker = 'UM03_FIRST_' + nonce;
    laterMarker = 'UM03_LATER_' + nonce;
    await request('/v1/replies', {
      threadId: sourceId,
      clientMessageId: randomUUID(),
      text:
        'The FIRST marker is ' +
        firstMarker +
        '. Remember it. Reply only ' +
        firstMarker +
        '. Do not use tools.',
      attachmentIds: [],
      mode: 'next-turn',
      model: 'gpt-6.1-sol',
      effort: 'xhigh',
    });
    const first = await completed(sourceId, firstMarker);
    turnId = first.turn.turnId;
    assert(turnId);
    await request('/v1/replies', {
      threadId: sourceId,
      clientMessageId: randomUUID(),
      text:
        'The LATER marker is ' +
        laterMarker +
        '. Remember it too. Reply only ' +
        laterMarker +
        '. Do not use tools.',
      attachmentIds: [],
      mode: 'next-turn',
      effort: 'xhigh',
    });
    await completed(sourceId, laterMarker);
    original = desktopTurns(await owner(sourceId));
    assert.equal(original.length, 2);
    await writeFile(join(repo, 'tracked.txt'), 'base\nstaged\n');
    git('add', 'tracked.txt');
    await writeFile(join(repo, 'tracked.txt'), 'base\nstaged\nunstaged\n');
    await writeFile(join(repo, 'untracked.txt'), 'UM03_UNTRACKED_' + nonce);
    baseline = {
      head: git('rev-parse', 'HEAD'),
      branch: git('symbolic-ref', 'HEAD'),
      diff: git('diff', 'HEAD', '--binary'),
      staged: git('diff', '--cached', '--binary'),
      untracked: await readFile(join(repo, 'untracked.txt'), 'utf8'),
    };
    report.source = {
      threadId: sourceId,
      turnId,
      projectId: project.id,
      firstMarker,
      laterMarker,
      preparerPid,
      preparerExit,
    };
    await writeFile(
      join(root, 'source-original.json'),
      JSON.stringify({ original, baseline }, null, 2),
      { mode: 0o600 },
    );
  } else {
    assert(prior.step === 'UM03' && prior.source && prior.source.preparerExit === 0);
    sourceId = prior.source.threadId;
    turnId = prior.source.turnId;
    firstMarker = prior.source.firstMarker;
    laterMarker = prior.source.laterMarker;
    const before = JSON.parse(
      await readFile(join(dirname(resolve(reusePath)), 'source-original.json'), 'utf8'),
    );
    original = desktopTurns(await owner(sourceId));
    assert.deepEqual(original, before.original);
    assert.equal(original.length, 2);
    baseline = before.baseline;
    for (const [key, args] of [
      ['head', ['rev-parse', 'HEAD']],
      ['branch', ['symbolic-ref', 'HEAD']],
      ['diff', ['diff', 'HEAD', '--binary']],
      ['staged', ['diff', '--cached', '--binary']],
    ])
      assert.equal(git(...args), baseline[key]);
    assert.equal(await readFile(join(repo, 'untracked.txt'), 'utf8'), baseline.untracked);
    report.source = prior.source;
    report.sourceReused = {
      priorReportSha256: hash(await readFile(reusePath)),
      sameOwnerTwoTurns: true,
      noRepeatedPrimerModel: true,
    };
    await writeFile(
      join(root, 'source-original.json'),
      JSON.stringify({ original, baseline }, null, 2),
      { mode: 0o600 },
    );
    await launchHost();
    assert.equal(
      (await request('/v1/history?hostId=' + hostId + '&threadId=' + sourceId)).runtime.canControl,
      true,
    );
  }
  await save();
  proxy = createServer(
    (req, res) =>
      void (async () => {
        try {
          assert.equal(req.headers.authorization, 'Bearer ' + token);
          const url = new URL(req.url, 'http://localhost');
          assert(url.pathname.startsWith('/v1/'));
          const chunks = [];
          for await (const c of req) chunks.push(c);
          const bytes = Buffer.concat(chunks);
          const fork = req.method === 'POST' && url.pathname === '/v1/threads/fork';
          if (fork && dropFork) blockQueries = true; // Fence native status observers before the forward.
          if (fork) {
            const input = JSON.parse(bytes);
            assert.equal(input.hostId, hostId);
            assert.equal(input.threadId, sourceId);
            assert.equal(input.turnId, turnId);
            report.commands.push({
              serial: active.serial,
              method: 'POST',
              path: url.pathname,
              input,
            });
            await save();
          }
          if (req.method === 'GET' && url.pathname === '/v1/threads/fork' && blockQueries) {
            res.destroy();
            return;
          }
          const response = await fetch(endpoint + url.pathname + url.search, {
            method: req.method,
            headers: {
              Authorization: 'Bearer ' + token,
              ...(bytes.length ? { 'Content-Type': 'application/json' } : {}),
            },
            ...(bytes.length ? { body: bytes } : {}),
            signal: AbortSignal.timeout(40_000),
          });
          const data = Buffer.from(await response.arrayBuffer());
          if (fork && dropFork) {
            const input = JSON.parse(bytes);
            const confirmed = await wait(
              async () => {
                const observed = await request(
                  '/v1/threads/fork?hostId=' +
                    hostId +
                    '&threadId=' +
                    sourceId +
                    '&id=' +
                    input.clientOperationId,
                );
                assert.notEqual(observed.phase, 'rejected');
                return observed.phase === 'confirmed' ? observed : null;
              },
              'original desktop owner before response loss',
              20_000,
            );
            active.confirmedBeforeLoss = confirmed;
            await save();
            dropFork = false;
            blockQueries = true;
            active.responseDropped = true;
            res.destroy();
            return;
          }
          res.writeHead(response.status, {
            'Content-Type': 'application/json',
            'Cache-Control': 'no-store',
          });
          res.end(data);
        } catch {
          res.destroy();
        }
      })(),
  );
  const proxyPort = await listen(proxy);
  control = createServer(
    (req, res) =>
      void (async () => {
        try {
          assert.equal(req.headers.authorization, 'Bearer ' + controlKey);
          assert.equal(req.method, 'POST');
          for await (const _ of req) {
            /* bounded empty control body */
          }
          if (req.url === '/restart-and-release') {
            assert(
              blockQueries &&
                active.responseDropped &&
                active.confirmedBeforeLoss &&
                !active.restarted,
            );
            const oldPid = host.pid;
            await closeHost();
            assert.throws(() => process.kill(oldPid, 0));
            await launchHost();
            active.restarted = { oldPid, newPid: host.pid };
            blockQueries = false;
            await save();
            reply(res, 200, { ok: true });
          } else if (req.url === '/observe') {
            active.observed = true;
            reply(res, 200, { ok: true });
          } else reply(res, 404, {});
        } catch {
          reply(res, 500, { error: 'Owned examiner control failed' });
        }
      })(),
  );
  const controlPort = await listen(control);
  for (const serial of serials) {
    assert.equal((await android(serial, 'get-state')).trim(), 'device');
    if (!serial.startsWith('emulator-'))
      assert(
        !/\bshowing=true\b/.test(await android(serial, 'shell', 'dumpsys', 'window', 'policy')),
        'Physical device unlock is still pending; no native execution started',
      );
    active = {
      serial,
      status: 'running',
      api: Number((await android(serial, 'shell', 'getprop', 'ro.build.version.sdk')).trim()),
    };
    report.devices.push(active);
    dropFork = true;
    blockQueries = false;
    await save();
    for (const [packageName, apk] of [
      [
        'com.easyai.agenthub.android.debug',
        '.build/native/android/outputs/apk/debug/android-debug.apk',
      ],
      [
        'com.easyai.agenthub.android.debug.test',
        '.build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk',
      ],
    ])
      await installAndroidApkIfNeeded({
        adb,
        serial,
        packageName,
        apk,
        allowPhysical: !serial.startsWith('emulator-'),
      });
    const before = (
      await run(
        adb,
        [
          '-s',
          serial,
          'exec-out',
          'run-as',
          'com.easyai.agenthub.android.debug',
          'tar',
          '-cf',
          '-',
          'no_backup',
          'files',
        ],
        { encoding: 'buffer', maxBuffer: 128 * 1024 * 1024 },
      )
    ).stdout;
    await writeFile(join(root, 'before-' + serial + '.tar'), before, { mode: 0o600 });
    active.privateAppSnapshotSha256 = hash(before);
    const fixture = {
      hostId,
      threadId: sourceId,
      turnId,
      firstMarker,
      laterMarker,
      sameMarker: 'UM03_SAME_' + nonce + '_' + serial,
      worktreeMarker: 'UM03_TREE_' + nonce + '_' + serial,
      endpoint: 'http://127.0.0.1:' + proxyPort,
      token,
      control: 'http://127.0.0.1:' + controlPort,
      controlKey,
    };
    const config = join(root, 'fixture-' + serial + '.json');
    await writeFile(config, JSON.stringify(fixture), { mode: 0o600 });
    await android(serial, 'reverse', 'tcp:' + proxyPort, 'tcp:' + proxyPort);
    await android(serial, 'reverse', 'tcp:' + controlPort, 'tcp:' + controlPort);
    await android(serial, 'push', config, '/data/local/tmp/um03-fork-' + nonce + '.json');
    await android(
      serial,
      'shell',
      'run-as',
      'com.easyai.agenthub.android.debug',
      'cp',
      '/data/local/tmp/um03-fork-' + nonce + '.json',
      'files/thread-fork-live.json',
    );
    await android(serial, 'shell', 'rm', '/data/local/tmp/um03-fork-' + nonce + '.json');
    const output = await android(
      serial,
      'shell',
      'am',
      'instrument',
      '-w',
      '-r',
      '-e',
      'class',
      'com.agenthub.android.MobileThreadForkLiveTest',
      'com.easyai.agenthub.android.debug.test/androidx.test.runner.AndroidJUnitRunner',
    );
    await writeFile(join(root, 'native-' + serial + '.log'), output, { mode: 0o600 });
    try {
      await android(
        serial,
        'pull',
        '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/UM03-live',
        join(root, 'screens-' + serial),
      );
    } catch (error) {
      active.screenshotCollectionError = error.message;
      await save();
    }
    assert(
      output.includes('OK (1 test)') &&
        !/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed/.test(output),
      'All actual native fork assertions',
    );
    assert(active.observed && active.responseDropped && active.restarted);
    const records = await Promise.all(
      (await readdir(join(state, 'desktop-forks')))
        .filter((f) => f.endsWith('.json'))
        .map(async (f) => JSON.parse(await readFile(join(state, 'desktop-forks', f), 'utf8'))),
    );
    const commands = report.commands.filter((c) => c.serial === serial);
    assert.equal(commands.length, 2);
    for (const environment of ['same-directory', 'worktree']) {
      const input = commands.find((c) => c.input.environment === environment).input;
      const record = records.find((r) => r.clientOperationId === input.clientOperationId);
      assert.equal(record.phase, 'confirmed');
      assert.equal(record.turnId, turnId);
      assert.equal(record.preparationExitCode, 0);
      assert.throws(() => process.kill(record.preparationPid, 0));
      const value = await owner(record.forkedThreadId),
        turns = desktopTurns(value);
      assert.equal(turns.length, 2);
      assert.equal(turns[0].turnId, turnId);
      assert(!JSON.stringify(turns).includes(laterMarker));
      assert.equal(turns[1].items.filter((i) => i.type === 'userMessage').length, 1);
      const marker = environment === 'worktree' ? fixture.worktreeMarker : fixture.sameMarker;
      assert(
        turns[1].items.some(
          (i) =>
            i.type === 'agentMessage' && i.text?.includes(firstMarker) && i.text?.includes(marker),
        ),
      );
      if (environment === 'same-directory') assert.equal(resolve(value.cwd), repo);
      else {
        assert.notEqual(resolve(value.cwd), repo);
        assert.equal(
          await readFile(join(value.cwd, 'tracked.txt'), 'utf8'),
          'base\nstaged\nunstaged\n',
        );
        assert.equal(await readFile(join(value.cwd, 'untracked.txt'), 'utf8'), baseline.untracked);
      }
      await writeFile(
        join(root, 'owner-' + serial + '-' + environment + '.json'),
        JSON.stringify(value, null, 2),
        { mode: 0o600 },
      );
      report.scenarios.push({
        serial,
        environment,
        operationId: input.clientOperationId,
        threadId: record.forkedThreadId,
        turnId,
        preparationExitCode: 0,
        cwd: value.cwd,
        inheritedTurns: 1,
        laterExcluded: true,
        oneNativeInput: true,
        actualModel: turns[1].model ?? null,
        actualEffort: turns[1].effort ?? null,
      });
    }
    assert.deepEqual(desktopTurns(await owner(sourceId)), original);
    for (const [key, args] of [
      ['head', ['rev-parse', 'HEAD']],
      ['branch', ['symbolic-ref', 'HEAD']],
      ['diff', ['diff', 'HEAD', '--binary']],
      ['staged', ['diff', '--cached', '--binary']],
    ])
      assert.equal(git(...args), baseline[key]);
    assert.equal(await readFile(join(repo, 'untracked.txt'), 'utf8'), baseline.untracked);
    active.status = 'passed';
    await save();
    console.log(JSON.stringify({ serial, status: 'passed', scenarios: 2 }));
  }
  assert.equal(await fingerprint(), report.sourceSha256, 'Inputs changed during real DEV');
  report.status = 'passed';
  report.sourceUnchanged = true;
  report.finishedAt = new Date().toISOString();
  await save();
  console.log(JSON.stringify({ status: report.status, report: join(root, 'report.json') }));
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  await save();
  throw error;
} finally {
  proxy?.close();
  control?.close();
  await closeHost();
  witness.close();
}
