import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { homedir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import { verifyRegularRuntime } from './windows-host-regular-runtime.mjs';
import { regularPowerShellEnvironment } from './windows-host-regular-recovery.mjs';
import { verifyLegacyTestScope } from './windows-host-legacy-scope.mjs';

async function prepareActivationStartup(configuration, directory) {
  const root = fileURLToPath(new URL('..', import.meta.url));
  const manifest = verifyRegularRuntime(root, root.split(/[\\/]/).filter(Boolean).at(-1));
  const { writePrivateFileSync, closeWindowsPrivateStateBackend } =
    await import('../packages/codex-adapter/dist/desktop-platform.js');
  const { prepareWindowsStartupProbe } = await import('./windows-startup-preflight.mjs');
  const fingerprints = {};
  for (const component of [
    'apps/host-agent',
    'packages/codex-adapter',
    'packages/protocol',
    'packages/service-runtime',
    'packages/notification-runtime',
  ]) {
    const prefix = component + '/dist/';
    const files = Object.fromEntries(
      Object.keys(manifest.files)
        .filter(
          (name) =>
            name.startsWith(prefix) &&
            name.endsWith('.js') &&
            !name.slice(prefix.length).includes('/'),
        )
        .sort()
        .map((name) => [name.slice(prefix.length), manifest.files[name].sha256]),
    );
    if (!Object.keys(files).length) fail('HOST_ACTIVATION_BUILD_MISSING');
    fingerprints[component] = sha(JSON.stringify(files));
  }
  const bundle = join(directory, 'startup-bundle.json');
  try {
    writePrivateFileSync(bundle, JSON.stringify({ runtimeFingerprints: fingerprints }) + '\n', {
      flag: 'wx',
    });
    return await prepareWindowsStartupProbe({
      hostConfig: configuration,
      bundle,
      directory: join(directory, 'startup-envelope'),
      workingDirectory: join(root, 'scripts'),
    });
  } finally {
    closeWindowsPrivateStateBackend();
  }
}

const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');
const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/;
const digest = /^[a-f0-9]{64}$/;
const fail = (code) => {
  throw Object.assign(new Error(code), { code });
};

// Uptime must decrease as well as the OS boot time changing. A wall-clock
// adjustment, sleep/resume or a new user logon is not sufficient evidence.
export function qualifyRestart(before, after, restartConfirmed) {
  if (!restartConfirmed) fail('HOST_RESTART_CONFIRMATION_REQUIRED');
  if (
    !before ||
    !after ||
    before.machineBinding !== after.machineBinding ||
    before.userBinding !== after.userBinding ||
    before.hostId !== after.hostId ||
    !digest.test(before.machineBinding ?? '') ||
    !digest.test(before.userBinding ?? '') ||
    !uuid.test(before.hostId ?? '')
  )
    fail('HOST_RESTART_TARGET_CHANGED');
  const utc = (value) => {
    if (typeof value !== 'string' || !/Z$/.test(value) || !Number.isFinite(Date.parse(value)))
      fail('HOST_RESTART_WITNESS_REJECTED');
    return Date.parse(value);
  };
  const counter = (value) => {
    if (typeof value !== 'string' || !/^[0-9]{1,20}$/.test(value))
      fail('HOST_RESTART_WITNESS_REJECTED');
    const result = BigInt(value);
    if (result < 0n || result > 18446744073709551615n) fail('HOST_RESTART_WITNESS_REJECTED');
    return result;
  };
  const oldUptime = counter(before.uptimeMs),
    newUptime = counter(after.uptimeMs);
  const oldBoot = utc(before.bootUtc),
    newBoot = utc(after.bootUtc);
  const prepared = utc(before.observedUtc),
    observed = utc(after.observedUtc);
  if (
    oldUptime < 60000n ||
    newUptime + 5000n >= oldUptime ||
    newBoot <= oldBoot ||
    newBoot < prepared - 2000 ||
    observed < newBoot ||
    observed < prepared
  )
    fail('HOST_NEW_BOOT_NOT_PROVED');
  return {
    qualifiedOldBootRetirement: true,
    historicalTestPassed: false,
    historicalSubtreeVerified: false,
  };
}

export function parseActivation(argv) {
  const keys = [
    'installed-program',
    'host-id',
    'legacy-operation-id',
    'preparation-id',
    'operation-id',
    'checkpoint-sha256',
    'runtime-sha256',
    'failed-domain-id',
    'failed-domain-sha256',
  ];
  const { values, positionals, tokens } = parseArgs({
    args: argv,
    strict: true,
    allowPositionals: true,
    tokens: true,
    options: {
      ...Object.fromEntries(keys.map((k) => [k, { type: 'string' }])),
      'restart-confirmed': { type: 'boolean', default: false },
      'preserve-legacy-test': { type: 'boolean', default: false },
      'deployment-runtime-sha256': { type: 'string' },
    },
  });
  if (
    positionals.length !== 1 ||
    ![
      'prepare',
      'continue-prepare',
      'status',
      'qualify-no-restart',
      'activate',
      'rollback',
    ].includes(positionals[0]) ||
    keys.some((k) => !values[k]) ||
    new Set(tokens.filter((t) => t.kind === 'option').map((t) => t.name)).size !==
      tokens.filter((t) => t.kind === 'option').length
  )
    fail('HOST_ACTIVATION_ARGUMENTS_REJECTED');
  for (const key of [
    'host-id',
    'legacy-operation-id',
    'preparation-id',
    'operation-id',
    'failed-domain-id',
  ])
    if (!uuid.test(values[key])) fail('HOST_ACTIVATION_ID_REJECTED');
  if (
    new Set(
      ['legacy-operation-id', 'preparation-id', 'operation-id', 'failed-domain-id'].map(
        (key) => values[key],
      ),
    ).size !== 4
  )
    fail('HOST_ACTIVATION_ID_REJECTED');
  for (const key of ['checkpoint-sha256', 'runtime-sha256', 'failed-domain-sha256'])
    if (!digest.test(values[key])) fail('HOST_ACTIVATION_HASH_REJECTED');
  const path = values['installed-program'];
  if (
    !/^[A-Za-z]:\\/.test(path) ||
    path.slice(2).includes(':') ||
    path.includes('/') ||
    [...path].some((character) => character.charCodeAt(0) < 32) ||
    /[<>"|?*]/.test(path) ||
    path
      .split('\\')
      .slice(1)
      .some((part) => !part || part === '.' || part === '..' || /[. ]$/.test(part))
  )
    fail('HOST_ACTIVATION_PATH_REJECTED');
  if (values['restart-confirmed'] && positionals[0] !== 'activate')
    fail('HOST_ACTIVATION_CONFIRMATION_REJECTED');
  if (
    values['preserve-legacy-test'] &&
    (positionals[0] !== 'activate' || values['restart-confirmed'])
  )
    fail('HOST_ACTIVATION_CONFIRMATION_REJECTED');
  if (
    values['deployment-runtime-sha256'] &&
    (positionals[0] === 'prepare' || !digest.test(values['deployment-runtime-sha256']))
  )
    fail('HOST_ACTIVATION_DEPLOYMENT_REJECTED');
  return { action: positionals[0], values };
}

export function regularActivation(argv = process.argv.slice(2)) {
  const { action, values } = parseActivation(argv);
  if (process.platform !== 'win32') fail('HOST_ACTIVATION_WINDOWS_REQUIRED');
  const root = fileURLToPath(new URL('..', import.meta.url));
  verifyRegularRuntime(root, values['runtime-sha256']);
  const runtimeSha = values['deployment-runtime-sha256'] ?? values['runtime-sha256'];
  const deployment = values['deployment-runtime-sha256']
    ? join(homedir(), '.config/agent-hub/windows-host-v1/programs', runtimeSha)
    : root;
  const manifest = verifyRegularRuntime(deployment, runtimeSha);
  const nativeFiles = [
    'private-state.cs',
    'private-state-compilation.cs',
    'private-state-recovery.cs',
    'private-state-activation.cs',
  ];
  const request = {
    format: 'windows-host-regular-activation-v1',
    action,
    installedProgram: values['installed-program'],
    hostId: values['host-id'],
    legacyOperationId: values['legacy-operation-id'],
    preparationId: values['preparation-id'],
    operationId: values['operation-id'],
    checkpointSha256: values['checkpoint-sha256'],
    runtimeSha256: runtimeSha,
    failedDomainId: values['failed-domain-id'],
    failedDomainSha256: values['failed-domain-sha256'],
    sourceRoot: deployment,
    controllerRoot: root,
    controllerSha256: values['runtime-sha256'],
    sourceCommit: manifest.sourceCommit,
    restartConfirmed: values['restart-confirmed'],
    preserveLegacyTest: values['preserve-legacy-test'] || action === 'qualify-no-restart',
    legacyScope:
      values['preserve-legacy-test'] || action === 'qualify-no-restart'
        ? verifyLegacyTestScope({
            profile: homedir(),
            hostId: values['host-id'],
            operationId: values['legacy-operation-id'],
            installedProgram: values['installed-program'],
          })
        : null,
    sourceHashes: Object.fromEntries(
      nativeFiles.map((name) => [
        name,
        sha(readFileSync(join(root, 'packages/codex-adapter/windows', name))),
      ]),
    ),
    helperDigests: Object.fromEntries(
      ['private-state', 'host-launcher'].map((name) => {
        const files =
          name === 'private-state'
            ? ['private-state.cs', 'private-state-compilation.cs']
            : [
                'host-launcher.cs',
                'private-state.cs',
                'private-state-compilation.cs',
                'private-state-recovery.cs',
              ];
        const hash = createHash('sha256');
        for (const file of files)
          hash.update(readFileSync(join(deployment, 'packages/codex-adapter/windows', file)));
        return [name, hash.digest('hex')];
      }),
    ),
  };
  let raw;
  try {
    raw = execFileSync(
      join(
        process.env.SystemRoot ?? 'C:\\Windows',
        'System32/WindowsPowerShell/v1.0/powershell.exe',
      ),
      [
        '-NoProfile',
        '-NonInteractive',
        '-File',
        join(root, 'scripts/windows-host-regular-activation.ps1'),
        '-Action',
        action,
      ],
      {
        input: JSON.stringify(request),
        encoding: 'utf8',
        windowsHide: true,
        timeout: 240000,
        maxBuffer: 16384,
        env: {
          ...regularPowerShellEnvironment(process.env),
          CODEX_MANAGE_WINDOWS_NATIVE_STORAGE: 'profile-v1',
          CODEX_DESKTOP_SUGGESTION_OBSERVER: '0',
          CODEX_DESKTOP_TRACE_RETENTION: '0',
        },
      },
    );
  } catch (error) {
    if (typeof error.stdout === 'string' && error.stdout.trim().startsWith('{')) raw = error.stdout;
    else fail('HOST_ACTIVATION_TRANSPORT_UNCONFIRMED');
  }
  const result = JSON.parse(raw);
  if (
    result.format !== request.format ||
    result.operationId !== request.operationId ||
    result.hostId !== request.hostId
  )
    fail('HOST_ACTIVATION_RESPONSE_REJECTED');
  return result;
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (process.argv.length === 5 && process.argv[2] === '--prepare-startup') {
    try {
      process.stdout.write(
        JSON.stringify(await prepareActivationStartup(process.argv[3], process.argv[4])) + '\n',
      );
    } catch (error) {
      process.stdout.write(
        JSON.stringify({
          status: 'rejected',
          code: /^HOST_[A-Z0-9_]+$/.test(error.code ?? '')
            ? error.code
            : 'HOST_ACTIVATION_STARTUP_PREPARATION_FAILED',
        }) + '\n',
      );
      process.exitCode = 1;
    }
  } else if (process.argv.length === 3 && process.argv[2] === '--qualify-restart') {
    try {
      const raw = readFileSync(0);
      if (raw.length > 16384) fail('HOST_RESTART_WITNESS_REJECTED');
      const input = JSON.parse(raw);
      process.stdout.write(
        JSON.stringify(qualifyRestart(input.before, input.after, input.restartConfirmed)) + '\n',
      );
    } catch (error) {
      process.stdout.write(
        JSON.stringify({
          status: 'rejected',
          code: /^HOST_[A-Z0-9_]+$/.test(error.code ?? '')
            ? error.code
            : 'HOST_RESTART_WITNESS_REJECTED',
        }) + '\n',
      );
      process.exitCode = 1;
    }
  } else {
    try {
      const result = regularActivation();
      process.stdout.write(JSON.stringify(result) + '\n');
      if (!['prepared', 'active', 'rolled-back'].includes(result.status) && !result.observationOnly)
        process.exitCode = 1;
    } catch (error) {
      process.stdout.write(
        JSON.stringify({
          format: 'windows-host-regular-activation-v1',
          status: 'rejected',
          code: /^HOST_[A-Z0-9_]+$/.test(error.code ?? '') ? error.code : 'HOST_ACTIVATION_FAILURE',
          resultMayBeUnknown: true,
        }) + '\n',
      );
      process.exitCode = 1;
    }
  }
}
