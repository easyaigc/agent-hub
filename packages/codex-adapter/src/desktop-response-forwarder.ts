import http, { type IncomingMessage } from 'node:http';
import https from 'node:https';
import type { Socket } from 'node:net';
import { randomUUID } from 'node:crypto';
import { Worker } from 'node:worker_threads';
import type {
  DesktopResponseForwarderEvent,
  DesktopResponseForwarderOptions,
  DesktopResponseForwarderStats,
  PredictionIdentity,
  ResponseTransport,
} from './desktop-response-forwarder-types.js';

const UPSTREAM = 'https://chatgpt.com/backend-api/codex';
const STREAM_LIMIT = 4 * 1024 * 1024;
// Reserve half of the global budget for data retained inside the worker.
const TOTAL_LIMIT = 8 * 1024 * 1024;
const CONNECTION_LIMIT = 64;

/** Starts a local byte-forwarding endpoint; observation never participates in forwarding. */
export async function startDesktopResponseForwarder(
  options: DesktopResponseForwarderOptions,
): Promise<{
  port: number;
  stats(): DesktopResponseForwarderStats;
  close(): Promise<void>;
}> {
  const { port, basePath, onEvent, fixture } = options;
  if (
    !Number.isInteger(port) ||
    port < 0 ||
    port > 65535 ||
    !/^\/[A-Za-z0-9_-]+(?:\/[A-Za-z0-9_-]+)*$/.test(basePath) ||
    basePath.endsWith('/backend-api/codex')
  )
    throw new Error('Invalid listener configuration');
  const target = new URL(fixture?.upstream ?? UPSTREAM);
  if (
    target.username ||
    target.password ||
    target.search ||
    target.hash ||
    (!fixture && target.href !== UPSTREAM) ||
    (fixture &&
      (!['http:', 'https:'].includes(target.protocol) ||
        !['127.0.0.1', 'localhost', '[::1]'].includes(target.hostname)))
  )
    throw new Error('Invalid upstream');
  if (!fixture && process.env.NODE_TLS_REJECT_UNAUTHORIZED === '0')
    throw new Error('TLS verification required');
  const transport = target.protocol === 'https:' ? https : http;
  const agent = new transport.Agent({
    keepAlive: true,
    maxSockets: 64,
    maxTotalSockets: 64,
    ...(target.protocol === 'https:'
      ? { rejectUnauthorized: true, ...(fixture?.ca ? { ca: fixture.ca } : {}) }
      : {}),
    ...(!fixture ? { proxyEnv: process.env } : {}),
  });
  const worker = new Worker(new URL('./desktop-response-observer.js', import.meta.url));
  const sockets = new Set<Socket>();
  const requests = new Set<http.ClientRequest>();
  const unavailable = new Set<string>();
  const active = new Set<string>();
  const observed = new Set<string>();
  const predictions = new Map<string, PredictionIdentity>();
  let pendingBytes = 0,
    bufferedBytes = 0,
    observerAlive = true,
    workerReady = false,
    closed = false;
  const emit = (value: DesktopResponseForwarderEvent) => {
    try {
      onEvent(value);
    } catch {
      /* Caller cannot interrupt forwarding. */
    }
  };
  function disable(id: string): void {
    if (!observed.has(id) || unavailable.has(id)) return;
    unavailable.add(id);
    const prediction = predictions.get(id);
    if (prediction) {
      predictions.delete(id);
      emit({
        kind: 'prediction-result',
        ...prediction,
        responseId: null,
        text: null,
        state: 'unverified',
        completedAt: Date.now(),
      });
    }
    emit({ kind: 'observation-unavailable', connectionId: id });
    if (observerAlive) {
      try {
        worker.postMessage({ kind: 'invalidate', id, bytes: 0 });
      } catch {
        /* worker is already unavailable */
      }
    }
  }
  function disableWorker(): void {
    if (!observerAlive) return;
    observerAlive = false;
    pendingBytes = 0;
    for (const id of active) disable(id);
    emit({ kind: 'observation-unavailable', connectionId: null });
  }
  worker.on('error', disableWorker);
  worker.on('exit', () => {
    if (!closed) disableWorker();
  });
  worker.on(
    'message',
    (message: {
      kind: string;
      bytes?: number;
      connectionId?: string;
      id?: string;
      operation?: string;
    }) => {
      if (message.kind === 'ready') {
        workerReady = true;
      } else if (message.kind === 'ack') {
        pendingBytes = Math.max(0, pendingBytes - (message.bytes ?? 0));
        if (message.operation === 'drop' && message.id) {
          unavailable.delete(message.id);
          emit({ kind: 'connection-closed', connectionId: message.id });
        }
      } else if (message.kind === 'observation-unavailable') {
        if (message.connectionId) unavailable.add(message.connectionId);
        emit(message as DesktopResponseForwarderEvent);
      } else if (!message.connectionId || !unavailable.has(message.connectionId)) {
        const event = message as DesktopResponseForwarderEvent;
        if (event.kind === 'prediction-start') predictions.set(event.connectionId, event);
        if (event.kind === 'prediction-result') predictions.delete(event.connectionId);
        emit(event);
      }
    },
  );
  function observe(message: {
    kind: string;
    id: string;
    transport?: ResponseTransport;
    direction?: 'request' | 'response';
    chunk?: Buffer;
    body?: Buffer;
    encoding?: string;
    compression?: unknown;
  }): void {
    if (unavailable.has(message.id) && message.kind !== 'drop') return;
    if (!observerAlive) {
      disable(message.id);
      return;
    }
    const bytes = message.chunk?.length ?? message.body?.length ?? 0;
    if (message.kind !== 'drop' && pendingBytes + bufferedBytes + bytes > TOTAL_LIMIT) {
      disable(message.id);
      return;
    }
    pendingBytes += bytes;
    try {
      worker.postMessage({ ...message, bytes });
    } catch {
      pendingBytes -= bytes;
      disableWorker();
    }
  }
  function bucket(id: string) {
    const chunks: Buffer[] = [];
    let size = 0,
      rejected = false;
    return {
      add(data: Buffer) {
        if (rejected || unavailable.has(id)) return;
        if (
          size + data.length > STREAM_LIMIT ||
          bufferedBytes + pendingBytes + data.length > TOTAL_LIMIT
        ) {
          rejected = true;
          bufferedBytes -= size;
          size = 0;
          chunks.length = 0;
          disable(id);
          return;
        }
        chunks.push(Buffer.from(data));
        size += data.length;
        bufferedBytes += data.length;
      },
      take(): Buffer | null {
        if (rejected) return null;
        const value = Buffer.concat(chunks);
        bufferedBytes -= size;
        size = 0;
        chunks.length = 0;
        return value;
      },
      clear() {
        bufferedBytes -= size;
        size = 0;
        chunks.length = 0;
        rejected = true;
      },
    };
  }
  function route(req: IncomingMessage): { path: string; observe: boolean; route: string } | null {
    if (
      !['127.0.0.1', '::ffff:127.0.0.1'].includes(req.socket.remoteAddress ?? '') ||
      !req.url?.startsWith('/') ||
      req.url.startsWith('//')
    )
      return null;
    const parsed = new URL(req.url, 'http://localhost');
    if (parsed.pathname !== basePath && !parsed.pathname.startsWith(`${basePath}/`)) return null;
    const suffix = parsed.pathname.slice(basePath.length);
    if (
      ![
        '/responses',
        '/responses/compact',
        '/models',
        '/images/generations',
        '/images/edits',
        '/memories/trace_summarize',
        '/alpha/search',
        '/realtime/calls',
        '/realtime',
        '/live',
        '',
      ].includes(suffix)
    )
      return null;
    return {
      path: target.pathname.replace(/\/$/, '') + suffix + parsed.search,
      observe: suffix === '/responses',
      route: suffix,
    };
  }
  function upstream(req: IncomingMessage, selected: { path: string }): http.RequestOptions {
    const headers: http.OutgoingHttpHeaders = { ...req.headers, host: target.host };
    delete headers['proxy-authorization'];
    delete headers['proxy-connection'];
    return {
      protocol: target.protocol,
      hostname: target.hostname,
      port: target.port || undefined,
      path: selected.path,
      method: req.method,
      headers,
      agent,
    };
  }
  function start(req: IncomingMessage, transportName: ResponseTransport, observe: boolean): string {
    const overLimit = observed.size >= CONNECTION_LIMIT;
    const id = randomUUID();
    active.add(id);
    if (!observe) return id;
    observed.add(id);
    emit({
      kind: 'connection',
      connectionId: id,
      clientPort: req.socket.remotePort ?? 0,
      listenerPort: req.socket.localPort ?? 0,
      transport: transportName,
    });
    if (overLimit) disable(id);
    return id;
  }
  function end(id: string): void {
    if (!active.delete(id)) return;
    if (!observed.delete(id)) return;
    if (observerAlive) observe({ kind: 'drop', id });
    else {
      unavailable.delete(id);
      emit({ kind: 'connection-closed', connectionId: id });
    }
  }
  const server = http.createServer((req, res) => {
    if (
      req.method === 'GET' &&
      (req.url === '/health' || req.url === `${basePath}/health`) &&
      ['127.0.0.1', '::ffff:127.0.0.1'].includes(req.socket.remoteAddress ?? '')
    ) {
      const address = server.address();
      const listenerPort = address && typeof address !== 'string' ? address.port : null;
      const segments = basePath.split('/');
      const candidate =
        segments[1] === '_agent_hub' ? (segments[2] ?? '') : (segments.at(-1) ?? '');
      const bindingId = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(candidate)
        ? candidate
        : null;
      res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' }).end(
        JSON.stringify({
          ready: server.listening && observerAlive && workerReady && !closed,
          bindingId,
          port: listenerPort,
          observerAlive: observerAlive && workerReady,
          pendingBytes,
          bufferedBytes,
          connections: active.size,
          requests: requests.size,
          ...(options.captureStatus ? { capture: options.captureStatus() } : {}),
        }),
      );
      return;
    }
    const selected = route(req);
    if (
      !selected ||
      !(
        (req.method === 'POST' && !['/models', '/realtime', ''].includes(selected.route)) ||
        (req.method === 'GET' && selected.route === '/models')
      )
    ) {
      res.writeHead(404).end();
      return;
    }
    const id = start(req, 'sse', selected.observe);
    const input = bucket(id),
      output = bucket(id);
    let finished = false;
    const request = transport.request(upstream(req, selected), (response) => {
      res.writeHead(response.statusCode ?? 502, response.headers);
      response.on('data', (chunk: Buffer) => {
        if (!res.write(chunk)) response.pause();
        if (selected.observe && response.statusCode === 200) output.add(chunk);
      });
      res.on('drain', () => response.resume());
      response.once('end', () => {
        finished = true;
        res.end();
        const body = output.take();
        if (selected.observe && response.statusCode === 200 && body)
          observe({
            kind: 'http-response',
            id,
            transport: 'sse',
            body,
            encoding: String(response.headers['content-encoding'] ?? ''),
          });
        else if (selected.observe) observe({ kind: 'http-failure', id });
        end(id);
      });
      response.once('aborted', () => {
        output.clear();
        disable(id);
        res.destroy();
        end(id);
      });
      response.once('error', () => {
        output.clear();
        disable(id);
        res.destroy();
        end(id);
      });
    });
    requests.add(request);
    request.once('close', () => requests.delete(request));
    request.once('error', () => {
      input.clear();
      output.clear();
      disable(id);
      if (!res.headersSent) res.writeHead(502);
      res.end();
      end(id);
    });
    req.on('data', (chunk: Buffer) => {
      if (!request.write(chunk)) req.pause();
      if (selected.observe) input.add(chunk);
    });
    request.on('drain', () => req.resume());
    req.once('end', () => {
      request.end();
      const body = input.take();
      if (selected.observe && body) observe({ kind: 'http-request', id, transport: 'sse', body });
    });
    req.once('aborted', () => {
      input.clear();
      output.clear();
      disable(id);
      request.destroy();
      end(id);
    });
    res.once('close', () => {
      if (!finished) {
        input.clear();
        output.clear();
        disable(id);
        request.destroy();
        end(id);
      }
    });
  });
  server.on('upgrade', (req, rawClient, head) => {
    const client = rawClient as Socket;
    const selected = route(req);
    if (
      !selected ||
      !['/responses', '/realtime', '/live', ''].includes(selected.route) ||
      req.method !== 'GET'
    ) {
      client.destroy();
      return;
    }
    const id = start(req, 'ws', selected.observe);
    let peer: Socket | undefined;
    const request = transport.request(upstream(req, selected));
    requests.add(request);
    request.once('close', () => requests.delete(request));
    request.once('error', () => {
      disable(id);
      client.destroy();
      end(id);
    });
    request.once('response', (response) => {
      const rejection = new http.ServerResponse(req);
      rejection.shouldKeepAlive = false;
      rejection.assignSocket(client);
      rejection.writeHead(response.statusCode ?? 502, response.headers);
      response.pipe(rejection);
      response.once('end', () => end(id));
      response.once('error', () => {
        client.destroy();
        end(id);
      });
    });
    request.once('upgrade', (response, rawPeer, upstreamHead) => {
      peer = rawPeer as Socket;
      if (client.destroyed) {
        peer.destroy();
        end(id);
        return;
      }
      sockets.add(peer);
      peer.once('close', () => sockets.delete(peer!));
      const extension = response.headers['sec-websocket-extensions'];
      let compression: unknown = null;
      if (selected.observe && extension) {
        const parts = extension.split(';').map((x) => x.trim());
        if (
          parts[0] !== 'permessage-deflate' ||
          !parts
            .slice(1)
            .every((x) =>
              /^(server_no_context_takeover|client_no_context_takeover|server_max_window_bits=(?:[89]|1[0-5])|client_max_window_bits=(?:[89]|1[0-5]))$/.test(
                x,
              ),
            )
        )
          disable(id);
        else {
          const option = (role: string) => ({
            noContextTakeover: parts.includes(`${role}_no_context_takeover`),
            windowBits: Number(
              parts.find((x) => x.startsWith(`${role}_max_window_bits=`))?.split('=')[1] ?? 15,
            ),
          });
          compression = { request: option('client'), response: option('server') };
        }
      }
      if (selected.observe) observe({ kind: 'ws-config', id, transport: 'ws', compression });
      client.write(
        `HTTP/1.1 ${response.statusCode} ${response.statusMessage}\r\n` +
          response.rawHeaders.reduce(
            (all, value, i, values) => (i % 2 ? all : all + value + ': ' + values[i + 1] + '\r\n'),
            '',
          ) +
          '\r\n',
      );
      if (head.length) {
        peer.write(head);
        if (selected.observe)
          observe({ kind: 'frames', id, transport: 'ws', direction: 'request', chunk: head });
      }
      if (upstreamHead.length) {
        client.write(upstreamHead);
        if (selected.observe)
          observe({
            kind: 'frames',
            id,
            transport: 'ws',
            direction: 'response',
            chunk: upstreamHead,
          });
      }
      client.on('data', (chunk: Buffer) => {
        if (!peer!.write(chunk)) client.pause();
        if (selected.observe)
          observe({ kind: 'frames', id, transport: 'ws', direction: 'request', chunk });
      });
      peer.on('drain', () => client.resume());
      peer.on('data', (chunk: Buffer) => {
        if (!client.write(chunk)) peer!.pause();
        if (selected.observe)
          observe({ kind: 'frames', id, transport: 'ws', direction: 'response', chunk });
      });
      client.on('drain', () => peer?.resume());
      peer.once('error', () => client.destroy());
      peer.once('close', () => {
        if (peer?.readableEnded) client.end();
        else client.destroy();
        end(id);
      });
      client.once('end', () => peer?.end());
      peer.once('end', () => client.end());
    });
    client.once('close', () => {
      request.destroy();
      peer?.destroy();
      end(id);
    });
    client.once('error', () => {
      request.destroy();
      peer?.destroy();
      end(id);
    });
    request.end();
  });
  server.on('connect', (_req, socket) => socket.destroy());
  server.on('connection', (socket) => {
    sockets.add(socket);
    socket.once('close', () => sockets.delete(socket));
    socket.on('error', () => {});
  });
  try {
    await new Promise<void>((resolve, reject) => {
      server.once('error', reject);
      server.listen(port, '127.0.0.1', () => {
        server.removeListener('error', reject);
        resolve();
      });
    });
  } catch (error) {
    closed = true;
    agent.destroy();
    await worker.terminate();
    throw error;
  }
  const address = server.address();
  if (!address || typeof address === 'string') throw new Error('Listener unavailable');
  return {
    port: address.port,
    stats: () => ({
      observerAlive: observerAlive && workerReady,
      pendingBytes,
      bufferedBytes,
      connections: active.size,
      requests: requests.size,
    }),
    async close() {
      if (closed) return;
      closed = true;
      for (const request of requests) request.destroy();
      for (const socket of sockets) socket.destroy();
      agent.destroy();
      await new Promise<void>((resolve) => server.close(() => resolve()));
      await worker.terminate();
    },
  };
}
