import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtemp, writeFile, readFile, rm, chmod, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { generateKeyPairSync, verify, randomBytes, createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { once } from 'node:events';
import Ajv from 'ajv';
import {
  defaultNotificationPreferences,
  parseNotificationRegistration,
} from '../packages/protocol/dist/index.js';
import {
  sealNotification,
  NotificationPublisher,
  NotificationObserver,
} from '../packages/notification-runtime/dist/index.js';
import { startPushServer } from '../apps/relay/dist/push-server.js';
import {
  createFCMAuthorizer,
  createFCMSender,
  notificationFCMPayload,
} from '../apps/relay/dist/fcm-sender.js';

const projectId = 'easywork-test';
const target = {
  agentId: 'easywork',
  hostId: 'fixture-host',
  workspaceId: 'fixture-workspace',
  runtimeId: 'local',
  account: { authority: 'https://fixture.test', subject: 'fixture-owner' },
};
const token = 'fixture:opaque_registration-token';
const recipient = generateKeyPairSync('x25519'),
  sender = generateKeyPairSync('x25519');
const event = {
  version: 1,
  id: 'a'.repeat(64),
  target,
  kind: 'completed',
  occurredAt: new Date().toISOString(),
  threadId: 'fixture-thread',
  threadTitle: 'Private thread title must stay encrypted',
};
const cipher = sealNotification(
  event,
  'fixture-binding',
  sender.privateKey.export({ format: 'pem', type: 'pkcs8' }),
  recipient.publicKey.export({ format: 'der', type: 'spki' }).subarray(-32).toString('base64'),
);
const envelope = () => ({
  version: 1,
  bindingId: 'fixture-binding',
  eventId: event.id,
  ...cipher,
  expiresAt: new Date(Date.now() + 60000).toISOString(),
  sound: 'default',
  timeSensitive: false,
});
const registration = () => ({
  version: 1,
  deviceId: 'fixture-phone',
  target,
  publicKey: recipient.publicKey
    .export({ format: 'der', type: 'spki' })
    .subarray(-32)
    .toString('base64'),
  preferences: defaultNotificationPreferences(),
});
const response = (body, status = 200, headers = {}) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json', ...headers },
  });
