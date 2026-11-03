import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { qualifyRestart, parseActivation } from './windows-host-regular-activation.mjs';

const id = (n) => `${n.repeat(8)}-${n.repeat(4)}-4${n.repeat(3)}-8${n.repeat(3)}-${n.repeat(12)}`;
const before = () => ({
  machineBinding: 'a'.repeat(64),
  userBinding: 'b'.repeat(64),
  hostId: id('1'),
  bootUtc: '2026-01-01T00:00:00.000Z',
  observedUtc: '2026-01-06T00:00:00.000Z',
  uptimeMs: '432000000',
});
const after = () => ({
  ...before(),
  bootUtc: '2026-01-06T00:01:00.000Z',
  observedUtc: '2026-01-06T00:02:00.000Z',
  uptimeMs: '60000',
});
const shell =
  process.env.AGENT_HUB_TEST_PWSH ??
  (process.platform === 'win32'
    ? join(
        process.env.SystemRoot ?? 'C:\\Windows',
        'System32/WindowsPowerShell/v1.0/powershell.exe',
      )
    : undefined);
// The portable XML fixture compares two literal users. Real Windows SID/name
// resolution stays in production and native Windows checks, never this stand-in.
const portablePrincipals = `
if([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT){
  function Regular-PrincipalSid([string]$UserId){
    if($UserId -cnotmatch '^S-1-5-21-1-2-3-100[12]$'){throw 'FIXTURE_PRINCIPAL_UNMAPPED'}
    return $UserId
  }
}
`;
test('qualified old-boot retirement requires explicit Restart and independently changed boot/counter with the same target', () => {
  assert.deepEqual(qualifyRestart(before(), after(), true), {
    qualifiedOldBootRetirement: true,
    historicalTestPassed: false,
    historicalSubtreeVerified: false,
  });
  assert.throws(() => qualifyRestart(before(), after(), false));
  const cases = [
    { machineBinding: 'c'.repeat(64) },
    { userBinding: 'c'.repeat(64) },
    { hostId: id('2') },
    { uptimeMs: '432060000' },
    { uptimeMs: '18446744073709551616' },
    { uptimeMs: '-1' },
    { bootUtc: before().bootUtc },
    { bootUtc: '2026-01-05T23:00:00.000Z' },
    { observedUtc: '2026-01-01T00:00:00.000Z' },
    { bootUtc: 'unknown' },
  ];
  for (const change of cases)
    assert.throws(() => qualifyRestart(before(), { ...after(), ...change }, true));
  assert.throws(() =>
    qualifyRestart(before(), { ...after(), bootUtc: '2026-01-06T00:01:00.000+00:00' }, true),
  );
  // A changed wall clock without a reset, and sleep/resume in the old boot,
  // must never release an operation whose historical child state is unknown.
  assert.throws(() => qualifyRestart(before(), { ...after(), uptimeMs: '432000100' }, true));
  assert.throws(() =>
    qualifyRestart(
      before(),
      { ...before(), observedUtc: '2026-01-07T00:00:00.000Z', uptimeMs: '518400000' },
      true,
    ),
  );
});
test('activation contract has no implicit restart, arbitrary path, force, duplicate option or target reuse', () => {
  const fields = {
    'installed-program': 'D:\\Host\\program',
    'host-id': id('1'),
    'legacy-operation-id': id('2'),
    'preparation-id': id('3'),
    'operation-id': id('4'),
    'failed-domain-id': id('5'),
    'checkpoint-sha256': 'a'.repeat(64),
    'runtime-sha256': 'b'.repeat(64),
    'failed-domain-sha256': 'c'.repeat(64),
  };
  const args = Object.entries(fields).flatMap(([key, value]) => ['--' + key, value]);
  assert.equal(parseActivation(['prepare', ...args]).values['restart-confirmed'], false);
  assert.equal(
    parseActivation(['continue-prepare', ...args, '--deployment-runtime-sha256', 'd'.repeat(64)])
      .values['deployment-runtime-sha256'],
    'd'.repeat(64),
  );
  assert.equal(
    parseActivation(['activate', ...args, '--restart-confirmed']).values['restart-confirmed'],
    true,
  );
  assert.equal(parseActivation(['qualify-no-restart', ...args]).action, 'qualify-no-restart');
  assert.equal(
    parseActivation(['activate', ...args, '--preserve-legacy-test']).values['preserve-legacy-test'],
    true,
  );
  for (const bad of [
    ['activate', ...args, '--restart-confirmed', '--preserve-legacy-test'],
    ['qualify-no-restart', ...args, '--restart-confirmed'],
    ['prepare', ...args, '--preserve-legacy-test'],
    ['status', ...args, '--preserve-legacy-test'],
    ['prepare', ...args, '--restart-confirmed'],
    ['prepare', ...args, '--deployment-runtime-sha256', 'd'.repeat(64)],
    ['continue-prepare', ...args, '--deployment-runtime-sha256', '..'],
    ['activate', ...args, '--force'],
    ['activate', ...args, '--operation-id', id('6')],
    ['activate', ...args.map((x) => (x === id('4') ? id('2') : x))],
    ['activate', ...args.map((x) => (x === 'D:\\Host\\program' ? 'D:\\Host\\..\\elsewhere' : x))],
  ])
    assert.throws(() => parseActivation(bad));
});
test('policy subprocess rejects a same-boot claim without loading a native activation entry', () => {
  const script = fileURLToPath(new URL('./windows-host-regular-activation.mjs', import.meta.url));
  try {
    execFileSync(process.execPath, [script, '--qualify-restart'], {
      input: JSON.stringify({ before: before(), after: before(), restartConfirmed: true }),
      encoding: 'utf8',
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    assert.fail('same boot was accepted');
  } catch (error) {
    assert.equal(error.status, 1);
    assert.equal(JSON.parse(error.stdout).code, 'HOST_NEW_BOOT_NOT_PROVED');
  }
});
test(
  'task shape tolerates only the planned action/enabled changes, preserving unrelated registration semantics',
  { skip: !shell },
  () => {
    const root = fileURLToPath(new URL('..', import.meta.url));
    const dir = mkdtempSync(join(tmpdir(), 'activation-shape-'));
    const q = (value) => "'" + value.replaceAll("'", "''") + "'";
    try {
      const source = readFileSync(
        join(root, 'scripts/windows-host-regular-activation.ps1'),
        'utf8',
      );
      assert.ok(source.includes('function Activation-Shape'));
      const path = join(dir, 'shape.ps1');
      writeFileSync(
        path,
        `\uFEFF$ErrorActionPreference='Stop'\n. ${q(join(root, 'scripts/windows-host-regular-domain-common.ps1'))}\n${portablePrincipals}\n$t=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile(${q(join(root, 'scripts/windows-host-regular-activation.ps1'))},[ref]$t,[ref]$errors)\nif($errors.Count -ne 0){throw 'activation parse failed'}\nforeach($name in @('Activation-Shape','Activation-Hash','Activation-Definition')){$f=$ast.Find({param($node)$node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name},$false);if($null -eq $f){throw 'function missing'};. ([ScriptBlock]::Create($f.Extent.Text))}\n$xml='<Task xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task"><RegistrationInfo><Description>preserve</Description></RegistrationInfo><Triggers><LogonTrigger/></Triggers><Principals><Principal><UserId>S-1-5-21-1-2-3-1001</UserId><RunLevel>LeastPrivilege</RunLevel></Principal></Principals><Settings><Enabled>true</Enabled><MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy></Settings><Actions><Exec><Command>old.exe</Command><Arguments>old</Arguments><WorkingDirectory>old</WorkingDirectory></Exec></Actions></Task>'\n$base=Activation-Shape $xml\n$allowed=$xml.Replace('old.exe','new.exe').Replace('>old<','>new<').Replace('<Enabled>true','<Enabled>false')\nif((Activation-Shape $allowed) -cne $base){throw 'planned update rejected'}\nforeach($bad in @($xml.Replace('preserve','changed'),$xml.Replace('LogonTrigger','TimeTrigger'),$xml.Replace('LeastPrivilege','HighestAvailable'),$xml.Replace('IgnoreNew','Parallel'),$xml.Replace('1001','1002'))){if((Activation-Shape $bad) -ceq $base){throw 'unrelated change accepted'}}\n'RESTORATION_SHAPE_OK'\n`,
      );
      const out = execFileSync(shell, ['-NoProfile', '-NonInteractive', '-File', path], {
        encoding: 'utf8',
        timeout: 30000,
        maxBuffer: 8192,
      });
      assert.equal(out.trim(), 'RESTORATION_SHAPE_OK');
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  },
);

test(
  'partial preparation remains queryable without a seal, and rollback rejects another installation before mutation',
  { skip: !shell },
  () => {
    const root = fileURLToPath(new URL('..', import.meta.url));
    const directory = mkdtempSync(join(tmpdir(), 'activation-status-'));
    const q = (value) => "'" + value.replaceAll("'", "''") + "'";
    try {
      const script = join(directory, 'status.ps1');
      writeFileSync(
        script,
        `\uFEFF$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile(${q(join(root, 'scripts/windows-host-regular-activation.ps1'))},[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'parse failed'}
foreach($name in @('Activation-RecordedStatus','Activation-CheckInstallation','Activation-Definition','Activation-PreservedScope')){
  $f=$ast.Find({param($node)$node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name},$false)
  . ([ScriptBlock]::Create($f.Extent.Text))
}
$operation=${q(directory)}
$r=@{format='windows-host-regular-activation-v1';hostId='host';operationId='operation';runtimeSha256='runtime';sourceCommit='source'}
$plan=@{program='program';configuration='config';state='state'}
function Activation-Read($Path){return @{Sha256='observed'}}
$s=Activation-RecordedStatus
if($s.status -cne 'incomplete' -or $s.replayAllowed -or -not $s.observationOnly){throw 'partial preparation not observable'}
foreach($pair in @(@('startup-task-intent.json','incomplete'),@('prepared.json','prepared'),@('legacy-preserved.json','unconfirmed'),@('task-write-attempt.json','unconfirmed'),@('active.json','recorded-active'),@('rolled-back.json','rolled-back'))){
  [IO.File]::WriteAllText((Join-Path $operation $pair[0]),'{}')
  $s=Activation-RecordedStatus
  if($s.status -cne $pair[1] -or $s.taskOrStateWritesAttempted -or $s.replayAllowed){throw 'incorrect recorded state'}
}
$route=@{format='windows-host-regular-installation-v1';hostId='host';operationId='operation';runtimeSha256='runtime';sourceCommit='source';program='program';configuration='config';state='state'}
Activation-CheckInstallation $route
foreach($key in @('format','hostId','operationId','runtimeSha256','sourceCommit','program','configuration','state')){
  $bad=$route.Clone();$bad[$key]='changed';$rejected=$false
  try{Activation-CheckInstallation $bad}catch{if($_.Exception.Message -cne 'HOST_ACTIVATION_ROUTE_CHANGED'){throw};$rejected=$true}
  if(-not $rejected){throw 'unrelated installation accepted'}
}
$original='<Task xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task"><RegistrationInfo><Description>unchanged</Description></RegistrationInfo><Settings><Enabled>false</Enabled></Settings></Task>'
$descriptor='O:BAG:BAD:PAI(A;;FA;;;SY)(A;;FA;;;BA)'
$registered=$original.Replace('</RegistrationInfo>','<SecurityDescriptor>'+$descriptor+'</SecurityDescriptor></RegistrationInfo>')
if((Activation-Definition $registered $descriptor) -cne (Activation-Definition $original $descriptor)){throw 'Scheduler descriptor projection rejected'}
if((Activation-Definition ($registered.Replace('unchanged','changed')) $descriptor) -ceq (Activation-Definition $original $descriptor)){throw 'task definition change hidden'}
$rejected=$false
try{Activation-Definition $registered 'different-security'}catch{if($_.Exception.Message -cne 'HOST_ACTIVATION_EMBEDDED_SECURITY_CHANGED'){throw};$rejected=$true}
if(-not $rejected){throw 'embedded security mismatch hidden'}
$hub=$operation
$r.legacyOperationId='legacy'
$r.legacyScope=@{manifestSha256='3b170dd2bc1325266b929c0f3934f5b658e82367f7f1815b1374251c56bee1ea';sourceCommit='35ff3a29e22614b4c97d3332f4dd3d3eb8e902ee';purpose='domain-verification';reviewedExecutionChainVerified=$true;historicalTestPassed=$false;historicalSubtreeVerified=$false;pendingArchived=$false;intentSha256='intent';domainIntentSha256='domain';actionSha256='action'}
$checkpoint=@{files=@{intent=@{sha256='intent'};domainIntent=@{sha256='domain'}}}
$plan.boot=@{machineBinding='machine';userBinding='user';hostId='host'}
function Activation-LegacyFiles([bool]$IncludePending){if(-not $IncludePending){throw 'pending not retained'}}
function Activation-Json($Lease){return @{actionSha256='action';operationId='legacy'}}
function Activation-Witness{return @{machineBinding='machine';userBinding='user';hostId='host'}}
$null=Activation-PreservedScope
$good=$r.legacyScope.Clone()
foreach($key in @('manifestSha256','sourceCommit','purpose','reviewedExecutionChainVerified','historicalTestPassed','historicalSubtreeVerified','pendingArchived','intentSha256','domainIntentSha256','actionSha256')){
  $r.legacyScope=$good.Clone();$r.legacyScope[$key]=if($good[$key] -is [bool]){-not $good[$key]}else{'changed'}
  $rejected=$false
  try{$null=Activation-PreservedScope}catch{$rejected=$true}
  if(-not $rejected){throw 'unbound legacy exception accepted'}
}
'PARTIAL_STATUS_AND_ROUTE_OK'
`,
      );
      assert.equal(
        execFileSync(shell, ['-NoProfile', '-NonInteractive', '-File', script], {
          encoding: 'utf8',
          timeout: 30000,
          maxBuffer: 8192,
        }).trim(),
        'PARTIAL_STATUS_AND_ROUTE_OK',
      );
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  },
);
