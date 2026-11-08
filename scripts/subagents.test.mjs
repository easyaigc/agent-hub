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

test('live details retain older items and full tool output below the card limit', async () => {
  const { renderItem } = await import('../packages/codex-adapter/dist/runtime.js');
  const command = 'tool head\n' + '界'.repeat(70_000) + '\ntool tail';
  const messages = Array.from({ length: 26 }, (_, index) => `child reply ${index}`);
  const projected = subagentItem(
    { type: 'collabAgentToolCall', prompt: 'original prompt', receiverThreadIds: ['child'] },
    () => ({
      turns: [
        {
          status: 'completed',
          items: [
            { type: 'commandExecution', command: 'read', aggregatedOutput: command },
            ...messages.map((text) => ({ type: 'agentMessage', text })),
          ],
        },
      ],
    }),
  );
  assert.equal(
    renderItem(projected).detail,
    ['original prompt', 'child · completed\nread\n' + command, ...messages].join('\n\n'),
  );
});

test('over-budget detail keeps its beginning, preserves Unicode, and marks omissions', async () => {
  const { renderItem, SUBAGENT_DETAIL_LIMIT } =
    await import('../packages/codex-adapter/dist/runtime.js');
  const ids = Array.from({ length: 33 }, (_, index) => `child-${index}`);
  const requested = [];
  const projected = subagentItem(
    { type: 'collabAgentToolCall', prompt: 'original prompt', receiverThreadIds: ids },
    (id) => {
      requested.push(id);
      return { turns: [{ items: [{ type: 'agentMessage', text: '😀'.repeat(110_000) }] }] };
    },
  );
  const detail = renderItem(projected).detail;
  assert.deepEqual(requested, ids.slice(0, 32));
  assert.ok(detail.length <= SUBAGENT_DETAIL_LIMIT);
  assert.ok(detail.startsWith('original prompt\n\nchild-0 · unknown\n😀'));
  assert.ok(detail.endsWith('[仅显示部分子代理详情，请在电脑查看原文]'));
  assert.equal(Buffer.from(detail, 'utf8').toString('utf8'), detail);
  assert.doesNotMatch(detail, /child-32/);

  const tooManyChildren = subagentItem(
    { type: 'collabAgentToolCall', receiverThreadIds: ids },
    () => undefined,
  );
  assert.match(tooManyChildren.detail, /child-31 · unknown/);
  assert.match(tooManyChildren.detail, /仅显示部分子代理详情/);
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
  const savedTurns = Array.from({ length: 5 }, (_, index) => ({
    status: 'completed',
    items: [
      {
        type: 'commandExecution',
        command: `saved command ${index}`,
        aggregatedOutput: 'saved head ' + 'x'.repeat(18_000) + ' saved tail',
      },
    ],
  }));
  runtime.reader.read = async (method, args) => {
    reads.push([method, args.threadId]);
    return {
      thread: {
        id: args.threadId,
        turns: savedTurns,
      },
    };
  };
  runtime.followSubagents('parent', parent);
  await runtime.refreshSubagentHistory();
  const projected = runtime.projectSubagent(parent.turns[0].items[0]);
  assert.equal(projected.status, 'inProgress');
  assert.match(projected.detail, /最近保存的输出/);
  assert.equal(
    projected.detail,
    'child · running\n最近保存的输出\n' +
      savedTurns
        .map(({ items: [item] }) => item.command + '\n' + item.aggregatedOutput)
        .join('\n\n'),
  );
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

test('native desktop activities update one stable parent card from the exact child', async () => {
  const { DesktopRuntime } = await import('../packages/codex-adapter/dist/desktop-ipc.js');
  const runtime = new DesktopRuntime({}, 'host', 'test');
  Object.defineProperty(runtime.ipc, 'ready', { value: true });
  Object.defineProperty(runtime.reader, 'ready', { value: true });
  const activity = (id, child, kind) => ({
    type: 'subAgentActivity',
    id,
    agentThreadId: child,
    agentPath: '/root/' + child,
    kind,
  });
  const started = activity('start', 'child', 'started');
  const completed = activity('complete', 'child', 'completed');
  const interacted = activity('contact', 'child', 'interacted');
  const legacy = {
    type: 'collabAgentToolCall',
    id: 'tool',
    receiverThreadIds: ['legacy-child'],
    agentsStates: { 'legacy-child': { status: 'running' } },
  };
  const parent = {
    id: 'parent',
    turns: [
      {
        status: 'inProgress',
        items: [
          started,
          completed,
          interacted,
          legacy,
          activity('legacy-activity', 'legacy-child', 'started'),
          activity('self', 'parent', 'started'),
          activity('bad', 'untrusted', 'unknown'),
        ],
      },
    ],
  };
  const reads = [];
  let text = 'original head\n' + 'a'.repeat(70_000) + '\noriginal tail';
  runtime.ipc.hasOwner = () => false;
  runtime.ipc.state = (id) => (id === 'parent' ? parent : undefined);
  runtime.ipc.follow = async () => {};
  runtime.ipc.close = () => {};
  runtime.reader.close = () => {};
  runtime.reader.read = async (method, { threadId }) => {
    reads.push([method, threadId]);
    return {
      thread: {
        id: threadId,
        turns: [{ items: [{ type: 'agentMessage', text }] }],
      },
    };
  };
  try {
    runtime.followSubagents('parent', parent);
    await runtime.refreshSubagentHistory();
    const card = runtime.projectSubagent(started, parent);
    assert.equal(card.type, 'subAgent');
    assert.equal(card.id, 'desktop-subagent-child');
    assert.equal(card.text, '/root/child');
    assert.equal(card.status, 'completed');
    assert.equal(card.detail, 'child · completed\n最近保存的输出\n' + text);
    for (const item of parent.turns[0].items.slice(1)) {
      if (item === legacy) assert.equal(runtime.projectSubagent(item, parent).id, 'tool');
      else assert.equal(runtime.projectSubagent(item, parent), undefined);
    }
    assert.deepEqual(reads, [
      ['thread/read', 'child'],
      ['thread/read', 'legacy-child'],
    ]);

    text = 'new original head\n' + '🙂'.repeat(100_000);
    await runtime.refreshSubagentHistory();
    const resumed = activity('resumed', 'child', 'started');
    const shiftedParent = { id: 'parent', turns: [{ items: [resumed] }] };
    const updated = runtime.projectSubagent(resumed, shiftedParent);
    assert.equal(updated.id, card.id);
    assert.equal(updated.status, 'inProgress');
    assert.match(updated.detail, /new original head/);
    assert.match(updated.detail, /仅显示部分子代理详情/);
    assert.equal(updated.detail.length <= 200_000, true);
  } finally {
    runtime.close();
  }
});
