import { list, object, string, type WireObject } from './runtime.js';

/** Version-sensitive requests stay here; phones submit only a declared decision ID. */
export interface ApprovalProjection {
  title: string;
  detail: string;
  decisions: string[];
  decisionLabels: Record<string, string>;
  responses: Record<string, WireObject>;
  desktopMethod: string;
}

export function approvalRequest(
  method: string,
  params: WireObject,
): ApprovalProjection | undefined {
  if (method === 'item/permissions/requestApproval') {
    const permissions = object(params.permissions);
    return {
      title: 'Codex 请求额外权限',
      detail: [string(params.reason), JSON.stringify(permissions, null, 2)]
        .filter(Boolean)
        .join('\n'),
      decisions: ['accept', 'acceptForSession', 'decline'],
      decisionLabels: { accept: '允许本轮', acceptForSession: '本会话允许', decline: '拒绝' },
      responses: {
        accept: { permissions, scope: 'turn' },
        acceptForSession: { permissions, scope: 'session' },
        decline: { permissions: {}, scope: 'turn' },
      },
      desktopMethod: 'thread-follower-permissions-request-approval-response',
    };
  }
  if (method !== 'mcpServer/elicitation/request') return;
  const meta = object(params._meta),
    schema = object(params.requestedSchema);
  const properties = object(schema.properties),
    keys = Object.keys(properties);
  // Approvals have no input fields, or the single explicit boolean confirmation.
  // URLs, verification challenges and richer forms require the original desktop UI.
  const booleanConfirmation =
    keys.length === 1 &&
    keys[0] === 'approved' &&
    object(properties.approved).type === 'boolean' &&
    Object.keys(object(properties.approved)).every((key) =>
      ['type', 'title', 'description', 'default'].includes(key),
    );
  const supported =
    params.mode === 'form' &&
    schema.type === 'object' &&
    schema.properties !== null &&
    typeof schema.properties === 'object' &&
    !Array.isArray(schema.properties) &&
    (keys.length === 0 || booleanConfirmation) &&
    (schema.required === undefined || Array.isArray(schema.required)) &&
    list(schema.required).every((key) => key === 'approved' && booleanConfirmation) &&
    Object.keys(schema).every((key) =>
      [
        'type',
        'properties',
        'required',
        'additionalProperties',
        'title',
        'description',
        '$schema',
      ].includes(key),
    );
  const responses: Record<string, WireObject> = {
    decline: { action: 'decline', content: null },
    cancel: { action: 'cancel', content: null },
  };
  if (supported) {
    responses.accept = { action: 'accept', content: booleanConfirmation ? { approved: true } : {} };
    const scopes = Array.isArray(meta.persist) ? meta.persist : [meta.persist];
    // Execution-bound confirmations cannot become a lasting permission grant.
    const executionBound =
      Object.hasOwn(object(meta.tool_params), 'plan_token') ||
      Object.hasOwn(object(meta.tool_params), 'confirmation_summary');
    if (!executionBound)
      for (const [scope, decision] of [
        ['session', 'acceptForSession'],
        ['always', 'acceptAlways'],
      ] as const) {
        if (scopes.includes(scope))
          responses[decision] = { ...responses.accept, _meta: { persist: scope } };
      }
  }
  const display = list(meta.tool_params_display)
    .map(object)
    .map(
      (field) =>
        `${string(field.display_name) || string(field.name)}: ${typeof field.value === 'string' ? field.value : JSON.stringify(field.value)}`,
    );
  const connector = string(meta.connector_id)
    .trim()
    .toLowerCase()
    .split(/[^a-z0-9]+/)
    .filter(Boolean)
    .join('-');
  const computerUse = connector === 'computer-use' || connector.startsWith('computer-use-');
  // Computer Use's default grant already applies to the conversation. Present
  // one conversation action and return the explicit advertised session scope.
  if (computerUse && responses.acceptForSession) {
    responses.accept = responses.acceptForSession;
    delete responses.acceptForSession;
  }
  const title =
    string(meta.tool_title) || string(params.title) || string(params.message) || '应用操作需要审批';
  const detail = [
    string(params.message) === title ? '' : string(params.message),
    string(meta.subtitle),
    string(meta.connector_name) || string(params.serverName),
    ...display,
  ];
  if (!display.length && meta.tool_params) detail.push(JSON.stringify(meta.tool_params, null, 2));
  if (meta.riskLevel === 'high') detail.unshift('高风险操作');
  if (computerUse) detail.push('允许后，Codex 可以查看、截屏并操作此应用。');
  if (!supported) detail.push('请在电脑端完成此请求；手机可以拒绝或取消。');
  return {
    title,
    detail: detail.filter(Boolean).join('\n'),
    decisions: ['accept', 'acceptForSession', 'acceptAlways', 'decline', 'cancel'].filter((key) =>
      Object.hasOwn(responses, key),
    ),
    decisionLabels: {
      accept: computerUse ? '本会话允许' : '允许一次',
      acceptForSession: '本会话允许',
      acceptAlways: '始终允许',
      decline: '拒绝',
      cancel: '取消',
    },
    responses,
    desktopMethod: 'thread-follower-submit-mcp-server-elicitation-response',
  };
}
