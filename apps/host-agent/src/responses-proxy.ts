import { createServer as httpServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { createServer as httpsServer } from 'node:https';
import { createHash, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import { readFile, mkdir, open, rename, readdir, lstat, realpath, rm } from 'node:fs/promises';
import { isIP } from 'node:net';
import { homedir } from 'node:os';
import { join, resolve, isAbsolute } from 'node:path';
import { once } from 'node:events';
import {
  DesktopResponses,
  DesktopImages,
  desktopImageModels,
  prepareDesktopImage,
  validateDesktopImageResponse,
  ResponsesProxyError,
  ResponsesEventReader,
  ResponseChatStream,
  prepareDesktopResponse,
  chatCompletionToResponse,
  responseToChatCompletion,
  assertPrivateFile,
  protectWindowsStateDirectory,
  protectWindowsStateFile,
  type DesktopResponsesTransport,
  type DesktopImagesTransport,
} from '@codex-manage/codex-adapter';
import { parseImageRequest } from './image-proxy.js';

type Row = Record<string, unknown>;
const row = (value: unknown): Row =>
  value && typeof value === 'object' && !Array.isArray(value) ? (value as Row) : {};
const digest = (value: Buffer | string) => createHash('sha256').update(value).digest('hex');
const error = (status: number, code: string, message: string) =>
  new ResponsesProxyError(status, code, message);
const json = (res: ServerResponse, status: number, value: unknown) => {
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
  });
  res.end(JSON.stringify(value));
};

export interface HostResponsesProxyConfig {
  bind: string;
  port: number;
  directory: string;
  tokenFile: string;
  models: string[];
  tls?: { cert: string; key: string };
  maxBytes: number;
  maxConcurrency: number;
  timeoutMs: number;
  images?: { models: string[]; maxBytes: number; maxConcurrency: number; timeoutMs: number };
}

