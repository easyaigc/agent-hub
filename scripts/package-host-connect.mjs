import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { cp, lstat, mkdir, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { dirname, extname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
import { verifyBundle } from '../skills/codex-host-connect/scripts/task-hub-core.mjs';

const rootFiles = [
  'AGENTS.md',
  'README.md',
  'LICENSE',
  'package.json',
  'pnpm-lock.yaml',
  'pnpm-workspace.yaml',
  'turbo.json',
  'eslint.config.mjs',
  '.editorconfig',
  '.gitignore',
  '.node-version',
  '.prettierignore',
  '.prettierrc.json',
  'settings.gradle.kts',
  'build.gradle.kts',
  'gradle.properties',
  'gradlew',
  'gradlew.bat',
];
const sourceRoots = [
  'apps',
  'packages',
  'scripts',
  'skills',
  'tooling',
  'docs',
  '.github',
  'gradle',
];
// Nonstandard source paths require explicit review, rather than permitting all
// JARs, properties, Dockerfiles or Bash scripts. The wrapper checksum stays fixed.
const fixedSourceFiles = new Set([
  'gradle/wrapper/gradle-wrapper.jar',
  'gradle/wrapper/gradle-wrapper.properties',
  'scripts/ci/Dockerfile',
  'scripts/ci/legacy-bootstrap.bash',
  'scripts/ci/legacy-sdkmanager.bash',
]);
const runtimePackages = ['codex-adapter', 'protocol', 'service-runtime', 'notification-runtime'];
const excluded = new Set([
  'node_modules',
  'dist',
  '.git',
  '.local',
  '.build',
  'build',
  '.gradle',
  '.kotlin',
  'local.properties',
  '.turbo',
  'coverage',
  'DerivedData',
  'xcuserdata',
  '.DS_Store',
  'access-token',
  'controller.json',
  'controller-verification.json',
]);
const extensions = new Set([
  '.ts',
  '.kt',
  '.kts',
  '.xml',
  '.txt',
  '.patch',
  '.mjs',
  '.swift',
  '.storyboard',
  '.json',
  '.md',
  '.yaml',
  '.yml',
  '.plist',
  '.entitlements',
  '.pbxproj',
  '.xcscheme',
  '.xcworkspacedata',
  '.png',
  '.sh',
  '.ps1',
  '.cs',
  '.html',
  '.css',
  '.svg',
  '.xcprivacy',
  '.strings',
  '.py',
]);

async function sourceFiles(directory, prefix = '') {
  const files = [];
  for (const name of (await readdir(directory)).sort()) {
    const relative = prefix ? prefix + '/' + name : name;
    if (
      excluded.has(name) ||
      (name.startsWith('.env') && name !== '.env.example') ||
      /\.(log|tsbuildinfo|xcuserstate)$/.test(name) ||
      relative === 'apps/ios/AppStore' ||
      relative === 'docs/acceptance' ||
      // README demos can contain real conversations; they are not reusable source.
      relative === 'docs/assets/readme' ||
      relative === 'skills/codex-host-connect/assets'
    )
      continue;
    const stat = await lstat(join(directory, name));
    if (stat.isSymbolicLink()) throw new Error('Source symlink cannot be distributed: ' + relative);
    if (stat.isDirectory()) files.push(...(await sourceFiles(join(directory, name), relative)));
    else if (stat.isFile()) {
      if (
        !extensions.has(extname(name)) &&
        name !== '.env.example' &&
        !fixedSourceFiles.has(relative)
      )
        throw new Error('Review unknown source file before distribution: ' + relative);
      files.push(relative);
    }
  }
  return files;
}

async function copyFiles(from, to, files) {
  for (const relative of files) {
    const stat = await lstat(join(from, relative));
    if (!stat.isFile() || stat.isSymbolicLink())
      throw new Error('Only regular distribution files are allowed: ' + relative);
    await mkdir(dirname(join(to, relative)), { recursive: true });
    await cp(join(from, relative), join(to, relative));
  }
}

async function compiledRuntimeFiles(repository, component) {
  const sources = await readdir(join(repository, component, 'src'), { withFileTypes: true });
  // These runtime projects emit one flat JS file per non-declaration TS source.
  // A changed layout needs an explicit packaging strategy, never silent omission.
  if (sources.some((entry) => entry.isDirectory()))
    throw new Error('Runtime source layout must remain flat for packaging: ' + component);
  const expected = [];
  for (const entry of sources) {
    if (!entry.name.endsWith('.ts') || entry.name.endsWith('.d.ts')) continue;
    if (!entry.isFile()) throw new Error('Runtime source must be a regular file: ' + component);
    expected.push(entry.name.slice(0, -3) + '.js');
  }
  expected.sort();
  const compiled = (await readdir(join(repository, component, 'dist')))
    .filter((name) => name.endsWith('.js'))
    .sort();
  const unexpected = compiled.filter((name) => !expected.includes(name));
  const missing = expected.filter((name) => !compiled.includes(name));
  if (!expected.length || unexpected.length || missing.length)
    throw new Error(
      'Compiled runtime inventory differs from TypeScript sources: ' +
        component +
        '; unexpected=' +
        JSON.stringify(unexpected) +
        '; missing=' +
        JSON.stringify(missing) +
        '. Rebuild clean frozen source with empty dist; do not copy development dist.',
    );
  return compiled;
}

// ditto writes UTF-8 filename bytes but omits ZIP's UTF-8 flag. Correct both
// headers without recompressing entries or changing CRCs, sizes or Unix modes.
// Validate the whole ZIP32 layout before mutating any bytes; unsupported ZIP64
// and inconsistent archives must fail rather than produce a misleading bundle.
export function correctZipUtf8Flags(archive) {
  const fail = (reason) => {
    throw new Error('Cannot mark distribution ZIP as UTF-8: ' + reason);
  };
  if (!Buffer.isBuffer(archive)) fail('expected ZIP bytes');
  const bounded = (offset, size, end = archive.length) => {
    if (offset < 0 || size < 0 || offset + size > end) fail('truncated ZIP structure');
  };
  let end = -1;
  for (let at = archive.length - 22; at >= Math.max(0, archive.length - 65557); at--) {
    if (
      archive.readUInt32LE(at) === 0x06054b50 &&
      at + 22 + archive.readUInt16LE(at + 20) === archive.length
    ) {
      end = at;
      break;
    }
  }
  if (end < 0) fail('missing end-of-central-directory record');
  const count = archive.readUInt16LE(end + 10),
    centralSize = archive.readUInt32LE(end + 12),
    centralOffset = archive.readUInt32LE(end + 16);
  if (count === 0xffff || centralSize === 0xffffffff || centralOffset === 0xffffffff)
    fail('ZIP64 is not supported by the encoding correction');
  if (
    archive.readUInt16LE(end + 4) !== 0 ||
    archive.readUInt16LE(end + 6) !== 0 ||
    archive.readUInt16LE(end + 8) !== count ||
    centralOffset + centralSize !== end
  )
    fail('multi-disk or inconsistent central directory');
  const utf8 = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });
  const decode = (bytes) => {
    try {
      const value = utf8.decode(bytes);
      if (!Buffer.from(value, 'utf8').equals(bytes)) fail('noncanonical UTF-8 bytes');
      return value;
    } catch {
      fail('filename or file comment is not valid UTF-8');
    }
  };
  const extra = (at, size) => {
    const limit = at + size;
    while (at < limit) {
      bounded(at, 4, limit);
      const kind = archive.readUInt16LE(at),
        length = archive.readUInt16LE(at + 2);
      bounded(at + 4, length, limit);
      if (kind === 1) fail('ZIP64 extra field is not supported');
      at += 4 + length;
    }
  };
  const entries = [],
    names = new Set();
  let cursor = centralOffset;
  for (let i = 0; i < count; i++) {
    bounded(cursor, 46, end);
    if (archive.readUInt32LE(cursor) !== 0x02014b50) fail('invalid central header');
    const flags = archive.readUInt16LE(cursor + 8),
      method = archive.readUInt16LE(cursor + 10),
      crc = archive.readUInt32LE(cursor + 16),
      compressed = archive.readUInt32LE(cursor + 20),
      uncompressed = archive.readUInt32LE(cursor + 24),
      nameLength = archive.readUInt16LE(cursor + 28),
      extraLength = archive.readUInt16LE(cursor + 30),
      commentLength = archive.readUInt16LE(cursor + 32),
      localOffset = archive.readUInt32LE(cursor + 42);
    bounded(cursor + 46, nameLength + extraLength + commentLength, end);
    if ([compressed, uncompressed, localOffset].includes(0xffffffff)) fail('ZIP64 entry');
    if (archive.readUInt16LE(cursor + 34) !== 0) fail('multi-disk entry');
    if (flags & 0x2041 || ![0, 8].includes(method)) fail('encrypted or unsupported entry');
    const name = archive.subarray(cursor + 46, cursor + 46 + nameLength),
      path = decode(name);
    if (
      !path ||
      path.startsWith('/') ||
      /^[a-z]:/i.test(path) ||
      path.includes('\\') ||
      path.includes('\0') ||
      path.split('/').includes('..') ||
      names.has(path)
    )
      fail('unsafe or duplicate filename');
    names.add(path);
    extra(cursor + 46 + nameLength, extraLength);
    decode(
      archive.subarray(
        cursor + 46 + nameLength + extraLength,
        cursor + 46 + nameLength + extraLength + commentLength,
      ),
    );
    entries.push({
      central: cursor,
      localOffset,
      flags,
      method,
      crc,
      compressed,
      uncompressed,
      name,
    });
    cursor += 46 + nameLength + extraLength + commentLength;
  }
  if (cursor !== end) fail('central directory count or size mismatch');
  cursor = 0;
  for (const entry of entries.toSorted((a, b) => a.localOffset - b.localOffset)) {
    const at = entry.localOffset;
    bounded(at, 30, centralOffset);
    if (at !== cursor || archive.readUInt32LE(at) !== 0x04034b50)
      fail('noncontiguous or invalid local header');
    if (
      archive.readUInt16LE(at + 6) !== entry.flags ||
      archive.readUInt16LE(at + 8) !== entry.method
    )
      fail('local and central flags or compression differ');
    const nameLength = archive.readUInt16LE(at + 26),
      extraLength = archive.readUInt16LE(at + 28);
    bounded(at + 30, nameLength + extraLength + entry.compressed, centralOffset);
    if (!archive.subarray(at + 30, at + 30 + nameLength).equals(entry.name))
      fail('local and central filenames differ');
    extra(at + 30 + nameLength, extraLength);
    for (const [offset, value] of [
      [14, entry.crc],
      [18, entry.compressed],
      [22, entry.uncompressed],
    ]) {
      const localValue = archive.readUInt32LE(at + offset);
      if (localValue !== value && (!(entry.flags & 8) || localValue !== 0))
        fail('local and central CRC or size mismatch');
    }
    cursor = at + 30 + nameLength + extraLength + entry.compressed;
    if (entry.flags & 8) {
      bounded(cursor, 12, centralOffset);
      if (archive.readUInt32LE(cursor) === 0x08074b50) cursor += 4;
      bounded(cursor, 12, centralOffset);
      if (
        archive.readUInt32LE(cursor) !== entry.crc ||
        archive.readUInt32LE(cursor + 4) !== entry.compressed ||
        archive.readUInt32LE(cursor + 8) !== entry.uncompressed
      )
        fail('data descriptor CRC or size mismatch');
      cursor += 12;
    }
  }
  if (cursor !== centralOffset) fail('local entries do not fill the payload section');
  for (const entry of entries) {
    archive.writeUInt16LE(entry.flags | 0x0800, entry.central + 8);
    archive.writeUInt16LE(entry.flags | 0x0800, entry.localOffset + 6);
  }
  return archive;
}

