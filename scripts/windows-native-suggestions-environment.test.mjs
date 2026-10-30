// Source-only delivery. Run later with pnpm exec node --test on the selected executor.
import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  symlinkSync,
  unlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join, win32 } from 'node:path';
import { fileURLToPath } from 'node:url';
import { windowsPowerShellEnvironment } from '../packages/codex-adapter/dist/desktop-platform.js';
import {
  nativeSuggestionsSource,
  windowsNativeSuggestionsNativeOperation,
  validateNativeSuggestionsConfig,
  windowsNativeSuggestionsEnvironment,
} from './windows-native-suggestions-environment.mjs';

const digest = 'a'.repeat(64);
const state = String.raw`C:\Users\fixture\private native`;
const entry = String.raw`C:\Package\scripts\desktop-native-suggestions-runtime.mjs`;
const config = () => ({
  version: 1,
  target: {
    agentId: 'codex',
    hostId: 'host-fixture',
    workspaceId: 'workspace-fixture',
    runtimeId: 'runtime-fixture',
  },
  traceRoot: String.raw`C:\Users\fixture\existing traces`,
  applicationPath: String.raw`C:\Program Files\Codex\Codex.exe`,
  executable: String.raw`C:\Program Files\Codex\resources\codex.exe`,
  node: String.raw`C:\Program Files\nodejs\node.exe`,
  entry,
  logDirectory: String.raw`C:\Users\fixture\original logs`,
  shimPath: win32.join(state, 'launcher.exe'),
});
const clone = (value) => JSON.parse(JSON.stringify(value));
function fixture(overrides = {}) {
  const data = { manifest: null, config: null };
  const environment = {
    user: { value: null, kind: null },
    machine: null,
    process: null,
    config: null,
    ...overrides,
  };
  const calls = [];
  let compileError, validateError, beforeRegistry;
  const run = (action, request) => {
    calls.push({ action, request: clone(request) });
    if (action === 'read') return clone(data);
    if (action === 'environment') return clone(environment);
    if (action === 'prepare') return { ok: true };
    if (action === 'publish') {
      data[request.name === 'config.json' ? 'config' : 'manifest'] = clone(request.value);
      return { ok: true };
    }
    if (action === 'compile') {
      if (compileError) throw Object.assign(new Error(compileError), { code: compileError });
      return { sourceSha256: request.sourceSha256, shimSha256: digest };
    }
    if (action === 'validate') {
      if (validateError) throw Object.assign(new Error(validateError), { code: validateError });
      return { ok: true };
    }
    if (action === 'registry') {
      beforeRegistry?.(environment);
      if (JSON.stringify(environment.user) !== JSON.stringify(request.expected))
        throw Object.assign(new Error('WINDOWS_NATIVE_OVERRIDE_CONFLICT'), {
          code: 'WINDOWS_NATIVE_OVERRIDE_CONFLICT',
        });
      environment.user = clone(request.value);
      return clone(environment);
    }
    throw new Error('Unexpected fixture operation');
  };
  const tool = windowsNativeSuggestionsEnvironment({ state, platform: 'win32', entry, run });
  return {
    tool,
    data,
    environment,
    calls,
    failCompile(code) {
      compileError = code;
    },
    failValidate(code) {
      validateError = code;
    },
    beforeRegistry(callback) {
      beforeRegistry = callback;
    },
  };
}

