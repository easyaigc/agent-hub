import { createHash } from 'node:crypto';
import { readFileSync, lstatSync, readdirSync, realpathSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { dirname, join, resolve, win32 } from 'node:path';
import { homedir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import { parseRegularPreparation, preparationExpected } from './windows-host-regular-prepare.mjs';
import {
  observePublicProgram,
  regularPowerShellEnvironment,
} from './windows-host-regular-recovery.mjs';

const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
const root = fileURLToPath(new URL('..', import.meta.url));
const fail = (code) => {
  throw Object.assign(new Error(code), { code });
};
export const regularDomainCases = Object.freeze([
  'positive-update',
  'positive-delete',
  'negative-update',
  'negative-delete',
  'create-task',
  'create-folder',
  'set-folder-security',
  'delete-guard-folder',
]);
export function parseRegularDomain(argv) {
  const keys = [
    'installed-program',
    'legacy-bundle',
    'host-id',
    'legacy-operation-id',
    'operation-id',
    'p0-report',
    'p0-sha256',
    'p1-report',
    'p1-sha256',
    'manifest-sha256',
  ];
  const { values, positionals, tokens } = parseArgs({
    args: argv,
    options: Object.fromEntries(
      [...keys, 'failure-report', 'failure-sha256', 'previous-manifest-sha256'].map((k) => [
        k,
        { type: 'string' },
      ]),
    ),
    strict: true,
    allowPositionals: true,
    tokens: true,
  });
  const names = tokens.filter((x) => x.kind === 'option').map((x) => x.name);
  if (
    positionals.length !== 1 ||
    !['verify', 'status', 'resume'].includes(positionals[0]) ||
    new Set(names).size !== names.length ||
    keys.some((k) => !values[k])
  )
    fail('HOST_DOMAIN_ARGUMENTS_REJECTED');
  const recoveryKeys = ['failure-report', 'failure-sha256', 'previous-manifest-sha256'];
  if (
    (positionals[0] === 'resume' && recoveryKeys.some((k) => !values[k])) ||
    (positionals[0] !== 'resume' && recoveryKeys.some((k) => values[k] !== undefined))
  )
    fail('HOST_DOMAIN_RESUME_ARGUMENTS_REJECTED');
  const common = [
    'installed-program',
    'legacy-bundle',
    'host-id',
    'legacy-operation-id',
    'operation-id',
    'p0-report',
    'p0-sha256',
    'manifest-sha256',
  ];
  parseRegularPreparation(['prepare', ...common.flatMap((k) => ['--' + k, values[k]])]);
  parseRegularPreparation([
    'prepare',
    ...common.flatMap((k) => [
      '--' + k,
      k === 'p0-report' ? values['p1-report'] : k === 'p0-sha256' ? values['p1-sha256'] : values[k],
    ]),
  ]);
  if (positionals[0] === 'resume') {
    parseRegularPreparation([
      'prepare',
      ...common.flatMap((k) => [
        '--' + k,
        k === 'p0-report'
          ? values['failure-report']
          : k === 'p0-sha256'
            ? values['failure-sha256']
            : k === 'manifest-sha256'
              ? values['previous-manifest-sha256']
              : values[k],
      ]),
    ]);
  }
  return { action: positionals[0], values };
}
export function preRunFailureBinding(value, operationId) {
  if (
    value?.format !== 'windows-host-regular-domain-v1' ||
    value.sourceCommit !== '7c6fb472526c93db43466d1c19de52c17cd0ebe4' ||
    value.status !== 'rejected' ||
    value.phase !== 'compile-fixed-domain-executable' ||
    value.code !== 'HOST_DOMAIN_NATIVE_FAILURE' ||
    value.errorType !== 'System.UnauthorizedAccessException' ||
    value.hresult !== '0x80070005' ||
    value.line !== 7 ||
    ![
      'taskMutationAttempted',
      'runAttempted',
      'originalTaskMutationAttempted',
      'hostStarted',
      'pendingReleased',
      'historicalSubtreeVerified',
      'restartAuthorized',
    ].every((k) => value[k] === false) ||
    value.installedProgramStillMatchesP0 !== true ||
    value.nodeFileStillMatchesP0 !== true
  )
    fail('HOST_DOMAIN_PRE_RUN_FAILURE_REJECTED');
  return { operationId, previousSourceCommit: value.sourceCommit };
}
export function nativeP1Binding(value, operation) {
  if (
    value?.format !== 'windows-host-regular-prepare-v1' ||
    value.status !== 'prepared' ||
    value.action !== 'prepare' ||
    value.sourceCommit !== '4901b94999bdb9aad5698133f34df9edef2acc54' ||
    value.operationId === operation ||
    !/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value.operationId ?? '') ||
    !/^[a-f0-9]{64}$/.test(value.checkpointSha256 ?? '') ||
    ![
      'rollbackCapsuleVerified',
      'originalFilesUnchanged',
      'installedProgramStillMatchesP0',
      'nodeFileStillMatchesP0',
    ].every((k) => value[k] === true) ||
    ![
      'hostStarted',
      'originalTaskMutationAttempted',
      'pendingReleased',
      'historicalSubtreeVerified',
      'restartAuthorized',
      'limitedTaskExecutionVerified',
      'activationImplemented',
    ].every((k) => value[k] === false) ||
    value.privateCheck?.exitConfirmed !== true ||
    value.privateCheck.exitCode !== 0 ||
    ![
      'writeRejected',
      'fileRenameRejected',
      'parentRenameRejected',
      'positiveControlsPassed',
    ].every((k) => value.leaseNegatives?.[k] === true)
  )
    fail('HOST_DOMAIN_NATIVE_P1_REJECTED');
  return {
    preparationId: value.operationId,
    checkpointSha256: value.checkpointSha256,
    hostId: value.hostId,
  };
}
export function verifyDomainMatrix(value) {
  if (
    value?.status !== 'verified' ||
    value.limitedTaskExecutionVerified !== true ||
    value.allSubjectsCovered !== false ||
    value.argumentCharacters !== 11264 ||
    value.argumentsRegisteredExactly !== true ||
    value.doubleRunSingleInstance !== true ||
    value.processSubtreeClosed !== true ||
    value.retainedProcessAnchors !== 3 ||
    value.fixturesRemoved !== true ||
    !Array.isArray(value.actualMatrix) ||
    value.actualMatrix.length !== 8
  )
    fail('HOST_DOMAIN_RESULT_REJECTED');
  for (const [i, name] of regularDomainCases.entries()) {
    const x = value.actualMatrix[i];
    if (
      x.name !== name ||
      x.attempted !== true ||
      x.outcome !== (i < 2 ? 'success' : 'denied') ||
      x.hresult !== (i < 2 ? null : '0x80070005')
    )
      fail('HOST_DOMAIN_MATRIX_REJECTED');
  }
  return true;
}
export function verifyDomainPackage(directory, expected) {
  const raw = readFileSync(join(directory, 'manifest.json'));
  if (hash(raw) !== expected) fail('HOST_DOMAIN_MANIFEST_CHANGED');
  const m = JSON.parse(raw);
  if (
    m.format !== 'windows-host-regular-domain-package-v1' ||
    !/^[a-f0-9]{40}$/.test(m.sourceCommit ?? '') ||
    !m.files ||
    Object.keys(m.files).length > 48
  )
    fail('HOST_DOMAIN_PACKAGE_REJECTED');
  const required = [
    'scripts/windows-host-regular-domain.mjs',
    'scripts/windows-host-regular-domain.ps1',
    'scripts/windows-host-regular-domain-common.ps1',
    'scripts/windows-host-regular-limited.ps1',
    'scripts/windows-host-regular-prepare.mjs',
    'scripts/windows-host-regular-recovery.mjs',
    ...[
      'host-domain.cs',
      'private-state.cs',
      'private-state-compilation.cs',
      'private-state-recovery.cs',
      'private-bootstrap.ps1',
    ].map((n) => 'packages/codex-adapter/windows/' + n),
  ];
  if (!required.every((n) => m.files[n])) fail('HOST_DOMAIN_PACKAGE_INCOMPLETE');
  const actual = [];
  function walk(at, prefix = '') {
    for (const entry of readdirSync(at, { withFileTypes: true })) {
      const name = prefix + entry.name;
      const p = join(at, entry.name);
      const s = lstatSync(p);
      if (s.isSymbolicLink()) fail('HOST_DOMAIN_PACKAGE_REPARSE');
      if (s.isDirectory()) walk(p, name + '/');
      else if (s.isFile()) actual.push(name);
      else fail('HOST_DOMAIN_PACKAGE_TYPE');
      if (actual.length > 49) fail('HOST_DOMAIN_PACKAGE_BUDGET');
    }
  }
  walk(directory);
  if (
    JSON.stringify(actual.sort()) !==
    JSON.stringify([...Object.keys(m.files), 'manifest.json'].sort())
  )
    fail('HOST_DOMAIN_PACKAGE_INVENTORY_CHANGED');
  for (const [name, entry] of Object.entries(m.files)) {
    if (
      !/^[a-zA-Z0-9._/-]+$/.test(name) ||
      name.startsWith('/') ||
      name.split('/').some((x) => !x || x === '.' || x === '..')
    )
      fail('HOST_DOMAIN_RESOURCE_PATH_REJECTED');
    const p = join(directory, name);
    let cursor = p;
    for (;;) {
      if (lstatSync(cursor).isSymbolicLink()) fail('HOST_DOMAIN_PACKAGE_REPARSE');
      const parent = dirname(cursor);
      if (parent === cursor) break;
      cursor = parent;
    }
    const s = lstatSync(p);
    if (
      !s.isFile() ||
      s.nlink !== 1 ||
      s.size !== entry.bytes ||
      s.size > 1048576 ||
      hash(readFileSync(p)) !== entry.sha256
    )
      fail('HOST_DOMAIN_RESOURCE_CHANGED');
  }
  return m;
}
function receipt(path, expected) {
  const raw = readFileSync(path);
  if (raw.length > 16384 || hash(raw) !== expected) fail('HOST_DOMAIN_RECEIPT_CHANGED');
  return JSON.parse(raw.toString('utf8'));
}
export function regularHostDomain(argv = process.argv.slice(2)) {
  const { action, values } = parseRegularDomain(argv);
  if (process.platform !== 'win32') fail('HOST_DOMAIN_NATIVE_WINDOWS_REQUIRED');
  const anchor = join(homedir(), '.config/agent-hub');
  const relative = win32.relative(anchor, root);
  if (
    !relative ||
    relative.startsWith('..') ||
    win32.isAbsolute(relative) ||
    realpathSync.native(root).toLowerCase() !== resolve(root).toLowerCase()
  )
    fail('HOST_DOMAIN_PRIVATE_PACKAGE_REQUIRED');
  const manifest = verifyDomainPackage(root, values['manifest-sha256']);
  const p0 = receipt(values['p0-report'], values['p0-sha256']);
  preparationExpected(p0, values);
  const binding = nativeP1Binding(
    receipt(values['p1-report'], values['p1-sha256']),
    values['operation-id'],
  );
  if (
    binding.hostId !== values['host-id'] ||
    binding.preparationId === values['legacy-operation-id']
  )
    fail('HOST_DOMAIN_TARGET_CHANGED');
  const sameNode = () =>
    process.version === p0.node?.version &&
    lstatSync(process.execPath).size === p0.node.executableFile.bytes &&
    hash(readFileSync(process.execPath)) === p0.node.executableFile.sha256;
  const sameProgram = () =>
    JSON.stringify(observePublicProgram(values['installed-program'])) ===
    JSON.stringify(p0.publicProgram);
  if (!sameNode() || !sameProgram()) fail('HOST_DOMAIN_ORIGINAL_CODE_CHANGED');
  if (
    hash(readFileSync(join(values['legacy-bundle'], 'resources.json'))) !==
    '3b170dd2bc1325266b929c0f3934f5b658e82367f7f1815b1374251c56bee1ea'
  )
    fail('HOST_DOMAIN_LEGACY_MANIFEST_CHANGED');
  const names = [
    'host-domain.cs',
    'private-state.cs',
    'private-state-compilation.cs',
    'private-state-recovery.cs',
  ];
  const h = createHash('sha256');
  for (const n of names) h.update(readFileSync(join(root, 'packages/codex-adapter/windows', n)));
  const request = {
    format: 'windows-host-regular-domain-v1',
    action,
    ...binding,
    operationId: values['operation-id'],
    legacyOperationId: values['legacy-operation-id'],
    installedProgram: values['installed-program'],
    p0Sha256: values['p0-sha256'],
    p1ManifestSha256: '797570d17e2ff734db1c76a7980a1ccdded1374e203bb4604f752cb776918e96',
    packageSha256: values['manifest-sha256'],
    helperDigest: h.digest('hex'),
    sourceHashes: Object.fromEntries(
      names.map((n) => [n, manifest.files['packages/codex-adapter/windows/' + n].sha256]),
    ),
    limitedSha256: manifest.files['scripts/windows-host-regular-limited.ps1'].sha256,
    commonSha256: manifest.files['scripts/windows-host-regular-domain-common.ps1'].sha256,
    ...(action === 'resume'
      ? {
          recovery: {
            ...preRunFailureBinding(
              receipt(values['failure-report'], values['failure-sha256']),
              values['operation-id'],
            ),
            failureSha256: values['failure-sha256'],
            previousPackageSha256: values['previous-manifest-sha256'],
          },
        }
      : {}),
  };
  const env = {
    ...regularPowerShellEnvironment(process.env),
    CODEX_MANAGE_WINDOWS_NATIVE_STORAGE: 'profile-v1',
    CODEX_DESKTOP_SUGGESTION_OBSERVER: '0',
    CODEX_DESKTOP_TRACE_RETENTION: '0',
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
        join(root, 'scripts/windows-host-regular-domain.ps1'),
        '-Action',
        action,
      ],
      {
        input: JSON.stringify(request),
        encoding: 'utf8',
        env,
        windowsHide: true,
        timeout: 140000,
        maxBuffer: 16384,
      },
    );
  } catch (e) {
    if (typeof e.stdout === 'string' && e.stdout.trim().startsWith('{')) raw = e.stdout;
    else fail('HOST_DOMAIN_TRANSPORT_UNCONFIRMED');
  }
  const value = JSON.parse(raw);
  if (
    value.format !== 'windows-host-regular-domain-v1' ||
    value.originalTaskMutationAttempted !== false ||
    value.hostStarted !== false ||
    value.pendingReleased !== false ||
    value.historicalSubtreeVerified !== false ||
    value.restartAuthorized !== false
  )
    fail('HOST_DOMAIN_RESPONSE_REJECTED');
  if (value.status === 'verified') {
    if (
      value.operationId !== values['operation-id'] ||
      value.preparationId !== binding.preparationId ||
      value.checkpointSha256 !== binding.checkpointSha256
    )
      fail('HOST_DOMAIN_RESULT_TARGET_CHANGED');
    verifyDomainMatrix(value);
  }
  value.sourceCommit = manifest.sourceCommit;
  value.installedProgramStillMatchesP0 = sameProgram();
  value.nodeFileStillMatchesP0 = sameNode();
  if (!value.installedProgramStillMatchesP0 || !value.nodeFileStillMatchesP0)
    value.status = 'original-code-changed';
  return value;
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const value = regularHostDomain();
    const raw = JSON.stringify(value);
    if (Buffer.byteLength(raw) > 16384) fail('HOST_DOMAIN_OUTPUT_BUDGET');
    process.stdout.write(raw + '\n');
    if (value.status !== 'verified') process.exitCode = 1;
  } catch (e) {
    process.stdout.write(
      JSON.stringify({
        format: 'windows-host-regular-domain-v1',
        status: 'rejected',
        code: /^HOST_[A-Z0-9_]+$/.test(e.code ?? '') ? e.code : 'HOST_DOMAIN_FAILURE',
        newResourcesMayExist: true,
        originalTaskMutationAttempted: false,
        hostStarted: false,
        pendingReleased: false,
        historicalSubtreeVerified: false,
        restartAuthorized: false,
      }) + '\n',
    );
    process.exitCode = 1;
  }
}
