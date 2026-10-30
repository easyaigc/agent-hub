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
    $privateStateStage = 'add-type'
    Add-Type -Path (Join-Path $PSScriptRoot 'private-state.cs')
    $privateStateStage = 'execute'
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
    [Console]::Error.WriteLine('WINDOWS_PRIVATE_STATE_BOOTSTRAP:' + $privateStateStage)
    # Only fixed labels/numbers leave this process; never exception messages or paths.
    $detail = $_.Exception
    for ($index = 0; $index -lt 4 -and $null -ne $detail.InnerException; $index++) { $detail = $detail.InnerException }
    $knownTypes = @('Win32Exception', 'UnauthorizedAccessException', 'IOException', 'ArgumentException', 'TypeInitializationException', 'InvalidOperationException', 'PSInvalidOperationException', 'PSInvalidCastException', 'PathTooLongException', 'FileNotFoundException', 'DirectoryNotFoundException', 'DllNotFoundException', 'EntryPointNotFoundException', 'BadImageFormatException', 'SecurityException', 'NotSupportedException', 'Exception')
    if ($detail.GetType().Name -in $knownTypes) { [Console]::Error.WriteLine('WINDOWS_PRIVATE_STATE_NATIVE_TYPE:' + $detail.GetType().Name) }
    if ($detail -is [System.ComponentModel.Win32Exception]) { [Console]::Error.WriteLine('WINDOWS_PRIVATE_STATE_WIN32:' + $detail.NativeErrorCode) }
    if ($detail.Message -in @('WRONG_FILE_OWNER', 'NULL_PRIVATE_DACL', 'UNKNOWN_PRIVATE_ACE', 'SHARED_PRIVATE_FILE', 'UNPROTECTED_PRIVATE_DACL')) { [Console]::Error.WriteLine('WINDOWS_PRIVATE_STATE_REJECTED:' + $detail.Message) }
    exit 1
}
