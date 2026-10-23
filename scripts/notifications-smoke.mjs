import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtemp, readFile, rm, writeFile, chmod } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  createDecipheriv,
  diffieHellman,
  generateKeyPairSync,
  hkdfSync,
  verify,
} from 'node:crypto';
import {
  defaultNotificationPreferences,
  parseNotificationPreferences,
  parseNotificationRegistration,
  parseNotificationEvent,
  parseNotificationEnvelope,
  notificationTaskSummary,
  normalizeNotificationTaskSummary,
} from '../packages/protocol/dist/index.js';
import { NotificationObserver } from '../packages/notification-runtime/dist/index.js';
import {
  NotificationPublisher,
  sealNotification,
  notificationPublicKey,
} from '../packages/notification-runtime/dist/index.js';
import {
  createAPNsAuthorizer,
  createAPNsSender,
  notificationAPNsPayload,
  startPushServer,
} from '../apps/relay/dist/push-server.js';
import Ajv from 'ajv';
import { createServer } from 'node:http';
import { RuntimeSession } from '../packages/codex-adapter/dist/runtime-session.js';

const target = {
  agentId: 'codex',
  hostId: 'test-host',
  workspaceId: 'test-host',
  runtimeId: 'desktop-runtime',
};
const preferences = () => ({
  ...defaultNotificationPreferences(),
  enabled: true,
  revision: 'test',
  generation: 1,
});
const observed = (overrides) => ({
  threadId: 'thread-a',
  title: 'Fixture task',
  live: true,
  runId: 'run-a',
  outcome: 'inProgress',
  requests: [],
  uncertainOperations: [],
  ...overrides,
});
const pair = () => {
  const key = generateKeyPairSync('x25519');
  return {
    ...key,
    raw: key.publicKey.export({ format: 'der', type: 'spki' }).subarray(-32).toString('base64'),
  };
};
const waitFor = async (check) => {
  const deadline = Date.now() + 5000;
  while (!(await check())) {
    if (Date.now() > deadline) throw Error('Notification boundary timed out');
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
};

test('encrypted mobile reply versions are scoped and silent badge updates carry no host-local count', async () => {
  const reading = { ...target, account: { authority: 'local', subject: 'phone-account' } };
  const position = { revision: 2, messageId: 'reply', contentVersion: 'a'.repeat(64) };
  const observer = new NotificationObserver(target);
  observer.observe(observed({ readTarget: reading }));
  const completed = observer.observe(
    observed({ outcome: 'completed', readTarget: reading, readPosition: position }),
  ).events[0];
  assert.deepEqual(parseNotificationEvent(completed).readPosition, position);
  assert.deepEqual(completed.readTarget, reading);
  assert.throws(() =>
    parseNotificationEvent({ ...completed, readTarget: { ...reading, hostId: 'wrong' } }),
  );
  assert.throws(() =>
    parseNotificationEvent({ ...completed, readPosition: { ...position, revision: 0 } }),
  );
  const directory = await mkdtemp(join(tmpdir(), 'mobile-unread-push-'));
  const sent = [],
    token = 'p'.repeat(40);
  const relay = await startPushServer(
    {
      statePath: join(directory, 'relay.json'),
      topic: 'test.bundle',
      teamId: 'fixture',
      keyId: 'fixture',
      keyPath: 'unused',
      hosts: [{ hostId: target.hostId, token }],
    },
    {
      port: 0,
      send: async (_, envelope) => {
        sent.push(envelope);
        return { state: 'accepted' };
      },
    },
  );
  const publisher = new NotificationPublisher({
    target,
    statePath: join(directory, 'host.json'),
    relayURL: 'http://127.0.0.1:' + relay.server.address().port,
    publisherToken: token,
  });
  try {
    await publisher.register({
      version: 1,
      deviceId: 'phone-badge',
      target,
      publicKey: pair().raw,
      deviceToken: 'a'.repeat(64),
      environment: 'development',
      preferences: { ...preferences(), kinds: [] },
    });
    publisher.observe([completed], 'thread-a', [], true);
    await waitFor(() => sent.length === 1);
    assert.equal(sent[0].badgeOnly, true);
    assert.equal(sent[0].sound, 'none');
    assert.equal(sent[0].timeSensitive, false);
    assert.equal(sent[0].badge, undefined);
    assert.deepEqual(notificationAPNsPayload(parseNotificationEnvelope(sent[0]), 'binding').aps, {
      'content-available': 1,
    });
    assert(!JSON.stringify(sent[0]).includes('phone-account'));
    assert.throws(() => parseNotificationEnvelope({ ...sent[0], badge: 2 }));
    assert.throws(() => parseNotificationEnvelope({ ...sent[0], sound: 'default' }));
  } finally {
    publisher.close();
    relay.close();
    await rm(directory, { recursive: true, force: true });
  }
});

test('APNs sandbox and production credentials sign independently and missing environments reject', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'notification-keys-'));
  const development = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const production = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const keys = {
    development: { keyId: 'DEVELOPKEY', keyPath: join(directory, 'sandbox.p8') },
    production: { keyId: 'PRODUCTKEY', keyPath: join(directory, 'production.p8') },
  };
  const config = {
    keys,
    topic: 'test.notification',
    teamId: 'TESTTEAM',
    statePath: join(directory, 'push.json'),
    hosts: [{ hostId: target.hostId, token: 'fixture-publisher-credential-32-characters' }],
  };
  try {
    for (const [environment, pair] of Object.entries({ development, production }))
      await writeFile(
        keys[environment].keyPath,
        pair.privateKey.export({ type: 'pkcs8', format: 'pem' }),
        { mode: 0o600 },
      );
    const authorize = createAPNsAuthorizer(config);
    for (const [environment, pair] of Object.entries({ development, production })) {
      const token = authorize(environment);
      assert.equal(authorize(environment), token);
      const [header, payload, signature] = token.split('.');
      assert.equal(JSON.parse(Buffer.from(header, 'base64url')).kid, keys[environment].keyId);
      assert.equal(JSON.parse(Buffer.from(payload, 'base64url')).iss, config.teamId);
      const data = Buffer.from(header + '.' + payload);
      const decodedSignature = Buffer.from(signature, 'base64url');
      assert(
        verify(
          'sha256',
          data,
          { key: pair.publicKey, dsaEncoding: 'ieee-p1363' },
          decodedSignature,
        ),
      );
      const other = environment === 'development' ? production : development;
      assert(
        !verify(
          'sha256',
          data,
          { key: other.publicKey, dsaEncoding: 'ieee-p1363' },
          decodedSignature,
        ),
      );
    }
    const onlyDevelopment = { ...config, keys: { development: keys.development } };
    assert.equal(createAPNsAuthorizer(onlyDevelopment)('production'), undefined);
    assert.deepEqual(await createAPNsSender(onlyDevelopment)({ environment: 'production' }, {}), {
      state: 'rejected',
      reason: 'APNsEnvironmentNotConfigured',
    });
    const legacy = createAPNsAuthorizer({ ...config, keys: undefined, ...keys.development });
    assert(legacy('development'));
    assert(legacy('production'));
    assert.throws(() => createAPNsAuthorizer({ ...config, keys: {} }));
    const server = await startPushServer(onlyDevelopment, { port: 0 });
    try {
      const port = server.server.address().port;
      const response = await fetch('http://127.0.0.1:' + port + '/v1/push/register', {
        method: 'POST',
        headers: {
          authorization: 'Bearer ' + config.hosts[0].token,
          'content-type': 'application/json',
        },
        body: JSON.stringify({
          hostId: target.hostId,
          deviceId: 'fixture-phone',
          target,
          deviceToken: 'a'.repeat(64),
          environment: 'production',
          generation: 1,
          managementToken: 'b'.repeat(43),
        }),
      });
      assert.equal(response.status, 503);
      assert.equal((await response.json()).error, 'APNsEnvironmentNotConfigured');
      assert.equal(
        Object.keys(JSON.parse(await readFile(config.statePath, 'utf8')).bindings).length,
        0,
      );
    } finally {
      server.close();
    }
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('notification input rejects unknown operations, types, paths and targets', () => {
  assert.equal(defaultNotificationPreferences().enabled, false);
  assert.throws(() => parseNotificationPreferences({ ...preferences(), operation: 'shell' }));
  assert.throws(() =>
    parseNotificationPreferences({ ...preferences(), sound: '../../attachment.caf' }),
  );
  assert.throws(() => parseNotificationPreferences({ ...preferences(), kinds: ['unknown'] }));
  assert.throws(() =>
    parseNotificationRegistration({
      version: 1,
      deviceId: 'phone',
      target: { ...target, runtimeId: '' },
      publicKey: pair().raw,
      preferences: preferences(),
    }),
  );
});

test('versioned JSON contract validates named commands and strict input', async () => {
  const schema = JSON.parse(
    await readFile('packages/protocol/schemas/task-notifications-v1.schema.json', 'utf8'),
  );
  const validator = new Ajv({ allErrors: true, strictKeywords: true }).compile(schema);
  const command = {
    version: 1,
    target,
    method: 'notification/preferences',
    params: { deviceId: 'phone', preferences: preferences() },
  };
  assert.equal(validator(command), true, JSON.stringify(validator.errors));
  for (const bad of [
    { ...command, method: 'shell' },
    { ...command, params: { ...command.params, deviceId: '__proto__' } },
    { ...command, target: { ...target, extra: 'wire' } },
  ])
    assert.equal(validator(bad), false);
});

test('v1 task summaries share strict UTF-16 limits with schema and accept old events', async () => {
  const schema = JSON.parse(
    await readFile('packages/protocol/schemas/task-notifications-v1.schema.json', 'utf8'),
  );
  const validator = new Ajv({ allErrors: true, strictKeywords: true }).compile(schema);
  const old = new NotificationObserver(target).observe(
    observed({ requests: [{ id: 'legacy-request', runId: 'run-a', kind: 'approval' }] }),
  ).events[0];
  assert.equal(old.version, 1);
  assert.equal(old.taskSummary, undefined);
  assert.deepEqual(parseNotificationEvent(JSON.parse(JSON.stringify(old))), old);
  assert.equal(validator(old), true);
  for (const taskSummary of ['修复通知内容', '任'.repeat(160), '🚀'.repeat(80)]) {
    const event = { ...old, taskSummary };
    assert.equal(notificationTaskSummary(taskSummary), true);
    assert.deepEqual(parseNotificationEvent(event), event);
    assert.equal(validator(event), true, JSON.stringify(validator.errors));
  }
  for (const taskSummary of [
    '',
    null,
    123,
    '任'.repeat(161),
    '🚀'.repeat(81),
    '任'.repeat(159) + '🚀',
    ' leading',
    'trailing ',
    'two  spaces',
    'tab\tinput',
    'line\ninput',
    'control\u0000input',
    'delete\u007finput',
    'control\u0085input',
    'bidi\u202einput',
  ]) {
    assert.equal(notificationTaskSummary(taskSummary), false);
    assert.throws(() => parseNotificationEvent({ ...old, taskSummary }));
    assert.equal(validator({ ...old, taskSummary }), false, String(taskSummary));
  }
  assert.throws(() => parseNotificationEvent({ ...old, extra: 'wire' }));
  assert.equal(
    normalizeNotificationTaskSummary(
      '  修复\t通知\u0000内容\r\n只处理本轮\r\n\r\n不要回显第二段  ',
    ),
    '修复 通知内容 只处理本轮',
  );
  const truncated = normalizeNotificationTaskSummary('任'.repeat(158) + '🚀后续正文');
  assert.equal(truncated, '任'.repeat(158) + '…');
  assert.equal(truncated.isWellFormed(), true);
  assert.equal(notificationTaskSummary(truncated), true);
});

test('summaries follow matching live runs, request fallback and stable notification identity', () => {
  const observer = new NotificationObserver(target, [observed()]); // Old observation cache.
  const request = { id: 'request', runId: 'run-a', kind: 'question', taskSummary: '实际问题说明' };
  const first = observer.observe(
    observed({ taskSummary: '当前任务\n\n详细第二段', requests: [request] }),
  ).events[0];
  assert.equal(first.taskSummary, '实际问题说明');
  const next = observer.observe(observed({ taskSummary: '当前任务更新', requests: [request] }))
    .events[0];
  assert.equal(next.id, first.id, 'Presentation content must not change dedup identity');
  assert.equal(next.requestId, first.requestId);
  assert.equal(next.taskSummary, '实际问题说明');
  const multiple = observer.observe(
    observed({
      taskSummary: '当前任务',
      requests: [request, { ...request, id: 'second-question', taskSummary: '第二个具体问题' }],
    }),
  ).events;
  assert.deepEqual(
    multiple.map((event) => event.taskSummary),
    ['实际问题说明', '第二个具体问题'],
  );
  assert.notEqual(multiple[0].id, multiple[1].id);
  assert.equal(
    observer.observe(
      observed({ taskSummary: '当前任务', requests: [{ ...request, taskSummary: undefined }] }),
    ).events[0].taskSummary,
    '当前任务',
  );
  assert.equal(
    observer.observe(
      observed({ taskSummary: '当前任务', requests: [{ ...request, kind: 'approval' }] }),
    ).events[0].taskSummary,
    '实际问题说明',
  );
  const fallback = observer.observe(
    observed({ runId: 'run-b', requests: [{ ...request, runId: 'run-b' }] }),
  ).events[0];
  assert.equal(fallback.taskSummary, request.taskSummary);
  assert.notEqual(fallback.id, first.id, 'A reused request ID in a new run is still a new event');
  const stale = observer.observe(
    observed({
      runId: 'run-c',
      taskSummary: '新任务',
      requests: [request],
      uncertainOperations: ['unknown-operation'],
    }),
  );
  assert(stale.events.every((event) => event.taskSummary === undefined));
  assert.deepEqual(
    observer.observe(observed({ live: false, taskSummary: '旧缓存', outcome: 'failed' })).events,
    [],
  );
  observer.observe(observed({ runId: 'run-d', taskSummary: '本轮真实输入' }));
  const completed = observer.observe(
    observed({ runId: 'run-d', outcome: 'completed', taskSummary: '本轮真实输入' }),
  ).events[0];
  assert.equal(completed.kind, 'completed');
  assert.equal(completed.taskSummary, '本轮真实输入');
  observer.observe(observed({ runId: 'run-e', taskSummary: '停止的任务' }));
  observer.markStopped('run-e');
  const stopped = observer.observe(
    observed({ runId: 'run-e', outcome: 'interrupted', taskSummary: '停止的任务' }),
  ).events[0];
  assert.equal(stopped.kind, 'stopped');
  assert.equal(stopped.taskSummary, '停止的任务');
});

test('action summaries use the exact plan or immutable operation, never another live input', () => {
  const observer = new NotificationObserver(target);
  const value = observed({
    taskSummary: '后来提交的其他任务',
    planId: 'plan-a',
    planSummary: '先备份配置，再更新通知展示',
    uncertainOperations: ['operation-a', 'operation-b', 'legacy-operation'],
    uncertainOperationDetails: [
      { id: 'operation-a', runId: 'old-run', taskSummary: '核对已提交消息是否执行：修复标题' },
      {
        id: 'operation-b',
        threadTitle: '新对话 · 核对通知',
        taskSummary: '核对新建对话是否成功：核对通知',
      },
      { id: 'unrelated-operation', taskSummary: '不能混进其他操作' },
    ],
  });
  const events = observer.observe(value).events;
  assert.equal(events[0].taskSummary, value.planSummary);
  assert.deepEqual(
    events.slice(1).map((event) => event.taskSummary),
    ['核对已提交消息是否执行：修复标题', '核对新建对话是否成功：核对通知', undefined],
  );
  assert.equal(events[1].runId, 'old-run');
  assert.equal(events[2].runId, undefined);
  assert.equal(events[2].threadTitle, '新对话 · 核对通知');
  const unknown = observer.observe({
    ...value,
    outcome: 'unknown',
    taskSummary: '不能回退的正文',
  }).events;
  assert.equal(unknown[0].taskSummary, undefined);
  assert.equal(
    unknown[1].taskSummary,
    events[1].taskSummary,
    'Unknown execution does not erase the original submitted intent',
  );
  assert.deepEqual(
    unknown.map((event) => event.id),
    events.map((event) => event.id),
  );
  assert.equal(
    unknown[3].taskSummary,
    undefined,
    'Legacy uncertainty never borrows the latest task',
  );
  const unicodeTitle = observer.observe({ ...value, title: '标'.repeat(99) + '🚀' }).events[0];
  assert(unicodeTitle.threadTitle.isWellFormed());
  assert.equal(parseNotificationEvent(unicodeTitle).threadTitle, '标'.repeat(99));
});

test('managed wire events keep live summaries per turn without reading persisted user messages', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'notification-live-input-'));
  const executable = join(directory, 'wire-fixture');
  // Actual JSON-RPC frames through the adapter, using an isolated synthetic process.
  await writeFile(
    executable,
    `#!${process.execPath}
const readline = require('node:readline');
let sequence = 0;
const send = value => process.stdout.write(JSON.stringify(value) + '\\n');
const event = (method, params) => send({ method, params });
readline.createInterface({ input: process.stdin }).on('line', line => {
  const wire = JSON.parse(line), params = wire.params || {};
  if (wire.id === undefined) return;
  let result = {};
  if (wire.method === 'initialize') result = { userAgent: 'summary-fixture' };
  else if (wire.method === 'config/read') result = { config: {} };
  else if (wire.method === 'thread/start') result = { thread: { id: 'live-thread' } };
  else if (wire.method === 'turn/start') {
    const id = 'live-turn-' + ++sequence;
    result = { turn: { id, status: 'inProgress' } };
    setTimeout(() => {
      event('turn/started', { threadId: params.threadId, turn: { id, status: 'inProgress' } });
      const content = sequence === 1 ? [
        { type: 'text', text: '实际实时输入\\t首段\\n\\n第二段不得作为摘要' },
        { type: 'image', text: '附件中的伪正文', data: 'ATTACHMENT_BINARY' },
      ] : [{ type: 'localImage', path: '/private/fixture/image.png' }];
      event('item/started', { threadId: params.threadId, turnId: id, item: {
        id: 'user-' + id, type: 'userMessage', clientId: params.clientUserMessageId, content,
      } });
      send({ id: 'question-' + id, method: 'item/tool/requestUserInput', params: {
        threadId: params.threadId, turnId: id, reason: '调用背景不能代替具体问题',
        questions: [{ question: '' }, { question: '请选择本轮实际范围', header: '范围' }],
      } });
      // A late item from the previous turn must not seed the new turn's summary.
      if (sequence > 1) event('item/completed', { threadId: params.threadId, turnId: 'live-turn-1', item: {
        id: 'late-old-user', type: 'userMessage', content: [{ type: 'text', text: '迟到旧输入' }],
      } });
      setTimeout(() => event('turn/completed', { threadId: params.threadId, turn: { id, status: 'completed', items: [] } }), 100);
    }, 10);
  } else throw Error('Unexpected fixture operation: ' + wire.method);
  send({ id: wire.id, result });
});
`,
  );
  await chmod(executable, 0o700);
  const options = {
    directory: join(directory, 'state'),
    root: directory,
    name: 'Live input fixture',
    mode: 'managed-runtime',
    executable,
  };
  let session = new RuntimeSession(options);
  const observations = [],
    events = [];
  try {
    await session.connect();
    const thread = await session.createThread(null, 'create-live');
    const observer = new NotificationObserver(session.notificationTarget());
    session.onNotificationObservation((value) => {
      observations.push(value);
      events.push(...observer.observe(value).events);
    });
    for (const number of [1, 2]) {
      await session.submit({
        hostId: session.hostId,
        threadId: thread.id,
        clientMessageId: 'input-' + number,
        text: '\n\n提交草稿不等同于实际live输入 ' + number,
        attachmentIds: [],
        mode: 'next-turn',
      });
      const runId = 'live-turn-' + number;
      await waitFor(() =>
        observations.some((value) => value.runId === runId && value.outcome === 'completed'),
      );
      const question = events.find((value) => value.kind === 'question' && value.runId === runId);
      const completed = events.find((value) => value.kind === 'completed' && value.runId === runId);
      assert.equal(question.requestId, 'question-' + runId);
      assert.equal(question.taskSummary, '请选择本轮实际范围');
      assert.equal(completed.taskSummary, number === 1 ? '实际实时输入 首段' : undefined);
    }
    const original = session.state.receipts.find(
      (receipt) => receipt.clientMessageId === 'input-1',
    );
    original.state = 'uncertain'; // Simulate receipt recovery while a later run is current.
    const recovered = new NotificationObserver(session.notificationTarget())
      .observe(session.notificationObservation(thread.id))
      .events.find((event) => event.kind === 'uncertain');
    assert.equal(recovered.operationId, 'input-1');
    assert.equal(recovered.taskSummary, '核对已提交消息是否执行：提交草稿不等同于实际live输入 1');
    assert.equal(
      original.reply.text,
      '\n\n提交草稿不等同于实际live输入 1',
      'Presentation never rewrites submitted intent',
    );
    session.close();
    session = new RuntimeSession(options);
    assert.equal(session.notificationObservation(thread.id).live, false);
    assert.equal(session.notificationObservation(thread.id).taskSummary, undefined);
    await session.connect();
    assert.equal(
      session.notificationObservation(thread.id).taskSummary,
      undefined,
      'Reconnection cannot manufacture live input from cached messages',
    );
  } finally {
    session.close();
    await rm(directory, { recursive: true, force: true });
  }
});

