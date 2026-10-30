const windowsHostEnvironmentKeys = new Set([
  'AGENT_HUB_WINDOWS_TRACE_DIAGNOSTICS',
  'CODEX_RUNTIME_MODE',
  'CODEX_DESKTOP_VERSION',
  'CODEX_EXECUTABLE',
  'CODEX_DESKTOP_WINDOWS_CONFIG',
  'CODEX_DESKTOP_CONTROLLER_CONFIG',
  'CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG',
  'CODEX_DESKTOP_SUGGESTION_OBSERVER',
  'CODEX_DESKTOP_SUGGESTION_TRACE_ROOT',
  'CODEX_DESKTOP_TRACE_RETENTION',
  'CODEX_DESKTOP_NATIVE_SUGGESTIONS_CONFIG',
  'HOST_AGENT_ROOT',
  'HOST_AGENT_NAME',
  'HOST_AGENT_PORT',
  'HOST_AGENT_STATE_DIR',
  'HOST_AGENT_DISCOVERY',
  'HOST_AGENT_DISCOVERY_BIND',
  'HOST_AGENT_DISCOVERY_PORT',
  'HOST_AGENT_DISCOVERY_TLS_CERT',
  'HOST_AGENT_DISCOVERY_TLS_KEY',
  'HOST_AGENT_OPENSSL',
  'HOST_AGENT_HTTPS_ENDPOINTS',
  'HOST_NOTIFICATION_RELAY_URL',
  'HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE',
]);

export function validateWindowsHostEnvironment(configuration) {
  if (configuration?.environment?.CODEX_RUNTIME_MODE !== 'desktop-runtime')
    throw new Error('Original desktop required');
  for (const [key, value] of Object.entries(configuration.environment)) {
    if (!windowsHostEnvironmentKeys.has(key) || typeof value !== 'string')
      throw new Error('Invalid Host environment');
    if (key === 'AGENT_HUB_WINDOWS_TRACE_DIAGNOSTICS' && value !== '0' && value !== '1')
      throw new Error('Invalid Windows Trace diagnostic mode');
  }
  return configuration.environment;
}