/** Disabled means no files, credentials, subprocesses, certificate reads or sockets. */
export function readHostResponsesProxyConfig(
  env: NodeJS.ProcessEnv = process.env,
): HostResponsesProxyConfig | undefined {
  if (env.HOST_RESPONSES_PROXY_ENABLED === undefined || env.HOST_RESPONSES_PROXY_ENABLED === '0')
    return undefined;
  if (env.HOST_RESPONSES_PROXY_ENABLED !== '1')
    throw error(500, 'invalid_proxy_configuration', 'HOST_RESPONSES_PROXY_ENABLED 必须为 0 或 1');
  const integer = (key: string, fallback: number, minimum: number, maximum: number) => {
    const raw = env[key];
    if (raw !== undefined && !/^\d+$/.test(raw))
      throw error(500, 'invalid_proxy_configuration', '代理数值配置无效');
    const value = raw === undefined ? fallback : Number(raw);
    if (!Number.isSafeInteger(value) || value < minimum || value > maximum)
      throw error(500, 'invalid_proxy_configuration', '代理数值超出范围');
    return value;
  };
  const bind = env.HOST_RESPONSES_PROXY_BIND ?? '127.0.0.1';
  if (!isIP(bind)) throw error(500, 'invalid_proxy_configuration', '代理必须绑定准确 IP 地址');
  let models: unknown;
  try {
    models = JSON.parse(env.HOST_RESPONSES_PROXY_MODELS ?? '[]');
  } catch {
    throw error(500, 'invalid_proxy_configuration', '模型清单必须为 JSON 数组');
  }
  if (
    !Array.isArray(models) ||
    models.length > 64 ||
    models.some((id) => typeof id !== 'string' || !/^[A-Za-z0-9_.:-]{1,128}$/.test(id)) ||
    new Set(models).size !== models.length
  )
    throw error(500, 'invalid_proxy_configuration', '显式启用前必须配置不重复的模型允许清单');
  let imageModels: unknown;
  try {
    imageModels = JSON.parse(env.HOST_RESPONSES_PROXY_IMAGE_MODELS ?? '[]');
  } catch {
    throw error(500, 'invalid_proxy_configuration', '图像模型清单必须为 JSON 数组');
  }
  if (
    !Array.isArray(imageModels) ||
    imageModels.some((id) => !desktopImageModels.includes(id)) ||
    new Set(imageModels).size !== imageModels.length ||
    (!models.length && !imageModels.length)
  )
    throw error(
      500,
      'invalid_proxy_configuration',
      '需要至少一类模型允许清单，图像模型只能使用已验证的名称',
    );
  const cert = env.HOST_RESPONSES_PROXY_TLS_CERT,
    key = env.HOST_RESPONSES_PROXY_TLS_KEY;
  if (!!cert !== !!key || (cert && (!isAbsolute(cert) || !isAbsolute(key!))))
    throw error(500, 'invalid_proxy_configuration', '需要两个绝对路径的代理 TLS 文件');
  if (!cert && bind !== '127.0.0.1' && bind !== '::1')
    throw error(500, 'proxy_tls_required', '局域网或 VPN 监听必须配置 TLS');
  const directory = resolve(
    env.HOST_RESPONSES_PROXY_STATE_DIR ??
      join(env.HOST_AGENT_STATE_DIR ?? '.local/host-agent', 'responses-proxy'),
  );
  return {
    bind,
    port: integer('HOST_RESPONSES_PROXY_PORT', 8797, 1, 65535),
    directory,
    tokenFile: resolve(env.HOST_RESPONSES_PROXY_TOKEN_FILE ?? join(directory, 'access-token')),
    models: models as string[],
    ...(cert ? { tls: { cert, key: key! } } : {}),
    maxBytes: integer('HOST_RESPONSES_PROXY_MAX_BYTES', 8 * 1024 * 1024, 1024, 32 * 1024 * 1024),
    maxConcurrency: integer('HOST_RESPONSES_PROXY_CONCURRENCY', 4, 1, 16),
    timeoutMs: integer('HOST_RESPONSES_PROXY_TIMEOUT_MS', 180000, 1000, 900000),
    ...(imageModels.length
      ? {
          images: {
            models: imageModels as string[],
            maxBytes: integer(
              'HOST_RESPONSES_PROXY_IMAGE_MAX_BYTES',
              32 * 1024 * 1024,
              1024,
              64 * 1024 * 1024,
            ),
            maxConcurrency: integer('HOST_RESPONSES_PROXY_IMAGE_CONCURRENCY', 1, 1, 4),
            timeoutMs: integer('HOST_RESPONSES_PROXY_IMAGE_TIMEOUT_MS', 600000, 1000, 900000),
          },
        }
      : {}),
  };
}

interface Receipt {
  id: string;
  endpoint: string;
  model: string;
  requestSha256: string;
  upstreamRequestSha256: string;
  acceptedAt: string;
  phase: 'prepared' | 'submitting' | 'streaming' | 'completed' | 'failed' | 'outcome-unknown';
  upstreamSubmissions: number;
  upstreamStatus?: number;
  upstreamBytes: number;
  responseBytes: number;
  upstreamSha256?: string;
  responseSha256?: string;
  errorCode?: string;
  finishedAt?: string;
  imagePreferences?: string[];
  imageOutput?: { size: unknown; output_format: unknown; quality?: unknown };
}

