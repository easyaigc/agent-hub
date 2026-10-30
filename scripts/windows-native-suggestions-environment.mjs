import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { win32, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const nativeSuggestionsVariable = 'CODEX_CLI_PATH';
export const nativeSuggestionsConfigVariable = 'AGENT_HUB_NATIVE_SUGGESTIONS_CONFIG';
export const nativeSuggestionsSource = fileURLToPath(
  new URL('../packages/codex-adapter/windows/desktop-native-sample-launcher.cs', import.meta.url),
);
export const nativeSuggestionsEntry = fileURLToPath(
  new URL('./desktop-native-suggestions-runtime.mjs', import.meta.url),
);
const limit = 16384;
const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');
const stableJson = (value) =>
  JSON.stringify(value, function (_key, item) {
    return item && typeof item === 'object' && !Array.isArray(item)
      ? Object.fromEntries(
          Object.keys(item)
            .sort()
            .map((key) => [key, item[key]]),
        )
      : item;
  });
const failure = (code) => Object.assign(new Error(code), { code });
const samePath = (a, b) =>
  typeof a === 'string' && typeof b === 'string' && a.toLowerCase() === b.toLowerCase();
const keys = (value, expected) =>
  value &&
  typeof value === 'object' &&
  !Array.isArray(value) &&
  Object.keys(value).sort().join('|') === [...expected].sort().join('|');
const pathFields = [
  'traceRoot',
  'applicationPath',
  'executable',
  'node',
  'entry',
  'logDirectory',
  'shimPath',
];
const canonicalPath = (item) =>
  typeof item === 'string' &&
  /^[a-z]:\\/iu.test(item) &&
  !/[/<>"|?*]/u.test(item) &&
  ![...item].some((c) => c.charCodeAt(0) < 32) &&
  !item.slice(2).includes(':') &&
  win32.normalize(item) === item &&
  !item.endsWith('\\') &&
  !item
    .split('\\')
    .slice(1)
    .some((part) => /[. ]$/u.test(part));
export function validateNativeSuggestionsConfig(value, { allowMissingDigest = false } = {}) {
  const fields = ['version', 'target', ...pathFields, 'shimSha256'];
  if (allowMissingDigest && value?.shimSha256 === undefined) fields.pop();
  if (
    !keys(value, fields) ||
    value.version !== 1 ||
    Buffer.byteLength(JSON.stringify(value)) > limit ||
    !keys(value.target, ['agentId', 'hostId', 'workspaceId', 'runtimeId']) ||
    Object.values(value.target).some(
      (item) =>
        typeof item !== 'string' ||
        !item.trim() ||
        item.length > 256 ||
        [...item].some((c) => c.charCodeAt(0) < 32),
    )
  )
    throw failure('WINDOWS_NATIVE_CONFIG_INVALID');
  for (const field of pathFields) {
    const item = value[field];
    if (!canonicalPath(item)) throw failure('WINDOWS_NATIVE_CONFIG_INVALID');
  }
  if (
    (!allowMissingDigest || value.shimSha256 !== undefined) &&
    !/^[a-f0-9]{64}$/u.test(value.shimSha256)
  )
    throw failure('WINDOWS_NATIVE_CONFIG_INVALID');
  if (
    win32.basename(value.node).toLowerCase() !== 'node.exe' ||
    win32.basename(value.executable).toLowerCase() !== 'codex.exe' ||
    win32.basename(value.entry) !== 'desktop-native-suggestions-runtime.mjs' ||
    win32.basename(value.shimPath) !== 'launcher.exe' ||
    samePath(value.executable, value.applicationPath) ||
    samePath(value.executable, value.shimPath)
  )
    throw failure('WINDOWS_NATIVE_CONFIG_INVALID');
  return value;
}

// Fixed operations only. Request data arrives over stdin, never interpolated into PowerShell.
// These operations do not touch task/Host/trace environment, credentials, app ASAR or processes.
const nativeScript = String.raw`
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
try {
 foreach($module in @('Microsoft.PowerShell.Security','Microsoft.PowerShell.Utility','Microsoft.PowerShell.Management')) {
  Import-Module (Join-Path $PSHOME ('Modules\'+$module)) -ErrorAction Stop
 }
 $request=[Console]::In.ReadToEnd() | ConvertFrom-Json
 $sid=[System.Security.Principal.WindowsIdentity]::GetCurrent().User
 function Canonical([string]$Path,[bool]$Directory) {
  if($Path -cnotmatch '^[a-zA-Z]:\\' -or $Path.Substring(2).Contains(':') -or $Path.Contains('/') -or $Path.Contains([char]0) -or
     [IO.Path]::GetFullPath($Path) -ine $Path){throw 'WINDOWS_NATIVE_PATH_REJECTED'}
  $item=Get-Item -LiteralPath $Path -Force
  if($item.PSIsContainer -ne $Directory){throw 'WINDOWS_NATIVE_PATH_REJECTED'}
  for($at=$item.FullName;$at;$at=[IO.Path]::GetDirectoryName($at)) {
   if((Get-Item -LiteralPath $at -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'WINDOWS_NATIVE_PATH_REJECTED'}
   if($at -eq [IO.Path]::GetPathRoot($at)){break}
  }
 }
 function Private([string]$Path,[bool]$Directory) {
  Canonical $Path $Directory
  $acl=Get-Acl -LiteralPath $Path
  if(!$acl.AreAccessRulesProtected -or $acl.GetOwner([System.Security.Principal.SecurityIdentifier]).Value -ne $sid.Value){throw 'WINDOWS_NATIVE_PRIVATE_STATE_REJECTED'}
  $allow=$false
  foreach($rule in $acl.GetAccessRules($true,$true,[System.Security.Principal.SecurityIdentifier])) {
   if($rule.AccessControlType -eq 'Allow') {
    if($rule.IdentityReference.Value -ne $sid.Value){throw 'WINDOWS_NATIVE_PRIVATE_STATE_REJECTED'}
    $allow=$true
   }
  }
  if(!$allow){throw 'WINDOWS_NATIVE_PRIVATE_STATE_REJECTED'}
 }
 function Protect([string]$Path,[bool]$Directory) {
  Canonical $Path $Directory
  $old=Get-Acl -LiteralPath $Path
  if($old.GetOwner([System.Security.Principal.SecurityIdentifier]).Value -ne $sid.Value){throw 'WINDOWS_NATIVE_PRIVATE_STATE_REJECTED'}
  $acl=if($Directory){[System.Security.AccessControl.DirectorySecurity]::new()}else{[System.Security.AccessControl.FileSecurity]::new()}
  $acl.SetOwner($sid);$acl.SetAccessRuleProtection($true,$false)
  $rule=if($Directory){[System.Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow')}else{[System.Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','Allow')}
  $acl.AddAccessRule($rule);Set-Acl -LiteralPath $Path -AclObject $acl
  Private $Path $Directory
 }
 function ReadJson([string]$Path) {
  Private ([IO.Path]::GetDirectoryName($Path)) $true;Private $Path $false
  $stream=[IO.File]::Open($Path,'Open','Read','Read')
  try {
   if($stream.Length -le 0 -or $stream.Length -gt 16384){throw 'WINDOWS_NATIVE_CONFIG_INVALID'}
   $reader=[IO.StreamReader]::new($stream,[Text.UTF8Encoding]::new($false,$true),$false)
   try {return ($reader.ReadToEnd() | ConvertFrom-Json)} finally {$reader.Dispose()}
  } finally {$stream.Dispose()}
 }
 function Publish([string]$Path,$Value) {
  Private ([IO.Path]::GetDirectoryName($Path)) $true
  if(Test-Path -LiteralPath $Path){Private $Path $false}
  $bytes=[Text.UTF8Encoding]::new($false).GetBytes(($Value | ConvertTo-Json -Depth 8 -Compress))
  if($bytes.Length -gt 16384){throw 'WINDOWS_NATIVE_CONFIG_INVALID'}
  $temp=$Path+'.'+[Guid]::NewGuid().ToString('N')+'.tmp'
  $stream=$null
  try {
   $stream=[IO.File]::Open($temp,'CreateNew','Write','None');Protect $temp $false
   $stream.Write($bytes,0,$bytes.Length);$stream.Flush($true);$stream.Dispose();$stream=$null
   if(Test-Path -LiteralPath $Path){[IO.File]::Replace($temp,$Path,$null)}else{[IO.File]::Move($temp,$Path)}
   Private $Path $false
  } finally {
   if($stream){$stream.Dispose()}
   if(Test-Path -LiteralPath $temp){Private $temp $false;[IO.File]::Delete($temp)}
  }
 }
 function EnvironmentSnapshot {
  $key=[Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment',$false)
  try {
   $value=if($key){$key.GetValue('CODEX_CLI_PATH',$null,[Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)}else{$null}
   return @{user=@{value=$value;kind=if($null -eq $value){$null}else{$key.GetValueKind('CODEX_CLI_PATH').ToString()}};
    machine=[Environment]::GetEnvironmentVariable('CODEX_CLI_PATH','Machine');process=[Environment]::GetEnvironmentVariable('CODEX_CLI_PATH','Process');
    config=[Environment]::GetEnvironmentVariable('AGENT_HUB_NATIVE_SUGGESTIONS_CONFIG','Process')}
  } finally {if($key){$key.Dispose()}}
 }
 $state=[string]$request.state
 $out=switch([string]$request.action) {
  'environment' {EnvironmentSnapshot;break}
  'readInput' {ReadJson ([string]$request.path);break}
  'read' {
   if(!(Test-Path -LiteralPath $state)){@{manifest=$null;config=$null};break}
   Private $state $true
   $manifest=Join-Path $state 'environment.json';$config=Join-Path $state 'config.json'
   if(!(Test-Path -LiteralPath $manifest)) {
    if(@(Get-ChildItem -LiteralPath $state -Force).Count){throw 'WINDOWS_NATIVE_EXISTING_STATE'}
    @{manifest=$null;config=$null};break
   }
   @{manifest=(ReadJson $manifest);config=if(Test-Path -LiteralPath $config){ReadJson $config}else{$null}};break
  }
  'prepare' {
   # Create each missing ancestor separately; never follow or tighten an existing shared directory.
   $missing=[Collections.Generic.List[string]]::new();$at=$state
   while(!(Test-Path -LiteralPath $at)){$missing.Add($at);$at=[IO.Path]::GetDirectoryName($at)}
   Canonical $at $true
   for($i=$missing.Count-1;$i -ge 0;$i--){[void][IO.Directory]::CreateDirectory($missing[$i]);Protect $missing[$i] $true}
   Private $state $true;@{ok=$true};break
  }
  'publish' {
   if([string]$request.name -notin @('config.json','environment.json')){throw 'WINDOWS_NATIVE_OPERATION_REJECTED'}
   Publish (Join-Path $state ([string]$request.name)) $request.value;@{ok=$true};break
  }
  'compile' {
   Private $state $true
   $source=[string]$request.source;Canonical $source $false
   if((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant() -cne $request.sourceSha256){throw 'WINDOWS_NATIVE_SOURCE_CHANGED'}
   $exe=Join-Path $state 'launcher.exe'
   if(Test-Path -LiteralPath $exe){
    Private $exe $false
    $existing=(Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash.ToLowerInvariant()
    if(!$request.shimSha256 -or $existing -cne $request.shimSha256){throw 'WINDOWS_NATIVE_EXISTING_STATE'}
    @{sourceSha256=$request.sourceSha256;shimSha256=$existing};break
   }
   $temp=Join-Path $state ([Guid]::NewGuid().ToString('N')+'.exe')
   try {
    $compiler=Join-Path ([Runtime.InteropServices.RuntimeEnvironment]::GetRuntimeDirectory()) 'csc.exe'
    if(!(Test-Path -LiteralPath $compiler -PathType Leaf) -or !(Get-Command Add-Type -CommandType Cmdlet -ErrorAction SilentlyContinue)) {throw 'WINDOWS_NATIVE_COMPILER_UNAVAILABLE'}
    try {
     Add-Type -Path $source -OutputAssembly $temp -OutputType ConsoleApplication -ReferencedAssemblies @('System.dll','System.Core.dll','System.Web.Extensions.dll')
    } catch {throw 'WINDOWS_NATIVE_LAUNCHER_COMPILE_FAILED'}
    Protect $temp $false
    if((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant() -cne $request.sourceSha256){throw 'WINDOWS_NATIVE_SOURCE_CHANGED'}
    [IO.File]::Move($temp,$exe);Private $exe $false
    @{sourceSha256=$request.sourceSha256;shimSha256=(Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash.ToLowerInvariant()}
   } finally {if(Test-Path -LiteralPath $temp){Canonical $temp $false;[IO.File]::Delete($temp)}}
   break
  }
  'validate' {
   $c=$request.value
   Private $state $true;Private $c.shimPath $false
   foreach($field in @('traceRoot','logDirectory')){Canonical ([string]$c.$field) $true}
   foreach($field in @('node','entry')){Canonical ([string]$c.$field) $false}
   # Old applicationPath/executable are lexical historical hints, not launch
   # authority. The fixed Node entry must resolve the current trusted pipe owner
   # with inspectWindowsInstallation(config.executable); never execute a stale hint.
   if((Get-FileHash -LiteralPath $c.shimPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $c.shimSha256){throw 'WINDOWS_NATIVE_SHIM_CHANGED'}
   @{ok=$true};break
  }
  'registry' {
   $before=EnvironmentSnapshot
   if($before.user.value -cne $request.expected.value -or $before.user.kind -cne $request.expected.kind -or ($request.value.value -and $before.machine)){throw 'WINDOWS_NATIVE_OVERRIDE_CONFLICT'}
   $key=[Microsoft.Win32.Registry]::CurrentUser.CreateSubKey('Environment')
   try {
    if($null -eq $request.value.value){$key.DeleteValue('CODEX_CLI_PATH',$false)}else{
     $kind=[string]$request.value.kind
     if($kind -notin @('String','ExpandString')){throw 'WINDOWS_NATIVE_OPERATION_REJECTED'}
     $key.SetValue('CODEX_CLI_PATH',[string]$request.value.value,[Microsoft.Win32.RegistryValueKind]::$kind)
    }
    $key.Flush()
   } finally {$key.Dispose()}
   # Notify Explorer about this one persisted value; never quit/restart Codex.
   try { Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class AgentHubNativeEnvironmentBroadcast {
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] public static extern IntPtr SendMessageTimeout(IntPtr h,uint m,UIntPtr w,string p,uint f,uint t,out UIntPtr r);
}
'@
    $sent=[UIntPtr]::Zero
    [void][AgentHubNativeEnvironmentBroadcast]::SendMessageTimeout([IntPtr]0xffff,0x1a,[UIntPtr]::Zero,'Environment',2,3000,[ref]$sent)
   } catch { # The HKCU write is durable even if notification compilation is unavailable.
   }
   EnvironmentSnapshot;break
  }
  default {throw 'WINDOWS_NATIVE_OPERATION_REJECTED'}
 }
 [Console]::Out.WriteLine(($out | ConvertTo-Json -Depth 10 -Compress))
} catch {
 $code=$_.Exception.Message
 if($code -cnotmatch '^WINDOWS_NATIVE_[A-Z_]+$'){$code='WINDOWS_NATIVE_OPERATION_FAILED'}
 [Console]::Error.WriteLine($code);exit 1
}
`;

function nativeOperation(action, data) {
  try {
    const root = process.env.SystemRoot;
    if (!root || !win32.isAbsolute(root)) throw failure('WINDOWS_NATIVE_PLATFORM_UNAVAILABLE');
    const output = execFileSync(
      win32.join(root, 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe'),
      [
        '-NoProfile',
        '-NonInteractive',
        '-EncodedCommand',
        Buffer.from(nativeScript, 'utf16le').toString('base64'),
      ],
      {
        input: JSON.stringify({ ...data, action }),
        encoding: 'utf8',
        windowsHide: true,
        timeout: action === 'compile' ? 60000 : 20000,
        maxBuffer: 65536,
        env: {
          ...process.env,
          PSModulePath: win32.join(root, 'System32', 'WindowsPowerShell', 'v1.0', 'Modules'),
        },
      },
    );
    return JSON.parse(output.trim());
  } catch (error) {
    const code = String(error.stderr ?? '').trim();
    throw failure(
      /^WINDOWS_NATIVE_[A-Z_]+$/u.test(code) ? code : 'WINDOWS_NATIVE_OPERATION_FAILED',
    );
  }
}

/** Injectable fixed-operation boundary for offline fixtures. No tests run on import. */
export function windowsNativeSuggestionsEnvironment({
  state = process.env.USERPROFILE &&
    win32.join(process.env.USERPROFILE, '.config', 'agent-hub', 'native-suggestions'),
  run = nativeOperation,
  platform = process.platform,
  entry = nativeSuggestionsEntry,
  node = process.execPath,
} = {}) {
  if (platform !== 'win32' || !canonicalPath(state))
    throw failure('WINDOWS_NATIVE_PLATFORM_UNAVAILABLE');
  const source = nativeSuggestionsSource;
  const configPath = win32.join(state, 'config.json'),
    shimPath = win32.join(state, 'launcher.exe');
  const call = (action, data = {}) => run(action, { ...data, state });
  const owned = { value: shimPath, kind: 'String' };
  const equalEnvironment = (a, b) => a?.value === b?.value && a?.kind === b?.kind;
  const checkOverrides = (environment, manifest) => {
    if (
      environment.machine ||
      (environment.process && !samePath(environment.process, shimPath)) ||
      (environment.config && !samePath(environment.config, configPath)) ||
      (!manifest && environment.process) ||
      (!manifest && environment.user.value !== null) ||
      (manifest &&
        !equalEnvironment(environment.user, owned) &&
        !equalEnvironment(environment.user, manifest.previous))
    )
      throw failure('WINDOWS_NATIVE_OVERRIDE_CONFLICT');
  };
  const load = () => {
    const value = call('read');
    const m = value.manifest;
    if (!m) return value;
    if (
      !keys(m, [
        'version',
        'kind',
        'phase',
        'enabled',
        'configPath',
        'shimPath',
        'sourceSha256',
        'shimSha256',
        'configSha256',
        'previous',
      ]) ||
      m.version !== 1 ||
      m.kind !== 'windows-native-suggestions' ||
      !['preparing', 'ready'].includes(m.phase) ||
      typeof m.enabled !== 'boolean' ||
      !samePath(m.configPath, configPath) ||
      !samePath(m.shimPath, shimPath) ||
      !/^[a-f0-9]{64}$/u.test(m.sourceSha256) ||
      !keys(m.previous, ['value', 'kind']) ||
      !(m.previous.value === null && m.previous.kind === null) || // Never adopt an unrelated previous override.
      (m.phase === 'preparing' &&
        (m.enabled ||
          m.configSha256 !== null ||
          (m.shimSha256 !== null && !/^[a-f0-9]{64}$/u.test(m.shimSha256))))
    )
      throw failure('WINDOWS_NATIVE_MANIFEST_INVALID');
    if (m.phase === 'ready') {
      validateNativeSuggestionsConfig(value.config);
      if (
        !samePath(value.config.shimPath, shimPath) ||
        m.shimSha256 !== value.config.shimSha256 ||
        m.configSha256 !== sha(stableJson(value.config))
      )
        throw failure('WINDOWS_NATIVE_MANIFEST_INVALID');
    }
    return value;
  };
  const save = (name, value) => call('publish', { name, value });
  const status = () => {
    try {
      const { manifest, config } = load();
      const environment = call('environment');
      checkOverrides(environment, manifest);
      if (!manifest)
        return {
          configured: false,
          code: 'WINDOWS_NATIVE_NOT_CONFIGURED',
          variable: nativeSuggestionsVariable,
          logDirectoryKnown: false,
        };
      if (manifest.sourceSha256 !== sha(readFileSync(source)))
        throw failure('WINDOWS_NATIVE_SOURCE_CHANGED');
      if (manifest.phase !== 'ready')
        return {
          configured: false,
          code: 'WINDOWS_NATIVE_PREPARING',
          phase: manifest.phase,
          logDirectoryKnown: false,
        };
      call('validate', { value: config });
      return {
        configured: manifest.enabled && equalEnvironment(environment.user, owned),
        phase: manifest.phase,
        variable: nativeSuggestionsVariable,
        configPath,
        shimPath,
        sourceSha256: manifest.sourceSha256,
        shimSha256: manifest.shimSha256,
        traceRoot: config.traceRoot,
        logDirectory: config.logDirectory,
        logDirectoryKnown: true,
        requiresFreshLauncher: true,
        currentInstanceVerified: false,
        originalCliResolution: 'current-trusted-pipe-owner',
        historicalInstallationPaths: true,
        modelInputsSubmitted: 0,
        desktopRestarted: false,
      };
    } catch (error) {
      return {
        configured: false,
        code: error.code ?? 'WINDOWS_NATIVE_STATUS_FAILED',
        logDirectoryKnown: false,
      };
    }
  };
  return {
    status,
    install(input) {
      const environment = call('environment'),
        previous = load();
      checkOverrides(environment, previous.manifest);
      const config = { node, entry, ...input, shimPath: input?.shimPath ?? shimPath };
      validateNativeSuggestionsConfig(config, { allowMissingDigest: true });
      if (!samePath(config.shimPath, shimPath) || !samePath(config.entry, entry))
        throw failure('WINDOWS_NATIVE_CONFIG_INVALID');
      const sourceSha256 = sha(readFileSync(source));
      let manifest = previous.manifest;
      if (manifest && manifest.sourceSha256 !== sourceSha256)
        throw failure('WINDOWS_NATIVE_SOURCE_CHANGED');
      if (manifest?.phase === 'ready') {
        config.shimSha256 ??= manifest.shimSha256;
        const launchFields = (value) =>
          Object.fromEntries(
            Object.entries(value).filter(
              ([key]) => !['applicationPath', 'executable', 'entry'].includes(key),
            ),
          );
        if (stableJson(launchFields(config)) !== stableJson(launchFields(previous.config)))
          throw failure('WINDOWS_NATIVE_EXISTING_STATE');
        // Store paths are history only. A reinstall may supply refreshed hints,
        // but must not rewrite the original private target/config or its digest.
        call('validate', { value: previous.config });
        if (!samePath(previous.config.entry, config.entry)) {
          const updated = { ...previous.config, entry: config.entry };
          call('validate', { value: updated });
          save('config.json', updated);
          manifest = { ...manifest, configSha256: sha(stableJson(updated)) };
        }
      } else {
        call('prepare');
        manifest ??= {
          version: 1,
          kind: 'windows-native-suggestions',
          phase: 'preparing',
          enabled: false,
          configPath,
          shimPath,
          sourceSha256,
          shimSha256: null,
          configSha256: null,
          previous: environment.user,
        };
        save('environment.json', manifest);
        let compiled;
        try {
          compiled = call('compile', { source, sourceSha256, shimSha256: manifest.shimSha256 });
        } catch (error) {
          if (error.code === 'WINDOWS_NATIVE_COMPILER_UNAVAILABLE')
            return { ...status(), code: error.code };
          throw error;
        }
        if (compiled.sourceSha256 !== sourceSha256 || !/^[a-f0-9]{64}$/u.test(compiled.shimSha256))
          throw failure('WINDOWS_NATIVE_SOURCE_CHANGED');
        // A recorded executable can be reused after interrupted configuration;
        // an unrecorded existing executable is never adopted or overwritten.
        manifest.shimSha256 = compiled.shimSha256;
        save('environment.json', manifest);
        if (config.shimSha256 !== undefined && config.shimSha256 !== compiled.shimSha256)
          throw failure('WINDOWS_NATIVE_SHIM_CHANGED');
        config.shimSha256 = compiled.shimSha256;
        call('validate', { value: config });
        save('config.json', config);
        manifest = {
          ...manifest,
          phase: 'ready',
          shimSha256: config.shimSha256,
          configSha256: sha(stableJson(config)),
        };
      }
      // Save intent before the CAS; an interrupted registry write remains recoverable.
      manifest.enabled = true;
      save('environment.json', manifest);
      const current = call('environment');
      checkOverrides(current, manifest);
      call('registry', { expected: current.user, value: owned });
      return status();
    },
    disable() {
      const { manifest } = load();
      if (!manifest) return status();
      const current = call('environment');
      // Disable concerns HKCU only: do not overwrite a later explicit user value.
      if (equalEnvironment(current.user, owned))
        call('registry', { expected: current.user, value: manifest.previous });
      else if (!equalEnvironment(current.user, manifest.previous))
        throw failure('WINDOWS_NATIVE_OVERRIDE_CONFLICT');
      manifest.enabled = false;
      save('environment.json', manifest);
      // Keep the managed binary/config for already-running desktops and audit/rollback.
      return status();
    },
    readInput(path) {
      return call('readInput', { path });
    },
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const [action = 'status', ...args] = process.argv.slice(2);
    if (!['install', 'status', 'disable'].includes(action) || args.length % 2)
      throw failure('WINDOWS_NATIVE_ARGUMENTS_INVALID');
    const options = {};
    for (let i = 0; i < args.length; i += 2) {
      const key = args[i];
      if (!['--state', '--config'].includes(key) || options[key] !== undefined)
        throw failure('WINDOWS_NATIVE_ARGUMENTS_INVALID');
      options[key] = args[i + 1];
    }
    if ((action === 'install') !== !!options['--config'])
      throw failure('WINDOWS_NATIVE_ARGUMENTS_INVALID');
    const tool = windowsNativeSuggestionsEnvironment({ state: options['--state'] });
    const result =
      action === 'install' ? tool.install(tool.readInput(options['--config'])) : tool[action]();
    console.log(JSON.stringify(result));
    if (action === 'install' && !result.configured) process.exitCode = 1;
  } catch (error) {
    console.log(
      JSON.stringify({
        configured: false,
        code: /^WINDOWS_NATIVE_[A-Z_]+$/u.test(error.code)
          ? error.code
          : 'WINDOWS_NATIVE_OPERATION_FAILED',
      }),
    );
    process.exitCode = 1;
  }
}
