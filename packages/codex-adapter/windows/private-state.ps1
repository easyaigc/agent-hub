param([ValidateSet('protect', 'protect-file', 'check')][string]$Action, [Parameter(Mandatory)][string]$Path)
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
    # Share the native implementation; the PowerShell provider's Set-Acl can
    # request audit privileges even though only a private DACL is needed.
    Add-Type -Path (Join-Path $PSScriptRoot 'private-state.cs')
    [CodexManagePrivateStateSecurity]::Execute($Action, $Path)
    [Console]::Out.WriteLine('PRIVATE_STATE_OK')
} catch {
    [Console]::Error.WriteLine('UNSAFE_WINDOWS_PRIVATE_STATE')
    exit 1
}
