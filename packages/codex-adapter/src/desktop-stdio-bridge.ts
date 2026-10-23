import { spawn, execFileSync } from 'node:child_process';
import { createServer, type Socket } from 'node:net';
import {
  chmodSync,
  lstatSync,
  mkdirSync,
  readFileSync,
  realpathSync,
  unlinkSync,
  writeFileSync,
  openSync,
  closeSync,
  existsSync,
  fsyncSync,
  renameSync,
} from 'node:fs';
import { dirname, isAbsolute, join } from 'node:path';
import { randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import type { Writable } from 'node:stream';
import { DesktopStdioRouter } from './desktop-stdio-router.js';
import { object, string, type WireObject } from './runtime.js';

interface Profile {
  kind: 'transport-poc';
  codexExecutable: string;
  desktopExecutable: string;
  desktopVersion: string;
  directory: string;
  projectId: string;
  projectDirectory: string;
}
const privateFile = (path: string) => {
  const stat = lstatSync(path);
  if (
    !stat.isFile() ||
    stat.isSymbolicLink() ||
    stat.uid !== process.getuid?.() ||
    stat.mode & 0o077
  )
    throw new Error('Expected a private user-owned profile');
};
function profileFrom(path: string): Profile {
  privateFile(path);
  const profile = JSON.parse(readFileSync(path, 'utf8')) as Profile;
  if (
    profile.kind !== 'transport-poc' ||
    ![
      profile.codexExecutable,
      profile.desktopExecutable,
      profile.directory,
      profile.projectDirectory,
    ].every((value) => typeof value === 'string' && isAbsolute(value)) ||
    !profile.projectId ||
    !profile.desktopVersion
  )
    throw new Error('Invalid transport PoC profile');
  const parentExecutable = execFileSync('/bin/ps', ['-p', String(process.ppid), '-o', 'comm='], {
    encoding: 'utf8',
  }).trim();
  if (realpathSync(parentExecutable) !== realpathSync(profile.desktopExecutable))
    throw new Error('The PoC must be started by the configured original desktop');
  const actualVersion = execFileSync(
    '/usr/libexec/PlistBuddy',
    [
      '-c',
      'Print :CFBundleShortVersionString',
      join(dirname(profile.desktopExecutable), '..', 'Info.plist'),
    ],
    { encoding: 'utf8' },
  ).trim();
  if (actualVersion !== profile.desktopVersion) throw new Error('Original desktop version changed');
  return profile;
}
function atomicPrivateJson(path: string, value: unknown): void {
  const temporary = path + '.' + randomUUID() + '.tmp';
  const fd = openSync(temporary, 'wx', 0o600);
  try {
    writeFileSync(fd, JSON.stringify(value));
    fsyncSync(fd);
  } finally {
    closeSync(fd);
  }
  renameSync(temporary, path);
  const directory = openSync(dirname(path), 'r');
  try {
    fsyncSync(directory);
  } finally {
    closeSync(directory);
  }
}

/** Bound JSONL input, independent of TCP fragmentation; never log transcript contents. */
export function receiveJsonLines(
  maximumBytes: number,
  accept: (message: WireObject) => void,
): (bytes: Buffer) => void {
  let buffer = Buffer.alloc(0);
  return (bytes) => {
    buffer = Buffer.concat([buffer, bytes]);
    for (;;) {
      const index = buffer.indexOf(10);
      if (index < 0) break;
      if (index > maximumBytes) throw new Error('Protocol frame too large');
      const line = buffer.subarray(0, index);
      buffer = buffer.subarray(index + 1);
      if (line.length) accept(object(JSON.parse(line.toString('utf8'))));
    }
    if (buffer.length > maximumBytes) throw new Error('Protocol frame too large');
  };
}
function writer(stream: Writable): (message: WireObject) => void {
  return (message) => {
    const line = JSON.stringify(message) + '\n';
    if (stream.destroyed || stream.writableLength + Buffer.byteLength(line) > 64 * 1024 * 1024)
      throw new Error('Protocol writer unavailable');
    stream.write(line);
  };
}

/**
 * Launch-time research shim. It is not installed into the current app and is not a Host capability.
 * Read-only status is always allowed; a fixed-project text creation probe needs explicit PoC opt-in.
 */
export async function runDesktopStdioBridge(args = process.argv.slice(2)): Promise<void> {
  const path = process.env.CODEX_MANAGE_STDIO_PROFILE;
  if (!path) throw new Error('Missing local transport PoC profile');
  privateFile(path);
  const raw = JSON.parse(readFileSync(path, 'utf8')) as Profile;
  const intercept =
    args.includes('app-server') &&
    !['proxy', 'daemon', 'generate-json-schema', 'generate-ts'].some((command) =>
      args.includes(command),
    ) &&
    process.env.CODEX_INTERNAL_ORIGINATOR_OVERRIDE === 'Codex Desktop';
  if (!intercept) {
    // Version/schema/other native desktop CLI calls retain their normal behavior.
    const child = spawn(raw.codexExecutable, args, { stdio: 'inherit' });
    await new Promise<void>((resolve, reject) => {
      child.once('error', reject);
      child.once('exit', (code) => {
        process.exitCode = code ?? 1;
        resolve();
      });
    });
    return;
  }
  const profile = profileFrom(path);
  mkdirSync(profile.directory, { recursive: true, mode: 0o700 });
  const directoryStat = lstatSync(profile.directory);
  if (
    !directoryStat.isDirectory() ||
    directoryStat.isSymbolicLink() ||
    directoryStat.uid !== process.getuid?.() ||
    directoryStat.mode & 0o077
  )
    throw new Error('Expected a private transport PoC directory');
  const lock = join(profile.directory, 'writer.lock');
  if (existsSync(lock)) {
    privateFile(lock);
    const pid = Number(readFileSync(lock, 'utf8'));
    if (!Number.isSafeInteger(pid) || pid <= 0) throw new Error('Invalid bridge lease');
    let live = true;
    try {
      process.kill(pid, 0);
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'ESRCH') live = false;
    }
    if (live) throw new Error('Original desktop transport already attached');
    unlinkSync(lock);
  }
  const lease = openSync(lock, 'wx', 0o600);
  writeFileSync(lease, String(process.pid));
  closeSync(lease);
  const socketPath = join(profile.directory, 'probe.sock');
  try {
    unlinkSync(socketPath);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error;
  }
  const token = randomBytes(32).toString('base64url');
  if (existsSync(join(profile.directory, 'probe-token')))
    privateFile(join(profile.directory, 'probe-token'));
  writeFileSync(join(profile.directory, 'probe-token'), token, { mode: 0o600 });
  const intentPath = join(profile.directory, 'probe-intents.json');
  if (existsSync(intentPath)) privateFile(intentPath);
  const intents: Record<
    string,
    { phase: 'submitted' | 'returned' | 'outcome-unknown'; method: string; threadId?: string }
  > = existsSync(intentPath) ? JSON.parse(readFileSync(intentPath, 'utf8')) : {};
  const child = spawn(profile.codexExecutable, args, {
    stdio: ['pipe', 'pipe', 'pipe'],
    // Native child tools should resolve the actual official CLI, not re-enter this shim.
    env: { ...process.env, CODEX_CLI_PATH: profile.codexExecutable },
  });
  const router = new DesktopStdioRouter(writer(child.stdin), writer(process.stdout));
  const readDesktop = receiveJsonLines(64 * 1024 * 1024, (message) => router.fromDesktop(message));
  const readUpstream = receiveJsonLines(64 * 1024 * 1024, (message) =>
    router.fromUpstream(message),
  );
  let closing = false;
  const created = new Set<string>(),
    submitted = new Set<string>();
  const sockets = new Set<Socket>();
  const writeEnabled = process.env.CODEX_MANAGE_STDIO_CREATE_POC === '1';
  const server = createServer((socket) => {
    sockets.add(socket);
    socket.once('close', () => sockets.delete(socket));
    const deliver = writer(socket);
    socket.on('error', () => {});
    const read = receiveJsonLines(128 * 1024, (request) => {
      const supplied = Buffer.from(string(request.token)),
        expected = Buffer.from(token);
      if (supplied.length !== expected.length || !timingSafeEqual(supplied, expected)) {
        socket.destroy();
        return;
      }
      const id = string(request.id),
        method = string(request.method),
        params = object(request.params);
      const reject = (message: string) => deliver({ id, error: { code: -32002, message } });
      if (!id || id.length > 100) {
        reject('Invalid operation ID');
        return;
      }
      if (method === 'bridge/status') {
        deliver({
          id,
          result: {
            ...router.status(),
            desktopPid: process.ppid,
            bridgePid: process.pid,
            executorPid: child.pid,
            desktopVersion: profile.desktopVersion,
            writeProbeEnabled: writeEnabled,
          },
        });
        return;
      }
      if (method === 'probe/status') {
        const operationId = string(params.operationId);
        deliver({
          id,
          result: { operation: Object.hasOwn(intents, operationId) ? intents[operationId] : null },
        });
        return;
      }
      if (!writeEnabled) {
        reject('Creation research probe is disabled');
        return;
      }
      if (!router.ready) {
        reject('Original desktop not initialized');
        return;
      }
      if (Number(router.status().pending) >= 256) {
        reject('Original desktop is busy');
        return;
      }
      if (['__proto__', 'prototype', 'constructor'].includes(id) || Object.hasOwn(intents, id)) {
        reject('Operation already submitted or invalid; do not replay');
        return;
      }
      let upstream: WireObject;
      if (method === 'probe/start') {
        if (params.projectId !== profile.projectId) {
          reject('Project not authorized for this probe');
          return;
        }
        upstream = {
          id,
          method: 'thread/start',
          params: {
            projectId: profile.projectId,
            cwd: realpathSync(profile.projectDirectory),
            experimentalRawEvents: false,
            ephemeral: false,
          },
        };
      } else if (method === 'probe/first-text') {
        const threadId = string(params.threadId),
          text = string(params.text);
        if (
          !created.has(threadId) ||
          submitted.has(threadId) ||
          !text.trim() ||
          Buffer.byteLength(text) > 64_000
        ) {
          reject('Only one first text on a thread created by this probe is allowed');
          return;
        }
        submitted.add(threadId);
        upstream = {
          id,
          method: 'turn/start',
          params: { threadId, input: [{ type: 'text', text }] },
        };
      } else {
        reject('Method not supported by this probe');
        return;
      }
      const intent: {
        phase: 'submitted' | 'returned' | 'outcome-unknown';
        method: string;
        threadId?: string;
      } = {
        phase: 'submitted',
        method,
      };
      intents[id] = intent;
      atomicPrivateJson(intentPath, intents); // Mark before writing; crashes must not replay.
      router.probe(upstream, (result) => {
        intent.phase = object(result.error).code === -32001 ? 'outcome-unknown' : 'returned';
        if (method === 'probe/start' && !result.error) {
          const thread = object(object(result.result).thread);
          if (string(thread.id)) {
            created.add(string(thread.id));
            intent.threadId = string(thread.id);
          }
        }
        atomicPrivateJson(intentPath, intents);
        if (!socket.destroyed) deliver(result);
      });
    });
    socket.on('data', (bytes) => {
      try {
        read(bytes);
      } catch {
        socket.destroy();
      }
    });
  });
  const cleanup = () => {
    if (closing) return;
    closing = true;
    router.close();
    for (const socket of sockets) socket.destroy();
    server.close();
    child.kill('SIGTERM');
    for (const file of [socketPath, lock]) {
      try {
        unlinkSync(file);
      } catch {
        /* Already gone. */
      }
    }
  };
  process.stdin.on('data', (bytes: Buffer) => {
    try {
      readDesktop(bytes);
    } catch {
      cleanup();
    }
  });
  child.stdout.on('data', (bytes: Buffer) => {
    try {
      readUpstream(bytes);
    } catch {
      cleanup();
    }
  });
  // Upstream diagnostics can contain private information. Keep them on the original desktop stderr only.
  child.stderr.pipe(process.stderr);
  for (const stream of [child.stdin, process.stdout]) stream.on('error', cleanup);
  process.stdin.once('end', cleanup);
  const exited = new Promise<void>((resolve) => {
    child.once('error', () => {
      cleanup();
      process.exitCode = 1;
      resolve();
    });
    child.once('exit', (code) => {
      cleanup();
      process.exitCode = code ?? 1;
      resolve();
    });
  });
  process.once('SIGTERM', cleanup);
  process.once('SIGINT', cleanup);
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(socketPath, () => {
      chmodSync(socketPath, 0o600);
      resolve();
    });
  }).catch((error) => {
    cleanup();
    throw error;
  });
  try {
    if (!closing)
      atomicPrivateJson(join(profile.directory, 'session.json'), {
        kind: 'transport-poc',
        desktopPid: process.ppid,
        bridgePid: process.pid,
        executorPid: child.pid,
        desktopVersion: profile.desktopVersion,
        realDesktopCreationVerified: false,
      });
  } catch (error) {
    cleanup();
    throw error;
  }
  await exited;
}