async function account(t) {
  const directory = await mkdtemp(join(tmpdir(), 'easywork-fcm-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const pair = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const path = join(directory, 'account.json');
  await writeFile(
    path,
    JSON.stringify({
      type: 'service_account',
      project_id: projectId,
      client_email: 'fixture@' + projectId + '.iam.gserviceaccount.com',
      private_key: pair.privateKey.export({ format: 'pem', type: 'pkcs8' }),
      token_uri: 'https://untrusted.test/must-not-use',
    }),
    { mode: 0o600 },
  );
  return { config: { projectId, serviceAccountPath: path }, pair, directory };
}

test('EasyWork FCM registration parser and generated schema agree on opaque tokens and strict provider branches', async () => {
  const ajv = new Ajv({ strict: false, formats: { 'date-time': true } });
  const schema = JSON.parse(
    await readFile(
      new URL('../packages/protocol/schemas/task-notifications-v1.schema.json', import.meta.url),
      'utf8',
    ),
  );
  const validate = ajv.compile({ ...schema, $ref: '#/definitions/Registration', oneOf: undefined });
  const good = [
    registration(),
    { ...registration(), deviceToken: 'a'.repeat(64), environment: 'development' },
    {
      ...registration(),
      pushProvider: 'apns',
      deviceToken: 'b'.repeat(64),
      environment: 'production',
    },
    { ...registration(), pushProvider: 'fcm', deviceToken: token },
  ];
  for (const value of good) {
    assert.deepEqual(parseNotificationRegistration(value), value);
    assert.equal(validate(value), true, JSON.stringify(validate.errors));
  }
  const bad = [
    { ...registration(), pushProvider: 'fcm' },
    { ...registration(), deviceToken: token },
    { ...registration(), pushProvider: 'fcm', deviceToken: token, environment: 'production' },
    { ...registration(), pushProvider: 'other', deviceToken: token },
    ...['', 'with space', 'with\nnewline', 'x'.repeat(4097)].map((deviceToken) => ({
      ...registration(),
      pushProvider: 'fcm',
      deviceToken,
    })),
  ];
  for (const value of bad) {
    assert.throws(() => parseNotificationRegistration(value));
    assert.equal(validate(value), false);
  }
});

test('EasyWork FCM OAuth is single-flight, uses the fixed endpoint and verifies a private RSA assertion', async (t) => {
  const { config, pair } = await account(t);
  let calls = 0;
  const authorize = createFCMAuthorizer(config, async (url, init) => {
    calls++;
    assert.equal(url, 'https://oauth2.googleapis.com/token');
    assert.equal(init.redirect, 'error');
    const body = new URLSearchParams(init.body);
    assert.equal(body.get('grant_type'), 'urn:ietf:params:oauth:grant-type:jwt-bearer');
    const jwt = body.get('assertion').split('.');
    assert.equal(jwt.length, 3);
    assert.equal(
      verify(
        'RSA-SHA256',
        Buffer.from(jwt[0] + '.' + jwt[1]),
        pair.publicKey,
        Buffer.from(jwt[2], 'base64url'),
      ),
      true,
    );
    const claims = JSON.parse(Buffer.from(jwt[1], 'base64url'));
    assert.equal(claims.aud, url);
    assert.equal(claims.iss, 'fixture@' + projectId + '.iam.gserviceaccount.com');
    assert.equal(claims.scope, 'https://www.googleapis.com/auth/firebase.messaging');
    assert.equal(claims.exp - claims.iat, 3600);
    await new Promise((resolve) => setTimeout(resolve, 5));
    return response({ access_token: 'fixture-access', token_type: 'Bearer', expires_in: 3600 });
  });
  assert.deepEqual(
    await Promise.all(Array.from({ length: 12 }, () => authorize())),
    Array(12).fill('fixture-access'),
  );
  assert.equal(calls, 1);
  await authorize();
  assert.equal(calls, 1);
  authorize.invalidate();
  await authorize();
  assert.equal(calls, 2);
});

test('EasyWork FCM rejects mismatched projects, public credential files and symlink credentials', async (t) => {
  const { config, directory } = await account(t);
  assert.throws(() => createFCMAuthorizer({ ...config, projectId: 'other-project' }));
  if (process.platform !== 'win32') {
    await chmod(config.serviceAccountPath, 0o644);
    assert.throws(() => createFCMAuthorizer(config));
    await chmod(config.serviceAccountPath, 0o600);
    const link = join(directory, 'linked.json');
    await symlink(config.serviceAccountPath, link);
    assert.throws(() => createFCMAuthorizer({ ...config, serviceAccountPath: link }));
  }
  await writeFile(config.serviceAccountPath, '{"private_key":"fixture-private-marker",broken', {
    mode: 0o600,
  });
  assert.throws(
    () => createFCMAuthorizer(config),
    (error) =>
      !error.message.includes('fixture-private-marker') && error.message === 'FCM 服务凭据格式无效',
  );
});

test('EasyWork FCM sends only an encrypted data envelope with bounded TTL and no automatic notification body', async (t) => {
  const { config } = await account(t);
  let sends = 0;
  const send = createFCMSender(config, async (url, init) => {
    if (url === 'https://oauth2.googleapis.com/token')
      return response({ access_token: 'fixture-access', token_type: 'Bearer', expires_in: 3600 });
    sends++;
    assert.equal(url, 'https://fcm.googleapis.com/v1/projects/' + projectId + '/messages:send');
    assert.equal(init.headers.authorization, 'Bearer fixture-access');
    const payload = JSON.parse(init.body);
    assert.equal(payload.message.token, token);
    assert.equal(payload.message.notification, undefined);
    assert.equal(payload.message.android.collapse_key, undefined);
    assert.equal(payload.message.android.priority, 'HIGH');
    assert.match(payload.message.android.ttl, /^\d+s$/);
    assert.equal(
      JSON.parse(payload.message.data.agent_hub_notification).ciphertext,
      cipher.ciphertext,
    );
    assert.equal(init.body.includes(event.threadTitle), false);
    assert.equal(init.body.includes(target.workspaceId), false);
    return response({ name: 'projects/' + projectId + '/messages/fixture-message' });
  });
  assert.deepEqual(await send({ deviceToken: token }, envelope()), { state: 'accepted' });
  assert.equal(sends, 1);
  const silent = notificationFCMPayload(token, { ...envelope(), badgeOnly: true, sound: 'none' });
  assert.equal(silent.message.android.priority, 'NORMAL');
  assert.throws(() =>
    notificationFCMPayload(token, {
      ...envelope(),
      expiresAt: new Date(Date.now() - 1000).toISOString(),
    }),
  );
});

test('EasyWork FCM distinguishes provider rejection, malformed acceptance, unknown send and explicit retry timing', async (t) => {
  const { config } = await account(t);
  for (const [answer, expected] of [
    [
      () => response({ error: { details: [{ errorCode: 'UNREGISTERED' }] } }, 404),
      { state: 'rejected', reason: 'Unregistered' },
    ],
    [
      () => new Response('unavailable', { status: 503, headers: { 'retry-after': '120' } }),
      { state: 'pending', reason: 'FCMRetryableRejection', retryAfterMs: 120000 },
    ],
    [
      () => new Response('forbidden', { status: 403 }),
      { state: 'rejected', reason: 'FCMRejected' },
    ],
    [
      () => response({ name: 'wrong-provider-receipt' }),
      { state: 'uncertain', reason: 'FCMInvalidAcceptance' },
    ],
    [
      () => {
        throw new Error('network result unknown');
      },
      { state: 'uncertain', reason: 'FCMUnknown' },
    ],
  ]) {
    let sends = 0;
    const send = createFCMSender(config, async (url) => {
      if (url === 'https://oauth2.googleapis.com/token')
        return response({ access_token: 'fixture-access', token_type: 'Bearer', expires_in: 3600 });
      sends++;
      return answer();
    });
    assert.deepEqual(await send({ deviceToken: token }, envelope()), expected);
    assert.equal(sends, 1);
  }
});

test('EasyWork FCM explicit expired-token rejection invalidates OAuth before a later retry', async (t) => {
  const { config } = await account(t);
  let authorizations = 0,
    sends = 0;
  const send = createFCMSender(config, async (url, init) => {
    if (url === 'https://oauth2.googleapis.com/token')
      return response({
        access_token: 'fixture-' + ++authorizations,
        token_type: 'Bearer',
        expires_in: 3600,
      });
    sends++;
    assert.equal(init.headers.authorization, 'Bearer fixture-' + authorizations);
    return sends === 1
      ? response({}, 401)
      : response({ name: 'projects/' + projectId + '/messages/after-refresh' });
  });
  assert.deepEqual(await send({ deviceToken: token }, envelope()), {
    state: 'pending',
    reason: 'FCMAuthorizationRejected',
    retryAfterMs: 30000,
  });
  assert.equal(authorizations, 1);
  assert.deepEqual(await send({ deviceToken: token }, envelope()), { state: 'accepted' });
  assert.equal(authorizations, 2);
  assert.equal(sends, 2);
});

test('EasyWork FCM a late rejection of an older OAuth token cannot evict a newer grant', async (t) => {
  const { config } = await account(t);
  let authorizations = 0,
    sends = 0;
  let releaseOld;
  const held = new Promise((resolve) => {
    releaseOld = resolve;
  });
  const send = createFCMSender(config, async (url, init) => {
    if (url === 'https://oauth2.googleapis.com/token')
      return response({
        access_token: 'fixture-' + ++authorizations,
        token_type: 'Bearer',
        expires_in: 3600,
      });
    const call = ++sends;
    if (call === 1) {
      await held;
      return response({}, 401);
    }
    if (call === 2) return response({}, 401);
    assert.equal(init.headers.authorization, 'Bearer fixture-2');
    return response({ name: 'projects/' + projectId + '/messages/current-' + call });
  });
  const old = send({ deviceToken: token }, envelope());
  await waitFor(() => sends === 1);
  assert.equal((await send({ deviceToken: token }, envelope())).state, 'pending');
  assert.equal((await send({ deviceToken: token }, envelope())).state, 'accepted');
  assert.equal(authorizations, 2);
  releaseOld();
  assert.equal((await old).state, 'pending');
  assert.equal((await send({ deviceToken: token }, envelope())).state, 'accepted');
  assert.equal(authorizations, 2);
});

const waitFor = async (check) => {
  const deadline = Date.now() + 5000;
  while (!(await check())) {
    if (Date.now() > deadline) throw new Error('Fixture boundary timed out');
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
};
const hostToken = 'fixture-publisher-token-'.repeat(3);
async function post(relay, path, body) {
  const response = await fetch('http://127.0.0.1:' + relay.server.address().port + path, {
    method: 'POST',
    headers: { authorization: 'Bearer ' + hostToken, 'content-type': 'application/json' },
    body: JSON.stringify(body),
  });
  return { status: response.status, body: await response.json() };
}
async function stopRelay(relay) {
  const closed = once(relay.server, 'close');
  relay.close();
  await closed;
}

test('EasyWork FCM Relay uses the declared provider and preserves accepted/unknown receipts across restart', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'easywork-fcm-relay-'));
  const config = {
    statePath: join(directory, 'relay.json'),
    topic: 'fixture.easywork',
    teamId: 'fixture',
    hosts: [{ hostId: target.hostId, token: hostToken }],
    fcm: { projectId, serviceAccountPath: 'fixture-unused' },
  };
  const sent = [];
  const sendFCM = async (binding, value) => {
    sent.push({ binding, value });
    return value.eventId === 'b'.repeat(64)
      ? { state: 'uncertain', reason: 'FCMUnknown' }
      : { state: 'accepted' };
  };
  let relay = await startPushServer(config, { port: 0, address: '127.0.0.1', sendFCM });
  t.after(async () => {
    await stopRelay(relay);
    await rm(directory, { recursive: true, force: true });
  });
  const input = {
    hostId: target.hostId,
    target,
    deviceId: 'fixture-phone',
    deviceToken: token,
    pushProvider: 'fcm',
    generation: 1,
    managementToken: randomBytes(32).toString('base64url'),
  };
  const registered = await post(relay, '/v1/push/register', input);
  assert.equal(registered.status, 200);
  const bindingId = registered.body.bindingId;
  assert.deepEqual(await post(relay, '/v1/push/register', input), registered);
  assert.equal(
    (await post(relay, '/v1/push/register', { ...input, deviceToken: 'different-token' })).status,
    409,
  );
  assert.equal(
    (
      await post(relay, '/v1/push/register', {
        ...input,
        pushProvider: 'fcm',
        environment: 'production',
      })
    ).status,
    403,
  );
  const accepted = { ...envelope(), bindingId },
    unknown = { ...accepted, eventId: 'b'.repeat(64) };
  assert.equal((await post(relay, '/v1/push/publish', accepted)).status, 200);
  await waitFor(
    async () =>
      (await post(relay, '/v1/push/status', { bindingId, eventId: accepted.eventId })).body
        .state === 'accepted',
  );
  await post(relay, '/v1/push/publish', unknown);
  await waitFor(
    async () =>
      (await post(relay, '/v1/push/status', { bindingId, eventId: unknown.eventId })).body.state ===
      'uncertain',
  );
  assert.equal(sent.length, 2);
  assert.equal(sent[0].binding.pushProvider, 'fcm');
  assert.equal(sent[0].binding.environment, undefined);
  await stopRelay(relay);
  relay = await startPushServer(config, { port: 0, address: '127.0.0.1', sendFCM });
  assert.equal((await post(relay, '/v1/push/publish', accepted)).body.state, 'accepted');
  assert.equal((await post(relay, '/v1/push/publish', unknown)).body.state, 'uncertain');
  await new Promise((resolve) => setTimeout(resolve, 100));
  assert.equal(sent.length, 2);
  const rotated = await post(relay, '/v1/push/register', {
    ...input,
    generation: 2,
    deviceToken: 'rotated-opaque-token',
  });
  assert.equal(rotated.status, 200);
  assert.equal((await post(relay, '/v1/push/register', input)).status, 409);
  await post(relay, '/v1/push/unregister', {
    hostId: target.hostId,
    target,
    deviceId: input.deviceId,
    generation: 3,
  });
  assert.equal((await post(relay, '/v1/push/register', { ...input, generation: 3 })).status, 409);
});

