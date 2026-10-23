import fs from 'node:fs';
import { Worker, isMainThread, parentPort, workerData } from 'node:worker_threads';
import {
  inspectWindowsPipe,
  inspectWindowsTraceInstance,
  protectWindowsStateDirectory,
} from '../packages/codex-adapter/dist/desktop-platform.js';
import { createWindowsTraceBackend } from '../packages/codex-adapter/dist/desktop-trace-native.js';
import { registerWindowsTraceTransport } from '../packages/codex-adapter/dist/desktop-private-state-native.js';
import {
  getWindowsProcessGate,
  registerWindowsProcessWorker,
  withWindowsProcessCreation,
} from '../packages/codex-adapter/dist/windows-process.js';
import { join, resolve } from 'node:path';
import { execFileSync } from 'node:child_process';
import { windowsPowerShellEnvironment } from '../packages/codex-adapter/dist/desktop-platform.js';

if (isMainThread) {
  registerWindowsTraceTransport();
  const root = resolve(process.argv[2]);
  fs.mkdirSync(root, { recursive: true });
  protectWindowsStateDirectory(root);
  const before = [];
  for (let i = 0; i < 3; i++) {
    const at = performance.now();
    const value = inspectWindowsTraceInstance();
    before.push({
      ms: performance.now() - at,
      pid: value.pid,
      traceEnabled: value.traceRoot !== null,
      executorCount: value.executors.length,
    });
  }
  const worker = new Worker(new URL(import.meta.url), {
    workerData: { root, windowsProcessGate: getWindowsProcessGate() },
  });
  registerWindowsProcessWorker(worker);
  const result = await new Promise((resolve, reject) => {
    worker.once('message', resolve);
    worker.once('error', reject);
  });
  const fileOwnership = JSON.parse(
    withWindowsProcessCreation(30000, (timeout) =>
      execFileSync(
        join(process.env.SystemRoot, 'System32/WindowsPowerShell/v1.0/powershell.exe'),
        [
          '-NoProfile',
          '-NonInteractive',
          '-Command',
          "$a=Get-Acl -LiteralPath $env:TRACE_NATIVE_OWNER_PATH;$u=[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value;$o=$a.GetOwner([System.Security.Principal.SecurityIdentifier]).Value;$r=@($a.GetAccessRules($true,$true,[System.Security.Principal.SecurityIdentifier]));@{owner=if($o -eq $u){'current-user'}elseif($o -eq 'S-1-5-32-544'){'Administrators'}else{'other'};inheritedUserGrant=@($r|Where-Object {$_.IdentityReference.Value -eq $u -and $_.IsInherited}).Count -gt 0;untrustedAllows=@($r|Where-Object {$_.AccessControlType -eq 'Allow' -and $_.IdentityReference.Value -notin @($u,'S-1-5-18','S-1-5-32-544')}).Count}|ConvertTo-Json -Compress",
        ],
        {
          encoding: 'utf8',
          windowsHide: true,
          timeout,
          env: {
            ...windowsPowerShellEnvironment(),
            TRACE_NATIVE_OWNER_PATH: join(root, 'ordinary-created.txt'),
          },
        },
      ),
    ),
  );
  fs.writeFileSync(
    join(root, 'probe-and-token-private.json'),
    JSON.stringify({ before, ...result, fileOwnership }, null, 2),
  );
  console.log(JSON.stringify({ before, ...result, fileOwnership }));
} else {
  const at = performance.now(),
    identity = inspectWindowsPipe();
  const native = await createWindowsTraceBackend(identity, workerData.root);
  const coldMs = performance.now() - at;
  try {
    const samples = [];
    for (let i = 0; i < 10; i++) {
      const t = performance.now(),
        value = native.backend.inspect();
      samples.push({
        ms: performance.now() - t,
        pid: value.pid,
        traceEnabled: value.traceRoot !== null,
        executorCount: value.executors.length,
      });
    }
    const tokens = native.tokens();
    native.budget(0);
    let budgetRejected = false;
    try {
      native.backend.inspect();
    } catch {
      budgetRejected = true;
    }
    native.resetBudget();
    const recoveredAfterBudget = native.backend.inspect().pid === identity.pid;
    fs.writeFileSync(join(workerData.root, 'ordinary-created.txt'), 'controlled synthetic bytes');
    const file = native.backend.snapshot(
      workerData.root,
      join(workerData.root, 'ordinary-created.txt'),
      100,
    );
    parentPort.postMessage({
      coldMs,
      after: samples,
      tokens,
      budgetRejected,
      recoveredAfterBudget,
      ordinaryUnmodifiedOwnerFileRead: file.bytes.toString() === 'controlled synthetic bytes',
    });
  } finally {
    native.close();
    parentPort.close();
  }
}
