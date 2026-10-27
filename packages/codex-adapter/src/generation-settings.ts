import { list, object, string, type WireObject } from './runtime.js';

/** Match the original owner's current model read, ahead of retained prior-turn settings. */
export function desktopModelSettings(state: WireObject): { model: string; effort: string | null } {
  const settings = object(state.latestThreadSettings);
  const collaboration = object(state.latestCollaborationMode ?? settings.collaborationMode);
  const model = object(collaboration.settings).model ?? state.latestModel ?? settings.model;
  const effort = Object.hasOwn(state, 'latestReasoningEffort')
    ? state.latestReasoningEffort
    : settings.effort !== undefined
      ? settings.effort
      : object(collaboration.settings).reasoning_effort;
  return { model: string(model), effort: typeof effort === 'string' ? effort : null };
}

export const speeds = ['standard', 'fast', 'ultrafast'] as const;
export function currentSpeed(tier: unknown): string | null {
  if (tier === null || tier === 'default' || tier === 'standard') return 'standard';
  if (tier === 'priority' || tier === 'fast') return 'fast';
  return tier === 'ultrafast' ? 'ultrafast' : null;
}
export function modelSpeedTiers(model: WireObject): Map<string, string> {
  const tiers = new Map<string, string>([['standard', 'default']]);
  for (const value of list(model.serviceTiers)) {
    const tier = object(value);
    const speed = currentSpeed(tier.id) ?? currentSpeed(string(tier.name).toLowerCase());
    if (speed && string(tier.id)) tiers.set(speed, string(tier.id));
  }
  for (const value of list(model.additionalSpeedTiers)) {
    const speed = currentSpeed(value);
    if (speed && !tiers.has(speed)) tiers.set(speed, string(value));
  }
  return tiers;
}

// Desktop 26.1002 presets. Keep version-specific permission policies in the adapter.
export const permissionModes = ['read-only', 'guardian-approvals', 'full-access'] as const;
export function permissionSettings(mode: string, cwd: string): WireObject {
  if (!permissionModes.includes(mode as (typeof permissionModes)[number]))
    throw new Error('无效权限模式');
  return {
    permissions:
      mode === 'read-only'
        ? ':read-only'
        : mode === 'full-access'
          ? ':danger-full-access'
          : ':workspace',
    approvalPolicy: mode === 'full-access' ? 'never' : 'on-request',
    approvalsReviewer: mode === 'guardian-approvals' ? 'guardian_subagent' : 'user',
    // Public App Server ignores the desktop-only `permissions` field. Supply
    // its concrete policy too, so the running sandbox matches the selection.
    sandboxPolicy:
      mode === 'full-access'
        ? { type: 'dangerFullAccess' }
        : mode === 'read-only'
          ? { type: 'readOnly' }
          : { type: 'workspaceWrite', writableRoots: [cwd], networkAccess: false },
  };
}

/** Effective computer config, with automatic review only when no policy is configured. */
export function configuredPermissionSettings(config: WireObject, cwd: string): WireObject {
  const configured = ['sandbox_mode', 'approval_policy', 'approvals_reviewer'].some(
    (key) => config[key] !== undefined && config[key] !== null,
  );
  if (!configured) return permissionSettings('guardian-approvals', cwd);
  const mode = string(config.sandbox_mode) || 'workspace-write';
  const workspace = object(config.sandbox_workspace_write);
  const sandboxPolicy =
    mode === 'danger-full-access'
      ? { type: 'dangerFullAccess' }
      : mode === 'read-only'
        ? { type: 'readOnly' }
        : {
            type: 'workspaceWrite',
            writableRoots: [...new Set([cwd, ...list(workspace.writable_roots).map(string)])],
            networkAccess: workspace.network_access === true,
            excludeTmpdirEnvVar: workspace.exclude_tmpdir_env_var === true,
            excludeSlashTmp: workspace.exclude_slash_tmp === true,
          };
  return {
    sandboxPolicy,
    approvalPolicy: config.approval_policy ?? 'on-request',
    approvalsReviewer: config.approvals_reviewer ?? 'user',
  };
}

export function threadPermissionSettings(settings: WireObject): WireObject {
  const type = string(object(settings.sandboxPolicy).type);
  return {
    sandbox:
      type === 'dangerFullAccess'
        ? 'danger-full-access'
        : type === 'readOnly'
          ? 'read-only'
          : 'workspace-write',
    approvalPolicy: settings.approvalPolicy,
    approvalsReviewer: settings.approvalsReviewer,
  };
}

/** Treat the owner’s effective policy as one source; never mix stale profile fields into it. */
export function desktopPermissionMode(state: WireObject): string | null {
  const current = object(state.currentPermissions);
  if (Object.keys(current).length) return currentPermissionMode(current);
  const settings = object(state.latestThreadSettings);
  if (
    ['permissions', 'activePermissionProfile', 'sandboxPolicy', 'approvalPolicy'].some(
      (key) => settings[key] !== undefined,
    )
  )
    return currentPermissionMode(settings);
  const turns = list(state.turns);
  return currentPermissionMode(object(object(turns.at(-1)).params));
}
export function currentPermissionMode(settings: WireObject): string | null {
  const profile =
    string(settings.permissions) || string(object(settings.activePermissionProfile).id);
  // A named profile supersedes older sandbox fields retained in the snapshot.
  const profiles: Record<string, string> = {
    ':read-only': 'readOnly',
    ':workspace': 'workspaceWrite',
    ':danger-full-access': 'dangerFullAccess',
  };
  if (profile && !Object.hasOwn(profiles, profile)) return null;
  const sandbox = profile
    ? profiles[profile]
    : string(object(settings.sandboxPolicy ?? settings.sandbox).type);
  if (sandbox === 'dangerFullAccess' && settings.approvalPolicy === 'never') return 'full-access';
  if (
    sandbox === 'workspaceWrite' &&
    ['auto_review', 'guardian_subagent'].includes(string(settings.approvalsReviewer)) &&
    settings.approvalPolicy === 'on-request'
  )
    return 'guardian-approvals';
  if (
    sandbox === 'readOnly' &&
    settings.approvalPolicy === 'on-request' &&
    settings.approvalsReviewer === 'user'
  )
    return 'read-only';
  return null;
}
