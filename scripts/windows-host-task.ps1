param(
    [ValidateSet('start', 'stop', 'status', 'restart')][string]$Action,
    [Parameter(Mandatory)][string]$NodePath,
    [Parameter(Mandatory)][string]$LauncherPath,
    [Parameter(Mandatory)][string]$WorkerPath,
    [Parameter(Mandatory)][string]$ConfigPath,
    [string]$LauncherRootsBase64 = '',
    [ValidateRange(1000, 45000)][int]$CommandBudgetMs = 45000
)
$ErrorActionPreference = 'Stop'
$commandClock = [System.Diagnostics.Stopwatch]::StartNew()
$processQueryCount = 0
$processOwnerCheckCount = 0
$trackedProcessCount = 0
function Get-HostCommandTime { return $commandClock.ElapsedMilliseconds }
function Assert-HostCommandBudget {
    if ((Get-HostCommandTime) -ge $CommandBudgetMs) { throw 'HOST_COMMAND_TIMEOUT' }
}
function Get-HostQueryTimeout {
    Assert-HostCommandBudget
    return [Math]::Max(1, [Math]::Min(5, [Math]::Ceiling(($CommandBudgetMs - (Get-HostCommandTime)) / 1000)))
}
function Get-HostProcesses([string]$Filter) {
    if ([string]::IsNullOrWhiteSpace($Filter)) { throw 'HOST_PROCESS_QUERY_REJECTED' }
    if ($script:processQueryCount -ge 256) { throw 'HOST_PROCESS_QUERY_LIMIT' }
    $script:processQueryCount++
    $timeout = Get-HostQueryTimeout
    try {
        $records = @(Get-CimInstance -ClassName Win32_Process -Filter $Filter -Property ProcessId, ParentProcessId, CreationDate, ExecutablePath, CommandLine, SessionId -OperationTimeoutSec $timeout)
    } catch {
        Assert-HostCommandBudget
        throw 'HOST_PROCESS_QUERY_FAILED'
    }
    Assert-HostCommandBudget
    return $records
}
$taskName = 'CodexManage Host Agent'
$arguments = '"' + $WorkerPath + '" "' + $ConfigPath + '"'
$launcherArguments = '"' + $NodePath + '" ' + $arguments
# Node supplies only roots whose logical/physical helper files and DACLs were
# validated. This covers the current root and the exact Codex MSIX cache root
# across packaged/unpackaged callers, never a root learned from a task action.
$helperRoot = [Regex]::Replace($LauncherPath, '[\\/][^\\/]+[\\/][^\\/]+$', '')
$LauncherRootsJson = if ([string]::IsNullOrWhiteSpace($LauncherRootsBase64)) { '' } else { [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($LauncherRootsBase64)) }
$helperRoots = if ([string]::IsNullOrWhiteSpace($LauncherRootsJson)) { @($helperRoot) } else { @(ConvertFrom-Json -InputObject $LauncherRootsJson) }
if ($helperRoots.Count -lt 1 -or $helperRoots.Count -gt 4 -or $helperRoot -notin $helperRoots) { throw 'Invalid private Host helper roots' }
foreach ($root in $helperRoots) {
    if ($root -isnot [string] -or [string]::IsNullOrWhiteSpace($root)) { throw 'Invalid private Host helper root' }
}
$rootPatterns = @($helperRoots | ForEach-Object { [Regex]::Escape($_) })
$launcherPattern = '^(?:' + ($rootPatterns -join '|') + ')[\\/][a-f0-9]{64}[\\/]host-launcher\.exe$'
if ($LauncherPath -notmatch $launcherPattern) { throw 'Invalid private Host launcher path' }
function Test-HostLauncher([string]$Path) { return $Path -match $launcherPattern }
function Get-HostCommandPattern($Words, [string]$Suffix = '') {
    $quoted = @($Words) | ForEach-Object {
        $escaped = [Regex]::Escape($_)
        '(?:"' + $escaped + '"|' + $escaped + ')'
    }
    return '^\s*' + ($quoted -join '\s+') + $Suffix + '\s*$'
}
function Test-CurrentUserId([string]$UserId, [string]$Sid) {
    if ($UserId -eq $Sid) { return $true }
    if ([string]::IsNullOrWhiteSpace($UserId)) { return $false }
    try {
        return [System.Security.Principal.NTAccount]::new($UserId).Translate([System.Security.Principal.SecurityIdentifier]).Value -eq $Sid
    } catch { return $false }
}

