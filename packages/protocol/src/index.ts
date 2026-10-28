/** Our application protocol version, independent of the Codex JSON-RPC version. */
export const PROTOCOL_VERSION = '0.1' as const;

export * from './read-state.js';
export * from './file-transfers.js';
export * from './thread-forks.js';
export * from './vpn-discovery.js';
export * from './codex-private-connection.js';
export * from './codex-connection-routes.js';
export * from './workspace-sidebar.js';

export type ServiceName = 'host-agent' | 'relay';

/** Client identity is independent of the computer name and transport mode. */
export interface ClientType {
  id: string;
  name: string;
}

/** Process liveness only. It does not imply Codex or another device is connected. */
export interface HealthResponse {
  service: ServiceName;
  status: 'ok';
  protocolVersion: typeof PROTOCOL_VERSION;
  capabilities: readonly string[];
}

/** Draft domain contracts for the next PoC; there are no remote endpoints yet. */
export interface ConversationRef {
  hostId: string;
  threadId: string;
}

export interface ProjectSummary {
  id: string;
  hostId: string;
  name: string;
  roots: readonly string[];
}

export interface ThreadSummary {
  ref: ConversationRef;
  projectId: string | null;
  title: string;
  updatedAt: string;
  state: 'unknown' | 'idle' | 'running' | 'waiting-for-approval' | 'failed';
  /** Outstanding non-blocking questions, independent of execution/approval state. */
  pendingAsyncQuestionCount?: number;
}

export interface Page<T> {
  items: readonly T[];
  nextCursor: string | null;
}

/** An attachment ID is a logical reference, never an arbitrary filesystem path. */
export interface AttachmentRef {
  id: string;
  hostId: string;
  threadId: string;
  name: string;
  mediaType: string;
  sizeBytes: number;
  sha256: string;
}

export interface ReplyRequest {
  clientMessageId: string;
  target: ConversationRef;
  text: string;
  attachments: readonly AttachmentRef[];
  mode: 'next-turn' | 'queue' | 'steer';
  expectedTurnId?: string;
}

export type DeliveryState =
  'pending' | 'received-by-host' | 'submitted-to-codex' | 'completed' | 'failed' | 'uncertain';

export * from './agent-management.js';
export * from './voice-transcription.js';
export * from './notifications.js';
export * from './notification-recovery-v2.js';

export * from './business-notifications.js';

export * from './workspace-management.js';
export * from './desktop-suggestion-observations.js';
export * from './chat-actions.js';
