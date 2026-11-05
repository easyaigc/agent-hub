export type ResponseTransport = 'sse' | 'ws';
export type ResponseSuggestionState = 'completed' | 'empty' | 'failed' | 'cancelled' | 'unverified';
export interface PredictionIdentity {
  connectionId: string;
  requestId: string;
  sourceThreadId: string;
  sourceTurnId: string;
  requestedAt: number;
}
export type DesktopResponseForwarderEvent =
  | {
      kind: 'connection';
      connectionId: string;
      clientPort: number;
      listenerPort: number;
      transport: ResponseTransport;
    }
  | ({ kind: 'prediction-start' } & PredictionIdentity)
  | ({
      kind: 'prediction-result';
      responseId: string | null;
      text: string | null;
      state: ResponseSuggestionState;
      completedAt: number;
    } & PredictionIdentity)
  | { kind: 'connection-closed'; connectionId: string }
  | { kind: 'observation-unavailable'; connectionId: string | null };
export interface DesktopResponseForwarderOptions {
  port: number;
  basePath: string;
  onEvent: (event: DesktopResponseForwarderEvent) => void;
  captureStatus?: () => {
    ready: boolean;
    identityAgeMs: number | null;
    unobservableConnections: number;
    storageReady: boolean;
    peerAccepted: number;
    peerRejected: number;
    predictionStarts: number;
    predictionResults: number;
    persistedResults: number;
  };
  fixture?: { upstream: string; ca?: string | Buffer };
}
export interface DesktopResponseForwarderStats {
  observerAlive: boolean;
  pendingBytes: number;
  bufferedBytes: number;
  connections: number;
  requests: number;
}
