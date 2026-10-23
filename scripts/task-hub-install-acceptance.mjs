import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createRequire } from 'node:module';
import { readFile, stat } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { request } from 'node:https';
import { fileURLToPath } from 'node:url';
import { execute } from '../skills/codex-host-connect/scripts/task-hub-service.mjs';
import {
  acquireLock,
  discover,
  exists,
  hash,
  json,
  ownedProcess,
  probe,
  save,
  verifyBundle,
  verifyRegistration,
} from '../skills/codex-host-connect/scripts/task-hub-core.mjs';

// Actual packaged API/PostgreSQL installation regression. No shared state,
// production service or existing Worker is used as this acceptance process.
const root = fileURLToPath(new URL('..', import.meta.url));
const at = process.argv.indexOf('--bundle');
if (at < 0) throw Error('Supply --bundle <frozen Task Hub artifact>');
if (!process.argv.includes('--confirmed-isolated-primary-window'))
  throw Error(
    'Parent must confirm no second-primary conflict before --confirmed-isolated-primary-window',
  );
const lifecycle = process.argv.includes('--autostart-lifecycle');
const loopbackLifecycle = process.argv.includes('--loopback-lifecycle');
if (loopbackLifecycle && !lifecycle)
  throw Error('Loopback lifecycle requires explicit --autostart-lifecycle');
const bundle = resolve(process.argv[at + 1]),
  runId = randomUUID();