test('install records source/exe separately, fills digest, is idempotent, and disables only its HKCU value', () => {
  const f = fixture();
  const installed = f.tool.install(config());
  assert.equal(installed.configured, true);
  assert.equal(installed.currentInstanceVerified, false);
  assert.equal(installed.requiresFreshLauncher, true);
  assert.equal(installed.desktopRestarted, false);
  assert.equal(f.data.config.shimSha256, digest);
  assert.match(f.data.manifest.sourceSha256, /^[a-f0-9]{64}$/u);
  assert.notEqual(f.data.manifest.sourceSha256, digest);
  assert.equal(f.data.config.sourceSha256, undefined);
  assert.deepEqual(f.environment.user, { value: config().shimPath, kind: 'String' });
  f.tool.install(Object.fromEntries(Object.entries(config()).reverse()));
  const historical = clone(f.data.config);
  f.tool.install({
    ...config(),
    applicationPath: String.raw`C:\New Store version\Codex.exe`,
    executable: String.raw`C:\New Store version\resources\codex.exe`,
  });
  assert.deepEqual(f.data.config, historical);
  assert.equal(f.calls.filter(({ action }) => action === 'compile').length, 1);
  const saved = clone(f.data.config);
  assert.equal(f.tool.disable().configured, false);
  assert.deepEqual(f.environment.user, { value: null, kind: null });
  assert.deepEqual(f.data.config, saved);
  assert.equal(f.tool.install(config()).configured, true);
  assert(
    f.calls.every(({ action }) =>
      ['read', 'environment', 'prepare', 'publish', 'compile', 'validate', 'registry'].includes(
        action,
      ),
    ),
  );
});

test('explicit user/process/machine CLI overrides and a conflicting config environment are preserved', () => {
  for (const override of [
    { user: { value: 'original.exe', kind: 'String' } },
    { user: { value: '%ORIGINAL%\\codex.exe', kind: 'ExpandString' } },
    { process: String.raw`C:\Other\codex.exe` },
    { machine: String.raw`C:\Other\codex.exe` },
    { process: config().shimPath }, // An unmanaged value is not ownership proof.
    { config: String.raw`C:\Other\config.json` },
  ]) {
    const f = fixture(override),
      before = clone(f.environment);
    assert.throws(() => f.tool.install(config()), { code: 'WINDOWS_NATIVE_OVERRIDE_CONFLICT' });
    assert.deepEqual(f.environment, before);
    assert.equal(
      f.calls.some(({ action }) => ['prepare', 'compile', 'publish', 'registry'].includes(action)),
      false,
    );
  }
});

test('later overrides fail CAS and are never restored over on disable', () => {
  const f = fixture();
  f.beforeRegistry((environment) => {
    environment.user = { value: 'later.exe', kind: 'String' };
  });
  assert.throws(() => f.tool.install(config()), { code: 'WINDOWS_NATIVE_OVERRIDE_CONFLICT' });
  assert.equal(f.tool.status().configured, false);
  assert.throws(() => f.tool.disable(), { code: 'WINDOWS_NATIVE_OVERRIDE_CONFLICT' });
  assert.equal(f.environment.user.value, 'later.exe');
});

test('invalid state roots are rejected before touching the native filesystem', () => {
  for (const root of [
    String.raw`\\server\private`,
    String.raw`C:\private\..\other`,
    'C:\\private.',
    'C:\\private:stream',
  ]) {
    assert.throws(
      () =>
        windowsNativeSuggestionsEnvironment({
          state: root,
          platform: 'win32',
          run() {
            throw new Error('must not be called');
          },
        }),
      { code: 'WINDOWS_NATIVE_PLATFORM_UNAVAILABLE' },
    );
  }
});

test('missing compiler never configures an environment; preparing intent permits a later retry', () => {
  const f = fixture();
  f.failCompile('WINDOWS_NATIVE_COMPILER_UNAVAILABLE');
  const result = f.tool.install(config());
  assert.equal(result.configured, false);
  assert.equal(result.code, 'WINDOWS_NATIVE_COMPILER_UNAVAILABLE');
  assert.equal(f.data.manifest.phase, 'preparing');
  assert.equal(f.data.manifest.enabled, false);
  assert.equal(
    f.calls.some(({ action }) => action === 'registry'),
    false,
  );
  f.failCompile(undefined);
  assert.equal(f.tool.install(config()).configured, true);
});

