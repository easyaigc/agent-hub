import { spawn } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { createInterface } from 'node:readline';

// Disposable metadata-only feasibility probe. Never uses the real CODEX_HOME,
// credentials, existing threads, a model turn, UI, or protected App Tools pipe.
const directory = resolve('.local/desktop-preparation-probe', randomUUID());
const home = join(directory, 'isolated-codex-home');
const cwd = join(directory, 'empty-project');
await mkdir(home, { recursive: true, mode: 0o700 });
await mkdir(cwd, { recursive: true, mode: 0o700 });
const env = Object.fromEntries(
  [
    'PATH',
    'HOME',
    'USER',
    'SHELL',
    'LANG',
    'LC_ALL',
    'TMPDIR',
    'TEMP',
    'TMP',
    'SystemRoot',
    'SYSTEMROOT',
    'WINDIR',
    'ComSpec',
    'COMSPEC',
    'SystemDrive',
    'USERPROFILE',
    'HOMEDRIVE',
    'HOMEPATH',
    'APPDATA',
    'LOCALAPPDATA',
    'PROGRAMFILES',
  ].flatMap((key) => (process.env[key] === undefined ? [] : [[key, process.env[key]]])),
);
env.CODEX_HOME = home;
const executable = process.env.CODEX_EXECUTABLE ?? 'codex';
const calls = [];
const allowed = new Set([
  'initialize',
  'project/create',
  'thread/start',
  'thread/name/set',
  'thread/read',
  'thread/unsubscribe',
  'thread/loaded/list',
]);

const connection = () => {
  const child = spawn(executable, ['app-server', '--stdio'], { cwd, env, stdio: 'pipe' });
  child.stderr.resume();
  let nextId = 0;
  const pending = new Map();
  const exited = new Promise((done) => child.once('exit', (code) => done(code)));
  const fail = () => {
    for (const request of pending.values()) {
      clearTimeout(request.timer);
      request.reject(new Error('Preparation transport unavailable'));
    }
    pending.clear();
  };
  child.once('error', fail);
  child.once('exit', fail);
  createInterface({ input: child.stdout }).on('line', (line) => {
    try {
      const response = JSON.parse(line);
      const request = pending.get(response.id);
      if (!request) return;
      clearTimeout(request.timer);
      pending.delete(response.id);
      if (response.error) request.reject(new Error('Official API rejected ' + request.method));
      else request.resolve(response.result);
    } catch {
      fail();
    }
  });
  return {
    rpc(method, params) {
      if (!allowed.has(method)) throw new Error('Metadata preparation method required');
      calls.push(method);
      const id = ++nextId;
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          pending.delete(id);
          reject(new Error('Metadata preparation timed out'));
        }, 20_000);
        pending.set(id, { method, resolve, reject, timer });
        child.stdin.write(JSON.stringify({ id, method, params }) + '\n', (error) => {
          if (error) fail();
        });
      });
    },
    initialized() {
      child.stdin.write(JSON.stringify({ method: 'initialized' }) + '\n');
    },
    async close() {
      child.stdin.end();
      let timer;
      try {
        return await Promise.race([
          exited,
          new Promise((_, reject) => {
            timer = setTimeout(() => {
              child.kill();
              reject(new Error('Preparation process did not exit cleanly'));
            }, 12_000);
          }),
        ]);
      } finally {
        clearTimeout(timer);
      }
    },
  };
};
const initialize = async (client) => {
  await client.rpc('initialize', {
    clientInfo: { name: 'codex_manage_empty_metadata_probe', version: '0.1.0' },
    capabilities: { experimentalApi: true },
  });
  client.initialized();
};
const report = { kind: 'isolated-empty-thread-preparation', calls, modelTurnCalls: 0 };
let client;
try {
  client = connection();
  await initialize(client);
  const result = await client.rpc('project/create', {
    idempotencyKey: randomUUID(),
    name: 'Empty metadata feasibility project',
    roots: [{ path: cwd }],
  });
  const projectId = result?.project?.id;
  if (typeof projectId !== 'string' || !projectId) throw new Error('Actual project ID required');
  const started = await client.rpc('thread/start', {
    cwd,
    projectId,
    ephemeral: false,
    historyMode: 'legacy',
  });
  const threadId = started?.thread?.id;
  if (typeof threadId !== 'string' || !/^[a-f0-9-]{36}$/.test(threadId))
    throw new Error('Actual thread ID required');
  report.threadId = threadId;
  report.projectId = projectId;
  // Materialize through an official metadata mutation, never a placeholder turn.
  await client.rpc('thread/name/set', { threadId, name: 'Empty metadata feasibility probe' });
  await client.rpc('thread/unsubscribe', { threadId });
  report.preparationExitCode = await client.close();
  client = undefined;
  if (report.preparationExitCode !== 0) throw new Error('Preparation exit required');
  client = connection();
  await initialize(client);
  const persisted = await client.rpc('thread/read', { threadId, includeTurns: true });
  if (
    persisted?.thread?.id !== threadId ||
    persisted.thread.projectId !== projectId ||
    persisted.thread.cwd !== cwd ||
    !Array.isArray(persisted.thread.turns) ||
    persisted.thread.turns.length !== 0
  )
    throw new Error('Persisted empty thread target mismatch');
  const loaded = await client.rpc('thread/loaded/list', {});
  if (!Array.isArray(loaded?.data) || loaded.data.length !== 0)
    throw new Error('Read-only verification must not load a runtime');
  report.readerExitCode = await client.close();
  client = undefined;
  if (report.readerExitCode !== 0) throw new Error('Reader exit required');
  report.persistedEmptyThread = true;
  report.projectAssignmentMatches = true;
  report.noLoadedExecution = true;
  report.passed = true;
} catch (error) {
  report.passed = false;
  report.error = error instanceof Error ? error.message : 'Preparation probe failed';
  process.exitCode = 1;
} finally {
  if (client) {
    try {
      await client.close();
    } catch {
      report.cleanupFailed = true;
    }
  }
  const bytes = JSON.stringify(report, null, 2);
  const path = join(directory, 'report.json');
  await writeFile(path, bytes, { mode: 0o600 });
  console.log(
    JSON.stringify({
      kind: report.kind,
      passed: report.passed,
      persistedEmptyThread: report.persistedEmptyThread,
      projectAssignmentMatches: report.projectAssignmentMatches,
      noLoadedExecution: report.noLoadedExecution,
      modelTurnCalls: report.modelTurnCalls,
      report: path,
      reportSha256: createHash('sha256').update(bytes).digest('hex'),
      error: report.error,
    }),
  );
}
