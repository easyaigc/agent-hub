import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { homedir } from 'node:os';
import { DesktopNativeQueue } from '../packages/codex-adapter/dist/desktop-native-queue.js';
import { fileURLToPath } from 'node:url';
import { DesktopRuntime, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import {
  assertPrivateFile,
  inspectWindowsPipe,
  protectWindowsStateDirectory,
  protectWindowsStateFile,
  windowsAdapterDigest,
} from '../packages/codex-adapter/dist/desktop-platform.js';

// Native Windows, real desktop IPC and a read-only metadata subprocess. Never
// creates/resumes an execution service or writes a model input or Host config.
assert.equal(process.platform, 'win32', 'Native Windows acceptance required');
process.umask(0o077);
const repository = dirname(dirname(fileURLToPath(import.meta.url)));
const state = join(repository, '.local/desktop-runtime');
const runtimeFile = join(state, 'runtime.json');
const desktopFile = join(state, 'windows-desktop.json');
const threadId = process.env.DESKTOP_WINDOWS_RECONNECT_THREAD_ID;
assert.match(
  threadId ?? '',
  /^[a-f0-9-]{36}$/i,
  'An exact existing original-owner chat is required',
);
for (const file of [runtimeFile, desktopFile]) assertPrivateFile(file);
const { hostId } = JSON.parse(readFileSync(runtimeFile, 'utf8'));
const original = readFileSync(desktopFile);
const directory = join(state, 'reconnect-acceptance-' + randomUUID());
mkdirSync(directory, { mode: 0o700 });
protectWindowsStateDirectory(directory);
const staleFile = join(directory, 'stale-desktop.json');
const missingCli = join(directory, 'removed-installation', 'codex.exe');
writeFileSync(
  staleFile,
  JSON.stringify({
    ...JSON.parse(original),
    executable: join(directory, 'removed-installation', 'Codex.exe'),
    archive: join(directory, 'removed-installation', 'resources', 'app.asar'),
  }),
  { mode: 0o600, flag: 'wx' },
);
protectWindowsStateFile(staleFile);
const beforeOwner = inspectWindowsPipe();
const options = {
  mode: 'desktop-runtime',
  windowsDesktopConfig: staleFile,
  executable: missingCli,
};
const queue = new DesktopNativeQueue(
  () => options.executable,
  process.env.CODEX_HOME ?? join(homedir(), '.codex'),
);
const runtime = new DesktopRuntime(options, hostId, 'Read-only Windows reconnect acceptance');
const snapshot = async () => {
  await runtime.call('thread/resume', { threadId });
  assert(runtime.ready && runtime.ipc.hasOwner(threadId));
  const turns = desktopTurns(runtime.ipc.state(threadId) ?? {});
  const users = turns.flatMap((turn) =>
    (turn.items ?? [])
      .filter((item) => item.type === 'userMessage')
      .map((item) => ({
        turnId: turn.turnId,
        id: item.id,
        sha256: createHash('sha256').update(JSON.stringify(item)).digest('hex'),
      })),
  );
  assert(users.length, 'Independent original owner must expose real existing input');
  return users;
};
try {
  await runtime.connect();
  assert.notEqual(
    options.executable,
    missingCli,
    'All metadata helpers must see the verified current CLI',
  );
  await queue.list(threadId); // Actual read-only queue helper constructed with the stale startup hint.
  const before = await snapshot();
  // Only this diagnostic follower disconnects; the desktop and formal Host stay.
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Diagnostic IPC close unconfirmed')), 10_000);
    runtime.onDisconnect = () => {
      clearTimeout(timer);
      resolve();
    };
    runtime.ipc.close();
  });
  // Exercise the real metadata-process exit barrier and stale in-memory CLI.
  await runtime.reader.configureExecutable(missingCli);
  assert(!runtime.reader.ready);
  await runtime.connect();
  assert.deepEqual(await snapshot(), before, 'No model input or source turn may be added');
  const afterOwner = inspectWindowsPipe();
  for (const key of ['pid', 'startedAtUnixMs', 'executableSha256'])
    assert.equal(afterOwner[key], beforeOwner[key], 'Original desktop identity must remain');
  assert.deepEqual(readFileSync(desktopFile), original, 'Original private config must remain');
  const result = {
    kind: 'real-windows-readonly-reconnect',
    checkedAt: new Date().toISOString(),
    hostId,
    threadId,
    adapterSha256: windowsAdapterDigest(),
    gates: {
      staleDiskPaths: true,
      staleMemoryCli: true,
      metadataHelpersRefreshed: true,
      realPipeOwner: true,
      metadataExitConfirmed: true,
      sameOriginalInputs: true,
      originalConfigPreserved: true,
      originalDesktopUnchanged: true,
    },
    userInputCount: before.length,
    modelInputsSubmitted: 0,
    limitation:
      'Stale configuration is isolated; the running desktop was not actually upgraded or restarted.',
  };
  const report = join(directory, 'result.json');
  writeFileSync(report, JSON.stringify(result, null, 2) + '\n', { mode: 0o600, flag: 'wx' });
  protectWindowsStateFile(report);
  assertPrivateFile(report);
  console.log(JSON.stringify({ ...result, report }));
} finally {
  queue.close();
  runtime.ipc.close();
  await runtime.reader.configureExecutable(join(directory, 'no-next-reader'));
  runtime.close();
}
