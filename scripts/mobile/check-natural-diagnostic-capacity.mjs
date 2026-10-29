// Checks the exact proposal or its adopted bytes in a temporary copy; never mutates source.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
const root = process.cwd();
const local = JSON.parse(readFileSync('.local/mobile-toolchain.json', 'utf8'));
const javaHome = process.env.JAVA_HOME || local.javaHome;
const hooks = JSON.parse(
  readFileSync(
    'docs/plans/android-alignment-20261022/parallel/p9-notifications/api37-am-capacity-hooks.json',
    'utf8',
  ),
);
const sha = (b) => createHash('sha256').update(b).digest('hex');
const temp = mkdtempSync(resolve(tmpdir(), 'p9-capacity-source-'));
const output = resolve('.build/p9-diagnostic-capacity');
mkdirSync(output, { recursive: true });
function run(cmd, args, cwd = root) {
  const r = spawnSync(cmd, args, { cwd, stdio: 'inherit', timeout: 120_000 });
  assert.equal(r.status, 0, 'Capacity auxiliary failed');
}
try {
  for (const [path, item] of Object.entries(hooks.patches)) {
    const b = readFileSync(path);
    const inputSha = sha(b);
    assert(
      inputSha === item.baseSha256 || inputSha === item.proposedSha256,
      'Capacity source must match the exact reviewed baseline or adopted proposal',
    );
    mkdirSync(dirname(resolve(temp, path)), { recursive: true });
    writeFileSync(resolve(temp, path), b);
    if (inputSha === item.baseSha256)
      run(
        'git',
        [
          'apply',
          resolve('docs/plans/android-alignment-20261022/parallel/p9-notifications', item.path),
        ],
        temp,
      );
    assert.equal(sha(readFileSync(resolve(temp, path))), item.proposedSha256);
  }
  const sources = [
    resolve(
      temp,
      'apps/android-inbox-examiner/src/main/java/com/agenthub/android/NaturalProcessVisibility.java',
    ),
    resolve('scripts/mobile/fixtures/NaturalVisibilityVectors.java'),
    resolve('scripts/mobile/fixtures/NaturalDiagnosticCapacityVectors.java'),
  ];
  run(resolve(javaHome, 'bin/javac'), ['--release', '17', '-d', output, ...sources]);
  run(resolve(javaHome, 'bin/java'), [
    '-cp',
    output,
    'com.agenthub.android.NaturalVisibilityVectors',
  ]);
  run(resolve(javaHome, 'bin/java'), [
    '-cp',
    output,
    'com.agenthub.android.NaturalDiagnosticCapacityVectors',
  ]);
  console.log(
    JSON.stringify({
      scope:
        'Kind-bounded RWE reviewed source; synthetic parser/counter boundaries only; no Android/native proof',
      sourceSha256: Object.fromEntries(sources.map((p) => [p, sha(readFileSync(p))])),
    }),
  );
} finally {
  rmSync(temp, { recursive: true, force: true });
}
