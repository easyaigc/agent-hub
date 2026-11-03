param([Parameter(Mandatory=$true)][ValidateSet('prepare','status','resume')][string]$Action)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$scratch=$null;$leases=@();$sourceLeases=@();$phase='request';$newStateWritten=$false
function Checkpoint-Hash([byte[]]$Bytes){$h=[Security.Cryptography.SHA256]::Create();try{return ([BitConverter]::ToString($h.ComputeHash($Bytes))).Replace('-','').ToLowerInvariant()}finally{$h.Dispose()}}
function Checkpoint-Json($Value){return [Text.UTF8Encoding]::new($false).GetBytes((ConvertTo-Json -InputObject $Value -Depth 30 -Compress)+"`n")}
function Checkpoint-Task {
    $service=New-Object -ComObject Schedule.Service;$service.Connect()
    $folder=$service.GetFolder('\');$task=$folder.GetTask('CodexManage Host Agent')
    if($task.GetInstances(0).Count -ne 0){throw 'HOST_ORIGINAL_TASK_RUNNING'}
    return @{xml=[string]$task.Xml;definitionXml=[string]$task.Definition.XmlText;sd5=[string]$task.GetSecurityDescriptor(5);sd7=[string]$task.GetSecurityDescriptor(7);rootSd5=[string]$folder.GetSecurityDescriptor(5);rootSd7=[string]$folder.GetSecurityDescriptor(7)}
}
function Checkpoint-TaskHashes($Value){$out=@{};foreach($key in @('xml','definitionXml','sd5','sd7','rootSd5','rootSd7')){$out[$key]=Checkpoint-Hash ([Text.Encoding]::UTF8.GetBytes($Value[$key]))};return $out}
function Assert-CheckpointTask($Value,$Expected){$h=Checkpoint-TaskHashes $Value;if($h.xml -cne $Expected.xmlSha256 -or $h.sd5 -cne $Expected.security.sha256 -or $h.rootSd5 -cne $Expected.rootSecurity.sha256){throw 'HOST_ORIGINAL_TASK_CHANGED'};return $h}
function Checkpoint-CreateIntent([string]$Path,[byte[]]$Bytes){
    $user=[Security.Principal.WindowsIdentity]::GetCurrent().User
    $security=[Security.AccessControl.FileSecurity]::new();$security.SetOwner($user);$security.SetAccessRuleProtection($true,$false)
    foreach($sid in @($user.Value,'S-1-5-18','S-1-5-32-544')){$security.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new($sid),[Security.AccessControl.FileSystemRights]::FullControl,[Security.AccessControl.AccessControlType]::Allow))}
    $stream=[IO.FileStream]::new($Path,[IO.FileMode]::CreateNew,[Security.AccessControl.FileSystemRights]::Write,[IO.FileShare]::Read,4096,[IO.FileOptions]::WriteThrough,$security)
    try{$stream.Write($Bytes,0,$Bytes.Length);$stream.Flush($true)}finally{$stream.Dispose()}
}
try {
    $reader=[IO.StreamReader]::new([Console]::OpenStandardInput(),[Text.UTF8Encoding]::new($false,$true))
    try{$raw=$reader.ReadToEnd()}finally{$reader.Dispose()}
    if($raw.Length -gt 16384){throw 'HOST_CHECKPOINT_REQUEST_TOO_LARGE'}
    $request=ConvertFrom-Json -InputObject $raw
    if($request.format -cne 'windows-host-regular-prepare-v1' -or $request.action -cne $Action){throw 'HOST_CHECKPOINT_REQUEST_REJECTED'}
    foreach($key in @('hostId','operationId','legacyOperationId')){if($request.$key -cnotmatch '^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$'){throw 'HOST_CHECKPOINT_TARGET_REJECTED'}}
    if($request.operationId -ceq $request.legacyOperationId -or $request.p0Sha256 -cnotmatch '^[a-f0-9]{64}$' -or $request.packageSha256 -cnotmatch '^[a-f0-9]{64}$'){throw 'HOST_CHECKPOINT_ID_REJECTED'}
    $source=Join-Path $PSScriptRoot '..\packages\codex-adapter\windows'
    . (Join-Path $source 'private-bootstrap.ps1')
    $scratch=Open-HostBootstrapScratch $source
    if($null -eq $scratch){throw 'HOST_PROTECTED_STORAGE_REQUIRED'}
    $userProfileDirectory=[Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)
    $hub=Join-Path $userProfileDirectory '.config\agent-hub'
    $root=Join-Path $hub 'host-regular-recovery'
    $operation=Join-Path $root $request.operationId
    $reservation=Join-Path $root 'reservation.json'
    if($Action -eq 'prepare') {
        $phase='reserve-new-checkpoint'
        New-HostBootstrapDirectory $root
        if((Test-Path -LiteralPath $reservation) -or (Test-Path -LiteralPath $operation)){throw 'HOST_PREPARATION_ALREADY_RESERVED_QUERY_STATUS'}
        # Exclusive durable reservation precedes any native compilation. An
        # unknown result must be queried by this ID, never replayed/new-ID'ed.
        Checkpoint-CreateIntent $reservation (Checkpoint-Json @{format='windows-host-regular-reservation-v1';operationId=$request.operationId;hostId=$request.hostId;p0Sha256=$request.p0Sha256;packageSha256=$request.packageSha256})
        $newStateWritten=$true
        New-HostBootstrapDirectory $operation
    } else {Assert-HostBootstrapPath $root $true;if(Test-Path -LiteralPath $operation){Assert-HostBootstrapPath $operation $true}}
    $phase='load-native-checkpoint'
    foreach($name in @('private-state.cs','private-state-compilation.cs','private-state-recovery.cs')){
        $sourceStream=[IO.FileStream]::new((Join-Path $source $name),[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
        $sourceLeases+=,$sourceStream
        if($sourceStream.Length -gt 1048576){throw 'HOST_CHECKPOINT_SOURCE_BUDGET'}
        $hash=[Security.Cryptography.SHA256]::Create()
        try{$sourceHash=([BitConverter]::ToString($hash.ComputeHash($sourceStream))).Replace('-','').ToLowerInvariant()}finally{$hash.Dispose()}
        if($sourceHash -cne $request.sourceHashes.$name){throw 'HOST_CHECKPOINT_SOURCE_CHANGED'}
        $sourceStream.Position=0
    }
    Add-Type -Path @((Join-Path $source 'private-state.cs'),(Join-Path $source 'private-state-compilation.cs'),(Join-Path $source 'private-state-recovery.cs'))
    $reservationLease=[CodexManagePrivateStateSecurity]::OpenRecoveryRead($reservation,4096);$leases+=,$reservationLease
    $saved=ConvertFrom-Json -InputObject ([Text.Encoding]::UTF8.GetString($reservationLease.Bytes))
    foreach($key in @('operationId','hostId','p0Sha256','packageSha256')){if($saved.$key -cne $request.$key){throw 'HOST_CHECKPOINT_RESERVATION_MISMATCH'}}
    $phase='bind-original-task-and-private-files'
    $task=Checkpoint-Task;$taskHashes=Assert-CheckpointTask $task $request.expected.task
    $legacy=Join-Path (Join-Path $hub 'host-migrations') $request.legacyOperationId
    $paths=@{configuration=(Join-Path $request.installedProgram '.local\desktop-runtime\windows-host.json');pending=(Join-Path $hub 'host-migrations\pending.json');intent=(Join-Path $legacy 'intent.json');domainIntent=(Join-Path $legacy 'domain.intent.json');runAttempt=(Join-Path $legacy 'domain.run-attempt.json')}
    if(Test-Path -LiteralPath (Join-Path $legacy 'domain.process-anchors.json')){throw 'HOST_LEGACY_ANCHORS_CHANGED'}
    if(Test-Path -LiteralPath (Join-Path $request.installedProgram '.local\desktop-runtime\task-recovery\pending.json')){throw 'HOST_DISTINCT_TASK_REPAIR_PENDING_PRESENT'}
    $files=@{};$snapshots=@{}
    foreach($name in @('configuration','pending','intent','domainIntent','runAttempt')){
        $lease=[CodexManagePrivateStateSecurity]::OpenRecoveryRead($paths[$name],131072);$leases+=,$lease
        if($lease.Sha256 -cne $request.expected.files.$name){throw 'HOST_ORIGINAL_PRIVATE_FILE_CHANGED'}
        $files[$name]=@{sha256=$lease.Sha256;identity=$lease.Identity;bytes=$lease.Bytes.Length};$snapshots[$name]=$lease
    }
    foreach($kind in @('CodexManage Recovery Test ','CodexManage Recovery Guard ')){
        $scheduler=New-Object -ComObject Schedule.Service;$scheduler.Connect()
        try{$null=$scheduler.GetFolder(('\'+$kind+$request.legacyOperationId));throw 'HOST_LEGACY_FIXTURE_PRESENT'}catch{if($_.Exception.Message -eq 'HOST_LEGACY_FIXTURE_PRESENT'){throw};$e=$_.Exception;while($null -ne $e.InnerException){$e=$e.InnerException};$code=([long]$e.HResult -band 0xffffL);if($code -ne 2 -and $code -ne 3){throw}}
    }
    $boot=(Get-CimInstance -ClassName Win32_OperatingSystem -OperationTimeoutSec 10).LastBootUpTime.ToUniversalTime().ToString('o')
    $checkpoint=Join-Path $operation 'checkpoint.json';$prepared=Join-Path $operation 'prepared.json'
    if($Action -ne 'prepare' -and (-not (Test-Path -LiteralPath $checkpoint) -or -not (Test-Path -LiteralPath $prepared))) {
        $result=@{format=$request.format;status='incomplete';action=$Action;operationId=$request.operationId;reservationBound=$true;originalFilesUnchanged=$true;originalTaskMutationAttempted=$false;pendingReleased=$false;hostStarted=$false;restartAuthorized=$false;next='retain the reservation and partial resources; maintainer must reconcile this exact operation before further preparation'}
    } else {
    if($Action -eq 'prepare'){
        if($boot -cne $request.expected.boot){throw 'HOST_BOOT_CHANGED_BEFORE_CHECKPOINT'}
        $phase='native-resource-preparation'
        $resources=@{}
        foreach($name in @('private-state','host-launcher')){
            $directory=Join-Path (Join-Path $hub 'windows-native-v1\native-helpers') $request.helperDigests.$name
            if(Test-Path -LiteralPath $directory){throw 'HOST_PREPARATION_RESOURCE_ALREADY_EXISTS'}
            $null=[CodexManagePrivateStateSecurity]::PrepareNative($name,$source,$directory,30000)
            $exe=Join-Path $directory ($name+'.exe');$lease=[CodexManagePrivateStateSecurity]::OpenRecoveryRead($exe,209715)
            try{$resources[$name]=@{sha256=$lease.Sha256;identity=$lease.Identity;bytes=$lease.Bytes.Length;digest=$request.helperDigests.$name}}finally{$lease.Dispose()}
        }
        $phase='write-new-rollback-capsule'
        $fixtureDirectory=Join-Path $operation 'lease-fixture'
        if(Test-Path -LiteralPath $fixtureDirectory){throw 'HOST_PREPARATION_FIXTURE_ALREADY_EXISTS'}
        [CodexManagePrivateStateSecurity]::Execute('create-directory',$fixtureDirectory)
        $fixture=Join-Path $fixtureDirectory 'synthetic.txt'
        $null=[CodexManagePrivateStateSecurity]::CreateRecoveryFile($fixture,[Text.Encoding]::ASCII.GetBytes('synthetic checkpoint lease fixture'))
        $fixtureLease=[CodexManagePrivateStateSecurity]::OpenRecoveryRead($fixture,4096)
        try{
            $writeRejected=$false;$renameRejected=$false;$parentRenameRejected=$false
            try{[IO.File]::WriteAllText($fixture,'unexpected')}catch [IO.IOException]{$writeRejected=$true}
            try{[IO.File]::Move($fixture,($fixture+'.moved'))}catch [IO.IOException]{$renameRejected=$true}
            try{[IO.Directory]::Move($fixtureDirectory,($fixtureDirectory+'.moved'))}catch [IO.IOException]{$parentRenameRejected=$true}
            if(-not $writeRejected -or -not $renameRejected -or -not $parentRenameRejected){throw 'HOST_CHECKPOINT_LEASE_NEGATIVE_FAILED'}
            $phase='execute-owned-private-check-only'
            $executable=Join-Path (Join-Path (Join-Path $hub 'windows-native-v1\native-helpers') $request.helperDigests.'private-state') 'private-state.exe'
            $imageLease=[CodexManagePrivateStateSecurity]::OpenRecoveryRead($executable,209715)
            try{
                $start=[Diagnostics.ProcessStartInfo]::new();$start.FileName=$executable
                $start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
                $start.Arguments='check "'+$fixture+'"'
                $child=[Diagnostics.Process]::new();$child.StartInfo=$start
                try{
                    if(-not $child.Start()){throw 'HOST_PRIVATE_CHECK_NOT_STARTED'}
                    $childId=$child.Id;$childStarted=$child.StartTime.ToUniversalTime().ToFileTimeUtc().ToString()
                    if(-not $child.WaitForExit(5000)){$child.Kill();if(-not $child.WaitForExit(5000)){throw 'HOST_PRIVATE_CHECK_EXIT_UNCONFIRMED'};throw 'HOST_PRIVATE_CHECK_TIMED_OUT'}
                    $out=$child.StandardOutput.ReadToEnd();$err=$child.StandardError.ReadToEnd()
                    if($child.ExitCode -ne 0 -or $out.Trim() -cne 'PRIVATE_STATE_OK' -or $err.Length -ne 0){throw 'HOST_PRIVATE_CHECK_REJECTED'}
                    $privateCheck=@{pid=$childId;startedFileTime=$childStarted;exitConfirmed=$true;exitCode=0;action='check-new-synthetic-file-only';sourceImageIdentity=$imageLease.Identity;stdoutSha256=(Checkpoint-Hash ([Text.Encoding]::UTF8.GetBytes($out)));existingElevatedContext=([Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator))}
                }finally{$child.Dispose()}
            }finally{$imageLease.Dispose()}
        }finally{$fixtureLease.Dispose()}
        # Same principal, same objects: the operations must succeed once the
        # native leases close. A permission/path failure cannot fake a negative.
        [IO.File]::WriteAllBytes($fixture,[Text.Encoding]::ASCII.GetBytes('synthetic checkpoint lease fixture'))
        [IO.File]::Move($fixture,($fixture+'.moved'));[IO.File]::Move(($fixture+'.moved'),$fixture)
        [IO.Directory]::Move($fixtureDirectory,($fixtureDirectory+'.moved'));[IO.Directory]::Move(($fixtureDirectory+'.moved'),$fixtureDirectory)
        foreach($name in $snapshots.Keys){$null=[CodexManagePrivateStateSecurity]::CreateRecoveryFile((Join-Path $operation ($name+'.snapshot')),$snapshots[$name].Bytes)}
        $taskBytes=Checkpoint-Json $task;$null=[CodexManagePrivateStateSecurity]::CreateRecoveryFile((Join-Path $operation 'task.snapshot.json'),$taskBytes)
        $record=@{format='windows-host-regular-checkpoint-v1';operationId=$request.operationId;hostId=$request.hostId;legacyOperationId=$request.legacyOperationId;p0Sha256=$request.p0Sha256;packageSha256=$request.packageSha256;boot=$boot;files=$files;task=$taskHashes;taskSnapshotSha256=(Checkpoint-Hash $taskBytes);resources=$resources;privateCheck=$privateCheck;leaseNegatives=@{writeRejected=$writeRejected;fileRenameRejected=$renameRejected;parentRenameRejected=$parentRenameRejected;positiveControlsPassed=$true};oldDaclRejectionCleared=$false;historicalSubtreeVerified=$false;pendingReleased=$false;formalTaskMutated=$false;activationImplemented=$false;restartAuthorized=$false}
        $bytes=Checkpoint-Json $record;$null=[CodexManagePrivateStateSecurity]::CreateRecoveryFile($checkpoint,$bytes)
        $again=Checkpoint-TaskHashes (Checkpoint-Task)
        foreach($key in $taskHashes.Keys){if($again[$key] -cne $taskHashes[$key]){throw 'HOST_ORIGINAL_TASK_CHANGED_DURING_PREPARATION'}}
    }
    $phase='verify-capsule'
    if($Action -eq 'prepare'){$sealValue=[pscustomobject]@{operationId=$request.operationId;checkpointSha256=(Checkpoint-Hash $bytes)}}
    else{$seal=[CodexManagePrivateStateSecurity]::OpenRecoveryRead($prepared,4096);$leases+=,$seal;$sealValue=ConvertFrom-Json -InputObject ([Text.Encoding]::UTF8.GetString($seal.Bytes))}
    $recordLease=[CodexManagePrivateStateSecurity]::OpenRecoveryRead($checkpoint,65536);$leases+=,$recordLease
    if($sealValue.operationId -cne $request.operationId -or $sealValue.checkpointSha256 -cne $recordLease.Sha256){throw 'HOST_CHECKPOINT_SEAL_MISMATCH'}
    $record=ConvertFrom-Json -InputObject ([Text.Encoding]::UTF8.GetString($recordLease.Bytes))
    foreach($key in @('operationId','hostId','legacyOperationId','p0Sha256','packageSha256')){if($record.$key -cne $request.$key){throw 'HOST_CHECKPOINT_SCOPE_MISMATCH'}}
    foreach($name in @('private-state','host-launcher')){
        $path=Join-Path (Join-Path (Join-Path $hub 'windows-native-v1\native-helpers') $request.helperDigests.$name) ($name+'.exe')
        $resourceLease=[CodexManagePrivateStateSecurity]::OpenRecoveryRead($path,209715);$leases+=,$resourceLease
        if($resourceLease.Identity -cne $record.resources.$name.identity -or $resourceLease.Sha256 -cne $record.resources.$name.sha256){throw 'HOST_CHECKPOINT_RESOURCE_CHANGED'}
    }
    foreach($name in @('configuration','pending','intent','domainIntent','runAttempt')){
        $copy=[CodexManagePrivateStateSecurity]::OpenRecoveryRead((Join-Path $operation ($name+'.snapshot')),131072);try{if($copy.Sha256 -cne $record.files.$name.sha256 -or $files[$name].identity -cne $record.files.$name.identity){throw 'HOST_CHECKPOINT_SNAPSHOT_MISMATCH'}}finally{$copy.Dispose()}
    }
    $copy=[CodexManagePrivateStateSecurity]::OpenRecoveryRead((Join-Path $operation 'task.snapshot.json'),65536);try{if($copy.Sha256 -cne $record.taskSnapshotSha256){throw 'HOST_CHECKPOINT_TASK_COPY_MISMATCH'}}finally{$copy.Dispose()}
    foreach($key in $taskHashes.Keys){if($taskHashes[$key] -cne $record.task.$key){throw 'HOST_CHECKPOINT_TASK_MISMATCH'}}
    if($Action -eq 'prepare'){$null=[CodexManagePrivateStateSecurity]::CreateRecoveryFile($prepared,(Checkpoint-Json @{format='windows-host-regular-prepared-v1';operationId=$request.operationId;checkpointSha256=$recordLease.Sha256}))}
    $result=@{format=$request.format;status='prepared';action=$Action;operationId=$request.operationId;hostId=$request.hostId;checkpointSha256=$recordLease.Sha256;taskHashes=$taskHashes;resources=$record.resources;privateCheck=$record.privateCheck;leaseNegatives=$record.leaseNegatives;originalFilesUnchanged=$true;originalTaskMutationAttempted=$false;pendingReleased=$false;historicalSubtreeVerified=$false;activationImplemented=$false;rollbackTaskMutationImplemented=$false;rollbackCapsuleVerified=$true;restartAuthorized=$false;bootChanged=($boot -cne $record.boot);completeRestartProved=$false;hostStarted=$false;newPreparationStateWritten=$newStateWritten;existingElevatedContext=([Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator));limitedTaskExecutionVerified=$false}
    }
} catch {
    $e=$_.Exception;$chain=@();for($n=0;$n -lt 6 -and $null -ne $e;$n++){$item=@{type=$e.GetType().FullName;hresult=('0x{0:X8}' -f ([long]$e.HResult -band 0xffffffffL))};if($e -is [ComponentModel.Win32Exception]){$item.win32=$e.NativeErrorCode};$chain+=,$item;$e=$e.InnerException}
    $safeCode=if($_.Exception.Message -cmatch '^(HOST_|UNSAFE_HOST_|SHARED_HOST_|UNPROTECTED_HOST_|UNKNOWN_HOST_)[A-Z_]{1,90}$'){$_.Exception.Message}else{'HOST_PREPARATION_NATIVE_FAILURE'}
    $result=@{format='windows-host-regular-prepare-v1';status='rejected';phase=$phase;code=$safeCode;errorChain=$chain;line=$_.InvocationInfo.ScriptLineNumber;column=$_.InvocationInfo.OffsetInLine;originalTaskMutationAttempted=$false;hostStarted=$false;pendingReleased=$false;historicalSubtreeVerified=$false;newPreparationStateWritten=$newStateWritten;newResourcesMayExist=$true;restartAuthorized=$false;next='review this exact preparation failure; do not replay prepare or delete reservations'}
} finally {
    foreach($lease in $leases){$lease.Dispose()}
    foreach($lease in $sourceLeases){$lease.Dispose()}
    if($null -ne $scratch){Close-HostBootstrapScratch $scratch}
}
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$json=ConvertTo-Json -InputObject $result -Depth 24 -Compress
if([Text.Encoding]::UTF8.GetByteCount($json) -gt 12288){throw 'HOST_CHECKPOINT_RESULT_TOO_LARGE'}
[Console]::Out.WriteLine($json)
if($result.status -eq 'rejected'){exit 1}
