import { homedir } from 'node:os';
import { join } from 'node:path';
import { createHash, randomUUID } from 'node:crypto';
import { Agent, request } from 'node:https';
import { execFileSync } from 'node:child_process';
import { RuntimeError } from './runtime-session.js';
import { readDesktopBackendAuth } from './desktop-backend-auth.js';

// Version-specific desktop dictation boundary, observed in installed Codex Desktop.
// No URL, headers, model, filesystem path or credential is accepted from a remote caller.
const endpoint = 'https://chatgpt.com/backend-api/transcribe';
export class DesktopTranscription {
  private readonly agents = new Set<Agent>();
  private readingAuth: ReturnType<DesktopTranscription['authRead']> | undefined;
  private closed = false;
  private capabilityHint: { available: boolean; checkedAt: number } | undefined;
  private capabilityRefresh: Promise<void> | undefined;
  constructor(
    private readonly codexHome = process.env.CODEX_HOME ?? join(homedir(), '.codex'),
    private readonly executable: string | (() => Promise<string>) = 'codex',
  ) {}
  private async authRead() {
    const executable =
      typeof this.executable === 'function' ? await this.executable() : this.executable;
    return readDesktopBackendAuth(executable, this.codexHome).catch(() => {
      throw new RuntimeError(503, '电脑暂时无法读取 Codex 登录状态，请稍后重试；原录音不会提交');
    });
  }
  private async auth() {
    try {
      // Deduplicate concurrent reads only; never cache an old account between operations.
      const reading = this.readingAuth ?? this.authRead();
      this.readingAuth = reading;
      const metadata = await reading.finally(() => {
        if (this.readingAuth === reading) this.readingAuth = undefined;
      });
      const token = metadata.auth.authToken;
      const account = metadata.account.account as { type?: string } | null;
      const routing = metadata.account.workspaceRouting as {
        chatgptAccountId?: string;
        backendOrigin?: string;
        accountRoutingOverride?: string;
      } | null;
      const config = metadata.config.config as { chatgpt_base_url?: string | null } | null;
      if (
        metadata.auth.authMethod !== 'chatgpt' ||
        account?.type !== 'chatgpt' ||
        typeof token !== 'string' ||
        !token ||
        token.length > 32_000
      )
        throw new Error('ChatGPT login required');
      const base = config?.chatgpt_base_url;
      if (
        !routing ||
        routing.backendOrigin !== 'https://chatgpt.com' ||
        routing.accountRoutingOverride !== 'NO_CONSTRAINT' ||
        (base && base.replace(/\/$/, '') !== 'https://chatgpt.com/backend-api') ||
        metadata.requirements.requirements !== null
      )
        throw new RuntimeError(
          503,
          '此 Codex 账户的专用路由或企业策略尚未支持 Host 转写，请使用原桌面听写',
        );
      const claims = JSON.parse(
        Buffer.from(token.split('.')[1] ?? '', 'base64url').toString('utf8'),
      ) as {
        exp?: number;
        sub?: string;
        'https://api.openai.com/auth'?: {
          chatgpt_account_id?: string;
          chatgpt_account_is_fedramp?: boolean;
        };
      };
      if (typeof claims.exp !== 'number' || claims.exp * 1000 <= Date.now() + 30_000)
        throw new Error('Desktop refresh required');
      const accountId = claims['https://api.openai.com/auth']?.chatgpt_account_id;
      const fedramp = claims['https://api.openai.com/auth']?.chatgpt_account_is_fedramp;
      if (fedramp !== undefined && fedramp !== false)
        throw new RuntimeError(503, '此 Codex 专用账户路由尚未支持 Host 转写');
      if (
        typeof claims.sub !== 'string' ||
        !claims.sub ||
        typeof accountId !== 'string' ||
        !accountId ||
        routing.chatgptAccountId !== accountId
      )
        throw new Error('Account changed');
      return {
        token,
        accountId,
        principal: createHash('sha256')
          .update(JSON.stringify([accountId, claims.sub]))
          .digest('hex'),
      };
    } catch (error) {
      if (error instanceof RuntimeError) throw error;
      throw new RuntimeError(
        401,
        '请在原 Codex 桌面登录 ChatGPT 并刷新登录状态，再重试转写；当前 Host 未取得有效的私有桌面凭据',
      );
    }
  }
  close() {
    this.closed = true;
    this.capabilityHint = undefined;
    for (const agent of this.agents) agent.destroy();
    this.agents.clear();
  }
  /** Display metadata only. Audio operations always re-read their actual principal. */
  get capabilityAvailable(): boolean {
    if (this.closed) return false;
    const age = this.capabilityHint ? performance.now() - this.capabilityHint.checkedAt : Infinity;
    if (!this.capabilityRefresh && age >= 10_000) {
      const refresh = this.readiness()
        .then(
          ({ available }) => {
            if (!this.closed) this.capabilityHint = { available, checkedAt: performance.now() };
          },
          () => {
            if (!this.closed)
              this.capabilityHint = { available: false, checkedAt: performance.now() };
          },
        )
        .finally(() => {
          if (this.capabilityRefresh === refresh) this.capabilityRefresh = undefined;
        });
      this.capabilityRefresh = refresh;
    }
    return age <= 30_000 && this.capabilityHint?.available === true;
  }
  async readiness() {
    try {
      const auth = await this.auth();
      return { available: true, reason: null, principal: auth.principal };
    } catch (error) {
      return { available: false, reason: (error as Error).message, principal: null };
    }
  }
  async transcribe(audio: Buffer, principal: string): Promise<string> {
    if (this.closed) throw new RuntimeError(503, '电脑转写连接已关闭，原录音未提交');
    const auth = await this.auth();
    if (this.closed) throw new RuntimeError(503, '电脑转写连接已关闭，原录音未提交');
    if (auth.principal !== principal)
      throw new RuntimeError(409, 'Codex 登录账户已变化，原录音未提交');
    const boundary = 'codex-host-' + randomUUID();
    const body = Buffer.concat([
      Buffer.from(
        `--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="recording.wav"\r\nContent-Type: audio/wav\r\n\r\n`,
      ),
      audio,
      Buffer.from(`\r\n--${boundary}--\r\n`),
    ]);
    // HTTP transcription matches the desktop completed-recording path. No re-encoding or retry.
    // A long-running Host can outlive VPN/system-proxy changes. Resolve the
    // current route once for this immutable upload; never retry the request.
    const agent = new Agent({ proxyEnv: desktopProxyEnvironment() });
    this.agents.add(agent);
    let stage = '建立连接';
    let response: { status: number; body: Buffer };
    try {
      response = await new Promise<{ status: number; body: Buffer }>((resolve, reject) => {
        const req = request(
          endpoint,
          {
            method: 'POST',
            agent,
            signal: AbortSignal.timeout(90_000),
            headers: {
              Authorization: `Bearer ${auth.token}`,
              'ChatGPT-Account-Id': auth.accountId,
              originator: 'Codex Desktop',
              'User-Agent': 'Codex Desktop',
              'Content-Type': `multipart/form-data; boundary=${boundary}`,
              'Content-Length': body.length,
            },
          },
          (res) => {
            stage = '读取响应';
            const chunks: Buffer[] = [];
            let size = 0;
            res.on('data', (chunk: Buffer) => {
              size += chunk.length;
              if (size > 1_000_000) req.destroy(new RuntimeError(502, 'Codex 转写响应过大'));
              else chunks.push(chunk);
            });
            res.on('error', reject);
            res.on('end', () =>
              resolve({ status: res.statusCode ?? 0, body: Buffer.concat(chunks) }),
            );
          },
        );
        req.once('finish', () => {
          stage = '等待响应';
        });
        req.on('error', reject);
        req.end(body);
      });
    } catch (error) {
      if (error instanceof RuntimeError) throw error;
      // Only a bounded code and request stage may enter a receipt. Native
      // messages/causes can contain proxy credentials, URLs or request details.
      const native = error as { code?: unknown; name?: unknown };
      const code =
        typeof native.code === 'string' && /^[A-Z0-9_]{1,40}$/.test(native.code)
          ? native.code
          : 'CONNECTION_FAILED';
      const timeout =
        code === 'ABORT_ERR' || code === 'ETIMEDOUT' || native.name === 'TimeoutError';
      throw new RuntimeError(
        502,
        `电脑连接转写服务${timeout ? '超时' : '中断'}（${stage}，${code}）；原录音已保留，请查询原回执，未重复提交`,
      );
    } finally {
      this.agents.delete(agent);
      agent.destroy();
    }
    if (response.status !== 200) {
      // Never relay backend error bodies: they may contain auth or request details.
      if (response.status === 401 || response.status === 403)
        throw new RuntimeError(
          response.status,
          'Codex 转写登录或账户权限不可用，请在原桌面检查后重新录音',
        );
      if (response.status === 429)
        throw new RuntimeError(429, 'Codex 转写当前达到用量限制，请稍后重新录音');
      if (response.status >= 400 && response.status < 500)
        throw new RuntimeError(
          response.status,
          `Codex 未接受录音（${response.status}），原录音已保留`,
        );
      throw new RuntimeError(502, 'Codex 转写服务未返回确定结果，请查询原回执；未重复提交');
    }
    let result;
    try {
      result = JSON.parse(response.body.toString('utf8')) as { text?: unknown };
    } catch {
      throw new RuntimeError(502, 'Codex 返回不兼容的转写结果');
    }
    if (typeof result.text !== 'string' || result.text.length > 100_000)
      throw new RuntimeError(502, 'Codex 转写结果缺少文字');
    return result.text;
  }
}

/** Match desktop system HTTPS proxy on macOS when the Host service has no proxy env. */
function desktopProxyEnvironment(): NodeJS.ProcessEnv {
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
    /* System proxy unavailable: use the ordinary HTTPS connection. */
  }
  return env;
}
