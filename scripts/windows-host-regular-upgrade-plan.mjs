import { win32 as path } from 'node:path';

const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;
const sha256 = /^[a-f0-9]{64}$/;
const commit = /^[a-f0-9]{40}$/;
const reject = () => {
  throw new Error('HOST_UPGRADE_PLAN_REJECTED');
};
const record = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
function absolute(value) {
  if (
    typeof value !== 'string' ||
    !/^[a-zA-Z]:\\/.test(value) ||
    value.includes('/') ||
    value.slice(2).includes(':') ||
    [...value].some((character) => character.charCodeAt(0) < 32) ||
    /[<>"|?*]/.test(value) ||
    path.normalize(value) !== value ||
    value.endsWith('\\') ||
    value
      .slice(3)
      .split('\\')
      .some((p) => !p || /[. ]$/.test(p) || /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(p))
  )
    reject();
  return value;
}
const same = (a, b) => a.toLowerCase() === b.toLowerCase();
function noSecrets(value) {
  if (!record(value)) reject();
  for (const [key, item] of Object.entries(value)) {
    if (
      /(?:token|password|secret|credential|authorization)/i.test(key) &&
      !/(?:_file|_path)$/i.test(key)
    )
      reject();
    if (record(item)) noSecrets(item);
    else if (Array.isArray(item)) for (const child of item) if (record(child)) noSecrets(child);
  }
}

/** Pure planning only. Caller owns identity checks, locking, publication and rollback. */
export function planRegularUpgrade({
  operationId,
  profile,
  current,
  expectedCurrentSha256,
  desiredRuntimeSha256,
  desiredSourceCommit,
  configuration,
  launcherPath,
  launcherSha256,
  responseConfigPath,
}) {
  if (
    !uuid.test(operationId ?? '') ||
    !sha256.test(expectedCurrentSha256 ?? '') ||
    !sha256.test(desiredRuntimeSha256 ?? '') ||
    !commit.test(desiredSourceCommit ?? '') ||
    !sha256.test(launcherSha256 ?? '') ||
    !record(current) ||
    !record(configuration)
  )
    reject();
  absolute(profile);
  absolute(launcherPath);
  if (
    current.format !== 'windows-host-regular-installation-v1' ||
    !uuid.test(current.hostId ?? '') ||
    !uuid.test(current.operationId ?? '') ||
    !sha256.test(current.runtimeSha256 ?? '') ||
    !commit.test(current.sourceCommit ?? '') ||
    current.runtimeSha256 === desiredRuntimeSha256 ||
    current.operationId.toLowerCase() === operationId.toLowerCase()
  )
    reject();
  noSecrets(current);
  noSecrets(configuration);
  const anchor = path.join(profile, '.config', 'agent-hub', 'windows-host-v1');
  const programs = path.join(anchor, 'programs');
  const instance = path.join(anchor, 'instances', current.hostId);
  const configPath = path.join(instance, 'windows-host.json');
  const launcherRoot = path.join(
    profile,
    '.config',
    'agent-hub',
    'windows-native-v1',
    'native-helpers',
  );
  const launcherDir = path.dirname(launcherPath);
  if (
    !same(path.dirname(launcherDir), launcherRoot) ||
    !sha256.test(path.basename(launcherDir)) ||
    path.basename(launcherPath).toLowerCase() !== 'host-launcher.exe'
  )
    reject();
  for (const v of [current.program, current.configuration, current.state]) absolute(v);
  if (
    !same(current.program, path.join(programs, current.runtimeSha256)) ||
    !same(current.configuration, configPath)
  )
    reject();
  const environment = configuration.environment;
  if (
    !record(environment) ||
    environment.CODEX_RUNTIME_MODE !== 'desktop-runtime' ||
    typeof environment.HOST_AGENT_STATE_DIR !== 'string' ||
    !same(absolute(environment.HOST_AGENT_STATE_DIR), current.state) ||
    typeof environment.HOST_AGENT_PORT !== 'string' ||
    !/^\d{1,5}$/.test(environment.HOST_AGENT_PORT) ||
    Number(environment.HOST_AGENT_PORT) < 1 ||
    Number(environment.HOST_AGENT_PORT) > 65535
  )
    reject();
  const oldConfiguration = structuredClone(configuration);
  const newConfiguration = structuredClone(configuration);
  if (responseConfigPath !== undefined) {
    absolute(responseConfigPath);
    const relative = path.relative(instance, responseConfigPath);
    if (
      !relative ||
      relative.startsWith('..') ||
      path.isAbsolute(relative) ||
      same(responseConfigPath, configPath)
    )
      reject();
    if (
      environment.CODEX_DESKTOP_RESPONSES_CONFIG !== undefined &&
      (typeof environment.CODEX_DESKTOP_RESPONSES_CONFIG !== 'string' ||
        !same(absolute(environment.CODEX_DESKTOP_RESPONSES_CONFIG), responseConfigPath))
    )
      reject();
    newConfiguration.environment.CODEX_DESKTOP_RESPONSES_CONFIG = responseConfigPath;
  }
  const oldRoute = structuredClone(current);
  const newRoute = {
    ...oldRoute,
    operationId,
    runtimeSha256: desiredRuntimeSha256,
    sourceCommit: desiredSourceCommit,
    program: path.join(programs, desiredRuntimeSha256),
  };
  const action = (program) => {
    const node = path.join(program, 'bin', 'node.exe');
    const worker = path.join(program, 'scripts', 'windows-desktop-worker.mjs');
    return {
      launcher: launcherPath,
      launcherSha256,
      node,
      worker,
      configuration: current.configuration,
      workingDirectory: path.join(program, 'scripts'),
      arguments: `"${node}" "${worker}" "${current.configuration}"`,
    };
  };
  return {
    operationId,
    expectedCurrentSha256,
    oldRoute,
    newRoute,
    oldConfiguration,
    newConfiguration,
    oldAction: action(oldRoute.program),
    newAction: action(newRoute.program),
  };
}
