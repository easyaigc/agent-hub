import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve, win32 } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

const legacyManifest = '3b170dd2bc1325266b929c0f3934f5b658e82367f7f1815b1374251c56bee1ea';
const legacySource = '35ff3a29e22614b4c97d3332f4dd3d3eb8e902ee';
const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/;
const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');
const fail = (code) => {
  throw new Error(code);
};
const errorCode = (error) =>
  /^[A-Z][A-Z0-9_]{1,100}$/.test(error.code ?? '')
    ? error.code
    : /^HOST_[A-Z0-9_]{1,100}$/.test(error.message ?? '')
      ? error.message
      : 'OBSERVATION_FAILED';
export function regularPowerShellEnvironment(source) {
  return Object.fromEntries(
    Object.entries(source).filter(([key]) => key.toUpperCase() !== 'PSMODULEPATH'),
  );
}

export function parseRegularPreflight(argv) {
  if (argv[0] !== 'preflight') fail('HOST_REGULAR_READONLY_PREFLIGHT_REQUIRED');
  const { values, tokens } = parseArgs({
    args: argv.slice(1),
    strict: true,
    allowPositionals: false,
    tokens: true,
    options: Object.fromEntries(
      ['installed-program', 'legacy-bundle', 'host-id', 'legacy-operation-id'].map((name) => [
        name,
        { type: 'string' },
      ]),
    ),
  });
  const seen = new Set();
  for (const token of tokens)
    if (token.kind === 'option') {
      if (seen.has(token.name)) fail('HOST_REGULAR_DUPLICATE_OPTION');
      seen.add(token.name);
    }
  for (const name of ['host-id', 'legacy-operation-id'])
    if (!uuid.test(values[name] ?? '')) fail('HOST_REGULAR_ID_REJECTED');
  for (const name of ['installed-program', 'legacy-bundle']) {
    const value = values[name];
    if (
      typeof value !== 'string' ||
      !/^[A-Za-z]:\\/.test(value) ||
      value.length > 2000 ||
      [...value].some((character) => character.charCodeAt(0) < 32) ||
      /[<>"|?*]/.test(value) ||
      value.includes('/') ||
      value.slice(2).includes(':') ||
      value
        .split('\\')
        .slice(1)
        .some((part) => !part || part === '.' || part === '..' || /[. ]$/.test(part)) ||
      win32.normalize(value) !== value
    )
      fail('HOST_REGULAR_PATH_REJECTED');
  }
  return values;
}

function assertOrdinaryAncestors(path) {
  let cursor = resolve(path);
  for (;;) {
    if (lstatSync(cursor).isSymbolicLink()) fail('HOST_REGULAR_CODE_NAMESPACE_REJECTED');
    const parent = dirname(cursor);
    if (parent === cursor) break;
    cursor = parent;
  }
}

// Public code observation only. This is neither an execution guard nor a claim
// that these inventories prove the complete dynamically resolved ESM closure.
export function publicInventory(root) {
  assertOrdinaryAncestors(root);
  let count = 0,
    total = 0,
    directories = 0;
  const rows = [];
  function visit(path, relative = '', depth = 0) {
    if (++directories > 512 || depth > 12) fail('HOST_REGULAR_CODE_BUDGET_REJECTED');
    const info = lstatSync(path);
    if (info.isSymbolicLink() || !info.isDirectory()) fail('HOST_REGULAR_CODE_NAMESPACE_REJECTED');
    for (const item of readdirSync(path, { withFileTypes: true }).sort((a, b) =>
      a.name.localeCompare(b.name),
    )) {
      const full = join(path, item.name),
        name = relative + item.name,
        s = lstatSync(full);
      if (s.isSymbolicLink()) fail('HOST_REGULAR_CODE_NAMESPACE_REJECTED');
      if (s.isDirectory()) {
        visit(full, name + '/', depth + 1);
        continue;
      }
      if (!s.isFile() || !item.name.endsWith('.js')) continue;
      if (++count > 2048 || s.size > 4194304 || (total += s.size) > 67108864)
        fail('HOST_REGULAR_CODE_BUDGET_REJECTED');
      const raw = readFileSync(full);
      const after = lstatSync(full);
      if (
        after.isSymbolicLink() ||
        raw.length !== s.size ||
        after.size !== s.size ||
        after.mtimeMs !== s.mtimeMs ||
        after.ino !== s.ino ||
        after.dev !== s.dev
      )
        fail('HOST_REGULAR_CODE_CHANGED');
      rows.push([name, raw.length, sha(raw)]);
    }
  }
  visit(root);
  rows.sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0));
  return {
    files: count,
    bytes: total,
    inventorySha256: sha(Buffer.from(JSON.stringify(rows))),
    algorithm: 'sorted-relative-name/bytes/sha256 JSON-v1',
    executionClosureVerified: false,
  };
}
function fileHash(path, maximum = 262144) {
  assertOrdinaryAncestors(path);
  const before = lstatSync(path);
  if (!before.isFile() || before.isSymbolicLink() || before.size > maximum)
    fail('HOST_REGULAR_PUBLIC_FILE_REJECTED');
  const raw = readFileSync(path),
    after = lstatSync(path);
  if (
    before.size !== raw.length ||
    after.size !== before.size ||
    after.mtimeMs !== before.mtimeMs ||
    after.ino !== before.ino ||
    after.dev !== before.dev
  )
    fail('HOST_REGULAR_CODE_CHANGED');
  return { bytes: raw.length, sha256: sha(raw) };
}
export function observePublicProgram(program) {
  const inventories = {};
  for (const [label, path] of Object.entries({
    host: 'apps/host-agent/dist',
    adapterCanonical: 'packages/codex-adapter/dist',
    adapterInstalled: 'node_modules/@codex-manage/codex-adapter/dist',
    protocol: 'packages/protocol/dist',
    service: 'packages/service-runtime/dist',
    notification: 'packages/notification-runtime/dist',
  })) {
    try {
      inventories[label] = publicInventory(join(program, path));
    } catch (error) {
      inventories[label] = { unavailable: true, errorType: errorCode(error) };
    }
  }
  const files = {};
  for (const name of [
    'scripts/windows-desktop-service.mjs',
    'scripts/windows-host-task.ps1',
    'scripts/windows-desktop-worker.mjs',
    'packages/codex-adapter/dist/desktop-platform.js',
  ]) {
    try {
      files[name] = fileHash(join(program, name));
    } catch (error) {
      files[name] = { unavailable: true, errorType: errorCode(error) };
    }
  }
  let launcherSource;
  try {
    const paths = ['host-launcher.cs', 'private-state.cs', 'private-state-compilation.cs'].map(
      (n) => join(program, 'packages/codex-adapter/windows', n),
    );
    const hashes = paths.map((p) => fileHash(p));
    const bytes = paths.map((p, i) => {
      const raw = readFileSync(p);
      if (sha(raw) !== hashes[i].sha256) fail('HOST_REGULAR_CODE_CHANGED');
      return raw;
    });
    launcherSource = {
      files: hashes,
      combinedSha256: sha(Buffer.concat(bytes)),
      algorithm:
        'host-launcher/private-state/private-state-compilation original bytes concatenated',
    };
  } catch (error) {
    launcherSource = { unavailable: true, errorType: errorCode(error) };
  }
  return { inventories, files, launcherSource, inventoryIsExecutionApproval: false };
}