class ProxyReceipts {
  private readonly directory: string;
  constructor(directory: string) {
    this.directory = join(directory, 'operations');
  }
  async initialize() {
    await mkdir(this.directory, { recursive: true, mode: 0o700 });
    const info = await lstat(this.directory);
    if (
      !info.isDirectory() ||
      (await realpath(this.directory)) !== resolve(this.directory) ||
      (process.platform !== 'win32' && (info.mode & 0o077) !== 0)
    )
      throw error(500, 'unsafe_proxy_state', '代理操作目录不安全');
    protectWindowsStateDirectory(this.directory);
    for (const name of await readdir(this.directory)) {
      if (!/^[a-f0-9]{64}\.json$/.test(name)) continue;
      const path = join(this.directory, name);
      assertPrivateFile(path);
      const value = JSON.parse(await readFile(path, 'utf8')) as Receipt;
      if (['prepared', 'submitting', 'streaming'].includes(value.phase)) {
        value.phase = 'outcome-unknown';
        await this.save(value);
      }
    }
  }
  private path(id: string) {
    return join(this.directory, digest(id) + '.json');
  }
  async read(id: string) {
    const path = this.path(id);
    try {
      assertPrivateFile(path);
      return JSON.parse(await readFile(path, 'utf8')) as Receipt;
    } catch (e) {
      if ((e as NodeJS.ErrnoException).code === 'ENOENT') return undefined;
      throw error(500, 'invalid_proxy_receipt', '代理回执无法读取');
    }
  }
  has(id: string) {
    try {
      assertPrivateFile(this.path(id));
      return true;
    } catch (e) {
      if ((e as NodeJS.ErrnoException).code === 'ENOENT') return false;
      throw error(500, 'invalid_proxy_receipt', '代理回执无法核对');
    }
  }
  async create(value: Receipt) {
    if ((await readdir(this.directory)).filter((name) => name.endsWith('.json')).length >= 4096)
      throw error(503, 'proxy_receipt_limit', '代理回执已达到保留上限，请由管理员处理');
    let file;
    try {
      file = await open(this.path(value.id), 'wx', 0o600);
    } catch (e) {
      if ((e as NodeJS.ErrnoException).code === 'EEXIST')
        throw error(409, 'request_already_accepted', '该操作已接受，请查询原回执；未重复提交');
      throw e;
    }
    try {
      await file.writeFile(JSON.stringify(value));
      await file.sync();
    } finally {
      await file.close();
    }
    protectWindowsStateFile(this.path(value.id));
    await this.sync();
  }
  async save(value: Receipt) {
    const temporary = this.path(value.id) + '.' + randomUUID();
    const file = await open(temporary, 'wx', 0o600);
    try {
      await file.writeFile(JSON.stringify(value));
      await file.sync();
    } finally {
      await file.close();
    }
    protectWindowsStateFile(temporary);
    await rename(temporary, this.path(value.id));
    await this.sync();
  }
  private async sync() {
    if (process.platform === 'win32') return;
    const directory = await open(this.directory, 'r');
    try {
      await directory.sync();
    } finally {
      await directory.close();
    }
  }
}

async function readBody(req: IncomingMessage, limit: number) {
  const parts: Buffer[] = [];
  let size = 0;
  if (Number(req.headers['content-length'] ?? 0) > limit)
    throw error(413, 'request_too_large', '代理请求过大');
  for await (const part of req.iterator({ destroyOnReturn: false })) {
    size += (part as Buffer).length;
    if (size > limit) throw error(413, 'request_too_large', '代理请求过大');
    parts.push(part as Buffer);
  }
  return Buffer.concat(parts);
}

