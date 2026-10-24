import { createServer as httpServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { createServer as httpsServer } from 'node:https';
import { randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import { mkdirSync, existsSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve, join, dirname } from 'node:path';
import { homedir } from 'node:os';
import {
  RuntimeSession,
  appendRuntimeDiagnostic,
  NotificationObserver,
  RuntimeError,
  DesktopWorkspaceView,
  DesktopChatActions,
  DesktopTranscription,
  CreationError,
  protectWindowsStateDirectory,
  protectWindowsStateFile,
  assertPrivateFile,
  DesktopDomBridgeError,
  DesktopSuggestionObserver,
  SuggestionObservationError,
  createWindowsTraceDiagnostics,
} from '@codex-manage/codex-adapter';
import { NotificationPublisher } from '@codex-manage/notification-runtime';
import { startDirectDiscovery } from './direct-discovery.js';
import { VPNPeerDirectory } from './direct-endpoints.js';
import { PrivateConnections } from './private-connections.js';
import { FileTransfers } from './file-transfers.js';
import { VoiceTranscriptions } from './voice-transcriptions.js';
import { DesktopDomSuggestions } from './desktop-dom-suggestions.js';
import { sendReadJSON } from './read-json.js';
import {
  parseNotificationRegistration,
  parseNotificationPreferences,
  parseNotificationTarget,
  machineRuntimeTargetKey,
  parseReadPosition,
  parseThreadForkRequest,
  parseChatActionRequest,
  parseWorkspaceManagementRequest,
  type VPNDiscoverySnapshot,
  type CodexConnectionRoutes,
  parseCodexSystemEndpoints,
  VOICE_MAX_BYTES,
} from '@codex-manage/protocol';

async function body(request: IncomingMessage, limit: number): Promise<Buffer> {
  const parts: Buffer[] = [];
  let length = 0;
  for await (const part of request) {
    length += (part as Buffer).length;
    if (length > limit) throw new RuntimeError(413, '请求过大');
    parts.push(part as Buffer);
  }
  return Buffer.concat(parts);
}
const text = (v: unknown): string => (typeof v === 'string' ? v : '');
const values = (v: unknown): string[] =>
  Array.isArray(v) && v.every((entry) => typeof entry === 'string') ? (v as string[]) : [];
function json(response: ServerResponse, status: number, value: unknown) {
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
  });
  response.end(JSON.stringify(value));
}

