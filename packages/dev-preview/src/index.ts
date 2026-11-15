import {
  createServer,
  request as httpRequest,
  type IncomingMessage,
  type ServerResponse,
  type Server,
} from 'node:http';
import { type Duplex } from 'node:stream';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { createHash } from 'node:crypto';
import { connect } from 'node:net';
import { WebSocketServer, createWebSocketStream } from 'ws';
import {
  parseDevPreviewIntent,
  parseDevServiceURL,
  usageTargetKey,
  parseUsageTarget,
  type UsageTarget,
  type DevPreviewIntent,
  type DevPreviewReceipt,
} from '@codex-manage/protocol';
export class DevPreviewError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = 'DevPreviewError';
  }
}

const execute = promisify(execFile);
const cookieName = '__agent_hub_preview';
const blocked = new Set([2375, 2376, 3306, 5432, 6379, 8788, 9222, 9229, 27017]);
const allowed = new Set(['GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS']);
const hop = new Set([
  'connection',
  'upgrade',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailer',
  'transfer-encoding',
]);
function fail(status: number, message: string): never {
  throw new DevPreviewError(status, message);
}
export async function devListenerIdentity(port: number): Promise<string> {
  if (process.platform !== 'darwin') fail(501, '此平台尚未提供开发服务归属检查');
  const { stdout } = await execute(
    '/usr/sbin/lsof',
    ['-nP', `-iTCP:${port}`, '-sTCP:LISTEN', '-Fp'],
    { timeout: 2000, maxBuffer: 16384 },
  );
  const pids = [
    ...new Set(
      stdout
        .split('\n')
        .filter((x) => /^p\d+$/.test(x))
        .map((x) => x.slice(1)),
    ),
  ];
  if (pids.length !== 1) fail(409, '端口没有唯一监听进程');
  const { stdout: start } = await execute('/bin/ps', ['-p', pids[0]!, '-o', 'lstart=,uid='], {
    timeout: 2000,
    maxBuffer: 2048,
  });
  if (!start.trim() || +start.trim().split(/\s+/).at(-1)! !== process.getuid?.())
    fail(403, '开发服务不属于当前用户');
  return `${pids[0]}:${start.trim()}`;
}
type Live = {
  intent: DevPreviewIntent;
  receipt: DevPreviewReceipt;
  fingerprint: string;
  owner: string;
  identity: string;
  upstream: ReturnType<typeof parseDevServiceURL>;
  dialHost: string;
  streams: Set<Duplex>;
  pending: Set<ReturnType<typeof httpRequest>>;
  closed: boolean;
};
/** One fixed local Web service per grant. The WS carries HTTP bytes, parsed before forwarding. */
export class DevPreviews {
  private sessions = new Map<string, Live>();
  private creating = new Map<
    string,
    { fingerprint: string; owner: string; result: Promise<DevPreviewReceipt> }
  >();
  private disposed = false;
  private receipts = new Map<
    string,
    { fingerprint: string; receipt: DevPreviewReceipt; owner: string }
  >();
  private socketSession = new WeakMap<object, Live>();
  private ws = new WebSocketServer({ noServer: true, maxPayload: 65536, perMessageDeflate: false });
  private parser = createServer(
    { maxHeaderSize: 32768 },
    (req, res) => void this.forward(req, res),
  );
  private timer: ReturnType<typeof setInterval>;
  readonly counters = { http: 0, upgrades: 0, denied: 0 };
  constructor(
    private options: {
      target: () => UsageTarget;
      authorize: (request: IncomingMessage) => string;
      scope: (threadId: string) => Promise<void>;
      identity?: (port: number) => Promise<string>;
      deniedPorts?: () => number[];
    },
  ) {
    this.parser.on(
      'upgrade',
      (req, socket, head) => void this.forward(req, undefined, socket, head),
    );
    this.parser.on('connect', (_req, socket) => socket.destroy());
    this.parser.on('clientError', (_error, socket) => socket.destroy());
    this.parser.headersTimeout = 10000;
    this.parser.requestTimeout = 120000;
    this.timer = setInterval(() => {
      for (const live of this.sessions.values()) {
        if (
          Date.now() >= Date.parse(live.receipt.expiresAt) ||
          usageTargetKey(live.intent.target) !== usageTargetKey(this.options.target())
        )
          this.end(live);
        else {
          try {
            this.options.authorize(liveAuthRequest(live));
          } catch {
            this.end(live);
          }
        }
      }
    }, 1000);
    this.timer.unref();
  }
  async create(value: unknown, owner: string): Promise<DevPreviewReceipt> {
    let intent: DevPreviewIntent;
    try {
      intent = parseDevPreviewIntent(value);
    } catch {
      fail(400, '仅支持带明确端口的本地 HTTP DEV 地址');
    }
    if (usageTargetKey(intent.target) !== usageTargetKey(this.options.target()))
      fail(409, '运行目标已变化');
    const fingerprint = createHash('sha256')
      .update(
        JSON.stringify([
          usageTargetKey(intent.target),
          intent.threadId,
          intent.url,
          intent.browserPort,
          intent.browserSecret,
        ]),
      )
      .digest('hex');
    const prior = this.receipts.get(intent.id);
    if (prior) {
      if (prior.fingerprint !== fingerprint || prior.owner !== owner)
        fail(409, '预览操作标识已使用');
      if (!this.sessions.has(intent.id)) fail(410, '预览已结束，请重新打开');
      return prior.receipt;
    }
    const pending = this.creating.get(intent.id);
    if (pending) {
      if (pending.fingerprint !== fingerprint || pending.owner !== owner)
        fail(409, '预览操作标识已使用');
      return pending.result;
    }
    if (this.disposed || this.creating.size + this.sessions.size >= 8)
      fail(429, '预览不可用或会话数量已达上限');
    const result = this.createResolved(intent, owner, fingerprint);
    this.creating.set(intent.id, { fingerprint, owner, result });
    try {
      return await result;
    } finally {
      this.creating.delete(intent.id);
    }
  }
  private async createResolved(intent: DevPreviewIntent, owner: string, fingerprint: string) {
    if (this.sessions.size >= 8 || this.receipts.size >= 512) fail(429, '预览会话数量已达上限');
    const upstream = parseDevServiceURL(intent.url);
    if (blocked.has(+upstream.port) || this.options.deniedPorts?.().includes(+upstream.port))
      fail(403, '不可预览管理服务端口');
    await this.options.scope(intent.threadId);
    const identity = await this.identity(+upstream.port).catch(() =>
      fail(503, '服务未启动或无法核对归属'),
    );
    // Resolve localhost only between the two fixed loopback literals, before any
    // HTTP request. Never retry a submitted web action to another address family.
    let dialHost = ['[::1]', '[::]'].includes(upstream.hostname) ? '::1' : '127.0.0.1';
    if (upstream.hostname === 'localhost') {
      const reachable = (host: string) =>
        new Promise<boolean>((done) => {
          const socket = connect({ host, port: +upstream.port });
          const finish = (value: boolean) => {
            socket.destroy();
            done(value);
          };
          socket.setTimeout(500, () => finish(false));
          socket.once('connect', () => finish(true));
          socket.once('error', () => finish(false));
        });
      if (await reachable('::1')) dialHost = '::1';
      else if (!(await reachable('127.0.0.1'))) fail(503, '开发服务未启动');
      if ((await this.identity(+upstream.port)) !== identity) fail(409, '开发服务归属已变化');
    }
    if (this.disposed || usageTargetKey(intent.target) !== usageTargetKey(this.options.target()))
      fail(409, '运行目标已变化');
    this.options.authorize({ headers: { authorization: owner } } as IncomingMessage);
    const receipt = {
      id: intent.id,
      target: intent.target,
      threadId: intent.threadId,
      expiresAt: new Date(Date.now() + 30 * 60000).toISOString(),
    };
    const live: Live = {
      intent,
      receipt,
      fingerprint,
      owner,
      identity,
      upstream,
      dialHost,
      streams: new Set(),
      pending: new Set(),
      closed: false,
    };
    this.sessions.set(intent.id, live);
    this.receipts.set(intent.id, { fingerprint, receipt, owner });
    return receipt;
  }
  private identity(port: number) {
    return (this.options.identity ?? devListenerIdentity)(port);
  }
  async control(req: IncomingMessage, res: ServerResponse, url: URL): Promise<boolean> {
    if (url.pathname !== '/v1/dev-previews') return false;
    const owner = this.options.authorize(req);
    if (url.searchParams.size) fail(400, '无效预览参数');
    const chunks: Buffer[] = [];
    let size = 0;
    for await (const c of req) {
      size += c.length;
      if (size > 16384) fail(413, '预览请求过大');
      chunks.push(c);
    }
    let data: Record<string, unknown>;
    try {
      data = JSON.parse(Buffer.concat(chunks).toString());
    } catch {
      fail(400, '无效预览请求');
    }
    let result: unknown;
    if (req.method === 'POST') result = await this.create(data, owner);
    else if (req.method === 'DELETE') {
      if (Object.keys(data).sort().join(',') !== 'id,target,threadId')
        fail(400, '无效预览关闭请求');
      result = this.revoke(
        String(data.id),
        parseUsageTarget(data.target),
        String(data.threadId),
        owner,
      );
    } else fail(405, '不支持的预览操作');
    res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify(result));
    return true;
  }
  revoke(id: string, target: UsageTarget, threadId: string, owner: string) {
    const prior = this.receipts.get(id);
    if (
      !prior ||
      prior.owner !== owner ||
      usageTargetKey(target) !== usageTargetKey(prior.receipt.target) ||
      threadId !== prior.receipt.threadId
    )
      fail(404, '预览不存在');
    const live = this.sessions.get(id);
    if (live) this.end(live);
    return { id, closed: true };
  }
  attach(server: Server, admit: (request: IncomingMessage) => boolean = () => true) {
    server.on('upgrade', (request, socket, head) => this.upgrade(request, socket, head, admit));
  }
  upgrade(
    req: IncomingMessage,
    socket: Duplex,
    head: Buffer,
    admit: (request: IncomingMessage) => boolean = () => true,
  ) {
    void (async () => {
      const url = new URL(req.url ?? '/', 'http://host');
      if (!admit(req)) fail(403, '当前网络不允许连接此入口');
      if (url.pathname !== '/v1/dev-previews/connect' || req.headers.origin || head.length)
        fail(403, '无效预览连接');
      const owner = this.options.authorize(req);
      if ([...url.searchParams.keys()].sort().join(',') !== 'hostId,id,target')
        fail(400, '无效预览连接');
      const live = this.sessions.get(url.searchParams.get('id') ?? '');
      if (
        !live ||
        live.owner !== owner ||
        live.closed ||
        Date.now() >= Date.parse(live.receipt.expiresAt) ||
        url.searchParams.get('hostId') !== live.intent.target.hostId ||
        usageTargetKey(parseUsageTarget(JSON.parse(url.searchParams.get('target')!))) !==
          usageTargetKey(live.intent.target) ||
        usageTargetKey(this.options.target()) !== usageTargetKey(live.intent.target)
      )
        fail(410, '预览已失效');
      if (live.streams.size >= 24) fail(429, '预览连接过多');
      this.ws.handleUpgrade(req, socket, head, (ws) => {
        const stream = createWebSocketStream(ws, { highWaterMark: 65536 });
        live.streams.add(stream);
        this.socketSession.set(stream, live);
        stream.on('error', () => stream.destroy());
        ws.on('error', () => stream.destroy());
        stream.on('close', () => {
          live.streams.delete(stream);
          ws.terminate();
        });
        this.parser.emit('connection', stream);
      });
    })().catch(() => {
      this.counters.denied++;
      socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\nContent-Length: 0\r\n\r\n');
    });
  }
  private async forward(
    req: IncomingMessage,
    res?: ServerResponse,
    socket?: Duplex,
    head = Buffer.alloc(0),
  ) {
    const live = this.socketSession.get(req.socket);
    const deny = () => {
      this.counters.denied++;
      if (res && !res.headersSent) {
        res.writeHead(403, { Connection: 'close' });
        res.end('开发服务访问已失效或来源不匹配');
      } else socket?.destroy();
    };
    try {
      if (
        !live ||
        live.closed ||
        Date.now() >= Date.parse(live.receipt.expiresAt) ||
        !allowed.has(req.method ?? '') ||
        !req.url?.startsWith('/') ||
        req.url.startsWith('//') ||
        req.headers.host !== `127.0.0.1:${live.intent.browserPort}`
      )
        return deny();
      this.options.authorize(liveAuthRequest(live));
      if (usageTargetKey(live.intent.target) !== usageTargetKey(this.options.target()))
        return deny();
      const localOrigin = `http://127.0.0.1:${live.intent.browserPort}`;
      if (req.headers.origin && req.headers.origin !== localOrigin) return deny();
      const cookies = (req.headers.cookie ?? '').split(';').map((x) => x.trim());
      if (!cookies.includes(`${cookieName}=${live.intent.browserSecret}`)) return deny();
      if ((await this.identity(+live.upstream.port)) !== live.identity) {
        this.end(live);
        return deny();
      }
      if (live.closed || live.pending.size >= 48) return deny();
      const headers = { ...req.headers };
      for (const key of (req.headers.connection ?? '')
        .split(',')
        .map((x) => x.trim().toLowerCase()))
        delete headers[key];
      for (const key of hop) delete headers[key];
      delete headers['x-agent-hub-approvals'];
      headers.host = live.upstream.host;
      headers.cookie = cookies.filter((x) => !x.startsWith(cookieName + '=')).join('; ');
      if (!headers.cookie) delete headers.cookie;
      if (headers.origin) headers.origin = live.upstream.origin;
      if (headers.referer?.startsWith(localOrigin + '/'))
        headers.referer = live.upstream.origin + headers.referer.slice(localOrigin.length);
      if (socket) {
        if (req.headers.upgrade?.toLowerCase() !== 'websocket') return deny();
        headers.connection = 'Upgrade';
        headers.upgrade = 'websocket';
      }
      let bytes = 0;
      const upstream = httpRequest(
        {
          hostname: live.dialHost,
          port: +live.upstream.port,
          method: req.method,
          path: req.url,
          headers,
          timeout: 30000,
        },
        (remote) => {
          if (!res) {
            remote.destroy();
            socket?.destroy();
            return;
          }
          const out = { ...remote.headers };
          for (const key of hop) delete out[key];
          if (out.location?.startsWith(live.upstream.origin + '/'))
            out.location = localOrigin + out.location.slice(live.upstream.origin.length);
          if (out['set-cookie'])
            out['set-cookie'] = out['set-cookie']
              .filter((x) => !x.toLowerCase().startsWith(cookieName + '='))
              .map((x) => x.replace(/;\s*domain=[^;]+/gi, ''));
          res.writeHead(remote.statusCode ?? 502, out);
          remote.on('data', (c) => {
            bytes += c.length;
            if (bytes > 128 * 1024 * 1024) remote.destroy();
          });
          remote.on('error', () => res.destroy());
          remote.pipe(res);
        },
      );
      live.pending.add(upstream);
      upstream.on('close', () => live.pending.delete(upstream));
      upstream.on('timeout', () => upstream.destroy());
      upstream.on('error', () => {
        if (res && !res.headersSent) {
          res.writeHead(502);
          res.end('开发服务连接中断；网页操作结果可能尚未确认');
        } else {
          res?.destroy();
          socket?.destroy();
        }
      });
      req.on('aborted', () => upstream.destroy());
      res?.on('close', () => {
        if (!res.writableFinished) upstream.destroy();
      });
      req.on('data', (c) => {
        bytes += c.length;
        if (bytes > 32 * 1024 * 1024) {
          upstream.destroy();
          req.destroy();
        }
      });
      if (socket) {
        this.counters.upgrades++;
        upstream.on('upgrade', (remote, peer, first) => {
          const status = `HTTP/1.1 101 Switching Protocols\r\n${Object.entries(remote.headers)
            .map(([k, v]) => `${k}: ${Array.isArray(v) ? v.join(', ') : v}\r\n`)
            .join('')}\r\n`;
          socket.write(status);
          if (first.length) socket.write(first);
          if (head.length) peer.write(head);
          peer.on('error', () => socket.destroy());
          socket.on('error', () => peer.destroy());
          socket.on('close', () => peer.destroy());
          peer.on('close', () => socket.destroy());
          socket.pipe(peer).pipe(socket);
        });
        upstream.end();
      } else {
        this.counters.http++;
        req.pipe(upstream);
      }
    } catch {
      deny();
    }
  }
  private end(live: Live) {
    live.closed = true;
    for (const p of live.pending) p.destroy();
    for (const s of live.streams) s.destroy();
    this.sessions.delete(live.intent.id);
  }
  close() {
    this.disposed = true;
    clearInterval(this.timer);
    for (const live of this.sessions.values()) this.end(live);
    this.ws.close();
    this.parser.close();
  }
}
function liveAuthRequest(live: Live): IncomingMessage {
  return { headers: { authorization: live.owner } } as IncomingMessage;
}
