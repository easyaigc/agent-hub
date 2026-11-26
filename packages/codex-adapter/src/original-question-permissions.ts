import { constants } from 'node:fs';
import { lstat, open, realpath } from 'node:fs/promises';
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { isDeepStrictEqual } from 'node:util';
import { object, string, type WireObject } from './runtime.js';

/** Private, immutable input intent. Never accepted from the mobile request body. */
export interface OriginalQuestionPermissions {
  turnId: string;
  settings: WireObject;
}

const sandboxTypes: Record<string, string> = {
  'danger-full-access': 'dangerFullAccess',
  'read-only': 'readOnly',
  'workspace-write': 'workspaceWrite',
  'external-sandbox': 'externalSandbox',
};
const fields: Record<string, string> = {
  writable_roots: 'writableRoots',
  network_access: 'networkAccess',
  exclude_tmpdir_env_var: 'excludeTmpdirEnvVar',
  exclude_slash_tmp: 'excludeSlashTmp',
};

/** Keep concrete restrictions, reviewer and approval routing, not a lossy preset. */
export function originalPermissionSettings(value: unknown): WireObject | undefined {
  const row = object(value);
  const approvalPolicy = row.approvalPolicy;
  if (!['never', 'on-request', 'untrusted', 'on-failure'].includes(string(approvalPolicy))) return;
  if (!['user', 'auto_review', 'guardian_subagent'].includes(string(row.approvalsReviewer))) return;
  const source = object(row.sandboxPolicy ?? row.sandbox);
  const type = sandboxTypes[string(source.type)] ?? string(source.type);
  const allowed: Record<string, string[]> = {
    dangerFullAccess: [],
    readOnly: ['networkAccess'],
    externalSandbox: ['networkAccess'],
    workspaceWrite: ['writableRoots', 'networkAccess', 'excludeTmpdirEnvVar', 'excludeSlashTmp'],
  };
  if (!Object.hasOwn(allowed, type)) return;
  const sandboxPolicy: WireObject = { type };
  for (const [key, value] of Object.entries(source)) {
    if (key === 'type') continue;
    const name = fields[key] ?? key;
    if (!allowed[type]!.includes(name) || Object.hasOwn(sandboxPolicy, name)) return;
    if (name === 'writableRoots') {
      if (!Array.isArray(value) || value.some((v) => typeof v !== 'string' || !isAbsolute(v)))
        return;
      sandboxPolicy[name] = [...value];
    } else if (type === 'externalSandbox') {
      if (!['enabled', 'restricted'].includes(string(value))) return;
      sandboxPolicy[name] = value;
    } else {
      if (typeof value !== 'boolean') return;
      sandboxPolicy[name] = value;
    }
  }
  const profile = string(row.permissions) || string(object(row.activePermissionProfile).id);
  if (profile) {
    const known: Record<string, string> = {
      ':read-only': 'readOnly',
      ':workspace': 'workspaceWrite',
      ':danger-full-access': 'dangerFullAccess',
    };
    if (known[profile] !== type) return;
  }
  if (row.cwd !== undefined && (typeof row.cwd !== 'string' || !isAbsolute(row.cwd))) return;
  return {
    sandboxPolicy,
    approvalPolicy,
    approvalsReviewer: row.approvalsReviewer,
    ...(row.cwd !== undefined ? { cwd: row.cwd } : {}),
  };
}

