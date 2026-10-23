import { connect } from 'node:net';
import { readFileSync, writeFileSync, lstatSync, realpathSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join, resolve } from 'node:path';
import { homedir } from 'node:os';
import { randomUUID } from 'node:crypto';
import { DesktopIpc } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { receiveJsonLines } from '../packages/codex-adapter/dist/desktop-stdio-bridge.js';

const directory = resolve('.local/desktop-stdio-poc');
const profile = JSON.parse(readFileSync(join(directory, 'profile.json'), 'utf8'));
const socketPath = join(profile.directory, 'probe.sock');
for (const path of [profile.directory, socketPath, join(profile.directory, 'probe-token')]) {
  const stat = lstatSync(path);
  if (stat.uid !== process.getuid() || stat.mode & 0o077 || stat.isSymbolicLink())
    throw new Error('Unsafe PoC endpoint');
}
const token = readFileSync(join(profile.directory, 'probe-token'), 'utf8').trim();
const socket = connect(socketPath);
const pending = new Map();
socket.on('error', () => {});
socket.on(
  'data',
  receiveJsonLines(8 * 1024 * 1024, (message) => {
    const call = pending.get(message.id);
    if (!call) return;
    pending.delete(message.id);
    clearTimeout(call.timer);
    if (message.error) call.reject(new Error(message.error.message));
    else call.resolve(message.result);
  }),
);
socket.on('close', () => {
  for (const call of pending.values()) {
    clearTimeout(call.timer);
    call.reject(new Error('PoC transport closed; do not resubmit'));
  }
  pending.clear();
});
const request = (method, params = {}) =>
  new Promise((resolveResult, reject) => {
    const id = randomUUID(),
      timer = setTimeout(() => {
        pending.delete(id);
        reject(new Error('PoC result unknown; do not resubmit'));
      }, 30_000);
    pending.set(id, { resolve: resolveResult, reject, timer });
    socket.write(JSON.stringify({ id, method, params, token }) + '\n');
  });
let ipc;
try {
  await new Promise((resolveReady, reject) => {
    socket.once('connect', resolveReady);
    socket.once('error', reject);
  });
  const status = await request('bridge/status');
  const parent = (pid) =>
    Number(
      execFileSync('/bin/ps', ['-p', String(pid), '-o', 'ppid='], { encoding: 'utf8' }).trim(),
    );
  const executable = (pid) =>
    execFileSync('/bin/ps', ['-p', String(pid), '-o', 'comm='], { encoding: 'utf8' }).trim();
  if (
    parent(status.bridgePid) !== status.desktopPid ||
    parent(status.executorPid) !== status.bridgePid ||
    realpathSync(executable(status.desktopPid)) !== realpathSync(profile.desktopExecutable) ||
    realpathSync(executable(status.executorPid)) !== realpathSync(profile.codexExecutable)
  )
    throw new Error('Original process chain could not be confirmed');
  if (!process.argv.includes('--create')) {
    process.stdout.write(JSON.stringify({ ...status, processChainVerified: true }) + '\n');
  } else {
    const index = process.argv.indexOf('--text'),
      text = index >= 0 ? process.argv[index + 1] : undefined;
    if (!text?.trim())
      throw new Error('--create requires explicit --text; status mode never creates a chat');
    if (!status.ready || !status.writeProbeEnabled)
      throw new Error('Original desktop creation PoC not ready');
    const start = await request('probe/start', { projectId: profile.projectId });
    const thread = start.thread;
    const evidence = {
      scope: 'original-desktop-stdio-poc',
      sameExecutorConfirmed: true,
      projectConfirmed:
        thread?.projectId === profile.projectId &&
        realpathSync(thread.cwd) === realpathSync(profile.projectDirectory),
      threadId: thread?.id ?? null,
      originalOwnerConfirmed: false,
      firstTextSubmitted: false,
      realDesktopCreationVerified: false,
    };
    writeFileSync(join(directory, 'acceptance.json'), JSON.stringify(evidence), { mode: 0o600 });
    if (!evidence.projectConfirmed)
      throw new Error('A thread returned, but its project was not confirmed; no first text sent');
    const turn = await request('probe/first-text', { threadId: thread.id, text });
    evidence.firstTextSubmitted = !!turn.turn?.id;
    ipc = new DesktopIpc(join(homedir(), '.codex', 'ipc', 'ipc.sock'));
    await ipc.connect();
    const state = await ipc.follow(thread.id);
    evidence.originalOwnerConfirmed =
      !!state &&
      state.id === thread.id &&
      state.hostId === 'local' &&
      state.resumeState === 'resumed';
    // This first probe still cannot establish the six product acceptance gates.
    writeFileSync(join(directory, 'acceptance.json'), JSON.stringify(evidence), { mode: 0o600 });
    process.stdout.write(JSON.stringify(evidence) + '\n');
  }
} finally {
  ipc?.close();
  socket.destroy();
}
