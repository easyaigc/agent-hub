import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { spawn, execFileSync } from 'node:child_process';
import { createInterface } from 'node:readline';
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { DesktopRuntime, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { CodexRuntime } from '../packages/codex-adapter/dist/runtime.js';
import { DesktopThreadPreparer } from '../packages/codex-adapter/dist/desktop-thread-preparer.js';
import {
  openOriginalDesktopThread,
  preparedAdapterDigest,
} from '../packages/codex-adapter/dist/desktop-prepared-bridge.js';

// Dedicated synthetic Git repo/Host. Execution belongs exclusively to original desktop owners.
const nonce = randomUUID().slice(0, 8);
const directory = resolve('.local/thread-fork-dev/live-' + nonce);
const physical = process.env.THREAD_FORK_PHYSICAL === '1';
const repo = join(directory, 'repository'),
  state = physical ? resolve('.local/desktop-runtime') : join(directory, 'host');
const executable =
  process.env.CODEX_EXECUTABLE ??
  '/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex';
const home = process.env.CODEX_HOME ?? join(process.env.HOME, '.codex');
const hostId = physical
  ? JSON.parse(await readFile(join(state, 'runtime.json'), 'utf8')).hostId
  : randomUUID();
await mkdir(repo, { recursive: true, mode: 0o700 });
if (!physical) await mkdir(state, { mode: 0o700 });
const report = {
  kind: 'real-desktop-thread-fork',
  nonce,
  hostId,
  adapterSha256: preparedAdapterDigest(),
  scenarios: [],
  native: null,
  physical,
};
const save = () =>
  writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
const git = (...args) => execFileSync('git', ['-C', repo, ...args], { encoding: 'utf8' }).trim();
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
const witness = new DesktopRuntime(
  { mode: 'desktop-runtime', executable },
  hostId,
  'Fork independent witness',
);
let child, server, endpoint, key;
const wait = async (probe, label, milliseconds = 180000) => {
  const end = Date.now() + milliseconds;
  while (Date.now() < end) {
    const value = await probe();
    if (value) return value;
    await new Promise((done) => setTimeout(done, 400));
  }
  throw Error('Timed out: ' + label);
};
const request = async (path, body) => {
  const response = await fetch(endpoint + path, {
    method: body ? 'POST' : 'GET',
    headers: {
      Authorization: 'Bearer ' + key,
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    ...(body ? { body: JSON.stringify({ hostId, ...body }) } : {}),
    signal: AbortSignal.timeout(40000),
  });
  const value = await response.json();
  if (!response.ok)
    throw Error('Host HTTP ' + response.status + ': ' + (value.error ?? value.message ?? ''));
  return value;
};
const owner = async (id) => {
  return wait(
    async () => {
      const value = await witness.ipc.follow(id, 3000, true);
      if (!value || !witness.ipc.hasOwner(id)) return;
      assert.equal(value.id, id);
      assert.equal(value.hostId, 'local');
      return value;
    },
    'exact original desktop owner ' + id,
    45000,
  );
};
const completed = async (id, marker) =>
  wait(async () => {
    const value = await owner(id);
    const turn = desktopTurns(value).at(-1);
    return turn?.status === 'completed' &&
      turn.items?.some((item) => item.type === 'agentMessage' && item.text?.includes(marker))
      ? { value, turn }
      : undefined;
  }, 'original owner result ' + marker);
const launch = async () => {
  if (physical) {
    endpoint = process.env.THREAD_FORK_HOST_ENDPOINT ?? 'http://127.0.0.1:8794';
    key = (await readFile(join(state, 'access-token'), 'utf8')).trim();
    return;
  }
  let address;
  child = spawn(process.execPath, [resolve('apps/host-agent/dist/index.js')], {
    env: {
      ...process.env,
      CODEX_RUNTIME_MODE: 'desktop-runtime',
      CODEX_EXECUTABLE: executable,
      HOST_AGENT_STATE_DIR: state,
      HOST_AGENT_ROOT: repo,
      HOST_AGENT_NAME: '分支 DEV ' + nonce,
      HOST_AGENT_BIND: '127.0.0.1',
      HOST_AGENT_PORT: '0',
      HOST_AGENT_DISCOVERY: '0',
      CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: join(state, 'bridge.json'),
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  child.stderr.resume();
  createInterface({ input: child.stdout }).on('line', (line) => {
    try {
      const value = JSON.parse(line);
      if (value.service === 'host-agent') address = value.address;
    } catch {
      /* Unstructured diagnostics are not startup evidence. */
    }
  });
  await wait(
    () => {
      if (child.exitCode !== null) throw Error('Dedicated Host exited');
      return address;
    },
    'dedicated Host startup',
    30000,
  );
  endpoint = 'http://127.0.0.1:' + address.port;
  key = (await readFile(join(state, 'access-token'), 'utf8')).trim();
};
try {
  await witness.connect();
  report.desktopVersion = witness.version;
  if (!physical)
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
  if (!physical)
    await writeFile(
      join(state, 'bridge.json'),
      JSON.stringify({ hostId, desktopVersion: witness.version }),
      { mode: 0o600 },
    );
  const metadata = new CodexRuntime({ mode: 'managed-runtime', executable });
  let project;
  try {
    await metadata.connect();
    project = (
      await metadata.call('project/create', {
        name: '分支 DEV ' + nonce,
        roots: [{ path: repo }],
        idempotencyKey: randomUUID(),
      })
    ).project;
  } finally {
    metadata.close();
  }
  assert.ok(project?.id);
  const preparer = new DesktopThreadPreparer(executable, home);
  let preparationPid, exitCode;
  let sourceId;
  try {
    sourceId = await preparer.prepare(
      { cwd: repo, projectId: project.id, title: '手机分支验收 ' + nonce },
      {
        starting: (pid) => {
          preparationPid = pid;
        },
        identified: () => {},
        materialized: () => {},
        exited: (code) => {
          exitCode = code;
        },
      },
    );
  } finally {
    preparer.close();
  }
  assert.equal(exitCode, 0);
  assert.throws(() => process.kill(preparationPid, 0));
  await openOriginalDesktopThread(sourceId);
  await wait(
    async () => {
      await witness.ipc.follow(sourceId, 1000, true);
      return witness.ipc.hasOwner(sourceId);
    },
    'source desktop owner',
    45000,
  );
  assert.equal(desktopTurns(await owner(sourceId)).length, 0);
  await launch();
  const workspace = await request('/v1/workspace');
  assert.equal(workspace.hostId, hostId);
  assert.ok(workspace.capabilities.includes('threads:fork'));
  // Zero-turn metadata can be absent from the desktop catalogue until first input.
  // Introduce it only through its already-verified exact original owner.
  const sourceHistory = await request('/v1/history?hostId=' + hostId + '&threadId=' + sourceId);
  assert.equal(sourceHistory.thread.id, sourceId);
  assert.equal(sourceHistory.runtime.canControl, true);
  const firstMarker = 'FORK_FIRST_' + nonce,
    laterMarker = 'FORK_LATER_' + nonce;
  const firstInput =
    'The FIRST marker is ' +
    firstMarker +
    '. Remember it. Reply only ' +
    firstMarker +
    '. Do not use tools.';
  await request('/v1/replies', {
    threadId: sourceId,
    clientMessageId: randomUUID(),
    text: firstInput,
    attachmentIds: [],
    mode: 'next-turn',
    effort: 'xhigh',
  });
  const first = await completed(sourceId, firstMarker);
  const turnId = first.turn.turnId;
  assert.ok(turnId);
  const secondInput =
    'The LATER marker is ' +
    laterMarker +
    '. Remember it too. Reply only ' +
    laterMarker +
    '. Do not use tools.';
  await request('/v1/replies', {
    threadId: sourceId,
    clientMessageId: randomUUID(),
    text: secondInput,
    attachmentIds: [],
    mode: 'next-turn',
    effort: 'xhigh',
  });
  await completed(sourceId, laterMarker);
  const turnPage = await witness.reader.read('thread/turns/list', {
    threadId: sourceId,
    limit: 100,
    sortDirection: 'desc',
    itemsView: 'notLoaded',
  });
  assert.ok(turnPage.data.some((turn) => turn.id === turnId && turn.status === 'completed'));
  report.officialReadonlyTurnPage = true;
  await writeFile(join(repo, 'tracked.txt'), 'base\nstaged\n');
  git('add', 'tracked.txt');
  await writeFile(join(repo, 'tracked.txt'), 'base\nstaged\nunstaged\n');
  await writeFile(join(repo, 'untracked.txt'), 'FORK_UNTRACKED_' + nonce);
  const originalHead = git('rev-parse', 'HEAD'),
    originalDiff = git('diff', 'HEAD', '--binary'),
    originalStagedDiff = git('diff', '--cached', '--binary');
  const options = await request(
    '/v1/threads/fork-options?hostId=' + hostId + '&threadId=' + sourceId,
  );
  assert.equal(options.sameDirectoryAvailable, true);
  assert.equal(options.worktreeAvailable, true);
  report.source = {
    threadId: sourceId,
    turnId,
    projectId: project.id,
    firstMarker,
    laterMarker,
    head: originalHead,
  };
  const fixtures = {
    endpoint,
    key,
    hostId,
    threadId: sourceId,
    title: '手机分支验收 ' + nonce,
    turnId,
    firstMarker,
    laterMarker,
    sameMarker: 'FORK_SAME_' + nonce,
    worktreeMarker: 'FORK_WORKTREE_' + nonce,
    sourceBaseline: { head: originalHead, diff: originalDiff, stagedDiff: originalStagedDiff },
  };
  await writeFile(join(directory, 'fixture.json'), JSON.stringify(fixtures), { mode: 0o600 });
  await save();
  console.log(JSON.stringify({ phase: 'source-ready', directory, sourceId, turnId }));
  if (physical) {
    // Only the human/Device Hub UI submits forks and continuation inputs in this mode.
    // The CLI waits for those exact durable receipts and independently witnesses their owners.
    const records = await wait(
      async () => {
        const files = await readdir(join(state, 'desktop-forks')).catch(() => []);
        const candidates = await Promise.all(
          files
            .filter((x) => x.endsWith('.json'))
            .map(async (file) =>
              JSON.parse(await readFile(join(state, 'desktop-forks', file), 'utf8')),
            ),
        );
        const selected = candidates.filter((r) => r.threadId === sourceId);
        assert.ok(selected.length <= 2, 'Unexpected duplicate physical fork');
        if (selected.length !== 2 || selected.some((r) => r.phase !== 'confirmed')) return;
        for (const record of selected) {
          const turns = desktopTurns(await owner(record.forkedThreadId));
          const marker =
            record.environment === 'same-directory' ? fixtures.sameMarker : fixtures.worktreeMarker;
          if (
            turns.at(-1)?.status !== 'completed' ||
            !turns
              .at(-1)
              ?.items.some(
                (item) =>
                  item.type === 'agentMessage' &&
                  item.text?.includes(firstMarker) &&
                  item.text?.includes(marker),
              )
          )
            return;
        }
        return selected;
      },
      'physical UI forks and model continuations',
      1800000,
    );
    for (const environment of ['same-directory', 'worktree']) {
      const record = records.find((r) => r.environment === environment);
      assert.ok(record);
      assert.equal(record.turnId, turnId);
      assert.equal(record.preparationExitCode, 0);
      const value = await owner(record.forkedThreadId),
        turns = desktopTurns(value);
      assert.equal(turns.length, 2);
      assert.equal(turns[0].turnId, turnId);
      assert.ok(!JSON.stringify(turns).includes(laterMarker));
      assert.equal(turns[1].items.filter((item) => item.type === 'userMessage').length, 1);
      if (environment === 'same-directory') assert.equal(resolve(value.cwd), repo);
      else {
        assert.notEqual(resolve(value.cwd), repo);
        assert.equal(
          await readFile(join(value.cwd, 'tracked.txt'), 'utf8'),
          'base\nstaged\nunstaged\n',
        );
        assert.equal(
          await readFile(join(value.cwd, 'untracked.txt'), 'utf8'),
          'FORK_UNTRACKED_' + nonce,
        );
      }
      report.scenarios.push({
        environment,
        receipt: record.clientOperationId,
        threadId: record.forkedThreadId,
        phase: record.phase,
        cwd: value.cwd,
        physical: true,
        historyPrefixAndModel: true,
        oneNativeInput: true,
      });
    }
  } else if (process.env.THREAD_FORK_NATIVE !== '1') {
    for (const environment of ['same-directory', 'worktree']) {
      const input = { threadId: sourceId, turnId, environment, clientOperationId: randomUUID() };
      await request('/v1/threads/fork', input);
      const receipt = await wait(
        async () => {
          const r = await request(
            '/v1/threads/fork?hostId=' +
              hostId +
              '&threadId=' +
              sourceId +
              '&id=' +
              input.clientOperationId,
          );
          if (r.phase === 'rejected') throw Error(r.error);
          return r.phase === 'confirmed' ? r : undefined;
        },
        'fork confirmation ' + environment,
        60000,
      );
      const fork = await owner(receipt.forkedThreadId),
        turns = desktopTurns(fork);
      assert.equal(turns.length, 1);
      assert.equal(turns[0].turnId, turnId);
      assert.ok(!JSON.stringify(turns).includes(laterMarker));
      if (environment === 'same-directory') assert.equal(resolve(fork.cwd), repo);
      else {
        assert.notEqual(resolve(fork.cwd), repo);
        assert.equal(
          await readFile(join(fork.cwd, 'tracked.txt'), 'utf8'),
          'base\nstaged\nunstaged\n',
        );
        assert.equal(
          await readFile(join(fork.cwd, 'untracked.txt'), 'utf8'),
          'FORK_UNTRACKED_' + nonce,
        );
      }
      const marker =
        environment === 'same-directory' ? fixtures.sameMarker : fixtures.worktreeMarker;
      const text =
        'What is the FIRST marker you remember? Reply with it and ' +
        marker +
        '. Do not use tools.';
      const clientMessageId = randomUUID();
      await request('/v1/replies', {
        threadId: receipt.forkedThreadId,
        clientMessageId,
        text,
        attachmentIds: [],
        mode: 'next-turn',
        effort: 'xhigh',
      });
      const response = await completed(receipt.forkedThreadId, marker);
      assert.ok(
        response.turn.items.some(
          (item) => item.type === 'agentMessage' && item.text?.includes(firstMarker),
        ),
      );
      assert.equal(desktopTurns(response.value).length, 2);
      assert.equal(
        response.turn.items.filter(
          (item) => item.type === 'userMessage' && item.clientId === clientMessageId,
        ).length,
        1,
      );
      assert.deepEqual(await request('/v1/threads/fork', input), receipt);
      report.scenarios.push({
        environment,
        receipt,
        cwd: fork.cwd,
        inheritedTurnCount: 1,
        laterHistoryExcluded: true,
        originalOwnerModelReply: true,
        oneNativeInput: true,
      });
      await save();
      console.log(
        JSON.stringify({ phase: 'fork-passed', environment, threadId: receipt.forkedThreadId }),
      );
    }
  } else {
    const device = process.env.IOS_SIMULATOR_ID;
    assert.ok(device);
    server = createServer((req, res) => {
      void (async () => {
        try {
          if (req.method !== 'GET' || req.headers.origin) {
            res.writeHead(403).end();
            return;
          }
          res.setHeader('Content-Type', 'application/json');
          res.setHeader('Cache-Control', 'no-store');
          if (req.url === '/') res.end(JSON.stringify(fixtures));
          else if (req.url === '/evidence') {
            const files = (await readdir(join(state, 'desktop-forks'))).filter((x) =>
              x.endsWith('.json'),
            );
            const records = await Promise.all(
              files.map(async (file) =>
                JSON.parse(await readFile(join(state, 'desktop-forks', file), 'utf8')),
              ),
            );
            res.end(JSON.stringify({ records, source: desktopTurns(await owner(sourceId)) }));
          } else res.writeHead(404).end();
        } catch {
          res.writeHead(500).end();
        }
      })();
    });
    await new Promise((done, reject) => {
      server.once('error', reject);
      server.listen(8798, '127.0.0.1', done);
    });
    const native = spawn(
      'xcodebuild',
      [
        '-project',
        'apps/ios/CodexManage.xcodeproj',
        '-scheme',
        'CodexManage',
        '-destination',
        'platform=iOS Simulator,id=' + device,
        '-derivedDataPath',
        resolve('.build/ios-thread-fork-real'),
        '-resultBundlePath',
        join(directory, 'native.xcresult'),
        '-parallel-testing-enabled',
        'NO',
        '-collect-test-diagnostics',
        'never',
        '-only-testing:CodexManageUITests/CodexManageUITests/testOriginalDesktopThreadFork',
        'CODE_SIGNING_ALLOWED=YES',
        'CODE_SIGN_IDENTITY=-',
        'ONLY_ACTIVE_ARCH=YES',
        'test',
      ],
      { env: process.env, stdio: ['ignore', 'pipe', 'pipe'] },
    );
    const logs = [];
    for (const stream of [native.stdout, native.stderr])
      createInterface({ input: stream }).on('line', (line) => {
        const safe = line.replaceAll(key, '[REDACTED]');
        logs.push(safe);
        if (/^Test Case|: error:|^\*\* TEST/.test(safe)) console.log(safe);
      });
    const code = await new Promise((done, reject) => {
      native.once('error', reject);
      native.once('exit', done);
    });
    await writeFile(join(directory, 'native.log'), logs.join('\n'), { mode: 0o600 });
    report.native = { code, xcresult: join(directory, 'native.xcresult') };
    await save();
    assert.equal(code, 0);
    const files = (await readdir(join(state, 'desktop-forks'))).filter((x) => x.endsWith('.json'));
    const records = await Promise.all(
      files.map(async (file) =>
        JSON.parse(await readFile(join(state, 'desktop-forks', file), 'utf8')),
      ),
    );
    assert.equal(records.length, 2);
    for (const environment of ['same-directory', 'worktree']) {
      const record = records.find((r) => r.environment === environment);
      assert.ok(record);
      assert.equal(record.phase, 'confirmed');
      assert.equal(record.turnId, turnId);
      const value = await owner(record.forkedThreadId),
        turns = desktopTurns(value);
      assert.equal(turns.length, 2);
      assert.equal(turns[0].turnId, turnId);
      assert.ok(!JSON.stringify(turns).includes(laterMarker));
      const marker =
        environment === 'same-directory' ? fixtures.sameMarker : fixtures.worktreeMarker;
      assert.ok(
        turns[1].items.some(
          (item) =>
            item.type === 'agentMessage' &&
            item.text?.includes(marker) &&
            item.text?.includes(firstMarker),
        ),
      );
      report.scenarios.push({
        environment,
        threadId: record.forkedThreadId,
        phase: record.phase,
        cwd: value.cwd,
        native: true,
        historyPrefixAndModel: true,
      });
    }
  }
  const original = desktopTurns(await owner(sourceId));
  assert.equal(original.length, 2);
  assert.equal(git('rev-parse', 'HEAD'), originalHead);
  assert.equal(git('diff', 'HEAD', '--binary'), originalDiff);
  assert.equal(git('diff', '--cached', '--binary'), originalStagedDiff);
  report.sourceUnchanged = true;
  report.passed = true;
  await save();
  console.log(JSON.stringify({ passed: true, report: join(directory, 'report.json') }));
} catch (error) {
  report.error = error.message;
  await save();
  throw error;
} finally {
  server?.close();
  if (child && child.exitCode === null) {
    const stopped = new Promise((done) => child.once('close', done));
    child.kill('SIGTERM');
    await stopped;
  }
  witness.close();
}
