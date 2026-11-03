param([Parameter(Mandatory=$true)][string]$Plan)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop';$cases=@();$child=$null;$planValue=$null
foreach($module in @('Microsoft.PowerShell.Management','Microsoft.PowerShell.Security','Microsoft.PowerShell.Utility')){Import-Module (Join-Path $PSHOME ('Modules\'+$module)) -ErrorAction Stop}
. (Join-Path $PSScriptRoot 'windows-host-regular-domain-common.ps1')
try {
    $planValue=ConvertFrom-Json -InputObject ([IO.File]::ReadAllText($Plan,[Text.UTF8Encoding]::new($false,$true)))
    $op=$planValue.operationId
    if($planValue.format -cne 'windows-host-regular-domain-plan-v1' -or $op -cnotmatch '^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$'){throw 'HOST_DOMAIN_PLAN_REJECTED'}
    $identity=[Security.Principal.WindowsIdentity]::GetCurrent()
    try{$userMatches=$identity.User.Value -ceq $planValue.userSid;$adminRole=([Security.Principal.WindowsPrincipal]::new($identity)).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)}finally{$identity.Dispose()}
    if(-not $userMatches -or $adminRole){throw 'HOST_DOMAIN_LIMITED_REQUIRED'}
    $expected=Join-Path ([Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)) ('.config\agent-hub\host-regular-recovery\'+$planValue.preparationId+'\domain-'+$op+'\plan.json')
    if(-not [string]::Equals([IO.Path]::GetFullPath($Plan),$expected,[StringComparison]::OrdinalIgnoreCase)){throw 'HOST_DOMAIN_PLAN_PATH_REJECTED'}
    $service=New-Object -ComObject Schedule.Service;$service.Connect();$root=$service.GetFolder('\')
    $folder=$service.GetFolder('\CodexManage Regular Verify '+$op)
    function Attempt-RegularDomain([string]$Name,[bool]$Positive,[ScriptBlock]$Call){
        $row=@{name=$Name;attempted=$true;outcome='unknown';hresult=$null}
        try{& $Call|Out-Null;$row.outcome='success'}catch{$e=Regular-RootException $_;$row.hresult='0x{0:X8}' -f ($e.HResult -band 0xffffffffL);$row.outcome=if($row.hresult -ceq '0x80070005'){'denied'}else{'native-error'}}
        $script:cases+=$row
        if(($Positive -and $row.outcome -cne 'success') -or (-not $Positive -and $row.outcome -cne 'denied')){throw 'HOST_DOMAIN_MATRIX_FAILED'}
    }
    $positive=Regular-NewFixture $service $planValue.userSid 'PositiveUpdate' $true
    $negative=Regular-NewFixture $service $planValue.userSid 'NegativeUpdate' $true
    $create=Regular-NewFixture $service $planValue.userSid 'UnexpectedCreate' $false
    # All pre-reads occur before recording API denial. Missing objects or other
    # HRESULTs never count as a negative-case success.
    [void]$folder.GetTask('PositiveUpdate');[void]$folder.GetTask('PositiveDelete');[void]$folder.GetTask('NegativeUpdate');[void]$folder.GetTask('NegativeDelete')
    $sameSd=[Security.AccessControl.RawSecurityDescriptor]::new($folder.GetSecurityDescriptor(5)).GetSddlForm([Security.AccessControl.AccessControlSections]::Access)
    Attempt-RegularDomain 'positive-update' $true {$folder.RegisterTaskDefinition('PositiveUpdate',$positive,20,$planValue.userSid,$null,3,$null)}
    Attempt-RegularDomain 'positive-delete' $true {$folder.DeleteTask('PositiveDelete',0)}
    Attempt-RegularDomain 'negative-update' $false {$folder.RegisterTaskDefinition('NegativeUpdate',$negative,20,$planValue.userSid,$null,3,$null)}
    Attempt-RegularDomain 'negative-delete' $false {$folder.DeleteTask('NegativeDelete',0)}
    Attempt-RegularDomain 'create-task' $false {$folder.RegisterTaskDefinition('UnexpectedCreate',$create,18,$planValue.userSid,$null,3,$null)}
    Attempt-RegularDomain 'create-folder' $false {$folder.CreateFolder('UnexpectedChild',$null)}
    Attempt-RegularDomain 'set-folder-security' $false {$folder.SetSecurityDescriptor($sameSd,0)}
    Attempt-RegularDomain 'delete-guard-folder' $false {$root.DeleteFolder('CodexManage Regular Guard '+$op,0)}
    $psi=[Diagnostics.ProcessStartInfo]::new((Join-Path ([Environment]::SystemDirectory) 'WindowsPowerShell\v1.0\powershell.exe'),'-NoProfile -NonInteractive -Command "[Threading.Thread]::Sleep(150000)"')
    $psi.UseShellExecute=$false;$psi.CreateNoWindow=$true;$psi.EnvironmentVariables.Remove('PSModulePath')
    $child=[Diagnostics.Process]::Start($psi)
    $self=[Diagnostics.Process]::GetCurrentProcess()
    try{$result=@{format='windows-host-regular-domain-running-v1';status='passed';operationId=$op;userMatches=$true;adminRole=$false;cases=@($cases);pid=$PID;creation=$self.StartTime.ToUniversalTime().ToFileTimeUtc().ToString();childPid=$child.Id;childCreation=$child.StartTime.ToUniversalTime().ToFileTimeUtc().ToString();hostStarted=$false;allSubjectsCovered=$false}}finally{$self.Dispose()}
    Regular-WriteReport (Join-Path (Split-Path -Parent $Plan) 'running.json') $result
    # No trigger can start this fixture after controller cleanup. The controller
    # owns the kill-on-close Job; this independent watchdog is a final bound.
    [Threading.Thread]::Sleep(150000)
} catch {
    if($null -ne $planValue){try{$e=Regular-RootException $_;Regular-WriteReport (Join-Path (Split-Path -Parent $Plan) 'limited-failure.json') @{status='failed';operationId=$planValue.operationId;cases=@($cases);type=$e.GetType().FullName;hresult=('0x{0:X8}' -f ($e.HResult -band 0xffffffffL));line=$_.InvocationInfo.ScriptLineNumber;hostStarted=$false}}catch{}}
    exit 1
} finally {if($null -ne $child){try{if(-not $child.HasExited){$child.Kill()};[void]$child.WaitForExit(3000)}catch{};$child.Dispose()}}
