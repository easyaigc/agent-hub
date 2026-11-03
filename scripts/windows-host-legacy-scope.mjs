import { createHash } from 'node:crypto';
import { readFileSync, lstatSync } from 'node:fs';
import { dirname, win32 } from 'node:path';

// This exception is for one reviewed synthetic test implementation, never an
// arbitrary pending migration. Its unknown execution result remains unknown.
const reviewedManifest = '3b170dd2bc1325266b929c0f3934f5b658e82367f7f1815b1374251c56bee1ea';
const reviewedCommit = '35ff3a29e22614b4c97d3332f4dd3d3eb8e902ee';
const sha = (value) => createHash('sha256').update(value).digest('hex');
const reject = () => {
  throw Object.assign(new Error('HOST_LEGACY_TEST_SCOPE_UNPROVED'), {
    code: 'HOST_LEGACY_TEST_SCOPE_UNPROVED',
  });
};

function publicBytes(path) {
  for (let cursor = path; ; cursor = dirname(cursor)) {
    if (lstatSync(cursor).isSymbolicLink()) reject();
    if (dirname(cursor) === cursor) break;
  }
  const stat = lstatSync(path);
  if (!stat.isFile() || stat.nlink !== 1 || stat.size > 1048576) reject();
  const bytes = readFileSync(path);
  if (bytes.length !== stat.size) reject();
  return bytes;
}

// Reconstruct the historical fixed command as data. Do not import, execute or
// replay the legacy installer, bootstrap, runner or domain verification.
export function legacyDomainAction({ profile, hostId, operationId, configuration, bootstrap }) {
  const privateRoot = win32.join(profile, '.config', 'agent-hub');
  const release = win32.join(privateRoot, 'host-installs', reviewedManifest);
  const mutable = win32.join(privateRoot, 'host-runtime', hostId);
  const settings = {
    format: 'windows-protected-host-v1',
    profile,
    manifestSha256: reviewedManifest,
    hostId,
    configuration,
    privateRoot,
    release,
    manifest: win32.join(release, 'resources.json'),
    node: win32.join(release, 'runtime', 'node.exe'),
    program: win32.join(release, 'program'),
    worker: win32.join(release, 'program', 'scripts', 'windows-desktop-worker.mjs'),
    mutable,
    compiler: win32.join(mutable, 'compiler'),
    nativeHelpers: win32.join(mutable, 'native-helpers'),
    researchOnly: true,
    purpose: 'domain-verification',
    verificationId: operationId,
  };
  const script =
    "$ErrorActionPreference='Stop';$ProtectedHostSettings='" +
    JSON.stringify(settings).replaceAll("'", "''") +
    "';\n" +
    bootstrap;
  const quoted =
    '"' +
    script
      .replaceAll('\r\n', '\n')
      .replace(/(\\*)"/g, '$1$1\\"')
      .replace(/(\\+)$/g, '$1$1') +
    '"';
  return '-NoProfile -NonInteractive -WindowStyle Hidden -Command ' + quoted;
}

export function verifyLegacyTestScope({ profile, hostId, operationId, installedProgram }) {
  const hub = win32.join(profile, '.config', 'agent-hub');
  const migration = win32.join(hub, 'host-migrations', operationId);
  const intentBytes = publicBytes(win32.join(migration, 'intent.json'));
  const domainBytes = publicBytes(win32.join(migration, 'domain.intent.json'));
  const intent = JSON.parse(intentBytes),
    domain = JSON.parse(domainBytes);
  const configuration = win32.join(
    installedProgram,
    '.local',
    'desktop-runtime',
    'windows-host.json',
  );
  if (
    intent.operationId !== operationId ||
    domain.operationId !== operationId ||
    intent.hostId !== hostId ||
    intent.installedProgram !== installedProgram ||
    intent.configuration !== configuration ||
    intent.manifestSha256 !== reviewedManifest ||
    domain.manifestSha256 !== reviewedManifest ||
    domain.domainFolder !== 'CodexManage Recovery Test ' + operationId ||
    domain.guardFolder !== 'CodexManage Recovery Guard ' + operationId
  )
    reject();
  const release = win32.join(hub, 'host-installs', reviewedManifest);
  const manifestBytes = publicBytes(win32.join(release, 'resources.json'));
  if (sha(manifestBytes) !== reviewedManifest) reject();
  const manifest = JSON.parse(manifestBytes);
  if (manifest.sourceCommit !== reviewedCommit) reject();
  const names = [
    'windows-host-protected-contract.mjs',
    'windows-host-protected-install.mjs',
    'windows-host-protected-install.ps1',
    'windows-host-sealed-bootstrap.ps1',
    'windows-host-protected-runner.cs',
    'windows-host-task-domain.ps1',
    'windows-host-task-domain-limited.ps1',
    'windows-host-task-domain-worker.mjs',
    'windows-host-task-domain-contract.mjs',
  ];
  let bootstrap;
  for (const name of names) {
    const relative = 'program/scripts/' + name;
    const entry = manifest.files.find((file) => file.path === relative);
    const bytes = publicBytes(win32.join(release, relative));
    if (!entry || bytes.length !== entry.bytes || sha(bytes) !== entry.sha256) reject();
    if (name === 'windows-host-sealed-bootstrap.ps1') bootstrap = bytes.toString('utf8');
  }
  const actionSha256 = sha(
    legacyDomainAction({ profile, hostId, operationId, configuration, bootstrap }),
  );
  if (actionSha256 !== domain.actionSha256) reject();
  return {
    manifestSha256: reviewedManifest,
    sourceCommit: reviewedCommit,
    actionSha256,
    intentSha256: sha(intentBytes),
    domainIntentSha256: sha(domainBytes),
    purpose: 'domain-verification',
    reviewedExecutionChainVerified: true,
    historicalTestPassed: false,
    historicalSubtreeVerified: false,
    pendingArchived: false,
  };
}
