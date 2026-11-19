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
    assert.equal(card.text, '子 Agent'); // A path is not the child's identity or task title.
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

test('paged child history keeps original messages and rejects stale cursors', async () => {
  const { subagentHistoryMessages, subagentHistoryPage } =
    await import('../packages/codex-adapter/dist/subagents.js');
  const { historyIdentity } =
    await import('../packages/codex-adapter/dist/desktop-history-identity.js');
  const input = Array.from({ length: 181 }, (_, i) => ({
    id: 'm' + i,
    type: 'agentMessage',
    text: 'message ' + i,
  }));
  const messages = subagentHistoryMessages([{ id: 'turn', status: 'completed', items: input }]);
  assert.equal(messages.length, 181);
  assert.equal(messages[0].id, historyIdentity('m0', 'turn'));
  let page = subagentHistoryPage(messages),
    collected = page.messages;
  while (page.nextCursor) {
    page = subagentHistoryPage(messages, page.nextCursor);
    collected = [...page.messages, ...collected];
  }
  assert.deepEqual(collected, messages);
  assert.throws(() => subagentHistoryPage(messages, 'missing'), /cursor/);
  const filtered = subagentHistoryMessages([{ id: 't', items: input }], (item) =>
    item.id === 'm0' ? item : undefined,
  );
  assert.equal(filtered.length, 1);
});

test('read-only child pages require a named parent item and retain native activity deduplication', async () => {
  const { DesktopRuntime } = await import('../packages/codex-adapter/dist/desktop-ipc.js');
  const { historyIdentity } =
    await import('../packages/codex-adapter/dist/desktop-history-identity.js');
  const runtime = new DesktopRuntime({}, 'host', 'test');
  const activity = (id, child, kind) => ({
    type: 'subAgentActivity',
    id,
    agentThreadId: child,
    agentPath: '/agents/' + child,
    kind,
  });
  const parent = {
    id: 'parent',
    turns: [
      {
        id: 'pt',
        items: [activity('start', 'child', 'started'), activity('end', 'child', 'completed')],
      },
    ],
  };
  const reads = [];
  runtime.ipc.hasOwner = () => false;
  runtime.ipc.state = (id) => (id === 'parent' ? parent : undefined);
  runtime.ipc.close = () => {};
  runtime.reader.close = () => {};
  runtime.reader.read = async (method, { threadId }) => {
    reads.push([method, threadId]);
    return {
      thread: {
        id: threadId,
        title: 'Named child',
        turns: [
          {
            id: 'ct',
            status: 'completed',
            items: [
              { id: 'answer', type: 'agentMessage', text: 'complete child output' },
              activity('nested', 'grandchild', 'started'),
              activity('nested-end', 'grandchild', 'completed'),
            ],
          },
        ],
      },
    };
  };
  try {
    const id = historyIdentity('desktop-subagent-child', 'pt');
    await assert.rejects(runtime.subagentHistoryPage('parent', id, 'unrelated'), /not named/);
    assert.deepEqual(reads, [], 'unnamed children must never be queried');
    const page = await runtime.subagentHistoryPage('parent', id, 'child');
    assert.equal(page.source, 'saved-history');
    assert.equal(page.threadId, 'child');
    assert.equal(page.children.length, 1);
    assert.equal(page.messages[0].text, 'complete child output');
    assert.equal(page.messages.filter((m) => m.kind === 'subAgent').length, 1);
    assert.deepEqual(reads, [['thread/read', 'child']]);
    await assert.rejects(runtime.subagentHistoryPage('parent', 'missing', 'child'), /unavailable/);
  } finally {
    runtime.close();
  }
});

