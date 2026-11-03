import { homedir } from 'node:os';
import { join, relative, resolve, isAbsolute } from 'node:path';

/** Exact protected deployment layout; never derive a root from Task arguments. */
export function protectedHostProgram(repository, profile = homedir()) {
  const anchor = join(profile, '.config/agent-hub/windows-host-v1/programs');
  const child = relative(resolve(anchor), resolve(repository));
  if (child.startsWith('..') || isAbsolute(child)) return false;
  if (!/^[a-f0-9]{64}$/.test(child)) throw new Error('Invalid protected Host program');
  return true;
}

export function applyProtectedHostEnvironment(repository, environment = process.env, profile) {
  if (!protectedHostProgram(repository, profile)) return false;
  environment.CODEX_MANAGE_WINDOWS_NATIVE_STORAGE = 'profile-v1';
  environment.CODEX_DESKTOP_SUGGESTION_OBSERVER = '0';
  environment.CODEX_DESKTOP_TRACE_RETENTION = '0';
  delete environment.CODEX_DESKTOP_NATIVE_SUGGESTIONS_CONFIG;
  return true;
}
