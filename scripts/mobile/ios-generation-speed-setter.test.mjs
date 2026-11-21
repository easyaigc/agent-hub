import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises';
import { delimiter, join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

const run = promisify(execFile);
const root = fileURLToPath(new URL('../../', import.meta.url));
const sha = (value) => createHash('sha256').update(value).digest('hex');
function speedProperty(source) {
  const start = source.indexOf('  var speed: String {');
  assert(start >= 0, 'Missing actual SessionStore.speed property');
  let depth = 0;
  for (let i = source.indexOf('{', start); i < source.length; i++) {
    if (source[i] === '{') depth++;
    if (source[i] === '}' && --depth === 0) return source.slice(start, i + 1);
  }
  assert.fail('Unclosed actual speed property');
}
async function bytes(directory) {
  let total = 0;
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    total += entry.isDirectory() ? await bytes(path) : (await stat(path)).size;
  }
  return total;
}

test(
  'actual iOS speed setter rejects expired catalogue callbacks using compiled KMP',
  { skip: process.platform !== 'darwin' },
  async (t) => {
    const source = await readFile(
      process.env.IOS_SPEED_SETTER_SOURCE || join(root, 'apps/ios/Sources/SessionStore.swift'),
      'utf8',
    );
    const property = speedProperty(source);
    // Build the real policy from this checkout, including on a clean CI worker.
    // A pre-existing jar alone is not evidence that it matches the source under test.
    await run(
      process.execPath,
      [join(root, 'scripts/mobile/gradle.mjs'), ':mobile-core:jvmJar', '--max-workers=2'],
      { cwd: root, timeout: 180000, maxBuffer: 2 * 1024 * 1024 },
    );
    const core = join(root, '.build/native/mobile-core/libs/mobile-core-jvm.jar');
    const cache = process.env.GRADLE_USER_HOME || join(root, '.local/gradle-home');
    const directory = join(
      cache,
      'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.4.21',
    );
    const candidates = [];
    for (const hash of await readdir(directory)) {
      const path = join(directory, hash, 'kotlin-stdlib-2.4.21.jar');
      if (await stat(path).catch(() => null)) candidates.push(path);
    }
    assert.equal(candidates.length, 1, 'Use one already-cached pinned Kotlin stdlib');
    const javaHome =
      process.env.JAVA_HOME || (await run('/usr/libexec/java_home', ['-v', '17'])).stdout.trim();
    const scratch = await mkdtemp(join(root, '.build/w4-speed-setter-'));
    t.after(() => rm(scratch, { recursive: true, force: true }));
    const classpath = [core, candidates[0], scratch].join(delimiter);
    // Bridge only: the boolean decision is executed by the actual compiled KMP class.
    await writeFile(
      join(scratch, 'KmpSpeedOracle.java'),
      `import java.util.*;
public final class KmpSpeedOracle {
  public static void main(String[] args) {
    String speed = args[0].equals("__nil__") ? null : args[0];
    System.out.print(com.agenthub.core.SessionPolicies.INSTANCE.generationSpeedAllowed(
      speed, Arrays.asList(Arrays.copyOfRange(args, 1, args.length))));
  }
}`,
    );
    await run(
      join(javaHome, 'bin/javac'),
      ['-J-Xmx128m', '-cp', classpath, '-d', scratch, join(scratch, 'KmpSpeedOracle.java')],
      { cwd: root, timeout: 30000 },
    );
    const swift = String.raw`@_silgen_name("popen") func pipeOpen(_ command: UnsafePointer<CChar>, _ mode: UnsafePointer<CChar>) -> OpaquePointer?
@_silgen_name("fgetc") func pipeRead(_ stream: OpaquePointer) -> Int32
@_silgen_name("pclose") func pipeClose(_ stream: OpaquePointer) -> Int32
@_silgen_name("exit") func finish(_ status: Int32) -> Never
func quote(_ text: String) -> String { "'" + text.split(separator: "'", omittingEmptySubsequences: false).joined(separator: "'\\''") + "'" }
final class SessionPolicies {
  static let shared = SessionPolicies()
  var calls = 0
  func generationSpeedAllowed(speed: String?, supportedSpeeds: [String]) -> Bool {
    calls += 1
    let args = [CommandLine.arguments[1], "-Xmx64m", "-cp", CommandLine.arguments[2], "KmpSpeedOracle", speed ?? "__nil__"] + supportedSpeeds
    let command = args.map(quote).joined(separator: " ")
    let stream = command.withCString { command in "r".withCString { pipeOpen(command, $0) } }!
    var output = ""
    while true { let c = pipeRead(stream); if c < 0 { break }; output.append(Character(UnicodeScalar(UInt32(c))!)) }
    guard pipeClose(stream) == 0, ["true", "false"].contains(output) else { print("KMP bridge failed"); finish(2) }
    return output == "true"
  }
}
struct Draft: Equatable {
  var model: String? = "original-model"
  var effort: String? = "high"
  var permissionMode: String? = "confirm_high_risk"
  var text: String = "independent new draft"
  var attachmentIds: [String] = ["original-local-file"]
  var speed: String? = "fast"
}
struct Drafts { var values: [String: Draft] = [:] }
enum Edit { case speed }
final class SetterFixture {
  var canConfigureGeneration = true
  var supportsGenerationSpeed = true
  var availableSpeeds: [String] = ["fast"]
  var targetKey = "captured-target"
  var drafts = Drafts()
  var draft: Draft { drafts.values[targetKey] ?? Draft() }
  var remembered = 0, persisted = 0
  let submittedIntent = "frozen original operation/text/settings"
  func rememberGeneration(_ edit: Edit) { remembered += 1 }
  func persist() { persisted += 1 }
${property}
}
struct Case {
  let name: String, input: String
  let speeds: [String], enabled: Bool, supported: Bool
  let initial: String?, expected: String?
  let edits: Int
}
let cases: [Case] = [
  Case(name: "stale-standard-fast-only", input: "standard", speeds: ["fast"], enabled: true, supported: true, initial: "fast", expected: "fast", edits: 0),
  Case(name: "stale-ultrafast-fast-only", input: "ultrafast", speeds: ["fast"], enabled: true, supported: true, initial: "fast", expected: "fast", edits: 0),
  Case(name: "valid-fast", input: "fast", speeds: ["fast"], enabled: true, supported: true, initial: nil, expected: "fast", edits: 1),
  Case(name: "valid-standard", input: "standard", speeds: ["standard", "fast"], enabled: true, supported: true, initial: "fast", expected: "standard", edits: 1),
  Case(name: "valid-ultrafast", input: "ultrafast", speeds: ["ultrafast"], enabled: true, supported: true, initial: "fast", expected: "ultrafast", edits: 1),
  Case(name: "follow-computer", input: "", speeds: ["fast"], enabled: true, supported: true, initial: "fast", expected: nil, edits: 1),
  Case(name: "follow-empty-catalogue", input: "", speeds: [], enabled: true, supported: true, initial: "fast", expected: nil, edits: 1),
  Case(name: "reject-empty-catalogue-explicit", input: "fast", speeds: [], enabled: true, supported: true, initial: "standard", expected: "standard", edits: 0),
  Case(name: "reject-whitespace", input: " fast ", speeds: ["fast"], enabled: true, supported: true, initial: "fast", expected: "fast", edits: 0),
  Case(name: "reject-unknown", input: "unknown", speeds: ["fast"], enabled: true, supported: true, initial: "fast", expected: "fast", edits: 0),
  Case(name: "disabled-keeps-override", input: "", speeds: ["fast"], enabled: false, supported: true, initial: "fast", expected: "fast", edits: 0),
  Case(name: "disabled-rejects-valid-fast", input: "fast", speeds: ["fast"], enabled: false, supported: true, initial: nil, expected: nil, edits: 0),
  Case(name: "unsupported-keeps-override", input: "", speeds: ["fast"], enabled: true, supported: false, initial: "fast", expected: "fast", edits: 0),
  Case(name: "unsupported-rejects-valid-fast", input: "fast", speeds: ["fast"], enabled: true, supported: false, initial: nil, expected: nil, edits: 0)
]
var failures = 0
for test in cases {
  let store = SetterFixture()
  var original = Draft(); original.speed = test.initial
  store.drafts.values[store.targetKey] = original
  var other = Draft(); other.speed = "ultrafast"; other.text = "other target draft"
  store.drafts.values["other-target"] = other
  store.availableSpeeds = test.speeds
  store.canConfigureGeneration = test.enabled; store.supportsGenerationSpeed = test.supported
  let submitted = store.submittedIntent
  let allowedByKmp = SessionPolicies.shared.generationSpeedAllowed(speed: test.input.isEmpty ? nil : test.input, supportedSpeeds: test.speeds)
  let policyMatchesCase = (test.enabled && test.supported && allowedByKmp) == (test.edits == 1)
  store.speed = test.input
  var expected = original; expected.speed = test.expected
  let passed = policyMatchesCase && store.draft == expected && store.drafts.values["other-target"] == other && store.submittedIntent == submitted && store.persisted == test.edits && store.remembered == test.edits
  if !passed { failures += 1 }
  print("{\"case\":\"\(test.name)\",\"passed\":\(passed),\"oracleAllowed\":\(allowedByKmp),\"persisted\":\(store.persisted),\"remembered\":\(store.remembered)}")
}
print("{\"cases\":\(cases.count),\"failures\":\(failures),\"kmpCalls\":\(SessionPolicies.shared.calls)}")
finish(failures == 0 ? 0 : 1)
`;
    const sourcePath = join(scratch, 'Setter.swift'),
      binary = join(scratch, 'setter');
    await writeFile(sourcePath, swift);
    await run(
      '/usr/bin/swiftc',
      ['-module-cache-path', join(scratch, 'module-cache'), sourcePath, '-o', binary],
      { cwd: root, timeout: 30000 },
    );
    const result = await run(binary, [join(javaHome, 'bin/java'), classpath], {
      cwd: root,
      timeout: 30000,
    }).catch((error) => ({ stdout: error.stdout, stderr: error.stderr, code: error.code }));
    const rows = result.stdout
      .trim()
      .split('\n')
      .map((line) => JSON.parse(line));
    const generatedBytes = await bytes(scratch);
    const record = {
      setterSha256: sha(property),
      policySourceSha256: sha(
        await readFile(
          join(
            root,
            'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SessionPolicies.kt',
          ),
        ),
      ),
      compiledCoreSha256: sha(await readFile(core)),
      generatedBytes,
      rows,
      stderr: result.stderr,
      exitCode: result.code || 0,
    };
    if (process.env.IOS_SPEED_RESULT_PATH)
      await writeFile(process.env.IOS_SPEED_RESULT_PATH, JSON.stringify(record, null, 2) + '\n');
    assert(
      generatedBytes <= 20 * 1024 * 1024,
      'Keep this fixture below the assigned 20 MiB budget',
    );
    assert.equal(record.exitCode, 0, JSON.stringify(rows));
    assert.equal(rows.at(-1).cases, 14);
    assert.equal(rows.at(-1).failures, 0);
    assert(rows.at(-1).kmpCalls > 0, 'The fixed setter must invoke the actual compiled KMP policy');
    t.diagnostic(JSON.stringify({ generatedBytes, summary: rows.at(-1) }));
  },
);
