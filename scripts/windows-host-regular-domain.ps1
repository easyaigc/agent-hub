param([Parameter(Mandatory=$true)][ValidateSet('verify','status','resume')][string]$Action)
$ErrorActionPreference='Stop';Set-StrictMode -Version Latest
$leases=@();$sourceLeases=@();$scratch=$null;$scope=$null;$phase='request';$runAttempted=$false;$fixtureMutation=$false;$subtreeConfirmed=$false
function Regular-RootException($ErrorRecord){$e=$ErrorRecord.Exception;for($i=0;$null -ne $e.InnerException -and $i -lt 10;$i++){$e=$e.InnerException};return $e}
function Domain-Hash([byte[]]$Bytes){$h=[Security.Cryptography.SHA256]::Create();try{return ([BitConverter]::ToString($h.ComputeHash($Bytes))).Replace('-','').ToLowerInvariant()}finally{$h.Dispose()}}
function Domain-Bytes($Value){return [Text.UTF8Encoding]::new($false).GetBytes((ConvertTo-Json -Depth 25 -Compress -InputObject $Value)+"`n")}
function Domain-Read([string]$Path,[int]$Maximum){$lease=[CodexManagePrivateStateSecurity]::OpenRecoveryRead($Path,$Maximum);$script:leases+=,$lease;return $lease}
function Domain-ReadCode([string]$Path,[int]$Maximum,[string]$Expected){$lease=[CodexManagePrivateStateSecurity]::OpenRecoveryCodeRead($Path,$Maximum,$Expected);$script:leases+=,$lease;return $lease}
function Domain-Create([string]$Path,$Value){$null=[CodexManagePrivateStateSecurity]::CreateRecoveryFile($Path,(Domain-Bytes $Value))}
function Domain-TaskSnapshot {
    $t=$rootFolder.GetTask('CodexManage Host Agent')
    if($t.GetInstances(0).Count -ne 0){throw 'HOST_ORIGINAL_TASK_RUNNING'}
    $v=@{xml=[string]$t.Xml;definitionXml=[string]$t.Definition.XmlText;sd5=[string]$t.GetSecurityDescriptor(5);sd7=[string]$t.GetSecurityDescriptor(7);rootSd5=[string]$rootFolder.GetSecurityDescriptor(5);rootSd7=[string]$rootFolder.GetSecurityDescriptor(7)}
    $out=@{};foreach($key in $v.Keys){$out[$key]=Domain-Hash ([Text.Encoding]::UTF8.GetBytes($v[$key]))};return $out
}
function Domain-AssertOriginal { $current=Domain-TaskSnapshot;foreach($key in $checkpoint.task.PSObject.Properties.Name){if($current[$key] -cne $checkpoint.task.$key){throw 'HOST_ORIGINAL_TASK_CHANGED'}} }
function Domain-AbsentFolder([string]$Name){try{$null=$rootFolder.GetFolder($Name);return $false}catch{$e=Regular-RootException $_;if((([long]$e.HResult -band 0xffffL) -in @(2,3))){return $true};throw}}
function Domain-AbsentTask($Folder,[string]$Name){try{$null=$Folder.GetTask($Name);return $false}catch{$e=Regular-RootException $_;if((([long]$e.HResult -band 0xffffL) -in @(2,3))){return $true};throw}}
function Domain-ReadRunning([string]$Path){
    try{$l=[CodexManagePrivateStateSecurity]::OpenRecoveryRead($Path,16384);try{return ConvertFrom-Json -InputObject ([Text.Encoding]::UTF8.GetString($l.Bytes))}finally{$l.Dispose()}}
    catch{$e=Regular-RootException $_;if($e -is [ComponentModel.Win32Exception] -and $e.NativeErrorCode -in @(2,3,32,33)){return $null};throw}
}
function Domain-ResourceNegative([string]$Path){
    try{$f=[IO.FileStream]::new($Path,[IO.FileMode]::Open,[IO.FileAccess]::Write,[IO.FileShare]::ReadWrite);$f.Dispose();throw 'HOST_DOMAIN_WRITE_NOT_BLOCKED'}
    catch{if($_.Exception.Message -eq 'HOST_DOMAIN_WRITE_NOT_BLOCKED'){throw};$e=Regular-RootException $_;if(([long]$e.HResult -band 0xffffL) -notin @(32,33)){throw}}
    try{[IO.File]::Move($Path,($Path+'.must-not-move'));throw 'HOST_DOMAIN_RENAME_NOT_BLOCKED'}
    catch{if($_.Exception.Message -eq 'HOST_DOMAIN_RENAME_NOT_BLOCKED'){throw};$e=Regular-RootException $_;if(([long]$e.HResult -band 0xffffL) -notin @(32,33)){throw}}
    $parent=Split-Path -Parent $Path
    try{[IO.Directory]::Move($parent,($parent+'.must-not-move'));throw 'HOST_DOMAIN_PARENT_RENAME_NOT_BLOCKED'}
    catch{if($_.Exception.Message -eq 'HOST_DOMAIN_PARENT_RENAME_NOT_BLOCKED'){throw};$e=Regular-RootException $_;if(([long]$e.HResult -band 0xffffL) -notin @(32,33)){throw}}
}
try {
    foreach($module in @('Microsoft.PowerShell.Management','Microsoft.PowerShell.Security','Microsoft.PowerShell.Utility')){Import-Module (Join-Path $PSHOME ('Modules\'+$module)) -ErrorAction Stop}
    $reader=[IO.StreamReader]::new([Console]::OpenStandardInput(),[Text.UTF8Encoding]::new($false,$true));try{$raw=$reader.ReadToEnd()}finally{$reader.Dispose()}
    if($raw.Length -gt 16384){throw 'HOST_DOMAIN_REQUEST_BUDGET'};$request=ConvertFrom-Json -InputObject $raw
    if($request.format -cne 'windows-host-regular-domain-v1' -or $request.action -cne $Action){throw 'HOST_DOMAIN_REQUEST_REJECTED'}
    $source=Join-Path $PSScriptRoot '..\packages\codex-adapter\windows'
    . (Join-Path $source 'private-bootstrap.ps1');. (Join-Path $PSScriptRoot 'windows-host-regular-domain-common.ps1')
    $scratch=Open-HostBootstrapScratch $source
    if($null -eq $scratch){throw 'HOST_DOMAIN_PROTECTED_STORAGE_REQUIRED'}
    $phase='load-audited-native-code'
    $sources=@('private-state.cs','private-state-compilation.cs','private-state-recovery.cs','host-domain.cs')
    foreach($name in $sources){$stream=[IO.FileStream]::new((Join-Path $source $name),[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read);$sourceLeases+=,$stream;$h=[Security.Cryptography.SHA256]::Create();try{$hash=([BitConverter]::ToString($h.ComputeHash($stream))).Replace('-','').ToLowerInvariant()}finally{$h.Dispose()};if($hash -cne $request.sourceHashes.$name){throw 'HOST_DOMAIN_SOURCE_CHANGED'};$stream.Position=0}
    Add-Type -Path @($sources|ForEach-Object{Join-Path $source $_}) -ReferencedAssemblies @('System.dll','System.Core.dll','System.Xml.dll','System.Runtime.Serialization.dll')
    $hub=Join-Path ([Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)) '.config\agent-hub'
    $preparation=Join-Path (Join-Path $hub 'host-regular-recovery') $request.preparationId
    $checkpointLease=Domain-Read (Join-Path $preparation 'checkpoint.json') 65536
    if($checkpointLease.Sha256 -cne $request.checkpointSha256){throw 'HOST_DOMAIN_CHECKPOINT_CHANGED'}
    $checkpoint=ConvertFrom-Json -InputObject ([Text.Encoding]::UTF8.GetString($checkpointLease.Bytes))
    foreach($key in @('hostId','legacyOperationId','p0Sha256')){if($checkpoint.$key -cne $request.$key){throw 'HOST_DOMAIN_CHECKPOINT_SCOPE_CHANGED'}}
    if($checkpoint.operationId -cne $request.preparationId -or $checkpoint.packageSha256 -cne $request.p1ManifestSha256 -or $checkpoint.activationImplemented -ne $false -or $checkpoint.pendingReleased -ne $false){throw 'HOST_DOMAIN_CHECKPOINT_REJECTED'}
    $prepared=ConvertFrom-Json -InputObject ([Text.Encoding]::UTF8.GetString((Domain-Read (Join-Path $preparation 'prepared.json') 4096).Bytes))
    $reservation=ConvertFrom-Json -InputObject ([Text.Encoding]::UTF8.GetString((Domain-Read (Join-Path (Split-Path -Parent $preparation) 'reservation.json') 4096).Bytes))
    if($prepared.operationId -cne $request.preparationId -or $prepared.checkpointSha256 -cne $request.checkpointSha256 -or $reservation.operationId -cne $request.preparationId -or $reservation.hostId -cne $request.hostId){throw 'HOST_DOMAIN_PREPARATION_SEAL_CHANGED'}
    $legacy=Join-Path (Join-Path $hub 'host-migrations') $request.legacyOperationId
    $paths=@{configuration=(Join-Path $request.installedProgram '.local\desktop-runtime\windows-host.json');pending=(Join-Path $hub 'host-migrations\pending.json');intent=(Join-Path $legacy 'intent.json');domainIntent=(Join-Path $legacy 'domain.intent.json');runAttempt=(Join-Path $legacy 'domain.run-attempt.json')}
    foreach($name in $paths.Keys){$l=Domain-Read $paths[$name] 131072;if($l.Identity -cne $checkpoint.files.$name.identity -or $l.Sha256 -cne $checkpoint.files.$name.sha256){throw 'HOST_DOMAIN_ORIGINAL_FILE_CHANGED'}}
    if((Test-Path -LiteralPath (Join-Path $legacy 'domain.process-anchors.json')) -or (Test-Path -LiteralPath (Join-Path $request.installedProgram '.local\desktop-runtime\task-recovery\pending.json'))){throw 'HOST_DOMAIN_DISTINCT_PENDING_OR_ANCHORS_CHANGED'}
    foreach($name in @('private-state','host-launcher')){$r=$checkpoint.resources.$name;$p=Join-Path (Join-Path (Join-Path $hub 'windows-native-v1\native-helpers') $r.digest) ($name+'.exe');$l=Domain-Read $p 1048576;if($l.Identity -cne $r.identity -or $l.Sha256 -cne $r.sha256){throw 'HOST_DOMAIN_P1_RESOURCE_CHANGED'}}
    $scheduler=New-Object -ComObject Schedule.Service;$scheduler.Connect();$rootFolder=$scheduler.GetFolder('\')
    Domain-AssertOriginal
    foreach($prefix in @('CodexManage Recovery Test ','CodexManage Recovery Guard ')){if(-not (Domain-AbsentFolder ('\'+$prefix+$request.legacyOperationId))){throw 'HOST_DOMAIN_OLD_FIXTURE_PRESENT'}}
    $operation=[CodexManageHostDomain]::DirectoryFor($request.preparationId,$request.operationId)
    $reservationPath=Join-Path $preparation 'domain-reservation.json'
    if($Action -eq 'status'){
        $saved=ConvertFrom-Json -InputObject ([Text.Encoding]::UTF8.GetString((Domain-Read $reservationPath 4096).Bytes))
        if($saved.operationId -cne $request.operationId){throw 'HOST_DOMAIN_RESERVATION_CHANGED'}
        if($saved.packageSha256 -cne $request.packageSha256){
            $transition=ConvertFrom-Json -InputObject ([Text.Encoding]::UTF8.GetString((Domain-Read (Join-Path $operation 'resume-intent.json') 4096).Bytes))
            if($transition.operationId -cne $request.operationId -or $transition.previousPackageSha256 -cne $saved.packageSha256 -or $transition.packageSha256 -cne $request.packageSha256){throw 'HOST_DOMAIN_RESUME_BINDING_CHANGED'}
        }
        if(Test-Path -LiteralPath (Join-Path $operation 'completed.json')){$result=ConvertFrom-Json -InputObject ([Text.Encoding]::UTF8.GetString((Domain-Read (Join-Path $operation 'completed.json') 16384).Bytes));$result.action='status'}
        else{$result=@{format=$request.format;status='unconfirmed';operationId=$request.operationId;hostStarted=$false;originalTaskMutationAttempted=$false;pendingReleased=$false;restartAuthorized=$false;historicalSubtreeVerified=$false;next='retain exact domain reservation/fixtures; no replay or marker deletion'}}
        if($result.status -ceq 'verified' -and (-not (Domain-AbsentFolder ('\CodexManage Regular Verify '+$request.operationId)) -or -not (Domain-AbsentFolder ('\CodexManage Regular Guard '+$request.operationId)))){throw 'HOST_DOMAIN_COMPLETED_FIXTURE_REAPPEARED'}
    } else {
        $phase='reserve-new-domain'
        if(-not ([Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){throw 'HOST_DOMAIN_EXISTING_ADMIN_CONTEXT_REQUIRED'}
        if($Action -eq 'resume'){
            $saved=ConvertFrom-Json -InputObject ([Text.Encoding]::UTF8.GetString((Domain-Read $reservationPath 4096).Bytes))
            if($saved.operationId -cne $request.operationId -or $saved.preparationId -cne $request.preparationId -or $saved.checkpointSha256 -cne $request.checkpointSha256 -or $saved.packageSha256 -cne $request.recovery.previousPackageSha256 -or $request.recovery.operationId -cne $request.operationId){throw 'HOST_DOMAIN_RESUME_BINDING_CHANGED'}
            Assert-HostBootstrapPath $operation $true
            if(@(Get-ChildItem -LiteralPath $operation -Force).Count -ne 0){throw 'HOST_DOMAIN_RESUME_NOT_BEFORE_RUN'}
            foreach($prefix in @('CodexManage Regular Verify ','CodexManage Regular Guard ')){if(-not (Domain-AbsentFolder ('\'+$prefix+$request.operationId))){throw 'HOST_DOMAIN_RESUME_FIXTURE_PRESENT'}}
            Domain-Create (Join-Path $operation 'resume-intent.json') @{format='windows-host-regular-domain-resume-v1';operationId=$request.operationId;previousPackageSha256=$saved.packageSha256;packageSha256=$request.packageSha256;failureSha256=$request.recovery.failureSha256;previousSourceCommit=$request.recovery.previousSourceCommit;taskRunNeverAttempted=$true}
        } else {
            if((Test-Path -LiteralPath $reservationPath) -or (Test-Path -LiteralPath $operation)){throw 'HOST_DOMAIN_ALREADY_RESERVED_QUERY_STATUS'}
            Domain-Create $reservationPath @{operationId=$request.operationId;packageSha256=$request.packageSha256;checkpointSha256=$request.checkpointSha256;preparationId=$request.preparationId}
            New-HostBootstrapDirectory $operation
        }
        $boot=(Get-CimInstance Win32_OperatingSystem -OperationTimeoutSec 10).LastBootUpTime.ToUniversalTime().ToString('o')
        if($boot -cne $checkpoint.boot){throw 'HOST_DOMAIN_BOOT_CHANGED'}
        $phase='compile-fixed-domain-executable'
        $cache=Join-Path (Join-Path $hub 'windows-native-v1\native-helpers') $request.helperDigest
        if(Test-Path -LiteralPath $cache){throw 'HOST_DOMAIN_COMPILED_RESOURCE_ALREADY_EXISTS'}
        $null=[CodexManagePrivateStateSecurity]::PrepareNative('host-domain',$source,$cache,30000)
        $runner=Join-Path $cache 'host-domain.exe';$runnerLease=Domain-Read $runner 1048576
        $phase='pin-frozen-execution-code'
        $limited=Join-Path $PSScriptRoot 'windows-host-regular-limited.ps1';$limitedLease=Domain-ReadCode $limited 1048576 $request.limitedSha256
        $commonLease=Domain-ReadCode (Join-Path $PSScriptRoot 'windows-host-regular-domain-common.ps1') 1048576 $request.commonSha256
        if($limitedLease.Sha256 -cne $request.limitedSha256 -or $commonLease.Sha256 -cne $request.commonSha256){throw 'HOST_DOMAIN_SCRIPT_CHANGED'}
        Domain-ResourceNegative $runner;Domain-ResourceNegative $limited
        $scope=[CodexManageHostDomain]::Begin($request.operationId)
        $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        $plan=@{format='windows-host-regular-domain-plan-v1';operationId=$request.operationId;preparationId=$request.preparationId;userSid=$sid;controllerPid=$scope.ControllerPid;controllerCreation=$scope.ControllerCreation;runnerSha256=$runnerLease.Sha256;limitedScript=$limited;limitedSha256=$limitedLease.Sha256;commonSha256=$commonLease.Sha256}
        $planBytes=Domain-Bytes $plan;$planPath=Join-Path $operation 'plan.json';$planSha=Domain-Hash $planBytes;$null=[CodexManagePrivateStateSecurity]::CreateRecoveryFile($planPath,$planBytes)
        $arguments=[CodexManageHostDomain]::Arguments($planPath,$planSha)
        $folderName='CodexManage Regular Verify '+$request.operationId;$guardName='CodexManage Regular Guard '+$request.operationId
        if(-not (Domain-AbsentFolder ('\'+$folderName)) -or -not (Domain-AbsentFolder ('\'+$guardName))){throw 'HOST_DOMAIN_FIXTURE_ALREADY_EXISTS'}
        $phase='register-protected-fixtures';$fixtureMutation=$true
        Domain-Create (Join-Path $operation 'fixture-intent.json') @{operationId=$request.operationId;folder=$folderName;guard=$guardName;actionSha256=(Domain-Hash ([Text.Encoding]::UTF8.GetBytes($arguments)));runnerSha256=$runnerLease.Sha256;runnerIdentity=$runnerLease.Identity}
        $folder=$rootFolder.CreateFolder($folderName,(Regular-DomainSddl $sid $false));$guard=$rootFolder.CreateFolder($guardName,'O:BAG:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)')
        Regular-AssertDomainSd $folder (Regular-DomainSddl $sid $false);Regular-AssertDomainSd $guard 'O:BAG:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)'
        $before=@{}
        foreach($name in @('PositiveUpdate','PositiveDelete','NegativeUpdate','NegativeDelete','Runner')){
            $def=Regular-NewFixture $scheduler $sid $name $false
            if($name -eq 'Runner'){$def.Actions.Item(1).Path=$runner;$def.Actions.Item(1).Arguments=$arguments;$def.Actions.Item(1).WorkingDirectory=$operation}
            $write=$name -in @('PositiveUpdate','PositiveDelete');$sd=Regular-DomainSddl $sid $write
            $task=$folder.RegisterTaskDefinition($name,$def,18,$sid,$null,3,$sd);Regular-AssertDomainSd $task $sd
            $before[$name]=@{xml=(Domain-Hash ([Text.Encoding]::UTF8.GetBytes([string]$task.Xml)));sd=(Regular-DomainSignature ([string]$task.GetSecurityDescriptor(5)))}
        }
        $task=$folder.GetTask('Runner')
        if($task.Definition.Actions.Item(1).Arguments -cne $arguments -or $task.Definition.Principal.RunLevel -ne 0 -or $task.Definition.Principal.LogonType -ne 3 -or $task.Definition.Settings.MultipleInstances -ne 2 -or $task.Definition.Triggers.Count -ne 0){throw 'HOST_DOMAIN_RUNNER_CONTRACT_CHANGED'}
        Domain-Create (Join-Path $operation 'run-attempt.json') @{operationId=$request.operationId;controllerPid=$scope.ControllerPid;controllerCreation=$scope.ControllerCreation;planSha256=$planSha}
        $phase='run-limited-fixture';$runAttempted=$true;$task.Enabled=$true;[void]$task.Run($null)
        $timer=[Diagnostics.Stopwatch]::StartNew();$running=$null;$entered=$null
        while($timer.ElapsedMilliseconds -lt 45000){
            if((Test-Path -LiteralPath (Join-Path $operation 'failure.json')) -or (Test-Path -LiteralPath (Join-Path $operation 'limited-failure.json'))){throw 'HOST_DOMAIN_FIXED_RUNNER_FAILED'}
            $running=Domain-ReadRunning (Join-Path $operation 'running.json');$entered=Domain-ReadRunning (Join-Path $operation 'entered.json')
            if($null -ne $running -and $null -ne $entered){break};Start-Sleep -Milliseconds 100
        }
        if($null -eq $running -or $null -eq $entered){throw 'HOST_DOMAIN_RUNNER_NOT_READY'}
        if($running.status -cne 'passed' -or $running.operationId -cne $request.operationId -or $running.userMatches -ne $true -or $running.adminRole -ne $false -or $entered.adminRole -ne $false -or $entered.argumentCharacters -ne 11264 -or $entered.planSha256 -cne $planSha){throw 'HOST_DOMAIN_LIMITED_RESULT_REJECTED'}
        $names=@('positive-update','positive-delete','negative-update','negative-delete','create-task','create-folder','set-folder-security','delete-guard-folder')
        if(@($running.cases).Count -ne 8){throw 'HOST_DOMAIN_MATRIX_REJECTED'}
        for($i=0;$i -lt 8;$i++){$c=$running.cases[$i];$positive=$i -lt 2;if($c.name -cne $names[$i] -or $c.attempted -ne $true -or ($positive -and ($c.outcome -cne 'success' -or $null -ne $c.hresult)) -or (-not $positive -and ($c.outcome -cne 'denied' -or $c.hresult -cne '0x80070005'))){throw 'HOST_DOMAIN_MATRIX_REJECTED'}}
        $scope.Retain([uint32]$entered.pid,[string]$entered.creation);$scope.Retain([uint32]$running.pid,[string]$running.creation);$scope.Retain([uint32]$running.childPid,[string]$running.childCreation)
        $phase='double-run-single-instance'
        $beforeInstances=$task.GetInstances(0)
        if($beforeInstances.Count -ne 1){throw 'HOST_DOMAIN_INITIAL_INSTANCE_COUNT'}
        $observedInstanceGuid=[string]$beforeInstances.Item(1).InstanceGuid
        # IgnoreNew can return S_OK without starting another instance. The
        # returned COM interface/GUID is not specified to equal the first reply.
        try{[void]$task.Run($null)}catch{$e=Regular-RootException $_;if(([long]$e.HResult -band 0xffffffffL) -ne 0x8004131fL){throw}}
        Start-Sleep -Milliseconds 1000
        $instances=$task.GetInstances(0)
        if($instances.Count -ne 1 -or [string]$instances.Item(1).InstanceGuid -cne $observedInstanceGuid -or $task.Definition.Actions.Item(1).Arguments -cne $arguments -or (Test-Path -LiteralPath (Join-Path $operation 'failure.json'))){throw 'HOST_DOMAIN_DUPLICATE_OR_ARGUMENT_CHANGE'}
        Domain-ResourceNegative $runner;Domain-ResourceNegative $limited
        foreach($name in @('NegativeUpdate','NegativeDelete')){$n=$folder.GetTask($name);if((Domain-Hash ([Text.Encoding]::UTF8.GetBytes([string]$n.Xml))) -cne $before[$name].xml -or (Regular-DomainSignature ([string]$n.GetSecurityDescriptor(5))) -cne $before[$name].sd){throw 'HOST_DOMAIN_NEGATIVE_OBJECT_CHANGED'}}
        if(-not (Domain-AbsentTask $folder 'PositiveDelete') -or -not (Domain-AbsentTask $folder 'UnexpectedCreate')){throw 'HOST_DOMAIN_POSITIVE_OR_CREATE_RESULT_CHANGED'}
        $updated=$folder.GetTask('PositiveUpdate')
        Regular-AssertFixtureTask $updated $sid 'PositiveUpdate' $true (Join-Path ([Environment]::SystemDirectory) 'WindowsPowerShell\v1.0\powershell.exe') '-NoProfile -NonInteractive -Command exit 0' '' $true
        if($updated.Definition.RegistrationInfo.Description -cne (Regular-NewFixture $scheduler $sid 'PositiveUpdate' $true).RegistrationInfo.Description){throw 'HOST_DOMAIN_POSITIVE_UPDATE_CHANGED'}
        Regular-AssertDomainSd $folder (Regular-DomainSddl $sid $false);Regular-AssertDomainSd $guard 'O:BAG:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)';Domain-AssertOriginal
        $phase='stop-and-reap-owned-subtree'
        $task=Regular-StopOwnedRunner $rootFolder $folderName $sid $runner $arguments $operation $true
        $subtreeConfirmed=$scope.StopAndConfirm()
        $timer.Restart();while($task.GetInstances(0).Count -ne 0 -and $timer.ElapsedMilliseconds -lt 10000){Start-Sleep -Milliseconds 100}
        if(-not $subtreeConfirmed -or $task.GetInstances(0).Count -ne 0){throw 'HOST_DOMAIN_SUBTREE_UNCONFIRMED'}
        $phase='cleanup-exact-fixtures'
        # Resolve the names again after Stop. Do not delete a replacement just
        # because an earlier COM interface or the same pathname was trusted.
        $folder=$rootFolder.GetFolder(('\'+$folderName));$guard=$rootFolder.GetFolder(('\'+$guardName))
        Regular-AssertDomainSd $folder (Regular-DomainSddl $sid $false);Regular-AssertDomainSd $guard 'O:BAG:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)'
        if($folder.GetTasks(1).Count -ne 4 -or $folder.GetFolders(0).Count -ne 0 -or $guard.GetTasks(1).Count -ne 0 -or $guard.GetFolders(0).Count -ne 0){throw 'HOST_DOMAIN_CLEANUP_INVENTORY_CHANGED'}
        $cleanupRunner=Regular-GetOwnedRunner $rootFolder $folderName $sid $runner $arguments $operation
        if($cleanupRunner.GetInstances(0).Count -ne 0 -or $cleanupRunner.Definition.Actions.Count -ne 1 -or $cleanupRunner.Definition.Actions.Item(1).Path -cne $runner -or $cleanupRunner.Definition.Actions.Item(1).Arguments -cne $arguments -or $cleanupRunner.Enabled){throw 'HOST_DOMAIN_CLEANUP_RUNNER_CHANGED'}
        foreach($name in @('NegativeUpdate','NegativeDelete')){$n=$folder.GetTask($name);if((Domain-Hash ([Text.Encoding]::UTF8.GetBytes([string]$n.Xml))) -cne $before[$name].xml -or (Regular-DomainSignature ([string]$n.GetSecurityDescriptor(5))) -cne $before[$name].sd){throw 'HOST_DOMAIN_CLEANUP_NEGATIVE_CHANGED'}}
        $updated=$folder.GetTask('PositiveUpdate')
        Regular-AssertFixtureTask $updated $sid 'PositiveUpdate' $true (Join-Path ([Environment]::SystemDirectory) 'WindowsPowerShell\v1.0\powershell.exe') '-NoProfile -NonInteractive -Command exit 0' '' $true
        if($updated.Definition.RegistrationInfo.Description -cne (Regular-NewFixture $scheduler $sid 'PositiveUpdate' $true).RegistrationInfo.Description){throw 'HOST_DOMAIN_CLEANUP_POSITIVE_CHANGED'}
        foreach($name in @('Runner','PositiveUpdate','NegativeUpdate','NegativeDelete')){
            if($name -eq 'Runner'){
                Regular-DeleteOwnedFixture $rootFolder $folderName $sid $name $false $runner $arguments $operation $false
            } else {
                $isPositive=$name -eq 'PositiveUpdate'
                Regular-DeleteOwnedFixture $rootFolder $folderName $sid $name $isPositive (Join-Path ([Environment]::SystemDirectory) 'WindowsPowerShell\v1.0\powershell.exe') '-NoProfile -NonInteractive -Command exit 0' '' $isPositive
            }
        }
        if($folder.GetTasks(1).Count -ne 0 -or $folder.GetFolders(0).Count -ne 0){throw 'HOST_DOMAIN_UNEXPECTED_FIXTURE_CHILD'}
        Regular-DeleteEmptyFixtureFolder $rootFolder $folderName (Regular-DomainSddl $sid $false)
        Regular-DeleteEmptyFixtureFolder $rootFolder $guardName 'O:BAG:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)'
        if(-not (Domain-AbsentFolder ('\'+$folderName)) -or -not (Domain-AbsentFolder ('\'+$guardName))){throw 'HOST_DOMAIN_CLEANUP_UNCONFIRMED'}
        Domain-AssertOriginal
        $result=@{format=$request.format;status='verified';action=$Action;operationId=$request.operationId;preparationId=$request.preparationId;checkpointSha256=$request.checkpointSha256;cases=8;limitedTaskExecutionVerified=$true;argumentCharacters=11264;argumentsRegisteredExactly=$true;doubleRunSingleInstance=$true;resourceWriteRenameBlocked=$true;processSubtreeClosed=$true;retainedProcessAnchors=3;fixturesRemoved=$true;allSubjectsCovered=$false;originalTaskMutationAttempted=$false;hostStarted=$false;pendingReleased=$false;historicalSubtreeVerified=$false;restartAuthorized=$false;activationImplemented=$false;runner=@{sha256=$runnerLease.Sha256;identity=$runnerLease.Identity;digest=$request.helperDigest};actualMatrix=$running.cases}
        Domain-Create (Join-Path $operation 'completed.json') $result
    }
} catch {
    $e=Regular-RootException $_;$code=$_.Exception.Message;if($code -cnotmatch '^HOST_[A-Z0-9_]+$'){$code='HOST_DOMAIN_NATIVE_FAILURE'}
    $result=@{format='windows-host-regular-domain-v1';status='rejected';phase=$phase;code=$code;errorType=$e.GetType().FullName;hresult=('0x{0:X8}' -f ($e.HResult -band 0xffffffffL));line=$_.InvocationInfo.ScriptLineNumber;column=$_.InvocationInfo.OffsetInLine;taskMutationAttempted=$fixtureMutation;runAttempted=$runAttempted;processSubtreeClosed=$subtreeConfirmed;originalTaskMutationAttempted=$false;hostStarted=$false;pendingReleased=$false;historicalSubtreeVerified=$false;restartAuthorized=$false;next='preserve this exact operation and query status; do not replay verify or delete any reservation'}
    if($runAttempted){try{$result.taskLastResult=[long]$task.LastTaskResult;foreach($name in @('failure.json','limited-failure.json')){if(Test-Path -LiteralPath (Join-Path $operation $name)){$report=Domain-ReadRunning (Join-Path $operation $name);if($null -ne $report){$result[$name]=$report}}}}catch{}}
    if($fixtureMutation){
        $result.ownedRunnerStopAttempted=Regular-TryStopOwnedRunner $rootFolder $folderName $sid $runner $arguments $operation $runAttempted
    }
} finally {
    if($null -ne $scope){$scope.Dispose()}
    for($i=$leases.Count-1;$i -ge 0;$i--){$leases[$i].Dispose()};for($i=$sourceLeases.Count-1;$i -ge 0;$i--){$sourceLeases[$i].Dispose()}
    if($null -ne $scratch){Close-HostBootstrapScratch $scratch}
}
$bytes=Domain-Bytes $result
if($bytes.Length -gt 16384){throw 'HOST_DOMAIN_RESULT_BUDGET'}
$out=[Console]::OpenStandardOutput();$out.Write($bytes,0,$bytes.Length);$out.Flush()
if($result.status -cne 'verified'){exit 1}
