import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFile, readdir, realpath } from 'node:fs/promises';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const components = {
  '@codex-manage/host-agent': 'apps/host-agent',
  '@codex-manage/codex-adapter': 'packages/codex-adapter',
  '@codex-manage/protocol': 'packages/protocol',
  '@codex-manage/service-runtime': 'packages/service-runtime',
  '@codex-manage/notification-runtime': 'packages/notification-runtime',
};
const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');

// Resolve ESM's import condition without importing or executing the Host or its dependencies.
const resolver = `import {readFileSync} from 'node:fs';
const requests=JSON.parse(readFileSync(0,'utf8'));
console.log(JSON.stringify(requests.map(({name,parent})=>{
 try{return {name,url:import.meta.resolve(name,parent)};}
 catch(error){return {name,error:error.code??'RESOLUTION_FAILED'};}
})));`;

async function packageAt(entry, expectedName) {
  entry = await realpath(entry);
  const directory = dirname(entry);
  const root = dirname(directory);
  const manifest = JSON.parse(await readFile(join(root, 'package.json'), 'utf8'));
  if (manifest.name !== expectedName || relative(root, directory) !== 'dist')
    throw new Error('Unexpected runtime package layout: ' + expectedName);
  const files = {};
  for (const name of (await readdir(directory)).filter((name) => name.endsWith('.js')).sort())
    files[name] = sha256(await readFile(join(directory, name)));
  if (!Object.keys(files).length) throw new Error('Empty runtime package: ' + expectedName);
  return { entry, root, manifest, fingerprint: sha256(JSON.stringify(files)) };
}

/** Compare the actual ESM dependency closure with a unified bundle's compiled fingerprints. */
export async function verifyHostRuntime({ entry, bundle, node = process.execPath }) {
  const expected = JSON.parse(await readFile(bundle, 'utf8')).runtimeFingerprints;
  for (const component of Object.values(components))
    if (!/^[a-f0-9]{64}$/.test(expected?.[component] ?? ''))
      throw new Error('Missing unified runtime fingerprint: ' + component);
  const pending = [{ name: '@codex-manage/host-agent', entry: resolve(entry), importer: null }];
  const inspected = new Set();
  const packages = [];
  const resolutions = [];
  const mismatches = [];
  while (pending.length) {
    const next = pending.shift();
    const value = await packageAt(next.entry, next.name);
    const key = next.name + '\n' + value.root;
    resolutions.push({ name: next.name, importer: next.importer, entry: value.entry });
    if (inspected.has(key)) continue;
    inspected.add(key);
    const component = components[next.name];
    const matches = value.fingerprint === expected[component];
    packages.push({
      name: next.name,
      component,
      root: value.root,
      entry: value.entry,
      fingerprint: value.fingerprint,
      expected: expected[component],
      matches,
    });
    if (!matches) mismatches.push({ name: next.name, root: value.root, reason: 'fingerprint' });
    const requests = Object.keys(value.manifest.dependencies ?? {})
      .filter((name) => Object.hasOwn(components, name))
      .map((name) => ({ name, parent: pathToFileURL(value.entry).href }));
    if (!requests.length) continue;
    const result = JSON.parse(
      execFileSync(
        node,
        ['--experimental-import-meta-resolve', '--input-type=module', '--eval', resolver],
        {
          input: JSON.stringify(requests),
          encoding: 'utf8',
          timeout: 5000,
          windowsHide: true,
          maxBuffer: 1024 * 1024,
          stdio: ['pipe', 'pipe', 'pipe'],
        },
      ),
    );
    for (const item of result) {
      if (item.error) {
        mismatches.push({ name: item.name, importer: value.entry, reason: item.error });
        continue;
      }
      pending.push({ name: item.name, entry: fileURLToPath(item.url), importer: value.entry });
    }
  }
  for (const [name] of Object.entries(components))
    if (!packages.some((item) => item.name === name))
      mismatches.push({ name, reason: 'not-resolved-from-host' });
  return {
    ok: mismatches.length === 0,
    evidence: 'esm-resolution-files',
    node: await realpath(node),
    packages,
    resolutions,
    mismatches,
  };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  if (args.length !== 4 || args[0] !== '--entry' || args[2] !== '--bundle')
    throw new Error('Use --entry <actual-host-dist/runtime-entry.js> --bundle <bundle.json>');
  const result = await verifyHostRuntime({ entry: args[1], bundle: args[3] });
  console.log(JSON.stringify(result, null, 2));
  if (!result.ok) process.exitCode = 1;
}
