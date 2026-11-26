import { existsSync, realpathSync, readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { assertPrivateFile } from '../packages/codex-adapter/dist/desktop-platform.js';
import { nativeSuggestionsEnvironment } from './desktop-native-suggestions-environment.mjs';
import { sharedDesktopEnvironment } from './desktop-shared-environment.mjs';

const defaultSharedState = () => join(homedir(), '.config/agent-hub/shared-desktop');

/** Configure one existing installation from one program; never restart the desktop. */
export function upgradeDesktopRuntime({
  program,
  environment,
  sharedState = defaultSharedState(),
  shared = sharedDesktopEnvironment({ state: sharedState }),
  native = (options) => nativeSuggestionsEnvironment(options),
}) {
  program = realpathSync(program);
  const entryPath = join(program, 'apps/host-agent/dist/runtime-entry.js');
  for (const path of [
    entryPath,
    join(program, 'packages/codex-adapter/dist/desktop-shared-launcher.js'),
    join(program, 'scripts/desktop-native-suggestions-runtime.mjs'),
    join(program, 'scripts/desktop-native-trace-policy.mjs'),
    join(program, 'packages/codex-adapter/resources/desktop-completion-notify-observer.py'),
  ]) {
    if (!existsSync(path)) throw new Error('Incomplete desktop runtime upgrade program');
  }
  const configured = shared.status();
  const configPath = environment.CODEX_DESKTOP_NATIVE_SUGGESTIONS_CONFIG;
  if (environment.CODEX_SHARED_SERVER_URL && !configured.installed)
    throw new Error('Existing shared desktop installation is unavailable for upgrade');
  let suggestions;
  if (configPath) {
    assertPrivateFile(configPath);
    const config = JSON.parse(readFileSync(configPath, 'utf8'));
    const runtimePath = join(environment.HOST_AGENT_STATE_DIR, 'runtime.json');
    assertPrivateFile(runtimePath);
    const { hostId } = JSON.parse(readFileSync(runtimePath, 'utf8'));
    if (
      config.target?.hostId !== hostId ||
      config.target?.agentId !== 'codex' ||
      config.target?.runtimeId !== 'desktop-runtime' ||
      config.traceRoot !== environment.CODEX_DESKTOP_SUGGESTION_TRACE_ROOT ||
      configured.nativeSuggestionsConfig !== configPath ||
      configured.url !== environment.CODEX_SHARED_SERVER_URL
    )
      throw new Error('Preserve mismatched desktop runtime upgrade configuration');
    suggestions = native({ state: dirname(configPath), shared }).upgrade({ program, hostId });
  } else if (configured.installed) {
    if (configured.url !== environment.CODEX_SHARED_SERVER_URL)
      throw new Error('Preserve mismatched shared desktop service');
    shared.install({
      program,
      executable: configured.executable,
      url: configured.url,
      preserveNode: true,
    });
  }
  if (configured.installed && shared.status().program !== program)
    throw new Error('Shared launcher did not adopt the upgraded program');
  if (suggestions && suggestions.program !== program)
    throw new Error('Suggestion producer did not adopt the upgraded program');
  return {
    entryPath,
    workingDirectory: program,
    program,
    componentsConfiguredTogether: true,
    suggestionsConfigured: Boolean(suggestions),
    currentProducerReady: suggestions?.currentProducerReady ?? null,
    normalDesktopReopenRequired: suggestions?.normalDesktopReopenRequired ?? null,
    desktopProcessesStarted: 0,
  };
}

/** A healthy Host alone does not prove the other installed components match. */
export function desktopRuntimeBundleStatus({
  entryPath,
  environment,
  shared = sharedDesktopEnvironment(),
  native = (options) => nativeSuggestionsEnvironment(options),
}) {
  if (!entryPath) return { componentsConfiguredTogether: false };
  const program = dirname(dirname(dirname(dirname(realpathSync(entryPath)))));
  try {
    const configured = shared.status();
    const configPath = environment.CODEX_DESKTOP_NATIVE_SUGGESTIONS_CONFIG;
    const suggestions = configPath
      ? native({ state: dirname(configPath), shared }).status()
      : undefined;
    return {
      program,
      componentsConfiguredTogether:
        (!configured.installed || configured.program === program) &&
        (!configPath ||
          (suggestions?.configured === true &&
            suggestions.program === program &&
            configured.nativeSuggestionsConfig === configPath)),
      suggestionsConfigured: Boolean(configPath),
      currentProducerReady: suggestions?.currentProducerReady ?? null,
      normalDesktopReopenRequired: suggestions?.normalDesktopReopenRequired ?? null,
      desktopProcessesStarted: 0,
    };
  } catch {
    return { program, componentsConfiguredTogether: false, code: 'DESKTOP_BUNDLE_UNVERIFIED' };
  }
}