test('EasyWork FCM Relay persists Retry-After instead of immediately resending a known rejection', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'easywork-fcm-backoff-'));
  let sends = 0;
  const config = {
    statePath: join(directory, 'relay.json'),
    topic: 'fixture.easywork',
    teamId: 'fixture',
    hosts: [{ hostId: target.hostId, token: hostToken }],
    fcm: { projectId, serviceAccountPath: 'fixture-unused' },
  };
  const relay = await startPushServer(config, {
    port: 0,
    address: '127.0.0.1',
    sendFCM: async () => {
      sends++;
      return { state: 'pending', reason: 'FCMRetryableRejection', retryAfterMs: 60000 };
    },
  });
  t.after(async () => {
    await stopRelay(relay);
    await rm(directory, { recursive: true, force: true });
  });
  const registration = await post(relay, '/v1/push/register', {
    hostId: target.hostId,
    target,
    deviceId: 'fixture-phone',
    deviceToken: token,
    pushProvider: 'fcm',
    generation: 1,
    managementToken: randomBytes(32).toString('base64url'),
  });
  await post(relay, '/v1/push/publish', { ...envelope(), bindingId: registration.body.bindingId });
  await waitFor(() => sends === 1);
  await new Promise((resolve) => setTimeout(resolve, 2200));
  assert.equal(sends, 1);
  const state = JSON.parse(await readFile(config.statePath, 'utf8'));
  const delivery = Object.values(state.deliveries)[0];
  assert.ok(delivery.retryAt > Date.now() + 50000);
});

