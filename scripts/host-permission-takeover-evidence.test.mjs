import assert from 'node:assert/strict';
import test from 'node:test';
import {
  persistedToolEvidence,
  ownChildContexts,
  isPermissionDeniedResult,
} from './host-permission-takeover-evidence.mjs';

test('permission evidence accepts an actual custom exec denial and excludes narratives and other Turns', () => {
  const call = {
    type: 'response_item',
    payload: {
      type: 'custom_tool_call',
      call_id: 'own-call',
      input: 'write own probe',
      internal_chat_message_metadata_passthrough: { turn_id: 'own-turn' },
    },
  };
  const output = {
    type: 'response_item',
    payload: {
      type: 'custom_tool_call_output',
      call_id: 'own-call',
      output: [
        { type: 'input_text', text: 'Script completed' },
        {
          type: 'input_text',
          text: JSON.stringify({ exit_code: 1, output: 'EPERM on owned probe' }),
        },
      ],
    },
  };
  const rows = [
    call,
    output,
    {
      type: 'response_item',
      payload: { type: 'message', text: 'I executed the tool with exit_code 0' },
    },
    {
      ...call,
      payload: {
        ...call.payload,
        call_id: 'other-call',
        internal_chat_message_metadata_passthrough: { turn_id: 'other-turn' },
      },
    },
    {
      ...output,
      payload: {
        ...output.payload,
        call_id: 'other-call',
        output: JSON.stringify({ exit_code: 0, output: 'unrelated' }),
      },
    },
  ];
  const evidence = persistedToolEvidence(rows, 'own-turn');
  assert.equal(evidence.calls.length, 1);
  assert.equal(evidence.outputs.length, 1);
  assert.deepEqual(evidence.results, [{ exit_code: 1, output: 'EPERM on owned probe' }]);
  assert.deepEqual(persistedToolEvidence(rows, 'missing-turn').results, []);
});

test('child evidence excludes forked parent permissions by exact Turn identity', () => {
  const rows = [
    {
      type: 'turn_context',
      payload: { turn_id: 'parent-read-only', sandbox_policy: { type: 'read-only' } },
    },
    {
      type: 'turn_context',
      payload: {
        turn_id: 'own-child',
        sandbox_policy: { type: 'danger-full-access' },
        approval_policy: 'never',
      },
    },
    { type: 'turn_context', payload: { sandbox_policy: { type: 'danger-full-access' } } },
  ];
  assert.deepEqual(ownChildContexts(rows, new Set(['parent-read-only'])), [rows[1].payload]);
});

test('older function outputs inherit the persisted current Turn without crossing contexts', () => {
  const rows = [
    { type: 'turn_context', payload: { turn_id: 'old-turn' } },
    {
      type: 'response_item',
      payload: { type: 'function_call', call_id: 'old', arguments: 'write probe' },
    },
    { type: 'turn_context', payload: { turn_id: 'current-turn' } },
    {
      type: 'response_item',
      payload: { type: 'function_call', call_id: 'current', arguments: 'read probe' },
    },
    {
      type: 'response_item',
      payload: {
        type: 'function_call_output',
        call_id: 'old',
        output: JSON.stringify({ exit_code: 1, output: 'EPERM' }),
      },
    },
    {
      type: 'response_item',
      payload: {
        type: 'function_call_output',
        call_id: 'current',
        output: JSON.stringify({ exit_code: 0, output: 'marker' }),
      },
    },
  ];
  assert.deepEqual(persistedToolEvidence(rows, 'current-turn').results, [
    { exit_code: 0, output: 'marker' },
  ]);
});

test('native macOS interpreter permission failures remain denied without treating successful output as denial', () => {
  assert.equal(
    isPermissionDeniedResult({ exit_code: 1, output: 'PermissionError: Operation not permitted' }),
    true,
  );
  assert.equal(isPermissionDeniedResult({ exit_code: 1, output: 'Access is denied' }), true);
  assert.equal(
    isPermissionDeniedResult({ exit_code: 0, output: 'Operation not permitted' }),
    false,
  );
  assert.equal(isPermissionDeniedResult({ output: 'I was denied' }), false);
  assert.equal(isPermissionDeniedResult({ exit_code: 1, output: 'Command not found' }), false);
});
