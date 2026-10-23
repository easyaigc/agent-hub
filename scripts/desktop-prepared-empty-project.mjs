import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { CodexRuntime } from '../packages/codex-adapter/dist/runtime.js';
import { DesktopRuntime } from '../packages/codex-adapter/dist/desktop-ipc.js';
import {
  protectWindowsStateDirectory,
  protectWindowsStateFile,
} from '../packages/codex-adapter/dist/desktop-platform.js';

// A dedicated saved project fixture only. Never starts or executes a thread.
assert.ok(process.env.CODEX_EXECUTABLE && process.env.PREPARED_ACCEPTANCE_IDENTITY_FILE);
const { hostId } = JSON.parse(
  await readFile(process.env.PREPARED_ACCEPTANCE_IDENTITY_FILE, 'utf8'),
);
const directory = resolve('.local/desktop-prepared-empty-project', randomUUID());
const cwd = join(directory, 'workspace');
await mkdir(cwd, { recursive: true, mode: 0o700 });
protectWindowsStateDirectory(directory);
const runtime = new CodexRuntime({
  mode: 'managed-runtime',
  executable: process.env.CODEX_EXECUTABLE,
});
const witness = new DesktopRuntime(
  {
    mode: 'desktop-runtime',
    executable: process.env.CODEX_EXECUTABLE,
    ...(process.env.CODEX_DESKTOP_WINDOWS_CONFIG
      ? { windowsDesktopConfig: process.env.CODEX_DESKTOP_WINDOWS_CONFIG }
      : {}),
  },
  hostId,
  'Read-only empty project witness',
);
const report = { kind: 'metadata-only-project-fixture', hostId, directory: cwd, modelTurns: 0 };
try {
  await runtime.connect();
  report.result = await runtime.call('project/create', {
    name: '独立桥接零聊天项目验收 ' + randomUUID().slice(0, 8),
    roots: [{ path: cwd }],
    idempotencyKey: randomUUID(),
  });
  report.projectId = report.result.project.id;
  runtime.close();
  await witness.connect();
  const catalog = await witness.reader.desktopWorkspace();
  assert.ok(catalog.projects.some((project) => project.id === report.projectId));
  assert.ok(
    !catalog.threads.some(
      (thread) =>
        thread.projectId === report.projectId || thread.projectIds?.includes(report.projectId),
    ),
  );
  report.empty = true;
} catch (error) {
  report.error = error.message;
  process.exitCode = 1;
} finally {
  runtime.close();
  witness.close();
  const file = join(directory, 'report.json');
  await writeFile(file, JSON.stringify(report, null, 2), { mode: 0o600 });
  protectWindowsStateFile(file);
  console.log(
    JSON.stringify({
      projectId: report.projectId,
      empty: report.empty === true,
      modelTurns: 0,
      error: report.error,
      report: file,
    }),
  );
}
