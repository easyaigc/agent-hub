# Fixed local read-only observation. No Add-Type, task setter/Run/Stop, directory
# creation, audit enablement, config write or old-operation cleanup is present.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
function Regular-Hash([byte[]]$Bytes) {
    $hash=[Security.Cryptography.SHA256]::Create()
    try{return ([BitConverter]::ToString($hash.ComputeHash($Bytes))).Replace('-','').ToLowerInvariant()}
    finally{$hash.Dispose()}
}
function Regular-Fault($Record) {
    $exception=$Record.Exception;$chain=@()
    for($depth=0;$null -ne $exception -and $depth -lt 6;$depth++) {
        $chain+=@{type=$exception.GetType().FullName;hresult=('0x{0:X8}' -f ($exception.HResult -band 0xffffffffL))}
        if($null -eq $exception.InnerException){break};$exception=$exception.InnerException
    }
    $command=$Record.InvocationInfo.MyCommand
    return @{chain=$chain;rootCode=($exception.HResult -band 0xffff);invocation=@{line=$Record.InvocationInfo.ScriptLineNumber;column=$Record.InvocationInfo.OffsetInLine;command=$(if($null -ne $command -and $command.Name.Length -le 80){$command.Name}else{$null})}}
}
function Regular-PathMetadata([string]$Path) {
    if(-not (Test-Path -LiteralPath $Path)){return @{exists=$false;privateLeaf=$false}}
    $item=Get-Item -LiteralPath $Path -Force
    $acl=Get-Acl -LiteralPath $Path
    $owner=$acl.GetOwner([Security.Principal.SecurityIdentifier]).Value
    $foreign=@();$effectiveForeign=0
    foreach($rule in $acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])) {
        if($rule.AccessControlType -ne 'Allow' -or $rule.IdentityReference.Value -in $script:regularAllowed){continue}
        $inheritOnly=(([int]$rule.PropagationFlags -band [int][Security.AccessControl.PropagationFlags]::InheritOnly) -ne 0)
        if(-not $inheritOnly -and [long]$rule.FileSystemRights -ne 0){$effectiveForeign++}
        if($foreign.Count -lt 4){$foreign+=@{sidSha256=(Regular-Hash ([Text.Encoding]::UTF8.GetBytes($rule.IdentityReference.Value)));mask=('0x{0:X8}' -f ([long]$rule.FileSystemRights -band 0xffffffffL));inheritOnly=$inheritOnly;inherited=$rule.IsInherited}}
    }
    $reparse=(([int]$item.Attributes -band [int][IO.FileAttributes]::ReparsePoint) -ne 0)
    return @{exists=$true;reparse=$reparse;directory=$item.PSIsContainer;ownerIsCurrentUser=($owner -ceq $script:regularUser);ownerIsTrusted=($owner -in $script:regularAllowed);daclProtected=$acl.AreAccessRulesProtected;effectiveForeignAllowCount=$effectiveForeign;foreignAllowSample=$foreign;privateLeaf=($owner -ceq $script:regularUser -and $acl.AreAccessRulesProtected -and $effectiveForeign -eq 0 -and -not $reparse)}
}
function Regular-Snapshot([string]$Path,[long]$Maximum) {
    # This snapshot does not pin a Win32 namespace or authorize execution. Share
    # the writer's read/write/delete rights and report detectable growth/change.
    $before=Get-Item -LiteralPath $Path -Force
    if($before.PSIsContainer -or (([int]$before.Attributes -band [int][IO.FileAttributes]::ReparsePoint) -ne 0) -or $before.Length -gt $Maximum){throw 'HOST_REGULAR_SNAPSHOT_REJECTED'}
    $stream=[IO.FileStream]::new($Path,[IO.FileMode]::Open,[IO.FileAccess]::Read,([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
    try {
        if($stream.Length -gt $Maximum){throw 'HOST_REGULAR_SNAPSHOT_REJECTED'}
        $length=$stream.Length;$bytes=[byte[]]::new($length);$offset=0
        while($offset -lt $length){$n=$stream.Read($bytes,$offset,$bytes.Length-$offset);if($n -eq 0){throw 'HOST_REGULAR_SNAPSHOT_CHANGED'};$offset+=$n}
        if($stream.Length -ne $length){throw 'HOST_REGULAR_SNAPSHOT_CHANGED'}
    } finally{$stream.Dispose()}
    $after=Get-Item -LiteralPath $Path -Force
    if($after.Length -ne $before.Length -or $after.LastWriteTimeUtc -ne $before.LastWriteTimeUtc){throw 'HOST_REGULAR_SNAPSHOT_CHANGED'}
    return @{sha256=(Regular-Hash $bytes);bytes=$bytes.Length;value=(ConvertFrom-Json -InputObject ([Text.UTF8Encoding]::new($false,$true).GetString($bytes)))}
}
function Regular-TaskSd($Object) {
    $sddl=$Object.GetSecurityDescriptor(5)
    $sd=[Security.AccessControl.RawSecurityDescriptor]::new($sddl)
    $foreign=@();$count=0
    if($null -ne $sd.DiscretionaryAcl){foreach($ace in $sd.DiscretionaryAcl){
        if($ace -isnot [Security.AccessControl.CommonAce] -or $ace.IsCallback){$count++;continue}
        if($ace.AceQualifier -ne 'AccessAllowed' -or (([int]$ace.AceFlags -band [int][Security.AccessControl.AceFlags]::InheritOnly) -ne 0)){continue}
        if($ace.SecurityIdentifier.Value -notin $script:regularAllowed -and $ace.AccessMask -ne 0){$count++;if($foreign.Count -lt 4){$foreign+=@{sidSha256=(Regular-Hash ([Text.Encoding]::UTF8.GetBytes($ace.SecurityIdentifier.Value)));mask=('0x{0:X8}' -f ([long]$ace.AccessMask -band 0xffffffffL));flags=[int]$ace.AceFlags}}}
    }}
    return @{sha256=(Regular-Hash ([Text.Encoding]::UTF8.GetBytes($sddl)));ownerTrusted=($null -ne $sd.Owner -and $sd.Owner.Value -in $script:regularAllowed);daclPresent=(([int]$sd.ControlFlags -band [int][Security.AccessControl.ControlFlags]::DiscretionaryAclPresent) -ne 0);daclProtected=(([int]$sd.ControlFlags -band [int][Security.AccessControl.ControlFlags]::DiscretionaryAclProtected) -ne 0);foreignAllowOrUnknownAceCount=$count;foreignSample=$foreign;effectiveAccessVerified=$false}
}
function Regular-BootEvents {
    $rows=@()
    foreach($filter in @(@{ProviderName='Microsoft-Windows-Kernel-General';Id=@(12,13)},@{ProviderName='Microsoft-Windows-Kernel-Boot';Id=27},@{ProviderName='User32';Id=1074},@{ProviderName='Microsoft-Windows-Eventlog';Id=104})) {
        $filter.LogName='System'
        try {
            $events=@(Get-WinEvent -FilterHashtable $filter -MaxEvents 2 -ErrorAction Stop)
            foreach($event in $events) {
                $bootType=$null
                if($event.ProviderName -ceq 'Microsoft-Windows-Kernel-Boot' -and $event.Id -eq 27){
                    [xml]$xml=$event.ToXml()
                    foreach($field in @($xml.Event.EventData.Data)){if($field.Name -ceq 'BootType' -and [string]$field.'#text' -match '^\d{1,3}$'){$bootType=[int][string]$field.'#text'}}
                }
                $rows+=@{provider=$event.ProviderName;id=$event.Id;recordId=[string]$event.RecordId;utc=$event.TimeCreated.ToUniversalTime().ToString('o');bootType=$bootType}
                $event.Dispose()
            }
        } catch{$rows+=@{provider=$filter.ProviderName;available=$false;error=(Regular-Fault $_)}}
    }
    return @{events=$rows;logSettingsChanged=$false;completeRestartProved=$false;historicalSubtreeVerified=$false;timeComparisonAuthorizesRetirement=$false}
}
# BEGIN REGULAR READONLY EXECUTION
$result=$null;$request=$null;$regularPhase='read-request'
try {
    $inputReader=[IO.StreamReader]::new([Console]::OpenStandardInput(),[Text.UTF8Encoding]::new($false,$true),$false,4096,$true)
    try{$raw=$inputReader.ReadToEnd()}finally{$inputReader.Dispose()}
    if($raw.Length -gt 8192){throw 'HOST_REGULAR_REQUEST_REJECTED'}
    $request=ConvertFrom-Json -InputObject $raw
    if($request.format -cne 'windows-host-regular-preflight-v1' -or $request.hostId -cnotmatch '^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$' -or $request.legacyOperationId -cnotmatch '^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$'){throw 'HOST_REGULAR_REQUEST_REJECTED'}
    if($request.installedProgram -isnot [string] -or $request.installedProgram -cnotmatch '^[A-Za-z]:\\' -or
       -not [string]::Equals([IO.Path]::GetFullPath($request.installedProgram),$request.installedProgram,[StringComparison]::OrdinalIgnoreCase) -or
       $request.legacyManifestSha256 -cne '3b170dd2bc1325266b929c0f3934f5b658e82367f7f1815b1374251c56bee1ea'){throw 'HOST_REGULAR_REQUEST_REJECTED'}
    $identity=[Security.Principal.WindowsIdentity]::GetCurrent()
    try{$script:regularUser=$identity.User.Value;$admin=([Security.Principal.WindowsPrincipal]::new($identity)).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)}finally{$identity.Dispose()}
    $script:regularAllowed=@($script:regularUser,'S-1-5-18','S-1-5-32-544')
    $profile=[Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)
    $privateRoot=Join-Path $profile '.config\agent-hub'
    $operation=Join-Path (Join-Path $privateRoot 'host-migrations') $request.legacyOperationId
    $paths=@{root=$privateRoot;migrations=(Split-Path -Parent $operation);operation=$operation;pending=(Join-Path (Split-Path -Parent $operation) 'pending.json');config=(Join-Path $request.installedProgram '.local\desktop-runtime\windows-host.json');runtimeStateDirectory=(Join-Path $request.installedProgram '.local\desktop-runtime');tokenMetadataOnly=(Join-Path $request.installedProgram '.local\desktop-runtime\access-token');taskRepairPending=(Join-Path $request.installedProgram '.local\desktop-runtime\task-recovery\pending.json');ordinaryLocal=(Join-Path $profile 'AppData\Local')}
    $regularPhase='private-metadata'
    $metadata=@{}
    foreach($name in $paths.Keys){try{$metadata[$name]=Regular-PathMetadata $paths[$name]}catch{$metadata[$name]=@{unavailable=$true;exists=$null;privateLeaf=$false;error=(Regular-Fault $_)}}}
    $regularPhase='bound-legacy-snapshots'
    $legacy=@{};$legacyOriginal=$null
    foreach($name in @('intent.json','domain.intent.json','domain.run-attempt.json','domain.process-anchors.json')) {
        $path=Join-Path $operation $name
        $m=Regular-PathMetadata $path
        if(-not $m.exists){$legacy[$name]=@{exists=$false};continue}
        if(-not $metadata.root.privateLeaf -or -not $metadata.migrations.privateLeaf -or -not $metadata.operation.privateLeaf -or -not $m.privateLeaf){$legacy[$name]=@{exists=$true;contentRead=$false;reason='private-boundary-unverified'};continue}
        $snapshot=Regular-Snapshot $path 131072
        $legacy[$name]=@{exists=$true;sha256=$snapshot.sha256;bytes=$snapshot.bytes}
        if($name -cne 'domain.process-anchors.json'){$legacy[$name].operationMatches=($snapshot.value.operationId -ceq $request.legacyOperationId)}
        if($name -ceq 'intent.json' -or $name -ceq 'domain.intent.json'){$legacy[$name].manifestMatches=($snapshot.value.manifestSha256 -ceq $request.legacyManifestSha256)}
        if($name -ceq 'intent.json'){
            $legacy[$name].hostIdMatches=($snapshot.value.hostId -ceq $request.hostId)
            $legacy[$name].installedProgramMatches=[string]::Equals($snapshot.value.installedProgram,$request.installedProgram,[StringComparison]::OrdinalIgnoreCase)
            $legacy[$name].configurationMatches=[string]::Equals($snapshot.value.configuration,$paths.config,[StringComparison]::OrdinalIgnoreCase)
        }
        if($name -ceq 'domain.intent.json'){$legacyOriginal=$snapshot.value.original}
        if($name -ceq 'domain.process-anchors.json'){$legacy[$name].anchorCount=@($snapshot.value.anchors).Count}
    }
    if($metadata.root.privateLeaf -and $metadata.migrations.privateLeaf -and $metadata.pending.exists -and $metadata.pending.privateLeaf){$snapshot=Regular-Snapshot $paths.pending 131072;$legacy.pending=@{exists=$true;sha256=$snapshot.sha256;bytes=$snapshot.bytes;operationMatches=($snapshot.value.operationId -ceq $request.legacyOperationId);manifestMatches=($snapshot.value.manifestSha256 -ceq $request.legacyManifestSha256);phase=$(if($snapshot.value.phase -ceq 'domain-verify'){'domain-verify'}else{'unexpected'})}}
    $configuration=@{read=$false}
    if($metadata.config.privateLeaf){
        $snapshot=Regular-Snapshot $paths.config 131072
        $configuration=@{read=$true;sha256=$snapshot.sha256;bytes=$snapshot.bytes;environmentKeys=@($snapshot.value.environment.PSObject.Properties.Name|Where-Object{$_ -in @('CODEX_DESKTOP_SUGGESTION_OBSERVER','CODEX_DESKTOP_TRACE_RETENTION','CODEX_DESKTOP_NATIVE_SUGGESTIONS_CONFIG')});suggestionAndRetentionDesired='disabled-in-future-Host-process-only';changed=$false}
    }
    $regularPhase='original-task-metadata'
    $scheduler=New-Object -ComObject 'Schedule.Service';$scheduler.Connect();$folder=$scheduler.GetFolder('\');$task=$folder.GetTask('CodexManage Host Agent');$definition=$task.Definition;$principal=$definition.Principal
    $principalMatches=($principal.UserId -ceq $script:regularUser)
    if(-not $principalMatches){try{$principalMatches=(([Security.Principal.NTAccount]::new($principal.UserId)).Translate([Security.Principal.SecurityIdentifier]).Value -ceq $script:regularUser)}catch{}}
    $actions=@();foreach($action in $definition.Actions){$actions+=@{type=[int]$action.Type;pathExists=$(if($action.Type -eq 0){Test-Path -LiteralPath $action.Path}else{$null});argumentsChars=$(if($action.Type -eq 0){$action.Arguments.Length}else{0});argumentsSha256=$(if($action.Type -eq 0){Regular-Hash ([Text.Encoding]::UTF8.GetBytes($action.Arguments))}else{$null});pathSha256=$(if($action.Type -eq 0){Regular-Hash ([Text.Encoding]::UTF8.GetBytes($action.Path))}else{$null})}}
    $domains=@();foreach($name in @('CodexManage Recovery Test ','CodexManage Recovery Guard ')){try{[void]$scheduler.GetFolder('\'+$name+$request.legacyOperationId);$domains+=@{kind=$name.Trim();exists=$true}}catch{$fault=Regular-Fault $_;if($fault.rootCode -in @(2,3)){$domains+=@{kind=$name.Trim();exists=$false}}else{$domains+=@{kind=$name.Trim();querySucceeded=$false;error=$fault}}}}
    $taskXmlHash=Regular-Hash ([Text.Encoding]::UTF8.GetBytes($task.Xml))
    $taskSecurity=Regular-TaskSd $task;$rootSecurity=Regular-TaskSd $folder
    $originalComparison=@{available=$false}
    if($null -ne $legacyOriginal){$originalComparison=@{available=$true;taskXmlMatches=($taskXmlHash -ceq $legacyOriginal.taskXml);taskSdMatches=($taskSecurity.sha256 -ceq $legacyOriginal.taskSd);rootSdMatches=($rootSecurity.sha256 -ceq $legacyOriginal.rootSd)}}
    $regularPhase='boot-observation'
    $result=@{status='observed';context=@{existingElevatedContext=$admin;userSIDExported=$false};paths=$metadata;legacy=$legacy;configuration=$configuration;task=@{xmlSha256=$taskXmlHash;security=$taskSecurity;rootSecurity=$rootSecurity;legacyOriginalComparison=$originalComparison;state=[int]$task.State;enabled=$task.Enabled;lastResult=[long]$task.LastTaskResult;principalMatchesCurrentUser=$principalMatches;logonType=[int]$principal.LogonType;runLevel=[int]$principal.RunLevel;multipleInstances=[int]$definition.Settings.MultipleInstances;instances=$task.GetInstances(0).Count;actions=$actions;triggers=@($definition.Triggers|ForEach-Object{@{type=[int]$_.Type;enabled=$_.Enabled}})};oldFixtureDomains=$domains;boot=@{lastBootUpTime=(Get-CimInstance -ClassName Win32_OperatingSystem).LastBootUpTime.ToUniversalTime().ToString('o');observation=(Regular-BootEvents)};namespaceDecision='old-AppData-rejection-not-overridden';ordinaryRecoveryImplemented=$false;pendingReleaseImplemented=$false;historicalCleanup='unconfirmed';next='review current-state evidence before any preparation or system prerequisite; no restart authorized by this report'}
} catch {
    $result=@{status='preflight-failed';phase=$regularPhase;error=(Regular-Fault $_);ordinaryRecoveryImplemented=$false;pendingReleaseImplemented=$false}
}
$result.format='windows-host-regular-preflight-v1';$result.hostId=$(if($null -ne $request){$request.hostId}else{$null});$result.legacyOperationId=$(if($null -ne $request){$request.legacyOperationId}else{$null});$result.readOnly=$true;$result.systemMutated=$false;$result.mutationAuthorized=$false;$result.hostStarted=$false
$bytes=[Text.UTF8Encoding]::new($false).GetBytes((ConvertTo-Json -InputObject $result -Depth 16 -Compress)+"`n")
if($bytes.Length -gt 12288){throw 'HOST_REGULAR_NATIVE_OUTPUT_BUDGET_REJECTED'}
$stream=[Console]::OpenStandardOutput();$stream.Write($bytes,0,$bytes.Length);$stream.Flush()
