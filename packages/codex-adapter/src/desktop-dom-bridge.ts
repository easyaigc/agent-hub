import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { assertPrivateFile } from './desktop-platform.js';

const composerSelector = '.ProseMirror[role="textbox"]';
const suggestionSelector = '[data-cumberbatch] > .cumberbatch-content';
const maximumBytes = 2_000_000;
// Keep below Chromium's 10,000-character nodeValue truncation boundary.
const maximumText = 8_000;
const timeoutMs = 3000;
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
const readMethods = new Set([
  'DOM.getDocument',
  'DOM.querySelectorAll',
  'DOM.describeNode',
  'DOM.getBoxModel',
]);
type RecordValue = Record<string, unknown>;
const record = (value: unknown): RecordValue =>
  value && typeof value === 'object' && !Array.isArray(value) ? (value as RecordValue) : {};

export class DesktopDomBridgeError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly status = 503,
  ) {
    super(message);
  }
}

export interface DesktopDomBridgeConfig {
  endpoint: string;
  targetId: string;
  origin: string;
}

function loopbackEndpoint(value: string): URL {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new DesktopDomBridgeError('invalid-config', 'DOM Bridge 地址无效');
  }
  if (
    url.protocol !== 'http:' ||
    !['127.0.0.1', '[::1]'].includes(url.hostname) ||
    !url.port ||
    url.username ||
    url.password ||
    url.search ||
    url.hash ||
    url.pathname !== '/'
  )
    throw new DesktopDomBridgeError('invalid-config', 'DOM Bridge 仅接受明确端口的本机回环地址');
  return url;
}
function documentOrigin(value: string): string {
  try {
    const url = new URL(value);
    return `${url.protocol}//${url.host}`;
  } catch {
    return '';
  }
}
export function readDesktopDomBridgeConfig(path: string): DesktopDomBridgeConfig {
  assertPrivateFile(path);
  const value = record(JSON.parse(readFileSync(path, 'utf8')));
  if (
    Object.keys(value).some((key) => !['endpoint', 'targetId', 'origin'].includes(key)) ||
    typeof value.endpoint !== 'string' ||
    typeof value.targetId !== 'string' ||
    !/^[A-Za-z0-9_-]{1,160}$/.test(value.targetId) ||
    typeof value.origin !== 'string' ||
    documentOrigin(value.origin) !== value.origin
  )
    throw new DesktopDomBridgeError('invalid-config', 'DOM Bridge 私有配置无效');
  loopbackEndpoint(value.endpoint);
  return { endpoint: value.endpoint, targetId: value.targetId, origin: value.origin };
}

class ReadOnlyCdp {
  private nextId = 0;
  private pending = new Map<
    number,
    { resolve: (value: RecordValue) => void; reject: (error: Error) => void; timer: NodeJS.Timeout }
  >();
  private constructor(private readonly socket: WebSocket) {
    socket.addEventListener('message', (event) => {
      if (typeof event.data !== 'string' || Buffer.byteLength(event.data) > maximumBytes) {
        this.close();
        return;
      }
      let message: RecordValue;
      try {
        message = record(JSON.parse(event.data));
      } catch {
        this.close();
        return;
      }
      if (typeof message.id !== 'number') return;
      const pending = this.pending.get(message.id);
      if (!pending) return;
      clearTimeout(pending.timer);
      this.pending.delete(message.id);
      if (message.error) {
        const error = record(message.error);
        pending.reject(
          new DesktopDomBridgeError(
            error.message === 'Could not compute box model.' ? 'not-rendered' : 'cdp-read-failed',
            'DOM 只读命令失败',
          ),
        );
      } else pending.resolve(record(message.result));
    });
    socket.addEventListener('close', () => this.fail());
    socket.addEventListener('error', () => this.fail());
  }
  static async connect(url: string): Promise<ReadOnlyCdp> {
    const socket = new WebSocket(url);
    const client = new ReadOnlyCdp(socket);
    try {
      await new Promise<void>((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('timeout')), timeoutMs);
        socket.addEventListener(
          'open',
          () => {
            clearTimeout(timer);
            resolve();
          },
          { once: true },
        );
        socket.addEventListener(
          'error',
          () => {
            clearTimeout(timer);
            reject(new Error('connect'));
          },
          { once: true },
        );
      });
      return client;
    } catch {
      client.close();
      throw new DesktopDomBridgeError('unreachable', '本机 DOM 调试连接不可用');
    }
  }
  call(method: string, params: RecordValue): Promise<RecordValue> {
    if (!readMethods.has(method)) throw new Error('Only fixed DOM reads are allowed');
    if (this.socket.readyState !== WebSocket.OPEN)
      return Promise.reject(new DesktopDomBridgeError('unreachable', 'DOM 调试连接已断开'));
    return new Promise((resolve, reject) => {
      const id = ++this.nextId;
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new DesktopDomBridgeError('timeout', 'DOM 读取超时'));
        this.close();
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      this.socket.send(JSON.stringify({ id, method, params }));
    });
  }
  private fail(): void {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer);
      pending.reject(new DesktopDomBridgeError('unreachable', 'DOM 调试连接已断开'));
    }
    this.pending.clear();
  }
  close(): void {
    this.fail();
    this.socket.close();
  }
}