test('EasyWork FCM Publisher reconciles the exact pending registration and fences token rotation readiness', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'easywork-fcm-publisher-'));
  let publisher;
  const attempts = [];
  let held;
  let failFirst = true;
  const relay = createServer(async (request, response) => {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const value = JSON.parse(Buffer.concat(chunks));
    response.setHeader('content-type', 'application/json');
    if (request.url === '/v1/push/register') {
      attempts.push(value);
      if (failFirst) {
        failFirst = false;
        response.destroy();
        return;
      }
      if (value.deviceToken === 'rotated-token') {
        held = { response, value };
        return;
      }
      response.end(
        JSON.stringify({ bindingId: 'fixture-binding', managementToken: value.managementToken }),
      );
      return;
    }
    response.end(JSON.stringify({ enabled: true }));
  });
  relay.listen(0, '127.0.0.1');
  await once(relay, 'listening');
  t.after(async () => {
    publisher?.close();
    relay.close();
    relay.closeAllConnections();
    await rm(directory, { recursive: true, force: true });
  });
  publisher = new NotificationPublisher({
    target,
    statePath: join(directory, 'publisher.json'),
    relayURL: 'http://127.0.0.1:' + relay.address().port,
    publisherToken: hostToken,
  });
  const value = {
    ...registration(),
    deviceToken: token,
    pushProvider: 'fcm',
    preferences: {
      ...defaultNotificationPreferences(),
      enabled: true,
      revision: 'fixture-1',
      generation: 1,
    },
  };
  const first = await publisher.register(value, 'owner');
  assert.equal(first.state, 'unavailable');
  assert.equal(attempts.length, 1);
  const confirmed = await publisher.register(value, 'owner');
  assert.equal(confirmed.state, 'ready');
  assert.equal(confirmed.delivery, 'fcm');
  assert.deepEqual(attempts[1], attempts[0]);
  assert.equal(confirmed.deviceTokenSha256, createHash('sha256').update(token).digest('hex'));
  const rotating = publisher.register({ ...value, deviceToken: 'rotated-token' }, 'owner');
  await waitFor(() => Boolean(held));
  assert.equal(publisher.status(value.deviceId, 'owner').state, 'pending');
  assert.ok(attempts[2].generation > attempts[1].generation);
  await assert.rejects(() => publisher.register(value, 'other-owner'));
  held.response.end(
    JSON.stringify({ bindingId: 'fixture-binding', managementToken: held.value.managementToken }),
  );
  const ready = await rotating;
  assert.equal(ready.state, 'ready');
  assert.equal(ready.deviceTokenSha256, createHash('sha256').update('rotated-token').digest('hex'));
});

