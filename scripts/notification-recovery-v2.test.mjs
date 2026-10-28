import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  randomUUID,
  generateKeyPairSync,
  createHash,
  createPrivateKey,
  diffieHellman,
} from 'node:crypto';
import { createServer } from 'node:http';
import { notificationRecoveryRoute } from '../apps/host-agent/dist/notification-recovery-routes.js';
import {
  NotificationPublisher,
  writeNotificationState,
} from '../packages/notification-runtime/dist/index.js';
import {
  NotificationRecoveryV2,
  intentHash,
  recoveryHash,
} from '../packages/notification-runtime/dist/recovery-v2.js';
import {
  defaultNotificationPreferences,
  notificationV2Canonical,
  parseNotificationIntentV2,
  parseNotificationFeedQueryV2,
  notificationRecoveryLimits as limits,
} from '../packages/protocol/dist/index.js';

const target = {
  agentId: 'codex',
  hostId: 'fixture-host',
  workspaceId: 'fixture-workspace',
  runtimeId: 'desktop-runtime',
};
const publicKey = () =>
  generateKeyPairSync('x25519')
    .publicKey.export({ format: 'der', type: 'spki' })
    .subarray(-32)
    .toString('base64');
async function fixture(t, overrides = {}) {
  const directory = await mkdtemp(join(tmpdir(), 'notification-recovery-v2-'));
  const statePath = join(directory, 'state.json');
  const ctx = {
    target: overrides.target ?? target,
    statePath,
    key: publicKey(),
    publisher: null,
    open() {
      this.publisher = new NotificationPublisher({ target: this.target, statePath, ...overrides });
      return this.publisher;
    },
    close() {
      const instance = this.publisher;
      this.publisher = null;
      instance?.close();
    },
    intent(values = {}) {
      const result = {
        version: 2,
        operationId: randomUUID(),
        epoch: this.publisher.recoveryCapabilities().epoch,
        deviceId: 'android-fixture',
        target: this.target,
        publicKey: this.key,
        preferences: {
          ...defaultNotificationPreferences(),
          enabled: true,
          generation: 1,
          revision: 'fixture-1',
        },
        generation: 1,
        kind: 'register',
        ...values,
        fingerprint: '',
      };
      result.fingerprint = intentHash(result);
      return result;
    },
  };
  ctx.open();
  t.after(async () => {
    try {
      ctx.close();
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  });
  return ctx;
}
function event(index, scope = target, extra = {}) {
  return {
    version: 1,
    id: createHash('sha256')
      .update('fixture-original-' + index)
      .digest('hex'),
    target: scope,
    kind: 'completed',
    occurredAt: new Date().toISOString(),
    threadId: 'fixture-thread',
    threadTitle: 'Synthetic task',
    taskSummary: 'Synthetic original summary',
    ...extra,
  };
}
const rejected = (status) => (error) => error?.status === status;
function reserveOnDisk(raw, path) {
  return new NotificationRecoveryV2({
    target: raw.target,
    read: () => raw.recoveryV2,
    commit: (next) => {
      raw.recoveryV2 = next;
      writeNotificationState(path, raw);
    },
    device: (id) => raw.devices[id],
    sources: () => raw.entries,
    validateKey: (key) => {
      diffieHellman({
        privateKey: createPrivateKey(raw.privateKey),
        publicKey: generateKeyPairSync('x25519').publicKey,
      });
      assert.equal(Buffer.from(key, 'base64').length, 32);
    },
    activeIds: () => {
      throw Error('Reserve must not read feed');
    },
    disposition: () => {
      throw Error('Reserve must not process events');
    },
  });
}

test('canonical cross-language subset preserves Unicode, array order and full intent', () => {
  assert.equal(
    notificationV2Canonical({
      z: '中😀\u2028</',
      a: [true, null, 9007199254740991],
      absent: undefined,
    }),
    '{"a":[true,null,9007199254740991],"z":"中😀\u2028</"}',
  );
  assert.equal(recoveryHash({ b: 2, a: 1 }), recoveryHash({ a: 1, b: 2 }));
  for (const invalid of [NaN, Infinity, 1.1, 9007199254740992, '\ud800', '\udfff', [undefined]])
    assert.throws(() => notificationV2Canonical(invalid));
  assert.notEqual(recoveryHash([1, 2]), recoveryHash([2, 1]));
});

test('lost reply recovers exact immutable receipt after restart, duplicate submission has no effects', async (t) => {
  const ctx = await fixture(t),
    intent = ctx.intent();
  assert.equal(ctx.publisher.recoverySubmit(intent).stage, 'applied');
  const original = JSON.parse(await readFile(ctx.statePath, 'utf8'));
  ctx.close();
  ctx.open();
  const query = ctx.publisher.recoveryReceipt(intent.deviceId, intent.operationId, intent.epoch);
  assert.equal(query.found, true);
  assert.deepEqual(query.receipt.intent, intent);
  assert.equal(query.receipt.stage, 'applied');
  assert.equal(ctx.publisher.recoverySubmit(intent).stage, 'applied');
  const current = JSON.parse(await readFile(ctx.statePath, 'utf8'));
  assert.equal(
    current.devices[intent.deviceId].subscriptionGeneration,
    original.devices[intent.deviceId].subscriptionGeneration,
  );
  for (const changes of [
    { publicKey: publicKey() },
    { generation: 2 },
    { preferences: { ...intent.preferences, showThreadTitle: false } },
    { kind: 'preferences' },
  ]) {
    const changed = { ...intent, ...changes };
    changed.fingerprint = intentHash(changed);
    assert.throws(() => ctx.publisher.recoverySubmit(changed), rejected(409));
  }
  assert.equal(JSON.parse(await readFile(ctx.statePath, 'utf8')).recoveryV2.operations.length, 1);
});

test('absent or new-epoch original ID remains unconfirmed, not an inferred revision receipt', async (t) => {
  const ctx = await fixture(t),
    intent = ctx.intent();
  const missing = ctx.publisher.recoveryReceipt(intent.deviceId, intent.operationId, intent.epoch);
  assert.equal(missing.found, false);
  assert.equal(missing.reason, 'absent-unknown');
  const wrongEpoch = ctx.publisher.recoveryReceipt(
    intent.deviceId,
    intent.operationId,
    randomUUID(),
  );
  assert.equal(wrongEpoch.found, false);
  assert.equal(wrongEpoch.reason, 'epoch-mismatch');
  assert.throws(
    () =>
      ctx.publisher.recoverySubmit({
        ...intent,
        epoch: randomUUID(),
        fingerprint: intent.fingerprint,
      }),
    rejected(400),
  );
  assert.equal(JSON.parse(await readFile(ctx.statePath, 'utf8')).recoveryV2.operations.length, 0);
});

test('crash after durable reservation applies saved original on actual publisher restart', async (t) => {
  const ctx = await fixture(t),
    intent = ctx.intent();
  ctx.close();
  const raw = JSON.parse(await readFile(ctx.statePath, 'utf8'));
  assert.equal(reserveOnDisk(raw, ctx.statePath).reserve(intent).stage, 'reserved');
  assert.equal(raw.devices[intent.deviceId], undefined);
  ctx.open();
  const result = ctx.publisher.recoveryReceipt(intent.deviceId, intent.operationId, intent.epoch);
  assert.equal(result.receipt.stage, 'applied');
  assert.equal(ctx.publisher.status(intent.deviceId).revision, intent.preferences.revision);
  ctx.close();
  ctx.open();
  assert.equal(
    JSON.parse(await readFile(ctx.statePath, 'utf8')).devices[intent.deviceId]
      .subscriptionGeneration,
    1,
  );
});

test('higher absent-device tombstone supersedes saved/later registrations through restart', async (t) => {
  const ctx = await fixture(t),
    old = ctx.intent();
  const off = ctx.intent({
    kind: 'revoke',
    generation: 2,
    preferences: { ...old.preferences, enabled: false },
  });
  ctx.close();
  const raw = JSON.parse(await readFile(ctx.statePath, 'utf8'));
  const recovery = reserveOnDisk(raw, ctx.statePath);
  recovery.reserve(old);
  recovery.reserve(off);
  ctx.open();
  assert.equal(
    ctx.publisher.recoveryReceipt(old.deviceId, old.operationId, old.epoch).receipt.reason,
    'superseded-generation',
  );
  assert.equal(
    ctx.publisher.recoveryReceipt(off.deviceId, off.operationId, off.epoch).receipt.stage,
    'applied',
  );
  assert.equal(ctx.publisher.status(old.deviceId).state, 'disabled');
  const late = ctx.intent({ generation: 1 });
  assert.equal(ctx.publisher.recoverySubmit(late).stage, 'rejected');
  const legacy = {
    version: 1,
    deviceId: old.deviceId,
    target,
    publicKey: ctx.key,
    preferences: old.preferences,
  };
  await assert.rejects(ctx.publisher.register(legacy), rejected(409));
  await assert.rejects(
    ctx.publisher.updatePreferences(old.deviceId, target, old.preferences),
    rejected(409),
  );
  await assert.rejects(ctx.publisher.unregister(old.deviceId), rejected(409));
  ctx.close();
  ctx.open();
  assert.equal(ctx.publisher.status(old.deviceId).state, 'disabled');
});

test('separate explicit off can fence unknown v1 without claiming it as a known v2 operation', async (t) => {
  const ctx = await fixture(t);
  const unknownV1Id = randomUUID();
  await ctx.publisher.register({
    version: 1,
    deviceId: 'android-fixture',
    target,
    publicKey: ctx.key,
    preferences: {
      ...defaultNotificationPreferences(),
      enabled: true,
      generation: 1,
      revision: 'old-v1',
    },
  });
  const off = ctx.intent({
    kind: 'revoke',
    preferences: {
      ...defaultNotificationPreferences(),
      enabled: false,
      generation: 2,
      revision: 'new-explicit-off',
    },
  });
  assert.equal(ctx.publisher.recoverySubmit(off).stage, 'applied');
  assert.equal(ctx.publisher.recoveryReceipt(off.deviceId, unknownV1Id, off.epoch).found, false);
  assert.equal(ctx.publisher.status(off.deviceId).state, 'disabled');
});

test('old revoke microtask cannot disable a later explicitly enabled generation', async (t) => {
  const ctx = await fixture(t),
    off = ctx.intent({
      kind: 'revoke',
      preferences: { ...defaultNotificationPreferences(), enabled: false },
    });
  ctx.publisher.recoverySubmit(off);
  const on = ctx.intent({
    generation: 2,
    preferences: {
      ...defaultNotificationPreferences(),
      enabled: true,
      generation: 3,
      revision: 'explicit-on',
    },
  });
  assert.equal(ctx.publisher.recoverySubmit(on).stage, 'applied');
  await new Promise((resolve) => setImmediate(resolve));
  assert.notEqual(ctx.publisher.status(on.deviceId).state, 'disabled');
});

test('owner and original operation identity remain separate from legacy Codex account metadata', async (t) => {
  const ctx = await fixture(t);
  const intent = ctx.intent({
    target: { ...target, account: { authority: 'old', subject: 'old-user' } },
  });
  ctx.publisher.recoverySubmit(intent, 'owner-a');
  assert.throws(
    () =>
      ctx.publisher.recoveryReceipt(intent.deviceId, intent.operationId, intent.epoch, 'owner-b'),
    rejected(403),
  );
  const changed = {
    ...intent,
    target: { ...target, account: { authority: 'new', subject: 'new-user' } },
  };
  changed.fingerprint = intentHash(changed);
  assert.throws(() => ctx.publisher.recoverySubmit(changed, 'owner-a'), rejected(409));
  const next = ctx.intent({
    kind: 'preferences',
    generation: 2,
    target: changed.target,
    preferences: { ...intent.preferences, generation: 2, revision: 'next' },
  });
  assert.equal(ctx.publisher.recoverySubmit(next, 'owner-a').stage, 'applied');
});

test('v1/iOS devices and wire remain available; activating v2 fences older disk writers', async (t) => {
  const ctx = await fixture(t),
    preferences = {
      ...defaultNotificationPreferences(),
      enabled: true,
      generation: 1,
      revision: 'ios-legacy',
    };
  const registration = {
    version: 1,
    deviceId: 'ios-legacy',
    target,
    publicKey: ctx.key,
    preferences,
  };
  await ctx.publisher.register(registration);
  assert.equal(JSON.parse(await readFile(ctx.statePath, 'utf8')).version, 1);
  assert.equal(ctx.publisher.status('ios-legacy').version, 1);
  const capability = ctx.publisher.recoveryCapabilities();
  assert.equal(capability.version, 2);
  assert.equal(JSON.parse(await readFile(ctx.statePath, 'utf8')).version, 2);
  await ctx.publisher.updatePreferences('ios-legacy', target, {
    ...preferences,
    generation: 2,
    revision: 'ios-next',
  });
  const feed = ctx.publisher.foreground('ios-legacy', '');
  assert.deepEqual(Object.keys(feed).sort(), ['activeIds', 'cursor', 'events', 'status']);
  ctx.close();
  ctx.open();
  assert.equal(ctx.publisher.recoveryCapabilities().epoch, capability.epoch);
  assert.equal(ctx.publisher.status('ios-legacy').revision, 'ios-next');
});

test('live feed is contiguous/paged with fixed high-water; new originals wait for next chain', async (t) => {
  const ctx = await fixture(t),
    intent = ctx.intent();
  ctx.publisher.recoverySubmit(intent);
  const events = Array.from({ length: 80 }, (_, i) => event(i));
  ctx.publisher.observe(events, 'fixture-thread', [], true);
  const query = {
    version: 2,
    deviceId: intent.deviceId,
    epoch: intent.epoch,
    generation: 1,
    after: 0,
  };
  const first = ctx.publisher.recoveryFeed(query);
  assert.equal(first.kind, 'page');
  assert.equal(first.items.length, 32);
  assert.equal(first.through, 80);
  ctx.publisher.observe([event(80)], 'fixture-thread', [], true);
  const result = [...first.items];
  let page = first;
  while (page.hasMore) {
    page = ctx.publisher.recoveryFeed({ ...query, after: page.next, through: first.through });
    assert.equal(page.kind, 'page');
    result.push(...page.items);
  }
  assert.deepEqual(
    result.map((i) => i.sequence),
    Array.from({ length: 80 }, (_, i) => i + 1),
  );
  assert.deepEqual(
    result.map((i) => i.event.id),
    events.map((e) => e.id),
  );
  assert.ok(result.every((i) => i.fingerprint === recoveryHash(i.event)));
  assert.equal(ctx.publisher.recoveryFeed({ ...query, after: 80 }).items[0].event.id, event(80).id);
});

test('filtered originals are represented rather than skipped when advancing a page', async (t) => {
  const ctx = await fixture(t),
    intent = ctx.intent({
      preferences: {
        ...defaultNotificationPreferences(),
        enabled: true,
        badgeEnabled: false,
        kinds: ['approval'],
        revision: 'filtered',
        generation: 1,
      },
    });
  ctx.publisher.recoverySubmit(intent);
  ctx.publisher.observe(
    Array.from({ length: 40 }, (_, i) => event(i)),
    'fixture-thread',
    [],
    true,
  );
  const page = ctx.publisher.recoveryFeed({
    version: 2,
    deviceId: intent.deviceId,
    epoch: intent.epoch,
    generation: 1,
    after: 0,
  });
  assert.equal(page.items.length, 32);
  assert.ok(page.items.every((i) => i.disposition === 'filtered'));
  assert.equal(page.next, 32);
});

test('byte budget bounds valid large originals independently of the count limit', async (t) => {
  const scope = {
    agentId: 'codex',
    hostId: '机'.repeat(256),
    workspaceId: '工'.repeat(256),
    runtimeId: '运'.repeat(256),
    account: { authority: '旧'.repeat(256), subject: '账'.repeat(256) },
  };
  const ctx = await fixture(t, { target: scope }),
    intent = ctx.intent();
  ctx.publisher.recoverySubmit(intent);
  const events = Array.from({ length: 40 }, (_, i) =>
    event(i, scope, {
      readTarget: scope,
      readPosition: { revision: 1, messageId: 'm'.repeat(512), contentVersion: 'a'.repeat(64) },
      threadTitle: '题'.repeat(100),
      taskSummary: '文'.repeat(160),
    }),
  );
  ctx.publisher.observe(events, 'fixture-thread', [], true);
  const page = ctx.publisher.recoveryFeed({
    version: 2,
    deviceId: intent.deviceId,
    epoch: intent.epoch,
    generation: 1,
    after: 0,
  });
  assert.equal(page.kind, 'page');
  assert.ok(page.items.length < 32 && page.items.length > 0);
  assert.ok(Buffer.byteLength(notificationV2Canonical(page)) <= limits.pageBytes);
});

test('retention gap never silently jumps; explicit original reset produces durable paged snapshot', async (t) => {
  const ctx = await fixture(t),
    registration = ctx.intent();
  ctx.publisher.recoverySubmit(registration);
  ctx.publisher.observe(
    Array.from({ length: 2049 }, (_, i) => event(i)),
    'fixture-thread',
    [],
    true,
  );
  const query = {
    version: 2,
    deviceId: registration.deviceId,
    epoch: registration.epoch,
    generation: 1,
    after: 0,
  };
  const gap = ctx.publisher.recoveryFeed(query);
  assert.equal(gap.kind, 'reset-required');
  assert.equal(gap.floor, 2);
  assert.equal(gap.head, 2049);
  const reset = ctx.intent({
    kind: 'feed-reset',
    gap: {
      epoch: gap.epoch,
      after: 0,
      floor: gap.floor,
      head: gap.head,
      acceptanceId: randomUUID(),
    },
  });
  const receipt = ctx.publisher.recoverySubmit(reset);
  assert.equal(receipt.stage, 'applied');
  assert.equal(receipt.snapshot.count, 2048);
  assert.equal(receipt.snapshot.baseline, 2049);
  ctx.close();
  ctx.open();
  assert.deepEqual(
    ctx.publisher.recoveryReceipt(reset.deviceId, reset.operationId, reset.epoch).receipt,
    receipt,
  );
  const page = ctx.publisher.recoveryFeed({ ...query, resetId: reset.operationId });
  assert.equal(page.kind, 'page');
  assert.equal(page.mode, 'reset');
  assert.equal(page.items[0].event.id, event(1).id);
  assert.equal(page.snapshotHash, receipt.snapshot.hash);
  assert.equal(
    ctx.publisher.recoveryFeed({ ...query, epoch: randomUUID() }).reason,
    'epoch-mismatch',
  );
  assert.equal(
    ctx.publisher.recoveryFeed({ ...query, resetId: randomUUID() }).reason,
    'snapshot-expired-or-missing',
  );
  assert.equal(
    ctx.publisher.recoveryReceipt(reset.deviceId, registration.operationId, reset.epoch).receipt
      .stage,
    'applied',
  );
});

test('changed original is rejected before v1/v2 state can mutate', async (t) => {
  const ctx = await fixture(t),
    intent = ctx.intent();
  ctx.publisher.recoverySubmit(intent);
  const original = event(1);
  ctx.publisher.observe([original], 'fixture-thread', [], true);
  const before = await readFile(ctx.statePath);
  assert.throws(() =>
    ctx.publisher.observe(
      [{ ...original, taskSummary: 'Changed intent' }],
      'fixture-thread',
      [],
      true,
    ),
  );
  assert.deepEqual(await readFile(ctx.statePath), before);
});

test('operation capacity rejects before effects while exact old queries remain readable', async (t) => {
  const ctx = await fixture(t),
    intent = ctx.intent();
  ctx.publisher.recoverySubmit(intent);
  ctx.close();
  const raw = JSON.parse(await readFile(ctx.statePath, 'utf8')),
    original = raw.recoveryV2.operations[0];
  for (let i = 1; i < limits.operations; i++) {
    const copy = structuredClone(original);
    copy.receipt.intent.operationId = randomUUID();
    copy.receipt.intent.fingerprint = intentHash(copy.receipt.intent);
    raw.recoveryV2.operations.push(copy);
  }
  writeNotificationState(ctx.statePath, raw);
  ctx.open();
  const before = await readFile(ctx.statePath);
  assert.throws(
    () => ctx.publisher.recoverySubmit(ctx.intent({ generation: 2, kind: 'preferences' })),
    rejected(507),
  );
  assert.deepEqual(await readFile(ctx.statePath), before);
  assert.equal(
    ctx.publisher.recoveryReceipt(intent.deviceId, intent.operationId, intent.epoch).found,
    true,
  );
});

test('failed publication poisons mutation/receipt access until actual durable restart; original bytes retained', async (t) => {
  let fail = false;
  const ctx = await fixture(t, {
    protectFile: () => {
      if (fail) throw Error('fixture disk protection failure');
    },
  });
  const intent = ctx.intent(),
    before = await readFile(ctx.statePath);
  fail = true;
  assert.throws(() => ctx.publisher.recoverySubmit(intent));
  fail = false;
  assert.throws(
    () => ctx.publisher.recoveryReceipt(intent.deviceId, intent.operationId, intent.epoch),
    rejected(503),
  );
  await assert.rejects(
    ctx.publisher.register({
      version: 1,
      deviceId: 'other',
      target,
      publicKey: ctx.key,
      preferences: intent.preferences,
    }),
    rejected(503),
  );
  assert.deepEqual(await readFile(ctx.statePath), before);
  assert.throws(() => ctx.close());
  ctx.open();
  assert.equal(
    ctx.publisher.recoveryReceipt(intent.deviceId, intent.operationId, intent.epoch).found,
    false,
  );
});

test('unknown/corrupt recovery disk versions preserve the file and never downgrade', async (t) => {
  const ctx = await fixture(t);
  ctx.publisher.recoveryCapabilities();
  ctx.close();
  const raw = JSON.parse(await readFile(ctx.statePath, 'utf8'));
  for (const corrupt of [
    { ...raw, version: 3 },
    { ...raw, recoveryV2: { ...raw.recoveryV2, version: 3 } },
    { ...raw, recoveryV2: { ...raw.recoveryV2, floor: 99 } },
  ]) {
    writeNotificationState(ctx.statePath, corrupt);
    const before = await readFile(ctx.statePath);
    assert.throws(() => ctx.open());
    assert.deepEqual(await readFile(ctx.statePath), before);
    ctx.publisher = null;
  }
});

test('strict v2 contract rejects coercion, unknown fields and noncanonical key bytes', async (t) => {
  const ctx = await fixture(t),
    intent = ctx.intent();
  for (const extra of [
    { generation: '1' },
    { version: 3 },
    { generation: 1.1 },
    { deviceId: '__proto__' },
    { arbitraryIPC: 'call' },
    { publicKey: intent.publicKey.slice(0, 42) + 'B=' },
  ])
    assert.throws(() => parseNotificationIntentV2({ ...intent, ...extra }));
  assert.throws(() =>
    parseNotificationFeedQueryV2({
      version: 2,
      deviceId: intent.deviceId,
      epoch: intent.epoch,
      generation: 1,
      after: '0',
    }),
  );
  assert.throws(() =>
    parseNotificationFeedQueryV2({
      version: 2,
      deviceId: intent.deviceId,
      epoch: intent.epoch,
      generation: 1,
      after: 0,
      unknown: true,
    }),
  );
});

test('late v1 APNs reply cannot revive separately fenced v2 revoke (owned fake relay only)', async (t) => {
  let release;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  let entered;
  const started = new Promise((resolve) => {
    entered = resolve;
  });
  const requests = [],
    server = createServer(async (request, response) => {
      const chunks = [];
      for await (const chunk of request) chunks.push(chunk);
      const body = JSON.parse(Buffer.concat(chunks).toString());
      requests.push({ path: request.url, generation: body.generation });
      if (request.url === '/v1/push/register') {
        entered();
        await gate;
      }
      response.setHeader('content-type', 'application/json');
      response.end(
        JSON.stringify(
          request.url === '/v1/push/register'
            ? { bindingId: 'fixture-binding', managementToken: 'fixture-management' }
            : { ok: true },
        ),
      );
    });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => {
    release();
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
  });
  const ctx = await fixture(t, {
    relayURL: 'http://127.0.0.1:' + server.address().port,
    publisherToken: 'fixture-publisher-token',
  });
  const pending = ctx.publisher.register({
    version: 1,
    deviceId: 'android-fixture',
    target,
    publicKey: ctx.key,
    deviceToken: 'a'.repeat(64),
    environment: 'development',
    preferences: {
      ...defaultNotificationPreferences(),
      enabled: true,
      generation: 1,
      revision: 'old-unknown',
    },
  });
  await started;
  const off = ctx.intent({
    kind: 'revoke',
    generation: 2,
    preferences: {
      ...defaultNotificationPreferences(),
      enabled: false,
      generation: 2,
      revision: 'off',
    },
  });
  assert.equal(ctx.publisher.recoverySubmit(off).stage, 'applied');
  await new Promise((resolve) => setTimeout(resolve, 40));
  release();
  await pending;
  await new Promise((resolve) => setTimeout(resolve, 40));
  assert.equal(ctx.publisher.status(off.deviceId).state, 'disabled');
  assert.ok(
    requests.some(
      (r) =>
        r.path === '/v1/push/unregister' &&
        r.generation > requests.find((r) => r.path === '/v1/push/register').generation,
    ),
  );
});

