import { parseArgs } from 'node:util';
import { open, readFile, mkdir, lstat, realpath, rename, rm } from 'node:fs/promises';
import { homedir } from 'node:os';
import { basename, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash, randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import https from 'node:https';

const MAX_BYTES = 20 * 1024 * 1024;
const uuid = /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/;
const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
const requireValue = (value, code) => {
  if (!value) throw Object.assign(new Error(code), { code });
};
// PS5 uses the current Windows user, never a WSL home or caller-supplied SID.
const windowsACL = (path, protect, directory) => {
  if (process.platform !== 'win32') return;
  const script = `
$ErrorActionPreference='Stop'
$p=$env:AGENT_HUB_PRIVATE_PATH
$sid=[System.Security.Principal.WindowsIdentity]::GetCurrent().User
$dir=$env:AGENT_HUB_PRIVATE_DIRECTORY -eq '1'
if ($env:AGENT_HUB_PRIVATE_PROTECT -eq '1') {
  if ($dir) { $acl=New-Object System.Security.AccessControl.DirectorySecurity }
  else { $acl=New-Object System.Security.AccessControl.FileSecurity }
  $acl.SetOwner($sid)
  $acl.SetAccessRuleProtection($true,$false)
  if ($dir) { $rule=New-Object System.Security.AccessControl.FileSystemAccessRule($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow') }
  else { $rule=New-Object System.Security.AccessControl.FileSystemAccessRule($sid,'FullControl','Allow') }
  $acl.AddAccessRule($rule)
  if ($dir) { [System.IO.Directory]::SetAccessControl($p,$acl) }
  else { [System.IO.File]::SetAccessControl($p,$acl) }
}
if ($dir) { $acl=[System.IO.Directory]::GetAccessControl($p) }
else { $acl=[System.IO.File]::GetAccessControl($p) }
if ($acl.GetOwner([System.Security.Principal.SecurityIdentifier]).Value -ne $sid.Value) { throw 'owner' }
$allowed=@($sid.Value,'S-1-5-18','S-1-5-32-544')
foreach ($r in $acl.GetAccessRules($true,$true,[System.Security.Principal.SecurityIdentifier])) {
  if ($r.AccessControlType -eq 'Allow' -and $allowed -notcontains $r.IdentityReference.Value) { throw 'public ACL' }
}
`;
  try {
    execFileSync(
      join(
        process.env.SystemRoot ?? 'C:\\Windows',
        'System32/WindowsPowerShell/v1.0/powershell.exe',
      ),
      ['-NoProfile', '-NonInteractive', '-Command', script],
      {
        stdio: 'pipe',
        timeout: 15000,
        windowsHide: true,
        env: {
          ...Object.fromEntries(
            Object.entries(process.env).filter(([key]) => key.toUpperCase() !== 'PSMODULEPATH'),
          ),
          AGENT_HUB_PRIVATE_PATH: path,
          AGENT_HUB_PRIVATE_PROTECT: protect ? '1' : '0',
          AGENT_HUB_PRIVATE_DIRECTORY: directory ? '1' : '0',
        },
      },
    );
  } catch {
    requireValue(false, 'UNSAFE_WINDOWS_PRIVATE_STATE');
  }
};
async function privatePath(path, directory = false) {
  const stat = await lstat(path);
  requireValue(
    !stat.isSymbolicLink() && (directory ? stat.isDirectory() : stat.isFile()),
    'UNSAFE_PRIVATE_PATH',
  );
  if (process.platform === 'win32') windowsACL(path, false, directory);
  else
    requireValue(
      stat.uid === process.getuid() && !(stat.mode & 0o077),
      'UNSAFE_PRIVATE_PERMISSIONS',
    );
  const actual = await realpath(path);
  requireValue(
    process.platform === 'win32'
      ? actual.toLowerCase() === resolve(path).toLowerCase()
      : actual === resolve(path),
    'UNSAFE_PRIVATE_PATH',
  );
}
async function writePrivate(path, bytes, exclusive = false) {
  const temporary = exclusive ? path : path + '.' + randomUUID() + '.tmp';
  const file = await open(temporary, 'wx', 0o600);
  try {
    await file.writeFile(bytes);
    await file.sync();
  } finally {
    await file.close();
  }
  windowsACL(temporary, true, false);
  if (!exclusive) await rename(temporary, path);
  if (process.platform !== 'win32') {
    const directory = await open(resolve(path, '..'), 'r');
    try {
      await directory.sync();
    } finally {
      await directory.close();
    }
  }
}
async function sourceBytes(path) {
  requireValue(
    (await lstat(path)).isFile() && !(await lstat(path)).isSymbolicLink(),
    'REGULAR_SOURCE_FILE_REQUIRED',
  );
  const file = await open(path, 'r');
  try {
    const stat = await file.stat();
    requireValue(
      stat.isFile() && stat.size > 0 && stat.size <= MAX_BYTES,
      'FILE_SIZE_OUT_OF_RANGE',
    );
    const parts = [];
    let size = 0;
    for await (const part of file.createReadStream({ autoClose: false })) {
      size += part.length;
      requireValue(size <= MAX_BYTES, 'FILE_SIZE_OUT_OF_RANGE');
      parts.push(part);
    }
    requireValue(size > 0 && size === stat.size, 'SOURCE_CHANGED_DURING_READ');
    return Buffer.concat(parts);
  } finally {
    await file.close();
  }
}
function validateManifest(file) {
  requireValue(
    file &&
      typeof file === 'object' &&
      typeof file.hostId === 'string' &&
      uuid.test(file.id) &&
      typeof file.name === 'string' &&
      file.name.trim() &&
      Buffer.byteLength(file.name) <= 180 &&
      ![...file.name].some((char) => char.charCodeAt(0) < 32 || char.charCodeAt(0) === 127) &&
      !/[/\\:<>"|?*]/.test(file.name) &&
      !/[. ]$/.test(file.name) &&
      !/^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\.|$)/i.test(file.name) &&
      typeof file.mediaType === 'string' &&
      /^[\w.+-]+\/[\w.+-]+$/.test(file.mediaType) &&
      Number.isSafeInteger(file.sizeBytes) &&
      file.sizeBytes > 0 &&
      file.sizeBytes <= MAX_BYTES &&
      typeof file.sha256 === 'string' &&
      /^[a-f0-9]{64}$/.test(file.sha256),
    'INVALID_FILE_MANIFEST',
  );
}

export function createTransferTransport(host, options = {}) {
  const endpoint = new URL(host.endpoint);
  requireValue(
    endpoint.protocol === 'https:' &&
      !endpoint.username &&
      !endpoint.password &&
      !endpoint.search &&
      !endpoint.hash &&
      endpoint.pathname === '/',
    'HTTPS_ROOT_REQUIRED',
  );
  return (path, body, authenticated = true) =>
    new Promise((resolve, reject) => {
      const binary = Buffer.isBuffer(body);
      const bytes =
        body === undefined ? undefined : binary ? body : Buffer.from(JSON.stringify(body));
      const request = https.request(
        new URL(path, endpoint),
        {
          ...(options.ca ? { ca: options.ca } : {}),
          method: bytes === undefined ? 'GET' : 'POST',
          headers: {
            ...(authenticated ? { Authorization: 'Bearer ' + host.clientKey } : {}),
            ...(bytes === undefined
              ? {}
              : {
                  'Content-Type': binary ? 'application/octet-stream' : 'application/json',
                  'Content-Length': bytes.length,
                }),
          },
        },
        (response) => {
          const parts = [];
          let size = 0;
          response.on('data', (part) => {
            size += part.length;
            if (size > 4 * 1024 * 1024) request.destroy(new Error('RESPONSE_TOO_LARGE'));
            else parts.push(part);
          });
          response.on('end', () => {
            clearTimeout(timer);
            let value = null;
            try {
              value = JSON.parse(Buffer.concat(parts).toString('utf8'));
            } catch {
              /* Refuse non-JSON receipts. */
            }
            resolve({ http: response.statusCode, value });
          });
          response.on('aborted', () => request.destroy(new Error('RESPONSE_ABORTED')));
          response.on('error', () => request.destroy(new Error('RESPONSE_FAILED')));
        },
      );
      const timer = setTimeout(() => request.destroy(new Error('TIMEOUT')), 55000);
      request.on('error', () => {
        clearTimeout(timer);
        reject(
          Object.assign(new Error('NETWORK_OUTCOME_UNKNOWN'), { code: 'NETWORK_OUTCOME_UNKNOWN' }),
        );
      });
      // Default TLS validation stays enabled; redirects are never followed.
      request.end(bytes);
    });
}

export async function main(args = process.argv.slice(2), options = {}) {
  const { values, positionals } = parseArgs({
    args,
    allowPositionals: true,
    strict: true,
    options: {
      'host-id': { type: 'string' },
      'operation-id': { type: 'string' },
      file: { type: 'string' },
      'media-type': { type: 'string' },
      help: { type: 'boolean' },
    },
  });
  if (values.help) {
    (options.output ?? console.log)(
      'send|status|resume --host-id ID --operation-id UUID [--file PATH] [--media-type TYPE]. Repeated send only queries; resume explicitly continues the same saved bytes.',
    );
    return 0;
  }
  const command = positionals[0],
    id = values['operation-id'];
  requireValue(
    positionals.length === 1 && ['send', 'status', 'resume'].includes(command),
    'UNKNOWN_NAMED_OPERATION',
  );
  requireValue(values['host-id'] && uuid.test(id), 'EXPLICIT_HOST_AND_TRANSFER_UUID_REQUIRED');
  requireValue(
    command === 'send' || (!values.file && !values['media-type']),
    'SOURCE_ONLY_ALLOWED_FOR_SEND',
  );
  const root = options.directory ?? join(homedir(), '.config/agent-hub');
  await privatePath(root, true);
  const registryPath = join(root, 'hosts.json');
  await privatePath(registryPath);
  const registry = JSON.parse(await readFile(registryPath, 'utf8'));
  requireValue(
    registry.schemaVersion === 1 && Array.isArray(registry.hosts),
    'INCOMPATIBLE_REGISTRY',
  );
  const matches = registry.hosts.filter((host) => host.hostId === values['host-id']);
  requireValue(matches.length === 1, 'HOST_ID_NOT_UNIQUE_OR_UNKNOWN');
  const host = matches[0];
  requireValue(
    typeof host.clientKey === 'string' && host.clientKey.length >= 32 && !/\s/.test(host.clientKey),
    'INVALID_CLIENT_KEY',
  );
  // Validate even with an injected transport. A registry cannot introduce HTTP or redirects.
  const transport = createTransferTransport(host, options);
  const request = options.transport ?? transport;
  const parent = join(root, 'file-transfers'),
    directory = join(parent, id);
  const intentPath = join(directory, 'intent.json'),
    contentPath = join(directory, 'content.bin');
  let saved,
    createdHere = false;
  try {
    await privatePath(parent, true);
    await privatePath(directory, true);
    await privatePath(intentPath);
    saved = JSON.parse(await readFile(intentPath, 'utf8'));
    validateManifest(saved.manifest);
    requireValue(
      saved.version === 1 && saved.manifest.id === id && saved.manifest.hostId === host.hostId,
      'LOCAL_INTENT_TARGET_MISMATCH',
    );
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  if (!saved) requireValue(command === 'send', 'ORIGINAL_LOCAL_INTENT_REQUIRED');
  if (saved && command === 'send' && values.file) {
    const bytes = await sourceBytes(values.file);
    requireValue(
      hash(bytes) === saved.manifest.sha256 &&
        bytes.length === saved.manifest.sizeBytes &&
        basename(values.file) === saved.manifest.name &&
        (!values['media-type'] || values['media-type'] === saved.manifest.mediaType),
      'TRANSFER_INTENT_IMMUTABLE',
    );
  }
  const health = await request('/healthz');
  requireValue(
    health.http === 200 &&
      health.value?.service === 'host-agent' &&
      health.value?.protocolVersion === '0.1',
    'INCOMPATIBLE_HEALTH',
  );
  const unauthenticated = await request('/healthz', undefined, false);
  requireValue(unauthenticated.http === 401, 'AUTHENTICATION_NOT_ENFORCED');
  const workspace = await request('/v1/workspace');
  const target = workspace.value?.readTarget;
  const expected = {
    agentId: host.agentId ?? 'codex',
    hostId: host.hostId,
    workspaceId: host.workspaceId ?? host.hostId,
    runtimeId: host.runtimeId ?? 'desktop-runtime',
  };
  requireValue(
    workspace.http === 200 &&
      workspace.value?.hostId === host.hostId &&
      workspace.value?.connectionMode === expected.runtimeId &&
      Object.entries(expected).every(([key, value]) => target?.[key] === value),
    'HOST_RUNTIME_IDENTITY_MISMATCH',
  );
  requireValue(workspace.value.capabilities?.includes('files:upload'), 'FILES_UPLOAD_UNAVAILABLE');
  if (saved)
    requireValue(
      Object.entries(expected).every(([key, value]) => saved.target?.[key] === value),
      'LOCAL_INTENT_TARGET_MISMATCH',
    );
  else {
    requireValue(values.file, 'SOURCE_FILE_REQUIRED');
    const bytes = await sourceBytes(values.file);
    const manifest = {
      hostId: host.hostId,
      id,
      name: basename(values.file),
      mediaType: values['media-type'] ?? 'application/octet-stream',
      sizeBytes: bytes.length,
      sha256: hash(bytes),
    };
    validateManifest(manifest);
    try {
      await mkdir(parent, { mode: 0o700 });
      windowsACL(parent, true, true);
    } catch (error) {
      if (error.code !== 'EEXIST') throw error;
    }
    await privatePath(parent, true);
    // Exclusive directory creation elects one initial sender. An incomplete local
    // preparation is never converted into another remote operation automatically.
    await mkdir(directory, { mode: 0o700 });
    windowsACL(directory, true, true);
    await privatePath(directory, true);
    saved = { version: 1, target: expected, manifest, createdAt: new Date().toISOString() };
    await writePrivate(contentPath, bytes, true);
    await writePrivate(intentPath, JSON.stringify(saved, null, 2) + '\n', true);
    createdHere = true;
  }
  const receiptPath = join(directory, 'receipt.json');
  const emit = async (status, response, receipt) => {
    const result = {
      operation: command,
      hostId: host.hostId,
      operationId: id,
      status,
      http: response?.http ?? null,
      ...(receipt ? { receipt } : {}),
      checkedAt: new Date().toISOString(),
      ...(status === 'outcome-unknown' ? { nextAction: 'status', retryAllowed: false } : {}),
    };
    await writePrivate(receiptPath, JSON.stringify(result, null, 2) + '\n');
    if (receipt?.phase === 'completed') await rm(contentPath, { force: true });
    (options.output ?? console.log)(JSON.stringify(result));
    return receipt?.phase === 'completed' ? 0 : 2;
  };
  const checkedReceipt = (response) => {
    requireValue(response.http === 200, 'TRANSFER_REQUEST_REJECTED');
    const receipt = response.value;
    requireValue(
      receipt &&
        Object.entries(saved.manifest).every(([key, value]) => receipt[key] === value) &&
        ['prepared', 'completed'].includes(receipt.phase) &&
        typeof receipt.destinationPath === 'string' &&
        receipt.destinationPath.length > 0 &&
        typeof receipt.createdAt === 'string',
      'TRANSFER_RECEIPT_MISMATCH',
    );
    return receipt;
  };
  const query = '/v1/file-transfers?' + new URLSearchParams({ hostId: host.hostId, id });
  try {
    // Always query first. A fresh ID gets a 404; a repeated send never uploads.
    let response = await request(query);
    let receipt;
    if (response.http === 200) {
      receipt = checkedReceipt(response);
      if (receipt.phase === 'completed' || command !== 'resume')
        return emit(receipt.phase, response, receipt);
    } else if (response.http !== 404) return emit('outcome-unknown', response);
    if (command === 'status') return emit('not-found', response);
    // Existing sends remain read-only, including after a lost prepare response.
    if (command === 'send' && !createdHere)
      return emit(response.http === 404 ? 'not-found' : 'prepared', response, receipt);
    await privatePath(contentPath);
    const bytes = await sourceBytes(contentPath);
    requireValue(
      bytes.length === saved.manifest.sizeBytes && hash(bytes) === saved.manifest.sha256,
      'SAVED_CONTENT_CHANGED',
    );
    if (!receipt) {
      response = await request('/v1/file-transfers', saved.manifest);
      if (response.http !== 200) return emit('outcome-unknown', response);
      receipt = checkedReceipt(response);
      if (receipt.phase === 'completed') return emit('completed', response, receipt);
    }
    response = await request(
      '/v1/file-transfers/content?' + new URLSearchParams({ hostId: host.hostId, id }),
      bytes,
    );
    if (response.http !== 200) return emit('outcome-unknown', response);
    receipt = checkedReceipt(response);
    return emit(receipt.phase, response, receipt);
  } catch (error) {
    if (error.code && error.code !== 'NETWORK_OUTCOME_UNKNOWN') throw error;
    return emit('outcome-unknown');
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url))
  main()
    .then((code) => {
      process.exitCode = code;
    })
    .catch((error) => {
      console.error(
        JSON.stringify({
          status: 'failed',
          error: typeof error.code === 'string' ? error.code : 'LOCAL_OPERATION_FAILED',
        }),
      );
      process.exitCode = 1;
    });
