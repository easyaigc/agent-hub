import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import Ajv from 'ajv';
import {
  parseChatManagementIntent,
  parseChatManagementOutcome,
} from '../../packages/protocol/dist/index.js';

const schema = JSON.parse(
  readFileSync('packages/protocol/schemas/agent-management-v1.schema.json', 'utf8'),
);
const ajv = new Ajv({ allErrors: true, strictKeywords: true });
ajv.addSchema(schema);
const intent = {
  hostId: 'local-connection-alias',
  target: {
    agentId: 'easywork',
    hostId: 'actual-runtime-host',
    workspaceId: 'workspace',
    runtimeId: 'runtime',
    account: { authority: 'https://account.test', subject: 'user' },
  },
  threadId: 'thread-12345678',
  clientOperationId: 'operation-12345678',
  action: 'rename',
  title: '手机修改的标题',
};

test('local management intent preserves the connection key, complete runtime identity and exact original title', () => {
  assert(ajv.getSchema(schema.$id + '#/definitions/chatManagementIntent')(intent));
  assert.deepEqual(parseChatManagementIntent(intent), intent);
  const outcome = { ...intent, phase: 'confirmed', error: null };
  assert(ajv.getSchema(schema.$id + '#/definitions/chatManagementOutcome')(outcome));
  assert.deepEqual(parseChatManagementOutcome(outcome), outcome);
  assert.notEqual(intent.hostId, intent.target.hostId);
});

test('management accepts only named actions and a title for rename, with an explicit bounded original result', () => {
  for (const bad of [
    { ...intent, method: 'shell' },
    { ...intent, title: '' },
    { ...intent, title: '\uFEFF\u2000' },
    { ...intent, title: 'x'.repeat(501) },
    { ...intent, title: '🙂'.repeat(251) },
    { ...intent, action: 'delete' },
    { ...intent, action: 'unknown' },
    { ...intent, target: { ...intent.target, command: 'delete-all' } },
  ])
    assert.throws(() => parseChatManagementIntent(bad));
  assert.throws(() => parseChatManagementOutcome({ ...intent, phase: 'confirmed' }));
  assert.throws(() => parseChatManagementOutcome({ ...intent, phase: 'completed', error: null }));
  const { title: _title, ...pin } = { ...intent, action: 'pin' };
  assert.equal(parseChatManagementIntent(pin).action, 'pin');
});
