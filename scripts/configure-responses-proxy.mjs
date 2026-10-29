import { randomBytes, X509Certificate } from 'node:crypto';
import {
  mkdir,
  readFile,
  writeFile,
  lstat,
  realpath,
  mkdtemp,
  chmod,
  rename,
  rm,
} from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { resolve, join } from 'node:path';
import { isIP } from 'node:net';
import {
  assertPrivateFile,
  protectWindowsStateDirectory,
  protectWindowsStateFile,
  withWindowsProcessCreation,
  desktopImageModels,
} from '../packages/codex-adapter/dist/index.js';

const flags = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  const key = process.argv[index],
    value = process.argv[index + 1];
  if (
    !['--state-dir', '--models', '--image-models', '--addresses', '--bind', '--port'].includes(
      key,
    ) ||
    !value ||
    flags.has(key)
  )
    throw Error('Use --state-dir, --models, --image-models, --addresses, --bind and --port');
  flags.set(key, value);
}
const directory = resolve(
  flags.get('--state-dir') ??
    join(process.env.HOST_AGENT_STATE_DIR ?? '.local/desktop-runtime', 'responses-proxy'),
);
const models = JSON.parse(flags.get('--models') ?? '[]');
const imageModels = JSON.parse(flags.get('--image-models') ?? '[]');
const addresses = JSON.parse(flags.get('--addresses') ?? '["127.0.0.1"]');
const bind = flags.get('--bind') ?? '127.0.0.1';
const port = Number(flags.get('--port') ?? 8797);
if (
  !Array.isArray(models) ||
  models.length > 64 ||
  models.some((id) => typeof id !== 'string' || !/^[A-Za-z0-9_.:-]{1,128}$/.test(id)) ||
  new Set(models).size !== models.length
)
  throw Error('Provide an explicit, unique model allowlist');
if (
  !Array.isArray(imageModels) ||
  imageModels.some((id) => !desktopImageModels.includes(id)) ||
  new Set(imageModels).size !== imageModels.length ||
  (!models.length && !imageModels.length)
)
  throw Error('Provide text models or the verified image model gpt-image-2');
if (
  !Array.isArray(addresses) ||
  !addresses.length ||
  addresses.length > 32 ||
  addresses.some((address) => typeof address !== 'string' || !isIP(address)) ||
  !isIP(bind) ||
  !Number.isInteger(port) ||
  port < 1 ||
  port > 65535
)
  throw Error('Invalid listener or certificate IP addresses');
await mkdir(directory, { recursive: true, mode: 0o700 });
const info = await lstat(directory);
if (
  !info.isDirectory() ||
  (await realpath(directory)) !== directory ||
  (process.platform !== 'win32' && info.mode & 0o077)
)
  throw Error('Use a private, owned, non-symlink state directory');
protectWindowsStateDirectory(directory);
const cert = join(directory, 'tls-cert.pem'),
  key = join(directory, 'tls-key.pem');
const exists = (path) =>
  lstat(path).then(
    () => true,
    (error) => {
      if (error.code === 'ENOENT') return false;
      throw error;
    },
  );
const present = await Promise.all([exists(cert), exists(key)]);
if (present.some(Boolean) && !present.every(Boolean))
  throw Error('Restore the original incomplete TLS identity; refusing automatic rotation');
if (!present[0]) {
  const scratch = await mkdtemp(join(directory, 'certificate-'));
  try {
    const executable =
      process.env.HOST_AGENT_OPENSSL ??
      (process.platform === 'win32'
        ? join(process.env.ProgramFiles ?? 'C:\\Program Files', 'Git', 'usr', 'bin', 'openssl.exe')
        : '/usr/bin/openssl');
    withWindowsProcessCreation(15000, (timeout) =>
      execFileSync(
        executable,
        [
          'req',
          '-x509',
          '-newkey',
          'rsa:2048',
          '-nodes',
          '-sha256',
          '-days',
          '365',
          '-keyout',
          join(scratch, 'key.pem'),
          '-out',
          join(scratch, 'cert.pem'),
          '-subj',
          '/CN=Agent Hub Responses Proxy',
          '-addext',
          'subjectAltName=' + addresses.map((address) => 'IP:' + address).join(','),
        ],
        { timeout, windowsHide: true, stdio: ['ignore', 'ignore', 'pipe'] },
      ),
    );
    for (const name of ['key.pem', 'cert.pem']) {
      await chmod(join(scratch, name), 0o600);
      protectWindowsStateFile(join(scratch, name));
    }
    await rename(join(scratch, 'key.pem'), key);
    await rename(join(scratch, 'cert.pem'), cert);
  } finally {
    await rm(scratch, { recursive: true, force: true });
  }
}
assertPrivateFile(key);
const certificate = new X509Certificate(await readFile(cert));
if (
  addresses.some((address) => certificate.checkIP(address) === undefined) ||
  Date.parse(certificate.validTo) <= Date.now()
)
  throw Error(
    'Existing certificate does not match the requested addresses or has expired; keep it and configure a new explicit identity',
  );
const tokenFile = join(directory, 'access-token');
try {
  await writeFile(tokenFile, randomBytes(32).toString('base64url'), { flag: 'wx', mode: 0o600 });
  protectWindowsStateFile(tokenFile);
} catch (error) {
  if (error.code !== 'EEXIST') throw error;
}
assertPrivateFile(tokenFile);
const environment = {
  HOST_RESPONSES_PROXY_ENABLED: '0',
  HOST_RESPONSES_PROXY_BIND: bind,
  HOST_RESPONSES_PROXY_PORT: String(port),
  HOST_RESPONSES_PROXY_STATE_DIR: directory,
  HOST_RESPONSES_PROXY_TOKEN_FILE: tokenFile,
  HOST_RESPONSES_PROXY_TLS_CERT: cert,
  HOST_RESPONSES_PROXY_TLS_KEY: key,
  HOST_RESPONSES_PROXY_MODELS: JSON.stringify(models),
  HOST_RESPONSES_PROXY_IMAGE_MODELS: JSON.stringify(imageModels),
};
const configuration = {
  enabled: false,
  environment,
  certificateFingerprint: certificate.fingerprint256,
  certificateExpiresAt: certificate.validTo,
  tokenFile,
};
await writeFile(
  join(directory, 'configuration.json'),
  JSON.stringify(configuration, null, 2) + '\n',
  { mode: 0o600 },
);
protectWindowsStateFile(join(directory, 'configuration.json'));
// Preparing configuration never starts a listener or enables inference.
console.log(JSON.stringify(configuration, null, 2));
