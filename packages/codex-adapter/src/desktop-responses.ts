import { Agent, request, type RequestOptions } from 'node:https';
import type { IncomingMessage } from 'node:http';
import { execFileSync } from 'node:child_process';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { StringDecoder } from 'node:string_decoder';
import { readDesktopBackendAuth } from './desktop-backend-auth.js';

type Row = Record<string, unknown>;
const row = (value: unknown): Row =>
  value && typeof value === 'object' && !Array.isArray(value) ? (value as Row) : {};

export class ResponsesProxyError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

export function prepareDesktopResponse(value: unknown, models: readonly string[]) {
  const data = row(value);
  if (
    !Object.keys(data).length ||
    typeof data.model !== 'string' ||
    !models.includes(data.model) ||
    (data.stream !== undefined && typeof data.stream !== 'boolean') ||
    (data.store !== undefined && data.store !== false) ||
    (typeof data.input !== 'string' && !Array.isArray(data.input)) ||
    ['base_url', 'baseURL', 'url', 'endpoint', 'headers', 'api_key', 'authorization'].some(
      (key) => key in data,
    ) ||
    data.previous_response_id !== undefined ||
    data.conversation !== undefined ||
    data.background !== undefined
  )
    throw new ResponsesProxyError(
      400,
      'invalid_response_request',
      '需要允许的模型和完整 input；仅支持 store=false、无持久上游会话的请求',
    );
  const input =
    typeof data.input === 'string'
      ? [{ role: 'user', content: data.input }]
      : (data.input as unknown[]).map((item) =>
          row(item).role === 'system' ? { ...row(item), role: 'developer' } : item,
        );
  if (data.instructions !== undefined && typeof data.instructions !== 'string')
    throw new ResponsesProxyError(400, 'invalid_instructions', 'instructions 必须是文字');
  return {
    model: data.model,
    stream: data.stream === true,
    body: Buffer.from(
      JSON.stringify({
        ...data,
        input,
        instructions: data.instructions ?? '',
        store: false,
        stream: true,
      }),
    ),
  };
}

/** The compatibility route translates protocols. The Responses route preserves SSE bytes. */
export function chatCompletionToResponse(value: unknown) {
  const data = row(value);
  if (
    typeof data.model !== 'string' ||
    !Array.isArray(data.messages) ||
    (data.stream !== undefined && typeof data.stream !== 'boolean') ||
    (data.n !== undefined && data.n !== 1)
  )
    throw new ResponsesProxyError(
      400,
      'invalid_chat_request',
      '需要 model、messages，且 n 只能为 1',
    );
  const instructions: string[] = [];
  const input: Row[] = [];
  for (const raw of data.messages) {
    const message = row(raw);
    if (message.role === 'system' || message.role === 'developer') {
      const content = message.content;
      if (typeof content === 'string') instructions.push(content);
      else if (Array.isArray(content) && content.every((part) => row(part).type === 'text'))
        instructions.push(content.map((part) => String(row(part).text ?? '')).join('\n'));
      else throw new ResponsesProxyError(400, 'invalid_system_message', '系统消息只支持文字');
      continue;
    }
    if (message.role === 'tool') {
      if (typeof message.tool_call_id !== 'string' || typeof message.content !== 'string')
        throw new ResponsesProxyError(
          400,
          'invalid_tool_output',
          '工具结果需要 tool_call_id 和文字 content',
        );
      input.push({
        type: 'function_call_output',
        call_id: message.tool_call_id,
        output: message.content,
      });
      continue;
    }
    if (message.role !== 'user' && message.role !== 'assistant')
      throw new ResponsesProxyError(400, 'unsupported_chat_role', '不支持的消息角色');
    if (message.content !== null && message.content !== undefined && message.content !== '') {
      const content =
        typeof message.content === 'string'
          ? [
              {
                type: message.role === 'assistant' ? 'output_text' : 'input_text',
                text: message.content,
              },
            ]
          : Array.isArray(message.content)
            ? message.content.map((part) => {
                const item = row(part);
                if (item.type === 'text' && typeof item.text === 'string')
                  return {
                    type: message.role === 'assistant' ? 'output_text' : 'input_text',
                    text: item.text,
                  };
                if (
                  item.type === 'image_url' &&
                  message.role === 'user' &&
                  typeof row(item.image_url).url === 'string'
                )
                  return {
                    type: 'input_image',
                    image_url: row(item.image_url).url,
                    ...(row(item.image_url).detail ? { detail: row(item.image_url).detail } : {}),
                  };
                throw new ResponsesProxyError(
                  400,
                  'unsupported_chat_content',
                  '仅支持文字和用户图片输入',
                );
              })
            : null;
      if (!content) throw new ResponsesProxyError(400, 'invalid_chat_content', '无效消息内容');
      input.push({ role: message.role, content });
    }
    if (message.tool_calls !== undefined) {
      if (message.role !== 'assistant' || !Array.isArray(message.tool_calls))
        throw new ResponsesProxyError(400, 'invalid_tool_calls', '无效工具调用');
      for (const rawCall of message.tool_calls) {
        const call = row(rawCall),
          fn = row(call.function);
        if (
          call.type !== 'function' ||
          typeof call.id !== 'string' ||
          typeof fn.name !== 'string' ||
          typeof fn.arguments !== 'string'
        )
          throw new ResponsesProxyError(400, 'invalid_function_call', '无效函数调用');
        input.push({
          type: 'function_call',
          call_id: call.id,
          name: fn.name,
          arguments: fn.arguments,
        });
      }
    }
  }
  const result: Row = {
    model: data.model,
    input,
    instructions: instructions.join('\n\n'),
    store: false,
    stream: data.stream === true,
  };
  if (data.tools !== undefined) {
    if (!Array.isArray(data.tools))
      throw new ResponsesProxyError(400, 'invalid_tools', 'tools 必须是数组');
    result.tools = data.tools.map((raw) => {
      const tool = row(raw),
        fn = row(tool.function);
      if (tool.type !== 'function' || typeof fn.name !== 'string')
        throw new ResponsesProxyError(400, 'unsupported_tool', '兼容入口仅支持 function 工具定义');
      return { type: 'function', ...fn };
    });
  }
  if (data.tool_choice !== undefined) {
    result.tool_choice =
      typeof data.tool_choice === 'string'
        ? data.tool_choice
        : { type: 'function', name: row(row(data.tool_choice).function).name };
  }
  if (data.parallel_tool_calls !== undefined) result.parallel_tool_calls = data.parallel_tool_calls;
  if (typeof data.reasoning_effort === 'string')
    result.reasoning = { effort: data.reasoning_effort };
  else if (Object.keys(row(data.reasoning)).length) result.reasoning = data.reasoning;
  const format = row(data.response_format);
  if (format.type === 'json_object') result.text = { format: { type: 'json_object' } };
  else if (format.type === 'json_schema')
    result.text = { format: { type: 'json_schema', ...row(format.json_schema) } };
  return result;
}

