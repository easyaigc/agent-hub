import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtemp, open, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

if (process.platform !== 'darwin') {
  console.log('EasyWork file transfer native checks skipped: macOS Swift required.');
  process.exit(0);
}
const source = async (name) => readFile(`apps/ios/Sources/${name}.swift`, 'utf8');
const [u, e, f, r, t, n, s] = await Promise.all(
  [
    'Uploads',
    'EasyWorkAgentClient',
    'FileTransfers',
    'EasyWorkReading',
    'EasyWorkTransport',
    'NotificationModels',
    'EasyWorkSession',
  ].map(source),
);
function section(text, start, end) {
  const i = text.indexOf(start);
  const j = text.indexOf(end, i + start.length);
  if (i < 0 || j < 0 || text.indexOf(start, i + start.length) >= 0)
    throw new Error(`Cannot uniquely extract ${start}`);
  return text.slice(i, j);
}
const account = n.match(/^struct AgentAccount:.*$/m)?.[0];
if (!account) throw new Error('Cannot extract AgentAccount');
const parts = {
  fileHash: section(
    u,
    '  nonisolated static func fileHash(',
    '  nonisolated static func identifier(',
  ),
  declarations: section(u, 'struct UploadManifest:', 'enum UploadRequestContext {'),
  fileDeclarations: section(f, 'struct ComputerFileManifest:', 'struct ComputerFileCatalog:'),
  urlMethod: section(
    u,
    '  func uploadTransferFile(_ client: Client, file: ComputerFileManifest, source: URL) async throws -> ComputerFileReceipt {\n    guard file.hostId == client.id',
    '  func uploadRequest(_ client: Client, manifest m:',
  ),
  defaultMethod: section(
    u,
    '  func uploadTransferFile(_ client: Client, file: ComputerFileManifest, source: URL) async throws -> ComputerFileReceipt {\n    try await uploadFileTransfer',
    '  func uploadRequest(_ client: Client, manifest: UploadManifest',
  ),
  account,
  target: section(t, 'struct EasyWorkTarget:', 'struct EasyWorkBinding:'),
  integer: section(r, '  static func integer(', '  static func position('),
  limits: section(e, 'struct EasyWorkFileLimits {', '\nenum EasyWorkAttachmentUpload {'),
  validation: section(u, 'enum EasyWorkUploadValidation {', '\nextension EasyWorkAgentClient {'),
  prepare: section(
    e,
    '  func prepareFileTransfer(_ client: Client, file: ComputerFileManifest)',
    '  func uploadFileTransfer(_ client: Client, file: ComputerFileManifest',
  ),
  verified: section(
    s,
    '  nonisolated static func verifiedWriteResult(',
    '  private func verifySessionLiveness(',
  ).replace('nonisolated static', 'static'),
  independentFileLimits: section(
    e,
    '  func independentFileLimits(_ channel: EasyWorkRemoteSession)',
    '  private func independentFile(',
  ),
  identity: section(u, 'enum EasyWorkCapturedUploadIdentity {', '/// Preserve EasyWork'),
  request: section(u, '  func uploadRequest(_ client: Client, manifest m:', '\n}\n\n#if DEBUG'),
};
const temporary = await mkdtemp(join(tmpdir(), 'easywork-file-transfer-'));
try {
  const env = { ...process.env, CLANG_MODULE_CACHE_PATH: join(temporary, 'module-cache') };
  // ru_maxrss is bytes on Darwin (getrusage(2)); each measurement is a fresh process.
  // Sparse all-zero inputs are synthesized locally and never represent user data.
  const fixture = await readFile(
    'scripts/mobile/fixtures/easywork-file-transfer/hash.swift.txt',
    'utf8',
  );
  const hashSource = join(temporary, 'hash.swift');
  const hashBinary = join(temporary, 'hash-check');
  await writeFile(hashSource, fixture.replace('@@fileHash@@', parts.fileHash));
  execFileSync('xcrun', ['swiftc', '-Onone', '-parse-as-library', hashSource, '-o', hashBinary], {
    env,
    stdio: 'inherit',
  });
  const peaks = [];
  for (const size of [0, 21 * 1024 * 1024, 64 * 1024 * 1024]) {
    const file = join(temporary, `input-${size}`);
    const handle = await open(file, 'w');
    try {
      await handle.truncate(size);
    } finally {
      await handle.close();
    }
    const hash = createHash('sha256');
    const block = Buffer.alloc(256 * 1024);
    for (let left = size; left > 0; left -= block.length)
      hash.update(block.subarray(0, Math.min(left, block.length)));
    const output = execFileSync(hashBinary, [file], { env, encoding: 'utf8' }).trim().split(' ');
    if (
      output.length !== 3 ||
      Number(output[0]) !== size ||
      output[1] !== hash.digest('hex') ||
      !/^\d+$/.test(output[2]) ||
      Number(output[2]) <= 0
    )
      throw new Error(`fileHash incorrect for ${size}`);
    console.log(`fileHash count=${output[0]} sha256=${output[1]} maxrssBytes=${output[2]}`);
    if (size) peaks.push(Number(output[2]));
  }
  execFileSync(hashBinary, ['--missing', join(temporary, 'missing')], { env, stdio: 'inherit' });
  if (peaks[1] - peaks[0] > 16 * 1024 * 1024)
    throw new Error(`fileHash peak grew by ${peaks[1] - peaks[0]} bytes`);
  console.log(
    `PASS fileHash empty, 21/64 MiB digests and peak growth ${peaks[1] - peaks[0]} bytes`,
  );
  for (const name of [
    'limits',
    'context',
    'url-dispatch',
    'prepare',
    'request-prepare-green',
    'request-boundaries',
  ]) {
    const fixture = await readFile(
      `scripts/mobile/fixtures/easywork-file-transfer/${name}.swift.txt`,
      'utf8',
    );
    const harness = fixture.replace(/@@(\w+)@@/g, (_, key) => {
      if (!(key in parts)) throw new Error(`Unknown extraction ${key}`);
      return parts[key];
    });
    const path = join(temporary, `${name}.swift`);
    const binary = join(temporary, name);
    await writeFile(path, harness);
    execFileSync('xcrun', ['swiftc', '-parse-as-library', path, '-o', binary], {
      env,
      stdio: 'inherit',
    });
    execFileSync(binary, [join(temporary, 'sample')], { env, stdio: 'inherit' });
  }
} finally {
  await rm(temporary, { recursive: true, force: true });
}
