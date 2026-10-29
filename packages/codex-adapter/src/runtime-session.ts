import { usageTarget, unavailableUsage } from '@codex-manage/protocol';
import { readCodexUsage } from './usage.js';
import { diagnosticEvent } from './diagnostic-events.js';
import { isDeepStrictEqual } from 'node:util';
import { randomUUID, createHash } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import { setImmediate } from 'node:timers/promises';
import { AttachmentFiles, AttachmentFileError, attachmentFileError } from './attachment-files.js';
import { realpath } from 'node:fs/promises';
import { appendRuntimeDiagnostic } from './runtime-diagnostics.js';
import { localMessagePath, messageLinks } from './message-links.js';
import {
  permissionModes,
  permissionSettings,
  configuredPermissionSettings,
  threadPermissionSettings,
  desktopPermissionMode,
  desktopModelSettings,
  desktopSpeed,
  currentPermissionMode,
  currentSpeed,
  modelSpeedTiers,
  speeds,
} from './generation-settings.js';
import { mkdirSync, readFileSync, writeFileSync, realpathSync, statSync } from 'node:fs';
import { basename, resolve, relative, isAbsolute, extname, join } from 'node:path';
import {
  CodexRuntime,
  CodexRejection,
  object,
  list,
  string,
  renderItem,
  type RenderItem,
  type RuntimeOptions,
  type WireObject,
} from './runtime.js';
import {
  DesktopRuntime,
  DesktopCommandNotSubmitted,
  desktopTurns,
  desktopProposedPlan,
  type ProposedPlan,
} from './desktop-ipc.js';
import { codexClientType } from './history-reader.js';
import { retainedDesktopHistory } from './desktop-history-identity.js';
import { approvalRequest } from './approval-requests.js';
import { DesktopPreparedBridge } from './desktop-prepared-bridge.js';
import { DesktopForkBroker, type ForkSource } from './desktop-forks.js';
import { inspectForkWorktree } from './fork-worktree.js';
import { homedir } from 'node:os';
import { dirname } from 'node:path';
import {
  DesktopCreationBroker,
  creationAttachmentPath,
  CreationError,
  CreationInputNotWritten,
  type CreationInputBoundary,
  type CreationInputCheckpoint,
  type CreationInputStage,
  type CreationInput,
  type CreationRecord,
  type DesktopCreationBridge,
} from './desktop-creation.js';
import { NativeDesktopCreationDriver } from './desktop-creation-driver.js';
import { DesktopControllerBridge } from './desktop-controller.js';
import type { NotificationObservation } from '@codex-manage/notification-runtime';
import {
  normalizeNotificationTaskSummary,
  machineRuntimeTargetKey,
  parseNotificationTarget,
  parseReadPosition,
  type ReadPosition,
  type ThreadForkRequest,
  type ThreadForkOptions,
  type ThreadForkOperation,
  type WorkspaceManagementRequest,
  type WorkspaceManagementOperation,
  type RuntimeTarget,
  type ChatActionReceipt,
} from '@codex-manage/protocol';
import { WorkspaceManagementBroker } from './workspace-management.js';
import { DesktopWorkspaceManagement } from './desktop-workspace-management.js';
import { sharedCatalogPage } from './shared-catalog.js';
import { mergeSharedMessages } from './shared-history.js';
import { desktopPinnedSection } from './desktop-sidebar.js';
import { RuntimePersistence } from './runtime-persistence.js';
import { DesktopNativeQueue, type NativeQueuedInput } from './desktop-native-queue.js';
import { MetadataRejection } from './desktop-thread-preparer.js';
import { runtimeFailure } from './runtime-diagnostics.js';
import { closeWindowsPrivateStateBackend } from './desktop-platform.js';
import {
  desktopQuestions,
  desktopQuestionAnswers,
  desktopQuestionReply,
  parseDesktopQuestionReply,
} from './desktop-questions.js';

