import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtemp, mkdir, readFile, writeFile, rm } from 'node:fs/promises';
import { join, resolve } from 'node:path';

if (process.platform !== 'darwin') {
  console.log('EasyWork message-file native regression requires macOS Swift.');
  process.exit(0);
}

const root = resolve(
  process.env.EASYWORK_MESSAGE_FILE_CHECK_OUTPUT ?? '.local/easywork-message-file-boundary',
);
await mkdir(root, { recursive: true, mode: 0o700 });
const output = await mkdtemp(join(root, 'run-'));
const sourcePath = 'apps/ios/Sources/EasyWorkAgentClient.swift';
const fixturePath = 'scripts/mobile/easywork-message-file-native.swift';
const source = await readFile(sourcePath, 'utf8');
const start = source.indexOf('enum EasyWorkMessageFileResolution {');
const end = source.indexOf('enum EasyWorkHistoricalAttachment {', start);
if (start < 0 || end <= start) throw new Error('Production message-file boundary not found');
const fixture = await readFile(fixturePath, 'utf8');
const combined = join(output, 'message-file-boundary.swift');
const executable = join(output, 'message-file-boundary');
await writeFile(
  combined,
  fixture.replace('@main struct', source.slice(start, end) + '\n@main struct'),
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
