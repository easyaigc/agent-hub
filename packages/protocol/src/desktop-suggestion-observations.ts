import type { RuntimeTarget } from './agent-management.js';

/** Captured desktop output. Execution identity is not the source conversation identity. */
export interface DesktopSuggestionObservation {
  id: string;
  target: RuntimeTarget;
  predictionThreadId: string;
  predictionTurnId: string;
  text: string;
  observedAt: string;
  expiresAt: string;
  collectorEpoch: string;
  source: null;
  bindingStatus: 'unbound';
}

export interface DesktopSuggestionObservations {
  schemaVersion: 1;
  target: RuntimeTarget;
  readOnly: true;
  collector: 'disabled' | 'starting' | 'listening' | 'unavailable';
  sourceBinding: 'unavailable';
  observations: DesktopSuggestionObservation[];
}

/** Original desktop prediction, bound to its exact completed source turn. */
export interface DesktopNextTurnSuggestionResponse {
  schemaVersion: 1;
  target: RuntimeTarget;
  threadId: string;
  sourceTurnId: string;
  readOnly: true;
  /** Trace identifies generated text; only desktop-display proves renderer state. */
  source?: 'desktop-display' | 'native-trace';
  suggestion: {
    id: string;
    text: string;
    contentSha256: string;
    /** Legacy readers use this limit. Source-aware readers use validity instead. */
    expiresAt: string;
    /** Valid while the exact latest completed source remains owner-confirmed. */
    validity?: 'source-turn' | 'desktop-display' | 'native-version';
  } | null;
}
