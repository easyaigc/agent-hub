# Read-only routing hint for an existing private regular installation. No
# compiler, Task operation, configuration write or process start is present.
$ErrorActionPreference='Stop';Set-StrictMode -Version Latest
foreach($module in @('Microsoft.PowerShell.Management','Microsoft.PowerShell.Security','Microsoft.PowerShell.Utility')){Import-Module (Join-Path $PSHOME ('Modules\'+$module)) -ErrorAction Stop}
. (Join-Path $PSScriptRoot '..\packages\codex-adapter\windows\private-bootstrap.ps1')
$profileRoot=[Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)
Assert-HostBootstrapPath $profileRoot $false
Assert-HostBootstrapPath (Join-Path $profileRoot '.config') $false
$hub=Join-Path $profileRoot '.config\agent-hub';Assert-HostBootstrapPath $hub $true
$directory=Join-Path $hub 'windows-host-v1';Assert-HostBootstrapPath $directory $true
$path=Join-Path $directory 'current.json'
$item=Get-Item -LiteralPath $path -Force
if($item.PSIsContainer -or ([int]$item.Attributes -band [int][IO.FileAttributes]::ReparsePoint) -ne 0 -or $item.Length -gt 4096){throw 'HOST_REGULAR_ROUTE_FILE_REJECTED'}
$user=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value;$allowed=@($user,'S-1-5-18','S-1-5-32-544')
$acl=Get-Acl -LiteralPath $path
if($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -cne $user -or -not $acl.AreAccessRulesProtected){throw 'HOST_REGULAR_ROUTE_OWNER_REJECTED'}
foreach($rule in $acl.Access){if($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -notin $allowed){throw 'HOST_REGULAR_ROUTE_DACL_REJECTED'}}
$stream=[IO.FileStream]::new($path,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
try{$reader=[IO.StreamReader]::new($stream,[Text.UTF8Encoding]::new($false,$true));$text=$reader.ReadToEnd();if($text.Length -gt 4096){throw 'HOST_REGULAR_ROUTE_BUDGET'};$value=ConvertFrom-Json $text
    $anchor=Join-Path $directory 'programs';$expected=Join-Path $anchor $value.runtimeSha256
    if($value.format -cne 'windows-host-regular-installation-v1' -or $value.runtimeSha256 -cnotmatch '^[a-f0-9]{64}$' -or $value.hostId -cnotmatch '^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$' -or -not [string]::Equals($expected,$value.program,[StringComparison]::OrdinalIgnoreCase)){throw 'HOST_REGULAR_ROUTE_TARGET_REJECTED'}
    Assert-HostBootstrapSource (Join-Path $expected 'packages\codex-adapter\windows') $hub
    $hash=[Security.Cryptography.SHA256]::Create();try{$bytes=[Text.Encoding]::UTF8.GetBytes($text);$sha=([BitConverter]::ToString($hash.ComputeHash($bytes))).Replace('-','').ToLowerInvariant()}finally{$hash.Dispose()}
    [Console]::Out.WriteLine((ConvertTo-Json -Compress @{program=$expected;runtimeSha256=$value.runtimeSha256;hostId=$value.hostId;pointerSha256=$sha;profile=$profileRoot}))
}finally{$stream.Dispose()}