test('runtime-bound child cursors reject cross-target reuse before querying history', async () => {
  const { RuntimeSession } = await import('../packages/codex-adapter/dist/runtime-session.js');
  const { DesktopRuntime } = await import('../packages/codex-adapter/dist/desktop-ipc.js');
  const runtime = Object.create(DesktopRuntime.prototype);
  Object.defineProperty(runtime, 'ready', { value: true });
  const session = Object.create(RuntimeSession.prototype);
  let reads = 0;
  const target = {
    agentId: 'codex',
    hostId: 'host',
    workspaceId: 'workspace',
    runtimeId: 'desktop-runtime',
  };
  session.runtime = runtime;
  Object.defineProperty(session, 'hostId', { value: 'host' });
  session.closed = false;
  session.subagentReadParents = new Map();
  session.requireReady = () => {};
  session.thread = (id) => assert.equal(id, 'parent');
  session.readingTarget = () => target;
  runtime.subagentHistoryPage = async (parent, message, child, cursor) => {
    reads++;
    assert.equal(parent, 'parent');
    assert.equal(message, 'message');
    assert.equal(child ?? 'child', 'child');
    if (cursor) assert.equal(cursor, 'anchor');
    return {
      children: [{ id: 'child', title: 'Child', status: 'completed' }],
      childId: 'child',
      threadId: 'child',
      source: 'saved-history',
      messages: [],
      nextCursor: cursor ? null : 'anchor',
    };
  };
  const first = await session.subagentHistoryPage('parent', 'message');
  assert.match(first.nextCursor, /^subagent-page-v1:/);
  await session.subagentHistoryPage('parent', 'message', undefined, first.nextCursor);
  for (const [parent, message, child] of [
    ['parent', 'other', 'child'],
    ['parent', 'message', 'other'],
  ])
    await assert.rejects(
      session.subagentHistoryPage(parent, message, child, first.nextCursor),
      /target changed/,
    );
  target.runtimeId = 'other-runtime';
  await assert.rejects(
    session.subagentHistoryPage('parent', 'message', 'child', first.nextCursor),
    /target changed/,
  );
  assert.equal(reads, 2);
});

test('legacy ordinal child cursors require a unique original turn', async () => {
  const { subagentHistoryMessages, subagentHistoryPage } =
    await import('../packages/codex-adapter/dist/subagents.js');
  const messages = subagentHistoryMessages([
    {
      id: 'first-turn',
      items: [
        { id: 'item-0', type: 'agentMessage', text: 'first' },
        { id: 'item-1', type: 'agentMessage', text: 'anchor' },
      ],
    },
    { id: 'second-turn', items: [{ id: 'item-0', type: 'agentMessage', text: 'second' }] },
  ]);
  assert.notEqual(messages[0].id, messages[2].id);
  assert.deepEqual(subagentHistoryPage(messages, 'item-1').messages, [messages[0]]);
  assert.throws(
    () => subagentHistoryPage(messages, 'item-0'),
    (error) => error.status === 409 && /refresh/.test(error.message),
  );
});

test('named child request errors retain explicit HTTP status at the session boundary', async () => {
  const { RuntimeSession } = await import('../packages/codex-adapter/dist/runtime-session.js');
  const { DesktopRuntime } = await import('../packages/codex-adapter/dist/desktop-ipc.js');
  const { SubagentHistoryError } = await import('../packages/codex-adapter/dist/subagents.js');
  const runtime = Object.create(DesktopRuntime.prototype),
    session = Object.create(RuntimeSession.prototype);
  Object.defineProperty(runtime, 'ready', { value: true });
  Object.defineProperty(session, 'hostId', { value: 'host' });
  session.runtime = runtime;
  session.closed = false;
  session.subagentReadParents = new Map();
  session.requireReady = () => {};
  session.thread = () => {};
  session.readingTarget = () => ({
    agentId: 'codex',
    hostId: 'host',
    workspaceId: 'workspace',
    runtimeId: 'desktop-runtime',
  });
  for (const status of [403, 404, 409]) {
    const reason = 'Named child validation ' + status;
    runtime.subagentHistoryPage = async () => {
      throw new SubagentHistoryError(status, reason);
    };
    await assert.rejects(
      session.subagentHistoryPage('parent', 'message', 'child'),
      (error) => error.status === status && error.message === reason,
    );
  }
});