function notificationTextSummary(text: string): string | undefined {
  return normalizeNotificationTaskSummary(
    text
      .replace(/data:[^\s"'`<>]+/gi, '')
      .replace(/\[([^\]]*)\]\((?:(?:file|sandbox):|[A-Za-z]:[\\/]|\/|\\\\)[^)]*\)/g, '$1')
      .replace(
        /(["'`])(?:file:\/\/|sandbox:)?(?:\/|[A-Za-z]:[\\/]|\\\\|~\/)[\s\S]*?\1/g,
        '[本地路径]',
      )
      .replace(
        /file:\/\/[^\s"'`<>]+|(?<![\w:/])(?:sandbox:)?(?:\/|[A-Za-z]:[\\/]|\\\\|~\/)[^\s"'`<>()[\]，。！？；]+/g,
        '[本地路径]',
      ),
  );
}
function notificationUserSummary(items: unknown): string | undefined {
  const user = list(items)
    .map(object)
    .find((item) => item.type === 'userMessage');
  if (!user) return undefined;
  const text = list(user.content)
    .map(object)
    .filter(
      (entry) =>
        entry.type === 'text' &&
        !/^用户附件 .*，已上传至本机 .*。按需要用工具读取该文件。$/.test(string(entry.text)),
    )
    .map((entry) => string(entry.text))
    .filter(Boolean)
    .join('\n\n');
  return notificationTextSummary(text);
}
function notificationRequestSummary(params: Record<string, unknown>): string | undefined {
  const question = list(params.questions)
    .map(object)
    .find((value) => string(value.question).trim() || string(value.header).trim());
  return notificationTextSummary(
    string(question?.question) ||
      string(question?.header) ||
      string(params.reason) ||
      string(params.command) ||
      string(params.message),
  );
}

export class RuntimeError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly code?: string,
  ) {
    super(message);
  }
}
class RuntimeSaveError extends RuntimeError {
  constructor(cause: unknown) {
    super(503, 'Host 状态保存失败；请保留原操作 ID，恢复后查询状态', 'HOST_STATE_SAVE_FAILED');
    this.cause = cause;
  }
}
interface Project {
  id: string;
  hostId: string;
  name: string;
  roots: string[];
}
interface Thread {
  archived?: boolean;
  lastTurnStatus?: string | undefined;
  id: string;
  codexId?: string;
  hostId: string;
  projectId: string | null;
  projectIds?: string[];
  title: string;
  state: string;
  hasUnreadTurn?: boolean | undefined;
  readHead?: ReadPosition;
  // Canonical desktop body behind readHead, whose body may contain projected
  // attachment URLs. Persist this fence so summary refreshes cannot revert it.
  readHeadSourceVersion?: string;
  mobileReaders?: Record<string, ReadPosition>;
  turnId: string | null;
  notificationLastTurn?: { id: string; outcome: string };
  messages: Message[];
  plan: Step[];
  proposedPlan?: ProposedPlan | undefined;
  collaborationMode?: string | undefined;
  runtimeModel?: string | undefined;
  runtimeEffort?: string | null | undefined;
  permissionMode?: string | null | undefined;
  speed?: string | null | undefined;
  error: string | null;
  historyCursor?: string | null;
  desktopDirectory?: string;
  historyDirectory?: string;
  desktopInitializationTurnId?: string;
  desktopCreationProjectId?: string | null;
}
interface Step {
  step: string;
  status: string;
}
interface Attachment {
  id: string;
  resourceId?: string;
  hostId: string;
  threadId: string;
  name: string;
  mediaType: string;
  sizeBytes: number;
  sha256: string;
  path: string;
  desktopMessageLinks?: { messageId: string; reference: string }[];
}
interface Message extends Omit<RenderItem, 'paths'> {
  attachments: Omit<Attachment, 'path'>[];
}
export interface Reply {
  clientMessageId: string;
  hostId: string;
  threadId: string;
  text: string;
  attachmentIds: string[];
  mode: string;
  expectedTurnId?: string;
  model?: string;
  effort?: string;
  permissionMode?: string;
  speed?: string;
  collaborationMode?: string;
  expectedPlanId?: string;
}
export interface ReplyTarget {
  agentId: string;
  hostId: string;
  workspaceId: string;
  runtimeId: string;
  account?: { authority: string; subject: string };
}
function replyTarget(value: unknown): ReplyTarget {
  let row;
  try {
    row = parseNotificationTarget(value);
  } catch {
    throw new RuntimeError(400, '完整回复目标无效');
  }
  return Object.freeze({
    agentId: row.agentId,
    hostId: row.hostId,
    workspaceId: row.workspaceId,
    runtimeId: row.runtimeId,
    ...(row.account
      ? {
          account: Object.freeze({
            authority: row.account.authority,
            subject: row.account.subject,
          }),
        }
      : {}),
  });
}
/** Ordered JSON, including absence of optional fields, is the v2 wire intent contract. */
export function replyIntentFingerprint(reply: Reply, target: ReplyTarget): string {
  return createHash('sha256')
    .update(
      JSON.stringify({
        version: 2,
        target: replyTarget(target),
        reply: {
          hostId: reply.hostId,
          threadId: reply.threadId,
          clientMessageId: reply.clientMessageId,
          text: reply.text,
          attachmentIds: reply.attachmentIds,
          mode: reply.mode,
          ...Object.fromEntries(
            [
              'expectedTurnId',
              'model',
              'effort',
              'permissionMode',
              'speed',
              'collaborationMode',
              'expectedPlanId',
            ]
              .filter((key) => reply[key as keyof Reply] !== undefined)
              .map((key) => [key, reply[key as keyof Reply]]),
          ),
        },
      }),
    )
    .digest('hex');
}
interface Receipt {
  clientMessageId: string;
  hostId: string;
  threadId: string;
  state: string;
  turnId: string | null;
  error: string | null;
  supersededByTurnId?: string;
  /** Positive original-owner evidence, retained when later snapshots omit old items. */
  acceptedTurnId?: string;
  fingerprint: string;
  reply: Reply;
  fingerprintVersion?: 2;
  target?: ReplyTarget;
  attachmentSnapshot?: readonly Attachment[];
  nativeQueue?: {
    id?: string;
    phase: 'adding' | 'queued' | 'removing' | 'detached' | 'uncertain';
    present?: boolean;
    missingSince?: number;
    action?: 'cancel' | 'execute';
    input?: Record<string, unknown>[];
    inputSha256?: string;
  };
  creationInput?: {
    state: 'not-written' | 'may-have-written';
    checkpoint: CreationInputCheckpoint;
  };
}
interface FirstInputAttempt {
  boundary: CreationInputBoundary;
  checkpoint: CreationInputCheckpoint;
  stage: CreationInputStage;
  boundaryAttempted: boolean;
  wrote: boolean;
  retryReceipt?: Receipt;
  failure?: unknown;
}
interface Approval {
  id: string;
  threadId: string;
  turnId: string;
  kind: string;
  title: string;
  detail: string;
  questions: unknown[];
  decisions: string[];
  decisionLabels?: Record<string, string>;
  responses?: Record<string, WireObject>;
  requestFingerprint?: string;
  wireId: string | number;
  notificationRequestId?: string;
  taskSummary?: string;
  interactionMode?: 'async';
  sourceItemId?: string;
}
interface State {
  workspaceManagement?: Record<string, WorkspaceManagementOperation>;
  hostId: string;
  projects: Project[];
  threads: Thread[];
  attachments: Attachment[];
  messageFileLinks?: { threadId: string; messageId: string; reference: string; path: string }[];
  receipts: Receipt[];
  creations: Record<string, string>;
  asyncQuestionActions?: Record<
    string,
    {
      threadId: string;
      turnId: string;
      decision: string;
      answers: Record<string, string[]>;
      reply?: Reply;
      resolved?: boolean;
    }
  >;
}

export class RuntimeSession {
  private readonly runtime: CodexRuntime | DesktopRuntime;
  // Owner-derived only: persisted history cannot restore suggestion eligibility.
  private readonly desktopSuggestionSources = new Map<string, string>();
  private readonly nativeQueue?: DesktopNativeQueue;
  private nativeQueueUnsupported = false;
  private nativeQueues = new Map<string, NativeQueuedInput[]>();
  private nativeQueueReads = new Map<string, Promise<void>>();
  private nativeQueueErrors = new Map<string, string>();
  private nativeQueueReadAt = new Map<string, number>();
  private nativeQueueRevisions = new Map<string, number>();
  private readonly queueActions = new Map<
    string,
    {
      action: 'execute' | 'cancel';
      expectedTurnId: string | undefined;
      work: Promise<{ ok: boolean }>;
    }
  >();
  private readonly file: string;
  private readonly persistence: RuntimePersistence<State>;
  private readonly replyNamespace: ReplyTarget;
  private readonly desktopCreation?: DesktopCreationBroker;
  private readonly desktopCreationDriver?: DesktopCreationBridge;
  private readonly desktopForks?: DesktopForkBroker;
  private workspaceManagement?: WorkspaceManagementBroker;
  private openOperations = new Map<
    string,
    {
      hostId: string;
      threadId: string;
      clientOperationId: string;
      phase: string;
      error: string | null;
    }
  >();
  private openingThreads = new Map<string, Promise<boolean>>();
  private state: State;
  private approvals = new Map<string, Approval>();
  private replySubmissions = new Map<
    string,
    {
      fingerprint: string;
      threadId: string;
      target: ReplyTarget;
      work: Promise<ReturnType<RuntimeSession['receipt']>>;
    }
  >();
  private questionSubmissions = new Map<
    string,
    { fingerprint: string; work: Promise<{ ok: boolean }> }
  >();
  private attachmentFiles = new AttachmentFiles(2048, ({ path, operation, attempt, change }) => {
    appendRuntimeDiagnostic(
      this.options.directory,
      {
        scope: 'attachment',
        stage: `${operation}-metadata-retry`,
        hostId: this.hostId,
        path,
        attempt,
      },
      new AttachmentFileError(409, '附件元数据变化后重新校验', 'FILE_CHANGED', change),
    );
  });
  private attachmentResourceIds = new Map<string, string>();
  private desktopProjections = new Map<
    string,
    {
      state: Record<string, unknown>;
      version: number;
      work: Promise<void>;
      resolve: () => void;
      reject: (error: unknown) => void;
      running: boolean;
    }
  >();
  private activeDesktopProjections = 0;
  private loaded = new Set<string>();
  private sharedPinnedThreadIds = new Set<string>();
  private sharedVersions = new Map<string, number>();
  private sharedLiveItems = new Map<string, Set<string>>();
  private restoring = new Map<string, Promise<ReturnType<RuntimeSession['history']>>>();
  private desktopReceiptHistoryReads = new Map<string, Promise<void>>();
  private desktopReceiptHistoryRetryAfter = new Map<string, number>();
  private desktopHistoryReads = new Map<string, number>();
  private desktopHistoryTips = new Map<string, string>();
  private historicalReceiptContexts = new Map<string, string>();
  private readReceiptRetryAt = new Map<string, number>();
  private verifiedDesktopMessageIds = new Map<string, Set<string>>();
  private startedTurns = new Set<string>();
  private locks = new Set<string>();
  private events: { seq: number; threadId: string; kind: string }[] = [];
  private seq = 0;
  private epoch = randomUUID();
  private listeners = new Set<() => void>();
  private notificationListeners = new Set<(value: NotificationObservation) => void>();
  // Live events only, bounded to the latest run of each thread; never restored from history.
  private notificationTasks = new Map<string, { runId: string; taskSummary?: string }>();
  private notificationDiscovery = false;
  private desktopDiscovering = new Set<string>();
  private needsCommit = false;
  private closed = false;
  private connectedOnce = false;
  private reconnecting = false;
  private reconnectTimer: NodeJS.Timeout | undefined;
  private reconnectDelay = 1000;
  private desktopOwnerQueue = new Set<string>();
  private desktopNotificationOwnerQueue = new Set<string>();
  private desktopNotificationNewThreads = new Map<string, number>();
  private desktopKnownOwners = new Set<string>();
  private desktopRefreshOffset = 0;
  private desktopOwnerDiscovery: Promise<void> | undefined;

  constructor(
    private readonly options: RuntimeOptions & {
      directory: string;
      root: string;
      name: string;
      replyCheckpointFile?: string;
    },
  ) {
    mkdirSync(options.directory, { recursive: true, mode: 0o700 });
    mkdirSync(join(options.directory, 'attachments'), { recursive: true, mode: 0o700 });
    this.file = join(options.directory, 'runtime.json');
    this.persistence = new RuntimePersistence(this.file, options.directory);
    const restored = this.persistence.load();
    this.state = restored ?? {
      hostId: randomUUID(),
      projects: [],
      threads: [],
      attachments: [],
      receipts: [],
      creations: {},
    };
    for (const file of this.state.attachments) {
      const key = JSON.stringify([file.threadId, file.path]);
      if (!this.attachmentResourceIds.has(key)) this.attachmentResourceIds.set(key, file.id);
    }
    this.replyNamespace = replyTarget(this.readingTarget());
    this.persistence.acquireReplyWriter(this.replyNamespace, options.replyCheckpointFile);
    for (const receipt of this.state.receipts) {
      if (['submitting', 'submitted'].includes(receipt.state)) receipt.state = 'uncertain';
      if (receipt.fingerprintVersion === 2) {
        receipt.target = replyTarget(receipt.target);
        receipt.reply = Object.freeze({
          ...receipt.reply,
          attachmentIds: Object.freeze([...receipt.reply.attachmentIds]) as unknown as string[],
        });
        receipt.attachmentSnapshot = Object.freeze(
          (receipt.attachmentSnapshot ?? []).map((v) => Object.freeze({ ...v })),
        );
      }
    }
    for (const thread of this.state.threads) {
      if (['running', 'waiting-for-approval'].includes(thread.state)) thread.state = 'unknown';
      thread.turnId = null;
      if (options.mode === 'desktop-runtime') {
        thread.proposedPlan = undefined;
        thread.collaborationMode = undefined;
        thread.runtimeModel = undefined;
        thread.runtimeEffort = undefined;
        thread.permissionMode = undefined;
        thread.speed = undefined;
      }
      for (const message of thread.messages)
        if (message.kind === 'contextCompaction' && message.status === 'inProgress')
          message.status = 'unknown';
    }
    if (!this.state.projects.length && options.mode === 'managed-runtime')
      this.state.projects.push({
        id: 'agent-workspace',
        hostId: this.hostId,
        name: basename(realpathSync(options.root)),
        roots: [realpathSync(options.root)],
      });
    this.runtime =
      options.mode === 'desktop-runtime'
        ? new DesktopRuntime(options, this.hostId, options.name)
        : new CodexRuntime(options);
    if (this.runtime instanceof DesktopRuntime) {
      const metadataExecutable = () =>
        options.executable ??
        (process.platform === 'darwin'
          ? '/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex'
          : 'codex');
      this.nativeQueue = new DesktopNativeQueue(
        metadataExecutable,
        process.platform === 'win32'
          ? (process.env.CODEX_HOME ?? join(homedir(), '.codex'))
          : dirname(dirname(options.socket ?? join(homedir(), '.codex', 'ipc', 'ipc.sock'))),
      );
      this.desktopCreationDriver = options.desktopPreparedBridge
        ? new DesktopPreparedBridge(
            options.desktopPreparedBridge,
            this.hostId,
            this.runtime,
            join(options.directory, 'desktop-prepared-bridge'),
            metadataExecutable,
            process.platform === 'win32'
              ? (process.env.CODEX_HOME ?? join(homedir(), '.codex'))
              : dirname(dirname(options.socket ?? join(homedir(), '.codex', 'ipc', 'ipc.sock'))),
            (plan, id, boundary, fingerprint) =>
              this.submitCreationInput(plan, id, boundary, fingerprint),
            (metadata) => {
              this.rememberDesktopThread(metadata);
              this.save();
            },
          )
        : options.desktopController
          ? new DesktopControllerBridge(
              options.desktopController,
              () => this.runtime.version,
              this.hostId,
              this.runtime,
              join(options.directory, 'desktop-controller'),
              (plan, id) => this.submitCreationInput(plan, id),
            )
          : new NativeDesktopCreationDriver(
              options.desktopCreation,
              options.desktopVersion ?? '',
              () => this.runtime.version,
            );
      this.desktopCreation = new DesktopCreationBroker(
        join(options.directory, 'desktop-creations'),
        this.hostId,
        this.desktopCreationDriver,
        (record, id) => this.verifyDesktopCreation(record, id),
      );
      if (this.desktopCreationDriver instanceof DesktopPreparedBridge) {
        this.desktopForks = new DesktopForkBroker(
          join(options.directory, 'desktop-forks'),
          this.hostId,
          metadataExecutable,
          process.platform === 'win32'
            ? (process.env.CODEX_HOME ?? join(homedir(), '.codex'))
            : dirname(dirname(options.socket ?? join(homedir(), '.codex', 'ipc', 'ipc.sock'))),
          (id, operationId) => this.desktopCreationDriver!.open!(id, operationId),
          (record, cwd) => this.verifyDesktopFork(record, cwd),
          join(homedir(), '.config', 'agent-hub', 'worktrees', this.hostId),
        );
      }
    }
    this.runtime.onEvent = (method, params) => {
      if (method === 'desktop/discovered') {
        void this.discoverDesktopThread(string(params.threadId)).catch(() => undefined);
      } else this.event(method, params);
    };
    this.runtime.onRequest = (id, method, params) => {
      const target = this.state.threads.find(
        (thread) => (thread.codexId ?? thread.id) === params.threadId,
      );
      if (!target) {
        this.runtime.rejectRequest(id);
        return;
      }
      const threadId = target.id;
      const projection = approvalRequest(method, params);
      const kind =
        method === 'item/tool/requestUserInput'
          ? 'question'
          : method === 'item/commandExecution/requestApproval' ||
              method === 'item/fileChange/requestApproval' ||
              projection
            ? 'approval'
            : '';
      if (!kind) {
        this.runtime.rejectRequest(id);
        return;
      }
      const available = list(params.availableDecisions).filter(
        (v): v is string => typeof v === 'string',
      );
      const approval: Approval = {
        id: randomUUID(),
        wireId: id,
        notificationRequestId: String(id),
        requestFingerprint: JSON.stringify([method, params]),
        threadId,
        turnId: string(params.turnId) || target.turnId || '',
        kind,
        title:
          string(params.command) ||
          (kind === 'question' ? 'Codex 需要你的回复' : '文件修改需要审批'),
        detail: [string(params.reason), string(params.cwd)].filter(Boolean).join('\n'),
        questions: list(params.questions),
        decisions:
          kind === 'question' ? [] : available.length ? available : ['accept', 'decline', 'cancel'],
      };
      if (projection) {
        const { desktopMethod: _desktopMethod, ...fields } = projection;
        Object.assign(approval, fields);
      }
      this.approvals.set(approval.id, approval);
      const taskSummary = notificationRequestSummary(params);
      if (taskSummary) approval.taskSummary = taskSummary;
      this.thread(threadId).state = 'waiting-for-approval';
      this.emit(threadId, 'approval');
    };
    this.runtime.onDisconnect = () => {
      if (this.closed) return;
      this.desktopSuggestionSources.clear();
      this.approvals.clear();
      this.loaded.clear();
      this.sharedVersions.clear();
      this.sharedLiveItems.clear();
      this.startedTurns.clear();
      this.notificationTasks.clear();
      for (const receipt of this.state.receipts)
        if (['submitting', 'submitted'].includes(receipt.state)) receipt.state = 'uncertain';
      for (const thread of this.state.threads) {
        thread.state = 'unknown';
        thread.turnId = null;
        if (this.runtime instanceof DesktopRuntime) {
          thread.proposedPlan = undefined;
          thread.collaborationMode = undefined;
          thread.runtimeModel = undefined;
          thread.runtimeEffort = undefined;
          thread.permissionMode = undefined;
          thread.speed = undefined;
        }
        for (const message of thread.messages)
          if (message.status === 'inProgress') message.status = 'unknown';
        this.emit(thread.id, 'disconnected');
      }
      this.saveBackground();
      if (this.connectedOnce && !this.closed && !this.reconnecting) this.scheduleReconnect();
    };
    this.save();
  }

  get hostId(): string {
    return this.state.hostId;
  }
  get persistenceStatus() {
    return { ...this.persistence.status, commandCommitPending: this.needsCommit };
  }
  commitPending(): void {
    if (this.needsCommit) this.save();
  }
  get ready(): boolean {
    return this.runtime.ready;
  }
  diagnosticStatus() {
    return {
      ready: this.ready,
      bridge: this.runtime instanceof DesktopRuntime ? this.runtime.ipc.ready : this.ready,
      reader: this.runtime instanceof DesktopRuntime ? this.runtime.reader.ready : this.ready,
      closed: this.closed,
      commandCommitPending: this.needsCommit,
      historyPending: this.persistence.status.historyPending,
      historyStalled: this.persistence.status.historyStalled,
    };
  }
  get mode(): string {
    return this.options.mode;
  }
  get capabilities(): string[] {
    if (this.mode === 'desktop-runtime')
      return this.ready
        ? [
            'projects:read',
            'projects:delete',
            'threads:archive:unloaded',
            'threads:read',
            'history:read',
            'threads:read-state',
            'reply',
            'reply:delivery-status',
            'attachments:upload',
            'attachments:download',
            'attachments:resolve-current',
            'attachments:resolve-message-link',
            'events',
            'stop',
            ...(this.nativeQueue && !this.nativeQueueUnsupported ? ['queue', 'queue:execute'] : []),
            ...(this.nativeQueue?.available ? ['queue:native'] : []),
            'steer',
            'approvals',
            'plan-mode',
            'models:read',
            'usage:read:v1',
            'generation-settings',
            'generation-speed',
            ...(this.desktopForks &&
            this.desktopCreationDriver?.openAvailable &&
            !this.desktopCreationDriver.openValidationOnly
              ? ['threads:fork', 'threads:fork:worktree']
              : []),
            ...(this.desktopCreation?.driver.available
              ? this.desktopCreationDriver?.validationOnly
                ? ['desktop:creations:testing']
                : ['desktop:creations', 'desktop:creations:projectless']
              : []),
            ...(this.desktopCreationDriver?.openAvailable
              ? [
                  this.desktopCreationDriver.openValidationOnly
                    ? 'desktop:open:testing'
                    : 'desktop:open',
                ]
              : []),
          ]
        : [];
    return this.ready
      ? [
          'projects:read',
          'projects:create',
          'threads:read',
          'threads:create',
          'threads:create:projectless',
          'history:read',
          'threads:read-state',
          'reply',
          'attachments:upload',
          'attachments:download',
          'attachments:resolve-current',
          'events',
          'stop',
          'steer',
          'approvals',
          'models:read',
          'usage:read:v1',
          'plan-mode',
          'generation-settings',
          'generation-speed',
        ]
      : [];
  }
  async connect(): Promise<void> {
    await this.runtime.connect();
    await this.desktopCreationDriver?.refresh();
    this.connectedOnce = true;
  }
  private scheduleReconnect(): void {
    if (this.reconnectTimer || this.closed) return;
    const delayMs = Math.round(this.reconnectDelay * (0.8 + Math.random() * 0.4));
    diagnosticEvent({ scope: 'runtime-reconnect', stage: 'scheduled', delayMs });
    this.reconnectTimer = setTimeout(() => {
      if (this.closed) return;
      this.reconnectTimer = undefined;
      this.reconnecting = true;
      void this.runtime
        .connect()
        .then(() => {
          if (this.closed) {
            this.runtime.close();
            return;
          }
          diagnosticEvent({ scope: 'runtime-reconnect', stage: 'ready', ready: this.ready });
          this.reconnectDelay = 1000;
          this.epoch = randomUUID();
          for (const thread of this.state.threads) this.emit(thread.id, 'runtime-reconnected');
        })
        .catch((error: unknown) => {
          runtimeFailure('runtime-reconnect', error);
          this.reconnectDelay = Math.min(this.reconnectDelay * 2, 15_000);
        })
        .finally(() => {
          this.reconnecting = false;
          if (!this.ready) this.scheduleReconnect();
        });
    }, delayMs);
    this.reconnectTimer.unref();
  }
  private requireReady(): void {
    if (!this.ready)
      throw new RuntimeError(503, 'Codex 运行时未连接', 'CODEX_RUNTIME_DISCONNECTED');
  }
  private save(readOnly = false): void {
    try {
      if (readOnly && !this.needsCommit) this.persistence.saveRead(this.state);
      else this.persistence.save(this.state);
      this.needsCommit = false;
    } catch (error) {
      this.needsCommit = true;
      runtimeFailure('command-save', error);
      throw new RuntimeSaveError(error);
    }
  }
  private saveBackground(): void {
    this.persistence.schedule(() => this.state);
  }

  /** A bounded readonly operation can yield Main to its owner/worker replies. */
  deferBackgroundPersistence(signal: AbortSignal): () => void {
    const resume = this.persistence.deferBackground();
    const release = () => {
      signal.removeEventListener('abort', release);
      resume();
    };
    if (signal.aborted) release();
    else signal.addEventListener('abort', release, { once: true });
    return release;
  }
  private emit(threadId: string, kind: string, notify = true, historyChanged = true): void {
    if (this.closed) return;
    if (this.options?.mode === 'shared-runtime')
      this.sharedVersions.set(threadId, (this.sharedVersions.get(threadId) ?? 0) + 1);
    const changed = this.state.threads.find((thread) => thread.id === threadId);
    if (changed) {
      if (!(this.runtime instanceof DesktopRuntime)) {
        const reply = changed.messages.findLast(
          (message) => message.role === 'assistant' && message.status !== 'inProgress',
        );
        if (reply) this.updateReadHead(changed, reply);
      }
      if (historyChanged) this.persistence.dirty(changed);
    }
    // Polls deliver the latest state; retain signals rather than hundreds of full transcripts.
    this.events.push({ seq: ++this.seq, threadId, kind });
    if (this.events.length > 512) this.events.shift();
    for (const listener of this.listeners) listener();
    if (notify) {
      const observation = this.notificationObservation(threadId);
      for (const listener of this.notificationListeners) {
        try {
          listener(observation);
        } catch {
          /* Notification delivery cannot fail a model task. */
        }
      }
    }
    this.saveBackground();
  }
  onNotificationObservation(listener: (value: NotificationObservation) => void): () => void {
    this.notificationListeners.add(listener);
    return () => this.notificationListeners.delete(listener);
  }
  setNotificationDiscovery(enabled: boolean): void {
    this.notificationDiscovery = enabled;
    if (!enabled) {
      this.desktopNotificationOwnerQueue.clear();
      this.desktopNotificationNewThreads.clear();
    }
  }
  private async discoverDesktopThread(threadId: string): Promise<void> {
    if (
      this.closed ||
      this.desktopDiscovering.has(threadId) ||
      !(this.runtime instanceof DesktopRuntime)
    )
      return;
    // Capture this before follow can grant hasOwner. Optional discovery must
    // not promote an unknown hint into a known chat after opt-out during await.
    const known = this.loaded.has(threadId) || this.runtime.ipc.hasOwner(threadId);
    if (!known && !this.notificationDiscovery) return;
    this.desktopDiscovering.add(threadId);
    try {
      // A different source is only a hint; the registry verifies the real owner.
      const state = await this.runtime.ipc.follow(threadId, 1000, true);
      if (
        this.closed ||
        state?.id !== threadId ||
        state.hostId !== 'local' ||
        !this.runtime.ipc.hasOwner(threadId)
      )
        return;
      // Known followers already project via onSnapshot (rich messages only
      // when loaded). Only opted-in unknown discovery may load another chat.
      if (!known && this.notificationDiscovery)
        await this.open(
          threadId,
          undefined,
          undefined,
          false,
          () => !this.closed && this.notificationDiscovery,
        );
    } finally {
      this.desktopDiscovering.delete(threadId);
    }
  }
  notificationTarget() {
    return {
      agentId: 'codex',
      hostId: this.hostId,
      workspaceId: this.hostId,
      runtimeId: this.mode,
    };
  }
  notificationObservation(threadId: string): NotificationObservation {
    const thread = this.thread(threadId);
    const base: NotificationObservation = {
      threadId,
      title: thread.title,
      live: this.ready,
      readTarget: this.readingTarget(),
      ...(thread.readHead ? { readPosition: { ...thread.readHead } } : {}),
      requests: [],
      uncertainOperations: this.state.receipts
        .filter((receipt) => receipt.threadId === threadId && receipt.state === 'uncertain')
        .map((receipt) => receipt.clientMessageId),
      uncertainOperationDetails: this.state.receipts
        .filter((receipt) => receipt.threadId === threadId && receipt.state === 'uncertain')
        .map((receipt) => ({
          id: receipt.clientMessageId,
          ...(receipt.turnId ? { runId: receipt.turnId } : {}),
          taskSummary: notificationTextSummary(
            '核对已提交消息是否执行：' +
              (notificationTextSummary(receipt.reply.text) ||
                `包含 ${receipt.reply.attachmentIds.length} 个附件的消息`),
          )!,
        })),
    };
    if (this.runtime instanceof DesktopRuntime) {
      base.live = this.ready && this.runtime.ipc.hasOwner(threadId);
      if (!base.live) return base;
      const state = this.runtime.ipc.state(threadId) ?? {};
      const latest = desktopTurns(state)
        .filter((turn) => turn.turnId !== thread.desktopInitializationTurnId)
        .at(-1);
      if (latest) {
        base.runId = string(latest.turnId);
        base.outcome = string(latest.status);
        const taskSummary =
          base.runId && base.outcome !== 'unknown'
            ? notificationUserSummary(latest.items)
            : undefined;
        if (taskSummary) base.taskSummary = taskSummary;
      }
      base.requests = list(state.requests).flatMap((value) => {
        const request = object(value),
          params = object(request.params),
          method = string(request.method);
        if (
          request.completed === true ||
          params.threadId !== threadId ||
          (params.turnId ? params.turnId !== base.runId : base.outcome !== 'inProgress') ||
          (typeof request.id !== 'string' && typeof request.id !== 'number')
        )
          return [];
        const kind =
          method === 'item/tool/requestUserInput'
            ? 'question'
            : approvalRequest(method, params) ||
                [
                  'item/commandExecution/requestApproval',
                  'item/fileChange/requestApproval',
                ].includes(method)
              ? 'approval'
              : undefined;
        const taskSummary = notificationRequestSummary(params);
        return kind
          ? [
              {
                id: String(request.id),
                runId: string(params.turnId) || base.runId!,
                kind: kind as 'approval' | 'question',
                ...(taskSummary ? { taskSummary } : {}),
              },
            ]
          : [];
      });
      if (latest) {
        const answered = desktopQuestionAnswers(
          desktopTurns(state).flatMap((turn) => list(turn.items)),
        );
        base.requests.push(
          ...desktopQuestions(latest.items)
            .filter(
              (question) =>
                !answered.has(question.id) &&
                !this.asyncQuestionResolved(`${threadId}:async:${question.id}`),
            )
            .map((question) => ({
              id: question.id,
              runId: string(latest.turnId),
              kind: 'question' as const,
              ...(notificationTextSummary(question.title)
                ? { taskSummary: notificationTextSummary(question.title)! }
                : {}),
            })),
        );
      }
      const plan = desktopProposedPlan(state);
      if (plan) {
        base.planId = plan.id;
        const summary = notificationTextSummary(plan.text);
        if (summary) base.planSummary = summary;
      }
    } else {
      if (!base.live) return base;
      const turn = thread.notificationLastTurn;
      if (turn) {
        base.runId = turn.id;
        base.outcome = turn.outcome;
        const task = this.notificationTasks.get(threadId);
        if (task?.runId === turn.id && task.taskSummary && turn.outcome !== 'unknown')
          base.taskSummary = task.taskSummary;
      }
      base.requests = [...this.approvals.values()]
        .filter((value) => value.threadId === threadId)
        .map((value) => ({
          id: String(value.wireId),
          runId: value.turnId,
          kind: value.kind === 'question' ? 'question' : 'approval',
          ...(value.taskSummary ? { taskSummary: value.taskSummary } : {}),
        }));
      if (thread.proposedPlan) {
        base.planId = thread.proposedPlan.id;
        const summary = notificationTextSummary(thread.proposedPlan.text);
        if (summary) base.planSummary = summary;
      }
    }
    return base;
  }
  notificationObservations(): NotificationObservation[] {
    const creations = this.desktopCreation?.notificationOperationDetails() ?? [];
    return [
      ...this.state.threads.map((thread) => this.notificationObservation(thread.id)),
      {
        threadId: '',
        title: '创建操作',
        live: true,
        requests: [],
        uncertainOperations: creations.map((record) => record.clientOperationId),
        uncertainOperationDetails: creations.map((record) => ({
          id: record.clientOperationId,
          threadTitle:
            this.state.threads.find((thread) => thread.id === record.threadId)?.title ??
            '新对话 · ' + (notificationTextSummary(record.text) ?? '附件消息'),
          taskSummary: notificationTextSummary(
            '核对新建对话是否成功：' +
              (notificationTextSummary(record.text) ||
                `包含 ${record.attachments.length} 个附件的消息`),
          )!,
        })),
      },
    ];
  }
  async refreshNotificationCreations(): Promise<void> {
    await this.desktopCreation?.refreshNotificationOperations();
  }
  /** Internal projection from the official list or a validated owned empty preparation. */
  rememberDesktopThread(metadata: {
    id: string;
    hostId: string;
    projectId: string | null;
    title: string;
    projectIds?: string[];
  }): Pick<Thread, 'state' | 'turnId' | 'lastTurnStatus'> {
    if (!(this.runtime instanceof DesktopRuntime) || metadata.hostId !== this.hostId)
      throw new RuntimeError(403, '目录目标不一致');
    // The execution cache survives restarts; a new read-only connection's allowlist does not.
    this.runtime.reader.registerCatalogThread(metadata.id);
    if (!this.state.threads.some((v) => v.id === metadata.id)) {
      this.state.threads.push({
        ...metadata,
        state: 'unknown',
        turnId: null,
        messages: [],
        plan: [],
        error: null,
      });
    }
    const thread = this.thread(metadata.id);
    return { state: thread.state, turnId: thread.turnId, lastTurnStatus: thread.lastTurnStatus };
  }
  async guardChatAction(threadId: string): Promise<void> {
    if (
      !(this.runtime instanceof DesktopRuntime) &&
      !(this.mode === 'shared-runtime' && this.options.sharedWebsocketUrl)
    )
      throw new RuntimeError(409, '需要原桌面或共享服务连接');
    this.requireReady();
    this.thread(threadId);
    // Execution state and reply receipts do not determine lifecycle eligibility.
    // The official management API decides whether it can perform this action;
    // its rejection must not be confused with an uncertain submission.
  }
  /** Project only a confirmed official result. Receipts remain durable after removal. */
  reconcileSharedChatAction(receipt: ChatActionReceipt): void {
    if (this.mode !== 'shared-runtime' || receipt.phase !== 'confirmed') return;
    if (receipt.action === 'delete') this.removeSharedThread(receipt.threadId);
    else if (receipt.action === 'archive' || receipt.action === 'unarchive') {
      const thread = this.state.threads.find((entry) => entry.id === receipt.threadId);
      if (thread) {
        thread.archived = receipt.action === 'archive';
        this.emit(thread.id, 'chat-action');
      }
    }
  }
  removeSharedThread(threadId: string): void {
    if (this.mode !== 'shared-runtime') return;
    this.loaded.delete(threadId);
    this.sharedPinnedThreadIds.delete(threadId);
    this.sharedVersions.delete(threadId);
    this.sharedLiveItems.delete(threadId);
    this.state.threads = this.state.threads.filter((thread) => thread.id !== threadId);
    this.events = this.events.filter((event) => event.threadId !== threadId);
    for (const [id, approval] of this.approvals)
      if (approval.threadId === threadId) this.approvals.delete(id);
    this.saveBackground();
  }
  private thread(id: string): Thread {
    const thread = this.state.threads.find((entry) => entry.id === id);
    if (!thread) throw new RuntimeError(404, '对话不属于当前运行时');
    return thread;
  }
  private cleanAttachment(value: Attachment): Omit<Attachment, 'path'> {
    const { path: _path, desktopMessageLinks: _links, ...result } = value;
    const key = JSON.stringify([value.threadId, value.path]);
    if (!this.attachmentResourceIds.has(key)) this.attachmentResourceIds.set(key, value.id);
    return { ...result, resourceId: this.attachmentResourceIds.get(key)! };
  }

  private readContentVersion(reply: Pick<RenderItem, 'id' | 'text' | 'status'>): string {
    // UTF-8 length framing is shared with iOS; clocks and transport epochs are irrelevant.
    const hash = createHash('sha256');
    for (const value of [reply.id, reply.text, reply.status])
      hash.update(Buffer.byteLength(value, 'utf8') + ':' + value);
    return hash.digest('hex');
  }
  private updateReadHead(thread: Thread, reply: Pick<RenderItem, 'id' | 'text' | 'status'>): void {
    if (!reply.id) return;
    const contentVersion = this.readContentVersion(reply);
    if (thread.readHead?.contentVersion === contentVersion) return;
    thread.readHead = {
      revision: (thread.readHead?.revision ?? 0) + 1,
      messageId: reply.id,
      contentVersion,
    };
  }

  readingTarget() {
    // Codex login is owned by the desktop. Product routing identifies its machine.
    return this.notificationTarget();
  }

  /** Authoritative source check for read-only desktop integrations; persisted history is insufficient. */
  async desktopSuggestionSource(
    threadId: string,
  ): Promise<{ threadId: string; sourceTurnId: string }> {
    this.thread(threadId);
    try {
      this.requireReady();
      if (!(this.runtime instanceof DesktopRuntime))
        throw new RuntimeError(503, '当前连接没有原桌面 owner', 'DESKTOP_OWNER_UNAVAILABLE');
      // Revalidation is not invalidation. Polls may publish while follow awaits;
      // preserve the last owner-confirmed source until this read settles. Live
      // new-turn/unavailable events continue clearing it immediately.
      const state = await this.runtime.ipc.follow(threadId, 1500, true);
      if (
        !state ||
        state.id !== threadId ||
        state.hostId !== 'local' ||
        !this.runtime.ipc.hasOwner(threadId)
      )
        throw new RuntimeError(
          503,
          '建议来源聊天的原 owner 不可用',
          'SUGGESTION_OWNER_UNAVAILABLE',
        );
      const sourceTurnId = this.completedDesktopSuggestionSource(threadId, state);
      if (!sourceTurnId) throw new RuntimeError(409, '建议来源必须是最新的已完成轮次');
      this.desktopSuggestionSources.set(threadId, sourceTurnId);
      return { threadId, sourceTurnId };
    } catch (error) {
      this.desktopSuggestionSources.delete(threadId);
      throw error;
    }
  }

  private completedDesktopSuggestionSource(
    threadId: string,
    state: Record<string, unknown>,
  ): string | null {
    const turns = desktopTurns(state);
    const latest = turns.at(-1);
    if (
      !this.ready ||
      !(this.runtime instanceof DesktopRuntime) ||
      !this.runtime.ipc.hasOwner(threadId) ||
      state.id !== threadId ||
      state.hostId !== 'local' ||
      latest?.status !== 'completed' ||
      object(state.threadRuntimeStatus).type === 'active' ||
      turns.some((turn) => turn.status === 'inProgress')
    )
      return null;
    return string(latest.turnId ?? latest.id) || null;
  }

  private mobileReadPosition(thread: Thread, readerId: string): ReadPosition | undefined {
    const suffix = ':' + this.mode + ':' + readerId;
    const readers = thread.mobileReaders ?? {};
    const currentKey = machineRuntimeTargetKey(this.readingTarget()) + suffix;
    if (Object.hasOwn(readers, currentKey)) {
      try {
        return parseReadPosition(readers[currentKey]);
      } catch {
        return undefined;
      }
    }
    let latest: ReadPosition | undefined;
    let conflict = false;
    // Old account-prefixed watermarks stay on disk. Only this machine's thread,
    // runtime mode and exact mobile reader may read them under the new policy.
    for (const [key, value] of Object.entries(readers)) {
      if (!key.endsWith(suffix)) continue;
      try {
        const position = parseReadPosition(value);
        if (!latest || position.revision > latest.revision) {
          latest = position;
          conflict = false;
        } else if (
          position.revision === latest.revision &&
          (position.messageId !== latest.messageId ||
            position.contentVersion !== latest.contentVersion)
        ) {
          conflict = true;
        }
      } catch {
        /* A damaged replaceable watermark cannot acknowledge new content. */
      }
    }
    return conflict ? undefined : latest;
  }

  /** Adds only this mobile reader's state, leaving the original desktop flag intact. */
  projectReadState<T>(value: T, readerId?: string): T {
    if (readerId !== undefined && !/^[a-f0-9]{64}$/.test(readerId))
      throw new RuntimeError(400, '无效阅读身份');
    if (!value || typeof value !== 'object' || Array.isArray(value)) return value;
    const row = value as Record<string, unknown>;
    const target = this.readingTarget();
    const summary = (input: Record<string, unknown>) => {
      const thread = this.state.threads.find((entry) => entry.id === input.id);
      if (input.hostId !== this.hostId || !thread?.readHead) return input;
      const read = readerId ? this.mobileReadPosition(thread, readerId) : undefined;
      return {
        ...input,
        readState: {
          version: 1,
          latest: thread.readHead,
          ...(readerId ? { readerId } : {}),
          ...(read ? { read } : {}),
        },
      };
    };
    return {
      ...row,
      ...(Array.isArray(row.threads)
        ? {
            threads: row.threads.map((entry) => summary(object(entry))),
          }
        : {}),
      ...(row.thread ? { thread: summary(object(row.thread)) } : {}),
      ...(row.hostId === this.hostId ? { readTarget: target } : {}),
      ...(Array.isArray(row.events)
        ? {
            events: row.events.map((entry) => {
              const event = object(entry);
              return { ...event, snapshot: this.projectReadState(event.snapshot, readerId) };
            }),
          }
        : {}),
    } as T;
  }

  /** Older phones label unknown decisions as Stop. Never send them a grant button. */
  projectApplicationApprovals<T>(value: T, supported: boolean): T {
    if (supported || !value || typeof value !== 'object' || Array.isArray(value)) return value;
    const row = value as Record<string, unknown>;
    const runtime = object(row.runtime);
    return {
      ...row,
      ...(Array.isArray(runtime.approvals)
        ? {
            runtime: {
              ...runtime,
              approvals: runtime.approvals.map((value) => {
                const approval = object(value);
                return approval.decisionLabels
                  ? {
                      ...approval,
                      decisions: ['decline'],
                      detail: [
                        string(approval.detail),
                        '请更新手机 App 后允许此操作；当前版本可以拒绝。',
                      ]
                        .filter(Boolean)
                        .join('\n'),
                    }
                  : value;
              }),
            },
          }
        : {}),
      ...(Array.isArray(row.events)
        ? {
            events: row.events.map((value) => {
              const event = object(value);
              return {
                ...event,
                snapshot: this.projectApplicationApprovals(event.snapshot, false),
              };
            }),
          }
        : {}),
    } as T;
  }

  acknowledgeRead(threadId: string, readerId: string, position: ReadPosition) {
    if (!/^[a-f0-9]{64}$/.test(readerId)) throw new RuntimeError(400, '无效阅读身份');
    const thread = this.thread(threadId);
    const latest = thread.readHead;
    if (
      !latest ||
      latest.revision !== position.revision ||
      latest.messageId !== position.messageId ||
      latest.contentVersion !== position.contentVersion
    )
      throw new RuntimeError(409, '回复已更新，旧阅读记录不能清除新消息');
    const key = machineRuntimeTargetKey(this.readingTarget()) + ':' + this.mode + ':' + readerId;
    thread.mobileReaders ??= {};
    if (
      thread.mobileReaders[key]?.revision !== latest.revision ||
      thread.mobileReaders[key]?.messageId !== latest.messageId ||
      thread.mobileReaders[key]?.contentVersion !== latest.contentVersion
    ) {
      thread.mobileReaders[key] = { ...latest };
      this.save();
      this.emit(threadId, 'mobile/read');
    }
    return { hostId: this.hostId, threadId, target: this.readingTarget(), readerId, read: latest };
  }
  private desktopWindow(thread: Thread, before?: string) {
    let end = thread.messages.length;
    if (before) {
      try {
        const value = JSON.parse(
          Buffer.from(before.slice('desktop-page-v1:'.length), 'base64url').toString(),
        );
        if (
          !Array.isArray(value) ||
          value.length !== 3 ||
          value[0] !== this.hostId ||
          value[1] !== thread.id ||
          typeof value[2] !== 'string'
        )
          throw new Error();
        end = thread.messages.findIndex((message) => message.id === value[2]);
        if (end < 0) throw new Error();
      } catch {
        throw new RuntimeError(409, '历史分页已变化，请重新读取此对话');
      }
    }
    let start = end,
      bytes = 0;
    while (start > 0 && end - start < 100) {
      const size = Buffer.byteLength(JSON.stringify(thread.messages[start - 1]));
      if (start < end && bytes + size > 2 * 1024 * 1024) break;
      bytes += size;
      start--;
    }
    return {
      messages: thread.messages.slice(start, end),
      nextCursor:
        start > 0
          ? 'desktop-page-v1:' +
            Buffer.from(
              JSON.stringify([this.hostId, thread.id, thread.messages[start]!.id]),
            ).toString('base64url')
          : (thread.historyCursor ?? null),
      historyWindow: { version: 1, kind: before ? 'earlier' : 'latest' },
      synchronization: {
        state:
          this.ready &&
          this.runtime instanceof DesktopRuntime &&
          this.runtime.ipc.hasOwner(thread.id)
            ? 'live'
            : Date.now() - (this.desktopHistoryReads.get(thread.id) ?? 0) < 10_000
              ? this.runtime instanceof DesktopRuntime &&
                this.runtime.ipc
                  .observation(thread.id)
                  ?.items.some((v) => v.turnId === this.desktopHistoryTips.get(thread.id))
                ? 'live-window'
                : 'history'
              : 'unavailable',
        ...(this.desktopHistoryReads.has(thread.id)
          ? { checkedAt: new Date(this.desktopHistoryReads.get(thread.id)!).toISOString() }
          : {}),
      },
    };
  }
  history(threadId: string, windowed = false, before?: string) {
    const thread = this.thread(threadId);
    const currentModel =
      this.runtime instanceof DesktopRuntime ? this.runtime.ipc.modelSettings(threadId) : undefined;
    const receipts = this.state.receipts.filter((value) => value.threadId === threadId);
    const recentReceipts = new Set(receipts.slice(-30).map((value) => value.clientMessageId));
    const {
      messages: _messages,
      plan: _plan,
      proposedPlan: _proposedPlan,
      collaborationMode: _collaborationMode,
      runtimeModel: _runtimeModel,
      runtimeEffort: _runtimeEffort,
      permissionMode: _permissionMode,
      speed: _speed,
      error: _error,
      codexId: _codexId,
      desktopDirectory: _directory,
      historyDirectory: _historyDirectory,
      desktopInitializationTurnId: _initialization,
      desktopCreationProjectId: _creationProject,
      notificationLastTurn: _notificationTurn,
      readHead: _readHead,
      readHeadSourceVersion: _readHeadSourceVersion,
      mobileReaders: _mobileReaders,
      ...summary
    } = thread;
    return {
      hostId: this.hostId,
      ready: this.ready,
      capabilities: this.capabilities,
      threadId,
      thread: {
        ...summary,
        pendingAsyncQuestionCount: this.pendingAsyncQuestionCounts().get(threadId) ?? 0,
      },
      messages: thread.messages,
      nextCursor: thread.historyCursor ?? null,
      runtime: {
        ...(this.runtime instanceof DesktopRuntime
          ? {
              canControl: this.runtime.ipc.hasOwner(threadId),
              suggestionSourceTurnId:
                this.ready &&
                this.runtime.ipc.hasOwner(threadId) &&
                thread.state === 'idle' &&
                !thread.turnId
                  ? (this.desktopSuggestionSources.get(threadId) ?? null)
                  : null,
            }
          : {}),
        state: thread.state,
        turnId: thread.turnId,
        plan: thread.plan,
        proposedPlan: thread.proposedPlan ?? null,
        collaborationMode: thread.collaborationMode ?? null,
        generationSettings: {
          model: currentModel?.model ?? thread.runtimeModel ?? null,
          effort: currentModel ? currentModel.effort : (thread.runtimeEffort ?? null),
          permissionMode: thread.permissionMode ?? null,
          speed: thread.speed ?? null,
        },
        error: thread.error,
        approvals: [...this.approvals.values()]
          .filter((v) => v.threadId === threadId)
          .map(
            ({
              wireId,
              responses: _responses,
              requestFingerprint: _requestFingerprint,
              ...rest
            }) => ({
              ...rest,
              notificationRequestId: rest.notificationRequestId ?? String(wireId),
            }),
          ),
        receipts: this.queueReceipts(threadId, receipts, recentReceipts),
      },
      epoch: this.epoch,
      cursor: this.seq,
      ...(windowed && this.runtime instanceof DesktopRuntime
        ? this.desktopWindow(thread, before)
        : {}),
    };
  }
  private async discoverDesktopOwners(): Promise<void> {
    if (!(this.runtime instanceof DesktopRuntime)) return;
    const ipc = this.runtime.ipc;
    while (
      !this.closed &&
      (this.desktopOwnerQueue.size || this.desktopNotificationOwnerQueue.size)
    ) {
      // Cold history can contain hundreds of chats with no owner. Bound its
      // probes so approval/stop commands retain the desktop's discovery budget.
      const ids = [
        ...new Set([...this.desktopNotificationOwnerQueue, ...this.desktopOwnerQueue]),
      ].slice(0, 4);
      for (const id of ids) {
        this.desktopOwnerQueue.delete(id);
        this.desktopNotificationOwnerQueue.delete(id);
      }
      await Promise.allSettled(
        ids.map(async (id) => {
          await ipc.follow(id, 1000);
          if (ipc.hasOwner(id)) this.desktopKnownOwners.add(id);
          else if (!this.closed && this.thread(id).state !== 'unknown')
            this.event('desktop/unavailable', { threadId: id });
        }),
      );
    }
    if (this.closed) {
      this.desktopOwnerQueue.clear();
      this.desktopNotificationOwnerQueue.clear();
      this.desktopNotificationNewThreads.clear();
    }
  }
  async workspace(projectCursor?: string, threadCursor?: string) {
    this.requireReady();
    await this.desktopCreationDriver?.refresh();
    let nextProjectCursor: string | null = null;
    let nextThreadCursor: string | null = null;
    let pinnedThreadIds: string[] | undefined;
    if (this.runtime instanceof DesktopRuntime) {
      const page = await this.runtime.reader.workspace(projectCursor, threadCursor);
      nextProjectCursor = page.nextProjectCursor;
      nextThreadCursor = page.nextThreadCursor;
      pinnedThreadIds = page.pinnedThreadIds;
      for (const project of page.projects) {
        const index = this.state.projects.findIndex((v) => v.id === project.id);
        if (index >= 0) this.state.projects[index] = project;
        else this.state.projects.push(project);
      }
      for (const metadata of page.threads) {
        const archived = this.state.threads.find((t) => t.id === metadata.id);
        if (archived) archived.archived = false;
        const current = this.state.threads.find((v) => v.id === metadata.id);
        if (current) {
          if (Object.hasOwn(current, 'desktopCreationProjectId')) {
            current.projectId = current.desktopCreationProjectId ?? null;
            current.projectIds = current.projectId ? [current.projectId] : [];
          } else {
            current.projectId = metadata.projectId;
            current.projectIds = metadata.projectIds ?? [];
          }
          current.title = metadata.title;
        } else {
          this.state.threads.push({
            ...metadata,
            turnId: null,
            messages: [],
            plan: [],
            error: null,
          });
          // New desktop work must not wait behind the persisted cold-history
          // catalog. The same bounded owner worker still verifies every target.
          if (this.notificationDiscovery)
            this.desktopNotificationNewThreads.set(metadata.id, Date.now());
        }
      }
      if (!threadCursor) {
        const firstPageIds = new Set(page.threads.map((v) => v.id));
        this.state.threads = [
          ...page.threads.map((v) => this.thread(v.id)),
          ...this.state.threads.filter((v) => !firstPageIds.has(v.id)),
        ];
      }
      // Metadata cannot describe execution state. Keep undiscovered owners
      // unknown, and give discovery a small budget per catalog page. The
      // bounded background queue continues without opening or resuming chats.
      const ipc = this.runtime.ipc;
      // Read-state changes can miss the owner's stream. Keep that fallback,
      // but rotate at most two owners rather than requesting every transcript.
      if (!projectCursor && !threadCursor) {
        const owners = this.state.threads.filter((thread) => ipc.hasOwner(thread.id));
        for (let index = 0; index < Math.min(2, owners.length); index++)
          ipc.refreshSnapshot(
            owners[(this.desktopRefreshOffset + index) % owners.length]!.id,
            false,
          );
        this.desktopRefreshOffset = owners.length
          ? (this.desktopRefreshOffset + 2) % owners.length
          : 0;
      }
      const priority: Promise<unknown>[] = [];
      for (const thread of page.threads) {
        const discoveredAt = this.desktopNotificationNewThreads.get(thread.id);
        if (discoveredAt !== undefined) {
          // Empty metadata may appear before its original desktop owner loads.
          // Retry recently discovered targets on catalog refresh, without a
          // tight retry loop or placing them behind the cold-history backlog.
          if (ipc.hasOwner(thread.id) || Date.now() - discoveredAt >= 120_000)
            this.desktopNotificationNewThreads.delete(thread.id);
          else if (this.notificationDiscovery) this.desktopNotificationOwnerQueue.add(thread.id);
        }
        if (ipc.hasOwner(thread.id)) {
          this.desktopKnownOwners.add(thread.id);
        } else {
          // Previously observed and selected owners bypass the cold catalog
          // queue, so unrelated unavailable chats cannot delay their recovery.
          if (this.desktopKnownOwners.has(thread.id) || this.loaded.has(thread.id))
            priority.push(ipc.follow(thread.id, 1000));
          else this.desktopOwnerQueue.add(thread.id);
          if (this.thread(thread.id).state !== 'unknown')
            this.event('desktop/unavailable', { threadId: thread.id });
        }
      }
      if (!this.desktopOwnerDiscovery)
        this.desktopOwnerDiscovery = this.discoverDesktopOwners().finally(() => {
          this.desktopOwnerDiscovery = undefined;
        });
      await Promise.race([
        Promise.allSettled([...priority, this.desktopOwnerDiscovery]),
        new Promise<void>((resolve) => setTimeout(resolve, 250)),
      ]);
      this.requireReady();
      // Catalog/read state is replaceable; command intent still uses save().
      this.saveBackground();
    }
    if (this.mode === 'shared-runtime') {
      const projects = await this.runtime.call('project/list', {
        limit: 100,
        ...(projectCursor ? { cursor: projectCursor } : {}),
      });
      const page = await sharedCatalogPage(this.runtime, threadCursor);
      nextProjectCursor = string(projects.nextCursor) || null;
      nextThreadCursor = page.nextCursor;
      const mappedProjects = list(projects.data).map((entry) => {
        const v = object(entry);
        return {
          id: string(v.id),
          hostId: this.hostId,
          name: string(v.name),
          roots: list(v.roots).map((root) => string(object(root).path)),
        };
      });
      for (const project of mappedProjects) {
        const index = this.state.projects.findIndex((v) => v.id === project.id);
        if (index >= 0) this.state.projects[index] = project;
        else this.state.projects.push(project);
      }
      for (const metadata of page.threads) {
        const id = string(metadata.id);
        if (!id) continue;
        const current = this.state.threads.find((thread) => thread.id === id);
        const fields = {
          projectId: string(metadata.projectId) || null,
          title: string(metadata.name) || string(metadata.preview) || '新对话',
          archived: metadata.archived === true,
        };
        if (current) Object.assign(current, fields);
        else
          this.state.threads.push({
            id,
            hostId: this.hostId,
            ...fields,
            state: 'unknown',
            turnId: null,
            messages: [],
            plan: [],
            error: null,
          });
        if (object(metadata.section).id === desktopPinnedSection)
          this.sharedPinnedThreadIds.add(id);
        else this.sharedPinnedThreadIds.delete(id);
      }
      pinnedThreadIds = [...this.sharedPinnedThreadIds];
      this.saveBackground();
    }

    const pendingAsyncQuestions = this.pendingAsyncQuestionCounts();
    return {
      hostId: this.hostId,
      name: this.options.name,
      connectionMode: this.mode,
      clientType: codexClientType,
      capabilities: this.capabilities,
      projects: this.state.projects,
      ...(pinnedThreadIds ? { pinnedThreadIds } : {}),
      threads: this.state.threads
        .filter((thread) => this.mode === 'shared-runtime' || !thread.archived)
        .map(
          ({
            messages: _messages,
            plan: _plan,
            error: _error,
            codexId: _codexId,
            proposedPlan: _proposedPlan,
            collaborationMode: _collaborationMode,
            runtimeModel: _runtimeModel,
            runtimeEffort: _runtimeEffort,
            permissionMode: _permissionMode,
            speed: _speed,
            desktopDirectory: _directory,
            historyDirectory: _historyDirectory,
            desktopInitializationTurnId: _initialization,
            desktopCreationProjectId: _creationProject,
            notificationLastTurn: _notificationTurn,
            readHead: _readHead,
            readHeadSourceVersion: _readHeadSourceVersion,
            mobileReaders: _mobileReaders,
            ...rest
          }) => ({
            ...rest,
            pendingAsyncQuestionCount: pendingAsyncQuestions.get(rest.id) ?? 0,
          }),
        ),
      nextProjectCursor,
      nextThreadCursor,
    };
  }

  private pendingAsyncQuestionCounts(): Map<string, number> {
    const counts = new Map<string, number>();
    for (const approval of this.approvals.values())
      if (approval.interactionMode === 'async')
        counts.set(approval.threadId, (counts.get(approval.threadId) ?? 0) + 1);
    return counts;
  }

  async searchHistoryPage(threadId: string, cursor?: string) {
    const thread = this.thread(threadId);
    const runtime = this.runtime;
    if (!(runtime instanceof DesktopRuntime)) throw new RuntimeError(409, '需要原桌面历史连接');
    if (!cursor) {
      const owner = await runtime.ipc.follow(threadId, 3000, true);
      if (owner && runtime.ipc.hasOwner(threadId)) {
        // Legacy thread/read can omit harness tool items. Read the exact owner's
        // complete history through its existing read-only revision fence.
        const complete = await runtime.ipc.completeHistory(threadId);
        if (
          complete.id !== threadId ||
          complete.hostId !== 'local' ||
          !runtime.ipc.hasOwner(threadId)
        )
          throw new RuntimeError(503, '完整历史的原 owner 已变化，请重试', 'DESKTOP_OWNER_CHANGED');
        const messages = desktopTurns(complete).flatMap((turn) =>
          list(turn.items).flatMap((value) => {
            const wire = object(value);
            if (['todo-list', 'planImplementation', 'steered'].includes(string(wire.type)))
              return [];
            const item =
              wire.type === 'steeringUserMessage'
                ? {
                    ...wire,
                    type: 'userMessage',
                    id: wire.serverUserMessageId || wire.id,
                    content: wire.input,
                  }
                : wire;
            const { paths, ...message } = renderItem({ ...item, runId: turn.turnId }, Infinity);
            if (message.kind === 'reasoning' && !message.text) return [];
            const cached = thread.messages.find((v) => v.id === message.id);
            return [
              {
                ...message,
                files: [
                  ...new Set([
                    ...paths
                      .map((path) => path.replace(/\\/g, '/').split('/').at(-1)!)
                      .filter(Boolean),
                    ...(cached?.files ?? []),
                  ]),
                ],
                attachments: cached?.attachments ?? [],
              },
            ];
          }),
        );
        if (
          messages.reduce(
            (changed, message) =>
              this.registerMessageReferences(thread, message, string(complete.cwd)) || changed,
            false,
          )
        )
          this.saveBackground();
        return {
          hostId: this.hostId,
          threadId,
          messages,
          nextCursor: null,
          readTarget: this.readingTarget(),
        };
      }
    }
    const page = await runtime.reader.history(threadId, cursor, true);
    if (
      page.messages.reduce(
        (changed, message) =>
          this.registerMessageReferences(thread, message, page.directory) || changed,
        false,
      )
    )
      this.saveBackground();
    return { ...page, readTarget: this.readingTarget() };
  }
  async refreshHistory(threadId: string, cursor?: string, windowed = false) {
    if (
      this.mode === 'shared-runtime' &&
      !cursor &&
      !this.state.threads.some((thread) => thread.id === threadId)
    )
      return this.open(threadId, cursor, undefined, windowed);
    const thread = this.thread(threadId);
    if (
      windowed &&
      this.runtime instanceof DesktopRuntime &&
      cursor?.startsWith('desktop-page-v1:')
    )
      return this.history(threadId, true, cursor);
    const previousFirst =
      cursor && windowed && this.runtime instanceof DesktopRuntime
        ? thread.messages[0]?.id
        : undefined;
    if (!cursor && this.runtime instanceof DesktopRuntime)
      this.runtime.ipc.refreshSnapshot(threadId, false);
    const result = await this.open(threadId, cursor, undefined, windowed);
    if (!windowed || !(this.runtime instanceof DesktopRuntime)) return result;
    const before = previousFirst
      ? 'desktop-page-v1:' +
        Buffer.from(JSON.stringify([this.hostId, threadId, previousFirst])).toString('base64url')
      : undefined;
    return this.history(threadId, true, before);
  }
  async open(
    threadId: string,
    cursor?: string,
    target?: ReplyTarget,
    windowed = false,
    admitUnknown?: () => boolean,
  ) {
    const pending = this.restoring.get(threadId);
    if (pending) {
      await this.readWithReplyFence(target, () => pending);
      return cursor
        ? this.openNow(threadId, cursor, target, windowed, admitUnknown)
        : this.history(threadId);
    }
    if (this.loaded.has(threadId))
      return this.openNow(threadId, cursor, target, windowed, admitUnknown);
    const request = this.openNow(threadId, cursor, target, windowed, admitUnknown);
    this.restoring.set(threadId, request);
    try {
      return await this.readWithReplyFence(target, () => request);
    } finally {
      this.restoring.delete(threadId);
    }
  }
  private async recoverDesktopReceiptHistory(
    thread: Thread,
    runtime: DesktopRuntime,
    target?: ReplyTarget,
  ): Promise<void> {
    if (!this.state.receipts.some((r) => r.threadId === thread.id && r.state === 'uncertain'))
      return;
    const pending = this.desktopReceiptHistoryReads.get(thread.id);
    if (pending) {
      await this.readWithReplyFence(target, () => pending);
      return;
    }
    if ((this.desktopReceiptHistoryRetryAfter.get(thread.id) ?? 0) > Date.now()) return;
    this.desktopReceiptHistoryRetryAfter.set(thread.id, Date.now() + 30_000);
    const work = (async () => {
      try {
        const complete = await this.readWithReplyFence(target, () =>
          runtime.ipc.completeHistory(thread.id),
        );
        if (this.closed || this.runtime !== runtime || !runtime.ipc.hasOwner(thread.id)) return;
        await this.readWithReplyFence(target, () => this.desktopSnapshot(thread, complete));
      } catch {
        // Missing/oversized old pages are not evidence of rejection or completion.
        // Keep the original receipt; a later explicit history read may retry the read.
        if (target) this.assertReplyOpen(target);
      }
    })();
    this.desktopReceiptHistoryReads.set(thread.id, work);
    try {
      await work;
    } finally {
      if (this.desktopReceiptHistoryReads.get(thread.id) === work)
        this.desktopReceiptHistoryReads.delete(thread.id);
    }
  }
  private async openNow(
    threadId: string,
    cursor?: string,
    target?: ReplyTarget,
    windowed = false,
    admitUnknown?: () => boolean,
  ) {
    if (target) this.assertReplyOpen(target);
    this.requireReady();
    const runtime = this.runtime;
    if (runtime instanceof DesktopRuntime) {
      // Browsing a known chat must not spend the command discovery budget on
      // an unloaded owner before returning fresh history. Commands and unknown
      // targets retain full discovery and all owner checks.
      const discoveryTimeoutMs =
        windowed && !target && this.state.threads.some((thread) => thread.id === threadId)
          ? 1000
          : 15_000;
      const state = await this.readWithReplyFence(target, async () => {
        const discovery = runtime.ipc.follow(threadId, discoveryTimeoutMs);
        if (discoveryTimeoutMs !== 1000) return discovery;
        // A command may already own a longer coalesced discovery. Bound only
        // this reader's wait, leaving that original discovery/command intact.
        let timer: ReturnType<typeof setTimeout> | undefined;
        try {
          return await Promise.race([
            discovery,
            new Promise<undefined>((resolve) => {
              timer = setTimeout(() => resolve(undefined), 1000);
            }),
          ]);
        } finally {
          if (timer) clearTimeout(timer);
        }
      }).catch((error: unknown) => {
        if (windowed && error instanceof RuntimeError && error.status === 503) return undefined;
        throw error;
      });
      if (state && runtime.ipc.hasOwner(threadId)) runtime.reader.registerOwnerThread(threadId);
      let thread = this.state.threads.find((entry) => entry.id === threadId);
      // Agent-created desktop chats may have no user event yet and are omitted
      // from thread/list. Only a validated live owner can introduce such a chat.
      if (!thread) {
        if (
          !state ||
          state.id !== threadId ||
          state.hostId !== 'local' ||
          !runtime.ipc.hasOwner(threadId) ||
          (admitUnknown && !admitUnknown())
        )
          throw new RuntimeError(404, '对话不属于当前运行时');
        const cwd = string(state.cwd);
        const projectIds = this.state.projects
          .filter(
            (project) =>
              isAbsolute(cwd) && project.roots.some((root) => resolve(root) === resolve(cwd)),
          )
          .map((project) => project.id);
        thread = {
          id: threadId,
          hostId: this.hostId,
          projectId: projectIds.length === 1 ? projectIds[0]! : null,
          projectIds,
          title: string(state.title) || '未命名对话',
          state: 'unknown',
          turnId: null,
          messages: [],
          plan: [],
          error: null,
        };
        this.state.threads.push(thread);
      }
      if (state && !cursor) {
        await this.readWithReplyFence(target, () => this.desktopSnapshot(thread, state));
        // Reading the latest mobile window must not expand the owner's entire
        // history just to reconcile an old unknown submission. Keep it unknown.
        if (!windowed) await this.recoverDesktopReceiptHistory(thread, runtime, target);
      } else {
        const localImagePaths = new Map<string, readonly string[]>();
        const page = await this.readWithReplyFence(target, () =>
          runtime.reader.history(threadId, cursor, false, (id, paths) =>
            localImagePaths.set(id, paths),
          ),
        ).catch((error: unknown) => {
          if (!cursor) this.desktopHistoryReads.delete(threadId);
          throw error;
        });
        if (!cursor) {
          this.desktopHistoryReads.set(threadId, Date.now());
          this.desktopHistoryTips.set(threadId, string(page.messages.at(-1)?.runId));
          const latest = page.latestTurn;
          thread.lastTurnStatus =
            latest &&
            latest.id === page.messages.at(-1)?.runId &&
            ['inProgress', 'completed', 'failed', 'interrupted'].includes(latest.status)
              ? latest.status
              : undefined;
          this.reconcileReadReceiptEvidence(thread, page.turns ?? []);
          if (
            !page.turns?.length &&
            this.state.receipts.some((v) => v.threadId === threadId && v.state === 'uncertain') &&
            (this.readReceiptRetryAt.get(threadId) ?? 0) <= Date.now()
          ) {
            this.readReceiptRetryAt.set(threadId, Date.now() + 30_000);
            while (this.readReceiptRetryAt.size > 2048)
              this.readReceiptRetryAt.delete(this.readReceiptRetryAt.keys().next().value!);
            try {
              const evidence = await this.readWithReplyFence(target, () =>
                runtime.reader.receiptEvidence(threadId),
              );
              this.reconcileReadReceiptEvidence(thread, evidence);
            } catch {
              // Unavailable history cannot settle or replay an old operation.
              if (target) this.assertReplyOpen(target);
            }
          }
        }
        if (page.directory) thread.historyDirectory = page.directory;
        const previous = thread.messages;
        runtime.ipc.bindObservation(threadId, page.messages);
        if (!state) await runtime.ipc.refreshObservation(threadId);
        const observation = runtime.ipc.observation(threadId);
        const continuousLegacy =
          !cursor &&
          page.historyMode === 'legacy' &&
          observation?.seeded === true &&
          observation.items.some((v) => v.turnId === this.desktopHistoryTips.get(threadId));
        const messages: Message[] = [];
        for (const value of page.messages) {
          const message: Message = { ...value, attachments: [] };
          // History carries newly rendered local links, not the old rewritten
          // attachment URLs. Inspect them afresh through the same live boundary.
          await this.projectMessageAttachments(
            thread,
            message,
            [
              ...(localImagePaths.get(value.id) ?? []),
              ...messageLinks(message.text).flatMap((link) => {
                const path = localMessagePath(link.reference);
                return path ? [path] : [];
              }),
            ],
            {},
            page.directory,
          );
          // Non-link media (for example an uploaded image) is not present in
          // the history renderer. Preserve only those existing media entries.
          const old = previous.find((entry) => entry.id === value.id);
          const oldLinkIds = new Set(
            messageLinks(old?.text ?? '').map((link) =>
              link.reference.replace(/^codex-attachment:\/\//, ''),
            ),
          );
          for (const file of old?.attachments ?? []) {
            if (
              !oldLinkIds.has(file.id) &&
              !message.attachments.some((entry) => entry.id === file.id) &&
              !(
                file.resourceId &&
                message.attachments.some((entry) => entry.resourceId === file.resourceId)
              )
            )
              message.attachments.push(file);
          }
          messages.push(message);
        }
        const ids = new Set(messages.map((v) => v.id));
        const retained = cursor
          ? previous.filter((v) => !ids.has(v.id))
          : retainedDesktopHistory(
              previous,
              messages,
              new Set(),
              new Set([
                ...(page.turns ?? [])
                  .filter((turn) => turn.status === 'completed')
                  .map((turn) => turn.id),
                ...(page.latestTurn?.status === 'completed' ? [page.latestTurn.id] : []),
              ]),
            );
        const anchor = previous.findIndex((v) => ids.has(v.id));
        // A fresh latest page is the authoritative tip; retained desktop-only
        // tools must not trail it. Earlier pages keep their existing position.
        thread.messages = !cursor
          ? [...retained, ...messages]
          : anchor >= 0
            ? [
                ...previous.slice(0, anchor).filter((v) => !ids.has(v.id)),
                ...messages,
                ...previous.slice(anchor).filter((v) => !ids.has(v.id)),
              ]
            : [...messages, ...retained];
        if (continuousLegacy) {
          const ids = new Set([
            ...(this.verifiedDesktopMessageIds.get(threadId) ?? []),
            ...observation.items.map((v) => string(v.item.id)),
          ]);
          thread.messages = previous.filter((v) => ids.has(v.id));
        }
        thread.historyCursor = page.nextCursor;
        // A concurrently loaded history page must survive an in-flight live projection.
        const projection = this.desktopProjections.get(threadId);
        if (projection) projection.version++;
        if (!state) {
          thread.state = 'unknown';
          thread.turnId = null;
          thread.plan = [];
          thread.proposedPlan = undefined;
          thread.collaborationMode = undefined;
          thread.runtimeModel = undefined;
          thread.runtimeEffort = undefined;
          thread.permissionMode = undefined;
          thread.speed = undefined;
          this.projectDesktopObservation(thread);
        }
        // The bounded mobile wait does not cancel desktop discovery. Its live
        // snapshot may arrive during this slower history/attachment read.
        // Reapply that verified owner instead of letting the fallback erase it.
        const current = !cursor && runtime.ipc.hasOwner(threadId) && runtime.ipc.state(threadId);
        if (current) await this.desktopSnapshot(thread, current);
        // Legacy live rows and a late owner select the body actually returned.
        if (!cursor) {
          const source = page.messages.find((message) => message.id === thread.readHead?.messageId);
          if (source) this.projectReadHead(thread, source);
        }
      }
      this.loaded.add(threadId);
      if (!cursor) await runtime.ipc.refreshModelSettings(threadId);
      await this.refreshNativeQueue(threadId);
      this.save(true);
      return this.history(threadId);
    }
    // The service intentionally omits zero-turn metadata from its interactive
    // catalog. An explicit open still verifies that exact ID and authorized cwd.
    if (this.mode === 'shared-runtime') {
      const metadata = object(
        (
          await this.readWithReplyFence(target, () =>
            runtime.call('thread/read', { threadId, includeTurns: false }),
          )
        ).thread,
      );
      if (metadata.id !== threadId || !isAbsolute(string(metadata.cwd)))
        throw new RuntimeError(404, '共享服务没有返回准确对话');
      this.allowedPath(resolve(string(metadata.cwd)));
      if (!this.state.threads.some((v) => v.id === threadId))
        this.state.threads.push({
          id: threadId,
          hostId: this.hostId,
          projectId: string(metadata.projectId) || null,
          title: string(metadata.name) || string(metadata.preview) || '新对话',
          state: 'unknown',
          turnId: null,
          messages: [],
          plan: [],
          error: null,
        });
    }
    const thread = this.thread(threadId);
    if (cursor && this.loaded.has(threadId)) {
      if (cursor !== thread.historyCursor) throw new RuntimeError(409, '历史游标已变化，请刷新');
      const page = await this.readWithReplyFence(target, () =>
        runtime.call('thread/items/list', {
          threadId: thread.codexId ?? threadId,
          limit: 100,
          cursor,
          sortDirection: 'desc',
        }),
      );
      const current = thread.messages;
      thread.messages = [];
      for (const entry of list(page.data).reverse())
        await this.readWithReplyFence(target, () =>
          this.upsert(thread, { ...object(object(entry).item), runId: object(entry).turnId }),
        );
      thread.messages.push(
        ...current.filter((v) => !thread.messages.some((older) => older.id === v.id)),
      );
      thread.historyCursor = string(page.nextCursor) || null;
      this.save();
      return this.history(threadId);
    }
    if (!this.loaded.has(threadId) || this.mode === 'shared-runtime') {
      const sharedVersion = this.sharedVersions.get(threadId) ?? 0;
      let result: Record<string, unknown>;
      let recreatedEmpty = false;
      try {
        const sharedRead =
          this.mode === 'shared-runtime'
            ? await this.readWithReplyFence(target, () =>
                runtime.call('thread/read', {
                  threadId,
                  includeTurns: true,
                }),
              )
            : undefined;
        result =
          sharedRead &&
          (thread.archived ||
            (this.loaded.has(threadId) &&
              object(object(sharedRead.thread).status).type !== 'notLoaded'))
            ? sharedRead
            : await this.readWithReplyFence(target, () =>
                runtime.call('thread/resume', { threadId: thread.codexId ?? threadId }),
              );
      } catch (error) {
        const neverSubmitted = !this.state.receipts.some(
          (r) => r.threadId === threadId && r.state !== 'received',
        );
        if (
          this.mode !== 'managed-runtime' ||
          thread.messages.length ||
          !neverSubmitted ||
          !(error instanceof CodexRejection) ||
          !error.message.includes('no rollout found')
        )
          throw error;
        const project = this.state.projects.find((p) => p.id === thread.projectId);
        if (thread.projectId && !project) throw new RuntimeError(404, '原项目已不可用');
        const permissions = await this.readWithReplyFence(target, () =>
          this.computerPermissions(project?.roots[0] ?? this.options.root, target),
        );
        result = await this.readWithReplyFence(target, () =>
          runtime.call('thread/start', {
            cwd: project?.roots[0] ?? this.options.root,
            ...threadPermissionSettings(permissions),
          }),
        );
        const recovered = string(object(result.thread).id);
        if (!recovered) throw new RuntimeError(502, '空对话恢复未得到有效标识');
        thread.codexId = recovered;
        recreatedEmpty = true;
        this.save();
      }
      let turns = list(object(result.thread).turns).map(object);
      const metadata = object(result.thread);
      if (string(result.model ?? metadata.model))
        thread.runtimeModel = string(result.model ?? metadata.model);
      if (this.mode === 'shared-runtime' && Object.hasOwn(metadata, 'reasoningEffort'))
        thread.runtimeEffort =
          typeof metadata.reasoningEffort === 'string' ? metadata.reasoningEffort : null;
      if (typeof result.reasoningEffort === 'string' || result.reasoningEffort === null)
        thread.runtimeEffort = result.reasoningEffort;
      if (Object.hasOwn(result, 'approvalPolicy'))
        thread.permissionMode = currentPermissionMode(result);
      if (Object.hasOwn(result, 'serviceTier')) thread.speed = currentSpeed(result.serviceTier);
      const collaboration = object(result.collaborationMode);
      if (['plan', 'default'].includes(string(collaboration.mode)))
        thread.collaborationMode = string(collaboration.mode);
      const projected =
        this.mode === 'shared-runtime' ? { ...thread, messages: [] as Message[] } : thread;
      projected.messages = [];
      for (const turn of turns)
        for (const item of list(turn.items))
          await this.readWithReplyFence(target, () =>
            this.upsert(projected, { ...object(item), runId: turn.id }),
          );
      if (object(result.thread).historyMode === 'paginated' && !recreatedEmpty) {
        const turnPage = await this.readWithReplyFence(target, () =>
          runtime.call('thread/turns/list', {
            threadId: thread.codexId ?? threadId,
            limit: 20,
            sortDirection: 'desc',
          }),
        );
        turns = list(turnPage.data).reverse().map(object);
        const page = await this.readWithReplyFence(target, () =>
          runtime.call('thread/items/list', {
            threadId: thread.codexId ?? threadId,
            limit: 100,
            sortDirection: 'desc',
          }),
        );
        for (const entry of list(page.data).reverse())
          await this.readWithReplyFence(target, () =>
            this.upsert(projected, { ...object(object(entry).item), runId: object(entry).turnId }),
          );
        thread.historyCursor = string(page.nextCursor) || null;
      }
      if (this.mode === 'shared-runtime') {
        const changed = sharedVersion !== (this.sharedVersions.get(threadId) ?? 0);
        thread.messages = mergeSharedMessages(
          projected.messages,
          thread.messages,
          this.sharedLiveItems.get(threadId) ?? new Set(),
          changed,
        );
        if (changed) {
          // Official events received while reading are newer than that snapshot.
          this.loaded.add(threadId);
          this.save();
          return this.history(threadId);
        }
      }
      const active = turns.find((turn) => turn.status === 'inProgress');
      thread.turnId = active ? string(active.id) : null;
      thread.state = active ? 'running' : 'idle';
      const last = turns.at(-1);
      if (this.mode === 'shared-runtime') {
        const status = string(last?.status);
        thread.lastTurnStatus = ['inProgress', 'completed', 'failed', 'interrupted'].includes(
          status,
        )
          ? status
          : undefined;
        if (last) thread.notificationLastTurn = { id: string(last.id), outcome: status };
        thread.state = active ? 'running' : status === 'failed' ? 'failed' : 'idle';
      }
      const proposed =
        list(last?.items)
          .map(object)
          .findLast((v) => v.type === 'plan') ??
        (this.mode === 'shared-runtime'
          ? projected.messages.findLast((item) => item.runId === last?.id && item.kind === 'plan')
          : undefined);
      thread.proposedPlan =
        !active && last?.status === 'completed' && proposed && string(proposed.text)
          ? { id: string(proposed.id), turnId: string(last.id), text: string(proposed.text) }
          : undefined;
      if (thread.turnId) this.startedTurns.add(thread.turnId);
      for (const receipt of this.state.receipts.filter(
        (r) => r.threadId === threadId && r.state === 'uncertain',
      )) {
        const turn = turns.find(
          (v) =>
            v.id === receipt.turnId ||
            list(v.items).some((item) => object(item).clientId === receipt.clientMessageId),
        );
        if (turn) {
          receipt.turnId = string(turn.id);
          receipt.state = turn.status === 'inProgress' ? 'submitted' : string(turn.status);
        }
      }
      this.loaded.add(threadId);
      this.save();
    }
    return this.history(threadId);
  }

  isThreadArchived(id: string): boolean {
    return this.state.threads.some((thread) => thread.id === id && thread.archived);
  }

  private managementBroker(): WorkspaceManagementBroker {
    this.requireReady();
    if (!(this.runtime instanceof DesktopRuntime))
      throw new RuntimeError(409, '此连接尚未支持项目删除和对话归档');
    if (this.workspaceManagement) return this.workspaceManagement;
    const runtime = this.runtime;
    const home =
      process.platform === 'win32'
        ? (process.env.CODEX_HOME ?? join(homedir(), '.codex'))
        : dirname(dirname(this.options.socket ?? join(homedir(), '.codex', 'ipc', 'ipc.sock')));
    const metadata = new DesktopWorkspaceManagement(
      () =>
        this.options.executable ??
        (process.platform === 'darwin'
          ? '/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex'
          : 'codex'),
      home,
      this.options.sharedWebsocketUrl,
    );
    this.state.workspaceManagement ??= {};
    this.workspaceManagement = new WorkspaceManagementBroker(
      this.readingTarget(),
      this.state.workspaceManagement,
      () => this.save(),
      async (action, id) => {
        const catalog = await runtime.reader.desktopWorkspace();
        if (action === 'projects:delete' && !catalog.projects.some((project) => project.id === id))
          throw new CodexRejection('项目已不在当前电脑列表中，请刷新后核对');
        if (action === 'threads:archive' && !catalog.threads.some((thread) => thread.id === id))
          throw new CodexRejection('对话已不在当前电脑列表中，请刷新后核对');
        await metadata.execute(action, id, async () => {
          this.requireReady();
        });
      },
      (action, id) => metadata.verify(action, id),
      (action, id) => {
        if (action === 'projects:delete') {
          this.state.projects = this.state.projects.filter((project) => project.id !== id);
          for (const thread of this.state.threads) {
            if (thread.projectId === id) thread.projectId = null;
            if (thread.projectIds)
              thread.projectIds = thread.projectIds.filter((projectId) => projectId !== id);
            if (thread.desktopCreationProjectId === id) thread.desktopCreationProjectId = null;
          }
        } else {
          const thread = this.state.threads.find((thread) => thread.id === id);
          if (thread) thread.archived = true;
        }
      },
    );
    this.save();
    return this.workspaceManagement;
  }
  manageWorkspace(request: WorkspaceManagementRequest): WorkspaceManagementOperation {
    try {
      return this.managementBroker().submit(request);
    } catch (error) {
      if (error instanceof CodexRejection) throw new RuntimeError(409, error.message);
      throw error;
    }
  }
  async workspaceManagementStatus(
    id: string,
    target: RuntimeTarget,
  ): Promise<WorkspaceManagementOperation> {
    try {
      return await this.managementBroker().status(id, target);
    } catch (error) {
      if (error instanceof CodexRejection) throw new RuntimeError(409, error.message);
      throw error;
    }
  }

  async createProject(name: string, directory: string, key: string) {
    this.requireReady();
    if (this.mode === 'desktop-runtime') throw new RuntimeError(409, '请在 Codex 中创建项目');
    if (!name.trim() || !key) throw new RuntimeError(400, '项目名称和操作 ID 必填');
    const root = this.allowedPath(resolve(this.options.root, directory));
    if (!statSync(root).isDirectory()) throw new RuntimeError(400, '项目目录不存在');
    if (this.mode === 'shared-runtime') {
      await this.runtime.call('project/create', {
        name: name.trim(),
        roots: [{ path: root }],
        idempotencyKey: key,
      });
      return this.workspace();
    }
    if (!this.state.projects.some((p) => p.id === key))
      this.state.projects.push({ id: key, hostId: this.hostId, name: name.trim(), roots: [root] });
    this.save();
    return this.workspace();
  }

  private async computerPermissions(
    cwd: string,
    target?: ReplyTarget,
  ): Promise<Record<string, unknown>> {
    const runtime = this.runtime;
    if (target) this.assertReplyOpen(target);
    if (runtime instanceof DesktopRuntime)
      return this.readWithReplyFence(target, () => runtime.defaultPermissions(cwd));
    const result = await this.readWithReplyFence(target, () =>
      runtime.call('config/read', { cwd, includeLayers: false }),
    );
    return configuredPermissionSettings(object(result.config), cwd);
  }

  async createThread(projectId: string | null, key: string) {
    this.requireReady();
    if (this.mode === 'desktop-runtime') throw new RuntimeError(409, '请在 Codex 中创建对话后刷新');
    if (this.state.creations[key]) {
      const existing = this.thread(this.state.creations[key]);
      if (existing.projectId !== projectId) throw new RuntimeError(409, '创建 ID 已用于其他项目');
      this.commitPending();
      return existing;
    }
    if (this.locks.has('create:' + key)) throw new RuntimeError(409, '对话正在创建');
    const project = this.state.projects.find((p) => p.id === projectId);
    if ((projectId !== null && !project) || !key)
      throw new RuntimeError(400, '请选择当前电脑上的项目或无项目对话');
    this.locks.add('create:' + key);
    try {
      const result = await this.runtime.call('thread/start', {
        cwd: project?.roots[0] ?? this.options.root,
        ...(this.mode === 'shared-runtime' ? { projectId } : {}),
        ...threadPermissionSettings(
          await this.computerPermissions(project?.roots[0] ?? this.options.root),
        ),
      });
      const id = string(object(result.thread).id);
      const thread: Thread = {
        id,
        hostId: this.hostId,
        projectId,
        title: '新对话',
        state: 'idle',
        turnId: null,
        messages: [],
        plan: [],
        error: null,
      };
      if (string(result.model)) thread.runtimeModel = string(result.model);
      if (typeof result.reasoningEffort === 'string' || result.reasoningEffort === null)
        thread.runtimeEffort = result.reasoningEffort;
      thread.permissionMode = currentPermissionMode(result);
      this.state.threads.unshift(thread);
      this.state.creations[key] = id;
      this.loaded.add(id);
      this.save();
      return this.history(id).thread;
    } finally {
      this.locks.delete('create:' + key);
    }
  }

  desktopCreationStatus() {
    if (!this.ready) return { available: false, reason: 'Codex 运行时未连接' };
    return (
      this.desktopCreation?.status() ?? {
        available: false,
        reason: '当前连接不是 Codex 客户端模式',
      }
    );
  }
  async refreshDesktopCreationAvailability(): Promise<void> {
    await this.desktopCreationDriver?.refresh();
  }
  private async forkSource(threadId: string, turnId?: string): Promise<ForkSource> {
    const source = this.thread(threadId);
    if (!(this.runtime instanceof DesktopRuntime))
      throw new CreationError(409, '当前连接不支持原桌面分支');
    const metadata = object(
      (await this.runtime.reader.read('thread/read', { threadId, includeTurns: false })).thread,
    );
    if (metadata.id !== threadId || typeof metadata.cwd !== 'string' || !isAbsolute(metadata.cwd))
      throw new CreationError(409, '源对话或工作目录尚未确认');
    // The directory comes exclusively from this Host's authoritative, already-known thread.
    // Remote callers cannot supply a path or select a repository by name.
    const cwd = realpathSync(metadata.cwd);
    if (!statSync(cwd).isDirectory()) throw new CreationError(409, '源对话目录不可用');
    if (turnId) {
      let turn: Record<string, unknown> | undefined;
      if (metadata.historyMode === 'paginated') {
        let cursor: string | undefined;
        const seen = new Set<string>();
        for (let page = 0; page < 100; page++) {
          const result = await this.runtime.reader.read('thread/turns/list', {
            threadId,
            limit: 100,
            sortDirection: 'desc',
            itemsView: 'notLoaded',
            ...(cursor ? { cursor } : {}),
          });
          turn = list(result.data)
            .map(object)
            .find((row) => row.id === turnId);
          if (turn || !result.nextCursor) break;
          cursor = string(result.nextCursor);
          if (!cursor || seen.has(cursor)) throw new CreationError(409, '源对话轮次分页无效');
          seen.add(cursor);
        }
      } else {
        const result = await this.runtime.reader.read('thread/read', {
          threadId,
          includeTurns: true,
        });
        turn = list(object(result.thread).turns)
          .map(object)
          .find((row) => row.id === turnId);
      }
      if (!turn || !['completed', 'interrupted', 'failed'].includes(string(turn.status)))
        throw new CreationError(409, '请选择源对话中已经结束的准确任务轮次');
    }
    return {
      cwd,
      projectId: source.projectId,
      title: source.title,
      ...(source.runtimeModel ? { model: source.runtimeModel } : {}),
      ...(source.runtimeEffort !== undefined ? { effort: source.runtimeEffort } : {}),
    };
  }
  async threadForkOptions(threadId: string): Promise<ThreadForkOptions> {
    this.thread(threadId);
    await this.desktopCreationDriver?.refresh();
    const available =
      this.ready &&
      !!this.desktopForks &&
      this.desktopCreationDriver?.openAvailable === true &&
      this.desktopCreationDriver.openValidationOnly !== true;
    if (!available)
      return {
        hostId: this.hostId,
        threadId,
        sameDirectoryAvailable: false,
        worktreeAvailable: false,
        reason: '当前电脑未提供原桌面分支能力',
        worktreeReason: '当前电脑未提供原桌面分支能力',
      };
    try {
      const source = await this.forkSource(threadId);
      const worktree = await inspectForkWorktree(source.cwd);
      return {
        hostId: this.hostId,
        threadId,
        sameDirectoryAvailable: true,
        worktreeAvailable: worktree.available,
        reason: null,
        worktreeReason: worktree.available
          ? null
          : '此目录不支持工作树分支，请检查是否为已有提交且无冲突的 Git 仓库；暂不支持子模块及稀疏索引',
      };
    } catch {
      return {
        hostId: this.hostId,
        threadId,
        sameDirectoryAvailable: false,
        worktreeAvailable: false,
        reason: '源对话目录不可用，请刷新',
        worktreeReason: '源对话目录不可用，请刷新',
      };
    }
  }
  async forkThread(request: ThreadForkRequest): Promise<ThreadForkOperation> {
    if (request.hostId !== this.hostId) throw new CreationError(403, '分支电脑身份不一致');
    if (!this.desktopForks) throw new CreationError(409, '当前连接不支持原桌面分支');
    // A repeated operation only returns its receipt, even if availability has since changed.
    const old = this.desktopForks.existing(request);
    if (old) return old;
    const options = await this.threadForkOptions(request.threadId);
    if (!options.sameDirectoryAvailable)
      throw new CreationError(409, options.reason ?? '分支不可用');
    if (request.environment === 'worktree' && !options.worktreeAvailable)
      throw new CreationError(409, options.worktreeReason ?? '此目录不能创建工作树');
    const source = await this.forkSource(request.threadId, request.turnId);
    return this.desktopForks.submit(request, source);
  }
  async threadForkStatus(threadId: string, id: string): Promise<ThreadForkOperation> {
    if (!this.desktopForks) throw new CreationError(409, '当前连接不支持原桌面分支');
    return this.desktopForks.query(threadId, id);
  }
  private async verifyDesktopFork(record: ThreadForkOperation, cwd: string): Promise<boolean> {
    if (!(this.runtime instanceof DesktopRuntime) || !record.forkedThreadId || !this.ready)
      return false;
    const id = record.forkedThreadId;
    const owner = await this.runtime.ipc.follow(id, 1000, true);
    if (
      !owner ||
      owner.id !== id ||
      owner.hostId !== 'local' ||
      !this.runtime.ipc.hasOwner(id) ||
      typeof owner.cwd !== 'string' ||
      realpathSync(owner.cwd) !== realpathSync(cwd)
    )
      return false;
    const turns = desktopTurns(owner);
    const inherited = turns.findIndex((turn) => (turn.turnId ?? turn.id) === record.turnId);
    // The official metadata fork already verified its source and exact cutoff.
    // A later query may observe legitimate new turns executed by the original owner.
    if (inherited < 0) return false;
    await this.open(id);
    const fork = this.thread(id);
    fork.projectId = record.projectId;
    fork.projectIds = record.projectId ? [record.projectId] : [];
    fork.desktopCreationProjectId = record.projectId;
    this.save();
    this.emit(id, 'desktop-forked');
    return true;
  }
  prepareDesktopCreation(input: CreationInput) {
    this.requireReady();
    if (!this.desktopCreation) throw new CreationError(409, '当前连接不是 Codex 客户端模式');
    const project = this.state.projects.find((p) => p.id === input.projectId);
    if (input.projectId !== null && !project)
      throw new CreationError(404, '目标项目不存在，请刷新');
    const projectTarget = this.desktopCreationDriver?.projectTarget;
    if (project && !projectTarget && project.roots.length !== 1)
      throw new CreationError(409, '此项目没有唯一根目录，暂不支持深链接创建');
    let directory: string | null;
    let nativeProjectId: string | undefined;
    try {
      if (project && projectTarget) {
        ({ directory, nativeProjectId } = projectTarget.call(
          this.desktopCreationDriver,
          project.name,
          project.roots,
          project.id,
        ));
      } else directory = project ? realpathSync(project.roots[0]!) : null;
    } catch {
      throw new CreationError(409, 'Codex 项目目录尚未确认，请刷新项目');
    }
    if (directory && !statSync(directory).isDirectory())
      throw new CreationError(409, '项目目录不可用');
    if (
      directory &&
      !projectTarget &&
      this.desktopCreationDriver?.projectAssignment !== 'app-server' &&
      this.state.projects.some(
        (p) =>
          p.id !== project?.id &&
          p.roots.some((root) => {
            try {
              return realpathSync(root) === directory;
            } catch {
              return false;
            }
          }),
      )
    )
      throw new CreationError(409, '多个项目共享目录，不能确定深链接目标');
    return this.creationView(
      this.desktopCreation.prepare(input, {
        hostId: this.hostId,
        projectId: input.projectId,
        directory,
        ...(nativeProjectId ? { nativeProjectId } : {}),
        existingThreadIds: this.state.threads.map((t) => t.id),
      }),
    );
  }
  uploadDesktopCreation(id: string, attachmentId: string, bytes: Buffer) {
    if (!this.desktopCreation) throw new CreationError(409, '当前连接不是 Codex 客户端模式');
    return {
      hostId: this.hostId,
      clientOperationId: id,
      ...this.desktopCreation.upload(id, attachmentId, bytes),
    };
  }
  async inspectDesktopCreation(id: string) {
    if (!this.desktopCreation) throw new CreationError(409, '当前连接不是 Codex 客户端模式');
    return this.creationView(await this.desktopCreation.inspect(id));
  }
  async queryDesktopCreation(id: string) {
    if (!this.desktopCreation) throw new CreationError(409, '当前连接不是 Codex 客户端模式');
    return this.creationView(await this.desktopCreation.query(id));
  }
  detachDesktopCreation(id: string, acknowledgeUncertain: boolean) {
    if (!this.desktopCreation) throw new CreationError(409, '当前连接不是 Codex 客户端模式');
    return this.creationView(this.desktopCreation.detach(id, acknowledgeUncertain));
  }
  async submitDesktopCreation(id: string) {
    this.requireReady();
    await this.desktopCreationDriver?.refresh();
    if (!this.desktopCreation) throw new CreationError(409, '当前连接不是 Codex 客户端模式');
    return this.creationView(
      await this.desktopCreation.submit(id, this.desktopCreationDriver?.backgroundSubmission),
    );
  }
  async activateDesktopThread(threadId: string, id: string) {
    const bridge = this.desktopCreationDriver;
    if (!bridge?.open) throw new RuntimeError(409, '当前连接未提供 Codex 打开入口');
    if (!/^[a-zA-Z0-9_-]{8,100}$/.test(id)) throw new RuntimeError(400, '操作 ID 无效');
    const old = this.openOperations.get(id);
    if (old && old.threadId !== threadId) throw new RuntimeError(409, '操作 ID 已用于其他对话');
    if (!this.state.threads.some((thread) => thread.id === threadId)) {
      const prepared = await this.desktopCreation?.preparedThreadMetadata(threadId);
      if (prepared) {
        this.rememberDesktopThread(prepared);
        this.save();
      }
    }
    this.thread(threadId);
    if (old && ['opening', 'opened'].includes(old.phase)) return old;
    const operation = old ?? {
      hostId: this.hostId,
      threadId,
      clientOperationId: id,
      phase: 'opening',
      error: null,
    };
    operation.phase = 'opening';
    operation.error = null;
    this.openOperations.set(id, operation);
    const prior = this.openingThreads.get(threadId);
    const work = prior ?? bridge.open(threadId, id);
    if (!prior) {
      this.openingThreads.set(threadId, work);
      void work.finally(() => this.openingThreads.delete(threadId)).catch(() => {});
    }
    void work
      .then(async (opened) => {
        operation.phase = opened ? 'opened' : 'outcome-unknown';
        if (opened) {
          await this.open(threadId);
          this.emit(threadId, 'desktop-opened');
        }
      })
      .catch(() => {
        operation.phase = 'outcome-unknown';
        operation.error = 'Codex 打开结果待确认，请刷新';
      });
    return operation;
  }
  async desktopOpenStatus(threadId: string, id: string) {
    const operation = this.openOperations.get(id);
    if (!operation || operation.threadId !== threadId)
      throw new RuntimeError(404, '打开操作不存在');
    if (
      operation.phase === 'outcome-unknown' &&
      this.ready &&
      this.runtime instanceof DesktopRuntime
    ) {
      // A renderer can resume after the original URI confirmation window.
      // Reconcile only from the exact original owner's fresh snapshot; a
      // status query never repeats navigation or submits any model input.
      const state = await this.runtime.ipc.follow(threadId, 1000, true);
      if (
        state?.id === threadId &&
        state.hostId === 'local' &&
        this.runtime.ipc.hasOwner(threadId) &&
        operation.phase === 'outcome-unknown'
      ) {
        operation.phase = 'opened';
        operation.error = null;
        await this.open(threadId);
        this.emit(threadId, 'desktop-opened');
      }
    }
    return operation;
  }
  private async submitCreationInput(
    plan: import('./desktop-creation.js').DesktopCreationPlan,
    id: string,
    boundary?: CreationInputBoundary,
    preparationFingerprint?: string,
  ): Promise<void> {
    if (!(this.runtime instanceof DesktopRuntime)) throw new RuntimeError(409, 'Codex 未连接');
    const runtime = this.runtime;
    plan = structuredClone(plan);
    const target = replyTarget(this.readingTarget());
    this.assertReplyOpen(target);
    const attachments: readonly Attachment[] = Object.freeze(
      plan.files.map((file) =>
        Object.freeze({
          ...file,
          id: createHash('sha256')
            .update(plan.clientOperationId + ':' + file.id)
            .digest('hex')
            .slice(0, 32),
          hostId: this.hostId,
          threadId: id,
        }),
      ),
    );
    const reply: Reply = Object.freeze({
      hostId: this.hostId,
      threadId: id,
      clientMessageId: 'first-' + plan.clientOperationId,
      mode: 'next-turn',
      text: plan.text,
      attachmentIds: Object.freeze(attachments.map((file) => file.id)) as unknown as string[],
      ...(plan.collaborationMode ? { collaborationMode: plan.collaborationMode } : {}),
      ...(plan.model ? { model: plan.model } : {}),
      ...(plan.effort ? { effort: plan.effort } : {}),
      ...(plan.permissionMode ? { permissionMode: plan.permissionMode } : {}),
      ...(plan.speed ? { speed: plan.speed } : {}),
    });
    const fingerprint = replyIntentFingerprint(reply, target);
    const messageId = reply.clientMessageId;
    const original = boundary?.originalCheckpoint;
    if (
      original &&
      (original.version !== 1 ||
        original.hostId !== this.hostId ||
        !Number.isSafeInteger(original.writerPid) ||
        original.writerPid <= 0 ||
        original.preparationFingerprint !== preparationFingerprint ||
        original.replyFingerprint !== fingerprint ||
        original.threadId !== id ||
        original.clientMessageId !== messageId ||
        JSON.stringify(replyTarget(original.target)) !== JSON.stringify(target))
    )
      throw new RuntimeError(409, '原首条输入的完整目标或不可变内容已变化');
    const pending = this.replySubmissions.get(messageId);
    if (pending) {
      if (pending.fingerprint !== fingerprint)
        throw new RuntimeError(409, '原首条输入已绑定不同内容');
      const receipt = await pending.work;
      if (!['submitted', 'completed'].includes(receipt.state))
        throw new RuntimeError(409, '首条输入结果待确认');
      return;
    }
    const old = this.state.receipts.find((value) => value.clientMessageId === messageId);
    if (
      old &&
      (old.fingerprintVersion !== 2 ||
        old.fingerprint !== fingerprint ||
        JSON.stringify(old.target) !== JSON.stringify(target))
    )
      throw new RuntimeError(409, '原首条输入已绑定不同或未证明的目标');
    const retryReceipt =
      old?.state === 'failed' &&
      boundary &&
      old.creationInput?.state === 'not-written' &&
      old.creationInput.checkpoint.preparationFingerprint === preparationFingerprint &&
      old.creationInput.checkpoint.replyFingerprint === fingerprint &&
      old.creationInput.checkpoint.threadId === id &&
      old.creationInput.checkpoint.clientMessageId === messageId &&
      JSON.stringify(old.creationInput.checkpoint.target) === JSON.stringify(target)
        ? old
        : undefined;
    if (old && !retryReceipt) {
      if (!['submitted', 'completed'].includes(old.state))
        throw new RuntimeError(409, '原首条输入结果未知，禁止重发');
      return;
    }
    const attempt: FirstInputAttempt | undefined =
      boundary && preparationFingerprint
        ? {
            boundary,
            checkpoint: {
              version: 1,
              hostId: this.hostId,
              threadId: id,
              clientMessageId: messageId,
              preparationFingerprint,
              replyFingerprint: fingerprint,
              target,
              writerPid: process.pid,
              attemptId: randomUUID(),
            },
            stage: 'owner',
            boundaryAttempted: false,
            wrote: false,
            ...(retryReceipt ? { retryReceipt } : {}),
          }
        : undefined;
    const check = (stage: CreationInputStage) => {
      this.assertReplyOpen(target);
      if (attempt) {
        attempt.stage = stage;
        attempt.boundary.assertOpen(stage);
      }
    };
    // Own the exact first-ID synchronously, before owner/attachment/model reads can yield.
    const work = Promise.resolve().then(async () => {
      try {
        check('owner');
        await this.open(id, undefined, target);
        check('owner');
        const thread = this.thread(id);
        thread.projectId = plan.projectId;
        thread.projectIds = plan.projectId ? [plan.projectId] : [];
        thread.desktopCreationProjectId = plan.projectId;
        const owner = runtime.ipc.state(id);
        const initialization = desktopTurns(owner ?? {}).find((turn) =>
          list(turn.items).some((value) => {
            const item = object(value);
            return (
              item.type === 'functionCallOutput' &&
              typeof item.output === 'string' &&
              item.output.includes('创建标识 ' + plan.clientOperationId + '。真实首条请求')
            );
          }),
        );
        if (initialization) thread.desktopInitializationTurnId = string(initialization.turnId);
        if (owner) await this.desktopSnapshot(thread, owner);
        check('owner');
        check('attachments');
        for (const file of attachments) {
          const bytes = readFileSync(file.path);
          if (
            bytes.length !== file.sizeBytes ||
            createHash('sha256').update(bytes).digest('hex') !== file.sha256
          )
            throw new RuntimeError(409, '首条附件已变化');
          const existing = this.state.attachments.find((value) => value.id === file.id);
          if (
            existing &&
            (existing.hostId !== file.hostId ||
              existing.threadId !== id ||
              existing.path !== file.path ||
              existing.sha256 !== file.sha256 ||
              existing.sizeBytes !== file.sizeBytes)
          )
            throw new RuntimeError(409, '首条附件已绑定不同输入');
          if (!existing) this.state.attachments.push(file);
        }
        const receipt = await this.submitReplyNow(reply, target, fingerprint, attachments, attempt);
        check(attempt?.stage ?? 'execution');
        if (!['submitted', 'completed'].includes(receipt.state))
          throw attempt?.failure ?? new RuntimeError(409, '首条输入结果待确认');
        return receipt;
      } catch (error) {
        // This proof comes from this owned continuation and its unentered write hook,
        // not from current history, idle state, a 404 or missing receipts.
        if (
          attempt &&
          !attempt.wrote &&
          !attempt.boundaryAttempted &&
          !(error instanceof RuntimeSaveError) &&
          ['owner', 'attachments', 'generation'].includes(attempt.stage)
        ) {
          check(attempt.stage);
          const diagnostic = {
            stage: attempt.stage,
            code: attempt.stage + '-preflight-failed',
            disposition: 'retry-original-preflight' as const,
          };
          if (retryReceipt) {
            retryReceipt.creationInput = { state: 'not-written', checkpoint: attempt.checkpoint };
            this.save();
          }
          throw new CreationInputNotWritten(attempt.checkpoint, diagnostic);
        }
        throw error;
      }
    });
    this.replySubmissions.set(messageId, { fingerprint, threadId: id, target, work });
    try {
      await work;
    } finally {
      if (this.replySubmissions.get(messageId)?.work === work)
        this.replySubmissions.delete(messageId);
    }
  }
  private creationView(record: CreationRecord) {
    // Local paths, fingerprints and native transport details stay on the host.
    const thread =
      record.phase === 'confirmed' && record.threadId
        ? this.state.threads.find((t) => t.id === record.threadId)
        : undefined;
    return {
      hostId: this.hostId,
      clientOperationId: record.clientOperationId,
      projectId: record.projectId,
      phase: record.phase,
      threadId: record.phase === 'confirmed' ? record.threadId : null,
      error: record.error,
      uploaded: record.uploaded,
      ...(record.detached ? { detached: true } : {}),
      ...(record.preparation ? { preparation: structuredClone(record.preparation) } : {}),
      ...(thread ? { thread: this.history(thread.id).thread } : {}),
    };
  }
  private async verifyDesktopCreation(record: CreationRecord, id: string): Promise<boolean> {
    if (!(this.runtime instanceof DesktopRuntime) || !this.runtime.ready) return false;
    if (record.existingThreadIds.includes(id)) return false; // An existing chat cannot masquerade as a newly created one.
    const state = await this.runtime.ipc.follow(id);
    if (!state || state.id !== id || state.hostId !== 'local') return false;
    const metadata = object(
      (await this.runtime.reader.read('thread/read', { threadId: id, includeTurns: false })).thread,
    );
    if (record.projectId === null) {
      if (state.workspaceKind !== 'projectless' || metadata.projectId) return false;
    } else if (
      this.desktopCreationDriver?.projectAssignment !== 'desktop-sidebar' &&
      metadata.projectId !== record.projectId
    )
      return false;
    if (
      record.directory &&
      (typeof metadata.cwd !== 'string' || realpathSync(metadata.cwd) !== record.directory)
    )
      return false;
    const first = desktopTurns(state)
      .flatMap((turn) => list(turn.items).map(object))
      .find((item) => item.type === 'userMessage');
    const nativeInput = this.desktopCreationDriver?.inputTransport === 'desktop-ipc';
    if (
      !first ||
      (nativeInput
        ? !list(first.content).some(
            (input) => object(input).type === 'text' && object(input).text === record.text,
          ) && record.text !== ''
        : renderItem(first)?.text !== record.text)
    )
      return false;
    const inputs = list(first.content).map(object);
    for (const file of record.attachments) {
      const matching = inputs.some((input) => {
        const expected = creationAttachmentPath(
          join(this.options.directory, 'desktop-creations'),
          record,
          file,
        );
        const path =
          string(input.path) ||
          (nativeInput &&
          input.type === 'text' &&
          input.text ===
            `用户附件 ${JSON.stringify(file.name)}，已上传至本机 ${JSON.stringify(expected)}。按需要用工具读取该文件。`
            ? expected
            : '');
        if (!path) return false;
        try {
          const stat = statSync(path);
          if (!stat.isFile() || stat.size !== file.sizeBytes) return false;
          const bytes = readFileSync(path);
          return (
            bytes.length === file.sizeBytes &&
            createHash('sha256').update(bytes).digest('hex') === file.sha256
          );
        } catch {
          return false;
        }
      });
      if (!matching) return false;
    }
    let thread = this.state.threads.find((t) => t.id === id);
    if (!thread) {
      thread = {
        id,
        hostId: this.hostId,
        projectId: record.projectId,
        title: string(state.title) || '新对话',
        state: 'unknown',
        turnId: null,
        messages: [],
        plan: [],
        error: null,
      };
      this.state.threads.unshift(thread);
    }
    this.state.creations[record.clientOperationId] = id;
    thread.desktopCreationProjectId = record.projectId;
    thread.projectId = record.projectId;
    thread.projectIds = record.projectId ? [record.projectId] : [];
    this.runtime.reader.registerOwnerThread(id);
    await this.desktopSnapshot(thread, state);
    const userMessage = thread.messages.find((message) => message.role === 'user');
    for (const file of record.attachments) {
      const path = creationAttachmentPath(
        join(this.options.directory, 'desktop-creations'),
        record,
        file,
      );
      let attachment = this.state.attachments.find(
        (a) => a.threadId === id && a.path === path && a.sha256 === file.sha256,
      );
      if (!attachment) {
        attachment = { ...file, id: randomUUID(), hostId: this.hostId, threadId: id, path };
        this.state.attachments.push(attachment);
      }
      if (userMessage && !userMessage.attachments.some((a) => a.id === attachment.id))
        userMessage.attachments.push(this.cleanAttachment(attachment));
    }
    this.loaded.add(id);
    this.save();
    this.emit(id, 'desktop-created');
    return true;
  }

  private allowedPath(path: string): string {
    const actual = realpathSync(path);
    const roots = [realpathSync(this.options.root), realpathSync(this.options.directory)];
    if (
      !roots.some((root) => {
        const rel = relative(root, actual);
        return !rel.startsWith('..') && !isAbsolute(rel);
      })
    )
      throw new RuntimeError(403, '文件超出电脑授权目录');
    return actual;
  }
  upload(threadId: string, name: string, mediaType: string, data: Buffer, expectedHash: string) {
    this.thread(threadId);
    this.requireReady();
    if (
      !data.length ||
      data.length > 20 * 1024 * 1024 ||
      !name ||
      name.length > 200 ||
      Array.from(name).some((character) => character.charCodeAt(0) < 32) ||
      ['.', '..'].includes(basename(name)) ||
      !/^[a-z]+\/[a-z0-9.+-]+$/i.test(mediaType)
    )
      throw new RuntimeError(400, '文件或类型无效，大小须为 1 byte–20 MB');
    const sha256 = createHash('sha256').update(data).digest('hex');
    if (sha256 !== expectedHash) throw new RuntimeError(400, '文件完整性校验失败');
    const id = randomUUID();
    const path = join(
      this.options.directory,
      'attachments',
      id + extname(basename(name)).slice(0, 12),
    );
    writeFileSync(path, data, { mode: 0o600 });
    const attachment = {
      id,
      hostId: this.hostId,
      threadId,
      name: basename(name),
      mediaType,
      sizeBytes: data.length,
      sha256,
      path,
    };
    this.state.attachments.push(attachment);
    this.save();
    return this.cleanAttachment(attachment);
  }
  private attachmentRoots(_thread: Thread): string[] {
    // Original-desktop downloads use only this thread's registered resources.
    // The legacy root remains an explicit boundary for managed/shared runtimes.
    return this.runtime instanceof DesktopRuntime
      ? []
      : [this.options.root, this.options.directory];
  }
  private attachmentExactPaths(threadId: string): string[] {
    return this.state.attachments
      .filter((file) => file.threadId === threadId)
      .map((file) => file.path);
  }
  async download(threadId: string, id: string) {
    const thread = this.thread(threadId);
    const file = this.state.attachments.find((v) => v.id === id && v.threadId === threadId);
    if (!file) throw new RuntimeError(404, '附件不属于当前对话');
    try {
      const data = await this.attachmentFiles.read(
        file.path,
        this.attachmentRoots(thread),
        file.sha256,
        this.attachmentExactPaths(threadId),
        !!file.desktopMessageLinks?.length,
      );
      return { file: this.cleanAttachment(file), data };
    } catch (error) {
      appendRuntimeDiagnostic(
        this.options.directory,
        {
          scope: 'attachment',
          stage: 'download',
          hostId: this.hostId,
          threadId,
          resourceId: id,
          path: file.path,
        },
        error,
      );
      if (error instanceof AttachmentFileError)
        throw new RuntimeError(error.status, error.message, error.code);
      throw error;
    }
  }
  async resolveAttachment(threadId: string, id: string) {
    const thread = this.thread(threadId);
    const source = this.state.attachments.find((v) => v.id === id && v.threadId === threadId);
    if (!source) throw new RuntimeError(404, '附件不属于当前对话');
    try {
      // Resolve only an already registered path. Never change the old version or input receipt.
      const metadata = await this.attachmentFiles.inspect(
        source.path,
        this.attachmentRoots(thread),
        this.attachmentExactPaths(threadId),
        !!source.desktopMessageLinks?.length,
      );
      let current = this.state.attachments.find(
        (v) => v.threadId === threadId && v.path === metadata.path && v.sha256 === metadata.sha256,
      );
      if (!current) {
        current = { ...source, ...metadata, id: randomUUID() };
        this.state.attachments.push(current);
        this.save();
      }
      return {
        hostId: this.hostId,
        threadId,
        sourceAttachmentId: id,
        readTarget: this.readingTarget(),
        attachment: this.cleanAttachment(current),
      };
    } catch (error) {
      appendRuntimeDiagnostic(
        this.options.directory,
        {
          scope: 'attachment',
          stage: 'resolve-current',
          hostId: this.hostId,
          threadId,
          resourceId: id,
          path: source.path,
        },
        error,
      );
      if (error instanceof AttachmentFileError)
        throw new RuntimeError(error.status, error.message, error.code);
      if (['ENOENT', 'ENOTDIR'].includes(String(object(error).code)))
        throw new RuntimeError(404, '文件不存在或已被移除');
      throw error;
    }
  }
  private async upsert(
    thread: Thread,
    item: unknown,
    indexes?: Map<string, number>,
  ): Promise<void> {
    const rendered = renderItem(item);
    const questions = desktopQuestions([item]);
    if (questions.length) {
      rendered.text = questions.map((question) => question.title).join('\n\n');
      rendered.replyPhase = 'commentary';
      rendered.status = 'completed';
    }
    if (rendered.kind === 'reasoning' && !rendered.text) return;
    const attachments: Omit<Attachment, 'path'>[] = [];
    const { paths: _paths, ...rest } = rendered;
    if (rendered.kind === 'userMessage') {
      const clientId = string(object(item).clientId);
      const receipt = this.state.receipts.find(
        (v) => v.threadId === thread.id && v.clientMessageId === clientId,
      );
      if (receipt) {
        rest.text = receipt.reply.text;
        for (const id of receipt.reply.attachmentIds) {
          const file = this.state.attachments.find((v) => v.id === id);
          if (file && !attachments.some((v) => v.id === file.id))
            attachments.push(this.cleanAttachment(file));
        }
      }
    }
    const questionReplies = parseDesktopQuestionReply(rest.text);
    if (rest.role === 'user' && questionReplies.length)
      rest.text = questionReplies
        .map((reply) => `${string(reply.question)}\n${string(reply.answer)}`)
        .join('\n\n');
    const message: Message = { ...rest, attachments };
    const index = indexes
      ? (indexes.get(message.id) ?? -1)
      : thread.messages.findIndex((v) => v.id === message.id);
    if (index >= 0) thread.messages[index] = message;
    else {
      indexes?.set(message.id, thread.messages.length);
      thread.messages.push(message);
    }
    await this.projectMessageAttachments(thread, message, rendered.paths, item);
  }

  private registerMessageReferences(
    thread: Thread,
    message: Pick<Message, 'id' | 'role' | 'kind' | 'text'>,
    directory?: string,
    parsedLinks?: ReturnType<typeof messageLinks>,
  ): boolean {
    if (
      !(this.runtime instanceof DesktopRuntime) ||
      message.role !== 'assistant' ||
      message.kind !== 'agentMessage'
    )
      return false;
    const root =
      directory && isAbsolute(directory)
        ? directory
        : (thread.desktopDirectory ??
          thread.historyDirectory ??
          this.state.projects.find((p) => p.id === thread.projectId)?.roots[0] ??
          this.options.root);
    let changed = false;
    for (const link of parsedLinks ??
      (message.text.includes('](') ? messageLinks(message.text) : [])) {
      const local = localMessagePath(link.reference);
      if (!local) continue;
      const sources = (this.state.messageFileLinks ??= []);
      if (
        sources.some(
          (entry) =>
            entry.threadId === thread.id &&
            entry.messageId === message.id &&
            entry.reference === link.reference,
        )
      )
        continue;
      sources.push({
        threadId: thread.id,
        messageId: message.id,
        reference: link.reference,
        path: resolve(root, local),
      });
      if (sources.length > 4096) sources.shift();
      changed = true;
    }
    return changed;
  }

  private async registerMessageFile(
    thread: Thread,
    messageId: string,
    requested: string,
    reference?: string,
  ): Promise<Attachment> {
    try {
      let exactPath: string | undefined;
      if (reference !== undefined) {
        const previous = this.state.attachments.find(
          (file) =>
            file.threadId === thread.id &&
            file.desktopMessageLinks?.some(
              (link) => link.messageId === messageId && link.reference === reference,
            ),
        );
        exactPath = previous?.path ?? (await realpath(requested));
        if ((await realpath(requested)) !== exactPath)
          throw new AttachmentFileError(
            403,
            '文件链接的真实目标已变化，请在电脑上核对原消息',
            'RESOURCE_TARGET_CHANGED',
          );
      }
      const registrationRoots =
        this.runtime instanceof DesktopRuntime
          ? thread.desktopDirectory
            ? [thread.desktopDirectory]
            : []
          : this.attachmentRoots(thread);
      const metadata = await this.attachmentFiles.inspect(requested, registrationRoots, [
        ...this.attachmentExactPaths(thread.id),
        ...(exactPath ? [exactPath] : []),
      ]);
      if (exactPath && metadata.path !== exactPath)
        throw new AttachmentFileError(
          403,
          '文件链接的真实目标已变化，请在电脑上核对原消息',
          'RESOURCE_TARGET_CHANGED',
        );
      let file = this.state.attachments.find(
        (entry) =>
          entry.threadId === thread.id &&
          entry.path === metadata.path &&
          entry.sha256 === metadata.sha256,
      );
      if (!file) {
        const media: Record<string, string> = {
          '.png': 'image/png',
          '.jpg': 'image/jpeg',
          '.jpeg': 'image/jpeg',
          '.webp': 'image/webp',
          '.pdf': 'application/pdf',
          '.txt': 'text/plain',
          '.md': 'text/markdown',
        };
        file = {
          id: randomUUID(),
          hostId: this.hostId,
          threadId: thread.id,
          ...metadata,
          name: basename(metadata.path),
          mediaType: media[extname(metadata.path).toLowerCase()] ?? 'application/octet-stream',
        };
        this.state.attachments.push(file);
      }
      if (
        reference !== undefined &&
        !file.desktopMessageLinks?.some(
          (link) => link.messageId === messageId && link.reference === reference,
        )
      )
        (file.desktopMessageLinks ??= []).push({ messageId, reference });
      return file;
    } catch (error) {
      throw attachmentFileError(error);
    }
  }

  async resolveMessageFile(threadId: string, messageId: string, reference: string) {
    const thread = this.thread(threadId);
    if (!(this.runtime instanceof DesktopRuntime))
      throw new RuntimeError(409, '当前运行时不支持原消息文件解析', 'MESSAGE_FILE_UNSUPPORTED');
    const local = localMessagePath(reference);
    if (!local || reference.length > 8192)
      throw new RuntimeError(400, '不是有效的电脑文件链接', 'INVALID_FILE_LINK');
    const message = thread.messages.find((message) => message.id === messageId);
    if (message) this.registerMessageReferences(thread, message);
    const source = this.state.messageFileLinks?.find(
      (entry) =>
        entry.threadId === threadId &&
        entry.messageId === messageId &&
        localMessagePath(entry.reference) === local,
    );
    if (!source)
      throw new RuntimeError(
        403,
        '文件链接不属于这条电脑助手消息，请刷新原对话后重试',
        'MESSAGE_LINK_MISMATCH',
      );
    try {
      const file = await this.registerMessageFile(thread, messageId, source.path, source.reference);
      this.save();
      return {
        hostId: this.hostId,
        threadId,
        messageId,
        reference,
        readTarget: this.readingTarget(),
        attachment: this.cleanAttachment(file),
      };
    } catch (error) {
      const fileError = attachmentFileError(error);
      appendRuntimeDiagnostic(
        this.options.directory,
        {
          scope: 'attachment',
          stage: 'resolve-message-link',
          hostId: this.hostId,
          threadId,
          messageId,
          path: source.path,
        },
        fileError,
      );
      throw new RuntimeError(fileError.status, fileError.message, fileError.code);
    }
  }

  private async projectMessageAttachments(
    thread: Thread,
    message: Message,
    paths: string[],
    item: unknown,
    directory?: string,
  ): Promise<void> {
    const links = messageLinks(message.text);
    this.registerMessageReferences(thread, message, directory, links);
    const attachments = message.attachments;
    const root =
      directory ??
      thread.desktopDirectory ??
      this.state.projects.find((project) => project.id === thread.projectId)?.roots[0] ??
      this.options.root;
    const resolvedPaths = new Map<string, string>();
    for (const path of paths) {
      try {
        const requested = resolve(root, path);
        const reference =
          this.runtime instanceof DesktopRuntime &&
          message.role === 'assistant' &&
          message.kind === 'agentMessage'
            ? links.find((link) => {
                const local = localMessagePath(link.reference);
                return local && resolve(root, local) === requested;
              })?.reference
            : undefined;
        const attachment = await this.registerMessageFile(thread, message.id, requested, reference);
        resolvedPaths.set(requested, attachment.path);
        if (!attachments.some((v) => v.id === attachment.id))
          attachments.push(this.cleanAttachment(attachment));
      } catch (error) {
        appendRuntimeDiagnostic(
          this.options.directory,
          {
            scope: 'attachment',
            stage: 'register',
            hostId: this.hostId,
            threadId: thread.id,
            messageId: message.id,
            path: resolve(root, path),
          },
          error,
        );
        if (error instanceof AttachmentFileError && error.status === 503)
          throw new RuntimeError(error.status, error.message, error.code);
      }
    }
    const wire = object(item);
    const media = [
      ...list(object(wire.result).content),
      ...list(wire.contentItems),
      ...list(wire.output),
    ].map(object);
    if (message.kind === 'imageGeneration' && typeof wire.result === 'string')
      media.push({ type: 'image', data: wire.result, mimeType: 'image/png' });
    for (const entry of media) {
      if (entry.type !== 'image') continue;
      const url =
        string(entry.imageUrl) ||
        string(entry.image_url) ||
        string(object(entry.image_url).url) ||
        string(entry.url);
      const dataURI = /^data:(image\/(?:png|jpeg|webp));base64,([A-Za-z0-9+/=]+)$/.exec(url);
      const encoded = string(entry.data) || dataURI?.[2] || '';
      const mediaType = string(entry.mimeType) || dataURI?.[1] || '';
      if (
        !/^image\/(png|jpeg|webp)$/.test(mediaType) ||
        !encoded ||
        encoded.length > 28 * 1024 * 1024
      )
        continue;
      const bytes = Buffer.from(encoded, 'base64');
      if (!bytes.length || bytes.length > 20 * 1024 * 1024) continue;
      const hash = createHash('sha256');
      for (let offset = 0; offset < bytes.length; offset += 1024 * 1024) {
        hash.update(bytes.subarray(offset, offset + 1024 * 1024));
        await setImmediate();
      }
      const sha256 = hash.digest('hex');
      let attachment = this.state.attachments.find(
        (v) => v.threadId === thread.id && v.sha256 === sha256,
      );
      if (!attachment) {
        const id = randomUUID();
        const name = `image-${message.id.replace(/[^a-z0-9-]/gi, '').slice(0, 48)}.${mediaType === 'image/jpeg' ? 'jpg' : mediaType.split('/')[1]}`;
        const path = join(this.options.directory, 'attachments', id + extname(name));
        await writeFile(path, bytes, { mode: 0o600 });
        attachment = {
          id,
          hostId: this.hostId,
          threadId: thread.id,
          name,
          mediaType,
          sizeBytes: bytes.length,
          sha256,
          path,
        };
        this.state.attachments.push(attachment);
      }
      if (!attachments.some((v) => v.id === attachment.id))
        attachments.push(this.cleanAttachment(attachment));
    }
    for (const link of [...links].reverse()) {
      const local = localMessagePath(link.reference);
      if (!local) continue;
      const path = resolvedPaths.get(resolve(root, local));
      // Select the exact version inspected for this message, not an older same-path digest.
      const file = attachments.find((file) =>
        this.state.attachments.some((stored) => stored.id === file.id && stored.path === path),
      );
      if (file)
        message.text =
          message.text.slice(0, link.start) +
          `codex-attachment://${file.id}` +
          message.text.slice(link.end);
    }
  }

  private assertReplyOpen(target: ReplyTarget): void {
    if (this.closed) throw new RuntimeError(503, 'Host 已关闭，旧回复不能继续提交', 'HOST_CLOSED');
    if (
      machineRuntimeTargetKey(this.replyNamespace) !== machineRuntimeTargetKey(target) ||
      machineRuntimeTargetKey(this.readingTarget()) !== machineRuntimeTargetKey(target)
    )
      throw new RuntimeError(409, '回复的机器或运行目标已变化');
  }
  private async readWithReplyFence<T>(
    target: ReplyTarget | undefined,
    work: () => Promise<T>,
  ): Promise<T> {
    if (target) this.assertReplyOpen(target);
    const value = await work();
    if (target) this.assertReplyOpen(target);
    return value;
  }
  private async replyAwait<T>(work: Promise<T>, target: ReplyTarget): Promise<T> {
    const result = await work;
    this.assertReplyOpen(target);
    return result;
  }
  async submit(caller: Reply, suppliedTarget?: unknown) {
    const target = replyTarget(
      suppliedTarget === undefined ? this.readingTarget() : suppliedTarget,
    );
    this.assertReplyOpen(target);
    const optional = [
      'expectedTurnId',
      'model',
      'effort',
      'permissionMode',
      'speed',
      'collaborationMode',
      'expectedPlanId',
    ];
    if (
      !caller ||
      typeof caller !== 'object' ||
      ['hostId', 'threadId', 'clientMessageId', 'text', 'mode'].some(
        (key) => typeof caller[key as keyof Reply] !== 'string',
      ) ||
      optional.some(
        (key) =>
          caller[key as keyof Reply] !== undefined &&
          typeof caller[key as keyof Reply] !== 'string',
      ) ||
      !Array.isArray(caller.attachmentIds) ||
      caller.attachmentIds.some((id) => typeof id !== 'string' || !id) ||
      caller.hostId !== this.hostId ||
      !caller.threadId ||
      !caller.clientMessageId ||
      caller.clientMessageId.length > 128 ||
      caller.text.length > 100_000 ||
      caller.attachmentIds.length > 10 ||
      !['next-turn', 'steer', 'queue'].includes(caller.mode)
    )
      throw new RuntimeError(400, '发送目标或内容无效');
    // Copy only validated fields. The caller and attachment catalogue can change after an await.
    const reply = Object.freeze({
      hostId: caller.hostId,
      threadId: caller.threadId,
      clientMessageId: caller.clientMessageId,
      text: caller.text,
      attachmentIds: Object.freeze([...caller.attachmentIds]) as unknown as string[],
      mode: caller.mode,
      ...Object.fromEntries(
        optional
          .filter((key) => caller[key as keyof Reply] !== undefined)
          .map((key) => [key, caller[key as keyof Reply]]),
      ),
    }) as Reply;
    const id = reply.clientMessageId,
      fingerprint = replyIntentFingerprint(reply, target);
    const pending = this.replySubmissions.get(id);
    if (pending) {
      if (pending.fingerprint !== fingerprint)
        throw new RuntimeError(409, '原消息正在确认不同目标或内容');
      return pending.work;
    }
    const old = this.state.receipts.find((value) => value.clientMessageId === id);
    if (old) {
      // A legacy receipt has no captured account/workspace. Never assign today's target to it.
      if (
        old.fingerprintVersion !== 2 ||
        old.fingerprint !== fingerprint ||
        JSON.stringify(old.target) !== JSON.stringify(target)
      )
        throw new RuntimeError(409, '消息 ID 已绑定不同或旧版未证明的目标/内容');
      return this.receipt(id);
    }
    const checkpointIntent = this.persistence.replyCheckpointIntent(id);
    if (
      checkpointIntent &&
      (checkpointIntent.threadId !== reply.threadId || checkpointIntent.fingerprint !== fingerprint)
    )
      throw new RuntimeError(409, '回复与私有连续性证明的不可变输入不一致');
    const attachments = Object.freeze(
      reply.attachmentIds.map((id) => {
        const entry = this.state.attachments.find(
          (v) => v.id === id && v.hostId === reply.hostId && v.threadId === reply.threadId,
        );
        if (!entry) throw new RuntimeError(403, '附件归属不符');
        return Object.freeze({ ...entry });
      }),
    );
    // Register synchronously before starting any asynchronous owner/catalogue work.
    const work = Promise.resolve().then(() =>
      this.submitReplyNow(reply, target, fingerprint, attachments),
    );
    this.replySubmissions.set(id, { fingerprint, threadId: reply.threadId, target, work });
    try {
      return await work;
    } finally {
      if (this.replySubmissions.get(id)?.work === work) this.replySubmissions.delete(id);
    }
  }
  replyDeliveryStatus(threadId: string, clientMessageId: string, suppliedTarget?: unknown) {
    const target = replyTarget(
      suppliedTarget === undefined ? this.readingTarget() : suppliedTarget,
    );
    if (!threadId || !clientMessageId || clientMessageId.length > 128)
      throw new RuntimeError(400, '原消息目标/ID 无效');
    if (machineRuntimeTargetKey(target) !== machineRuntimeTargetKey(this.readingTarget()))
      throw new RuntimeError(409, '原消息查询目标不一致');
    const receipt = this.state.receipts.find((value) => value.clientMessageId === clientMessageId);
    const pending = this.replySubmissions.get(clientMessageId);
    if (
      (receipt &&
        (receipt.threadId !== threadId ||
          (receipt.target &&
            machineRuntimeTargetKey(receipt.target) !== machineRuntimeTargetKey(target)))) ||
      (pending &&
        (pending.threadId !== threadId ||
          machineRuntimeTargetKey(pending.target) !== machineRuntimeTargetKey(target)))
    )
      throw new RuntimeError(409, '原消息属于其他目标');
    // Positive receipts are readable even when the execution runtime is offline.
    let evidence;
    if (!receipt && !pending && !this.closed && this.ready) {
      this.thread(threadId);
      this.commitPending();
      if (suppliedTarget !== undefined)
        evidence = this.persistence.replyAbsence(threadId, clientMessageId, target);
      if (evidence) {
        this.save(); // Prove that this successor can durably commit its authoritative ledger.
        evidence = this.persistence.replyAbsence(threadId, clientMessageId, target);
      }
    }
    return {
      version: 2,
      hostId: this.hostId,
      target,
      threadId,
      clientMessageId,
      status: receipt
        ? 'accepted'
        : pending
          ? 'confirming'
          : evidence
            ? 'not-accepted'
            : 'unavailable',
      observedAt: new Date().toISOString(),
      evidence: evidence ?? null,
      capturedTarget: receipt?.target ?? pending?.target ?? (evidence ? target : null),
      fingerprintVersion: receipt?.fingerprintVersion ?? (pending || evidence ? 2 : null),
      fingerprint: receipt?.fingerprint ?? pending?.fingerprint ?? evidence?.fingerprint ?? null,
      receipt: receipt ? this.receipt(clientMessageId) : null,
    };
  }
  private async submitReplyNow(
    reply: Reply,
    target: ReplyTarget,
    fingerprint: string,
    attachments: readonly Attachment[],
    creation?: FirstInputAttempt,
  ) {
    const check = (stage: CreationInputStage) => {
      this.assertReplyOpen(target);
      if (creation) {
        creation.stage = stage;
        creation.boundary.assertOpen(stage);
      }
    };
    this.assertReplyOpen(target);
    this.requireReady();
    const thread = this.thread(reply.threadId);
    if (
      reply.hostId !== this.hostId ||
      !reply.clientMessageId ||
      reply.clientMessageId.length > 128 ||
      reply.text.length > 100_000 ||
      reply.attachmentIds.length > 10 ||
      !['next-turn', 'steer', 'queue'].includes(reply.mode)
    )
      throw new RuntimeError(400, '发送目标或内容无效');
    const old = this.state.receipts.find((v) => v.clientMessageId === reply.clientMessageId);
    if (old && old !== creation?.retryReceipt) {
      if (old.fingerprint !== fingerprint) throw new RuntimeError(409, '消息 ID 已用于不同内容');
      return this.receipt(reply.clientMessageId);
    }
    if (this.runtime instanceof DesktopRuntime) {
      check('owner');
      // Restart recovery and a fast completion may precede background owner
      // discovery. First refresh this exact target without any execution.
      let owner: Record<string, unknown> | undefined;
      for (let attempt = 0; attempt < 3; attempt++) {
        owner = await this.replyAwait(this.runtime.ipc.follow(thread.id, 5000, true), target);
        if (
          owner?.id === thread.id &&
          owner.hostId === 'local' &&
          this.runtime.ipc.hasOwner(thread.id)
        )
          break;
        if (attempt < 2)
          await this.replyAwait(new Promise<void>((done) => setTimeout(done, 250)), target);
      }
      if (
        (!owner ||
          owner.id !== thread.id ||
          owner.hostId !== 'local' ||
          !this.runtime.ipc.hasOwner(thread.id)) &&
        this.desktopCreationDriver?.recoverCreationOwner === true &&
        this.desktopCreationDriver.openAvailable &&
        this.desktopCreationDriver.open
      ) {
        // Closing a Host subscription can unload a completed native owner.
        // An explicit reply also authorizes reopening a known historical ID.
        // Reopen only this exact ID through the existing desktop URI;
        // never prepare another thread or retry an execution. Receipt dedupe
        // above excludes any already accepted or uncertain message.
        const opened = await this.replyAwait(
          this.desktopCreationDriver.open(thread.id, 'reply-owner-' + fingerprint),
          target,
        );
        if (opened)
          owner = await this.replyAwait(this.runtime.ipc.follow(thread.id, 5000, true), target);
      }
      if (
        !owner ||
        owner.id !== thread.id ||
        owner.hostId !== 'local' ||
        !this.runtime.ipc.hasOwner(thread.id)
      )
        throw new RuntimeError(409, '请先在 Codex 中打开此对话，再刷新手机');
      await this.replyAwait(this.desktopSnapshot(thread, owner), target);
    }
    if (
      reply.collaborationMode !== undefined &&
      !['plan', 'default'].includes(reply.collaborationMode)
    )
      throw new RuntimeError(400, '无效计划模式');
    if (
      reply.mode === 'steer' &&
      (reply.model !== undefined ||
        reply.effort !== undefined ||
        reply.permissionMode !== undefined ||
        reply.speed !== undefined)
    )
      throw new RuntimeError(400, '运行中补充指令不能改变设置，请排队到下一轮');
    if (
      reply.permissionMode !== undefined &&
      !permissionModes.includes(reply.permissionMode as (typeof permissionModes)[number])
    )
      throw new RuntimeError(400, '无效权限模式');
    if (reply.model !== undefined && (!reply.model.trim() || reply.model.length > 200))
      throw new RuntimeError(400, '无效模型设置');
    if (reply.speed !== undefined && !speeds.includes(reply.speed as (typeof speeds)[number]))
      throw new RuntimeError(400, '无效速度设置');
    if (reply.model !== undefined || reply.effort !== undefined || reply.speed !== undefined) {
      check('generation');
      await this.replyAwait(this.validateGeneration(reply, thread, target), target);
      check('generation');
    }
    // Catalog reads above yield. Another request may have committed this same intent meanwhile.
    const concurrent = this.state.receipts.find((v) => v.clientMessageId === reply.clientMessageId);
    if (concurrent && concurrent !== creation?.retryReceipt) {
      if (concurrent.fingerprint !== fingerprint)
        throw new RuntimeError(409, '消息 ID 已用于不同内容');
      return this.receipt(reply.clientMessageId);
    }
    if (reply.mode === 'steer' && reply.collaborationMode !== undefined)
      throw new RuntimeError(400, '运行中不能切换计划模式，请排队到下一轮');
    if (
      reply.expectedPlanId !== undefined &&
      (reply.mode !== 'next-turn' ||
        !reply.expectedPlanId ||
        thread.proposedPlan?.id !== reply.expectedPlanId)
    )
      throw new RuntimeError(409, '计划已变化或已处理，请刷新后重试');
    if (!reply.text.trim() && !reply.attachmentIds.length)
      throw new RuntimeError(400, '消息不能为空');
    if (
      reply.effort &&
      !['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max', 'ultra'].includes(reply.effort)
    )
      throw new RuntimeError(400, '无效推理设置');
    for (const id of reply.attachmentIds)
      if (!this.state.attachments.some((v) => v.id === id && v.threadId === thread.id))
        throw new RuntimeError(403, '附件归属不符');
    if (this.locks.has(thread.id)) throw new RuntimeError(409, '对话正在提交消息');
    if (thread.turnId && reply.mode === 'next-turn')
      throw new RuntimeError(409, '当前任务进行中，请排队或补充指令');
    if (reply.mode === 'steer' && (!thread.turnId || thread.turnId !== reply.expectedTurnId))
      throw new RuntimeError(409, '当前任务已变化');
    check('receipt');
    const receipt: Receipt = creation?.retryReceipt ?? {
      ...reply,
      state: 'received',
      turnId: null,
      error: null,
      fingerprint,
      fingerprintVersion: 2,
      target,
      attachmentSnapshot: attachments,
      reply,
      ...(reply.mode === 'queue' &&
      this.nativeQueue &&
      !this.nativeQueueUnsupported &&
      this.nativeQueueThread(thread.id)
        ? { nativeQueue: { phase: 'adding' as const, present: false } }
        : {}),
    };
    if (creation?.retryReceipt) {
      if (receipt.state !== 'failed' || receipt.creationInput?.state !== 'not-written')
        throw new RuntimeError(409, '原首条输入前置状态已变化');
      receipt.state = 'received';
      receipt.error = null;
    } else this.state.receipts.push(receipt);
    try {
      this.save();
    } catch (error) {
      // The intent never crossed the execution boundary. Keep its ID reserved;
      // recovery can query it, but cannot silently submit the failed command.
      receipt.state = 'failed';
      receipt.error = 'Host 状态保存失败，消息未提交';
      this.saveBackground();
      throw error;
    }
    if (reply.mode === 'queue') {
      if (this.nativeQueue && !this.nativeQueueUnsupported && this.nativeQueueThread(thread.id))
        await this.replyAwait(this.enqueueNative(receipt), target);
      else {
        receipt.state = 'failed';
        receipt.error = '客户端队列不可用，消息未提交；不会由 Host 代为排队或执行';
        this.save();
      }
    } else await this.replyAwait(this.execute(receipt, creation), target);
    this.assertReplyOpen(target);
    this.emit(thread.id, 'receipt');
    return this.receipt(reply.clientMessageId);
  }
  private nativeQueueThread(threadId: string): boolean {
    return /^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(threadId);
  }
  private queueReceipts(threadId: string, receipts: Receipt[], recent: Set<string>) {
    const native = this.nativeQueues.get(threadId) ?? [];
    const ids = new Set(native.map((item) => item.clientUserMessageId));
    return [
      ...native.map((item) => ({
        ...(receipts.some((receipt) => receipt.clientMessageId === item.clientUserMessageId)
          ? this.publicReceipt(
              receipts.find((receipt) => receipt.clientMessageId === item.clientUserMessageId)!,
            )
          : {
              clientMessageId: item.clientUserMessageId,
              hostId: this.hostId,
              threadId,
              state: 'queued',
              turnId: null,
              error: null,
            }),
        text: item.input
          .filter((part) => part.type === 'text')
          .map((part) => string(part.text))
          .join('\n'),
        queueSource: 'native',
        queuePresent: true,
        ...(this.nativeQueueErrors.has(threadId)
          ? { queueSyncError: this.nativeQueueErrors.get(threadId) }
          : {}),
        canExecuteNow:
          !this.nativeQueueErrors.has(threadId) &&
          !this.queueActions.has(JSON.stringify([threadId, item.clientUserMessageId])) &&
          !receipts.find((receipt) => receipt.clientMessageId === item.clientUserMessageId)
            ?.nativeQueue?.action,
      })),
      ...receipts
        .filter(
          (receipt) =>
            !ids.has(receipt.clientMessageId) &&
            // A completed tail cannot retire an unresolved original input.
            // Keep it queryable/visible without changing its acceptance state.
            (receipt.state === 'queued' ||
              receipt.state === 'uncertain' ||
              recent.has(receipt.clientMessageId)),
        )
        .map((receipt) => this.publicReceipt(receipt)),
    ];
  }
  private async refreshNativeQueue(threadId: string): Promise<void> {
    if (
      !this.nativeQueue ||
      !this.nativeQueueThread(threadId) ||
      this.nativeQueueUnsupported ||
      this.desktopDiscovering.has(threadId) ||
      !this.loaded.has(threadId) ||
      this.closed ||
      !this.ready ||
      !(this.runtime instanceof DesktopRuntime)
    )
      return;
    const pending = this.nativeQueueReads.get(threadId);
    if (pending) return pending;
    if (Date.now() - (this.nativeQueueReadAt.get(threadId) ?? 0) < 750) return;
    this.nativeQueueReadAt.set(threadId, Date.now());
    const revision = this.nativeQueueRevisions.get(threadId) ?? 0;
    const work = (async () => {
      try {
        const items = await this.nativeQueue!.list(threadId);
        if (this.closed || revision !== (this.nativeQueueRevisions.get(threadId) ?? 0)) return;
        const recovered = this.nativeQueueErrors.delete(threadId);
        const previous = JSON.stringify(this.nativeQueues.get(threadId));
        const previousReceipts = JSON.stringify(
          this.state.receipts
            .filter((receipt) => receipt.threadId === threadId)
            .map((receipt) => [
              receipt.clientMessageId,
              receipt.state,
              receipt.nativeQueue?.phase,
              receipt.nativeQueue?.present,
              receipt.nativeQueue?.id,
            ]),
        );
        this.nativeQueues.set(threadId, items);
        for (const receipt of this.state.receipts) {
          if (receipt.threadId !== threadId || !receipt.nativeQueue) continue;
          const item = items.find((entry) => entry.clientUserMessageId === receipt.clientMessageId);
          receipt.nativeQueue.present = !!item;
          if (item) delete receipt.nativeQueue.missingSince;
          else if (
            receipt.nativeQueue.phase === 'queued' &&
            !receipt.nativeQueue.action &&
            !receipt.acceptedTurnId
          ) {
            receipt.nativeQueue.missingSince ??= Date.now();
            if (
              Date.now() - receipt.nativeQueue.missingSince >= 2000 &&
              receipt.state === 'queued'
            ) {
              receipt.state = 'cancelled';
              receipt.error = null;
            }
          }
          if (
            item &&
            !receipt.nativeQueue.action &&
            ['adding', 'queued', 'uncertain'].includes(receipt.nativeQueue.phase) &&
            (!receipt.nativeQueue.inputSha256 ||
              createHash('sha256').update(JSON.stringify(item.input)).digest('hex') ===
                receipt.nativeQueue.inputSha256)
          ) {
            receipt.nativeQueue.id = item.id;
            receipt.nativeQueue.phase = 'queued';
            if (['submitting', 'uncertain'].includes(receipt.state)) receipt.state = 'queued';
          }
        }
        if (
          recovered ||
          previous !== JSON.stringify(items) ||
          previousReceipts !==
            JSON.stringify(
              this.state.receipts
                .filter((receipt) => receipt.threadId === threadId)
                .map((receipt) => [
                  receipt.clientMessageId,
                  receipt.state,
                  receipt.nativeQueue?.phase,
                  receipt.nativeQueue?.present,
                  receipt.nativeQueue?.id,
                ]),
            )
        )
          this.emit(threadId, 'native-queue');
      } catch (error) {
        const firstFailure = !this.nativeQueueErrors.has(threadId);
        this.nativeQueueErrors.set(
          threadId,
          '客户端队列同步失败，当前显示上次结果；不会自动重发消息',
        );
        if (firstFailure) this.emit(threadId, 'native-queue-sync-failed');
        if (error instanceof MetadataRejection && error.reason === 'unsupported-method') {
          this.nativeQueueUnsupported = true;
          this.nativeQueue?.close();
        }
        // Reads never replay an add/delete. Keep the last snapshot, without
        // presenting a failed query as cancellation or execution evidence.
      }
    })();
    this.nativeQueueReads.set(threadId, work);
    try {
      await work;
    } finally {
      if (this.nativeQueueReads.get(threadId) === work) this.nativeQueueReads.delete(threadId);
    }
  }
  private async enqueueNative(receipt: Receipt): Promise<void> {
    if (!this.nativeQueue) throw new RuntimeError(409, '客户端队列不可用');
    const reply = receipt.reply,
      thread = this.thread(reply.threadId);
    await this.refreshNativeQueue(thread.id);
    if (this.nativeQueueUnsupported) {
      receipt.state = 'failed';
      receipt.error = '客户端不支持原生队列，消息未提交；不会由 Host 代为执行';
      this.save();
      return;
    }
    if (!this.nativeQueue.available) {
      receipt.state = 'failed';
      receipt.error = '原生队列暂不可用，消息尚未投递';
      this.save();
      return;
    }
    // The official persistent queue inherits the desktop thread settings.
    // Reject an override it cannot represent rather than silently dropping it.
    if (
      (reply.model !== undefined && reply.model !== thread.runtimeModel) ||
      (reply.effort !== undefined && reply.effort !== thread.runtimeEffort) ||
      (reply.permissionMode !== undefined && reply.permissionMode !== thread.permissionMode) ||
      (reply.speed !== undefined && reply.speed !== thread.speed) ||
      (reply.collaborationMode !== undefined &&
        reply.collaborationMode !== thread.collaborationMode)
    ) {
      receipt.state = 'failed';
      receipt.error = '原生队列沿用当前桌面设置，请保持当前设置后排队';
      this.save();
      return;
    }
    const input: Record<string, unknown>[] = [];
    if (reply.text) input.push({ type: 'text', text: reply.text, text_elements: [] });
    for (const attachment of receipt.attachmentSnapshot ?? [])
      input.push(
        attachment.mediaType.startsWith('image/')
          ? { type: 'localImage', path: attachment.path }
          : {
              type: 'text',
              text: `用户附件 ${JSON.stringify(attachment.name)}，已上传至本机 ${JSON.stringify(attachment.path)}。按需要用工具读取该文件。`,
              text_elements: [],
            },
      );
    const inputSha256 = createHash('sha256').update(JSON.stringify(input)).digest('hex');
    receipt.nativeQueue = { phase: 'adding', input, inputSha256 };
    this.nativeQueueRevisions.set(thread.id, (this.nativeQueueRevisions.get(thread.id) ?? 0) + 1);
    let wrote = false;
    try {
      const item = await this.nativeQueue.add(thread.id, receipt.clientMessageId, input, () => {
        this.assertReplyOpen(receipt.target!);
        if (
          this.closed ||
          !this.ready ||
          receipt.fingerprint !== replyIntentFingerprint(reply, receipt.target!)
        )
          throw new RuntimeError(409, '排队目标或原输入已变化');
        for (const attachment of receipt.attachmentSnapshot ?? []) {
          const bytes = readFileSync(attachment.path);
          if (
            bytes.length !== attachment.sizeBytes ||
            createHash('sha256').update(bytes).digest('hex') !== attachment.sha256
          )
            throw new RuntimeError(409, '排队附件实际字节已变化');
        }
        receipt.state = 'submitting';
        this.save();
        wrote = true;
      });
      if (this.closed) return;
      receipt.nativeQueue.id = item.id;
      receipt.nativeQueue.phase = 'queued';
      receipt.nativeQueue.present = true;
      if (!receipt.acceptedTurnId) receipt.state = 'queued';
      this.nativeQueues.set(thread.id, [
        ...(this.nativeQueues.get(thread.id) ?? []).filter(
          (entry) => entry.clientUserMessageId !== item.clientUserMessageId,
        ),
        item,
      ]);
      this.save();
    } catch (error) {
      if (this.closed) throw error;
      receipt.state = wrote ? 'uncertain' : 'failed';
      receipt.nativeQueue.phase = 'uncertain';
      receipt.error = wrote
        ? '原生排队结果待核对，原消息不会重复投递'
        : error instanceof Error
          ? error.message
          : '原生排队未提交';
      this.save();
    } finally {
      this.nativeQueueRevisions.set(thread.id, (this.nativeQueueRevisions.get(thread.id) ?? 0) + 1);
    }
  }
  async executeQueue(
    threadId: string,
    id: string,
    expectedTurnId?: string,
  ): Promise<{ ok: boolean }> {
    if (
      this.nativeQueue &&
      (this.nativeQueues.get(threadId)?.some((item) => item.clientUserMessageId === id) ||
        this.state.receipts.some(
          (r) => r.threadId === threadId && r.clientMessageId === id && r.nativeQueue,
        ))
    )
      return this.nativeQueueAction(threadId, id, 'execute', expectedTurnId);
    throw new RuntimeError(409, '旧 Host 消息未提交到客户端队列，请先核对；不能自动执行或重发');
  }
  private nativeQueueAction(
    threadId: string,
    id: string,
    action: 'cancel' | 'execute',
    expectedTurnId?: string,
  ): Promise<{ ok: boolean }> {
    const key = JSON.stringify([threadId, id]);
    const pending = this.queueActions.get(key);
    if (pending) {
      if (pending.action !== action || pending.expectedTurnId !== expectedTurnId)
        return Promise.reject(new RuntimeError(409, '此队列项正在处理另一操作'));
      return pending.work;
    }
    const work = this.nativeQueueActionNow(threadId, id, action, expectedTurnId);
    this.queueActions.set(key, { action, expectedTurnId, work });
    void work
      .finally(() => {
        if (this.queueActions.get(key)?.work === work) this.queueActions.delete(key);
      })
      .catch(() => undefined);
    return work;
  }
  private async nativeQueueActionNow(
    threadId: string,
    id: string,
    action: 'cancel' | 'execute',
    expectedTurnId?: string,
  ): Promise<{ ok: boolean }> {
    this.requireReady();
    if (!this.nativeQueue || !(this.runtime instanceof DesktopRuntime))
      throw new RuntimeError(409, '原生队列不可用');
    const thread = this.thread(threadId);
    const state = await this.runtime.ipc.follow(threadId, 5000, true);
    if (!state || !this.runtime.ipc.hasOwner(threadId))
      throw new RuntimeError(409, '原桌面运行目标不可用');
    await this.desktopSnapshot(thread, state);
    let receipt = this.state.receipts.find(
      (value) => value.threadId === threadId && value.clientMessageId === id,
    );
    if (receipt?.acceptedTurnId) return { ok: action === 'execute' };
    if (
      receipt?.nativeQueue?.action === action &&
      ['detached', 'uncertain', 'removing'].includes(receipt.nativeQueue.phase)
    )
      return {
        ok:
          action === 'cancel'
            ? receipt.state === 'cancelled'
            : ['submitted', 'completed', 'interrupted'].includes(receipt.state),
      };
    if (this.locks.has(threadId)) throw new RuntimeError(409, '对话正在提交消息');
    if (action === 'execute' && expectedTurnId && thread.turnId !== expectedTurnId)
      throw new RuntimeError(409, '当前任务已变化');
    const items = await this.nativeQueue.list(threadId);
    this.nativeQueues.set(threadId, items);
    const item = items.find((entry) => entry.clientUserMessageId === id);
    if (!item) {
      if (receipt?.nativeQueue) receipt.nativeQueue.present = false;
      this.emit(threadId, 'native-queue');
      if (action === 'execute' && receipt?.acceptedTurnId) return { ok: true };
      throw new RuntimeError(409, '此队列项已由电脑处理，请刷新核对');
    }
    const inputSha256 = createHash('sha256').update(JSON.stringify(item.input)).digest('hex');
    if (receipt?.nativeQueue?.inputSha256 && receipt.nativeQueue.inputSha256 !== inputSha256)
      throw new RuntimeError(409, '电脑已修改这条排队输入，请在电脑处理原队列项');
    if (!receipt) {
      const reply: Reply = {
        clientMessageId: id,
        hostId: this.hostId,
        threadId,
        text: item.input
          .filter((entry) => entry.type === 'text')
          .map((entry) => string(entry.text))
          .join('\n'),
        attachmentIds: [],
        mode: 'queue',
      };
      const target = replyTarget(this.readingTarget());
      receipt = {
        ...reply,
        reply,
        target,
        fingerprintVersion: 2,
        fingerprint: replyIntentFingerprint(reply, target),
        state: 'queued',
        turnId: null,
        error: null,
        attachmentSnapshot: [],
      };
      this.state.receipts.push(receipt);
    }
    if (action === 'execute' && thread.state === 'waiting-for-approval')
      throw new RuntimeError(409, '请先处理当前任务的审批或问题');
    const originalTurnId = thread.turnId;
    receipt.nativeQueue = {
      id: item.id,
      phase: 'removing',
      present: true,
      action,
      input: structuredClone(item.input),
      inputSha256,
    };
    const record = receipt;
    this.nativeQueueRevisions.set(threadId, (this.nativeQueueRevisions.get(threadId) ?? 0) + 1);
    let wrote = false;
    try {
      const removed = await this.nativeQueue.remove(threadId, item.id, () => {
        this.assertReplyOpen(record.target!);
        this.save();
        wrote = true;
      });
      this.assertReplyOpen(record.target!);
      if (!removed) {
        // Already consumed/cancelled on the desktop. Never issue another input.
        await this.refreshNativeQueue(threadId);
        const latest = await this.runtime.ipc.follow(threadId, 5000, true);
        if (latest) await this.desktopSnapshot(thread, latest);
        record.nativeQueue!.present = false;
        if (record.acceptedTurnId) return { ok: action === 'execute' };
        record.state = action === 'cancel' ? 'cancelled' : 'uncertain';
        record.nativeQueue!.phase = 'uncertain';
        record.error =
          action === 'execute' ? '队列项已由电脑处理，请核对原任务；没有再次提交' : null;
        this.save();
        return { ok: action === 'cancel' };
      }
      record.nativeQueue!.present = false;
      record.nativeQueue!.phase = 'detached';
      this.nativeQueues.set(
        threadId,
        items.filter((entry) => entry.id !== item.id),
      );
      if (action === 'cancel') {
        record.state = 'cancelled';
        this.save();
      } else {
        // Persist the detach before the next execution boundary. A crash here
        // stays queryable and never recreates/replays the removed queue entry.
        record.state = 'submitting';
        this.save();
        const latest = await this.runtime.ipc.follow(threadId, 5000, true);
        if (!latest) throw new Error('原桌面执行状态待核对');
        await this.desktopSnapshot(thread, latest);
        if (record.acceptedTurnId) return { ok: true };
        if (thread.turnId && thread.turnId !== originalTurnId)
          throw new Error('当前任务已变化，已移除队列项但未追加输入');
        await this.execute(record, undefined, {
          mode: thread.turnId ? 'steer' : 'next-turn',
          ...(thread.turnId ? { turnId: thread.turnId } : {}),
        });
      }
    } catch (error) {
      if (this.closed) throw error;
      if (wrote && !record.acceptedTurnId && record.state !== 'cancelled') {
        record.state = 'uncertain';
        record.nativeQueue!.phase = 'uncertain';
        record.error = error instanceof Error ? error.message : '队列操作结果待核对';
        this.save();
      }
      throw error;
    } finally {
      this.nativeQueueRevisions.set(threadId, (this.nativeQueueRevisions.get(threadId) ?? 0) + 1);
      this.emit(threadId, 'native-queue');
    }
    return {
      ok:
        action === 'cancel'
          ? record.state === 'cancelled'
          : ['submitted', 'completed', 'interrupted'].includes(record.state),
    };
  }
  receipt(id: string) {
    const value = this.state.receipts.find((v) => v.clientMessageId === id);
    if (!value) throw new RuntimeError(404, '没有找到发送记录');
    if (this.needsCommit) this.save();
    return this.publicReceipt(value);
  }
  private publicReceipt(value: Receipt) {
    const {
      reply: _reply,
      fingerprint: _fingerprint,
      attachmentSnapshot: _attachments,
      creationInput: _creationInput,
      acceptedTurnId: _acceptedTurnId,
      nativeQueue,
      ...rest
    } = value;
    return {
      ...rest,
      ...(nativeQueue
        ? {
            queueSource: 'native',
            ...(this.nativeQueueErrors.has(value.threadId)
              ? { queueSyncError: this.nativeQueueErrors.get(value.threadId) }
              : {}),
            queuePresent: nativeQueue.present !== false,
            canExecuteNow:
              value.state === 'queued' &&
              !this.nativeQueueErrors.has(value.threadId) &&
              nativeQueue.phase === 'queued' &&
              nativeQueue.present === true,
          }
        : {
            queueSource: 'host',
            queuePresent: false,
            canExecuteNow: false,
            ...(value.state === 'queued' ? { queueDisposition: 'legacy-review' } : {}),
          }),
      target: value.target ?? null,
      ...(value.state === 'uncertain' && this.historicalReceiptContexts.has(value.clientMessageId)
        ? {
            noticeContext: 'historical',
            laterTurnId: this.historicalReceiptContexts.get(value.clientMessageId),
          }
        : {}),
      fingerprintVersion: value.fingerprintVersion ?? null,
    };
  }
  private async callWithOwnerRecovery(
    thread: Thread,
    messageId: string,
    method: string,
    params: Record<string, unknown>,
    target: ReplyTarget,
    beforeWire: () => void,
  ) {
    this.assertReplyOpen(target);
    const call = () => {
      if (this.runtime instanceof DesktopRuntime)
        return this.runtime.call(method, params, beforeWire);
      beforeWire();
      return this.runtime.call(method, params);
    };
    try {
      this.assertReplyOpen(target);
      this.save(); // File and directory flush precede each execution write, including owner recovery.
      return await this.replyAwait(call(), target);
    } catch (error) {
      const bridge = this.desktopCreationDriver;
      if (
        this.closed ||
        !(this.runtime instanceof DesktopRuntime) ||
        !(error instanceof DesktopCommandNotSubmitted) ||
        !error.ownerUnavailable ||
        bridge?.recoverCreationOwner !== true ||
        !bridge.openAvailable ||
        !bridge.open
      )
        throw error;
      // This error proves the execution request was never written. Reacquire
      // only the same original owner once; a response timeout or rejection
      // after an execution write never enters this branch.
      const id = 'reply-owner-' + createHash('sha256').update(messageId).digest('hex');
      if (!(await this.replyAwait(bridge.open(thread.id, id), target))) throw error;
      this.assertReplyOpen(target);
      this.save();
      return this.replyAwait(call(), target);
    }
  }
  private async execute(
    receipt: Receipt,
    creation?: FirstInputAttempt,
    promotion?: { mode: 'steer' | 'next-turn'; turnId?: string },
  ): Promise<void> {
    const reply = receipt.reply;
    const mode = promotion?.mode ?? reply.mode;
    const target = receipt.target;
    if (!target || receipt.fingerprintVersion !== 2)
      throw new RuntimeError(409, '旧版排队回复缺少完整目标证明');
    this.assertReplyOpen(target);
    const thread = this.thread(reply.threadId);
    this.locks.add(thread.id);
    let wireAttempted = false;
    const check = (stage: CreationInputStage) => {
      this.assertReplyOpen(target);
      if (creation) {
        creation.stage = stage;
        creation.boundary.assertOpen(stage);
      }
    };
    try {
      check('owner');
      if (!this.loaded.has(thread.id))
        await this.replyAwait(this.open(thread.id, undefined, target), target);
      if (reply.expectedPlanId && thread.proposedPlan?.id !== reply.expectedPlanId)
        throw new CodexRejection('计划已变化或已处理，请刷新后重试');
      check('attachments');
      const input: unknown[] = receipt.nativeQueue?.input
        ? structuredClone(receipt.nativeQueue.input)
        : [];
      if (!receipt.nativeQueue?.input && reply.text) input.push({ type: 'text', text: reply.text });
      for (const id of receipt.nativeQueue?.input ? [] : reply.attachmentIds) {
        const attachment = receipt.attachmentSnapshot?.find((v) => v.id === id);
        if (!attachment) throw new CodexRejection('附件快照缺失');
        input.push(
          attachment.mediaType.startsWith('image/')
            ? { type: 'localImage', path: attachment.path }
            : {
                type: 'text',
                text: `用户附件 ${JSON.stringify(attachment.name)}，已上传至本机 ${JSON.stringify(attachment.path)}。按需要用工具读取该文件。`,
              },
        );
      }
      check('generation');
      const speedTier = reply.speed
        ? (await this.replyAwait(this.validateGeneration(reply, thread, target), target)).get(
            reply.speed,
          )
        : undefined;
      const cwd =
        thread.desktopDirectory ||
        this.state.projects.find((project) => project.id === thread.projectId)?.roots[0] ||
        this.options.root;
      const permissions =
        mode === 'steer'
          ? {}
          : reply.permissionMode
            ? permissionSettings(reply.permissionMode, cwd)
            : this.runtime instanceof DesktopRuntime
              ? {} // Resolve the desktop choice immediately before its owner submits.
              : await this.replyAwait(this.computerPermissions(cwd, target), target);
      if (!(this.runtime instanceof DesktopRuntime)) delete permissions.permissions;
      this.assertReplyOpen(target);
      receipt.state = 'submitting';
      let collaborationMode: unknown = reply.collaborationMode;
      if (reply.collaborationMode && !(this.runtime instanceof DesktopRuntime)) {
        const presets = list(
          (await this.replyAwait(this.runtime.call('collaborationMode/list'), target)).data,
        ).map(object);
        const preset = presets.find((v) => v.mode === reply.collaborationMode);
        if (!preset) throw new CodexRejection('当前运行时未提供此计划模式');
        const prior = this.state.receipts
          .filter(
            (v) =>
              v.threadId === thread.id &&
              v !== receipt &&
              ['submitted', 'completed'].includes(v.state),
          )
          .at(-1)?.reply;
        let model = reply.model || thread.runtimeModel || prior?.model || string(preset.model);
        if (!model) {
          const models = list(
            (await this.replyAwait(this.runtime.call('model/list', { limit: 100 }), target)).data,
          ).map(object);
          model = string(models.find((v) => v.isDefault === true)?.id);
        }
        if (!model) throw new CodexRejection('无法确认计划模式模型');
        collaborationMode = {
          mode: reply.collaborationMode,
          settings: {
            model,
            reasoning_effort:
              reply.effort ??
              (thread.runtimeEffort !== undefined
                ? thread.runtimeEffort
                : (prior?.effort ?? preset.reasoning_effort ?? null)),
            developer_instructions: null,
          },
        };
      }
      this.assertReplyOpen(target);
      check('owner');
      const result = await this.callWithOwnerRecovery(
        thread,
        reply.clientMessageId,
        mode === 'steer' ? 'turn/steer' : 'turn/start',
        {
          threadId: thread.codexId ?? thread.id,
          input,
          clientUserMessageId: reply.clientMessageId,
          ...(mode === 'steer'
            ? { expectedTurnId: promotion?.turnId ?? reply.expectedTurnId }
            : {}),
          ...(mode !== 'steer' && reply.model ? { model: reply.model } : {}),
          ...(mode !== 'steer' && reply.effort ? { effort: reply.effort } : {}),
          ...(collaborationMode ? { collaborationMode } : {}),
          ...(speedTier ? { serviceTier: speedTier } : {}),
          ...permissions,
          ...(this.runtime instanceof DesktopRuntime && reply.expectedPlanId
            ? { expectedPlanId: reply.expectedPlanId }
            : {}),
        },
        target,
        () => {
          this.assertReplyOpen(target);
          check('attachments');
          if (receipt.fingerprint !== replyIntentFingerprint(reply, target))
            throw new RuntimeError(409, '回复持久意图已变化');
          for (const attachment of receipt.attachmentSnapshot ?? []) {
            const bytes = readFileSync(attachment.path);
            if (
              bytes.length !== attachment.sizeBytes ||
              createHash('sha256').update(bytes).digest('hex') !== attachment.sha256
            )
              throw new RuntimeError(409, '回复附件实际字节已变化');
          }
          check('execution');
          if (creation) {
            creation.boundaryAttempted = true;
            creation.boundary.beforeWrite(creation.checkpoint);
            receipt.creationInput = { state: 'may-have-written', checkpoint: creation.checkpoint };
          }
          // This save is at the final native write hook, after all asynchronous reads.
          this.save();
          wireAttempted = true;
          if (creation) creation.wrote = true;
        },
      );
      this.assertReplyOpen(target);
      if (!(this.runtime instanceof DesktopRuntime) && reply.collaborationMode) {
        thread.collaborationMode = reply.collaborationMode;
        const settings = object(object(collaborationMode).settings);
        thread.runtimeModel = string(settings.model);
        if (typeof settings.reasoning_effort === 'string' || settings.reasoning_effort === null)
          thread.runtimeEffort = settings.reasoning_effort;
      }
      if (mode !== 'steer' && reply.model) thread.runtimeModel = reply.model;
      if (mode !== 'steer' && reply.effort) thread.runtimeEffort = reply.effort;
      if (this.runtime instanceof DesktopRuntime) {
        thread.permissionMode = desktopPermissionMode(this.runtime.ipc.state(thread.id) ?? {});
      } else if (mode !== 'steer') thread.permissionMode = currentPermissionMode(permissions);
      if (reply.speed) thread.speed = reply.speed;
      receipt.turnId = string(object(result.turn).id) || string(result.turnId) || thread.turnId;
      if (!['completed', 'interrupted', 'failed'].includes(receipt.state))
        receipt.state = 'submitted';
      if (
        receipt.state === 'submitted' &&
        object(result.turn).status === 'inProgress' &&
        !thread.turnId
      ) {
        thread.turnId = receipt.turnId;
        thread.state = 'running';
      }
      if (thread.title === '新对话') thread.title = reply.text.trim().slice(0, 36) || '附件对话';
      this.save();
    } catch (error) {
      if (this.closed) throw error; // The close snapshot is fenced; no late callback may save or execute.
      if (creation) creation.failure = error;
      receipt.state = wireAttempted && !(error instanceof CodexRejection) ? 'uncertain' : 'failed';
      receipt.error = creation
        ? '首条输入阶段尚未确认，原操作已保留'
        : error instanceof Error
          ? error.message
          : '发送失败';
      if (
        creation &&
        !creation.wrote &&
        !creation.boundaryAttempted &&
        !(error instanceof RuntimeSaveError) &&
        ['owner', 'attachments', 'generation'].includes(creation.stage)
      ) {
        check(creation.stage);
        receipt.creationInput = { state: 'not-written', checkpoint: creation.checkpoint };
      }
      try {
        this.save();
      } catch (failure) {
        this.saveBackground();
        throw failure;
      }
      // A second successful write cannot turn a failed durable commit into a
      // successful HTTP submission. The caller must reconcile the same ID.
      if (error instanceof RuntimeSaveError) throw error;
    } finally {
      this.locks.delete(thread.id);
    }
  }
  async stop(threadId: string, turnId: string, onSubmitted?: () => void) {
    this.requireReady();
    const thread = this.thread(threadId);
    if (!turnId || thread.turnId !== turnId) throw new RuntimeError(409, '任务已变化或已经结束');
    // A start receipt can arrive before the runtime has activated the turn.
    for (let attempt = 0; attempt < 20 && !this.startedTurns.has(turnId); attempt++) {
      await new Promise<void>((done) => setTimeout(done, 100));
      if (!thread.turnId) return { ok: true };
      if (thread.turnId !== turnId) throw new RuntimeError(409, '任务已变化');
    }
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        onSubmitted?.();
        await this.runtime.call('turn/interrupt', { threadId: thread.codexId ?? threadId, turnId });
        break;
      } catch (error) {
        if (!(error instanceof CodexRejection)) throw error;
        if (attempt === 1)
          throw new RuntimeError(409, 'Codex 拒绝停止：' + error.message.slice(0, 200));
        await new Promise<void>((done) => setTimeout(done, 200));
        if (!thread.turnId) return { ok: true };
        if (thread.turnId !== turnId) throw new RuntimeError(409, '任务已变化');
      }
    }
    return { ok: true };
  }
  cancelQueue(threadId: string, id: string) {
    if (
      this.nativeQueue &&
      (this.nativeQueues.get(threadId)?.some((item) => item.clientUserMessageId === id) ||
        this.state.receipts.some(
          (r) => r.threadId === threadId && r.clientMessageId === id && r.nativeQueue,
        ))
    )
      return this.nativeQueueAction(threadId, id, 'cancel');
    const receipt = this.state.receipts.find(
      (v) => v.threadId === threadId && v.clientMessageId === id && v.state === 'queued',
    );
    if (!receipt) throw new RuntimeError(409, '排队消息已经提交或不存在');
    receipt.state = 'cancelled';
    this.save();
    this.emit(threadId, 'receipt');
    return { ok: true };
  }
  async answer(
    threadId: string,
    id: string,
    turnId: string,
    decision: string,
    answers: Record<string, string[]>,
  ) {
    this.requireReady();
    const fingerprint = JSON.stringify([threadId, turnId, decision, answers]);
    const pending = this.questionSubmissions.get(id);
    if (pending) {
      if (pending.fingerprint !== fingerprint)
        throw new RuntimeError(409, '此请求正在提交不同的回复');
      return pending.work;
    }
    const work = this.answerRequest(threadId, id, turnId, decision, answers);
    this.questionSubmissions.set(id, { fingerprint, work });
    try {
      return await work;
    } finally {
      this.questionSubmissions.delete(id);
    }
  }
  private async answerRequest(
    threadId: string,
    id: string,
    turnId: string,
    decision: string,
    answers: Record<string, string[]>,
  ) {
    if (
      this.runtime instanceof DesktopRuntime &&
      (this.approvals.get(id)?.interactionMode === 'async' ||
        Object.hasOwn(this.state.asyncQuestionActions ?? {}, id))
    ) {
      return this.answerAsyncQuestion(threadId, id, turnId, decision, answers);
    }
    const approval = this.approvals.get(id);
    if (
      !approval ||
      approval.threadId !== threadId ||
      approval.turnId !== turnId ||
      this.thread(threadId).turnId !== turnId
    )
      throw new RuntimeError(409, '审批已处理或已过期');
    if (approval.kind === 'approval' && !approval.decisions.includes(decision))
      throw new RuntimeError(400, '无效审批选项');
    if (approval.kind === 'question') {
      for (const q of approval.questions.map(object))
        if (!answers[string(q.id)]?.length) throw new RuntimeError(400, '请回答所有问题');
    }
    await this.runtime.respond(
      approval.wireId,
      approval.kind === 'question'
        ? {
            answers: Object.fromEntries(
              Object.entries(answers).map(([key, value]) => [key, { answers: value }]),
            ),
          }
        : (approval.responses?.[decision] ?? { decision }),
    );
    const current = this.approvals.get(id);
    if (current?.turnId === turnId && current.requestFingerprint === approval.requestFingerprint)
      this.approvals.delete(id);
    const thread = this.thread(threadId);
    if (
      thread.turnId === turnId &&
      thread.state === 'waiting-for-approval' &&
      ![...this.approvals.values()].some(
        (pending) => pending.threadId === threadId && pending.interactionMode !== 'async',
      )
    )
      thread.state = 'running';
    this.emit(threadId, 'approval-resolved');
    return { ok: true };
  }
  private asyncQuestionResolved(id: string): boolean {
    const action = this.state.asyncQuestionActions?.[id];
    if (!action) return false;
    if (action.decision === 'skip' || action.resolved === true) return true;
    if (action.resolved === false || !action.reply) return false;
    // Older actions did not distinguish saved intent from accepted input.
    // Reuse only a confirmed receipt; failed/unknown submissions stay pending.
    return this.state.receipts.some(
      (receipt) =>
        receipt.threadId === action.threadId &&
        receipt.clientMessageId === action.reply!.clientMessageId &&
        ['submitted', 'completed', 'interrupted'].includes(receipt.state),
    );
  }
  private async answerAsyncQuestion(
    threadId: string,
    id: string,
    turnId: string,
    decision: string,
    answers: Record<string, string[]>,
  ): Promise<{ ok: boolean }> {
    if (!(this.runtime instanceof DesktopRuntime)) throw new RuntimeError(409, '问题不可用');
    const actions = (this.state.asyncQuestionActions ??= {});
    let action = Object.hasOwn(actions, id) ? actions[id] : undefined;
    if (action) {
      if (
        action.threadId !== threadId ||
        action.turnId !== turnId ||
        action.decision !== decision ||
        JSON.stringify(action.answers) !== JSON.stringify(answers)
      )
        throw new RuntimeError(409, '此问题已有不同的提交，请刷新');
    } else {
      const owner = await this.runtime.ipc.follow(threadId, 5000, true);
      if (
        !owner ||
        owner.id !== threadId ||
        owner.hostId !== 'local' ||
        !this.runtime.ipc.hasOwner(threadId)
      )
        throw new RuntimeError(409, '问题的桌面连接已断开');
      await this.desktopSnapshot(this.thread(threadId), owner);
      // A concurrent submit may have persisted the immutable action while discovery yielded.
      if (Object.hasOwn(actions, id))
        return this.answerAsyncQuestion(threadId, id, turnId, decision, answers);
      const approval = this.approvals.get(id);
      const latest = desktopTurns(owner).at(-1);
      if (
        !approval ||
        approval.interactionMode !== 'async' ||
        approval.threadId !== threadId ||
        approval.turnId !== turnId ||
        latest?.turnId !== turnId
      )
        throw new RuntimeError(409, '问题已回答或任务已变化，请刷新');
      if (!['', 'skip'].includes(decision)) throw new RuntimeError(400, '无效问题操作');
      const question = desktopQuestions(latest.items).find(
        (q) => q.id === string(object(approval.questions[0]).id),
      );
      if (!question) throw new RuntimeError(409, '问题已变化');
      const selected = answers[question.id];
      if (
        decision === 'skip'
          ? Object.keys(answers).length > 0
          : Object.keys(answers).length !== 1 ||
            !Array.isArray(selected) ||
            !selected.length ||
            selected.some((v) => typeof v !== 'string' || !v.trim() || v.length > 10000) ||
            selected.length > 10
      )
        throw new RuntimeError(400, '请填写有效回复');
      const originalPermissionMode = currentPermissionMode(object(latest.params));
      if (decision !== 'skip' && latest.status !== 'inProgress' && !originalPermissionMode)
        throw new RuntimeError(409, '无法确认原问题的权限设置，回复未发送；请在桌面确认后刷新');
      action = {
        threadId,
        turnId,
        decision,
        answers,
        resolved: decision === 'skip',
        ...(decision === 'skip'
          ? {}
          : {
              reply: {
                hostId: this.hostId,
                threadId,
                clientMessageId: 'async-question-' + createHash('sha256').update(id).digest('hex'),
                text: desktopQuestionReply(question, selected!),
                attachmentIds: [],
                mode: latest.status === 'inProgress' ? ('steer' as const) : ('next-turn' as const),
                ...(latest.status === 'inProgress'
                  ? { expectedTurnId: turnId }
                  : { permissionMode: originalPermissionMode! }),
              },
            }),
      };
      actions[id] = action;
      this.save();
    }
    if (action.reply && !this.asyncQuestionResolved(id)) {
      if (
        action.reply.mode === 'next-turn' &&
        !action.reply.permissionMode &&
        !this.state.receipts.some(
          (receipt) => receipt.clientMessageId === action.reply!.clientMessageId,
        )
      )
        throw new RuntimeError(409, '原问题回复未固定权限，保留原操作且不会重新发送；请在桌面核对');
      const receipt = await this.submit(action.reply);
      if (!['submitted', 'completed', 'interrupted'].includes(receipt.state))
        throw new RuntimeError(
          409,
          receipt.state === 'uncertain'
            ? '回复结果未知，请刷新查询；不会重复发送'
            : receipt.error || '回复尚未确认，请刷新',
        );
      action.resolved = true;
      this.save();
    }
    this.approvals.delete(id);
    this.emit(threadId, 'question-resolved');
    return { ok: true };
  }
  private async modelCatalog(target?: ReplyTarget) {
    this.requireReady();
    const data: unknown[] = [];
    let cursor: string | undefined;
    const seen = new Set<string>();
    do {
      const result = await this.readWithReplyFence(target, () =>
        this.runtime.call('model/list', {
          limit: 100,
          ...(cursor ? { cursor } : {}),
        }),
      );
      data.push(...list(result.data));
      cursor = string(result.nextCursor) || undefined;
      if (cursor && seen.has(cursor)) throw new RuntimeError(502, '模型列表游标无效');
      if (cursor) seen.add(cursor);
    } while (cursor && data.length < 1000);
    return data.map(object).filter((v) => v.hidden !== true);
  }

  async usage(requestId: string) {
    const target = usageTarget(this.readingTarget());
    if (!this.ready) return unavailableUsage(target, requestId, 'offline');
    const response = await readCodexUsage(
      (method, params) => this.runtime.call(method, params),
      target,
      requestId,
    );
    if (JSON.stringify(target) !== JSON.stringify(usageTarget(this.readingTarget())))
      return unavailableUsage(usageTarget(this.readingTarget()), requestId, 'offline');
    return response;
  }

  async models() {
    return {
      models: (await this.modelCatalog()).map((value) => {
        const v = object(value);
        return {
          id: string(v.model) || string(v.id),
          name: string(v.displayName) || string(v.id),
          isDefault: v.isDefault === true,
          defaultEffort: string(v.defaultReasoningEffort) || null,
          defaultSpeed: currentSpeed(v.defaultServiceTier ?? null),
          supportedSpeeds: [...modelSpeedTiers(v).keys()],
          supportedEfforts: list(v.supportedReasoningEfforts)
            .map((value) => string(object(value).reasoningEffort))
            .filter(Boolean),
        };
      }),
    };
  }

  private async validateGeneration(
    reply: Pick<Reply, 'model' | 'effort' | 'speed'>,
    thread: Thread,
    target?: ReplyTarget,
  ): Promise<Map<string, string>> {
    const catalog = await this.readWithReplyFence(target, () => this.modelCatalog(target));
    const selected =
      catalog.find(
        (v) => (string(v.model) || string(v.id)) === (reply.model || thread.runtimeModel),
      ) ?? (!reply.model && !thread.runtimeModel ? catalog.find((v) => v.isDefault) : undefined);
    if (!selected) throw new RuntimeError(400, '当前电脑未提供所选模型，请刷新模型列表');
    if (
      reply.effort &&
      list(selected.supportedReasoningEfforts).length &&
      !list(selected.supportedReasoningEfforts).some(
        (v) => object(v).reasoningEffort === reply.effort,
      )
    )
      throw new RuntimeError(400, '所选模型不支持此推理强度');
    const tiers = modelSpeedTiers(selected);
    if (reply.speed && !tiers.has(reply.speed))
      throw new RuntimeError(400, '所选模型不支持此速度模式');
    return tiers;
  }

  private event(method: string, params: Record<string, unknown>): void {
    if (this.closed) return;
    const thread = this.state.threads.find((v) => (v.codexId ?? v.id) === params.threadId);
    if (!thread) return;
    if (method === 'desktop/model-settings') {
      this.emit(thread.id, method, false);
      return;
    }
    if (method === 'desktop/observation') {
      if (this.loaded.has(thread.id) && this.projectDesktopObservation(thread))
        this.emit(thread.id, method, false);
      return;
    }
    if (method === 'desktop/snapshot') {
      if (this.loaded.has(thread.id) || this.restoring.has(thread.id))
        void this.desktopSnapshot(thread, object(params.state)).catch(() => undefined);
      else this.desktopSummary(thread, object(params.state));
      // Controls/approvals are immediate; complete rich messages publish atomically later.
      this.emit(thread.id, method);
      return;
    }
    if (method === 'desktop/unavailable') {
      this.desktopSuggestionSources.delete(thread.id);
      const job = this.desktopProjections.get(thread.id);
      if (job) {
        this.desktopProjections.delete(thread.id);
        job.resolve();
      }
      thread.state = 'unknown';
      thread.turnId = null;
      thread.proposedPlan = undefined;
      thread.collaborationMode = undefined;
      thread.runtimeModel = undefined;
      thread.runtimeEffort = undefined;
      thread.permissionMode = undefined;
      thread.speed = undefined;
      for (const message of thread.messages)
        if (message.kind === 'contextCompaction' && message.status === 'inProgress')
          message.status = 'unknown';
      for (const [id, approval] of this.approvals)
        if (approval.threadId === thread.id) this.approvals.delete(id);
      this.projectDesktopObservation(thread);
      this.emit(thread.id, method);
      return;
    }
    const threadId = thread.id;
    if (this.mode === 'shared-runtime') {
      if (method === 'turn/started') this.sharedLiveItems.set(threadId, new Set());
      if (method.startsWith('item/')) {
        const id = string(params.itemId) || string(object(params.item).id);
        if (id) {
          let ids = this.sharedLiveItems.get(threadId);
          if (!ids) this.sharedLiveItems.set(threadId, (ids = new Set()));
          ids.add(id);
        }
      }
      if (method === 'thread/deleted') {
        this.removeSharedThread(threadId);
        return;
      }
      if (method === 'thread/archived' || method === 'thread/unarchived') {
        thread.archived = method === 'thread/archived';
        this.loaded.delete(threadId);
        this.emit(threadId, method);
        return;
      }
      if (method === 'thread/status/changed') {
        const status = string(object(params.status).type);
        if (status === 'notLoaded') {
          this.loaded.delete(threadId);
          thread.state = 'unknown';
          thread.turnId = null;
        } else if (status === 'active')
          thread.state = list(object(params.status).activeFlags).includes('waitingOnApproval')
            ? 'waiting-for-approval'
            : 'running';
        else if (status === 'systemError') thread.state = 'failed';
        else if (status === 'idle') {
          thread.state = 'idle';
          thread.turnId = null;
        } else return;
        this.emit(threadId, method);
        return;
      }
    }
    if (method === 'turn/started') {
      this.desktopSuggestionSources.delete(thread.id);
      thread.turnId = string(object(params.turn).id);
      thread.notificationLastTurn = { id: thread.turnId, outcome: 'inProgress' };
      if (this.mode === 'shared-runtime') thread.lastTurnStatus = 'inProgress';
      const taskSummary = notificationUserSummary(object(params.turn).items);
      this.notificationTasks.set(threadId, {
        runId: thread.turnId,
        ...(taskSummary ? { taskSummary } : {}),
      });
      this.startedTurns.add(thread.turnId);
      thread.state = 'running';
      thread.error = null;
      thread.plan = [];
      thread.proposedPlan = undefined;
    } else if (method === 'turn/completed') {
      const turn = object(params.turn);
      thread.notificationLastTurn = { id: string(turn.id), outcome: string(turn.status) };
      if (this.mode === 'shared-runtime') thread.lastTurnStatus = string(turn.status);
      const taskSummary = notificationUserSummary(turn.items);
      if (taskSummary)
        this.notificationTasks.set(threadId, { runId: string(turn.id), taskSummary });
      const plan =
        list(turn.items)
          .map(object)
          .findLast((v) => v.type === 'plan') ??
        (thread.proposedPlan && thread.proposedPlan.turnId === turn.id
          ? { id: thread.proposedPlan.id, text: thread.proposedPlan.text }
          : undefined);
      thread.proposedPlan =
        turn.status === 'completed' && plan && string(plan.text)
          ? { id: string(plan.id), turnId: string(turn.id), text: string(plan.text) }
          : undefined;
      this.startedTurns.delete(string(turn.id));
      thread.state = turn.status === 'failed' ? 'failed' : 'idle';
      thread.turnId = null;
      thread.error = string(object(turn.error).message) || null;
      for (const message of thread.messages)
        if (message.status === 'inProgress') message.status = string(turn.status);
      for (const receipt of this.state.receipts)
        if (receipt.threadId === threadId && receipt.turnId === turn.id) {
          receipt.state = string(turn.status);
          receipt.error = turn.status === 'failed' ? thread.error : null;
        }
      for (const [id, approval] of this.approvals)
        if (approval.threadId === threadId) this.approvals.delete(id);
      this.saveBackground();
    } else if (method === 'item/started' || method === 'item/completed') {
      const item = object(params.item);
      if (item.type === 'plan' && string(item.text))
        thread.proposedPlan = {
          id: string(item.id),
          turnId: string(params.turnId),
          text: string(item.text),
        };
      if (object(params.item).type === 'userMessage' && string(params.turnId)) {
        const runId = string(params.turnId);
        const taskSummary = notificationUserSummary([params.item]);
        if (thread.notificationLastTurn?.id === runId && taskSummary)
          this.notificationTasks.set(threadId, { runId, taskSummary });
        const receipt = this.state.receipts.find(
          (r) => r.threadId === threadId && r.clientMessageId === object(params.item).clientId,
        );
        if (receipt) receipt.turnId = string(params.turnId);
      }
      void this.upsert(thread, {
        ...object(params.item),
        runId: params.turnId,
        status:
          method === 'item/started'
            ? 'inProgress'
            : string(object(params.item).status) || 'completed',
      })
        .then(() => this.emit(threadId, method, false))
        .catch(() => undefined);
    } else if (
      method === 'item/agentMessage/delta' ||
      method === 'item/plan/delta' ||
      method === 'item/commandExecution/outputDelta' ||
      method === 'item/reasoning/summaryTextDelta'
    ) {
      const id = string(params.itemId);
      let message = thread.messages.find((v) => v.id === id);
      if (!message) {
        message = {
          id,
          ...(string(params.turnId) ? { runId: string(params.turnId) } : {}),
          role:
            method.includes('agentMessage') || method.includes('plan/delta') ? 'assistant' : 'tool',
          text: '',
          files: [],
          kind: method.includes('agentMessage')
            ? 'agentMessage'
            : method.includes('plan/delta')
              ? 'plan'
              : 'progress',
          status: 'inProgress',
          detail: '',
          attachments: [],
        };
        thread.messages.push(message);
      }
      if (method.includes('outputDelta'))
        message.detail = (message.detail + string(params.delta)).slice(-200_000);
      else message.text += string(params.delta);
      message.status = 'inProgress';
    } else if (method === 'turn/plan/updated')
      thread.plan = list(params.plan).map((v) => ({
        step: string(object(v).step),
        status: string(object(v).status),
      }));
    else if (method === 'serverRequest/resolved') {
      for (const [id, approval] of this.approvals)
        if (approval.wireId === params.requestId) this.approvals.delete(id);
    } else if (method === 'error') thread.error = string(object(params.error).message);
    else return;
    this.emit(threadId, method);
  }

  private desktopSummary(thread: Thread, state: Record<string, unknown>) {
    const suggestionSource = this.completedDesktopSuggestionSource(thread.id, state);
    if (suggestionSource) this.desktopSuggestionSources.set(thread.id, suggestionSource);
    else this.desktopSuggestionSources.delete(thread.id);
    // Only the desktop decides what was read. History timestamps and task
    // completion alone cannot establish unread state.
    thread.hasUnreadTurn =
      typeof state.hasUnreadTurn === 'boolean' ? state.hasUnreadTurn : undefined;
    const turns = desktopTurns(state);
    const latestStatus = string(
      turns.findLast((turn) => turn.turnId !== thread.desktopInitializationTurnId)?.status,
    );
    thread.lastTurnStatus = ['inProgress', 'completed', 'failed', 'interrupted'].includes(
      latestStatus,
    )
      ? latestStatus
      : undefined;
    const reply = this.desktopReadReply(thread, state);
    if (reply) {
      const sourceVersion = this.readContentVersion(reply);
      if (thread.readHeadSourceVersion !== sourceVersion) {
        this.updateReadHead(thread, reply);
        thread.readHeadSourceVersion = sourceVersion;
      }
    }
    const active = turns.findLast((v) => v.status === 'inProgress');
    const priorTurn = thread.turnId;
    thread.turnId = active ? string(active.turnId) || null : null;
    thread.state =
      object(state.threadRuntimeStatus).type === 'active'
        ? 'running'
        : turns.at(-1)?.status === 'failed'
          ? 'failed'
          : 'idle';
    if (
      thread.turnId &&
      list(state.requests).some((value) => {
        const request = object(value),
          params = object(request.params);
        return (
          [
            'item/commandExecution/requestApproval',
            'item/fileChange/requestApproval',
            'item/tool/requestUserInput',
          ].includes(string(request.method)) &&
          (typeof request.id === 'string' || typeof request.id === 'number') &&
          params.threadId === thread.id &&
          params.turnId === thread.turnId
        );
      })
    )
      thread.state = 'waiting-for-approval';
    if (string(state.title)) thread.title = string(state.title);
    return { turns, active, priorTurn };
  }

  private desktopReadReply(thread: Thread, state: Record<string, unknown>) {
    const finished = desktopTurns(state).findLast(
      (turn) => turn.turnId !== thread.desktopInitializationTurnId && turn.status !== 'inProgress',
    );
    const reply = list(finished?.items)
      .map(object)
      .findLast((item) => item.type === 'agentMessage');
    return reply
      ? {
          ...renderItem(reply),
          status: ['interrupted', 'failed'].includes(string(finished?.status))
            ? string(finished?.status)
            : 'completed',
        }
      : undefined;
  }
  private projectReadHead(
    thread: Thread,
    source: Pick<RenderItem, 'id' | 'text' | 'status'>,
  ): void {
    // Only a projection of the exact canonical latest reply can move its read
    // position. Older pages and superseded asynchronous projections cannot.
    if (
      thread.readHead?.messageId !== source.id ||
      thread.readHeadSourceVersion !== this.readContentVersion(source)
    )
      return;
    const projected = thread.messages.find(
      (message) =>
        message.id === source.id &&
        message.role === 'assistant' &&
        message.status === source.status,
    );
    if (projected) this.updateReadHead(thread, projected);
  }
  private projectDesktopObservation(thread: Thread): boolean {
    if (!(this.runtime instanceof DesktopRuntime) || this.runtime.ipc.hasOwner(thread.id))
      return false;
    const observation = this.runtime.ipc.observation(thread.id);
    const tip = this.desktopHistoryTips.get(thread.id);
    if (
      !observation ||
      !tip ||
      Date.now() - (this.desktopHistoryReads.get(thread.id) ?? 0) >= 10_000
    )
      return false;
    let changed = false;
    for (const { turnId, item, receivedAt } of observation.items) {
      // Only the independently read exact tip can anchor new observed items.
      // A retired stream, another turn, or an unknown ancestor cannot move it.
      if (
        turnId !== tip ||
        (!observation.seeded && receivedAt <= (this.desktopHistoryReads.get(thread.id) ?? 0)) ||
        ['userMessage', 'steeringUserMessage'].includes(string(item.type))
      )
        continue;
      const { paths: _paths, ...message } = renderItem({ ...item, runId: turnId });
      if (!message.id || (message.kind === 'reasoning' && !message.text)) continue;
      const index = thread.messages.findIndex((v) => v.id === message.id);
      const previous = index >= 0 ? thread.messages[index] : undefined;
      if (
        previous &&
        (previous.runId !== turnId ||
          (['completed', 'failed', 'interrupted'].includes(previous.status) &&
            message.status === 'inProgress') ||
          !message.text.startsWith(previous.text) ||
          !message.detail.startsWith(previous.detail))
      )
        continue;
      const next = {
        ...message,
        attachments: index >= 0 ? thread.messages[index]!.attachments : [],
      };
      if (index >= 0) thread.messages[index] = next;
      else thread.messages.push(next);
      changed = true;
    }
    return changed;
  }

  private reconcileReadReceiptEvidence(
    thread: Thread,
    turns: import('./history-reader.js').ReadTurnEvidence[],
  ): void {
    for (const receipt of this.state.receipts) {
      if (receipt.threadId !== thread.id || receipt.state !== 'uncertain') continue;
      const matching = turns.filter(
        (turn) =>
          receipt.reply.attachmentIds.length === 0 &&
          turn.acceptedMessageIds.includes(receipt.clientMessageId) &&
          turn.acceptedMessageHashes?.[receipt.clientMessageId] ===
            createHash('sha256').update(receipt.reply.text).digest('hex'),
      );
      const accepted = matching.length === 1 ? matching[0] : undefined;
      if (
        accepted &&
        ['inProgress', 'completed', 'failed', 'interrupted'].includes(accepted.status)
      ) {
        receipt.acceptedTurnId = accepted.id;
        receipt.turnId = accepted.id;
        receipt.state = accepted.status === 'inProgress' ? 'submitted' : accepted.status;
        // Persisted acceptance settles this exact message only. Runtime control
        // still requires the original owner's complete current snapshot.
        this.historicalReceiptContexts.delete(receipt.clientMessageId);
        continue;
      }
      const index = turns.findIndex(
        (turn) =>
          turn.id === receipt.turnId &&
          ['completed', 'failed', 'interrupted'].includes(turn.status),
      );
      const later = index >= 0 ? turns.slice(index + 1).at(-1) : undefined;
      if (later) this.historicalReceiptContexts.set(receipt.clientMessageId, later.id);
    }
  }

  private desktopControls(thread: Thread, state: Record<string, unknown>): void {
    if (!(this.runtime instanceof DesktopRuntime)) return;
    const { turns, active, priorTurn } = this.desktopSummary(thread, state);
    thread.proposedPlan = desktopProposedPlan(state);
    const collaboration = object(
      object(state.latestThreadSettings).collaborationMode ?? state.latestCollaborationMode,
    );
    thread.collaborationMode = ['plan', 'default'].includes(string(collaboration.mode))
      ? string(collaboration.mode)
      : undefined;
    const currentModel = desktopModelSettings(state);
    thread.runtimeModel = currentModel.model || undefined;
    thread.runtimeEffort = currentModel.effort;
    const latest = turns.at(-1);
    thread.speed = desktopSpeed(state, latest);
    thread.permissionMode = desktopPermissionMode({ ...state, turns });
    const cwd = string(state.cwd);
    if (isAbsolute(cwd) && state.id === thread.id && this.runtime.ipc.hasOwner(thread.id))
      thread.desktopDirectory = resolve(cwd);
    if (thread.turnId) this.startedTurns.add(thread.turnId);
    thread.plan = list(
      list((active ?? turns.at(-1))?.items)
        .map(object)
        .findLast((v) => v.type === 'todo-list')?.plan,
    ).map((v) => ({ step: string(object(v).step), status: string(object(v).status) }));
    thread.error = string(object(active?.error).message) || null;
    const pendingIds = new Set<string>();
    for (const value of list(state.requests)) {
      const request = object(value),
        params = object(request.params);
      const method = string(request.method);
      const projection = approvalRequest(method, params);
      const target =
        method === 'item/commandExecution/requestApproval'
          ? 'thread-follower-command-approval-decision'
          : method === 'item/fileChange/requestApproval'
            ? 'thread-follower-file-approval-decision'
            : method === 'item/tool/requestUserInput'
              ? 'thread-follower-submit-user-input'
              : (projection?.desktopMethod ?? '');
      if (request.completed === true) continue;
      if (
        !target ||
        params.threadId !== thread.id ||
        (params.turnId ? params.turnId !== thread.turnId : !active)
      )
        continue;
      if (!(typeof request.id === 'string' || typeof request.id === 'number')) continue;
      const id = `${thread.id}:${String(request.id)}`;
      pendingIds.add(id);
      this.runtime.registerApproval(id, thread.id, target, request.id, params);
      this.approvals.set(id, {
        id,
        wireId: id,
        notificationRequestId: String(request.id),
        requestFingerprint: JSON.stringify([method, params]),
        threadId: thread.id,
        turnId: string(params.turnId) || thread.turnId!,
        kind: target.endsWith('submit-user-input') ? 'question' : 'approval',
        title: string(params.command) || 'Codex 需要你的审批',
        detail: string(params.reason),
        questions: list(params.questions),
        decisions: list(params.availableDecisions).filter(
          (v): v is string => typeof v === 'string',
        ),
      });
      const approval = this.approvals.get(id)!;
      if (projection) {
        const { desktopMethod: _desktopMethod, ...fields } = projection;
        Object.assign(approval, fields);
      }
      if (approval.kind === 'approval' && !approval.decisions.length)
        approval.decisions = ['accept', 'decline', 'cancel'];
    }
    const latestTurn = turns.at(-1);
    if (latestTurn && latestTurn.turnId !== thread.desktopInitializationTurnId) {
      const answered = desktopQuestionAnswers(turns.flatMap((turn) => list(turn.items)));
      for (const question of desktopQuestions(latestTurn.items)) {
        const id = `${thread.id}:async:${question.id}`;
        if (answered.has(question.id) || this.asyncQuestionResolved(id)) continue;
        pendingIds.add(id);
        this.approvals.set(id, {
          id,
          wireId: id,
          notificationRequestId: question.id,
          threadId: thread.id,
          turnId: string(latestTurn.turnId),
          kind: 'question',
          interactionMode: 'async',
          sourceItemId: question.sourceItemId,
          title: '问题',
          detail: '',
          decisions: [],
          questions: [
            {
              id: question.id,
              question: question.title,
              options: question.options.map((label) => ({ label, description: '' })),
              custom: true,
            },
          ],
        });
      }
    }
    for (const [id, approval] of this.approvals)
      if (approval.threadId === thread.id && !pendingIds.has(id)) this.approvals.delete(id);
    if (
      [...this.approvals.values()].some(
        (v) => v.threadId === thread.id && v.interactionMode !== 'async',
      )
    )
      thread.state = 'waiting-for-approval';
    for (const receipt of this.state.receipts.filter((v) => v.threadId === thread.id)) {
      const turn = turns.find(
        (v) =>
          object(v.params).clientUserMessageId === receipt.clientMessageId ||
          list(v.items).some(
            (item) =>
              (object(item).type === 'userMessage' &&
                object(item).clientId === receipt.clientMessageId) ||
              (object(item).type === 'steeringUserMessage' &&
                object(item).status === 'accepted' &&
                object(item).clientUserMessageId === receipt.clientMessageId),
          ) ||
          (receipt.reply.mode !== 'steer' &&
            Boolean(v.turnId) &&
            Boolean(receipt.turnId) &&
            v.turnId === receipt.turnId) ||
          (Boolean(receipt.acceptedTurnId) && v.turnId === receipt.acceptedTurnId),
      );
      if (turn) {
        receipt.turnId = string(turn.turnId) || receipt.turnId;
        if (receipt.turnId) receipt.acceptedTurnId = receipt.turnId;
        receipt.state = turn.status === 'inProgress' ? 'submitted' : string(turn.status);
        receipt.error =
          turn.status === 'failed' ? string(object(turn.error).message) || receipt.error : null;
        if (turn.status === 'failed') {
          // Only the authenticated owner's ordered turns can retire a task
          // failure. Unmatched/unknown submissions and rejected steers stay visible.
          const later = turns
            .slice(turns.indexOf(turn) + 1)
            .findLast(
              (value) =>
                string(value.turnId) &&
                ['inProgress', 'completed', 'interrupted', 'failed'].includes(string(value.status)),
            );
          if (later) receipt.supersededByTurnId = string(later.turnId);
        } else delete receipt.supersededByTurnId;
      }
    }
    if (priorTurn && !thread.turnId) this.startedTurns.delete(priorTurn);
  }
  private desktopSnapshot(thread: Thread, state: Record<string, unknown>): Promise<void> {
    this.desktopControls(thread, state);
    const existing = this.desktopProjections.get(thread.id);
    if (existing) {
      // One in-flight projection plus the latest snapshot, never a transcript queue.
      existing.state = state;
      existing.version++;
      return existing.work;
    }
    let resolve!: () => void;
    let reject!: (error: unknown) => void;
    const work = new Promise<void>((done, fail) => {
      resolve = done;
      reject = fail;
    });
    this.desktopProjections.set(thread.id, {
      state,
      version: 0,
      work,
      resolve,
      reject,
      running: false,
    });
    this.runDesktopProjections();
    return work;
  }
  private runDesktopProjections(): void {
    if (this.closed) return;
    for (const [id, job] of this.desktopProjections) {
      if (this.activeDesktopProjections >= 2) break;
      if (job.running) continue;
      job.running = true;
      this.activeDesktopProjections++;
      void (async () => {
        try {
          while (!this.closed && this.desktopProjections.get(id) === job) {
            const version = job.version;
            const original = this.thread(id);
            const thread = { ...original, messages: original.messages };
            await this.projectDesktopMessages(
              thread,
              job.state,
              () => !this.closed && this.desktopProjections.get(id) === job,
            );
            if (this.closed || this.desktopProjections.get(id) !== job) break;
            if (version !== job.version) continue;
            if (this.runtime instanceof DesktopRuntime && this.runtime.ipc.hasOwner(id)) {
              // Same authoritative projection must not rewrite a full history
              // cache just because the desktop sent another snapshot object.
              const historyChanged = !isDeepStrictEqual(original.messages, thread.messages);
              if (historyChanged) original.messages = thread.messages;
              const source = this.desktopReadReply(original, job.state);
              if (source) this.projectReadHead(original, source);
              this.verifiedDesktopMessageIds.set(id, new Set(thread.messages.map((v) => v.id)));
              while (this.verifiedDesktopMessageIds.size > 32)
                this.verifiedDesktopMessageIds.delete(
                  this.verifiedDesktopMessageIds.keys().next().value!,
                );
              this.emit(id, 'desktop/snapshot', false, historyChanged);
            }
            break;
          }
          job.resolve();
        } catch (error) {
          job.reject(error);
        } finally {
          if (this.desktopProjections.get(id) === job) this.desktopProjections.delete(id);
          this.activeDesktopProjections--;
          this.runDesktopProjections();
        }
      })();
    }
  }
  private async projectDesktopMessages(
    thread: Thread,
    state: Record<string, unknown>,
    valid: () => boolean = () => true,
  ): Promise<void> {
    const turns = desktopTurns(state);
    const previous = thread.messages;
    thread.messages = [];
    const indexes = new Map<string, number>();
    const initializationIds = new Set(
      turns
        .filter((turn) => turn.turnId === thread.desktopInitializationTurnId)
        .flatMap((turn) => list(turn.items).map((item) => string(object(item).id))),
    );
    let projected = 0;
    for (const turn of turns) {
      for (const item of list(turn.items)) {
        if (!valid()) return;
        if (++projected % 32 === 0) await setImmediate();
        if (turn.turnId === thread.desktopInitializationTurnId) continue;
        if (['todo-list', 'planImplementation', 'steered'].includes(string(object(item).type)))
          continue;
        const wire = object(item);
        if (wire.type === 'steeringUserMessage') {
          if (!string(wire.serverUserMessageId)) continue;
          await this.upsert(
            thread,
            {
              type: 'userMessage',
              id: wire.serverUserMessageId || wire.id,
              clientId: wire.serverClientUserMessageId || wire.clientUserMessageId,
              content: wire.input,
              status: wire.status === 'accepted' ? 'completed' : 'inProgress',
              runId: turn.turnId,
            },
            indexes,
          );
          continue;
        }
        const completed = object(turn.agentMessageCompletedAtMsById);
        const status =
          wire.type === 'contextCompaction'
            ? turn.status === 'completed' || wire.completed === true
              ? 'completed'
              : wire.completed === false
                ? turn.status === 'inProgress'
                  ? 'inProgress'
                  : ['interrupted', 'failed'].includes(string(turn.status))
                    ? turn.status
                    : 'unknown'
                : wire.status
            : ['agentMessage', 'plan'].includes(string(wire.type))
              ? turn.status === 'inProgress' && !Object.hasOwn(completed, string(wire.id))
                ? 'inProgress'
                : ['interrupted', 'failed'].includes(string(turn.status))
                  ? turn.status
                  : 'completed'
              : wire.status === 'inProgress' && turn.status !== 'inProgress'
                ? turn.status
                : wire.status;
        await this.upsert(
          thread,
          { ...wire, runId: turn.turnId, ...(typeof status === 'string' ? { status } : {}) },
          indexes,
        );
      }
      if (turn.status === 'failed' && string(turn.turnId))
        await this.upsert(
          thread,
          {
            id: `turn-failure-${string(turn.turnId)}`,
            type: 'turnFailure',
            text: string(object(turn.error).message) || '任务执行失败，电脑未提供错误详情。',
            status: 'failed',
            runId: turn.turnId,
          },
          indexes,
        );
    }
    thread.messages.unshift(
      ...retainedDesktopHistory(
        previous,
        thread.messages,
        initializationIds,
        new Set(
          turns
            .filter((turn) => turn.status === 'completed')
            .map((turn) => string(turn.turnId))
            .filter(Boolean),
        ),
      ),
    );
  }
  async poll(
    after: number,
    epoch: string,
    signal: AbortSignal,
    threadId?: string,
    windowed = false,
  ) {
    if (threadId) {
      this.thread(threadId);
      if (this.runtime instanceof DesktopRuntime)
        await this.runtime.ipc.refreshModelSettings(threadId);
      await this.refreshNativeQueue(threadId);
    }
    const reset =
      epoch !== this.epoch || after < (this.events[0]?.seq ?? this.seq) - 1 || after > this.seq;
    if (!reset && after === this.seq && !signal.aborted)
      await new Promise<void>((done) => {
        const finish = () => {
          clearTimeout(timer);
          clearInterval(queueTimer);
          this.listeners.delete(finish);
          signal.removeEventListener('abort', finish);
          done();
        };
        const timer = setTimeout(finish, 15_000);
        const queueTimer = setInterval(() => {
          if (threadId) {
            void this.refreshNativeQueue(threadId).catch(() => undefined);
            if (this.runtime instanceof DesktopRuntime)
              void this.runtime.ipc.refreshModelSettings(threadId).catch(() => undefined);
          }
        }, 1000);
        this.listeners.add(finish);
        signal.addEventListener('abort', finish, { once: true });
      });
    if (!signal.aborted && !reset && this.seq > after)
      await new Promise<void>((done) => setTimeout(done, 75));
    // A latest snapshot per thread coalesces bursts while retaining actual streamed text/progress.
    const latest = new Map<string, (typeof this.events)[number]>();
    for (const event of this.events)
      if (event.seq > after && (!threadId || event.threadId === threadId))
        latest.set(event.threadId, event);
    return {
      hostId: this.hostId,
      epoch: this.epoch,
      ready: this.ready,
      capabilities: this.capabilities,
      cursor: this.seq,
      reset,
      events: reset
        ? this.state.threads
            .filter((t) => !threadId || t.id === threadId)
            .map((t) => ({
              seq: this.seq,
              threadId: t.id,
              kind: 'resync',
              snapshot: this.history(t.id, windowed),
            }))
        : [...latest.values()].map((event) => ({
            ...event,
            snapshot: this.history(event.threadId, windowed),
          })),
    };
  }
  close(): void {
    if (this.closed) return;
    this.closed = true;
    for (const job of this.desktopProjections.values()) job.resolve();
    this.desktopProjections.clear();
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    let failure: unknown;
    for (const close of [
      () => this.save(),
      () => this.persistence.close(true),
      () => this.desktopCreationDriver?.close(),
      () => this.desktopCreation?.close(),
      () => this.desktopForks?.close(),
      () => this.nativeQueue?.close(),
      () => this.runtime.close(),
      () => closeWindowsPrivateStateBackend(),
    ]) {
      try {
        close();
      } catch (error) {
        failure ??= error;
      }
    }
    if (failure) throw failure;
  }
}
