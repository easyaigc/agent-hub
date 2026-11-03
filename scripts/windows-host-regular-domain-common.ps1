# File-defined functions only; callers dot-source this frozen file, never a
# memory-defined function that guesses its PSScriptRoot.
function Regular-DomainSddl([string]$Sid,[bool]$UserWrite) {
    $right=if($UserWrite){'FA'}else{'FRFX'}
    return 'O:BAG:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)(A;;'+$right+';;;'+$Sid+')'
}
function Regular-DomainSignature([string]$Sddl) {
    $sd=[Security.AccessControl.RawSecurityDescriptor]::new($Sddl)
    if($null -eq $sd.Owner -or $null -eq $sd.DiscretionaryAcl -or (([int]$sd.ControlFlags -band [int][Security.AccessControl.ControlFlags]::DiscretionaryAclProtected) -eq 0)){throw 'HOST_DOMAIN_SD_REJECTED'}
    $rows=@()
    foreach($ace in $sd.DiscretionaryAcl){
        if($ace -isnot [Security.AccessControl.CommonAce] -or $ace.IsCallback){throw 'HOST_DOMAIN_SD_REJECTED'}
        $rows+=($ace.SecurityIdentifier.Value+'|'+[string]$ace.AceQualifier+'|'+[string][int]$ace.AceFlags+'|'+[string]([long]$ace.AccessMask -band 0xffffffffL))
    }
    return $sd.Owner.Value+'|'+(($rows|Sort-Object)-join ';')
}
function Regular-AssertDomainSd($Object,[string]$Expected) {
    if((Regular-DomainSignature ([string]$Object.GetSecurityDescriptor(5))) -cne (Regular-DomainSignature $Expected)){throw 'HOST_DOMAIN_SD_CHANGED'}
}
function Regular-NewFixture($Service,[string]$Sid,[string]$Name,[bool]$Updated) {
    $d=$Service.NewTask(0);$d.RegistrationInfo.Description='Agent Hub regular recovery synthetic '+$Name+':'+[string]$Updated
    $d.Principal.UserId=$Sid;$d.Principal.LogonType=3;$d.Principal.RunLevel=0
    $d.Settings.Enabled=$false;$d.Settings.MultipleInstances=2;$d.Settings.ExecutionTimeLimit='PT3M'
    $d.Settings.DisallowStartIfOnBatteries=$false;$d.Settings.StopIfGoingOnBatteries=$false
    $a=$d.Actions.Create(0);$a.Path=Join-Path ([Environment]::SystemDirectory) 'WindowsPowerShell\v1.0\powershell.exe'
    $a.Arguments='-NoProfile -NonInteractive -Command exit 0'
    return $d
}
function Regular-PrincipalSid([string]$UserId) {
    if($UserId -cmatch '^S-1-'){return ([Security.Principal.SecurityIdentifier]::new($UserId)).Value}
    if([string]::IsNullOrWhiteSpace($UserId)){throw 'HOST_DOMAIN_PRINCIPAL_MISSING'}
    return ([Security.Principal.NTAccount]::new($UserId)).Translate([Security.Principal.SecurityIdentifier]).Value
}
function Regular-AssertFixtureTask($Task,[string]$Sid,[string]$Name,[bool]$Updated,[string]$Executable,[string]$Arguments,[string]$WorkingDirectory,[bool]$UserWrite) {
    Regular-AssertDomainSd $Task (Regular-DomainSddl $Sid $UserWrite)
    $d=$Task.Definition
    $description='Agent Hub regular recovery synthetic '+$Name+':'+[string]$Updated
    if($d.RegistrationInfo.Description -cne $description -or (Regular-PrincipalSid $d.Principal.UserId) -cne $Sid -or
        $d.Principal.LogonType -ne 3 -or $d.Principal.RunLevel -ne 0 -or
        $d.Actions.Count -ne 1 -or $d.Triggers.Count -ne 0 -or
        $d.Settings.MultipleInstances -ne 2 -or $d.Settings.ExecutionTimeLimit -cne 'PT3M') {
        throw 'HOST_DOMAIN_FIXTURE_CONTRACT_CHANGED'
    }
    $action=$d.Actions.Item(1)
    if(-not [string]::Equals([string]$action.Path,$Executable,[StringComparison]::OrdinalIgnoreCase) -or
        $action.Arguments -cne $Arguments -or
        -not [string]::Equals([string]$action.WorkingDirectory,$WorkingDirectory,[StringComparison]::OrdinalIgnoreCase)) {
        throw 'HOST_DOMAIN_FIXTURE_ACTION_CHANGED'
    }
}
function Regular-GetOwnedRunner($Root,[string]$FolderName,[string]$Sid,[string]$Executable,[string]$Arguments,[string]$WorkingDirectory) {
    # A stale COM object/name is not authority over a replacement. Both normal
    # and exceptional cleanup must resolve and validate the current objects.
    $folder=$Root.GetFolder(('\'+$FolderName))
    Regular-AssertDomainSd $folder (Regular-DomainSddl $Sid $false)
    $task=$folder.GetTask('Runner')
    Regular-AssertFixtureTask $task $Sid 'Runner' $false $Executable $Arguments $WorkingDirectory $false
    return $task
}
function Regular-StopOwnedRunner($Root,[string]$FolderName,[string]$Sid,[string]$Executable,[string]$Arguments,[string]$WorkingDirectory,[bool]$Stop) {
    $task=Regular-GetOwnedRunner $Root $FolderName $Sid $Executable $Arguments $WorkingDirectory
    $task.Enabled=$false
    if($Stop){$task.Stop(0)}
    return $task
}
function Regular-TryStopOwnedRunner($Root,[string]$FolderName,[string]$Sid,[string]$Executable,[string]$Arguments,[string]$WorkingDirectory,[bool]$Stop) {
    try {
        $null=Regular-StopOwnedRunner $Root $FolderName $Sid $Executable $Arguments $WorkingDirectory $Stop
        return $true
    } catch {
        # Retain an unknown/refused/replaced Task. The caller may still close
        # its own retained Job; that never authorizes a mutation by Task name.
        return $false
    }
}
function Regular-DeleteOwnedFixture($Root,[string]$FolderName,[string]$Sid,[string]$Name,[bool]$Updated,[string]$Executable,[string]$Arguments,[string]$WorkingDirectory,[bool]$UserWrite) {
    if($Name -notin @('Runner','PositiveUpdate','PositiveDelete','NegativeUpdate','NegativeDelete')){throw 'HOST_DOMAIN_CLEANUP_NAME_REJECTED'}
    $folder=$Root.GetFolder(('\'+$FolderName))
    Regular-AssertDomainSd $folder (Regular-DomainSddl $Sid $false)
    $task=$folder.GetTask($Name)
    Regular-AssertFixtureTask $task $Sid $Name $Updated $Executable $Arguments $WorkingDirectory $UserWrite
    if($task.Enabled -or $task.GetInstances(0).Count -ne 0){throw 'HOST_DOMAIN_CLEANUP_TASK_ACTIVE'}
    $folder.DeleteTask($Name,0)
}
function Regular-DeleteEmptyFixtureFolder($Root,[string]$Name,[string]$ExpectedSddl) {
    $folder=$Root.GetFolder(('\'+$Name))
    Regular-AssertDomainSd $folder $ExpectedSddl
    if($folder.GetTasks(1).Count -ne 0 -or $folder.GetFolders(0).Count -ne 0){throw 'HOST_DOMAIN_CLEANUP_INVENTORY_CHANGED'}
    $Root.DeleteFolder($Name,0)
}
function Regular-RootException($ErrorRecord) {
    $e=$ErrorRecord.Exception
    for($i=0;$null -ne $e.InnerException -and $i -lt 10;$i++){$e=$e.InnerException}
    return $e
}
function Regular-WriteReport([string]$Path,$Value) {
    $user=[Security.Principal.WindowsIdentity]::GetCurrent().User
    $sd=[Security.AccessControl.FileSecurity]::new();$sd.SetOwner($user);$sd.SetAccessRuleProtection($true,$false)
    foreach($sid in @($user.Value,'S-1-5-18','S-1-5-32-544')){$sd.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new($sid),'FullControl','Allow'))}
    $bytes=[Text.UTF8Encoding]::new($false).GetBytes((ConvertTo-Json -Depth 20 -Compress -InputObject $Value)+"`n")
    if($bytes.Length -gt 16384){throw 'HOST_DOMAIN_REPORT_BUDGET'}
    $stream=[IO.FileStream]::new($Path,[IO.FileMode]::CreateNew,[Security.AccessControl.FileSystemRights]::Write,[IO.FileShare]::Read,4096,[IO.FileOptions]::WriteThrough,$sd)
    try{$stream.Write($bytes,0,$bytes.Length);$stream.Flush($true)}finally{$stream.Dispose()}
}
