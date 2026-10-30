import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import { cp, lstat, mkdir, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import {
  fileInventory,
  exists,
  hash,
  json,
  verifyBundle,
} from '../skills/codex-host-connect/scripts/task-hub-core.mjs';

const sourceRoots = [
  'apps/api/src',
  'apps/api/migrations',
  'apps/web/src',
  'apps/web/public',
  'packages/contracts/src',
];
const sourceFiles = [
  'package.json',
  'pnpm-lock.yaml',
  'pnpm-workspace.yaml',
  'tsconfig.base.json',
  'apps/api/package.json',
  'apps/api/tsconfig.json',
  'apps/web/package.json',
  'apps/web/tsconfig.json',
  'apps/web/vite.config.ts',
  'apps/web/index.html',
  'packages/contracts/package.json',
];
const sourceExtensions = /\.(ts|tsx|js|mjs|css|scss|sql|html|svg|png|jpg|webp|json)$/;
async function reviewedSource(source) {
  const files = [...sourceFiles];
  const walk = async (prefix) => {
    for (const name of (await readdir(join(source, prefix))).sort()) {
      if (name.startsWith('.') || ['node_modules', 'dist', 'coverage'].includes(name)) continue;
      const path = prefix + '/' + name,
        st = await lstat(join(source, path));
      if (st.isSymbolicLink()) throw Error('Source symlink is not allowed: ' + path);
      if (st.isDirectory()) await walk(path);
      else if (st.isFile() && sourceExtensions.test(name)) files.push(path);
      else throw Error('Unreviewed source artifact: ' + path);
    }
  };
  for (const root of sourceRoots) await walk(root);
  const result = {};
  for (const file of files.sort()) {
    const st = await lstat(join(source, file));
    if (!st.isFile() || st.isSymbolicLink()) throw Error('Invalid source file');
    result[file] = hash(await readFile(join(source, file)));
  }
  return result;
}
async function pnpm(args, cwd, log, executable, nodeEnvironment = 'development') {
  const child = spawn(executable, args, {
    cwd,
    env: {
      ...process.env,
      CI: 'true',
      NODE_ENV: nodeEnvironment,
      COREPACK_ENABLE_PROJECT_SPEC: '1',
      VITE_API_BASE: '',
      WEB_ORIGIN: 'https://agenthub-task.local:9443',
      API_PORT: '9444',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let output = '';
  child.stdout.on('data', (b) => {
    output += b;
  });
  child.stderr.on('data', (b) => {
    output += b;
  });
  const code = await new Promise((res, rej) => {
    child.on('error', rej);
    child.on('exit', res);
  });
  await writeFile(log, output, { mode: 0o600 });
  if (code !== 0)
    throw Error('Frozen Task Hub build command failed; inspect private build log: ' + log);
}
// Discovery/QR dependencies are the Host's reviewed JavaScript dependency closure.
// Keep them outside the Task Hub workspace, which retains its own unchanged lock.
async function networkDependencies(repository, destination) {
  const versions = new Map();
  const copy = async (name, parent) => {
    const require = createRequire(parent);
    let packagePath;
    try {
      packagePath = require.resolve(name + '/package.json');
    } catch {
      let dir = dirname(require.resolve(name));
      for (;;) {
        try {
          if ((await json(join(dir, 'package.json'))).name === name) {
            packagePath = join(dir, 'package.json');
            break;
          }
        } catch {
          /* Not a package root. */
        }
        if (dirname(dir) === dir) throw Error('Cannot resolve reviewed network dependency');
        dir = dirname(dir);
      }
    }
    const manifest = await json(packagePath);
    if (versions.has(name)) {
      if (versions.get(name) !== manifest.version)
        throw Error('Conflicting network dependency versions');
      return;
    }
    versions.set(name, manifest.version);
    await cp(dirname(packagePath), join(destination, 'node_modules', name), {
      recursive: true,
      filter: (p) => !['node_modules', '.git', '.env', '.local'].includes(p.split('/').at(-1)),
    });
    for (const dep of Object.keys(manifest.dependencies ?? {})) await copy(dep, packagePath);
  };
  await mkdir(destination, { recursive: true });
  await writeFile(
    join(destination, 'package.json'),
    JSON.stringify({ private: true, type: 'module' }),
  );
  for (const name of ['bonjour-service', 'qrcode'])
    await copy(name, join(repository, 'apps/host-agent/package.json'));
  return Object.fromEntries(versions);
}
/** Compile the canonical cipher and protocol closure, never a second crypto implementation. */
export async function buildBusinessCrypto({ repository, destination }) {
  if (Number(process.versions.node.split('.')[0]) !== 24)
    throw Error('Business notification freeze requires Node 24');
  repository = resolve(repository);
  destination = resolve(destination);
  if (await exists(destination)) throw Error('Business crypto output already exists');
  const files = ['packages/notification-runtime/src/business-crypto.ts'];
  const walk = async (directory) => {
    for (const name of (await readdir(join(repository, directory))).sort()) {
      const path = directory + '/' + name;
      const st = await lstat(join(repository, path));
      if (st.isSymbolicLink()) throw Error('Canonical protocol source symlink is not allowed');
      if (st.isDirectory()) await walk(path);
      else if (st.isFile() && name.endsWith('.ts')) files.push(path);
      else throw Error('Unreviewed canonical protocol input');
    }
  };
  await walk('packages/protocol/src');
  const before = {};
  for (const file of files.sort()) {
    const st = await lstat(join(repository, file));
    if (!st.isFile() || st.isSymbolicLink())
      throw Error('Invalid canonical business crypto source');
    before[file] = hash(await readFile(join(repository, file)));
  }
  const relayRequire = createRequire(join(repository, 'apps/relay/package.json'));
  const toolsRequire = createRequire(relayRequire.resolve('tsx/package.json'));
  const esbuild = toolsRequire('esbuild');
  const lockSha256 = hash(await readFile(join(repository, 'pnpm-lock.yaml')));
  await mkdir(join(repository, '.local'), { recursive: true, mode: 0o700 });
  const frozen = await mkdtemp(join(repository, '.local/business-crypto-build-'));
  try {
    for (const file of files) {
      await mkdir(dirname(join(frozen, file)), { recursive: true });
      await writeFile(join(frozen, file), await readFile(join(repository, file)));
      if (before[file] !== hash(await readFile(join(frozen, file))))
        throw Error('Canonical business crypto input changed during freeze');
    }
    const compiled = await esbuild.build({
      absWorkingDir: frozen,
      entryPoints: ['packages/notification-runtime/src/business-crypto.ts'],
      alias: { '@codex-manage/protocol': join(frozen, 'packages/protocol/src/index.ts') },
      bundle: true,
      platform: 'node',
      target: 'node24',
      format: 'cjs',
      outfile: 'business-crypto.cjs',
      write: false,
      metafile: true,
      legalComments: 'none',
      logLevel: 'silent',
    });
    // A relocatable single-file closure may import only Node's crypto primitive.
    if (
      compiled.outputFiles.length !== 1 ||
      Object.values(compiled.metafile.outputs).some((output) =>
        output.imports.some((item) => !item.external || item.path !== 'node:crypto'),
      )
    )
      throw Error('Business crypto has an unbundled runtime dependency');
    const sources = {};
    for (const input of Object.keys(compiled.metafile.inputs).sort()) {
      const file = relative(frozen, resolve(frozen, input)).replaceAll('\\', '/');
      if (!Object.hasOwn(before, file)) throw Error('Unreviewed business crypto compiler input');
      sources[file] = before[file];
    }
    for (const file of files)
      if (before[file] !== hash(await readFile(join(repository, file))))
        throw Error('Canonical business crypto input changed during compile');
    if (lockSha256 !== hash(await readFile(join(repository, 'pnpm-lock.yaml'))))
      throw Error('Canonical compiler dependency lock changed during compile');
    const bytes = compiled.outputFiles[0].contents;
    const descriptor = {
      version: 1,
      domain: 'taskhub.business',
      format: 'cjs',
      node: '24',
      entry: 'business-crypto.cjs',
      sha256: hash(bytes),
      size: bytes.length,
      sourceSha256: hash(JSON.stringify(sources)),
      sources,
      compiler: { name: 'esbuild', version: esbuild.version, lockSha256 },
    };
    await mkdir(destination);
    await writeFile(join(destination, descriptor.entry), bytes);
    for (const file of Object.keys(sources)) {
      await mkdir(dirname(join(destination, 'source', file)), { recursive: true });
      await cp(join(frozen, file), join(destination, 'source', file));
    }
    await writeFile(join(destination, 'manifest.json'), JSON.stringify(descriptor, null, 2) + '\n');
    return descriptor;
  } finally {
    await rm(frozen, { recursive: true, force: true });
  }
}
export async function buildTaskHub({ source, output, repository, pnpmExecutable = 'pnpm' }) {
  if (Number(process.versions.node.split('.')[0]) !== 24)
    throw Error('Task Hub canonical notification build requires Node 24');
  source = resolve(source);
  output = resolve(output);
  repository = resolve(repository);
  if (await exists(output))
    throw Error('Frozen output already exists; choose a new candidate directory');
  await mkdir(join(repository, '.local'), { recursive: true, mode: 0o700 });
  const temp = await mkdtemp(join(repository, '.local/task-hub-build-'));
  let success = false;
  try {
    const frozen = join(temp, 'frozen'),
      artifact = join(temp, 'artifact');
    const before = await reviewedSource(source);
    for (const file of Object.keys(before)) {
      await mkdir(dirname(join(frozen, file)), { recursive: true });
      await cp(join(source, file), join(frozen, file));
    }
    if (
      JSON.stringify(before) !== JSON.stringify(await reviewedSource(source)) ||
      JSON.stringify(before) !== JSON.stringify(await reviewedSource(frozen))
    )
      throw Error('Source changed during freeze; retry with a stable candidate');
    const rootPackage = await json(join(frozen, 'package.json'));
    const version = await new Promise((res, rej) => {
      const c = spawn(pnpmExecutable, ['--version'], {
        cwd: frozen,
        stdio: ['ignore', 'pipe', 'pipe'],
      });
      let s = '';
      c.stdout.on('data', (b) => {
        s += b;
      });
      c.on('error', rej);
      c.on('exit', (code) =>
        code === 0 ? res(s.trim()) : rej(Error('pnpm version check failed')),
      );
    });
    if (rootPackage.packageManager !== 'pnpm@' + version)
      throw Error(
        'Task Hub must build with its independent pinned pnpm version: ' +
          rootPackage.packageManager,
      );
    await pnpm(['install', '--frozen-lockfile'], frozen, join(temp, 'install.log'), pnpmExecutable);
    // Install needs devDependencies; the shipped web build must use React's
    // production transform, which omits JSX debug filenames and local paths.
    await pnpm(['build'], frozen, join(temp, 'build.log'), pnpmExecutable, 'production');
    await mkdir(artifact, { recursive: true });
    await pnpm(
      ['--filter', '@task-hub/api', 'deploy', '--legacy', '--prod', join(artifact, 'runtime')],
      frozen,
      join(temp, 'deploy.log'),
      pnpmExecutable,
    );
    // pnpm legacy deploy includes an unused hoisted self-reference back into the
    // build workspace and machine-local package-manager metadata. Neither is a
    // runtime dependency. Preserve only the relocatable production closure.
    await rm(join(artifact, 'runtime/node_modules/.pnpm/node_modules/@task-hub/api'), {
      force: true,
    });
    const scrub = async (directory) => {
      for (const entry of await readdir(directory, { withFileTypes: true })) {
        const path = join(directory, entry.name);
        if (
          ['.bin', '.modules.yaml', '.pnpm-workspace-state-v1.json'].includes(entry.name) ||
          path === join(artifact, 'runtime/node_modules/.pnpm/lock.yaml')
        )
          await rm(path, { recursive: true, force: true });
        else if (entry.isDirectory()) await scrub(path);
      }
    };
    await scrub(join(artifact, 'runtime/node_modules'));
    await cp(join(frozen, 'apps/web/dist'), join(artifact, 'web'), { recursive: true });
    for (const file of Object.keys(before)) {
      await mkdir(dirname(join(artifact, 'source', file)), { recursive: true });
      await cp(join(frozen, file), join(artifact, 'source', file));
    }
    await mkdir(join(artifact, 'tools'));
    for (const name of ['task-hub-core.mjs', 'task-hub-runtime.mjs', 'task-hub-service.mjs'])
      await cp(
        join(repository, 'skills/codex-host-connect/scripts', name),
        join(artifact, 'tools', name),
      );
    const network = await networkDependencies(repository, join(artifact, 'network'));
    const businessCrypto = await buildBusinessCrypto({
      repository,
      destination: join(artifact, 'business-crypto'),
    });
    const files = await fileInventory(artifact);
    const manifest = {
      format: 1,
      contractVersion: '1',
      sourceSha256: hash(JSON.stringify(before)),
      payloadSha256: hash(JSON.stringify(files)),
      files,
      sourcePackageManager: rootPackage.packageManager,
      workspaceVersion: rootPackage.version,
      lockSha256: before['pnpm-lock.yaml'],
      node: '>=24 <25',
      primaryPlatforms: ['darwin'],
      executorPlatforms: ['darwin', 'win32'],
      architecture: process.arch,
      networkDependencies: network,
      businessCrypto: {
        format: businessCrypto.format,
        sha256: businessCrypto.sha256,
        sourceSha256: businessCrypto.sourceSha256,
        manifest: 'business-crypto/manifest.json',
      },
      entries: {
        api: 'runtime/dist/main.js',
        web: 'web',
        migrations: 'runtime/migrations',
        businessCrypto: 'business-crypto/business-crypto.cjs',
      },
      acceptance: 'build-only; real installation and business acceptance reported separately',
    };
    await writeFile(join(artifact, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
    await verifyBundle(artifact);
    // Replacing a published candidate implicitly is forbidden. Explicitly choose
    // a new output directory for each frozen candidate.
    await cp(artifact, output, {
      recursive: true,
      errorOnExist: true,
      force: false,
      verbatimSymlinks: true,
    });
    await verifyBundle(output);
    success = true;
    return {
      output,
      sourceSha256: manifest.sourceSha256,
      payloadSha256: manifest.payloadSha256,
      sourcePackageManager: manifest.sourcePackageManager,
    };
  } catch (error) {
    // Preserve the frozen input and private diagnostics for an actual failure.
    error.message += '; frozen input and diagnostics retained at ' + temp;
    throw error;
  } finally {
    // Success leaves an immutable artifact; failures remain available privately.
    if (success) await rm(temp, { recursive: true, force: true });
  }
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const args = process.argv.slice(2),
    repository = fileURLToPath(new URL('..', import.meta.url));
  const option = (name) => {
    const at = args.indexOf('--' + name);
    return at < 0 ? undefined : args[at + 1];
  };
  if (!option('source') || !option('output'))
    throw Error('Use --source <approved source> --output <new frozen artifact directory>');
  console.log(
    JSON.stringify(
      await buildTaskHub({
        source: option('source'),
        output: option('output'),
        repository,
        pnpmExecutable: option('pnpm') ?? 'pnpm',
      }),
    ),
  );
}
