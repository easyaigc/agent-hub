import test from 'node:test';
import assert from 'node:assert/strict';
import { subagentIds, subagentItem } from '../packages/codex-adapter/dist/subagents.js';

test('projects only explicitly named children with prompt and live messages', () => {
  const item = {
    type: 'collabAgentToolCall',
    tool: 'spawn',
    prompt: 'investigate',
    receiverThreadIds: ['child', 'child'],
    agentsStates: {
      child: { status: 'pending', message: 'waiting' },
      unrelated: { status: 'completed' },
    },
  };
  const seen = [];
  assert.deepEqual(subagentIds(item), ['child']);
  const projected = subagentItem(item, (id) => {
    seen.push(id);
    return {
      turns: [{ status: 'inProgress', items: [{ type: 'agentMessage', text: 'live progress' }] }],
    };
  });
  assert.deepEqual(seen, ['child']);
  assert.equal(projected.type, 'subAgent');
  assert.match(projected.detail, /investigate/);
  assert.match(projected.detail, /inProgress/);
  assert.match(projected.detail, /live progress/);
  assert.doesNotMatch(projected.detail, /unrelated/);
});

test('uses reported state when child snapshot is unavailable', () => {
  const projected = subagentItem(
    {
      type: 'collabAgentToolCall',
      receiverThreadIds: ['child'],
      agentsStates: { child: { status: 'failed', message: 'error detail' } },
    },
    () => undefined,
  );
  assert.match(projected.detail, /failed/);
  assert.match(projected.detail, /error detail/);
});

test('saved child output preserves parent status and polling stops on close', async () => {
  const { DesktopRuntime } = await import('../packages/codex-adapter/dist/desktop-ipc.js');
  const runtime = new DesktopRuntime({}, 'host', 'test');
  Object.defineProperty(runtime.ipc, 'ready', { value: true });
  Object.defineProperty(runtime.reader, 'ready', { value: true });
  const parent = {
    turns: [
      {
        status: 'inProgress',
        items: [
          {
            type: 'collabAgentToolCall',
            receiverThreadIds: ['child'],
            agentsStates: { child: { status: 'running' } },
          },
        ],
      },
    ],
  };
  const reads = [];
  runtime.ipc.hasOwner = () => false;
  runtime.ipc.state = (id) => (id === 'parent' ? parent : undefined);
  runtime.ipc.follow = async () => {};
  runtime.ipc.close = () => {};
  runtime.reader.close = () => {};
  runtime.reader.read = async (method, args) => {
    reads.push([method, args.threadId]);
    return {
      thread: {
        id: args.threadId,
        turns: [{ status: 'completed', items: [{ type: 'agentMessage', text: 'saved output' }] }],
      },
    };
  };
  runtime.followSubagents('parent', parent);
  await runtime.refreshSubagentHistory();
  const projected = runtime.projectSubagent(parent.turns[0].items[0]);
  assert.equal(projected.status, 'inProgress');
  assert.match(projected.detail, /最近保存的输出/);
  assert.match(projected.detail, /saved output/);
  assert.deepEqual(reads, [['thread/read', 'child']]);
  runtime.close();
  await new Promise((resolve) => setTimeout(resolve, 1700));
  assert.equal(reads.length, 1);
});

test('history status reflects child state, never the completed tool call', async () => {
  const { renderItem } = await import('../packages/codex-adapter/dist/runtime.js');
  const item = {
    type: 'collabAgentToolCall',
    status: 'completed',
    receiverThreadIds: ['child'],
    agentsStates: { child: { status: 'running' } },
  };
  assert.equal(renderItem(item).status, 'inProgress');
  assert.equal(renderItem({ ...item, agentsStates: {} }).status, 'unknown');
});
