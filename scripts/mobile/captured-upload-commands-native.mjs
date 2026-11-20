import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const repo = fileURLToPath(new URL('../../', import.meta.url));
if (process.platform !== 'darwin') {
  console.log('Captured upload commands native regression requires macOS Swift; skipped.');
  process.exit(0);
}
function declaration(source, marker) {
  const start = source.indexOf(marker);
  if (start < 0) throw new Error(`Missing ${marker}`);
  let depth = 0,
    opened = false;
  for (let i = start; i < source.length; i++) {
    if (source[i] === '{') {
      depth++;
      opened = true;
    }
    if (source[i] === '}' && --depth === 0 && opened) return source.slice(start, i + 1);
  }
  throw new Error(`Unclosed ${marker}`);
}
const paths = [
  'apps/ios/Sources/Uploads.swift',
  'apps/ios/Sources/Models.swift',
  'apps/ios/Sources/HostService.swift',
  'apps/ios/Sources/EasyWorkAgentClient.swift',
  'apps/ios/Sources/EasyWorkReading.swift',
  'scripts/mobile/captured-upload-commands-native.swift',
];
const [uploads, models, host, easy, reading, fixture] = await Promise.all(
  paths.map((p) => readFile(join(repo, p), 'utf8')),
);
const easyExtension = uploads.slice(uploads.indexOf('extension EasyWorkAgentClient {'));
const code = [
  declaration(models, 'struct DesktopCreationFile:'),
  ...[
    'struct UploadManifest:',
    'struct UploadResult:',
    'struct UploadReceipt:',
    'enum UploadAction:',
    'enum UploadRequestContext {',
    'struct UploadBatch:',
    'struct UploadJob:',
    'enum EasyWorkCapturedUploadIdentity {',
    'enum EasyWorkUploadValidation {',
  ].map((m) => declaration(uploads, m)),
  `enum HostService { ${declaration(host, 'static func hash(')} }`,
  `enum EasyWorkAttachmentUpload { ${declaration(easy, 'static func displayName(')} }`,
  `enum EasyWorkReading { ${declaration(reading, 'static func integer(')} }`,
  `extension EasyWorkAgentClient { ${declaration(easyExtension, 'func uploadRequest(_ client: Client, manifest m:')} }`,
].join('\n');
const root = resolve(
  process.env.CAPTURED_UPLOAD_CHECK_OUTPUT ?? join(repo, '.local/captured-upload-commands'),
);
await mkdir(root, { recursive: true, mode: 0o700 });
const dir = await mkdtemp(join(root, 'run-'));
const swift = join(dir, 'boundary.swift'),
  binary = join(dir, 'boundary');
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
} catch (e) {
  detail = `${e.message}\n${e.stderr?.toString() ?? ''}`;
  console.error(detail);
  process.exitCode = 1;
} finally {
  await writeFile(
    join(dir, 'summary.json'),
    JSON.stringify(
      {
        status,
        detail,
        inputs: Object.fromEntries(
          paths.map((p, i) => [
            p,
            createHash('sha256')
              .update([uploads, models, host, easy, reading, fixture][i])
              .digest('hex'),
          ]),
        ),
        scope: 'production declarations and request; fake channel; no network or GUI',
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