test('owned child reads metadata once while keeping live status and output', async () => {
  const { DesktopRuntime } = await import('../packages/codex-adapter/dist/desktop-ipc.js');
  const { renderItem } = await import('../packages/codex-adapter/dist/runtime.js');
  const runtime = new DesktopRuntime({}, 'host', 'test');
  Object.defineProperty(runtime.ipc, 'ready', { value: true });
  Object.defineProperty(runtime.reader, 'ready', { value: true });
  const item = {
    id: 'tool',
    type: 'collabAgentToolCall',
    tool: 'sendInput',
    prompt: 'followup',
    receiverThreadIds: ['child'],
    agentsStates: { child: { status: 'failed' } },
  };
  const parent = {
    id: 'parent',
    turns: [{ id: 'parent-turn', status: 'inProgress', items: [item] }],
  };
  const child = {
    id: 'child',
    source: {},
    turns: [
      {
        id: 'child-turn',
        status: 'inProgress',
        items: [{ id: 'live', type: 'agentMessage', text: 'live progress' }],
      },
    ],
  };
  const reads = [];
  let owned = true;
  runtime.ipc.hasOwner = (id) => id === 'child' && owned;
  runtime.ipc.state = (id) =>
    id === 'parent' ? parent : id === 'child' && owned ? child : undefined;
  runtime.ipc.completeHistory = async () => child;
  runtime.ipc.close = () => {};
  runtime.reader.close = () => {};
  runtime.reader.read = async (method, args) => {
    reads.push([method, args]);
    return {
      thread: {
        id: 'child',
        title: 'Investigate task',
        source: { subAgent: { thread_spawn: { agent_nickname: 'Terra' } } },
        turns: args.includeTurns
          ? [
              {
                status: 'completed',
                items: [{ type: 'agentMessage', text: 'saved output after owner exit' }],
              },
            ]
          : [],
      },
    };
  };
  try {
    runtime.followSubagents('parent', parent);
    await runtime.refreshSubagentHistory();
    const result = renderItem(runtime.projectSubagent(item));
    assert.equal(result.text, 'Terra · Investigate task');
    assert.equal(result.status, 'inProgress');
    assert.match(result.detail, /live progress/);
    assert.doesNotMatch(result.detail, /stale saved output|最近保存的输出/);
    const popup = await runtime.subagentHistoryPage('parent', 'tool', 'child');
    assert.equal(popup.children[0].title, 'Terra · Investigate task');
    assert.equal(popup.children[0].status, 'inProgress');
    assert.equal(popup.source, 'desktop-runtime');
    await runtime.refreshSubagentHistory();
    assert.deepEqual(reads, [['thread/read', { threadId: 'child', includeTurns: false }]]);
    assert.equal(runtime.subagentHistory.has('child'), false);
    assert.equal(runtime.subagentIdentities.get('child').agentNickname, 'Terra');
    assert.equal(runtime.subagentIdentities.get('child').title, 'Investigate task');
    owned = false;
    parent.turns[0].status = 'completed';
    await runtime.refreshSubagentHistory();
    assert.deepEqual(reads, [
      ['thread/read', { threadId: 'child', includeTurns: false }],
      ['thread/read', { threadId: 'child', includeTurns: true }],
    ]);
    assert.equal(runtime.subagentHistory.has('child'), true);
    const saved = renderItem(runtime.projectSubagent(item));
    assert.equal(saved.text, 'Terra · Investigate task');
    assert.equal(saved.status, 'failed');
    assert.match(saved.detail, /最近保存的输出/);
    assert.match(saved.detail, /saved output after owner exit/);
    assert.doesNotMatch(saved.detail, /live progress/);
    await runtime.refreshSubagentHistory();
    assert.equal(reads.length, 2);
    // Identity eviction must be recoverable even with cached output and a terminal parent.
    runtime.subagentIdentities.delete('child');
    await runtime.refreshSubagentHistory();
    assert.equal(reads.length, 3);
    assert.equal(renderItem(runtime.projectSubagent(item)).text, 'Terra · Investigate task');
  } finally {
    runtime.close();
  }
});

