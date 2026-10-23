import type {
  ConversationRef,
  Page,
  ProjectSummary,
  ReplyRequest,
  ThreadSummary,
} from '@codex-manage/protocol';

export { CodexHistoryReader } from './history-reader.js';
export { DesktopWorkspaceView } from './desktop-workspace-view.js';
export { RuntimeSession, RuntimeError } from './runtime-session.js';
export {
  NotificationObserver,
  type NotificationObservation,
} from '@codex-manage/notification-runtime';
export { CreationError } from './desktop-creation.js';
export {
  assertPrivateFile,
  protectWindowsStateDirectory,
  protectWindowsStateFile,
} from './desktop-platform.js';
export { runDesktopStdioBridge } from './desktop-stdio-bridge.js';

export type CodexConnectionMode =
  'shared-runtime' | 'managed-runtime' | 'history-only' | 'desktop-runtime';

export interface CodexCapabilities {
  connectionMode: CodexConnectionMode;
  version: string;
  canReply: boolean;
  canSteer: boolean;
  canQueue: boolean;
  canHandleApprovals: boolean;
}

export interface SubmissionReceipt {
  clientMessageId: string;
  target: ConversationRef;
  turnId: string | null;
  disposition: 'started' | 'queued' | 'steered';
}

/** Boundary only: no Codex process is launched by this bootstrap package. */
export interface CodexAdapter {
  capabilities(): Promise<CodexCapabilities>;
  listProjects(cursor?: string): Promise<Page<ProjectSummary>>;
  listThreads(projectId: string | null, cursor?: string): Promise<Page<ThreadSummary>>;
  submitReply(request: ReplyRequest): Promise<SubmissionReceipt>;
  close(): Promise<void>;
}
export { DesktopTranscription } from './desktop-transcription.js';
export {
  DesktopSuggestionObserver,
  SuggestionObservationError,
} from './desktop-suggestion-observer.js';
export {
  DesktopDomBridge,
  DesktopDomBridgeError,
  readDesktopDomBridgeConfig,
  type DesktopDomBridgeConfig,
  type DesktopDomSuggestion,
} from './desktop-dom-bridge.js';

export { DesktopChatActions } from './desktop-chat-actions.js';
export {
  DesktopPredictionTraceReader,
  type DesktopPredictionTraceBinding,
  type DesktopPredictionTracePrediction,
} from './desktop-prediction-trace.js';

// Local process creation guard; no executable, environment or HTTP configuration.
export { withWindowsProcessCreation } from './windows-process.js';
