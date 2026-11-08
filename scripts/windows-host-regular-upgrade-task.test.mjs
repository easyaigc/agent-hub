import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { resolve, join } from 'node:path';

const executable =
  process.platform === 'win32'
    ? join(process.env.SystemRoot, 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe')
    : (process.env.AGENT_HUB_TEST_PWSH ?? 'pwsh');
const script = resolve('scripts/windows-host-regular-upgrade-task.ps1').replaceAll("'", "''");
const run = (command) =>
  spawnSync(executable, ['-NoProfile', '-NonInteractive', '-Command', command], {
    encoding: 'utf8',
    windowsHide: true,
    timeout: 30000,
  });

test(
  'XML rewrite changes only action and enabled, and rejects duplicate actions or mismatched security',
  { skip: process.platform !== 'win32' },
  () => {
    const command = `\n$ErrorActionPreference='Stop'\n. '${script}'\n$xml='<Task xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task"><RegistrationInfo><SecurityDescriptor>same</SecurityDescriptor><Description>preserve</Description></RegistrationInfo><Principals><Principal><UserId>S-1-5-21-1</UserId></Principal></Principals><Triggers><LogonTrigger><Enabled>true</Enabled></LogonTrigger></Triggers><Settings><Enabled>true</Enabled><Priority>4</Priority></Settings><Actions><Exec><Command>old.exe</Command><Arguments>old args</Arguments><WorkingDirectory>C:\\old</WorkingDirectory></Exec></Actions></Task>'\n$target=@{launcher='new.exe';arguments='new args';workingDirectory='C:\\new'}\n$v=Upgrade-Xml $xml 'same' $target $false\nif($v -notmatch '<Command>new.exe</Command>' -or $v -notmatch '<Arguments>new args</Arguments>' -or $v -notmatch '<WorkingDirectory>C:\\\\new</WorkingDirectory>' -or $v -notmatch '<Priority>4</Priority>' -or $v -notmatch '<Description>preserve</Description>' -or $v -notmatch '<LogonTrigger><Enabled>true</Enabled></LogonTrigger>' -or $v -notmatch '<Settings><Enabled>false</Enabled>'){throw 'FIXTURE_ASSERTION'}\n$rejected=0;try{Upgrade-Xml $xml 'other' $target $false}catch{$rejected++}\ntry{Upgrade-Xml ($xml.Replace('</Actions>','<Exec><Command>x</Command></Exec></Actions>')) 'same' $target $false}catch{$rejected++}\nif($rejected -ne 2){throw 'FIXTURE_NOT_REJECTED'}\n`;
    const result = run(command);
    assert.equal(result.status, 0, result.stderr);
  },
);

test(
  'real Scheduler fixture round-trips disabled XML and SDDL without touching formal task',
  { skip: process.platform !== 'win32' },
  () => {
    const fixture = 'AgentHub Upgrade Fixture ' + crypto.randomUUID();
    const command = `
$ErrorActionPreference='Stop'
. '${script}'
$name='${fixture}';$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$service=New-Object -ComObject 'Schedule.Service';$service.Connect();$root=$service.GetFolder('\\')
$d=$service.NewTask(0);$d.Principal.UserId=$sid;$d.Principal.LogonType=3;$d.Principal.RunLevel=0;$d.Settings.Enabled=$false;$d.Settings.MultipleInstances=2
$a=$d.Actions.Create(0);$a.Path=Join-Path $env:SystemRoot 'System32\\WindowsPowerShell\\v1.0\\powershell.exe';$a.Arguments='-NoProfile -NonInteractive -WindowStyle Hidden -Command exit 0';$a.WorkingDirectory=Join-Path $env:SystemRoot 'System32'
$owned=$false
try {
 $task=$root.RegisterTaskDefinition($name,$d,2,$sid,$null,3,$null);$owned=$true
 $before=[string]$task.Definition.XmlText;$sd=[string]$task.GetSecurityDescriptor(7)
 $old=@{launcher=$a.Path;arguments=$a.Arguments;workingDirectory=$a.WorkingDirectory};$new=@{launcher=$a.Path;arguments='-NoProfile -NonInteractive -WindowStyle Hidden -Command exit 1';workingDirectory=$a.WorkingDirectory}
 if(-not (Upgrade-SameAction $task $old) -or $task.Enabled -or $task.GetInstances(0).Count){throw 'FIXTURE_PRECONDITION'}
 $xml=Upgrade-Xml $before $sd $new $false;$next=$service.NewTask(0);$next.XmlText=$xml
 $task=$root.RegisterTaskDefinition($name,$next,20,$sid,$null,3,$sd)
 if(-not (Upgrade-SameAction $task $new) -or $task.Enabled -or [string]$task.GetSecurityDescriptor(7) -cne $sd -or (Upgrade-Xml ([string]$task.Definition.XmlText) $sd $new $false) -cne $xml){throw 'FIXTURE_APPLY'}
  $beforeRun=$task.LastRunTime;$task.Enabled=$true;[void]$task.Run($null)
  $clock=[Diagnostics.Stopwatch]::StartNew();$finished=$false
  do {
   $task=$root.GetTask($name)
   $finished=$task.LastRunTime -gt $beforeRun -and $task.GetInstances(0).Count -eq 0 -and $task.LastTaskResult -eq 1
   if(-not $finished){Start-Sleep -Milliseconds 100}
  } while(-not $finished -and $clock.ElapsedMilliseconds -lt 10000)
  if(-not $finished){throw 'FIXTURE_RUN'};$task.Enabled=$false
 $oldXml=Upgrade-Xml $before $sd $old $false;$oldDef=$service.NewTask(0);$oldDef.XmlText=$oldXml
 $task=$root.RegisterTaskDefinition($name,$oldDef,20,$sid,$null,3,$sd)
 if(-not (Upgrade-SameAction $task $old) -or $task.Enabled -or [string]$task.GetSecurityDescriptor(7) -cne $sd -or (Upgrade-Xml ([string]$task.Definition.XmlText) $sd $old $false) -cne $oldXml){throw 'FIXTURE_RESTORE'}
} finally {
 if($owned){$task=$root.GetTask($name);if($task.Enabled -or $task.GetInstances(0).Count -ne 0 -or -not (Upgrade-SameAction $task $old) -or (Upgrade-Sid $task.Definition.Principal.UserId) -cne $sid){throw 'FIXTURE_CLEANUP_UNCONFIRMED'};$root.DeleteTask($name,0)}
}
`;
    const result = run(command);
    assert.equal(result.status, 0, `fixture=${fixture} ${result.stderr}`);
  },
);

test(
  'runtime command matching is exact for child, supervisor and launcher',
  { skip: process.platform !== 'win32' },
  () => {
    const command = `
$ErrorActionPreference='Stop'
. '${script}'
$node='C:\\Program Files\\Runtime\\node.exe';$worker='C:\\Program Files\\Runtime\\worker.mjs';$config='C:\\Private\\host.json';$launcher='C:\\Private\\host-launcher.exe'
$child='"'+$node+'" "'+$worker+'" "'+$config+'" --host-child'
$supervisor='"'+$node+'" "'+$worker+'" "'+$config+'"'
$launch='"'+$launcher+'" '+$supervisor
if($child -notmatch (Upgrade-CommandPattern @($node,$worker,$config) '\\s+--host-child')){throw 'CHILD_MISMATCH'}
if($supervisor -notmatch (Upgrade-CommandPattern @($node,$worker,$config))){throw 'SUPERVISOR_MISMATCH'}
if($launch -notmatch (Upgrade-CommandPattern @($launcher,$node,$worker,$config))){throw 'LAUNCHER_MISMATCH'}
if(($child+' --extra') -match (Upgrade-CommandPattern @($node,$worker,$config) '\\s+--host-child')){throw 'EXTRA_ACCEPTED'}
`;
    const result = run(command);
    assert.equal(result.status, 0, result.stderr);
  },
);
