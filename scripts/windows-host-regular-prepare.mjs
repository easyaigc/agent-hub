import { createHash } from 'node:crypto';
import { readFileSync, lstatSync, realpathSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { dirname, join, resolve, win32 } from 'node:path';
import { homedir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import {
  observePublicProgram,
  parseRegularPreflight,
  regularPowerShellEnvironment,
} from './windows-host-regular-recovery.mjs';

const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');
const root = fileURLToPath(new URL('..', import.meta.url));
const fail = (code) => {
  throw Object.assign(new Error(code), { code });
};
export function parseRegularPreparation(argv) {
  const options = Object.fromEntries(
    [
      'installed-program',
      'legacy-bundle',
      'host-id',
      'legacy-operation-id',
      'operation-id',
      'p0-report',
      'p0-sha256',
      'manifest-sha256',
    ].map((k) => [k, { type: 'string' }]),
  );
  const { values, positionals, tokens } = parseArgs({
    args: argv,
    options,
    allowPositionals: true,
    tokens: true,
    strict: true,
  });
  const names = tokens.filter((x) => x.kind === 'option').map((x) => x.name);
  if (
    new Set(names).size !== names.length ||
    positionals.length !== 1 ||
    !['prepare', 'status', 'resume'].includes(positionals[0]) ||
    Object.keys(options).some((k) => !values[k])
  )
    fail('HOST_PREPARATION_ARGUMENTS_REJECTED');
  parseRegularPreflight([
    'preflight',
    ...['installed-program', 'legacy-bundle', 'host-id', 'legacy-operation-id'].flatMap((k) => [
      '--' + k,
      values[k],
    ]),
  ]);
  parseRegularPreflight([
    'preflight',
    '--installed-program',
    values['p0-report'],
    '--legacy-bundle',
    values['legacy-bundle'],
    '--host-id',
    values['host-id'],
    '--legacy-operation-id',
    values['operation-id'],
  ]);
  if (
    values['operation-id'] === values['legacy-operation-id'] ||
    !/^[a-f0-9]{64}$/.test(values['p0-sha256']) ||
    !/^[a-f0-9]{64}$/.test(values['manifest-sha256'])
  )
    fail('HOST_PREPARATION_IDENTITY_REJECTED');
  return { action: positionals[0], values };
}
export function preparationExpected(p0, values) {
  if (
    p0.format !== 'windows-host-regular-preflight-v1' ||
    p0.status !== 'observed' ||
    p0.readOnly !== true ||
    p0.mutationAuthorized !== false ||
    p0.hostId !== values['host-id'] ||
    p0.legacyOperationId !== values['legacy-operation-id'] ||
    p0.legacy?.pending?.phase !== 'domain-verify' ||
    p0.legacy?.['domain.process-anchors.json']?.exists !== false ||
    !['available', 'taskXmlMatches', 'taskSdMatches', 'rootSdMatches'].every(
      (k) => p0.task?.legacyOriginalComparison?.[k] === true,
    )
  )
    fail('HOST_PREPARATION_P0_REJECTED');
  if (
    ![
      'hostIdMatches',
      'installedProgramMatches',
      'configurationMatches',
      'operationMatches',
      'manifestMatches',
    ].every((k) => p0.legacy['intent.json']?.[k] === true) ||
    p0.legacy['domain.intent.json']?.operationMatches !== true ||
    p0.legacy['domain.intent.json']?.manifestMatches !== true ||
    p0.legacy['domain.run-attempt.json']?.operationMatches !== true
  )
    fail('HOST_PREPARATION_LEGACY_BINDING_REJECTED');
  const fields = {
    configuration: p0.configuration?.sha256,
    pending: p0.legacy.pending.sha256,
    intent: p0.legacy['intent.json']?.sha256,
    domainIntent: p0.legacy['domain.intent.json']?.sha256,
    runAttempt: p0.legacy['domain.run-attempt.json']?.sha256,
  };
  if (
    !Object.values(fields).every((x) => /^[a-f0-9]{64}$/.test(x ?? '')) ||
    p0.task?.instances !== 0 ||
    p0.task?.principalMatchesCurrentUser !== true ||
    p0.task?.runLevel !== 0 ||
    p0.task?.logonType !== 3 ||
    p0.legacy.pending.operationMatches !== true ||
    p0.legacy.pending.manifestMatches !== true
  )
    fail('HOST_PREPARATION_P0_BINDING_REJECTED');
  return { files: fields, task: p0.task, boot: p0.boot.lastBootUpTime };
}
export function verifyPreparationPackage(directory, expected) {
  const raw = readFileSync(join(directory, 'manifest.json'));
  if (sha(raw) !== expected) fail('HOST_PREPARATION_MANIFEST_CHANGED');
  const manifest = JSON.parse(raw);
  if (
    manifest.format !== 'windows-host-regular-prepare-package-v1' ||
    !/^[a-f0-9]{40}$/.test(manifest.sourceCommit ?? '') ||
    !manifest.files ||
    Object.keys(manifest.files).length > 32
  )
    fail('HOST_PREPARATION_PACKAGE_REJECTED');
  const required = [
    'scripts/windows-host-regular-prepare.mjs',
    'scripts/windows-host-regular-prepare.ps1',
    'scripts/windows-host-regular-recovery.mjs',
    ...[
      'private-state.cs',
      'private-state-compilation.cs',
      'private-state-recovery.cs',
      'private-state.ps1',
      'private-bootstrap.ps1',
      'host-launcher.cs',
    ].map((x) => 'packages/codex-adapter/windows/' + x),
  ];
  if (!required.every((k) => manifest.files[k])) fail('HOST_PREPARATION_PACKAGE_INCOMPLETE');
  for (const [name, info] of Object.entries(manifest.files)) {
    if (
      !/^[a-zA-Z0-9._/-]+$/.test(name) ||
      name.startsWith('/') ||
      name.split('/').some((x) => !x || x === '.' || x === '..')
    )
      fail('HOST_PREPARATION_RESOURCE_PATH_REJECTED');
    const path = join(directory, name);
    let cursor = path;
    while (true) {
      if (lstatSync(cursor).isSymbolicLink()) fail('HOST_PREPARATION_RESOURCE_REPARSE');
      const p = dirname(cursor);
      if (p === cursor) break;
      cursor = p;
    }
    const st = lstatSync(path);
    if (
      !st.isFile() ||
      st.nlink !== 1 ||
      st.size !== info.bytes ||
      st.size > 1048576 ||
      sha(readFileSync(path)) !== info.sha256
    )
      fail('HOST_PREPARATION_RESOURCE_CHANGED');
  }
  return manifest;
}
export function regularHostPreparation(argv = process.argv.slice(2)) {
  const { action, values } = parseRegularPreparation(argv);
  if (process.platform !== 'win32') fail('HOST_PREPARATION_NATIVE_WINDOWS_REQUIRED');
  const anchor = join(homedir(), '.config/agent-hub');
  const relative = win32.relative(anchor, root);
  if (
    !relative ||
    relative.startsWith('..') ||
    win32.isAbsolute(relative) ||
    realpathSync.native(root).toLowerCase() !== resolve(root).toLowerCase()
  )
    fail('HOST_PREPARATION_PRIVATE_PACKAGE_REQUIRED');
  const manifest = verifyPreparationPackage(root, values['manifest-sha256']);
  const raw = readFileSync(values['p0-report']);
  if (raw.length > 16384 || sha(raw) !== values['p0-sha256']) fail('HOST_PREPARATION_P0_CHANGED');
  const p0 = JSON.parse(raw.toString('utf8'));
  const expected = preparationExpected(p0, values);
  const nodeFile = lstatSync(process.execPath);
  if (
    nodeFile.size > 268435456 ||
    process.version !== p0.node?.version ||
    nodeFile.size !== p0.node?.executableFile?.bytes ||
    sha(readFileSync(process.execPath)) !== p0.node?.executableFile?.sha256
  )
    fail('HOST_PREPARATION_NODE_CHANGED');
  if (
    JSON.stringify(observePublicProgram(values['installed-program'])) !==
    JSON.stringify(p0.publicProgram)
  )
    fail('HOST_PREPARATION_INSTALLED_CODE_CHANGED');
  if (
    sha(readFileSync(join(values['legacy-bundle'], 'resources.json'))) !==
    '3b170dd2bc1325266b929c0f3934f5b658e82367f7f1815b1374251c56bee1ea'
  )
    fail('HOST_PREPARATION_LEGACY_MANIFEST_CHANGED');
  const helperDigests = {};
  for (const name of ['private-state', 'host-launcher']) {
    const files =
      name === 'private-state'
        ? ['private-state.cs', 'private-state-compilation.cs']
        : [
            'host-launcher.cs',
            'private-state.cs',
            'private-state-compilation.cs',
            'private-state-recovery.cs',
          ];
    const h = createHash('sha256');
    for (const file of files)
      h.update(readFileSync(join(root, 'packages/codex-adapter/windows', file)));
    helperDigests[name] = h.digest('hex');
  }
  const sourceHashes = Object.fromEntries(
    ['private-state.cs', 'private-state-compilation.cs', 'private-state-recovery.cs'].map(
      (name) => [name, manifest.files['packages/codex-adapter/windows/' + name].sha256],
    ),
  );
  const request = {
    format: 'windows-host-regular-prepare-v1',
    action,
    operationId: values['operation-id'],
    legacyOperationId: values['legacy-operation-id'],
    hostId: values['host-id'],
    installedProgram: values['installed-program'],
    p0Sha256: values['p0-sha256'],
    packageSha256: values['manifest-sha256'],
    helperDigests,
    sourceHashes,
    expected,
  };
  // Only this independent preparation process and its PS/native children use
  // the new mode. No desktop/User/Machine environment or config is written.
  const env = {
    ...regularPowerShellEnvironment(process.env),
    CODEX_MANAGE_WINDOWS_NATIVE_STORAGE: 'profile-v1',
    CODEX_DESKTOP_SUGGESTION_OBSERVER: '0',
    CODEX_DESKTOP_TRACE_RETENTION: '0',
  };
  let rawResult;
  try {
    rawResult = execFileSync(
      join(
        process.env.SystemRoot ?? 'C:\\Windows',
        'System32/WindowsPowerShell/v1.0/powershell.exe',
      ),
      [
        '-NoProfile',
        '-NonInteractive',
        '-File',
        join(root, 'scripts/windows-host-regular-prepare.ps1'),
        '-Action',
        action,
      ],
      {
        input: JSON.stringify(request),
        encoding: 'utf8',
        env,
        windowsHide: true,
        timeout: 110000,
        maxBuffer: 16384,
      },
    );
  } catch (e) {
    if (typeof e.stdout === 'string' && e.stdout.trim().startsWith('{')) rawResult = e.stdout;
    else
      return {
        format: request.format,
        status: 'transport-failed',
        operationId: request.operationId,
        originalTaskMutationAttempted: false,
        hostStarted: false,
        newResourcesMayExist: true,
        restartAuthorized: false,
        stderrSha256: sha(e.stderr ?? ''),
        next: 'query this exact preparation ID; no prepare replay or reservation deletion',
      };
  }
  const result = JSON.parse(rawResult);
  if (
    result.format !== request.format ||
    result.originalTaskMutationAttempted !== false ||
    result.hostStarted !== false ||
    result.restartAuthorized !== false
  )
    fail('HOST_PREPARATION_NATIVE_CONTRACT_REJECTED');
  result.sourceCommit = manifest.sourceCommit;
  result.installedProgramStillMatchesP0 =
    JSON.stringify(observePublicProgram(values['installed-program'])) ===
    JSON.stringify(p0.publicProgram);
  result.nodeFileStillMatchesP0 =
    sha(readFileSync(process.execPath)) === p0.node.executableFile.sha256;
  if (!result.installedProgramStillMatchesP0 || !result.nodeFileStillMatchesP0) {
    result.status = 'original-code-changed';
    result.restartAuthorized = false;
  }
  return result;
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const result = regularHostPreparation();
    const raw = JSON.stringify(result);
    if (Buffer.byteLength(raw) > 16384) fail('HOST_PREPARATION_OUTPUT_BUDGET');
    process.stdout.write(raw + '\n');
    if (result.status !== 'prepared') process.exitCode = 1;
  } catch (e) {
    process.stdout.write(
      JSON.stringify({
        status: 'rejected',
        code: /^HOST_[A-Z_]+$/.test(e.code ?? '') ? e.code : 'HOST_PREPARATION_FAILURE',
        originalTaskMutationAttempted: false,
        hostStarted: false,
        restartAuthorized: false,
        newResourcesMayExist: true,
      }) + '\n',
    );
    process.exitCode = 1;
  }
}