test('trust/digest failures and an older or edited manifest do not acquire environment ownership', () => {
  const f = fixture();
  f.failValidate('WINDOWS_NATIVE_SHIM_CHANGED');
  assert.throws(() => f.tool.install(config()), { code: 'WINDOWS_NATIVE_SHIM_CHANGED' });
  assert.equal(
    f.calls.some(({ action }) => action === 'registry'),
    false,
  );
  const old = fixture();
  old.data.manifest = {
    version: 1,
    enabled: true,
    previous: { value: 'old-wrapper.cmd', kind: 'String' },
  };
  assert.throws(() => old.tool.install(config()), { code: 'WINDOWS_NATIVE_MANIFEST_INVALID' });
  const changed = fixture();
  changed.tool.install(config());
  changed.data.config.target.hostId = 'other-host';
  assert.equal(changed.tool.status().code, 'WINDOWS_NATIVE_MANIFEST_INVALID');
});

test('config denies unknown/credential keys, unknown log location, noncanonical paths and extra launch arguments', () => {
  const valid = { ...config(), shimSha256: digest };
  assert.equal(validateNativeSuggestionsConfig(valid), valid);
  for (const change of [
    { token: 'never-allowed' },
    { logDirectory: undefined },
    { logDirectory: '' },
    { entry: String.raw`C:\Package\other.mjs` },
    { node: String.raw`C:\Windows\cmd.exe` },
    { executable: String.raw`C:\Other\codex.cmd` },
    { shimPath: String.raw`C:\Other\launcher.cmd` },
    { traceRoot: String.raw`C:\Users\fixture\..\traces` },
    { traceRoot: String.raw`\\server\traces` },
    { traceRoot: String.raw`C:\traces:stream` },
    { traceRoot: 'C:\\traces.\\data' },
    { shimSha256: 'bad' },
    { args: ['app-server'] },
    { logDirectory: 'C:\\' + 'x'.repeat(17000) },
    { target: { ...valid.target, token: 'never-allowed' } },
  ])
    assert.throws(() => validateNativeSuggestionsConfig({ ...valid, ...change }), {
      code: 'WINDOWS_NATIVE_CONFIG_INVALID',
    });
  const f = fixture();
  assert.equal(f.tool.status().logDirectoryKnown, false);
  assert.throws(() => f.tool.install({ ...config(), shimSha256: 'b'.repeat(64) }), {
    code: 'WINDOWS_NATIVE_SHIM_CHANGED',
  });
});

