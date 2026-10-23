/** Versioned product contracts. Upstream wire and transport versions remain independent. */
export const AGENT_MANAGEMENT_VERSION = 1 as const;
export interface RuntimeTarget {
  agentId: string;
  hostId: string;
  workspaceId: string;
  runtimeId: string;
  account?: { authority: string; subject: string };
}
/** Codex connections identify the machine runtime. Legacy account data is metadata. */
export function machineRuntimeTargetKey(target: RuntimeTarget): string {
  return JSON.stringify([target.agentId, target.hostId, target.workspaceId, target.runtimeId]);
}
export interface CapabilityDescriptor {
  id: string;
  version: number;
  available: boolean;
  reason?: string;
}
export interface ConnectionCandidate {
  id: string;
  transportId: string;
  endpoint: string;
  peerFingerprint?: string;
  networkKind?: 'lan' | 'vpn' | 'unknown';
}
export type ConnectionPreference = 'automatic' | 'lan-only' | 'relay-only';
export interface AgentConnection {
  target: RuntimeTarget;
  candidates: readonly ConnectionCandidate[];
  preference: ConnectionPreference;
  credentialRef: string;
}
export type ContentPart =
  | { type: 'text'; text: string }
  | { type: 'resource'; resourceId: string; name: string; mediaType: string }
  | { type: 'tool'; id: string; name: string; status: string; input?: string; output?: string }
  | { type: 'diff'; id: string; text: string }
  | { type: 'extension'; namespace: string; data: Readonly<Record<string, unknown>> };
export interface AgentMessage {
  id: string;
  role: 'user' | 'assistant' | 'tool';
  parts: readonly ContentPart[];
  status: string;
}
export interface OperationReceipt {
  operationId: string;
  target: RuntimeTarget;
  threadId?: string;
  state: 'received' | 'submitting' | 'completed' | 'failed' | 'uncertain';
  result?: unknown;
  error?: string;
}
export interface EventCursor {
  target: RuntimeTarget;
  threadId: string;
  epoch: string;
  sequence: number;
}
export interface AgentProject {
  id: string;
  target: RuntimeTarget;
  name: string;
  roots: readonly string[];
}
export interface AgentThread {
  id: string;
  target: RuntimeTarget;
  projectId?: string;
  title: string;
  state: 'unknown' | 'idle' | 'running' | 'waiting-for-approval' | 'failed';
}
export interface AgentResource {
  id: string;
  target: RuntimeTarget;
  threadId: string;
  name: string;
  mediaType: string;
  sizeBytes: number;
  sha256: string;
}
export interface AgentApproval {
  id: string;
  target: RuntimeTarget;
  threadId: string;
  runId: string;
  kind: 'permission' | 'question';
  title: string;
  detail: string;
  decisions: readonly string[];
  questions: readonly {
    id: string;
    question: string;
    options: readonly { label: string; description: string }[];
    multiple?: boolean;
    custom?: boolean;
    isSecret?: boolean;
  }[];
}
/** Settings use opaque catalogue IDs; adapters validate each provider's supported values. */
export interface AgentGenerationSettings {
  model?: string;
  effort?: string;
  permissionMode?: string;
  mode?: 'normal' | 'plan';
}
export interface AgentModel {
  id: string;
  name: string;
  isDefault: boolean;
  supportedEfforts: readonly string[];
}
export interface AgentPlan {
  id: string;
  version: number;
  runId: string;
  status: string;
  text: string;
  steps: readonly { title: string; status: string }[];
}
/** The transport never turns an arbitrary string into a host method call. */
export type AgentOperation =
  | { operation: 'thread.create'; projectId?: string }
  | {
      operation: 'message.send';
      threadId: string;
      parts: readonly ContentPart[];
      mode?: 'next-turn' | 'queue' | 'steer';
      expectedRunId?: string;
      settings?: AgentGenerationSettings;
    }
  | { operation: 'models.list' }
  | {
      operation: 'plan.approve';
      threadId: string;
      planId: string;
      version: number;
      settings?: AgentGenerationSettings;
    }
  | {
      operation: 'plan.revise';
      threadId: string;
      planId: string;
      version: number;
      parts: readonly ContentPart[];
      settings?: AgentGenerationSettings;
    }
  | { operation: 'queue.cancel'; threadId: string; messageId: string }
  | { operation: 'queue.execute'; threadId: string; messageId: string; expectedRunId?: string }
  | { operation: 'run.stop'; threadId: string; runId: string }
  | { operation: 'approval.respond'; threadId: string; approvalId: string; decision: string }
  | {
      operation: 'question.answer';
      threadId: string;
      approvalId: string;
      answers: Readonly<Record<string, readonly string[]>>;
    }
  | { operation: 'resource.read'; threadId: string; resourceId: string }
  | { operation: 'operation.status'; threadId: string; operationId: string };
export interface AgentCommand {
  version: typeof AGENT_MANAGEMENT_VERSION;
  target: RuntimeTarget;
  operationId: string;
  command: AgentOperation;
}
export interface AgentConnectionState {
  target: RuntimeTarget;
  phase: 'disconnected' | 'connecting' | 'connected' | 'recovering';
  transportId?: string;
  processHealthy: boolean;
  runtimeConnected: boolean;
  historyReadable: boolean;
}
/** Transport moves opaque frames. Business meaning belongs to an Agent adapter. */
export interface AgentTransport {
  send(frame: string): Promise<void>;
  onFrame(handler: (frame: string) => void): () => void;
  onClose(handler: (reason?: string) => void): () => void;
  close(): Promise<void>;
}
