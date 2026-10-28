import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import prettier from 'prettier';
import {
  notificationRecoveryLimits,
  defaultNotificationPreferences,
} from '../packages/protocol/dist/index.js';
import { intentHash } from '../packages/notification-runtime/dist/recovery-v2.js';

const v1 = (name) => ({
  $ref: 'https://codex-manage.invalid/contracts/task-notifications-v1#/definitions/' + name,
});
const uuid = {
  type: 'string',
  pattern: '^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}(?![\\s\\S])',
};
const hash = { type: 'string', pattern: '^[a-f0-9]{64}(?![\\s\\S])' },
  number = { type: 'integer', minimum: 0, maximum: 9007199254740991 };
const device = {
  type: 'string',
  pattern: '^[A-Za-z0-9._-]{1,128}(?![\\s\\S])',
  not: { enum: ['__proto__', 'prototype', 'constructor'] },
};
const object = (properties, required = Object.keys(properties)) => ({
  type: 'object',
  additionalProperties: false,
  required,
  properties,
});
const ref = (name) => ({ $ref: '#/definitions/' + name });
const key = { type: 'string', pattern: '^[A-Za-z0-9+/]{42}[AEIMQUYcgkosw048]=(?![\\s\\S])' };
const intent = object(
  {
    version: { const: 2 },
    operationId: uuid,
    epoch: uuid,
    deviceId: device,
    target: v1('Target'),
    publicKey: key,
    preferences: v1('Preferences'),
    generation: { ...number, minimum: 1 },
    kind: { enum: ['register', 'preferences', 'revoke', 'feed-reset'] },
    gap: object({
      epoch: uuid,
      after: number,
      floor: { ...number, minimum: 1 },
      head: number,
      acceptanceId: uuid,
    }),
    fingerprint: hash,
  },
  [
    'version',
    'operationId',
    'epoch',
    'deviceId',
    'target',
    'publicKey',
    'preferences',
    'generation',
    'kind',
    'fingerprint',
  ],
);
intent.allOf = [
  {
    if: { properties: { kind: { const: 'feed-reset' } } },
    then: { required: ['gap'] },
    else: { not: { required: ['gap'] } },
  },
  {
    if: { properties: { kind: { const: 'revoke' } } },
    then: { properties: { preferences: { properties: { enabled: { const: false } } } } },
  },
];
const snapshot = object({
  id: uuid,
  hash,
  count: { ...number, maximum: notificationRecoveryLimits.snapshotEvents },
  baseline: number,
  expiresAt: number,
});
const status = object(
  {
    version: { const: 1 },
    target: v1('Target'),
    delivery: { enum: ['foreground-only', 'apns'] },
    state: { enum: ['disabled', 'ready', 'pending', 'unavailable'] },
    revision: { type: 'string', minLength: 1, maxLength: 256 },
    reason: { type: 'string', maxLength: 4096 },
  },
  ['version', 'target', 'delivery', 'state'],
);
const common = {
  version: { const: 2 },
  epoch: uuid,
  deviceId: device,
  target: v1('Target'),
  generation: { ...number, minimum: 1 },
};
const page = object(
  {
    ...common,
    kind: { const: 'page' },
    mode: { enum: ['live', 'reset'] },
    resetId: uuid,
    snapshotHash: hash,
    baseline: number,
    after: number,
    next: number,
    through: number,
    hasMore: { type: 'boolean' },
    items: {
      type: 'array',
      maxItems: notificationRecoveryLimits.pageEvents,
      items: object({
        sequence: { ...number, minimum: 1 },
        event: v1('Event'),
        fingerprint: hash,
        disposition: { enum: ['eligible', 'inactive', 'stale', 'filtered'] },
      }),
    },
    activeIds: {
      type: 'array',
      maxItems: notificationRecoveryLimits.activeIds,
      uniqueItems: true,
      items: hash,
    },
    publicKey: key,
    preferencesFingerprint: hash,
  },
  [
    'version',
    'epoch',
    'deviceId',
    'target',
    'generation',
    'kind',
    'mode',
    'baseline',
    'after',
    'next',
    'through',
    'hasMore',
    'items',
    'activeIds',
    'publicKey',
    'preferencesFingerprint',
  ],
);
page.allOf = [
  {
    if: { properties: { mode: { const: 'reset' } } },
    then: { required: ['resetId', 'snapshotHash'] },
    else: { not: { anyOf: [{ required: ['resetId'] }, { required: ['snapshotHash'] }] } },
  },
];
const schema = {
  $schema: 'http://json-schema.org/draft-07/schema#',
  $id: 'https://codex-manage.invalid/contracts/notification-recovery-v2',
  oneOf: [ref('Intent'), ref('Receipt'), ref('QueryReply'), ref('Feed'), ref('Capabilities')],
  definitions: {
    Intent: intent,
    Receipt: object(
      {
        version: { const: 2 },
        epoch: uuid,
        intent: ref('Intent'),
        stage: { enum: ['reserved', 'applied', 'rejected'] },
        reason: { type: 'string', minLength: 1, maxLength: 128 },
        status,
        snapshot,
      },
      ['version', 'epoch', 'intent', 'stage'],
    ),
    QueryReply: {
      oneOf: [
        object({
          version: { const: 2 },
          epoch: uuid,
          target: v1('Target'),
          deviceId: device,
          operationId: uuid,
          found: { const: true },
          receipt: ref('Receipt'),
        }),
        object({
          version: { const: 2 },
          epoch: uuid,
          target: v1('Target'),
          deviceId: device,
          operationId: uuid,
          found: { const: false },
          reason: { enum: ['absent-unknown', 'epoch-mismatch'] },
        }),
      ],
    },
    Feed: {
      oneOf: [
        page,
        object({
          ...common,
          kind: { const: 'reset-required' },
          reason: {
            enum: [
              'epoch-mismatch',
              'subscription-mismatch',
              'snapshot-expired-or-missing',
              'retention-gap',
              'invalid-page-range',
              'non-contiguous-source',
            ],
          },
          floor: { ...number, minimum: 1 },
          head: number,
        }),
        object({
          ...common,
          kind: { const: 'capacity' },
          reason: { enum: ['active-ids', 'page-bytes'] },
        }),
      ],
    },
    Capabilities: object({
      version: { const: 2 },
      epoch: uuid,
      target: v1('Target'),
      operations: {
        type: 'array',
        minItems: 4,
        maxItems: 4,
        uniqueItems: true,
        items: { enum: ['register', 'preferences', 'revoke', 'feed-reset'] },
      },
      limits: object(
        Object.fromEntries(
          Object.entries(notificationRecoveryLimits).map(([name, value]) => [
            name,
            { const: value },
          ]),
        ),
      ),
    }),
  },
};
const base = {
  version: 2,
  operationId: '66813d85-9271-4209-80f5-e895a7f17c64',
  epoch: '59518182-c795-40fc-aa18-99a8d84b62d0',
  deviceId: 'android-fixture',
  target: {
    agentId: 'codex',
    hostId: 'fixture-host-中',
    workspaceId: 'fixture-workspace',
    runtimeId: 'desktop-runtime',
  },
  publicKey: Buffer.from(
    '8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a',
    'hex',
  ).toString('base64'),
  preferences: {
    ...defaultNotificationPreferences(),
    enabled: true,
    generation: 17,
    revision: 'fixture-😀-\u2028-</-"-\\',
    excludedHosts: ['mac-中', 'server-😀'],
    categorySounds: { question: 'none', approval: 'soft.caf' },
  },
  generation: 23,
  kind: 'register',
  fingerprint: '',
};
const vectors = [
  ['register-unicode', base],
  ['same-wire-keys-shuffled', Object.fromEntries(Object.entries(base).reverse())],
  [
    'preferences',
    {
      ...base,
      operationId: '5fa3b6c1-1c40-4a69-b5b0-d4e84d63592c',
      generation: 24,
      kind: 'preferences',
      preferences: { ...base.preferences, showThreadTitle: false },
    },
  ],
  [
    'revoke',
    {
      ...base,
      operationId: '8ebf92a3-7455-4573-9ea1-e3e23b75a820',
      generation: 25,
      kind: 'revoke',
      preferences: { ...base.preferences, enabled: false },
    },
  ],
  [
    'explicit-gap-reset',
    {
      ...base,
      kind: 'feed-reset',
      gap: {
        epoch: base.epoch,
        after: 2,
        floor: 4,
        head: 9,
        acceptanceId: '3bc890ea-d654-41e9-8840-2764ed74edb1',
      },
    },
  ],
].map(([name, value]) => {
  const intent = { ...value, fingerprint: intentHash(value) };
  return { name, intent, expectedFingerprint: intent.fingerprint };
});
assert.equal(vectors[0].expectedFingerprint, vectors[1].expectedFingerprint);
const files = {
  'packages/protocol/schemas/notification-recovery-v2.schema.json': schema,
  'packages/protocol/fixtures/mobile/notification-recovery-v2.json': {
    version: 2,
    algorithm: 'sha256-canonical-json-v2',
    vectors,
  },
};
for (const [path, value] of Object.entries(files)) {
  const bytes = await prettier.format(JSON.stringify(value), {
    ...(await prettier.resolveConfig(path)),
    parser: 'json',
  });
  if (process.argv.includes('--write')) await writeFile(path, bytes);
  else
    assert.equal(
      await readFile(path, 'utf8'),
      bytes,
      'Notification v2 schema/vector drift: ' + path,
    );
}
const manifestPath = 'packages/protocol/notification-recovery-v2-contract-manifest.json';
const manifest = {
  contract: 'notification-recovery',
  version: 2,
  files: Object.fromEntries(
    await Promise.all(
      Object.keys(files).map(async (path) => [
        path,
        createHash('sha256')
          .update(await readFile(path))
          .digest('hex'),
      ]),
    ),
  ),
};
const manifestBytes = await prettier.format(JSON.stringify(manifest), {
  ...(await prettier.resolveConfig(manifestPath)),
  parser: 'json',
});
if (process.argv.includes('--write')) await writeFile(manifestPath, manifestBytes);
else
  assert.equal(
    await readFile(manifestPath, 'utf8'),
    manifestBytes,
    'Notification v2 manifest drift',
  );
console.log(
  'Notification recovery v2 schema and five cross-language vectors match production canonical code',
);
