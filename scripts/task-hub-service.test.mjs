import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, randomBytes, randomUUID, X509Certificate } from 'node:crypto';
import {
  chmod,
  link,
  mkdtemp,
  mkdir,
  readFile,
  realpath,
  rm,
  stat,
  symlink,
  writeFile,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from 'node:http';
import { request } from 'node:https';
import { EventEmitter } from 'node:events';
import {
  acquireLock,
  assertStartAllowed,
  assertActivationRuntime,
  assertNode24,
  businessMaterial,
  businessConfigEnv,
  businessKeyName,
  readBusinessNotifications,
  readCommittedActivation,
  restoreActivationKind,
  restoreActivationVersion,
  backupKey,
  hash,
  keyFingerprint,
  save,
  saveImmutable,
  shaFile,
  sealBackup,
  openBackup,
  privateTree,
  parseNativeService,
  processFingerprint,
  signedRegistration,
  tlsJson,
  verifyRegistration,
} from '../skills/codex-host-connect/scripts/task-hub-core.mjs';
import {
  gateway,
  publishDiscovery,
  nativeDiscoveryArguments,
  discoveryServiceName,
} from '../skills/codex-host-connect/scripts/task-hub-runtime.mjs';
import {
  argumentsFor,
  execute,
  launchJobState,
  prepareBusinessConfiguration,
  writeBusinessConfiguration,
} from '../skills/codex-host-connect/scripts/task-hub-service.mjs';
import { execFileSync } from 'node:child_process';

async function certificateFixture(root, domain) {
  execFileSync(
    '/usr/bin/openssl',
    [
      'req',
      '-x509',
      '-newkey',
      'rsa:2048',
      '-nodes',
      '-days',
      '2',
      '-subj',
      '/CN=' + domain,
      '-addext',
      'subjectAltName=DNS:' + domain,
      '-keyout',
      join(root, 'certificate-key.pem'),
      '-out',
      join(root, 'certificate.pem'),
    ],
    { stdio: 'ignore' },
  );
  return readFile(join(root, 'certificate.pem'), 'utf8');
}
test('native DNS-SD resolves escaped public metadata as discovery hints; invalid or conflicting identities never qualify', () => {
  const output =
    ' 7:00:00.100 Task\\032Hub._agenthub-task._tcp.local. can be reached at task-test.local.:19543 (interface 16)\n version=1 deploymentId=test-deployment serverInstanceId=test-instance primaryHostId=test-host name=Task\\ Hub contractVersion=1 path=/api/v1/service\n';
  assert.deepEqual(parseNativeService(output), {
    deploymentId: 'test-deployment',
    serverInstanceId: 'test-instance',
    primaryHostId: 'test-host',
    name: 'Task Hub',
    endpoint: 'https://task-test.local:19543',
    contractVersion: '1',
  });
  assert.equal(parseNativeService(output.replace('test-instance', 'invalid/identity')), null);
  assert.equal(parseNativeService(output.replace('name=Task', 'version=2 name=Task')), null);
  assert.equal(parseNativeService(output.replace('task-test.local.', 'untrusted.example.')), null);
  assert.equal(parseNativeService(output.replace(':19543', ':65536')), null);
  assert.equal(parseNativeService(output.replace('Task\\ Hub', '电脑😀')).name, '电脑😀');
  const name = discoveryServiceName({ name: '电脑'.repeat(40), serverInstanceId: 'instance-one' });
  assert.ok(Buffer.byteLength(name) <= 63);
  assert.notEqual(
    name,
    discoveryServiceName({ name: '电脑'.repeat(40), serverInstanceId: 'instance-two' }),
  );
});
test('native publication is restricted to public Task Hub or explicit QA metadata and fixed local DNS-SD arguments', () => {
  const descriptor = {
    name: 'Task Hub',
    type: 'agenthub-task',
    host: 'task-test.local',
    port: 19543,
    txt: {
      version: '1',
      deploymentId: 'test-deployment',
      serverInstanceId: 'test-instance',
      primaryHostId: 'test-host',
      name: 'Task Hub',
      contractVersion: '1',
      path: '/api/v1/service',
    },
  };
  assert.deepEqual(nativeDiscoveryArguments(descriptor, '192.0.2.5').slice(0, 7), [
    '-P',
    'Task Hub',
    '_agenthub-task._tcp',
    'local.',
    '19543',
    'task-test.local',
    '192.0.2.5',
  ]);
  for (const bad of [
    { ...descriptor, host: 'outside.invalid' },
    { ...descriptor, name: 'line\nbreak' },
    { ...descriptor, txt: { ...descriptor.txt, publisherToken: 'secret' } },
    { ...descriptor, txt: { ...descriptor.txt, primaryHostId: 'wrong/identity' } },
  ])
    assert.throws(() => nativeDiscoveryArguments(bad, '192.0.2.5'));
  assert.throws(() => nativeDiscoveryArguments(descriptor, '127.0.0.1'));
  assert.throws(() =>
    nativeDiscoveryArguments({ ...descriptor, type: 'agenthub-mdns-qa' }, '192.0.2.5'),
  );
});
test('mDNS datagram failure is contained and reported unavailable, without qualifying discovery or throwing into primary execution', () => {
  let callback;
  const emitter = new EventEmitter(),
    states = [];
  class BonjourFixture {
    constructor(_options, errorCallback) {
      callback = errorCallback;
    }
    publish() {
      return emitter;
    }
  }
  const publication = publishDiscovery(BonjourFixture, { name: 'Fixture' }, (state, errorCode) =>
    states.push({ state, errorCode }),
  );
  assert.equal(publication.service, emitter);
  emitter.emit('up');
  assert.equal(states.at(-1).state, 'publication-announced; discovery checked separately');
  assert.doesNotThrow(() =>
    callback(Object.assign(new Error('private network detail'), { code: 'EHOSTUNREACH' })),
  );
  assert.deepEqual(states.at(-1), { state: 'unavailable', errorCode: 'EHOSTUNREACH' });
  emitter.emit('up');
  assert.equal(states.at(-1).state, 'unavailable');
  assert.doesNotThrow(() => emitter.emit('error', new Error('private network detail')));
  assert.deepEqual(states.at(-1), { state: 'unavailable', errorCode: 'MDNS_ERROR' });
});

test('immutable publication never exposes file-sync failures, removes failed parent-sync publication and refuses replacement', async () => {
  const root = await mkdtemp(join(await realpath(tmpdir()), 'task-hub-immutable-'));
  try {
    const path = join(root, 'committed.json');
    await save(join(root, 'restore-fence.json'), { state: 'restored-offline-verified' });
    const ioError = Object.assign(new Error('injected fsync I/O failure'), { code: 'EIO' });
    await assert.rejects(
      saveImmutable(
        path,
        { phase: 'activated' },
        {
          syncFile: async () => {
            throw ioError;
          },
        },
      ),
      { code: 'EIO' },
    );
    await assert.rejects(readFile(path), { code: 'ENOENT' });
    await assert.rejects(
      saveImmutable(
        path,
        { phase: 'activated' },
        {
          syncParent: async () => {
            throw ioError;
          },
        },
      ),
      { code: 'EIO' },
    );
    await assert.rejects(readFile(path), { code: 'ENOENT' });
    const results = await Promise.allSettled([
      saveImmutable(path, { winner: 1 }),
      saveImmutable(path, { winner: 2 }),
    ]);
    assert.equal(results.filter((r) => r.status === 'fulfilled').length, 1);
    const original = await readFile(path);
    await assert.rejects(saveImmutable(path, { overwrite: true }), { code: 'EEXIST' });
    assert.deepEqual(await readFile(path), original);
    assert.equal((await stat(path)).nlink, 1);
    assert.ok(await readFile(join(root, 'restore-fence.json')));
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('private business config and X25519 key fail closed on identity, links, empty old directory and changed paths; upgrades preserve key bytes', async () => {
  const root = await mkdtemp(join(await realpath(tmpdir()), 'task-hub-business-'));
  try {
    const state = join(root, 'service'),
      parent = join(root, 'private'),
      configPath = join(parent, 'publisher.json'),
      keyDirectory = join(parent, 'keys'),
      oldBundle = join(root, 'release-one'),
      nextBundle = join(root, 'release-two');
    await mkdir(state, { mode: 0o700 });
    await mkdir(parent, { mode: 0o700 });
    const identity = {
      deploymentId: 'test-deployment',
      serverInstanceId: 'test-instance',
      primaryHostId: 'test-host',
    };
    const config = {
      version: 1,
      domain: 'taskhub.business',
      ...identity,
      relayURL: 'https://synthetic.invalid',
      publisherToken: 'dedicated-synthetic-publisher-credential',
      keyDirectory,
      bundleRoot: oldBundle,
      payloadSha256: 'a'.repeat(64),
    };
    await save(configPath, config);
    assert.equal(
      (await readBusinessNotifications(configPath, identity, { state, allowMissingKey: true }))
        .keyPresent,
      false,
    );
    await mkdir(keyDirectory, { mode: 0o700 });
    await assert.rejects(
      readBusinessNotifications(configPath, identity, { allowMissingKey: true }),
      /empty or incomplete/,
    );
    const keyPath = join(keyDirectory, businessKeyName);
    await save(keyPath, {
      version: 1,
      domain: 'taskhub.business',
      ...identity,
      privateKeyPem: generateKeyPairSync('x25519')
        .privateKey.export({ format: 'pem', type: 'pkcs8' })
        .toString(),
    });
    const keySha256 = await shaFile(keyPath);
    const runtime = {
      ...identity,
      bundle: oldBundle,
      payloadSha256: config.payloadSha256,
      businessNotificationsConfigPath: configPath,
    };
    await save(join(state, 'runtime.json'), runtime);
    await save(join(state, 'env.json'), { [businessConfigEnv]: configPath });
    await writeBusinessConfiguration(
      state,
      {
        path: configPath,
        material: await readBusinessNotifications(configPath, identity),
        next: config,
      },
      identity,
    );
    const manifest = {
      node: '>=24 <25',
      payloadSha256: 'b'.repeat(64),
      entries: { businessCrypto: 'business-crypto/business-crypto.cjs' },
      businessCrypto: {
        format: 'cjs',
        manifest: 'business-crypto/manifest.json',
        sha256: 'c'.repeat(64),
      },
      files: {
        'business-crypto/business-crypto.cjs': { sha256: 'c'.repeat(64) },
        'business-crypto/manifest.json': { sha256: 'd'.repeat(64) },
      },
    };
    const prepared = await prepareBusinessConfiguration(state, {
      previous: runtime,
      identity,
      sourceBundle: nextBundle,
      release: nextBundle,
      manifest,
    });
    assert.equal(prepared.path, configPath);
    assert.equal(prepared.next.keyDirectory, keyDirectory);
    await writeBusinessConfiguration(state, prepared, identity);
    await save(join(state, 'runtime.json'), {
      ...runtime,
      bundle: nextBundle,
      payloadSha256: manifest.payloadSha256,
    });
    assert.equal((await businessMaterial(state)).keySha256, keySha256);
    await assert.rejects(
      readBusinessNotifications(configPath, { ...identity, primaryHostId: 'other' }),
      /identity/,
    );
    await chmod(configPath, 0o644);
    await assert.rejects(readBusinessNotifications(configPath, identity), /private file rejected/);
    await chmod(configPath, 0o600);
    await link(configPath, join(parent, 'linked.json'));
    await assert.rejects(readBusinessNotifications(configPath, identity), /private file rejected/);
    await rm(join(parent, 'linked.json'));
    await save(join(state, 'env.json'), { [businessConfigEnv]: '' });
    await assert.rejects(businessMaterial(state), /paths disagree/);
    await save(join(state, 'env.json'), { [businessConfigEnv]: configPath });
    await save(configPath, { ...prepared.next, keyDirectory: join(parent, 'missing-replacement') });
    await assert.rejects(businessMaterial(state, { allowMissingKey: true }), /binding rejected/);
    assert.equal(await shaFile(keyPath), keySha256);
    await assert.rejects(readBusinessNotifications('', identity), /rejected/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('primary requires the executable to report Node 24; version declarations are insufficient', () => {
  assertNode24();
  assert.throws(() => assertNode24('/usr/bin/false'), /suppressed|Node 24/);
});

test('live install/instance locks reject concurrent writers; stale owner can recover', async () => {
  const root = await mkdtemp(join(await realpath(tmpdir()), 'task-hub-lock-'));
  try {
    const path = join(root, 'instance.lock');
    const one = await acquireLock(path);
    await assert.rejects(acquireLock(path), /already running/);
    await one.release();
    await mkdir(path);
    await save(join(path, 'owner.json'), { pid: 2147483647 });
    const recovered = await acquireLock(path);
    await recovered.release();
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
test('public registry needs an explicitly trusted signing key; trusted signatures still reject invalid fields and certificate bytes', async () => {
  const root = await mkdtemp(join(await realpath(tmpdir()), 'task-hub-registration-'));
  try {
    const keys = generateKeyPairSync('ed25519', {
      publicKeyEncoding: { type: 'spki', format: 'pem' },
      privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
    });
    const record = {
      version: 1,
      registryVersion: 1,
      deploymentId: 'deployment',
      serverInstanceId: 'instance',
      primaryHostId: 'host',
      name: 'test',
      domain: 'task-test.local',
      endpoints: ['https://task-test.local:9443'],
      contractVersion: '1',
      certificatePem: await certificateFixture(root, 'task-test.local'),
    };
    record.certificateSha256 = hash(new X509Certificate(record.certificatePem).raw);
    const document = signedRegistration(record, keys.publicKey, keys.privateKey);
    assert.equal(
      verifyRegistration(document, keyFingerprint(keys.publicKey)).primaryHostId,
      'host',
    );
    assert.throws(() => verifyRegistration(document, 'b'.repeat(64)), /explicitly trusted/);
    assert.throws(
      () =>
        verifyRegistration(
          { ...document, registration: { ...record, primaryHostId: 'other' } },
          keyFingerprint(keys.publicKey),
        ),
      /explicitly trusted/,
    );
    for (const invalid of [
      { ...record, unknownField: true },
      { ...record, certificatePem: 42 },
      { ...record, certificatePem: 'not-a-certificate' },
      { ...record, certificateSha256: 'e'.repeat(64) },
      { ...record, endpoints: [42] },
    ]) {
      const signedInvalid = signedRegistration(invalid, keys.publicKey, keys.privateKey);
      assert.throws(
        () => verifyRegistration(signedInvalid, keyFingerprint(keys.publicKey)),
        /Invalid|fingerprint is inconsistent/,
      );
    }
    assert.throws(
      () => verifyRegistration({ ...document, extra: true }, keyFingerprint(keys.publicKey)),
      /fields/,
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
test('executor without trusted registry never creates a database', async () => {
  const state = await mkdtemp(join(await realpath(tmpdir()), 'task-hub-executor-'));
  try {
    await assert.rejects(execute({ action: 'executor', state }), /separately verified/);
    await assert.rejects(readFile(join(state, 'postgres/PG_VERSION')), { code: 'ENOENT' });
  } finally {
    await rm(state, { recursive: true, force: true });
  }
});
test('TLS same-origin gateway streams SSE and uploads; certificate mismatch and hostile Host fail', async () => {
  const root = await mkdtemp(join(await realpath(tmpdir()), 'task-hub-gateway-'));
  let api, tls;
  try {
    await mkdir(join(root, 'web'));
    await writeFile(join(root, 'web/index.html'), '<html>frozen-web</html>');
    await writeFile(join(root, 'web/.env'), 'do-not-serve');
    execFileSync(
      '/usr/bin/openssl',
      [
        'req',
        '-x509',
        '-newkey',
        'rsa:2048',
        '-sha256',
        '-nodes',
        '-days',
        '2',
        '-subj',
        '/CN=task-test.local',
        '-addext',
        'subjectAltName=DNS:task-test.local',
        '-keyout',
        join(root, 'key.pem'),
        '-out',
        join(root, 'cert.pem'),
      ],
      { stdio: 'ignore' },
    );
    const cert = await readFile(join(root, 'cert.pem'));
    let serviceProbes = 0;
    api = createServer((req, res) => {
      if (req.url === '/api/v1/service') serviceProbes++;
      if (req.url === '/api/v1/events') {
        res.writeHead(200, { 'Content-Type': 'text/event-stream' });
        res.write('id: ' + req.headers['last-event-id'] + '\ndata: first\n\n');
        setTimeout(() => res.end('data: last\n\n'), 500);
        return;
      }
      if (req.url === '/api/upload') {
        let size = 0;
        req.on('data', (b) => {
          size += b.length;
        });
        req.on('end', () =>
          res.end(
            JSON.stringify({
              size,
              origin: req.headers.origin,
              forwarded: req.headers['x-forwarded-host'] ?? null,
            }),
          ),
        );
        return;
      }
      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify({ version: 1 }));
    });
    await new Promise((res) => api.listen(0, '127.0.0.1', res));
    const registration = {
      domain: 'task-test.local',
      certificatePem: cert.toString(),
      certificateSha256: hash(new X509Certificate(cert).raw),
      endpoints: [],
    };
    tls = await gateway({
      bundle: root,
      state: root,
      registration,
      apiPort: api.address().port,
      key: await readFile(join(root, 'key.pem')),
      cert,
    });
    await new Promise((res) => tls.listen(0, '127.0.0.1', res));
    registration.endpoints = ['https://task-test.local:' + tls.address().port];
    const origin = registration.endpoints[0],
      host = new URL(origin).host;
    assert.equal(
      (await tlsJson(origin, '/api/v1/service', registration, { connectHost: '127.0.0.1' }))
        .version,
      1,
    );
    await assert.rejects(
      tlsJson(
        origin,
        '/api/v1/service',
        { ...registration, certificateSha256: 'b'.repeat(64) },
        { connectHost: '127.0.0.1' },
      ),
      /HTTPS identity/,
    );
    assert.equal(serviceProbes, 1, 'a rejected pin must not reach the upstream API');
    assert.equal(
      (await tlsJson(origin, '/api/v1/service', registration, { connectHost: '127.0.0.1' }))
        .version,
      1,
      'a rejected registration must not poison a later valid probe',
    );
    const wrongName = 'https://wrong-task-test.local:' + tls.address().port;
    await assert.rejects(
      tlsJson(
        wrongName,
        '/api/v1/service',
        { ...registration, domain: 'wrong-task-test.local', endpoints: [wrongName] },
        { connectHost: '127.0.0.1' },
      ),
      /HTTPS identity/,
    );
    assert.equal(serviceProbes, 2, 'hostname verification must still reject before HTTP');
    const call = (path, headers = {}, data) =>
      new Promise((resolve, reject) => {
        const req = request(
          {
            host: '127.0.0.1',
            port: tls.address().port,
            servername: registration.domain,
            ca: cert,
            path,
            method: data ? 'POST' : 'GET',
            headers: { host, ...headers },
          },
          (res) => {
            let body = '';
            res.on('data', (b) => {
              body += b;
            });
            res.on('end', () => resolve({ status: res.statusCode, body }));
          },
        );
        req.on('error', reject);
        req.end(data);
      });
    assert.match((await call('/')).body, /frozen-web/);
    assert.equal((await call('/.env')).status, 404);
    assert.equal((await call('/', { host: 'evil.example' })).status, 421);
    const upload = JSON.parse(
      (
        await call(
          '/api/upload',
          { origin, 'x-forwarded-host': 'evil.example' },
          Buffer.alloc(120000),
        )
      ).body,
    );
    assert.deepEqual(upload, { size: 120000, origin, forwarded: null });
    const sse = await new Promise((resolve, reject) => {
      const start = Date.now();
      const req = request(
        {
          host: '127.0.0.1',
          port: tls.address().port,
          servername: registration.domain,
          ca: cert,
          path: '/api/v1/events',
          headers: { host, 'last-event-id': '42' },
        },
        (res) => {
          res.once('data', (b) => {
            resolve({ text: b.toString(), elapsed: Date.now() - start });
            req.destroy();
          });
        },
      );
      req.on('error', reject);
      req.end();
    });
    assert.match(sse.text, /id: 42/);
    assert.ok(sse.elapsed < 450, 'SSE first event arrives before upstream ends');
  } finally {
    if (tls) {
      tls.closeAllConnections();
      await new Promise((res) => tls.close(res));
    }
    if (api) {
      api.closeAllConnections();
      await new Promise((res) => api.close(res));
    }
    await rm(root, { recursive: true, force: true });
  }
});

test('encrypted backup authenticates every byte before extraction; damaged/wrong-key copies refuse', async () => {
  const root = await mkdtemp(join(await realpath(tmpdir()), 'task-hub-backup-'));
  try {
    const source = join(root, 'source'),
      archive = join(root, 'backup.enc');
    await mkdir(join(source, 'uploads'), { recursive: true });
    const bytes = Buffer.concat([
      Buffer.from('private-evidence-marker'),
      randomBytes(2 * 1024 * 1024),
    ]);
    await writeFile(join(source, 'uploads/attachment.bin'), bytes);
    await save(join(source, 'env.json'), { SESSION_SECRET: 'never-visible-in-archive' });
    const key = await backupKey(join(root, 'keys/backup.key'), true);
    await assert.rejects(backupKey(join(root, 'keys/backup.key'), true), { code: 'EEXIST' });
    const manifest = {
      format: 1,
      kind: 'taskhub-cold-backup',
      backupId: 'roundtrip',
      ...(await privateTree(source)),
    };
    const sealed = await sealBackup(source, manifest, archive, key);
    assert.equal(sealed.encrypted, 'AES-256-GCM');
    const ciphertext = await readFile(archive);
    assert.equal(ciphertext.includes('never-visible-in-archive'), false);
    assert.equal(ciphertext.includes('private-evidence-marker'), false);
    const restored = join(root, 'restored');
    assert.equal((await openBackup(archive, key, restored)).backupId, 'roundtrip');
    assert.deepEqual(await readFile(join(restored, 'uploads/attachment.bin')), bytes);
    assert.equal((await stat(restored)).mode & 0o777, 0o700);
    assert.equal((await stat(join(restored, 'uploads/attachment.bin'))).mode & 0o777, 0o600);
    assert.equal((await stat(archive)).mode & 0o777, 0o600);
    await assert.rejects(sealBackup(source, manifest, archive, key), /already exists/);
    const bad = join(root, 'wrong-key');
    await assert.rejects(openBackup(archive, randomBytes(32), bad), /authentication failed/);
    await assert.rejects(stat(bad), { code: 'ENOENT' });
    const tampered = Buffer.from(ciphertext);
    tampered[50] ^= 1;
    await writeFile(join(root, 'tampered'), tampered, { mode: 0o600 });
    await assert.rejects(
      openBackup(join(root, 'tampered'), key, join(root, 'tamper-dest')),
      /authentication failed/,
    );
    await writeFile(join(root, 'truncated'), ciphertext.subarray(0, ciphertext.length - 3), {
      mode: 0o600,
    });
    await assert.rejects(
      openBackup(join(root, 'truncated'), key, join(root, 'truncate-dest')),
      /authentication failed/,
    );
    await chmod(join(root, 'keys/backup.key'), 0o644);
    await assert.rejects(backupKey(join(root, 'keys/backup.key')), /permissions/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
test('authenticated path traversal and external symlinks fail closed', async () => {
  const root = await mkdtemp(join(await realpath(tmpdir()), 'task-hub-backup-path-'));
  try {
    const archive = join(root, 'bad.enc'),
      key = randomBytes(32);
    await mkdir(join(root, 'source'));
    await save(join(root, 'outside'), 'sensitive');
    const manifest = {
      format: 1,
      kind: 'taskhub-cold-backup',
      files: { '../outside': { size: 9, sha256: hash('sensitive') } },
      directories: [],
    };
    await sealBackup(join(root, 'source'), manifest, archive, key);
    await assert.rejects(
      openBackup(archive, key, join(root, 'destination')),
      /Invalid backup file entry/,
    );
    assert.equal(await readFile(join(root, 'outside'), 'utf8'), 'sensitive');
    await mkdir(join(root, 'tree'));
    await symlink(join(root, 'outside'), join(root, 'tree/link'));
    await assert.rejects(privateTree(join(root, 'tree')), /cannot follow links/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
test('offline/recovery fences prohibit runtime start, and maintenance cannot default to live state', async () => {
  const root = await mkdtemp(join(await realpath(tmpdir()), 'task-hub-fence-'));
  try {
    for (const file of ['offline-only.json', 'restore-fence.json', 'maintenance.json']) {
      await save(join(root, file), { version: 1 });
      await assert.rejects(assertStartAllowed(root), /start is fenced/);
      await assert.rejects(
        execute({ action: 'start', state: root }),
        process.platform === 'darwin'
          ? /start is fenced/
          : /Primary install\/start\/upgrade supports macOS only/,
      );
      const partial = await execute({ action: 'status', state: root });
      assert.equal(partial.partialInstallation, true);
      assert.equal(partial.primaryEligibility, 'fenced');
      await rm(join(root, file));
    }
    await assert.rejects(
      execute({ action: 'backup', output: join(root, 'file') }),
      /explicit --state/,
    );
    await assert.rejects(execute({ action: 'restore', offline: true }), /explicit --state/);
    assert.equal(
      argumentsFor(['restore', '--state', root, '--offline', '--destination', join(root, 'other')])
        .offline,
      true,
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
test('autostart distinguishes unloaded, loaded but disabled, and an actual running job', () => {
  const label = 'com.agenthub.task.example';
  assert.equal(launchJobState(undefined, '{}', label).loaded, false);
  const running = 'state = running\npid = 123\nlast exit code = 1\n';
  assert.deepEqual(launchJobState(running, '"' + label + '" => true', label), {
    loaded: true,
    disabled: true,
    state: 'running',
    pid: 123,
    lastExitCode: 1,
  });
  assert.equal(launchJobState(running, '"' + label + '" => false', label).disabled, false);
  assert.equal(launchJobState(running, undefined, label).disabled, null);
});
test('activation runtime permits only the live exact controller, revokes abandoned/conflicting grants, and requires commit', async () => {
  const state = await mkdtemp(join(await realpath(tmpdir()), 'task-hub-activation-'));
  const restoreId = randomUUID(),
    activationId = randomUUID();
  let lock;
  try {
    const identity = {
      deploymentId: 'fixture-deployment',
      serverInstanceId: 'fixture-instance',
      primaryHostId: 'fixture-host',
    };
    const bundle = join(state, 'bundle');
    await save(join(bundle, 'manifest.json'), { fixture: true });
    const payloadSha256 = 'b'.repeat(64),
      manifestSha256 = 'c'.repeat(64),
      archiveSha256 = 'a'.repeat(64),
      bundleManifestSha256 = await shaFile(join(bundle, 'manifest.json'));
    await save(join(state, 'runtime.json'), {
      role: 'primary',
      ...identity,
      bundle,
      payloadSha256,
    });
    const proofPath = join(state, 'recovery', restoreId, 'restore-verification.json');
    await saveImmutable(proofPath, {
      version: 2,
      kind: 'taskhub-restore-verification',
      source: 'native-single-user-cold-restore',
      action: 'original-primary-restored-fenced',
      sqlRestoreVerified: true,
      sourceStateSha256: hash(state),
      identity,
      restoreId,
      archiveSha256,
      payloadSha256,
      bundleManifestSha256,
      manifestSha256,
      fixture: true,
    });
    lock = await acquireLock(join(state, 'install.lock'), { action: 'restore-activate' });
    const owner = await readFile(join(state, 'install.lock/owner.json'), 'utf8');
    const grant = {
      version: restoreActivationVersion,
      kind: restoreActivationKind,
      source: 'actual-normal-start',
      action: 'restore-activation-intent',
      phase: 'prepared',
      identity,
      payloadSha256,
      bundleManifestSha256,
      manifestSha256,
      restoreId,
      activationId,
      pid: process.pid,
      controllerSha256: processFingerprint(process.pid),
      installNonce: lock.owner.nonce,
      sourceStateSha256: hash(state),
      verificationSha256: await shaFile(proofPath),
      runtimeSha256: await shaFile(join(state, 'runtime.json')),
      archiveSha256,
    };
    const family = join(state, 'recovery', restoreId, 'activations', activationId);
    await saveImmutable(join(family, 'intent.json'), grant);
    const fence = {
      version: 2,
      restoreId,
      activationId,
      state: 'activating',
      verificationSha256: grant.verificationSha256,
    };
    await save(join(state, 'restore-activation.json'), grant);
    await save(join(state, 'restore-fence.json'), fence);
    await assert.rejects(assertStartAllowed(state), /explicit operation/);
    assert.equal((await assertStartAllowed(state, { activationId })).activationId, activationId);
    assert.equal((await assertStartAllowed(state, { runtime: true })).restoreId, restoreId);
    await assertActivationRuntime(state, grant);
    await save(join(state, 'restore-fence.json'), { ...fence, restoreId: randomUUID() });
    await assert.rejects(assertActivationRuntime(state, grant), /conflicts/);
    await save(join(state, 'restore-fence.json'), fence);
    await save(join(state, 'install.lock/owner.json'), { ...lock.owner, pid: 2147483647 });
    await assert.rejects(assertActivationRuntime(state, grant), /owner is absent or unknown/);
    await save(join(state, 'install.lock/owner.json'), owner);
    await rm(join(state, 'restore-fence.json'));
    await assert.rejects(assertStartAllowed(state), /explicit operation/);
    await lock.release();
    lock = null;
    await assert.rejects(assertActivationRuntime(state, grant), { code: 'ENOENT' });
    const receipt = {
      ...grant,
      action: 'restore-activated',
      phase: 'activated',
      evidence: 'actual-normal-start',
      serviceStarted: true,
      fenceRetained: false,
      loginAutostart: true,
    };
    await saveImmutable(join(family, 'committed.json'), receipt);
    assert.equal((await assertStartAllowed(state)).activationId, activationId);
    await assertActivationRuntime(state, grant);
    for (const changed of [
      { version: 1 },
      { kind: 'unknown' },
      { source: 'synthetic-lifecycle-callback' },
      { action: 'wrong-action' },
      { activationId: randomUUID() },
      { restoreId: randomUUID() },
      { identity: { ...identity, primaryHostId: 'wrong-host' } },
      { manifestSha256: 'd'.repeat(64) },
      { runtimeSha256: 'f'.repeat(64) },
      { serviceStarted: false },
    ]) {
      await save(join(family, 'committed.json'), { ...receipt, ...changed });
      assert.equal(await readCommittedActivation(state, restoreId, activationId), null);
      await assert.rejects(assertStartAllowed(state));
    }
    await save(join(family, 'committed.json'), receipt);
    await save(join(family, 'failed.json'), 'incomplete or unreadable failure receipt');
    assert.equal(await readCommittedActivation(state, restoreId, activationId), null);
    await assert.rejects(assertActivationRuntime(state, grant), /failed/);
    await rm(join(family, 'failed.json'));
    await rm(join(state, 'restore-activation.json'));
    await assertActivationRuntime(state, grant);
    await rm(join(state, 'recovery', restoreId, 'activations', activationId, 'committed.json'));
    await assert.rejects(assertActivationRuntime(state, grant), /never committed/);
  } finally {
    if (lock) await lock.release();
    await rm(state, { recursive: true, force: true });
  }
});
