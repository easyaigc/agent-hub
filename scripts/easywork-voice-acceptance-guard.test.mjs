import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, mkdir, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

test(
  'actual voice acceptance guard binds bundle, arguments, target and approved input',
  {
    skip: process.platform !== 'darwin',
  },
  async (t) => {
    const directory = await mkdtemp(join(tmpdir(), 'voice-acceptance-guard-'));
    t.after(() => rm(directory, { recursive: true, force: true }));
    const helper = await readFile('apps/ios/Sources/EasyWorkVoiceRecoveryAcceptance.swift', 'utf8');
    const voice = await readFile('apps/ios/Sources/VoiceInput.swift', 'utf8');
    const start = voice.indexOf('struct VoiceTranscriptionRequest:');
    const end = voice.indexOf('struct VoiceTranscriptionReceipt:', start);
    assert.ok(start >= 0 && end > start);
    const setup = voice.indexOf('    setupTask = Task {');
    const preflightStart = voice.indexOf('      do {\n', setup) + '      do {\n'.length;
    const preflightEnd = voice.indexOf(
      '        if !usesLocalRecognition, restorePending,',
      preflightStart,
    );
    assert.ok(setup >= 0 && preflightStart > setup && preflightEnd > preflightStart);
    const app = join(directory, 'Guard.app', 'Contents');
    await mkdir(join(app, 'MacOS'), { recursive: true });
    const plist = (identifier) => `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict><key>CFBundleIdentifier</key><string>${identifier}</string><key>CFBundleExecutable</key><string>guard</string><key>CFBundlePackageType</key><string>APPL</string></dict></plist>`;
    const info = join(app, 'Info.plist');
    await writeFile(info, plist('com.easyai.agenthub.easyworkacceptance'));
    const code = String.raw`import Foundation
import CryptoKit
enum HostServiceError: Error { case wrongHost }
enum HostService {
  static func hash(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
}
${voice.slice(start, end)}
${helper}
@main struct GuardChecks {
  static func actualPreflight(target: String) throws {
    var diagnosticInfo = ""
${voice.slice(preflightStart, preflightEnd)}
    _ = diagnosticInfo
  }
  static func main() async throws {
    let target = "owned-voice-draft"
    if ProcessInfo.processInfo.arguments.contains("--guard-only") {
      let allowed = EasyWorkVoiceRecoveryAcceptance.allowsFixture(target: target)
      var entered = false
      do { try actualPreflight(target: target); entered = true } catch HostServiceError.wrongHost {}
      precondition(entered == (!EasyWorkVoiceRecoveryAcceptance.requested || allowed))
      print(allowed ? "allowed" : "denied")
      return
    }
    // This calls the DEBUG delivery hook after a controlled verdict; it does not
    // prove receipt authorization, ASR, or microphone behavior.
    let scope = try JSONEncoder().encode(["caller-validated-scope", target])
    let manifest = VoiceTranscriptionRequest(hostId: "host", threadId: "thread", draftId: "voice-v1:" + scope.base64EncodedString(),
      operationId: "11111111-1111-4111-8111-111111111111", mediaType: "audio/wav", sizeBytes: 96,
      sha256: String(repeating: "a", count: 64), durationMs: 1000)
    var dropped = false
    do { try await EasyWorkVoiceRecoveryAcceptance.completion.afterVerifiedCompletion(manifest, queryOnly: false) }
    catch let error as URLError { precondition(error.code == .networkConnectionLost); dropped = true }
    precondition(dropped)
    try await EasyWorkVoiceRecoveryAcceptance.completion.afterVerifiedCompletion(manifest, queryOnly: true)
    try await EasyWorkVoiceRecoveryAcceptance.completion.afterVerifiedCompletion(manifest, queryOnly: true)
    try await EasyWorkVoiceRecoveryAcceptance.completion.afterVerifiedCompletion(manifest, queryOnly: false)
    print("drop-once-query-original")
  }
}
`;
    const source = join(directory, 'Guard.swift');
    await writeFile(source, code);
    const binary = join(app, 'MacOS', 'guard');
    const compiled = spawnSync(
      'xcrun',
      [
        '--sdk',
        'macosx',
        'swiftc',
        '-parse-as-library',
        '-D',
        'DEBUG',
        '-module-cache-path',
        join(directory, 'module-cache'),
        source,
        '-o',
        binary,
      ],
      { encoding: 'utf8' },
    );
    assert.equal(compiled.status, 0, compiled.stdout + compiled.stderr);
    const flags = ['-easywork-local-dev', '-voice-recovery-acceptance', '-voice-file-fixture'];
    const environment = {
      ...process.env,
      VOICE_FILE_FIXTURE_TARGET_SHA256: createHash('sha256')
        .update('owned-voice-draft')
        .digest('hex'),
      VOICE_FILE_FIXTURE_SHA256: 'a'.repeat(64),
      VOICE_FILE_FIXTURE_URL: 'http://127.0.0.1:12345/owned.wav',
      EASYWORK_DROP_VOICE_COMPLETION_ONCE: '1',
    };
    function run(env, args = [...flags, '--guard-only']) {
      const result = spawnSync(binary, args, { env, encoding: 'utf8' });
      assert.equal(result.status, 0, result.stdout + result.stderr);
      return result.stdout.trim();
    }
    assert.equal(run(environment), 'allowed');
    assert.equal(run(environment, [...flags, '-ui-testing', '--guard-only']), 'denied');
    for (const flag of flags) {
      assert.equal(
        run(environment, [...flags.filter((item) => item !== flag), '--guard-only']),
        'denied',
      );
    }
    for (const key of [
      'VOICE_FILE_FIXTURE_TARGET_SHA256',
      'VOICE_FILE_FIXTURE_SHA256',
      'VOICE_FILE_FIXTURE_URL',
    ]) {
      const env = { ...environment };
      delete env[key];
      assert.equal(run(env), 'denied');
    }
    for (const [key, value] of [
      ['VOICE_FILE_FIXTURE_TARGET_SHA256', '0'.repeat(64)],
      ['VOICE_FILE_FIXTURE_SHA256', 'invalid'],
      ['VOICE_FILE_FIXTURE_URL', 'https://example.invalid/owned.wav'],
      ['VOICE_FILE_FIXTURE_URL', 'file:///tmp/owned.wav'],
    ])
      assert.equal(run({ ...environment, [key]: value }), 'denied');
    await writeFile(info, plist('com.example.unrelated'));
    assert.equal(run(environment), 'denied');
    await writeFile(info, plist('com.easyai.agenthub.easyworkacceptance'));
    const result = run(environment, flags);
    assert.equal(result.match(/completion-dropped/g)?.length, 1);
    assert.equal(result.match(/recovered-original/g)?.length, 1);
    assert.match(result, /drop-once-query-original/);
  },
);
