import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// A reviewed private operator script is an input, never a public source export.
// Refuse every other revision rather than patching a possibly edited recovery flow.
export const formalDiagnosticBaseSha256 =
  '78de186b19c9c0e0537677a8395a6223f211061aa52f5ee0aaf5b95dc4eee98e';

export function diagnosticEnvironmentConfirmed(environment, options) {
  return (
    Array.isArray(environment?.processes) &&
    environment.processes.some(
      (process) =>
        process.hostChild === true && process.checked === true && process.flag === 'enabled-1',
    ) &&
    Array.isArray(options?.processes) &&
    options.processes.length > 0 &&
    options.processes.every(
      (process) =>
        process.checked === true &&
        process.nodeOptionsClassification === 'absent' &&
        process.hasProfilingArgument === false,
    )
  );
}

export function buildFormalDiagnostic(bytes) {
  assert.equal(createHash('sha256').update(bytes).digest('hex'), formalDiagnosticBaseSha256);
  let source = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  const once = (needle, replacement) => {
    assert.equal(source.split(needle).length, 2, 'Reviewed edit must match exactly once');
    source = source.replace(needle, replacement);
  };
  once(
    '{root,operationId,priorRoot,program,state,sources}',
    '{root,operationId,priorRoot,program,state,sources,expectedFormalHostPid,expectedSupervisorPid,protectedProcessIds}',
  );
  once(
    "!env.processes.some(x=>x.hostChild&&x.checked&&x.flag==='1')||options.processes.some(x=>!x.checked||x.nodeOptionsClassification!=='absent'||x.hasProfilingArgument)",
    '!diagnosticEnvironmentConfirmed(env,options)',
  );
  const oldHost = source.match(/system\.formalHostIds\[0\]!==([0-9]+)/)?.[1];
  assert.ok(oldHost);
  once('system.formalHostIds[0]!==' + oldHost, 'system.formalHostIds[0]!==expectedFormalHostPid');
  const cohort = source.match(/for\(const pid of \[([0-9,]+)\]\)if\(!system\.processes/)?.[1];
  assert.ok(cohort);
  const pids = cohort.split(',');
  assert.equal(pids.length, 6);
  assert.equal(pids[0], oldHost);
  once(
    'for(const pid of [' + cohort + '])',
    'for(const pid of [expectedFormalHostPid,expectedSupervisorPid,...protectedProcessIds])',
  );
  once(
    'for(const pid of [' + pids.slice(2).join(',') + '])',
    'for(const pid of protectedProcessIds)',
  );
  const outputs = [
    ...source.matchAll(
      /'outputs\/windows-v28-[a-z0-9-]+-diagnostic-first(?:-numeric)?-20261027\.(?:json|jsonl)'/g,
    ),
  ];
  assert.equal(outputs.length, 2);
  for (const [old] of outputs)
    once(
      old,
      old.includes('-numeric-')
        ? "'outputs/'+operationId+'-diagnostic-first-numeric.jsonl'"
        : "'outputs/'+operationId+'-diagnostic-first.json'",
    );
  const identityGuard = `\nif(!Number.isSafeInteger(expectedFormalHostPid)||expectedFormalHostPid<=0||!Number.isSafeInteger(expectedSupervisorPid)||expectedSupervisorPid<=0||!Array.isArray(protectedProcessIds)||protectedProcessIds.length!==4||protectedProcessIds.some(pid=>!Number.isSafeInteger(pid)||pid<=0)||new Set([expectedFormalHostPid,expectedSupervisorPid,...protectedProcessIds]).size!==6)throw Error('INVALID_EXPECTED_PROCESS_COHORT');\n`;
  const lines = source.split('\n');
  lines.splice(5, 0, diagnosticEnvironmentConfirmed.toString() + identityGuard);
  return lines.join('\n');
}

export async function buildFile(input, output) {
  assert.notEqual(resolve(input), resolve(output), 'Never overwrite the original recovery script');
  const result = buildFormalDiagnostic(await readFile(input));
  await writeFile(output, result, { flag: 'wx', mode: 0o600 });
  return createHash('sha256').update(result).digest('hex');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  assert.equal(process.argv.length, 4, 'Expected reviewed input and a new output file');
  console.log(JSON.stringify({ outputSha256: await buildFile(process.argv[2], process.argv[3]) }));
}
