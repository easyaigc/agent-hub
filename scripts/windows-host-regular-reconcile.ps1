param([Parameter(Mandatory=$true)][ValidateSet('close-failed-domain')][string]$Action)
$ErrorActionPreference='Stop';Set-StrictMode -Version Latest
$leases=@();$phase='request';$mutation=$false;$scratch=$null
function Hash-Bytes([byte[]]$Bytes){$h=[Security.Cryptography.SHA256]::Create();try{return ([BitConverter]::ToString($h.ComputeHash($Bytes))).Replace('-','').ToLowerInvariant()}finally{$h.Dispose()}}
function Read-Bound([string]$Path,[int]$Maximum){$l=[CodexManagePrivateStateSecurity]::OpenRecoveryRead($Path,$Maximum);$script:leases+=,$l;return $l}
function Create-Record([string]$Path,$Value){$b=[Text.UTF8Encoding]::new($false).GetBytes((ConvertTo-Json -Depth 20 -Compress -InputObject $Value)+"`n");$null=[CodexManagePrivateStateSecurity]::CreateRecoveryFile($Path,$b)}
function Absent-Folder([string]$Name){try{$null=$root.GetFolder($Name);return $false}catch{$e=Regular-RootException $_;if(([long]$e.HResult -band 0xffffL) -in @(2,3)){return $true};throw}}
try {
    foreach($module in @('Microsoft.PowerShell.Management','Microsoft.PowerShell.Security','Microsoft.PowerShell.Utility')){Import-Module (Join-Path $PSHOME ('Modules\'+$module)) -ErrorAction Stop}
    $r=ConvertFrom-Json ([Console]::In.ReadToEnd())
    if($r.format -cne 'windows-host-regular-reconcile-v1' -or $r.action -cne $Action){throw 'HOST_RECONCILE_REQUEST_REJECTED'}
    foreach($key in @('operationId','preparationId')){if($r.$key -cnotmatch '^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$'){throw 'HOST_RECONCILE_ID_REJECTED'}}
    foreach($key in @('checkpointSha256','planSha256','failureSha256')){if($r.$key -cnotmatch '^[a-f0-9]{64}$'){throw 'HOST_RECONCILE_HASH_REJECTED'}}
    $source=Join-Path $PSScriptRoot '..\packages\codex-adapter\windows'
    . (Join-Path $source 'private-bootstrap.ps1');. (Join-Path $PSScriptRoot 'windows-host-regular-domain-common.ps1')
    $scratch=Open-HostBootstrapScratch $source
    if($null -eq $scratch){throw 'HOST_RECONCILE_PROTECTED_STORAGE_REQUIRED'}
    Add-Type -Path @((Join-Path $source 'private-state.cs'),(Join-Path $source 'private-state-compilation.cs'),(Join-Path $source 'private-state-recovery.cs'),(Join-Path $source 'host-domain.cs')) -ReferencedAssemblies @('System.dll','System.Core.dll','System.Xml.dll','System.Runtime.Serialization.dll')
    $hub=Join-Path ([Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)) '.config\agent-hub'
    $preparation=Join-Path (Join-Path $hub 'host-regular-recovery') $r.preparationId
    $operation=[CodexManageHostDomain]::DirectoryFor($r.preparationId,$r.operationId)
    $checkpointLease=Read-Bound (Join-Path $preparation 'checkpoint.json') 65536
    if($checkpointLease.Sha256 -cne $r.checkpointSha256){throw 'HOST_RECONCILE_CHECKPOINT_CHANGED'}
    $checkpoint=ConvertFrom-Json ([Text.Encoding]::UTF8.GetString($checkpointLease.Bytes))
    if($checkpoint.operationId -cne $r.preparationId){throw 'HOST_RECONCILE_PREPARATION_CHANGED'}
    $planLease=Read-Bound (Join-Path $operation 'plan.json') 65536
    $failureLease=Read-Bound (Join-Path $operation 'failure.json') 16384
    if($planLease.Sha256 -cne $r.planSha256 -or $failureLease.Sha256 -cne $r.failureSha256){throw 'HOST_RECONCILE_FAILURE_CHANGED'}
    $plan=ConvertFrom-Json ([Text.Encoding]::UTF8.GetString($planLease.Bytes));$failure=ConvertFrom-Json ([Text.Encoding]::UTF8.GetString($failureLease.Bytes))
    if($plan.format -cne 'windows-host-regular-domain-plan-v1' -or $plan.operationId -cne $r.operationId -or $plan.preparationId -cne $r.preparationId -or $failure.phase -cne 'arguments' -or $failure.type -cne 'System.InvalidOperationException' -or $failure.hresult -cne '0x80131509' -or $null -ne $failure.workerExitCode){throw 'HOST_RECONCILE_NOT_A_PRE_CHILD_FAILURE'}
    foreach($name in @('entered.json','running.json','limited-failure.json','completed.json','reconciliation-intent.json','failed-closed.json')){if(Test-Path -LiteralPath (Join-Path $operation $name)){throw 'HOST_RECONCILE_UNEXPECTED_EXECUTION_OR_PRIOR_INTENT'}}
    $intent=ConvertFrom-Json ([Text.Encoding]::UTF8.GetString((Read-Bound (Join-Path $operation 'fixture-intent.json') 16384).Bytes))
    $attempt=ConvertFrom-Json ([Text.Encoding]::UTF8.GetString((Read-Bound (Join-Path $operation 'run-attempt.json') 16384).Bytes))
    if($intent.operationId -cne $r.operationId -or $attempt.operationId -cne $r.operationId -or $attempt.planSha256 -cne $planLease.Sha256 -or $intent.runnerSha256 -cne $plan.runnerSha256){throw 'HOST_RECONCILE_INTENT_CHANGED'}
    $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    if($sid -cne $plan.userSid){throw 'HOST_RECONCILE_USER_CHANGED'}
    $scheduler=New-Object -ComObject Schedule.Service;$scheduler.Connect();$root=$scheduler.GetFolder('\')
    $folderName='CodexManage Regular Verify '+$r.operationId;$guardName='CodexManage Regular Guard '+$r.operationId
    if($intent.folder -cne $folderName -or $intent.guard -cne $guardName){throw 'HOST_RECONCILE_FOLDER_CHANGED'}
    $folder=$root.GetFolder(('\'+$folderName));$guard=$root.GetFolder(('\'+$guardName))
    Regular-AssertDomainSd $folder (Regular-DomainSddl $sid $false);Regular-AssertDomainSd $guard 'O:BAG:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)'
    if($folder.GetTasks(1).Count -ne 5 -or $folder.GetFolders(0).Count -ne 0 -or $guard.GetTasks(1).Count -ne 0 -or $guard.GetFolders(0).Count -ne 0){throw 'HOST_RECONCILE_INVENTORY_CHANGED'}
    $runnerTask=$folder.GetTask('Runner');$runner=[string]$runnerTask.Definition.Actions.Item(1).Path
    $runnerLease=Read-Bound $runner 1048576
    if($runnerLease.Sha256 -cne $plan.runnerSha256 -or $runnerLease.Identity -cne $intent.runnerIdentity -or $runnerTask.LastTaskResult -ne 190){throw 'HOST_RECONCILE_RUNNER_CHANGED'}
    $arguments=[CodexManageHostDomain]::Arguments((Join-Path $operation 'plan.json'),$planLease.Sha256)
    if((Hash-Bytes ([Text.Encoding]::UTF8.GetBytes($arguments))) -cne $intent.actionSha256){throw 'HOST_RECONCILE_ARGUMENTS_CHANGED'}
    # This exact runner's arguments-phase has no child/process creation. Confirm
    # its failed Scheduler invocation has ended; do not infer old T1 closure.
    $candidates=@(Get-CimInstance Win32_Process -Filter "Name='host-domain.exe'" -Property ProcessId,ExecutablePath,CommandLine,CreationDate -OperationTimeoutSec 10)
    foreach($p in $candidates){if([string]::Equals([string]$p.ExecutablePath,$runner,[StringComparison]::OrdinalIgnoreCase)){throw 'HOST_RECONCILE_RUNNER_STILL_PRESENT'}}
    foreach($name in @('Runner','PositiveUpdate','PositiveDelete','NegativeUpdate','NegativeDelete')){
        $task=$folder.GetTask($name)
        if($task.GetInstances(0).Count -ne 0){throw 'HOST_RECONCILE_FIXTURE_STILL_ACTIVE'}
        if($name -eq 'Runner'){Regular-AssertFixtureTask $task $sid $name $false $runner $arguments $operation $false}
        else{Regular-AssertFixtureTask $task $sid $name $false (Join-Path ([Environment]::SystemDirectory) 'WindowsPowerShell\v1.0\powershell.exe') '-NoProfile -NonInteractive -Command exit 0' '' ($name.StartsWith('Positive'));if($task.Enabled){throw 'HOST_RECONCILE_UNEXPECTED_ENABLED_FIXTURE'}}
    }
    $phase='bind-original-task'
    function Assert-Original {
        $t=$root.GetTask('CodexManage Host Agent');if($t.GetInstances(0).Count -ne 0){throw 'HOST_RECONCILE_ORIGINAL_RUNNING'}
        $values=@{xml=[string]$t.Xml;definitionXml=[string]$t.Definition.XmlText;sd5=[string]$t.GetSecurityDescriptor(5);sd7=[string]$t.GetSecurityDescriptor(7);rootSd5=[string]$root.GetSecurityDescriptor(5);rootSd7=[string]$root.GetSecurityDescriptor(7)}
        foreach($key in $values.Keys){if((Hash-Bytes ([Text.Encoding]::UTF8.GetBytes($values[$key]))) -cne $checkpoint.task.$key){throw 'HOST_RECONCILE_ORIGINAL_CHANGED'}}
    }
    Assert-Original
    Create-Record (Join-Path $operation 'reconciliation-intent.json') @{format=$r.format;operationId=$r.operationId;planSha256=$planLease.Sha256;failureSha256=$failureLease.Sha256;historicalSubtreeVerified=$false;pendingReleased=$false;purpose='close only the failed pre-child synthetic fixtures'}
    $phase='close-exact-failed-fixtures';$mutation=$true
    $null=Regular-StopOwnedRunner $root $folderName $sid $runner $arguments $operation $false
    foreach($name in @('Runner','PositiveUpdate','PositiveDelete','NegativeUpdate','NegativeDelete')){
        if($name -eq 'Runner'){Regular-DeleteOwnedFixture $root $folderName $sid $name $false $runner $arguments $operation $false}
        else{Regular-DeleteOwnedFixture $root $folderName $sid $name $false (Join-Path ([Environment]::SystemDirectory) 'WindowsPowerShell\v1.0\powershell.exe') '-NoProfile -NonInteractive -Command exit 0' '' ($name.StartsWith('Positive'))}
    }
    Regular-DeleteEmptyFixtureFolder $root $folderName (Regular-DomainSddl $sid $false)
    Regular-DeleteEmptyFixtureFolder $root $guardName 'O:BAG:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)'
    if(-not (Absent-Folder ('\'+$folderName)) -or -not (Absent-Folder ('\'+$guardName))){throw 'HOST_RECONCILE_CLEANUP_UNCONFIRMED'}
    Assert-Original
    $result=@{format=$r.format;status='failed-domain-closed';operationId=$r.operationId;fixturesRemoved=$true;preChildFailureClosed=$true;historicalSubtreeVerified=$false;pendingReleased=$false;hostStarted=$false;originalTaskMutated=$false}
    Create-Record (Join-Path $operation 'failed-closed.json') $result
} catch {
    $e=$_.Exception;while($null -ne $e.InnerException){$e=$e.InnerException}
    $result=@{format='windows-host-regular-reconcile-v1';status='rejected';phase=$phase;errorType=$e.GetType().FullName;hresult=('0x{0:X8}' -f ($e.HResult -band 0xffffffffL));line=$_.InvocationInfo.ScriptLineNumber;fixtureMutationAttempted=$mutation;originalTaskMutated=$false;pendingReleased=$false;hostStarted=$false}
} finally {for($i=$leases.Count-1;$i -ge 0;$i--){$leases[$i].Dispose()};if($null -ne $scratch){Close-HostBootstrapScratch $scratch}}
[Console]::Out.WriteLine((ConvertTo-Json -Depth 20 -Compress -InputObject $result))
if($result.status -cne 'failed-domain-closed'){exit 1}
