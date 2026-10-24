param(
    [Parameter(Mandatory)][string]$ScriptPath,
    [ValidateSet('lifecycle', 'unrelated', 'wrong-owner', 'stop-tree', 'wrong-process-owner', 'owner-query-failed', 'batch-identity-drift', 'wide-tree', 'pid-reuse', 'stop-timeout', 'wait-kernel-signal', 'wait-kernel-timeout', 'wait-kernel-throw', 'slow-queries', 'command-budget', 'late-child', 'outer-recovery', 'launcher-migration', 'launcher-tree', 'unrelated-launcher', 'msix-paths', 'msix-other-root', 'msix-wrong-owner', 'msix-wrong-arguments')][string]$Scenario = 'lifecycle'
)
# Execute the real task script; only Windows identity and Task Scheduler calls are mocked.
$ErrorActionPreference = 'Stop'
$global:FixtureTask = $null
$global:FixtureStarts = 0
$global:FixtureStops = 0
$global:FixtureWrites = 0
$global:FixtureProcesses = @{}
$global:FixtureOrder = @()
$global:FixtureElapsedMs = 0
$global:FixtureRootQueries = 0
$global:FixtureQueries = 0
$global:FixtureOwners = 0
$global:FixtureOpenedHandles = @()
$global:FixtureDisposedHandles = @()
$global:FixtureLastResult = 0
$global:FixtureHelperRootsJson = ''
$global:FixtureWaitSamples = @()
$global:FixtureWaitThrows = 0
$global:FixtureActualWaitExceptions = 0
$global:FixtureKernelSignalAtMs = $null
$global:FixtureKernelExitCodeAtMs = $null
$node = 'C:\Fixture Private\node.exe'
$worker = 'C:\Fixture Private\windows-desktop-worker.mjs'
$config = 'C:\Fixture Private\windows-host.json'
$launcher = 'C:\Fixture Private\native-helpers\' + ('a' * 64) + '\host-launcher.exe'
function Assert-Fixture($Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Get-ScheduledTask {
    if ($Scenario -eq 'slow-queries') { $global:FixtureElapsedMs += 1000 }
    $global:FixtureTask
}
function New-ScheduledTaskAction($Execute, $Argument, $WorkingDirectory) {
    [pscustomobject]@{ Execute = $Execute; Arguments = $Argument; WorkingDirectory = $WorkingDirectory }
}
function New-ScheduledTaskPrincipal($UserId, $LogonType, $RunLevel) {
    [pscustomobject]@{ UserId = $UserId; LogonType = $LogonType; RunLevel = $RunLevel }
}
function New-ScheduledTaskTrigger([switch]$AtLogOn, [switch]$Once, $User, [datetime]$At = [datetime]::MinValue, [TimeSpan]$RepetitionInterval = [TimeSpan]::Zero, [TimeSpan]$RepetitionDuration = [TimeSpan]::Zero) {
    if ($AtLogOn) {
        Assert-Fixture (-not $Once) 'Login trigger must remain separate'
        return [pscustomobject]@{ UserId = $User; Enabled = $true; CimClass = @{ CimClassName = 'MSFT_TaskLogonTrigger' } }
    }
    Assert-Fixture $Once 'Recovery must be a time trigger on the same task'
    Assert-Fixture (-not $PSBoundParameters.ContainsKey('RepetitionDuration')) 'Recovery cannot expire'
    [pscustomobject]@{
        Enabled = $true; StartBoundary = $At.ToString('o'); EndBoundary = $null
        CimClass = @{ CimClassName = 'MSFT_TaskTimeTrigger' }
        Repetition = [pscustomobject]@{ Interval = [System.Xml.XmlConvert]::ToString($RepetitionInterval); Duration = $null; StopAtDurationEnd = $false }
    }
}
function New-ScheduledTaskSettingsSet($ExecutionTimeLimit, $RestartCount, $RestartInterval, [switch]$AllowStartIfOnBatteries, [switch]$DontStopIfGoingOnBatteries, [switch]$StartWhenAvailable, $MultipleInstances) {
    [pscustomobject]@{
        Enabled = $true; ExecutionTimeLimit = $ExecutionTimeLimit; RestartCount = $RestartCount
        RestartInterval = $RestartInterval; AllowBattery = [bool]$AllowStartIfOnBatteries
        StayOnBattery = [bool]$DontStopIfGoingOnBatteries; StartWhenAvailable = [bool]$StartWhenAvailable
        MultipleInstances = $MultipleInstances
    }
}
function Register-ScheduledTask($TaskName, $Action, $Principal, $Trigger, $Settings) {
    Assert-Fixture ($TaskName -eq 'CodexManage Host Agent') 'Unexpected task'
    $global:FixtureWrites++
    $global:FixtureTask = [pscustomobject]@{ Actions = @($Action); Principal = $Principal; Triggers = @($Trigger); Settings = $Settings; State = 'Ready' }
}
function Set-ScheduledTask($TaskName, $Action, $Trigger, $Settings) {
    $global:FixtureWrites++
    $global:FixtureTask.Actions = @($Action)
    $global:FixtureTask.Triggers = @($Trigger)
    $global:FixtureTask.Settings = $Settings
}
function Enable-ScheduledTask {
    $global:FixtureWrites++
    $global:FixtureTask.Settings.Enabled = $true
    if ($global:FixtureTask.State -eq 'Disabled') { $global:FixtureTask.State = 'Ready' }
}
function Disable-ScheduledTask {
    $global:FixtureOrder += 'disable'
    $global:FixtureWrites++
    $global:FixtureTask.Settings.Enabled = $false
    $global:FixtureTask.State = 'Disabled'
}
function Start-ScheduledTask {
    if ($Scenario -in @('wait-kernel-signal', 'wait-kernel-timeout', 'wait-kernel-throw') -and $global:FixtureProcesses.ContainsKey(101)) {
        Assert-Fixture $global:FixtureProcesses[101].Native.KernelSignaled 'Restart preceded the retained kernel signal'
    }
    $global:FixtureStarts++; $global:FixtureTask.State = 'Running'
}
function Stop-ScheduledTask {
    Assert-Fixture (-not $global:FixtureTask.Settings.Enabled) 'Disable must precede task stop'
    $global:FixtureOrder += 'task-stop'
    $global:FixtureStops++
    $global:FixtureTask.State = 'Ready'
    if ($global:FixtureProcesses.ContainsKey(100)) {
        $global:FixtureProcesses[100].Native.HasExited = $true
        $global:FixtureProcesses[100].Native.KernelSignaled = $true
    }
    if ($Scenario -eq 'late-child') { Add-FixtureProcess 104 101 'late-private-helper' 'powershell.exe' }
}
function Get-ScheduledTaskInfo { [pscustomobject]@{ LastTaskResult = $global:FixtureLastResult; LastRunTime = [datetime]'2026-10-11T00:00:00Z'; NextRunTime = [datetime]'2026-10-18T00:01:00Z' } }
function Get-CimInstance($ClassName, $Filter, $Property, $OperationTimeoutSec) {
    Assert-Fixture ($ClassName -eq 'Win32_Process') 'Unexpected process query'
    Assert-Fixture ($OperationTimeoutSec -ge 1 -and $OperationTimeoutSec -le 5) 'Native query timeout missing'
    Assert-Fixture ($Property -contains 'CreationDate' -and $Property -contains 'CommandLine') 'Identity properties missing'
    Assert-Fixture (-not [string]::IsNullOrWhiteSpace($Filter)) 'Full-machine scan is forbidden'
    $global:FixtureQueries++
    if ($Scenario -in @('slow-queries', 'command-budget', 'wide-tree')) { $global:FixtureElapsedMs += 2000 }
    $records = @($global:FixtureProcesses.Values | Where-Object { -not $_.Native.HasExited } | ForEach-Object { $_.Record })
    if ($Filter -match "^Name = 'node.exe' OR Name = 'host-launcher.exe'$") {
        $global:FixtureRootQueries++
        $records | Where-Object { $_.Name -in @('node.exe', 'host-launcher.exe') }
    } else {
        $clauses = [Regex]::Matches($Filter, '(ProcessId|ParentProcessId) = (\d+)')
        Assert-Fixture ($clauses.Count -gt 0 -and $clauses.Count -le 32) 'Unbounded parent query'
        if ($Filter -notmatch 'ParentProcessId') { Assert-Fixture ($clauses.Count -le 16) 'Identity batches must be bounded' }
        foreach ($record in $records) {
            if ($Scenario -eq 'batch-identity-drift' -and $Filter -notmatch 'ParentProcessId' -and $record.ProcessId -eq 101) {
                $record = $record | Select-Object *
                $record.CommandLine += ' changed-after-handle-retained'
            }
            foreach ($clause in $clauses) {
                if ($record.($clause.Groups[1].Value) -eq [uint32]$clause.Groups[2].Value) { $record; break }
            }
        }
    }
}
function Get-FixtureHostOwnerSid([IntPtr]$ProcessHandle) {
    $global:FixtureOwners++
    if ($Scenario -eq 'owner-query-failed') { throw 'Native token query denied' }
    if ($Scenario -eq 'slow-queries') { $global:FixtureElapsedMs += 1000 }
    Assert-Fixture ($global:FixtureProcesses.ContainsKey($ProcessHandle.ToInt32())) 'Owner must use the retained handle'
    $global:FixtureProcesses[$ProcessHandle.ToInt32()].Sid
}
function Get-Process($Id) {
    $native = $global:FixtureProcesses[[int]$Id].Native
    if ($null -ne $native) { $global:FixtureOpenedHandles += [int]$Id }
    $native
}
function Start-Sleep($Milliseconds) {
    $global:FixtureElapsedMs += $Milliseconds
    # The synthetic OS signal changes independently of the observed HasExited value.
    if ($null -ne $global:FixtureKernelSignalAtMs -and $global:FixtureElapsedMs -ge $global:FixtureKernelSignalAtMs) {
        $global:FixtureProcesses[101].Native.KernelSignaled = $true
        $global:FixtureOrder += 'kernel-signaled-101'
        $global:FixtureKernelSignalAtMs = $null
    }
}
function Add-FixtureProcess([int]$Id, [int]$Parent, [string]$Command, [string]$Executable, [string]$Sid = 'fixture-sid', [int]$Offset = 0) {
    $created = ([datetime]'2026-10-11T00:00:00Z').AddSeconds($Id)
    $native = [pscustomobject]@{ Id = $Id; Handle = [IntPtr]$Id; HasExited = $false; KernelSignaled = $false; StartTime = $created.AddSeconds($Offset) }
    $native | Add-Member -MemberType ScriptMethod -Name Kill -Value {
        Assert-Fixture (-not $global:FixtureTask.Settings.Enabled) 'Disable must precede forced cleanup'
        Assert-Fixture ($global:FixtureProcesses[[int]$this.Id].Sid -eq 'fixture-sid') 'Cleanup touched an unrelated process owner'
        $global:FixtureOrder += "kill-$($this.Id)"
        $this.HasExited = $true
        $this.KernelSignaled = $true
        if ($this.Id -eq 101 -and $Scenario -in @('wait-kernel-signal', 'wait-kernel-timeout', 'wait-kernel-throw')) {
            $this.KernelSignaled = $false
            $global:FixtureKernelExitCodeAtMs = $global:FixtureElapsedMs
            if ($Scenario -eq 'wait-kernel-signal') { $global:FixtureKernelSignalAtMs = $global:FixtureElapsedMs + 200 }
        }
    }
    $native | Add-Member -MemberType ScriptMethod -Name WaitForExit -Value {
        param($Timeout)
        Assert-Fixture ($Timeout -eq 0) 'Shared wait must poll with zero per-process timeout'
        if ($Scenario -in @('wait-kernel-signal', 'wait-kernel-timeout', 'wait-kernel-throw')) {
            $global:FixtureWaitSamples += @{ id=[int]$this.Id; timeout=[int]$Timeout; hasExited=$this.HasExited; kernelSignaled=$this.KernelSignaled; atMs=$global:FixtureElapsedMs }
            if ($Scenario -eq 'wait-kernel-throw' -and $this.Id -eq 101 -and $this.HasExited) {
                $global:FixtureWaitThrows++
                # A real local .NET method failure, without starting/opening any OS process.
                $disposed = New-Object System.Diagnostics.Process
                $disposed.Dispose()
                try { $disposed.WaitForExit(0) }
                catch { $global:FixtureActualWaitExceptions++; throw }
                finally { $disposed.Dispose() }
            }
        }
        $this.KernelSignaled
    }
    $native | Add-Member -MemberType ScriptMethod -Name Dispose -Value { $global:FixtureDisposedHandles += [int]$this.Id }
    $global:FixtureProcesses[$Id] = @{
        Native = $native; Sid = $Sid
        Record = [pscustomobject]@{ Name = [Regex]::Match($Executable, '[^\\/]+$').Value; ProcessId = [uint32]$Id; ParentProcessId = [uint32]$Parent; CreationDate = $created; CommandLine = $Command; ExecutablePath = $Executable; SessionId = 1 }
    }
}
function Invoke-Fixture([string]$Action) {
    $global:FixtureElapsedMs = 0
    $global:FixtureRootQueries = 0
    $global:FixtureQueries = 0
    $global:FixtureOwners = 0
    $global:FixtureOpenedHandles = @()
    $global:FixtureDisposedHandles = @()
    $global:LASTEXITCODE = 0
    $output = & $ScriptPath -Action $Action -NodePath $node -LauncherPath $launcher -WorkerPath $worker -ConfigPath $config -LauncherRootsBase64 ([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($FixtureHelperRootsJson)))
    Assert-Fixture ($LASTEXITCODE -eq 0) 'Task script rejected a valid fixture'
    $output | ConvertFrom-Json
}

# Deliver one mocked scheduler occurrence. This is not Windows timing evidence.
function Invoke-FixtureRecoveryTick {
    if (-not $FixtureTask.Settings.Enabled) { return }
    foreach ($trigger in $FixtureTask.Triggers) {
        if ($trigger.Enabled -and $trigger.CimClass.CimClassName -eq 'MSFT_TaskTimeTrigger' -and
            $trigger.Repetition.Interval -eq 'PT1M' -and
            [string]::IsNullOrWhiteSpace($trigger.Repetition.Duration) -and
            [string]::IsNullOrWhiteSpace($trigger.EndBoundary)) {
            if ($FixtureTask.State -eq 'Running' -and $FixtureTask.Settings.MultipleInstances -eq 'IgnoreNew') { return }
            Start-ScheduledTask
        }
    }
}
if ($Scenario -in @('wait-kernel-signal', 'wait-kernel-timeout', 'wait-kernel-throw')) {
    $null = Invoke-Fixture start
    $rootArguments = '"' + $node + '" "' + $worker + '" "' + $config + '"'
    Add-FixtureProcess 100 1 $rootArguments $node
    Add-FixtureProcess 101 100 ($rootArguments + ' --host-child') $node
    Add-FixtureProcess 200 1 'original-official-desktop' 'Codex.exe'
    Add-FixtureProcess 201 1 ('"' + $node + '" "' + $worker + '" "other-config"') $node
    $global:LASTEXITCODE = 0
    $output = & $ScriptPath -Action restart -NodePath $node -LauncherPath $launcher -WorkerPath $worker -ConfigPath $config
    $early = @($FixtureWaitSamples | Where-Object { $_.id -eq 101 -and $_.hasExited -and -not $_.kernelSignaled })
    Assert-Fixture ($early.Count -gt 0) 'HasExited=true bypassed the independent retained kernel wait'
    Assert-Fixture ((($FixtureOpenedHandles | Sort-Object) -join ',') -eq (($FixtureDisposedHandles | Sort-Object) -join ',')) 'Retained wait leaked or double-disposed handles'
    foreach ($Id in @(200, 201)) { Assert-Fixture (-not $FixtureProcesses[$Id].Native.HasExited -and -not $FixtureProcesses[$Id].Native.KernelSignaled) 'Retained wait touched unrelated process' }
    if ($Scenario -eq 'wait-kernel-signal') {
        Assert-Fixture ($LASTEXITCODE -eq 0) 'Signal arriving inside the shared wait must permit restart'
        $result = $output | ConvertFrom-Json
        Assert-Fixture ($FixtureStarts -eq 2 -and $FixtureProcesses[101].Native.KernelSignaled) 'Restart failed to wait for the later kernel signal'
        Assert-Fixture (@($FixtureOrder | Where-Object { $_ -eq 'kernel-signaled-101' }).Count -eq 1) 'Independent signal not delivered exactly once'
        Assert-Fixture (($FixtureElapsedMs - $FixtureKernelExitCodeAtMs) -eq 200 -and $result.commandBudgetMs -eq 45000) 'Restart did not wait for the independent signal on100ms polls'
        @{ scenario=$Scenario; passed=$true; independentSignal=$true; earlyFalseSamples=$early.Count; elapsedMs=$FixtureElapsedMs } | ConvertTo-Json -Compress
    } else {
        Assert-Fixture ($LASTEXITCODE -eq 1 -and $FixtureStarts -eq 1 -and -not $FixtureTask.Settings.Enabled) 'Failed kernel wait must reject and prevent restart'
        Assert-Fixture (-not $FixtureProcesses[101].Native.KernelSignaled) 'Failed wait fabricated the kernel signal'
        if ($Scenario -eq 'wait-kernel-throw') { Assert-Fixture ($FixtureWaitThrows -eq 1 -and $FixtureActualWaitExceptions -eq 1) 'Disposed Process.WaitForExit exception was swallowed or replayed' }
        else { Assert-Fixture (($FixtureElapsedMs - $FixtureKernelExitCodeAtMs) -ge 0 -and ($FixtureElapsedMs - $FixtureKernelExitCodeAtMs) -le 2000) 'Per-process waiting multiplied the final shared2000ms deadline' }
        @{ scenario=$Scenario; rejected=$true; restartPrevented=$true; waitThrows=$FixtureWaitThrows; actualMethodExceptions=$FixtureActualWaitExceptions; elapsedMs=$FixtureElapsedMs } | ConvertTo-Json -Compress
    }
    exit 0
}

if ($Scenario -eq 'outer-recovery') {
    $first = Invoke-Fixture start
    Assert-Fixture ($first.periodicRecovery -and $first.periodicRecoveryInterval -eq 'PT1M' -and $first.loginAutostart) 'Both registered recovery and login required'
    Assert-Fixture ($FixtureTask.Triggers.Count -eq 2) 'Exactly two triggers on the existing single task'
    $originalAction = $FixtureTask.Actions[0] | ConvertTo-Json -Compress
    $originalPrincipal = $FixtureTask.Principal | ConvertTo-Json -Compress
    # Supervisor itself ended; there is no child-supervisor retry in this scenario.
    $FixtureTask.State = 'Ready'
    $global:FixtureLastResult = [uint32]3221225786
    $ended = Invoke-Fixture status
    Assert-Fixture (-not $ended.running -and $ended.periodicRecovery -and $ended.lastTaskResult -eq 3221225786) '0xC000013A/Ready must remain visible'
    Invoke-FixtureRecoveryTick
    Assert-Fixture ($FixtureStarts -eq 2 -and $FixtureTask.State -eq 'Running') 'Periodic occurrence did not restore the absent outer supervisor'
    Invoke-FixtureRecoveryTick
    Assert-Fixture ($FixtureStarts -eq 2) 'Periodic occurrence replaced a live supervisor'
    $stopped = Invoke-Fixture stop
    Assert-Fixture (-not $stopped.periodicRecovery -and -not $stopped.loginAutostart) 'Explicit stop must disable all automatic entry points'
    Invoke-FixtureRecoveryTick
    Assert-Fixture ($FixtureStarts -eq 2) 'A disabled task recovered without explicit start'
    $null = Invoke-Fixture start
    $null = Invoke-Fixture start
    Assert-Fixture ($FixtureStarts -eq 3 -and $FixtureTask.Triggers.Count -eq 2) 'Start must re-enable without duplicate launches/triggers'
    $periodic = $FixtureTask.Triggers[1]
    foreach ($invalid in @('disabled', 'finite', 'ending', 'stop-at-end', 'wrong-interval', 'malformed', 'parallel')) {
        $periodic.Enabled = $true; $periodic.Repetition.Duration = $null; $periodic.EndBoundary = $null; $periodic.Repetition.StopAtDurationEnd = $false; $periodic.Repetition.Interval = 'PT1M'; $FixtureTask.Settings.MultipleInstances = 'IgnoreNew'
        switch ($invalid) {
            'disabled' { $periodic.Enabled = $false }
            'finite' { $periodic.Repetition.Duration = 'P1D' }
            'ending' { $periodic.EndBoundary = '2026-10-18T00:00:00' }
            'stop-at-end' { $periodic.Repetition.StopAtDurationEnd = $true }
            'wrong-interval' { $periodic.Repetition.Interval = 'PT2M' }
            'malformed' { $periodic.Repetition.Interval = 'invalid-duration' }
            'parallel' { $FixtureTask.Settings.MultipleInstances = 'Parallel' }
        }
        $status = Invoke-Fixture status
        Assert-Fixture (-not $status.periodicRecovery -and $status.loginAutostart -and $status.running) 'Invalid recovery reported as configured, or unrelated status changed'
    }
    # Repeated start/restart must preserve this repair, including upgrading a
    # legacy login-only task, without changing private action/principal fields.
    $FixtureTask.Triggers = @($FixtureTask.Triggers[0])
    Assert-Fixture (-not (Invoke-Fixture status).periodicRecovery) 'Legacy login-only task appeared protected'
    $null = Invoke-Fixture start
    Assert-Fixture ($FixtureStarts -eq 3 -and (Invoke-Fixture status).periodicRecovery) 'Legacy upgrade interrupted a running task'
    $FixtureTask.Settings.MultipleInstances = 2
    Assert-Fixture ((Invoke-Fixture status).periodicRecovery) 'Native numeric IgnoreNew policy rejected'
    $null = Invoke-Fixture restart
    Assert-Fixture ($FixtureStarts -eq 4 -and $FixtureStops -eq 2 -and $FixtureTask.Triggers.Count -eq 2 -and (Invoke-Fixture status).periodicRecovery) 'Restart lost outer recovery'
    Assert-Fixture (($FixtureTask.Actions[0] | ConvertTo-Json -Compress) -eq $originalAction -and ($FixtureTask.Principal | ConvertTo-Json -Compress) -eq $originalPrincipal) 'Outer recovery changed private paths/identity'
    @{ scenario = $Scenario; passed = $true; simulatedExitCode = 3221225786; outerSupervisorRecovery = 'mocked-task-scheduler'; existingInstancePreserved = $true; explicitStopPreserved = $true } | ConvertTo-Json -Compress
    exit 0
}

if ($Scenario -like 'msix-*') {
    $normal = $launcher
    $physicalRoot = 'C:\Fixture Private\Packages\OpenAI.Codex_fixture\LocalCache\Local\CodexManage\native-helpers'
    $physical = $physicalRoot + '\' + ('a' * 64) + '\host-launcher.exe'
    $global:FixtureHelperRootsJson = @('C:\Fixture Private\native-helpers', $physicalRoot) | ConvertTo-Json -Compress
    $null = Invoke-Fixture start
    $FixtureTask.Actions[0].Execute = $physical # Source's precise temporary correction.
    $beforeOwner = $FixtureTask.Principal | ConvertTo-Json -Compress
    $beforeArgs = $FixtureTask.Actions[0].Arguments
    if ($Scenario -ne 'msix-paths') {
        switch ($Scenario) {
            'msix-other-root' { $FixtureTask.Actions[0].Execute = $physical.Replace('OpenAI.Codex_fixture', 'Other.Agent_fixture') }
            'msix-wrong-owner' { $FixtureTask.Principal.UserId = 'other-fixture-owner' }
            'msix-wrong-arguments' { $FixtureTask.Actions[0].Arguments = $beforeArgs.Replace($config, 'other-config') }
        }
        $writes = $FixtureWrites
        foreach ($verb in @('status', 'start', 'stop')) {
            $global:LASTEXITCODE = 0
            $null = & $ScriptPath -Action $verb -NodePath $node -LauncherPath $launcher -WorkerPath $worker -ConfigPath $config -LauncherRootsBase64 ([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($FixtureHelperRootsJson)))
            Assert-Fixture ($LASTEXITCODE -eq 1) 'Unverified MSIX task was accepted'
        }
        Assert-Fixture ($FixtureWrites -eq $writes -and $FixtureStarts -eq 1 -and $FixtureStops -eq 0) 'Rejected MSIX task was mutated'
        @{ scenario = $Scenario; rejected = $true; mutations = 0 } | ConvertTo-Json -Compress
        exit 0
    }
    Assert-Fixture ((Invoke-Fixture status).running) 'Physical legacy action rejected by external context'
    $null = Invoke-Fixture start
    Assert-Fixture ($FixtureStarts -eq 1 -and $FixtureStops -eq 0 -and $FixtureTask.Actions[0].Execute -eq $normal) 'Start interrupted a live legacy launcher or retained wrong next action'
    Assert-Fixture (($FixtureTask.Principal | ConvertTo-Json -Compress) -eq $beforeOwner -and $FixtureTask.Actions[0].Arguments -eq $beforeArgs) 'MSIX migration changed owner or private parameters'
    $rootArgs = '"' + $node + '" "' + $worker + '" "' + $config + '"'
    Add-FixtureProcess 90 1 ('"' + $physical + '" ' + $rootArgs) $physical
    Add-FixtureProcess 100 90 $rootArgs $node
    Add-FixtureProcess 101 100 ($rootArgs + ' --host-child') $node
    $other = $physical.Replace('OpenAI.Codex_fixture', 'Other.Agent_fixture')
    Add-FixtureProcess 201 1 ('"' + $other + '" ' + $rootArgs) $other
    $null = Invoke-Fixture stop
    foreach ($Id in @(90, 100, 101)) { Assert-Fixture $FixtureProcesses[$Id].Native.HasExited 'Physical launcher tree escaped stop' }
    Assert-Fixture (-not $FixtureProcesses[201].Native.HasExited) 'Different MSIX package process stopped'
    # A packaged caller now selects its physical executable for registration;
    # either caller can still status/stop this same task afterwards.
    $launcher = $physical
    $null = Invoke-Fixture start
    Assert-Fixture ($FixtureTask.Actions[0].Execute -eq $physical) 'Registration lost the physical executable'
    $launcher = $normal
    Assert-Fixture ((Invoke-Fixture status).running) 'Changed context lost the registered task'
    $null = Invoke-Fixture stop
    @{ scenario = $Scenario; passed = $true; physicalActionPreserved = $true; liveMigrationDidNotRestart = $true; crossContextStop = $true; unrelatedPreserved = $true } | ConvertTo-Json -Compress
    exit 0
}

if ($Scenario -in @('unrelated', 'wrong-owner', 'unrelated-launcher')) {
    $global:FixtureTask = [pscustomobject]@{
        Actions = @(@{ Execute = $node; Arguments = ('"' + $worker + '" "' + $config + '"') })
        Principal = @{ UserId = 'fixture-sid' }; Triggers = @(); Settings = @{ Enabled = $true }; State = 'Running'
    }
    if ($Scenario -eq 'unrelated') { $global:FixtureTask.Actions[0].Arguments = 'unrelated-worker' }
    elseif ($Scenario -eq 'unrelated-launcher') {
        $global:FixtureTask.Actions[0].Execute = 'C:\Unrelated\host-launcher.exe'
        $global:FixtureTask.Actions[0].Arguments = '"' + $node + '" "' + $worker + '" "' + $config + '"'
    } else { $global:FixtureTask.Principal.UserId = 'unknown-fixture-owner' }
    $global:LASTEXITCODE = 0
    $null = & $ScriptPath -Action restart -NodePath $node -LauncherPath $launcher -WorkerPath $worker -ConfigPath $config
    Assert-Fixture ($LASTEXITCODE -eq 1) 'Invalid identity must be rejected'
    Assert-Fixture ($FixtureWrites -eq 0 -and $FixtureStarts -eq 0 -and $FixtureStops -eq 0) 'Rejected task was mutated'
    @{ scenario = $Scenario; rejected = $true; mutations = 0 } | ConvertTo-Json -Compress
    exit 0
}

if ($Scenario -eq 'launcher-migration') {
    $null = Invoke-Fixture start
    $principalBefore = $FixtureTask.Principal | ConvertTo-Json -Compress
    $FixtureTask.Actions = @([pscustomobject]@{ Execute = $node; Arguments = ('"' + $worker + '" "' + $config + '"') })
    Assert-Fixture ((Invoke-Fixture status).consoleLaunch -eq 'legacy-console') 'Legacy action not distinguished'
    $migrated = Invoke-Fixture start
    Assert-Fixture ($migrated.consoleLaunch -eq 'native-hidden-wait' -and $FixtureStarts -eq 1 -and $FixtureStops -eq 0) 'Live migration interrupted or duplicated the task'
    Assert-Fixture ($FixtureTask.Actions[0].Execute -eq $launcher -and $FixtureTask.Actions[0].Arguments -eq ('"' + $node + '" "' + $worker + '" "' + $config + '"')) 'Private worker tuple changed'
    $FixtureTask.Actions[0].Execute = $launcher.Replace(('a' * 64), ('b' * 64))
    $null = Invoke-Fixture restart
    Assert-Fixture ($FixtureStarts -eq 2 -and $FixtureStops -eq 1 -and $FixtureTask.Actions[0].Execute -eq $launcher) 'Older immutable launcher could not upgrade'
    Assert-Fixture (($FixtureTask.Principal | ConvertTo-Json -Compress) -eq $principalBefore) 'Launcher migration changed owner'
    @{ scenario = $Scenario; passed = $true; legacyMigrated = $true; oldLauncherMigrated = $true } | ConvertTo-Json -Compress
    exit 0
}
if ($Scenario -eq 'launcher-tree') {
    $null = Invoke-Fixture start
    $rootArgs = '"' + $node + '" "' + $worker + '" "' + $config + '"'
    Add-FixtureProcess 90 1 ('"' + $launcher + '" ' + $rootArgs) $launcher
    Add-FixtureProcess 100 90 $rootArgs $node
    Add-FixtureProcess 101 100 ($rootArgs + ' --host-child') $node
    Add-FixtureProcess 102 101 'private-pipe-helper' 'powershell.exe'
    Add-FixtureProcess 200 1 ('"' + $launcher + '" ' + $rootArgs.Replace($config, 'other-config')) $launcher
    Add-FixtureProcess 201 1 ('"C:\Unrelated\host-launcher.exe" ' + $rootArgs) 'C:\Unrelated\host-launcher.exe'
    $FixtureTask.State = 'Ready' # Retained launcher/worker after task exit.
    $result = Invoke-Fixture stop
    foreach ($Id in @(90, 100, 101, 102)) { Assert-Fixture $FixtureProcesses[$Id].Native.HasExited 'Owned launcher tree remained' }
    foreach ($Id in @(200, 201)) { Assert-Fixture (-not $FixtureProcesses[$Id].Native.HasExited) 'Unrelated launcher stopped' }
    Assert-Fixture (-not $result.running -and -not $result.periodicRecovery) 'Stop left auto recovery enabled'
    @{ scenario = $Scenario; passed = $true; launcherTreeStopped = $true; unrelatedPreserved = $true } | ConvertTo-Json -Compress
    exit 0
}

if ($Scenario -in @('stop-tree', 'wrong-process-owner', 'owner-query-failed', 'batch-identity-drift', 'wide-tree', 'pid-reuse', 'stop-timeout', 'slow-queries', 'command-budget', 'late-child')) {
    $null = Invoke-Fixture start
    $rootArguments = '"' + $node + '" "' + $worker + '" "' + $config + '"'
    Add-FixtureProcess 100 1 $rootArguments $node
    Add-FixtureProcess 101 100 ($rootArguments + ' --host-child') $node
    Add-FixtureProcess 102 101 'private-pipe-helper' 'powershell.exe'
    Add-FixtureProcess 103 102 'private-helper-child' 'fixture-helper.exe'
    Add-FixtureProcess 200 1 'original-official-desktop' 'Codex.exe'
    Add-FixtureProcess 201 1 ('"' + $node + '" "' + $worker + '" "other-config"') $node
    if ($Scenario -eq 'wide-tree') { foreach ($Id in 110..121) { Add-FixtureProcess $Id 101 'owned-private-helper' 'powershell.exe' } }
    if ($Scenario -eq 'wrong-process-owner') { $FixtureProcesses[101].Sid = 'other-sid' }
    if ($Scenario -eq 'pid-reuse') { $FixtureProcesses[101].Native.StartTime = $FixtureProcesses[101].Native.StartTime.AddSeconds(1) }
    if ($Scenario -eq 'command-budget') {
        $global:FixtureElapsedMs = 0
        $global:LASTEXITCODE = 0
        $null = & $ScriptPath -Action restart -NodePath $node -LauncherPath $launcher -WorkerPath $worker -ConfigPath $config -CommandBudgetMs 1000
        Assert-Fixture ($LASTEXITCODE -eq 1 -and $FixtureStarts -eq 1 -and $FixtureStops -eq 0) 'Expired command budget must fail before execution and prevent restart'
        Assert-Fixture (-not $FixtureTask.Settings.Enabled) 'Budget failure must leave disabled task'
        Assert-Fixture (@($FixtureOrder | Where-Object { $_ -like 'kill-*' }).Count -eq 0) 'Expired budget killed a process'
        @{ scenario = $Scenario; rejected = $true; restartPrevented = $true } | ConvertTo-Json -Compress
        exit 0
    }
    if ($Scenario -eq 'stop-timeout') {
        $FixtureProcesses[101].Native | Add-Member -Force -MemberType ScriptMethod -Name Kill -Value { }
        $global:LASTEXITCODE = 0
        $null = & $ScriptPath -Action restart -NodePath $node -LauncherPath $launcher -WorkerPath $worker -ConfigPath $config
        Assert-Fixture ($LASTEXITCODE -eq 1 -and $FixtureStarts -eq 1) 'Unfinished stop must not restart'
        Assert-Fixture (-not $FixtureTask.Settings.Enabled -and -not $FixtureProcesses[101].Native.HasExited) 'Stop failure must remain explicit and disabled'
        Assert-Fixture (-not $FixtureProcesses[200].Native.HasExited -and -not $FixtureProcesses[201].Native.HasExited) 'Stop failure touched unrelated processes'
        @{ scenario = $Scenario; rejected = $true; restartPrevented = $true } | ConvertTo-Json -Compress
        exit 0
    }
    if ($Scenario -notin @('stop-tree', 'slow-queries', 'wide-tree', 'late-child')) {
        $global:LASTEXITCODE = 0
        $null = & $ScriptPath -Action stop -NodePath $node -LauncherPath $launcher -WorkerPath $worker -ConfigPath $config
        Assert-Fixture ($LASTEXITCODE -eq 1) 'Unverified process must reject stop'
        Assert-Fixture (-not $FixtureTask.Settings.Enabled -and $FixtureStops -eq 0) 'Rejected tree stop must remain disabled without stopping task'
        Assert-Fixture (@($FixtureOrder | Where-Object { $_ -like 'kill-*' }).Count -eq 0) 'Identity rejection killed a process'
        Assert-Fixture ((($FixtureOpenedHandles | Sort-Object) -join ',') -eq (($FixtureDisposedHandles | Sort-Object) -join ',')) 'Rejected batch leaked or double-disposed handles'
        @{ scenario = $Scenario; rejected = $true; mutations = 0 } | ConvertTo-Json -Compress
        exit 0
    }
    $result = Invoke-Fixture stop
    Assert-Fixture (-not $result.running -and -not $result.loginAutostart) 'Tree stop status'
    foreach ($Id in @(100, 101, 102, 103)) { Assert-Fixture $FixtureProcesses[$Id].Native.HasExited 'Owned child remained alive' }
    foreach ($Id in @(200, 201)) { Assert-Fixture (-not $FixtureProcesses[$Id].Native.HasExited) 'Unrelated process was stopped' }
    Assert-Fixture ($FixtureRootQueries -eq 2 -and $FixtureQueries -le 14 -and $FixtureOwners -le $(if ($Scenario -eq 'wide-tree') { 20 } else { 5 })) 'Repeated/broad enumeration or repeated owner checks'
    $expectedCleanup = @('kill-101', 'kill-102', 'kill-103')
    if ($Scenario -eq 'wide-tree') {
        foreach ($Id in 110..121) {
            Assert-Fixture $FixtureProcesses[$Id].Native.HasExited 'Batched owned child remained alive'
            $expectedCleanup += 'kill-' + $Id
        }
    }
    if ($Scenario -eq 'late-child') {
        Assert-Fixture $FixtureProcesses[104].Native.HasExited 'Child born during task stop escaped cleanup'
        $expectedCleanup += 'kill-104'
    }
    # Require the safety ordering and exact cleanup set, not Hashtable enumeration
    # or Sort-Object tie order, which differs between PowerShell engines.
    Assert-Fixture ($FixtureOrder[0] -eq 'disable' -and $FixtureOrder[1] -eq 'task-stop') 'Disable/task stop must precede descendant cleanup'
    $cleanup = @($FixtureOrder | Select-Object -Skip 2)
    Assert-Fixture ((($cleanup | Sort-Object) -join ',') -eq (($expectedCleanup | Sort-Object) -join ',')) 'Unexpected, missing or repeated process cleanup'
    Assert-Fixture ((($FixtureOpenedHandles | Sort-Object) -join ',') -eq (($FixtureDisposedHandles | Sort-Object) -join ',')) 'Successful batch leaked or double-disposed handles'
    if ($Scenario -eq 'wide-tree') {
        Assert-Fixture ($result.ownerChecks -ge 15 -and $result.processQueries -le 12 -and $result.commandElapsedMs -lt 45000) 'Per-process query round trips returned'
        @{ scenario = $Scenario; passed = $true; elapsedMs = $result.commandElapsedMs; processQueries = $result.processQueries; ownerChecks = $result.ownerChecks; retainedHandlesDisposed = $true } | ConvertTo-Json -Compress
        exit 0
    }
    if ($Scenario -eq 'slow-queries') {
        Assert-Fixture ($result.commandElapsedMs -gt 30000 -and $result.commandElapsedMs -lt $result.commandBudgetMs -and $result.commandBudgetMs -eq 45000) 'Slow provider must return within matched command budget'
        @{ scenario = $Scenario; passed = $true; elapsedMs = $result.commandElapsedMs; processQueries = $result.processQueries; ownerChecks = $result.ownerChecks; rootQueries = $FixtureRootQueries; virtualProviderLatency = $true } | ConvertTo-Json -Compress
        exit 0
    }
    # A task already Ready can still have a disconnected worker holding HTTP.
    Add-FixtureProcess 110 999 ($rootArguments + ' --host-child') $node
    Add-FixtureProcess 111 110 'private-pipe-helper' 'powershell.exe'
    $FixtureTask.State = 'Ready'
    $null = Invoke-Fixture stop
    Assert-Fixture ($FixtureProcesses[110].Native.HasExited -and $FixtureProcesses[111].Native.HasExited) 'Ready task orphan was not stopped'
    Assert-Fixture ($FixtureStops -eq 1) 'Orphan cleanup stopped a different task instance'
    @{ scenario = $Scenario; passed = $true; ownedTreeStopped = $true; orphanStopped = $true; unrelatedPreserved = $true } | ConvertTo-Json -Compress
    exit 0
}

$absent = Invoke-Fixture status
Assert-Fixture (-not $absent.installed -and -not $absent.running -and -not $absent.loginAutostart) 'Absent task status'
$first = Invoke-Fixture start
Assert-Fixture ($first.running -and $first.loginAutostart -and $FixtureStarts -eq 1) 'Install must start and enable login'
Assert-Fixture ($FixtureTask.Principal.LogonType -eq 'Interactive' -and $FixtureTask.Principal.RunLevel -eq 'Limited') 'Expected current interactive user'
$settings = $FixtureTask.Settings
Assert-Fixture ($settings.AllowBattery -and $settings.StayOnBattery -and $settings.StartWhenAvailable) 'Battery startup settings'
Assert-Fixture ($settings.RestartCount -eq 3 -and $settings.RestartInterval.TotalMinutes -eq 1 -and $settings.ExecutionTimeLimit -eq [TimeSpan]::Zero -and $settings.MultipleInstances -eq 'IgnoreNew') 'Retry and singleton settings'
$originalAction = $FixtureTask.Actions[0] | ConvertTo-Json -Compress
$null = Invoke-Fixture start
Assert-Fixture ($FixtureStarts -eq 1) 'Repeated start duplicated the Host'

# Registration, current running and actual login triggers are independent.
$FixtureTask.Triggers[0].Enabled = $false
Assert-Fixture (-not (Invoke-Fixture status).loginAutostart) 'Disabled login trigger was accepted'
$FixtureTask.Triggers[0].Enabled = $true
$FixtureTask.Triggers[0].UserId = 'unknown-fixture-owner'
Assert-Fixture (-not (Invoke-Fixture status).loginAutostart) 'Wrong-user login trigger was accepted'
$FixtureTask.Triggers[0].UserId = 'fixture-sid'
$FixtureTask.Triggers[0].CimClass.CimClassName = 'MSFT_TaskTimeTrigger'
Assert-Fixture (-not (Invoke-Fixture status).loginAutostart) 'Non-login trigger was accepted'
$null = Invoke-Fixture start
Assert-Fixture ((Invoke-Fixture status).loginAutostart -and $FixtureStarts -eq 1) 'Old task must regain login without duplicate execution'
Assert-Fixture (($FixtureTask.Actions[0] | ConvertTo-Json -Compress) -eq $originalAction) 'Upgrade changed private paths'

$FixtureTask.State = 'Ready' # Simulate a logged-out Host; this is not OS reboot evidence.
$idle = Invoke-Fixture status
Assert-Fixture ($idle.loginAutostart -and -not $idle.running) 'Registration was mistaken for online'
$null = Invoke-Fixture start
$stopped = Invoke-Fixture stop
Assert-Fixture ($stopped.installed -and -not $stopped.running -and -not $stopped.loginAutostart) 'Stop must persistently disable login'
$null = Invoke-Fixture stop
Assert-Fixture ($FixtureStops -eq 1) 'Repeated stop must not stop another instance'
$resumed = Invoke-Fixture start
Assert-Fixture ($resumed.running -and $resumed.loginAutostart) 'Start must re-enable stopped task'
$null = Invoke-Fixture restart
Assert-Fixture ($FixtureStops -eq 2 -and $FixtureStarts -eq 4) 'Restart must replace only one Host'
Assert-Fixture (($FixtureTask.Actions[0] | ConvertTo-Json -Compress) -eq $originalAction) 'Lifecycle changed private paths'
@{ scenario = $Scenario; passed = $true; starts = $FixtureStarts; stops = $FixtureStops } | ConvertTo-Json -Compress
