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
