import { createServer } from 'node:https';
import { request } from 'node:http';
import { createReadStream } from 'node:fs';
import { readFile, rm, stat } from 'node:fs/promises';
import { fork, spawn } from 'node:child_process';
import { networkInterfaces } from 'node:os';
import { createRequire } from 'node:module';
import { dirname, extname, join, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import {
  acquireLock,
  alive,
  availablePort,
  assertStartAllowed,
  assertActivationRuntime,
  assertNode24,
  assertId,
  assertBusinessBundle,
  businessMaterial,
  exists,
  hash,
  json,
  ownedProcess,
  processFingerprint,
  privateDir,
  privateFile,
  run,
  sameIdentity,
  save,
  verifyBundle,
  verifyRegistration,
} from './task-hub-core.mjs';

const hopHeaders = new Set([
  'connection',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
]);
// mDNS availability is independent from the already-qualified database/API.
// The library's default datagram-send callback throws outside publish().
export function publishDiscovery(Bonjour, descriptor, onState) {
  let failedOnce = false,
    bonjour;
  const failed = (error) => {
    failedOnce = true;
    onState('unavailable', /^[A-Z0-9_]{1,40}$/.test(error?.code ?? '') ? error.code : 'MDNS_ERROR');
  };
  try {
    bonjour = new Bonjour(undefined, failed);
    const service = bonjour.publish(descriptor);
    service.on('error', failed);
    service.on('up', () => {
      if (!failedOnce) onState('publication-announced; discovery checked separately', null);
    });
    return { bonjour, service };
  } catch (error) {
    failed(error);
    return { bonjour, service: null };
  }
}
export function discoveryServiceName(registration) {
  let name = '';
  for (const character of registration.name) {
    if (Buffer.byteLength(name + character) > 52) break;
    name += character;
  }
  return name.trim() + ' ' + hash(registration.serverInstanceId).slice(0, 8);
}
export function nativeDiscoveryArguments(descriptor, address) {
  if (
    !descriptor ||
    !['agenthub-task', 'agenthub-mdns-qa'].includes(descriptor.type) ||
    (descriptor.protocol !== undefined && descriptor.protocol !== 'tcp') ||
    typeof descriptor.name !== 'string' ||
    !descriptor.name.trim() ||
    Buffer.byteLength(descriptor.name) > 63 ||
    [...descriptor.name].some((character) => character.charCodeAt(0) < 32) ||
    !/^[a-z0-9-]{1,63}\.local$/.test(descriptor.host ?? '') ||
    !Number.isInteger(descriptor.port) ||
    descriptor.port < 1 ||
    descriptor.port > 65535 ||
    !/^\d{1,3}(\.\d{1,3}){3}$/.test(address) ||
    address.split('.').some((n) => Number(n) > 255) ||
    address.startsWith('127.') ||
    address === '0.0.0.0' ||
    Number(address.split('.')[0]) >= 224 ||
    !descriptor.txt ||
    Object.keys(descriptor.txt).some(
      (key) =>
        ![
          'version',
          'deploymentId',
          'serverInstanceId',
          'primaryHostId',
          'name',
          'contractVersion',
          'path',
          'probeOnly',
        ].includes(key),
    )
  )
    throw Error('Invalid native DNS-SD publication');
  for (const [key, value] of Object.entries(descriptor.txt))
    if (
      typeof value !== 'string' ||
      [...value].some((character) => character.charCodeAt(0) < 32) ||
      Buffer.byteLength(key + '=' + value) > 255
    )
      throw Error('Invalid public DNS-SD TXT');
  if (descriptor.type === 'agenthub-task') {
    ['deploymentId', 'serverInstanceId', 'primaryHostId'].forEach((key) =>
      assertId(descriptor.txt[key]),
    );
    if (
      descriptor.txt.version !== '1' ||
      descriptor.txt.contractVersion !== '1' ||
      typeof descriptor.txt.name !== 'string' ||
      !descriptor.txt.name.trim() ||
      descriptor.txt.path !== '/api/v1/service'
    )
      throw Error('Invalid Task Hub DNS-SD contract');
  } else if (descriptor.txt.probeOnly !== '1') throw Error('QA DNS-SD must be explicitly a probe');
  return [
    '-P',
    descriptor.name,
    '_' + descriptor.type + '._tcp',
    'local.',
    String(descriptor.port),
    descriptor.host,
    address,
    ...Object.entries(descriptor.txt).map(([key, value]) => key + '=' + value),
  ];
}
function nativeLanAddress() {
  try {
    const iface = run('/sbin/route', ['-n', 'get', '224.0.0.251']).match(
      /\binterface:\s+([a-zA-Z0-9]+)\b/,
    )?.[1];
    if (!iface || !/^(en\d+|bridge\d+)$/.test(iface)) return null;
    return (
      networkInterfaces()[iface]?.find((a) => a.family === 'IPv4' && !a.internal)?.address ?? null
    );
  } catch {
    return null;
  }
}
// The IPC channel closes when the owning runtime exits, including SIGKILL. The
// helper then terminates its DNS-SD registration; it never opens a DB or API.
export function publishNativeDiscovery(descriptor, onState, { node = process.execPath } = {}) {
  nativeDiscoveryArguments(descriptor, '192.0.2.1');
  let child,
    closed,
    retry,
    stopped = false;
  function start() {
    let nativeResource;
    const current = fork(fileURLToPath(import.meta.url), ['--native-discovery-child'], {
      execPath: node,
      env: { PATH: '/usr/bin:/bin:/usr/sbin:/sbin', LANG: 'C', LC_ALL: 'C' },
      stdio: ['ignore', 'ignore', 'ignore', 'ipc'],
    });
    child = current;
    closed = new Promise((resolveClosed) => {
      current.on('message', (message) => {
        if (child !== current) return;
        if (message?.kind === 'discovery-resource') nativeResource = message.resource;
        if (!stopped && message?.kind === 'discovery-state')
          onState(message.state, message.errorCode);
      });
      current.on('error', () => {
        if (!stopped) onState('unavailable', 'DNS_SD_SIDECAR_ERROR');
      });
      current.on('close', async () => {
        if (!stopped) onState('unavailable', 'DNS_SD_SIDECAR_EXITED');
        let ownerVerified = true;
        try {
          if (nativeResource && alive(nativeResource.pid)) {
            if (processFingerprint(nativeResource.pid) !== nativeResource.fingerprint)
              throw Error('DNS-SD owner changed');
            process.kill(nativeResource.pid, 'SIGTERM');
            for (let i = 0; i < 15 && alive(nativeResource.pid); i++) await pause(100);
            if (alive(nativeResource.pid)) {
              if (processFingerprint(nativeResource.pid) !== nativeResource.fingerprint)
                throw Error('DNS-SD owner changed');
              process.kill(nativeResource.pid, 'SIGKILL');
            }
          }
        } catch {
          ownerVerified = false;
          if (!stopped) onState('unavailable', 'DNS_SD_OWNER_UNVERIFIED');
        } finally {
          if (!stopped && ownerVerified) retry = setTimeout(start, 3000);
          resolveClosed();
        }
      });
      current.send({ action: 'publish', descriptor }, (error) => {
        if (error && !stopped) onState('unavailable', 'DNS_SD_IPC_ERROR');
      });
    });
  }
  start();
  return {
    get pid() {
      return child.pid;
    },
    async destroy() {
      clearTimeout(retry);
      if (!stopped) {
        stopped = true;
        if (child.connected) child.send({ action: 'stop' }, () => {});
        else child.kill('SIGTERM');
      }
      const timer = setTimeout(() => child.kill('SIGTERM'), 2500);
      try {
        await closed;
      } finally {
        clearTimeout(timer);
      }
    },
  };
}
async function nativeDiscoveryChild() {
  if (!process.connected || typeof process.send !== 'function')
    throw Error('DNS-SD helper requires its owner IPC');
  let descriptor,
    native,
    address,
    timer,
    closing = false,
    checking = false;
  let nativeStopping;
  const notify = (state, errorCode = null) => {
    if (process.connected) process.send({ kind: 'discovery-state', state, errorCode }, () => {});
  };
  async function stopNative() {
    if (nativeStopping) return nativeStopping;
    const previous = native;
    if (!previous || previous.exitCode !== null || previous.signalCode !== null) return;
    const exited = new Promise((res) => previous.once('close', res));
    previous.kill('SIGTERM');
    const killTimer = setTimeout(() => previous.kill('SIGKILL'), 1000);
    nativeStopping = exited.finally(() => {
      clearTimeout(killTimer);
      if (native === previous) native = null;
      nativeStopping = null;
    });
    return nativeStopping;
  }
  async function close() {
    if (closing) return;
    closing = true;
    clearTimeout(timer);
    await stopNative();
    if (process.connected) process.disconnect();
  }
  process.once('disconnect', () => {
    void close();
  });
  process.once('SIGTERM', () => {
    void close();
  });
  process.once('SIGINT', () => {
    void close();
  });
  async function tick() {
    if (closing || checking) return;
    checking = true;
    try {
      if (nativeStopping) await nativeStopping;
      const selected = nativeLanAddress();
      if (native && address !== selected) await stopNative();
      if (!selected) notify('unavailable', 'NO_LAN_IPV4');
      else if (!native && !closing) {
        const args = nativeDiscoveryArguments(descriptor, selected);
        address = selected;
        const current = (native = spawn('/usr/bin/dns-sd', args, {
          env: { PATH: '/usr/bin:/bin', LANG: 'C', LC_ALL: 'C' },
          stdio: ['ignore', 'pipe', 'pipe'],
        }));
        current.on('spawn', () => {
          if (process.connected)
            process.send(
              {
                kind: 'discovery-resource',
                resource: { pid: current.pid, fingerprint: processFingerprint(current.pid) },
              },
              () => {},
            );
        });
        let output = '',
          record = false,
          service = false;
        notify('native-publication-requested; discovery checked separately');
        current.stdout.on('data', (bytes) => {
          output += bytes.toString('utf8');
          if (Buffer.byteLength(output) > 65536) {
            notify('unavailable', 'DNS_SD_OUTPUT_LIMIT');
            void stopNative();
            return;
          }
          record ||= output.includes(
            'Got a reply for record ' + descriptor.host + ': Name now registered and active',
          );
          service ||=
            output.includes('Got a reply for service ') &&
            output.includes(
              '._' + descriptor.type + '._tcp.local.: Name now registered and active',
            );
          if (record && service)
            notify('native-record-and-service-registered; discovery checked separately');
          if (
            /:\s*(Error|Name Conflict)|DNSService\w+[^\n]*(failed|error)|\bPolicyDenied\b/i.test(
              output,
            )
          ) {
            notify('unavailable', 'DNS_SD_REGISTRATION_FAILED');
            void stopNative();
          }
        });
        current.stderr.on('data', () => {
          notify('unavailable', 'DNS_SD_REGISTRATION_FAILED');
          void stopNative();
        });
        current.on('error', () => notify('unavailable', 'DNS_SD_PROCESS_ERROR'));
        current.on('close', () => {
          if (process.connected)
            process.send({ kind: 'discovery-resource', resource: null }, () => {});
          if (native === current) {
            native = null;
            if (!closing) notify('unavailable', 'DNS_SD_REGISTRATION_EXITED');
          }
        });
      }
    } catch {
      notify('unavailable', 'DNS_SD_PUBLICATION_FAILED');
    } finally {
      checking = false;
      if (!closing)
        timer = setTimeout(() => {
          void tick();
        }, 3000);
    }
  }
  process.on('message', (message) => {
    if (message?.action === 'stop') {
      void close();
      return;
    }
    if (message?.action !== 'publish' || descriptor || closing) {
      notify('unavailable', 'DNS_SD_INVALID_REQUEST');
      void close();
      return;
    }
    try {
      nativeDiscoveryArguments(message.descriptor, '192.0.2.1');
      descriptor = message.descriptor;
      void tick();
    } catch {
      notify('unavailable', 'DNS_SD_INVALID_REQUEST');
      void close();
    }
  });
}
const mime = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.woff2': 'font/woff2',
  '.json': 'application/json',
};
export async function gateway({ bundle, registration, state, apiPort, key, cert }) {
  const root = resolve(bundle, 'web');
  const server = createServer({ key, cert, minVersion: 'TLSv1.2' }, async (incoming, outgoing) => {
    outgoing.setHeader('X-Content-Type-Options', 'nosniff');
    outgoing.setHeader('Referrer-Policy', 'same-origin');
    try {
      const url = new URL(incoming.url, 'https://' + registration.domain);
      // Prevent hostile Host headers from reusing ambient browser cookies. A
      // stable signed origin also preserves the API's own Origin restrictions.
      const expectedHost = new URL(registration.endpoints[0]).host;
      if (incoming.headers.host !== expectedHost) {
        outgoing.writeHead(421).end();
        return;
      }
      if (
        url.pathname === '/health' ||
        url.pathname === '/api' ||
        url.pathname.startsWith('/api/')
      ) {
        const headers = Object.fromEntries(
          Object.entries(incoming.headers).filter(
            ([k]) => !hopHeaders.has(k) && !k.startsWith('x-forwarded-'),
          ),
        );
        headers.host = '127.0.0.1:' + apiPort;
        const upstream = request(
          {
            host: '127.0.0.1',
            port: apiPort,
            method: incoming.method,
            path: incoming.url,
            headers,
          },
          (response) => {
            const responseHeaders = Object.fromEntries(
              Object.entries(response.headers).filter(([k]) => !hopHeaders.has(k)),
            );
            outgoing.writeHead(response.statusCode, responseHeaders);
            outgoing.flushHeaders(); // SSE is streamed immediately, never buffered.
            response.pipe(outgoing);
            response.on('error', () => outgoing.destroy());
          },
        );
        upstream.on('error', () => {
          if (!outgoing.headersSent) outgoing.writeHead(503);
          outgoing.end();
        });
        outgoing.on('close', () => upstream.destroy());
        incoming.pipe(upstream);
        return;
      }
      if (!['GET', 'HEAD'].includes(incoming.method)) {
        outgoing.writeHead(405).end();
        return;
      }
      if (url.pathname === '/task-hub/deployment.json') {
        outgoing.setHeader('Content-Type', mime['.json']);
        outgoing.setHeader('Cache-Control', 'no-store');
        outgoing.end(await readFile(join(state, 'deployment.json')));
        return;
      }
      if (url.pathname === '/task-hub/pairing.json' || url.pathname === '/task-hub/pairing.png') {
        const file = join(
          state,
          dirname('pairing.json'),
          url.pathname.endsWith('.png') ? 'pairing.png' : 'pairing.json',
        );
        outgoing.setHeader(
          'Content-Type',
          url.pathname.endsWith('.png') ? mime['.png'] : mime['.json'],
        );
        outgoing.setHeader('Cache-Control', 'no-store');
        createReadStream(file).pipe(outgoing);
        return;
      }
      const path = decodeURIComponent(url.pathname);
      if (path.split('/').some((p) => p === '..' || p.startsWith('.')) || path.includes('\\')) {
        outgoing.writeHead(404).end();
        return;
      }
      let file = resolve(root, '.' + path);
      if (!file.startsWith(root + sep)) {
        if (path === '/') file = join(root, 'index.html');
        else {
          outgoing.writeHead(404).end();
          return;
        }
      }
      let info;
      try {
        info = await stat(file);
      } catch {
        /* SPA routes use the built index. */
      }
      if (!info?.isFile()) {
        if (extname(path)) {
          outgoing.writeHead(404).end();
          return;
        }
        file = join(root, 'index.html');
        info = await stat(file);
      }
      outgoing.setHeader('Content-Type', mime[extname(file)] ?? 'application/octet-stream');
      outgoing.setHeader('Content-Length', info.size);
      outgoing.setHeader(
        'Cache-Control',
        extname(file) === '.html' ? 'no-cache' : 'public, max-age=3600',
      );
      if (incoming.method === 'HEAD') outgoing.end();
      else {
        const stream = createReadStream(file);
        stream.on('error', () => outgoing.destroy());
        stream.pipe(outgoing);
      }
    } catch {
      if (!outgoing.headersSent) outgoing.writeHead(400);
      outgoing.end();
    }
  });
  server.requestTimeout = 0;
  server.headersTimeout = 20000;
  return server;
}
function pg(runtime, args, env = {}) {
  return run(runtime.pg.pg_ctl, ['-D', runtime.pg.data, ...args], {
    env: { ...process.env, LC_ALL: 'C', LANG: 'C', ...env },
    timeout: 30000,
  });
}
async function loopbackJson(port, path) {
  return new Promise((res, rej) => {
    const req = request({ host: '127.0.0.1', port, path, timeout: 1000 }, (response) => {
      let s = '';
      response.on('data', (b) => {
        s += b;
        if (s.length > 256 * 1024) req.destroy();
      });
      response.on('end', () => {
        try {
          if (response.statusCode !== 200) throw Error();
          res(JSON.parse(s));
        } catch {
          rej(Error('API not ready'));
        }
      });
    });
    req.on('error', () => rej(Error('API not ready')));
    req.on('timeout', () => req.destroy());
    req.end();
  });
}
async function pause(ms) {
  await new Promise((res) => setTimeout(res, ms));
}
export async function runtimeMain(state) {
  if (process.platform !== 'darwin')
    throw Error('Task Hub primary runtime is supported only on macOS');
  state = resolve(state);
  await privateDir(state);
  const activation = await assertStartAllowed(state, { runtime: true });
  const config = await json(join(state, 'runtime.json'), true);
  const document = await json(join(state, 'deployment.json'));
  const registration = verifyRegistration(
    document,
    (await json(join(state, 'trust.json'), true)).registryKeySha256,
  );
  if (config.role !== 'primary' || !sameIdentity(config, registration))
    throw Error('Primary role or registration identity is invalid');
  if ((await json(config.hostRuntime)).hostId !== registration.primaryHostId)
    throw Error('This computer is not the designated primary Host');
  assertNode24();
  assertNode24(config.node);
  const manifest = await verifyBundle(config.bundle);
  const notification = await businessMaterial(state, { allowMissingKey: true });
  if (notification) assertBusinessBundle(manifest);
  const lock = await acquireLock(join(state, 'instance.lock'), {
    ...registration,
    domain: registration.domain,
  });
  try {
    const permission = await assertStartAllowed(state, { runtime: true });
    if (permission?.activationId !== activation?.activationId)
      throw Error('Primary activation changed during startup');
  } catch (error) {
    await lock.release();
    throw error;
  }
  let api,
    server,
    nativeDiscovery,
    databaseLock,
    pgStarted = false,
    stopping = false;
  let createdResources = false,
    stopRequested = false;
  let activationWatch;
  let readyRecord,
    discoveryError = null,
    discoveryState = 'publication-requested; discovery checked separately',
    discoveryUpdates = Promise.resolve();
  let stopResolve;
  const stopped = new Promise((res) => {
    stopResolve = res;
  });
  const shutdown = async (failure = false) => {
    if (stopping) return stopped;
    stopping = true;
    clearInterval(activationWatch);
    if (nativeDiscovery) await nativeDiscovery.destroy();
    await discoveryUpdates;
    if (server) {
      server.close();
      server.closeAllConnections();
    }
    if (api && alive(api.pid)) {
      api.kill('SIGTERM');
      for (let i = 0; i < 150 && alive(api.pid); i++) await pause(100);
      if (alive(api.pid)) {
        api.kill('SIGKILL');
        for (let i = 0; i < 30 && alive(api.pid); i++) await pause(100);
      }
      if (alive(api.pid)) {
        process.stderr.write('API did not stop; instance lock retained.\n');
        process.exit(1);
      }
    }
    if (databaseLock) await databaseLock.end().catch(() => {});
    if (pgStarted) {
      try {
        pg(config, ['stop', '-m', 'fast', '-w']);
      } catch {
        failure = true;
      }
    }
    if (createdResources) await rm(join(state, 'resources.json'), { force: true });
    await lock.release();
    stopResolve();
    process.exitCode = failure ? 1 : 0;
  };
  const signal = () => {
    stopRequested = true;
    if (server) void shutdown();
  };
  process.once('SIGTERM', signal);
  process.once('SIGINT', signal);
  try {
    if (activation) {
      let checking = false;
      activationWatch = setInterval(async () => {
        if (checking || stopping) return;
        checking = true;
        try {
          await assertActivationRuntime(state, activation);
        } catch {
          stopRequested = true;
          if (pgStarted || api || server) void shutdown(true);
        } finally {
          checking = false;
        }
      }, 1000);
    }
    if (await exists(join(state, 'resources.json'))) {
      const resources = await json(join(state, 'resources.json'), true);
      if (resources.entry !== join(config.bundle, 'runtime/dist/main.js'))
        throw Error(
          'Orphan API journal belongs to another artifact; explicit reconciliation required',
        );
      if (ownedProcess(resources.apiPid, resources.entry)) {
        process.kill(resources.apiPid, 'SIGTERM');
        for (let i = 0; i < 100 && alive(resources.apiPid); i++) await pause(100);
        if (alive(resources.apiPid))
          throw Error('Orphan API did not stop; no replacement execution started');
      }
    }
    const env = await json(join(state, 'env.json'), true);
    const databaseUrl = new URL(env.DATABASE_URL);
    if (
      databaseUrl.protocol !== 'postgresql:' ||
      databaseUrl.hostname !== '127.0.0.1' ||
      Number(databaseUrl.port) !== config.pgPort ||
      databaseUrl.username !== 'taskhub' ||
      databaseUrl.pathname !== '/taskhub' ||
      !/^[a-f0-9]{64}$/.test(databaseUrl.password) ||
      env.WEB_ORIGIN !== registration.endpoints[0] ||
      env.UPLOAD_DIR !== join(state, 'uploads') ||
      env.HOST_CONNECTION_STATE_DIR !== join(state, 'host-connections')
    )
      throw Error(
        'Private database, origin or attachment state escaped its dedicated installation',
      );
    await availablePort(config.apiPort);
    await availablePort(config.tlsPort, config.bind);
    // Recover only this private postmaster after the runtime crashes or reboots.
    // The instance lock is held and its journaled API has already stopped.
    if (await exists(join(config.pg.data, 'postmaster.pid'))) {
      const pidfile = (await readFile(join(config.pg.data, 'postmaster.pid'), 'utf8')).split('\n');
      if (pidfile[1] !== config.pg.data)
        throw Error('PostgreSQL data-directory ownership mismatch');
      const oldPid = Number(pidfile[0]);
      if (ownedProcess(oldPid, '-D ' + config.pg.data)) pg(config, ['stop', '-m', 'fast', '-w']);
      // PostgreSQL handles a dead postmaster stale pidfile itself.
    }
    await availablePort(config.pgPort);
    await privateDir(config.pg.socket);
    if (!(await exists(join(config.pg.data, 'PG_VERSION')))) {
      await privateFile(join(state, 'pg-password'));
      run(
        config.pg.initdb,
        [
          '-D',
          config.pg.data,
          '-U',
          'taskhub_admin',
          '--auth=scram-sha-256',
          '--encoding=UTF8',
          '--locale=C',
          '--pwfile',
          join(state, 'pg-password'),
        ],
        { env: { ...process.env, LC_ALL: 'C', LANG: 'C' } },
      );
      await save(
        join(config.pg.data, 'pg_hba.conf'),
        'local all all scram-sha-256\nhost all all 127.0.0.1/32 scram-sha-256\nhost all all ::1/128 scram-sha-256\n',
      );
    }
    if ((await readFile(join(config.pg.data, 'PG_VERSION'), 'utf8')).trim() !== config.pg.major)
      throw Error('PostgreSQL major version mismatch; explicit database upgrade required');
    if (stopRequested) throw Error('Activation stopped during PostgreSQL preparation');
    if (activation) await assertActivationRuntime(state, activation);
    pg(config, [
      'start',
      '-l',
      join(state, 'postgres.log'),
      '-o',
      `-p ${config.pgPort} -h 127.0.0.1 -k ${config.pg.socket}`,
      '-w',
    ]);
    pgStarted = true;
    const { Client } = createRequire(join(config.bundle, 'runtime/package.json'))('pg');
    const admin = new Client({
      host: '127.0.0.1',
      port: config.pgPort,
      user: 'taskhub_admin',
      database: 'postgres',
      password: env.PG_ADMIN_PASSWORD,
    });
    try {
      await admin.connect();
      const role = await admin.query("SELECT 1 FROM pg_roles WHERE rolname='taskhub'");
      if (!role.rowCount)
        await admin.query(
          "CREATE ROLE taskhub LOGIN PASSWORD '" +
            new URL(env.DATABASE_URL).password +
            "' NOSUPERUSER NOCREATEDB NOCREATEROLE",
        );
      const db = await admin.query("SELECT 1 FROM pg_database WHERE datname='taskhub'");
      if (!db.rowCount) await admin.query('CREATE DATABASE taskhub OWNER taskhub');
    } finally {
      await admin.end();
    }
    databaseLock = new Client({
      connectionString: env.DATABASE_URL,
      application_name: 'agenthub-task-primary-lock',
    });
    databaseLock.on('error', () => {
      void shutdown(true);
    });
    await databaseLock.connect();
    const locked = await databaseLock.query('SELECT pg_try_advisory_lock(84311322) AS locked');
    if (!locked.rows[0].locked) throw Error('Another primary holds the database execution lock');
    const entry = join(config.bundle, 'runtime/dist/main.js');
    if (activation) await assertActivationRuntime(state, activation);
    if (notification && !notification.keyPresent) {
      const binding = await json(join(state, 'business-key-binding.json'), true);
      await save(join(state, 'business-key-binding.json'), {
        ...binding,
        phase: 'initialization-started',
      });
    }
    api = spawn(process.execPath, [entry], {
      cwd: join(config.bundle, 'runtime'),
      env: { ...env, PATH: config.path, HOME: process.env.HOME, NODE_ENV: 'production' },
      stdio: ['ignore', 'inherit', 'inherit'],
    });
    api.once('error', () => {
      void shutdown(true);
    });
    api.once('exit', () => {
      if (!stopping) void shutdown(true);
    });
    await save(join(state, 'resources.json'), {
      apiPid: api.pid,
      entry,
      runtimePid: process.pid,
      nonce: lock.owner.nonce,
    });
    createdResources = true;
    for (let i = 0; ; i++) {
      if (stopping || stopRequested) throw Error('API exited or stop requested during startup');
      try {
        const description = await loopbackJson(config.apiPort, '/api/v1/service');
        const health = await loopbackJson(config.apiPort, '/health');
        if (
          !sameIdentity(description, registration) ||
          description.version !== 1 ||
          description.contractVersion !== '1'
        )
          throw Error('API service identity does not match the designated primary');
        if (health.status === 'ok' && health.database === 'ok') break;
      } catch {
        if (i >= 100)
          throw Error('API identity/database readiness failed; see private service log');
      }
      await pause(150);
    }
    if (notification) {
      const current = await businessMaterial(state);
      const binding = await json(join(state, 'business-key-binding.json'), true);
      await save(join(state, 'business-key-binding.json'), {
        ...binding,
        phase: 'initialized',
        keySha256: current.keySha256,
      });
    }
    for (const name of ['tls-key.pem', 'tls-cert.pem']) await privateFile(join(state, name));
    if (activation) await assertActivationRuntime(state, activation);
    server = await gateway({
      bundle: config.bundle,
      registration,
      state,
      apiPort: config.apiPort,
      key: await readFile(join(state, 'tls-key.pem')),
      cert: await readFile(join(state, 'tls-cert.pem')),
    });
    server.on('error', () => {
      void shutdown(true);
    });
    await new Promise((res, rej) => {
      server.once('error', rej);
      server.listen(config.tlsPort, config.bind, res);
    });
    if (activation) await assertActivationRuntime(state, activation);
    nativeDiscovery = publishNativeDiscovery(
      {
        name: discoveryServiceName(registration),
        host: registration.domain,
        type: 'agenthub-task',
        protocol: 'tcp',
        port: config.tlsPort,
        txt: {
          version: '1',
          deploymentId: registration.deploymentId,
          serverInstanceId: registration.serverInstanceId,
          primaryHostId: registration.primaryHostId,
          name: registration.name,
          contractVersion: '1',
          path: '/api/v1/service',
        },
      },
      (publicationState, errorCode) => {
        discoveryState = publicationState;
        discoveryError = errorCode;
        if (errorCode) process.stderr.write('Task Hub discovery unavailable: ' + errorCode + '\n');
        if (readyRecord && !stopping)
          discoveryUpdates = discoveryUpdates
            .then(async () => {
              if (!stopping)
                await save(join(state, 'ready.json'), {
                  ...readyRecord,
                  bonjour: discoveryState,
                  discoveryError,
                });
            })
            .catch(() => {
              process.stderr.write('Task Hub discovery status could not be persisted.\n');
            });
      },
      { node: config.node },
    );
    readyRecord = {
      identity: Object.fromEntries(
        ['deploymentId', 'serverInstanceId', 'primaryHostId'].map((k) => [k, registration[k]]),
      ),
      payloadSha256: (await json(join(config.bundle, 'manifest.json'))).payloadSha256,
      pid: process.pid,
      nonce: lock.owner.nonce,
      readyAt: new Date().toISOString(),
      restoreActivationId: activation?.activationId ?? null,
      restoreId: activation?.restoreId ?? null,
      worker: {
        enabled: env.HOST_BACKGROUND_JOBS !== '0',
        active: null,
        reason: 'API provides no independent Worker liveness contract',
      },
      bonjour: discoveryState,
      discoveryError,
      discoveryTransport: 'system-dns-sd',
    };
    await save(join(state, 'ready.json'), readyRecord);
    process.stdout.write(
      'Task Hub HTTPS primary ready; database/API loopback, independent lifecycle.\n',
    );
    await stopped;
  } catch (error) {
    await shutdown(true);
    throw error;
  } finally {
    await rm(join(state, 'ready.json'), { force: true });
  }
}
if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(resolve(process.argv[1])).href &&
  process.argv.includes('--native-discovery-child')
) {
  nativeDiscoveryChild().catch(() => {
    process.exitCode = 1;
  });
} else if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const at = process.argv.indexOf('--state');
  if (at < 0) throw Error('Explicit --state is required');
  runtimeMain(process.argv[at + 1]).catch(() => {
    process.stderr.write(
      'Task Hub primary stopped: inspect private configuration, locks, dependencies and service log.\n',
    );
    process.exitCode = 1;
  });
}