async function routeFixture(t, ctx) {
  const token = 'fixture-route-token';
  const server = createServer(async (request, response) => {
    try {
      if (request.headers.authorization !== 'Bearer ' + token) {
        response.writeHead(401).end();
        return;
      }
      const chunks = [];
      let bytes = 0;
      for await (const chunk of request) {
        bytes += chunk.length;
        if (bytes > 300_000) throw Error('Fixture request bound');
        chunks.push(chunk);
      }
      const data = chunks.length ? JSON.parse(Buffer.concat(chunks).toString()) : {};
      const result = notificationRecoveryRoute(
        request.method,
        new URL(request.url, 'http://127.0.0.1'),
        data,
        ctx.publisher,
        ctx.target,
      );
      response.setHeader('content-type', 'application/json');
      response.writeHead(result === undefined ? 404 : 200);
      response.end(JSON.stringify(result ?? {}));
    } catch (failure) {
      response.writeHead(failure.status ?? 500);
      response.end(JSON.stringify({ error: 'Fixture rejected request' }));
    }
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => {
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
  });
  const base = 'http://127.0.0.1:' + server.address().port + '/v1/notifications/recovery-v2/';
  const url = (route, extra = {}) => {
    const parameters = new URLSearchParams({
      hostId: ctx.target.hostId,
      target: JSON.stringify(ctx.target),
      deviceId: 'android-fixture',
      ...extra,
    });
    return base + route + '?' + parameters.toString();
  };
  const request = (address, body) =>
    fetch(address, {
      method: body ? 'POST' : 'GET',
      headers: { authorization: 'Bearer ' + token, 'content-type': 'application/json' },
      ...(body ? { body: JSON.stringify(body) } : {}),
    });
  return { url, request };
}

test('actual fixed HTTP route dispatcher supports submit/query/feed with exact original receipt', async (t) => {
  const ctx = await fixture(t),
    http = await routeFixture(t, ctx);
  const capability = await (await http.request(http.url('capabilities'))).json();
  assert.equal(capability.version, 2);
  const intent = ctx.intent();
  const submission = await http.request(http.url('operations'), { hostId: target.hostId, intent });
  assert.equal(submission.status, 200);
  assert.equal((await submission.json()).stage, 'applied');
  const found = await (
    await http.request(
      http.url('operations', { operationId: intent.operationId, epoch: intent.epoch }),
    )
  ).json();
  assert.equal(found.found, true);
  assert.deepEqual(found.receipt.intent, intent);
  const feed = await (
    await http.request(http.url('feed', { epoch: intent.epoch, generation: '1', after: '0' }))
  ).json();
  assert.equal(feed.kind, 'page');
  assert.equal((await fetch(http.url('capabilities'))).status, 401);
});

test('fixed route rejects missing/foreign/duplicate target fields and arbitrary methods without effects', async (t) => {
  const ctx = await fixture(t),
    http = await routeFixture(t, ctx),
    intent = ctx.intent();
  for (const [url, status] of [
    [http.url('capabilities', { hostId: 'foreign' }), 400],
    [http.url('capabilities', { target: '{}' }), 400],
    [
      http.url('capabilities', { target: JSON.stringify({ ...target, runtimeId: 'foreign' }) }),
      409,
    ],
    [http.url('capabilities') + '&deviceId=duplicate', 400],
    [http.url('capabilities', { arbitraryIPC: 'execute' }), 400],
    [http.url('execute-shell'), 404],
    [http.url('feed', { epoch: intent.epoch, generation: '1', after: '00' }), 400],
  ])
    assert.equal((await http.request(url)).status, status);
  assert.equal(
    (
      await http.request(http.url('operations'), {
        hostId: target.hostId,
        intent,
        shell: 'forbidden',
      })
    ).status,
    400,
  );
  assert.equal(
    (
      await http.request(http.url('operations', { operationId: randomUUID() }), {
        hostId: target.hostId,
        intent,
      })
    ).status,
    400,
  );
  assert.equal(JSON.parse(await readFile(ctx.statePath, 'utf8')).recoveryV2.operations.length, 0);
});

test('account-only observation change retains the first raw event and original fingerprint', async (t) => {
  const ctx = await fixture(t),
    intent = ctx.intent();
  ctx.publisher.recoverySubmit(intent);
  const original = event(1, { ...target, account: { authority: 'old', subject: 'old' } });
  ctx.publisher.observe([original], 'fixture-thread', [], true);
  ctx.publisher.observe(
    [{ ...original, target: { ...target, account: { authority: 'new', subject: 'new' } } }],
    'fixture-thread',
    [],
    true,
  );
  const page = ctx.publisher.recoveryFeed({
    version: 2,
    deviceId: intent.deviceId,
    epoch: intent.epoch,
    generation: 1,
    after: 0,
  });
  assert.equal(page.items.length, 1);
  assert.deepEqual(page.items[0].event, original);
  assert.equal(page.items[0].fingerprint, recoveryHash(original));
});

test('new v2 IDs cannot reuse consumed generations or regress restored preference generations', async (t) => {
  const ctx = await fixture(t),
    registration = ctx.intent({
      preferences: {
        ...defaultNotificationPreferences(),
        enabled: true,
        revision: 'higher-pref',
        generation: 5,
      },
    });
  ctx.publisher.recoverySubmit(registration);
  const off = ctx.intent({
    kind: 'revoke',
    generation: 2,
    preferences: {
      ...defaultNotificationPreferences(),
      enabled: false,
      revision: 'explicit-off',
      generation: 1,
    },
  });
  assert.equal(ctx.publisher.recoverySubmit(off).stage, 'applied');
  const stale = ctx.intent({
    generation: 3,
    preferences: {
      ...defaultNotificationPreferences(),
      enabled: true,
      revision: 'restored-old',
      generation: 2,
    },
  });
  assert.equal(ctx.publisher.recoverySubmit(stale).reason, 'preferences-generation-conflict');
  const reused = ctx.intent({
    generation: 3,
    preferences: {
      ...defaultNotificationPreferences(),
      enabled: true,
      revision: 'current',
      generation: 6,
    },
  });
  assert.equal(ctx.publisher.recoverySubmit(reused).reason, 'stale-generation');
  const current = ctx.intent({ generation: 4, preferences: reused.preferences });
  assert.equal(ctx.publisher.recoverySubmit(current).stage, 'applied');
});

test('v2 generated schema and canonical vectors validate actual receipts, pages and intent constraints', async (t) => {
  const { default: Ajv } = await import('ajv');
  const old = JSON.parse(
    await readFile('packages/protocol/schemas/task-notifications-v1.schema.json', 'utf8'),
  );
  const schema = JSON.parse(
    await readFile('packages/protocol/schemas/notification-recovery-v2.schema.json', 'utf8'),
  );
  const vectors = JSON.parse(
    await readFile('packages/protocol/fixtures/mobile/notification-recovery-v2.json', 'utf8'),
  );
  const ajv = new Ajv({ allErrors: true });
  ajv.addSchema(old);
  const validate = ajv.compile(schema);
  for (const vector of vectors.vectors) {
    assert.equal(intentHash(vector.intent), vector.expectedFingerprint);
    assert.equal(validate(vector.intent), true, JSON.stringify(validate.errors));
  }
  const ctx = await fixture(t),
    intent = ctx.intent();
  const receipt = ctx.publisher.recoverySubmit(intent);
  for (const response of [
    ctx.publisher.recoveryCapabilities(),
    receipt,
    ctx.publisher.recoveryReceipt(intent.deviceId, intent.operationId, intent.epoch),
    ctx.publisher.recoveryFeed({
      version: 2,
      deviceId: intent.deviceId,
      epoch: intent.epoch,
      generation: 1,
      after: 0,
    }),
  ])
    assert.equal(validate(response), true, JSON.stringify(validate.errors));
  assert.equal(validate({ ...intent, kind: 'feed-reset' }), false);
  assert.equal(validate({ ...intent, kind: 'revoke' }), false);
  assert.equal(validate({ ...intent, unknown: true }), false);
});

test('array kinds and trailing line breaks cannot be coerced into executable v2 intents', async (t) => {
  const ctx = await fixture(t),
    original = ctx.intent();
  for (const changes of [
    { kind: ['register'] },
    { kind: { value: 'register' } },
    { deviceId: 'android-fixture\n' },
    { operationId: original.operationId + '\n' },
    { epoch: original.epoch + '\r' },
    { publicKey: original.publicKey + '\n' },
    { fingerprint: original.fingerprint + '\n' },
  ]) {
    const candidate = { ...original, ...changes };
    assert.throws(() => ctx.publisher.recoverySubmit(candidate), rejected(400));
  }
  assert.equal(JSON.parse(await readFile(ctx.statePath, 'utf8')).recoveryV2.operations.length, 0);
});

test('owner loss/restart preserves actionable active IDs; stale eligibility is not resolution', async (t) => {
  const ctx = await fixture(t),
    intent = ctx.intent();
  ctx.publisher.recoverySubmit(intent);
  const original = event(77, target, { kind: 'approval', requestId: 'fixture-request' });
  ctx.publisher.observe([original], 'fixture-thread', [original.id], true);
  ctx.close();
  ctx.open();
  const page = ctx.publisher.recoveryFeed({
    version: 2,
    deviceId: intent.deviceId,
    epoch: intent.epoch,
    generation: 1,
    after: 0,
  });
  assert.equal(page.kind, 'page');
  assert.equal(page.items[0].disposition, 'stale');
  assert.deepEqual(page.activeIds, [original.id]);
  ctx.publisher.observe([], 'fixture-thread', [], false);
  assert.deepEqual(
    ctx.publisher.recoveryFeed({
      version: 2,
      deviceId: intent.deviceId,
      epoch: intent.epoch,
      generation: 1,
      after: 0,
    }).activeIds,
    [original.id],
  );
  ctx.publisher.observe([], 'fixture-thread', [], true);
  const resolved = ctx.publisher.recoveryFeed({
    version: 2,
    deviceId: intent.deviceId,
    epoch: intent.epoch,
    generation: 1,
    after: 0,
  });
  assert.equal(resolved.items[0].disposition, 'inactive');
  assert.deepEqual(resolved.activeIds, []);
});

test('v2 authoritative pending IDs are independent of preference filtering and expired originals stay stale', async (t) => {
  const ctx = await fixture(t),
    intent = ctx.intent({
      preferences: {
        ...defaultNotificationPreferences(),
        enabled: true,
        kinds: ['approval'],
        badgeEnabled: false,
        revision: 'muted-kind',
        generation: 1,
      },
    });
  ctx.publisher.recoverySubmit(intent);
  const pending = event(88, target, { kind: 'question', requestId: 'fixture-question' }),
    expired = event(89, target, { occurredAt: new Date(Date.now() - 601000).toISOString() });
  ctx.publisher.observe([pending, expired], 'fixture-thread', [pending.id], true);
  const query = {
    version: 2,
    deviceId: intent.deviceId,
    epoch: intent.epoch,
    generation: 1,
    after: 0,
  };
  const page = ctx.publisher.recoveryFeed(query);
  assert.deepEqual(page.activeIds, [pending.id]);
  assert.equal(page.items[0].disposition, 'filtered');
  assert.equal(page.items[1].disposition, 'stale');
  ctx.publisher.observe([], 'fixture-thread', [], true);
  const resolved = ctx.publisher.recoveryFeed(query);
  assert.deepEqual(resolved.activeIds, []);
  assert.equal(resolved.items[0].disposition, 'inactive');
});