test('live outcomes distinguish first snapshots, owner loss, requests, pause and stop', () => {
  const observer = new NotificationObserver(target);
  assert.deepEqual(observer.observe(observed({ outcome: 'failed' })).events, []);
  observer.observe(observed({ runId: 'run-b' }));
  assert.deepEqual(
    observer.observe(observed({ live: false, runId: 'run-b', outcome: 'failed' })).events,
    [],
  );
  assert.equal(
    observer.observe(observed({ runId: 'run-b', outcome: 'failed' })).events[0].kind,
    'failed',
  );
  const request = observed({
    runId: 'run-c',
    requests: [{ id: 'request-c', runId: 'run-c', kind: 'question' }],
  });
  const first = observer.observe(request),
    next = observer.observe(request);
  assert.equal(first.events[0].id, next.events[0].id);
  assert.equal(first.events[0].requestId, 'request-c');
  const reused = observer.observe({
    ...request,
    runId: 'run-d',
    requests: [{ id: 'request-c', runId: 'run-d', kind: 'question' }],
  });
  assert.notEqual(
    first.events[0].id,
    reused.events[0].id,
    'An upstream request ID reused in a new run is a new notification',
  );
  const pause = observer.observe(observed({ runId: 'run-c', outcome: 'paused' }));
  assert.ok(pause.activeIds.includes(pause.events[0].id));
  observer.markStopped('run-c');
  assert.equal(
    observer.observe(observed({ runId: 'run-c', outcome: 'interrupted' })).events[0].kind,
    'stopped',
  );
});

