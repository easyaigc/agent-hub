#!/usr/bin/env node
// Controlled, synthetic comparison. Run after both modules are built:
// BASELINE_RUNTIME_MODULE=/absolute/path/runtime-persistence.js node scripts/runtime-persistence-benchmark.mjs
import { spawn } from 'node:child_process';
import { appendFile, realpath } from 'node:fs/promises';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash, randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
const opts = { ticks: 35, intervalMs: 850, coldMiB: 512, warmupTicks: 4, changeTicks: 3 };
let output = resolve(root, '../persistence-benchmark.jsonl');
let single;
for (let i = 0; i < args.length; i++) {
  const key = args[i];
  const value = args[++i];
  if (value === undefined) throw Error(`Missing value for ${key}`);
  if (key === '--output') output = resolve(value);
  else if (key === '--module') single = resolve(value);
  else if (key === '--ticks') opts.ticks = Number(value);
  else if (key === '--interval-ms') opts.intervalMs = Number(value);
  else if (key === '--cold-mib') opts.coldMiB = Number(value);
  else if (key === '--warmup-ticks') opts.warmupTicks = Number(value);
  else if (key === '--change-ticks') opts.changeTicks = Number(value);
  else throw Error(`Unknown option ${key}`);
}
for (const [key, value] of Object.entries(opts)) {
  if (!Number.isSafeInteger(value) || value < (key === 'coldMiB' || key === 'warmupTicks' ? 0 : 1))
    throw Error(`Invalid ${key}`);
}
if (output === root || output.startsWith(root + '/'))
  throw Error('Output must be outside worktree');
const candidate = resolve(root, 'packages/codex-adapter/dist/runtime-persistence.js');
const snapshotModule = resolve(root, 'packages/codex-adapter/dist/persistence-snapshots.js');
if (!single && !process.env.BASELINE_RUNTIME_MODULE)
  throw Error('BASELINE_RUNTIME_MODULE required for comparison');
const modules = single
  ? [['single', single]]
  : [
      ['baseline', resolve(process.env.BASELINE_RUNTIME_MODULE)],
      ['candidate', candidate],
    ];
const runId = randomUUID();
let previous;
for (const [label, path] of modules) {
  const modulePath = await realpath(path);
  const fingerprint = createHash('sha256')
    .update(await readFile(modulePath))
    .digest('hex');
  const snapshotSha256 = createHash('sha256')
    .update(await readFile(snapshotModule))
    .digest('hex');
  console.error(`Starting ${label}: ${modulePath}`);
  const child = spawn(
    process.execPath,
    [
      '--expose-gc',
      join(root, 'scripts/fixtures/runtime-persistence-benchmark-child.mjs'),
      modulePath,
      JSON.stringify(opts),
    ],
    { stdio: ['ignore', 'pipe', 'inherit'] },
  );
  let stdout = '';
  child.stdout.setEncoding('utf8');
  child.stdout.on('data', (chunk) => {
    stdout += chunk;
  });
  const code = await new Promise((res, rej) => {
    child.on('error', rej);
    child.on('close', res);
  });
  if (code !== 0) throw Error(`${label} child exited ${code}; no success record appended`);
  const result = JSON.parse(stdout);
  const after = createHash('sha256')
    .update(await readFile(modulePath))
    .digest('hex');
  assertUnchanged(fingerprint, after);
  assertUnchanged(
    snapshotSha256,
    createHash('sha256')
      .update(await readFile(snapshotModule))
      .digest('hex'),
  );
  if (previous && previous !== result.equivalence.stateSha256)
    throw Error('Baseline and candidate results differ');
  previous = result.equivalence.stateSha256;
  await appendFile(
    output,
    JSON.stringify({
      runId,
      label,
      modulePath,
      moduleSha256: fingerprint,
      snapshotSha256,
      ...result,
    }) + '\n',
    { mode: 0o600 },
  );
  console.error(`Completed ${label}; appended ${output}`);
}
function assertUnchanged(before, after) {
  if (before !== after) throw Error('Runtime module changed during measurement; discard run');
}
