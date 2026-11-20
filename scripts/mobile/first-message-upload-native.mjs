import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repo = fileURLToPath(new URL('../../', import.meta.url));
if (process.platform !== 'darwin') {
  console.log('First-message upload native regression requires macOS Swift; skipped.');
  process.exit(0);
}
function declaration(source, marker) {
  const start = source.indexOf(marker);
  if (start < 0) throw new Error(`Missing production declaration: ${marker}`);
  let depth = 0,
    opened = false;
  for (let i = start; i < source.length; i++) {
    if (source[i] === '{') {
      depth++;
      opened = true;
    }
    if (source[i] === '}' && --depth === 0 && opened) return source.slice(start, i + 1);
  }
  throw new Error(`Unclosed production declaration: ${marker}`);
}
const [uploads, host, fixture] = await Promise.all(
  [
    'apps/ios/Sources/Uploads.swift',
    'apps/ios/Sources/HostService.swift',
    'scripts/mobile/first-message-upload-native.swift',
  ].map((p) => readFile(join(repo, p), 'utf8')),
);
const root = resolve(
  process.env.FIRST_MESSAGE_UPLOAD_CHECK_OUTPUT ??
    join(repo, '.local/first-message-upload-boundary'),
);
await mkdir(root, { recursive: true, mode: 0o700 });
const directory = await mkdtemp(join(root, 'run-'));
const swift = join(directory, 'boundary.swift'),
  binary = join(directory, 'boundary');
const code = `enum HostService { ${declaration(host, 'static func hash(')} }
final class UploadCenter {
 static let shared = UploadCenter()
 var calls = 0
 var manifests: [UploadManifest] = []
 var captures: [DesktopCreationFile] = []
 var result = RemoteAttachment(hostId: "", threadId: "", name: "", mediaType: "", sha256: "", sizeBytes: 0)
 func transfer(_ client: Client, manifest: UploadManifest, source: URL, capturedFile: DesktopCreationFile) async throws -> Receipt { calls += 1; manifests.append(manifest); captures.append(capturedFile); return Receipt(result: result) }
 ${declaration(uploads, 'nonisolated static func identifier(')}
 ${declaration(uploads, 'nonisolated static func fileHash(')}
}
${declaration(uploads, 'private func uploadManifest(')}
extension AgentClient { ${declaration(uploads, 'func uploadFile(_ client: Client, threadId: String, file: LocalAttachment, source: URL, manifest expected:')} }`;
await writeFile(swift, fixture.replace('// PRODUCTION_DECLARATIONS', code), { mode: 0o600 });
let status = 'failed',
  detail = '';
try {
  execFileSync('xcrun', ['swiftc', '-parse-as-library', swift, '-o', binary], {
    cwd: repo,
    stdio: 'pipe',
    timeout: 120000,
  });
  detail = execFileSync(binary, { cwd: repo, encoding: 'utf8', timeout: 30000 }).trim();
  status = 'passed-auxiliary';
  console.log(detail);
} catch (error) {
  detail = `${error.message}\n${error.stderr?.toString() ?? ''}`;
  console.error(detail);
  process.exitCode = 1;
} finally {
  await writeFile(
    join(directory, 'summary.json'),
    JSON.stringify(
      {
        status,
        detail,
        sourceSha256: createHash('sha256').update(uploads).digest('hex'),
        hostSha256: createHash('sha256').update(host).digest('hex'),
        fixtureSha256: createHash('sha256').update(fixture).digest('hex'),
        scope: 'auxiliary; real hash and manifest, controlled transfer; no network or GUI',
      },
      null,
      2,
    ),
    { mode: 0o600 },
  );
  await rm(binary, { force: true });
  if (status === 'passed-auxiliary') await rm(swift, { force: true });
  else console.error(`Exact Swift input retained: ${swift}`);
}
