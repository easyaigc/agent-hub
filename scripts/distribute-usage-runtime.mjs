import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
const destination = process.argv[2];
if (!destination) throw new Error('Specify EasyWork checkout');
const files = {};
async function copy(source, target) {
  const bytes = Buffer.from(
    (await readFile(source, 'utf8')).replace(/\n?\/\/# sourceMappingURL=[^\n]+\n?$/, '\n'),
  );
  await mkdir(resolve(target, '..'), { recursive: true });
  await writeFile(target, bytes);
  files[target.slice(resolve(destination).length + 1)] = createHash('sha256')
    .update(bytes)
    .digest('hex');
}
for (const suffix of ['js', 'd.ts']) {
  await copy(
    `packages/protocol/dist/usage.${suffix}`,
    join(destination, `packages/codex-notification-protocol/runtime/usage.${suffix}`),
  );
  const index = join(destination, `packages/codex-notification-protocol/runtime/index.${suffix}`);
  const before = await readFile(index, 'utf8');
  if (!before.includes("export * from './usage.js'"))
    await writeFile(index, before + "\nexport * from './usage.js';\n");
  files[index.slice(resolve(destination).length + 1)] = createHash('sha256')
    .update(await readFile(index))
    .digest('hex');
  await copy(
    `packages/easywork-adapter/dist/index.${suffix}`,
    join(destination, `packages/codex-easywork-adapter/runtime/index.${suffix}`),
  );
}
await writeFile(
  join(destination, 'packages/codex-easywork-adapter/package.json'),
  JSON.stringify(
    {
      name: '@codex-manage/easywork-adapter',
      version: '0.1.0',
      private: true,
      type: 'module',
      exports: { '.': { types: './runtime/index.d.ts', import: './runtime/index.js' } },
      dependencies: { '@codex-manage/protocol': 'workspace:*' },
    },
    null,
    2,
  ) + '\n',
);
// Use the destination's pinned formatter before recording immutable artifact hashes.
execFileSync(
  join(resolve(destination), 'node_modules/.bin/oxfmt'),
  ['--write', 'packages/codex-easywork-adapter'],
  { cwd: resolve(destination), stdio: 'pipe' },
);
for (const suffix of ['js', 'd.ts']) {
  const file = `packages/codex-easywork-adapter/runtime/index.${suffix}`;
  files[file] = createHash('sha256')
    .update(await readFile(join(destination, file)))
    .digest('hex');
}
files['packages/codex-easywork-adapter/package.json'] = createHash('sha256')
  .update(await readFile(join(destination, 'packages/codex-easywork-adapter/package.json')))
  .digest('hex');
await writeFile(
  join(destination, 'packages/codex-easywork-adapter/source-manifest.json'),
  JSON.stringify({ version: 1, source: 'codex-manage/packages/easywork-adapter', files }, null, 2) +
    '\n',
);
console.log('Usage runtime distributed: ' + Object.keys(files).length + ' files');
