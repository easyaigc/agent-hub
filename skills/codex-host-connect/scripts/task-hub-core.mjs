import {
  createHash,
  createCipheriv,
  createDecipheriv,
  createPrivateKey,
  randomBytes,
  randomUUID,
  sign,
  verify,
  X509Certificate,
} from 'node:crypto';
import { constants, createReadStream, createWriteStream } from 'node:fs';
import {
  chmod,
  lstat,
  link,
  mkdir,
  open,
  readFile,
  readdir,
  readlink,
  realpath,
  rename,
  rm,
  writeFile,
} from 'node:fs/promises';
import { createRequire } from 'node:module';
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { request } from 'node:https';
import { checkServerIdentity } from 'node:tls';
import { execFileSync, spawn, spawnSync } from 'node:child_process';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { createInterface } from 'node:readline';
import net from 'node:net';

export const hash = (value) => createHash('sha256').update(value).digest('hex');
export const canonical = (value) => JSON.stringify(value, Object.keys(value).sort());
export const identityKeys = ['deploymentId', 'serverInstanceId', 'primaryHostId'];
export const sameIdentity = (a, b) => identityKeys.every((key) => a[key] === b[key]);
export function assertId(value) {
  if (typeof value !== 'string' || !/^[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}$/.test(value))
    throw Error('Invalid stable identity');
  return value;
}
export function port(value) {
  const n = Number(value);
  if (!Number.isInteger(n) || n < 1024 || n > 65535)
    throw Error('Ports must be integers from 1024 to 65535');
  return n;
}
export async function exists(path) {
  return lstat(path).then(
    () => true,
    (error) => {
      if (error.code === 'ENOENT') return false;
      throw error;
    },
  );
}
export async function privateDir(path) {
  path = resolve(path);
  for (let cursor = path; cursor !== dirname(cursor); cursor = dirname(cursor)) {
    if (await exists(cursor)) {
      const st = await lstat(cursor);
      if (st.isSymbolicLink() || !st.isDirectory())
        throw Error('Private directory cannot traverse a symlink');
    }
  }
  await mkdir(path, { recursive: true, mode: 0o700 });
  const st = await lstat(path);
  if (process.getuid && st.uid !== process.getuid())
    throw Error('Private directory belongs to another user');
  await chmod(path, 0o700);
}
export async function privateFile(path) {
  const st = await lstat(path);
  if (
    !st.isFile() ||
    st.isSymbolicLink() ||
    (process.getuid && st.uid !== process.getuid()) ||
    (process.platform !== 'win32' && st.mode & 0o077)
  )
    throw Error('Private file ownership or permissions are invalid');
}
export async function json(path, secret = false) {
  if (secret) await privateFile(path);
  const st = await lstat(path);
  if (st.isSymbolicLink() || !st.isFile() || st.size > 16 * 1024 * 1024)
    throw Error('Invalid JSON file');
  try {
    return JSON.parse(await readFile(path, 'utf8'));
  } catch {
    throw Error('Invalid JSON file (private content suppressed)');
  }
}
export async function save(path, value, mode = 0o600) {
  await privateDir(dirname(path));
  const tmp = path + '.' + randomUUID() + '.tmp';
  const fd = await open(tmp, 'wx', mode);
  try {
    await fd.writeFile(typeof value === 'string' ? value : JSON.stringify(value, null, 2) + '\n');
    await fd.sync();
  } finally {
    await fd.close();
  }
  await rename(tmp, path);
  if (process.platform !== 'win32') {
    const directory = await open(dirname(path), 'r');
    try {
      await directory.sync();
    } finally {
      await directory.close();
    }
  }
}
export async function saveImmutable(path, value, hooks = {}) {
  await privateDir(dirname(path));
  const temporary = join(dirname(path), '.immutable-' + randomUUID() + '.tmp');
  let published = false;
  try {
    const fd = await open(temporary, 'wx', 0o600);
    try {
      await fd.chmod(0o600);
      await fd.writeFile(JSON.stringify(value, null, 2) + '\n');
      await (hooks.syncFile ? hooks.syncFile(fd) : fd.sync());
    } finally {
      await fd.close();
    }
    // Same-directory hard-link publication is atomic and refuses replacement.
    await link(temporary, path);
    published = true;
    await rm(temporary);
    await (hooks.syncParent ? hooks.syncParent(dirname(path)) : syncDirectory(dirname(path)));
  } catch (error) {
    if (published) {
      await rm(path).catch(() => {});
      await syncDirectory(dirname(path)).catch(() => {});
    }
    throw error;
  } finally {
    await rm(temporary, { force: true });
  }
}
export async function syncDirectory(path) {
  if (process.platform === 'win32') return;
  const fd = await open(path, 'r');
  try {
    await fd.sync();
  } finally {
    await fd.close();
  }
}
export async function removeDurable(path) {
  await rm(path);
  await syncDirectory(dirname(path));
}
export function alive(pid) {
  if (!Number.isInteger(pid) || pid < 2) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return error.code !== 'ESRCH';
  }
}
export function processFingerprint(pid) {
  if (!alive(pid)) return null;
  return hash(
    run('/bin/ps', ['-ww', '-p', String(pid), '-o', 'lstart=', '-o', 'command='], {
      env: { PATH: process.env.PATH, LC_ALL: 'C', LANG: 'C', TZ: 'UTC' },
    }),
  );
}
// Never steal a live lock, including a PID reused by another process. Serialize
// stale-lock recovery separately so competing recovery cannot remove a new lock.
export function ownedProcess(pid, entry, state) {
  if (!alive(pid)) return false;
  const command = run('/bin/ps', ['-p', String(pid), '-o', 'command=']).trim();
  if (!command.includes(entry) || (state && !command.endsWith('--state ' + state)))
    throw Error('Process ownership cannot be proved; refusing to signal PID');
  return true;
}
export async function acquireLock(path, details = {}) {
  await privateDir(dirname(path));
  try {
    await mkdir(path, { mode: 0o700 });
  } catch (error) {
    if (error.code !== 'EEXIST') throw error;
    const reclaim = path + '.reclaim';
    try {
      await mkdir(reclaim, { mode: 0o700 });
    } catch {
      throw Error('Lock recovery already in progress');
    }
    try {
      const previous = await json(join(path, 'owner.json'), true);
      if (alive(previous.pid))
        throw Error('Task Hub instance or installation is already running', { cause: error });
      await rm(path, { recursive: true });
      await mkdir(path, { mode: 0o700 });
    } finally {
      await rm(reclaim, { recursive: true });
    }
  }
  const owner = {
    ...details,
    pid: process.pid,
    nonce: randomUUID(),
    startedAt: new Date().toISOString(),
  };
  await save(join(path, 'owner.json'), owner);
  return {
    owner,
    release: async () => {
      if ((await json(join(path, 'owner.json'), true)).nonce !== owner.nonce)
        throw Error('Lock ownership changed');
      await rm(path, { recursive: true });
    },
  };
}
export async function availablePort(n, host = '127.0.0.1') {
  await new Promise((res, rej) => {
    const s = net.createServer();
    s.once('error', () => rej(Error('Required port is already occupied: ' + n)));
    s.listen(n, host, () => s.close(res));
  });
}
export function run(file, args, options = {}) {
  try {
    return execFileSync(file, args, {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
      timeout: 30000,
      ...options,
    });
  } catch {
    throw Error(
      'Task Hub dependency command failed: ' +
        file.split('/').at(-1) +
        ' (private output suppressed)',
    );
  }
}
export async function tool(name) {
  for (const candidate of [
    process.env['TASK_HUB_' + name.toUpperCase()],
    '/opt/homebrew/bin/' + name,
    '/usr/local/bin/' + name,
    name,
  ].filter(Boolean)) {
    try {
      run(candidate, ['--version']);
      const path = isAbsolute(candidate) ? candidate : run('/usr/bin/which', [candidate]).trim();
      return await realpath(path);
    } catch {
      /* Try only known executables; never substitute another database. */
    }
  }
  throw Error('Missing required PostgreSQL executable: ' + name);
}
export async function fileInventory(root, exclude = new Set()) {
  const result = {};
  const walk = async (prefix) => {
    for (const name of (await readdir(join(root, prefix))).sort()) {
      const path = prefix ? prefix + '/' + name : name;
      if (exclude.has(path)) continue;
      const full = join(root, path),
        st = await lstat(full);
      if (st.isSymbolicLink()) {
        const target = await readlink(full),
          rel = relative(root, await realpath(full));
        if (isAbsolute(target) || rel === '..' || rel.startsWith('..' + sep))
          throw Error('Bundle symlink escapes its dependency tree: ' + path);
        result[path] = { link: target };
      } else if (st.isDirectory()) await walk(path);
      else if (st.isFile()) result[path] = { sha256: hash(await readFile(full)), size: st.size };
      else throw Error('Unsupported bundle entry: ' + path);
    }
  };
  await walk('');
  return result;
}
export async function verifyBundle(root, { primary = true } = {}) {
  const m = await json(join(root, 'manifest.json'));
  if (
    m.format !== 1 ||
    m.contractVersion !== '1' ||
    m.payloadSha256 !== hash(JSON.stringify(m.files))
  )
    throw Error('Invalid Task Hub manifest');
  const current = await fileInventory(root, new Set(['manifest.json']));
  if (JSON.stringify(current) !== JSON.stringify(m.files))
    throw Error('Task Hub bundle contents differ from frozen manifest');
  if (
    primary &&
    (!m.primaryPlatforms.includes(process.platform) || m.architecture !== process.arch)
  )
    throw Error('This bundle does not support primary on this platform/architecture');
  return m;
}
export function networkRequire(bundle) {
  return createRequire(join(bundle, 'network/package.json'));
}
export function keyFingerprint(publicKey) {
  return hash(publicKey);
}
export function signedRegistration(registration, publicKey, privateKey) {
  return {
    registration,
    publicKey,
    signature: sign(null, Buffer.from(JSON.stringify(registration)), privateKey).toString('base64'),
  };
}
export function verifyRegistration(document, trustedKey) {
  const { registration: r, publicKey, signature } = document ?? {};
  const exact = (value, keys) =>
    value &&
    typeof value === 'object' &&
    !Array.isArray(value) &&
    Object.keys(value).length === keys.length &&
    keys.every((key) => Object.hasOwn(value, key));
  if (
    !exact(document, ['registration', 'publicKey', 'signature']) ||
    !exact(r, [
      'version',
      'registryVersion',
      ...identityKeys,
      'name',
      'domain',
      'endpoints',
      'certificateSha256',
      'certificatePem',
      'contractVersion',
    ])
  )
    throw Error('Invalid public deployment registration fields');
  if (
    !r ||
    typeof publicKey !== 'string' ||
    typeof signature !== 'string' ||
    !/^[A-Za-z0-9+/]{86}==$/.test(signature) ||
    !/^[a-f0-9]{64}$/.test(trustedKey ?? '') ||
    keyFingerprint(publicKey) !== trustedKey ||
    !verify(null, Buffer.from(JSON.stringify(r)), publicKey, Buffer.from(signature ?? '', 'base64'))
  )
    throw Error('Deployment registration is not signed by the explicitly trusted key');
  identityKeys.forEach((key) => assertId(r[key]));
  if (
    r.version !== 1 ||
    r.contractVersion !== '1' ||
    r.registryVersion !== 1 ||
    typeof r.domain !== 'string' ||
    !/^[a-z0-9-]{1,63}\.local$/.test(r.domain) ||
    typeof r.certificateSha256 !== 'string' ||
    !/^[a-f0-9]{64}$/.test(r.certificateSha256) ||
    typeof r.certificatePem !== 'string' ||
    r.certificatePem.length > 32768 ||
    typeof r.name !== 'string' ||
    !r.name.trim() ||
    Buffer.byteLength(r.name) > 80 ||
    !Array.isArray(r.endpoints) ||
    r.endpoints.length < 1 ||
    r.endpoints.length > 8
  )
    throw Error('Invalid public deployment registration');
  let certificate;
  try {
    certificate = new X509Certificate(r.certificatePem);
  } catch {
    throw Error('Invalid public deployment registration certificate');
  }
  if (hash(certificate.raw) !== r.certificateSha256)
    throw Error('Public deployment certificate DER fingerprint is inconsistent');
  for (const endpoint of r.endpoints) {
    if (typeof endpoint !== 'string') throw Error('Invalid registered HTTPS origin');
    const u = new URL(endpoint);
    if (
      u.protocol !== 'https:' ||
      u.hostname !== r.domain ||
      u.username ||
      u.password ||
      u.search ||
      u.hash ||
      u.pathname !== '/'
    )
      throw Error('Invalid registered HTTPS origin');
  }
  return r;
}
export async function tlsJson(endpoint, path, registration, { connectHost } = {}) {
  const url = new URL(path, endpoint);
  if (
    url.protocol !== 'https:' ||
    url.hostname !== registration.domain ||
    !registration.endpoints.some((e) => new URL(e).origin === url.origin)
  )
    throw Error('HTTPS path is outside the trusted registration');
  return new Promise((res, rej) => {
    const req = request(
      url,
      {
        ...(connectHost ? { hostname: connectHost } : {}),
        servername: registration.domain,
        ca: registration.certificatePem,
        rejectUnauthorized: true,
        checkServerIdentity: (host, cert) =>
          checkServerIdentity(host, cert) ||
          (hash(cert.raw) !== registration.certificateSha256
            ? Error('TLS pin mismatch')
            : undefined),
        timeout: 5000,
        headers: { accept: 'application/json', host: url.host },
      },
      (response) => {
        if (response.statusCode !== 200) {
          response.resume();
          rej(Error('HTTPS service refused identity probe'));
          return;
        }
        let data = '';
        response.setEncoding('utf8');
        response.on('data', (part) => {
          data += part;
          if (data.length > 256 * 1024) req.destroy(Error('Service descriptor is oversized'));
        });
        response.on('end', () => {
          try {
            res(JSON.parse(data));
          } catch {
            rej(Error('Invalid HTTPS descriptor'));
          }
        });
      },
    );
    const deadline = setTimeout(
      () =>
        req.destroy(
          Object.assign(Error('HTTPS identity probe timed out'), { code: 'PROBE_TIMEOUT' }),
        ),
      5000,
    );
    req.on('close', () => clearTimeout(deadline));
    req.on('timeout', () =>
      req.destroy(
        Object.assign(Error('HTTPS identity probe timed out'), { code: 'PROBE_TIMEOUT' }),
      ),
    );
    req.on('error', (error) => {
      const code = /^[A-Z0-9_]{1,60}$/.test(error?.code ?? '') ? error.code : 'NETWORK_ERROR';
      const failure = Error('HTTPS identity probe failed (TLS, offline or network): ' + code);
      failure.code = code;
      rej(failure);
    });
    req.end();
  });
}
export async function probe(registration, options = {}) {
  const descriptor = await tlsJson(
    registration.endpoints[0],
    '/api/v1/service',
    registration,
    options,
  );
  if (
    !sameIdentity(descriptor, registration) ||
    descriptor.version !== 1 ||
    descriptor.contractVersion !== '1' ||
    !Array.isArray(descriptor.capabilities)
  )
    throw Error('HTTPS Task Hub service identity conflicts with registration');
  const health = await tlsJson(registration.endpoints[0], '/health', registration, options);
  return {
    identityVerified: true,
    backendReady: health.database === 'ok' && health.status === 'ok',
    capabilities: descriptor.capabilities,
  };
}
export async function discover(bundle, duration = 1500) {
  if (!Number.isInteger(duration) || duration < 100 || duration > 10000)
    throw Error('Discovery requires a bounded duration');
  if (process.platform === 'darwin') return discoverNative(duration);
  const { Bonjour } = networkRequire(bundle)('bonjour-service');
  let failure;
  const bonjour = new Bonjour(undefined, (error) => {
      failure = error;
    }),
    found = new Map();
  bonjour.server?.mdns?.on('error', (error) => {
    failure = error;
  });
  const browser = bonjour.find({ type: 'agenthub-task', protocol: 'tcp' }, (service) => {
    const txt = service.txt ?? {};
    if (txt.version !== '1') return;
    const value = {
      deploymentId: txt.deploymentId,
      serverInstanceId: txt.serverInstanceId,
      primaryHostId: txt.primaryHostId,
      name: txt.name,
      endpoint: 'https://' + service.host + ':' + service.port,
      contractVersion: txt.contractVersion,
    };
    if (identityKeys.every((k) => typeof value[k] === 'string'))
      found.set(identityKeys.map((k) => value[k]).join('/'), value);
  });
  try {
    await new Promise((res) => setTimeout(res, duration));
    if (failure) throw Error('Discovery unavailable', { cause: failure });
  } finally {
    browser.stop();
    bonjour.destroy();
  }
  return [...found.values()];
}

