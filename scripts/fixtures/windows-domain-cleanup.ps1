param([Parameter(Mandatory=$true)][string]$Common)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
. $Common
# The COM adapter is fake here. Native DACL and Scheduler behavior are tested
# separately; these cases exercise the real mutation ordering on failures.
function Regular-AssertDomainSd($Object,[string]$Expected) {
    $Object.SdReads++
    if($Object.Security -cne $Expected){throw 'HOST_DOMAIN_SD_CHANGED'}
}
$sid='S-1-5-21-1-2-3-1001'
if([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT){
    # The fake COM fixture checks mutation ordering, not Windows SID parsing.
    # .NET's SecurityIdentifier API is unavailable on macOS/Linux. Only this
    # fixture's literal user is mapped; production and Windows use the real API.
    function Regular-PrincipalSid([string]$UserId){
        if($UserId -cne $script:sid){throw 'FIXTURE_PRINCIPAL_UNMAPPED'}
        return $UserId
    }
}
$folderName='CodexManage Regular Verify 33333333-3333-4333-8333-333333333333'
$image='C:\Private\host-domain.exe'
$arguments='fixture "C:\Private\plan.json" fixed-plan'
$directory='C:\Private\operation'
function New-MockFixture {
    $effects=[pscustomobject]@{writes=0;stops=0;deletes=0;folderDeletes=0;enabled=$false}
    $action=[pscustomobject]@{Path=$image;Arguments=$arguments;WorkingDirectory=$directory}
    $actions=[pscustomobject]@{Count=1;Entry=$action}
    $actions|Add-Member ScriptMethod Item {param($index);if($index -ne 1){throw 'bad index'};return $this.Entry}
    $definition=[pscustomobject]@{
        RegistrationInfo=[pscustomobject]@{Description='Agent Hub regular recovery synthetic Runner:False'}
        Principal=[pscustomobject]@{UserId=$sid;LogonType=3;RunLevel=0}
        Actions=$actions;Triggers=[pscustomobject]@{Count=0}
        Settings=[pscustomobject]@{MultipleInstances=2;ExecutionTimeLimit='PT3M'}
    }
    $task=[pscustomobject]@{Definition=$definition;Security=(Regular-DomainSddl $sid $false);Effects=$effects;SdReads=0;InstanceCount=0}
    $task|Add-Member ScriptProperty Enabled {$this.Effects.enabled} {param($v);$this.Effects.writes++;$this.Effects.enabled=$v}
    $task|Add-Member ScriptMethod Stop {param($flags);$this.Effects.stops++}
    $task|Add-Member ScriptMethod GetInstances {param($flags);return [pscustomobject]@{Count=$this.InstanceCount}}
    $folder=[pscustomobject]@{Task=$task;Security=(Regular-DomainSddl $sid $false);Effects=$effects;SdReads=0;LookupError=$null;TaskCount=0;FolderCount=0}
    $folder|Add-Member ScriptMethod GetTask {param($name);if($null -ne $this.LookupError){throw $this.LookupError};return $this.Task}
    $folder|Add-Member ScriptMethod DeleteTask {param($name,$flags);$this.Effects.deletes++}
    $folder|Add-Member ScriptMethod GetTasks {param($flags);return [pscustomobject]@{Count=$this.TaskCount}}
    $folder|Add-Member ScriptMethod GetFolders {param($flags);return [pscustomobject]@{Count=$this.FolderCount}}
    $root=[pscustomobject]@{Folder=$folder;Effects=$effects;LookupError=$null}
    $root|Add-Member ScriptMethod GetFolder {param($name);if($null -ne $this.LookupError){throw $this.LookupError};return $this.Folder}
    $root|Add-Member ScriptMethod DeleteFolder {param($name,$flags);$this.Effects.folderDeletes++}
    return @{root=$root;folder=$folder;task=$task;action=$action;effects=$effects}
}
function Assert-NoMutation($Mock) {
    if($Mock.effects.writes -ne 0 -or $Mock.effects.stops -ne 0 -or $Mock.effects.deletes -ne 0 -or $Mock.effects.folderDeletes -ne 0){throw 'unknown object was mutated'}
}
$cases=0
foreach($change in @('folder-sd','task-sd','image','arguments','directory','user','level','trigger','actions','missing-folder','denied-task','description')){
    $m=New-MockFixture
    switch($change){
        'folder-sd' {$m.folder.Security='replacement'}
        'task-sd' {$m.task.Security='replacement'}
        'image' {$m.action.Path='C:\Other\user-job.exe'}
        'arguments' {$m.action.Arguments='different user work'}
        'directory' {$m.action.WorkingDirectory='C:\Other'}
        'user' {$m.task.Definition.Principal.UserId='other-user'}
        'level' {$m.task.Definition.Principal.RunLevel=1}
        'trigger' {$m.task.Definition.Triggers.Count=1}
        'actions' {$m.task.Definition.Actions.Count=2}
        'missing-folder' {$m.root.LookupError=[IO.DirectoryNotFoundException]::new('synthetic')}
        'denied-task' {$m.folder.LookupError=[UnauthorizedAccessException]::new('synthetic')}
        'description' {$m.task.Definition.RegistrationInfo.Description='unrelated task'}
    }
    $threw=$false
    try{$null=Regular-StopOwnedRunner $m.root $folderName $sid $image $arguments $directory $true}catch{$threw=$true}
    if(-not $threw){throw 'normal path accepted a changed fixture'}
    if(Regular-TryStopOwnedRunner $m.root $folderName $sid $image $arguments $directory $true){throw 'exception cleanup accepted a changed fixture'}
    $threw=$false
    try{Regular-DeleteOwnedFixture $m.root $folderName $sid 'Runner' $false $image $arguments $directory $false}catch{$threw=$true}
    if(-not $threw){throw 'delete accepted a changed fixture'}
    Assert-NoMutation $m
    $cases++
}
$m=New-MockFixture
if(-not (Regular-TryStopOwnedRunner $m.root $folderName $sid $image $arguments $directory $true)){throw 'owned runner could not stop'}
if($m.effects.writes -ne 1 -or $m.effects.stops -ne 1){throw 'owned runner stop not executed exactly once'}
Regular-DeleteOwnedFixture $m.root $folderName $sid 'Runner' $false $image $arguments $directory $false
if($m.effects.deletes -ne 1){throw 'owned fixture delete not executed'}
$cases++
$m=New-MockFixture;$m.task.InstanceCount=1
$threw=$false;try{Regular-DeleteOwnedFixture $m.root $folderName $sid 'Runner' $false $image $arguments $directory $false}catch{$threw=$true}
if(-not $threw){throw 'active fixture deleted'};Assert-NoMutation $m;$cases++
$m=New-MockFixture;$m.folder.Security='replacement'
$threw=$false;try{Regular-DeleteEmptyFixtureFolder $m.root $folderName (Regular-DomainSddl $sid $false)}catch{$threw=$true}
if(-not $threw){throw 'replacement folder deleted'};Assert-NoMutation $m;$cases++
$m=New-MockFixture;$m.folder.TaskCount=1
$threw=$false;try{Regular-DeleteEmptyFixtureFolder $m.root $folderName (Regular-DomainSddl $sid $false)}catch{$threw=$true}
if(-not $threw){throw 'nonempty folder deleted'};Assert-NoMutation $m;$cases++
$m=New-MockFixture
Regular-DeleteEmptyFixtureFolder $m.root $folderName (Regular-DomainSddl $sid $false)
if($m.effects.folderDeletes -ne 1){throw 'owned empty folder not deleted'};$cases++
[Console]::Out.WriteLine((ConvertTo-Json -Compress @{cases=$cases;nativeScheduler=$false}))
