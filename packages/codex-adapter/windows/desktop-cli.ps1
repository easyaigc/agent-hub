param([Parameter(Mandatory)][string]$Path)
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
    $item = Get-Item -LiteralPath $Path -Force
    if ($item.PSIsContainer -or $item.Name -ne 'codex.exe') { throw 'INVALID_CLI' }
    # Never follow a redirected candidate or installation directory.
    $ancestor = $item
    while ($null -ne $ancestor) {
        if ($ancestor.Attributes -band [System.IO.FileAttributes]::ReparsePoint) { throw 'REDIRECTED_CLI' }
        $ancestor = if ($ancestor -is [System.IO.FileInfo]) { $ancestor.Directory } else { $ancestor.Parent }
    }
    # Discovery fixes the candidate; publishers do not determine compatibility.
    @{ sha256 = (Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash.ToLowerInvariant() } | ConvertTo-Json -Compress
} catch {
    [Console]::Error.WriteLine('WINDOWS_CLI_REJECTED')
    exit 1
}