test('notification encryption authenticates event identity and recipient', () => {
  const host = pair(),
    phone = pair();
  const event = new NotificationObserver(target).observe(
    observed({
      taskSummary: '当前实时任务的文字概要',
      requests: [{ id: 'req', runId: 'run-a', kind: 'approval' }],
    }),
  ).events[0];
  const envelope = sealNotification(
    event,
    'binding',
    host.privateKey.export({ format: 'pem', type: 'pkcs8' }).toString(),
    phone.raw,
  );
  const secret = diffieHellman({ privateKey: phone.privateKey, publicKey: host.publicKey });
  const key = Buffer.from(
    hkdfSync(
      'sha256',
      secret,
      Buffer.from('binding'),
      Buffer.from('codex-manage/notifications/v1'),
      32,
    ),
  );
  const decrypt = (id) => {
    const cipher = createDecipheriv('aes-256-gcm', key, Buffer.from(envelope.nonce, 'base64'));
    cipher.setAAD(Buffer.from(id));
    cipher.setAuthTag(Buffer.from(envelope.tag, 'base64'));
    return Buffer.concat([
      cipher.update(Buffer.from(envelope.ciphertext, 'base64')),
      cipher.final(),
    ]);
  };
  assert.deepEqual(JSON.parse(decrypt(event.id)), event);
  assert.equal(JSON.parse(decrypt(event.id)).taskSummary, '当前实时任务的文字概要');
  assert(!JSON.stringify(envelope).includes(event.taskSummary));
  assert(!('taskSummary' in envelope));
  assert.throws(() => decrypt('wrong-event'));
  assert.deepEqual(
    notificationPublicKey(phone.raw).export({ format: 'der', type: 'spki' }),
    phone.publicKey.export({ format: 'der', type: 'spki' }),
  );
});

