import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { lstat, realpath, open } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { homedir } from 'node:os';
import { join } from 'node:path';
const exec = promisify(execFile);
const run = async (file, args) =>
  (await exec(file, args, { encoding: 'utf8', timeout: 8000, maxBuffer: 1024 * 1024 })).stdout;
const digest = (x) => createHash('sha256').update(x).digest('hex');
function require(ok, message) {
  if (!ok) throw Error(message);
}
export function processIdentity(text) {
  const match = text
    .trim()
    .match(/^(\d+)\s+(\d+)\s+(\w{3}\s+\w{3}\s+\d{1,2}\s+\d\d:\d\d:\d\d\s+\d{4})\s+(.+)$/);
  require(match, 'Original process identity unavailable');
  return {
    pid: Number(match[1]),
    uid: Number(match[2]),
    startedAtText: match[3],
    executable: match[4],
  };
}
export function mappedExecutable(text, path) {
  let file;
  const matches = [];
  for (const line of text.split('\n')) {
    if (line.startsWith('f')) {
      if (file?.path === path) matches.push(file);
      file = {};
    } else if (file && line.startsWith('D')) file.device = String(BigInt(line.slice(1)));
    else if (file && line.startsWith('i')) file.inode = line.slice(1);
    else if (file && line.startsWith('s')) file.size = line.slice(1);
    else if (file && line.startsWith('n')) file.path = line.slice(1);
  }
  if (file?.path === path) matches.push(file);
  require(matches.length === 1, 'Exact executable txt mapping unavailable');
  return matches[0];
}
export function verifyProcessAndImage(before, after, image, statBefore, statAfter) {
  require(JSON.stringify(before) === JSON.stringify(after), 'Process PID/start/path changed');
  for (const key of ['dev', 'ino', 'size', 'mtimeNs', 'ctimeNs'])
    require(String(statBefore[key]) === String(statAfter[key]), 'Executable identity changed');
  require(image.path === before.executable &&
    image.device === String(statBefore.dev) &&
    image.inode === String(statBefore.ino) &&
    image.size === String(statBefore.size), 'Mapped executable differs from current trusted inode');
  return true;
}
export async function observeDesktopRuntime({
  ipc,
  desktopTurns,
  hostId,
  threadId,
  executable,
  executableSha256,
  projectRoot,
}) {
  const socket = join(homedir(), '.codex', 'ipc', 'ipc.sock');
  return observeAtSocket({
    ipc,
    desktopTurns,
    hostId,
    threadId,
    executable,
    executableSha256,
    projectRoot,
    socket,
  });
}
async function observeAtSocket({
  ipc,
  desktopTurns,
  hostId,
  threadId,
  executable,
  executableSha256,
  projectRoot,
  socket,
}) {
  require(socket.endsWith('/.codex/ipc/ipc.sock') &&
    (await realpath(socket)) === socket, 'Exact desktop socket required');
  const sockBefore = await lstat(socket, { bigint: true });
  require(sockBefore.isSocket() &&
    Number(sockBefore.uid) === process.getuid(), 'Desktop socket owner mismatch');
  const named = await run('/usr/sbin/lsof', ['-nP', '-Fpcu', '--', socket]);
  const ids = [
    ...new Set(
      named
        .split('\n')
        .filter((x) => /^p\d+$/.test(x))
        .map((x) => Number(x.slice(1))),
    ),
  ];
  const candidates = [];
  for (const pid of ids) {
    const value = processIdentity(
      await run('/bin/ps', ['-p', String(pid), '-o', 'pid=,uid=,lstart=,comm=']),
    );
    if (value.executable === executable && value.uid === process.getuid()) candidates.push(value);
  }
  require(candidates.length === 1, 'Unique exact desktop socket process unavailable');
  const before = candidates[0];
  require((await realpath(executable)) === executable, 'Canonical trusted executable required');
  const first = await lstat(executable, { bigint: true });
  require(first.size > 0n && first.size <= 268435456n, 'Executable hash budget exceeded');
  require(first.isFile() &&
    !first.isSymbolicLink() &&
    [0, process.getuid()].includes(Number(first.uid)) &&
    (Number(first.mode) & 0o022) === 0, 'Trusted executable permissions changed');
  const image = mappedExecutable(
    await run('/usr/sbin/lsof', ['-nP', '-a', '-p', String(before.pid), '-d', 'txt', '-FfDins']),
    executable,
  );
  const fd = await open(executable, 'r');
  let actualSha;
  try {
    const stat = await fd.stat({ bigint: true });
    require(stat.dev === first.dev && stat.ino === first.ino, 'Executable swapped before read');
    actualSha = digest(await fd.readFile());
  } finally {
    await fd.close();
  }
  require(actualSha ===
    executableSha256, 'Observed executable SHA differs from trusted installation');
  const state = await ipc.follow(threadId, 5000, true);
  require(state?.id === threadId &&
    state.hostId === 'local' &&
    ipc.hasOwner(threadId), 'Fresh exact original owner unavailable');
  require(!projectRoot || state.cwd === projectRoot, 'Original owner project root mismatch');
  const after = processIdentity(
    await run('/bin/ps', ['-p', String(before.pid), '-o', 'pid=,uid=,lstart=,comm=']),
  );
  const last = await lstat(executable, { bigint: true });
  verifyProcessAndImage(before, after, image, first, last);
  const namedAfter = await run('/usr/sbin/lsof', ['-nP', '-Fpcu', '--', socket]);
  require(namedAfter
    .split('\n')
    .includes('p' + before.pid), 'Desktop lost exact socket association');
  const sockAfter = await lstat(socket, { bigint: true });
  require(sockAfter.dev === sockBefore.dev &&
    sockAfter.ino === sockBefore.ino, 'Socket replaced during observation');
  const identity = {
    hostId,
    runtimeId: 'desktop-runtime',
    pid: before.pid,
    uid: before.uid,
    startedAtText: before.startedAtText,
    executable,
    device: String(first.dev),
    inode: String(first.ino),
    size: String(first.size),
    executableSha256: actualSha,
    socket,
    socketDevice: String(sockBefore.dev),
    socketInode: String(sockBefore.ino),
  };
  return {
    observedAtEpochMs: Date.now(),
    identity,
    fingerprint: runtimeIdentityFingerprint(identity),
    processAndInstallVerified: true,
    source:
      'exact local socket process + ps before/after + mapped txt device/inode + disk SHA + fresh original IPC owner',
    originalOwnerVerified: true,
    threadId,
    turnCount: desktopTurns(state).length,
    state,
  };
}

export function runtimeIdentityFingerprint(identity) {
  const keys = [
    'hostId',
    'runtimeId',
    'pid',
    'uid',
    'startedAtText',
    'executable',
    'device',
    'inode',
    'size',
    'executableSha256',
    'socket',
    'socketDevice',
    'socketInode',
  ];
  require(keys.every((key) => Object.hasOwn(identity, key)), 'Complete observed identity required');
  return digest(JSON.stringify(Object.fromEntries(keys.map((key) => [key, identity[key]]))));
}
