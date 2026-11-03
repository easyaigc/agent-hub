param([Parameter(Mandatory=$true)][ValidateSet('prepare','continue-prepare','status','qualify-no-restart','activate','rollback')][string]$Action)
$ErrorActionPreference='Stop';Set-StrictMode -Version Latest
$leases=@();$originalLeases=@();$sourceLeases=@();$scratch=$null;$phase='request';$formalWrite=$false;$runAttempt=$false;$pendingArchived=$false;$fixtureCreated=$false;$retirementIntent=$false
function Activation-Hash([byte[]]$Bytes){$h=[Security.Cryptography.SHA256]::Create();try{return ([BitConverter]::ToString($h.ComputeHash($Bytes))).Replace('-','').ToLowerInvariant()}finally{$h.Dispose()}}
function Activation-Bytes($Value){return [Text.UTF8Encoding]::new($false).GetBytes((ConvertTo-Json -Depth 40 -Compress -InputObject $Value)+"`n")}
function Activation-Read([string]$Path,[int]$Maximum=1048576){$l=[CodexManagePrivateStateSecurity]::OpenRecoveryRead($Path,$Maximum);$script:leases+=,$l;return $l}
function Activation-Json($Lease){return ConvertFrom-Json ([Text.Encoding]::UTF8.GetString($Lease.Bytes))}
function Activation-Create([string]$Path,$Value){$null=[CodexManagePrivateStateSecurity]::CreateRecoveryFile($Path,(Activation-Bytes $Value))}
function Activation-Task {
    $t=$root.GetTask('CodexManage Host Agent')
    $raw=@{xml=[string]$t.Xml;definitionXml=[string]$t.Definition.XmlText;sd5=[string]$t.GetSecurityDescriptor(5);sd7=[string]$t.GetSecurityDescriptor(7);rootSd5=[string]$root.GetSecurityDescriptor(5);rootSd7=[string]$root.GetSecurityDescriptor(7)}
    $hashes=@{};foreach($key in $raw.Keys){$hashes[$key]=Activation-Hash ([Text.Encoding]::UTF8.GetBytes($raw[$key]))}
    return @{task=$t;raw=$raw;hashes=$hashes}
}
function Activation-OriginalTask {
    $v=Activation-Task
    if($v.task.GetInstances(0).Count -ne 0){throw 'HOST_ACTIVATION_ORIGINAL_TASK_RUNNING'}
    foreach($key in $checkpoint.task.PSObject.Properties.Name){if($v.hashes[$key] -cne $checkpoint.task.$key){throw 'HOST_ACTIVATION_ORIGINAL_TASK_CHANGED'}}
    return $v
}
function Activation-AbsentFolder([string]$Path){try{$null=$root.GetFolder($Path);return $false}catch{$e=Regular-RootException $_;if(([long]$e.HResult -band 0xffffL) -in @(2,3)){return $true};throw}}
function Activation-ReadOriginal {
    $state=Join-Path $r.installedProgram '.local\desktop-runtime'
    $paths=@{configuration=(Join-Path $state 'windows-host.json');desktop=(Join-Path $state 'windows-desktop.json');token=(Join-Path $state 'access-token');certificate=(Join-Path $state 'direct-discovery\cert.pem');key=(Join-Path $state 'direct-discovery\key.pem');runtime=(Join-Path $state 'runtime.json')}
    $values=@{};$files=@{}
    $script:phase='bind-original-state-directory';[CodexManagePrivateStateSecurity]::Execute('check',$state)
    foreach($name in $paths.Keys){
        $script:phase='bind-original-'+$name
        $l=if($name -eq 'runtime'){[CodexManagePrivateStateSecurity]::OpenRecoveryStateRead($paths[$name])}else{[CodexManagePrivateStateSecurity]::OpenRecoveryRead($paths[$name],1048576)}
        $script:originalLeases+=,$l;$files[$name]=@{sha256=$l.Sha256;identity=$l.Identity;bytes=$l.Bytes.Length};$values[$name]=$l
    }
    if($files.configuration.sha256 -cne $checkpoint.files.configuration.sha256 -or $files.configuration.identity -cne $checkpoint.files.configuration.identity){throw 'HOST_ACTIVATION_ORIGINAL_CONFIG_CHANGED'}
    $configuration=Activation-Json $values.configuration
    if(-not [string]::Equals($configuration.environment.HOST_AGENT_STATE_DIR,$state,[StringComparison]::OrdinalIgnoreCase) -or $configuration.environment.CODEX_RUNTIME_MODE -cne 'desktop-runtime' -or (Activation-Json $values.runtime).hostId -cne $r.hostId){throw 'HOST_ACTIVATION_ORIGINAL_IDENTITY_CHANGED'}
    return @{state=$state;files=$files;configuration=$configuration}
}
function Activation-CloseOriginal {foreach($l in $script:originalLeases){$l.Dispose()};$script:originalLeases=@()}
function Activation-LegacyFiles([bool]$IncludePending) {
    $legacy=Join-Path (Join-Path $hub 'host-migrations') $r.legacyOperationId
    if(Test-Path -LiteralPath (Join-Path $legacy 'domain.process-anchors.json')){throw 'HOST_ACTIVATION_LEGACY_ANCHORS_CHANGED'}
    $names=@('intent','domainIntent','runAttempt');if($IncludePending){$names+=,'pending'}
    foreach($name in $names){$script:phase='bind-legacy-'+$name;$path=switch($name){'pending'{Join-Path $hub 'host-migrations\pending.json'}'intent'{Join-Path $legacy 'intent.json'}'domainIntent'{Join-Path $legacy 'domain.intent.json'}'runAttempt'{Join-Path $legacy 'domain.run-attempt.json'}};$l=Activation-Read $path;if($l.Sha256 -cne $checkpoint.files.$name.sha256 -or $l.Identity -cne $checkpoint.files.$name.identity){throw 'HOST_ACTIVATION_LEGACY_FILE_CHANGED'}}
}
function Activation-Witness {
    $machine=[string](Get-ItemProperty -LiteralPath 'HKLM:\SOFTWARE\Microsoft\Cryptography' -Name MachineGuid).MachineGuid
    $user=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    return @{machineBinding=(Activation-Hash ([Text.Encoding]::UTF8.GetBytes($machine)));userBinding=(Activation-Hash ([Text.Encoding]::UTF8.GetBytes($user)));hostId=$r.hostId;bootUtc=(Get-CimInstance Win32_OperatingSystem -OperationTimeoutSec 10).LastBootUpTime.ToUniversalTime().ToString('o');observedUtc=[DateTime]::UtcNow.ToString('o');uptimeMs=[CodexManagePrivateStateSecurity]::GetTickCount64().ToString([Globalization.CultureInfo]::InvariantCulture)}
}
function Activation-PreservedScope {
    # The wrapper reconstructed the old sealed command without executing it.
    # Bind that proof to the same immutable records held by native read leases.
    $scope=$r.legacyScope
    if($scope.manifestSha256 -cne '3b170dd2bc1325266b929c0f3934f5b658e82367f7f1815b1374251c56bee1ea' -or $scope.sourceCommit -cne '35ff3a29e22614b4c97d3332f4dd3d3eb8e902ee' -or $scope.purpose -cne 'domain-verification' -or $scope.reviewedExecutionChainVerified -ne $true -or $scope.historicalTestPassed -ne $false -or $scope.historicalSubtreeVerified -ne $false -or $scope.pendingArchived -ne $false){throw 'HOST_LEGACY_TEST_SCOPE_UNPROVED'}
    if($scope.intentSha256 -cne $checkpoint.files.intent.sha256 -or $scope.domainIntentSha256 -cne $checkpoint.files.domainIntent.sha256){throw 'HOST_LEGACY_TEST_SCOPE_CHANGED'}
    Activation-LegacyFiles $true
    $legacy=Join-Path (Join-Path $hub 'host-migrations') $r.legacyOperationId
    $domain=Activation-Json (Activation-Read (Join-Path $legacy 'domain.intent.json'))
    if($domain.actionSha256 -cne $scope.actionSha256 -or $domain.operationId -cne $r.legacyOperationId){throw 'HOST_LEGACY_TEST_SCOPE_CHANGED'}
    $witness=Activation-Witness
    foreach($key in @('machineBinding','userBinding','hostId')){if($witness[$key] -cne $plan.boot.$key){throw 'HOST_ACTIVATION_TARGET_CHANGED'}}
    return $scope
}
function Activation-Node([string]$Node,[string]$Script,[string[]]$Arguments,[string]$InputText='') {
    $p=[Diagnostics.ProcessStartInfo]::new();$p.FileName=$Node;$p.UseShellExecute=$false;$p.CreateNoWindow=$true;$p.RedirectStandardInput=$true;$p.RedirectStandardOutput=$true;$p.RedirectStandardError=$true
    foreach($word in @($Script)+$Arguments){if($word.Contains('"') -or $word.EndsWith('\')){throw 'HOST_ACTIVATION_NODE_ARGUMENT_REJECTED'}}
    $p.Arguments=(@(@($Script)+$Arguments)|ForEach-Object{'"'+$_+'"'}) -join ' '
    $p.EnvironmentVariables.Remove('NODE_OPTIONS');$p.EnvironmentVariables.Remove('NODE_PATH');$p.EnvironmentVariables.Remove('PSModulePath');$p.EnvironmentVariables['CODEX_MANAGE_WINDOWS_NATIVE_STORAGE']='profile-v1'
    $child=[Diagnostics.Process]::new();$child.StartInfo=$p
    try{
        if(-not $child.Start()){throw 'HOST_ACTIVATION_NODE_NOT_STARTED'}
        $helperName='helper-'+$child.Id+'-'+[Guid]::NewGuid().ToString('N');$helperCreation=$child.StartTime.ToUniversalTime().ToFileTimeUtc().ToString()
        Activation-Create (Join-Path $operation ($helperName+'.json')) @{pid=$child.Id;creation=$helperCreation;scriptSha256=(Activation-Hash ([IO.File]::ReadAllBytes($Script)));purpose='fixed-readonly-policy-or-startup-preparation';processExitNotYetConfirmed=$true}
        $stdout=$child.StandardOutput.ReadToEndAsync();$stderr=$child.StandardError.ReadToEndAsync();$child.StandardInput.Write($InputText);$child.StandardInput.Close()
        if(-not $child.WaitForExit(60000)){throw 'HOST_ACTIVATION_NODE_EXIT_UNCONFIRMED'}
        $out=$stdout.Result;$err=$stderr.Result
        Activation-Create (Join-Path $operation ($helperName+'.exit.json')) @{pid=$child.Id;creation=$helperCreation;exitConfirmed=$true;exitCode=$child.ExitCode;stdoutSha256=(Activation-Hash ([Text.Encoding]::UTF8.GetBytes($out)));stderrBytes=[Text.Encoding]::UTF8.GetByteCount($err)}
        if($out.Length -gt 16384 -or $err.Length -ne 0 -or $child.ExitCode -ne 0){throw 'HOST_ACTIVATION_NODE_REJECTED'}
        return ConvertFrom-Json $out
    }finally{$child.Dispose()}
}
function Activation-Deploy {
    $raw=[IO.File]::ReadAllBytes((Join-Path $r.sourceRoot 'runtime-manifest.json'))
    if((Activation-Hash $raw) -cne $r.runtimeSha256){throw 'HOST_ACTIVATION_RUNTIME_CHANGED'}
    $m=ConvertFrom-Json ([Text.Encoding]::UTF8.GetString($raw))
    if($m.format -cne 'windows-host-regular-runtime-v1' -or $m.sourceCommit -cne $r.sourceCommit){throw 'HOST_ACTIVATION_RUNTIME_REJECTED'}
    New-HostBootstrapDirectory $serviceRoot;New-HostBootstrapDirectory (Join-Path $serviceRoot 'programs')
    $target=Join-Path (Join-Path $serviceRoot 'programs') $r.runtimeSha256
    $existing=Test-Path -LiteralPath $target
    if(-not $existing){New-HostBootstrapDirectory $target}
    $expected=@('runtime-manifest.json')
    foreach($property in $m.files.PSObject.Properties){
        $name=$property.Name;$entry=$property.Value
        if($name -cnotmatch '^[a-zA-Z0-9@._/-]+$' -or $name.StartsWith('/') -or @($name.Split('/')|Where-Object{$_ -in @('','.','..')}).Count -ne 0 -or $entry.sha256 -cnotmatch '^[a-f0-9]{64}$' -or $entry.bytes -lt 0 -or $entry.bytes -gt 134217728){throw 'HOST_ACTIVATION_RUNTIME_ENTRY_REJECTED'}
        $sourcePath=Join-Path $r.sourceRoot $name;$destination=Join-Path $target $name
        if(-not $existing){
            $cursor=$target;$parts=$name.Split('/');for($i=0;$i -lt $parts.Count-1;$i++){$cursor=Join-Path $cursor $parts[$i];New-HostBootstrapDirectory $cursor}
            [IO.File]::Copy($sourcePath,$destination,$false)
        }
        $l=[CodexManagePrivateStateSecurity]::OpenRecoveryRuntimeRead($destination,134217728,$entry.sha256)
        try{if($l.Bytes.Length -ne $entry.bytes){throw 'HOST_ACTIVATION_RUNTIME_SIZE_CHANGED'}}finally{$l.Dispose()}
        $expected+=,$name
    }
    if(-not $existing){[IO.File]::WriteAllBytes((Join-Path $target 'runtime-manifest.json'),$raw)}
    $l=[CodexManagePrivateStateSecurity]::OpenRecoveryRuntimeRead((Join-Path $target 'runtime-manifest.json'),2097152,$r.runtimeSha256);$l.Dispose()
    $actual=@();foreach($item in Get-ChildItem -LiteralPath $target -Recurse -Force){if(([int]$item.Attributes -band [int][IO.FileAttributes]::ReparsePoint) -ne 0){throw 'HOST_ACTIVATION_RUNTIME_REPARSE'};if(-not $item.PSIsContainer){$actual+=,$item.FullName.Substring($target.Length+1).Replace('\','/')}}
    if((($actual|Sort-Object) -join "`n") -cne (($expected|Sort-Object) -join "`n")){throw 'HOST_ACTIVATION_RUNTIME_INVENTORY_CHANGED'}
    return $target
}
function Activation-Definition([string]$Xml,[string]$SecurityDescriptor) {
    $doc=[xml]$Xml;$ns=[Xml.XmlNamespaceManager]::new($doc.NameTable);$ns.AddNamespace('t',$doc.DocumentElement.NamespaceURI)
    $nodes=$doc.SelectNodes('/t:Task/t:RegistrationInfo/t:SecurityDescriptor',$ns)
    if($nodes.Count -gt 1){throw 'HOST_ACTIVATION_EMBEDDED_SECURITY_CHANGED'}
    if($nodes.Count -eq 1){if([string]::IsNullOrEmpty($SecurityDescriptor) -or $nodes[0].InnerText -cne $SecurityDescriptor){throw 'HOST_ACTIVATION_EMBEDDED_SECURITY_CHANGED'};$null=$nodes[0].ParentNode.RemoveChild($nodes[0])}
    return $doc.OuterXml
}
function Activation-Shape([string]$Xml,[string]$SecurityDescriptor='') {
    $Xml=Activation-Definition $Xml $SecurityDescriptor
    $doc=[xml]$Xml;$ns=[Xml.XmlNamespaceManager]::new($doc.NameTable);$ns.AddNamespace('t',$doc.DocumentElement.NamespaceURI)
    foreach($name in @('Command','Arguments','WorkingDirectory')){$node=$doc.SelectSingleNode('/t:Task/t:Actions/t:Exec/t:'+$name,$ns);if($null -eq $node){throw 'HOST_ACTIVATION_ACTION_SCHEMA_CHANGED'};$node.InnerText='expected-'+$name}
    $user=$doc.SelectSingleNode('/t:Task/t:Principals/t:Principal/t:UserId',$ns);if($null -eq $user){throw 'HOST_ACTIVATION_PRINCIPAL_MISSING'};$user.InnerText=Regular-PrincipalSid $user.InnerText
    $enabled=$doc.SelectSingleNode('/t:Task/t:Settings/t:Enabled',$ns);if($null -eq $enabled){throw 'HOST_ACTIVATION_ENABLED_MISSING'};$enabled.InnerText='expected-enabled'
    return Activation-Hash ([Text.Encoding]::UTF8.GetBytes($doc.OuterXml))
}
function Activation-DesiredTask([bool]$Enabled) {
    $v=Activation-Task;$d=$v.task.Definition
    if((Activation-Shape $d.XmlText $v.raw.sd7) -cne $plan.taskShape -or $d.Actions.Count -ne 1 -or $d.Principal.LogonType -ne 3 -or $d.Principal.RunLevel -ne 0 -or (Regular-PrincipalSid $d.Principal.UserId) -cne $sid -or $d.Settings.MultipleInstances -ne 2 -or $v.task.Enabled -ne $Enabled){throw 'HOST_ACTIVATION_REPLACEMENT_TASK_CHANGED'}
    $a=$d.Actions.Item(1)
    if(-not [string]::Equals($a.Path,$plan.launcher,[StringComparison]::OrdinalIgnoreCase) -or $a.Arguments -cne $plan.arguments -or -not [string]::Equals($a.WorkingDirectory,(Join-Path $plan.program 'scripts'),[StringComparison]::OrdinalIgnoreCase)){throw 'HOST_ACTIVATION_REPLACEMENT_ACTION_CHANGED'}
    foreach($key in @('sd5','sd7','rootSd5','rootSd7')){if($v.hashes[$key] -cne $checkpoint.task.$key){throw 'HOST_ACTIVATION_SECURITY_CHANGED'}}
    return $v
}
function Activation-RestoreDefinition($Service,$Folder,[string]$Name,$Saved,[string]$UserSid) {
    # The caller first binds the current Task and stops its exact owned tree.
    # This same restore operation is rehearsed on the disabled private fixture.
    $definition=$Service.NewTask(0);$definition.XmlText=$Saved.definitionXml
    return $Folder.RegisterTaskDefinition($Name,$definition,20,$UserSid,$null,3,$Saved.sd7)
}
function Activation-CheckInstallation($Value) {
    if($Value.format -cne 'windows-host-regular-installation-v1'){throw 'HOST_ACTIVATION_ROUTE_CHANGED'}
    foreach($key in @('hostId','operationId','runtimeSha256','sourceCommit')){if($Value.$key -cne $r.$key){throw 'HOST_ACTIVATION_ROUTE_CHANGED'}}
    foreach($key in @('program','configuration','state')){if(-not [string]::Equals($Value.$key,$plan.$key,[StringComparison]::OrdinalIgnoreCase)){throw 'HOST_ACTIVATION_ROUTE_CHANGED'}}
}
function Activation-BindInstallation {
    $path=Join-Path $serviceRoot 'current.json'
    if(-not (Test-Path -LiteralPath $path)){return $null}
    $read=[CodexManagePrivateStateSecurity]::OpenRecoveryRead($path,4096)
    try{$hash=$read.Sha256;$identity=$read.Identity;Activation-CheckInstallation (Activation-Json $read)}finally{$read.Dispose()}
    $retained=[CodexManagePrivateStateSecurity]::RetainRegularInstallation($hash,$identity)
    try{Activation-CheckInstallation (Activation-Json $retained);$retained.CheckArchiveTarget($r.operationId);return $retained}catch{$retained.Dispose();throw}
}
function Activation-RecordedStatus {
    # A reservation may outlive any preparation phase. Query its evidence
    # without requiring the completion seal or replaying the failed phase.
    $recordNames=@('task.snapshot.json','startup-task-intent.json','rollback-rehearsal.json','plan.json','prepared.json','retirement-authorized.json','legacy-preserved.json','task-write-attempt.json','active.json','rolled-back.json')
    $records=@{}
    foreach($name in $recordNames){$path=Join-Path $operation $name;if(Test-Path -LiteralPath $path){$lease=Activation-Read $path;$records[$name]=$lease.Sha256}}
    $status=if($records.ContainsKey('rolled-back.json')){'rolled-back'}elseif($records.ContainsKey('active.json')){'recorded-active'}elseif($records.ContainsKey('task-write-attempt.json') -or $records.ContainsKey('retirement-authorized.json') -or $records.ContainsKey('legacy-preserved.json')){'unconfirmed'}elseif($records.ContainsKey('prepared.json')){'prepared'}else{'incomplete'}
    return @{format=$r.format;operationId=$r.operationId;hostId=$r.hostId;status=$status;observationOnly=$true;runtimeSha256=$r.runtimeSha256;records=$records;taskOrStateWritesAttempted=$false;compilationScratchMayBeUsed=$true;liveHostHealthUnchecked=$true;replayAllowed=$false}
}
function Activation-SealPreparation {
    $null=Activation-OriginalTask
    $taskCopy=Activation-Read (Join-Path $operation 'task.snapshot.json');$boot=Activation-Witness
    if($boot.bootUtc -cne $checkpoint.boot){throw 'HOST_ACTIVATION_BOOT_CHANGED_DURING_PREPARATION'}
    $plan=@{format=$r.format;operationId=$r.operationId;hostId=$r.hostId;legacyOperationId=$r.legacyOperationId;preparationId=$r.preparationId;runtimeSha256=$r.runtimeSha256;sourceCommit=$r.sourceCommit;controllerSha256=$r.controllerSha256;failedDomainId=$r.failedDomainId;failedDomainSha256=$r.failedDomainSha256;checkpointSha256=$r.checkpointSha256;program=$program;node=$node;worker=$worker;launcher=$resources.'host-launcher'.path;configuration=$configuration;state=$origin.state;port=$origin.configuration.environment.HOST_AGENT_PORT;arguments=$arguments;files=$origin.files;resources=$resources;taskShape=(Activation-Shape $before.raw.definitionXml $before.raw.sd7);taskSnapshotSha256=$taskCopy.Sha256;boot=$boot;startupProof=@{sha256=$reportLease.Sha256;nonce=$probe.nonce;passed=$true;actualContext=$report.contextBeforeConfiguration};rollbackRehearsalPassed=$true;historicalTestPassed=$false;formalTaskMutated=$false;restartAuthorized=$false}
    $planPath=Join-Path $operation 'plan.json';Activation-Create $planPath $plan;$planLease=Activation-Read $planPath
    Activation-Create (Join-Path $operation 'prepared.json') @{operationId=$r.operationId;planSha256=$planLease.Sha256}
    return @{format=$r.format;operationId=$r.operationId;hostId=$r.hostId;status='prepared';planSha256=$planLease.Sha256;runtimeSha256=$r.runtimeSha256;controllerSha256=$r.controllerSha256;startupProofPassed=$true;pendingDispositionLeaseVerified=$true;formalTaskMutated=$false;hostStarted=$false;pendingArchived=$false;restartAuthorized=$false;next='save work and obtain explicit authorization for a complete Windows Restart before activate'}
}
function Activation-Rollback {
    $route=Activation-BindInstallation
    try {
    $enabled=[bool]$root.GetTask('CodexManage Host Agent').Enabled;$null=Activation-DesiredTask $enabled
    Activation-Create (Join-Path $operation 'rollback-attempt.json') @{operationId=$r.operationId;runtimeSha256=$r.runtimeSha256;originalTaskSnapshotSha256=$plan.taskSnapshotSha256}
    $roots=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -Compress -InputObject @((Join-Path $hub 'windows-native-v1\native-helpers')))))
    $out=& (Join-Path ([Environment]::SystemDirectory) 'WindowsPowerShell\v1.0\powershell.exe') -NoProfile -NonInteractive -File (Join-Path $plan.program 'scripts\windows-host-task.ps1') -Action stop -NodePath $plan.node -LauncherPath $plan.launcher -LauncherRootsBase64 $roots -WorkerPath $plan.worker -ConfigPath $plan.configuration -CommandBudgetMs 45000
    if($LASTEXITCODE -ne 0){throw 'HOST_ACTIVATION_ROLLBACK_STOP_UNCONFIRMED'}
    $null=Activation-DesiredTask $false
    $snapshotLease=Activation-Read (Join-Path $operation 'task.snapshot.json')
    if($snapshotLease.Sha256 -cne $plan.taskSnapshotSha256){throw 'HOST_ACTIVATION_ROLLBACK_CAPSULE_CHANGED'}
    $saved=Activation-Json $snapshotLease
    $null=Activation-RestoreDefinition $scheduler $root 'CodexManage Host Agent' $saved $sid
    $v=Activation-Task
    if((Activation-Shape $v.raw.definitionXml $v.raw.sd7) -cne $plan.taskShape -or $v.raw.sd7 -cne $saved.sd7 -or $v.task.GetInstances(0).Count -ne 0){throw 'HOST_ACTIVATION_ROLLBACK_TASK_UNCONFIRMED'}
    $old=$scheduler.NewTask(0);$old.XmlText=$saved.definitionXml
    if($v.task.Definition.Actions.Item(1).Path -cne $old.Actions.Item(1).Path -or $v.task.Definition.Actions.Item(1).Arguments -cne $old.Actions.Item(1).Arguments -or $v.task.Enabled -ne $old.Settings.Enabled){throw 'HOST_ACTIVATION_ROLLBACK_ACTION_UNCONFIRMED'}
    $routeArchived=$false
    if($null -ne $route){$null=$route.Archive($r.operationId);$routeArchived=$true}
    Activation-Create (Join-Path $operation 'rolled-back.json') @{format=$r.format;operationId=$r.operationId;hostId=$r.hostId;status='rolled-back';originalTaskRestored=$true;rawTaskBytesRestored=($v.raw.xml -ceq $saved.xml);originalStatePreserved=$true;pendingArchived=(Test-Path -LiteralPath (Join-Path $operation 'retirement-committed.json'));legacyTestPreserved=(Test-Path -LiteralPath (Join-Path $operation 'legacy-preserved.json'));installationRouteArchived=$routeArchived;historicalTestPassed=$false}
    } finally {if($null -ne $route){$route.Dispose()}}
}
try {
    foreach($module in @('Microsoft.PowerShell.Management','Microsoft.PowerShell.Security','Microsoft.PowerShell.Utility')){Import-Module (Join-Path $PSHOME ('Modules\'+$module)) -ErrorAction Stop}
    $text=[Console]::In.ReadToEnd();if($text.Length -gt 16384){throw 'HOST_ACTIVATION_REQUEST_TOO_LARGE'};$r=ConvertFrom-Json $text
    if($r.format -cne 'windows-host-regular-activation-v1' -or $r.action -cne $Action){throw 'HOST_ACTIVATION_REQUEST_REJECTED'}
    foreach($key in @('hostId','operationId','preparationId','legacyOperationId','failedDomainId')){if($r.$key -cnotmatch '^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$'){throw 'HOST_ACTIVATION_ID_REJECTED'}}
    foreach($key in @('checkpointSha256','runtimeSha256','failedDomainSha256')){if($r.$key -cnotmatch '^[a-f0-9]{64}$'){throw 'HOST_ACTIVATION_HASH_REJECTED'}}
    $hub=Join-Path ([Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)) '.config\agent-hub';$serviceRoot=Join-Path $hub 'windows-host-v1'
    if(Test-Path -LiteralPath (Join-Path $r.installedProgram '.local\desktop-runtime\task-recovery\pending.json')){throw 'HOST_ACTIVATION_DISTINCT_RECOVERY_PENDING'}
    $source=Join-Path $r.controllerRoot 'packages\codex-adapter\windows';. (Join-Path $source 'private-bootstrap.ps1');. (Join-Path $r.controllerRoot 'scripts\windows-host-regular-domain-common.ps1')
    $scratch=Open-HostBootstrapScratch $source;if($null -eq $scratch){throw 'HOST_ACTIVATION_PROTECTED_STORAGE_REQUIRED'}
    foreach($name in @('private-state.cs','private-state-compilation.cs','private-state-recovery.cs','private-state-activation.cs')){$stream=[IO.FileStream]::new((Join-Path $source $name),[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read);$sourceLeases+=,$stream;$h=[Security.Cryptography.SHA256]::Create();try{$hash=([BitConverter]::ToString($h.ComputeHash($stream))).Replace('-','').ToLowerInvariant()}finally{$h.Dispose()};if($hash -cne $r.sourceHashes.$name){throw 'HOST_ACTIVATION_NATIVE_SOURCE_CHANGED'};$stream.Position=0}
    Add-Type -Path @('private-state.cs','private-state-compilation.cs','private-state-recovery.cs','private-state-activation.cs'|ForEach-Object{Join-Path $source $_}) -ReferencedAssemblies @('System.dll','System.Core.dll','System.Xml.dll','System.Runtime.Serialization.dll')
    $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $preparation=Join-Path (Join-Path $hub 'host-regular-recovery') $r.preparationId;$operation=Join-Path $preparation ('activation-'+$r.operationId)
    $checkpointLease=Activation-Read (Join-Path $preparation 'checkpoint.json')
    if($checkpointLease.Sha256 -cne $r.checkpointSha256){throw 'HOST_ACTIVATION_CHECKPOINT_CHANGED'};$checkpoint=Activation-Json $checkpointLease
    $p1Seal=Activation-Json (Activation-Read (Join-Path $preparation 'prepared.json'));$p1Reservation=Activation-Json (Activation-Read (Join-Path (Split-Path -Parent $preparation) 'reservation.json'))
    if($checkpoint.operationId -cne $r.preparationId -or $p1Seal.operationId -cne $r.preparationId -or $p1Seal.checkpointSha256 -cne $r.checkpointSha256 -or $p1Reservation.operationId -cne $r.preparationId -or $p1Reservation.hostId -cne $r.hostId -or $checkpoint.pendingReleased -ne $false -or $checkpoint.formalTaskMutated -ne $false){throw 'HOST_ACTIVATION_PREPARATION_SEAL_CHANGED'}
    foreach($key in @('hostId','legacyOperationId')){if($checkpoint.$key -cne $r.$key){throw 'HOST_ACTIVATION_CHECKPOINT_TARGET_CHANGED'}}
    $closed=Activation-Read (Join-Path (Join-Path $preparation ('domain-'+$r.failedDomainId)) 'failed-closed.json')
    $closedValue=Activation-Json $closed
    if($closed.Sha256 -cne $r.failedDomainSha256 -or $closedValue.operationId -cne $r.failedDomainId -or $closedValue.preChildFailureClosed -ne $true -or $closedValue.fixturesRemoved -ne $true){throw 'HOST_ACTIVATION_FAILED_DOMAIN_UNRESOLVED'}
    $scheduler=New-Object -ComObject Schedule.Service;$scheduler.Connect();$root=$scheduler.GetFolder('\')
    foreach($prefix in @('CodexManage Recovery Test ','CodexManage Recovery Guard ')){if(-not (Activation-AbsentFolder ('\'+$prefix+$r.legacyOperationId))){throw 'HOST_ACTIVATION_LEGACY_FIXTURE_PRESENT'}}
    foreach($prefix in @('CodexManage Regular Verify ','CodexManage Regular Guard ')){if(-not (Activation-AbsentFolder ('\'+$prefix+$r.failedDomainId))){throw 'HOST_ACTIVATION_FAILED_FIXTURE_PRESENT'}}
    $phase='load-activation-plan';$reservationPath=Join-Path $preparation 'activation-reservation.json'
    if($Action -eq 'prepare') {
        if((Test-Path -LiteralPath $reservationPath) -or (Test-Path -LiteralPath $operation) -or (Test-Path -LiteralPath (Join-Path $serviceRoot 'current.json'))){throw 'HOST_ACTIVATION_ALREADY_RESERVED_QUERY_STATUS'}
        $before=Activation-OriginalTask;$origin=Activation-ReadOriginal
        Activation-LegacyFiles $false
        # Acquiring the exact retained marker handle is read-only. Prove the
        # identity/ACL and eventual rename capability before asking for Restart;
        # Archive is not called in preparation and the original is untouched.
        $phase='verify-retirement-marker-and-target';$marker=[CodexManagePrivateStateSecurity]::RetainLegacyPending($checkpoint.files.pending.sha256,$checkpoint.files.pending.identity)
        try{if((Activation-Json $marker).operationId -cne $r.legacyOperationId){throw 'HOST_ACTIVATION_LEGACY_OPERATION_CHANGED'};$marker.CheckArchiveTarget($r.legacyOperationId)}finally{$marker.Dispose()}
        $port=$origin.configuration.environment.HOST_AGENT_PORT
        if($port -cnotmatch '^[0-9]{1,5}$' -or [int]$port -lt 1 -or [int]$port -gt 65535){throw 'HOST_ACTIVATION_PORT_REJECTED'}
        if(@([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()|Where-Object{$_.Port -eq [int]$port}).Count -ne 0){throw 'HOST_ACTIVATION_EXISTING_LISTENER'}
        Activation-Create $reservationPath @{format=$r.format;operationId=$r.operationId;hostId=$r.hostId;runtimeSha256=$r.runtimeSha256;checkpointSha256=$r.checkpointSha256}
        New-HostBootstrapDirectory $operation
        $phase='deploy-frozen-runtime';$program=Activation-Deploy
        $phase='prepare-fixed-helpers';$resources=@{}
        foreach($name in @('private-state','host-launcher')){$phase='prepare-fixed-helper-'+$name;$directory=Join-Path (Join-Path $hub 'windows-native-v1\native-helpers') $r.helperDigests.$name;$null=[CodexManagePrivateStateSecurity]::PrepareNative($name,(Join-Path $program 'packages\codex-adapter\windows'),$directory,30000);$path=Join-Path $directory ($name+'.exe');$l=Activation-Read $path;$resources[$name]=@{path=$path;sha256=$l.Sha256;identity=$l.Identity}}
        $phase='create-private-instance-configuration';New-HostBootstrapDirectory (Join-Path $serviceRoot 'instances');$instance=Join-Path (Join-Path $serviceRoot 'instances') $r.hostId
        if(Test-Path -LiteralPath $instance){throw 'HOST_ACTIVATION_INSTANCE_ALREADY_EXISTS'};New-HostBootstrapDirectory $instance
        $config=$origin.configuration
        foreach($pair in @(@('CODEX_MANAGE_WINDOWS_NATIVE_STORAGE','profile-v1'),@('CODEX_DESKTOP_SUGGESTION_OBSERVER','0'),@('CODEX_DESKTOP_TRACE_RETENTION','0'))){$config.environment|Add-Member -NotePropertyName $pair[0] -NotePropertyValue $pair[1] -Force}
        $config.environment.PSObject.Properties.Remove('CODEX_DESKTOP_NATIVE_SUGGESTIONS_CONFIG')
        $configuration=Join-Path $instance 'windows-host.json';Activation-Create $configuration $config
        $node=Join-Path $program 'bin\node.exe';$worker=Join-Path $program 'scripts\windows-desktop-worker.mjs';$arguments='"'+$node+'" "'+$worker+'" "'+$configuration+'"'
        Activation-Create (Join-Path $operation 'task.snapshot.json') $before.raw
        $phase='prepare-readonly-startup-proof'
        $probe=Activation-Node $node (Join-Path $program 'scripts\windows-host-regular-activation.mjs') @('--prepare-startup',$configuration,$operation)
        $fixtureName='CodexManage Regular Startup '+$r.operationId
        if(-not (Activation-AbsentFolder ('\'+$fixtureName))){throw 'HOST_ACTIVATION_STARTUP_FIXTURE_EXISTS'}
        $fixtureArguments='"'+$node+'" "'+$worker+'" "'+$probe.configuration+'"';$fixtureImage=$resources.'host-launcher'.path;$fixtureDirectory=Join-Path $program 'scripts'
        Activation-Create (Join-Path $operation 'startup-task-intent.json') @{operationId=$r.operationId;folder=$fixtureName;argumentsSha256=(Activation-Hash ([Text.Encoding]::UTF8.GetBytes($fixtureArguments)));nonce=$probe.nonce;runtimeSha256=$r.runtimeSha256}
        $fixture=$root.CreateFolder($fixtureName,(Regular-DomainSddl $sid $false));$fixtureCreated=$true
        $definition=Regular-NewFixture $scheduler $sid 'Runner' $false;$definition.Actions.Item(1).Path=$fixtureImage;$definition.Actions.Item(1).Arguments=$fixtureArguments;$definition.Actions.Item(1).WorkingDirectory=$fixtureDirectory
        $testTask=$fixture.RegisterTaskDefinition('Runner',$definition,18,$sid,$null,3,(Regular-DomainSddl $sid $false));$attemptTime=[DateTime]::UtcNow.AddSeconds(-1);$testTask.Enabled=$true;[void]$testTask.Run($null)
        $clock=[Diagnostics.Stopwatch]::StartNew();while($clock.ElapsedMilliseconds -lt 90000){if($testTask.GetInstances(0).Count -eq 0 -and $testTask.LastRunTime.ToUniversalTime() -ge $attemptTime){break};Start-Sleep -Milliseconds 100}
        if($testTask.GetInstances(0).Count -ne 0 -or $testTask.LastTaskResult -ne 0){throw 'HOST_ACTIVATION_STARTUP_PROOF_FAILED'}
        $reportLease=Activation-Read $probe.report 16384;$report=Activation-Json $reportLease
        if($report.passed -ne $true -or $report.nonce -cne $probe.nonce -or $report.backendClosed -ne $true -or $report.hostStarted -ne $false -or $report.portsOpened -ne $false){throw 'HOST_ACTIVATION_STARTUP_REPORT_REJECTED'}
        $null=Regular-StopOwnedRunner $root $fixtureName $sid $fixtureImage $fixtureArguments $fixtureDirectory $false
        $phase='rehearse-disabled-task-rollback'
        $testTask=Regular-GetOwnedRunner $root $fixtureName $sid $fixtureImage $fixtureArguments $fixtureDirectory
        if($testTask.Enabled -or $testTask.GetInstances(0).Count -ne 0){throw 'HOST_ACTIVATION_REHEARSAL_TASK_ACTIVE'}
        $restoreSnapshot=@{definitionXml=[string]$testTask.Definition.XmlText;sd7=[string]$testTask.GetSecurityDescriptor(7);xml=[string]$testTask.Xml}
        Activation-Create (Join-Path $operation 'rollback-rehearsal-intent.json') @{operationId=$r.operationId;folder=$fixtureName;definitionSha256=(Activation-Hash ([Text.Encoding]::UTF8.GetBytes($restoreSnapshot.definitionXml)));sdSha256=(Activation-Hash ([Text.Encoding]::UTF8.GetBytes($restoreSnapshot.sd7)));taskAlwaysDisabled=$true}
        $changed=$scheduler.NewTask(0);$changed.XmlText=$before.raw.definitionXml
        $changed.RegistrationInfo.URI=[string]$testTask.Definition.RegistrationInfo.URI
        $changed.Settings.Enabled=$false;$changed.Actions.Item(1).Path=$fixtureImage;$changed.Actions.Item(1).Arguments=$fixtureArguments;$changed.Actions.Item(1).WorkingDirectory=$fixtureDirectory
        $null=$fixture.RegisterTaskDefinition('Runner',$changed,20,$sid,$null,3,$restoreSnapshot.sd7)
        $current=$fixture.GetTask('Runner')
        if($current.Enabled -or $current.GetInstances(0).Count -ne 0 -or $current.Definition.Actions.Item(1).Arguments -cne $fixtureArguments){throw 'HOST_ACTIVATION_REHEARSAL_STATE_CHANGED'}
        $null=Activation-RestoreDefinition $scheduler $fixture 'Runner' $restoreSnapshot $sid
        $restored=$fixture.GetTask('Runner')
        if($restored.Enabled -or $restored.GetInstances(0).Count -ne 0 -or (Activation-Definition $restored.Definition.XmlText $restored.GetSecurityDescriptor(7)) -cne (Activation-Definition $restoreSnapshot.definitionXml $restoreSnapshot.sd7) -or [string]$restored.GetSecurityDescriptor(7) -cne $restoreSnapshot.sd7){throw 'HOST_ACTIVATION_REHEARSAL_RESTORE_FAILED'}
        Activation-Create (Join-Path $operation 'rollback-rehearsal.json') @{operationId=$r.operationId;definitionAndSecurityRestored=$true;rawTaskBytesRestored=([string]$restored.Xml -ceq $restoreSnapshot.xml);formalTaskMutated=$false;fixtureAlwaysDisabled=$true}
        Regular-DeleteOwnedFixture $root $fixtureName $sid 'Runner' $false $fixtureImage $fixtureArguments $fixtureDirectory $false
        Regular-DeleteEmptyFixtureFolder $root $fixtureName (Regular-DomainSddl $sid $false);$fixtureCreated=$false
        if(-not (Activation-AbsentFolder ('\'+$fixtureName))){throw 'HOST_ACTIVATION_STARTUP_CLEANUP_UNCONFIRMED'}
        $result=Activation-SealPreparation
    } elseif($Action -eq 'continue-prepare') {
        # Only finish the observed descriptor-normalization false negative.
        # No Task Run, Task update, helper compilation or new reservation occurs.
        $phase='reconcile-disabled-rehearsal'
        $reservation=Activation-Json (Activation-Read $reservationPath)
        foreach($key in @('operationId','hostId','runtimeSha256','checkpointSha256')){if($reservation.$key -cne $r.$key){throw 'HOST_ACTIVATION_RESERVATION_CHANGED'}}
        foreach($name in @('continue-intent.json','rollback-rehearsal.json','prepared.json','plan.json','retirement-authorized.json','task-write-attempt.json')){if(Test-Path -LiteralPath (Join-Path $operation $name)){throw 'HOST_ACTIVATION_CONTINUATION_ALREADY_ATTEMPTED'}}
        if(Test-Path -LiteralPath (Join-Path $serviceRoot 'current.json')){throw 'HOST_ACTIVATION_ALREADY_ACTIVE'}
        $before=Activation-OriginalTask;$origin=Activation-ReadOriginal;Activation-LegacyFiles $false
        $saved=Activation-Json (Activation-Read (Join-Path $operation 'task.snapshot.json'))
        foreach($key in $before.raw.Keys){if((Activation-Hash ([Text.Encoding]::UTF8.GetBytes($saved.$key))) -cne $before.hashes[$key]){throw 'HOST_ACTIVATION_ROLLBACK_CAPSULE_CHANGED'}}
        $marker=[CodexManagePrivateStateSecurity]::RetainLegacyPending($checkpoint.files.pending.sha256,$checkpoint.files.pending.identity)
        try{$marker.CheckArchiveTarget($r.legacyOperationId)}finally{$marker.Dispose()}
        $program=Join-Path (Join-Path $serviceRoot 'programs') $r.runtimeSha256
        if(-not [string]::Equals($program.TrimEnd('\'),$r.sourceRoot.TrimEnd('\'),[StringComparison]::OrdinalIgnoreCase)){throw 'HOST_ACTIVATION_CONTINUATION_RUNTIME_CHANGED'}
        $node=Join-Path $program 'bin\node.exe';$worker=Join-Path $program 'scripts\windows-desktop-worker.mjs'
        $configuration=Join-Path (Join-Path (Join-Path $serviceRoot 'instances') $r.hostId) 'windows-host.json'
        $config=$origin.configuration
        foreach($pair in @(@('CODEX_MANAGE_WINDOWS_NATIVE_STORAGE','profile-v1'),@('CODEX_DESKTOP_SUGGESTION_OBSERVER','0'),@('CODEX_DESKTOP_TRACE_RETENTION','0'))){$config.environment|Add-Member -NotePropertyName $pair[0] -NotePropertyValue $pair[1] -Force}
        $config.environment.PSObject.Properties.Remove('CODEX_DESKTOP_NATIVE_SUGGESTIONS_CONFIG')
        $configurationLease=Activation-Read $configuration
        if($configurationLease.Sha256 -cne (Activation-Hash (Activation-Bytes $config))){throw 'HOST_ACTIVATION_CONTINUATION_CONFIG_CHANGED'}
        $resources=@{}
        foreach($name in @('private-state','host-launcher')){$path=Join-Path (Join-Path (Join-Path $hub 'windows-native-v1\native-helpers') $r.helperDigests.$name) ($name+'.exe');$l=Activation-Read $path;$resources[$name]=@{path=$path;sha256=$l.Sha256;identity=$l.Identity}}
        $arguments='"'+$node+'" "'+$worker+'" "'+$configuration+'"'
        $startup=Activation-Json (Activation-Read (Join-Path $operation 'startup-task-intent.json'))
        $rehearsal=Activation-Json (Activation-Read (Join-Path $operation 'rollback-rehearsal-intent.json'))
        $fixtureName='CodexManage Regular Startup '+$r.operationId
        if($startup.operationId -cne $r.operationId -or $startup.runtimeSha256 -cne $r.runtimeSha256 -or $startup.folder -cne $fixtureName -or $rehearsal.operationId -cne $r.operationId -or $rehearsal.folder -cne $fixtureName -or $rehearsal.taskAlwaysDisabled -ne $true){throw 'HOST_ACTIVATION_CONTINUATION_SCOPE_CHANGED'}
        $probe=@{configuration=(Join-Path $operation 'startup-envelope\windows-host.json');report=(Join-Path $operation 'startup-envelope\startup-preflight-report.json');nonce=$startup.nonce}
        $fixtureArguments='"'+$node+'" "'+$worker+'" "'+$probe.configuration+'"';$fixtureImage=$resources.'host-launcher'.path;$fixtureDirectory=Join-Path $program 'scripts'
        if((Activation-Hash ([Text.Encoding]::UTF8.GetBytes($fixtureArguments))) -cne $startup.argumentsSha256){throw 'HOST_ACTIVATION_CONTINUATION_ARGUMENTS_CHANGED'}
        $testTask=Regular-GetOwnedRunner $root $fixtureName $sid $fixtureImage $fixtureArguments $fixtureDirectory
        $restoredXml=[string]$testTask.Definition.XmlText;$restoredSd=[string]$testTask.GetSecurityDescriptor(7)
        $null=Activation-Definition $restoredXml $restoredSd
        $withoutEmbedded=[regex]::Replace($restoredXml,'(?m)^[ \t]*<SecurityDescriptor>[^\r\n]*</SecurityDescriptor>\r?\n','')
        if($testTask.Enabled -or $testTask.GetInstances(0).Count -ne 0 -or (Activation-Hash ([Text.Encoding]::UTF8.GetBytes($withoutEmbedded))) -cne $rehearsal.definitionSha256 -or (Activation-Hash ([Text.Encoding]::UTF8.GetBytes($restoredSd))) -cne $rehearsal.sdSha256){throw 'HOST_ACTIVATION_CONTINUATION_RESTORATION_CHANGED'}
        $reportLease=Activation-Read $probe.report 16384;$report=Activation-Json $reportLease
        if($report.passed -ne $true -or $report.nonce -cne $probe.nonce -or $report.backendClosed -ne $true -or $report.hostStarted -ne $false -or $report.portsOpened -ne $false){throw 'HOST_ACTIVATION_STARTUP_REPORT_REJECTED'}
        $prior=Get-Process -Id $report.contextBeforeConfiguration.pid -ErrorAction SilentlyContinue
        if($null -ne $prior -and $prior.StartTime.ToUniversalTime().ToFileTimeUtc().ToString() -ceq $report.contextBeforeConfiguration.startedFileTime){throw 'HOST_ACTIVATION_STARTUP_PROCESS_STILL_ACTIVE'}
        Activation-Create (Join-Path $operation 'continue-intent.json') @{operationId=$r.operationId;controllerSha256=$r.controllerSha256;runtimeSha256=$r.runtimeSha256;startupProofSha256=$reportLease.Sha256;onlyEmbeddedDescriptorAdded=$true;taskRunReplayed=$false}
        Activation-Create (Join-Path $operation 'rollback-rehearsal.json') @{operationId=$r.operationId;definitionAndSecurityRestored=$true;rawTaskBytesRestored=$false;onlyEmbeddedDescriptorAdded=$true;formalTaskMutated=$false;fixtureAlwaysDisabled=$true;reconciledWithoutReplay=$true}
        Regular-DeleteOwnedFixture $root $fixtureName $sid 'Runner' $false $fixtureImage $fixtureArguments $fixtureDirectory $false
        Regular-DeleteEmptyFixtureFolder $root $fixtureName (Regular-DomainSddl $sid $false)
        if(-not (Activation-AbsentFolder ('\'+$fixtureName))){throw 'HOST_ACTIVATION_STARTUP_CLEANUP_UNCONFIRMED'}
        $result=Activation-SealPreparation
    } else {
        $reservation=Activation-Json (Activation-Read $reservationPath)
        foreach($key in @('operationId','hostId','runtimeSha256','checkpointSha256')){if($reservation.$key -cne $r.$key){throw 'HOST_ACTIVATION_RESERVATION_CHANGED'}}
        if($Action -eq 'status') {
            $result=Activation-RecordedStatus
        } else {
        $seal=Activation-Json (Activation-Read (Join-Path $operation 'prepared.json'));$planLease=Activation-Read (Join-Path $operation 'plan.json');$plan=Activation-Json $planLease
        if($seal.operationId -cne $r.operationId -or $seal.planSha256 -cne $planLease.Sha256){throw 'HOST_ACTIVATION_PLAN_SEAL_CHANGED'}
        foreach($key in @('operationId','hostId','legacyOperationId','preparationId','runtimeSha256','sourceCommit','failedDomainId','failedDomainSha256','checkpointSha256')){if($plan.$key -cne $r.$key){throw 'HOST_ACTIVATION_PLAN_SCOPE_CHANGED'}}
        if($Action -eq 'rollback') {
            Activation-Rollback;$result=Activation-Json (Activation-Read (Join-Path $operation 'rolled-back.json'))
        } else {
            $phase='qualify-activation-scope'
            if($r.restartConfirmed -eq $true -and $r.preserveLegacyTest -eq $true){throw 'HOST_ACTIVATION_CONFIRMATION_REJECTED'}
            if((Test-Path -LiteralPath (Join-Path $operation 'retirement-authorized.json')) -or (Test-Path -LiteralPath (Join-Path $operation 'legacy-preserved.json')) -or (Test-Path -LiteralPath (Join-Path $operation 'task-write-attempt.json')) -or (Test-Path -LiteralPath (Join-Path $serviceRoot 'current.json'))){throw 'HOST_ACTIVATION_ATTEMPT_EXISTS_QUERY_STATUS'}
            if($r.preserveLegacyTest -eq $true){
                $scope=Activation-PreservedScope
            }else{
                if($r.restartConfirmed -ne $true -or $Action -eq 'qualify-no-restart'){throw 'HOST_RESTART_CONFIRMATION_REQUIRED'}
                $afterBoot=Activation-Witness
                $qualified=Activation-Node (Join-Path $r.sourceRoot 'bin\node.exe') (Join-Path $r.sourceRoot 'scripts\windows-host-regular-activation.mjs') @('--qualify-restart') ([Text.Encoding]::UTF8.GetString((Activation-Bytes @{before=$plan.boot;after=$afterBoot;restartConfirmed=$r.restartConfirmed})))
                if($qualified.qualifiedOldBootRetirement -ne $true -or $qualified.historicalTestPassed -ne $false){throw 'HOST_NEW_BOOT_NOT_PROVED'}
            }
            $before=Activation-OriginalTask;$origin=Activation-ReadOriginal;Activation-LegacyFiles $false
            foreach($name in $origin.files.Keys){if($origin.files[$name].sha256 -cne $plan.files.$name.sha256 -or $origin.files[$name].identity -cne $plan.files.$name.identity){throw 'HOST_ACTIVATION_ORIGINAL_STATE_CHANGED'}}
            foreach($name in @('private-state','host-launcher')){$l=Activation-Read $plan.resources.$name.path;if($l.Sha256 -cne $plan.resources.$name.sha256 -or $l.Identity -cne $plan.resources.$name.identity){throw 'HOST_ACTIVATION_HELPER_CHANGED'}}
            $port=$origin.configuration.environment.HOST_AGENT_PORT
            if(@([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()|Where-Object{$_.Port -eq [int]$port}).Count -ne 0){throw 'HOST_ACTIVATION_EXISTING_LISTENER'}
            $config=$origin.configuration
            foreach($pair in @(@('CODEX_MANAGE_WINDOWS_NATIVE_STORAGE','profile-v1'),@('CODEX_DESKTOP_SUGGESTION_OBSERVER','0'),@('CODEX_DESKTOP_TRACE_RETENTION','0'))){$config.environment|Add-Member -NotePropertyName $pair[0] -NotePropertyValue $pair[1] -Force}
            $config.environment.PSObject.Properties.Remove('CODEX_DESKTOP_NATIVE_SUGGESTIONS_CONFIG')
            $configurationLease=Activation-Read $plan.configuration
            if($configurationLease.Sha256 -cne (Activation-Hash (Activation-Bytes $config))){throw 'HOST_ACTIVATION_INSTANCE_CONFIG_CHANGED'}
            if($Action -eq 'qualify-no-restart'){
                $result=@{format=$r.format;operationId=$r.operationId;hostId=$r.hostId;status='qualified';observationOnly=$true;formalTaskMutated=$false;hostStarted=$false;pendingArchived=$false;legacyTestPreserved=$true;historicalTestPassed=$false;historicalSubtreeVerified=$false;scope=$scope;compilationScratchMayBeUsed=$true}
            }else{
            if($r.preserveLegacyTest -eq $true){
                $phase='preserve-isolated-legacy-test';$retirementIntent=$true
                Activation-Create (Join-Path $operation 'legacy-preserved.json') @{operationId=$r.operationId;hostId=$r.hostId;legacyOperationId=$r.legacyOperationId;controllerSha256=$r.controllerSha256;scope=$scope;pendingIdentity=$checkpoint.files.pending.identity;pendingSha256=$checkpoint.files.pending.sha256;pendingArchived=$false;historicalTestPassed=$false;historicalSubtreeVerified=$false}
            }else{
            $phase='qualified-identity-bound-retirement'
            $pending=[CodexManagePrivateStateSecurity]::RetainLegacyPending($checkpoint.files.pending.sha256,$checkpoint.files.pending.identity)
            try{if((Activation-Json $pending).operationId -cne $r.legacyOperationId){throw 'HOST_ACTIVATION_LEGACY_OPERATION_CHANGED'};$retirementIntent=$true;Activation-Create (Join-Path $operation 'retirement-authorized.json') @{operationId=$r.operationId;hostId=$r.hostId;legacyOperationId=$r.legacyOperationId;pendingIdentity=$pending.Identity;pendingSha256=$pending.Sha256;beforeBoot=$plan.boot;afterBoot=$afterBoot;historicalTestPassed=$false;historicalSubtreeVerified=$false};$null=$pending.Archive($r.legacyOperationId);$pendingArchived=$true;Activation-Create (Join-Path $operation 'retirement-committed.json') @{operationId=$r.operationId;hostId=$r.hostId;legacyOperationId=$r.legacyOperationId;pendingIdentity=$pending.Identity;pendingSha256=$pending.Sha256;archivePath=$pending.ArchivePath;qualifiedOldBootRetirement=$true;historicalTestPassed=$false}}finally{$pending.Dispose()}
            }
            $phase='replace-formal-task-disabled';$null=Activation-OriginalTask
            $definition=$scheduler.NewTask(0);$definition.XmlText=$before.raw.definitionXml
            if($definition.Actions.Count -ne 1 -or $definition.Principal.LogonType -ne 3 -or $definition.Principal.RunLevel -ne 0 -or (Regular-PrincipalSid $definition.Principal.UserId) -cne $sid -or $definition.Settings.MultipleInstances -ne 2){throw 'HOST_ACTIVATION_ORIGINAL_TASK_CONTRACT_REJECTED'}
            $definition.Actions.Item(1).Path=$plan.launcher;$definition.Actions.Item(1).Arguments=$plan.arguments;$definition.Actions.Item(1).WorkingDirectory=Join-Path $plan.program 'scripts';$definition.Settings.Enabled=$false
            Activation-Create (Join-Path $operation 'task-write-attempt.json') @{operationId=$r.operationId;runtimeSha256=$r.runtimeSha256;argumentsSha256=(Activation-Hash ([Text.Encoding]::UTF8.GetBytes($plan.arguments)));taskShape=$plan.taskShape}
            $formalWrite=$true;$null=$root.RegisterTaskDefinition('CodexManage Host Agent',$definition,20,$sid,$null,3,$before.raw.sd7)
            $v=Activation-DesiredTask $false;Activation-Create (Join-Path $operation 'task-written.json') @{operationId=$r.operationId;hashes=$v.hashes}
            Activation-Create (Join-Path $operation 'run-attempt.json') @{operationId=$r.operationId;runMayHaveOccurred=$true;runtimeSha256=$r.runtimeSha256}
            Activation-CloseOriginal
            # The worker may refresh its discovered Codex executable normally.
            # Release this configuration snapshot before starting that writer.
            $configurationLease.Dispose()
            $phase='start-owned-regular-host';$runAttempt=$true;$v.task.Enabled=$true
            if($v.task.GetInstances(0).Count -eq 0){[void]$v.task.Run($null)}
            $clock=[Diagnostics.Stopwatch]::StartNew();$workspace=$null
            $tokenLease=[CodexManagePrivateStateSecurity]::OpenRecoveryRead((Join-Path $plan.state 'access-token'),4096)
            try{
                if($tokenLease.Sha256 -cne $plan.files.token.sha256){throw 'HOST_ACTIVATION_TOKEN_CHANGED'}
                $token=[Text.Encoding]::UTF8.GetString($tokenLease.Bytes).Trim()
                while($clock.ElapsedMilliseconds -lt 60000){
                    try{$http=[Net.HttpWebRequest]::Create(('http://127.0.0.1:'+$plan.port+'/v1/workspace'));$http.Method='GET';$http.Proxy=$null;$http.AllowAutoRedirect=$false;$http.Timeout=2000;$http.Headers['Authorization']='Bearer '+$token;$response=$http.GetResponse();try{$reader=[IO.StreamReader]::new($response.GetResponseStream());try{$body=$reader.ReadToEnd();if($body.Length -gt 16777216){throw 'HOST_ACTIVATION_WORKSPACE_TOO_LARGE'};$workspace=ConvertFrom-Json $body}finally{$reader.Dispose()}}finally{$response.Dispose()};break}catch [Net.WebException]{Start-Sleep -Milliseconds 250}
                }
            }finally{$tokenLease.Dispose()}
            if($null -eq $workspace -or $workspace.hostId -cne $r.hostId -or $workspace.connectionMode -cne 'desktop-runtime'){throw 'HOST_ACTIVATION_IDENTITY_PROBE_FAILED'}
            $v=Activation-DesiredTask $true;if($v.task.GetInstances(0).Count -ne 1){throw 'HOST_ACTIVATION_TASK_INSTANCE_UNCONFIRMED'}
            Activation-Create (Join-Path $serviceRoot 'current.json') @{format='windows-host-regular-installation-v1';hostId=$r.hostId;operationId=$r.operationId;program=$plan.program;configuration=$plan.configuration;state=$plan.state;runtimeSha256=$r.runtimeSha256;sourceCommit=$r.sourceCommit}
            $result=@{format=$r.format;operationId=$r.operationId;hostId=$r.hostId;status='active';runtimeSha256=$r.runtimeSha256;loopbackAuthenticated=$true;connectionMode=$workspace.connectionMode;formalTaskMutated=$true;originalIdentityPreserved=$true;pendingArchived=$pendingArchived;legacyTestPreserved=$r.preserveLegacyTest;historicalTestPassed=$false;outsideConnectivityVerified=$false;loginAutostartTested=$false}
            Activation-Create (Join-Path $operation 'active.json') $result
            }
        }
        }
    }
} catch {
    $e=$_.Exception;while($null -ne $e.InnerException){$e=$e.InnerException};$code=$_.Exception.Message;if($code -cnotmatch '^HOST_[A-Z0-9_]+$'){$code='HOST_ACTIVATION_NATIVE_FAILURE'}
    $result=@{format='windows-host-regular-activation-v1';operationId=$(if($null -ne (Get-Variable r -ErrorAction SilentlyContinue)){$r.operationId}else{$null});hostId=$(if($null -ne (Get-Variable r -ErrorAction SilentlyContinue)){$r.hostId}else{$null});status='rejected';phase=$phase;code=$code;errorType=$e.GetType().FullName;hresult=('0x{0:X8}' -f ($e.HResult -band 0xffffffffL));line=$_.InvocationInfo.ScriptLineNumber;formalTaskMutationAttempted=$formalWrite;runAttempted=$runAttempt;pendingArchived=$pendingArchived;resultMayBeUnknown=($formalWrite -or $pendingArchived -or $retirementIntent)}
    if($e -is [ComponentModel.Win32Exception]){$result.win32=$e.NativeErrorCode}
    if($fixtureCreated){$result.fixtureStopAccepted=Regular-TryStopOwnedRunner $root $fixtureName $sid $fixtureImage $fixtureArguments $fixtureDirectory $true}
    if($formalWrite){try{Activation-CloseOriginal;Activation-Rollback;$result.rollbackConfirmed=$true}catch{$result.rollbackConfirmed=$false}}
} finally {
    Activation-CloseOriginal
    for($i=$leases.Count-1;$i -ge 0;$i--){$leases[$i].Dispose()};for($i=$sourceLeases.Count-1;$i -ge 0;$i--){$sourceLeases[$i].Dispose()}
    if($null -ne $scratch){Close-HostBootstrapScratch $scratch}
}
[Console]::Out.WriteLine((ConvertTo-Json -Depth 20 -Compress -InputObject $result))
if($result.status -notin @('prepared','active','rolled-back') -and -not ($Action -in @('status','qualify-no-restart') -and $result.ContainsKey('observationOnly') -and $result.observationOnly -eq $true)){exit 1}
