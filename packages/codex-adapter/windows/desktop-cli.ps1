param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Desktop)
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
    $signature = Get-AuthenticodeSignature -LiteralPath $item.FullName
    $trusted = $signature.Status -eq 'Valid' -and $signature.SignerCertificate.Subject -match 'OpenAI'
    if (-not $trusted) {
        # An unsigned Store payload is accepted only inside the verified owner's package.
        $trusted = @(Get-AppxPackage | Where-Object {
            $_.SignatureKind -eq 'Store' -and $_.Publisher -match 'OpenAI' -and
            $Desktop.StartsWith($_.InstallLocation.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase) -and
            $item.FullName.StartsWith($_.InstallLocation.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)
        }).Count -eq 1
    }
    if (-not $trusted) { throw 'UNTRUSTED_CLI' }
    @{ sha256 = (Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash.ToLowerInvariant() } | ConvertTo-Json -Compress
} catch {
    [Console]::Error.WriteLine('WINDOWS_CLI_REJECTED')
    exit 1
}
