import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createPrivateKey, createPublicKey, randomUUID, X509Certificate } from 'node:crypto';
import { lstat, readFile, writeFile } from 'node:fs/promises';
import { createServer, request } from 'node:https';
import { join, resolve } from 'node:path';
import { checkServerIdentity } from 'node:tls';
import { fileURLToPath } from 'node:url';
import {
  taskHubAcceptanceState,
  taskHubAcceptanceOutput,
  taskHubAcceptanceReport,
} from './task-hub-acceptance-state.mjs';
import {
  alive,
  hash,
  json,
  privateFile,
  probe,
  sameIdentity,
  save,
  verifyBundle,
  verifyRegistration,
} from '../skills/codex-host-connect/scripts/task-hub-core.mjs';

// Only a transport fault harness. Every business response comes from the registered
// primary HTTPS gateway. No API fixture, database, worker, service restart or second backend.
// Parent integration: --scenario network --endpoint <same-domain:19546>, fixed
// TaskHubNetworkUITests/testTaskHubRealUnknownAndEndWaitingDEV selector, TEST_RUNNER_
// TASK_HUB_NETWORK_{RUN_ID,CERT_SHA256,SCENARIO} forwarding, and PBX target inclusion.
const repository = fileURLToPath(new URL('..', import.meta.url));
const args = process.argv.slice(2);
function option(name) {
  const index = args.indexOf('--' + name);
  if (index < 0) return undefined;
  const value = args[index + 1];
  if (!value || value.startsWith('--')) throw Error('Missing --' + name + ' value');
  return value;
}
const allowed = new Set(['--state', '--device', '--physical', '--output-root']);
for (let index = 0; index < args.length; index++) {
  if (!allowed.has(args[index])) throw Error('Unknown network acceptance option');
  if (args[index] !== '--physical') index++;
}
const state = resolve(option('state') ?? join(repository, '.local/task-management/service'));
const device = option('device');
if (!device || !/^[A-Za-z0-9-]{10,80}$/.test(device))
  throw Error(
    'Use --device <dedicated UDID> [--physical] [--state .local/task-management/service]',
  );
const physical = args.includes('--physical');
const runId = randomUUID();
const root = await taskHubAcceptanceOutput(repository, option('output-root'), 'network-acceptance');
const report = {
  taskId: 'agent-hub-ios-task-management-20261014',
  kind: physical ? 'physical-native-task-network' : 'simulator-native-task-network',
  runId,
  device,
  startedAt: new Date().toISOString(),
  phase: 'preflight',
  businessTask: { completion: 'not-verified', finalizationAttempted: false },
  checks: [],
  limitations: [
    'Only actual human progress writes and recovery are covered; no model, attachment, acceptance or finalization pass is claimed.',
    'The fault task intentionally remains uncompleted in its own new DEV project.',
    'Transport faults apply only to two exact run-scoped progress bodies; every business request is forwarded to the same primary.',
  ],
};
const operations = new Map();
let externalProgress;
let externalReporter;
let revokeExternalReporter;
const wires = [];
const sockets = new Set();
const upstreams = new Set();
const timers = new Set();
let evidenceWrites = Promise.resolve();
let server, child;
let aborted = false;
const now = () => new Date().toISOString();
const bodyLimit = 2 * 1024 * 1024;
const headerDenylist = new Set([
  'connection',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
]);
const filteredHeaders = (headers) =>
  Object.fromEntries(
    Object.entries(headers).filter(
      ([name]) => !headerDenylist.has(name) && !name.startsWith('x-forwarded-'),
    ),
  );
function publicOperations() {
  return [...operations.values()].map((entry) => ({
    mode: entry.mode,
    operationId: entry.operationId,
    taskId: entry.taskId,
    backendStatus: entry.backendStatus,
    backendCommittedAt: entry.backendCommittedAt,
    downstreamClosedAt: entry.downstreamClosedAt,
    requestCount: entry.requestCount,
    receiptQueries: entry.receiptQueries,
    bodySha256: entry.bodySha256,
    progressCountAtCommit: entry.progressCountAtCommit,
    receiptConfirmedAt: entry.receiptConfirmedAt,
    recoveredDetailReads: entry.recoveredDetailReads,
    firstRecoveredProgressCount: entry.firstRecoveredProgressCount,
    recoveredSSEConnections: entry.recoveredSSEConnections,
  }));
}
function publicExternalProgress() {
  if (!externalProgress) return null;
  const entry = externalProgress;
  return {
    operationId: entry.operationId,
    taskId: entry.taskId,
    reporterDeviceId: entry.reporterDeviceId,
    requestCount: entry.requestCount,
    bodySha256: entry.bodySha256,
    backendStatus: entry.backendStatus,
    backendCommittedAt: entry.backendCommittedAt,
    committedRevision: entry.committedRevision,
    actualProgressCount: entry.actualProgressCount,
    nativeSSEFrames: entry.nativeSSEFrames,
    firstNativeSSEAt: entry.firstNativeSSEAt,
    nativeDetailReads: entry.nativeDetailReads,
    firstNativeDetailAt: entry.firstNativeDetailAt,
    firstNativeProgressCount: entry.firstNativeProgressCount,
  };
}
function checkpoint() {
  const snapshot = {
    runId,
    operations: publicOperations(),
    externalProgress: publicExternalProgress(),
    wires: [...wires],
  };
  evidenceWrites = evidenceWrites.then(() => save(join(root, 'wire-evidence.json'), snapshot));
  return evidenceWrites;
}
const safeError = (error) => {
  // HTTP bodies, authentication headers and environment values are never printed.
  const code = error?.code;
  return typeof code === 'string' && /^[A-Z0-9_]{1,60}$/.test(code)
    ? code
    : error?.name === 'AssertionError'
      ? 'EVIDENCE_ASSERTION_FAILED'
      : 'NETWORK_ACCEPTANCE_FAILED';
};
function track(req) {
  upstreams.add(req);
  req.once('close', () => upstreams.delete(req));
  return req;
}
function shutdownSignal() {
  aborted = true;
  if (child && child.exitCode === null) child.kill('SIGTERM');
  for (const socket of sockets) socket.destroy();
  for (const req of upstreams) req.destroy();
}
process.once('SIGINT', shutdownSignal);
process.once('SIGTERM', shutdownSignal);