export async function packageHostConnect({
  repository,
  output,
  taskHubBundle = process.env.TASK_HUB_BUNDLE,
}) {
  if (process.platform !== 'darwin') throw new Error('Bundle builder requires macOS ditto');
  repository = resolve(repository);
  output = resolve(output);
  if (!taskHubBundle)
    throw new Error(
      'Complete package requires TASK_HUB_BUNDLE pointing to a frozen Task Hub artifact',
    );
  taskHubBundle = resolve(taskHubBundle);
  const taskHubManifest = await verifyBundle(taskHubBundle);
  const components = ['apps/host-agent', ...runtimePackages.map((name) => 'packages/' + name)];
  const compiledByComponent = new Map();
  // Validate every runtime before staging or creating an archive/fingerprint.
  for (const component of components)
    compiledByComponent.set(component, await compiledRuntimeFiles(repository, component));
  const stagingRoot = join(repository, '.local');
  await mkdir(stagingRoot, { recursive: true });
  const staging = await mkdtemp(join(stagingRoot, 'connect-package-'));
  try {
    const skill = join(staging, 'codex-host-connect');
    const program = join(skill, 'assets/codex-manage');
    await copyFiles(
      join(repository, 'skills/codex-host-connect'),
      skill,
      await sourceFiles(join(repository, 'skills/codex-host-connect')),
    );
    await copyFiles(repository, program, rootFiles);
    for (const directory of sourceRoots)
      await copyFiles(
        repository,
        program,
        await sourceFiles(join(repository, directory), directory),
      );

    const runtimeFingerprints = {};
    for (const component of components) {
      const manifest = JSON.parse(
        await readFile(join(repository, component, 'package.json'), 'utf8'),
      );
      for (const [name, version] of Object.entries(manifest.dependencies ?? {})) {
        if (component === 'apps/host-agent' && ['bonjour-service', 'qrcode'].includes(name))
          continue;
        if (
          !runtimePackages.some((entry) => name === '@codex-manage/' + entry) ||
          !version.startsWith('workspace:')
        )
          throw new Error('Runtime dependency needs a deployment strategy: ' + name);
      }
      const dist = join(repository, component, 'dist');
      const compiled = compiledByComponent.get(component);
      await copyFiles(dist, join(program, component, 'dist'), compiled);
      const fingerprintFiles = {};
      for (const name of compiled)
        fingerprintFiles[name] = createHash('sha256')
          .update(await readFile(join(program, component, 'dist', name)))
          .digest('hex');
      runtimeFingerprints[component] = createHash('sha256')
        .update(JSON.stringify(fingerprintFiles))
        .digest('hex');
      if (component.startsWith('packages/')) {
        const deployed = join(program, 'node_modules', manifest.name);
        await copyFiles(join(program, component), deployed, ['package.json']);
        await copyFiles(dist, join(deployed, 'dist'), compiled);
        if (component === 'packages/codex-adapter') {
          await copyFiles(
            join(repository, component),
            deployed,
            await sourceFiles(join(repository, component, 'windows'), 'windows'),
          );
          const helper = 'desktop-completion-notify-observer.py';
          for (const adapter of [join(program, component), deployed])
            await copyFiles(join(repository, 'scripts'), join(adapter, 'resources'), [helper]);
        }
      }
    }
    // Copy the reviewed JS discovery dependencies and their declared dependency
    // closure. Resolve pnpm links locally; deployed files contain no host symlinks.
    const deployedVersions = new Map();
    const copyDependency = async (name, fromManifest) => {
      const require = createRequire(fromManifest);
      let manifestPath;
      try {
        manifestPath = require.resolve(name + '/package.json');
      } catch (error) {
        if (error.code !== 'ERR_PACKAGE_PATH_NOT_EXPORTED') throw error;
        let directory = dirname(require.resolve(name));
        while (true) {
          const candidate = join(directory, 'package.json');
          let manifest;
          try {
            manifest = JSON.parse(await readFile(candidate, 'utf8'));
          } catch (error) {
            if (error.code !== 'ENOENT') throw error;
          }
          if (manifest?.name === name) {
            manifestPath = candidate;
            break;
          }
          const parent = dirname(directory);
          if (parent === directory)
            throw Error('Cannot locate dependency manifest: ' + name, { cause: error });
          directory = parent;
        }
      }
      const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
      if (deployedVersions.has(name)) {
        if (deployedVersions.get(name) !== manifest.version)
          throw Error('Conflicting deployment dependency: ' + name);
        return;
      }
      deployedVersions.set(name, manifest.version);
      const files = [];
      const collect = async (directory, prefix = '') => {
        for (const entry of await readdir(directory)) {
          if (['node_modules', '.git', '.env', '.DS_Store'].includes(entry)) continue;
          const path = join(directory, entry),
            relative = prefix ? prefix + '/' + entry : entry;
          const stat = await lstat(path);
          if (stat.isSymbolicLink())
            throw Error('Dependency symlink cannot be distributed: ' + name);
          if (stat.isDirectory()) await collect(path, relative);
          else if (stat.isFile()) files.push(relative);
        }
      };
      await collect(dirname(manifestPath));
      await copyFiles(dirname(manifestPath), join(program, 'node_modules', name), files);
      for (const dependency of Object.keys(manifest.dependencies ?? {}))
        await copyDependency(dependency, manifestPath);
    };
    for (const name of ['bonjour-service', 'qrcode'])
      await copyDependency(name, join(repository, 'apps/host-agent/package.json'));
    await cp(taskHubBundle, join(skill, 'assets/task-hub'), {
      recursive: true,
      verbatimSymlinks: true,
    });
    await verifyBundle(join(skill, 'assets/task-hub'));
    // The deployment includes only compiled workspace dependencies, with no machine-local symlinks.
    const packageManifest = {
      format: 1,
      createdAt: new Date().toISOString(),
      node: '24.x',
      pnpm: '11.19.0',
      originalDesktop: {
        supportedPlatforms: ['darwin'],
        experimentalPlatforms: ['win32'],
        verifiedBuilds: { darwin: ['26.930.61225', '26.1002.52244'], win32: [] },
        versionPolicy: 'metadata-only',
        protocolPolicy: 'validated-at-runtime',
        desktopCreationPolicy: 'runtime-readiness',
        desktopAcceptancePolicy: 'report-only',
        windows: 'implemented-owner-control',
        windowsReplyPolicy: 'actual-owner',
        windowsAcceptancePolicy: 'report-only',
        suggestions: {
          darwin: 'read-existing-native-trace-and-completion-notify',
          win32: 'read-existing-native-trace-with-current-instance-dacl-validation',
          windowsLiveAcceptance: 'pending-physical-phone-and-read-performance',
          originalDesktopTraceRequiresRestart: true,
          defaultDesktopStartupEnablesTrace: false,
          hostInstallationTraceDefault: 'persistent',
          mobileAdoption: 'explicit-local-draft-only',
        },
      },
      program: 'assets/codex-manage',
      entry: 'apps/host-agent/dist/runtime-entry.js',
      includes: ['compiled-host', 'runtime-dependencies', 'full-source', 'ios-source', 'skill'],
      runtimeFingerprints,
      taskHub: {
        program: 'assets/task-hub',
        sourceSha256: taskHubManifest.sourceSha256,
        payloadSha256: taskHubManifest.payloadSha256,
        lockSha256: taskHubManifest.lockSha256,
        sourcePackageManager: taskHubManifest.sourcePackageManager,
        primaryPlatforms: taskHubManifest.primaryPlatforms,
        executorPlatforms: taskHubManifest.executorPlatforms,
        architecture: taskHubManifest.architecture,
        contractVersion: taskHubManifest.contractVersion,
        rolePolicy: 'explicit-designated-primary; executor-never-creates-database',
      },
    };
    packageManifest.includes.push(
      'task-hub-web',
      'task-hub-api',
      'task-hub-migrations',
      'task-hub-runtime-dependencies',
    );
    await writeFile(join(skill, 'bundle.json'), JSON.stringify(packageManifest, null, 2) + '\n');
    await mkdir(dirname(output), { recursive: true });
    execFileSync('/usr/bin/ditto', [
      '-c',
      '-k',
      '--norsrc',
      '--noqtn',
      '--keepParent',
      skill,
      output,
    ]);
    const archive = correctZipUtf8Flags(await readFile(output));
    await writeFile(output, archive);
    const sha256 = createHash('sha256').update(archive).digest('hex');
    await writeFile(output + '.sha256', sha256 + '  ' + output.split('/').at(-1) + '\n');
    return { archive: output, sha256, ...packageManifest };
  } finally {
    await rm(staging, { recursive: true, force: true });
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const repository = resolve(fileURLToPath(new URL('..', import.meta.url)));
  console.log(
    JSON.stringify(
      await packageHostConnect({
        repository,
        output: join(repository, '.local/skill-distribution/codex-host-connect-complete.zip'),
      }),
      null,
      2,
    ),
  );
}
