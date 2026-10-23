import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import ts from 'typescript';

// Recover independent native cases with identical inputs. Preserve all failed
// attempts; only animation-notification timeouts permit a bounded retry.
const basePath = process.argv[2];
assert(basePath, 'Pass a closed baseline report with individual native case results');
const read = async (path) => JSON.parse(await readFile(path, 'utf8'));
const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
const base = await read(basePath);
assert.notEqual(base.status, 'running');
const phase = process.env.MOBILE_PHASE ?? base.phase;
assert(/^A[1-8]$/.test(phase));
assert.equal(base.phase, phase);
assert.equal(process.env.IOS_SIMULATOR_ID, base.simulator);
const baseDirectory = dirname(basePath);
const input = await read(baseDirectory + '/tooling-inputs.json');
const sourceRoots = [
  'apps/ios',
  'packages/protocol',
  'packages/mobile-core',
  'gradle',
  'gradlew',
  'gradlew.bat',
  'settings.gradle.kts',
  'build.gradle.kts',
  'gradle.properties',
  'scripts/mobile/build-ios-core.sh',
  'scripts/mobile/gradle.mjs',
];
async function fingerprint() {
  const files = execFileSync('git', ['ls-files', '-co', '--exclude-standard', ...sourceRoots], {
    encoding: 'utf8',
  })
    .trim()
    .split('\n')
    .filter(Boolean)
    .sort();
  const digest = createHash('sha256');
  for (const file of new Set(files))
    digest
      .update(file + '\0')
      .update(await readFile(file))
      .update('\0');
  return digest.digest('hex');
}
assert.equal(await fingerprint(), base.sourceSha256);
assert.equal(input.sourceSha256, base.sourceSha256);
const common = [
  'scripts/mobile/ios-baseline.mjs',
  'scripts/mobile/export-ios-baseline.mjs',
  'scripts/mobile/native-ios-cases.mjs',
  'package.json',
  'pnpm-lock.yaml',
  'design/mobile/tokens.json',
];
const captured = new Map(input.inputs.map((entry) => [entry.file, entry.sha256]));
for (const file of common) assert.equal(hash(await readFile(file)), captured.get(file));
const runner = ts.createSourceFile(
  common[0],
  await readFile(common[0], 'utf8'),
  ts.ScriptTarget.Latest,
);
const declaration = runner.statements
  .filter(ts.isVariableStatement)
  .flatMap((statement) => [...statement.declarationList.declarations])
  .find((entry) => entry.name.getText(runner) === 'nativeTests');
assert(ts.isArrayLiteralExpression(declaration.initializer));
const tests = declaration.initializer.elements.map((element) => {
  assert(ts.isStringLiteral(element));
  return element.text;
});
assert.equal(new Set(tests).size, tests.length);
const summary = (path) =>
  JSON.parse(
    execFileSync(
      'xcrun',
      ['xcresulttool', 'get', 'test-results', 'summary', '--path', path, '--compact'],
      { encoding: 'utf8' },
    ),
  );
const previous = await read(baseDirectory + '/native-cases/index.json');
const device = summary(previous[0].resultBundle).devicesAndConfigurations[0].device;
assert.equal(device.deviceId, base.simulator);
function passed(result, expected = 1) {
  return (
    result.result === 'Passed' &&
    result.totalTestCount === expected &&
    result.passedTests === expected &&
    result.failedTests === 0 &&
    result.skippedTests === 0 &&
    result.expectedFailures === 0 &&
    result.devicesAndConfigurations.length === 1 &&
    result.devicesAndConfigurations[0].device.deviceId === base.simulator &&
    result.devicesAndConfigurations[0].device.osBuildNumber === device.osBuildNumber
  );
}
const animationTimeout = (statistics, log) =>
  statistics.failedTests === 1 &&
  statistics.skippedTests === 0 &&
  statistics.testFailures?.length === 1 &&
  /exceeded.*execution time allowance/i.test(statistics.testFailures[0].failureText) &&
  log.includes('App animations complete notification not received');
