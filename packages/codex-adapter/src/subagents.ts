import { historyIdentity } from './desktop-history-identity.js';
import {
  boundedSubagentDetail,
  combinedSubagentStatus,
  list,
  object,
  renderItem,
  string,
  SUBAGENT_DETAIL_LIMIT,
  subagentDetailPrefix,
  subagentStatus,
  type WireObject,
} from './runtime.js';

export class SubagentHistoryError extends Error {
  constructor(
    readonly status: 403 | 404 | 409,
    message: string,
  ) {
    super(message);
    this.name = 'SubagentHistoryError';
  }
}

const maxChildren = 32;
const outputKinds = new Set([
  'agentMessage',
  'commandExecution',
  'fileChange',
  'mcpToolCall',
  'dynamicToolCall',
]);

function namedChildren(item: WireObject): string[] {
  return [...new Set(list(item.receiverThreadIds).map(string).filter(Boolean))];
}

export function subagentIds(item: WireObject): string[] {
  if (item.type === 'collabAgentToolCall') return namedChildren(item).slice(0, maxChildren);
  const child = string(item.agentThreadId);
  return item.type === 'subAgentActivity' &&
    string(item.id) &&
    child &&
    ['started', 'interacted', 'completed', 'interrupted'].includes(string(item.kind))
    ? [child]
    : [];
}

export function subagentActivityStatus(kind: unknown): string | undefined {
  if (kind === 'started') return 'inProgress';
  if (kind === 'completed' || kind === 'interrupted') return kind;
  // An interaction records contact with a child, not a lifecycle transition.
  return undefined;
}

export interface SubagentTranscript {
  text: string;
  omitted: boolean;
}

function joinTranscript(parts: Iterable<string>): SubagentTranscript {
  let text = '';
  for (const part of parts) {
    if (!part) continue;
    const next = text ? '\n\n' + part : part;
    const remaining = SUBAGENT_DETAIL_LIMIT - text.length;
    if (next.length > remaining) {
      return { text: text + subagentDetailPrefix(next, remaining), omitted: true };
    }
    text += next;
  }
  return { text, omitted: false };
}

/** Collect both live and saved output with the same bound and no per-item tail cut. */
export function subagentTranscript(turns: unknown): SubagentTranscript {
  return joinTranscript(
    (function* () {
      for (const turn of list(turns)) {
        for (const raw of list(object(turn).items)) {
          const item = object(raw);
          if (!outputKinds.has(string(item.type))) continue;
          const message = renderItem(item, Infinity);
          yield [message.text, message.detail].filter(Boolean).join('\n');
        }
      }
    })(),
  );
}

/** Read-only projection of children explicitly named by the original parent's tool item. */
export function subagentItem(
  item: WireObject,
  state: (id: string) => WireObject | undefined,
  saved: (id: string) => SubagentTranscript | undefined = () => undefined,
): WireObject {
  if (item.type !== 'collabAgentToolCall') return item;
  const agents = object(item.agentsStates);
  const ids = namedChildren(item);
  const children = ids.slice(0, maxChildren).map((id) => {
    const reported = object(agents[id]);
    const live = state(id);
    const status = live
      ? string(object(list(live.turns).at(-1)).status) ||
        string(object(live.threadRuntimeStatus).type)
      : string(reported.status);
    return { id, status, live, reported };
  });

  let omitted = ids.length > children.length;
  const transcript = joinTranscript(
    (function* () {
      yield string(item.prompt);
      for (const { id, status, live, reported } of children) {
        const history = live ? undefined : saved(id);
        const output = live ? subagentTranscript(live.turns) : history;
        if (output?.omitted) omitted = true;
        yield [
          id + ' · ' + (status || 'unknown'),
          history ? '最近保存的输出' : '',
          output?.text || string(reported.message),
        ]
          .filter(Boolean)
          .join('\n');
      }
    })(),
  );
  return {
    ...item,
    type: 'subAgent',
    status: combinedSubagentStatus(children.map(({ status }) => subagentStatus(status))),
    text: string(item.tool) || '子 Agent',
    detail: boundedSubagentDetail(
      transcript.text,
      SUBAGENT_DETAIL_LIMIT,
      omitted || transcript.omitted,
    ),
  };
}

/** Same item renderer as the parent, preserving message and tool boundaries. */
export function subagentHistoryMessages(
  turns: WireObject[],
  project?: (item: WireObject) => WireObject | undefined,
) {
  return turns.flatMap((turn) => {
    const items = list(turn.items).map(object);
    if (turn.status === 'failed')
      items.push({
        id: 'turn-failure-' + string(turn.turnId || turn.id),
        type: 'turnFailure',
        text: string(object(turn.error).message) || '任务执行失败，电脑未提供错误详情。',
        status: 'failed',
      });
    return items.flatMap((raw) => {
      let item = project ? project(raw) : raw;
      if (!item) return [];
      if (['todo-list', 'planImplementation', 'steered'].includes(string(item.type))) return [];
      if (item.type === 'steeringUserMessage') {
        if (!string(item.serverUserMessageId)) return [];
        item = {
          ...item,
          type: 'userMessage',
          id: item.serverUserMessageId,
          content: item.input,
          status: item.status === 'accepted' ? 'completed' : 'inProgress',
        };
      }
      const { paths, ...message } = renderItem({ ...item, runId: turn.turnId || turn.id });
      void paths;
      if (!message.id) return [];
      if (message.kind === 'reasoning' && !message.text) return [];
      if (message.role === 'assistant')
        message.status =
          turn.status === 'inProgress' &&
          item.status !== 'completed' &&
          !Object.hasOwn(object(turn.agentMessageCompletedAtMsById), message.id)
            ? 'inProgress'
            : ['failed', 'interrupted'].includes(string(turn.status))
              ? string(turn.status)
              : 'completed';
      else if (
        message.status === 'inProgress' &&
        ['failed', 'interrupted', 'completed'].includes(string(turn.status))
      )
        message.status = string(turn.status);
      message.id = historyIdentity(message.id, string(turn.turnId || turn.id));
      return [message];
    });
  });
}

export function subagentHistoryPage(
  messages: ReturnType<typeof subagentHistoryMessages>,
  cursor?: string,
) {
  let end =
    cursor === undefined ? messages.length : messages.findIndex((message) => message.id === cursor);
  if (end < 0 && cursor && /^item-\d+$/.test(cursor)) {
    const matches = messages.flatMap((message, index) =>
      message.id === historyIdentity(cursor, message.runId) ? [index] : [],
    );
    if (matches.length === 1) end = matches[0]!;
  }
  if (end < 0)
    throw new SubagentHistoryError(
      409,
      'Subagent history cursor no longer exists; refresh the record',
    );
  let start = end;
  let bytes = 0;
  while (start > 0 && end - start < 80) {
    const size = Buffer.byteLength(JSON.stringify(messages[start - 1]));
    if (bytes + size > 1024 * 1024 && start < end) break;
    bytes += size;
    start--;
  }
  return {
    messages: messages.slice(start, end),
    nextCursor: start > 0 ? messages[start]!.id : null,
  };
}