test('EasyWork FCM foreground fallback revokes the old binding with one durable intent and preserves foreground events', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'easywork-fcm-fallback-'));
  let publisher;
  const revocations = [];
  const relay = createServer(async (request, response) => {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const value = JSON.parse(Buffer.concat(chunks));
    response.setHeader('content-type', 'application/json');
    if (request.url === '/v1/push/register') {
      response.end(
        JSON.stringify({ bindingId: 'fixture-binding', managementToken: value.managementToken }),
      );
      return;
    }
    if (request.url === '/v1/push/unregister') {
      revocations.push(value);
      if (revocations.length === 1) {
        response.destroy();
        return;
      }
      response.end(JSON.stringify({ state: 'revoked' }));
      return;
    }
    response.end(JSON.stringify({ enabled: true }));
  });
  relay.listen(0, '127.0.0.1');
  await once(relay, 'listening');
  t.after(async () => {
    publisher?.close();
    relay.close();
    relay.closeAllConnections();
    await rm(directory, { recursive: true, force: true });
  });
  publisher = new NotificationPublisher({
    target,
    statePath: join(directory, 'publisher.json'),
    relayURL: 'http://127.0.0.1:' + relay.address().port,
    publisherToken: hostToken,
  });
  assert.ok(publisher.registrationCapabilities.includes('notifications:registration:fcm:v1'));
  const foreground = {
    ...registration(),
    preferences: {
      ...defaultNotificationPreferences(),
      enabled: true,
      revision: 'fixture-fallback',
      generation: 1,
    },
  };
  assert.equal(
    (await publisher.register({ ...foreground, deviceToken: token, pushProvider: 'fcm' }, 'owner'))
      .delivery,
    'fcm',
  );
  const pending = await publisher.register(foreground, 'owner');
  assert.equal(pending.delivery, 'foreground-only');
  assert.notEqual(pending.state, 'ready');
  assert.equal(revocations.length, 1);
  const confirmed = await publisher.register(foreground, 'owner');
  assert.equal(confirmed.delivery, 'foreground-only');
  assert.equal(confirmed.bindingId, undefined);
  assert.equal(revocations.length, 2);
  assert.deepEqual(revocations[1], revocations[0]);
  const saved = JSON.parse(await readFile(join(directory, 'publisher.json'), 'utf8')).devices[
    foreground.deviceId
  ];
  assert.equal(saved.registration.preferences.enabled, true);
  assert.equal(saved.pendingPushRevocation, undefined);
  const notice = { ...event, kind: 'approval', requestId: 'fixture-request' };
  publisher.observe([notice], notice.threadId, [notice.id], true);
  assert.equal(publisher.foreground(foreground.deviceId, '', 'owner').events[0].id, notice.id);
});

