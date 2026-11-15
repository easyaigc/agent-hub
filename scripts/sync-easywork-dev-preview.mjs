// Distribute the canonical, built preview engine to a local EasyWork checkout.
import { readFile, writeFile, mkdir, copyFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';

const index = process.argv.indexOf('--target');
if (index < 0 || !process.argv[index + 1]) throw Error('Use --target <EasyWork checkout>');
const target = resolve(process.argv[index + 1]);
const protocol = join(target, 'packages/codex-notification-protocol');
if (JSON.parse(await readFile(join(protocol, 'package.json'))).name !== '@codex-manage/protocol')
  throw Error('Expected EasyWork protocol artifact package');
const destination = join(target, 'packages/agent-hub-dev-preview');
if (
  JSON.parse(await readFile(join(destination, 'package.json'))).name !== '@codex-manage/dev-preview'
)
  throw Error('Expected EasyWork preview artifact package');
const files = [
  ['packages/dev-preview/dist/index.js', 'packages/agent-hub-dev-preview/runtime/index.js'],
  ['packages/dev-preview/dist/index.js.map', 'packages/agent-hub-dev-preview/runtime/index.js.map'],
  [
    'packages/dev-preview/dist/index.d.ts.map',
    'packages/agent-hub-dev-preview/runtime/index.d.ts.map',
  ],
  ['packages/dev-preview/dist/index.d.ts', 'packages/agent-hub-dev-preview/runtime/index.d.ts'],
  [
    'packages/protocol/dist/dev-previews.js',
    'packages/codex-notification-protocol/runtime/dev-previews.js',
  ],
  [
    'packages/protocol/dist/dev-previews.js.map',
    'packages/codex-notification-protocol/runtime/dev-previews.js.map',
  ],
  [
    'packages/protocol/dist/dev-previews.d.ts.map',
    'packages/codex-notification-protocol/runtime/dev-previews.d.ts.map',
  ],
  [
    'packages/protocol/dist/dev-previews.d.ts',
    'packages/codex-notification-protocol/runtime/dev-previews.d.ts',
  ],
];
const fingerprints = {};
for (const [source, relative] of files) {
  await mkdir(resolve(target, relative, '..'), { recursive: true });
  await copyFile(source, join(target, relative));
  fingerprints[relative] = createHash('sha256')
    .update(await readFile(source))
    .digest('hex');
}
for (const name of ['index.js', 'index.d.ts']) {
  const path = join(protocol, 'runtime', name);
  const text = await readFile(path, 'utf8');
  if (!text.includes("export * from './dev-previews.js'"))
    await writeFile(path, text + "\nexport * from './dev-previews.js';\n");
}
await writeFile(
  join(destination, 'source-manifest.json'),
  JSON.stringify(
    {
      format: 1,
      component: '@codex-manage/dev-preview',
      repository: 'BCAI/agent-hub-app',
      baseCommit: execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim(),
      sourceSha256: createHash('sha256')
        .update(await readFile('packages/dev-preview/src/index.ts'))
        .digest('hex'),
      note: 'Base commit is not a claim that uncommitted core changes are integrated; exact input and output bytes are pinned.',
      files: fingerprints,
    },
    null,
    2,
  ) + '\n',
);
console.log(
  'Synchronized eight built preview artifact files; no credentials or service configuration copied.',
);
