import assert from 'node:assert/strict';
import { test } from 'node:test';
import { generateKeyPairSync, randomBytes, randomUUID } from 'node:crypto';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { businessNotificationCipher } from '../packages/notification-runtime/dist/index.js';
import {
  parseBusinessNotice,
  parseBusinessEnvelope,
  parseBusinessTarget,
} from '../packages/protocol/dist/index.js';
import { startPushServer, notificationAPNsPayload } from '../apps/relay/dist/push-server.js';

const identity = {
  deploymentId: randomUUID(),
  serverInstanceId: randomUUID(),
  primaryHostId: randomUUID(),
};
const target = { ...identity, actorId: randomUUID() };
const event = {
  version: 1,
  domain: 'taskhub.business',
  ...target,
  eventId: 'a'.repeat(64),
  projectId: randomUUID(),
  taskId: randomUUID(),
  roundId: randomUUID(),
  revision: 4,
  kind: 'acceptance_failed',
  occurredAt: new Date().toISOString(),
  title: 'Private business title',
};
const cipher = () =>
  businessNotificationCipher(
    generateKeyPairSync('x25519').privateKey.export({ format: 'pem', type: 'pkcs8' }).toString(),
  );
const envelope = (value, bindingId, sender, recipient) => ({
  version: 1,
  domain: 'taskhub.business',
  bindingId,
  eventId: value.eventId,
  ...sender.seal(value, bindingId, recipient.publicKey),
  expiresAt: new Date(Date.now() + 600000).toISOString(),
  sound: 'default',
  timeSensitive: false,
});

test('strict business domain rejects Host targets, extra fields and incomplete navigation', () => {
  assert.throws(() =>
    parseBusinessTarget({
      agentId: 'codex',
      hostId: identity.primaryHostId,
      workspaceId: 'host',
      runtimeId: 'desktop-runtime',
    }),
  );
  assert.deepEqual(parseBusinessNotice(event), event);
  for (const change of [
    { domain: 'agenthub.task' },
    { threadId: randomUUID() },
    { roundId: '' },
    { revision: 0 },
    { actorId: 'agent' },
    { kind: 'approval' },
  ])
    assert.throws(() => parseBusinessNotice({ ...event, ...change }));
});

test('cipher authenticates event, binding and domain with separate X25519 key', () => {
  const sender = cipher(),
    recipient = cipher(),
    stranger = cipher(),
    binding = randomUUID();
  const encrypted = envelope(event, binding, sender, recipient);
  assert.deepEqual(recipient.open(encrypted, binding, sender.publicKey), event);
  assert.throws(() =>
    recipient.open({ ...encrypted, eventId: 'b'.repeat(64) }, binding, sender.publicKey),
  );
  assert.throws(() => recipient.open(encrypted, randomUUID(), sender.publicKey));
  assert.throws(() => stranger.open(encrypted, binding, sender.publicKey));
  assert.throws(() => sender.validateRecipient(Buffer.alloc(32).toString('base64')));
  assert.throws(() => parseBusinessEnvelope({ ...encrypted, badge: 5 }));
  assert.throws(() => parseBusinessEnvelope({ ...encrypted, domain: 'conversation' }));
  const payload = notificationAPNsPayload(encrypted, binding);
  assert.equal(payload.aps.category, 'agenthub.business');
  assert.equal(payload.notification, undefined);
  assert.equal(payload.aps.badge, undefined);
  assert.equal(payload.businessNotification.domain, 'taskhub.business');
  assert.ok(!JSON.stringify(payload).includes(event.title));
});

async function fixture(run, send = async () => ({ state: 'accepted' })) {
  const directory = await mkdtemp(join(tmpdir(), 'business-push-fixture-'));
  const serviceToken = randomBytes(32).toString('base64url'),
    hostToken = randomBytes(32).toString('base64url');
  const config = {
    statePath: join(directory, 'state.json'),
    topic: 'test.invalid',
    teamId: 'synthetic',
    keys: { development: { keyId: 'synthetic', keyPath: 'not-read-by-injected-sender' } },
    hosts: [{ hostId: identity.primaryHostId, token: hostToken }],
    businessPublishers: [{ ...identity, token: serviceToken, topic: 'test.business.dev' }],
  };
  let service = await startPushServer(config, { port: 0, address: '127.0.0.1', send });
  const api = async (path, body, token = serviceToken) => {
    const response = await fetch(
      'http://127.0.0.1:' + service.server.address().port + '/v1/' + path,
      {
        method: 'POST',
        headers: { authorization: 'Bearer ' + token, 'content-type': 'application/json' },
        body: JSON.stringify(body),
      },
    );
    return { status: response.status, body: await response.json() };
  };
  try {
    await run({
      api,
      config,
      hostToken,
      serviceToken,
      directory,
      restart: async () => {
        service.close();
        service = await startPushServer(config, { port: 0, address: '127.0.0.1', send });
      },
    });
  } finally {
    service.close();
    await rm(directory, { recursive: true, force: true });
  }
}
const registration = () => ({
  target,
  projectId: event.projectId,
  deviceId: randomUUID(),
  deviceToken: 'c'.repeat(64),
  environment: 'development',
  generation: 1,
  managementToken: randomBytes(32).toString('base64url'),
});
const settle = async () => {
  await new Promise((resolve) => setTimeout(resolve, 40));
};

