import { readdir, readFile } from 'node:fs/promises';

// The runner workspace is temporary. Export only bounded synthetic-test logs
// before cleanup, including when the CI step timeout killed its parent.
const root = '.local/mobile-acceptance/A0';
let directories;
try {
  directories = await readdir(root);
} catch (error) {
  if (error.code !== 'ENOENT') throw error;
  console.log('No native baseline output was created.');
  process.exit(0);
}
const latest = directories
  .filter((name) => /^\d+$/.test(name))
  .sort()
  .at(-1);
if (!latest) process.exit(0);
const output = root + '/' + latest;
for (const file of [
  'report.json',
  ...['subagents', 'voiceTap', 'usageDetails'].flatMap((group) => [
    group + '-cases/index.json',
    group + '.log',
  ]),
]) {
  try {
    const bytes = await readFile(output + '/' + file);
    const limit = file.endsWith('.log') ? 4096 : 2048;
    console.log('Native diagnostic:', file, bytes.subarray(-limit).toString('utf8'));
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
}