export async function createHostResponsesProxy(
  config: HostResponsesProxyConfig,
  transport: DesktopResponsesTransport,
  imageTransport?: DesktopImagesTransport,
) {
  await mkdir(config.directory, { recursive: true, mode: 0o700 });
  const info = await lstat(config.directory);
  if (
    !info.isDirectory() ||
    (await realpath(config.directory)) !== config.directory ||
    (process.platform !== 'win32' && (info.mode & 0o077) !== 0)
  )
    throw error(500, 'unsafe_proxy_state', '代理私有目录不安全');
  protectWindowsStateDirectory(config.directory);
  try {
    const token = await open(config.tokenFile, 'wx', 0o600);
    try {
      await token.writeFile(randomBytes(32).toString('base64url'));
      await token.sync();
    } finally {
      await token.close();
    }
    protectWindowsStateFile(config.tokenFile);
  } catch (e) {
    if ((e as NodeJS.ErrnoException).code !== 'EEXIST') throw e;
  }
  assertPrivateFile(config.tokenFile);
  const token = (await readFile(config.tokenFile, 'utf8')).trim();
  if (!/^[A-Za-z0-9_-]{32,256}$/.test(token)) throw error(500, 'invalid_proxy_key', '代理密钥无效');
  const authority = Buffer.from('Bearer ' + token);
  const receipts = new ProxyReceipts(config.directory);
  const ownerPath = join(config.directory, 'listener-owner.json');
  const owner = { pid: process.pid, nonce: randomUUID(), bind: config.bind, port: config.port };
  let ownsState = false;
  let initializing: Promise<void> | undefined;
  // Reserve the actual socket before recovering receipts. A second process must
  // never mark the still-running listener's operations unknown before EADDRINUSE.
  const ready = () =>
    (initializing ??= (async () => {
      let previous: Row | undefined;
      try {
        assertPrivateFile(ownerPath);
        previous = row(JSON.parse(await readFile(ownerPath, 'utf8')));
      } catch (e) {
        if ((e as NodeJS.ErrnoException).code !== 'ENOENT')
          throw error(503, 'proxy_owner_unavailable', '代理写入者状态不可用');
      }
      if (previous) {
        if (
          !Number.isSafeInteger(previous.pid) ||
          Number(previous.pid) <= 0 ||
          previous.bind !== config.bind ||
          previous.port !== config.port
        )
          throw error(503, 'proxy_state_in_use', '该私有状态属于另一监听配置');
        let gone = false;
        try {
          process.kill(Number(previous.pid), 0);
        } catch (e) {
          gone = (e as NodeJS.ErrnoException).code === 'ESRCH';
        }
        if (!gone) throw error(503, 'proxy_state_in_use', '该代理状态仍有存活写入者');
        // Same socket is exclusively owned now, and the old PID is confirmed gone.
        const current = await readFile(ownerPath, 'utf8');
        if (row(JSON.parse(current)).nonce !== previous.nonce)
          throw error(503, 'proxy_owner_changed', '代理写入者已变化');
        await rm(ownerPath);
      }
      const file = await open(ownerPath, 'wx', 0o600);
      try {
        await file.writeFile(JSON.stringify(owner));
        await file.sync();
      } finally {
        await file.close();
      }
      protectWindowsStateFile(ownerPath);
      ownsState = true;
      await receipts.initialize();
    })());
  const controllers = new Set<AbortController>();
  const jobs = new Set<Promise<void>>();
  let slots = 0,
    closed = false;
  let imageSlots = 0;
  const handler = async (req: IncomingMessage, res: ServerResponse) => {
    await ready();
    const supplied = Buffer.from(req.headers.authorization ?? '');
    if (closed) return json(res, 503, { error: { code: 'proxy_closed', message: '代理已关闭' } });
    if (
      req.headers.origin ||
      supplied.length !== authority.length ||
      !timingSafeEqual(supplied, authority)
    )
      return json(res, 401, {
        error: { code: 'proxy_auth_required', message: '需要独立的代理密钥' },
      });
    if (req.method === 'GET' && req.url === '/healthz')
      return json(res, 200, {
        service: 'host-responses-proxy',
        enabled: true,
        activeRequests: slots,
        models: config.models,
        imageModels: config.images?.models ?? [],
      });
    if (req.method === 'GET' && req.url === '/v1/models')
      return json(res, 200, {
        object: 'list',
        data: [...new Set([...config.models, ...(config.images?.models ?? [])])].map((id) => ({
          id,
          object: 'model',
          owned_by: 'codex-desktop',
        })),
      });
    if (req.method === 'GET' && req.url?.startsWith('/v1/requests/')) {
      const id = req.url.slice('/v1/requests/'.length);
      if (!/^[A-Za-z0-9_-]{8,128}$/.test(id))
        return json(res, 400, { error: { code: 'invalid_request_id', message: '操作 ID 无效' } });
      const receipt = await receipts.read(id);
      return receipt
        ? json(res, 200, receipt)
        : json(res, 404, {
            error: { code: 'request_not_found', message: '未找到原操作；不代表可以重发未知输入' },
          });
    }
    const imageOperation =
      req.url === '/v1/images/generations'
        ? 'generations'
        : req.url === '/v1/images/edits'
          ? 'edits'
          : undefined;
    if (
      req.method !== 'POST' ||
      (!['/v1/responses', '/v1/chat/completions'].includes(req.url ?? '') &&
        !(imageOperation && config.images && imageTransport))
    )
      return json(res, 404, {
        error: {
          code: 'unsupported_proxy_route',
          message: '仅开放已配置的 Responses、Chat、Images 和具名只读查询',
        },
      });
    if (!imageOperation && !/^application\/json(?:\s*;|$)/i.test(req.headers['content-type'] ?? ''))
      return json(res, 415, { error: { code: 'json_required', message: '需要 application/json' } });
    const id = req.headers['idempotency-key'] ?? req.headers['x-client-request-id'] ?? randomUUID();
    if (
      typeof id !== 'string' ||
      !/^[A-Za-z0-9_-]{8,128}$/.test(id) ||
      (req.headers['idempotency-key'] &&
        req.headers['x-client-request-id'] &&
        req.headers['idempotency-key'] !== req.headers['x-client-request-id'])
    )
      return json(res, 400, {
        error: { code: 'invalid_request_id', message: '操作 ID 无效或不一致' },
      });
    if (receipts.has(id)) {
      req.resume();
      return json(res, 409, {
        error: {
          code: 'request_already_accepted',
          message: '原操作已存在，请查询原回执；代理未重放',
        },
      });
    }
    if (
      slots >= config.maxConcurrency ||
      (imageOperation && imageSlots >= config.images!.maxConcurrency)
    )
      return json(res, 429, { error: { code: 'proxy_busy', message: '代理并发已满' } });
    slots++;
    if (imageOperation) imageSlots++;
    const controller = new AbortController();
    controllers.add(controller);
    const timeout = setTimeout(
      () => controller.abort(),
      imageOperation ? config.images!.timeoutMs : config.timeoutMs,
    );
    const onClose = () => {
      if (!res.writableFinished) controller.abort();
    };
    res.once('close', onClose);
    res.setHeader('x-request-id', id);
    let receipt: Receipt | undefined;
    let imageHeartbeat: ReturnType<typeof setInterval> | undefined;
    const imageResponseHash = createHash('sha256');
    try {
      const body = await readBody(req, imageOperation ? config.images!.maxBytes : config.maxBytes);
      let source: unknown;
      if (imageOperation)
        source = await parseImageRequest(body, req.headers['content-type'] ?? '', imageOperation);
      else
        try {
          source = JSON.parse(body.toString('utf8'));
        } catch {
          throw error(400, 'invalid_json', '请求 JSON 无效');
        }
      const chat = req.url === '/v1/chat/completions';
      const imagePrepared = imageOperation
        ? prepareDesktopImage(source, imageOperation, config.images!.models)
        : undefined;
      const prepared =
        imagePrepared ??
        prepareDesktopResponse(chat ? chatCompletionToResponse(source) : source, config.models);
      const intent: Receipt = {
        id,
        endpoint: req.url!,
        model: prepared.model,
        requestSha256: digest(body),
        upstreamRequestSha256: digest(prepared.body),
        acceptedAt: new Date().toISOString(),
        phase: 'prepared',
        upstreamSubmissions: 0,
        upstreamBytes: 0,
        responseBytes: 0,
        ...(imagePrepared ? { imagePreferences: imagePrepared.preferenceFields } : {}),
      };
      await receipts.create(intent);
      receipt = intent;
      if (controller.signal.aborted)
        throw error(503, 'request_detached', '当前等待已结束，原操作未重放');
      const beforeSubmit = async () => {
        receipt!.phase = 'submitting';
        receipt!.upstreamSubmissions = 1;
        await receipts.save(receipt!);
        if (imagePrepared?.stream) {
          res.writeHead(200, {
            'content-type': 'text/event-stream; charset=utf-8',
            'cache-control': 'no-store',
            'x-accel-buffering': 'no',
            'x-agent-hub-image-parameters': 'prompt-preferences',
            'x-agent-hub-image-stream': 'final-only',
          });
          res.flushHeaders();
          // Comments are transport keepalives, not generated image progress.
          imageHeartbeat = setInterval(() => {
            if (!res.destroyed) {
              const bytes = Buffer.from(': keepalive\n\n');
              imageResponseHash.update(bytes);
              receipt!.responseBytes += bytes.length;
              res.write(bytes);
            }
          }, 15000);
        }
      };
      const upstream = imageOperation
        ? await imageTransport![imageOperation === 'generations' ? 'generate' : 'edit'](
            prepared.body,
            controller.signal,
            beforeSubmit,
          )
        : await transport.send(prepared.body, controller.signal, beforeSubmit);
      // Persistence may yield before the async iterator attaches. Retain the
      // stream's errored state without allowing an early error to crash Node.
      upstream.on('error', () => {});
      receipt.upstreamStatus = upstream.statusCode ?? 502;
      if (upstream.statusCode !== 200) {
        upstream.destroy();
        receipt.phase =
          receipt.upstreamStatus >= 400 && receipt.upstreamStatus < 500
            ? 'failed'
            : 'outcome-unknown';
        throw error(
          receipt.upstreamStatus >= 400 && receipt.upstreamStatus < 500
            ? receipt.upstreamStatus
            : 502,
          'upstream_rejected',
          '上游未接受请求，请核对原操作；代理未重试',
        );
      }
      receipt.phase = 'streaming';
      await receipts.save(receipt);
      if (imagePrepared && imageOperation) {
        const chunks: Buffer[] = [],
          hash = createHash('sha256');
        try {
          for await (const part of upstream) {
            const bytes = part as Buffer;
            receipt.upstreamBytes += bytes.length;
            if (receipt.upstreamBytes > 64 * 1024 * 1024)
              throw error(502, 'response_too_large', '上游图像响应过大');
            hash.update(bytes);
            chunks.push(bytes);
          }
          const raw = Buffer.concat(chunks);
          let value: unknown;
          try {
            value = JSON.parse(raw.toString('utf8'));
          } catch {
            throw error(502, 'invalid_image_response', '上游未返回完整图像 JSON');
          }
          const response = validateDesktopImageResponse(value);
          receipt.imageOutput = {
            size: response.size,
            output_format: response.output_format,
            quality: response.quality,
          };
          const final = imagePrepared.stream
            ? {
                type:
                  imageOperation === 'generations'
                    ? 'image_generation.completed'
                    : 'image_edit.completed',
                created_at: response.created,
                ...row((response.data as unknown[])[0]),
                size: response.size,
                output_format: response.output_format,
                quality: response.quality,
                background: response.background,
                usage: response.usage,
              }
            : response;
          const payload = Buffer.from(
            imagePrepared.stream
              ? `event: ${final.type}\ndata: ${JSON.stringify(final)}\n\n`
              : JSON.stringify(final),
          );
          if (imageHeartbeat) {
            clearInterval(imageHeartbeat);
            imageHeartbeat = undefined;
          }
          receipt.upstreamSha256 = hash.digest('hex');
          receipt.responseSha256 = imageResponseHash.copy().update(payload).digest('hex');
          receipt.responseBytes += payload.length;
          receipt.phase = 'completed';
          await receipts.save(receipt);
          if (!res.headersSent)
            res.writeHead(200, {
              'content-type': 'application/json; charset=utf-8',
              'cache-control': 'no-store',
              'x-content-type-options': 'nosniff',
              'x-agent-hub-image-parameters': 'prompt-preferences',
              'x-agent-hub-image-preference-fields': imagePrepared.preferenceFields.join(','),
            });
          res.end(payload);
        } finally {
          upstream.destroy();
        }
        return;
      }
      const upstreamHash = createHash('sha256'),
        responseHash = createHash('sha256');
      let completed: Row | undefined,
        terminalFailure = false,
        mapped = '';
      const mapper = new ResponseChatStream(row(row(source).stream_options).include_usage === true);
      const reader = new ResponsesEventReader((event) => {
        if (event.type === 'response.completed' && row(event.response).status === 'completed')
          completed = row(event.response);
        if (['response.failed', 'response.incomplete', 'error'].includes(String(event.type)))
          terminalFailure = true;
        // Some Chat clients close as soon as they see finish_reason or [DONE].
        // Publish that terminal only after the upstream stream has been validated.
        if (chat && prepared.stream && event.type !== 'response.completed')
          mapped += mapper.event(event);
      });
      const write = async (value: Buffer | string) => {
        if (!value.length) return;
        const bytes = typeof value === 'string' ? Buffer.from(value) : value;
        responseHash.update(bytes);
        receipt!.responseBytes += bytes.length;
        if (!res.write(bytes)) await once(res, 'drain', { signal: controller.signal });
      };
      if (prepared.stream) {
        res.writeHead(200, {
          'content-type': 'text/event-stream; charset=utf-8',
          'cache-control': 'no-store',
          'x-accel-buffering': 'no',
          'x-content-type-options': 'nosniff',
        });
        res.flushHeaders();
      }
      try {
        for await (const chunk of upstream) {
          const bytes = chunk as Buffer;
          receipt.upstreamBytes += bytes.length;
          if (receipt.upstreamBytes > 64 * 1024 * 1024)
            throw error(502, 'response_too_large', '上游响应过大');
          upstreamHash.update(bytes);
          reader.push(bytes);
          if (prepared.stream) {
            await write(chat ? mapped : bytes);
            mapped = '';
          }
        }
        reader.finish();
        if (!completed || terminalFailure)
          throw error(
            502,
            terminalFailure ? 'upstream_inference_failed' : 'upstream_result_unknown',
            '未收到成功终态，请核对原操作；代理未重试',
          );
        if (!prepared.stream) {
          const payload = Buffer.from(
            JSON.stringify(chat ? responseToChatCompletion(completed) : completed),
          );
          responseHash.update(payload);
          receipt.responseBytes = payload.length;
          res.writeHead(200, {
            'content-type': 'application/json; charset=utf-8',
            'cache-control': 'no-store',
          });
          res.end(payload);
        } else {
          if (chat) {
            await write(mapper.event({ type: 'response.completed', response: completed }));
            receipt.upstreamSha256 = upstreamHash.copy().digest('hex');
            receipt.responseSha256 = responseHash.copy().digest('hex');
            receipt.phase = 'completed';
            await receipts.save(receipt);
          }
          res.end();
        }
        receipt.phase = 'completed';
      } finally {
        upstream.destroy();
        receipt.upstreamSha256 = upstreamHash.digest('hex');
        receipt.responseSha256 = responseHash.digest('hex');
        if (terminalFailure) receipt.phase = 'failed';
      }
    } catch (e) {
      const failure =
        e instanceof ResponsesProxyError
          ? e
          : error(502, 'proxy_request_failed', '代理请求未完成，请核对原操作；代理未重试');
      if (receipt) {
        if (!['failed', 'completed', 'outcome-unknown'].includes(receipt.phase))
          receipt.phase = receipt.upstreamSubmissions ? 'outcome-unknown' : 'failed';
        receipt.errorCode = failure.code;
        // A client can query immediately after the error response.
        await receipts.save(receipt);
      }
      req.resume();
      if (!res.headersSent && !res.destroyed)
        json(res, failure.status, {
          error: { code: failure.code, message: failure.message },
          request_id: id,
        });
      else if (!res.destroyed && imageOperation) {
        if (imageHeartbeat) {
          clearInterval(imageHeartbeat);
          imageHeartbeat = undefined;
        }
        const bytes = Buffer.from(
          `event: error\ndata: ${JSON.stringify({ type: 'error', code: failure.code, message: failure.message })}\n\n`,
        );
        imageResponseHash.update(bytes);
        if (receipt) {
          receipt.responseBytes += bytes.length;
          receipt.responseSha256 = imageResponseHash.digest('hex');
        }
        res.end(bytes);
      } else if (!res.destroyed) res.destroy();
    } finally {
      clearTimeout(timeout);
      if (imageHeartbeat) clearInterval(imageHeartbeat);
      res.off('close', onClose);
      controllers.delete(controller);
      slots--;
      if (imageOperation) imageSlots--;
      if (receipt) {
        receipt.finishedAt = new Date().toISOString();
        await receipts.save(receipt);
      }
    }
  };
  let tls;
  if (config.tls) {
    assertPrivateFile(config.tls.key);
    tls = { cert: await readFile(config.tls.cert), key: await readFile(config.tls.key) };
  }
  const listener = (req: IncomingMessage, res: ServerResponse) => {
    const job = handler(req, res).catch(() => {
      if (!res.headersSent && !res.destroyed)
        json(res, 500, { error: { code: 'proxy_state_failed', message: '代理状态不可用' } });
      else res.destroy();
    });
    jobs.add(job);
    void job.finally(() => jobs.delete(job));
  };
  const server = tls ? httpsServer(tls, listener) : httpServer(listener);
  server.once('listening', () => {
    void ready().catch(() => {
      server.close();
      server.closeAllConnections();
    });
  });
  server.requestTimeout = 30000;
  server.headersTimeout = 10000;
  server.maxConnections = 32;
  return {
    server,
    ready,
    async close() {
      if (closed) return;
      closed = true;
      for (const controller of controllers) controller.abort();
      transport.close();
      imageTransport?.close();
      const stopped = new Promise<void>((done) => server.close(() => done()));
      server.closeAllConnections();
      await Promise.allSettled([...jobs, stopped]);
      if (ownsState) {
        assertPrivateFile(ownerPath);
        if (row(JSON.parse(await readFile(ownerPath, 'utf8'))).nonce === owner.nonce)
          await rm(ownerPath);
        ownsState = false;
      }
    },
  };
}