export async function startRuntimeServer() {
  const mode = process.env.CODEX_RUNTIME_MODE;
  if (!mode || !['managed-runtime', 'shared-runtime', 'desktop-runtime'].includes(mode))
    throw new Error(
      'Set CODEX_RUNTIME_MODE explicitly to managed-runtime, shared-runtime or desktop-runtime',
    );
  const directory = resolve(process.env.HOST_AGENT_STATE_DIR ?? '.local/host-agent');
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  protectWindowsStateDirectory(directory);
  const tokenFile = join(directory, 'access-token');
  if (!existsSync(tokenFile)) {
    writeFileSync(tokenFile, randomBytes(32).toString('base64url'), { mode: 0o600 });
    protectWindowsStateFile(tokenFile);
  }
  assertPrivateFile(tokenFile);
  const token = process.env.HOST_AGENT_TOKEN ?? readFileSync(tokenFile, 'utf8').trim();
  if (token.length < 32) throw new Error('Host token must have at least 32 characters');
  const bind = process.env.HOST_AGENT_BIND ?? '127.0.0.1';
  const tls =
    process.env.HOST_AGENT_TLS_CERT && process.env.HOST_AGENT_TLS_KEY
      ? {
          cert: readFileSync(process.env.HOST_AGENT_TLS_CERT),
          key: readFileSync(process.env.HOST_AGENT_TLS_KEY),
        }
      : undefined;
  if (!['127.0.0.1', '::1', 'localhost'].includes(bind) && !tls)
    throw new Error('Non-loopback listeners require HOST_AGENT_TLS_CERT and HOST_AGENT_TLS_KEY');
  const session = new RuntimeSession({
    mode: mode as 'managed-runtime' | 'shared-runtime' | 'desktop-runtime',
    ...(process.env.CODEX_DESKTOP_VERSION
      ? { desktopVersion: process.env.CODEX_DESKTOP_VERSION }
      : {}),
    ...(process.env.CODEX_EXECUTABLE ? { executable: process.env.CODEX_EXECUTABLE } : {}),
    ...(process.env.CODEX_SOCKET ? { socket: process.env.CODEX_SOCKET } : {}),
    ...(process.env.CODEX_SHARED_SERVER_URL
      ? { sharedWebsocketUrl: process.env.CODEX_SHARED_SERVER_URL }
      : {}),
    ...(process.env.CODEX_DESKTOP_WINDOWS_CONFIG
      ? { windowsDesktopConfig: resolve(process.env.CODEX_DESKTOP_WINDOWS_CONFIG) }
      : {}),
    ...(process.env.CODEX_DESKTOP_CONTROLLER_CONFIG
      ? { desktopController: resolve(process.env.CODEX_DESKTOP_CONTROLLER_CONFIG) }
      : {}),
    ...(process.env.CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG
      ? { desktopPreparedBridge: resolve(process.env.CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG) }
      : {}),
    ...(process.env.CODEX_DESKTOP_CREATION_HELPER &&
    process.env.CODEX_DESKTOP_CREATION_PROFILE &&
    process.env.CODEX_DESKTOP_CREATION_VERIFICATION
      ? {
          desktopCreation: {
            helper: resolve(process.env.CODEX_DESKTOP_CREATION_HELPER),
            profile: resolve(process.env.CODEX_DESKTOP_CREATION_PROFILE),
            verification: resolve(process.env.CODEX_DESKTOP_CREATION_VERIFICATION),
          },
        }
      : {}),
    directory,
    ...(process.env.HOST_AGENT_REPLY_CHECKPOINT_FILE
      ? { replyCheckpointFile: resolve(process.env.HOST_AGENT_REPLY_CHECKPOINT_FILE) }
      : {}),
    root: resolve(process.env.HOST_AGENT_ROOT ?? process.cwd()),
    name: process.env.HOST_AGENT_NAME ?? 'Codex Host',
  });
  const domSuggestions = new DesktopDomSuggestions(
    session,
    mode === 'desktop-runtime' ? process.env.CODEX_DESKTOP_DOM_BRIDGE_CONFIG : undefined,
  );
  try {
    await session.connect();
  } catch (error) {
    session.close();
    throw error;
  }
  const suggestionObserver = new DesktopSuggestionObserver({
    directory,
    target: () => session.readingTarget(),
    enabled: mode === 'desktop-runtime' && process.env.CODEX_DESKTOP_SUGGESTION_OBSERVER === '1',
    ...(process.env.CODEX_DESKTOP_SUGGESTION_TRACE_ROOT
      ? { traceRoot: resolve(process.env.CODEX_DESKTOP_SUGGESTION_TRACE_ROOT) }
      : {}),
    ...(mode === 'desktop-runtime' &&
    process.platform === 'darwin' &&
    process.env.CODEX_SHARED_SERVER_URL
      ? { sharedDesktopState: join(homedir(), '.config/agent-hub/shared-desktop') }
      : {}),
    root: resolve(process.env.HOST_AGENT_ROOT ?? process.cwd()),
    codexHome:
      process.env.CODEX_HOME ??
      dirname(dirname(process.env.CODEX_SOCKET ?? join(homedir(), '.codex', 'ipc', 'ipc.sock'))),
  });
  const fileTransfers = new FileTransfers(
    session.hostId,
    directory,
    resolve(process.env.HOST_AGENT_TRANSFER_DIR ?? join(homedir(), 'Downloads', 'Agent Hub')),
  );
  const desktopTranscription =
    mode === 'desktop-runtime'
      ? new DesktopTranscription(
          process.env.CODEX_HOME ??
            (process.platform === 'win32'
              ? join(homedir(), '.codex')
              : dirname(
                  dirname(process.env.CODEX_SOCKET ?? join(homedir(), '.codex', 'ipc', 'ipc.sock')),
                )),
          process.env.CODEX_EXECUTABLE,
        )
      : undefined;
  const voice = new VoiceTranscriptions(
    session.hostId,
    directory,
    desktopTranscription ?? {
      readiness: async () => ({
        available: false,
        reason: '此 Host 未连接原 Codex 桌面转写能力',
        principal: null,
      }),
      transcribe: async () => {
        throw new RuntimeError(503, '原桌面转写不可用');
      },
    },
  );
  const workspaceView =
    mode === 'desktop-runtime'
      ? new DesktopWorkspaceView(
          session,
          process.env.HOST_AGENT_NAME ?? 'Codex Host',
          process.env.CODEX_EXECUTABLE,
          join(
            process.platform !== 'win32'
              ? dirname(
                  dirname(process.env.CODEX_SOCKET ?? join(homedir(), '.codex', 'ipc', 'ipc.sock')),
                )
              : (process.env.CODEX_HOME ?? join(homedir(), '.codex')),
            '.codex-global-state.json',
          ),
        )
      : undefined;
  const chatActions =
    mode === 'desktop-runtime' || (mode === 'shared-runtime' && process.env.CODEX_SHARED_SERVER_URL)
      ? new DesktopChatActions(
          join(directory, 'chat-actions'),
          session.hostId,
          process.env.CODEX_EXECUTABLE ?? 'codex',
          process.env.CODEX_HOME ??
            (process.platform === 'win32'
              ? join(homedir(), '.codex')
              : dirname(
                  dirname(process.env.CODEX_SOCKET ?? join(homedir(), '.codex/ipc/ipc.sock')),
                )),
          async (request) => {
            if (
              machineRuntimeTargetKey(request.target) !==
              machineRuntimeTargetKey(session.readingTarget())
            )
              throw new RuntimeError(409, '聊天操作运行目标已变化');
            await session.guardChatAction(request.threadId);
          },
          process.env.CODEX_SHARED_SERVER_URL,
        )
      : undefined;
  const voiceExpiryTimer = setInterval(() => {
    void voice.prune().catch(() => {});
  }, 3600_000);
  voiceExpiryTimer.unref();
  void voice.prune().catch(() => {});
  if (process.env.HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE)
    assertPrivateFile(process.env.HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE);
  const notifications = new NotificationPublisher({
    target: session.notificationTarget(),
    statePath: join(directory, 'notifications.json'),
    ...(process.env.HOST_NOTIFICATION_RELAY_URL
      ? { relayURL: process.env.HOST_NOTIFICATION_RELAY_URL }
      : {}),
    ...(process.env.HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE
      ? {
          publisherToken: readFileSync(
            process.env.HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE,
            'utf8',
          ).trim(),
        }
      : {}),
    protectFile: protectWindowsStateFile,
  });
  const observer = new NotificationObserver(
    session.notificationTarget(),
    notifications.observations,
    notifications.stoppedRuns,
  );
  const observe = (value: ReturnType<RuntimeSession['notificationObservation']>) => {
    if (!notifications.hasSubscribers) return;
    const result = observer.observe(value);
    notifications.observe(result.events, value.threadId, result.activeIds, value.live, value);
  };
  const unsubscribeNotifications = session.onNotificationObservation(observe);
  let monitoring = false;
  const monitor = async () => {
    session.setNotificationDiscovery(notifications.hasSubscribers);
    if (monitoring || !notifications.hasSubscribers) return;
    notifications.observeConnection(session.ready);
    if (!session.ready) {
      for (const value of session.notificationObservations()) observe(value);
      return;
    }
    monitoring = true;
    try {
      let page = workspaceView ? await workspaceView.workspace() : await session.workspace();
      const seen = new Set<string>();
      while (page.nextProjectCursor || page.nextThreadCursor) {
        const key = JSON.stringify([page.nextProjectCursor, page.nextThreadCursor]);
        if (seen.has(key)) break;
        seen.add(key);
        page = await session.workspace(
          page.nextProjectCursor ?? undefined,
          page.nextThreadCursor ?? undefined,
        );
      }
      await session.refreshNotificationCreations();
      for (const value of session.notificationObservations()) observe(value);
    } catch {
      /* Runtime loss is not a terminal task outcome. The next monitor refresh repairs discovery. */
    } finally {
      monitoring = false;
    }
  };
  const notificationTimer = setInterval(() => {
    void monitor();
  }, 10_000);
  notificationTimer.unref();
  let direct: Awaited<ReturnType<typeof startDirectDiscovery>> | undefined;
  const vpnDirectory = new VPNPeerDirectory();
  const privateConnections = new PrivateConnections(directory);
  const httpsEndpoints = parseCodexSystemEndpoints(process.env.HOST_AGENT_HTTPS_ENDPOINTS);
  // Every response that replaces the client's capabilities must include current
  // Host-owned readiness, including collector and trace-directory changes.
  const mergeHostCapabilities = (capabilities: string[]): string[] => [
    ...new Set([
      ...capabilities,
      ...suggestionObserver.capabilities,
      ...domSuggestions.capabilities,
      ...(desktopTranscription?.capabilityAvailable ? ['voice:transcribe'] : []),
      ...(chatActions ? ['threads:manage'] : []),
      'notifications',
      'files:upload',
      'commands:target-bound:v1',
    ]),
  ];
  let fencedSuggestionPending = false;
  let suggestionDiagnosticRequest = 0;
  const handler = async (request: IncomingMessage, response: ServerResponse) => {
    const requestStartedAt = performance.now();
    const requestId = randomUUID();
    let requestURL: URL | undefined;
    try {
      if (request.headers.origin) throw new RuntimeError(403, '不接受浏览器跨域请求');
      const supplied = Buffer.from((request.headers.authorization ?? '').replace(/^Bearer /, ''));
      const expected = Buffer.from(token);
      if (
        !request.headers.authorization?.startsWith('Bearer ') ||
        !(
          (supplied.length === expected.length && timingSafeEqual(supplied, expected)) ||
          privateConnections.accepts(supplied, session.readingTarget())
        )
      )
        throw new RuntimeError(401, '客户端密钥无效或已撤销');
      try {
        requestURL = new URL(request.url ?? '/', 'http://localhost');
      } catch {
        throw new RuntimeError(400, '无效请求地址', 'INVALID_REQUEST_TARGET');
      }
      const url = requestURL;
      const scopedTarget = url.searchParams.get('target');
      let commandTarget: ReturnType<typeof parseNotificationTarget> | undefined;
      if (scopedTarget !== null) {
        try {
          if (scopedTarget.length > 8192) throw new Error('target-too-large');
          commandTarget = parseNotificationTarget(JSON.parse(scopedTarget));
        } catch {
          throw new RuntimeError(400, '无效操作目标');
        }
      }
      const verifyCommandTarget = () => {
        if (!commandTarget) return;
        const current = session.readingTarget();
        if (
          commandTarget.agentId !== current.agentId ||
          commandTarget.hostId !== current.hostId ||
          commandTarget.workspaceId !== current.workspaceId ||
          commandTarget.runtimeId !== current.runtimeId
        )
          throw new RuntimeError(409, '运行目标已变化，请刷新连接');
      };
      verifyCommandTarget();
      if (request.method === 'GET' && url.pathname === '/v1/connection-routes') {
        if (url.searchParams.get('hostId') !== session.hostId || [...url.searchParams].length !== 1)
          throw new RuntimeError(400, '连接路径请求必须指定当前电脑');
        const routes: CodexConnectionRoutes = {
          version: 1,
          target: session.readingTarget(),
          httpsEndpoints,
        };
        json(response, 200, routes);
        return;
      }
      if (request.method === 'GET' && url.pathname === '/v1/discovery/peers') {
        if (
          url.searchParams.get('hostId') !== session.hostId ||
          [...url.searchParams].length !== 1 ||
          [...url.searchParams.keys()].some((key) => key !== 'hostId')
        )
          throw new RuntimeError(400, '发现请求必须指定当前电脑');
        const snapshot: VPNDiscoverySnapshot = {
          version: 1,
          target: session.readingTarget(),
          peers: await vpnDirectory.peers(),
        };
        json(response, 200, snapshot);
        return;
      }
      if (request.method === 'GET' && url.pathname === '/host' && direct) {
        json(response, 200, direct.descriptor());
        return;
      }
      const readerId = url.searchParams.get('readerId') ?? undefined;
      if (readerId !== undefined && !/^[a-f0-9]{64}$/.test(readerId))
        throw new RuntimeError(400, '无效阅读身份');
      if (request.method === 'GET' && url.pathname === '/healthz') {
        await session.refreshDesktopCreationAvailability();
        json(response, 200, {
          service: 'host-agent',
          status: 'ok',
          protocolVersion: '0.1',
          capabilities: mergeHostCapabilities(['health', ...session.capabilities]),
          persistence: session.persistenceStatus,
        });
        return;
      }
      if (request.method === 'GET' && url.pathname === '/v1/workspace') {
        for (const id of chatActions?.deletedThreadIds() ?? []) {
          workspaceView?.removed(id);
          session.removeSharedThread(id);
        }
        const workspace = workspaceView
          ? await workspaceView.workspace()
          : await session.workspace(
              url.searchParams.get('projectCursor') ?? undefined,
              url.searchParams.get('threadCursor') ?? undefined,
            );
        await sendReadJSON(
          request,
          response,
          session.projectReadState(
            {
              ...workspace,
              capabilities: mergeHostCapabilities(workspace.capabilities),
            },
            readerId,
          ),
        );
        return;
      }
      if (url.pathname === '/v1/workspace-management') {
        if (request.method === 'POST') {
          let parsed;
          try {
            parsed = parseWorkspaceManagementRequest(
              JSON.parse((await body(request, 150_000)).toString('utf8')),
            );
          } catch {
            throw new RuntimeError(400, '无效批量管理请求');
          }
          verifyCommandTarget();
          json(response, 200, session.manageWorkspace(parsed));
        } else if (request.method === 'GET') {
          if (
            url.searchParams.get('hostId') !== session.hostId ||
            [...url.searchParams.keys()].some((key) => !['hostId', 'id', 'target'].includes(key)) ||
            [...url.searchParams.keys()].some((key) => url.searchParams.getAll(key).length !== 1)
          )
            throw new RuntimeError(400, '批量操作查询目标无效');
          let target;
          try {
            target = parseNotificationTarget(JSON.parse(url.searchParams.get('target') ?? ''));
          } catch {
            throw new RuntimeError(400, '批量操作完整目标无效');
          }
          json(
            response,
            200,
            await session.workspaceManagementStatus(url.searchParams.get('id') ?? '', target),
          );
        } else throw new RuntimeError(405, '批量管理方法不支持');
        return;
      }
      const upload =
        request.method === 'POST' &&
        [
          '/v1/attachments',
          '/v1/desktop/creation-attachments',
          '/v1/file-transfers/content',
          '/v1/voice/transcriptions/audio',
        ].includes(url.pathname);
      let data: Record<string, unknown> = {};
      if (request.method === 'POST' && !upload) {
        try {
          data = JSON.parse((await body(request, 150_000)).toString('utf8')) as Record<
            string,
            unknown
          >;
        } catch (error) {
          if (error instanceof RuntimeError) throw error;
          throw new RuntimeError(400, '无效 JSON');
        }
        if (!data || typeof data !== 'object' || Array.isArray(data))
          throw new RuntimeError(400, '无效请求');
        const required: Record<string, string[]> = {
          '/v1/projects': ['hostId', 'name', 'directory', 'clientOperationId'],
          '/v1/threads': ['hostId', 'clientOperationId'],
          '/v1/replies': ['hostId', 'threadId', 'clientMessageId', 'text', 'mode'],
          '/v1/stop': ['hostId', 'threadId', 'turnId'],
          '/v1/queue/cancel': ['hostId', 'threadId', 'clientMessageId'],
          '/v1/queue/execute': ['hostId', 'threadId', 'clientMessageId'],
          '/v1/approvals': ['hostId', 'threadId', 'turnId', 'approvalId'],
          '/v1/desktop/creations': ['hostId', 'clientOperationId', 'text'],
          '/v1/desktop/creations/submit': ['hostId', 'clientOperationId'],
          '/v1/desktop/creations/detach': ['hostId', 'clientOperationId'],
          '/v1/desktop/open': ['hostId', 'threadId', 'clientOperationId'],
          '/v1/threads/read': ['hostId', 'threadId', 'readerId'],
          '/v1/threads/fork': ['hostId', 'threadId', 'turnId', 'clientOperationId', 'environment'],
        };
        if (
          (required[url.pathname] ?? []).some(
            (key) => typeof data[key] !== 'string' || (key !== 'text' && !text(data[key]).trim()),
          )
        )
          throw new RuntimeError(400, '缺少必填字段或字段类型错误');
        if (
          ['/v1/threads', '/v1/desktop/creations'].includes(url.pathname) &&
          data.projectId !== null &&
          (typeof data.projectId !== 'string' || !data.projectId.trim())
        )
          throw new RuntimeError(400, 'projectId 必须为有效项目 ID 或 null');
        if (url.pathname === '/v1/desktop/creations' && !Array.isArray(data.attachments))
          throw new RuntimeError(400, '首条附件清单必填');
        if (
          url.pathname === '/v1/desktop/creations/detach' &&
          typeof data.acknowledgeUncertain !== 'boolean'
        )
          throw new RuntimeError(400, '结束等待确认字段必填');
        if (
          ['/v1/replies', '/v1/desktop/creations'].includes(url.pathname) &&
          ['model', 'effort', 'permissionMode', 'speed'].some(
            (key) => data[key] !== undefined && typeof data[key] !== 'string',
          )
        )
          throw new RuntimeError(400, '生成设置类型无效');
        if (url.pathname === '/v1/replies') {
          if (
            !Array.isArray(data.attachmentIds) ||
            data.attachmentIds.some((v: unknown) => typeof v !== 'string') ||
            [
              'model',
              'effort',
              'permissionMode',
              'speed',
              'expectedTurnId',
              'collaborationMode',
              'expectedPlanId',
            ].some(
              (key) =>
                data[key] !== undefined && data[key] !== null && typeof data[key] !== 'string',
            )
          )
            throw new RuntimeError(400, '消息附件或设置无效');
        }
        if (
          ['/v1/replies', '/v1/desktop/creations'].includes(url.pathname) &&
          data.collaborationMode !== undefined &&
          (typeof data.collaborationMode !== 'string' ||
            !['plan', 'default'].includes(data.collaborationMode))
        )
          throw new RuntimeError(400, '无效计划模式');
      }
      const hostId = text(data.hostId) || url.searchParams.get('hostId');
      if (hostId !== session.hostId) throw new RuntimeError(403, '电脑身份不一致');
      // Body transfer can outlive a runtime identity change.
      verifyCommandTarget();
      if (url.pathname === '/v1/threads/actions') {
        if (!chatActions) throw new RuntimeError(409, '此连接未开放聊天管理');
        let receipt;
        if (request.method === 'POST') {
          if (
            [...url.searchParams.keys()].some(
              (key) =>
                !['hostId', 'target'].includes(key) || url.searchParams.getAll(key).length !== 1,
            ) ||
            (url.searchParams.has('hostId') && url.searchParams.get('hostId') !== data.hostId)
          )
            throw new RuntimeError(400, '聊天操作查询目标不一致或包含未声明参数');
          let parsed;
          try {
            parsed = parseChatActionRequest(data);
          } catch {
            throw new RuntimeError(400, '无效聊天操作');
          }
          if (
            machineRuntimeTargetKey(parsed.target) !==
            machineRuntimeTargetKey(session.readingTarget())
          )
            throw new RuntimeError(409, '聊天操作运行目标已变化');
          receipt = await chatActions.submit(parsed);
        } else if (request.method === 'GET') {
          if (
            [...url.searchParams.keys()].some(
              (key) =>
                !['hostId', 'target', 'threadId', 'id'].includes(key) ||
                url.searchParams.getAll(key).length !== 1,
            )
          )
            throw new RuntimeError(400, '无效回执查询');
          receipt = chatActions.status(
            url.searchParams.get('threadId') ?? '',
            url.searchParams.get('id') ?? '',
          );
          if (
            machineRuntimeTargetKey(receipt.target) !==
            machineRuntimeTargetKey(session.readingTarget())
          )
            throw new RuntimeError(409, '回执运行目标已变化');
        } else throw new RuntimeError(405, '不支持此方法');
        if (receipt.phase === 'confirmed' && receipt.action === 'delete')
          workspaceView?.removed(receipt.threadId);
        session.reconcileSharedChatAction(receipt);
        json(response, 200, receipt);
        return;
      }
      if (url.pathname.startsWith('/v1/voice/')) {
        if (
          (data.hostId &&
            url.searchParams.has('hostId') &&
            data.hostId !== url.searchParams.get('hostId')) ||
          [...url.searchParams.keys()].some((key) => url.searchParams.getAll(key).length !== 1)
        )
          throw new RuntimeError(400, '转写目标字段不一致或重复');
        if (
          Object.keys(Object.fromEntries(url.searchParams)).some(
            (key) => !['hostId', 'id'].includes(key),
          )
        )
          throw new RuntimeError(400, '转写接口包含未声明字段');
        let result;
        if (request.method === 'GET' && url.pathname === '/v1/voice/readiness')
          result = await voice.readiness();
        else if (url.pathname === '/v1/voice/transcriptions' && request.method === 'POST')
          result = await voice.prepare(data);
        else if (url.pathname === '/v1/voice/transcriptions' && request.method === 'GET')
          result = await voice.status(url.searchParams.get('id') ?? '');
        else if (url.pathname === '/v1/voice/transcriptions/audio' && request.method === 'POST') {
          if (request.headers['content-type'] !== 'audio/wav')
            throw new RuntimeError(415, '需要 WAV 录音文件');
          result = await voice.upload(
            url.searchParams.get('id') ?? '',
            await body(request, VOICE_MAX_BYTES),
          );
        } else throw new RuntimeError(405, '转写接口或方法不支持');
        json(response, 200, result);
        return;
      }
      if (url.pathname === '/v1/file-transfers' || url.pathname === '/v1/file-transfers/content') {
        let transferResult;
        try {
          if (url.pathname === '/v1/file-transfers' && request.method === 'GET') {
            transferResult = url.searchParams.has('id')
              ? await fileTransfers.status(url.searchParams.get('id') ?? '')
              : await fileTransfers.catalog();
          } else if (url.pathname === '/v1/file-transfers' && request.method === 'POST') {
            transferResult = await fileTransfers.prepare(data);
          } else if (url.pathname === '/v1/file-transfers/content' && request.method === 'POST') {
            transferResult = await fileTransfers.upload(url.searchParams.get('id') ?? '', request);
          } else throw new RuntimeError(405, '请求方法不支持');
        } catch (error) {
          if (error instanceof RuntimeError) throw error;
          throw new RuntimeError(
            507,
            '电脑未能保存或读取文件，请检查接收目录和可用空间，并查询原传输结果',
          );
        }
        json(response, 200, transferResult);
        return;
      }
      const threadId = text(data.threadId) || url.searchParams.get('threadId') || '';
      if (url.pathname === '/v1/threads/next-turn-suggestion') {
        if (request.method !== 'GET') throw new RuntimeError(405, '建议接口只支持读取');
        const fields = ['hostId', 'target', 'threadId', 'sourceTurnId'];
        if (
          [...url.searchParams.keys()].some((key) => !fields.includes(key)) ||
          fields.some(
            (key) =>
              url.searchParams.getAll(key).length !== 1 || !url.searchParams.get(key)?.trim(),
          )
        )
          throw new RuntimeError(400, '建议读取必须指定唯一完整来源目标');
        let target;
        try {
          const value = JSON.parse(url.searchParams.get('target')!);
          if (
            Object.keys(value).some(
              (key) => !['agentId', 'hostId', 'workspaceId', 'runtimeId', 'account'].includes(key),
            )
          )
            throw new Error('Unsupported target field');
          target = parseNotificationTarget(value);
        } catch {
          throw new RuntimeError(400, '建议读取必须指定完整机器运行目标');
        }
        if (domSuggestions.capabilities.length === 0) {
          const diagnosticRequest = ++suggestionDiagnosticRequest;
          const diagnostic = suggestionObserver.usesFencedSourceRead
            ? createWindowsTraceDiagnostics('host')
            : undefined;
          const traced = <T>(
            phase: 'source_fence' | 'prepare' | 'commit',
            operation: () => Promise<T>,
          ) => (diagnostic ? diagnostic.asyncPhase(phase, operation) : operation());
          const deadlineAt = requestStartedAt + 8000;
          const lifetime = new AbortController();
          const timeoutError = () => new RuntimeError(503, '原桌面建议读取已结束或超时');
          const active = () => {
            if (lifetime.signal.aborted || response.destroyed || performance.now() >= deadlineAt)
              throw timeoutError();
          };
          const timer = setTimeout(
            () => lifetime.abort(),
            Math.max(0, deadlineAt - performance.now()),
          );
          const disconnected = () => {
            if (!response.writableEnded) lifetime.abort();
          };
          response.once('close', disconnected);
          const within = async <T>(operation: () => Promise<T>): Promise<T> => {
            active();
            let rejectAbort: () => void = () => {};
            const stopped = new Promise<never>((_resolve, reject) => {
              rejectAbort = () => reject(timeoutError());
              lifetime.signal.addEventListener('abort', rejectAbort, { once: true });
            });
            try {
              const value = await Promise.race([operation(), stopped]);
              active();
              return value;
            } finally {
              lifetime.signal.removeEventListener('abort', rejectAbort);
            }
          };
          let prepared:
            Awaited<ReturnType<DesktopSuggestionObserver['prepareBoundSourceAsync']>> | undefined;
          let ownsFencedSlot = false;
          try {
            if (suggestionObserver.usesFencedSourceRead) {
              if (fencedSuggestionPending) throw new RuntimeError(503, '原桌面建议读取忙碌');
              fencedSuggestionPending = true;
              ownsFencedSlot = true;
            }
            active();
            suggestionObserver.assertBoundReadAvailable(target);
            active();
            const sourceTurnId = url.searchParams.get('sourceTurnId')!;
            const source = async () =>
              traced('source_fence', async () => {
                const value = await within(() => session.desktopSuggestionSource(threadId));
                if (value.threadId !== threadId || value.sourceTurnId !== sourceTurnId)
                  throw new RuntimeError(409, '建议读取期间来源轮次已变化');
              });
            await source(); // Original before owner/source guard.
            let confirmed;
            if (suggestionObserver.usesFencedSourceRead) {
              prepared = await traced('prepare', () =>
                within(() =>
                  suggestionObserver.prepareBoundSourceAsync(
                    target,
                    threadId,
                    sourceTurnId,
                    deadlineAt,
                    lifetime.signal,
                  ),
                ),
              );
              await source(); // Original middle guard, no reverse business RPC.
              confirmed = await traced('commit', () => within(() => prepared!.commit()));
            } else {
              // Darwin retains both original projections and their identity comparison.
              const first = await within(() =>
                suggestionObserver.readBoundSourceAsync(target, threadId, sourceTurnId),
              );
              await source();
              confirmed = await within(() =>
                suggestionObserver.readBoundSourceAsync(target, threadId, sourceTurnId),
              );
              if (first.suggestion?.id !== confirmed.suggestion?.id) confirmed.suggestion = null;
            }
            await source(); // Original final guard, still inside the HTTP deadline.
            active();
            suggestionObserver.assertBoundReadAvailable(target);
            active();
            if (confirmed.suggestion && Date.parse(confirmed.suggestion.expiresAt) <= Date.now())
              confirmed.suggestion = null;
            // Same-clock lease evidence after every source guard; no lease extension.
            response.setHeader('X-Agent-Hub-Suggestion-Response-Unix-Ms', String(Date.now()));
            active();
            json(response, 200, confirmed);
            diagnostic?.end('ok', {
              request: diagnosticRequest,
              remainingMs: deadlineAt - performance.now(),
            });
            return;
          } catch (error) {
            diagnostic?.end(
              lifetime.signal.aborted || performance.now() >= deadlineAt
                ? 'phase_deadline'
                : (error as { status?: number })?.status === 409
                  ? 'source_changed'
                  : 'native_rejected',
              { request: diagnosticRequest, remainingMs: deadlineAt - performance.now() },
            );
            throw error;
          } finally {
            try {
              prepared?.dispose();
            } finally {
              if (ownsFencedSlot) fencedSuggestionPending = false;
              lifetime.abort();
              clearTimeout(timer);
              response.removeListener('close', disconnected);
            }
          }
        }
        try {
          json(
            response,
            200,
            await domSuggestions.readNextTurn(
              threadId,
              url.searchParams.get('sourceTurnId')!,
              target,
            ),
          );
        } catch (error) {
          if (!(error instanceof DesktopDomBridgeError)) throw error;
          json(response, error.status, { error: error.message, code: error.code });
        }
        return;
      }
      if (url.pathname === '/v1/desktop/suggestion-observations') {
        if (request.method !== 'GET') throw new RuntimeError(405, '建议采集接口只支持读取');
        if (
          [...url.searchParams.keys()].some((key) => !['hostId', 'target'].includes(key)) ||
          [...url.searchParams.keys()].some((key) => url.searchParams.getAll(key).length !== 1)
        )
          throw new RuntimeError(400, '建议采集目标无效');
        let target;
        try {
          target = parseNotificationTarget(JSON.parse(url.searchParams.get('target') ?? ''));
        } catch {
          throw new RuntimeError(400, '建议采集必须指定完整运行目标');
        }
        json(response, 200, suggestionObserver.read(target));
        return;
      }
      if (url.pathname === '/v1/desktop/composer-suggestion') {
        if (request.method !== 'GET') throw new RuntimeError(405, '建议接口只支持读取');
        if (
          [...url.searchParams.keys()].some(
            (key) => !['hostId', 'threadId', 'sourceTurnId', 'target'].includes(key),
          ) ||
          [...url.searchParams.keys()].some((key) => url.searchParams.getAll(key).length !== 1) ||
          !threadId ||
          !url.searchParams.get('sourceTurnId')
        )
          throw new RuntimeError(400, '建议读取目标无效');
        let target;
        try {
          target = parseNotificationTarget(JSON.parse(url.searchParams.get('target') ?? ''));
        } catch {
          throw new RuntimeError(400, '建议读取必须指定完整运行目标');
        }
        try {
          json(
            response,
            200,
            await domSuggestions.read(threadId, url.searchParams.get('sourceTurnId')!, target),
          );
        } catch (error) {
          if (!(error instanceof DesktopDomBridgeError)) throw error;
          json(response, error.status, { error: error.message, code: error.code });
        }
        return;
      }
      if (url.pathname === '/v1/threads/fork-options' && request.method === 'GET') {
        json(response, 200, await session.threadForkOptions(threadId));
        return;
      }
      if (url.pathname === '/v1/threads/fork') {
        if (request.method === 'GET') {
          json(
            response,
            200,
            await session.threadForkStatus(threadId, url.searchParams.get('id') ?? ''),
          );
        } else if (request.method === 'POST') {
          let fork;
          try {
            fork = parseThreadForkRequest(data);
          } catch {
            throw new RuntimeError(400, '分支请求包含无效目标、环境或未声明字段');
          }
          json(response, 200, await session.forkThread(fork));
        } else throw new RuntimeError(405, '请求方法不支持');
        return;
      }
      if (request.method === 'POST' && url.pathname === '/v1/threads/read') {
        if (
          Object.keys(data).some(
            (key) => !['hostId', 'threadId', 'readerId', 'position', 'target'].includes(key),
          )
        )
          throw new RuntimeError(400, '阅读接口包含未声明字段');
        let position, target;
        try {
          position = parseReadPosition(data.position);
          target = parseNotificationTarget(data.target);
        } catch {
          throw new RuntimeError(400, '无效阅读位置或目标');
        }
        const current = session.readingTarget();
        if (machineRuntimeTargetKey(target) !== machineRuntimeTargetKey(current))
          throw new RuntimeError(409, '阅读机器或运行时已变化，请刷新');
        json(response, 200, session.acknowledgeRead(threadId, text(data.readerId), position));
        return;
      }
      if (url.pathname.startsWith('/v1/notifications/')) {
        const keys =
          url.pathname === '/v1/notifications/register'
            ? ['hostId', 'registration']
            : url.pathname === '/v1/notifications/preferences'
              ? ['hostId', 'deviceId', 'target', 'preferences']
              : ['hostId', 'deviceId'];
        if (request.method === 'POST' && Object.keys(data).some((key) => !keys.includes(key)))
          throw new RuntimeError(400, '通知接口包含未声明字段');
        const deviceId = text(data.deviceId) || url.searchParams.get('deviceId') || '';
        if (request.method === 'POST' && url.pathname === '/v1/notifications/register') {
          let registration;
          try {
            registration = parseNotificationRegistration(data.registration);
          } catch {
            throw new RuntimeError(400, '无效通知注册');
          }
          const status = await notifications.register(registration);
          void monitor();
          json(response, 200, status);
          return;
        }
        if (
          !/^[A-Za-z0-9._-]{1,128}$/.test(deviceId) ||
          ['__proto__', 'prototype', 'constructor'].includes(deviceId)
        )
          throw new RuntimeError(400, '无效通知设备');
        if (request.method === 'POST' && url.pathname === '/v1/notifications/preferences') {
          let target, preferences;
          try {
            target = parseNotificationTarget(data.target);
            preferences = parseNotificationPreferences(data.preferences);
          } catch {
            throw new RuntimeError(400, '无效通知偏好');
          }
          json(response, 200, await notifications.updatePreferences(deviceId, target, preferences));
          return;
        }
        if (request.method === 'POST' && url.pathname === '/v1/notifications/unregister') {
          await notifications.unregister(deviceId);
          json(response, 200, notifications.status(deviceId));
          return;
        }
        if (request.method === 'GET' && url.pathname === '/v1/notifications/status') {
          json(response, 200, notifications.status(deviceId));
          return;
        }
        if (request.method === 'GET' && url.pathname === '/v1/notifications/events') {
          const after = url.searchParams.get('after') ?? '';
          if (after && !/^[a-f0-9]{64}$/.test(after)) throw new RuntimeError(400, '无效通知游标');
          json(response, 200, notifications.foreground(deviceId, after));
          return;
        }
        throw new RuntimeError(404, '未知通知操作');
      }
      let result: unknown;
      if (request.method === 'GET') {
        if (url.pathname === '/v1/desktop/open-status')
          result = await session.desktopOpenStatus(threadId, url.searchParams.get('id') ?? '');
        else if (url.pathname === '/v1/desktop/creation-status')
          result = session.desktopCreationStatus();
        else if (url.pathname === '/v1/desktop/creations')
          result = await session.queryDesktopCreation(url.searchParams.get('id') ?? '');
        else if (url.pathname === '/v1/desktop/creation-receipts')
          result = await session.inspectDesktopCreation(url.searchParams.get('id') ?? '');
        else if (url.pathname === '/v1/threads/search-history') {
          if (
            [...url.searchParams.keys()].some(
              (key) =>
                !['hostId', 'threadId', 'cursor'].includes(key) ||
                url.searchParams.getAll(key).length !== 1,
            )
          )
            throw new RuntimeError(400, '无效搜索历史请求');
          result = await session.searchHistoryPage(
            threadId,
            url.searchParams.get('cursor') ?? undefined,
          );
        } else if (url.pathname === '/v1/history')
          result = await session.refreshHistory(
            threadId,
            url.searchParams.get('cursor') ?? undefined,
            url.searchParams.get('window') === 'latest-v1',
          );
        else if (url.pathname === '/v1/events') {
          const controller = new AbortController();
          response.on('close', () => controller.abort());
          const cursor = Number(url.searchParams.get('after') ?? '0');
          if (!Number.isSafeInteger(cursor) || cursor < 0)
            throw new RuntimeError(400, '无效事件游标');
          const batch = await session.poll(
            cursor,
            url.searchParams.get('epoch') ?? '',
            controller.signal,
            url.searchParams.get('threadId') ?? undefined,
            url.searchParams.get('window') === 'latest-v1',
          );
          result = {
            ...batch,
            capabilities: mergeHostCapabilities(batch.capabilities),
          };
          if (response.destroyed) return;
        } else if (url.pathname === '/v1/receipts')
          result = session.receipt(url.searchParams.get('id') ?? '');
        else if (url.pathname === '/v1/replies/status') {
          if (
            [...url.searchParams.keys()].some(
              (key) => !['hostId', 'threadId', 'id', 'target'].includes(key),
            ) ||
            [...url.searchParams.keys()].some((key) => url.searchParams.getAll(key).length !== 1)
          )
            throw new RuntimeError(400, '原消息核对包含无效字段');
          let target;
          if (url.searchParams.has('target')) {
            try {
              target = JSON.parse(url.searchParams.get('target')!);
            } catch {
              throw new RuntimeError(400, '原消息完整目标无效');
            }
          }
          result = session.replyDeliveryStatus(threadId, url.searchParams.get('id') ?? '', target);
        } else if (url.pathname === '/v1/models') result = await session.models();
        else if (url.pathname === '/v1/attachments/resolve-link') {
          if (
            [...url.searchParams.keys()].some(
              (key) =>
                !['hostId', 'threadId', 'messageId', 'reference'].includes(key) ||
                url.searchParams.getAll(key).length !== 1,
            )
          )
            throw new RuntimeError(400, '原消息文件解析请求无效', 'INVALID_FILE_LINK_REQUEST');
          result = await session.resolveMessageFile(
            threadId,
            url.searchParams.get('messageId') ?? '',
            url.searchParams.get('reference') ?? '',
          );
        } else if (url.pathname === '/v1/attachments/resolve') {
          if (
            [...url.searchParams.keys()].some(
              (key) =>
                !['hostId', 'threadId', 'id'].includes(key) ||
                url.searchParams.getAll(key).length !== 1,
            )
          )
            throw new RuntimeError(400, '附件解析请求无效');
          try {
            result = await session.resolveAttachment(threadId, url.searchParams.get('id') ?? '');
          } catch (error) {
            // Reserve 404 for an old Host without this endpoint. Missing resources must
            // not trigger the client's legacy fallback and resurrect a cached version.
            if (error instanceof RuntimeError && error.status === 404)
              throw new RuntimeError(410, error.message);
            throw error;
          }
        } else if (url.pathname === '/v1/attachments') {
          const { file, data: bytes } = await session.download(
            threadId,
            url.searchParams.get('id') ?? '',
          );
          response.writeHead(200, {
            'Content-Type': file.mediaType,
            'Content-Length': bytes.length,
            'Cache-Control': 'no-store',
            'X-Content-Type-Options': 'nosniff',
            'Content-Disposition': `attachment; filename*=UTF-8''${encodeURIComponent(file.name)}`,
          });
          response.end(bytes);
          return;
        } else throw new RuntimeError(404, '接口不存在');
      } else if (request.method === 'POST') {
        if (url.pathname === '/v1/desktop/open')
          result = await session.activateDesktopThread(threadId, text(data.clientOperationId));
        else if (url.pathname === '/v1/desktop/creation-attachments') {
          const bytes = await body(request, 20 * 1024 * 1024);
          verifyCommandTarget();
          result = session.uploadDesktopCreation(
            url.searchParams.get('id') ?? '',
            url.searchParams.get('attachmentId') ?? '',
            bytes,
          );
        } else if (url.pathname === '/v1/desktop/creations')
          result = session.prepareDesktopCreation({
            hostId: text(data.hostId),
            clientOperationId: text(data.clientOperationId),
            projectId: data.projectId === null ? null : text(data.projectId),
            text: text(data.text),
            attachments: data.attachments as Parameters<
              RuntimeSession['prepareDesktopCreation']
            >[0]['attachments'],
            ...(typeof data.collaborationMode === 'string'
              ? { collaborationMode: data.collaborationMode }
              : {}),
            ...(typeof data.model === 'string' ? { model: data.model } : {}),
            ...(typeof data.effort === 'string' ? { effort: data.effort } : {}),
            ...(typeof data.speed === 'string' ? { speed: data.speed } : {}),
            ...(typeof data.permissionMode === 'string'
              ? { permissionMode: data.permissionMode }
              : {}),
          });
        else if (url.pathname === '/v1/desktop/creations/submit')
          result = await session.submitDesktopCreation(text(data.clientOperationId));
        else if (url.pathname === '/v1/desktop/creations/detach')
          result = session.detachDesktopCreation(
            text(data.clientOperationId),
            data.acknowledgeUncertain === true,
          );
        else if (upload) {
          const bytes = await body(request, 20 * 1024 * 1024);
          verifyCommandTarget();
          result = session.upload(
            threadId,
            url.searchParams.get('name') ?? '',
            text(request.headers['content-type']),
            bytes,
            text(request.headers['x-content-sha256']),
          );
        } else if (url.pathname === '/v1/threads')
          result = await session.createThread(
            data.projectId === null ? null : text(data.projectId),
            text(data.clientOperationId),
          );
        else if (url.pathname === '/v1/projects')
          result = await session.createProject(
            text(data.name),
            text(data.directory),
            text(data.clientOperationId),
          );
        else if (url.pathname === '/v1/replies')
          result = await session.submit(
            {
              hostId: text(data.hostId),
              threadId: text(data.threadId),
              clientMessageId: text(data.clientMessageId),
              text: text(data.text),
              attachmentIds: values(data.attachmentIds),
              mode: text(data.mode),
              ...(typeof data.expectedTurnId === 'string'
                ? { expectedTurnId: data.expectedTurnId }
                : {}),
              ...(typeof data.model === 'string' ? { model: data.model } : {}),
              ...(typeof data.permissionMode === 'string'
                ? { permissionMode: data.permissionMode }
                : {}),
              ...(typeof data.effort === 'string' ? { effort: data.effort } : {}),
              ...(typeof data.speed === 'string' ? { speed: data.speed } : {}),
              ...(typeof data.collaborationMode === 'string'
                ? { collaborationMode: data.collaborationMode }
                : {}),
              ...(typeof data.expectedPlanId === 'string'
                ? { expectedPlanId: data.expectedPlanId }
                : {}),
            },
            data.target,
          );
        else if (url.pathname === '/v1/stop') {
          result = await session.stop(threadId, text(data.turnId), () => {
            observer.markStopped(text(data.turnId));
            notifications.markStopped(text(data.turnId));
          });
        } else if (url.pathname === '/v1/queue/cancel')
          result = await session.cancelQueue(threadId, text(data.clientMessageId));
        else if (url.pathname === '/v1/queue/execute') {
          if (
            Object.keys(data).some(
              (key) => !['hostId', 'threadId', 'clientMessageId', 'expectedTurnId'].includes(key),
            )
          )
            throw new RuntimeError(400, '队列执行包含未支持的字段');
          if (
            data.expectedTurnId !== undefined &&
            (typeof data.expectedTurnId !== 'string' || !data.expectedTurnId.trim())
          )
            throw new RuntimeError(400, '无效当前任务 ID');
          result = await session.executeQueue(
            threadId,
            text(data.clientMessageId),
            typeof data.expectedTurnId === 'string' ? data.expectedTurnId : undefined,
          );
        } else if (url.pathname === '/v1/approvals') {
          if (
            data.answers !== undefined &&
            (!data.answers ||
              typeof data.answers !== 'object' ||
              Array.isArray(data.answers) ||
              Object.values(data.answers).some(
                (value) =>
                  !Array.isArray(value) || value.some((answer) => typeof answer !== 'string'),
              ))
          )
            throw new RuntimeError(400, '问题回复必须为字符串数组');
          const answers =
            data.answers && typeof data.answers === 'object' && !Array.isArray(data.answers)
              ? Object.fromEntries(
                  Object.entries(data.answers).map(([key, value]) => [key, values(value)]),
                )
              : {};
          result = await session.answer(
            threadId,
            text(data.approvalId),
            text(data.turnId),
            text(data.decision),
            answers,
          );
        } else throw new RuntimeError(404, '接口不存在');
      } else throw new RuntimeError(405, '请求方法不支持');
      if (request.method === 'POST') session.commitPending();
      const projected = session.projectReadState(
        workspaceView ? workspaceView.project(result) : result,
        readerId,
      );
      if (request.method === 'GET' && ['/v1/history', '/v1/events'].includes(url.pathname))
        await sendReadJSON(request, response, projected);
      else json(response, 200, projected);
    } catch (error) {
      const status =
        error instanceof RuntimeError ||
        error instanceof CreationError ||
        error instanceof SuggestionObservationError
          ? error.status
          : 502;
      const resource = requestURL?.pathname.startsWith('/v1/attachments');
      const nativeCode = (error as { code?: unknown })?.code;
      const code =
        typeof nativeCode === 'string' && /^[A-Z0-9_]{1,64}$/.test(nativeCode)
          ? nativeCode
          : 'HOST_REQUEST_FAILED';
      appendRuntimeDiagnostic(
        directory,
        {
          scope: 'http',
          hostId: session.hostId,
          requestId,
          route: requestURL?.pathname,
          method: request.method,
          status,
          threadId: requestURL?.searchParams.get('threadId') ?? undefined,
          messageId: requestURL?.searchParams.get('messageId') ?? undefined,
          resourceId: requestURL?.searchParams.get('id') ?? undefined,
          elapsedMs: Math.round(performance.now() - requestStartedAt),
        },
        error,
      );
      if (!response.headersSent && !response.destroyed)
        json(response, status, {
          error:
            error instanceof RuntimeError ||
            error instanceof CreationError ||
            error instanceof SuggestionObservationError
              ? error.message
              : `电脑请求失败（${code}），请根据诊断记录核对原因`,
          ...(resource ? { code, requestId } : {}),
        });
    }
  };
  const server = tls
    ? httpsServer(tls, (req, res) => void handler(req, res))
    : httpServer((req, res) => void handler(req, res));
  server.requestTimeout = 40_000;
  server.headersTimeout = 10_000;
  try {
    await new Promise<void>((done, reject) => {
      server.once('error', reject);
      server.listen(Number(process.env.HOST_AGENT_PORT ?? 8788), bind, done);
    });
  } catch (error) {
    clearInterval(notificationTimer);
    unsubscribeNotifications();
    suggestionObserver.close();
    notifications.close();
    workspaceView?.close();
    chatActions?.close();
    clearInterval(voiceExpiryTimer);
    desktopTranscription?.close();
    session.close();
    throw error;
  }
  suggestionObserver.start();
  console.log(
    JSON.stringify({
      service: 'host-agent',
      mode,
      address: server.address(),
      tokenFile,
      ready: session.ready,
    }),
  );
  if (
    process.env.HOST_AGENT_DISCOVERY === '1' ||
    (mode === 'desktop-runtime' && process.env.HOST_AGENT_DISCOVERY !== '0')
  ) {
    try {
      direct = await startDirectDiscovery({
        directory,
        name: process.env.HOST_AGENT_NAME ?? 'Codex Host',
        token,
        target: () => session.readingTarget(),
        handler,
        peers: () => vpnDirectory.peers(),
        claim: (input) => privateConnections.claim(input, session.readingTarget()),
      });
      console.log(
        JSON.stringify({
          service: 'host-agent-discovery',
          address: direct.server.address(),
          pagePath: direct.pagePath,
        }),
      );
    } catch {
      console.error(
        'Host direct discovery unavailable; check private TLS files, OpenSSL and discovery port. Existing Host routes remain active.',
      );
    }
  }
  const close = () => {
    process.removeListener('SIGINT', close);
    process.removeListener('SIGTERM', close);
    clearInterval(notificationTimer);
    let failure: unknown;
    clearInterval(voiceExpiryTimer);
    for (const dispose of [
      unsubscribeNotifications,
      () => notifications.close(),
      () => direct?.close(),
      () => workspaceView?.close(),
      () => chatActions?.close(),
      () => desktopTranscription?.close(),
      () => suggestionObserver.close(),
      () => session.close(),
      () => server.close(),
      () => server.closeAllConnections(),
    ]) {
      try {
        dispose();
      } catch (error) {
        failure ??= error;
      }
    }
    if (failure) throw failure;
  };
  process.once('SIGINT', close);
  process.once('SIGTERM', close);
  return { server, session, close, direct };
}