// Native boundary checks compile the unchanged package source and run ONLY a local
// byte/argv fixture Node entry. No Codex, models, desktop restart or registry writes.
test(
  'Windows PS5 compilation, CRT argv, binary stdio, EOF, nonzero exits and private process rejection',
  { skip: process.platform !== 'win32', timeout: 120000 },
  async (t) => {
    const directory = mkdtempSync(join(tmpdir(), 'agent-hub-native-fixture-'));
    t.after(() => rmSync(directory, { recursive: true }));
    const powershell = join(
      process.env.SystemRoot,
      'System32',
      'WindowsPowerShell',
      'v1.0',
      'powershell.exe',
    );
    const env = {
      ...process.env,
      AH_TEST_DIRECTORY: directory,
      AH_TEST_SOURCE: nativeSuggestionsSource,
    };
    // Fixed test code; all paths are data in test-only environment variables.
    const setup = String.raw`
$ErrorActionPreference='Stop'
try {
 $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
 function Protect($path,$directory) {
  $acl=if($directory){[Security.AccessControl.DirectorySecurity]::new()}else{[Security.AccessControl.FileSecurity]::new()}
  $acl.SetOwner($sid);$acl.SetAccessRuleProtection($true,$false)
  $rule=if($directory){[Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow')}else{[Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','Allow')}
  $acl.AddAccessRule($rule);Set-Acl -LiteralPath $path -AclObject $acl
 }
 Protect $env:AH_TEST_DIRECTORY $true
 $exe=Join-Path $env:AH_TEST_DIRECTORY 'launcher.exe'
 Add-Type -Path $env:AH_TEST_SOURCE -OutputAssembly $exe -OutputType ConsoleApplication -ReferencedAssemblies @('System.dll','System.Core.dll','System.Web.Extensions.dll')
 Protect $exe $false
 [Console]::Out.WriteLine((Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash.ToLowerInvariant())
} catch {[Console]::Error.WriteLine('NATIVE_FIXTURE_COMPILE_FAILED');exit 1}
`;
    const shimSha256 = execFileSync(
      powershell,
      [
        '-NoProfile',
        '-NonInteractive',
        '-EncodedCommand',
        Buffer.from(setup, 'utf16le').toString('base64'),
      ],
      { env, encoding: 'utf8', timeout: 60000, maxBuffer: 16384 },
    ).trim();
    assert.match(shimSha256, /^[a-f0-9]{64}$/u);
    const runtime = join(directory, 'desktop-native-suggestions-runtime.mjs');
    writeFileSync(
      runtime,
      `
if (process.argv[2] === 'early' || process.argv[2] === 'zero') {
 process.stdout.write(process.argv[2]); process.exitCode = process.argv[2] === 'early' ? 23 : 0;
}
else {
 process.stdout.write(Buffer.from(JSON.stringify(process.argv.slice(2)) + '\\n'));
 process.stderr.write(Buffer.from([255, 0, 128, 10]));
 for await (const chunk of process.stdin) process.stdout.write(chunk);
 process.exitCode = 37;
}
`,
    );
    // These two placeholders are checked as files but never executed by the fixture.
    const original = join(directory, 'codex.exe'),
      desktop = join(directory, 'desktop.exe');
    writeFileSync(original, 'not an executor');
    writeFileSync(desktop, 'not a desktop');
    const cfg = {
      ...config(),
      traceRoot: directory,
      applicationPath: desktop,
      executable: original,
      node: process.execPath,
      entry: runtime,
      logDirectory: directory,
      shimPath: join(directory, 'launcher.exe'),
      shimSha256,
    };
    const configPath = join(directory, 'config.json');
    writeFileSync(configPath, JSON.stringify(cfg));
    // Inherit only from this already-private test directory, then protect files explicitly.
    execFileSync(
      powershell,
      [
        '-NoProfile',
        '-NonInteractive',
        '-EncodedCommand',
        Buffer.from(
          String.raw`
$ErrorActionPreference='Stop'
foreach($file in Get-ChildItem -LiteralPath $env:AH_TEST_DIRECTORY -File) {
 $acl=Get-Acl -LiteralPath $file.FullName;$acl.SetAccessRuleProtection($true,$true);Set-Acl -LiteralPath $file.FullName -AclObject $acl
}
`,
          'utf16le',
        ).toString('base64'),
      ],
      { env, timeout: 20000, maxBuffer: 16384 },
    );
    const childEnv = { ...process.env, AGENT_HUB_NATIVE_SUGGESTIONS_CONFIG: configPath };
    delete childEnv.NODE_OPTIONS;
    delete childEnv.NODE_PATH;
    const collect = (args, input, closeInput = true, extraEnv = {}) =>
      new Promise((resolvePromise, reject) => {
        const child = spawn(cfg.shimPath, args, {
          env: { ...childEnv, ...extraEnv },
          windowsHide: true,
          stdio: 'pipe',
        });
        const out = [],
          err = [];
        const deadline = setTimeout(() => {
          child.kill();
          reject(new Error('Native fixture deadline'));
        }, 15000);
        child.on('error', (error) => {
          clearTimeout(deadline);
          reject(error);
        });
        child.stdin.on('error', () => {});
        child.stdout.on('data', (chunk) => out.push(chunk));
        child.stderr.on('data', (chunk) => err.push(chunk));
        child.on('close', (code) => {
          clearTimeout(deadline);
          resolvePromise({ code, out: Buffer.concat(out), err: Buffer.concat(err) });
        });
        if (closeInput) child.stdin.end(input);
      });
    const args = [
      '',
      'one two',
      'quote"inside',
      '\\',
      'ends with\\',
      'x\\\\"y',
      '中文🙂',
      '--eval',
      'a&b|c',
      'two  spaces',
      'line\nnext',
    ];
    const input = Buffer.from(Array.from({ length: 180000 }, (_, i) => i % 256));
    const result = await collect(args, input);
    assert.equal(result.code, 37);
    const line = result.out.indexOf(10);
    assert.deepEqual(JSON.parse(result.out.subarray(0, line)), args);
    assert.deepEqual(result.out.subarray(line + 1), input);
    assert.deepEqual(result.err, Buffer.from([255, 0, 128, 10]));
    const early = await collect(['early'], undefined, false);
    assert.equal(early.code, 23);
    assert.equal(early.out.toString(), 'early');
    const defaultConfig = await collect(['zero'], Buffer.alloc(0), true, {
      AGENT_HUB_NATIVE_SUGGESTIONS_CONFIG: undefined,
    });
    assert.equal(defaultConfig.code, 0);
    assert.equal(defaultConfig.out.toString(), 'zero');
    // Store removed the old installation; startup must still reach the fixed Node
    // entry. Runtime (outside this fixture) resolves CLI from the current pipe owner.
    const upgradedConfig = {
      ...cfg,
      applicationPath: join(directory, 'removed version', 'Codex.exe'),
      executable: join(directory, 'removed version', 'resources', 'codex.exe'),
    };
    writeFileSync(configPath, JSON.stringify(upgradedConfig));
    assert.equal((await collect(['zero'], Buffer.alloc(0))).code, 0);
    const conflict = await collect([], Buffer.alloc(0), true, {
      AGENT_HUB_NATIVE_SUGGESTIONS_CONFIG: join(directory, 'other.json'),
    });
    assert.equal(conflict.code, 125);
    assert.equal(conflict.err.toString().trim(), 'WINDOWS_NATIVE_LAUNCHER_REJECTED');
    // Exact real Node PID cannot pass a missing shim/Electron parent chain.
    const helper = fileURLToPath(
      new URL(
        '../packages/codex-adapter/windows/desktop-native-sample-process.ps1',
        import.meta.url,
      ),
    );
    assert.throws(
      () =>
        execFileSync(
          powershell,
          [
            '-NoProfile',
            '-NonInteractive',
            '-File',
            helper,
            '-ProducerPid',
            String(process.pid),
            '-Config',
            configPath,
          ],
          { encoding: 'utf8', timeout: 20000, maxBuffer: 16384, stdio: 'pipe' },
        ),
      (error) =>
        error.stdout === '' &&
        /^WINDOWS_NATIVE_(PROCESS_REJECTED|PROCESS_UNAVAILABLE)\r?\n$/u.test(error.stderr),
    );
    // Mocked OS evidence exercises the helper's Store update and trust boundaries;
    // this is NOT a signed-desktop or real-process acceptance result.
    const currentStore = join(directory, 'current Store version');
    mkdirSync(currentStore);
    const currentDesktop = join(currentStore, 'Codex.exe');
    writeFileSync(currentDesktop, 'fixture Store payload');
    const processHarness = String.raw`
$ErrorActionPreference='Stop'
$script:at=[DateTime]::UtcNow.AddMinutes(-1)
$script:seen=@{};$script:last=@{}
$script:session=[Diagnostics.Process]::GetCurrentProcess().SessionId
$script:sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
function Get-Process {
 param([int]$Id)
 $which=switch($Id){501{'node'}502{'shim'}503{'desktop'}default{throw 'fixture pid'}}
 $script:seen[$Id]=[int]$script:seen[$Id]+1
 $path=switch($which){'node'{$env:AH_TEST_NODE}'shim'{$env:AH_TEST_SHIM}'desktop'{$env:AH_TEST_DESKTOP}}
 $parent=switch($which){'node'{502}'shim'{503}'desktop'{50}}
 $time=$script:at.AddSeconds($(switch($which){'node'{2}'shim'{1}'desktop'{0}}))
 if($env:AH_TEST_MODE -eq 'pid-reuse' -and $Id -eq 503 -and $script:seen[$Id] -gt 1){$time=$time.AddSeconds(10)}
 if($env:AH_TEST_MODE -eq 'wrong-node' -and $Id -eq 501){$path=$env:AH_TEST_DESKTOP}
 if($env:AH_TEST_MODE -eq 'wrong-shim' -and $Id -eq 502){$path=$env:AH_TEST_DESKTOP}
 $session=if($env:AH_TEST_MODE -eq 'wrong-session'){$script:session+1}else{$script:session}
 $value=[pscustomobject]@{Path=$path;StartTime=$time;SessionId=$session;HasExited=$false;Handle=[IntPtr]1;parent=$parent}
 $value | Add-Member -MemberType ScriptMethod -Name Dispose -Value {}
 $script:last[$Id]=$value
 return $value
}
function Get-CimInstance {
 param($ClassName,$Filter,$Property)
 $id=[int]($Filter -replace '^ProcessId=','');$p=$script:last[$id]
 [pscustomobject]@{ProcessId=$id;ParentProcessId=$p.parent;ExecutablePath=$p.Path;CreationDate=$p.StartTime;SessionId=$p.SessionId}
}
function Invoke-CimMethod {
 param($InputObject,$MethodName)
 @{ReturnValue=0;Sid=$(if($env:AH_TEST_MODE -eq 'wrong-owner'){'S-1-0-0'}else{$script:sid})}
}
function Get-AuthenticodeSignature {
 param($LiteralPath)
 # Valid OpenAI signature alone cannot turn another product into Codex.
 @{Status=$(if($env:AH_TEST_MODE -eq 'foreign-product'){'Valid'}else{'NotSigned'});SignerCertificate=@{Subject='CN=OpenAI, O=OpenAI'}}
}
function Get-AppxPackage {
 @{SignatureKind='Store';Name=$(if($env:AH_TEST_MODE -eq 'foreign-product'){'Other.Agent'}else{'OpenAI.Codex'});
  Publisher=$(if($env:AH_TEST_MODE -eq 'foreign-publisher'){'CN=Other Vendor'}else{'CN=OpenAI, O=OpenAI'});
  InstallLocation=$(if($env:AH_TEST_MODE -eq 'foreign-location'){$env:AH_TEST_OTHER_LOCATION}else{[IO.Path]::GetDirectoryName($env:AH_TEST_DESKTOP)})}
}
& $env:AH_TEST_HELPER -ProducerPid 501 -Config $env:AH_TEST_CONFIG
exit $LASTEXITCODE
`;
    const harnessEnv = {
      ...env,
      AH_TEST_NODE: cfg.node,
      AH_TEST_SHIM: cfg.shimPath,
      AH_TEST_DESKTOP: currentDesktop,
      AH_TEST_HELPER: helper,
      AH_TEST_CONFIG: configPath,
      AH_TEST_OTHER_LOCATION: join(directory, 'another product location'),
    };
    const inspectFixture = (mode) =>
      execFileSync(
        powershell,
        [
          '-NoProfile',
          '-NonInteractive',
          '-EncodedCommand',
          Buffer.from(processHarness, 'utf16le').toString('base64'),
        ],
        {
          env: { ...harnessEnv, AH_TEST_MODE: mode },
          encoding: 'utf8',
          timeout: 20000,
          maxBuffer: 16384,
          stdio: 'pipe',
        },
      );
    const verified = JSON.parse(inspectFixture('store-upgrade'));
    assert.deepEqual(Object.keys(verified).sort(), [
      'desktopPid',
      'desktopStartedAt',
      'pid',
      'wrapperStartedAt',
    ]);
    assert.equal(verified.pid, 501);
    assert.equal(verified.desktopPid, 503);
    assert(verified.desktopStartedAt < verified.wrapperStartedAt);
    for (const mode of [
      'wrong-node',
      'wrong-shim',
      'wrong-session',
      'wrong-owner',
      'pid-reuse',
      'foreign-product',
      'foreign-publisher',
      'foreign-location',
    ]) {
      assert.throws(
        () => inspectFixture(mode),
        (error) =>
          error.stdout === '' &&
          /^WINDOWS_NATIVE_(PROCESS_REJECTED|PROCESS_CHANGED|DESKTOP_UNTRUSTED)\r?\n$/u.test(
            error.stderr,
          ),
      );
    }
    const redirect = join(directory, 'redirected');
    symlinkSync(directory, redirect, 'junction');
    try {
      assert.throws(
        () =>
          execFileSync(
            powershell,
            [
              '-NoProfile',
              '-NonInteractive',
              '-File',
              helper,
              '-ProducerPid',
              String(process.pid),
              '-Config',
              join(redirect, 'config.json'),
            ],
            { encoding: 'utf8', timeout: 20000, maxBuffer: 16384, stdio: 'pipe' },
          ),
        (error) =>
          error.stdout === '' && error.stderr.trim() === 'WINDOWS_NATIVE_PRIVATE_STATE_REJECTED',
      );
    } finally {
      unlinkSync(redirect);
    }
    // World-readable config is rejected even with unchanged contents and owner.
    const shareConfig = String.raw`
$ErrorActionPreference='Stop'
$acl=Get-Acl -LiteralPath $env:AH_TEST_CONFIG
$world=[Security.Principal.SecurityIdentifier]::new('S-1-1-0')
$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($world,'Read','Allow'))
Set-Acl -LiteralPath $env:AH_TEST_CONFIG -AclObject $acl
`;
    execFileSync(
      powershell,
      [
        '-NoProfile',
        '-NonInteractive',
        '-EncodedCommand',
        Buffer.from(shareConfig, 'utf16le').toString('base64'),
      ],
      { env: harnessEnv, timeout: 20000, maxBuffer: 16384 },
    );
    assert.throws(
      () => inspectFixture('store-upgrade'),
      (error) =>
        error.stdout === '' && error.stderr.trim() === 'WINDOWS_NATIVE_PRIVATE_STATE_REJECTED',
    );
    const unshareConfig = String.raw`
$ErrorActionPreference='Stop'
$acl=Get-Acl -LiteralPath $env:AH_TEST_CONFIG
$acl.PurgeAccessRules([Security.Principal.SecurityIdentifier]::new('S-1-1-0'))
Set-Acl -LiteralPath $env:AH_TEST_CONFIG -AclObject $acl
`;
    execFileSync(
      powershell,
      [
        '-NoProfile',
        '-NonInteractive',
        '-EncodedCommand',
        Buffer.from(unshareConfig, 'utf16le').toString('base64'),
      ],
      { env: harnessEnv, timeout: 20000, maxBuffer: 16384 },
    );
    // A private config's size is bounded before JSON parsing, without echoing its bytes.
    writeFileSync(configPath, 'secret-fixture-marker'.repeat(1000));
    assert.throws(
      () =>
        execFileSync(
          powershell,
          [
            '-NoProfile',
            '-NonInteractive',
            '-File',
            helper,
            '-ProducerPid',
            String(process.pid),
            '-Config',
            configPath,
          ],
          { encoding: 'utf8', timeout: 20000, maxBuffer: 16384, stdio: 'pipe' },
        ),
      (error) => error.stdout === '' && error.stderr.trim() === 'WINDOWS_NATIVE_CONFIG_INVALID',
    );
  },
);

