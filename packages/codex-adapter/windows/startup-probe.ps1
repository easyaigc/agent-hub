param([Parameter(Mandatory)][int]$NodePid, [string]$Path)
$ErrorActionPreference = 'Stop'
try {
    Import-Module (Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Utility') -ErrorAction Stop
    Import-Module (Join-Path $PSHOME 'Modules\CimCmdlets') -ErrorAction Stop
    $self = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $PID)
    if ($self.ParentProcessId -ne $NodePid) { throw 'WRONG_PROBE_PARENT' }
    Add-Type -Path (Join-Path $PSScriptRoot 'startup-probe.cs')
    [CodexManageStartupProbe]::Capture($NodePid, $Path) | ConvertTo-Json -Depth 6 -Compress
} catch {
    [Console]::Error.WriteLine('WINDOWS_STARTUP_PROBE_FAILED')
    $detail = $_.Exception
    for ($index = 0; $index -lt 4 -and $null -ne $detail.InnerException; $index++) { $detail = $detail.InnerException }
    if ($detail -is [System.ComponentModel.Win32Exception]) { [Console]::Error.WriteLine('WINDOWS_STARTUP_PROBE_WIN32:' + $detail.NativeErrorCode) }
    exit 1
}
