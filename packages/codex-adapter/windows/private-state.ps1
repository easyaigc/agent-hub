param(
    [ValidateSet('protect', 'protect-file', 'check', 'create-directory', 'create-file', 'prepare-native', 'bootstrap-native')][string]$Action,
    [Parameter(Mandatory)][string]$Path,
    [string]$BinaryPath,
    [string]$PhysicalPath,
    [ValidateSet('private-state', 'trace-state', 'host-launcher')][string]$NativeName,
    [ValidateRange(1, 30000)][int]$BudgetMs = 30000
)
$ErrorActionPreference = 'Stop'
$privateStateElapsed = [Diagnostics.Stopwatch]::StartNew()
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
    $privateStateSources = @((Join-Path $PSScriptRoot 'private-state.cs'))
    if ($Action -eq 'prepare-native') {
        $privateStateSources += (Join-Path $PSScriptRoot 'private-state-compilation.cs')
    }
    Add-Type -Path $privateStateSources
    $privateStateStage = 'execute'
    $privateStateResponse = 'PRIVATE_STATE_OK'
    function Invoke-PrivateState([string]$Operation, [string]$TargetPath) {
        # Share the current-owner private DACL implementation; never request a SACL.
        [CodexManagePrivateStateSecurity]::Execute($Operation, $TargetPath)
    }
    if ($Action -ne 'prepare-native' -and ($PSBoundParameters.ContainsKey('NativeName') -or $PSBoundParameters.ContainsKey('BudgetMs'))) { throw 'UNEXPECTED_BINARY_PATH' }
    if ($Action -eq 'prepare-native') {
        if ([string]::IsNullOrWhiteSpace($NativeName) -or $BinaryPath -or $PhysicalPath) { throw 'UNEXPECTED_BINARY_PATH' }
        # Add-Type and imports spend the same outer budget as compilation.
        # The Node owner still enforces its original, non-renewable deadline.
        $remaining = [long]$BudgetMs - [long][Math]::Ceiling($privateStateElapsed.Elapsed.TotalMilliseconds)
        if ($remaining -le 0) { throw [InvalidOperationException]::new() }
        # Native owns A's metadata lease, the handle-only copy to B and B's publication.
        $privateStateResponse = [CodexManagePrivateStateSecurity]::PrepareNative($NativeName, $PSScriptRoot, $Path, [int]$remaining)
    } elseif ($Action -eq 'bootstrap-native') {
        if ([string]::IsNullOrWhiteSpace($BinaryPath) -or [string]::IsNullOrWhiteSpace($PhysicalPath)) { throw 'BINARY_PATH_REQUIRED' }
        # Identical checks in one PowerShell process, not cached permission authority.
        Invoke-PrivateState 'protect' $Path
        Invoke-PrivateState 'check' $BinaryPath
        Invoke-PrivateState 'check' $PhysicalPath
    } else {
        if ($BinaryPath -or $PhysicalPath) { throw 'UNEXPECTED_BINARY_PATH' }
        if ($Action -eq 'create-file') {
            $privateStateResponse = [CodexManagePrivateStateSecurity]::CreatePrivateFileReceipt($Path)
        } else {
            Invoke-PrivateState $Action $Path
        }
    }
    [Console]::Out.WriteLine($privateStateResponse)
} catch {
    [Console]::Error.WriteLine('UNSAFE_WINDOWS_PRIVATE_STATE')
    [Console]::Error.WriteLine('WINDOWS_PRIVATE_STATE_BOOTSTRAP:' + $privateStateStage)
    # Only fixed labels/numbers leave this process; never exception messages or paths.
    $detail = $_.Exception
    for ($index = 0; $index -lt 4 -and $null -ne $detail.InnerException; $index++) { $detail = $detail.InnerException }
    $knownTypes = @('Win32Exception', 'UnauthorizedAccessException', 'PrivateStateAlreadyExistsException', 'IOException', 'ArgumentException', 'TypeInitializationException', 'InvalidOperationException', 'PSInvalidOperationException', 'PSInvalidCastException', 'PathTooLongException', 'FileNotFoundException', 'DirectoryNotFoundException', 'DllNotFoundException', 'EntryPointNotFoundException', 'BadImageFormatException', 'SecurityException', 'NotSupportedException', 'Exception')
    if ($detail.GetType().Name -in $knownTypes) { [Console]::Error.WriteLine('WINDOWS_PRIVATE_STATE_NATIVE_TYPE:' + $detail.GetType().Name) }
    if ($detail -is [System.ComponentModel.Win32Exception]) { [Console]::Error.WriteLine('WINDOWS_PRIVATE_STATE_WIN32:' + $detail.NativeErrorCode) }
    # Native assigns only fixed step labels. Node accepts an explicit enum;
    # these diagnostics never grant an operation or renew its budget.
    foreach ($key in @('CodexManage.NativeStep', 'CodexManage.NativeCleanupStep')) {
        $step = $detail.Data[$key]
        $prefix = if ($key -eq 'CodexManage.NativeStep') { 'WINDOWS_PRIVATE_STATE_NATIVE_STEP:' } else { 'WINDOWS_PRIVATE_STATE_CLEANUP_STEP:' }
        if ($step -is [string] -and $step -cmatch '^[a-z][a-z0-9-]{0,39}$') { [Console]::Error.WriteLine($prefix + $step) }
    }
    $cleanupCode = $detail.Data['CodexManage.NativeCleanupWin32']
    if ($cleanupCode -is [int] -and $cleanupCode -gt 0) { [Console]::Error.WriteLine('WINDOWS_PRIVATE_STATE_CLEANUP_WIN32:' + $cleanupCode) }

    if ($privateStateStage -eq 'execute') {
        try {
            $ownerEvidence = [CodexManagePrivateStateSecurity]::OwnerFailureEvidence($detail)
            if ($ownerEvidence) { [Console]::Error.WriteLine('WINDOWS_PRIVATE_STATE_OWNER:' + $ownerEvidence) }
        } catch { # Diagnostic collection must not replace the original refusal.
        }
    }
    if ($detail.Message -in @('WRONG_FILE_OWNER', 'NULL_PRIVATE_DACL', 'UNKNOWN_PRIVATE_ACE', 'SHARED_PRIVATE_FILE', 'UNPROTECTED_PRIVATE_DACL')) { [Console]::Error.WriteLine('WINDOWS_PRIVATE_STATE_REJECTED:' + $detail.Message) }
    exit 1
}
