// Test-only loader. No product module, helper, PID policy or dist file is edited.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { lstatSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';
import {
  protectWindowsStateDirectory,
  protectWindowsStateFile,
  windowsPowerShellEnvironment,
} from '../../packages/codex-adapter/dist/desktop-platform.js';
import { withWindowsProcessCreation } from '../../packages/codex-adapter/dist/windows-process.js';

const roots = new Map();
let readRange;
let childExitUnknown = false;
const rootIdentity = (path) => {
  const s = lstatSync(path, { bigint: true });
  assert(s.isDirectory() && !s.isSymbolicLink(), 'owned synthetic log directory required');
  return [s.dev, s.ino, s.birthtimeNs].join(':');
};

// Called only by fixture(t), immediately after its real mkdtemp/private mkdirs.
// Registration ends in that same fixture's t.after, after closing its bridges.
export function registerSyntheticLogRoot(fixtureRoot, logRoot) {
  assert.equal(realpathSync.native(fixtureRoot), fixtureRoot);
  assert.equal(realpathSync.native(logRoot), logRoot);
  assert.equal(dirname(logRoot), fixtureRoot);
  assert(!roots.has(logRoot), 'synthetic root already registered');
  const entry = { identity: rootIdentity(logRoot), calls: [] };
  roots.set(logRoot, entry);
  return {
    unregister() {
      assert.equal(roots.get(logRoot), entry);
      assert(
        !childExitUnknown,
        `preserved synthetic fixture after unknown child exit: ${fixtureRoot}`,
      );
      roots.delete(logRoot);
    },
    takeCalls() {
      return entry.calls.splice(0);
    },
  };
}

// Signature matches the product export, but _syntheticDesktopPid is deliberately
// unused: kernel range evidence is NOT original MSIX desktop identity evidence.
export function syntheticWindowsDesktopLogRangeSnapshot(
  root,
  path,
  offset,
  count,
  _syntheticDesktopPid,
) {
  const entry = roots.get(root);
  assert(entry, 'refusing a log root outside live synthetic fixtures');
  assert.equal(rootIdentity(root), entry.identity);
  assert.equal(realpathSync.native(root), root);
  assert(isAbsolute(path) && resolve(path) === path);
  const child = relative(root, path);
  assert(!isAbsolute(child) && child !== '..' && !child.startsWith('..' + sep));
  assert(Number.isSafeInteger(offset) && offset >= 0);
  assert(Number.isSafeInteger(count) && count >= 0 && count <= 1048576);
  assert(readRange, 'synthetic native range harness has not been loaded');
  const snapshot = readRange(root, path, offset, count);
  entry.calls.push({ path, offset, count, bytes: snapshot.bytes.length, size: snapshot.size });
  return snapshot;
}

