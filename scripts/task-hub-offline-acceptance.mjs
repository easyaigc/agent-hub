import assert from 'node:assert/strict';
import { generateKeyPairSync, randomUUID, X509Certificate } from 'node:crypto';
import { cp, mkdir, readFile, readdir, rename, rm, stat } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import {
  fileInventory,
  acquireLock,
  assertStartAllowed,
  businessMaterial,
  businessConfigEnv,
  businessKeyName,
  hash,
  json,
  keyFingerprint,
  privateDir,
  run,
  save,
  shaFile,
  signedRegistration,
  tool,
  verifyBundle,
  readCommittedActivation,
} from '../skills/codex-host-connect/scripts/task-hub-core.mjs';
import {
  activateRestoredPrimary,
  execute,
  prepareBusinessConfiguration,
  writeBusinessConfiguration,
} from '../skills/codex-host-connect/scripts/task-hub-service.mjs';
import { runtimeMain } from '../skills/codex-host-connect/scripts/task-hub-runtime.mjs';

// Explicit original SQL, real initdb/pg_controldata/postgres --single, synthetic
// service/Host metadata. No postmaster, socket, API, TLS, Worker or Bonjour.
// This is auxiliary native-tool evidence, never an iOS/DEV/installed-service pass.
function option(name) {
  const at = process.argv.indexOf('--' + name);
  if (at < 0) return undefined;
  if (!process.argv[at + 1] || process.argv[at + 1].startsWith('--'))
    throw Error('Missing AUX option: ' + name);
  return process.argv[at + 1];
}
const realBundle = option('bundle') === undefined ? null : resolve(option('bundle'));
const expectedPayloadSha256 = option('expected-payload'),
  expectedSourceSha256 = option('expected-source');
if (
  realBundle &&
  (!/^[a-f0-9]{64}$/.test(expectedPayloadSha256 ?? '') ||
    !/^[a-f0-9]{64}$/.test(expectedSourceSha256 ?? ''))
)
  throw Error(
    'Real frozen artifact AUX requires explicit --expected-payload and --expected-source',
  );
const migrationsArg = option('migrations');
if (!realBundle && migrationsArg === undefined)
  throw Error('Supply --migrations <approved original SQL directory>');
const migrations = resolve(migrationsArg ?? join(realBundle, 'runtime/migrations'));
const repo = fileURLToPath(new URL('..', import.meta.url));
const runId = randomUUID(),
  root = join(repo, '.local/task-management/deployment-completeness', runId);
await privateDir(root);
const state = join(root, 'service');
let bundle = join(root, 'sql-tools-fixture');
const factoryBundleArg = option('business-factory-bundle');
const factoryBundle = factoryBundleArg === undefined ? realBundle : resolve(factoryBundleArg);
if (realBundle && factoryBundle !== realBundle)
  throw Error('Real artifact AUX must use its own frozen factory');
let realManifest,
  originalBundleManifestSha256,
  artifactUnchanged = null;
let factoryManifest, createPublisher, configPath, notificationKeySha256, notificationConfigSha256;
let frozenPublisher, notificationFixture;
const originalFetch = globalThis.fetch;
let networkRequests = 0;
globalThis.fetch = async () => {
  networkRequests++;
  throw Error('Offline AUX forbids network');
};
const keyFile = join(root, 'keys/backup.key'),
  archive = join(root, 'backups/service.enc');
const results = [],
  sources = {},
  canonicalTools = {};
function pass(scenario, extra = {}) {
  results.push({ scenario, status: 'passed', ...extra });
  if (process.argv.includes('--progress'))
    console.log(JSON.stringify({ scenario, status: 'passed' }));
}
const pgCtl = await tool('pg_ctl'),
  initdb = await tool('initdb');
