/** Original credentials, arguments and transport stay intact; only diagnostic recording is disabled. */
export function nativeDesktopChildEnvironment(environment: NodeJS.ProcessEnv): NodeJS.ProcessEnv {
  const next = { ...environment };
  delete next.CODEX_ROLLOUT_TRACE_ROOT;
  return next;
}