export async function startHostResponsesProxy(env: NodeJS.ProcessEnv = process.env) {
  const config = readHostResponsesProxyConfig(env);
  if (!config) return undefined;
  const transport = new DesktopResponses(
    env.CODEX_HOME ?? join(homedir(), '.codex'),
    env.CODEX_EXECUTABLE ?? 'codex',
  );
  const imageTransport = config.images
    ? new DesktopImages(
        env.CODEX_HOME ?? join(homedir(), '.codex'),
        env.CODEX_EXECUTABLE ?? 'codex',
      )
    : undefined;
  const proxy = await createHostResponsesProxy(config, transport, imageTransport);
  try {
    await new Promise<void>((done, reject) => {
      proxy.server.once('error', reject);
      proxy.server.listen(config.port, config.bind, done);
    });
    await proxy.ready();
  } catch (e) {
    await proxy.close();
    throw e;
  }
  const close = () => {
    process.off('SIGINT', close);
    process.off('SIGTERM', close);
    void proxy.close();
  };
  process.once('SIGINT', close);
  process.once('SIGTERM', close);
  console.log(
    JSON.stringify({
      service: 'host-responses-proxy',
      enabled: true,
      bind: config.bind,
      port: config.port,
      tls: !!config.tls,
      models: config.models,
      imageModels: config.images?.models ?? [],
    }),
  );
  return proxy;
}

/** An unavailable opt-in listener must not stop the existing desktop Host. */
export async function startOptionalHostResponsesProxy(
  env: NodeJS.ProcessEnv = process.env,
  report: (message: string) => void = console.error,
) {
  try {
    return await startHostResponsesProxy(env);
  } catch (e) {
    report(
      JSON.stringify({
        service: 'host-responses-proxy',
        status: 'unavailable',
        code: e instanceof Error && 'code' in e ? String(e.code) : 'configuration_error',
      }),
    );
    return undefined;
  }
}