/** Bounded, incremental UTF-8/SSE reader; never rewrites the raw Responses transport. */
export class ResponsesEventReader {
  private readonly decoder = new StringDecoder('utf8');
  private pending = '';
  constructor(private readonly onEvent: (event: Row) => void) {}
  push(chunk: Buffer) {
    this.pending += this.decoder.write(chunk);
    this.drain();
  }
  finish() {
    this.pending += this.decoder.end();
    this.drain();
    if (this.pending.trim())
      throw new ResponsesProxyError(502, 'truncated_upstream_event', '上游事件未完整结束');
  }
  private drain() {
    for (;;) {
      const boundary = /\r?\n\r?\n/.exec(this.pending);
      if (!boundary) break;
      const block = this.pending.slice(0, boundary.index);
      this.pending = this.pending.slice(boundary.index + boundary[0].length);
      if (Buffer.byteLength(block) > 16 * 1024 * 1024)
        throw new ResponsesProxyError(502, 'upstream_event_too_large', '上游事件过大');
      const data = block
        .split(/\r?\n/)
        .filter((line) => line.startsWith('data:'))
        .map((line) => line.slice(5).trimStart())
        .join('\n');
      if (!data || data === '[DONE]') continue;
      let event;
      try {
        event = JSON.parse(data);
      } catch {
        throw new ResponsesProxyError(502, 'invalid_upstream_event', '上游事件格式不兼容');
      }
      if (typeof row(event).type !== 'string')
        throw new ResponsesProxyError(502, 'invalid_upstream_event', '上游事件缺少类型');
      this.onEvent(row(event));
    }
    if (Buffer.byteLength(this.pending) > 16 * 1024 * 1024)
      throw new ResponsesProxyError(502, 'upstream_event_too_large', '上游事件过大');
  }
}

export function responseUsageToChat(value: unknown) {
  const usage = row(value);
  return {
    prompt_tokens: usage.input_tokens ?? 0,
    completion_tokens: usage.output_tokens ?? 0,
    total_tokens: usage.total_tokens ?? 0,
    prompt_tokens_details: usage.input_tokens_details ?? {},
    completion_tokens_details: usage.output_tokens_details ?? {},
  };
}

