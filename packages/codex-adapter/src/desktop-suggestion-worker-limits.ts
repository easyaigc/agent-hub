// Private Windows reader transport limits. The capability TTL is only a hint;
// every actual read still performs the existing fresh source/native guards.
export const windowsSuggestionWorkerLimits = Object.freeze({
  fencedPhaseMs: 4000,
  readerMs: 3000,
  rpcMs: 3250,
  fencedRpcMs: 7000,
  capabilityTtlMs: 5000,
  closeMs: 2500,
});
