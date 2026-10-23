import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import ts from 'typescript';

// A0 closure may reuse successful independent groups only with identical app,
// protocol, dependencies and that group's fixture. Failed runs remain untouched.
const paths = process.argv.slice(2);
const phase = process.env.MOBILE_PHASE ?? 'A0';
assert(/^A[0-8]$/.test(phase));
assert(paths.length, 'Pass the A0 reports whose groups form the complete baseline');
const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
const runnerPath = 'scripts/mobile/ios-baseline.mjs';
const runner = ts.createSourceFile(
  runnerPath,
  await readFile(runnerPath, 'utf8'),
  ts.ScriptTarget.Latest,
);
function initializer(name) {
  for (const statement of runner.statements) {
    if (!ts.isVariableStatement(statement)) continue;
    const declaration = statement.declarationList.declarations.find(
      (entry) => entry.name.getText(runner) === name,
    );
    if (declaration) return declaration.initializer;
  }
  assert.fail('Missing baseline catalog: ' + name);
}
const native = initializer('nativeTests');
const fixtures = initializer('fixtures');
assert(ts.isArrayLiteralExpression(native) && ts.isObjectLiteralExpression(fixtures));
const catalog = new Map([['native', { expected: native.elements.length }]]);
for (const property of fixtures.properties) {
  assert(ts.isPropertyAssignment(property) && ts.isArrayLiteralExpression(property.initializer));
  const [file, count] = property.initializer.elements;
  assert(ts.isStringLiteral(file) && ts.isNumericLiteral(count));
  catalog.set(property.name.getText(runner), {
    file: 'scripts/' + file.text + '-ios-fixture.mjs',
    expected: Number(count.text),
  });
}
const sourceRoots = ['apps/ios', 'packages/protocol'];
if (phase !== 'A0')
  sourceRoots.push(
    'packages/mobile-core',
    'gradle',
    'gradlew',
    'gradlew.bat',
    'settings.gradle.kts',
    'build.gradle.kts',
    'gradle.properties',
    'scripts/mobile/build-ios-core.sh',
    'scripts/mobile/gradle.mjs',
  );
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
const sourceSha256 = digest.digest('hex');
const candidates = [];
for (const path of paths) {
  const report = JSON.parse(await readFile(path, 'utf8'));
  assert.equal(report.phase, phase);
  assert.notEqual(report.status, 'running', 'Wait for the runner and its child processes to close');
  assert.equal(
    report.sourceSha256,
    sourceSha256,
    'App/protocol changes require new simulator evidence',
  );
  const inputs = JSON.parse(await readFile(dirname(path) + '/tooling-inputs.json', 'utf8'));
  assert.equal(inputs.sourceSha256, sourceSha256);
  candidates.push({
    path,
    report,
    inputs: new Map(inputs.inputs.map((input) => [input.file, input.sha256])),
  });
}
const simulator = candidates[0].report.simulator;
assert(
  candidates.every((entry) => entry.report.simulator === simulator),
  'Do not mix simulator environments',
);
const fixtureFiles = new Set([...catalog.values()].map((entry) => entry.file).filter(Boolean));
const common = [
  runnerPath,
  'scripts/mobile/export-ios-baseline.mjs',
  'scripts/mobile/native-ios-cases.mjs',
  'scripts/mobile/android-read-fixture.mjs',
  'scripts/mobile/generate-design-tokens.mjs',
  'package.json',
  'pnpm-lock.yaml',
  'design/mobile/tokens.json',
];
const current = new Map();
for (const file of [...common, ...fixtureFiles]) current.set(file, hash(await readFile(file)));
const groups = [];
for (const [group, definition] of catalog) {
  const required = [...common, ...(definition.file ? [definition.file] : [])];
  const matches = candidates.flatMap((candidate) =>
    candidate.report.groups
      .filter(
        (result) =>
          result.group === group &&
          result.passed &&
          result.expected === definition.expected &&
          required.every((file) => candidate.inputs.get(file) === current.get(file)),
      )
      .map((result) => ({ candidate, result })),
  );
  assert(matches.length, 'No passing evidence with current inputs for ' + group);
  const { candidate, result } = matches.at(-1);
  if (result.recoveryToolSha256) {
    assert.equal(group, 'native');
    const recoveryTool = 'scripts/mobile/recover-native-ios-cases.mjs';
    const recoveryHash = hash(await readFile(recoveryTool));
    assert.equal(result.recoveryToolSha256, recoveryHash);
    assert.equal(candidate.inputs.get(recoveryTool), recoveryHash);
    const origin = JSON.parse(await readFile(candidate.report.baseReport, 'utf8'));
    assert.notEqual(origin.status, 'running');
    assert.equal(origin.sourceSha256, sourceSha256);
    assert.equal(origin.simulator, simulator);
    const originInputs = JSON.parse(
      await readFile(dirname(candidate.report.baseReport) + '/tooling-inputs.json', 'utf8'),
    );
    const originHashes = new Map(originInputs.inputs.map((entry) => [entry.file, entry.sha256]));
    for (const file of common) assert.equal(originHashes.get(file), current.get(file));
    const expectedCases = new Set(native.elements.map((entry) => entry.text));
    assert.equal(result.caseEvidence.length, expectedCases.size);
    for (const entry of result.caseEvidence) {
      assert(expectedCases.delete(entry.test), 'Missing, duplicate or unexpected native case');
      assert(['passed', 'reused-matching'].includes(entry.evidenceStatus));
      const actual = JSON.parse(
        execFileSync(
          'xcrun',
          ['xcresulttool', 'get', 'test-results', 'summary', '--path', entry.result, '--compact'],
          { encoding: 'utf8' },
        ),
      );
      assert.equal(actual.result, 'Passed');
      assert.equal(actual.totalTestCount, 1);
      assert.equal(actual.passedTests, 1);
      for (const field of ['failedTests', 'skippedTests', 'expectedFailures'])
        assert.equal(actual[field], 0);
      assert.equal(actual.devicesAndConfigurations[0]?.device.deviceId, simulator);
      assert.equal(actual.devicesAndConfigurations[0]?.device.osBuildNumber, '24A434');
    }
    assert.equal(expectedCases.size, 0);
  }
  const summary = JSON.parse(
    execFileSync(
      'xcrun',
      ['xcresulttool', 'get', 'test-results', 'summary', '--path', result.result, '--compact'],
      { encoding: 'utf8' },
    ),
  );
  assert.equal(result.exitCode, 0);
  assert.equal(summary.result, 'Passed');
  assert.equal(summary.totalTestCount, definition.expected);
  assert.equal(summary.passedTests, definition.expected);
  for (const field of ['failedTests', 'skippedTests', 'expectedFailures'])
    assert.equal(summary[field], 0);
  const environment = summary.devicesAndConfigurations[0]?.device;
  assert.equal(environment?.deviceId, simulator);
  assert.equal(environment?.osBuildNumber, '24A434');
  groups.push({
    ...result,
    summary,
    evidenceStatus: candidate.report.status === 'passed' ? 'passed' : 'reused-matching',
    sourceReport: candidate.path,
    inputs: required.map((file) => ({ file, sha256: current.get(file) })),
  });
}
const output = '.local/mobile-acceptance/' + phase + '/complete-' + Date.now();
await mkdir(output, { recursive: true });
const report = {
  phase,
  scope: 'complete-baseline',
  evidenceMode: 'matching-inputs-aggregate',
  sourceSha256,
  simulator,
  device: candidates[0].report.device,
  output,
  createdAt: new Date().toISOString(),
  sources: paths,
  groups,
  status: 'passed',
  closureToolSha256: hash(await readFile('scripts/mobile/complete-ios-baseline.mjs')),
};
await writeFile(output + '/report.json', JSON.stringify(report, null, 2) + '\n');
console.log(
  'Complete ' + phase + ' iOS gate:',
  groups.reduce((sum, group) => sum + group.expected, 0),
  'passed, zero failures/skips; explicitly marked reused groups:',
  groups
    .filter((group) => group.evidenceStatus === 'reused-matching')
    .map((group) => group.group)
    .join(', '),
);
console.log('Evidence:', output + '/report.json');