test(
  'Windows fixed filesystem operations protect newly created objects and refuse existing foreign state',
  { skip: process.platform !== 'win32', timeout: 120000 },
  () => {
    const root = mkdtempSync(join(tmpdir(), 'agent-hub-owner-regression-'));
    const ps = join(
      process.env.SystemRoot,
      'System32',
      'WindowsPowerShell',
      'v1.0',
      'powershell.exe',
    );
    const runScript = (script, path) =>
      execFileSync(
        ps,
        [
          '-NoProfile',
          '-NonInteractive',
          '-EncodedCommand',
          Buffer.from(script, 'utf16le').toString('base64'),
        ],
        {
          env: { ...windowsPowerShellEnvironment(), AH_OWNER_TEST_PATH: path },
          encoding: 'utf8',
          stdio: 'pipe',
        },
      ).trim();
    const protect = String.raw`$s=[Security.Principal.WindowsIdentity]::GetCurrent().User
$a=[Security.AccessControl.DirectorySecurity]::new();$a.SetOwner($s);$a.SetAccessRuleProtection($true,$false)
$a.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($s,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
Set-Acl -LiteralPath $env:AH_OWNER_TEST_PATH -AclObject $a`;
    const inspect = String.raw`$a=Get-Acl -LiteralPath $env:AH_OWNER_TEST_PATH
$s=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$r=@($a.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]) | Where-Object {$_.AccessControlType -eq 'Allow'})
[Console]::Out.WriteLine((@{owner=$a.GetOwner([Security.Principal.SecurityIdentifier]).Value;current=$s;protected=$a.AreAccessRulesProtected;allows=@($r | ForEach-Object {$_.IdentityReference.Value})} | ConvertTo-Json -Compress))`;
    const privateObject = (path) => {
      const a = JSON.parse(runScript(inspect, path));
      assert.equal(a.owner, a.current);
      assert.equal(a.protected, true);
      assert(a.allows.length > 0);
      assert(a.allows.every((sid) => sid === a.current));
    };
    const call = (action, state, extra = {}) =>
      windowsNativeSuggestionsNativeOperation(action, { state, ...extra });
    try {
      runScript(protect, root);
      // This regression must exercise the elevated token whose default owner is Administrators.
      assert.equal(
        runScript(
          String.raw`[Console]::Out.WriteLine([Security.Principal.WindowsIdentity]::GetCurrent().Owner.Value)`,
          root,
        ),
        'S-1-5-32-544',
      );
      const state = join(root, 'created', 'state');
      assert.deepEqual(call('prepare', state), { ok: true });
      privateObject(join(root, 'created'));
      privateObject(state);
      assert.deepEqual(
        call('publish', state, { name: 'environment.json', value: { fixture: 1 } }),
        { ok: true },
      );
      assert.deepEqual(call('publish', state, { name: 'config.json', value: { fixture: 2 } }), {
        ok: true,
      });
      assert.deepEqual(call('read', state), { manifest: { fixture: 1 }, config: { fixture: 2 } });
      privateObject(join(state, 'environment.json'));
      privateObject(join(state, 'config.json'));
      const sourceSha256 = createHash('sha256')
        .update(readFileSync(nativeSuggestionsSource))
        .digest('hex');
      const compiled = call('compile', state, { source: nativeSuggestionsSource, sourceSha256 });
      assert.equal(compiled.sourceSha256, sourceSha256);
      assert.match(compiled.shimSha256, /^[a-f0-9]{64}$/);
      privateObject(join(state, 'launcher.exe'));
      const existing = join(root, 'existing');
      mkdirSync(existing);
      execFileSync('icacls', [existing, '/setowner', '*S-1-5-32-544'], { stdio: 'pipe' });
      const before = runScript(inspect, existing);
      assert.equal(JSON.parse(before).owner, 'S-1-5-32-544');
      assert.throws(() => call('prepare', existing), {
        code: 'WINDOWS_NATIVE_PRIVATE_STATE_REJECTED',
      });
      assert.equal(runScript(inspect, existing), before);
      const foreign = join(state, 'config.json');
      execFileSync('icacls', [foreign, '/grant', '*S-1-1-0:R'], { stdio: 'pipe' });
      const foreignBefore = readFileSync(foreign);
      const foreignAcl = runScript(inspect, foreign);
      assert.throws(
        () => call('publish', state, { name: 'config.json', value: { replaced: true } }),
        { code: 'WINDOWS_NATIVE_PRIVATE_STATE_REJECTED' },
      );
      assert.deepEqual(readFileSync(foreign), foreignBefore);
      assert.equal(runScript(inspect, foreign), foreignAcl);
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  },
);
