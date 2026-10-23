import { mkdir, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';
import { DesktopThreadPreparer } from '../packages/codex-adapter/dist/desktop-thread-preparer.js';
import { DesktopRuntime, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { openOriginalDesktopThread } from '../packages/codex-adapter/dist/desktop-prepared-bridge.js';

// Real empty handoff probe: metadata only, then official existing-thread URI.
// No model input, protected App Tools or accessibility automation.
const directory = resolve('.local/desktop-prepared-handoff', randomUUID());
await mkdir(directory, { recursive: true, mode: 0o700 });
const cwd = join(
  homedir(),
  'Documents',
  'Codex',
  new Date().toISOString().slice(0, 10),
  'codex-plus-probe-' + randomUUID(),
);
await mkdir(cwd, { recursive: true, mode: 0o700 });
const executable = process.env.CODEX_EXECUTABLE;
if (!executable) throw new Error('Set the installed official CODEX_EXECUTABLE');
const runtime = new DesktopRuntime(
  {
    executable,
    mode: 'desktop-runtime',
    ...(process.env.CODEX_DESKTOP_WINDOWS_CONFIG
      ? { windowsDesktopConfig: process.env.CODEX_DESKTOP_WINDOWS_CONFIG }
      : {}),
  },
  randomUUID(),
  'Empty handoff probe',
);
const report = {
  kind: 'real-empty-thread-handoff-probe',
  modelTurnCalls: 0,
  projectId: process.env.PROBE_PROJECT_ID ?? null,
};
try {
  await runtime.connect();
  report.desktopVersion = runtime.version;
  const prepare = new DesktopThreadPreparer(
    executable,
    process.env.CODEX_HOME ?? join(homedir(), '.codex'),
  );
  report.threadId = await prepare.prepare(
    {
      cwd: process.env.PROBE_PROJECT_DIRECTORY ?? cwd,
      projectId: report.projectId,
      title: '独立空会话接管验收 ' + randomUUID().slice(0, 8),
    },
    {
      starting: (pid) => {
        report.preparationPid = pid;
      },
      identified: (id) => {
        report.threadId = id;
      },
      materialized: () => {
        report.materialized = true;
      },
      exited: (code) => {
        report.preparationExitCode = code;
      },
    },
  );
  const metadata = (
    await runtime.reader.read('thread/read', { threadId: report.threadId, includeTurns: true })
  ).thread;
  report.persistedEmpty = Array.isArray(metadata?.turns) && metadata.turns.length === 0;
  await openOriginalDesktopThread(report.threadId);
  let state;
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    state = await runtime.ipc.follow(report.threadId, 1000, true);
    if (state && runtime.ipc.hasOwner(report.threadId)) break;
    await new Promise((done) => setTimeout(done, 350));
  }
  report.originalOwner = runtime.ipc.hasOwner(report.threadId);
  report.exactOwnerTarget = state?.id === report.threadId && state?.hostId === 'local';
  report.workspaceKind = state?.workspaceKind ?? null;
  report.ownerEmpty = state ? desktopTurns(state).length === 0 : false;
  report.passed =
    report.preparationExitCode === 0 &&
    report.persistedEmpty &&
    report.originalOwner &&
    report.exactOwnerTarget &&
    report.ownerEmpty;
  // Save the snapshot locally for metadata/settings diagnosis; never publish it.
  if (state)
    await writeFile(join(directory, 'owner-snapshot.json'), JSON.stringify(state), { mode: 0o600 });
} catch (error) {
  report.passed = false;
  report.error = error.message;
} finally {
  runtime.close();
  await writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
  console.log(JSON.stringify({ ...report, report: join(directory, 'report.json') }));
  if (!report.passed) process.exitCode = 1;
}
