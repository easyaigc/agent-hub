import assert from 'node:assert/strict';
import { test } from 'node:test';
import { verifyClientQueueEvidence } from './easywork-receive-evidence.mjs';

// Synthetic collector records only: no desktop, service, transport or model.
const nonce = 'offline-fixture';
const nativeLog =
  '** TEST SUCCEEDED **\nEASYWORK_CLIENT_QUEUE_PASSED lan\nEASYWORK_CLIENT_QUEUE_PASSED relay';
function scene(route = 'lan') {
  const threadId = 'fixture-thread-' + route;
  const marker = 'EASYWORK_NATIVE_QUEUE_' + route + '_' + nonce;
  const ids = ['ORIGIN', 'FIFO', 'PROMOTE', 'CANCEL'].map((name) => route + '-' + name);
  return {
    requests: ids.map((id, index) => ({
      request: {
        method: 'turn/start',
        params: {
          threadId,
          clientRequestId: id,
          mode: index ? 'queue' : 'next-turn',
          parts: [
            {
              type: 'text',
              text: marker + '_' + ['ORIGIN', 'FIFO', 'PROMOTE', 'CANCEL'][index],
            },
          ],
        },
      },
      response: {
        result: {
          state: index ? 'queued' : 'submitted',
          queueSource: 'native',
          queuePresent: true,
        },
      },
    })),
    independentDesktop: {
      queueViews: [
        {
          threadId,
          operation: 'desktop.queue-capture',
          items: ids.slice(1).map((messageId) => ({ messageId })),
        },
      ],
      snapshots: [
        {
          id: threadId,
          messages: ['ORIGIN', 'FIFO'].map((name) => ({
            role: 'user',
            content: marker + '_' + name,
          })),
        },
      ],
      executions: ids.slice(0, 2).map((messageId) => ({
        run: {
          threadId,
          kind: 'root',
          status: 'completed',
          input: { trigger: { kind: 'user', messageId } },
        },
      })),
    },
  };
}
const verify = (witness, routes = ['lan']) =>
  verifyClientQueueEvidence(witness, nonce, nativeLog, routes);

test('one exact original and FIFO execution supports both independently scoped routes', () => {
  const first = scene(),
    second = scene('relay');
  first.requests.push(...second.requests);
  for (const key of Object.keys(first.independentDesktop))
    first.independentDesktop[key].push(...second.independentDesktop[key]);
  const result = verify(first, ['lan', 'relay']);
  assert.equal(result.lan.executedInputs, 2);
  assert.equal(result.relay.executedInputs, 2);
});

test('completed native continuation and another thread do not count as replayed user input', () => {
  const witness = scene();
  witness.independentDesktop.executions.push(
    {
      run: {
        threadId: 'fixture-thread-lan',
        kind: 'root',
        status: 'completed',
        input: { trigger: { kind: 'continuation' } },
      },
    },
    {
      run: {
        threadId: 'unrelated-fixture-thread',
        kind: 'root',
        status: 'completed',
        input: { trigger: { kind: 'user', messageId: 'lan-FIFO' } },
      },
    },
  );
  assert.equal(verify(witness).lan.naturalClientDispatch, 'passed');
});

for (const [name, mutate] of [
  [
    'empty execution evidence',
    (w) => {
      w.independentDesktop.executions = [];
    },
  ],
  [
    'duplicate FIFO execution',
    (w) => {
      w.independentDesktop.executions.push(structuredClone(w.independentDesktop.executions[1]));
    },
  ],
  [
    'duplicate original execution',
    (w) => {
      w.independentDesktop.executions.push(structuredClone(w.independentDesktop.executions[0]));
    },
  ],
  [
    'missing original execution',
    (w) => {
      w.independentDesktop.executions.shift();
    },
  ],
  [
    'missing FIFO execution',
    (w) => {
      w.independentDesktop.executions.pop();
    },
  ],
  [
    'different immutable execution ID',
    (w) => {
      w.independentDesktop.executions[1].run.input.trigger.messageId = 'different-fixture-input';
    },
  ],
  [
    'only another thread has executions',
    (w) => {
      for (const entry of w.independentDesktop.executions)
        entry.run.threadId = 'another-fixture-thread';
    },
  ],
  [
    'cancelled input executed despite absent history',
    (w) => {
      const extra = structuredClone(w.independentDesktop.executions[1]);
      extra.run.input.trigger.messageId = 'lan-CANCEL';
      w.independentDesktop.executions.push(extra);
    },
  ],
  [
    'removed desktop input executed despite absent history',
    (w) => {
      const extra = structuredClone(w.independentDesktop.executions[1]);
      extra.run.input.trigger.messageId = 'lan-PROMOTE';
      w.independentDesktop.executions.push(extra);
    },
  ],
  [
    'missing original request',
    (w) => {
      w.requests.shift();
    },
  ],
  [
    'duplicate queue operation IDs',
    (w) => {
      w.requests[2].request.params.clientRequestId = 'lan-FIFO';
      w.independentDesktop.queueViews[0].items[1].messageId = 'lan-FIFO';
    },
  ],
  [
    'wrong original history content',
    (w) => {
      w.independentDesktop.snapshots[0].messages[0].content = 'unrelated-fixture-input';
    },
  ],
  [
    'unfinished execution',
    (w) => {
      w.independentDesktop.executions[1].run.status = 'running';
    },
  ],
])
  test('rejects ' + name, () => {
    const witness = scene();
    mutate(witness);
    assert.throws(() => verify(witness), { name: 'AssertionError' });
  });