export function responseToChatCompletion(value: Row) {
  const output = Array.isArray(value.output) ? value.output : [];
  const calls = output
    .filter((item) => row(item).type === 'function_call')
    .map((item) => {
      const call = row(item);
      return {
        id: call.call_id,
        type: 'function',
        function: { name: call.name, arguments: call.arguments },
      };
    });
  const content = output
    .flatMap((item) => (Array.isArray(row(item).content) ? (row(item).content as unknown[]) : []))
    .filter((part) => row(part).type === 'output_text')
    .map((part) => row(part).text)
    .join('');
  return {
    id: value.id,
    object: 'chat.completion',
    created: value.created_at,
    model: value.model,
    choices: [
      {
        index: 0,
        message: {
          role: 'assistant',
          content: content || null,
          ...(calls.length ? { tool_calls: calls } : {}),
        },
        finish_reason: calls.length ? 'tool_calls' : 'stop',
      },
    ],
    usage: responseUsageToChat(value.usage),
  };
}

export class ResponseChatStream {
  private id = '';
  private model = '';
  private created = 0;
  private started = false;
  private readonly tools = new Map<number, number>();
  constructor(private readonly includeUsage: boolean) {}
  event(event: Row): string {
    let output = '';
    const chunk = (delta: Row, finish: string | null = null, usage?: unknown) =>
      `data: ${JSON.stringify({ id: this.id, object: 'chat.completion.chunk', created: this.created, model: this.model, choices: usage ? [] : [{ index: 0, delta, finish_reason: finish }], ...(usage ? { usage } : {}) })}\n\n`;
    const response = row(event.response);
    if (response.id) {
      this.id = String(response.id);
      this.model = String(response.model ?? this.model);
      this.created = Number(response.created_at ?? this.created);
    }
    if (!this.started && this.id) {
      this.started = true;
      output += chunk({ role: 'assistant', content: '' });
    }
    if (event.type === 'response.output_text.delta') output += chunk({ content: event.delta });
    if (event.type === 'response.reasoning_summary_text.delta')
      output += chunk({ reasoning_content: event.delta });
    if (event.type === 'response.output_item.added' && row(event.item).type === 'function_call') {
      const item = row(event.item),
        index = this.tools.size;
      this.tools.set(Number(event.output_index), index);
      output += chunk({
        tool_calls: [
          {
            index,
            id: item.call_id,
            type: 'function',
            function: { name: item.name, arguments: '' },
          },
        ],
      });
    }
    if (event.type === 'response.function_call_arguments.delta') {
      const index = this.tools.get(Number(event.output_index));
      if (index === undefined)
        throw new ResponsesProxyError(502, 'invalid_tool_stream', '上游工具事件顺序不兼容');
      output += chunk({ tool_calls: [{ index, function: { arguments: event.delta } }] });
    }
    if (event.type === 'response.completed' && response.status === 'completed') {
      output += chunk({}, this.tools.size ? 'tool_calls' : 'stop');
      if (this.includeUsage) output += chunk({}, null, responseUsageToChat(response.usage));
      output += 'data: [DONE]\n\n';
    }
    if (
      event.type === 'response.failed' ||
      event.type === 'response.incomplete' ||
      event.type === 'error'
    )
      throw new ResponsesProxyError(
        502,
        'upstream_inference_failed',
        '上游推理未完成，请核对原操作；代理未重试',
      );
    return output;
  }
}

function proxyEnvironment(): NodeJS.ProcessEnv {
  const env = { ...process.env };
  if (process.platform !== 'darwin' || env.HTTPS_PROXY || env.https_proxy) return env;
  try {
    const config = execFileSync('/usr/sbin/scutil', ['--proxy'], {
      encoding: 'utf8',
      timeout: 2000,
      stdio: ['ignore', 'pipe', 'ignore'],
    });
    const host = config.match(/HTTPSProxy\s*:\s*([^\s]+)/)?.[1];
    const port = Number(config.match(/HTTPSPort\s*:\s*(\d+)/)?.[1]);
    if (
      /HTTPSEnable\s*:\s*1\b/.test(config) &&
      host &&
      /^[a-zA-Z0-9.:-]+$/.test(host) &&
      port > 0 &&
      port <= 65535
    ) {
      const url = new URL('http://localhost');
      url.hostname = host.includes(':') ? `[${host}]` : host;
      url.port = String(port);
      env.HTTPS_PROXY = url.origin;
    }
  } catch {
    /* Fall back to ordinary HTTPS, never change the system proxy. */
  }
  return env;
}

export interface DesktopResponsesTransport {
  send(
    body: Buffer,
    signal: AbortSignal,
    beforeSubmit?: () => Promise<void>,
  ): Promise<IncomingMessage>;
  close(): void;
}

