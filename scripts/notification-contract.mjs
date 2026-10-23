import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import {
  notificationKinds,
  notificationTaskSummaryPattern,
  TASK_SUMMARY_MAX_LENGTH,
} from '../packages/protocol/dist/index.js';
import prettier from 'prettier';

const id = { type: 'string', minLength: 1, maxLength: 256, pattern: '^[^\\u0000-\\u001f]+$' };
const ref = (name) => ({ $ref: '#/definitions/' + name });
const object = (properties, required = Object.keys(properties)) => ({
  type: 'object',
  additionalProperties: false,
  properties,
  required,
});
const bool = { type: 'boolean' },
  sound = { type: 'string', pattern: '^(default|none|[a-zA-Z0-9_-]{1,72}\\.caf)$' };
const target = object(
  {
    agentId: id,
    hostId: id,
    workspaceId: id,
    runtimeId: id,
    account: object({ authority: id, subject: id }),
  },
  ['agentId', 'hostId', 'workspaceId', 'runtimeId'],
);
const prefs = object({
  version: { const: 1 },
  revision: id,
  generation: { type: 'integer', minimum: 0 },
  enabled: bool,
  kinds: { type: 'array', uniqueItems: true, maxItems: 10, items: { enum: notificationKinds } },
  excludedHosts: { type: 'array', maxItems: 256, items: id },
  foregroundBanner: bool,
  soundEnabled: bool,
  badgeEnabled: bool,
  showThreadTitle: bool,
  timeSensitive: bool,
  sound,
  categorySounds: object(Object.fromEntries(notificationKinds.map((kind) => [kind, sound])), []),
});
const deviceId = {
  type: 'string',
  pattern: '^[A-Za-z0-9._-]{1,128}$',
  not: { enum: ['__proto__', 'constructor', 'prototype'] },
};
const registration = object(
  {
    version: { const: 1 },
    deviceId,
    target: ref('Target'),
    publicKey: { type: 'string', pattern: '^[A-Za-z0-9+/]{43}=$' },
    deviceToken: { type: 'string', pattern: '^[a-f0-9]{64,256}$' },
    environment: { enum: ['development', 'production'] },
    preferences: ref('Preferences'),
  },
  ['version', 'deviceId', 'target', 'publicKey', 'preferences'],
);
registration.dependencies = { deviceToken: ['environment'], environment: ['deviceToken'] };
const command = (method, params) =>
  object({ version: { const: 1 }, method: { const: method }, target: ref('Target'), params });
const schema = {
  $schema: 'http://json-schema.org/draft-07/schema#',
  $id: 'https://codex-manage.invalid/contracts/task-notifications-v1',
  oneOf: ['Registration', 'Event', 'Command'].map(ref),
  definitions: {
    Target: target,
    Preferences: prefs,
    Registration: registration,
    Event: object(
      {
        version: { const: 1 },
        id: { type: 'string', pattern: '^[a-f0-9]{64}$' },
        target: ref('Target'),
        kind: { enum: notificationKinds },
        occurredAt: { type: 'string', format: 'date-time' },
        threadId: id,
        runId: id,
        requestId: id,
        operationId: id,
        threadTitle: { type: 'string', maxLength: 100 },
        readTarget: ref('Target'),
        readPosition: object({
          revision: { type: 'integer', minimum: 1 },
          messageId: { type: 'string', minLength: 1, maxLength: 512 },
          contentVersion: { type: 'string', pattern: '^[a-f0-9]{64}$' },
        }),
        taskSummary: {
          type: 'string',
          minLength: 1,
          maxLength: TASK_SUMMARY_MAX_LENGTH,
          pattern: notificationTaskSummaryPattern,
          $comment: 'At most 160 UTF-16 code units, matching Swift String.utf16.count.',
        },
      },
      ['version', 'id', 'target', 'kind', 'occurredAt'],
    ),
    Command: {
      oneOf: [
        command('notification/register', object({ registration: ref('Registration') })),
        command('notification/preferences', object({ deviceId, preferences: ref('Preferences') })),
        ...['notification/unregister', 'notification/status'].map((method) =>
          command(method, object({ deviceId })),
        ),
        command(
          'notification/events',
          object({ deviceId, after: { type: 'string', maxLength: 64 } }, ['deviceId']),
        ),
      ],
    },
  },
};
const path = 'packages/protocol/schemas/task-notifications-v1.schema.json';
const bytes = await prettier.format(JSON.stringify(schema), {
  ...(await prettier.resolveConfig(path)),
  parser: 'json',
});
const manifest = {
  contract: 'task-notifications',
  version: 1,
  sha256: createHash('sha256').update(bytes).digest('hex'),
};
if (process.argv.includes('--write')) {
  await writeFile(path, bytes);
  await writeFile(
    'packages/protocol/notification-contract-manifest.json',
    JSON.stringify(manifest, null, 2) + '\n',
  );
} else {
  if (
    (await readFile(path, 'utf8')) !== bytes ||
    JSON.stringify(
      JSON.parse(await readFile('packages/protocol/notification-contract-manifest.json', 'utf8')),
    ) !== JSON.stringify(manifest)
  )
    throw new Error('通知契约发生漂移，请运行 notification-contract.mjs --write');
}
console.log('Task notifications v1: ' + manifest.sha256);