const postgres = join(dirname(pgCtl), 'postgres');
function sql(database, query) {
  const r = spawnSync(
    postgres,
    [
      '--single',
      '-D',
      join(state, 'postgres'),
      '-c',
      'log_statement=none',
      '-c',
      'log_min_error_statement=panic',
      database,
    ],
    {
      input: query + '\n',
      encoding: 'utf8',
      env: { PATH: process.env.PATH, LC_ALL: 'C', LANG: 'C' },
      timeout: 60000,
      maxBuffer: 4 * 1024 * 1024,
    },
  );
  if (r.error || r.status !== 0 || /(?:ERROR|FATAL|PANIC):/.test(r.stderr ?? ''))
    throw Error('Native single-user fixture SQL failed (private output suppressed)');
}
let backupResult;
try {
  if (realBundle) {
    realManifest = await verifyBundle(realBundle);
    assert.equal(realManifest.payloadSha256, expectedPayloadSha256);
    assert.equal(realManifest.sourceSha256, expectedSourceSha256);
    originalBundleManifestSha256 = await shaFile(join(realBundle, 'manifest.json'));
    for (const name of ['task-hub-core.mjs', 'task-hub-runtime.mjs', 'task-hub-service.mjs'])
      assert.equal(
        await shaFile(join(realBundle, 'tools', name)),
        await shaFile(join(repo, 'skills/codex-host-connect/scripts', name)),
        'Frozen canonical tool differs: ' + name,
      );
    pass(
      'full real frozen artifact inventory, caller supplied payload/source pins and current canonical tool hashes match',
      { payloadSha256: expectedPayloadSha256, sourceSha256: expectedSourceSha256 },
    );
  }
  for (const dir of [state, join(bundle, 'runtime/migrations'), join(bundle, 'tools')])
    await privateDir(dir);
  const files = (await readdir(migrations)).filter((n) => /^\d{4}_[a-z_]+\.sql$/.test(n)).sort();
  assert.ok(files.length >= 9, 'Use the real original Task Hub migration sequence');
  for (const name of files) {
    sources[name] = await shaFile(join(migrations, name));
    if (realBundle)
      assert.equal(sources[name], await shaFile(join(realBundle, 'runtime/migrations', name)));
    await cp(join(migrations, name), join(bundle, 'runtime/migrations', name));
    assert.equal(await shaFile(join(bundle, 'runtime/migrations', name)), sources[name]);
  }
  for (const name of ['task-hub-core.mjs', 'task-hub-runtime.mjs', 'task-hub-service.mjs']) {
    canonicalTools[name] = await shaFile(join(repo, 'skills/codex-host-connect/scripts', name));
    await cp(join(repo, 'skills/codex-host-connect/scripts', name), join(bundle, 'tools', name));
    assert.equal(await shaFile(join(bundle, 'tools', name)), canonicalTools[name]);
  }
  if (factoryBundle) {
    factoryManifest = realManifest ?? (await verifyBundle(factoryBundle));
    createPublisher = createRequire(join(factoryBundle, 'runtime/package.json'))(
      './dist/notifications/business-notification.factory.js',
    ).createBusinessNotificationPublisher;
    await cp(join(factoryBundle, 'business-crypto'), join(bundle, 'business-crypto'), {
      recursive: true,
    });
  }
  const inventory = await fileInventory(bundle);
  let payloadSha256 = hash(JSON.stringify(inventory));
  await save(join(bundle, 'manifest.json'), {
    format: 1,
    contractVersion: '1',
    files: inventory,
    payloadSha256,
    sourceSha256: hash(JSON.stringify(sources)),
    primaryPlatforms: ['darwin'],
    architecture: process.arch,
    ...(factoryManifest
      ? {
          node: factoryManifest.node,
          entries: { businessCrypto: factoryManifest.entries.businessCrypto },
          businessCrypto: factoryManifest.businessCrypto,
        }
      : {}),
  });
  const keys = generateKeyPairSync('ed25519', {
    publicKeyEncoding: { type: 'spki', format: 'pem' },
    privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
  });
  const id = {
    deploymentId: 'offline-' + runId,
    serverInstanceId: 'offline-instance',
    primaryHostId: 'synthetic-offline-host',
  };
  const record = {
    version: 1,
    registryVersion: 1,
    ...id,
    name: 'Offline tools fixture',
    domain: 'task-offline.local',
    endpoints: ['https://task-offline.local:29643'],
    contractVersion: '1',
  };
  run('/usr/bin/openssl', [
    'req',
    '-x509',
    '-newkey',
    'rsa:2048',
    '-nodes',
    '-days',
    '2',
    '-subj',
    '/CN=task-offline.local',
    '-addext',
    'subjectAltName=DNS:task-offline.local',
    '-keyout',
    join(root, 'synthetic-tls-key.pem'),
    '-out',
    join(root, 'synthetic-tls-cert.pem'),
  ]);
  record.certificatePem = await readFile(join(root, 'synthetic-tls-cert.pem'), 'utf8');
  record.certificateSha256 = hash(new X509Certificate(record.certificatePem).raw);
  await save(
    join(state, 'deployment.json'),
    signedRegistration(record, keys.publicKey, keys.privateKey),
  );
  await save(join(state, 'trust.json'), { registryKeySha256: keyFingerprint(keys.publicKey) });
  await save(join(state, 'registry-key.pem'), keys.privateKey);
  for (const name of ['pairing.json', 'pairing.png'])
    await save(join(state, name), 'offline-test-no-TLS');
  await save(
    join(state, 'tls-key.pem'),
    await readFile(join(root, 'synthetic-tls-key.pem'), 'utf8'),
  );
  await save(join(state, 'tls-cert.pem'), record.certificatePem);
  const hostRuntime = join(root, 'synthetic-host-runtime.json');
  await save(hostRuntime, { hostId: id.primaryHostId });
  await save(join(state, 'pg-password'), 'a'.repeat(64) + '\n');
  const config = {
    role: 'primary',
    ...id,
    hostRuntime,
    bundle,
    payloadSha256,
    pgPort: 29645,
    apiPort: 29644,
    tlsPort: 29643,
    bind: '127.0.0.1',
    node: process.execPath,
    path: process.env.PATH,
    autostart: true,
    pg: {
      pg_ctl: pgCtl,
      initdb,
      major: run(pgCtl, ['--version']).match(/(\d+)\.\d+/)[1],
      data: join(state, 'postgres'),
      socket: join(root, 'unused-socket'),
    },
  };
  await save(join(state, 'runtime.json'), config);
  await save(join(state, 'env.json'), {
    DATABASE_URL: 'postgresql://taskhub:' + 'b'.repeat(64) + '@127.0.0.1:29645/taskhub',
    PG_ADMIN_PASSWORD: 'a'.repeat(64),
    SESSION_SECRET: 'c'.repeat(64),
    KEY_HMAC_SECRET: 'd'.repeat(64),
    TASK_HUB_DEPLOYMENT_ID: id.deploymentId,
    TASK_HUB_INSTANCE_ID: id.serverInstanceId,
    TASK_HUB_PRIMARY_HOST_ID: id.primaryHostId,
    UPLOAD_DIR: join(state, 'uploads'),
    HOST_CONNECTION_STATE_DIR: join(state, 'host-connections'),
    HOST_BACKGROUND_JOBS: '1',
  });
  if (createPublisher) {
    const credentials = join(root, 'synthetic-business-private');
    await privateDir(credentials);
    configPath = join(credentials, 'publisher.json');
    const privateConfig = {
      version: 1,
      domain: 'taskhub.business',
      ...id,
      relayURL: 'https://offline-business.invalid',
      publisherToken: 'synthetic-dedicated-business-publisher-only',
      keyDirectory: join(credentials, 'publisher-keys'),
      bundleRoot: bundle,
      payloadSha256,
    };
    await save(configPath, privateConfig);
    let prepared = await prepareBusinessConfiguration(state, {
      identity: id,
      configPath,
      sourceBundle: bundle,
      release: bundle,
      manifest: await json(join(bundle, 'manifest.json')),
    });
    await writeBusinessConfiguration(state, prepared, id);
    config.businessNotificationsConfigPath = configPath;
    await save(join(state, 'runtime.json'), config);
    await save(join(state, 'env.json'), {
      ...(await json(join(state, 'env.json'), true)),
      [businessConfigEnv]: configPath,
    });
    assert.equal((await businessMaterial(state, { allowMissingKey: true })).keyPresent, false);
    const first = await createPublisher(id, { configPath });
    notificationKeySha256 = await shaFile(join(privateConfig.keyDirectory, businessKeyName));
    const oldBundle = bundle;
    bundle = realBundle ?? join(root, 'sql-tools-upgraded-fixture');
    if (!realBundle) {
      await cp(oldBundle, bundle, { recursive: true });
      await save(join(bundle, 'aux-release-generation.txt'), 'second synthetic frozen release');
      const upgradedInventory = await fileInventory(bundle, new Set(['manifest.json']));
      payloadSha256 = hash(JSON.stringify(upgradedInventory));
      await save(join(bundle, 'manifest.json'), {
        ...(await json(join(oldBundle, 'manifest.json'))),
        files: upgradedInventory,
        payloadSha256,
      });
    } else payloadSha256 = realManifest.payloadSha256;
    prepared = await prepareBusinessConfiguration(state, {
      previous: config,
      identity: id,
      sourceBundle: bundle,
      release: bundle,
      manifest: await verifyBundle(bundle),
    });
    assert.equal(prepared.path, configPath);
    assert.equal(prepared.next.keyDirectory, privateConfig.keyDirectory);
    await writeBusinessConfiguration(state, prepared, id);
    config.bundle = bundle;
    config.payloadSha256 = payloadSha256;
    await save(join(state, 'runtime.json'), config);
    const second = await createPublisher(id, { configPath });
    frozenPublisher = second;
    assert.equal(first.publicKey, second.publicKey);
    assert.equal((await businessMaterial(state)).keySha256, notificationKeySha256);
    assert.equal((await businessMaterial(state)).config.bundleRoot, bundle);
    assert.equal((await businessMaterial(state)).config.payloadSha256, payloadSha256);
    notificationConfigSha256 = await shaFile(configPath);
    pass(
      'actual frozen F01 factory creates one X25519 key; deployment install/upgrade helpers preserve paths/key and update canonical release pin',
      {
        category: 'offline synthetic deployment helpers; no install/start/job',
        factoryPayloadSha256: factoryManifest.payloadSha256,
        factoryRuntimeSha256:
          factoryManifest.files['runtime/dist/notifications/business-notification.factory.js']
            .sha256,
        keySha256: notificationKeySha256,
        networkRequests,
      },
    );
    const preserved = privateConfig.keyDirectory + '.synthetic-preserved';
    await rename(privateConfig.keyDirectory, preserved);
    await mkdir(privateConfig.keyDirectory, { mode: 0o700 });
    await assert.rejects(
      createPublisher(id, { configPath }),
      /BusinessNotificationConfigurationRejected/,
    );
    await assert.rejects(businessMaterial(state, { allowMissingKey: true }), /empty or incomplete/);
    assert.deepEqual(await readdir(privateConfig.keyDirectory), []);
    await rm(privateConfig.keyDirectory, { recursive: true });
    await assert.rejects(businessMaterial(state, { allowMissingKey: true }), /binding rejected/);
    await rename(preserved, privateConfig.keyDirectory);
    assert.equal((await businessMaterial(state)).keySha256, notificationKeySha256);
    pass(
      'old empty key directory never regenerates; initialized missing-directory binding rejects startup',
    );
  }
  await privateDir(join(state, 'uploads'));
  await privateDir(join(state, 'host-connections'));
  run(
    initdb,
    [
      '-D',
      config.pg.data,
      '-U',
      'taskhub_admin',
      '--encoding=UTF8',
      '--locale=C',
      '--auth=scram-sha-256',
      '--pwfile',
      join(state, 'pg-password'),
    ],
    { env: { ...process.env, LC_ALL: 'C', LANG: 'C' }, timeout: 60000 },
  );
  sql('postgres', 'CREATE DATABASE taskhub;');
  sql(
    'taskhub',
    'CREATE TABLE taskhub_migrations(version integer PRIMARY KEY,checksum text NOT NULL,applied_at timestamptz NOT NULL DEFAULT now());',
  );
  for (const name of files) {
    const body = (await readFile(join(bundle, 'runtime/migrations', name), 'utf8'))
      .replace(/--[^\n]*(?:\n|$)/g, ' ')
      .replace(/\r?\n/g, ' ');
    sql(
      'taskhub',
      'BEGIN; ' +
        body +
        ' INSERT INTO taskhub_migrations(version,checksum) VALUES (' +
        Number(name.slice(0, 4)) +
        ",'" +
        sources[name] +
        "'); COMMIT;",
    );
  }
  pass('real original SQL migration sequence applied by native single-user PostgreSQL', {
    migrationCount: files.length,
  });
  const actor = randomUUID(),
    reviewer = randomUUID(),
    project = randomUUID(),
    task = randomUUID(),
    round = randomUUID(),
    attachment = randomUUID();
  const evidence = Buffer.from('original-private-attachment-byte-evidence\n'),
    thumbnail = Buffer.from('thumbnail-fixture-bytes');
  const storage = attachment + '.png',
    thumb = attachment + '.thumb.webp';
  await save(join(state, 'uploads', storage), evidence.toString());
  await save(join(state, 'uploads', thumb), thumbnail.toString());
  const operations = ['unknown', 'confirmed'].map(() => randomUUID());
  const serviceKey = JSON.stringify([id.deploymentId, id.serverInstanceId, id.primaryHostId]);
  sql(
    'taskhub',
    `BEGIN;
    INSERT INTO projects(id,key,name,description) VALUES('${project}','OFFLINE','Offline project','Native SQL backup fixture');
    INSERT INTO actors(id,name,kind,role,username,password_hash) VALUES('${actor}','Owner','human','admin','owner','fixture'),('${reviewer}','Reviewer','human','member','reviewer','fixture');
    INSERT INTO project_grants(actor_id,project_id) VALUES('${actor}','${project}'),('${reviewer}','${project}');
    INSERT INTO tasks(id,project_id,key,title,description,acceptance_criteria,type,priority,status,owner_id,creator_id,reviewer_id,acceptance_mode)
      VALUES('${task}','${project}','OFFLINE-1','Saved task','Preserve round and unknown operations','Restore matching original rows','chore','P3','doing','${actor}','${actor}','${reviewer}','human');
    INSERT INTO task_rounds(id,task_id,number,status) VALUES('${round}','${task}',1,'open');
    INSERT INTO attachments(id,project_id,uploader_id,name,media_type,size_bytes,sha256,storage_name,thumbnail_name,width,height)
      VALUES('${attachment}','${project}','${actor}','fixture.png','image/png',${evidence.length},'${hash(evidence)}','${storage}','${thumb}',1,1);
    INSERT INTO attachment_links(attachment_id,project_id,task_id) VALUES('${attachment}','${project}','${task}');
    INSERT INTO business_operations(operation_id,actor_id,device_id,service_key,content_hash,phase,project_id,result,sealed_result)
      VALUES('${operations[0]}','${actor}','offline-device','${serviceKey}','${hash('unknown')}','unknown','${project}',null,null),
      ('${operations[1]}','${actor}','offline-device','${serviceKey}','${hash('confirmed')}','confirmed','${project}','{"taskId":"${task}"}','private-sealed-response-fixture');
    COMMIT;`.replace(/\n/g, ' '),
  );
  pass(
    'real ledger with stable unknown and confirmed operation IDs, task/round and attachment associations',
  );
  if (frozenPublisher && files.includes('0010_business_notifications.sql')) {
    const cipher = createRequire(join(factoryBundle, 'runtime/package.json'))(
      '../business-crypto/business-crypto.cjs',
    ).businessNotificationCipher;
    const peer = cipher(
      generateKeyPairSync('x25519').privateKey.export({ format: 'pem', type: 'pkcs8' }).toString(),
    );
    const bindingId = randomUUID(),
      subscriptionId = randomUUID(),
      deviceId = randomUUID(),
      eventId = hash('notice-' + runId);
    const notice = {
      version: 1,
      domain: 'taskhub.business',
      eventId,
      ...id,
      actorId: actor,
      projectId: project,
      taskId: task,
      roundId: round,
      revision: 1,
      kind: 'acceptance_failed',
      occurredAt: new Date().toISOString(),
    };
    const envelope = { ...frozenPublisher.seal(notice, bindingId, peer.publicKey), eventId };
    assert.deepEqual(peer.open(envelope, bindingId, frozenPublisher.publicKey), notice);
    const literal = (value) => "'" + JSON.stringify(value).replaceAll("'", "''") + "'";
    sql(
      'taskhub',
      `BEGIN;
      INSERT INTO business_notice_subscriptions(id,service_key,actor_id,device_id,project_id,session_digest,recipient_key,device_token,environment,preferences,generation,content_hash,enabled,binding_id,management_token,binding_generation)
        VALUES('${subscriptionId}','${serviceKey}','${actor}','${deviceId}','${project}','${hash('synthetic-session')}','${peer.publicKey}','synthetic-device-token','development','{"acceptance_failed":true}',2,'${hash('synthetic-content')}',true,'${bindingId}','synthetic-management-token',2);
      INSERT INTO business_notice_outbox(event_id,service_key,actor_id,project_id,task_id,round_id,revision,kind,source_event_id,notice)
        VALUES('${eventId}','${serviceKey}','${actor}','${project}','${task}','${round}',1,'acceptance_failed','synthetic-notice-source',${literal(notice)});
      INSERT INTO business_notice_deliveries(event_id,subscription_id,binding_id,subscription_generation,envelope,state,reason)
        VALUES('${eventId}','${subscriptionId}','${bindingId}',2,${literal(envelope)},'uncertain','offline fixture: preserve unknown delivery');
      COMMIT;`.replace(/\n/g, ' '),
    );
    notificationFixture = {
      peer,
      bindingId,
      notice,
      envelope,
      senderPublicKey: frozenPublisher.publicKey,
    };
    pass(
      'actual canonical cipher and original SQL preserve synthetic subscription, exact task/round outbox and uncertain encrypted delivery; no Relay request',
    );
  }
  const offlineStatus = await execute({ action: 'status', state });
  assert.equal(offlineStatus.processRunning, false);
  assert.equal(offlineStatus.loginAutostart, false);
  assert.equal(offlineStatus.autostart.loaded, false);
  assert.equal(offlineStatus.runtimeHealthy, false);
  assert.equal(offlineStatus.worker.active, false);
  assert.equal(offlineStatus.worker.enabled, true);
  pass(
    'real offline service status distinguishes installation, loaded job, runtime health and Worker activity',
  );
  const hostBefore = await shaFile(hostRuntime);
  backupResult = await execute({
    action: 'backup',
    state,
    output: archive,
    'key-file': keyFile,
    'create-key': true,
  });
  assert.equal(backupResult.serviceStarted, false);
  assert.equal((await stat(archive)).mode & 0o777, 0o600);
  const ciphertext = await readFile(archive);
  assert.equal(ciphertext.includes('original-private-attachment'), false);
  assert.equal(ciphertext.includes('private-sealed-response-fixture'), false);
  if (configPath) {
    assert.equal(ciphertext.includes('synthetic-dedicated-business-publisher-only'), false);
    assert.equal(ciphertext.includes('BEGIN PRIVATE KEY'), false);
  }
  pass(
    'encrypted cold whole-cluster backup; SQL ledger, migrations, attachment bytes and configuration',
    { archiveSha256: backupResult.archiveSha256 },
  );
  const checked = await execute({ action: 'backup-status', archive, 'key-file': keyFile });
  assert.equal(checked.byteManifestVerified, true);
  assert.equal(checked.sqlRestoreVerified, false);
  const destination = join(root, 'offline-restored');
  const restored = await execute({
    action: 'restore',
    state,
    archive,
    'key-file': keyFile,
    offline: true,
    destination,
    bundle,
  });
  assert.deepEqual(restored.operationPhases, { confirmed: 1, unknown: 1 });
  assert.deepEqual(await readFile(join(destination, 'uploads', storage)), evidence);
  await assert.rejects(runtimeMain(destination), /start is fenced/);
  if (configPath) {
    assert.equal(await shaFile(configPath), notificationConfigSha256);
    assert.equal(
      (await businessMaterial(destination, { staged: true })).keySha256,
      notificationKeySha256,
    );
    assert.equal(
      await shaFile(join(destination, 'business-notifications/config.json')),
      notificationConfigSha256,
    );
    pass(
      'encrypted backup/offline restore preserves private publisher config and exact X25519 bytes without writing original external paths',
    );
  }
  if (notificationFixture) {
    for (const table of [
      'business_notice_subscriptions',
      'business_notice_outbox',
      'business_notice_deliveries',
    ])
      assert.equal(restored.database.counts[table], 1);
    const row = JSON.parse(
      Buffer.from(
        (
          await readFile(join(destination, 'sql-evidence/business_notice_deliveries.rows'), 'utf8')
        ).trim(),
        'base64',
      ).toString('utf8'),
    );
    assert.equal(row.state, 'uncertain');
    assert.deepEqual(row.envelope, notificationFixture.envelope);
    assert.deepEqual(
      notificationFixture.peer.open(
        row.envelope,
        notificationFixture.bindingId,
        notificationFixture.senderPublicKey,
      ),
      notificationFixture.notice,
    );
    pass(
      'offline real PostgreSQL restore retains notice subscription/outbox/delivery digests and independently decryptable exact task/round envelope',
    );
  }
  pass(
    'offline native SQL restore rechecks all row digests, migration checksums and attachment SHA; unknown receipts preserved',
    restored,
  );
  // Bad application bytes cannot acquire a valid backup merely because PG stopped.
  await save(join(state, 'uploads', storage), 'corrupt-bytes');
  await assert.rejects(
    execute({
      action: 'backup',
      state,
      output: join(root, 'backups/corrupt.enc'),
      'key-file': keyFile,
    }),
    /Attachment SQL reference or byte SHA/,
  );
  assert.equal(await stat(join(state, 'maintenance.json')).then(() => true), true);
  await save(join(state, 'uploads', storage), evidence.toString());
  await rm(join(state, 'maintenance.json'));
  pass('native SQL-to-attachment mismatch refuses backup and retains maintenance fence');
  await save(join(state, 'postgres/postmaster.pid'), '2147483647\n' + config.pg.data + '\n');
  await assert.rejects(
    execute({
      action: 'backup',
      state,
      output: join(root, 'backups/live-refused.enc'),
      'key-file': keyFile,
    }),
    /postmaster pidfile/,
  );
  await rm(join(state, 'postgres/postmaster.pid'));
  pass('postmaster marker refuses directory copying; no live-cluster backup is claimed');
  sql(
    'taskhub',
    "UPDATE taskhub_migrations SET checksum='" + 'e'.repeat(64) + "' WHERE version=1;",
  );
  await assert.rejects(
    execute({
      action: 'backup',
      state,
      output: join(root, 'backups/bad-migration.enc'),
      'key-file': keyFile,
    }),
    /SQL migration ledger/,
  );
  sql(
    'taskhub',
    "UPDATE taskhub_migrations SET checksum='" + sources[files[0]] + "' WHERE version=1;",
  );
  await rm(join(state, 'maintenance.json'));
  pass('real SQL migration checksum mismatch refuses a new backup');
  const orphanTask = randomUUID();
  sql(
    'taskhub',
    `SET session_replication_role = replica; INSERT INTO attachment_links(attachment_id,project_id,task_id) VALUES('${attachment}','${project}','${orphanTask}');`,
  );
  await assert.rejects(
    execute({
      action: 'backup',
      state,
      output: join(root, 'backups/orphan.enc'),
      'key-file': keyFile,
    }),
    /Offline PostgreSQL SQL verification failed/,
  );
  sql('taskhub', `DELETE FROM attachment_links WHERE task_id='${orphanTask}';`);
  await rm(join(state, 'maintenance.json'));
  pass('native offline FK data audit rejects an orphan task/attachment relationship');
  const wrongKey = join(root, 'keys/wrong.key');
  await save(wrongKey, 'e'.repeat(64) + '\n');
  const configBefore = await shaFile(join(state, 'runtime.json'));
  await assert.rejects(
    execute({ action: 'restore', state, archive, 'key-file': wrongKey, 'in-place': true, bundle }),
    /authentication failed/,
  );
  assert.equal(await shaFile(join(state, 'runtime.json')), configBefore);
  assert.equal((await json(join(state, 'restore-fence.json'), true)).state, 'restoring');
  await assert.rejects(execute({ action: 'start', state }), /start is fenced/);
  await rm(join(state, 'restore-fence.json'));
  pass('failed original-primary restore preserves source configuration and durable start fence');
  const workerHost = randomUUID(),
    workerActor = randomUUID(),
    workerBinding = randomUUID(),
    workerRun = randomUUID();
  sql(
    'taskhub',
    `BEGIN;
    INSERT INTO hosts(id,name,verified_at) VALUES('${workerHost}','Synthetic Worker',now());
    INSERT INTO host_connections(host_id,name,status,revoked) VALUES('${workerHost}','Synthetic Worker','offline',false);
    INSERT INTO actors(id,name,kind,role,host_id) VALUES('${workerActor}','Synthetic executor','agent','member','${workerHost}');
    INSERT INTO project_grants(actor_id,project_id) VALUES('${workerActor}','${project}');
    INSERT INTO host_project_bindings(id,project_id,host_id,remote_project_id,remote_project_name,root,role,actor_id,status,source_revision)
      VALUES('${workerBinding}','${project}','${workerHost}','fixture-project','Fixture','/fixture','executor','${workerActor}','verified',1);
    INSERT INTO execution_runs(id,task_id,round_id,role,binding_id,actor_id,host_id,remote_project_id,root,source_snapshot,target_snapshot,input_feedback_ids,input_revision,operation_id,operation_kind,request_body,state,submit_attempted,created_by,lease_owner,lease_until)
      VALUES('${workerRun}','${task}','${round}','executor','${workerBinding}','${workerActor}','${workerHost}','fixture-project','/fixture','{}','{}','[]',1,'fixture-worker-operation','create','{}','uncertain',true,'${actor}','unknown-old-worker',now()+interval '1 hour');
    COMMIT;`.replace(/\n/g, ' '),
  );
  const workerArchive = join(root, 'backups/unknown-worker.enc');
  await execute({ action: 'backup', state, output: workerArchive, 'key-file': keyFile });
  const workerRestore = await execute({
    action: 'restore',
    state,
    archive: workerArchive,
    'key-file': keyFile,
    'in-place': true,
    bundle,
  });
  await assert.rejects(
    execute({
      action: 'restore-activate',
      state,
      archive: workerArchive,
      'key-file': keyFile,
      'restore-id': workerRestore.restoreId,
      'verify-only': true,
    }),
    /execution or lease remains unresolved/,
  );
  pass('real restored uncertain execution and old Worker lease refuse activation; no input replay');
  // Synthetic fixture reset only. No service is running, and the next named
  // restore installs the original archive again with a distinct immutable ID.
  await rm(join(state, 'restore-fence.json'));
  if (configPath) {
    const material = await businessMaterial(state);
    await rm(material.config.keyDirectory, { recursive: true });
    await rm(configPath);
  }
  const original = await execute({
    action: 'restore',
    state,
    archive,
    'key-file': keyFile,
    'in-place': true,
    bundle,
  });
  assert.equal(original.primaryEligibility, 'fenced');
  if (configPath) {
    assert.equal(await shaFile(configPath), notificationConfigSha256);
    const restoredPublisher = await createPublisher(id, { configPath });
    assert.ok(restoredPublisher.publicKey);
    assert.equal((await businessMaterial(state)).keySha256, notificationKeySha256);
    pass(
      'original stopped synthetic primary restores missing external config/key to original bound paths; frozen factory reuses restored key without regeneration',
    );
    if (notificationFixture) {
      assert.equal(restoredPublisher.publicKey, notificationFixture.senderPublicKey);
      for (const table of [
        'business_notice_subscriptions',
        'business_notice_outbox',
        'business_notice_deliveries',
      ])
        assert.equal(original.database.counts[table], 1);
      assert.deepEqual(
        notificationFixture.peer.open(
          notificationFixture.envelope,
          notificationFixture.bindingId,
          restoredPublisher.publicKey,
        ),
        notificationFixture.notice,
      );
      pass(
        'original synthetic primary in-place restore retains notification rows and original sender key; uncertain delivery is not replayed',
      );
    }
  }
  await assert.rejects(execute({ action: 'start', state }), /start is fenced/);
  assert.equal(await shaFile(hostRuntime), hostBefore);
  assert.equal((await json(join(state, 'runtime.json'), true)).primaryHostId, id.primaryHostId);
  const recovery = await json(join(state, 'restore-journal.json'), true);
  assert.equal(recovery.phase, 'completed');
  assert.ok(await stat(join(recovery.rollback, 'postgres/PG_VERSION')));
  pass(
    'original stopped primary restore retains durable fence and rollback state; synthetic Host reference unchanged',
  );
  const activationOptions = {
    action: 'restore-activate',
    state,
    archive,
    'key-file': keyFile,
    'restore-id': original.restoreId,
  };
  const proofPath = join(state, 'recovery', original.restoreId, 'restore-verification.json');
  const proofBefore = await shaFile(proofPath),
    restoreReportBefore = await shaFile(join(state, 'restore-report.json'));
  const check = await execute({ ...activationOptions, 'verify-only': true });
  assert.equal(check.fenceRetained, true);
  assert.equal(check.serviceStarted, false);
  assert.equal(check.unresolvedWorkerRuns, 0);
  pass(
    'restore-activate verify-only authenticates archive, manifest, original state/Host, completed journal and fresh SQL again',
    check,
  );
  await assert.rejects(
    execute({ ...activationOptions, 'restore-id': randomUUID(), 'verify-only': true }),
    { code: 'ENOENT' },
  );
  await assert.rejects(
    execute({ ...activationOptions, 'key-file': wrongKey, 'verify-only': true }),
    /authentication failed/,
  );
  for (const file of [
    'offline-only.json',
    'maintenance.json',
    'restore-activation.json',
    'resources.json',
  ]) {
    await save(join(state, file), { version: 1, syntheticConflict: true });
    await assert.rejects(
      execute({ ...activationOptions, 'verify-only': true }),
      /fence conflicts|journal remains unknown/,
    );
    await rm(join(state, file));
  }
  await save(hostRuntime, { hostId: 'different-synthetic-host' });
  await assert.rejects(
    execute({ ...activationOptions, 'verify-only': true }),
    /original Host or service identity/,
  );
  await save(hostRuntime, { hostId: id.primaryHostId });
  const journalBefore = await json(join(state, 'restore-journal.json'), true);
  await save(join(state, 'restore-journal.json'), { ...journalBefore, phase: 'replacing' });
  await assert.rejects(execute({ ...activationOptions, 'verify-only': true }), /completed journal/);
  await save(join(state, 'restore-journal.json'), journalBefore);
  const rawArchive = await readFile(archive);
  await save(join(root, 'backups/substitute.enc'), 'not-the-original-archive');
  await assert.rejects(
    execute({
      ...activationOptions,
      archive: join(root, 'backups/substitute.enc'),
      'verify-only': true,
    }),
    /archive SHA/,
  );
  assert.deepEqual(await readFile(archive), rawArchive);
  pass(
    'wrong restore ID/key/archive, cross-Host, unfinished journal, conflicting fence and unknown Worker journal all refuse',
  );
  const negativeBundle = join(root, 'bundle-tamper-negative');
  await cp(bundle, negativeBundle, { recursive: true, verbatimSymlinks: true });
  const toolPath = join(negativeBundle, 'tools/task-hub-runtime.mjs'),
    toolBytes = await readFile(toolPath);
  await save(toolPath, toolBytes.toString() + '\n// synthetic mismatch\n');
  const runtimeBytes = await readFile(join(state, 'runtime.json'), 'utf8');
  await save(join(state, 'runtime.json'), { ...config, bundle: negativeBundle });
  try {
    await assert.rejects(execute({ ...activationOptions, 'verify-only': true }), /frozen manifest/);
  } finally {
    await save(join(state, 'runtime.json'), runtimeBytes);
  }
  await rm(negativeBundle, { recursive: true });
  await save(join(state, 'uploads', storage), 'synthetic drift');
  await assert.rejects(
    execute({ ...activationOptions, 'verify-only': true }),
    /bytes changed since verification/,
  );
  await save(join(state, 'uploads', storage), evidence.toString());
  if (configPath) {
    const business = await businessMaterial(state);
    await save(configPath, { ...business.config, relayURL: 'https://changed-offline.invalid' });
    await assert.rejects(
      execute({ ...activationOptions, 'verify-only': true }),
      /bytes changed since verification/,
    );
    await save(configPath, business.config);
    assert.equal(await shaFile(configPath), notificationConfigSha256);
    pass('post-restore private business config drift blocks restore activation');
  }
  pass(
    'changed bundle and post-restore attachment/configuration/PG material fingerprint cannot be activated',
  );
  let delegatedStarts = 0,
    delegatedStops = 0;
  async function lifecycleProbe(startPrimary) {
    const installLock = await acquireLock(join(state, 'install.lock'), {
      action: 'restore-activate',
    });
    try {
      return await activateRestoredPrimary(activationOptions, state, {
        startPrimary,
        stopPrimary: async () => {
          delegatedStops++;
          assert.equal(
            (await json(join(state, 'restore-fence.json'), true)).state,
            'restored-offline-verified',
          );
        },
      });
    } finally {
      await installLock.release();
    }
  }
  const healthy = {
    ...id,
    payloadSha256,
    processOwnershipVerified: true,
    runtimeHealthy: true,
    loginAutostart: true,
  };
  await assert.rejects(
    lifecycleProbe(async () => {
      delegatedStarts++;
      throw Error('synthetic startup failure');
    }),
    /synthetic startup failure/,
  );
  await assert.rejects(
    lifecycleProbe(async () => {
      delegatedStarts++;
      return { ...healthy, loginAutostart: false };
    }),
    /default loaded autostart/,
  );
  await assert.rejects(
    lifecycleProbe(async () => {
      delegatedStarts++;
      const pending = await json(join(state, 'restore-activation.json'), true);
      await mkdir(
        join(
          state,
          'recovery',
          original.restoreId,
          'activations',
          pending.activationId,
          'committed.json',
        ),
        { mode: 0o700 },
      );
      return healthy;
    }),
    { code: 'EEXIST' },
  );
  assert.equal(await shaFile(proofPath), proofBefore);
  assert.equal(await shaFile(join(state, 'restore-report.json')), restoreReportBefore);
  assert.equal(
    (await json(join(state, 'restore-fence.json'), true)).state,
    'restored-offline-verified',
  );
  pass(
    'synthetic normal-start and default-autostart failures stop only delegated scope and retain original fence/immutable report',
  );
  const activated = await lifecycleProbe(async (options, originalState, activationId) => {
    delegatedStarts++;
    assert.equal(options.action, 'restore-activate');
    assert.equal(originalState, state);
    await assert.rejects(assertStartAllowed(state, { activationId }), /conflicts/);
    await assert.rejects(assertStartAllowed(state), /conflicts/);
    await assert.rejects(assertStartAllowed(state, { runtime: true }), /conflicts/);
    return healthy;
  });
  assert.equal(activated.phase, 'auxiliary-verified');
  assert.equal(activated.evidence, 'synthetic-lifecycle-callback');
  assert.equal(activated.serviceStarted, false);
  await assert.rejects(assertStartAllowed(state), /fenced/);
  assert.equal(
    await stat(join(state, 'restore-fence.json')).then(
      () => true,
      () => false,
    ),
    true,
  );
  assert.equal(
    await stat(join(state, 'restore-activation.json')).then(
      () => true,
      () => false,
    ),
    false,
  );
  assert.equal(await shaFile(proofPath), proofBefore);
  assert.equal(await shaFile(join(state, 'restore-report.json')), restoreReportBefore);
  const committedIntent = await json(
    join(
      state,
      'recovery',
      original.restoreId,
      'activations',
      activated.activationId,
      'intent.json',
    ),
    true,
  );
  await save(join(state, 'restore-activation.json'), committedIntent);
  const committedStatus = await execute({ action: 'status', state });
  assert.equal(committedStatus.primaryEligibility, 'fenced');
  assert.equal(committedStatus.committedActivationGuard, false);
  await assert.rejects(
    execute({ ...activationOptions, 'verify-only': true }),
    /Pending activation fence/,
  );
  await rm(join(state, 'restore-activation.json'));
  assert.equal(
    await readCommittedActivation(state, original.restoreId, activated.activationId),
    null,
  );
  const savedFence = await json(join(state, 'restore-fence.json'), true);
  // Adversarial contract fixtures are separate families; never overwrite the
  // immutable auxiliary intent, success/failure or restore proof.
  const adversarialId = randomUUID(),
    family = join(state, 'recovery', original.restoreId, 'activations', adversarialId);
  await save(join(family, 'intent.json'), {
    ...committedIntent,
    source: 'actual-normal-start',
    activationId: adversarialId,
  });
  await save(join(family, 'committed.json'), {
    ...activated,
    source: 'actual-normal-start',
    evidence: 'actual-normal-start',
    action: 'restore-activated',
    phase: 'activated',
    serviceStarted: true,
    fenceRetained: false,
  });
  assert.equal(await readCommittedActivation(state, original.restoreId, adversarialId), null);
  await rm(join(state, 'restore-fence.json'));
  await assert.rejects(
    execute({ ...activationOptions, 'verify-only': true }),
    /fence is absent without a committed receipt/,
  );
  await save(join(state, 'restore-fence.json'), savedFence);
  assert.equal(delegatedStarts, 4);
  assert.equal(delegatedStops, 3);
  pass(
    'synthetic activation orchestration retains qualification fence; production rejects auxiliary receipt, wrong family activation ID and duplicate activation without valid actual receipt',
    {
      restoreId: original.restoreId,
      activationId: activated.activationId,
      evidence: 'synthetic-lifecycle-callback; no real start or job loaded',
    },
  );
  const childDirs = await readdir(root);
  assert.equal(childDirs.includes('unused-socket'), false);
  for (const dir of [state, destination])
    assert.equal(
      await stat(join(dir, 'postgres/postmaster.pid')).then(
        () => true,
        () => false,
      ),
      false,
    );
  pass('no postmaster, socket, API, TLS, Worker or Bonjour started');
} catch (error) {
  results.push({
    scenario: 'offline native-tool acceptance',
    status: 'failed',
    reason: error.message,
  });
  process.exitCode = 1;
} finally {
  globalThis.fetch = originalFetch;
  if (realBundle && realManifest) {
    try {
      const finalManifest = await verifyBundle(realBundle);
      assert.equal(finalManifest.payloadSha256, expectedPayloadSha256);
      assert.equal(finalManifest.sourceSha256, expectedSourceSha256);
      assert.equal(await shaFile(join(realBundle, 'manifest.json')), originalBundleManifestSha256);
      artifactUnchanged = true;
      pass(
        'original full frozen artifact inventory and manifest bytes unchanged after all AUX cases',
      );
    } catch (error) {
      artifactUnchanged = false;
      process.exitCode = 1;
      results.push({
        scenario: 'immutable real frozen artifact final check',
        status: 'failed',
        reason: error.message,
      });
    }
  }
  const report = {
    taskId: 'agent-hub-ios-task-management-20261014-F08',
    runId,
    finishedAt: new Date().toISOString(),
    category: 'auxiliary-real-postgresql-single-user-tools; synthetic-service-host-metadata',
    node: process.version,
    platform: process.platform,
    architecture: process.arch,
    originalMigrationSha256: sources,
    canonicalToolSha256: canonicalTools,
    realArtifact: realManifest
      ? {
          bundle: realBundle,
          payloadSha256: realManifest.payloadSha256,
          sourceSha256: realManifest.sourceSha256,
          manifestSha256: originalBundleManifestSha256,
          fullInventoryVerified: true,
          unchanged: artifactUnchanged,
        }
      : null,
    pgTools: { initdb, pgCtl, postgres },
    businessFactory: factoryManifest
      ? {
          bundle: factoryBundle,
          payloadSha256: factoryManifest.payloadSha256,
          runtimeSha256:
            factoryManifest.files['runtime/dist/notifications/business-notification.factory.js']
              .sha256,
          businessCryptoSha256: factoryManifest.businessCrypto.sha256,
          keySha256: notificationKeySha256,
          networkRequests,
        }
      : null,
    backup: backupResult,
    results,
    notVerified: [
      'installed LaunchAgent startup/crash/login/reboot recovery',
      'actual primary restore-activate/default-autostart (activation orchestration uses synthetic callbacks)',
      'live R2 or original primary restore',
      'iOS/Electron/model acceptance',
      'cross-machine primary migration',
      'packaging by parent',
    ],
    liveServicesTouched: false,
    businessListenersOpened: false,
    portAvailabilityProbesUsed: true,
  };
  await save(join(root, 'report.json'), report);
  console.log(
    JSON.stringify({
      report: join(root, 'report.json'),
      passed: results.filter((r) => r.status === 'passed').length,
      failed: results.filter((r) => r.status === 'failed').length,
    }),
  );
}