test('push routing, persistent dedup, device preferences and revocation', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'codex-notification-'));
  const credential = 'a'.repeat(40),
    other = 'b'.repeat(40),
    sent = [];
  const config = {
    statePath: join(directory, 'relay.json'),
    topic: 'test.bundle',
    teamId: 'fixture',
    keyId: 'fixture',
    keyPath: 'unused',
    hosts: [
      { hostId: target.hostId, token: credential },
      { hostId: 'another-host', token: other },
    ],
  };
  let relay = await startPushServer(config, {
    port: 0,
    send: async (binding, envelope) => {
      sent.push({ binding, envelope });
      return { state: 'accepted' };
    },
  });
  const base = 'http://127.0.0.1:' + relay.server.address().port;
  const publisherOptions = {
    target,
    statePath: join(directory, 'host.json'),
    relayURL: base,
    publisherToken: credential,
  };
  let publisher = new NotificationPublisher(publisherOptions);
  assert.throws(() => new NotificationPublisher(publisherOptions), /运行中的服务/);
  await assert.rejects(
    startPushServer(config, { port: 0, send: async () => ({ state: 'accepted' }) }),
    /运行中的服务/,
  );
  const phone = pair();
  const registration = {
    version: 1,
    deviceId: 'phone-a',
    target,
    publicKey: phone.raw,
    deviceToken: 'a'.repeat(64),
    environment: 'development',
    preferences: preferences(),
  };
  try {
    const status = await publisher.register(registration);
    assert.equal(status.delivery, 'apns');
    assert.equal(status.state, 'ready');
    const muted = await publisher.register({
      ...registration,
      deviceId: 'phone-muted',
      preferences: { ...preferences(), kinds: [], badgeEnabled: false },
    });
    assert.equal(muted.state, 'ready');
    const observer = new NotificationObserver(target);
    const result = observer.observe(
      observed({
        taskSummary: '仅端点解密的任务概要',
        requests: [{ id: 'request-a', runId: 'run-a', kind: 'approval' }],
      }),
    );
    publisher.observe(result.events, 'thread-a', result.activeIds, true);
    await waitFor(() => sent.length === 1);
    await waitFor(
      async () =>
        JSON.parse(await readFile(publisherOptions.statePath, 'utf8')).entries[0].deliveries[
          'phone-a'
        ] === 'accepted',
    );
    assert.equal(
      publisher.foreground('phone-a', '').events.length,
      0,
      'APNs and local presentation must not both fire',
    );
    assert.equal(sent[0].binding.deviceId, 'phone-a');
    assert(!JSON.stringify(sent[0].envelope).includes('仅端点解密的任务概要'));
    assert(
      !JSON.stringify(JSON.parse(await readFile(config.statePath, 'utf8'))).includes(
        '仅端点解密的任务概要',
      ),
    );
    const oversized = observer.observe(
      observed({
        threadId: '线'.repeat(256),
        runId: '任'.repeat(256),
        requests: [{ id: '问'.repeat(256), runId: '任'.repeat(256), kind: 'question' }],
      }),
    );
    publisher.observe(oversized.events, '线'.repeat(256), oversized.activeIds, true);
    await waitFor(
      () => publisher.status('phone-a').lastDelivery?.reason === 'NotificationPayloadTooLarge',
    );
    assert.equal(
      sent.length,
      1,
      'Oversized encrypted identities must produce an explicit rejection, not a stuck retry loop',
    );
    const post = (path, token, body) =>
      fetch(base + path, {
        method: 'POST',
        headers: { authorization: 'Bearer ' + token, 'content-type': 'application/json' },
        body: JSON.stringify(body),
      });
    assert.equal((await post('/v1/push/publish', other, sent[0].envelope)).status, 403);
    assert.equal(
      (await post('/v1/push/publish', credential, { ...sent[0].envelope, sound: 'chime.caf' }))
        .status,
      409,
    );
    publisher.close();
    publisher = new NotificationPublisher(publisherOptions);
    publisher.observe(result.events, 'thread-a', result.activeIds, true);
    await new Promise((resolve) => setTimeout(resolve, 100));
    assert.equal(sent.length, 1);
    assert.equal(
      (await post('/v1/push/revoke', status.managementToken, { bindingId: status.bindingId }))
        .status,
      200,
    );
    assert.equal((await post('/v1/push/publish', credential, sent[0].envelope)).status, 403);
    await publisher.unregister('phone-a');
    await assert.rejects(
      publisher.register({ ...registration, preferences: preferences() }),
      /设置已变化/,
    );
    relay.close();
    relay = await startPushServer(config, {
      port: 0,
      send: async () => {
        throw Error('Accepted delivery must never replay after restart');
      },
    });
  } finally {
    publisher.close();
    relay.close();
    await new Promise((resolve) => setTimeout(resolve, 100));
    await rm(directory, { recursive: true, force: true });
  }
});

