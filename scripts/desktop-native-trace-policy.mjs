/**
 * The passive native result sampler reads the original wire and safety log;
 * it does not need the upstream full-session diagnostic recorder. Strip only
 * that recorder's opt-in from the original CLI's environment. No arguments,
 * protocol bytes, credentials, shared-service settings or user data change.
 * Existing bounded native diagnostics remain available independently.
 */
export { nativeDesktopChildEnvironment } from '../packages/codex-adapter/dist/desktop-native-trace-policy.js';
