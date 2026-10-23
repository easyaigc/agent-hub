import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { promisify } from 'node:util';

assert.equal(
  process.platform,
  'darwin',
  'The native discovery scheduler check requires macOS Swift',
);
const execute = promisify(execFile);
const sourceFile = process.env.MOBILE_DISCOVERY_SOURCE ?? 'apps/ios/Sources/CodexDiscovery.swift';
const source = await readFile(sourceFile, 'utf8');
const marker = '@MainActor final class CodexHostDiscovery:';
const start = source.indexOf(marker);
assert(start >= 0, 'Require the actual production discovery implementation');
let depth = 0,
  opened = false,
  declaration;
for (let index = start; index < source.length; index++) {
  if (source[index] === '{') {
    depth++;
    opened = true;
  }
  if (source[index] === '}' && --depth === 0 && opened) {
    declaration = source.slice(start, index + 1);
    break;
  }
}
assert(declaration, 'Discovery declaration must be complete');
const fixture = await readFile('scripts/mobile/fixtures/codex-discovery-scheduling.swift', 'utf8');
assert.equal(fixture.split('/*__DISCOVERY_CLASS__*/').length, 2);
const directory = resolve('.local/mobile-acceptance/UM04/discovery-scheduler-' + randomUUID());
await mkdir(directory, { recursive: true, mode: 0o700 });
const program = join(directory, 'scheduler.swift');
await writeFile(program, fixture.replace('/*__DISCOVERY_CLASS__*/', declaration));
const env = {
  ...process.env,
  DEVELOPER_DIR: process.env.DEVELOPER_DIR ?? '/Applications/Xcode.app/Contents/Developer',
};
const build = await execute(
  'xcrun',
  [
    'swiftc',
    '-swift-version',
    '6',
    '-parse-as-library',
    program,
    '-o',
    join(directory, 'scheduler'),
  ],
  { env, timeout: 60000 },
);
await writeFile(join(directory, 'build.log'), build.stdout + build.stderr);
let result;
try {
  const run = await execute(join(directory, 'scheduler'), [], { env, timeout: 10000 });
  result = { stdout: run.stdout, stderr: run.stderr, exitCode: 0 };
} catch (error) {
  result = { stdout: error.stdout ?? '', stderr: error.stderr ?? '', exitCode: error.code };
}
await writeFile(join(directory, 'run.log'), result.stdout + result.stderr);
const cases = result.stdout
  .trim()
  .split('\n')
  .filter(Boolean)
  .map((line) => JSON.parse(line));
const report = {
  scope:
    'Actual Swift discovery scheduler with deterministic platform/probe/store interfaces; auxiliary only, no real network or model',
  sourceFile,
  sourceSha256: createHash('sha256').update(source).digest('hex'),
  fixtureSha256: createHash('sha256').update(fixture).digest('hex'),
  cases,
  exitCode: result.exitCode,
};
await writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
console.log(JSON.stringify({ directory, ...report }));
assert.equal(result.exitCode, 0, 'Discovery scheduling boundary failed; retain its raw report');
assert.equal(cases.length, 3);
assert(cases.every((item) => item.passed));