test('foreground subscriptions filter resolved requests and persist once-per-event identity', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'codex-notification-local-'));
  const publisher = new NotificationPublisher({ target, statePath: join(directory, 'host.json') });
  try {
    const status = await publisher.register({
      version: 1,
      deviceId: 'phone',
      target,
      publicKey: pair().raw,
      preferences: preferences(),
    });
    assert.equal(status.delivery, 'foreground-only');
    const observer = new NotificationObserver(target),
      first = observer.observe(
        observed({ requests: [{ id: 'req', runId: 'run-a', kind: 'approval' }] }),
      );
    publisher.observe(first.events, 'thread-a', first.activeIds, true);
    assert.equal(publisher.foreground('phone', '').events.length, 1);
    const next = observer.observe(observed());
    publisher.observe(next.events, 'thread-a', next.activeIds, true);
    assert.equal(publisher.foreground('phone', '').events.length, 0);
    assert.equal(publisher.foreground('phone', '').activeIds.length, 0);
    await publisher.unregister('phone');
    assert.equal(publisher.foreground('phone', '').events.length, 0);
    const disabled = { ...preferences(), enabled: false, generation: 2, revision: 'off' };
    assert.equal((await publisher.updatePreferences('phone', target, disabled)).state, 'disabled');
    assert.equal(
      (
        await publisher.register({
          version: 1,
          deviceId: 'phone',
          target,
          publicKey: pair().raw,
          preferences: { ...disabled, enabled: true, generation: 3, revision: 'on' },
        })
      ).state,
      'unavailable',
    );
    assert.equal(
      publisher.hasSubscribers,
      true,
      'Explicitly newer preferences may re-enable after a revocation',
    );
  } finally {
    publisher.close();
    await rm(directory, { recursive: true, force: true });
  }
});