/** Legacy sandbox overrides must not silently discard custom filesystem rules. */
function legacyProfile(context: WireObject, settings: WireObject): boolean {
  if (context.permission_profile === undefined) return true;
  const profile = object(context.permission_profile),
    sandbox = object(settings.sandboxPolicy);
  if (profile.type === 'disabled')
    return sandbox.type === 'dangerFullAccess' && Object.keys(profile).length === 1;
  if (
    profile.type !== 'managed' ||
    Object.keys(profile).some((k) => !['type', 'file_system', 'network'].includes(k))
  )
    return false;
  const filesystem = object(profile.file_system);
  if (
    filesystem.type !== 'restricted' ||
    !Array.isArray(filesystem.entries) ||
    Object.keys(filesystem).some((k) => !['type', 'entries'].includes(k))
  )
    return false;
  if (
    !['workspaceWrite', 'readOnly'].includes(string(sandbox.type)) ||
    profile.network !== (sandbox.networkAccess === true ? 'enabled' : 'restricted')
  )
    return false;
  const cwd = string(context.cwd);
  if (!isAbsolute(cwd)) return false;
  const roots =
    sandbox.type === 'workspaceWrite'
      ? [cwd, ...(Array.isArray(sandbox.writableRoots) ? (sandbox.writableRoots as string[]) : [])]
      : [];
  const writes = new Set<string>(),
    special = new Set<string>();
  let rootRead = false;
  for (const value of filesystem.entries) {
    const entry = object(value),
      path = object(entry.path);
    if (Object.keys(entry).some((k) => !['path', 'access', 'missing_path_behavior'].includes(k)))
      return false;
    if (path.type === 'special' && Object.keys(path).length === 2) {
      const kind = object(path.value).kind;
      if (Object.keys(object(path.value)).length !== 1) return false;
      if (kind === 'root' && entry.access === 'read' && entry.missing_path_behavior === undefined)
        rootRead = true;
      else if (
        sandbox.type === 'workspaceWrite' &&
        entry.access === 'write' &&
        entry.missing_path_behavior === undefined &&
        ((kind === 'slash_tmp' && sandbox.excludeSlashTmp !== true) ||
          (kind === 'tmpdir' && sandbox.excludeTmpdirEnvVar !== true))
      )
        special.add(string(kind));
      else return false;
    } else if (
      path.type === 'path' &&
      Object.keys(path).length === 2 &&
      typeof path.path === 'string'
    ) {
      if (
        entry.access === 'write' &&
        roots.includes(path.path) &&
        entry.missing_path_behavior === undefined
      )
        writes.add(path.path);
      else if (
        entry.access !== 'read' ||
        entry.missing_path_behavior !== 'skip' ||
        !roots.some((root) =>
          ['.git', '.agents', '.codex', '.aws'].some((name) => join(root, name) === path.path),
        )
      )
        return false;
    } else return false;
  }
  return (
    rootRead &&
    roots.every((root) => writes.has(root)) &&
    (sandbox.type !== 'workspaceWrite' ||
      ((sandbox.excludeSlashTmp === true || special.has('slash_tmp')) &&
        (sandbox.excludeTmpdirEnvVar === true || special.has('tmpdir'))))
  );
}

/** Read only the original owner's official rollout, bound to both thread and turn.
 * The shared App Server deliberately omits historical turn-start permissions.
 * A bounded tail read avoids scanning/exporting a whole conversation on answer.
 */
export async function readOriginalQuestionPermissions(
  home: string,
  path: string,
  threadId: string,
  turnId: string,
): Promise<WireObject | undefined> {
  if (!path || !isAbsolute(path) || !threadId || !turnId) return;
  let file;
  try {
    const root = await realpath(home);
    const location = relative(resolve(home), resolve(path));
    if (
      isAbsolute(location) ||
      !['sessions' + sep, 'archived_sessions' + sep].some((prefix) => location.startsWith(prefix))
    )
      return;
    // macOS may alias the configured home prefix (e.g. /var -> /private/var).
    // Resolve only that trusted prefix; still reject every symlink below it.
    path = join(root, location);
    // Do not follow a substituted rollout or a symlinked directory to another source.
    for (let cursor = path; cursor !== root; cursor = dirname(cursor)) {
      const info = await lstat(cursor);
      if (info.isSymbolicLink() || info.uid !== process.getuid?.()) return;
      if (dirname(cursor) === cursor) return;
    }
    file = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
    const before = await file.stat();
    if (!before.isFile() || before.uid !== process.getuid?.()) return;
    const head = Buffer.alloc(Math.min(before.size, 64 * 1024));
    await file.read(head, 0, head.length, 0);
    const firstLine = head.toString('utf8').split('\n')[0];
    const metadata = object(JSON.parse(firstLine ?? ''));
    if (metadata.type !== 'session_meta' || object(metadata.payload).id !== threadId) return;
    const size = Math.min(before.size, 16 * 1024 * 1024);
    const tail = Buffer.alloc(size);
    let offset = 0;
    while (offset < size) {
      const read = await file.read(tail, offset, size - offset, before.size - size + offset);
      if (!read.bytesRead) return;
      offset += read.bytesRead;
    }
    const lines = tail.toString('utf8').split('\n');
    if (before.size > size) lines.shift(); // A partial first JSON line is not evidence.
    let result: WireObject | undefined;
    let found = false;
    for (const line of lines) {
      if (line.length > 256 * 1024 || !line.includes('"turn_context"')) continue;
      let event;
      try {
        event = object(JSON.parse(line));
      } catch {
        continue;
      }
      if (event.type !== 'turn_context') continue;
      const context = object(event.payload);
      if (context.turn_id !== turnId) continue;
      const next = originalPermissionSettings({
        sandboxPolicy: context.sandbox_policy,
        approvalPolicy: context.approval_policy,
        approvalsReviewer: context.approvals_reviewer,
        activePermissionProfile: context.active_permission_profile,
        cwd: context.cwd,
      });
      // Unknown/conflicting contexts must not silently become today's defaults.
      if (!next || !legacyProfile(context, next) || (found && !isDeepStrictEqual(result, next)))
        return;
      found = true;
      result = next;
    }
    const after = await lstat(path);
    if (after.isSymbolicLink() || after.ino !== before.ino || after.dev !== before.dev) return;
    return result;
  } catch {
    return;
  } finally {
    await file?.close();
  }
}