export async function loadSyntheticWindowsBridge() {
  assert.equal(process.platform, 'win32');
  assert.equal(readRange, undefined, 'one synthetic bridge loader per test process');
  const directory = realpathSync.native(
    mkdtempSync(join(tmpdir(), 'native-bridge-synthetic-range-')),
  );
  const close = () => {
    assert.equal(roots.size, 0, 'live synthetic fixture registrations remain');
    assert(!childExitUnknown, `preserved synthetic harness after unknown child exit: ${directory}`);
    readRange = undefined;
    rmSync(directory, { recursive: true });
  };
  try {
    protectWindowsStateDirectory(directory);
    const write = (name, bytes) => {
      const path = join(directory, name);
      writeFileSync(path, bytes, { flag: 'wx', mode: 0o600 });
      protectWindowsStateFile(path);
      return path;
    };
    const execute = (file, args, milliseconds) => {
      assert(!childExitUnknown, 'previous synthetic child exit remains unknown');
      childExitUnknown = true;
      const result = withWindowsProcessCreation(milliseconds, (timeout) =>
        spawnSync(file, args, {
          timeout,
          windowsHide: true,
          env: windowsPowerShellEnvironment(),
          maxBuffer: 8192,
          stdio: ['ignore', 'pipe', 'pipe'],
        }),
      );
      childExitUnknown = !!result.error || result.signal !== null || result.status === null;
      assert(!childExitUnknown, 'synthetic child exit is unknown; not a native boundary rejection');
      assert.equal(
        result.status,
        0,
        'synthetic harness failed: ' + result.stderr.toString().slice(0, 1024),
      );
      return result.stdout.toString().trim();
    };
    let executable, snapshotPath;
    const compile = () => {
      const sources = [
        [
          'trace-state.cs',
          new URL('../../packages/codex-adapter/windows/trace-state.cs', import.meta.url),
        ],
        [
          'trace-process.cs',
          new URL('../../packages/codex-adapter/windows/trace-process.cs', import.meta.url),
        ],
        [
          'synthetic-main.cs',
          new URL('./desktop-native-sample-synthetic-log-range.cs', import.meta.url),
        ],
      ].map(([name, url]) => {
        const bytes = readFileSync(url),
          path = write(name, bytes);
        assert(readFileSync(path).equals(bytes), 'native source copy must be byte-for-byte');
        return path;
      });
      const output = join(directory, 'synthetic-log-range.exe');
      const quote = (value) => "'" + value.replaceAll("'", "''") + "'";
      const command = `$ErrorActionPreference='Stop';
$provider=New-Object Microsoft.CSharp.CSharpCodeProvider;
$parameters=New-Object System.CodeDom.Compiler.CompilerParameters;
$parameters.TempFiles=New-Object System.CodeDom.Compiler.TempFileCollection -ArgumentList @(${quote(directory)},$false);
$parameters.GenerateExecutable=$true;
$parameters.OutputAssembly=${quote(output)};
$parameters.CompilerOptions='/nologo /platform:x64 /main:SyntheticNativeSampleLogRange';
[void]$parameters.ReferencedAssemblies.Add('System.dll');
[void]$parameters.ReferencedAssemblies.Add('System.Core.dll');
[void]$parameters.ReferencedAssemblies.Add('System.Web.Extensions.dll');
try {
  $result=$provider.CompileAssemblyFromFile($parameters,[string[]]@(${sources.map(quote).join(',')}));
  if($result.Errors.HasErrors){
    foreach($issue in $result.Errors){[Console]::Error.WriteLine(('synthetic compile {0}:{1}' -f $issue.ErrorNumber,$issue.Line));}
    exit 1;
  }
} finally {$provider.Dispose();}
[Console]::WriteLine('SYNTHETIC_RANGE_COMPILED');`;
      assert.equal(
        execute(
          join(
            process.env.SystemRoot ?? 'C:\\Windows',
            'System32/WindowsPowerShell/v1.0/powershell.exe',
          ),
          ['-NoProfile', '-NonInteractive', '-Command', command],
          30000,
        ),
        'SYNTHETIC_RANGE_COMPILED',
      );
      protectWindowsStateFile(output);
      snapshotPath = write('synthetic-snapshot.json', '');
      executable = output;
    };
    // Fail visibly at loader setup if compilation is unavailable, rather than
    // letting product startup translate a compiler error into a bridge status.
    compile();
    readRange = (root, path, offset, count) => {
      assert.equal(
        execute(executable, [root, path, String(offset), String(count), snapshotPath], 3000),
        'SYNTHETIC_NTFS_RANGE_ONLY',
      );
      const value = JSON.parse(readFileSync(snapshotPath, 'utf8'));
      assert.deepEqual(Object.keys(value).sort(), ['bytes', 'chain', 'identity', 'size']);
      assert.equal(typeof value.identity, 'string');
      assert(Array.isArray(value.chain) && value.chain.every((part) => typeof part === 'string'));
      assert(Number.isSafeInteger(value.size) && value.size >= offset);
      assert.equal(typeof value.bytes, 'string');
      const bytes = Buffer.from(value.bytes, 'base64');
      // Never put log bytes/base64 in assertion failure messages or diagnostics.
      assert(
        bytes.length <= count && bytes.toString('base64') === value.bytes,
        'invalid synthetic range byte encoding or bound',
      );
      return { ...value, bytes };
    };
    const platformURL = new URL(
      '../../packages/codex-adapter/dist/desktop-platform.js',
      import.meta.url,
    ).href;
    const platform = write(
      'synthetic-platform.mjs',
      `export * from ${JSON.stringify(platformURL)};\n` +
        `export { syntheticWindowsDesktopLogRangeSnapshot as windowsDesktopLogRangeSnapshot } from ${JSON.stringify(import.meta.url)};\n`,
    );
    let source = readFileSync(
      new URL('../../packages/codex-adapter/dist/desktop-native-sample-bridge.js', import.meta.url),
      'utf8',
    );
    const replacements = [
      [
        "'@codex-manage/protocol'",
        new URL('../../packages/protocol/dist/index.js', import.meta.url).href,
      ],
      ...[
        'desktop-native-sample-capture',
        'desktop-native-sample-envelope',
        'desktop-native-sample-diagnostics',
        'desktop-native-sample-platform',
        'desktop-suggestion-store',
      ].map((name) => [
        `'./${name}.js'`,
        new URL(`../../packages/codex-adapter/dist/${name}.js`, import.meta.url).href,
      ]),
      ["'./desktop-platform.js'", pathToFileURL(platform).href],
    ];
    for (const [from, to] of replacements) {
      assert.equal(source.split(from).length - 1, 1, `exact synthetic bridge replacement: ${from}`);
      source = source.replace(from, JSON.stringify(to));
    }
    const bridgePath = write('synthetic-bridge.mjs', source);
    return { module: await import(pathToFileURL(bridgePath).href), close };
  } catch (error) {
    if (!childExitUnknown) close();
    else
      throw new Error(`synthetic harness retained after unknown child exit: ${directory}`, {
        cause: error,
      });
    throw error;
  }
}