test('APNs token registration accepts equivalent reordered JSON and still rejects changed same-generation preferences', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'codex-notification-preference-order-'));
  const options = { target, statePath: join(directory, 'host.json') };
  let publisher = new NotificationPublisher(options);
  try {
    const original = {
      ...preferences(),
      categorySounds: { approval: 'attention.caf', question: 'soft.caf' },
    };
    const registration = {
      version: 1,
      deviceId: 'phone',
      target,
      publicKey: pair().raw,
      preferences: original,
    };
    await publisher.register(registration);
    publisher.close();
    publisher = new NotificationPublisher(options);
    const reordered = Object.fromEntries(Object.entries(original).reverse());
    reordered.categorySounds = Object.fromEntries(
      Object.entries(original.categorySounds).reverse(),
    );
    await publisher.register({
      ...registration,
      deviceToken: 'a'.repeat(64),
      environment: 'development',
      preferences: reordered,
    });
    const state = JSON.parse(await readFile(options.statePath, 'utf8'));
    assert.equal(state.devices.phone.registration.deviceToken, 'a'.repeat(64));
    await assert.rejects(
      publisher.register({ ...registration, preferences: { ...reordered, soundEnabled: false } }),
      /通知设置已变化/,
    );
  } finally {
    publisher.close();
    await rm(directory, { recursive: true, force: true });
  }
});

