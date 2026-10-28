param([ValidateSet('protect', 'protect-file', 'check', 'bootstrap-native')][string]$Action, [Parameter(Mandatory)][string]$Path, [string]$BinaryPath, [string]$PhysicalPath)
$ErrorActionPreference = 'Stop'
try {
    foreach ($name in @('Microsoft.PowerShell.Security', 'Microsoft.PowerShell.Utility', 'Microsoft.PowerShell.Management')) {
        Import-Module (Join-Path $PSHOME ('Modules\' + $name)) -ErrorAction Stop
    }
} catch {
    [Console]::Error.WriteLine('WINDOWS_POWERSHELL_MODULE_UNAVAILABLE')
    exit 1
}
try {
    Add-Type -Path (Join-Path $PSScriptRoot 'private-state.cs')
    function Invoke-PrivateState([string]$Operation, [string]$TargetPath) {
        # Share the current-owner private DACL implementation; never request a SACL.
        [CodexManagePrivateStateSecurity]::Execute($Operation, $TargetPath)
    }
    if ($Action -eq 'bootstrap-native') {
        if ([string]::IsNullOrWhiteSpace($BinaryPath) -or [string]::IsNullOrWhiteSpace($PhysicalPath)) { throw 'BINARY_PATH_REQUIRED' }
        # Identical checks in one PowerShell process, not cached permission authority.
        Invoke-PrivateState 'protect' $Path
        Invoke-PrivateState 'check' $BinaryPath
        Invoke-PrivateState 'check' $PhysicalPath
    } else {
        if ($BinaryPath -or $PhysicalPath) { throw 'UNEXPECTED_BINARY_PATH' }
        Invoke-PrivateState $Action $Path
    }
    [Console]::Out.WriteLine('PRIVATE_STATE_OK')
} catch {
    [Console]::Error.WriteLine('UNSAFE_WINDOWS_PRIVATE_STATE')
    exit 1
}
