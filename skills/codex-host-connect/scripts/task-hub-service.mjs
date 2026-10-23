import {
  createHash,
  generateKeyPairSync,
  randomBytes,
  randomUUID,
  X509Certificate,
} from 'node:crypto';
import {
  chmod,
  cp,
  lstat,
  mkdir,
  open,
  readFile,
  readdir,
  realpath,
  rename,
  rm,
  writeFile,
} from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { request as httpRequest } from 'node:http';
import { homedir, networkInterfaces } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import {
  acquireLock,
  alive,
  assertId,
  assertNode24,
  assertBusinessBundle,
  availablePort,
  assertStartAllowed,
  auditOfflineDatabase,
  backupConfigFiles,
  backupKey,
  businessConfigEnv,
  businessMaterial,
  businessDescriptor,
  below,
  cleanCluster,
  discover,
  exists,
  hash,
  identityKeys,
  json,
  keyFingerprint,
  networkRequire,
  migrationInventory,
  openBackup,
  optionalBackupConfigFiles,
  pgOfflineTools,
  port,
  privateDir,
  privateFile,
  privateTree,
  processFingerprint,
  probe,
  run,
  removeDurable,
  restoreActivationVersion,
  restoreActivationKind,
  activationUUID,
  readCommittedActivation,
  readBusinessNotifications,
  sameIdentity,
  save,
  saveImmutable,
  sealBackup,
  shaFile,
  signedRegistration,
  syncDirectory,
  tool,
  verifyBundle,
  verifyRegistration,
} from './task-hub-core.mjs';

const defaultState = () => join(homedir(), '.config/agent-hub/task-hub');
export function argumentsFor(args) {
  const result = { action: args.shift() ?? 'status' };
  const flags = new Set([
    'first-primary',
    'no-autostart',
    'isolated-dev',
    'disable-worker',
    'create-key',
    'offline',
    'in-place',
    'verify-only',
  ]);
  const keys = new Set([
    'state',
    'bundle',
    'host-runtime',
    'primary-host-id',
    'deployment-id',
    'instance-id',
    'domain',
    'name',
    'tls-port',
    'api-port',
    'pg-port',
    'registry',
    'trust-key',
    'bind',
    'legacy-api-origin',
    'output',
    'key-file',
    'archive',
    'destination',
    'restore-id',
    'business-notifications-config',
  ]);
  while (args.length) {
    const raw = args.shift();
    if (!raw.startsWith('--')) throw Error('Use named Task Hub options');
    const key = raw.slice(2);
    if (Object.hasOwn(result, key)) throw Error('Duplicate option: ' + key);
    if (flags.has(key)) result[key] = true;
    else if (keys.has(key) && args.length && !args[0].startsWith('--')) result[key] = args.shift();
    else throw Error('Unknown or missing Task Hub option: ' + key);
  }
  return result;
}
async function local(state) {
  return (await exists(join(state, 'runtime.json')))
    ? json(join(state, 'runtime.json'), true)
    : null;
}
async function registration(state) {
  return verifyRegistration(
    await json(join(state, 'deployment.json')),
    (await json(join(state, 'trust.json'), true)).registryKeySha256,
  );
}
export async function preflight(options) {
  const state = resolve(options.state ?? defaultState()),
    installed = await local(state);
  const bundle = options.bundle ? resolve(options.bundle) : installed?.bundle;
  const manifest = bundle
    ? await verifyBundle(bundle, { primary: options.action !== 'executor' })
    : null;
  let standardPrimary = { registered: false };
  if (
    state !== resolve(defaultState()) &&
    (await exists(join(defaultState(), 'deployment.json')))
  ) {
    try {
      const standard = await registration(defaultState());
      standardPrimary = {
        registered: true,
        ...Object.fromEntries(identityKeys.map((k) => [k, standard[k]])),
        endpoint: standard.endpoints[0],
      };
    } catch {
      standardPrimary = {
        registered: true,
        error: 'existing-standard-registration-cannot-be-verified; preserve-it',
      };
    }
  }
  const legacy = await legacyApi(options['legacy-api-origin'] ?? 'http://127.0.0.1:4311');
  let known,
    discovery = [],
    discoveryError = null,
    identity = { identityVerified: false, backendReady: false };
  if (installed) known = await registration(state);
  if (options.registry) {
    const external = verifyRegistration(
      await json(resolve(options.registry)),
      options['trust-key'],
    );
    if (known && !sameIdentity(known, external))
      throw Error(
        'Registration conflicts with the preserved deployment; migration fencing is required',
      );
    known = external;
  }
  if (bundle) {
    try {
      discovery = await discover(bundle);
    } catch {
      discoveryError = 'discovery-unavailable';
    }
  }
  if (
    known &&
    discovery.some((d) => d.deploymentId === known.deploymentId && !sameIdentity(d, known))
  )
    throw Error('Multiple primary identities advertised for the same deployment');
  if (
    !known &&
    options['deployment-id'] &&
    discovery.some((d) => d.deploymentId === options['deployment-id'])
  )
    throw Error(
      'An existing deployment was discovered; obtain its trusted registration before installing',
    );
  if (known) {
    try {
      identity = await probe(known);
    } catch {
      identity = {
        identityVerified: false,
        backendReady: false,
        reason: 'offline, TLS or identity mismatch; primary remains reserved',
      };
    }
  }
  let dependencies = null;
  if (options.action !== 'executor' && process.platform === 'darwin') {
    try {
      dependencies = {};
      for (const name of ['initdb', 'pg_ctl']) dependencies[name] = await tool(name);
    } catch {
      dependencies = { ready: false, reason: 'PostgreSQL dependency missing' };
    }
  }
  return {
    installed: installed?.role ?? false,
    standardPrimary,
    legacyTaskApi: legacy,
    isolation: options['isolated-dev']
      ? 'explicit-new-dev-deployment; old-database-not-migrated'
      : 'normal-designated-primary',
    manifest: manifest
      ? {
          payloadSha256: manifest.payloadSha256,
          sourceSha256: manifest.sourceSha256,
          primaryPlatforms: manifest.primaryPlatforms,
        }
      : null,
    known: known ? Object.fromEntries(identityKeys.map((k) => [k, known[k]])) : null,
    discovery,
    discoveryError,
    hostDirectory: 'not-implemented-by-current-host; no directory results fabricated',
    identity,
    dependencies,
    networkInterfaces: Object.values(networkInterfaces())
      .flat()
      .filter((n) => n && !n.internal)
      .map((n) => ({ family: n.family, address: n.address })),
    decision: installed
      ? 'preserve-installed-role-and-database'
      : known
        ? 'reuse-designated-primary; never-create-another-database'
        : options['first-primary']
          ? 'explicit-primary-designation-required'
          : 'executor-awaits-trusted-primary',
    businessAcceptance: 'not-verified',
  };
}
async function legacyApi(origin) {
  const endpoint = new URL(origin);
  if (
    endpoint.protocol !== 'http:' ||
    endpoint.hostname !== '127.0.0.1' ||
    endpoint.username ||
    endpoint.password ||
    endpoint.search ||
    endpoint.hash ||
    endpoint.pathname !== '/'
  )
    throw Error('Legacy API inspection must target an explicit loopback HTTP origin');
  const read = (path) =>
    new Promise((res, rej) => {
      const req = httpRequest(new URL(path, endpoint), { timeout: 1200 }, (response) => {
        let data = '';
        response.on('data', (b) => {
          data += b;
          if (data.length > 4 * 1024 * 1024) req.destroy();
        });
        response.on('end', () => {
          try {
            if (response.statusCode !== 200) throw Error();
            res(JSON.parse(data));
          } catch {
            rej(Error('not-a-task-api'));
          }
        });
      });
      req.on('error', rej);
      req.on('timeout', () => req.destroy(Error('legacy-probe-timeout')));
      req.end();
    });
  try {
    const schema = await read('/api/v1/openapi.json');
    if (schema.info?.title !== 'Agent Task Hub')
      return {
        origin: endpoint.origin,
        detected: false,
        absenceProved: false,
        reason: 'not-identified-as-task-api',
      };
    const health = await read('/health');
    return {
      origin: endpoint.origin,
      detected: true,
      databaseReady: health.database === 'ok',
      risk: 'existing-task-api-worker-and-database-preserved; discovery-silence-does-not-authorize-new-production-database',
    };
  } catch {
    return {
      origin: endpoint.origin,
      detected: false,
      absenceProved: false,
      reason: 'probe-no-response-or-unidentified; absence-not-established',
    };
  }
}
function requireMac() {
  if (process.platform !== 'darwin')
    throw Error('Primary install/start/upgrade supports macOS only; Windows can be an executor');
}
function launchConfig(state) {
  const label = 'com.agenthub.task.' + hash(state).slice(0, 16),
    domain = 'gui/' + process.getuid();
  return {
    label,
    domain,
    target: domain + '/' + label,
    plist: join(homedir(), 'Library/LaunchAgents', label + '.plist'),
  };
}
function launchInstalled(target) {
  try {
    run('/bin/launchctl', ['print', target]);
    return true;
  } catch {
    return false;
  }
}
export function launchJobState(output, disabledOutput, label) {
  const loaded = typeof output === 'string';
  const disabled =
    typeof disabledOutput === 'string'
      ? new RegExp('"' + label.replaceAll('.', '\\.') + '"\\s*=>\\s*true').test(disabledOutput)
      : null;
  return {
    loaded,
    disabled,
    state: loaded ? (output.match(/\bstate = ([^\n]+)/)?.[1]?.trim() ?? 'unknown') : 'not-loaded',
    pid: loaded ? Number(output.match(/\bpid = (\d+)/)?.[1]) || null : null,
    lastExitCode: loaded ? Number(output.match(/last exit code = (-?\d+)/)?.[1] ?? NaN) : null,
  };
}
async function autostartState(state, config) {
  if (process.platform !== 'darwin' || config.role !== 'primary')
    return {
      configured: false,
      loaded: false,
      enabled: false,
      disabled: null,
      state: 'not-applicable',
    };
  const launch = launchConfig(state);
  let configured = false;
  if (await exists(launch.plist)) {
    await privateFile(launch.plist);
    const content = await readFile(launch.plist, 'utf8');
    configured =
      [launch.label, config.node, join(config.bundle, 'tools/task-hub-runtime.mjs'), state].every(
        (s) => content.includes('<string>' + xml(s) + '</string>'),
      ) &&
      content.includes('<key>RunAtLoad</key><true/>') &&
      content.includes('<key>KeepAlive</key><true/>');
  }
  let output, disabled;
  try {
    output = run('/bin/launchctl', ['print', launch.target]);
  } catch {
    /* Not loaded. */
  }
  try {
    disabled = run('/bin/launchctl', ['print-disabled', launch.domain]);
  } catch {
    /* Unknown. */
  }
  const job = launchJobState(output, disabled, launch.label);
  return { configured, ...job, enabled: configured && job.loaded && job.disabled === false };
}
const xml = (value) =>
  String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
