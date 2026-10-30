import {
  object,
  list,
  string,
  renderItem,
  subagentStatus,
  combinedSubagentStatus,
  type WireObject,
} from './runtime.js';

export function subagentIds(item: WireObject): string[] {
  if (item.type !== 'collabAgentToolCall') return [];
  return [...new Set(list(item.receiverThreadIds).map(string).filter(Boolean))].slice(0, 32);
}

/** Read-only projection of children explicitly named by the original parent's tool item. */
export function subagentItem(
  item: WireObject,
  state: (id: string) => WireObject | undefined,
): WireObject {
  if (item.type !== 'collabAgentToolCall') return item;
  const agents = object(item.agentsStates);
  const statuses: string[] = [];
  const details = subagentIds(item).map((id) => {
    const reported = object(agents[id]);
    const child = state(id);
    const turns = list(child?.turns).map(object);
    const last = turns.at(-1);
    const status =
      child && child.agentHubSource !== 'history'
        ? string(last?.status) || string(object(child.threadRuntimeStatus).type)
        : string(reported.status);
    statuses.push(subagentStatus(status));
    const messages = turns
      .flatMap((turn) => list(turn.items))
      .map(object)
      .filter((value) =>
        [
          'agentMessage',
          'commandExecution',
          'fileChange',
          'mcpToolCall',
          'dynamicToolCall',
        ].includes(string(value.type)),
      )
      .slice(-24)
      .map((value) => renderItem(value, 16_000))
      .map((message) => [message.text, message.detail].filter(Boolean).join('\n'))
      .filter(Boolean);
    return [
      id + (status ? ` · ${status}` : ' · unknown'),
      child?.agentHubSource === 'history' ? '最近保存的输出' : '',
      messages.join('\n\n') || string(reported.message),
    ]
      .filter(Boolean)
      .join('\n');
  });
  const status = combinedSubagentStatus(statuses);
  return {
    ...item,
    type: 'subAgent',
    status,
    text: string(item.tool) || '子 Agent',
    detail: [string(item.prompt), ...details].filter(Boolean).join('\n\n').slice(-200_000),
  };
}