async function historyRuntime() {
  const { DesktopRuntime } = await import('../packages/codex-adapter/dist/desktop-ipc.js');
  const runtime = new DesktopRuntime({}, 'host', 'test');
  const item = {
    id: 'tool-1',
    type: 'collabAgentToolCall',
    receiverThreadIds: ['child'],
    agentsStates: { child: { status: 'running' } },
  };
  const parent = { id: 'parent', turns: [{ id: 'p-turn', status: 'completed', items: [item] }] };
  const child = {
    id: 'child',
    turns: [
      {
        id: 'c-turn',
        status: 'completed',
        items: [{ id: 'child-message', type: 'agentMessage', text: 'saved' }],
      },
    ],
  };
  const reads = [];
  const live = [];
  runtime.ipc.state = () => undefined;
  runtime.ipc.hasOwner = () => false;
  runtime.ipc.completeHistory = async (id) => {
    live.push(id);
    return id === 'parent' ? parent : child;
  };
  runtime.reader.read = async (method, args) => {
    reads.push([method, args]);
    return { thread: args.threadId === 'parent' ? parent : child };
  };
  return { runtime, parent, child, reads, live };
}

test('saved paginated parent and child histories retain order and turn status', async () => {
  const { runtime } = await historyRuntime();
  const calls = [];
  runtime.reader.read = async (method, args) => {
    calls.push([method, { ...args }]);
    const parent = args.threadId === 'parent';
    if (method === 'thread/read')
      return { thread: { id: args.threadId, historyMode: 'paginated', turns: [] } };
    assert.equal(args.sortDirection, 'desc');
    assert.equal(args.limit, 100);
    if (method === 'thread/turns/list') {
      assert.equal(args.itemsView, 'notLoaded');
      if (parent)
        return {
          data: [
            { id: 'p-new', status: 'completed' },
            { id: 'p-old', status: 'completed' },
          ],
          nextCursor: null,
        };
      return args.cursor
        ? { data: [{ id: 'c-old', status: 'completed' }], nextCursor: null }
        : {
            data: [
              { id: 'c-failed', status: 'failed', error: { message: 'failed turn' } },
              { id: 'c-new', status: 'inProgress' },
            ],
            nextCursor: 'turn-page-2',
          };
    }
    assert.equal(method, 'thread/items/list');
    if (parent)
      return args.cursor
        ? {
            data: [
              {
                turnId: 'p-old',
                item: { id: 'tool-1', type: 'collabAgentToolCall', receiverThreadIds: ['child'] },
              },
            ],
            nextCursor: null,
          }
        : {
            data: [{ turnId: 'p-new', item: { id: 'other', type: 'agentMessage', text: 'later' } }],
            nextCursor: 'parent-page-2',
          };
    return args.cursor
      ? {
          data: [{ turnId: 'c-old', item: { id: 'older', type: 'agentMessage', text: 'older' } }],
          nextCursor: null,
        }
      : {
          data: [{ turnId: 'c-new', item: { id: 'newer', type: 'agentMessage', text: 'newer' } }],
          nextCursor: 'child-page-2',
        };
  };
  const result = await runtime.subagentHistoryPage('parent', 'tool-1', 'child');
  assert.deepEqual(
    result.messages.map(({ id, runId, status }) => [id, runId, status]),
    [
      ['older', 'c-old', 'completed'],
      ['newer', 'c-new', 'inProgress'],
      ['turn-failure-c-failed', 'c-failed', 'failed'],
    ],
  );
  assert.ok(
    calls.some(
      ([method, args]) =>
        method === 'thread/items/list' &&
        args.threadId === 'parent' &&
        args.cursor === 'parent-page-2',
    ),
  );
  assert.ok(
    calls.some(
      ([method, args]) =>
        method === 'thread/items/list' &&
        args.threadId === 'child' &&
        args.cursor === 'child-page-2',
    ),
  );
  assert.ok(
    calls.some(
      ([method, args]) =>
        method === 'thread/turns/list' &&
        args.threadId === 'child' &&
        args.cursor === 'turn-page-2',
    ),
  );
});

