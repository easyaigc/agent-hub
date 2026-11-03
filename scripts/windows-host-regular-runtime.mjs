import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  lstatSync,
  readFileSync,
  readdirSync,
  mkdirSync,
  writeFileSync,
  existsSync,
} from 'node:fs';

const digest = (bytes) => createHash('sha256').update(bytes).digest('hex');
const fail = (code) => {
  throw Object.assign(new Error(code), { code });
};
const components = [
  'apps/host-agent',
  ...['codex-adapter', 'protocol', 'service-runtime', 'notification-runtime'].map(
    (name) => 'packages/' + name,
  ),
];
const resourceHelpers = ['desktop-completion-notify-observer.py', 'desktop-trace-retire.py'];
const safeName = (name) =>
  /^[a-zA-Z0-9@._/-]+$/.test(name) &&
  !name.startsWith('/') &&
  name.split('/').every((part) => part && part !== '.' && part !== '..');

function regularBytes(path, maximum = 128 * 1024 * 1024, allowSourceHardlinks = false) {
  let cursor = resolve(path);
  for (;;) {
    if (lstatSync(cursor).isSymbolicLink()) fail('HOST_RUNTIME_REPARSE');
    const parent = dirname(cursor);
    if (parent === cursor) break;
    cursor = parent;
  }
  const stat = lstatSync(path);
  if (!stat.isFile() || (!allowSourceHardlinks && stat.nlink !== 1) || stat.size > maximum)
    fail('HOST_RUNTIME_FILE_REJECTED');
  const bytes = readFileSync(path);
  if (bytes.length !== stat.size) fail('HOST_RUNTIME_FILE_CHANGED');
  return bytes;
}

function walk(directory, prefix = '') {
  const result = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const name = prefix + entry.name;
    const path = join(directory, entry.name);
    if (lstatSync(path).isSymbolicLink()) fail('HOST_RUNTIME_REPARSE');
    if (entry.isDirectory()) result.push(...walk(path, name + '/'));
    else if (entry.isFile()) result.push(name);
    else fail('HOST_RUNTIME_FILE_REJECTED');
  }
  return result.sort();
}

// Match the existing distribution gate: these five projects emit one flat JS
// module per non-declaration TS source. Never freeze stale or missing output.
export function regularCompiledFiles(repository, component) {
  const sources = readdirSync(join(repository, component, 'src'), { withFileTypes: true });
  if (sources.some((entry) => entry.isDirectory())) fail('HOST_RUNTIME_SOURCE_LAYOUT_CHANGED');
  const expected = sources
    .filter((entry) => entry.name.endsWith('.ts') && !entry.name.endsWith('.d.ts'))
    .map((entry) => {
      if (!entry.isFile()) fail('HOST_RUNTIME_SOURCE_LAYOUT_CHANGED');
      return entry.name.slice(0, -3) + '.js';
    })
    .sort();
  const compiled = walk(join(repository, component, 'dist'));
  const modules = compiled.filter((name) => name.endsWith('.js'));
  if (!expected.length || JSON.stringify(modules) !== JSON.stringify(expected))
    fail('HOST_RUNTIME_COMPILED_INVENTORY_CHANGED');
  return compiled;
}