function Initialize-HostProcessIdentity {
    if ('AgentHub.HostProcessIdentityV1' -as [type]) { return }
    # Query the token of the SAME retained process handle used for cleanup.
    # WMI GetOwnerSid can take seconds per child on real Windows installations.
    # No impersonation, privilege adjustment, PID reopen, or account lookup.
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Security.Principal;
namespace AgentHub {
    public static class HostProcessIdentityV1 {
        [DllImport("advapi32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool OpenProcessToken(IntPtr process, uint access, out IntPtr token);
        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool CloseHandle(IntPtr handle);
        public static string Read(IntPtr process) {
            IntPtr token;
            if (!OpenProcessToken(process, 0x0008, out token))
                throw new Win32Exception(Marshal.GetLastWin32Error());
            try {
                using (var identity = new WindowsIdentity(token)) {
                    if (identity.User == null) throw new InvalidOperationException("Missing process SID");
                    return identity.User.Value;
                }
            } finally { CloseHandle(token); }
        }
    }
}
'@
}

function Add-HostProcesses($Candidates, $Tracked, [string]$Sid) {
    $opened = @{}
    try {
        foreach ($candidate in @($Candidates)) {
            Assert-HostCommandBudget
            if ($null -eq $candidate) { continue }
            $record = $candidate.Record
            if ($null -eq $record) { throw 'HOST_PROCESS_IDENTITY_MISSING' }
            $processId = [uint32]$record.ProcessId
            if ($processId -eq 0) { throw 'HOST_PROCESS_IDENTITY_MISSING' }
            if ($Tracked.ContainsKey($processId)) {
                if ($Tracked[$processId].Record.CreationDate -ne $record.CreationDate) { throw 'HOST_PROCESS_IDENTITY_CHANGED' }
                continue
            }
            if ($opened.ContainsKey($processId)) { continue }
            if (($Tracked.Count + $opened.Count) -ge 128) { throw 'HOST_PROCESS_LIMIT' }
            $nativeProcess = Get-Process -Id $processId -ErrorAction SilentlyContinue
            if ($null -eq $nativeProcess) { continue }
            # Retain ALL handles before the fresh, bounded identity query. A PID
            # reused before/after that query cannot redirect cleanup to a new process.
            $opened[$processId] = @{ Record = $record; Process = $nativeProcess; Depth = $candidate.Depth }
            try { $null = $nativeProcess.Handle }
            catch { if (-not $nativeProcess.HasExited) { throw } }
        }
        $ids = @($opened.Keys)
        for ($offset = 0; $offset -lt $ids.Count; $offset += 16) {
            Assert-HostCommandBudget
            $batch = @($ids | Select-Object -Skip $offset -First 16)
            $clauses = @($batch | ForEach-Object { "ProcessId = $_" })
            $currentById = @{}
            foreach ($current in @(Get-HostProcesses ($clauses -join ' OR '))) {
                $currentById[[uint32]$current.ProcessId] = $current
            }
            foreach ($processId in $batch) {
                Assert-HostCommandBudget
                $entry = $opened[$processId]
                $record = $entry.Record
                $nativeProcess = $entry.Process
                try {
                    if ($nativeProcess.HasExited) { continue }
                    $current = $currentById[$processId]
                    if ($null -eq $current) { continue }
                    if ($current.CreationDate -ne $record.CreationDate -or
                        $current.ExecutablePath -ne $record.ExecutablePath -or
                        $current.CommandLine -ne $record.CommandLine -or
                        [Math]::Abs(($nativeProcess.StartTime.ToUniversalTime() - $record.CreationDate.ToUniversalTime()).Ticks) -gt 10000) { throw 'HOST_PROCESS_IDENTITY_CHANGED' }
                    $script:processOwnerCheckCount++
                    try {
                        Initialize-HostProcessIdentity
                        $ownerSid = [AgentHub.HostProcessIdentityV1]::Read($nativeProcess.Handle)
                    }
                    catch { Assert-HostCommandBudget; throw 'HOST_PROCESS_OWNER_QUERY_FAILED' }
                    Assert-HostCommandBudget
                    if ($ownerSid -ne $Sid) { throw 'WRONG_HOST_PROCESS_OWNER' }
                    $Tracked[$processId] = @{ Record = $current; Process = $nativeProcess; Depth = $entry.Depth }
                    $script:trackedProcessCount = $Tracked.Count
                }
                catch { if (-not $nativeProcess.HasExited) { throw } }
            }
        }
    } finally {
        foreach ($processId in @($opened.Keys)) {
            $entry = $opened[$processId]
            if (-not $Tracked.ContainsKey($processId)) { $entry.Process.Dispose() }
        }
    }
}
function Update-HostRoots($Tracked, [string]$Sid) {
    # Provider-side Name filter and selected properties; never enumerate the whole machine.
    $nodeName = [Regex]::Match($NodePath, '[^\\/]+$').Value.Replace("'", "\'")
    $records = @(Get-HostProcesses "Name = '$nodeName' OR Name = 'host-launcher.exe'")
    $pattern = Get-HostCommandPattern @($NodePath, $WorkerPath, $ConfigPath) '(?:\s+--host-child)?'
    $candidates = @()
    foreach ($record in $records) {
        Assert-HostCommandBudget
        if ($record.ExecutablePath -eq $NodePath -and $record.CommandLine -match $pattern) {
            $candidates += @{ Record = $record; Depth = 0 }
        } elseif ((Test-HostLauncher $record.ExecutablePath) -and
            $record.CommandLine -match (Get-HostCommandPattern @($record.ExecutablePath, $NodePath, $WorkerPath, $ConfigPath))) {
            $candidates += @{ Record = $record; Depth = 0 }
        }
    }
    Add-HostProcesses $candidates $Tracked $Sid
}
function Update-HostDescendants($Tracked, [string]$Sid, [bool]$LiveOnly = $false) {
    $visited = @{}
    do {
        Assert-HostCommandBudget
        $pending = @($Tracked.Keys | Where-Object { -not $visited.ContainsKey($_) -and (-not $LiveOnly -or -not $Tracked[$_].Process.HasExited) })
        for ($offset = 0; $offset -lt $pending.Count; $offset += 16) {
            $ids = @($pending | Select-Object -Skip $offset -First 16)
            $clauses = @($ids | ForEach-Object { "ProcessId = $_ OR ParentProcessId = $_" })
            # Include the current parent record to reject reused parent IDs.
            $records = @(Get-HostProcesses ($clauses -join ' OR '))
            $byId = @{}
            foreach ($record in $records) { $byId[[uint32]$record.ProcessId] = $record }
            $candidates = @()
            foreach ($record in $records) {
                $parent = $Tracked[[uint32]$record.ParentProcessId]
                $currentParent = $byId[[uint32]$record.ParentProcessId]
                if ($null -ne $currentParent -and $null -ne $parent -and $currentParent.CreationDate -ne $parent.Record.CreationDate) { continue }
                if ($null -ne $parent -and $record.CreationDate -ge $parent.Record.CreationDate -and
                    $record.SessionId -eq $parent.Record.SessionId) {
                    $candidates += @{ Record = $record; Depth = ($parent.Depth + 1) }
                }
            }
            Add-HostProcesses $candidates $Tracked $Sid
            foreach ($processId in $ids) { $visited[$processId] = $true }
        }
    } while ($pending.Count -gt 0)
}
function Stop-HostSupervisors($Tracked) {
    foreach ($entry in @($Tracked.Values)) {
        Assert-HostCommandBudget
        if ($entry.Record.CommandLine -notmatch '\s+--host-child\s*$' -and
            $entry.Depth -eq 0 -and
            -not $entry.Process.HasExited) {
            try { $entry.Process.Kill() } catch { if (-not $entry.Process.HasExited) { throw } }
        }
    }
}
function Wait-HostProcessExit($Tracked, [int]$WaitMs) {
    $until = [Math]::Min($CommandBudgetMs, (Get-HostCommandTime) + $WaitMs)
    while (@($Tracked.Values | Where-Object { -not $_.Process.WaitForExit(0) }).Count -gt 0) {
        Assert-HostCommandBudget
        if ((Get-HostCommandTime) -ge $until) { return $false }
        Start-Sleep -Milliseconds 100
    }
    return $true
}
function Stop-HostProcessTree($Task, [string]$Sid) {
    $tracked = @{}
    $wasRunning = $null -ne $Task -and $Task.State -eq 'Running'
    try {
        Assert-HostCommandBudget
        # Disable first: login/start-condition retries cannot race a stop/restart.
        if ($null -ne $Task) { $null = Disable-ScheduledTask -TaskName $taskName }
        Assert-HostCommandBudget
        Update-HostRoots $tracked $Sid
        Update-HostDescendants $tracked $Sid
        if ($wasRunning) {
            Assert-HostCommandBudget
            Stop-ScheduledTask -TaskName $taskName
            $taskUntil = [Math]::Min($CommandBudgetMs, (Get-HostCommandTime) + 5000)
            while ((Get-ScheduledTask -TaskName $taskName).State -eq 'Running') {
                Assert-HostCommandBudget
                if ((Get-HostCommandTime) -ge $taskUntil) { throw 'HOST_STOP_TIMEOUT' }
                Start-Sleep -Milliseconds 100
            }
            Assert-HostCommandBudget
        }
        Stop-HostSupervisors $tracked
        # Capture a child born during the first snapshot/task stop, even if its
        # retained parent has since exited. No repeated root or whole-machine scan.
        Update-HostDescendants $tracked $Sid
        $graceful = Wait-HostProcessExit $tracked 5000
        if (-not $graceful) { Update-HostDescendants $tracked $Sid $true }
        # One final narrow root probe catches a disconnected --host-child.
        $before = $tracked.Count
        Update-HostRoots $tracked $Sid
        if ($tracked.Count -gt $before) {
            Stop-HostSupervisors $tracked
            Update-HostDescendants $tracked $Sid
        }
        foreach ($entry in @($tracked.Values | Sort-Object -Property Depth -Descending)) {
            Assert-HostCommandBudget
            if (-not $entry.Process.HasExited) {
                try { $entry.Process.Kill() } catch { if (-not $entry.Process.HasExited) { throw } }
            }
        }
        # A single shared exit wait, not one second multiplied by every process.
        if (-not (Wait-HostProcessExit $tracked 2000)) { throw 'HOST_CHILD_STOP_TIMEOUT' }
        Assert-HostCommandBudget
    } finally {
        foreach ($entry in @($tracked.Values)) { $entry.Process.Dispose() }
    }
}

try {
    Assert-HostCommandBudget
    $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $task = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    if ($null -ne $task) {
        $legacyAction = $task.Actions.Count -eq 1 -and $task.Actions[0].Execute -eq $NodePath -and $task.Actions[0].Arguments -eq $arguments
        $hiddenAction = $task.Actions.Count -eq 1 -and (Test-HostLauncher $task.Actions[0].Execute) -and $task.Actions[0].Arguments -eq $launcherArguments
        if (-not $legacyAction -and -not $hiddenAction) { throw 'UNRELATED_SCHEDULED_TASK' }
        if (-not (Test-CurrentUserId $task.Principal.UserId $sid)) { throw 'WRONG_TASK_OWNER' }
    }
    Assert-HostCommandBudget
    if ($Action -eq 'stop' -or $Action -eq 'restart') { Stop-HostProcessTree $task $sid }
    if ($Action -eq 'start' -or $Action -eq 'restart') {
        Assert-HostCommandBudget
        $loginTrigger = New-ScheduledTaskTrigger -AtLogOn -User $sid
        # RestartOnFailure is not an outer supervisor liveness guarantee (e.g.
        # 0xC000013A). Re-enter the SAME task every minute; IgnoreNew below
        # preserves a running instance. No Duration/EndBoundary means no expiry.
        $recoveryTrigger = New-ScheduledTaskTrigger -Once -At ([datetime]::Now.AddMinutes(1)) -RepetitionInterval ([TimeSpan]::FromMinutes(1))
        $recoveryTrigger.Repetition.StopAtDurationEnd = $false
        $triggers = @($loginTrigger, $recoveryTrigger)
        # Task Scheduler defaults to7 (BelowNormal), inherited by Node and its
        # native helpers. This interactive user service needs ordinary scheduling
        # for bounded IPC;4 is Normal, with the same limited user token.
        $settings = New-ScheduledTaskSettingsSet -Priority 4 -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval ([TimeSpan]::FromMinutes(1)) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -MultipleInstances IgnoreNew
        $jobAction = New-ScheduledTaskAction -Execute $LauncherPath -Argument $launcherArguments -WorkingDirectory (Split-Path -Parent $WorkerPath)
        if ($null -eq $task) {
            $principal = New-ScheduledTaskPrincipal -UserId $sid -LogonType Interactive -RunLevel Limited
            $null = Register-ScheduledTask -TaskName $taskName -Action $jobAction -Principal $principal -Trigger $triggers -Settings $settings
        } else {
            # Updating the action preserves a running legacy Node; restart
            # applies the launcher immediately. Owner and private paths stay.
            $null = Set-ScheduledTask -TaskName $taskName -Action $jobAction -Trigger $triggers -Settings $settings
        }
        Assert-HostCommandBudget
        $null = Enable-ScheduledTask -TaskName $taskName
        Assert-HostCommandBudget
        if ((Get-ScheduledTask -TaskName $taskName).State -ne 'Running') { Assert-HostCommandBudget; Start-ScheduledTask -TaskName $taskName }
    }
    $task = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    $loginAutostart = $false
    $periodicRecovery = $false
    if ($null -ne $task -and $task.Settings.Enabled) {
        foreach ($trigger in $task.Triggers) {
            if ($trigger.Enabled -and $trigger.CimClass.CimClassName -eq 'MSFT_TaskLogonTrigger' -and (Test-CurrentUserId $trigger.UserId $sid)) { $loginAutostart = $true }
            if ($trigger.Enabled -and $trigger.CimClass.CimClassName -eq 'MSFT_TaskTimeTrigger' -and
                [string]::IsNullOrWhiteSpace($trigger.EndBoundary) -and
                [string]::IsNullOrWhiteSpace($trigger.Repetition.Duration) -and
                -not $trigger.Repetition.StopAtDurationEnd -and
                ($task.Settings.MultipleInstances -eq 2 -or [string]$task.Settings.MultipleInstances -eq 'IgnoreNew')) {
                try {
                    if ([System.Xml.XmlConvert]::ToTimeSpan($trigger.Repetition.Interval) -eq [TimeSpan]::FromMinutes(1)) { $periodicRecovery = $true }
                } catch { # Malformed/legacy schedules are not registered recovery evidence.
                }
            }
        }
    }
    $info = if ($null -ne $task) { Get-ScheduledTaskInfo -TaskName $taskName } else { $null }
    $consoleLaunch = if ($null -eq $task) { 'not-installed' } elseif (Test-HostLauncher $task.Actions[0].Execute) { 'native-hidden-wait' } else { 'legacy-console' }
    Assert-HostCommandBudget
    @{ taskPriority = if ($null -ne $task) { $task.Settings.Priority } else { $null }; consoleLaunch = $consoleLaunch; recoveryPolicy = 'observed-exit-restart'; outerRecoveryPolicy = 'periodic-trigger-ignore-running'; periodicRecovery = $periodicRecovery; periodicRecoveryInterval = if ($periodicRecovery) { 'PT1M' } else { $null }; liveHttpRecovery = 'diagnostic-only'; desktopRefresh = 'validated-at-worker-start'; commandElapsedMs = Get-HostCommandTime; commandBudgetMs = $CommandBudgetMs; processQueries = $processQueryCount; ownerChecks = $processOwnerCheckCount; trackedProcesses = $trackedProcessCount; installed = $null -ne $task; running = $null -ne $task -and $task.State -eq 'Running'; loginAutostart = $loginAutostart; task = $taskName; lastTaskResult = if ($null -ne $info) { $info.LastTaskResult } else { $null }; lastRunTime = if ($null -ne $info) { $info.LastRunTime.ToString('o') } else { $null }; nextRunTime = if ($null -ne $info -and $null -ne $info.NextRunTime) { $info.NextRunTime.ToString('o') } else { $null }; restartCount = if ($null -ne $task) { $task.Settings.RestartCount } else { $null }; restartInterval = if ($null -ne $task) { $task.Settings.RestartInterval } else { $null } } | ConvertTo-Json -Compress
} catch {
    $known = @('UNRELATED_SCHEDULED_TASK', 'WRONG_TASK_OWNER', 'HOST_PROCESS_IDENTITY_CHANGED', 'WRONG_HOST_PROCESS_OWNER', 'HOST_STOP_TIMEOUT', 'HOST_CHILD_STOP_TIMEOUT', 'HOST_TREE_STOP_TIMEOUT', 'HOST_COMMAND_TIMEOUT', 'HOST_PROCESS_QUERY_REJECTED', 'HOST_PROCESS_QUERY_LIMIT', 'HOST_PROCESS_LIMIT', 'HOST_PROCESS_QUERY_FAILED', 'HOST_PROCESS_OWNER_QUERY_FAILED')
    $reason = if ($_.Exception.Message -in $known) { $_.Exception.Message } else { 'UNAVAILABLE' }
    [Console]::Error.WriteLine(('WINDOWS_HOST_TASK_REJECTED {0} elapsedMs={1} processQueries={2} ownerChecks={3}' -f $reason, (Get-HostCommandTime), $processQueryCount, $processOwnerCheckCount))
    exit 1
}