const state = join(root, '.local/task-hub-install-acceptance', runId, 'service');
const executorState = join(root, '.local/task-hub-install-acceptance', runId, 'executor');
const hostRuntime = join(root, '.local/desktop-runtime/runtime.json');
const hostId = (await json(hostRuntime)).hostId;
const manifest = await verifyBundle(bundle);
const beforeHostId = hostId;
const results = [];
let started = false;
const result = (scenario, evidence) => {
  results.push({ scenario, status: 'passed', ...evidence });
  console.log(JSON.stringify({ scenario, status: 'passed', ...evidence }));
};
const networkFailure = (scenario, error) => {
  if (!loopbackLifecycle) throw error;
  results.push({ scenario, status: 'failed', reason: error.message });
  console.log(JSON.stringify({ scenario, status: 'failed', reason: error.message }));
};
async function canonicalProbe(registration) {
  const deadline = Date.now() + 15000;
  let last;
  while (Date.now() < deadline) {
    try {
      return await probe(registration);
    } catch (error) {
      last = error;
    }
    await new Promise((res) => setTimeout(res, 200));
  }
  throw Error('Registered canonical HTTPS probe did not verify within 15s', { cause: last });
}
async function recovered(previousNonce) {
  const deadline = Date.now() + 45000;
  while (Date.now() < deadline) {
    const ready = await json(join(state, 'ready.json'), true).catch(() => null);
    if (
      ready &&
      ready.nonce !== previousNonce &&
      (await execute({ action: 'status', state })).runtimeHealthy
    )
      return;
    await new Promise((res) => setTimeout(res, 200));
  }
  throw Error('Actual isolated LaunchAgent crash recovery did not become healthy');
}
function call(registration, path, { method = 'GET', headers = {}, data } = {}) {
  return new Promise((res, rej) => {
    const url = new URL(path, registration.endpoints[0]);
    const req = request(
      url,
      {
        hostname: '127.0.0.1',
        servername: registration.domain,
        ca: registration.certificatePem,
        rejectUnauthorized: true,
        method,
        headers: { host: url.host, ...headers },
        timeout: 6000,
      },
      (response) => {
        let body = '';
        response.on('data', (b) => {
          body += b;
        });
        response.on('end', () =>
          res({ status: response.statusCode, headers: response.headers, body }),
        );
      },
    );
    req.on('error', rej);
    req.on('timeout', () => req.destroy(Error('acceptance-timeout')));
    req.end(data);
  });
}
try {
  const options = {
    action: 'install',
    bundle,
    state,
    'first-primary': true,
    'isolated-dev': true,
    'disable-worker': true,
    'no-autostart': !lifecycle,
    'deployment-id': 'install-dev-' + runId,
    'instance-id': 'instance-' + runId,
    'primary-host-id': hostId,
    'host-runtime': hostRuntime,
    name: 'Task Hub Isolated Install QA',
    'tls-port': '19643',
    'api-port': '19644',
    'pg-port': '19645',
    'legacy-api-origin': 'http://127.0.0.1:19646',
  };
  const installed = await execute(options);
  assert.equal(installed.role, 'primary');
  const secretBefore = hash(await readFile(join(state, 'env.json')));
  const trust = await json(join(state, 'trust.json'), true);
  const registration = verifyRegistration(
    await json(join(state, 'deployment.json')),
    trust.registryKeySha256,
  );
  assert.equal((await stat(state)).mode & 0o777, 0o700);
  assert.equal((await stat(join(state, 'env.json'))).mode & 0o777, 0o600);
  result('dedicated primary installation, immutable candidate and private permissions', {
    payloadSha256: manifest.payloadSha256,
  });
  started = true;
  await execute({ action: 'start', state, 'no-autostart': !lifecycle });
  const runningStatus = await execute({ action: 'status', state });
  const initialReady = await json(join(state, 'ready.json'), true);
  const initialResources = await json(join(state, 'resources.json'), true);
  const pgPid = Number(
    (await readFile(join(state, 'postgres/postmaster.pid'), 'utf8')).split('\n')[0],
  );
  assert.equal(runningStatus.runtimeHealthy, true);
  assert.equal(runningStatus.worker.enabled, false);
  assert.equal(runningStatus.worker.active, false);
  if (lifecycle) {
    assert.equal(runningStatus.autostart.configured, true);
    assert.equal(runningStatus.autostart.loaded, true);
    assert.equal(runningStatus.autostart.disabled, false);
    assert.equal(runningStatus.loginAutostart, true);
    result(
      'actual matching plist, loaded enabled LaunchAgent and healthy runtime; Worker disabled',
      {
        runtimePid: initialReady.pid,
        apiPid: initialResources.apiPid,
        postgresPid: pgPid,
        runtimeNonce: initialReady.nonce,
      },
    );
  }
  try {
    assert.equal((await canonicalProbe(registration)).backendReady, true);
  } catch (error) {
    networkFailure('actual registered-name HTTPS path', error);
  }
  assert.equal((await probe(registration, { connectHost: '127.0.0.1' })).backendReady, true);
  result('actual HTTPS canonical hostname, API service identity and PostgreSQL readiness', {
    endpoint: registration.endpoints[0],
    connectionPath: 'owned loopback with canonical Host/SNI and exact certificate',
  });
  try {
    const discovered = await discover(bundle, 2200);
    assert.ok(discovered.some((d) => d.serverInstanceId === registration.serverInstanceId));
    result('actual Bonjour public discovery', { credentialsAdvertised: false });
  } catch (error) {
    networkFailure('actual Bonjour public discovery', error);
  }
  const env = await json(join(state, 'env.json'), true);
  const { Client } = createRequire(join(bundle, 'runtime/package.json'))('pg');
  const db = new Client({ connectionString: env.DATABASE_URL });
  await db.connect();
  try {
    const role = (
      await db.query(
        'SELECT rolsuper,rolcreatedb,rolcreaterole FROM pg_roles WHERE rolname=current_user',
      )
    ).rows[0];
    assert.deepEqual(role, { rolsuper: false, rolcreatedb: false, rolcreaterole: false });
    const lock = (await db.query('SELECT pg_try_advisory_lock(84311322) AS acquired')).rows[0]
      .acquired;
    assert.equal(lock, false);
    const listen = (await db.query('SHOW listen_addresses')).rows[0].listen_addresses;
    assert.equal(listen, '127.0.0.1');
    const migrations = (await db.query('SELECT count(*)::int AS count FROM taskhub_migrations'))
      .rows[0].count;
    assert.ok(migrations >= 6);
    result('actual restricted database role, loopback PostgreSQL, migrations and execution lock', {
      migrationCount: migrations,
    });
  } finally {
    await db.end();
  }
  const web = await call(registration, '/');
  assert.equal(web.status, 200);
  assert.match(web.body, /<html/);
  const login = await call(registration, '/api/v1/auth/login', {
    method: 'POST',
    headers: { 'content-type': 'application/json', origin: env.WEB_ORIGIN },
    data: JSON.stringify({ username: 'admin', password: env.BOOTSTRAP_PASSWORD }),
  });
  assert.equal(login.status, 200);
  const cookie = login.headers['set-cookie'][0].split(';')[0];
  assert.match(login.headers['set-cookie'][0], /Secure/);
  const projects = JSON.parse(
    (await call(registration, '/api/v1/projects', { headers: { cookie } })).body,
  );
  assert.ok(Array.isArray(projects) && projects.length);
  const actor = JSON.parse(login.body).actor;
  const actors = JSON.parse(
    (await call(registration, '/api/v1/actors', { headers: { cookie } })).body,
  );
  const reviewer = actors.find((value) => value.kind === 'human' && value.id !== actor.id);
  assert.ok(reviewer, 'an independent human reviewer is required');
  const task = await call(registration, '/api/v1/tasks', {
    method: 'POST',
    headers: { cookie, origin: env.WEB_ORIGIN, 'content-type': 'application/json' },
    data: JSON.stringify({
      projectId: projects[0].id,
      title: 'Isolated installation persistence check',
      description: 'Only this dedicated database is used.',
      acceptanceCriteria: 'Task survives Task Hub restart.',
      type: 'chore',
      priority: 'P3',
      ownerId: actor.id,
      acceptanceMode: 'human',
      reviewerId: reviewer.id,
    }),
  });
  assert.ok([200, 201].includes(task.status), 'actual packaged task create succeeds');
  const taskId = JSON.parse(task.body).task?.id ?? JSON.parse(task.body).id;
  assert.equal(typeof taskId, 'string');
  result('actual built Web, secure same-origin login and task persistence input', {
    taskId,
    productionDataUsed: false,
  });
  if (lifecycle) {
    const beforeApiCrash = await json(join(state, 'ready.json'), true);
    const children = await json(join(state, 'resources.json'), true);
    assert.equal(
      children.entry,
      join((await json(join(state, 'runtime.json'), true)).bundle, 'runtime/dist/main.js'),
    );
    assert.equal(ownedProcess(children.apiPid, children.entry), true);
    process.kill(children.apiPid, 'SIGKILL');
    await recovered(beforeApiCrash.nonce);
    assert.equal(
      (await call(registration, '/api/v1/tasks/' + taskId, { headers: { cookie } })).status,
      200,
    );
    const afterApiCrash = await json(join(state, 'ready.json'), true);
    result('actual dedicated API crash; LaunchAgent restores same identity, task and session', {
      killedApiPid: children.apiPid,
      oldRuntimePid: beforeApiCrash.pid,
      newRuntimePid: afterApiCrash.pid,
      oldNonce: beforeApiCrash.nonce,
      newNonce: afterApiCrash.nonce,
      newApiPid: (await json(join(state, 'resources.json'), true)).apiPid,
    });
    const beforeRuntimeCrash = await json(join(state, 'ready.json'), true);
    assert.equal(
      ownedProcess(
        beforeRuntimeCrash.pid,
        join((await json(join(state, 'runtime.json'), true)).bundle, 'tools/task-hub-runtime.mjs'),
        state,
      ),
      true,
    );
    process.kill(beforeRuntimeCrash.pid, 'SIGKILL');
    await recovered(beforeRuntimeCrash.nonce);
    assert.equal(
      (await call(registration, '/api/v1/tasks/' + taskId, { headers: { cookie } })).status,
      200,
    );
    result(
      'actual dedicated runtime crash; orphan API and private PG recover without duplicate primary',
      {
        killedRuntimePid: beforeRuntimeCrash.pid,
        oldNonce: beforeRuntimeCrash.nonce,
        newNonce: (await json(join(state, 'ready.json'), true)).nonce,
        newRuntimePid: (await json(join(state, 'ready.json'), true)).pid,
        newApiPid: (await json(join(state, 'resources.json'), true)).apiPid,
      },
    );
  }
  await assert.rejects(acquireLock(join(state, 'instance.lock')), /already running/);
  const reused = await execute(options);
  assert.equal(reused.action, 'reused');
  assert.equal(hash(await readFile(join(state, 'env.json'))), secretBefore);
  result(
    'repeat install reuses identity, credentials, ports and database; duplicate instance refused',
    {},
  );
  try {
    await execute({
      action: 'executor',
      state: executorState,
      bundle,
      registry: join(state, 'deployment.json'),
      'trust-key': trust.registryKeySha256,
    });
    assert.equal(await exists(join(executorState, 'postgres')), false);
    result('actual executor HTTPS binding creates no database or API', {});
  } catch (error) {
    networkFailure('actual registered-name executor binding', error);
  }
  await execute({ action: 'stop', state });
  started = false;
  assert.equal((await execute({ action: 'status', state })).processRunning, false);
  await execute({ action: 'upgrade', state, bundle });
  started = true;
  await execute({ action: 'start', state, 'no-autostart': !lifecycle });
  assert.equal(hash(await readFile(join(state, 'env.json'))), secretBefore);
  const after = await call(registration, '/api/v1/tasks/' + taskId, { headers: { cookie } });
  assert.equal(after.status, 200);
  assert.equal(JSON.parse(after.body).task?.id ?? JSON.parse(after.body).id, taskId);
  result('explicit stop, same-instance upgrade/start and real stored task/session retained', {});
  await assert.rejects(
    execute({ ...options, 'primary-host-id': 'other-primary' }),
    /stable identity/,
  );
  await execute({ action: 'stop', state });
  started = false;
  const backupKey = join(dirnameForReport(state), 'keys/backup.key');
  const archive = join(dirnameForReport(state), 'backups/service.enc');
  const cold = await execute({
    action: 'backup',
    state,
    output: archive,
    'key-file': backupKey,
    'create-key': true,
  });
  const offline = await execute({
    action: 'restore',
    state,
    archive,
    'key-file': backupKey,
    offline: true,
    destination: join(dirnameForReport(state), 'offline-restore'),
    bundle,
  });
  assert.equal(offline.archiveSha256, cold.archiveSha256);
  assert.equal(offline.serviceStarted, false);
  result('actual stopped packaged primary cold backup and offline native SQL restore', {
    archiveSha256: cold.archiveSha256,
  });
  const restored = await execute({
    action: 'restore',
    state,
    archive,
    'key-file': backupKey,
    'in-place': true,
    bundle,
  });
  const resume = {
    action: 'restore-activate',
    state,
    archive,
    'key-file': backupKey,
    'restore-id': restored.restoreId,
    'no-autostart': !lifecycle,
  };
  const verify = await execute({ ...resume, 'verify-only': true });
  assert.equal(verify.fenceRetained, true);
  assert.equal(verify.serviceStarted, false);
  started = true;
  const activated = await execute(resume);
  assert.equal(activated.phase, 'activated');
  assert.equal(activated.evidence, 'actual-normal-start');
  assert.equal((await execute({ action: 'status', state })).runtimeHealthy, true);
  const restoredTask = await call(registration, '/api/v1/tasks/' + taskId, { headers: { cookie } });
  assert.equal(restoredTask.status, 200);
  assert.equal(JSON.parse(restoredTask.body).task?.id ?? JSON.parse(restoredTask.body).id, taskId);
  result(
    'actual original-primary restore-activate resumes normal startup, task/session and selected user autostart',
    {
      restoreId: restored.restoreId,
      activationId: activated.activationId,
      archiveSha256: cold.archiveSha256,
    },
  );
  await execute({ action: 'stop', state });
  started = false;
  await assert.rejects(
    execute({
      action: 'executor',
      state: executorState,
      bundle,
      registry: join(state, 'deployment.json'),
      'trust-key': trust.registryKeySha256,
    }),
    /HTTPS identity/,
  );
  assert.equal(await exists(join(executorState, 'postgres')), false);
  result('primary identity change and offline executor fallback refuse; no second database', {});
  assert.equal((await json(hostRuntime)).hostId, beforeHostId);
} catch (error) {
  results.push({ scenario: 'installation regression', status: 'failed', reason: error.message });
  process.exitCode = 1;
} finally {
  if (started) {
    try {
      await execute({ action: 'stop', state });
    } catch {
      results.push({ scenario: 'dedicated service cleanup', status: 'failed' });
      process.exitCode = 1;
    }
  }
  const report = {
    taskId: 'agent-hub-ios-task-management-20261014',
    runId,
    finishedAt: new Date().toISOString(),
    node: process.version,
    platform: process.platform,
    architecture: process.arch,
    state,
    payloadSha256: manifest.payloadSha256,
    sourceSha256: manifest.sourceSha256,
    results,
    worker: 'disabled-only-for-this-installation-test',
    validationProfile: loopbackLifecycle
      ? 'explicit-loopback-lifecycle; network failures remain failed'
      : 'full registered-name and discovery',
    oldDatabaseMigration: 'not-performed',
    sharedHostRestarted: false,
    sharedWorkerStopped: false,
    notVerified: [
      'iPhone/Electron/model business loop',
      'Windows executor',
      'login/reboot recovery',
      'primary migration',
    ],
  };
  if (lifecycle)
    report.notVerified.push(
      'physical login/reboot recovery (loaded job and crash recovery are separate evidence)',
    );
  else report.notVerified.push('LaunchAgent autostart and API/runtime crash recovery');
  const path = join(dirnameForReport(state), 'installation-report.json');
  await save(path, report);
  if (results.some((r) => r.status === 'failed')) process.exitCode = 1;
  console.log(
    JSON.stringify({
      report: path,
      payloadSha256: manifest.payloadSha256,
      passed: results.filter((r) => r.status === 'passed').length,
      failed: results.filter((r) => r.status === 'failed').length,
    }),
  );
}
function dirnameForReport(state) {
  return resolve(state, '..');
}
