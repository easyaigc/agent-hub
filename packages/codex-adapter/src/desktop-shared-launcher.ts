import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { createHash } from 'node:crypto';
import { mkdirSync, lstatSync, readFileSync, writeFileSync, unlinkSync } from 'node:fs';
import { isAbsolute, join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { createServer } from 'node:net';

/** Local executable override, invoked by Electron. No remote endpoint can invoke this launcher. */
export async function runDesktopSharedLauncher(executable: string, args: string[]) {
  if (!isAbsolute(executable)) throw new Error('Absolute original Codex executable required');
  // The desktop also invokes its executable before native tool initialization,
  // for policy/authentication reads. Preserve those original invocations exactly.
  const shared =
    args.includes('app-server') &&
    args.includes('--analytics-default-enabled') &&
    !args.includes('--remote') &&
    Boolean(process.env.CODEX_APP_TOOLS_PIPE_PATH);
  if (!shared) {
    const child = spawn(executable, args, { stdio: 'inherit' });
    for (const signal of ['SIGINT', 'SIGTERM'] as const)
      process.on(signal, () => child.kill(signal));
    child.on('error', () => process.exit(1));
    child.on('exit', (code) => process.exit(code ?? 1));
    return;
  }
  if (process.platform !== 'darwin')
    throw new Error('Shared desktop launcher currently requires macOS');
  const url = new URL(process.env.AGENT_HUB_SHARED_APP_SERVER_URL ?? '');
  if (
    url.protocol !== 'ws:' ||
    !['127.0.0.1', '[::1]'].includes(url.hostname) ||
    !url.port ||
    url.username ||
    url.password ||
    url.pathname !== '/' ||
    url.search ||
    url.hash
  )
    throw new Error('Explicit loopback shared service required');
  const directory = process.env.AGENT_HUB_SHARED_APP_SERVER_DIRECTORY ?? '';
  if (!isAbsolute(directory)) throw new Error('Private shared service directory required');
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  const info = lstatSync(directory);
  if (
    !info.isDirectory() ||
    info.isSymbolicLink() ||
    info.uid !== process.getuid?.() ||
    info.mode & 0o077
  )
    throw new Error('Shared service directory must be private');
  const ownerPath = join(directory, 'desktop-service-owner.json');
  const fingerprint = createHash('sha256')
    .update(JSON.stringify([executable, args]))
    .digest('hex');
  const identity = {
    electronPid: process.ppid,
    home: process.env.CODEX_HOME,
    url: url.origin,
    fingerprint,
  };
  let child: ReturnType<typeof spawn> | undefined;
  const connection: { socket?: WebSocket } = {};
  let ending = false;
  let ownsService = false;
  const alive = (pid: number) => {
    if (!Number.isSafeInteger(pid) || pid <= 1) return true;
    try {
      process.kill(pid, 0);
      return true;
    } catch (error) {
      return (error as NodeJS.ErrnoException).code !== 'ESRCH';
    }
  };
  try {
    writeFileSync(ownerPath, JSON.stringify({ ...identity, ownerPid: process.pid }), {
      flag: 'wx',
      mode: 0o600,
    });
    ownsService = true;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'EEXIST') throw error;
    const info = lstatSync(ownerPath);
    if (
      !info.isFile() ||
      info.isSymbolicLink() ||
      info.uid !== process.getuid?.() ||
      info.mode & 0o077
    )
      throw new Error('Shared service owner record must be private', { cause: error });
    const ownerBytes = readFileSync(ownerPath, 'utf8');
    const owner = JSON.parse(ownerBytes);
    // A previous normal exit removes this file. After an unclean exit, only
    // reclaim a dead owner's record when its actual endpoint is also unused.
    if (
      !alive(owner.ownerPid) &&
      (owner.electronPid === identity.electronPid || !alive(owner.electronPid))
    ) {
      const available = await new Promise<boolean>((resolve) => {
        const probe = createServer();
        probe.once('error', () => resolve(false));
        probe.listen(Number(url.port), url.hostname.replace(/^\[|\]$/g, ''), () =>
          probe.close(() => resolve(true)),
        );
      });
      if (!available)
        throw new Error('Previous shared service endpoint is still occupied', { cause: error });
      if (readFileSync(ownerPath, 'utf8') !== ownerBytes)
        throw new Error('Shared service owner changed during recovery', { cause: error });
      unlinkSync(ownerPath);
      writeFileSync(ownerPath, JSON.stringify({ ...identity, ownerPid: process.pid }), {
        flag: 'wx',
        mode: 0o600,
      });
      ownsService = true;
    }
    if (!ownsService) {
      for (const [key, value] of Object.entries(identity))
        if (owner[key] !== value)
          throw new Error('Shared service belongs to another desktop or configuration', {
            cause: error,
          });
      process.kill(owner.ownerPid, 0);
    }
  }
  const release = () => {
    if (!ownsService) return;
    try {
      if (JSON.parse(readFileSync(ownerPath, 'utf8')).ownerPid === process.pid)
        unlinkSync(ownerPath);
    } catch {
      /* A missing record cannot authorize another service. */
    }
  };
  const finish = () => {
    if (ending) return;
    ending = true;
    connection.socket?.close();
    if (child) {
      child.kill('SIGTERM');
      setTimeout(() => {
        child?.kill('SIGKILL');
        release();
        process.exit(1);
      }, 3000).unref();
    } else process.exit(0);
  };
  if (ownsService) {
    // Inherit the real Electron child environment. Never reconstruct or copy
    // native tool credentials from another process or configuration file.
    child = spawn(executable, [...args, '--listen', url.origin], {
      stdio: ['ignore', 'ignore', 'inherit'],
    });
    child.on('error', () => {
      release();
      process.exit(1);
    });
    child.on('exit', (code) => {
      release();
      process.exit(code ?? 1);
    });
  }
  for (const signal of ['SIGINT', 'SIGTERM'] as const) process.on(signal, finish);
  const readiness = new URL(url);
  readiness.protocol = 'http:';
  readiness.pathname = '/readyz';
  let ready = false;
  for (let attempt = 0; attempt < 100 && !ending; attempt++) {
    ready = await fetch(readiness, { signal: AbortSignal.timeout(1000) }).then(
      (response) => response.ok,
      () => false,
    );
    if (ready) break;
    await delay(100);
  }
  if (!ready || ending) {
    finish();
    return;
  }
  connection.socket = new WebSocket(url);
  let open = false;
  const queued: string[] = [];
  let queuedBytes = 0;
  const maximum = 32 * 1024 * 1024;
  connection.socket.addEventListener('open', () => {
    open = true;
    for (const line of queued) connection.socket!.send(line);
    queued.length = 0;
    queuedBytes = 0;
  });
  connection.socket.addEventListener('message', (event) => {
    if (
      typeof event.data !== 'string' ||
      Buffer.byteLength(event.data) > maximum ||
      process.stdout.writableLength > maximum
    ) {
      finish();
      return;
    }
    process.stdout.write(event.data + '\n');
  });
  connection.socket.addEventListener('close', finish);
  connection.socket.addEventListener('error', finish);
  process.stdout.on('error', finish);
  createInterface({ input: process.stdin, crlfDelay: Infinity })
    .on('line', (line) => {
      if (!line.trim() || ending) return;
      const length = Buffer.byteLength(line);
      if (
        length > maximum ||
        queuedBytes + length > maximum ||
        connection.socket!.bufferedAmount > maximum
      ) {
        finish();
        return;
      }
      if (open) connection.socket!.send(line);
      else {
        queued.push(line);
        queuedBytes += length;
      }
    })
    .on('close', finish);
}