interface DomNode {
  nodeId?: number;
  nodeType?: number;
  nodeName?: string;
  nodeValue?: string;
  documentURL?: string;
  attributes?: string[];
  children?: DomNode[];
}
function attributes(node: DomNode): Map<string, string> {
  const values = node.attributes ?? [];
  if (
    !Array.isArray(values) ||
    values.length % 2 ||
    values.some((value) => typeof value !== 'string')
  )
    throw new DesktopDomBridgeError('unsupported', 'DOM 属性格式无效');
  return new Map(
    Array.from({ length: Math.floor(values.length / 2) }, (_, i) => [
      values[i * 2]!,
      values[i * 2 + 1]!,
    ]),
  );
}
function textContent(node: DomNode, omitPrediction = false, depth = 0): string {
  if (depth > 40) throw new DesktopDomBridgeError('content-too-large', 'DOM 节点层级过深');
  if (omitPrediction && attributes(node).has('data-cumberbatch')) return '';
  if (node.nodeType === 3) return node.nodeValue ?? '';
  if (node.nodeName === 'BR') return '\n';
  let text = '';
  for (const child of node.children ?? []) {
    text += textContent(child, omitPrediction, depth + 1);
    if (text.length > maximumText)
      throw new DesktopDomBridgeError('content-too-large', '建议或草稿超出读取上限');
  }
  return text;
}
function routeThreadId(value: string): string | undefined {
  const url = new URL(value);
  const lastId = (path: string) => {
    const last = decodeURIComponent(path).replace(/\/$/, '').split('/').at(-1) ?? '';
    return uuid.test(last) ? last : undefined;
  };
  const path = lastId(url.pathname),
    hash = lastId(url.hash.replace(/^#/, '').split('?')[0] ?? '');
  return path && hash && path !== hash ? undefined : (hash ?? path);
}

export interface DesktopDomSuggestion {
  status: 'present' | 'empty' | 'draft-present';
  source: 'desktop-dom';
  representation: 'rendered-text';
  threadId: string;
  observedAt: string;
  text?: string;
  contentSha256?: string;
}

/** Fixed DOM commands only. Never starts, navigates, types in, or evaluates scripts in an app. */
export class DesktopDomBridge {
  private readonly endpoint: URL;
  private busy = false;
  constructor(private readonly config: DesktopDomBridgeConfig) {
    this.endpoint = loopbackEndpoint(config.endpoint);
    if (
      !/^[A-Za-z0-9_-]{1,160}$/.test(config.targetId) ||
      documentOrigin(config.origin) !== config.origin
    )
      throw new DesktopDomBridgeError('invalid-config', 'DOM Bridge 目标配置无效');
  }
  private async debuggerUrl(): Promise<string> {
    let response: Response;
    try {
      response = await fetch(new URL('/json/list', this.endpoint), {
        redirect: 'error',
        signal: AbortSignal.timeout(timeoutMs),
      });
    } catch {
      throw new DesktopDomBridgeError('unreachable', '本机调试端点不可用');
    }
    if (!response.ok || Number(response.headers.get('content-length') ?? '0') > maximumBytes)
      throw new DesktopDomBridgeError('unreachable', '本机调试目录读取失败');
    const chunks: Uint8Array[] = [];
    let size = 0;
    for await (const chunk of response.body ?? []) {
      size += chunk.length;
      if (size > maximumBytes)
        throw new DesktopDomBridgeError('content-too-large', '调试目录超出读取上限');
      chunks.push(chunk);
    }
    let values: unknown;
    try {
      values = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    } catch {
      throw new DesktopDomBridgeError('unsupported', '调试目录格式不支持');
    }
    const targets = Array.isArray(values)
      ? values
          .map(record)
          .filter((target) => target.id === this.config.targetId && target.type === 'page')
      : [];
    const target = targets[0];
    if (
      targets.length !== 1 ||
      !target ||
      typeof target.url !== 'string' ||
      documentOrigin(target.url) !== this.config.origin
    )
      throw new DesktopDomBridgeError('target-unavailable', '未找到配置的准确页面目标');
    try {
      const ws = new URL(String(target.webSocketDebuggerUrl));
      if (
        ws.protocol !== 'ws:' ||
        ws.hostname !== this.endpoint.hostname ||
        ws.port !== this.endpoint.port ||
        ws.username ||
        ws.password ||
        ws.search ||
        ws.hash ||
        ws.pathname !== `/devtools/page/${this.config.targetId}`
      )
        throw new Error();
      return ws.href;
    } catch {
      throw new DesktopDomBridgeError('unsupported', '调试连接与配置目标不一致');
    }
  }
  async readExisting(threadId: string): Promise<DesktopDomSuggestion> {
    if (this.busy) throw new DesktopDomBridgeError('busy', 'DOM Bridge 正在读取，请稍后重试');
    this.busy = true;
    try {
      return await this.readOne(threadId);
    } finally {
      this.busy = false;
    }
  }
  private async readOne(threadId: string): Promise<DesktopDomSuggestion> {
    if (!uuid.test(threadId))
      throw new DesktopDomBridgeError('invalid-target', '聊天 ID 无效', 400);
    const client = await ReadOnlyCdp.connect(await this.debuggerUrl());
    const deadline = setTimeout(() => client.close(), 10_000);
    const describe = async (nodeId: number) =>
      (await client.call('DOM.describeNode', { nodeId, depth: -1 })).node as DomNode;
    const select = async (nodeId: number, selector: string) => {
      const result = await client.call('DOM.querySelectorAll', { nodeId, selector });
      if (
        !Array.isArray(result.nodeIds) ||
        result.nodeIds.length > 16 ||
        result.nodeIds.some((id) => !Number.isSafeInteger(id) || id <= 0)
      )
        throw new DesktopDomBridgeError('ambiguous-target', 'DOM 候选节点无效或过多', 409);
      return result.nodeIds as number[];
    };
    const visible = async (nodeId: number) => {
      try {
        const model = record((await client.call('DOM.getBoxModel', { nodeId })).model);
        return (
          typeof model.width === 'number' &&
          model.width > 0 &&
          typeof model.height === 'number' &&
          model.height > 0
        );
      } catch (error) {
        if (error instanceof DesktopDomBridgeError && error.code === 'not-rendered') return false;
        throw error;
      }
    };
    try {
      const root = (await client.call('DOM.getDocument', { depth: 0 })).root as DomNode;
      if (
        !root?.nodeId ||
        !root.documentURL ||
        documentOrigin(root.documentURL) !== this.config.origin
      )
        throw new DesktopDomBridgeError('wrong-document', '当前文档与配置来源不一致', 409);
      const composers: number[] = [];
      for (const id of await select(root.nodeId, composerSelector))
        if (await visible(id)) composers.push(id);
      const composerId = composers[0];
      if (composers.length !== 1 || composerId === undefined)
        throw new DesktopDomBridgeError('ambiguous-composer', '无法确认唯一可见输入框', 409);
      const composer = await describe(composerId);
      const attributeThread = attributes(composer).get('data-conversation-id');
      const urlThread = routeThreadId(root.documentURL);
      const boundThread = attributeThread ?? urlThread;
      if (
        boundThread !== threadId ||
        (attributeThread && urlThread && attributeThread !== urlThread)
      )
        throw new DesktopDomBridgeError(
          'source-binding-unavailable',
          'DOM 未能证明属于请求的聊天',
          409,
        );
      const base = {
        source: 'desktop-dom' as const,
        representation: 'rendered-text' as const,
        threadId,
        observedAt: new Date().toISOString(),
      };
      if (textContent(composer, true).trim()) return { ...base, status: 'draft-present' };
      const suggestions: number[] = [];
      for (const id of await select(composerId, suggestionSelector))
        if (await visible(id)) suggestions.push(id);
      if (suggestions.length > 1)
        throw new DesktopDomBridgeError('ambiguous-suggestion', '存在多个可见建议节点', 409);
      const suggestionId = suggestions[0];
      const text = suggestionId === undefined ? '' : textContent(await describe(suggestionId));
      // getDocument resets frontend node IDs in Chromium; describe the existing root for revalidation.
      const finalRoot = (await client.call('DOM.describeNode', { nodeId: root.nodeId, depth: 0 }))
        .node as DomNode;
      const finalComposer = await describe(composerId);
      const finalSuggestions: number[] = [];
      for (const id of await select(composerId, suggestionSelector))
        if (await visible(id)) finalSuggestions.push(id);
      if (
        JSON.stringify(finalSuggestions) !== JSON.stringify(suggestions) ||
        root.nodeId !== finalRoot?.nodeId ||
        root.documentURL !== finalRoot.documentURL ||
        attributes(finalComposer).get('data-conversation-id') !== attributeThread ||
        textContent(finalComposer, true).trim() ||
        (suggestionId !== undefined && textContent(await describe(suggestionId)) !== text)
      )
        throw new DesktopDomBridgeError('document-changed', '读取期间输入框或页面已变化', 409);
      return text.trim()
        ? {
            ...base,
            status: 'present',
            text,
            contentSha256: createHash('sha256').update(text).digest('hex'),
          }
        : { ...base, status: 'empty' };
    } finally {
      clearTimeout(deadline);
      client.close();
    }
  }
}
