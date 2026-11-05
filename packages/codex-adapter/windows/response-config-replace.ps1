param([Parameter(Mandatory=$true)][string]$Target,[Parameter(Mandatory=$true)][string]$Prepared,[Parameter(Mandatory=$true)][string]$Backup,[Parameter(Mandatory=$true)][string]$Expected)
$ErrorActionPreference = 'Stop'
function Get-Sha256([string]$Path) {
  $sha = [System.Security.Cryptography.SHA256]::Create()
  try { return ([BitConverter]::ToString($sha.ComputeHash([System.IO.File]::ReadAllBytes($Path)))).Replace('-', '').ToLowerInvariant() } finally { $sha.Dispose() }
}
$before = Get-Sha256 $Target
if ($before -ne $Expected) { throw 'Configuration changed before replacement' }
[System.IO.File]::Replace($Prepared, $Target, $Backup, $false)
$actual = Get-Sha256 $Backup
Write-Output ('{"replacedSha256":"' + $actual + '"}')
