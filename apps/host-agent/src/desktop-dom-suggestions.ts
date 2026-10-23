import { createHash } from 'node:crypto';
import {
  DesktopDomBridge,
  DesktopDomBridgeError,
  readDesktopDomBridgeConfig,
  type DesktopDomSuggestion,
} from '@codex-manage/codex-adapter';
import {
  machineRuntimeTargetKey,
  type RuntimeTarget,
  type DesktopNextTurnSuggestionResponse,
} from '@codex-manage/protocol';

interface SourceReader {
  readingTarget(): RuntimeTarget;
  desktopSuggestionSource(threadId: string): Promise<{ threadId: string; sourceTurnId: string }>;
}

/** Existing displayed text only. No generation, launch, navigation or UI writes. */
export class DesktopDomSuggestions {
  private readonly bridge?: DesktopDomBridge;
  constructor(
    private readonly session: SourceReader,
    configPath?: string,
  ) {
    if (configPath) this.bridge = new DesktopDomBridge(readDesktopDomBridgeConfig(configPath));
  }
  get capabilities(): string[] {
    return this.bridge ? ['desktop:suggestions:read:v1', 'desktop:suggestions:displayed:v1'] : [];
  }

  async readNextTurn(
    threadId: string,
    sourceTurnId: string,
    target: RuntimeTarget,
  ): Promise<DesktopNextTurnSuggestionResponse> {
    const first = await this.read(threadId, sourceTurnId, target);
    const observation = await this.read(threadId, sourceTurnId, target);
    if (
      first.status !== observation.status ||
      first.text !== observation.text ||
      first.id !== observation.id
    )
      throw new DesktopDomBridgeError('display-changed', '读取期间桌面建议已变化', 409);
    return {
      schemaVersion: 1,
      target: observation.target,
      threadId,
      sourceTurnId,
      readOnly: true,
      source: 'desktop-display',
      suggestion:
        observation.status === 'present' &&
        observation.text &&
        observation.contentSha256 &&
        observation.id
          ? {
              id: observation.id,
              validity: 'desktop-display',
              text: observation.text,
              contentSha256: observation.contentSha256,
              // A completed source turn can be predicted again after desktop restart.
              // Never grant a rendered value an unlimited source-turn cache lifetime.
              expiresAt: new Date(Date.parse(observation.observedAt) + 6000).toISOString(),
            }
          : null,
    };
  }

  async read(threadId: string, sourceTurnId: string, target: RuntimeTarget) {
    if (!this.bridge)
      throw new DesktopDomBridgeError('not-configured', '此 Host 未配置 DOM 只读 Bridge');
    const key = machineRuntimeTargetKey(target);
    if (key !== machineRuntimeTargetKey(this.session.readingTarget()))
      throw new DesktopDomBridgeError('wrong-target', '建议请求的机器运行目标已变化', 409);
    const before = await this.session.desktopSuggestionSource(threadId);
    if (before.threadId !== threadId || before.sourceTurnId !== sourceTurnId)
      throw new DesktopDomBridgeError('stale-turn', '建议来源轮次已变化', 409);
    const observation: DesktopDomSuggestion = await this.bridge.readExisting(threadId);
    const after = await this.session.desktopSuggestionSource(threadId);
    if (
      after.threadId !== threadId ||
      after.sourceTurnId !== sourceTurnId ||
      key !== machineRuntimeTargetKey(this.session.readingTarget())
    )
      throw new DesktopDomBridgeError('stale-turn', 'DOM 读取期间来源轮次或运行目标已变化', 409);
    return {
      ...observation,
      target: this.session.readingTarget(),
      sourceTurnId,
      sourceTurnBinding: 'latest-completed-owner' as const,
      ...(observation.contentSha256
        ? {
            id: createHash('sha256')
              .update(JSON.stringify([key, threadId, sourceTurnId, observation.contentSha256]))
              .digest('hex'),
          }
        : {}),
    };
  }
}
