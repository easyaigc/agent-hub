import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtemp, mkdir, readFile, writeFile, rm } from 'node:fs/promises';
import { join, resolve } from 'node:path';

if (process.platform !== 'darwin') {
  console.log('EasyWork document-file native regression requires macOS Swift.');
  process.exit(0);
}

const root = resolve(
  process.env.EASYWORK_DOCUMENT_FILE_CHECK_OUTPUT ?? '.local/easywork-document-file-boundary',
);
await mkdir(root, { recursive: true, mode: 0o700 });
const output = await mkdtemp(join(root, 'run-'));
const sourcePath = 'apps/ios/Sources/EasyWorkDocumentFileResolution.swift';
const fixturePath = 'scripts/mobile/easywork-document-file-native.swift';
const source = await readFile(sourcePath, 'utf8');
const start = source.indexOf('enum EasyWorkDocumentFileResolution {');
const end = source.length;
if (start < 0 || end <= start) throw new Error('Production document-file boundary not found');
const fixture = await readFile(fixturePath, 'utf8');
const modelsPath = 'apps/ios/Sources/Models.swift';
const models = await readFile(modelsPath, 'utf8');
const modelsStart = models.indexOf('struct DocumentMessageSource:');
const modelsEnd = models.indexOf('struct PlanStep:', modelsStart);
if (modelsStart < 0 || modelsEnd <= modelsStart)
  throw new Error('Production document reading models not found');
const combined = join(output, 'document-file-boundary.swift');
const executable = join(output, 'document-file-boundary');
await writeFile(
  combined,
  fixture.replace(
    '@main struct',
    source.slice(start, end) + models.slice(modelsStart, modelsEnd) + '\n@main struct',
  ),
  { mode: 0o600 },
);
execFileSync('xcrun', ['swiftc', '-parse-as-library', combined, '-o', executable], {
  stdio: 'inherit',
});
const result = execFileSync(executable, { encoding: 'utf8' });
await writeFile(
  join(output, 'summary.json'),
  JSON.stringify(
    {
      status: 'passed-auxiliary-foundation',
      sourceSha256: createHash('sha256').update(source).digest('hex'),
      modelsSha256: createHash('sha256').update(models).digest('hex'),
      fixtureSha256: createHash('sha256').update(fixture).digest('hex'),
      transport: 'test-double',
      physicalIPhone: false,
      newModels: 0,
      result: result.trim(),
    },
    null,
    2,
  ),
  { mode: 0o600 },
);
await rm(combined);
await rm(executable);
process.stdout.write(result);
