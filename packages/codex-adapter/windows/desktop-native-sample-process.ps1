param([Parameter(Mandatory)][int]$ProducerPid, [Parameter(Mandatory)][string]$Config,
      [ValidateSet('identity','installation')][string]$Action='identity')
# Inspection only. No model, MCP, pipe connection, environment/argv reads or process starts.
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
try {
 foreach($module in @('Microsoft.PowerShell.Security','Microsoft.PowerShell.Utility','Microsoft.PowerShell.Management','CimCmdlets')) {
  Import-Module (Join-Path $PSHOME ('Modules\'+$module)) -ErrorAction Stop
 }
 $sid=[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
 $session=[System.Diagnostics.Process]::GetCurrentProcess().SessionId
 function PathText([string]$Path) {
  if([string]::IsNullOrWhiteSpace($Path) -or $Path -cnotmatch '^[a-zA-Z]:\\' -or
     $Path.Substring(2).Contains(':') -or $Path.Contains('/') -or $Path -match '[\x00-\x1f]' -or
     [IO.Path]::GetFullPath($Path) -ine $Path -or $Path.EndsWith('\')){throw 'WINDOWS_NATIVE_CONFIG_INVALID'}
  foreach($part in $Path.Substring(3).Split('\')){if($part -match '[. ]$'){throw 'WINDOWS_NATIVE_CONFIG_INVALID'}}
 }
 function Canonical([string]$Path,[bool]$Directory) {
  PathText $Path
  $item=Get-Item -LiteralPath $Path -Force
  if($item.PSIsContainer -ne $Directory){throw 'WINDOWS_NATIVE_CONFIG_INVALID'}
  for($at=$item.FullName;$at;$at=[IO.Path]::GetDirectoryName($at)) {
   if((Get-Item -LiteralPath $at -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'WINDOWS_NATIVE_PRIVATE_STATE_REJECTED'}
   if($at -eq [IO.Path]::GetPathRoot($at)){break}
  }
 }
 function Private([string]$Path,[bool]$Directory) {
  Canonical $Path $Directory
  $acl=Get-Acl -LiteralPath $Path
  if(!$acl.AreAccessRulesProtected -or $acl.GetOwner([System.Security.Principal.SecurityIdentifier]).Value -ne $sid){throw 'WINDOWS_NATIVE_PRIVATE_STATE_REJECTED'}
  $allow=$false
  foreach($rule in $acl.GetAccessRules($true,$true,[System.Security.Principal.SecurityIdentifier])) {
   if($rule.AccessControlType -eq 'Allow') {
    if($rule.IdentityReference.Value -ne $sid){throw 'WINDOWS_NATIVE_PRIVATE_STATE_REJECTED'}
    $allow=$true
   }
  }
  if(!$allow){throw 'WINDOWS_NATIVE_PRIVATE_STATE_REJECTED'}
 }
 function ExactKeys($Value,[string[]]$Names) {
  if($null -eq $Value -or $Value -isnot [pscustomobject] -or
     ((@($Value.PSObject.Properties.Name | Sort-Object) -join '|') -cne (@($Names | Sort-Object) -join '|'))){throw 'WINDOWS_NATIVE_CONFIG_INVALID'}
 }
 Private ([IO.Path]::GetDirectoryName($Config)) $true;Private $Config $false
 if([IO.Path]::GetFileName($Config) -cne 'config.json'){throw 'WINDOWS_NATIVE_CONFIG_INVALID'}
 $file=[IO.File]::Open($Config,'Open','Read','Read')
 try {
  if($file.Length -le 0 -or $file.Length -gt 16384){throw 'WINDOWS_NATIVE_CONFIG_INVALID'}
  $configDigest=(Get-FileHash -LiteralPath $Config -Algorithm SHA256).Hash
  $reader=[IO.StreamReader]::new($file,[Text.UTF8Encoding]::new($false,$true),$false)
  try {$cfg=$reader.ReadToEnd() | ConvertFrom-Json} finally {$reader.Dispose()}
 } finally {$file.Dispose()}
 ExactKeys $cfg @('version','target','traceRoot','applicationPath','executable','node','entry','logDirectory','shimPath','shimSha256')
 if($cfg.version -isnot [int] -or $cfg.version -ne 1 -or $cfg.shimSha256 -isnot [string] -or $cfg.shimSha256 -cnotmatch '^[a-f0-9]{64}$'){throw 'WINDOWS_NATIVE_CONFIG_INVALID'}
 ExactKeys $cfg.target @('agentId','hostId','workspaceId','runtimeId')
 foreach($name in @('agentId','hostId','workspaceId','runtimeId')) {
  $text=$cfg.target.$name
  if($text -isnot [string] -or [string]::IsNullOrWhiteSpace($text) -or $text.Length -gt 256 -or $text -match '[\x00-\x1f]'){throw 'WINDOWS_NATIVE_CONFIG_INVALID'}
 }
 foreach($name in @('traceRoot','logDirectory')) {
  if($cfg.$name -isnot [string]){throw 'WINDOWS_NATIVE_CONFIG_INVALID'}
  Canonical $cfg.$name $true
 }
 foreach($name in @('node','entry','shimPath')) {
  if($cfg.$name -isnot [string]){throw 'WINDOWS_NATIVE_CONFIG_INVALID'}
  Canonical $cfg.$name $false
 }
 foreach($name in @('applicationPath','executable')) {
  if($cfg.$name -isnot [string]){throw 'WINDOWS_NATIVE_CONFIG_INVALID'}
  PathText $cfg.$name
 }
 if($cfg.shimPath -ine (Join-Path ([IO.Path]::GetDirectoryName($Config)) 'launcher.exe') -or
    [IO.Path]::GetFileName($cfg.node) -ine 'node.exe' -or [IO.Path]::GetFileName($cfg.executable) -ine 'codex.exe' -or
    [IO.Path]::GetFileName($cfg.entry) -cne 'desktop-native-suggestions-runtime.mjs' -or
    $cfg.executable -ieq $cfg.shimPath -or $cfg.executable -ieq $cfg.applicationPath){throw 'WINDOWS_NATIVE_CONFIG_INVALID'}
 Private $cfg.shimPath $false
 if((Get-FileHash -LiteralPath $cfg.shimPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $cfg.shimSha256){throw 'WINDOWS_NATIVE_SHIM_CHANGED'}
 function Snapshot([int]$Id) {
  if($Id -le 0){throw 'WINDOWS_NATIVE_PROCESS_REJECTED'}
  # Retain a Process handle while correlating CIM ancestry with the exact live instance.
  $process=Get-Process -Id $Id
  try {
   $handle=$process.Handle
   $started=$process.StartTime.ToUniversalTime()
   $path=$process.Path
   if(!$path -or $process.SessionId -ne $session){throw 'WINDOWS_NATIVE_PROCESS_REJECTED'}
   Canonical $path $false
   $cim=Get-CimInstance Win32_Process -Filter "ProcessId=$Id" -Property ProcessId,ParentProcessId,ExecutablePath,CreationDate,SessionId
   if(!$cim -or $cim.ExecutablePath -ine $path -or $cim.SessionId -ne $session -or
      [DateTimeOffset]::new($cim.CreationDate.ToUniversalTime()).ToUnixTimeMilliseconds() -ne [DateTimeOffset]::new($started).ToUnixTimeMilliseconds()) {throw 'WINDOWS_NATIVE_PROCESS_CHANGED'}
   $owner=Invoke-CimMethod -InputObject $cim -MethodName GetOwnerSid
   if($owner.ReturnValue -ne 0 -or $owner.Sid -ne $sid){throw 'WINDOWS_NATIVE_PROCESS_REJECTED'}
   if($process.HasExited){throw 'WINDOWS_NATIVE_PROCESS_CHANGED'}
   return @{id=$Id;parent=[int]$cim.ParentProcessId;path=$path;ticks=$started.Ticks;ms=[DateTimeOffset]::new($started).ToUnixTimeMilliseconds()}
  } finally {$process.Dispose()}
 }
 $producer=Snapshot $ProducerPid
 $shim=Snapshot $producer.parent
 $desktop=Snapshot $shim.parent
 if($producer.path -ine $cfg.node -or $shim.path -ine $cfg.shimPath -or
    $desktop.ticks -gt $shim.ticks -or $shim.ticks -gt $producer.ticks -or
    $producer.id -eq $shim.id -or $shim.id -eq $desktop.id -or $producer.id -eq $desktop.id){throw 'WINDOWS_NATIVE_PROCESS_REJECTED'}
 # Trust the CURRENT parent, including its product identity, not an old Store
 # version path. Merely being some OpenAI-signed binary is insufficient.
 if([IO.Path]::GetFileName($desktop.path) -inotmatch '^(?:Codex|ChatGPT)\.exe$'){throw 'WINDOWS_NATIVE_DESKTOP_UNTRUSTED'}
 $signature=Get-AuthenticodeSignature -LiteralPath $desktop.path
 $product=(Get-Item -LiteralPath $desktop.path -Force).VersionInfo.ProductName
 $trusted=$signature.Status -eq 'Valid' -and $signature.SignerCertificate.Subject -match '(?:^|,\s*)(?:CN|O)="?OpenAI(?:[, "]|$)' -and
  $product -match '^(?:OpenAI )?Codex(?: Desktop)?$'
 if(!$trusted) {
  $trusted=@(Get-AppxPackage | Where-Object {$_.SignatureKind -eq 'Store' -and $_.Name -eq 'OpenAI.Codex' -and $_.Publisher -match '(?:^|,\s*)(?:CN|O)="?OpenAI(?:[, "]|$)' -and
   $desktop.path.StartsWith($_.InstallLocation.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase)}).Count -eq 1
 }
 if(!$trusted){throw 'WINDOWS_NATIVE_DESKTOP_UNTRUSTED'}
 # Repeat every identity and parent edge, including main PID/start time, after trust work.
 foreach($before in @($producer,$shim,$desktop)) {
  $after=Snapshot $before.id
  if($after.ticks -ne $before.ticks -or $after.path -ine $before.path -or $after.parent -ne $before.parent){throw 'WINDOWS_NATIVE_PROCESS_CHANGED'}
 }
 Private $Config $false;Private $cfg.shimPath $false
 if((Get-FileHash -LiteralPath $Config -Algorithm SHA256).Hash -cne $configDigest){throw 'WINDOWS_NATIVE_PROCESS_CHANGED'}
 if((Get-FileHash -LiteralPath $cfg.shimPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $cfg.shimSha256){throw 'WINDOWS_NATIVE_SHIM_CHANGED'}
 # Host must additionally compare desktopPid with actualOwner from its original trusted pipe.
 if($Action -eq 'installation') {
  # Startup cannot depend on the desktop IPC pipe: that pipe may itself wait
  # for this original app-server to initialize. Trust the verified native parent.
  [Console]::Out.WriteLine((@{producer=@{pid=$producer.id;desktopPid=$desktop.id;desktopStartedAt=$desktop.ms;wrapperStartedAt=$producer.ms};
    desktop=@{pid=$desktop.id;startedAtUnixMs=$desktop.ms;executable=$desktop.path;
      executableSha256=(Get-FileHash -LiteralPath $desktop.path -Algorithm SHA256).Hash.ToLowerInvariant();
      userSid=$sid;sameUser=$true;sameSession=$true;trustedPublisher=$true}} | ConvertTo-Json -Depth 5 -Compress))
 } else {
  [Console]::Out.WriteLine((@{pid=$producer.id;desktopPid=$desktop.id;desktopStartedAt=$desktop.ms;wrapperStartedAt=$producer.ms} | ConvertTo-Json -Compress))
 }
} catch {
 $code=$_.Exception.Message
 $known=@('WINDOWS_NATIVE_CONFIG_INVALID','WINDOWS_NATIVE_PRIVATE_STATE_REJECTED','WINDOWS_NATIVE_SHIM_CHANGED','WINDOWS_NATIVE_PROCESS_REJECTED','WINDOWS_NATIVE_PROCESS_CHANGED','WINDOWS_NATIVE_DESKTOP_UNTRUSTED')
 if($code -cnotin $known){$code='WINDOWS_NATIVE_PROCESS_UNAVAILABLE'}
 [Console]::Error.WriteLine($code);exit 1
}