test('real loopback relay separates publisher/Host credentials and project subscriptions', async () => {
  await fixture(async ({ api, hostToken }) => {
    const input = registration();
    assert.equal((await api('business-push/register', input, hostToken)).status, 401);
    assert.equal(
      (
        await api('business-push/register', {
          ...input,
          target: { ...target, serverInstanceId: randomUUID() },
        })
      ).status,
      403,
    );
    assert.equal(
      (await api('business-push/register', { ...input, environment: 'production' })).status,
      503,
    );
    assert.equal(
      (await api('business-push/register', { ...input, target: { ...target, runtimeId: 'codex' } }))
        .status,
      400,
    );
    const one = await api('business-push/register', input),
      two = await api('business-push/register', { ...input, projectId: randomUUID() });
    assert.equal(one.status, 200);
    assert.equal(two.status, 200);
    assert.notEqual(one.body.bindingId, two.body.bindingId);
    assert.equal(
      (await api('push/binding-status', { bindingId: one.body.bindingId }, hostToken)).status,
      403,
    );
    assert.equal(
      (await api('push/publish', envelope(event, one.body.bindingId, cipher(), cipher()))).status,
      401,
    );
  });
});

test('idempotent ciphertext delivery, immutable conflict, direct revoke and stale registration', async () => {
  let count = 0;
  await fixture(
    async ({ api, directory }) => {
      const input = registration(),
        binding = (await api('business-push/register', input)).body.bindingId;
      const sender = cipher(),
        recipient = cipher(),
        encrypted = envelope(event, binding, sender, recipient);
      assert.equal((await api('business-push/publish', encrypted)).status, 200);
      await settle();
      assert.equal((await api('business-push/publish', encrypted)).status, 200);
      await settle();
      assert.equal(count, 1);
      assert.equal(
        (await api('business-push/publish', { ...encrypted, sound: 'none' })).status,
        409,
      );
      assert.equal(
        (await api('business-push/status', { bindingId: binding, eventId: event.eventId })).body
          .state,
        'accepted',
      );
      assert.ok(!(await readFile(join(directory, 'state.json'), 'utf8')).includes(event.title));
      assert.equal(
        (await api('business-push/revoke', { bindingId: binding }, input.managementToken)).status,
        200,
      );
      assert.equal((await api('business-push/register', input)).status, 409);
      assert.equal((await api('business-push/publish', encrypted)).status, 403);
      assert.equal((await api('business-push/register', { ...input, generation: 2 })).status, 200);
    },
    async () => {
      count++;
      return { state: 'accepted' };
    },
  );
});

test('unknown APNs result survives restart without resubmission; unpublished revoke leaves tombstone', async () => {
  let count = 0;
  await fixture(
    async ({ api, restart }) => {
      const input = registration(),
        binding = (await api('business-push/register', input)).body.bindingId;
      const encrypted = envelope(event, binding, cipher(), cipher());
      await api('business-push/publish', encrypted);
      await settle();
      await restart();
      await api('business-push/publish', encrypted);
      await settle();
      assert.equal(count, 1);
      assert.equal(
        (await api('business-push/status', { bindingId: binding, eventId: event.eventId })).body
          .state,
        'uncertain',
      );
      const next = registration();
      assert.equal(
        (
          await api('business-push/unregister', {
            target: next.target,
            projectId: next.projectId,
            deviceId: next.deviceId,
            generation: 4,
          })
        ).status,
        200,
      );
      assert.equal((await api('business-push/register', { ...next, generation: 4 })).status, 409);
    },
    async () => {
      count++;
      return { state: 'uncertain' };
    },
  );
});

test('same credential cannot be both Host and business publisher', async () => {
  await assert.rejects(
    startPushServer(
      {
        statePath: '/unused',
        topic: 'test',
        teamId: 'synthetic',
        hosts: [{ hostId: identity.primaryHostId, token: 'a'.repeat(32) }],
        businessPublishers: [{ ...identity, token: 'a'.repeat(32) }],
      },
      { send: async () => ({ state: 'accepted' }) },
    ),
    /publisher configuration/,
  );
});

test('publisher removal fences queued deliveries and business topic is registered explicitly', async () => {
  let count = 0;
  await fixture(
    async ({ api, config, restart, directory }) => {
      const input = registration(),
        binding = (await api('business-push/register', input)).body.bindingId;
      await api('business-push/publish', envelope(event, binding, cipher(), cipher()));
      await settle();
      config.businessPublishers = [];
      await restart();
      assert.equal(
        (await api('business-push/status', { bindingId: binding, eventId: event.eventId })).status,
        401,
      );
      await settle();
      assert.equal(count, 1);
      const state = JSON.parse(await readFile(join(directory, 'state.json'), 'utf8'));
      assert.equal(state.business.bindings[binding].topic, 'test.business.dev');
      assert.equal(state.business.deliveries[binding + ':' + event.eventId].state, 'rejected');
    },
    async (binding) => {
      assert.equal(binding.topic, 'test.business.dev');
      count++;
      return { state: 'pending' };
    },
  );
});