export function regularRecoveryPreflight(argv = process.argv.slice(2)) {
  const values = parseRegularPreflight(argv);
  if (process.platform !== 'win32') fail('HOST_REGULAR_NATIVE_WINDOWS_REQUIRED');
  const manifestPath = join(values['legacy-bundle'], 'resources.json');
  const original = fileHash(manifestPath, 2097152);
  if (original.sha256 !== legacyManifest) fail('HOST_REGULAR_LEGACY_MANIFEST_REJECTED');
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  if (manifest.sourceCommit !== legacySource) fail('HOST_REGULAR_LEGACY_SOURCE_REJECTED');
  const request = {
    format: 'windows-host-regular-preflight-v1',
    hostId: values['host-id'],
    legacyOperationId: values['legacy-operation-id'],
    installedProgram: values['installed-program'],
    legacyManifestSha256: legacyManifest,
  };
  const environment = regularPowerShellEnvironment(process.env);
  const script = fileURLToPath(new URL('./windows-host-regular-preflight.ps1', import.meta.url));
  let raw;
  try {
    raw = execFileSync(
      join(
        process.env.SystemRoot ?? 'C:\\Windows',
        'System32/WindowsPowerShell/v1.0/powershell.exe',
      ),
      ['-NoProfile', '-NonInteractive', '-File', script],
      {
        input: JSON.stringify(request),
        env: environment,
        timeout: 45000,
        maxBuffer: 16384,
        encoding: null,
        windowsHide: true,
        stdio: ['pipe', 'pipe', 'pipe'],
      },
    );
  } catch (error) {
    return {
      status: 'preflight-failed',
      readOnly: true,
      systemMutated: false,
      mutationAuthorized: false,
      errorType: errorCode(error),
      stdoutBytes: error.stdout?.length ?? 0,
      stdoutSha256: sha(error.stdout ?? Buffer.alloc(0)),
      stderrBytes: error.stderr?.length ?? 0,
      stderrSha256: sha(error.stderr ?? Buffer.alloc(0)),
    };
  }
  const native = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(raw));
  if (
    native.format !== request.format ||
    native.hostId !== request.hostId ||
    native.legacyOperationId !== request.legacyOperationId ||
    native.readOnly !== true ||
    native.systemMutated !== false ||
    native.mutationAuthorized !== false
  )
    fail('HOST_REGULAR_NATIVE_RESULT_REJECTED');
  return {
    ...native,
    node: { version: process.version, executableFile: fileHash(process.execPath, 268435456) },
    publicProgram: observePublicProgram(values['installed-program']),
  };
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const result = regularRecoveryPreflight();
    const output = JSON.stringify(result, null, 2) + '\n';
    if (Buffer.byteLength(output) > 16384) fail('HOST_REGULAR_OUTPUT_BUDGET_REJECTED');
    process.stdout.write(output);
    if (result.status !== 'observed') process.exitCode = 1;
  } catch (error) {
    process.stdout.write(
      JSON.stringify({
        status: 'preflight-failed',
        readOnly: true,
        systemMutated: false,
        mutationAuthorized: false,
        code: errorCode(error),
      }) + '\n',
    );
    process.exitCode = 1;
  }
}