// The native DNS-SD client delegates interface handling to mDNSResponder. A GUI
// LaunchAgent can have working HTTPS and DNS while its raw multicast is denied.
async function dnsSdRead(args, duration) {
  return new Promise((resolveRead, reject) => {
    const child = spawn('/usr/bin/dns-sd', args, {
      env: { PATH: '/usr/bin:/bin', LANG: 'C', LC_ALL: 'C' },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let output = '',
      failed = false,
      killTimer;
    const timer = setTimeout(() => {
      child.kill('SIGTERM');
      killTimer = setTimeout(() => child.kill('SIGKILL'), 1000);
    }, duration);
    child.stdout.on('data', (bytes) => {
      output += bytes.toString('utf8');
      if (Buffer.byteLength(output) > 65536) {
        failed = true;
        child.kill('SIGTERM');
      }
    });
    child.stderr.on('data', () => {
      failed = true;
    });
    child.on('error', () => {
      failed = true;
    });
    child.on('close', (code, signal) => {
      clearTimeout(timer);
      clearTimeout(killTimer);
      if (failed || (code !== 0 && signal !== 'SIGTERM'))
        reject(Error('Native DNS-SD query unavailable'));
      else resolveRead(output);
    });
  });
}
function dnsSdTokens(line) {
  const tokens = [];
  let bytes = [];
  const flush = () => {
    if (bytes.length) tokens.push(Buffer.from(bytes).toString('utf8'));
    bytes = [];
  };
  for (let i = 0; i < line.length; i++) {
    if (line[i] === '\\') {
      const decimal = line.slice(i + 1, i + 4);
      if (/^\d{3}$/.test(decimal)) {
        if (Number(decimal) > 255) return [];
        bytes.push(Number(decimal));
        i += 3;
      } else if (++i < line.length) bytes.push(...Buffer.from(line[i]));
      else return [];
    } else if (/\s/.test(line[i])) flush();
    else {
      const codePoint = line.codePointAt(i);
      bytes.push(...Buffer.from(String.fromCodePoint(codePoint)));
      if (codePoint > 0xffff) i++;
    }
  }
  flush();
  return tokens;
}
export function parseNativeService(output) {
  const target = output.match(
    / can be reached at ([a-z0-9-]{1,63}\.local)\.:([0-9]{1,5}) \(interface \d+\)/,
  );
  if (!target || Number(target[2]) < 1 || Number(target[2]) > 65535) return null;
  let txt;
  for (const line of output.split('\n')) {
    if (!/^\s+\S+=/.test(line)) continue;
    const frame = {};
    for (const token of dnsSdTokens(line.trim())) {
      const at = token.indexOf('=');
      if (at < 1) return null;
      const key = token.slice(0, at);
      if (Object.hasOwn(frame, key)) return null;
      Object.defineProperty(frame, key, { value: token.slice(at + 1), enumerable: true });
    }
    if (txt && canonical(txt) !== canonical(frame)) return null;
    txt = frame;
  }
  if (
    !txt ||
    txt.version !== '1' ||
    txt.contractVersion !== '1' ||
    typeof txt.name !== 'string' ||
    !txt.name.trim() ||
    Buffer.byteLength(txt.name) > 80
  )
    return null;
  try {
    identityKeys.forEach((key) => assertId(txt[key]));
  } catch {
    return null;
  }
  return {
    ...Object.fromEntries(identityKeys.map((key) => [key, txt[key]])),
    name: txt.name,
    endpoint: 'https://' + target[1] + ':' + target[2],
    contractVersion: txt.contractVersion,
  };
}
async function discoverNative(duration) {
  const browse = await dnsSdRead(['-B', '_agenthub-task._tcp', 'local.'], duration);
  const names = new Set();
  for (const line of browse.split('\n')) {
    const row = line.match(
      /^\s*\d+:\d+:\d+\.\d+\s+(Add|Rmv)\s+\S+\s+\d+\s+local\.\s+_agenthub-task\._tcp\.\s+(.+)$/,
    );
    if (!row) continue;
    if (row[1] === 'Rmv') names.delete(row[2]);
    else if (
      Buffer.byteLength(row[2]) <= 255 &&
      ![...row[2]].some((character) => character.charCodeAt(0) < 32)
    )
      names.add(row[2]);
    if (names.size > 32) throw Error('Too many DNS-SD discovery results');
  }
  const found = new Map();
  const pending = [...names];
  await Promise.all(
    Array.from({ length: Math.min(4, pending.length) }, async () => {
      while (pending.length) {
        const name = pending.shift();
        const service = parseNativeService(
          await dnsSdRead(['-L', name, '_agenthub-task._tcp', 'local.'], Math.min(duration, 1500)),
        );
        if (service) found.set(identityKeys.map((key) => service[key]).join('/'), service);
      }
    }),
  );
  return [...found.values()];
}

// Cold backups deliberately require a stopped whole cluster. Never substitute a
// copy of a running PGDATA directory, or open a second postmaster for validation.
export const backupConfigFiles = [
  'runtime.json',
  'env.json',
  'pg-password',
  'deployment.json',
  'trust.json',
  'registry-key.pem',
  'tls-key.pem',
  'tls-cert.pem',
  'pairing.json',
  'pairing.png',
];
export async function shaFile(path) {
  const digest = createHash('sha256');
  for await (const bytes of createReadStream(path)) digest.update(bytes);
  return digest.digest('hex');
}
export function below(root, path) {
  const rel = relative(resolve(root), resolve(path));
  return rel === '' || (rel !== '..' && !rel.startsWith('..' + sep) && !isAbsolute(rel));
}
export const businessConfigEnv = 'TASK_HUB_BUSINESS_NOTIFICATIONS_CONFIG';
export const businessKeyName = 'publisher-x25519.json';
export const optionalBackupConfigFiles = ['business-key-binding.json'];
export function assertNode24(executable = process.execPath) {
  if (!/^v24\./.test(run(executable, ['--version'])))
    throw Error('Task Hub primary requires actual Node 24');
}
async function strictPrivateDirectory(path) {
  const st = await lstat(path);
  if (
    !st.isDirectory() ||
    st.isSymbolicLink() ||
    (await realpath(path)) !== path ||
    !process.getuid ||
    st.uid !== process.getuid() ||
    (st.mode & 0o7777) !== 0o700
  )
    throw Error('Business notification private directory rejected');
}
async function strictPrivateBytes(path, limit) {
  await strictPrivateDirectory(dirname(path));
  const fd = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
  try {
    const st = await fd.stat();
    if (
      !st.isFile() ||
      st.nlink !== 1 ||
      !process.getuid ||
      st.uid !== process.getuid() ||
      (st.mode & 0o7777) !== 0o600 ||
      st.size < 1 ||
      st.size > limit
    )
      throw Error('Business notification private file rejected');
    const bytes = await fd.readFile();
    if (bytes.length > limit) throw Error('Business notification private file rejected');
    return bytes;
  } finally {
    await fd.close();
  }
}
function businessPaths(config, configPath, state) {
  for (const path of [configPath, config.keyDirectory, config.bundleRoot])
    if (
      typeof path !== 'string' ||
      !isAbsolute(path) ||
      resolve(path) !== path ||
      path.includes('\0')
    )
      throw Error('Business notification absolute paths rejected');
  if (
    below(config.bundleRoot, config.keyDirectory) ||
    below(config.keyDirectory, config.bundleRoot) ||
    below(config.bundleRoot, configPath) ||
    below(config.keyDirectory, configPath)
  )
    throw Error('Business notification credentials must be outside the release and separate');
  if (state) {
    if (below(config.keyDirectory, state))
      throw Error('Business key directory overlaps service state');
    for (const name of [
      'postgres',
      'uploads',
      'host-connections',
      'releases',
      'recovery',
      'business-notifications',
    ]) {
      const reserved = join(state, name);
      if (
        below(reserved, configPath) ||
        below(reserved, config.keyDirectory) ||
        below(config.keyDirectory, reserved)
      )
        throw Error('Business notification paths overlap reserved service material');
    }
    if (dirname(configPath) === state || dirname(config.keyDirectory) === state)
      throw Error('Use a dedicated business credential parent directory');
  }
}
// Deployment validates existing bytes; only the canonical API factory creates a
// first key. Staged archives never dereference their original external paths.
export async function readBusinessNotifications(
  configPath,
  identity,
  { state, bundleRoot, payloadSha256, stagedRoot, allowMissingKey = false } = {},
) {
  if (configPath === undefined) return null;
  try {
    const path = stagedRoot ? join(stagedRoot, 'business-notifications/config.json') : configPath;
    const configBytes = await strictPrivateBytes(path, 16 * 1024);
    const config = JSON.parse(configBytes.toString('utf8'));
    const fields = [
      'version',
      'domain',
      ...identityKeys,
      'relayURL',
      'publisherToken',
      'keyDirectory',
      'bundleRoot',
      'payloadSha256',
    ];
    if (
      !config ||
      Object.keys(config).length !== fields.length ||
      fields.some((k) => !Object.hasOwn(config, k)) ||
      config.version !== 1 ||
      config.domain !== 'taskhub.business' ||
      !sameIdentity(config, identity)
    )
      throw Error('Business notification configuration identity/fields rejected');
    businessPaths(config, configPath, state);
    const url = new URL(config.relayURL);
    if (
      url.protocol !== 'https:' ||
      url.username ||
      url.password ||
      url.pathname !== '/' ||
      url.search ||
      url.hash ||
      typeof config.publisherToken !== 'string' ||
      !/^[\x21-\x7e]{32,512}$/.test(config.publisherToken)
    )
      throw Error('Business notification publisher configuration rejected');
    if (
      !/^[a-f0-9]{64}$/.test(config.payloadSha256) ||
      (bundleRoot !== undefined && config.bundleRoot !== bundleRoot) ||
      (payloadSha256 !== undefined && config.payloadSha256 !== payloadSha256)
    )
      throw Error('Business notification canonical release pin rejected');
    const keyDirectory = stagedRoot
      ? join(stagedRoot, 'business-notifications/keys')
      : config.keyDirectory;
    await strictPrivateDirectory(dirname(keyDirectory));
    if (!(await exists(keyDirectory))) {
      if (!allowMissingKey || stagedRoot)
        throw Error('Business notification persistent key missing');
      return { config, configSha256: hash(configBytes), keySha256: null, keyPresent: false };
    }
    await strictPrivateDirectory(keyDirectory);
    if (JSON.stringify((await readdir(keyDirectory)).sort()) !== JSON.stringify([businessKeyName]))
      throw Error('Business notification existing key directory is empty or incomplete');
    const bytes = await strictPrivateBytes(join(keyDirectory, businessKeyName), 16 * 1024);
    const key = JSON.parse(bytes.toString('utf8'));
    const keyFields = ['version', 'domain', ...identityKeys, 'privateKeyPem'];
    if (
      Object.keys(key).length !== keyFields.length ||
      keyFields.some((k) => !Object.hasOwn(key, k)) ||
      key.version !== 1 ||
      key.domain !== 'taskhub.business' ||
      !sameIdentity(key, config) ||
      createPrivateKey(key.privateKeyPem).asymmetricKeyType !== 'x25519'
    )
      throw Error('Business notification persistent key identity/type rejected');
    return { config, configSha256: hash(configBytes), keySha256: hash(bytes), keyPresent: true };
  } catch (error) {
    // Never surface JSON, OpenSSL, URLs or private file contents.
    if (
      /^Business notification [A-Za-z /-]+ rejected$|^Business notification persistent key missing$|^Business notification existing key directory is empty or incomplete$/.test(
        error.message,
      )
    )
      throw error;
    // eslint-disable-next-line preserve-caught-error -- causes may contain publisher credentials or private key bytes
    throw Error('Business notification private configuration/key rejected');
  }
}
export async function businessMaterial(root, { staged = false, allowMissingKey = false } = {}) {
  const runtime = await json(join(root, 'runtime.json'), true);
  const env = await json(join(root, 'env.json'), true);
  if (env[businessConfigEnv] !== runtime.businessNotificationsConfigPath)
    throw Error('Business notification configured paths disagree');
  const material = await readBusinessNotifications(
    runtime.businessNotificationsConfigPath,
    runtime,
    {
      state: staged ? undefined : root,
      bundleRoot: runtime.bundle,
      payloadSha256: runtime.payloadSha256,
      stagedRoot: staged ? root : undefined,
      allowMissingKey,
    },
  );
  if (!material && staged && (await exists(join(root, 'business-notifications'))))
    throw Error('Unconfigured business notification archive material rejected');
  if (material && (await exists(join(root, 'business-key-binding.json')))) {
    const binding = await json(join(root, 'business-key-binding.json'), true);
    if (
      binding.version !== 1 ||
      !sameIdentity(binding, runtime) ||
      binding.configPath !== runtime.businessNotificationsConfigPath ||
      binding.keyDirectory !== material.config.keyDirectory ||
      !['initial-pending', 'initialization-started', 'initialized'].includes(binding.phase) ||
      (binding.keySha256 && binding.keySha256 !== material.keySha256) ||
      (!material.keyPresent && binding.phase !== 'initial-pending')
    )
      throw Error('Business notification persistent key binding rejected');
  }
  if (material && !material.keyPresent && !(await exists(join(root, 'business-key-binding.json'))))
    throw Error('Business notification first-key authorization missing');
  return material;
}
export function businessDescriptor(material, configPath) {
  return material
    ? {
        configPath,
        keyDirectory: material.config.keyDirectory,
        configSha256: material.configSha256,
        keySha256: material.keySha256,
      }
    : null;
}
export function assertBusinessBundle(manifest) {
  if (
    manifest.node !== '>=24 <25' ||
    manifest.entries?.businessCrypto !== 'business-crypto/business-crypto.cjs' ||
    manifest.businessCrypto?.format !== 'cjs' ||
    manifest.businessCrypto.manifest !== 'business-crypto/manifest.json' ||
    manifest.files['business-crypto/business-crypto.cjs']?.sha256 !==
      manifest.businessCrypto.sha256 ||
    !manifest.files['business-crypto/manifest.json']
  )
    throw Error('Canonical business cipher missing from frozen release');
}
export async function privateTree(root, { protect = true } = {}) {
  const files = {},
    directories = [];
  async function walk(prefix) {
    const path = join(root, prefix);
    const info = await lstat(path);
    if (info.isSymbolicLink() || (process.getuid && info.uid !== process.getuid()))
      throw Error('Backup cannot follow links or foreign-owned state');
    if (info.isDirectory()) {
      if (protect) await chmod(path, 0o700);
      if (prefix) directories.push(prefix);
      for (const name of (await readdir(path)).sort())
        await walk(prefix ? prefix + '/' + name : name);
    } else if (info.isFile()) {
      if (protect) await chmod(path, 0o600);
      files[prefix] = { size: info.size, sha256: await shaFile(path) };
    } else throw Error('Backup contains an unsupported entry');
  }
  await walk('');
  return { files, directories };
}
export async function backupKey(path, create = false) {
  if (!path) throw Error('An explicit private --key-file is required');
  await privateDir(dirname(path));
  if (create) {
    const fd = await open(path, 'wx', 0o600);
    try {
      await fd.writeFile(randomBytes(32).toString('hex') + '\n');
      await fd.sync();
    } finally {
      await fd.close();
    }
  }
  await privateFile(path);
  const key = (await readFile(path, 'utf8')).trim();
  if (!/^[a-f0-9]{64}$/.test(key))
    throw Error('Backup key must contain exactly 32 random bytes in hex');
  return Buffer.from(key, 'hex');
}
const backupMagic = Buffer.from('THUBBK01');
export async function sealBackup(root, manifest, output, key) {
  await privateDir(dirname(output));
  if (await exists(output))
    throw Error('Backup output already exists; preserve immutable archives');
  const temp = output + '.' + randomUUID() + '.tmp';
  const iv = randomBytes(12),
    header = Buffer.concat([backupMagic, iv]);
  const cipher = createCipheriv('aes-256-gcm', key, iv);
  cipher.setAAD(header);
  const body = Buffer.from(JSON.stringify(manifest));
  const size = Buffer.alloc(4);
  size.writeUInt32BE(body.length);
  async function* payload() {
    yield size;
    yield body;
    for (const path of Object.keys(manifest.files)) {
      for await (const chunk of createReadStream(join(root, path))) yield chunk;
    }
  }
  const fd = await open(temp, 'wx', 0o600);
  try {
    await fd.writeFile(header);
  } finally {
    await fd.close();
  }
  try {
    await pipeline(
      Readable.from(payload()),
      cipher,
      createWriteStream(temp, { flags: 'a', mode: 0o600 }),
    );
    const tail = await open(temp, 'a');
    try {
      await tail.writeFile(cipher.getAuthTag());
      await tail.sync();
    } finally {
      await tail.close();
    }
    // A hard link publishes without replacing an archive created concurrently.
    const { link } = await import('node:fs/promises');
    await link(temp, output);
    if (process.platform !== 'win32') {
      const directory = await open(dirname(output), 'r');
      try {
        await directory.sync();
      } finally {
        await directory.close();
      }
    }
    return {
      archiveSha256: await shaFile(output),
      encrypted: 'AES-256-GCM',
      manifestSha256: hash(body),
    };
  } finally {
    await rm(temp, { force: true });
  }
}
function backupPath(path) {
  return (
    typeof path === 'string' &&
    path.length < 512 &&
    !path.includes('\\') &&
    !path.includes('\0') &&
    !path.includes('\n') &&
    path.split('/').every((p) => p && p !== '.' && p !== '..') &&
    (['postgres', 'uploads', 'host-connections', 'sql-evidence', 'business-notifications'].includes(
      path.split('/')[0],
    ) ||
      backupConfigFiles.includes(path) ||
      optionalBackupConfigFiles.includes(path))
  );
}
export async function openBackup(archive, key, destination) {
  await privateFile(archive);
  if (await exists(destination)) throw Error('Offline destination must not exist');
  await privateDir(destination);
  const plaintext = join(destination, '.authenticated-payload');
  const fd = await open(archive, 'r');
  let manifest;
  try {
    const size = (await fd.stat()).size;
    if (size < 40) throw Error('Invalid encrypted backup');
    const header = Buffer.alloc(20),
      tag = Buffer.alloc(16);
    await fd.read(header, 0, 20, 0);
    await fd.read(tag, 0, 16, size - 16);
    if (!header.subarray(0, 8).equals(backupMagic))
      throw Error('Unsupported encrypted backup format');
    const decipher = createDecipheriv('aes-256-gcm', key, header.subarray(8));
    decipher.setAAD(header);
    decipher.setAuthTag(tag);
    try {
      await pipeline(
        createReadStream(archive, { start: 20, end: size - 17 }),
        decipher,
        createWriteStream(plaintext, { flags: 'wx', mode: 0o600 }),
      );
    } catch {
      throw Error('Backup authentication failed; wrong key, corrupt or truncated archive');
    }
    // No paths or bytes are restored until GCM final authentication succeeds.
    const source = await open(plaintext, 'r');
    try {
      const length = Buffer.alloc(4);
      await source.read(length, 0, 4, 0);
      const n = length.readUInt32BE();
      if (n < 2 || n > 64 * 1024 * 1024) throw Error('Backup manifest is oversized');
      const bytes = Buffer.alloc(n);
      if ((await source.read(bytes, 0, n, 4)).bytesRead !== n)
        throw Error('Incomplete backup manifest');
      manifest = JSON.parse(bytes.toString('utf8'));
      if (
        manifest.format !== 1 ||
        manifest.kind !== 'taskhub-cold-backup' ||
        !manifest.files ||
        !Array.isArray(manifest.directories) ||
        Object.keys(manifest.files).length > 1000000 ||
        manifest.directories.some((p) => !backupPath(p))
      )
        throw Error('Invalid backup manifest');
      for (const path of manifest.directories) await privateDir(join(destination, path));
      let offset = 4 + n;
      for (const [path, info] of Object.entries(manifest.files)) {
        if (
          !backupPath(path) ||
          !Number.isSafeInteger(info.size) ||
          info.size < 0 ||
          !/^[a-f0-9]{64}$/.test(info.sha256)
        )
          throw Error('Invalid backup file entry');
        const target = join(destination, path);
        await privateDir(dirname(target));
        if (info.size)
          await pipeline(
            createReadStream(plaintext, { start: offset, end: offset + info.size - 1 }),
            createWriteStream(target, { flags: 'wx', mode: 0o600 }),
          );
        else await writeFile(target, '', { flag: 'wx', mode: 0o600 });
        if ((await lstat(target)).size !== info.size || (await shaFile(target)) !== info.sha256)
          throw Error('Backup file SHA-256 mismatch');
        offset += info.size;
      }
      if ((await source.stat()).size !== offset)
        throw Error('Backup has incomplete or trailing payload');
    } finally {
      await source.close();
    }
  } catch (error) {
    await rm(destination, { recursive: true, force: true });
    throw error;
  } finally {
    await fd.close();
    await rm(plaintext, { force: true });
  }
  // A decrypted tree is always inert, including interruption before validation.
  await save(join(destination, 'offline-only.json'), { version: 1, neverStart: true });
  return manifest;
}
export function pgOfflineTools(config) {
  const root = dirname(config.pg.pg_ctl);
  const tools = { postgres: join(root, 'postgres'), control: join(root, 'pg_controldata') };
  for (const path of Object.values(tools)) {
    const major = run(path, ['--version']).match(/(\d+)\.\d+/)?.[1];
    if (major !== config.pg.major)
      throw Error('Backup requires the original PostgreSQL major tools');
  }
  return tools;
}
export function cleanCluster(data, tools) {
  const output = run(tools.control, [data], {
    env: { PATH: process.env.PATH, LC_ALL: 'C', LANG: 'C' },
  });
  if (!/^Database cluster state:\s+shut down\s*$/m.test(output))
    throw Error('Cold backup requires a cleanly shut down PostgreSQL cluster');
  return {
    systemIdentifier: output.match(/^Database system identifier:\s+(\d+)/m)?.[1],
    checkpoint: output.match(/^Latest checkpoint location:\s+(\S+)/m)?.[1],
  };
}
export function singleUser(tools, data, database, sql) {
  const result = spawnSync(
    tools.postgres,
    [
      '--single',
      '-D',
      data,
      '-c',
      'default_transaction_read_only=on',
      '-c',
      'log_statement=none',
      '-c',
      'log_min_error_statement=panic',
      database,
    ],
    {
      input: sql + '\n',
      encoding: 'utf8',
      timeout: 60000,
      maxBuffer: 4 * 1024 * 1024,
      env: { PATH: process.env.PATH, LC_ALL: 'C', LANG: 'C' },
    },
  );
  if (result.error || result.status !== 0 || /(?:ERROR|FATAL|PANIC):/.test(result.stderr ?? ''))
    throw Error('Offline PostgreSQL SQL verification failed (private output suppressed)');
}
const sqlLiteral = (value) => "'" + value.replaceAll("'", "''") + "'";
// Every public table is exported in canonical row order. Hashes cover receipts,
// unknown/attempted executions and future forward-migration tables, without
// publishing their content. Native COPY writes only into this private staging tree.
export async function auditOfflineDatabase(root, config, tools, migrations) {
  const data = join(root, 'postgres'),
    evidence = join(root, 'sql-evidence');
  if (await exists(join(data, 'postmaster.pid')))
    throw Error('Offline audit refuses a postmaster pidfile');
  cleanCluster(data, tools);
  await privateDir(evidence);
  // A dedicated cluster must not silently back up an unrelated business DB.
  singleUser(
    tools,
    data,
    'postgres',
    "SELECT 1 / CASE WHEN count(*)=1 AND min(datname)='taskhub' THEN 1 ELSE 0 END FROM pg_database WHERE NOT datistemplate AND datname<>'postgres';",
  );
  const schemaFile = join(evidence, 'schema.rows');
  await rm(schemaFile, { force: true });
  singleUser(
    tools,
    data,
    'taskhub',
    "COPY (SELECT replace(encode(convert_to(jsonb_build_object('table',c.relname,'name',k.conname,'type',k.contype,'validated',k.convalidated,'definition',pg_get_constraintdef(k.oid))::text,'UTF8'),'base64'),chr(10),'') FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' ORDER BY c.relname,k.conname) TO " +
      sqlLiteral(schemaFile) +
      ';',
  );
  // Validate existing FK data as well as catalog declarations. PostgreSQL's
  // constraints govern future writes; offline recovery checks current rows too.
  const fkFile = join(evidence, 'foreign-keys.rows');
  await rm(fkFile, { force: true });
  singleUser(
    tools,
    data,
    'taskhub',
    "COPY (SELECT replace(encode(convert_to(jsonb_build_object('table',c.relname,'referenced',r.relname,'columns',(SELECT jsonb_agg(a.attname ORDER BY x.ord) FROM unnest(k.conkey) WITH ORDINALITY x(num,ord) JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum=x.num),'refColumns',(SELECT jsonb_agg(a.attname ORDER BY x.ord) FROM unnest(k.confkey) WITH ORDINALITY x(num,ord) JOIN pg_attribute a ON a.attrelid=r.oid AND a.attnum=x.num))::text,'UTF8'),'base64'),chr(10),'') FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_class r ON r.oid=k.confrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND k.contype='f' ORDER BY c.relname,k.conname) TO " +
      sqlLiteral(fkFile) +
      ';',
  );
  const identifier = (value) => {
    if (!/^[a-z][a-z0-9_]*$/.test(value)) throw Error('Unsupported SQL relationship identifier');
    return '"' + value + '"';
  };
  for await (const line of createInterface({
    input: createReadStream(fkFile),
    crlfDelay: Infinity,
  })) {
    const fk = JSON.parse(Buffer.from(line, 'base64').toString('utf8'));
    const match = fk.columns
      .map((c, i) => 'a.' + identifier(c) + '=b.' + identifier(fk.refColumns[i]))
      .join(' AND ');
    const nonnull = fk.columns.map((c) => 'a.' + identifier(c) + ' IS NOT NULL').join(' AND ');
    singleUser(
      tools,
      data,
      'taskhub',
      'SELECT 1 / CASE WHEN NOT EXISTS (SELECT 1 FROM public.' +
        identifier(fk.table) +
        ' a WHERE ' +
        nonnull +
        ' AND NOT EXISTS (SELECT 1 FROM public.' +
        identifier(fk.referenced) +
        ' b WHERE ' +
        match +
        ')) THEN 1 ELSE 0 END;',
    );
  }
  const catalog = join(evidence, 'tables.txt');
  await rm(catalog, { force: true });
  singleUser(
    tools,
    data,
    'taskhub',
    "COPY (SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename) TO " +
      sqlLiteral(catalog) +
      ';',
  );
  const tables = (await readFile(catalog, 'utf8')).trim().split('\n');
  for (const name of [
    'taskhub_migrations',
    'business_operations',
    'execution_runs',
    'attachments',
    'tasks',
    'task_rounds',
  ])
    if (!tables.includes(name)) throw Error('Backup lacks required Task Hub SQL ledger');
  if (tables.some((t) => !/^[a-z][a-z0-9_]*$/.test(t)))
    throw Error('Unsupported SQL table identifier');
  const counts = {},
    digests = {},
    attachments = [],
    applied = [],
    phases = {};
  let unresolvedWorkerRuns = 0;
  for (const table of tables) {
    const file = join(evidence, table + '.rows');
    await rm(file, { force: true });
    singleUser(
      tools,
      data,
      'taskhub',
      "COPY (SELECT replace(encode(convert_to(to_jsonb(t)::text,'UTF8'),'base64'),chr(10),'') FROM public.\"" +
        table +
        '" t ORDER BY to_jsonb(t)::text COLLATE "C") TO ' +
        sqlLiteral(file) +
        ';',
    );
    await chmod(file, 0o600);
    counts[table] = 0;
    digests[table] = await shaFile(file);
    for await (const line of createInterface({
      input: createReadStream(file),
      crlfDelay: Infinity,
    })) {
      let row;
      try {
        row = JSON.parse(Buffer.from(line, 'base64').toString('utf8'));
      } catch {
        throw Error('Offline SQL row decoding failed (private content suppressed)');
      }
      counts[table]++;
      if (table === 'attachments') attachments.push(row);
      if (table === 'taskhub_migrations')
        applied.push({ version: row.version, checksum: row.checksum });
      if (
        ['business_operations', 'mobile_sessions'].includes(table) &&
        row.service_key !== JSON.stringify(identityKeys.map((k) => config[k]))
      )
        throw Error('SQL session or operation belongs to a different service identity');
      if (table === 'business_operations') phases[row.phase] = (phases[row.phase] ?? 0) + 1;
      if (
        table === 'execution_runs' &&
        (!['completed', 'failed', 'superseded'].includes(row.state) ||
          row.lease_owner !== null ||
          row.lease_until !== null)
      )
        unresolvedWorkerRuns++;
    }
  }
  applied.sort((a, b) => a.version - b.version);
  if (
    JSON.stringify(applied) !==
    JSON.stringify(migrations.map((m) => ({ version: m.version, checksum: m.checksum })))
  )
    throw Error('SQL migration ledger differs from the frozen original migration checksums');
  const uploaded = await privateTree(join(root, 'uploads'));
  for (const a of attachments) {
    if (
      typeof a.storage_name !== 'string' ||
      a.storage_name.includes('/') ||
      a.storage_name.includes('\\')
    )
      throw Error('Attachment SQL storage path is invalid');
    const bytes = uploaded.files[a.storage_name],
      thumbnail = a.thumbnail_name ? uploaded.files[a.thumbnail_name] : true;
    if (!bytes || bytes.sha256 !== a.sha256 || bytes.size !== a.size_bytes || !thumbnail)
      throw Error('Attachment SQL reference or byte SHA-256 is inconsistent');
  }
  return {
    counts,
    digests,
    applied,
    phases,
    attachmentFiles: uploaded.files,
    executionPolicy: 'preserve-original-operations-and-unknown-results; never-replay-on-restore',
    control: cleanCluster(data, tools),
    schemaSha256: await shaFile(schemaFile),
    foreignKeysSha256: await shaFile(fkFile),
    unresolvedWorkerRuns,
  };
}
export async function migrationInventory(bundle) {
  const directory = join(bundle, 'runtime/migrations');
  const files = (await readdir(directory)).filter((n) => /^\d{4}_[a-z_]+\.sql$/.test(n)).sort();
  if (!files.length) throw Error('Frozen bundle lacks SQL migrations');
  return Promise.all(
    files.map(async (name) => ({
      name,
      version: Number(name.slice(0, 4)),
      checksum: await shaFile(join(directory, name)),
    })),
  );
}
export const restoreActivationVersion = 2;
export const restoreActivationKind = 'taskhub-restore-activation';
export const activationUUID = (value) =>
  typeof value === 'string' && /^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/.test(value);
const activationHashes = [
  'sourceStateSha256',
  'verificationSha256',
  'archiveSha256',
  'runtimeSha256',
  'payloadSha256',
  'bundleManifestSha256',
  'manifestSha256',
];
const activationBindings = [
  'version',
  'kind',
  'source',
  'restoreId',
  'activationId',
  'identity',
  'pid',
  'installNonce',
  'controllerSha256',
  ...activationHashes,
];
function validActivation(value, state, restoreId, activationId) {
  return (
    value?.version === restoreActivationVersion &&
    value.kind === restoreActivationKind &&
    value.source === 'actual-normal-start' &&
    activationUUID(value.restoreId) &&
    activationUUID(value.activationId) &&
    value.restoreId === restoreId &&
    value.activationId === activationId &&
    value.sourceStateSha256 === hash(resolve(state)) &&
    activationHashes.every((key) => /^[a-f0-9]{64}$/.test(value[key] ?? '')) &&
    Number.isSafeInteger(value.pid) &&
    value.pid > 0 &&
    activationUUID(value.installNonce) &&
    /^[a-f0-9]{64}$/.test(value.controllerSha256 ?? '') &&
    value.identity &&
    Object.keys(value.identity).length === identityKeys.length &&
    identityKeys.every(
      (key) =>
        typeof value.identity[key] === 'string' &&
        /^[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}$/.test(value.identity[key]),
    )
  );
}
function sameActivation(left, right) {
  return activationBindings.every((key) =>
    key === 'identity' ? sameIdentity(left.identity, right.identity) : left[key] === right[key],
  );
}
async function verifiedActivationBinding(state, value) {
  const proofPath = join(state, 'recovery', value.restoreId, 'restore-verification.json');
  const proof = await json(proofPath, true),
    runtime = await json(join(state, 'runtime.json'), true);
  return (
    proof.version === 2 &&
    proof.kind === 'taskhub-restore-verification' &&
    proof.source === 'native-single-user-cold-restore' &&
    proof.restoreId === value.restoreId &&
    proof.action === 'original-primary-restored-fenced' &&
    proof.sqlRestoreVerified === true &&
    proof.sourceStateSha256 === value.sourceStateSha256 &&
    sameIdentity(proof.identity, value.identity) &&
    sameIdentity(runtime, value.identity) &&
    ['archiveSha256', 'payloadSha256', 'bundleManifestSha256', 'manifestSha256'].every(
      (key) => proof[key] === value[key],
    ) &&
    runtime.payloadSha256 === value.payloadSha256 &&
    (await shaFile(proofPath)) === value.verificationSha256 &&
    (await shaFile(join(state, 'runtime.json'))) === value.runtimeSha256 &&
    (await shaFile(join(runtime.bundle, 'manifest.json'))) === value.bundleManifestSha256
  );
}
export async function readCommittedActivation(state, restoreId, activationId, { expected } = {}) {
  if (!activationUUID(restoreId) || !activationUUID(activationId)) return null;
  const family = join(state, 'recovery', restoreId, 'activations', activationId);
  // Presence dominates, including an unreadable or incomplete failure marker.
  if (
    (await exists(join(family, 'failed.json'))) ||
    !(await exists(join(family, 'committed.json')))
  )
    return null;
  const receipt = JSON.parse(
    (await strictPrivateBytes(join(family, 'committed.json'), 1024 * 1024)).toString('utf8'),
  );
  const intent = JSON.parse(
    (await strictPrivateBytes(join(family, 'intent.json'), 1024 * 1024)).toString('utf8'),
  );
  if (
    !validActivation(receipt, state, restoreId, activationId) ||
    !validActivation(intent, state, restoreId, activationId) ||
    intent.action !== 'restore-activation-intent' ||
    intent.phase !== 'prepared' ||
    receipt.action !== 'restore-activated' ||
    receipt.phase !== 'activated' ||
    receipt.serviceStarted !== true ||
    receipt.fenceRetained !== false ||
    receipt.evidence !== 'actual-normal-start' ||
    typeof receipt.loginAutostart !== 'boolean' ||
    !sameActivation(receipt, intent) ||
    (expected && !sameActivation(receipt, expected)) ||
    !(await verifiedActivationBinding(state, receipt))
  )
    return null;
  if (await exists(join(family, 'failed.json'))) return null;
  return receipt;
}
export async function assertStartAllowed(state, { activationId, runtime = false } = {}) {
  for (const name of ['offline-only.json', 'maintenance.json'])
    if (await exists(join(state, name)))
      throw Error('Primary start is fenced by ' + name + '; preserve state and reconcile recovery');
  const fenced = await exists(join(state, 'restore-fence.json'));
  const pending = await exists(join(state, 'restore-activation.json'));
  if (!pending) {
    if (fenced || activationId)
      throw Error(
        'Primary start is fenced by restore-fence.json; preserve state and reconcile recovery',
      );
    return null;
  }
  const grant = await json(join(state, 'restore-activation.json'), true);
  if (
    !validActivation(grant, state, grant.restoreId, grant.activationId) ||
    grant.action !== 'restore-activation-intent' ||
    grant.phase !== 'prepared'
  )
    throw Error('Restore activation fence conflicts with the explicit operation');
  if (
    await exists(
      join(state, 'recovery', grant.restoreId, 'activations', grant.activationId, 'failed.json'),
    )
  )
    throw Error('Restore activation failed; primary remains fenced');
  if (
    (await shaFile(join(state, 'restore-activation.json'))) !==
    (await shaFile(
      join(state, 'recovery', grant.restoreId, 'activations', grant.activationId, 'intent.json'),
    ))
  )
    throw Error('Restore activation pending grant differs from immutable intent');
  if (fenced) {
    const fence = await json(join(state, 'restore-fence.json'), true);
    if (
      fence.state !== 'activating' ||
      fence.restoreId !== grant.restoreId ||
      fence.activationId !== grant.activationId ||
      fence.verificationSha256 !== grant.verificationSha256
    )
      throw Error('Restore activation fence conflicts with the verified restoration');
  } else if (await activationCommitted(state, grant)) {
    if (activationId && activationId !== grant.activationId)
      throw Error('Restore activation ID conflicts with committed receipt');
    return grant;
  }
  if (!runtime && activationId !== grant.activationId)
    throw Error('Restore activation fence requires the explicit operation');
  const owner = await json(join(state, 'install.lock/owner.json'), true);
  if (
    owner.action !== 'restore-activate' ||
    owner.pid !== grant.pid ||
    owner.nonce !== grant.installNonce ||
    !alive(owner.pid) ||
    processFingerprint(owner.pid) !== grant.controllerSha256
  )
    throw Error('Restore activation owner is absent or unknown; primary remains fenced');
  if (!(await verifiedActivationBinding(state, grant)))
    throw Error('Restore activation proof or configuration changed');
  return grant;
}
async function activationCommitted(state, grant) {
  return !!(await readCommittedActivation(state, grant.restoreId, grant.activationId, {
    expected: grant,
  }));
}
export async function assertActivationRuntime(state, grant) {
  if (await exists(join(state, 'restore-activation.json'))) {
    const current = await assertStartAllowed(state, { runtime: true });
    if (current?.activationId !== grant.activationId)
      throw Error('Restore activation runtime grant changed');
    return;
  }
  await assertStartAllowed(state);
  if (!(await activationCommitted(state, grant)))
    throw Error('Restore activation never committed; runtime must stop');
}