/** Collect the actual compiled closure; never include configuration or state. */
export function collectRegularRuntime(repository, nodeExecutable) {
  repository = resolve(repository);
  const git = (...args) =>
    execFileSync('git', args, { cwd: repository, encoding: 'utf8', windowsHide: true });
  if (git('status', '--porcelain=v1', '--untracked-files=all').trim())
    fail('HOST_RUNTIME_SOURCE_DIRTY');
  const sourceCommit = git('rev-parse', 'HEAD').trim();
  if (!/^[a-f0-9]{40}$/.test(sourceCommit)) fail('HOST_RUNTIME_SOURCE_REJECTED');
  const files = new Map();
  const add = (name, bytes) => {
    if (!safeName(name) || files.has(name)) fail('HOST_RUNTIME_NAME_REJECTED');
    files.set(name, bytes);
  };
  const tracked = git('ls-files', '-z').split('\0').filter(Boolean);
  for (const name of tracked) {
    if (
      ['package.json', 'LICENSE', 'pnpm-lock.yaml'].includes(name) ||
      name.startsWith('scripts/') ||
      name.startsWith('packages/codex-adapter/windows/')
    )
      add(name, regularBytes(join(repository, name)));
  }
  const versions = new Map();
  const copyDependency = (name, fromManifest) => {
    const require = createRequire(fromManifest);
    let manifestPath;
    try {
      manifestPath = require.resolve(name + '/package.json');
    } catch (error) {
      if (error.code !== 'ERR_PACKAGE_PATH_NOT_EXPORTED') throw error;
      let directory = dirname(require.resolve(name));
      for (;;) {
        const candidate = join(directory, 'package.json');
        if (existsSync(candidate) && JSON.parse(readFileSync(candidate, 'utf8')).name === name) {
          manifestPath = candidate;
          break;
        }
        const parent = dirname(directory);
        if (parent === directory) fail('HOST_RUNTIME_DEPENDENCY_MANIFEST_MISSING');
        directory = parent;
      }
    }
    // pnpm resolves the link here. Only the package's real regular files are
    // copied; no links or another package manager are deployed.
    const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
    if (versions.has(name)) {
      if (versions.get(name) !== manifest.version) fail('HOST_RUNTIME_DEPENDENCY_CONFLICT');
      return;
    }
    versions.set(name, manifest.version);
    const directory = dirname(manifestPath);
    const visit = (at, prefix = '') => {
      for (const entry of readdirSync(at, { withFileTypes: true })) {
        if (['node_modules', '.git', '.env', '.DS_Store'].includes(entry.name)) continue;
        const path = join(at, entry.name);
        const child = prefix + entry.name;
        if (lstatSync(path).isSymbolicLink()) fail('HOST_RUNTIME_DEPENDENCY_REPARSE');
        if (entry.isDirectory()) visit(path, child + '/');
        else if (entry.isFile())
          // pnpm's store uses hardlinks. Read/hash those source bytes, then
          // create independent files; deployed inventory still requires nlink=1.
          add('node_modules/' + name + '/' + child, regularBytes(path, undefined, true));
        else fail('HOST_RUNTIME_DEPENDENCY_FILE_REJECTED');
      }
    };
    visit(directory);
    for (const dependency of Object.keys(manifest.dependencies ?? {}))
      copyDependency(dependency, manifestPath);
  };
  for (const component of components) {
    const manifestBytes = regularBytes(join(repository, component, 'package.json'));
    const manifest = JSON.parse(manifestBytes);
    add(component + '/package.json', manifestBytes);
    const compiled = regularCompiledFiles(repository, component);
    if (!compiled.includes(component === 'apps/host-agent' ? 'runtime-entry.js' : 'index.js'))
      fail('HOST_RUNTIME_BUILD_MISSING');
    for (const name of compiled)
      add(component + '/dist/' + name, regularBytes(join(repository, component, 'dist', name)));
    for (const [dependency, version] of Object.entries(manifest.dependencies ?? {})) {
      if (component === 'apps/host-agent' && ['bonjour-service', 'qrcode'].includes(dependency)) {
        copyDependency(dependency, join(repository, component, 'package.json'));
      } else if (
        !components.some((c) => c === 'packages/' + dependency.replace('@codex-manage/', '')) ||
        !version.startsWith('workspace:')
      )
        fail('HOST_RUNTIME_DEPENDENCY_UNREVIEWED');
    }
    if (component.startsWith('packages/')) {
      const deployed = 'node_modules/' + manifest.name;
      add(deployed + '/package.json', manifestBytes);
      for (const name of compiled)
        add(deployed + '/dist/' + name, files.get(component + '/dist/' + name));
      if (component === 'packages/codex-adapter') {
        for (const name of tracked.filter((n) => n.startsWith(component + '/windows/')))
          add(deployed + name.slice(component.length), files.get(name));
        for (const helper of resourceHelpers) {
          const bytes = files.get('scripts/' + helper);
          if (!bytes) fail('HOST_RUNTIME_RESOURCE_MISSING');
          for (const root of [component, deployed]) add(root + '/resources/' + helper, bytes);
        }
      }
    }
  }
  if (versions.get('bonjour-service') !== '1.4.4' || versions.get('qrcode') !== '1.5.4')
    fail('HOST_RUNTIME_DEPENDENCY_VERSION_CHANGED');
  add('bin/node.exe', regularBytes(nodeExecutable));
  const inventory = Object.fromEntries(
    [...files]
      .sort(([a], [b]) => a.localeCompare(b, 'en'))
      .map(([name, bytes]) => [name, { bytes: bytes.length, sha256: digest(bytes) }]),
  );
  const manifest = {
    format: 'windows-host-regular-runtime-v1',
    sourceCommit,
    node: process.version,
    dependencies: Object.fromEntries([...versions].sort()),
    files: inventory,
  };
  const bytes = Buffer.from(JSON.stringify(manifest) + '\n');
  return { manifest, bytes, sha256: digest(bytes), files };
}