/** Version-specific backend wire and credentials stay inside the Codex adapter. */
export class DesktopResponses implements DesktopResponsesTransport {
  private readonly agents = new Set<Agent>();
  private closed = false;
  constructor(
    codexHome = process.env.CODEX_HOME ?? join(homedir(), '.codex'),
    executable = 'codex',
    private readonly metadata = () => readDesktopBackendAuth(executable, codexHome),
    private readonly httpRequest: typeof request = request,
  ) {}
  private async authorization() {
    let metadata;
    try {
      metadata = await this.metadata();
    } catch {
      throw new ResponsesProxyError(503, 'desktop_auth_unavailable', '暂时无法读取原 Codex 授权');
    }
    const token = metadata.auth.authToken,
      routing = row(metadata.account.workspaceRouting);
    if (
      metadata.auth.authMethod !== 'chatgpt' ||
      row(metadata.account.account).type !== 'chatgpt' ||
      typeof token !== 'string' ||
      !token ||
      token.length > 32000
    )
      throw new ResponsesProxyError(
        401,
        'desktop_login_required',
        '请在原 Codex 桌面恢复 ChatGPT 登录',
      );
    const base = row(metadata.config.config).chatgpt_base_url;
    if (
      routing.backendOrigin !== 'https://chatgpt.com' ||
      routing.accountRoutingOverride !== 'NO_CONSTRAINT' ||
      metadata.requirements.requirements !== null ||
      (base &&
        (typeof base !== 'string' || base.replace(/\/$/, '') !== 'https://chatgpt.com/backend-api'))
    )
      throw new ResponsesProxyError(
        503,
        'unsupported_desktop_route',
        '当前专用账户路由或企业策略尚不支持代理',
      );
    let claims: Row;
    try {
      claims = row(
        JSON.parse(Buffer.from(token.split('.')[1] ?? '', 'base64url').toString('utf8')),
      );
    } catch {
      throw new ResponsesProxyError(401, 'invalid_desktop_token', '原 Codex 授权暂不可用');
    }
    const account = row(claims['https://api.openai.com/auth']);
    if (
      typeof claims.exp !== 'number' ||
      claims.exp * 1000 <= Date.now() + 30000 ||
      typeof claims.sub !== 'string' ||
      !claims.sub ||
      typeof account.chatgpt_account_id !== 'string' ||
      !account.chatgpt_account_id ||
      account.chatgpt_account_id !== routing.chatgptAccountId
    )
      throw new ResponsesProxyError(
        401,
        'desktop_login_required',
        '请在原 Codex 桌面刷新登录后再调用',
      );
    if (
      account.chatgpt_account_is_fedramp !== undefined &&
      account.chatgpt_account_is_fedramp !== false
    )
      throw new ResponsesProxyError(
        503,
        'unsupported_desktop_route',
        '当前专用账户路由尚不支持代理',
      );
    return { token, accountId: account.chatgpt_account_id };
  }
  async send(body: Buffer, signal: AbortSignal, beforeSubmit?: () => Promise<void>) {
    if (this.closed || signal.aborted)
      throw new ResponsesProxyError(503, 'proxy_closed', '代理已关闭，请查询原操作');
    const auth = await this.authorization();
    if (this.closed || signal.aborted)
      throw new ResponsesProxyError(503, 'proxy_closed', '代理已关闭，请查询原操作');
    await beforeSubmit?.();
    if (this.closed || signal.aborted)
      throw new ResponsesProxyError(503, 'proxy_closed', '代理已关闭，请查询原操作');
    const agent = new Agent({ proxyEnv: proxyEnvironment() });
    this.agents.add(agent);
    const cleanup = () => {
      this.agents.delete(agent);
      agent.destroy();
    };
    try {
      return await new Promise<IncomingMessage>((resolve, reject) => {
        const options: RequestOptions = {
          method: 'POST',
          agent,
          signal,
          headers: {
            Authorization: `Bearer ${auth.token}`,
            'ChatGPT-Account-Id': auth.accountId,
            originator: 'agent_hub_host',
            'User-Agent': 'AgentHub Host Responses Proxy',
            'Content-Type': 'application/json',
            'Content-Length': body.length,
            Accept: 'text/event-stream',
            'Accept-Encoding': 'identity',
          },
        };
        const req = this.httpRequest(
          'https://chatgpt.com/backend-api/codex/responses',
          options,
          (res) => {
            res.once('close', cleanup);
            resolve(res);
          },
        );
        req.once('error', reject);
        req.end(body);
      });
    } catch {
      cleanup();
      throw new ResponsesProxyError(
        502,
        'upstream_connection_failed',
        '上游连接中断，结果可能未知；代理未重试',
      );
    }
  }
  close() {
    this.closed = true;
    for (const agent of this.agents) agent.destroy();
    this.agents.clear();
  }
}