export async function status(state) {
  const fences = [];
  for (const name of [
    'offline-only.json',
    'restore-fence.json',
    'maintenance.json',
    'restore-activation.json',
  ])
    if (await exists(join(state, name))) fences.push(name);
  const config = await local(state);
  if (!config) {
    const reserved = await exists(join(state, 'deployment.json'));
    return {
      installed: false,
      partialInstallation: reserved || fences.length > 0,
      primaryEligibility: fences.length
        ? 'fenced'
        : reserved
          ? 'reserved-unverified'
          : 'uninstalled',
      fences,
      businessAcceptance: 'not-verified',
    };
  }
  const known = await registration(state);
  const lock = (await exists(join(state, 'instance.lock/owner.json')))
    ? await json(join(state, 'instance.lock/owner.json'), true)
    : null;
  const live = !!lock && alive(lock.pid);
  let ownerVerified = false;
  if (live) {
    try {
      ownerVerified = ownedProcess(
        lock.pid,
        join(config.bundle, 'tools/task-hub-runtime.mjs'),
        state,
      );
    } catch {
      /* A reused PID never proves a runtime, and remains reserved. */
    }
  }
  let verified = { identityVerified: false, backendReady: false };
  try {
    verified = await probe(known, config.role === 'primary' ? { connectHost: '127.0.0.1' } : {});
  } catch {
    /* Offline is distinct from no installation. */
  }
  const launch = await autostartState(state, config);
  const ready = (await exists(join(state, 'ready.json')))
    ? await json(join(state, 'ready.json'), true)
    : null;
  const runtimeHealthy =
    ownerVerified &&
    verified.backendReady &&
    ready?.pid === lock.pid &&
    ready?.nonce === lock.nonce &&
    sameIdentity(ready.identity ?? {}, config) &&
    ready.payloadSha256 === config.payloadSha256;
  const workerEnabled =
    config.role === 'primary' &&
    (await json(join(state, 'env.json'), true)).HOST_BACKGROUND_JOBS !== '0';
  let committedActivationGuard = false;
  if (fences.length === 1 && fences[0] === 'restore-activation.json') {
    try {
      committedActivationGuard = !!(await assertStartAllowed(state));
    } catch {
      /* An uncommitted guard remains fenced. */
    }
  }
  return {
    installed: true,
    role: config.role,
    ...Object.fromEntries(identityKeys.map((k) => [k, known[k]])),
    endpoint: known.endpoints[0],
    certificateSha256: known.certificateSha256,
    registryKeySha256: (await json(join(state, 'trust.json'), true)).registryKeySha256,
    payloadSha256: config.payloadSha256,
    processRunning: live,
    processOwnershipVerified: ownerVerified,
    loginAutostart: launch.enabled,
    autostart: launch,
    runtimeHealthy: !!runtimeHealthy,
    primaryEligibility:
      fences.length && !committedActivationGuard
        ? 'fenced'
        : config.role === 'primary'
          ? 'designated'
          : 'executor',
    fences,
    committedActivationGuard,
    ...verified,
    worker: {
      enabled: workerEnabled,
      active: workerEnabled && runtimeHealthy ? null : false,
      reason:
        workerEnabled && runtimeHealthy
          ? 'no-independent-worker-liveness-contract'
          : 'disabled-or-runtime-not-healthy',
    },
    bonjour: runtimeHealthy ? (ready.bonjour ?? 'unknown') : 'not-running',
    discovery: {
      transport: runtimeHealthy ? (ready.discoveryTransport ?? 'legacy-raw-multicast') : null,
      publication: runtimeHealthy ? (ready.bonjour ?? 'unknown') : 'not-running',
      errorCode: runtimeHealthy ? (ready.discoveryError ?? null) : null,
      lanDiscoveryVerified: false,
      reason: 'local publication receipt does not prove another device discovered the service',
    },
    businessAcceptance: 'not-verified',
  };
}
async function copyRelease(bundle, state, manifest) {
  const release = join(state, 'releases', manifest.payloadSha256);
  if (await exists(release)) {
    await verifyBundle(release);
    return release;
  }
  const temp = release + '.installing';
  if (await exists(temp)) throw Error('Incomplete release copy preserved; inspect before recovery');
  await mkdir(dirname(release), { recursive: true, mode: 0o700 });
  await cp(bundle, temp, { recursive: true, verbatimSymlinks: true });
  await verifyBundle(temp);
  const { rename } = await import('node:fs/promises');
  await rename(temp, release);
  return release;
}
export async function prepareBusinessConfiguration(
  state,
  { previous, identity, configPath, sourceBundle, release, manifest },
) {
  const explicit = configPath !== undefined;
  const path = explicit ? configPath : previous?.businessNotificationsConfigPath;
  if (path === undefined) return null;
  assertBusinessBundle(manifest);
  const old = previous ? await businessMaterial(state, { allowMissingKey: true }) : null;
  const material = await readBusinessNotifications(path, identity, {
    state,
    allowMissingKey: !old?.keyPresent,
  });
  const acceptedPin =
    (material.config.bundleRoot === sourceBundle &&
      material.config.payloadSha256 === manifest.payloadSha256) ||
    (previous &&
      material.config.bundleRoot === previous.bundle &&
      material.config.payloadSha256 === previous.payloadSha256);
  if (!acceptedPin) throw Error('Business notification canonical release pin rejected');
  if (
    old &&
    ((!explicit &&
      (path !== previous.businessNotificationsConfigPath ||
        material.config.keyDirectory !== old.config.keyDirectory)) ||
      (old.keyPresent && material.keySha256 !== old.keySha256))
  )
    throw Error('Upgrade must preserve the existing business publisher key and configured paths');
  return {
    path,
    material,
    next: { ...material.config, bundleRoot: release, payloadSha256: manifest.payloadSha256 },
  };
}
export async function writeBusinessConfiguration(state, notification, identity) {
  if (!notification) return;
  const checked = await readBusinessNotifications(notification.path, identity, {
    state,
    allowMissingKey: !notification.material.keyPresent,
  });
  if (
    checked.configSha256 !== notification.material.configSha256 ||
    checked.keySha256 !== notification.material.keySha256
  )
    throw Error('Business notification configuration changed during install/upgrade');
  await save(notification.path, notification.next);
  await save(join(state, 'business-key-binding.json'), {
    version: 1,
    ...Object.fromEntries(identityKeys.map((k) => [k, identity[k]])),
    configPath: notification.path,
    keyDirectory: notification.next.keyDirectory,
    phase: notification.material.keyPresent ? 'initialized' : 'initial-pending',
    keySha256: notification.material.keySha256,
  });
}
async function install(options, state) {
  requireMac();
  assertNode24();
  if (!options.bundle) throw Error('Install/upgrade requires an explicit frozen --bundle');
  const manifest = await verifyBundle(resolve(options.bundle));
  const check = await preflight(options);
  await save(join(state, 'preflight.json'), { checkedAt: new Date().toISOString(), ...check });
  const previous = await local(state);
  if (previous) {
    await assertStartAllowed(state);
    if (previous.role !== 'primary')
      throw Error('Executor cannot be promoted; old-primary fencing must be proved');
    const known = await registration(state);
    for (const [flag, key] of [
      ['deployment-id', 'deploymentId'],
      ['instance-id', 'serverInstanceId'],
      ['primary-host-id', 'primaryHostId'],
    ])
      if (options[flag] && options[flag] !== known[key])
        throw Error('Upgrade cannot change stable identity; migration fencing is required');
    if ((await status(state)).processRunning) {
      if (
        previous.payloadSha256 === manifest.payloadSha256 &&
        options['business-notifications-config'] === undefined
      ) {
        assertNode24(previous.node);
        await businessMaterial(state);
        return { action: 'reused', ...(await status(state)) };
      }
      throw Error(
        'Upgrade requires explicit Task Hub stop; installer never restarts a shared service',
      );
    }
    if (
      (await exists(join(state, 'resources.json'))) &&
      alive((await json(join(state, 'resources.json'), true)).apiPid)
    )
      throw Error('Upgrade requires explicit stop of this installation orphan API');
    if (await exists(join(previous.pg.data, 'postmaster.pid')))
      throw Error('Upgrade requires explicit stop of this installation PostgreSQL');
    if ((await json(previous.hostRuntime)).hostId !== known.primaryHostId)
      throw Error('Original primary Host identity changed');
    const launch = await autostartState(state, previous);
    if (
      launch.configured ||
      launch.loaded ||
      (await exists(join(state, 'ready.json'))) ||
      (await exists(join(state, 'resources.json')))
    )
      throw Error(
        'Upgrade requires a fully stopped original primary with no loaded job or unknown Worker',
      );
    for (const [p, h] of [
      [previous.pgPort, '127.0.0.1'],
      [previous.apiPort, '127.0.0.1'],
      [previous.tlsPort, previous.bind],
    ])
      await availablePort(p, h);
    const release = await copyRelease(resolve(options.bundle), state, manifest);
    const notification = await prepareBusinessConfiguration(state, {
      previous,
      identity: known,
      configPath: options['business-notifications-config'],
      sourceBundle: resolve(options.bundle),
      release,
      manifest,
    });
    const instance = await acquireLock(join(state, 'instance.lock'), { action: 'upgrade' });
    let finished = false;
    try {
      await save(join(state, 'maintenance.json'), {
        version: 1,
        action: 'upgrade',
        operationId: instance.owner.nonce,
      });
      const rollback = join(state, 'recovery', 'upgrade-' + instance.owner.nonce);
      await privateDir(rollback);
      for (const name of ['runtime.json', 'env.json', ...optionalBackupConfigFiles])
        if (await exists(join(state, name))) await cp(join(state, name), join(rollback, name));
      if (notification) await cp(notification.path, join(rollback, 'business-config.json'));
      await save(join(rollback, 'journal.json'), {
        version: 1,
        phase: 'prepared',
        configPath: notification?.path ?? null,
        oldPayloadSha256: previous.payloadSha256,
        newPayloadSha256: manifest.payloadSha256,
      });
      await writeBusinessConfiguration(state, notification, known);
      const env = await json(join(state, 'env.json'), true);
      if (notification) env[businessConfigEnv] = notification.path;
      await save(join(state, 'env.json'), env);
      await save(join(state, 'runtime.json'), {
        ...previous,
        node: process.execPath,
        bundle: release,
        payloadSha256: manifest.payloadSha256,
        ...(notification ? { businessNotificationsConfigPath: notification.path } : {}),
      });
      await businessMaterial(state, { allowMissingKey: true });
      await save(join(rollback, 'journal.json'), {
        version: 1,
        phase: 'completed',
        configPath: notification?.path ?? null,
        oldPayloadSha256: previous.payloadSha256,
        newPayloadSha256: manifest.payloadSha256,
      });
      finished = true;
    } finally {
      if (finished) await removeDurable(join(state, 'maintenance.json'));
      await instance.release();
    }
    return { action: 'upgraded-or-reused', state, ...(await status(state)) };
  }
  if (options.action === 'upgrade')
    throw Error('Upgrade requires an existing primary installation');
  if (options.registry || (await exists(join(state, 'deployment.json'))))
    throw Error(
      'An existing registration reserves its primary; refuse to initialize a second database',
    );
  if (
    (check.standardPrimary.registered || check.legacyTaskApi.detected) &&
    !options['isolated-dev']
  )
    throw Error(
      'An existing primary registration or legacy Task Hub API reserves the production deployment; explicit isolated DEV is allowed, production migration requires fencing and data reconciliation',
    );
  if (options['isolated-dev'] && check.standardPrimary.deploymentId === options['deployment-id'])
    throw Error('Isolated DEV must use a new deploymentId, not the registered production identity');
  if (
    !options['first-primary'] ||
    !options['primary-host-id'] ||
    !options['host-runtime'] ||
    !options['deployment-id']
  )
    throw Error(
      'First primary needs explicit --first-primary, --deployment-id, --primary-host-id and --host-runtime; discovery silence never authorizes creation',
    );
  if (check.discoveryError)
    throw Error('Discovery preflight failed; cannot treat it as no existing primary');
  const hostRuntime = resolve(options['host-runtime']);
  const primaryHostId = assertId(options['primary-host-id']);
  if ((await json(hostRuntime)).hostId !== primaryHostId)
    throw Error('Designated primaryHostId differs from this computer Host');
  const deploymentId = assertId(options['deployment-id']),
    serverInstanceId = assertId(options['instance-id'] ?? randomUUID());
  const domain = options.domain ?? 'agenthub-task-' + hash(deploymentId).slice(0, 12) + '.local';
  if (!/^[a-z0-9-]{1,63}\.local$/.test(domain))
    throw Error('Use a stable Bonjour .local deployment domain');
  const tlsPort = port(options['tls-port'] ?? 9443),
    apiPort = port(options['api-port'] ?? 9444),
    pgPort = port(options['pg-port'] ?? 55432);
  if (new Set([tlsPort, apiPort, pgPort]).size !== 3)
    throw Error('Task Hub ports must be distinct');
  const bind = options.bind ?? '0.0.0.0';
  if (
    !['0.0.0.0', '127.0.0.1'].includes(bind) &&
    !Object.values(networkInterfaces())
      .flat()
      .some((n) => n?.address === bind)
  )
    throw Error('TLS bind must be a local interface address');
  await availablePort(tlsPort, bind);
  await availablePort(apiPort);
  await availablePort(pgPort);
  const pgTools = {};
  for (const name of ['initdb', 'pg_ctl']) pgTools[name] = await tool(name);
  pgTools.major = run(pgTools.initdb, ['--version']).match(/(\d+)\.\d+/)?.[1];
  if (!pgTools.major) throw Error('PostgreSQL version could not be verified');
  const release = await copyRelease(resolve(options.bundle), state, manifest);
  const notification = await prepareBusinessConfiguration(state, {
    identity: { deploymentId, serverInstanceId, primaryHostId },
    configPath: options['business-notifications-config'],
    sourceBundle: resolve(options.bundle),
    release,
    manifest,
  });
  for (const f of ['tls-key.pem', 'tls-cert.pem', 'registry-key.pem', 'env.json'])
    if (await exists(join(state, f)))
      throw Error(
        'Partial private installation exists; preserve and reconcile before initializing',
      );
  const endpoint = 'https://' + domain + ':' + tlsPort;
  const name = options.name ?? 'Agent Hub Task Hub';
  if (!name.trim() || Buffer.byteLength(name) > 80)
    throw Error('Task Hub display name must be 1–80 UTF-8 bytes');
  const tlsConfig =
    '[req]\ndistinguished_name=dn\nx509_extensions=extensions\nprompt=no\n[dn]\nCN=' +
    domain +
    '\n[extensions]\nsubjectAltName=DNS:' +
    domain +
    '\nbasicConstraints=critical,CA:TRUE\nkeyUsage=critical,digitalSignature,keyEncipherment,keyCertSign\nextendedKeyUsage=serverAuth\n';
  await save(join(state, 'tls.conf'), tlsConfig);
  run('/usr/bin/openssl', [
    'req',
    '-x509',
    '-newkey',
    'rsa:3072',
    '-sha256',
    '-nodes',
    '-days',
    '825',
    '-config',
    join(state, 'tls.conf'),
    '-keyout',
    join(state, 'tls-key.pem'),
    '-out',
    join(state, 'tls-cert.pem'),
  ]);
  const { chmod } = await import('node:fs/promises');
  for (const f of ['tls-key.pem', 'tls-cert.pem']) await chmod(join(state, f), 0o600);
  const certificatePem = await readFile(join(state, 'tls-cert.pem'), 'utf8'),
    certificateSha256 = hash(new X509Certificate(certificatePem).raw);
  const keys = generateKeyPairSync('ed25519', {
    publicKeyEncoding: { type: 'spki', format: 'pem' },
    privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
  });
  const publicRegistry = signedRegistration(
    {
      version: 1,
      registryVersion: 1,
      deploymentId,
      serverInstanceId,
      primaryHostId,
      name,
      domain,
      endpoints: [endpoint],
      certificateSha256,
      certificatePem,
      contractVersion: '1',
    },
    keys.publicKey,
    keys.privateKey,
  );
  const registryKeySha256 = keyFingerprint(keys.publicKey);
  verifyRegistration(publicRegistry, registryKeySha256);
  const databasePassword = randomBytes(32).toString('hex'),
    adminPassword = randomBytes(32).toString('hex');
  await save(join(state, 'registry-key.pem'), keys.privateKey);
  await save(join(state, 'trust.json'), { registryKeySha256 });
  await save(join(state, 'env.json'), {
    DATABASE_URL: 'postgresql://taskhub:' + databasePassword + '@127.0.0.1:' + pgPort + '/taskhub',
    PG_ADMIN_PASSWORD: adminPassword,
    KEY_HMAC_SECRET: randomBytes(32).toString('hex'),
    SESSION_SECRET: randomBytes(32).toString('hex'),
    UPLOAD_DIR: join(state, 'uploads'),
    BOOTSTRAP_PASSWORD: randomBytes(24).toString('base64url'),
    WEB_ORIGIN: endpoint,
    API_PORT: String(apiPort),
    TASK_HUB_DEPLOYMENT_ID: deploymentId,
    TASK_HUB_INSTANCE_ID: serverInstanceId,
    TASK_HUB_PRIMARY_HOST_ID: primaryHostId,
    TASK_HUB_SERVICE_NAME: name,
    HOST_CONNECTION_STATE_DIR: join(state, 'host-connections'),
    HOST_BACKGROUND_JOBS: options['disable-worker'] ? '0' : '1',
    ...(notification ? { [businessConfigEnv]: notification.path } : {}),
  });
  await save(join(state, 'pg-password'), adminPassword + '\n');
  await privateDir(join(state, 'uploads'));
  await privateDir(join(state, 'host-connections'));
  await save(join(state, 'deployment.json'), publicRegistry, 0o644);
  const pairing = {
    version: 1,
    type: 'agenthub-task',
    deploymentId,
    serverInstanceId,
    primaryHostId,
    name,
    endpoint,
    canonicalOrigin: endpoint,
    fingerprint: certificateSha256,
    spkiSha256Base64: createHash('sha256')
      .update(new X509Certificate(certificatePem).publicKey.export({ type: 'spki', format: 'der' }))
      .digest('base64'),
    registryKeySha256,
  };
  await save(join(state, 'pairing.json'), pairing, 0o644);
  const QR = networkRequire(release)('qrcode');
  await QR.toFile(join(state, 'pairing.png'), JSON.stringify(pairing));
  await chmod(join(state, 'pairing.png'), 0o644);
  await save(join(state, 'runtime.json'), {
    role: 'primary',
    deploymentId,
    serverInstanceId,
    primaryHostId,
    hostRuntime,
    bundle: release,
    payloadSha256: manifest.payloadSha256,
    tlsPort,
    apiPort,
    pgPort,
    bind,
    node: process.execPath,
    path: process.env.PATH,
    autostart: !options['no-autostart'],
    ...(notification ? { businessNotificationsConfigPath: notification.path } : {}),
    pg: {
      ...pgTools,
      data: join(state, 'postgres'),
      // macOS per-user TMPDIR can exceed PostgreSQL's Unix socket path limit.
      socket: join(await realpath('/tmp'), 'agenthub-task-pg-' + hash(state).slice(0, 16)),
    },
  });
  await writeBusinessConfiguration(state, notification, {
    deploymentId,
    serverInstanceId,
    primaryHostId,
  });
  return { action: 'installed', state, autostart: 'configured-on-start', ...(await status(state)) };
}
async function executor(options, state) {
  if (!options.registry || !options['trust-key'])
    throw Error(
      'Executor requires public --registry and separately verified --trust-key SHA256; Bonjour cannot grant trust',
    );
  const document = await json(resolve(options.registry)),
    known = verifyRegistration(document, options['trust-key']);
  const previous = await local(state);
  if (previous?.role === 'primary') throw Error('A primary cannot be replaced with an executor');
  if (previous && !sameIdentity(await registration(state), known))
    throw Error(
      'Executor is already bound to another deployment; do not silently switch databases',
    );
  if (options.bundle) await preflight({ ...options, action: 'executor' });
  const verified = await probe(known);
  if (!verified.backendReady)
    throw Error('Designated primary database is not ready; keep existing binding');
  await save(join(state, 'deployment.json'), document, 0o644);
  await save(join(state, 'trust.json'), { registryKeySha256: options['trust-key'] });
  await save(join(state, 'runtime.json'), {
    role: 'executor',
    ...(options.bundle ? { bundle: resolve(options.bundle) } : {}),
    ...Object.fromEntries(identityKeys.map((k) => [k, known[k]])),
  });
  return {
    action: 'executor-bound',
    ...verified,
    endpoint: known.endpoints[0],
    identity: Object.fromEntries(identityKeys.map((k) => [k, known[k]])),
    databaseCreated: false,
    hostChanged: false,
    businessAcceptance: 'not-verified',
  };
}
async function waitFor(predicate, message) {
  const deadline = Date.now() + 45000;
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await new Promise((res) => setTimeout(res, 150));
  }
  throw Error(message);
}
async function start(options, state, activationId) {
  requireMac();
  await assertStartAllowed(state, { activationId });
  const config = await local(state);
  if (config?.role !== 'primary') throw Error('Only the installed designated primary can start');
  assertNode24(config.node);
  const before = await status(state);
  if (before.processRunning) {
    if (!before.backendReady)
      throw Error(
        'Existing Task Hub is starting or unhealthy; preserve instance and inspect status',
      );
    return { action: 'already-running', ...before };
  }
  await registration(state);
  const manifest = await verifyBundle(config.bundle);
  if (await businessMaterial(state, { allowMissingKey: true })) assertBusinessBundle(manifest);
  const entry = join(config.bundle, 'tools/task-hub-runtime.mjs');
  if (!options['no-autostart'] && config.autostart) {
    const launch = launchConfig(state);
    await mkdir(dirname(launch.plist), { recursive: true });
    const plist =
      '<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict><key>Label</key><string>' +
      xml(launch.label) +
      '</string><key>ProgramArguments</key><array>' +
      [config.node, entry, '--state', state]
        .map((s) => '<string>' + xml(s) + '</string>')
        .join('') +
      '</array><key>RunAtLoad</key><true/><key>KeepAlive</key><true/><key>ThrottleInterval</key><integer>10</integer><key>StandardOutPath</key><string>' +
      xml(join(state, 'service.log')) +
      '</string><key>StandardErrorPath</key><string>' +
      xml(join(state, 'service.log')) +
      '</string></dict></plist>';
    await writeFile(launch.plist, plist, { mode: 0o600 });
    if (launchInstalled(launch.target)) run('/bin/launchctl', ['bootout', launch.target]);
    run('/bin/launchctl', ['enable', launch.target]);
    run('/bin/launchctl', ['bootstrap', launch.domain, launch.plist]);
  } else {
    const log = await open(join(state, 'service.log'), 'a', 0o600);
    const child = spawn(config.node, [entry, '--state', state], {
      detached: true,
      env: { PATH: config.path, HOME: homedir() },
      stdio: ['ignore', log.fd, log.fd],
    });
    child.on('error', () => {});
    child.unref();
    await log.close();
  }
  await waitFor(
    async () => (await status(state)).runtimeHealthy,
    'Task Hub failed to become ready; inspect private service.log',
  );
  return { action: 'started', ...(await status(state)) };
}
function ownedProcess(pid, entry, state) {
  if (!alive(pid)) return false;
  const command = run('/bin/ps', ['-p', String(pid), '-o', 'command=']).trim();
  if (!command.includes(entry) || (state && !command.endsWith('--state ' + state)))
    throw Error('Process ownership cannot be proved; refusing to signal PID');
  return true;
}
async function stop(state) {
  requireMac();
  const config = await local(state);
  if (config?.role !== 'primary') return { action: 'no-primary-to-stop' };
  const launch = launchConfig(state);
  if (await exists(launch.plist)) {
    await privateFile(launch.plist);
    await rm(launch.plist);
  }
  if (launchInstalled(launch.target)) run('/bin/launchctl', ['bootout', launch.target]);
  const lockPath = join(state, 'instance.lock');
  if (await exists(join(lockPath, 'owner.json'))) {
    const lock = await json(join(lockPath, 'owner.json'), true);
    if (ownedProcess(lock.pid, join(config.bundle, 'tools/task-hub-runtime.mjs'), state)) {
      process.kill(lock.pid, 'SIGTERM');
      await waitFor(() => !alive(lock.pid), 'Task Hub did not stop; state and lock preserved');
    }
  }
  // Recover only children named in this private installation's own journal.
  if (await exists(join(state, 'resources.json'))) {
    const child = await json(join(state, 'resources.json'), true);
    if (child.entry !== join(config.bundle, 'runtime/dist/main.js'))
      throw Error('Orphan journal entry mismatch');
    if (ownedProcess(child.apiPid, child.entry)) {
      process.kill(child.apiPid, 'SIGTERM');
      await waitFor(() => !alive(child.apiPid), 'Orphan API did not stop');
    }
    await rm(join(state, 'resources.json'));
  }
  if (await exists(join(config.pg.data, 'postmaster.pid'))) {
    const pidfile = (await readFile(join(config.pg.data, 'postmaster.pid'), 'utf8')).split('\n');
    if (pidfile[1] !== config.pg.data || !ownedProcess(Number(pidfile[0]), '-D ' + config.pg.data))
      throw Error('PostgreSQL ownership cannot be proved; stale or foreign pidfile preserved');
    run(config.pg.pg_ctl, ['-D', config.pg.data, 'stop', '-m', 'fast', '-w']);
  }
  if (await exists(lockPath)) {
    const lock = await json(join(lockPath, 'owner.json'), true);
    if (alive(lock.pid)) throw Error('Live instance lock retained');
    await rm(lockPath, { recursive: true });
  }
  await rm(join(state, 'ready.json'), { force: true });
  return { action: 'stopped', ...(await status(state)) };
}
async function stoppedPrimary(state, { allowFence = false } = {}) {
  const config = await local(state);
  if (config?.role !== 'primary' || config.pg.data !== join(state, 'postgres'))
    throw Error('Backup/restore requires the original dedicated primary installation');
  if (await exists(join(state, 'offline-only.json')))
    throw Error('An offline verification copy can never become a primary');
  if (!allowFence) await assertStartAllowed(state);
  const known = await registration(state);
  if (
    !sameIdentity(config, known) ||
    (await json(config.hostRuntime)).hostId !== known.primaryHostId
  )
    throw Error('Original primary Host or service identity changed; no promotion is allowed');
  const launch = await autostartState(state, config);
  if (launch.configured || launch.loaded)
    throw Error(
      'Explicit stop and removal of this primary LaunchAgent are required before backup/restore',
    );
  for (const [file, key] of [
    ['instance.lock/owner.json', 'pid'],
    ['resources.json', 'apiPid'],
  ])
    if ((await exists(join(state, file))) && alive((await json(join(state, file), true))[key]))
      throw Error('Runtime/API is still active; backup/restore never stops it implicitly');
  if (await exists(join(config.pg.data, 'postmaster.pid')))
    throw Error('PostgreSQL must be explicitly stopped; postmaster pidfile is present');
  for (const [p, h] of [
    [config.pgPort, '127.0.0.1'],
    [config.apiPort, '127.0.0.1'],
    [config.tlsPort, config.bind],
  ])
    await availablePort(p, h);
  const env = await json(join(state, 'env.json'), true);
  const database = new URL(env.DATABASE_URL);
  if (
    env.UPLOAD_DIR !== join(state, 'uploads') ||
    env.HOST_CONNECTION_STATE_DIR !== join(state, 'host-connections') ||
    database.hostname !== '127.0.0.1' ||
    database.pathname !== '/taskhub' ||
    Number(database.port) !== config.pgPort ||
    !sameIdentity(
      {
        deploymentId: env.TASK_HUB_DEPLOYMENT_ID,
        serverInstanceId: env.TASK_HUB_INSTANCE_ID,
        primaryHostId: env.TASK_HUB_PRIMARY_HOST_ID,
      },
      known,
    )
  )
    throw Error('Private service state escaped the original primary installation');
  const tools = pgOfflineTools(config);
  cleanCluster(config.pg.data, tools);
  return { config, known, tools };
}
async function snapshotFiles(source, destination) {
  await privateDir(destination);
  for (const file of backupConfigFiles) {
    if (['deployment.json', 'pairing.json', 'pairing.png'].includes(file)) {
      const info = await lstat(join(source, file));
      if (
        !info.isFile() ||
        info.isSymbolicLink() ||
        (process.getuid && info.uid !== process.getuid())
      )
        throw Error('Public service registration or pairing file is invalid');
    } else await privateFile(join(source, file));
    await cp(join(source, file), join(destination, file));
    await chmod(join(destination, file), 0o600);
  }
  for (const dir of ['postgres', 'uploads', 'host-connections']) {
    // Inventory before copying rejects tablespaces, external WAL and links.
    await privateTree(join(source, dir), { protect: false });
    await cp(join(source, dir), join(destination, dir), {
      recursive: true,
      verbatimSymlinks: true,
    });
  }
  for (const file of optionalBackupConfigFiles)
    if (await exists(join(source, file))) {
      await privateFile(join(source, file));
      await cp(join(source, file), join(destination, file));
    }
  const notification = await businessMaterial(source);
  if (notification) {
    const target = join(destination, 'business-notifications');
    await privateDir(target);
    const config = await local(source);
    await cp(config.businessNotificationsConfigPath, join(target, 'config.json'));
    await cp(notification.config.keyDirectory, join(target, 'keys'), {
      recursive: true,
      verbatimSymlinks: true,
    });
  }
}
async function backup(options, state) {
  if (!options.output) throw Error('Backup requires an immutable --output archive');
  const output = resolve(options.output),
    keyFile = options['key-file'] ? resolve(options['key-file']) : null;
  if (await exists(output))
    throw Error('Backup output already exists; preserve immutable archives');
  if (!keyFile || below(state, keyFile) || below(dirname(output), keyFile))
    throw Error('Keep the backup key outside the service state and backup output directory');
  const original = await stoppedPrimary(state);
  const notification = await businessMaterial(state);
  if (
    notification &&
    (below(notification.config.keyDirectory, output) ||
      below(notification.config.keyDirectory, keyFile))
  )
    throw Error('Backup archive/key must be outside the business publisher key directory');
  const manifest = await verifyBundle(original.config.bundle);
  if (manifest.payloadSha256 !== original.config.payloadSha256)
    throw Error('Installed payload fingerprint changed');
  const key = await backupKey(keyFile, options['create-key']);
  const instance = await acquireLock(join(state, 'instance.lock'), { action: 'backup' });
  const staging = join(dirname(output), '.backup-staging-' + randomUUID());
  let finished = false;
  try {
    await save(join(state, 'maintenance.json'), {
      version: 1,
      action: 'backup',
      operationId: instance.owner.nonce,
    });
    const before = {};
    for (const dir of ['postgres', 'uploads', 'host-connections'])
      before[dir] = await privateTree(join(state, dir), { protect: false });
    for (const file of backupConfigFiles) before[file] = await shaFile(join(state, file));
    before.businessNotifications = businessDescriptor(
      await businessMaterial(state),
      original.config.businessNotificationsConfigPath,
    );
    for (const file of optionalBackupConfigFiles)
      before[file] = (await exists(join(state, file))) ? await shaFile(join(state, file)) : null;
    await snapshotFiles(state, staging);
    if (
      JSON.stringify(before.businessNotifications) !==
      JSON.stringify(
        businessDescriptor(
          await businessMaterial(staging, { staged: true }),
          original.config.businessNotificationsConfigPath,
        ),
      )
    )
      throw Error('Copied business notification configuration/key differs from immutable source');
    for (const file of optionalBackupConfigFiles)
      if (
        before[file] !==
        ((await exists(join(staging, file))) ? await shaFile(join(staging, file)) : null)
      )
        throw Error('Copied business key binding differs from immutable source');
    const migrations = await migrationInventory(original.config.bundle);
    await privateDir(join(staging, 'sql-evidence/migrations'));
    for (const m of migrations)
      await cp(
        join(original.config.bundle, 'runtime/migrations', m.name),
        join(staging, 'sql-evidence/migrations', m.name),
      );
    const database = await auditOfflineDatabase(
      staging,
      original.config,
      original.tools,
      migrations,
    );
    for (const dir of ['postgres', 'uploads', 'host-connections'])
      if (
        JSON.stringify(before[dir]) !==
        JSON.stringify(await privateTree(join(state, dir), { protect: false }))
      )
        throw Error('Source bytes changed during cold backup; archive refused');
    for (const file of backupConfigFiles)
      if (before[file] !== (await shaFile(join(state, file))))
        throw Error('Source private configuration changed during backup');
    if (
      JSON.stringify(before.businessNotifications) !==
      JSON.stringify(
        businessDescriptor(
          await businessMaterial(state),
          original.config.businessNotificationsConfigPath,
        ),
      )
    )
      throw Error('Source business notification configuration/key changed during backup');
    for (const file of optionalBackupConfigFiles)
      if (
        before[file] !==
        ((await exists(join(state, file))) ? await shaFile(join(state, file)) : null)
      )
        throw Error('Source business key binding changed during backup');
    cleanCluster(original.config.pg.data, original.tools);
    const inventory = await privateTree(staging);
    const record = {
      format: 1,
      kind: 'taskhub-cold-backup',
      backupId: randomUUID(),
      createdAt: new Date().toISOString(),
      identity: Object.fromEntries(identityKeys.map((k) => [k, original.known[k]])),
      sourceStateSha256: hash(state),
      payloadSha256: manifest.payloadSha256,
      bundleManifestSha256: await shaFile(join(original.config.bundle, 'manifest.json')),
      sourceSha256: manifest.sourceSha256,
      platform: process.platform,
      architecture: process.arch,
      pgMajor: original.config.pg.major,
      consistency:
        'explicit-stop; launchagent-unloaded; clean-cluster; immutable-source; single-user-offline-sql',
      migrations,
      database,
      businessNotifications: before.businessNotifications,
      ...inventory,
      executionHostStateIncluded: false,
    };
    const sealed = await sealBackup(staging, record, output, key);
    await save(join(state, 'last-backup.json'), {
      backupId: record.backupId,
      createdAt: record.createdAt,
      ...sealed,
    });
    finished = true;
    return {
      action: 'backed-up',
      backupId: record.backupId,
      ...sealed,
      databaseTables: Object.keys(database.counts).length,
      attachmentFiles: Object.keys(database.attachmentFiles).length,
      serviceStarted: false,
      executionHostStateIncluded: false,
    };
  } finally {
    key.fill(0);
    await rm(staging, { recursive: true, force: true });
    if (finished) await rm(join(state, 'maintenance.json'), { force: true });
    await instance.release();
  }
}
async function verifyRestoredTree(root, record, options) {
  if (record.platform !== process.platform || record.architecture !== process.arch)
    throw Error('Cold restore requires the original OS, architecture and PostgreSQL major');
  const config = await json(join(root, 'runtime.json'), true);
  const known = verifyRegistration(
    await json(join(root, 'deployment.json')),
    (await json(join(root, 'trust.json'), true)).registryKeySha256,
  );
  if (
    !sameIdentity(record.identity, known) ||
    !sameIdentity(config, known) ||
    record.payloadSha256 !== config.payloadSha256 ||
    record.pgMajor !== config.pg.major
  )
    throw Error('Backup service identity or original payload is inconsistent');
  const notification = await businessMaterial(root, { staged: true });
  if (
    JSON.stringify(businessDescriptor(notification, config.businessNotificationsConfigPath)) !==
    JSON.stringify(record.businessNotifications ?? null)
  )
    throw Error('Backup business notification configuration/key manifest differs');
  if (options.bundle) {
    const bundle = await verifyBundle(resolve(options.bundle));
    if (bundle.payloadSha256 !== record.payloadSha256)
      throw Error('Restore bundle is not the original frozen payload');
    if (
      JSON.stringify(await migrationInventory(resolve(options.bundle))) !==
      JSON.stringify(record.migrations)
    )
      throw Error('Original SQL migrations differ from backup');
  }
  for (const migration of record.migrations)
    if (
      !/^\d{4}_[a-z_]+\.sql$/.test(migration.name) ||
      (await shaFile(join(root, 'sql-evidence/migrations', migration.name))) !== migration.checksum
    )
      throw Error('Backup original migration bytes are inconsistent');
  const tools = pgOfflineTools(config);
  const database = await auditOfflineDatabase(root, config, tools, record.migrations);
  for (const name of [
    'counts',
    'digests',
    'applied',
    'phases',
    'attachmentFiles',
    'schemaSha256',
    'foreignKeysSha256',
  ])
    if (JSON.stringify(database[name]) !== JSON.stringify(record.database[name]))
      throw Error(
        'Restored SQL ledger, unknown receipts or attachment manifest differ from backup',
      );
  return { config, known, database };
}
async function materialFingerprint(root, { staged = false } = {}) {
  const files = {};
  for (const name of backupConfigFiles) files[name] = await shaFile(join(root, name));
  for (const directory of ['postgres', 'uploads', 'host-connections'])
    files[directory] = await privateTree(join(root, directory), { protect: false });
  for (const name of optionalBackupConfigFiles)
    files[name] = (await exists(join(root, name))) ? await shaFile(join(root, name)) : null;
  const config = await local(root);
  files.businessNotifications = businessDescriptor(
    await businessMaterial(root, { staged }),
    config.businessNotificationsConfigPath,
  );
  return hash(JSON.stringify(files));
}
async function assertBusinessRestorePaths(state, original, record) {
  const path = original.config.businessNotificationsConfigPath;
  const archived = record.businessNotifications ?? null;
  if ((path === undefined) !== (archived === null) || (archived && path !== archived.configPath))
    throw Error('Restore cannot change original business configuration paths');
  if (!archived) return;
  const binding = await json(join(state, 'business-key-binding.json'), true);
  if (
    binding.version !== 1 ||
    !sameIdentity(binding, original.known) ||
    binding.configPath !== path ||
    binding.keyDirectory !== archived.keyDirectory
  )
    throw Error('Restore business key path differs from original private binding');
  for (const dir of [dirname(path), dirname(archived.keyDirectory)]) {
    const st = await lstat(dir);
    if (
      !st.isDirectory() ||
      (await realpath(dir)) !== dir ||
      st.uid !== process.getuid() ||
      (st.mode & 0o7777) !== 0o700
    )
      throw Error('Original business private parent rejected');
  }
}
async function restoreBusinessMaterials(state, staging, record, rollback, journal) {
  const descriptor = record.businessNotifications;
  if (!descriptor) return journal;
  const { configPath, keyDirectory } = descriptor;
  const oldDirectory = join(dirname(keyDirectory), '.business-old-' + journal.restoreId);
  const pendingDirectory = join(dirname(keyDirectory), '.business-restore-' + journal.restoreId);
  if ((await exists(oldDirectory)) || (await exists(pendingDirectory)))
    throw Error('External business restore staging conflicts');
  await privateDir(join(rollback, 'business-notifications'));
  if (await exists(configPath)) {
    await privateFile(configPath);
    if ((await lstat(configPath)).nlink !== 1)
      throw Error('Original business config hardlink rejected');
    await cp(configPath, join(rollback, 'business-notifications/config.json'));
  }
  if (await exists(keyDirectory)) await privateTree(keyDirectory, { protect: false });
  await cp(join(staging, 'business-notifications/keys'), pendingDirectory, {
    recursive: true,
    verbatimSymlinks: true,
  });
  await syncDirectory(pendingDirectory);
  journal = {
    ...journal,
    phase: 'replacing-business',
    externalRollback: { configPath, keyDirectory, oldDirectory, pendingDirectory },
  };
  await save(join(state, 'restore-journal.json'), journal);
  if (await exists(keyDirectory)) await rename(keyDirectory, oldDirectory);
  await rename(pendingDirectory, keyDirectory);
  await syncDirectory(dirname(keyDirectory));
  await save(
    configPath,
    await readFile(join(staging, 'business-notifications/config.json'), 'utf8'),
  );
  await businessMaterial(state);
  return journal;
}
async function restore(options, state) {
  if (!options.archive || !options['key-file'] || !!options.offline === !!options['in-place'])
    throw Error('Restore requires --archive, --key-file and exactly one of --offline / --in-place');
  const keyFile = resolve(options['key-file']);
  const key = await backupKey(keyFile);
  const destination = options.offline && options.destination ? resolve(options.destination) : null;
  if (options.offline && (!destination || below(state, destination) || below(destination, keyFile)))
    throw Error('Offline restore needs a new --destination outside service state and key storage');
  if (options.offline && (await exists(join(state, 'business-key-binding.json')))) {
    const binding = await json(join(state, 'business-key-binding.json'), true);
    for (const path of [binding.configPath, binding.keyDirectory])
      if (typeof path !== 'string' || below(destination, path) || below(path, destination))
        throw Error('Offline restore destination overlaps original business credentials');
  }
  let original, instance;
  const restoreId = randomUUID();
  const archivePath = resolve(options.archive),
    archiveSha256 = await shaFile(archivePath);
  const staging = options.offline ? destination : join(state, '.restore-staging-' + randomUUID());
  try {
    if (options['in-place']) {
      original = await stoppedPrimary(state);
      instance = await acquireLock(join(state, 'instance.lock'), { action: 'restore' });
      // Durable eligibility fence precedes any decryption or replacement. Failure
      // or interruption never permits KeepAlive to activate a half-restored DB.
      await save(join(state, 'restore-fence.json'), {
        version: 1,
        operationId: instance.owner.nonce,
        restoreId,
        state: 'restoring',
        oldPrimaryEligibility: 'revoked-until-reconciliation',
      });
    }
    const record = await openBackup(resolve(options.archive), key, staging);
    if ((await shaFile(archivePath)) !== archiveSha256)
      throw Error('Restore archive changed during verification');
    const verified = await verifyRestoredTree(staging, record, options);
    if (options['in-place']) {
      if (
        record.sourceStateSha256 !== hash(state) ||
        !sameIdentity(original.known, record.identity) ||
        original.config.payloadSha256 !== record.payloadSha256 ||
        original.config.bundle !== verified.config.bundle
      )
        throw Error(
          'In-place restore is restricted to this original stopped primary and frozen payload',
        );
      await assertBusinessRestorePaths(state, original, record);
      const rollback = join(state, 'recovery', restoreId);
      await privateDir(rollback);
      const files = [
        ...backupConfigFiles,
        ...optionalBackupConfigFiles.filter((file) => record.files[file]),
        'postgres',
        'uploads',
        'host-connections',
      ];
      let journal = {
        version: 1,
        operationId: instance.owner.nonce,
        restoreId,
        files,
        rollback,
        phase: 'replacing',
      };
      await save(join(state, 'restore-journal.json'), journal);
      for (const file of files) {
        if (await exists(join(state, file))) await rename(join(state, file), join(rollback, file));
        await rename(join(staging, file), join(state, file));
      }
      await restoreBusinessMaterials(state, staging, record, rollback, journal);
    }
    const report = {
      version: 2,
      kind: 'taskhub-restore-verification',
      source: 'native-single-user-cold-restore',
      restoreId,
      action: options.offline ? 'offline-restore-verified' : 'original-primary-restored-fenced',
      backupId: record.backupId,
      archiveSha256,
      manifestSha256: hash(JSON.stringify(record)),
      sourceStateSha256: hash(state),
      identity: record.identity,
      payloadSha256: record.payloadSha256,
      bundleManifestSha256: record.bundleManifestSha256 ?? null,
      materialSha256: await materialFingerprint(options.offline ? staging : state, {
        staged: !!options.offline,
      }),
      businessNotifications: record.businessNotifications ?? null,
      database: verified.database,
      sqlRestoreVerified: true,
      databaseTables: Object.keys(verified.database.counts).length,
      attachmentFiles: Object.keys(verified.database.attachmentFiles).length,
      operationPhases: verified.database.phases,
      serviceStarted: false,
      workerStarted: false,
      businessListenersOpened: false,
      executionHostStateIncluded: false,
      primaryEligibility: 'fenced',
    };
    if (options['in-place']) {
      const proofPath = join(state, 'recovery', restoreId, 'restore-verification.json');
      await saveImmutable(proofPath, report);
      const verificationSha256 = await shaFile(proofPath);
      await save(join(state, 'restore-fence.json'), {
        version: 2,
        restoreId,
        operationId: instance.owner.nonce,
        state: 'restored-offline-verified',
        backupId: record.backupId,
        archiveSha256,
        verificationSha256,
        oldPrimaryEligibility: 'revoked-until-explicit-activation',
        workerAllowed: false,
      });
      await save(join(state, 'restore-journal.json'), {
        ...(await json(join(state, 'restore-journal.json'), true)),
        version: 2,
        restoreId,
        operationId: instance.owner.nonce,
        archiveSha256,
        manifestSha256: report.manifestSha256,
        verificationSha256,
        files: [
          ...backupConfigFiles,
          ...optionalBackupConfigFiles.filter((file) => record.files[file]),
          'postgres',
          'uploads',
          'host-connections',
        ],
        rollback: join(state, 'recovery', restoreId),
        phase: 'completed',
        primaryEligibility: 'fenced',
      });
    }
    await save(join(options.offline ? staging : state, 'restore-report.json'), report);
    return report;
  } finally {
    key.fill(0);
    if (options['in-place']) await rm(staging, { recursive: true, force: true });
    if (instance) await instance.release();
  }
}
async function backupStatus(options) {
  if (!options.archive || !options['key-file'])
    throw Error('backup-status requires --archive and --key-file');
  const archive = resolve(options.archive),
    staging = join(dirname(archive), '.verify-' + randomUUID());
  const key = await backupKey(resolve(options['key-file']));
  try {
    const record = await openBackup(archive, key, staging);
    return {
      action: 'backup-authenticated',
      backupId: record.backupId,
      encrypted: 'AES-256-GCM',
      archiveSha256: await shaFile(archive),
      payloadSha256: record.payloadSha256,
      pgMajor: record.pgMajor,
      byteManifestVerified: true,
      sqlRestoreVerified: false,
      serviceStarted: false,
    };
  } finally {
    key.fill(0);
    await rm(staging, { recursive: true, force: true });
  }
}
// Hooks are only a module-level offline-test seam; CLI input cannot supply a
// lifecycle implementation. Production always uses the existing start/stop.
export async function activateRestoredPrimary(options, state, hooks = {}) {
  requireMac();
  assertNode24();
  const restoreId = options['restore-id'];
  if (!activationUUID(restoreId) || !options.archive || !options['key-file'])
    throw Error('restore-activate requires --restore-id, --archive and --key-file');
  if (options.offline || options['in-place'] || options.destination || options['create-key'])
    throw Error('restore-activate cannot create, move or promote a primary');
  if (
    (await exists(join(state, 'offline-only.json'))) ||
    (await exists(join(state, 'maintenance.json')))
  )
    throw Error('Offline, maintenance or pending activation fence conflicts with restore-activate');
  const proofPath = join(state, 'recovery', restoreId, 'restore-verification.json');
  const proof = await json(proofPath, true),
    verificationSha256 = await shaFile(proofPath);
  if (
    proof.version !== 2 ||
    proof.kind !== 'taskhub-restore-verification' ||
    proof.source !== 'native-single-user-cold-restore' ||
    proof.restoreId !== restoreId ||
    proof.action !== 'original-primary-restored-fenced' ||
    proof.sqlRestoreVerified !== true ||
    proof.sourceStateSha256 !== hash(state) ||
    !proof.bundleManifestSha256
  )
    throw Error('Restore is unverified, legacy, offline or belongs to another original state');
  const current = await local(state),
    known = await registration(state);
  if (
    !current ||
    !sameIdentity(current, proof.identity) ||
    !sameIdentity(known, proof.identity) ||
    (await json(current.hostRuntime)).hostId !== known.primaryHostId ||
    current.payloadSha256 !== proof.payloadSha256
  )
    throw Error('Restore activation cannot change the original Host or service identity');
  const manifest = await verifyBundle(current.bundle);
  if (
    (options.bundle && resolve(options.bundle) !== current.bundle) ||
    manifest.payloadSha256 !== proof.payloadSha256 ||
    (await shaFile(join(current.bundle, 'manifest.json'))) !== proof.bundleManifestSha256
  )
    throw Error('Original frozen bundle or full manifest changed before activation');
  if (
    !(await readFile(join(current.bundle, 'tools/task-hub-core.mjs'), 'utf8')).includes(
      'export const restoreActivationVersion = 2',
    ) ||
    !(await readFile(join(current.bundle, 'tools/task-hub-runtime.mjs'), 'utf8')).includes(
      'assertActivationRuntime',
    )
  )
    throw Error(
      'Frozen runtime lacks the verified restore activation guard; use a coherent approved candidate',
    );
  const archive = resolve(options.archive);
  if ((await shaFile(archive)) !== proof.archiveSha256)
    throw Error('Restore activation archive SHA differs from immutable verification');
  if (!(await exists(join(state, 'restore-fence.json')))) {
    const directory = join(state, 'recovery', restoreId, 'activations');
    for (const id of await readdir(directory)) {
      if (!activationUUID(id)) continue;
      const receipt = await readCommittedActivation(state, restoreId, id);
      if (
        receipt &&
        receipt.verificationSha256 === verificationSha256 &&
        receipt.archiveSha256 === proof.archiveSha256 &&
        receipt.sourceStateSha256 === hash(state)
      )
        return {
          action: 'activation-already-committed',
          restoreId,
          activationId: id,
          serviceStartedAgain: false,
        };
    }
    throw Error('Restore activation fence is absent without a committed receipt');
  }
  const originalFence = await json(join(state, 'restore-fence.json'), true);
  if (await exists(join(state, 'restore-activation.json')))
    throw Error('Pending activation fence conflicts with restore-activate');
  const fenceSha256 = await shaFile(join(state, 'restore-fence.json'));
  const journal = await json(join(state, 'restore-journal.json'), true);
  const journalSha256 = await shaFile(join(state, 'restore-journal.json'));
  if (
    originalFence.version !== 2 ||
    originalFence.state !== 'restored-offline-verified' ||
    originalFence.restoreId !== restoreId ||
    originalFence.archiveSha256 !== proof.archiveSha256 ||
    originalFence.verificationSha256 !== verificationSha256 ||
    journal.version !== 2 ||
    journal.phase !== 'completed' ||
    journal.restoreId !== restoreId ||
    journal.operationId !== originalFence.operationId ||
    journal.archiveSha256 !== proof.archiveSha256 ||
    journal.verificationSha256 !== verificationSha256 ||
    journal.manifestSha256 !== proof.manifestSha256 ||
    journal.rollback !== join(state, 'recovery', restoreId) ||
    (await shaFile(join(state, 'restore-report.json'))) !== verificationSha256
  )
    throw Error('Restore fence, completed journal and immutable verification report conflict');
  for (const file of ['resources.json', 'ready.json'])
    if (await exists(join(state, file)))
      throw Error('Old runtime/Worker journal remains unknown; explicitly reconcile this primary');
  if (await exists(launchConfig(state).plist))
    throw Error('A configured job remains; primary must be explicitly stopped');
  const original = await stoppedPrimary(state, { allowFence: true });
  const key = await backupKey(resolve(options['key-file']));
  const activationId = randomUUID(),
    archiveTree = join(state, '.activation-archive-' + activationId),
    materialTree = join(state, '.activation-material-' + activationId);
  let instance,
    grant,
    startedAttempt = false;
  const startPrimary = hooks.startPrimary ?? start,
    stopPrimary = hooks.stopPrimary ?? stop;
  const actual = startPrimary === start && stopPrimary === stop;
  const receiptDirectory = join(state, 'recovery', restoreId, 'activations', activationId);
  try {
    instance = await acquireLock(join(state, 'instance.lock'), {
      action: 'restore-activate-verification',
      restoreId,
    });
    const record = await openBackup(archive, key, archiveTree);
    if (
      hash(JSON.stringify(record)) !== proof.manifestSha256 ||
      record.sourceStateSha256 !== hash(state) ||
      !sameIdentity(record.identity, known) ||
      record.bundleManifestSha256 !== proof.bundleManifestSha256
    )
      throw Error('Authenticated archive manifest differs from the completed original restore');
    await verifyRestoredTree(archiveTree, record, { bundle: current.bundle });
    if ((await materialFingerprint(state)) !== proof.materialSha256)
      throw Error(
        'Restored PG, attachment or private configuration bytes changed since verification',
      );
    await snapshotFiles(state, materialTree);
    await privateDir(join(materialTree, 'sql-evidence'));
    await cp(
      join(archiveTree, 'sql-evidence/migrations'),
      join(materialTree, 'sql-evidence/migrations'),
      { recursive: true },
    );
    const verified = await verifyRestoredTree(materialTree, record, { bundle: current.bundle });
    if (verified.database.unresolvedWorkerRuns !== 0)
      throw Error(
        'Restored execution or lease remains unresolved; reconcile original Worker operations before activation',
      );
    if (
      (await materialFingerprint(state)) !== proof.materialSha256 ||
      (await shaFile(archive)) !== proof.archiveSha256 ||
      (await shaFile(join(state, 'restore-fence.json'))) !== fenceSha256 ||
      (await shaFile(join(state, 'restore-journal.json'))) !== journalSha256 ||
      (await shaFile(proofPath)) !== verificationSha256 ||
      (await json(current.hostRuntime)).hostId !== proof.identity.primaryHostId ||
      (await shaFile(join(current.bundle, 'manifest.json'))) !== proof.bundleManifestSha256
    )
      throw Error('Restore activation inputs changed during offline verification');
    cleanCluster(current.pg.data, original.tools);
    const check = {
      action: 'restore-activation-verified',
      restoreId,
      archiveSha256: proof.archiveSha256,
      manifestSha256: proof.manifestSha256,
      verificationSha256,
      serviceStarted: false,
      fenceRetained: true,
      unresolvedWorkerRuns: 0,
      workerEnabled: (await json(join(state, 'env.json'), true)).HOST_BACKGROUND_JOBS !== '0',
    };
    if (options['verify-only']) return check;
    const owner = await json(join(state, 'install.lock/owner.json'), true);
    if (owner.pid !== process.pid || owner.action !== 'restore-activate')
      throw Error('Explicit restore-activate installation lock is required');
    grant = {
      version: restoreActivationVersion,
      kind: restoreActivationKind,
      source: actual ? 'actual-normal-start' : 'synthetic-lifecycle-callback',
      action: 'restore-activation-intent',
      phase: 'prepared',
      restoreId,
      activationId,
      identity: proof.identity,
      payloadSha256: proof.payloadSha256,
      bundleManifestSha256: proof.bundleManifestSha256,
      manifestSha256: proof.manifestSha256,
      sourceStateSha256: hash(state),
      verificationSha256,
      archiveSha256: proof.archiveSha256,
      runtimeSha256: await shaFile(join(state, 'runtime.json')),
      installNonce: owner.nonce,
      pid: owner.pid,
      controllerSha256: processFingerprint(owner.pid),
    };
    await saveImmutable(join(receiptDirectory, 'intent.json'), grant);
    await save(join(state, 'restore-activation.json'), grant);
    await save(join(state, 'restore-fence.json'), {
      ...originalFence,
      state: 'activating',
      activationId,
      workerAllowed: check.workerEnabled,
    });
    await instance.release();
    instance = null;
    startedAttempt = true;
    const running = await startPrimary(options, state, activationId);
    if (startPrimary === start) {
      const ready = await json(join(state, 'ready.json'), true);
      if (ready.restoreActivationId !== activationId || ready.restoreId !== restoreId)
        throw Error('Healthy runtime belongs to a different restore activation');
    }
    if (
      running.runtimeHealthy !== true ||
      running.processOwnershipVerified !== true ||
      !sameIdentity(running, known) ||
      running.payloadSha256 !== proof.payloadSha256 ||
      (!options['no-autostart'] && current.autostart && running.loginAutostart !== true)
    )
      throw Error('Normal primary startup or default loaded autostart failed verification');
    if (actual) await assertStartAllowed(state, { activationId });
    const receipt = {
      ...grant,
      ...check,
      action: actual ? 'restore-activated' : 'restore-activation-auxiliary-verified',
      phase: actual ? 'activated' : 'auxiliary-verified',
      activationId,
      sourceStateSha256: hash(state),
      identity: proof.identity,
      payloadSha256: proof.payloadSha256,
      bundleManifestSha256: proof.bundleManifestSha256,
      serviceStarted: actual,
      fenceRetained: !actual,
      loginAutostart: running.loginAutostart,
      evidence:
        startPrimary === start && stopPrimary === stop
          ? 'actual-normal-start'
          : 'synthetic-lifecycle-callback',
    };
    // Keep the qualification fence until the complete receipt has been fsynced
    // and atomically published. Auxiliary callbacks never qualify a primary.
    await saveImmutable(join(receiptDirectory, 'committed.json'), receipt);
    if (actual) await removeDurable(join(state, 'restore-fence.json'));
    else await save(join(state, 'restore-fence.json'), originalFence);
    await removeDurable(join(state, 'restore-activation.json'));
    return receipt;
  } catch (error) {
    if (grant) {
      // The immutable restore proof is never rewritten. Fence dominates any
      // partially published success receipt, including I/O failure at commit.
      let failurePublished = false;
      await saveImmutable(join(receiptDirectory, 'failed.json'), {
        ...grant,
        action: 'restore-activation-failed',
        phase: 'failed',
        fenceRetained: true,
      }).then(
        () => {
          failurePublished = true;
        },
        () => {},
      );
      const hasFence = await exists(join(state, 'restore-fence.json'));
      const fence = await json(join(state, 'restore-fence.json'), true).catch(() => null);
      let fencePreserved = false;
      if (!hasFence || (fence?.restoreId === restoreId && fence.activationId === activationId))
        await save(join(state, 'restore-fence.json'), originalFence).then(
          () => {
            fencePreserved = true;
          },
          () => {},
        );
      else fencePreserved = !!fence;
      let stopped = !startedAttempt;
      if (startedAttempt) {
        try {
          await stopPrimary(state);
          stopped = true;
        } catch {
          /* Keep pending guard too. */
        }
      }
      await saveImmutable(join(receiptDirectory, 'cleanup.json'), {
        ...grant,
        action: 'restore-activation-cleanup',
        phase: 'cleanup',
        fenceRetained: true,
        cleanupProved: stopped,
      }).catch(() => {});
      if (
        stopped &&
        failurePublished &&
        fencePreserved &&
        (await exists(join(state, 'restore-activation.json')))
      ) {
        const pending = await json(join(state, 'restore-activation.json'), true);
        if (pending.activationId === activationId && pending.restoreId === restoreId)
          await removeDurable(join(state, 'restore-activation.json'));
      }
    }
    throw error;
  } finally {
    key.fill(0);
    await rm(archiveTree, { recursive: true, force: true });
    await rm(materialTree, { recursive: true, force: true });
    if (instance) await instance.release();
  }
}
export async function execute(options) {
  if (
    options['business-notifications-config'] !== undefined &&
    !['install', 'upgrade'].includes(options.action)
  )
    throw Error('Business notification private path is explicit only for install/upgrade');
  if (
    ![
      'install',
      'upgrade',
      'start',
      'stop',
      'status',
      'preflight',
      'executor',
      'trust-certificate',
      'backup',
      'backup-status',
      'restore',
      'restore-activate',
    ].includes(options.action)
  )
    throw Error(
      'Use install/upgrade/start/stop/status/preflight/executor/trust-certificate/backup/backup-status/restore/restore-activate',
    );
  const state = resolve(options.state ?? defaultState());
  if (['backup', 'restore', 'restore-activate'].includes(options.action) && !options.state)
    throw Error('Backup/restore requires an explicit --state; never infer a live primary');
  if (options.action === 'backup-status') return backupStatus(options);
  if (options.action === 'restore' && options.offline) return restore(options, state);
  if (options.action === 'preflight') return preflight(options);
  if (options.action === 'status') return status(state);
  await privateDir(state);
  const lock = await acquireLock(join(state, 'install.lock'), { action: options.action });
  try {
    if (options.action === 'install' || options.action === 'upgrade')
      return await install(options, state);
    if (options.action === 'executor') return await executor(options, state);
    if (options.action === 'start') return await start(options, state);
    if (options.action === 'stop') return await stop(state);
    if (options.action === 'backup') return await backup(options, state);
    if (options.action === 'restore') return await restore(options, state);
    if (options.action === 'restore-activate') return await activateRestoredPrimary(options, state);
    requireMac();
    const known = await registration(state);
    run('/usr/bin/security', [
      'add-trusted-cert',
      '-r',
      'trustRoot',
      '-k',
      join(homedir(), 'Library/Keychains/login.keychain-db'),
      join(state, 'tls-cert.pem'),
    ]);
    return {
      action: 'current-user-certificate-trusted',
      certificateSha256: known.certificateSha256,
      systemPromptMayRequireUser: true,
    };
  } finally {
    await lock.release();
  }
}
export async function cli(args = process.argv.slice(2)) {
  try {
    console.log(JSON.stringify(await execute(argumentsFor([...args])), null, 2));
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href)
  await cli();