try {
  // Use the explicitly selected existing DEV installation from an isolated checkout.
  // Do not copy state or relax the registration/live identity/certificate checks below.
  assert.equal(await taskHubAcceptanceState(repository, option('state')), state);
  const config = await json(join(state, 'runtime.json'), true);
  const env = await json(join(state, 'env.json'), true);
  const ready = await json(join(state, 'ready.json'), true);
  const trust = await json(join(state, 'trust.json'), true);
  const registry = verifyRegistration(
    await json(join(state, 'deployment.json')),
    trust.registryKeySha256,
  );
  const manifest = await verifyBundle(config.bundle);
  assert.equal(config.role, 'primary');
  assert.ok(config.deploymentId?.includes('dev'));
  assert.equal(config.tlsPort, 19543);
  assert.ok(sameIdentity(config, registry) && sameIdentity(config, ready.identity));
  assert.equal(manifest.payloadSha256, config.payloadSha256);
  assert.equal(ready.payloadSha256, config.payloadSha256);
  assert.ok(alive(ready.pid), 'The parent-owned DEV runtime must already be running');
  const primary = new URL(registry.endpoints[0]);
  assert.equal(primary.hostname, registry.domain);
  assert.equal(primary.port, '19543');
  assert.equal(primary.origin, env.WEB_ORIGIN);
  assert.ok(typeof env.BOOTSTRAP_PASSWORD === 'string' && env.BOOTSTRAP_PASSWORD.length > 0);
  const health = await probe(registry, { connectHost: '127.0.0.1' });
  assert.ok(health.identityVerified && health.backendReady);
  const certPath = join(state, 'tls-cert.pem');
  const keyPath = join(state, 'tls-key.pem');
  await privateFile(certPath);
  await privateFile(keyPath);
  assert.equal((await lstat(certPath)).mode & 0o777, 0o600);
  assert.equal((await lstat(keyPath)).mode & 0o777, 0o600);
  const cert = await readFile(certPath);
  const key = await readFile(keyPath);
  const certificate = new X509Certificate(cert);
  assert.equal(hash(certificate.raw), registry.certificateSha256);
  assert.equal(hash(new X509Certificate(registry.certificatePem).raw), registry.certificateSha256);
  assert.equal(certificate.checkHost(registry.domain), registry.domain);
  assert.ok(certificate.checkPrivateKey(createPrivateKey(key)));
  assert.deepEqual(
    createPublicKey(createPrivateKey(key)).export({ format: 'der', type: 'spki' }),
    certificate.publicKey.export({ format: 'der', type: 'spki' }),
  );
  const proxy = new URL(primary);
  proxy.port = '19546';
  const proxyOrigin = proxy.origin;
  const controlPath = '/__taskhub_network/' + runId + '/status';
  const base = join(repository, 'scripts/task-hub-ios-acceptance.mjs');
  const baseText = await readFile(base, 'utf8');
  for (const requirement of [
    'scenario',
    'TaskHubNetworkUITests',
    'testTaskHubRealUnknownAndEndWaitingDEV',
    'TASK_HUB_NETWORK_RUN_ID',
    'TASK_HUB_NETWORK_CERT_SHA256',
    'TASK_HUB_NETWORK_SCENARIO',
  ])
    assert.ok(
      baseText.includes(requirement),
      'Parent base runner integration is still required: ' + requirement,
    );
  const nativePath = 'apps/ios/Tests/TaskHubNetworkUITests.swift';
  const runnerPath = 'scripts/task-hub-ios-network-acceptance.mjs';
  report.harness = Object.fromEntries(
    await Promise.all(
      [nativePath, runnerPath, 'scripts/task-hub-ios-acceptance.mjs'].map(async (path) => [
        path,
        hash(await readFile(join(repository, path))),
      ]),
    ),
  );
  Object.assign(report, {
    endpoint: proxyOrigin,
    primaryEndpoint: primary.origin,
    deploymentId: config.deploymentId,
    serverInstanceId: config.serverInstanceId,
    primaryHostId: config.primaryHostId,
    payloadSha256: config.payloadSha256,
    sourceSha256: manifest.sourceSha256,
    certificateSha256: registry.certificateSha256,
    registryKeySha256: trust.registryKeySha256,
  });
  report.checks.push({
    scenario: 'registered live primary, complete frozen payload, CA and exact certificate pin',
    phase: 'passed',
  });

  // Direct evidence reads use the same mobile session/device as each observed write.
  function actual(path, { method = 'GET', headers = {}, body } = {}) {
    return new Promise((accept, reject) => {
      const req = track(
        request(
          {
            hostname: '127.0.0.1',
            port: 19543,
            servername: registry.domain,
            ca: registry.certificatePem,
            rejectUnauthorized: true,
            checkServerIdentity: (host, peer) =>
              checkServerIdentity(host, peer) ||
              (hash(peer.raw) === registry.certificateSha256
                ? undefined
                : Error('TLS pin mismatch')),
            method,
            path,
            timeout: 8000,
            headers: { accept: 'application/json', host: primary.host, ...headers },
          },
          (response) => {
            const parts = [];
            let size = 0;
            response.on('data', (part) => {
              size += part.length;
              if (size > bodyLimit) req.destroy(Error('Evidence response is oversized'));
              else parts.push(part);
            });
            response.on('error', reject);
            response.on('end', () =>
              accept({ status: response.statusCode, bytes: Buffer.concat(parts) }),
            );
          },
        ),
      );
      req.on('timeout', () => req.destroy(Error('Evidence request timed out')));
      req.on('error', reject);
      req.end(body);
    });
  }
  revokeExternalReporter = async () => {
    if (!externalReporter?.auth) return null;
    const reply = await actual('/api/v1/auth/mobile/logout', {
      method: 'POST',
      headers: { ...externalReporter.auth, 'content-type': 'application/json' },
      body: Buffer.from('{}'),
    });
    assert.equal(reply.status, 200);
    return reply.status;
  };
  async function markCommitted(entry, status, bytes) {
    entry.backendStatus = status;
    if (status < 200 || status >= 300) return;
    const detail = JSON.parse(bytes);
    assert.equal(detail.id, entry.taskId);
    assert.ok(
      detail.events?.some(
        (event) => event.kind === 'progress.reported' && event.body === entry.body.body,
      ),
    );
    entry.progressCountAtCommit = detail.events.filter(
      (event) => event.kind === 'progress.reported',
    ).length;
    assert.equal(entry.progressCountAtCommit, entry.mode === 'drop' ? 1 : 2);
    entry.projectId = detail.projectId;
    entry.ownerId = detail.ownerId;
    report.businessTask = {
      id: detail.id,
      projectId: detail.projectId,
      status: detail.status,
      workflowStage: detail.workflowStage,
      completion: detail.status === 'done' ? 'completed' : 'uncompleted',
      finalizationAttempted: false,
      actualProgressCount: entry.progressCountAtCommit,
    };
    entry.backendCommittedAt = now();
    entry.responseSha256 = hash(bytes);
    await save(join(root, 'operations', entry.operationId + '.json'), {
      operationId: entry.operationId,
      method: 'POST',
      path: entry.path,
      body: entry.body,
      bodySha256: entry.bodySha256,
      backendStatus: status,
      backendResponseSha256: entry.responseSha256,
      backendCommittedAt: entry.backendCommittedAt,
      progressCountAtCommit: entry.progressCountAtCommit,
    });
    await save(join(root, 'operations', entry.operationId + '-backend-response.json'), detail);
    await checkpoint();
  }
  async function markExternalCommitted(entry, status, bytes) {
    entry.backendStatus = status;
    assert.equal(status, 201);
    const detail = JSON.parse(bytes);
    assert.equal(detail.id, entry.taskId);
    assert.equal(detail.projectId, entry.projectId);
    assert.equal(detail.ownerId, externalReporter.actorId);
    assert.equal(detail.status, 'doing');
    assert.equal(detail.workflowStage, 'development');
    assert.equal(detail.currentRound, 1);
    assert.equal(detail.revision, entry.body.revision + 1);
    const events = detail.events.filter((event) => event.kind === 'progress.reported');
    assert.equal(events.length, 3);
    for (const fault of operations.values())
      assert.equal(events.filter((event) => event.body === fault.body.body).length, 1);
    const event = events.filter((event) => event.body === entry.body.body);
    assert.equal(event.length, 1);
    assert.equal(event[0].actorId, externalReporter.actorId);
    entry.eventId = event[0].id;
    entry.committedRevision = detail.revision;
    entry.actualProgressCount = events.length;
    entry.backendCommittedAt = now();
    await save(join(root, 'operations', entry.operationId + '-external-progress.json'), {
      operationId: entry.operationId,
      taskId: entry.taskId,
      reporterDeviceId: entry.auth['x-taskhub-device-id'],
      body: entry.body,
      bodySha256: entry.bodySha256,
      backendStatus: status,
      backendResponseSha256: hash(bytes),
      committedRevision: detail.revision,
      backendCommittedAt: entry.backendCommittedAt,
      actualProgressCount: events.length,
    });
    await save(join(root, 'operations', entry.operationId + '-external-backend-task.json'), detail);
    await checkpoint();
  }
  function observeNativeSSE(response, headers, projectId) {
    // Read-only observation of the original bytes; response.pipe still forwards
    // them unchanged. Bound lines and frames, preserve UTF-8 and blank boundaries.
    let line = [],
      frameBytes = 0,
      data = [],
      eventId;
    let failed = false;
    const decoder = new TextDecoder('utf-8', { fatal: true });
    const limit = 64 * 1024;
    response.on('data', (part) => {
      if (failed) return;
      try {
        for (const byte of part) {
          if (++frameBytes > limit) throw Error('SSE_OBSERVATION_FRAME_TOO_LARGE');
          if (byte !== 10) {
            line.push(byte);
            continue;
          }
          if (line.at(-1) === 13) line.pop();
          const value = decoder.decode(Uint8Array.from(line));
          line = [];
          if (value) {
            if (value.startsWith('id:')) eventId = value.slice(3).trim();
            if (value.startsWith('data:')) data.push(value.slice(5).replace(/^ /, ''));
            continue;
          }
          if (data.length) {
            const change = JSON.parse(data.join('\n'));
            const entry = externalProgress;
            const native = [...operations.values()].some(
              (fault) =>
                fault.projectId === projectId &&
                headers.authorization === fault.auth.authorization &&
                headers['x-taskhub-device-id'] === fault.auth['x-taskhub-device-id'],
            );
            if (
              entry &&
              native &&
              entry.projectId === projectId &&
              change.taskId === entry.taskId &&
              change.revision === entry.body.revision + 1
            ) {
              assert.match(eventId ?? '', /^\d{1,16}$/);
              entry.nativeSSEFrames++;
              entry.firstNativeSSEAt ??= now();
              wires.push({
                kind: 'actual-native-external-progress-sse',
                at: now(),
                operationId: entry.operationId,
                taskId: entry.taskId,
                revision: change.revision,
                eventSequenceSha256: hash(eventId),
              });
              void checkpoint();
            }
          }
          data = [];
          eventId = undefined;
          frameBytes = 0;
        }
      } catch {
        failed = true;
        wires.push({ kind: 'native-sse-observation-error', at: now() });
        void checkpoint();
      }
    });
  }
  async function handle(incoming, outgoing) {
    const url = new URL(incoming.url, proxyOrigin);
    if (incoming.headers.host !== proxy.host) {
      outgoing.writeHead(421).end();
      return;
    }
    if (url.pathname.startsWith('/__taskhub_network/')) {
      if (incoming.method !== 'GET' || url.pathname !== controlPath || url.search) {
        outgoing.writeHead(404).end();
        return;
      }
      outgoing.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
      outgoing.end(
        JSON.stringify({
          runId,
          operations: publicOperations(),
          externalProgress: publicExternalProgress(),
        }),
      );
      return;
    }
    // Local harness clients never follow public endpoints or redirects.
    if (
      !['/health', '/api/v1/service'].includes(url.pathname) &&
      !url.pathname.startsWith('/api/v1/')
    ) {
      outgoing.writeHead(404).end();
      return;
    }
    const headers = filteredHeaders(incoming.headers);
    headers.host = primary.host;
    if (headers.origin === proxyOrigin) headers.origin = primary.origin;
    else if (headers.origin && headers.origin !== primary.origin) {
      outgoing.writeHead(403).end();
      return;
    }
    let operation;
    let externalWrite;
    let reporterLogin;
    let captured;
    const progressPath = /^\/api\/v1\/tasks\/([0-9a-f-]{36})\/progress$/i.exec(url.pathname);
    if (
      incoming.method === 'POST' &&
      (progressPath || url.pathname === '/api/v1/auth/mobile/login')
    ) {
      const parts = [];
      let size = 0;
      for await (const part of incoming) {
        size += part.length;
        if (size > bodyLimit) {
          outgoing.writeHead(413).end();
          return;
        }
        parts.push(part);
      }
      captured = Buffer.concat(parts);
      let body;
      try {
        body = JSON.parse(captured);
      } catch {
        outgoing.writeHead(400).end();
        return;
      }
      if (!progressPath && body.deviceName === 'native-network/external-sse:' + runId) {
        assert.equal(operations.size, 2);
        assert.ok(!externalReporter, 'This run may create only one independent reporter session');
        assert.match(
          body.deviceId ?? '',
          /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/i,
        );
        for (const fault of operations.values())
          assert.notEqual(body.deviceId, fault.auth['x-taskhub-device-id']);
        reporterLogin = { deviceId: body.deviceId };
      }
      const mode = ['drop', 'hold'].find(
        (candidate) =>
          typeof body.body === 'string' &&
          body.body.startsWith('native-network/' + candidate + ':' + runId + ' ·'),
      );
      if (mode) {
        const id = incoming.headers['x-taskhub-operation-id'];
        assert.match(
          id ?? '',
          /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/i,
        );
        assert.match(headers.authorization ?? '', /^Bearer thm_[A-Za-z0-9_-]{43}$/);
        assert.match(headers['x-taskhub-device-id'] ?? '', /^[0-9a-f-]{36}$/i);
        operation = operations.get(id);
        if (operation) {
          assert.equal(operation.mode, mode);
          assert.equal(operation.bodySha256, hash(captured));
          operation.requestCount++;
        } else {
          assert.ok(
            ![...operations.values()].some((entry) => entry.mode === mode),
            'A second intent for the same fault is a replay',
          );
          operation = {
            mode,
            operationId: id,
            taskId: progressPath[1],
            path: url.pathname,
            body,
            bodySha256: hash(captured),
            requestCount: 1,
            receiptQueries: 0,
            recoveredDetailReads: 0,
            recoveredSSEConnections: 0,
            // Secrets remain only in memory for exact actor/device receipt reads.
            auth: {
              authorization: headers.authorization,
              'x-taskhub-device-id': headers['x-taskhub-device-id'],
            },
          };
          operations.set(id, operation);
        }
        wires.push({
          kind: 'original-progress-request',
          at: now(),
          operationId: id,
          taskId: operation.taskId,
          mode,
          bodySha256: operation.bodySha256,
          requestOrdinal: operation.requestCount,
        });
        await checkpoint();
      }
      if (progressPath && body.body === 'native-network/external-sse:' + runId) {
        assert.equal(operations.size, 2);
        const faults = [...operations.values()];
        for (const fault of faults) {
          assert.equal(progressPath[1], fault.taskId);
          assert.ok(
            fault.receiptConfirmedAt &&
              fault.recoveredDetailReads > 0 &&
              fault.recoveredSSEConnections > 0,
          );
          assert.equal(fault.requestCount, 1);
          assert.equal(fault.firstRecoveredProgressCount, fault.mode === 'drop' ? 1 : 2);
        }
        assert.ok(externalReporter?.auth);
        assert.equal(headers.authorization, externalReporter.auth.authorization);
        assert.equal(headers['x-taskhub-device-id'], externalReporter.auth['x-taskhub-device-id']);
        const id = incoming.headers['x-taskhub-operation-id'];
        assert.match(
          id ?? '',
          /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/i,
        );
        assert.ok(!operations.has(id));
        assert.ok(Number.isSafeInteger(body.revision) && body.revision >= 1);
        if (externalProgress) {
          assert.equal(id, externalProgress.operationId);
          assert.equal(hash(captured), externalProgress.bodySha256);
          externalProgress.requestCount++;
        } else {
          externalProgress = {
            operationId: id,
            taskId: progressPath[1],
            projectId: faults[0].projectId,
            body,
            bodySha256: hash(captured),
            auth: externalReporter.auth,
            reporterDeviceId: externalReporter.auth['x-taskhub-device-id'],
            requestCount: 1,
            nativeSSEFrames: 0,
            nativeDetailReads: 0,
          };
        }
        externalWrite = externalProgress;
        wires.push({
          kind: 'actual-external-progress-request',
          at: now(),
          operationId: id,
          taskId: externalWrite.taskId,
          bodySha256: externalWrite.bodySha256,
          requestOrdinal: externalWrite.requestCount,
        });
        await checkpoint();
      }
    }
    const receiptId = /^\/api\/v1\/operations\/([0-9a-f-]{36})$/i.exec(url.pathname)?.[1];
    const queried = incoming.method === 'GET' ? operations.get(receiptId) : undefined;
    if (queried) {
      assert.equal(headers.authorization, queried.auth.authorization);
      assert.equal(headers['x-taskhub-device-id'], queried.auth['x-taskhub-device-id']);
      queried.receiptQueries++;
      wires.push({ kind: 'native-original-receipt-query', operationId: receiptId, at: now() });
      await checkpoint();
    }
    const inject = operation && operation.requestCount === 1;
    const matchingSession = (entry) =>
      headers.authorization === entry.auth.authorization &&
      headers['x-taskhub-device-id'] === entry.auth['x-taskhub-device-id'];
    const observedDetails =
      incoming.method === 'GET'
        ? [...operations.values()].filter(
            (entry) =>
              entry.receiptConfirmedAt &&
              url.pathname === '/api/v1/tasks/' + entry.taskId &&
              matchingSession(entry),
          )
        : [];
    const observedStreams =
      incoming.method === 'GET' && url.pathname === '/api/v1/events'
        ? [...operations.values()].filter(
            (entry) =>
              entry.receiptConfirmedAt &&
              url.searchParams.get('projectId') === entry.projectId &&
              matchingSession(entry),
          )
        : [];
    const externalRead =
      externalProgress &&
      externalProgress.firstNativeSSEAt &&
      incoming.method === 'GET' &&
      url.pathname === '/api/v1/tasks/' + externalProgress.taskId &&
      [...operations.values()].some(matchingSession)
        ? { entry: externalProgress, requestedAt: now() }
        : undefined;
    const req = track(
      request(
        {
          hostname: '127.0.0.1',
          port: 19543,
          servername: registry.domain,
          ca: registry.certificatePem,
          rejectUnauthorized: true,
          checkServerIdentity: (host, peer) =>
            checkServerIdentity(host, peer) ||
            (hash(peer.raw) === registry.certificateSha256 ? undefined : Error('TLS pin mismatch')),
          path: incoming.url,
          method: incoming.method,
          headers,
        },
        (response) => {
          if (
            response.statusCode === 200 &&
            incoming.method === 'GET' &&
            url.pathname === '/api/v1/events'
          )
            observeNativeSSE(response, headers, url.searchParams.get('projectId'));
          if (response.statusCode === 200) {
            for (const entry of observedStreams) {
              entry.recoveredSSEConnections++;
              wires.push({
                kind: 'actual-native-sse-after-receipt',
                operationId: entry.operationId,
                projectId: entry.projectId,
                status: response.statusCode,
                at: now(),
                cursorSha256:
                  typeof headers['last-event-id'] === 'string'
                    ? hash(headers['last-event-id'])
                    : null,
              });
            }
            if (observedStreams.length) void checkpoint();
          }
          if (
            !inject &&
            !queried &&
            observedDetails.length === 0 &&
            !externalRead &&
            !externalWrite &&
            !reporterLogin
          ) {
            outgoing.writeHead(response.statusCode, filteredHeaders(response.headers));
            outgoing.flushHeaders();
            response.pipe(outgoing);
            response.on('error', () => outgoing.destroy());
            return;
          }
          const parts = [];
          let size = 0;
          response.on('data', (part) => {
            size += part.length;
            if (size > bodyLimit) req.destroy(Error('Observed response is oversized'));
            else parts.push(part);
          });
          response.on('error', () => outgoing.destroy());
          response.on('end', () => {
            void (async () => {
              const bytes = Buffer.concat(parts);
              if (reporterLogin) {
                report.externalReporterLoginStatus = response.statusCode;
                assert.equal(response.statusCode, 200);
                const session = JSON.parse(bytes);
                assert.match(session.token ?? '', /^thm_[A-Za-z0-9_-]{43}$/);
                externalReporter = {
                  actorId: session.actor?.id,
                  auth: {
                    authorization: 'Bearer ' + session.token,
                    'x-taskhub-device-id': reporterLogin.deviceId,
                  },
                };
                assert.equal(session.actor?.kind, 'human');
                assert.equal(session.actor?.id, [...operations.values()][0].ownerId);
                wires.push({
                  kind: 'actual-independent-human-reporter-login',
                  at: now(),
                  actorId: session.actor.id,
                  reporterDeviceId: reporterLogin.deviceId,
                  status: response.statusCode,
                });
                await checkpoint();
              }
              if (externalWrite)
                await markExternalCommitted(externalWrite, response.statusCode, bytes);
              if (queried) {
                const receipt = JSON.parse(bytes);
                if (
                  response.statusCode === 200 &&
                  receipt.operationId === receiptId &&
                  receipt.phase === 'confirmed'
                )
                  queried.receiptConfirmedAt ??= now();
                wires.push({
                  kind: 'actual-native-receipt-response',
                  operationId: receiptId,
                  status: response.statusCode,
                  phase: receipt.phase,
                  at: now(),
                });
                await save(join(root, 'operations', receiptId + '-native-receipt.json'), receipt);
                await checkpoint();
              }
              if (observedDetails.length && response.statusCode === 200) {
                const detail = JSON.parse(bytes);
                for (const entry of observedDetails) {
                  assert.equal(detail.id, entry.taskId);
                  const count = detail.events.filter(
                    (event) => event.kind === 'progress.reported',
                  ).length;
                  if (entry.firstRecoveredProgressCount === undefined) {
                    entry.firstRecoveredProgressCount = count;
                    await save(
                      join(
                        root,
                        'operations',
                        entry.operationId + '-fresh-task-after-receipt.json',
                      ),
                      detail,
                    );
                  }
                  entry.recoveredDetailReads++;
                  wires.push({
                    kind: 'actual-native-task-detail-after-receipt',
                    operationId: entry.operationId,
                    taskId: entry.taskId,
                    status: response.statusCode,
                    progressCount: count,
                    detailSha256: hash(bytes),
                    at: now(),
                  });
                }
                await checkpoint();
              }
              if (externalRead && response.statusCode === 200) {
                const detail = JSON.parse(bytes);
                const entry = externalRead.entry;
                assert.equal(detail.id, entry.taskId);
                const events = detail.events.filter((event) => event.kind === 'progress.reported');
                if (
                  detail.revision === entry.body.revision + 1 &&
                  events.filter((event) => event.body === entry.body.body).length === 1
                ) {
                  assert.equal(events.length, 3);
                  entry.nativeDetailReads++;
                  entry.firstNativeDetailAt ??= now();
                  entry.firstNativeProgressCount ??= events.length;
                  wires.push({
                    kind: 'actual-native-detail-after-external-sse',
                    at: now(),
                    requestedAt: externalRead.requestedAt,
                    operationId: entry.operationId,
                    taskId: entry.taskId,
                    revision: detail.revision,
                    actualProgressCount: events.length,
                    detailSha256: hash(bytes),
                  });
                  await save(
                    join(
                      root,
                      'operations',
                      entry.operationId + '-native-task-after-external-sse.json',
                    ),
                    detail,
                  );
                  await checkpoint();
                }
              }
              if (inject) {
                await markCommitted(operation, response.statusCode, bytes);
                if (operation.backendCommittedAt) {
                  operation.faultInjectedAt = now();
                  if (operation.mode === 'hold') operation.heldResponse = bytes;
                  // Drop: discard successful bytes, leave this request silent until the app's
                  // real timeout. This avoids introducing a socket reset that could transparently
                  // re-send POST. Hold: retain bytes until local end-waiting closes the request.
                  wires.push({
                    kind:
                      operation.mode === 'drop'
                        ? 'actual-success-response-discarded'
                        : 'actual-success-response-held',
                    operationId: operation.operationId,
                    status: response.statusCode,
                    responseSha256: hash(bytes),
                    at: operation.faultInjectedAt,
                  });
                  await checkpoint();
                  if (outgoing.destroyed) operation.downstreamClosedAt ??= now();
                  const timer = setTimeout(() => {
                    timers.delete(timer);
                    outgoing.destroy();
                  }, 45000);
                  timers.add(timer);
                  outgoing.once('close', () => {
                    clearTimeout(timer);
                    timers.delete(timer);
                  });
                  return;
                }
              }
              outgoing.writeHead(response.statusCode, filteredHeaders(response.headers));
              outgoing.end(bytes);
            })().catch((error) => {
              wires.push({ kind: 'proxy-evidence-error', code: safeError(error), at: now() });
              void checkpoint();
              outgoing.destroy();
            });
          });
        },
      ),
    );
    req.on('error', (error) => {
      if (!outgoing.destroyed) {
        wires.push({ kind: 'upstream-transport-error', code: safeError(error), at: now() });
        void checkpoint();
      }
      outgoing.destroy();
    });
    outgoing.once('close', () => {
      if (inject) {
        operation.downstreamClosedAt = now();
        operation.heldResponse = undefined;
        wires.push({
          kind: 'native-wait-connection-closed',
          operationId: operation.operationId,
          at: now(),
        });
        void checkpoint();
        // Never abort a real already-submitted business write to implement local end-waiting.
      } else req.destroy();
    });
    if (captured) req.end(captured);
    else incoming.pipe(req);
  }
  server = createServer(
    { key, cert, minVersion: 'TLSv1.2', maxHeaderSize: 32 * 1024 },
    (incoming, outgoing) => {
      void handle(incoming, outgoing).catch((error) => {
        wires.push({ kind: 'proxy-handler-error', code: safeError(error), at: now() });
        void checkpoint();
        outgoing.destroy();
      });
    },
  );
  server.on('connection', (socket) => {
    sockets.add(socket);
    socket.once('close', () => sockets.delete(socket));
  });
  server.headersTimeout = 15000;
  server.requestTimeout = 60000;
  // Share only the primary's existing LAN binding; do not change its gateway or advertise another service.
  assert.ok(
    ['127.0.0.1', '0.0.0.0'].includes(config.bind) ||
      /^192\.168\.|^10\.|^172\.(1[6-9]|2[0-9]|3[01])\./.test(config.bind),
  );
  if (physical) assert.notEqual(config.bind, '127.0.0.1');
  await new Promise((accept, reject) => {
    server.once('error', reject);
    server.listen(19546, config.bind, accept);
  });
  report.phase = 'running';
  await save(join(root, 'report.json'), report);
  await checkpoint();
  const childArgs = [
    base,
    '--state',
    state,
    '--device',
    device,
    '--scenario',
    'network',
    '--endpoint',
    proxyOrigin,
    '--output-root',
    root,
    ...(physical ? ['--physical'] : []),
  ];
  child = spawn(process.execPath, childArgs, {
    cwd: repository,
    env: {
      ...process.env,
      TASK_HUB_NETWORK_RUN_ID: runId,
      TASK_HUB_NETWORK_CERT_SHA256: registry.certificateSha256,
      TASK_HUB_NETWORK_SCENARIO: '1',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let output = '';
  const redact = (text) =>
    text
      .replaceAll(env.BOOTSTRAP_PASSWORD, '[private]')
      .replace(/Bearer [A-Za-z0-9_.-]+/g, 'Bearer [private]')
      .replace(/thm_[A-Za-z0-9_-]{43}/g, '[private-session]');
  for (const stream of [child.stdout, child.stderr])
    stream.on('data', (part) => {
      if (output.length < 2 * 1024 * 1024) output += part.toString();
    });
  const exitCode = await new Promise((accept, reject) => {
    child.once('error', reject);
    child.once('close', accept);
  });
  await writeFile(join(root, 'base-runner.log'), redact(output), { mode: 0o600 });
  report.nativeExitCode = exitCode;
  assert.ok(!aborted, 'The run was interrupted; no pass may be claimed');
  // The base runner prints a final JSON object and freezes the actual native input independently.
  const result = JSON.parse(output.slice(output.lastIndexOf('\n{') + 1).trim());
  const nativeReportPath = await taskHubAcceptanceReport(root, result.report);
  const native = await json(nativeReportPath, true);
  report.native = {
    report: nativeReportPath,
    phase: native.phase,
    xcresult: result.xcresult,
    implementation: native.implementation,
    summary: native.nativeSummary,
  };
  assert.equal(exitCode, 0);
  assert.equal(result.phase, 'passed');
  assert.equal(native.phase, 'passed');
  assert.ok(native.sourceUnchanged);
  assert.equal(native.device, device);
  assert.equal(native.payloadSha256, config.payloadSha256);
  assert.equal(native.implementation.files[nativePath], report.harness[nativePath]);
  const liveManifest = await verifyBundle(config.bundle);
  assert.equal(liveManifest.payloadSha256, report.payloadSha256);
  assert.ok(sameIdentity(await json(join(state, 'runtime.json'), true), registry));
  assert.equal((await json(join(state, 'runtime.json'), true)).payloadSha256, report.payloadSha256);
  assert.equal(hash(new X509Certificate(await readFile(certPath)).raw), registry.certificateSha256);
  const finalHealth = await probe(registry, { connectHost: '127.0.0.1' });
  assert.ok(finalHealth.identityVerified && finalHealth.backendReady);
  for (const [path, digest] of Object.entries(report.harness))
    assert.equal(hash(await readFile(join(repository, path))), digest);
  assert.equal(operations.size, 2);
  const entries = [...operations.values()];
  assert.deepEqual(entries.map((entry) => entry.mode).sort(), ['drop', 'hold']);
  assert.equal(new Set(entries.map((entry) => entry.taskId)).size, 1);
  for (const entry of entries) {
    assert.equal(entry.requestCount, 1);
    assert.ok(entry.backendCommittedAt && entry.faultInjectedAt && entry.downstreamClosedAt);
    assert.ok(entry.receiptQueries >= 1);
    assert.ok(
      entry.receiptConfirmedAt &&
        entry.recoveredDetailReads >= 1 &&
        entry.recoveredSSEConnections >= 1,
    );
    assert.equal(entry.firstRecoveredProgressCount, entry.mode === 'drop' ? 1 : 2);
    assert.ok(
      wires.some(
        (wire) =>
          wire.kind === 'actual-native-receipt-response' &&
          wire.operationId === entry.operationId &&
          wire.status === 200 &&
          wire.phase === 'confirmed',
      ),
    );
    const reply = await actual('/api/v1/operations/' + entry.operationId, { headers: entry.auth });
    assert.equal(reply.status, 200);
    const receipt = JSON.parse(reply.bytes);
    assert.equal(receipt.operationId, entry.operationId);
    assert.equal(receipt.phase, 'confirmed');
    assert.equal(receipt.result.id, entry.taskId);
    assert.equal(
      receipt.result.events.filter((event) => event.kind === 'progress.reported').length,
      entry.progressCountAtCommit,
    );
    await save(join(root, 'operations', entry.operationId + '-independent-receipt.json'), receipt);
    report.checks.push({
      scenario:
        entry.mode === 'drop'
          ? 'actual committed write, lost response, unknown draft and original receipt recovery'
          : 'actual committed write, local end-waiting and original receipt recovery',
      phase: 'passed',
      operationId: entry.operationId,
      nativeWriteRequests: entry.requestCount,
      originalReceiptQueries: entry.receiptQueries,
      actualProgressCountAtCommit: entry.progressCountAtCommit,
      freshNativeDetailReadsAfterReceipt: entry.recoveredDetailReads,
      firstRecoveredProgressCount: entry.firstRecoveredProgressCount,
      actualSSEConnectionsAfterReceipt: entry.recoveredSSEConnections,
    });
  }
  assert.ok(externalProgress?.backendCommittedAt);
  assert.equal(externalProgress.taskId, entries[0].taskId);
  assert.equal(externalProgress.body.body, 'native-network/external-sse:' + runId);
  assert.equal(externalProgress.requestCount, 1);
  assert.equal(externalProgress.actualProgressCount, 3);
  assert.equal(externalProgress.committedRevision, externalProgress.body.revision + 1);
  assert.ok(externalProgress.nativeSSEFrames >= 1 && externalProgress.firstNativeSSEAt);
  assert.ok(externalProgress.nativeDetailReads >= 1 && externalProgress.firstNativeDetailAt);
  assert.equal(externalProgress.firstNativeProgressCount, 3);
  assert.ok(
    Date.parse(externalProgress.firstNativeDetailAt) >=
      Date.parse(externalProgress.firstNativeSSEAt),
  );
  assert.ok(
    wires.some(
      (wire) =>
        wire.kind === 'actual-native-detail-after-external-sse' &&
        wire.operationId === externalProgress.operationId &&
        Date.parse(wire.requestedAt) >= Date.parse(externalProgress.firstNativeSSEAt),
    ),
  );
  for (const entry of entries) {
    assert.notEqual(externalProgress.operationId, entry.operationId);
    assert.notEqual(
      externalProgress.auth['x-taskhub-device-id'],
      entry.auth['x-taskhub-device-id'],
    );
  }
  const externalReceiptReply = await actual('/api/v1/operations/' + externalProgress.operationId, {
    headers: externalProgress.auth,
  });
  assert.equal(externalReceiptReply.status, 200);
  const externalReceipt = JSON.parse(externalReceiptReply.bytes);
  assert.equal(externalReceipt.operationId, externalProgress.operationId);
  assert.equal(externalReceipt.phase, 'confirmed');
  assert.equal(externalReceipt.result.id, externalProgress.taskId);
  assert.equal(externalReceipt.result.revision, externalProgress.committedRevision);
  const externalReceiptEvents = externalReceipt.result.events.filter(
    (event) => event.kind === 'progress.reported',
  );
  assert.equal(externalReceiptEvents.length, 3);
  assert.equal(
    externalReceiptEvents.filter((event) => event.body === externalProgress.body.body).length,
    1,
  );
  for (const entry of entries)
    assert.equal(externalReceiptEvents.filter((event) => event.body === entry.body.body).length, 1);
  await save(
    join(root, 'operations', externalProgress.operationId + '-independent-external-receipt.json'),
    externalReceipt,
  );
  report.checks.push({
    scenario:
      'independent real human progress, original mobile SSE business frame and automatic UI/detail refresh',
    phase: 'passed',
    operationId: externalProgress.operationId,
    taskId: externalProgress.taskId,
    nativeWriteRequests: externalProgress.requestCount,
    actualProgressCount: 3,
    nativeSSEBusinessFrames: externalProgress.nativeSSEFrames,
    nativeDetailReadsAfterBusinessFrame: externalProgress.nativeDetailReads,
    passiveNativeUIAppearanceRequired: true,
  });
  const detailReply = await actual('/api/v1/tasks/' + entries[0].taskId, {
    headers: entries[0].auth,
  });
  assert.equal(detailReply.status, 200);
  const detail = JSON.parse(detailReply.bytes);
  assert.equal(detail.id, entries[0].taskId);
  assert.equal(detail.status, 'doing');
  assert.equal(detail.workflowStage, 'development');
  assert.equal(detail.acceptanceMode, 'human');
  assert.notEqual(detail.ownerId, detail.reviewerId);
  const actorReply = await actual('/api/v1/actors', { headers: entries[0].auth });
  assert.equal(actorReply.status, 200);
  const actors = JSON.parse(actorReply.bytes);
  assert.equal(actors.find((actor) => actor.id === detail.ownerId)?.kind, 'human');
  assert.equal(actors.find((actor) => actor.id === detail.reviewerId)?.kind, 'human');
  const projectReply = await actual('/api/v1/projects', { headers: entries[0].auth });
  assert.equal(projectReply.status, 200);
  const project = JSON.parse(projectReply.bytes).find((value) => value.id === detail.projectId);
  const runNonce = runId.replaceAll('-', '').slice(0, 12);
  assert.equal(project?.name, '原生网络故障 DEV ' + runNonce);
  assert.equal(detail.title, '原回执与结束等待 ' + runNonce);
  assert.equal(detail.currentRound, 1);
  const events = detail.events.filter((event) => event.kind === 'progress.reported');
  assert.equal(events.length, 3);
  for (const entry of entries)
    assert.equal(events.filter((event) => event.body === entry.body.body).length, 1);
  const finalExternalEvents = events.filter((event) => event.body === externalProgress.body.body);
  assert.equal(finalExternalEvents.length, 1);
  assert.equal(finalExternalEvents[0].actorId, detail.ownerId);
  assert.ok(
    !detail.events.some((event) =>
      ['task.finalized', 'delivery.submitted', 'acceptance.decided'].includes(event.kind),
    ),
  );
  assert.ok(!wires.some((wire) => /-error$/.test(wire.kind)));
  await save(join(root, 'actual-final-task-detail.json'), detail);
  report.businessTask = {
    id: detail.id,
    projectId: detail.projectId,
    status: detail.status,
    workflowStage: detail.workflowStage,
    completion: 'uncompleted',
    finalizationAttempted: false,
    actualProgressCount: events.length,
  };
  report.checks.push({
    scenario:
      'actual task detail contains exactly one event per immutable operation, human participants and no finalization',
    phase: 'passed',
  });
  report.phase = 'passed';
} catch (error) {
  report.phase = 'failed';
  report.failureCode = safeError(error);
  if (
    error?.name === 'AssertionError' &&
    typeof error.message === 'string' &&
    error.message.startsWith('Parent base runner integration is still required:')
  )
    report.integrationRequired = error.message;
  process.exitCode = 1;
} finally {
  if (child && child.exitCode === null) child.kill('SIGTERM');
  for (const timer of timers) clearTimeout(timer);
  for (const req of upstreams) req.destroy();
  for (const socket of sockets) socket.destroy();
  if (server?.listening) await new Promise((accept) => server.close(accept));
  if (externalReporter?.auth) {
    try {
      report.externalReporterCleanup = {
        phase: 'passed',
        logoutStatus: await revokeExternalReporter(),
      };
    } catch {
      report.externalReporterCleanup = {
        phase: 'failed',
        failureCode: 'EXTERNAL_REPORTER_LOGOUT_FAILED',
      };
      report.phase = 'failed';
      process.exitCode = 1;
    }
  }
  if (externalProgress) externalProgress.auth = undefined;
  externalReporter = undefined;
  for (const entry of operations.values()) {
    entry.auth = undefined;
    entry.heldResponse = undefined;
  }
  report.operations = publicOperations();
  report.externalProgress = publicExternalProgress();
  report.finishedAt = now();
  await checkpoint();
  await save(join(root, 'report.json'), report);
  process.removeListener('SIGINT', shutdownSignal);
  process.removeListener('SIGTERM', shutdownSignal);
}
console.log(
  JSON.stringify(
    {
      phase: report.phase,
      kind: report.kind,
      report: join(root, 'report.json'),
      wireEvidence: join(root, 'wire-evidence.json'),
      businessTask: report.businessTask,
    },
    null,
    2,
  ),
);