export function verifyRegularRuntime(directory, expectedSha256) {
  const bytes = regularBytes(join(directory, 'runtime-manifest.json'), 2 * 1024 * 1024);
  if (!/^[a-f0-9]{64}$/.test(expectedSha256) || digest(bytes) !== expectedSha256)
    fail('HOST_RUNTIME_MANIFEST_CHANGED');
  const manifest = JSON.parse(bytes);
  if (
    manifest.format !== 'windows-host-regular-runtime-v1' ||
    !/^[a-f0-9]{40}$/.test(manifest.sourceCommit ?? '') ||
    !manifest.files ||
    Object.keys(manifest.files).length > 20000
  )
    fail('HOST_RUNTIME_MANIFEST_REJECTED');
  const names = Object.keys(manifest.files);
  if (
    !names.includes('bin/node.exe') ||
    !names.includes('scripts/windows-desktop-worker.mjs') ||
    !names.includes('apps/host-agent/dist/runtime-entry.js') ||
    JSON.stringify(walk(directory).sort()) !==
      JSON.stringify([...names, 'runtime-manifest.json'].sort())
  )
    fail('HOST_RUNTIME_INVENTORY_CHANGED');
  for (const name of names) {
    if (!safeName(name)) fail('HOST_RUNTIME_NAME_REJECTED');
    const entry = manifest.files[name];
    const actual = regularBytes(join(directory, name));
    if (actual.length !== entry.bytes || digest(actual) !== entry.sha256)
      fail('HOST_RUNTIME_FILE_CHANGED');
  }
  return manifest;
}

// Creation is confined to a new runtime child of an existing private operation
// directory. Formal activation separately checks native handles, ACLs and the
// exact manifest. This command never changes the installed service or identity.
export function freezeRegularRuntime(repository, nodeExecutable, operationDirectory) {
  const relativePath = relative(
    resolve(operationDirectory),
    resolve(operationDirectory, 'runtime'),
  );
  if (relativePath !== 'runtime') fail('HOST_RUNTIME_DESTINATION_REJECTED');
  const target = join(operationDirectory, 'runtime');
  if (!lstatSync(operationDirectory).isDirectory() || existsSync(target))
    fail('HOST_RUNTIME_DESTINATION_EXISTS');
  const collected = collectRegularRuntime(repository, nodeExecutable);
  mkdirSync(target);
  for (const [name, bytes] of collected.files) {
    const path = join(target, ...name.split('/'));
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, bytes, { flag: 'wx' });
  }
  writeFileSync(join(target, 'runtime-manifest.json'), collected.bytes, { flag: 'wx' });
  verifyRegularRuntime(target, collected.sha256);
  return { sourceCommit: collected.manifest.sourceCommit, sha256: collected.sha256, target };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (process.argv.length !== 5 || process.argv[2] !== 'freeze')
    fail('HOST_RUNTIME_ARGUMENTS_REJECTED');
  const here = fileURLToPath(new URL('..', import.meta.url));
  console.log(JSON.stringify(freezeRegularRuntime(here, process.argv[3], process.argv[4])));
}
