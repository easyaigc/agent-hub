import { createHash } from 'node:crypto';
import {
  existsSync,
  mkdirSync,
  readFileSync,
  realpathSync,
  renameSync,
  writeFileSync,
} from 'node:fs';
import { dirname, join } from 'node:path';
import { DesktopCommandNotSubmitted, DesktopRuntime, desktopTurns } from './desktop-ipc.js';
import { object, list, string, type WireObject } from './runtime.js';
import {
  BeforeDesktopSubmit,
  type DesktopCreationDriver,
  type DesktopCreationPlan,
} from './desktop-creation.js';
import { creationGates } from './desktop-creation-driver.js';
import { assertPrivateFile, protectWindowsStateFile } from './desktop-platform.js';

interface ControllerConfig {
  hostId: string;
  threadId: string;
  desktopVersion: string;
  validationOnly?: boolean;
  openOnly?: boolean;
  verification?: string;
  openVerification?: string;
}
interface Command {
  id: string;
  kind: 'create' | 'open';
  request: string;
  initializer: string;
  title: string;
  directory: string | null;
  nativeProjectId?: string;
  targetId: string | null;
  phase: 'prepared' | 'sent' | 'finished' | 'rejected';
  rejection?: string;
  turnId: string | null;
  resultId: string | null;
}
// Bind verification to this process's loaded build. A development rebuild can
// replace these files while the existing Host continues running the old code.
const loadedAdapterDigest = (() => {
  const hash = createHash('sha256');
  for (const name of [
    'desktop-controller.js',
    'desktop-ipc.js',
    'desktop-framing.js',
    'runtime-session.js',
    'desktop-history-identity.js',
    'attachment-files.js',
    'desktop-questions.js',
    'runtime-persistence.js',
    'runtime-diagnostics.js',
    'desktop-creation.js',
    'windows-process.js',
    'desktop-private-state-native.js',
    'desktop-private-state-native-worker.js',
    'desktop-trace-native.js',
    'desktop-trace-native-worker.js',
    'desktop-suggestion-read-worker.js',
    'desktop-suggestion-worker-client.js',
    'desktop-suggestion-worker-limits.js',
    'windows-trace-diagnostics.js',
  ])
    hash.update(readFileSync(new URL(name, import.meta.url)));
  return hash.digest('hex');
})();
export function controllerAdapterDigest(): string {
  return loadedAdapterDigest;
}
const safeFile = assertPrivateFile;
const jsonResults = (item: WireObject): WireObject[] =>
  list(object(item.result).content).flatMap((value) => {
    try {
      return [object(JSON.parse(string(object(value).text)))];
    } catch {
      return [];
    }
  });
const active = (state: WireObject) =>
  desktopTurns(state).some((turn) => turn.status === 'inProgress');
const pause = () => new Promise<void>((done) => setTimeout(done, 350));

