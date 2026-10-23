import { createHash } from 'node:crypto';
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';

// Distribute reproducible compiled packages. This repository remains the source of truth.
const destination = process.argv[2];
if (!destination)
  throw new Error(
    '用法：pnpm build && node scripts/distribute-notification-runtime.mjs <EasyWork 仓库>',
  );
const hashes = {};
for (const name of ['protocol', 'notification-runtime']) {
  const source = resolve('packages', name);
  const target = resolve(
    destination,
    'packages',
    name === 'protocol' ? 'codex-notification-protocol' : 'codex-notification-runtime',
  );
  await mkdir(join(target, 'runtime'), { recursive: true });
  for (const file of (await readdir(join(source, 'dist'))).sort()) {
    if (!/\.(js|d\.ts)$/.test(file)) continue;
    const data = Buffer.from(
      (await readFile(join(source, 'dist', file), 'utf8')).replace(
        /\n\/\/# sourceMappingURL=[^\n]+\n?$/,
        '\n',
      ),
    );
    hashes[name + '/' + file] = createHash('sha256').update(data).digest('hex');
    await writeFile(join(target, 'runtime', file), data);
  }
  const original = JSON.parse(await readFile(join(source, 'package.json'), 'utf8'));
  await writeFile(
    join(target, 'package.json'),
    JSON.stringify(
      {
        name: original.name,
        version: original.version,
        private: true,
        type: 'module',
        exports: { '.': { types: './runtime/index.d.ts', import: './runtime/index.js' } },
        ...(original.dependencies ? { dependencies: original.dependencies } : {}),
      },
      null,
      2,
    ) + '\n',
  );
}
const manifest = {
  version: 1,
  source: 'codex-manage/packages/{protocol,notification-runtime}',
  files: hashes,
  sha256: createHash('sha256').update(JSON.stringify(hashes)).digest('hex'),
};
await writeFile(
  resolve(destination, 'packages/codex-notification-runtime/source-manifest.json'),
  JSON.stringify(manifest, null, 2) + '\n',
);
console.log('notification-runtime sha256: ' + manifest.sha256);