test('live baseline survives restart, loss of owner keeps requests, and device ownership stays isolated', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'codex-notification-recovery-'));
  const options = { target, statePath: join(directory, 'host.json') };
  let publisher = new NotificationPublisher(options);
  try {
    const registration = {
      version: 1,
      deviceId: 'phone',
      target,
      publicKey: pair().raw,
      preferences: preferences(),
    };
    await publisher.register(registration, 'owner-a');
    await assert.rejects(publisher.register(registration, 'owner-b'), /不属于/);
    assert.throws(() => publisher.foreground('phone', '', 'owner-b'), /不属于/);
    let observer = new NotificationObserver(target),
      value = observed({ requests: [{ id: 'request', runId: 'run-a', kind: 'approval' }] });
    let result = observer.observe(value);
    publisher.observe(result.events, value.threadId, result.activeIds, true, value);
    publisher.observe([], value.threadId, [], false);
    assert.equal(publisher.foreground('phone', '', 'owner-a').activeIds.length, 1);
    publisher.close();
    publisher = new NotificationPublisher(options);
    observer = new NotificationObserver(target, publisher.observations, publisher.stoppedRuns);
    assert.equal(
      publisher.foreground('phone', '', 'owner-a').events.length,
      0,
      'A restarted journal cannot stand in for current live proof',
    );
    value = observed({ outcome: 'failed' });
    result = observer.observe(value);
    assert.equal(result.events[0].kind, 'failed');
    publisher.observe(result.events, value.threadId, result.activeIds, true, value);
    assert.deepEqual(
      publisher.foreground('phone', '', 'owner-a').events.map((event) => event.kind),
      ['failed'],
    );
    publisher.observeConnection(true);
    publisher.observeConnection(false);
    publisher.observeConnection(false);
    await publisher.updatePreferences(
      'phone',
      target,
      {
        ...preferences(),
        generation: 2,
        revision: 'connection',
        kinds: ['connection'],
        badgeEnabled: false,
      },
      'owner-a',
    );
    assert.equal(publisher.foreground('phone', '', 'owner-a').events.length, 1);
    assert.equal(publisher.foreground('phone', '', 'owner-a').events[0].kind, 'connection');
    await publisher.retainOwners([]);
    assert.equal(publisher.foreground('phone', '', 'owner-a').events.length, 0);
    publisher.close();
    assert.throws(
      () => new NotificationPublisher({ ...options, target: { ...target, workspaceId: 'other' } }),
      /目标不一致/,
    );
  } finally {
    publisher.close();
    await rm(directory, { recursive: true, force: true });
  }
});

test('relay rejects resolved queued requests, preserves uncertain delivery and updates changed device tokens', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'codex-push-recovery-'));
  const credential = 'c'.repeat(40),
    config = {
      statePath: join(directory, 'relay.json'),
      topic: 'test.bundle',
      teamId: 'fixture',
      keyId: 'fixture',
      keyPath: 'unused',
      hosts: [{ hostId: target.hostId, token: credential }],
    };
  let sent = 0;
  let relay = await startPushServer(config, {
    port: 0,
    send: async () => {
      sent++;
      return { state: 'pending', reason: 'OfflineFixture' };
    },
  });
  const base = 'http://127.0.0.1:' + relay.server.address().port;
  const post = async (path, body) => {
    const response = await fetch(base + path, {
      method: 'POST',
      headers: { authorization: 'Bearer ' + credential, 'content-type': 'application/json' },
      body: JSON.stringify(body),
    });
    assert.equal(response.status, 200);
    return response.json();
  };
  try {
    const registration = {
      hostId: target.hostId,
      target,
      deviceId: 'phone',
      deviceToken: 'a'.repeat(64),
      environment: 'development',
      generation: 1,
      managementToken: 'm'.repeat(43),
    };
    const binding = await post('/v1/push/register', registration);
    const phone = pair(),
      host = pair(),
      event = new NotificationObserver(target).observe(
        observed({ requests: [{ id: 'expired-request', runId: 'run-a', kind: 'approval' }] }),
      ).events[0];
    const envelope = {
      version: 1,
      bindingId: binding.bindingId,
      eventId: event.id,
      ...sealNotification(
        event,
        binding.bindingId,
        host.privateKey.export({ format: 'pem', type: 'pkcs8' }).toString(),
        phone.raw,
      ),
      expiresAt: new Date(Date.now() + 600_000).toISOString(),
      sound: 'none',
      timeSensitive: false,
    };
    await post('/v1/push/publish', envelope);
    await waitFor(() => sent === 1);
    await post('/v1/push/cancel', { bindingId: binding.bindingId, eventId: event.id });
    assert.equal((await post('/v1/push/publish', envelope)).state, 'rejected');
    assert.equal(
      (
        await post('/v1/push/register', {
          ...registration,
          generation: 2,
          deviceToken: 'b'.repeat(64),
        })
      ).bindingId,
      binding.bindingId,
    );
    assert.equal(
      JSON.parse(await readFile(config.statePath, 'utf8')).bindings[binding.bindingId].deviceToken,
      'b'.repeat(64),
    );
    relay.close();
    const state = JSON.parse(await readFile(config.statePath, 'utf8')),
      id = binding.bindingId + ':' + event.id;
    state.deliveries[id].state = 'submitting';
    delete state.deliveries[id].cancelled;
    state.cancelled = {};
    const { writeFile } = await import('node:fs/promises');
    await writeFile(config.statePath, JSON.stringify(state), { mode: 0o600 });
    relay = await startPushServer(config, {
      port: 0,
      send: async () => {
        throw Error('Unknown APNs submission must not replay');
      },
    });
    const recovery = await fetch(
      'http://127.0.0.1:' + relay.server.address().port + '/v1/push/status',
      {
        method: 'POST',
        headers: { authorization: 'Bearer ' + credential, 'content-type': 'application/json' },
        body: JSON.stringify({ bindingId: binding.bindingId, eventId: event.id }),
      },
    );
    assert.equal((await recovery.json()).state, 'uncertain');
  } finally {
    relay.close();
    await rm(directory, { recursive: true, force: true });
  }
});