/** Uses an existing original-desktop chat. Never opens a Codex executor or bypasses App Tools authorization. */
export class DesktopControllerBridge implements DesktopCreationDriver {
  readonly backgroundSubmission = true;
  readonly inputTransport = 'desktop-ipc';
  readonly projectAssignment = 'desktop-sidebar';
  available = false;
  openAvailable = false;
  validationOnly = false;
  openValidationOnly = false;
  reason: string | null = 'Codex 控制聊天未配置';
  private config: ControllerConfig | undefined;
  private closed = false;
  private tail: Promise<unknown> = Promise.resolve();
  private work = new Map<string, Promise<string>>();
  private creations = new Map<string, Promise<string>>();
  private refreshing: Promise<void> | undefined;
  constructor(
    private readonly path: string,
    private readonly version: string | (() => string),
    private readonly hostId: string,
    private readonly runtime: DesktopRuntime,
    private readonly directory: string,
    private readonly firstInput: (plan: DesktopCreationPlan, id: string) => Promise<void>,
  ) {
    mkdirSync(directory, { recursive: true, mode: 0o700 });
  }
  async refresh(): Promise<void> {
    if (this.refreshing) return this.refreshing;
    const work = this.refreshNow();
    this.refreshing = work;
    try {
      await work;
    } finally {
      if (this.refreshing === work) this.refreshing = undefined;
    }
  }
  private async refreshNow(): Promise<void> {
    let available = false;
    let openAvailable = false;
    let validationOnly = false;
    let openValidationOnly = false;
    let nextConfig: ControllerConfig | undefined;
    let reason = 'Codex 控制聊天配置不可用';
    try {
      if (this.closed) {
        reason = 'Codex 创建桥接已关闭';
        throw Error('closed');
      }
      safeFile(this.path);
      const config = JSON.parse(readFileSync(this.path, 'utf8')) as ControllerConfig;
      const version = typeof this.version === 'function' ? this.version() : this.version;
      reason = 'Codex 控制聊天配置与当前电脑或桌面版本不一致';
      if (
        config.hostId !== this.hostId ||
        config.desktopVersion !== version ||
        !/^[a-f0-9-]{36}$/.test(config.threadId)
      )
        throw Error('target');
      nextConfig = config;
      validationOnly = config.validationOnly === true;
      const openOnly = config.openOnly === true;
      const verified = (
        path: string | undefined,
        kind: 'real-desktop-controller-open' | 'real-desktop-controller',
        describe: boolean,
      ): boolean => {
        if (describe) reason = 'Codex 新建和打开尚未完成真实验收';
        if (!path) return false;
        try {
          if (describe) reason = 'Codex 新建和打开验收记录不可用，请重新验收';
          safeFile(path);
          const proof = JSON.parse(readFileSync(path, 'utf8'));
          if (
            proof.kind !== kind ||
            proof.hostId !== this.hostId ||
            proof.controllerThreadId !== config.threadId ||
            proof.desktopVersion !== version ||
            proof.unloadedOpen !== true ||
            (kind === 'real-desktop-controller-open'
              ? proof.gates?.open !== true
              : !creationGates.every((gate) => proof.gates?.[gate] === true))
          )
            return false;
          if (describe) reason = 'Codex 桥接版本与验收记录不一致，新建和打开需要重新验收';
          return proof.adapterSha256 === controllerAdapterDigest();
        } catch {
          return false;
        }
      };
      const primaryVerified = verified(
        config.verification,
        openOnly ? 'real-desktop-controller-open' : 'real-desktop-controller',
        !validationOnly,
      );
      const independentOpenVerified = verified(
        config.openVerification,
        'real-desktop-controller-open',
        false,
      );
      const creationReason = reason;
      reason = 'Codex 控制聊天未加载，请在 Codex 中打开控制聊天';
      // Catalog reads must remain usable when the optional control chat is
      // unloaded. A later refresh can confirm its owner and enable controls.
      const state = await this.runtime.ipc.follow(config.threadId, 1000, true);
      if (!state || !this.runtime.ipc.hasOwner(config.threadId)) throw Error('owner');
      available = !openOnly && (validationOnly || primaryVerified);
      openAvailable = validationOnly || primaryVerified || independentOpenVerified;
      openValidationOnly = validationOnly && !primaryVerified && !independentOpenVerified;
      reason = available || (openOnly && openAvailable) ? '' : creationReason;
    } catch {
      // Publish the completed probe together. Concurrent catalog/event reads
      // must not observe a temporary reset while a valid owner is being checked.
    }
    this.config = nextConfig;
    this.available = !this.closed && available;
    this.openAvailable = !this.closed && openAvailable;
    this.validationOnly = validationOnly;
    this.openValidationOnly = openValidationOnly;
    this.reason = this.closed ? 'Codex 创建桥接已关闭' : reason || null;
  }
  private file(id: string): string {
    if (
      !/^[a-zA-Z0-9_-]{8,100}$/.test(id) ||
      ['constructor', 'prototype', '__proto__'].includes(id)
    )
      throw Error('Invalid operation ID');
    return join(this.directory, id + '.json');
  }
  private save(command: Command): void {
    const path = this.file(command.id);
    writeFileSync(path + '.tmp', JSON.stringify(command), { mode: 0o600 });
    protectWindowsStateFile(path + '.tmp');
    renameSync(path + '.tmp', path);
  }
  private load(id: string): Command | undefined {
    const path = this.file(id);
    if (existsSync(path)) safeFile(path);
    return existsSync(path) ? JSON.parse(readFileSync(path, 'utf8')) : undefined;
  }
  private command(plan: DesktopCreationPlan): Command {
    const initializer = `这是用户通过手机新建聊天的会话初始化，创建标识 ${plan.clientOperationId}。真实首条请求将在本轮结束后送达。现在仅回复“会话已就绪”，然后结束本轮。不要调用工具、读写文件或创建其他聊天。`;
    const title = plan.text.trim().replace(/\s+/g, ' ').slice(0, 80) || '附件对话';
    const args = {
      kind: 'create',
      operationId: plan.clientOperationId,
      directory: plan.directory,
      ...(plan.nativeProjectId ? { projectId: plan.nativeProjectId } : {}),
      title,
      prompt: initializer,
    };
    const projectSelection = plan.nativeProjectId
      ? 'projectId 等于 JSON 中的 projectId、path 与 directory 精确相同的唯一项目（其他项目可以共享此目录）'
      : 'path 与 directory 精确相同的唯一项目';
    const request = `这是用户从 iOS APP 显式新建聊天的独立请求，用户已授权本次创建及测试。只处理下面 JSON 数据中的这一条操作。先调用 list_projects；directory 非 null 时，必须在其实际结果中找到 hostId=local、projectKind=local、${projectSelection}，再使用该实际 projectId 和 local environment。directory=null 时必须使用 projectless target，并以 mobile-${plan.clientOperationId.slice(0, 8)} 作为 directoryName。调用一次Codex内置 create_thread，title 和 prompt 必须逐字采用 JSON 值。不要发送用户正文或附件（Host 会向真实新会话提交），不使用 worktree/cloud，不调用其他工具或 shell，不编辑任何文件或历史，不发送其他聊天消息，不创建第二条聊天，不建立自动化或循环。创建返回不确定时禁止重试。直接返回工具实际结果 JSON，然后结束本轮。数据：\n${JSON.stringify(args)}`;
    return {
      id: plan.clientOperationId,
      kind: 'create',
      request,
      initializer,
      title,
      directory: plan.directory,
      ...(plan.nativeProjectId ? { nativeProjectId: plan.nativeProjectId } : {}),
      targetId: null,
      phase: 'prepared',
      turnId: null,
      resultId: null,
    };
  }
  projectTarget(name: string, roots: string[]): { directory: string; nativeProjectId: string } {
    const state = this.config && this.runtime.ipc.state(this.config.threadId);
    const catalog = desktopTurns(state ?? {})
      .flatMap((turn) => list(turn.items).map(object))
      .filter((item) => item.tool === 'list_projects' && item.status === 'completed')
      .flatMap(jsonResults)
      .at(-1);
    const matches = list(catalog?.projects)
      .map(object)
      .filter(
        (project) =>
          project.hostId === 'local' &&
          project.projectKind === 'local' &&
          typeof project.projectId === 'string' &&
          project.projectId.length > 0 &&
          project.label === name &&
          roots.some(
            (root) =>
              string(project.path) && realpathSync(root) === realpathSync(string(project.path)),
          ),
      );
    if (matches.length !== 1) throw new BeforeDesktopSubmit('Codex 项目目录尚未确认，请刷新项目');
    return {
      directory: realpathSync(string(matches[0]!.path)),
      nativeProjectId: string(matches[0]!.projectId),
    };
  }
  private async result(command: Command): Promise<string | undefined> {
    const state = this.runtime.ipc.state(this.config!.threadId);
    if (!state) return undefined;
    const turns = desktopTurns(state);
    if (!command.turnId) {
      const found = turns.find((turn) =>
        list(turn.items).some((value) => {
          const item = object(value);
          return item.type === 'userMessage' && item.clientId === 'controller-' + command.id;
        }),
      );
      if (found) {
        command.turnId = string(found.turnId);
        this.save(command);
      }
    }
    const turn = turns.find((turn) => turn.turnId === command.turnId);
    if (!turn) return undefined;
    const items = list(turn.items).map(object);
    const tool = command.kind === 'create' ? 'create_thread' : 'navigate_to_codex_page';
    const calls = items.filter(
      (item) => item.type === 'mcpToolCall' && item.server === 'codex_app' && item.tool === tool,
    );
    if (calls.length > 1) throw Error('Multiple desktop side effects; outcome uncertain');
    const call = calls[0];
    if (!call || call.status !== 'completed' || call.error) {
      if (!call && turn.status !== 'inProgress')
        throw new BeforeDesktopSubmit('Desktop controller did not submit');
      return undefined;
    }
    const args = object(call.arguments);
    if (command.kind === 'open') {
      if (args.threadId !== command.targetId) throw Error('Desktop navigation target mismatch');
      return command.targetId!;
    }
    if (args.prompt !== command.initializer || args.title !== command.title)
      throw Error('Desktop initializer or title mismatch');
    const target = object(args.target);
    if (command.directory === null) {
      if (target.type !== 'projectless') throw Error('Desktop projectless target mismatch');
    } else {
      const catalog = items
        .filter((item) => item.tool === 'list_projects' && item.status === 'completed')
        .flatMap(jsonResults)
        .flatMap((result) => list(result.projects).map(object));
      const matches = catalog.filter(
        (project) =>
          project.hostId === 'local' &&
          project.projectKind === 'local' &&
          (!command.nativeProjectId || project.projectId === command.nativeProjectId) &&
          string(project.path) &&
          realpathSync(string(project.path)) === command.directory,
      );
      if (
        matches.length !== 1 ||
        target.type !== 'project' ||
        target.projectId !== matches[0]!.projectId ||
        object(target.environment).type !== 'local'
      )
        throw Error('Desktop project mapping mismatch');
    }
    const results = jsonResults(call).filter(
      (result) => result.hostId === 'local' && typeof result.threadId === 'string',
    );
    if (results.length !== 1) throw Error('Missing actual desktop creation result');
    return string(results[0]!.threadId);
  }
  private async run(command: Command): Promise<string> {
    if (command.resultId) return command.resultId;
    if (command.phase === 'rejected')
      throw new BeforeDesktopSubmit(command.rejection ?? 'Desktop command was not submitted');
    await this.refresh();
    if (!(command.kind === 'open' ? this.openAvailable : this.available)) {
      if (command.phase === 'prepared')
        throw new BeforeDesktopSubmit(this.reason ?? 'Controller unavailable');
      throw Error('Controller unavailable after submission');
    }
    if (command.phase === 'prepared') {
      const idleDeadline = Date.now() + 30_000;
      while (
        active(this.runtime.ipc.state(this.config!.threadId) ?? {}) &&
        Date.now() < idleDeadline
      )
        await pause();
      if (active(this.runtime.ipc.state(this.config!.threadId) ?? {}))
        throw new BeforeDesktopSubmit('Codex 控制聊天正在处理其他请求');
      command.phase = 'sent';
      this.save(command); // Any crash after this point is reconciled, never replayed.
      let result: WireObject;
      try {
        result = await this.runtime.call('turn/start', {
          threadId: this.config!.threadId,
          clientUserMessageId: 'controller-' + command.id,
          input: [{ type: 'text', text: command.request }],
        });
      } catch (error) {
        if (error instanceof DesktopCommandNotSubmitted) {
          command.phase = 'rejected';
          command.rejection = error.message;
          this.save(command);
          throw new BeforeDesktopSubmit(error.message);
        }
        throw error;
      }
      command.turnId = string(object(result.turn).id) || string(result.turnId) || null;
      this.save(command);
    }
    const deadline = Date.now() + 150_000;
    while (!this.closed && Date.now() < deadline) {
      const id = await this.result(command);
      if (id) {
        command.resultId = id;
        command.phase = 'finished';
        this.save(command);
        return id;
      }
      await pause();
    }
    throw Error('Desktop command result unknown');
  }
  private serialize(command: Command): Promise<string> {
    const existing = this.work.get(command.id);
    if (existing) return existing;
    const result = this.tail.then(() => this.run(command));
    this.tail = result.catch(() => {});
    this.work.set(command.id, result);
    void result.finally(() => this.work.delete(command.id)).catch(() => {});
    return result;
  }
  private async finishCreation(plan: DesktopCreationPlan, command: Command): Promise<string> {
    const id = await this.serialize(command);
    mkdirSync(dirname(plan.receiptPath), { recursive: true, mode: 0o700 });
    writeFileSync(
      plan.receiptPath,
      JSON.stringify({
        hostId: plan.hostId,
        clientOperationId: plan.clientOperationId,
        phase: 'submitted',
        threadId: id,
      }),
      { mode: 0o600 },
    );
    protectWindowsStateFile(plan.receiptPath);
    const deadline = Date.now() + 90_000;
    while (!this.closed && Date.now() < deadline) {
      const state = await this.runtime.ipc.follow(id);
      const submitted =
        state &&
        desktopTurns(state).some((turn) =>
          list(turn.items).some(
            (value) =>
              object(value).type === 'userMessage' &&
              object(value).clientId === 'first-' + plan.clientOperationId,
          ),
        );
      if (state && this.runtime.ipc.hasOwner(id) && (!active(state) || submitted)) {
        if (
          plan.directory
            ? realpathSync(string(state.cwd)) !== plan.directory
            : state.workspaceKind !== 'projectless'
        )
          throw Error('Created owner workspace mismatch');
        await this.firstInput(plan, id);
        return id;
      }
      await pause();
    }
    throw Error('Created owner not ready');
  }
  create(plan: DesktopCreationPlan): Promise<string> {
    return this.complete(plan, this.load(plan.clientOperationId) ?? this.command(plan));
  }
  private complete(plan: DesktopCreationPlan, command: Command): Promise<string> {
    const existing = this.creations.get(plan.clientOperationId);
    if (existing) return existing;
    const result = this.finishCreation(plan, command);
    this.creations.set(plan.clientOperationId, result);
    void result.finally(() => this.creations.delete(plan.clientOperationId)).catch(() => {});
    return result;
  }
  async reconcile(plan: DesktopCreationPlan): Promise<string | undefined> {
    const command = this.load(plan.clientOperationId);
    if (!command || command.phase === 'prepared') return undefined;
    return this.complete(plan, command);
  }
  async open(threadId: string, id: string): Promise<boolean> {
    const loaded = await this.runtime.ipc.follow(threadId);
    if (loaded && this.runtime.ipc.hasOwner(threadId)) return true;
    let command = this.load(id);
    if (command && (command.kind !== 'open' || command.targetId !== threadId))
      throw Error('Open operation target mismatch');
    command ??= {
      id,
      kind: 'open',
      request: `这是用户从 iOS APP 显式打开已有 Codex 聊天的请求。仅调用一次Codex内置 navigate_to_codex_page，threadId 必须为 ${JSON.stringify(threadId)}。不要创建聊天、发送消息、执行 shell 或修改文件；工具完成后返回其实际结果，然后结束本轮。`,
      initializer: '',
      title: '',
      directory: null,
      targetId: threadId,
      phase: 'prepared',
      turnId: null,
      resultId: null,
    };
    await this.serialize(command);
    const deadline = Date.now() + 30_000;
    while (!this.closed && Date.now() < deadline) {
      if (await this.runtime.ipc.follow(threadId)) return this.runtime.ipc.hasOwner(threadId);
      await pause();
    }
    return false;
  }
  close(): void {
    this.closed = true;
    this.available = false;
    this.openAvailable = false;
  }
}
