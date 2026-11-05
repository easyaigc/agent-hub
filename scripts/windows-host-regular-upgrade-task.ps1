param(
 [ValidateSet('snapshot','apply','start','restore','start-old','status','verify-runtime')][string]$Action,
 [string]$PlanPath,[string]$PlanSha256,[string]$SnapshotPath,[string]$SnapshotSha256,
 [string]$FixtureName
)
$ErrorActionPreference='Stop';Set-StrictMode -Version Latest
function Upgrade-Hash([string]$Text){$h=[Security.Cryptography.SHA256]::Create();try{return ([BitConverter]::ToString($h.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text)))).Replace('-','').ToLowerInvariant()}finally{$h.Dispose()}}
function Upgrade-Sid([string]$Name){if($Name -match '^S-1-'){return ([Security.Principal.SecurityIdentifier]::new($Name)).Value};return ([Security.Principal.NTAccount]::new($Name)).Translate([Security.Principal.SecurityIdentifier]).Value}
function Upgrade-Xml([string]$Xml,[string]$Sddl,$ActionValue,[bool]$Enabled) {
 $doc=[xml]$Xml;$ns=[Xml.XmlNamespaceManager]::new($doc.NameTable);$ns.AddNamespace('t',$doc.DocumentElement.NamespaceURI)
 $embedded=$doc.SelectNodes('/t:Task/t:RegistrationInfo/t:SecurityDescriptor',$ns)
 if($embedded.Count -gt 1){throw 'UPGRADE_XML_REJECTED'}
 if($embedded.Count -eq 1){if($embedded[0].InnerText -cne $Sddl){throw 'UPGRADE_XML_REJECTED'};[void]$embedded[0].ParentNode.RemoveChild($embedded[0])}
 $exec=$doc.SelectNodes('/t:Task/t:Actions/t:Exec',$ns);$all=$doc.SelectNodes('/t:Task/t:Actions/*',$ns);if($exec.Count -ne 1 -or $all.Count -ne 1){throw 'UPGRADE_XML_REJECTED'}
 foreach($pair in @(@('Command','launcher'),@('Arguments','arguments'),@('WorkingDirectory','workingDirectory'))){$node=$exec[0].SelectSingleNode('t:'+$pair[0],$ns);if($null -eq $node){throw 'UPGRADE_XML_REJECTED'};$node.InnerText=[string]$ActionValue.($pair[1])}
 $enabledNode=$doc.SelectSingleNode('/t:Task/t:Settings/t:Enabled',$ns);if($null -eq $enabledNode){throw 'UPGRADE_XML_REJECTED'};$enabledNode.InnerText=if($Enabled){'true'}else{'false'}
 return $doc.OuterXml
}
function Upgrade-SameAction($Task,$Expected){$d=$Task.Definition;if($d.Actions.Count -ne 1){return $false};$a=$d.Actions.Item(1);return ([string]::Equals($a.Path,[string]$Expected.launcher,[StringComparison]::OrdinalIgnoreCase) -and $a.Arguments -ceq [string]$Expected.arguments -and [string]::Equals($a.WorkingDirectory,[string]$Expected.workingDirectory,[StringComparison]::OrdinalIgnoreCase))}
function Upgrade-CommandPattern($Words,[string]$Suffix=''){$parts=@($Words|ForEach-Object{$x=[regex]::Escape([string]$_);'(?:"'+$x+'"|'+$x+')'});return '^\s*'+($parts -join '\s+')+$Suffix+'\s*$'}
function Upgrade-VerifyRuntime($Plan,[string]$Sid) {
 $clock=[Diagnostics.Stopwatch]::StartNew();$held=@()
 try {
  $port=[string]$Plan.newConfiguration.environment.HOST_AGENT_PORT;if($port -cnotmatch '^\d{1,5}$' -or [int]$port -lt 1 -or [int]$port -gt 65535){throw 'UPGRADE_RUNTIME_PORT_REJECTED'}
  $listeners=@(Get-NetTCPConnection -LocalPort ([int]$port) -State Listen -ErrorAction Stop|Where-Object{$_.LocalAddress -eq '127.0.0.1'});if($listeners.Count -ne 1){throw 'UPGRADE_RUNTIME_LISTENER_REJECTED'}
  Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Security.Principal;
public static class UpgradeProcessOwner {
 [DllImport("advapi32.dll", SetLastError=true)] static extern bool OpenProcessToken(IntPtr p,uint access,out IntPtr token);
 [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);
 public static string Read(IntPtr p){IntPtr t;if(!OpenProcessToken(p,8,out t))throw new Win32Exception(Marshal.GetLastWin32Error());try{using(var i=new WindowsIdentity(t))return i.User.Value;}finally{CloseHandle(t);}}
}
'@
  $ids=@([uint32]$listeners[0].OwningProcess);$records=@()
  for($index=0;$index -lt 3;$index++){
   if($clock.ElapsedMilliseconds -ge 45000){throw 'UPGRADE_RUNTIME_TIMEOUT'}
   $id=$ids[$index];$record=Get-CimInstance Win32_Process -Filter ('ProcessId='+$id) -OperationTimeoutSec 8 -ErrorAction Stop;if($null -eq $record){throw 'UPGRADE_RUNTIME_PROCESS_MISSING'}
   $process=Get-Process -Id $id -ErrorAction Stop;$null=$process.Handle;$held+=,$process
   if($process.HasExited -or [Math]::Abs(($process.StartTime.ToUniversalTime()-$record.CreationDate.ToUniversalTime()).Ticks) -gt 10000 -or $process.SessionId -ne [Diagnostics.Process]::GetCurrentProcess().SessionId -or [UpgradeProcessOwner]::Read($process.Handle) -cne $Sid){throw 'UPGRADE_RUNTIME_PROCESS_CHANGED'}
   $records+=,$record;if($index -lt 2){$ids+=,[uint32]$record.ParentProcessId}
  }
  $a=$Plan.newAction
  if(-not [string]::Equals($records[0].ExecutablePath,$a.node,[StringComparison]::OrdinalIgnoreCase) -or
     -not [string]::Equals($records[1].ExecutablePath,$a.node,[StringComparison]::OrdinalIgnoreCase) -or
     -not [string]::Equals($records[2].ExecutablePath,$a.launcher,[StringComparison]::OrdinalIgnoreCase) -or
     $records[0].CommandLine -notmatch (Upgrade-CommandPattern @($a.node,$a.worker,$a.configuration) '\s+--host-child') -or
     $records[1].CommandLine -notmatch (Upgrade-CommandPattern @($a.node,$a.worker,$a.configuration)) -or
     $records[2].CommandLine -notmatch (Upgrade-CommandPattern @($a.launcher,$a.node,$a.worker,$a.configuration))){throw 'UPGRADE_RUNTIME_LINEAGE_REJECTED'}
  for($index=0;$index -lt 3;$index++){
   if($clock.ElapsedMilliseconds -ge 45000){throw 'UPGRADE_RUNTIME_TIMEOUT'}
   $r=Get-CimInstance Win32_Process -Filter ('ProcessId='+$ids[$index]) -OperationTimeoutSec 8 -ErrorAction Stop
   if($null -eq $r -or $held[$index].HasExited -or $r.CreationDate -ne $records[$index].CreationDate -or $r.ParentProcessId -ne $records[$index].ParentProcessId -or $r.ExecutablePath -cne $records[$index].ExecutablePath -or $r.CommandLine -cne $records[$index].CommandLine){throw 'UPGRADE_RUNTIME_PROCESS_CHANGED'}
  }
  $again=@(Get-NetTCPConnection -LocalPort ([int]$port) -State Listen -ErrorAction Stop|Where-Object{$_.LocalAddress -eq '127.0.0.1' -and $_.OwningProcess -eq $ids[0]});if($again.Count -ne 1 -or $clock.ElapsedMilliseconds -ge 45000){throw 'UPGRADE_RUNTIME_LISTENER_CHANGED'}
  $h=[Security.Cryptography.SHA256]::Create();try{$f=[IO.File]::OpenRead($a.launcher);try{$launcherSha=([BitConverter]::ToString($h.ComputeHash($f))).Replace('-','').ToLowerInvariant()}finally{$f.Dispose()}}finally{$h.Dispose()};if($launcherSha -cne $a.launcherSha256){throw 'UPGRADE_RUNTIME_LAUNCHER_CHANGED'}
  return @{status='verified';listenerPid=$ids[0];supervisorPid=$ids[1];launcherPid=$ids[2];listenerStartedUtc=$records[0].CreationDate.ToUniversalTime().ToString('o');supervisorStartedUtc=$records[1].CreationDate.ToUniversalTime().ToString('o');launcherStartedUtc=$records[2].CreationDate.ToUniversalTime().ToString('o');nodePathSha256=(Upgrade-Hash $a.node);launcherPathSha256=(Upgrade-Hash $a.launcher);launcherSha256=$launcherSha;elapsedMs=$clock.ElapsedMilliseconds}
 } finally {foreach($p in $held){$p.Dispose()}}
}
if($MyInvocation.InvocationName -eq '.'){return}
$leases=@();$sourceLeases=@();$scratch=$null
try {
 if($FixtureName){if($FixtureName -cnotmatch '^AgentHub Upgrade Fixture [a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$'){throw 'UPGRADE_FIXTURE_NAME_REJECTED'};$taskName=$FixtureName}else{$taskName='CodexManage Host Agent'}
 if($PlanSha256 -cnotmatch '^[a-f0-9]{64}$' -or ($Action -ne 'snapshot' -and $SnapshotSha256 -cnotmatch '^[a-f0-9]{64}$')){throw 'UPGRADE_HASH_REQUIRED'}
 foreach($m in @('Microsoft.PowerShell.Security','Microsoft.PowerShell.Utility','Microsoft.PowerShell.Management')){Import-Module (Join-Path $PSHOME ('Modules\'+$m)) -ErrorAction Stop}
 $source=Join-Path $PSScriptRoot '..\packages\codex-adapter\windows';. (Join-Path $source 'private-bootstrap.ps1')
 $scratch=Open-HostBootstrapScratch $source
 if($null -eq $scratch){throw 'UPGRADE_PROTECTED_STORAGE_REQUIRED'}
 $sourceHashes=@{};foreach($name in @('private-state.cs','private-state-compilation.cs','private-state-recovery.cs')){
  $file=[IO.FileStream]::new((Join-Path $source $name),[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read);$sourceLeases+=,$file
  $h=[Security.Cryptography.SHA256]::Create();try{$sourceHashes[$name]=([BitConverter]::ToString($h.ComputeHash($file))).Replace('-','').ToLowerInvariant()}finally{$h.Dispose()};$file.Position=0
 }
 Add-Type -Path @((Join-Path $source 'private-state.cs'),(Join-Path $source 'private-state-compilation.cs'),(Join-Path $source 'private-state-recovery.cs')) -ReferencedAssemblies @('System.dll','System.Core.dll','System.Xml.dll','System.Runtime.Serialization.dll')
 $p=[CodexManagePrivateStateSecurity]::OpenRecoveryRead($PlanPath,1048576);$leases+=,$p;if($p.Sha256 -cne $PlanSha256){throw 'UPGRADE_PLAN_CHANGED'};$plan=ConvertFrom-Json ([Text.Encoding]::UTF8.GetString($p.Bytes))
 foreach($name in $sourceHashes.Keys){if([string]$plan.taskHelperSources.$name -cne $sourceHashes[$name]){throw 'UPGRADE_SOURCE_CHANGED'}}
 if($plan.operationId -cnotmatch '^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$'){throw 'UPGRADE_PLAN_REJECTED'}
 $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
 $scheduler=New-Object -ComObject 'Schedule.Service';$scheduler.Connect();$root=$scheduler.GetFolder('\');$task=$root.GetTask($taskName);$d=$task.Definition
 if((Upgrade-Sid $d.Principal.UserId) -cne $sid -or $d.Principal.LogonType -ne 3 -or $d.Principal.RunLevel -ne 0){throw 'UPGRADE_PRINCIPAL_REJECTED'}
 $sd=[string]$task.GetSecurityDescriptor(7);$instances=@($task.GetInstances(0)|ForEach-Object{[string]$_.InstanceGuid})
 if($Action -eq 'snapshot'){
  if(-not (Upgrade-SameAction $task $plan.oldAction)){throw 'UPGRADE_ACTION_CHANGED'}
  $snap=@{format='windows-host-upgrade-task-snapshot-v1';operationId=$plan.operationId;planSha256=$p.Sha256;taskName=$taskName;sid=$sid;definitionXml=[string]$d.XmlText;sddl=$sd;enabled=[bool]$task.Enabled;instances=$instances;definitionSha256=(Upgrade-Hash ([string]$d.XmlText));sddlSha256=(Upgrade-Hash $sd)}
  $bytes=[Text.UTF8Encoding]::new($false).GetBytes((ConvertTo-Json -Depth 20 -Compress -InputObject $snap)+"`n");[void][CodexManagePrivateStateSecurity]::CreateRecoveryFile($SnapshotPath,$bytes)
  $result=@{status='snapshot';snapshotSha256=([BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($bytes))).Replace('-','').ToLowerInvariant();definitionSha256=$snap.definitionSha256;sddlSha256=$snap.sddlSha256;enabled=$snap.enabled;instances=$instances.Count}
 }else{
  $s=[CodexManagePrivateStateSecurity]::OpenRecoveryRead($SnapshotPath,1048576);$leases+=,$s;if($s.Sha256 -cne $SnapshotSha256){throw 'UPGRADE_SNAPSHOT_CHANGED'};$snap=ConvertFrom-Json ([Text.Encoding]::UTF8.GetString($s.Bytes))
  if($snap.format -cne 'windows-host-upgrade-task-snapshot-v1' -or $snap.operationId -cne $plan.operationId -or $snap.planSha256 -cne $p.Sha256 -or $snap.sid -cne $sid -or $snap.taskName -cne $taskName -or (Upgrade-Hash $snap.definitionXml) -cne $snap.definitionSha256 -or (Upgrade-Hash $snap.sddl) -cne $snap.sddlSha256 -or $sd -cne $snap.sddl){throw 'UPGRADE_SNAPSHOT_REJECTED'}
  $oldXml=Upgrade-Xml $snap.definitionXml $snap.sddl $plan.oldAction $false;$newXml=Upgrade-Xml $snap.definitionXml $snap.sddl $plan.newAction $false
  $actual=Upgrade-Xml ([string]$d.XmlText) $sd $(if(Upgrade-SameAction $task $plan.oldAction){$plan.oldAction}else{$plan.newAction}) $false
  $old=(Upgrade-SameAction $task $plan.oldAction) -and $actual -ceq $oldXml;$new=(Upgrade-SameAction $task $plan.newAction) -and $actual -ceq $newXml
  if($Action -eq 'status'){$result=@{status='observed';route=$(if($old -and -not $new){'old'}elseif($new -and -not $old){'new'}else{'unknown'});stopped=($instances.Count -eq 0);running=($instances.Count -gt 0);enabled=[bool]$task.Enabled;instances=$instances.Count}}
  elseif($Action -eq 'verify-runtime') {if(-not $new -or -not $task.Enabled -or $instances.Count -ne 1){throw 'UPGRADE_RUNTIME_TASK_REJECTED'};$result=Upgrade-VerifyRuntime $plan $sid}
  else {
   if($task.Enabled -or $instances.Count -ne 0){throw 'UPGRADE_TASK_NOT_STOPPED'}
   if($Action -eq 'apply' -or $Action -eq 'restore'){
    if(($Action -eq 'apply' -and -not $old) -or ($Action -eq 'restore' -and -not $new)){throw 'UPGRADE_TASK_CHANGED'}
    $definition=$scheduler.NewTask(0);$definition.XmlText=if($Action -eq 'apply'){$newXml}else{$oldXml};$null=$root.RegisterTaskDefinition($taskName,$definition,20,$sid,$null,3,$snap.sddl)
    $updated=$root.GetTask($taskName);$want=if($Action -eq 'apply'){$plan.newAction}else{$plan.oldAction};$expected=if($Action -eq 'apply'){$newXml}else{$oldXml}
    if($updated.Enabled -or $updated.GetInstances(0).Count -ne 0 -or -not (Upgrade-SameAction $updated $want) -or [string]$updated.GetSecurityDescriptor(7) -cne $snap.sddl -or (Upgrade-Xml ([string]$updated.Definition.XmlText) $snap.sddl $want $false) -cne $expected){throw 'UPGRADE_TASK_WRITE_UNCONFIRMED'}
    $result=@{status=if($Action -eq 'apply'){'applied'}else{'restored'};enabled=$false;instances=0}
   } else {
    if(($Action -eq 'start' -and -not $new) -or ($Action -eq 'start-old' -and -not $old) -or -not $snap.enabled){throw 'UPGRADE_TASK_CHANGED'}
    $task.Enabled=$true;[void]$task.Run($null);$result=@{status='run-requested';enabled=$true;instances=@($task.GetInstances(0)).Count}
   }
  }
 }
 [Console]::Out.WriteLine((ConvertTo-Json -Compress -Depth 5 $result))
}catch{$e=$_.Exception;while($null -ne $e.InnerException){$e=$e.InnerException};$code=$_.Exception.Message;if($code -cnotmatch '^UPGRADE_[A-Z0-9_]+$'){$code='UPGRADE_NATIVE_FAILURE'};[Console]::Error.WriteLine((ConvertTo-Json -Compress @{status='rejected';code=$code;errorType=$e.GetType().FullName;hresult=('0x{0:X8}' -f ($e.HResult -band 0xffffffffL))}));exit 1}finally{for($i=$leases.Count-1;$i -ge 0;$i--){$leases[$i].Dispose()};for($i=$sourceLeases.Count-1;$i -ge 0;$i--){$sourceLeases[$i].Dispose()};if($scratch){Close-HostBootstrapScratch $scratch}}