test('out-of-order registration cannot restore an old token or revoke a newer subscription', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'codex-push-order-'));
  const credential = 'd'.repeat(40),
    config = {
      statePath: join(directory, 'relay.json'),
      topic: 'test.bundle',
      teamId: 'fixture',
      keyId: 'fixture',
      keyPath: 'unused',
      hosts: [{ hostId: target.hostId, token: credential }],
    };
  let relay = await startPushServer(config, { port: 0, send: async () => ({ state: 'accepted' }) });
  let base = 'http://127.0.0.1:' + relay.server.address().port;
  let release,
    delayed = false;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  const proxy = createServer((request, response) => {
    void (async () => {
      const chunks = [];
      for await (const chunk of request) chunks.push(chunk);
      const body = Buffer.concat(chunks);
      const path = request.url.replace(/^\/notifications(?=\/)/, '');
      const result = await fetch(base + path, {
        method: 'POST',
        headers: { authorization: 'Bearer ' + credential, 'content-type': 'application/json' },
        body,
      });
      const data = await result.text();
      if (path === '/v1/push/register' && JSON.parse(body).generation === 1) {
        delayed = true;
        await gate;
      }
      response.writeHead(result.status, { 'content-type': 'application/json' });
      response.end(data);
    })().catch(() => {
      response.writeHead(500);
      response.end();
    });
  });
  await new Promise((resolve) => proxy.listen(0, '127.0.0.1', resolve));
  const publisher = new NotificationPublisher({
    target,
    statePath: join(directory, 'host.json'),
    relayURL: 'http://127.0.0.1:' + proxy.address().port + '/notifications',
    publisherToken: credential,
  });
  const registration = {
    version: 1,
    deviceId: 'phone',
    target,
    publicKey: pair().raw,
    deviceToken: 'a'.repeat(64),
    environment: 'development',
    preferences: preferences(),
  };
  const post = (path, body) =>
    fetch(base + path, {
      method: 'POST',
      headers: { authorization: 'Bearer ' + credential, 'content-type': 'application/json' },
      body: JSON.stringify(body),
    });
  try {
    const older = publisher.register(registration);
    await waitFor(() => delayed);
    const newer = await publisher.register({
      ...registration,
      deviceToken: 'b'.repeat(64),
      preferences: { ...preferences(), generation: 2, revision: 'newer' },
    });
    release();
    await older;
    assert.equal(publisher.status('phone').state, 'ready');
    let state = JSON.parse(await readFile(config.statePath, 'utf8'));
    assert.equal(state.bindings[newer.bindingId].enabled, true);
    assert.equal(state.bindings[newer.bindingId].deviceToken, 'b'.repeat(64));
    const cloudRegistration = {
      hostId: target.hostId,
      target,
      deviceId: 'phone',
      generation: 1,
      deviceToken: 'a'.repeat(64),
      environment: 'development',
      managementToken: newer.managementToken,
    };
    assert.equal((await post('/v1/push/register', cloudRegistration)).status, 409);
    await publisher.unregister('phone');
    assert.equal(
      (await post('/v1/push/register', { ...cloudRegistration, generation: 2 })).status,
      409,
    );
    await publisher.register({
      ...registration,
      preferences: { ...preferences(), generation: 4, revision: 're-enabled' },
    });
    assert.equal(
      (
        await post('/v1/push/unregister', {
          hostId: target.hostId,
          target,
          deviceId: 'phone',
          generation: 3,
        })
      ).status,
      409,
    );
    state = JSON.parse(await readFile(config.statePath, 'utf8'));
    assert.equal(state.bindings[newer.bindingId].enabled, true);
    // A revoke that reaches the relay before any registration also survives restart.
    assert.equal(
      (
        await post('/v1/push/unregister', {
          hostId: target.hostId,
          target,
          deviceId: 'offline-phone',
          generation: 2,
        })
      ).status,
      200,
    );
    relay.close();
    relay = await startPushServer(config, { port: 0, send: async () => ({ state: 'accepted' }) });
    base = 'http://127.0.0.1:' + relay.server.address().port;
    assert.equal(
      (await post('/v1/push/register', { ...cloudRegistration, deviceId: 'offline-phone' })).status,
      409,
    );
  } finally {
    release();
    publisher.close();
    proxy.close();
    proxy.closeAllConnections();
    relay.close();
    await rm(directory, { recursive: true, force: true });
  }
});