test('EasyWork current notification observer keeps request-specific summaries and complete Unicode titles', () => {
  const observer = new NotificationObserver(target);
  const value = {
    threadId: 'fixture-thread',
    title: 'x'.repeat(99) + '🙂',
    live: true,
    runId: 'fixture-run',
    outcome: 'inProgress',
    taskSummary: 'Broad task summary',
    requests: [
      {
        kind: 'approval',
        id: 'fixture-request',
        runId: 'fixture-run',
        taskSummary: 'Exact permission request',
      },
    ],
    uncertainOperations: [],
    planId: 'fixture-plan',
    planSummary: 'Exact current plan',
  };
  const result = observer.observe(value).events;
  assert.equal(
    result.find((value) => value.kind === 'approval').taskSummary,
    'Exact permission request',
  );
  assert.equal(result.find((value) => value.kind === 'plan').taskSummary, 'Exact current plan');
  assert.equal(result.find((value) => value.kind === 'approval').threadTitle, 'x'.repeat(99));
});

test('EasyWork unknown operation notices retain the original submitted context independently of the current run', () => {
  const observer = new NotificationObserver(target);
  const result = observer
    .observe({
      threadId: 'fixture-thread',
      title: 'Newer task',
      live: true,
      runId: 'newer-run',
      outcome: 'inProgress',
      requests: [],
      uncertainOperations: ['original-operation'],
      uncertainOperationDetails: [
        {
          id: 'original-operation',
          runId: 'original-run',
          threadTitle: 'Original task',
          taskSummary: 'Exact original submitted intent',
        },
      ],
    })
    .events.find((value) => value.kind === 'uncertain');
  assert.equal(result.runId, 'original-run');
  assert.equal(result.threadTitle, 'Original task');
  assert.equal(result.taskSummary, 'Exact original submitted intent');
});