const output = '.local/mobile-acceptance/' + phase + '/native-recovery-' + Date.now();
await mkdir(output, { recursive: true });
const self = 'scripts/mobile/recover-native-ios-cases.mjs';
const toolSha256 = hash(await readFile(self));
await writeFile(
  output + '/tooling-inputs.json',
  JSON.stringify(
    {
      ...input,
      capturedAt: new Date().toISOString(),
      inputs: [...input.inputs, { file: self, sha256: toolSha256 }],
    },
    null,
    2,
  ) + '\n',
);
const cases = [];
const attempts = previous.map((entry) => ({ ...entry, evidenceStatus: 'original-attempt' }));
const report = {
  phase,
  scope: 'native-independent-case-recovery',
  sourceSha256: base.sourceSha256,
  simulator: base.simulator,
  device: base.device,
  output,
  groups: [],
  status: 'running',
  baseReport: basePath,
  recoveryToolSha256: toolSha256,
  cases,
  attempts,
};
const save = () => writeFile(output + '/report.json', JSON.stringify(report, null, 2) + '\n');
await save();
console.log('Recovery report:', output + '/report.json');
try {
  for (const test of tests) {
    const old = previous.find((entry) => entry.test === test);
    if (old?.exitCode === 0 && passed(summary(old.resultBundle))) {
      cases.push({ test, result: old.resultBundle, evidenceStatus: 'reused-matching' });
      await save();
      continue;
    }
    if (old)
      assert(
        animationTimeout(
          summary(old.resultBundle),
          await readFile(baseDirectory + '/native-cases/' + test + '.log', 'utf8'),
        ),
        'Original functional or infrastructure failures require diagnosis, not automatic retry',
      );
    let completed = false;
    for (let attempt = 1; attempt <= 3; attempt++) {
      assert.equal(await fingerprint(), base.sourceSha256);
      const result = `${output}/${test}-${attempt}.xcresult`;
      const logPath = `${output}/${test}-${attempt}.log`;
      let log = '';
      const child = spawn(process.execPath, ['scripts/mobile/native-ios-cases.mjs', test], {
        env: {
          ...process.env,
          IOS_DERIVED_DATA_PATH: '.build/mobile/' + phase + '/ios',
          IOS_RESULT_BUNDLE_PATH: result,
        },
        stdio: ['ignore', 'pipe', 'pipe'],
      });
      for (const stream of [child.stdout, child.stderr])
        stream.on('data', (bytes) => (log += bytes));
      const exitCode = await new Promise((resolve, reject) => {
        child.once('error', reject);
        child.once('close', resolve);
      });
      await writeFile(logPath, log);
      const single = await read(result.replace(/\.xcresult$/, '-cases') + '/index.json');
      assert.equal(single.length, 1);
      assert.equal(single[0].test, test);
      const actual = single[0].resultBundle;
      const statistics = summary(actual);
      attempts.push({ test, attempt, exitCode, result: actual, log: logPath, summary: statistics });
      await save();
      if (exitCode === 0 && passed(statistics)) {
        cases.push({ test, result: actual, evidenceStatus: 'passed' });
        await save();
        console.log('Passed native recovery case:', test);
        completed = true;
        break;
      }
      assert(
        animationTimeout(statistics, log),
        'Functional assertions or infrastructure failures are not automatically retried',
      );
      console.log('Retained animation timeout:', test, 'attempt', attempt);
    }
    assert(completed, 'Native case still failed after bounded animation-timeout recovery: ' + test);
  }
  assert.equal(cases.length, tests.length);
  assert.equal(await fingerprint(), base.sourceSha256);
  for (const file of common) assert.equal(hash(await readFile(file)), captured.get(file));
  assert.equal(hash(await readFile(self)), toolSha256);
  const result = output + '/native.xcresult';
  execFileSync('xcrun', [
    'xcresulttool',
    'merge',
    '--output-path',
    result,
    ...cases.map((entry) => entry.result),
  ]);
  const statistics = summary(result);
  assert(passed(statistics, tests.length));
  report.groups.push({
    group: 'native',
    expected: tests.length,
    passed: true,
    exitCode: 0,
    result,
    summary: statistics,
    recoveryToolSha256: toolSha256,
    caseEvidence: cases,
  });
  report.status = 'passed';
  await save();
  console.log('Complete native recovery:', tests.length, 'passed, zero skips');
} catch (error) {
  report.status = 'failed';
  await save();
  throw error;
}