test('saved paginated history rejects a repeated next cursor', async () => {
  const { runtime } = await historyRuntime();
  let pageCalls = 0;
  runtime.reader.read = async (method, args) => {
    if (method === 'thread/read')
      return { thread: { id: args.threadId, historyMode: 'paginated', turns: [] } };
    assert.equal(method, 'thread/turns/list');
    pageCalls++;
    return { data: [], nextCursor: 'same-cursor' };
  };
  await assert.rejects(
    runtime.subagentHistoryPage('parent', 'tool-1', 'child'),
    /cursor did not advance/i,
  );
  assert.equal(pageCalls, 2);
});

test('child identity and title survive projection, rendering, and metadata updates', async () => {
  const { renderItem } = await import('../packages/codex-adapter/dist/runtime.js');
  const item = {
    type: 'collabAgentToolCall',
    tool: 'sendInput',
    prompt: 'followup instruction',
    receiverThreadIds: ['a'],
    agentsStates: { a: { status: 'running' } },
  };
  const child = {
    agentNickname: 'Terra',
    name: 'Investigate issue',
    turns: [{ status: 'inProgress', items: [{ type: 'agentMessage', text: 'output' }] }],
  };
  const projected = subagentItem(item, () => child);
  const rendered = renderItem(projected);
  assert.equal(rendered.text, 'Terra · Investigate issue');
  assert.equal(rendered.status, 'inProgress');
  assert.match(rendered.detail, /output/);
  assert.doesNotMatch(rendered.text, /sendInput|followup|output/);
  const updated = renderItem(
    subagentItem({ ...item, agentsStates: { a: { status: 'completed' } } }, () => ({
      ...child,
      turns: [{ status: 'completed', items: [] }],
    })),
  );
  assert.equal(updated.text, rendered.text);
  assert.equal(updated.status, 'completed');
});

test('multiple explicit children and spawn fallback do not use followup instructions as titles', async () => {
  const { renderItem } = await import('../packages/codex-adapter/dist/runtime.js');
  const children = {
    a: { agentNickname: 'Alpha', name: 'First' },
    b: {
      source: { subAgent: { thread_spawn: { agent_nickname: 'Beta' } } },
      preview: 'Second\nmore',
    },
    extra: { name: 'Unrelated' },
  };
  const base = {
    type: 'collabAgentToolCall',
    receiverThreadIds: ['a', 'b'],
    agentsStates: { extra: { status: 'running' } },
  };
  const result = renderItem(
    subagentItem({ ...base, tool: 'sendInput', prompt: 'followup' }, (id) => children[id]),
  );
  assert.match(result.text, /Alpha · First/);
  assert.match(result.text, /Beta · Second/);
  assert.doesNotMatch(result.text, /Unrelated|followup|sendInput/);
  assert.equal(
    renderItem(
      subagentItem(
        { ...base, receiverThreadIds: ['missing'], tool: 'spawn', prompt: 'Spawn task\nmore' },
        () => undefined,
      ),
    ).text,
    'Spawn task',
  );
  assert.equal(
    renderItem(
      subagentItem(
        { ...base, receiverThreadIds: ['missing'], tool: 'sendInput', prompt: 'followup' },
        () => undefined,
      ),
    ).text,
    '子 Agent',
  );
});

test('saved raw collaboration items keep task identity instead of tool verbs', async () => {
  const { renderItem } = await import('../packages/codex-adapter/dist/runtime.js');
  const raw = {
    type: 'collabAgentToolCall',
    prompt: 'Original task\nfull instructions',
    agentsStates: {},
  };
  for (const tool of ['spawn', 'spawnAgent'])
    assert.equal(renderItem({ ...raw, tool }).text, 'Original task');
  assert.equal(renderItem({ ...raw, tool: 'sendInput', prompt: 'Followup only' }).text, '子 Agent');
  assert.equal(
    renderItem({ type: 'subAgent', text: 'Terra · Original task', tool: 'sendInput' }).text,
    'Terra · Original task',
  );
});
