import assert from 'node:assert/strict';
import test from 'node:test';
import { runNativeProcess } from './native-process.mjs';

test('native command retains progress and a nonzero exit as failure', async () => {
  const lines = [];
  const result = await runNativeProcess(
    process.execPath,
    ['-e', 'console.log("started"); console.error("failed"); process.exitCode = 7'],
    { timeoutMs: 5000, onLine: (line) => lines.push(line) },
  );
  assert.equal(result.exitCode, 7);
  assert.equal(result.timedOut, false);
  assert(lines.includes('started') && lines.includes('failed'));
});

test('wall deadline kills an unresponsive owned command and descendant', async () => {
  if (process.platform === 'win32') return;
  let descendant;
  const result = await runNativeProcess(
    process.execPath,
    [
      '-e',
      `const { spawn } = require('node:child_process');
       process.on('SIGTERM', () => {});
       const child = spawn(process.execPath, ['-e',
         'process.on("SIGTERM", () => {}); setInterval(() => {}, 1000)'], { stdio: 'inherit' });
       console.log('descendant:' + child.pid);
       setInterval(() => {}, 1000);`,
    ],
    {
      timeoutMs: 500,
      killGraceMs: 100,
      onLine: (line) => {
        if (line.startsWith('descendant:')) descendant = Number(line.slice(11));
      },
    },
  );
  assert.equal(result.timedOut, true);
  assert.notEqual(result.exitCode, 0);
  assert.equal(result.signal, 'SIGKILL');
  assert(descendant);
  // The kernel may retain an adopted zombie briefly; it must not be running.
  const { execFileSync } = await import('node:child_process');
  let state = '';
  try {
    state = execFileSync('ps', ['-o', 'stat=', '-p', String(descendant)], {
      encoding: 'utf8',
    }).trim();
  } catch (error) {
    assert.equal(error.status, 1);
  }
  assert(!state || state.startsWith('Z'), `Owned descendant still active: ${state}`);
});

test('native launch errors reject without leaving signal handlers', async () => {
  const count = process.listenerCount('SIGTERM');
  await assert.rejects(
    runNativeProcess('agent-hub-missing-native-command', [], {
      timeoutMs: 5000,
      onLine: () => {},
    }),
    { code: 'ENOENT' },
  );
  assert.equal(process.listenerCount('SIGTERM'), count);
});
