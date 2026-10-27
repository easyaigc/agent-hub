import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import {
  buildFile,
  buildFormalDiagnostic,
  diagnosticEnvironmentConfirmed,
} from './windows-formal-diagnostic-builder.mjs';

const environment = { processes: [{ hostChild: true, checked: true, flag: 'enabled-1' }] };
const options = {
  processes: [{ checked: true, nodeOptionsClassification: 'absent', hasProfilingArgument: false }],
};

test('accepts the observed classified environment receipt and rejects raw or unknown values', () => {
  assert.equal(diagnosticEnvironmentConfirmed(environment, options), true);
  for (const flag of ['1', 'absent', 'enabled-0', '', null])
    assert.equal(
      diagnosticEnvironmentConfirmed(
        { processes: [{ hostChild: true, checked: true, flag }] },
        options,
      ),
      false,
    );
  assert.equal(
    diagnosticEnvironmentConfirmed(
      { processes: [{ hostChild: false, checked: true, flag: 'enabled-1' }] },
      options,
    ),
    false,
  );
  assert.equal(
    diagnosticEnvironmentConfirmed(
      { processes: [{ hostChild: true, checked: false, flag: 'enabled-1' }] },
      options,
    ),
    false,
  );
});

test('missing or unverified options cannot authorize a diagnostic request', () => {
  for (const value of [
    undefined,
    {},
    { processes: [] },
    { processes: [{ ...options.processes[0], checked: false }] },
    { processes: [{ ...options.processes[0], hasProfilingArgument: true }] },
    { processes: [{ ...options.processes[0], nodeOptionsClassification: 'present' }] },
  ])
    assert.equal(diagnosticEnvironmentConfirmed(environment, value), false);
});

test('an unreviewed source cannot produce or overwrite a recovery script', async () => {
  assert.throws(() => buildFormalDiagnostic(Buffer.from('unreviewed source')));
  const directory = await mkdtemp(join(tmpdir(), 'formal-diagnostic-builder-'));
  try {
    const input = join(directory, 'input.mjs'),
      output = join(directory, 'output.mjs');
    await writeFile(input, 'unreviewed source');
    await writeFile(output, 'existing work');
    await assert.rejects(buildFile(input, output));
    assert.equal(await readFile(input, 'utf8'), 'unreviewed source');
    assert.equal(await readFile(output, 'utf8'), 'existing work');
    await assert.rejects(buildFile(input, input));
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
