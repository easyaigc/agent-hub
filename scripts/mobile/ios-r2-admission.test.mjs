import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, writeFile, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';

const root = fileURLToPath(new URL('../../', import.meta.url));
const ios = resolve(root, 'apps/ios');
const sha = (data) => createHash('sha256').update(data).digest('hex');

test('actual R2 Swift admission policy rejects foreign scope/expired lease/write substitution', async () => {
  if (process.platform !== 'darwin')
    throw new Error('Requires the installed Apple Swift compiler; do not report fixture success');
  const directory = await mkdtemp(join(tmpdir(), 'w4-r2-policy-'));
  try {
    const targets = await readFile(join(ios, 'Sources/NotificationModels.swift'), 'utf8');
    const start = targets.indexOf('struct AgentRuntimeTarget:');
    const end = targets.indexOf('// Machine identity is distinct');
    assert(start >= 0 && end > start);
    // Exact production declarations, without UIKit/notifications dependencies.
    await writeFile(
      join(directory, 'Target.swift'),
      'import Foundation\n' + targets.slice(start, end),
    );
    const main = String.raw`
import Foundation
@main enum AdmissionRegression {
  static func main() throws {
    var checks = 0
    func ok(_ body: () throws -> Void) throws { try body(); checks += 1 }
    func denied(_ body: () throws -> Void) throws {
      do { try body() } catch is R2AdmissionPolicy.Denied { checks += 1; return }
      throw NSError(domain: "R2 counterexample unexpectedly admitted", code: checks)
    }
    let account = AgentAccount(authority: "fixture", subject: "fixture-local")
    let target = AgentRuntimeTarget(agentId: "easywork", hostId: "fixture-host", workspaceId: "fixture-workspace", runtimeId: "fixture-runtime", account: account)
    let manifest = R2AdmissionPolicy.Manifest(version: 1, taskId: R2AdmissionPolicy.task, bundleId: R2AdmissionPolicy.bundle,
      ownerHostId: R2AdmissionPolicy.ownerHost, caseId: "00000000-0000-4000-8000-000000000001", target: target,
      fingerprint: String(repeating: "a", count: 64), publicKey: String(repeating: "b", count: 43), endpoints: ["https://192.168.1.2:9443"])
    let data = try JSONEncoder().encode(manifest)
    let lease = R2AdmissionPolicy.Lease(manifestSHA256: R2AdmissionPolicy.hash(data), leaseId: "00000000-0000-4000-8000-000000000002", issuedAt: 1000, expiresAt: 1100, mode: "read", intents: [])
    func validate(_ m: R2AdmissionPolicy.Manifest, _ l: R2AdmissionPolicy.Lease, _ bundle: String? = R2AdmissionPolicy.bundle, _ now: Double = 1050) throws {
      try R2AdmissionPolicy.validate(m, lease: l, data: data, bundle: bundle, now: now)
    }
    try ok { try validate(manifest, lease) }
    try denied { try validate(manifest, lease, "com.easyai.agenthub.easyworkacceptance") }
    try denied { try validate(manifest, lease, nil) }
    for modify in [
      { (m: inout R2AdmissionPolicy.Manifest) in m.taskId += "foreign" },
      { (m: inout R2AdmissionPolicy.Manifest) in m.ownerHostId += "foreign" },
      { (m: inout R2AdmissionPolicy.Manifest) in m.bundleId += ".foreign" },
      { (m: inout R2AdmissionPolicy.Manifest) in m.target.agentId = "codex" },
      { (m: inout R2AdmissionPolicy.Manifest) in m.target.account = nil },
      { (m: inout R2AdmissionPolicy.Manifest) in m.target.runtimeId = "" },
      { (m: inout R2AdmissionPolicy.Manifest) in m.caseId = "bad" },
      { (m: inout R2AdmissionPolicy.Manifest) in m.fingerprint = "bad" },
      { (m: inout R2AdmissionPolicy.Manifest) in m.publicKey = "bad" },
      { (m: inout R2AdmissionPolicy.Manifest) in m.endpoints = [] },
      { (m: inout R2AdmissionPolicy.Manifest) in m.endpoints = ["https://example.com"] },
      { (m: inout R2AdmissionPolicy.Manifest) in m.endpoints = ["https://10.1.2.3.evil.com"] },
      { (m: inout R2AdmissionPolicy.Manifest) in m.endpoints = ["http://192.168.1.2"] },
      { (m: inout R2AdmissionPolicy.Manifest) in m.endpoints += m.endpoints }
    ] { var changed = manifest; modify(&changed); try denied { try validate(changed, lease) } }
    try denied { try validate(manifest, lease, R2AdmissionPolicy.bundle, 1100) }
    try denied { try validate(manifest, lease, R2AdmissionPolicy.bundle, 999) }
    for modify in [
      { (l: inout R2AdmissionPolicy.Lease) in l.manifestSHA256 = "foreign" },
      { (l: inout R2AdmissionPolicy.Lease) in l.expiresAt = 5000 },
      { (l: inout R2AdmissionPolicy.Lease) in l.leaseId = "bad" },
      { (l: inout R2AdmissionPolicy.Lease) in l.mode = "unknown" }
    ] { var changed = lease; modify(&changed); try denied { try validate(manifest, changed) } }
    let candidate = [(transport: "lan-wss", endpoint: "https://192.168.1.2:9443", pin: Optional(manifest.fingerprint))]
    try ok { try R2AdmissionPolicy.connection(manifest, target: target, candidates: candidate) }
    for modify in [
      { (t: inout AgentRuntimeTarget) in t.agentId = "codex" },
      { (t: inout AgentRuntimeTarget) in t.hostId += "foreign" },
      { (t: inout AgentRuntimeTarget) in t.workspaceId += "foreign" },
      { (t: inout AgentRuntimeTarget) in t.runtimeId += "foreign" },
      { (t: inout AgentRuntimeTarget) in t.account?.subject += "foreign" }
    ] { var changed = target; modify(&changed); try denied { try R2AdmissionPolicy.connection(manifest, target: changed, candidates: candidate) } }
    try denied { try R2AdmissionPolicy.connection(manifest, target: target, candidates: []) }
    try denied { try R2AdmissionPolicy.connection(manifest, target: target, candidates: [("relay-http", candidate[0].endpoint, candidate[0].pin)]) }
    try denied { try R2AdmissionPolicy.connection(manifest, target: target, candidates: [("lan-wss", candidate[0].endpoint, nil)]) }
    try denied { try R2AdmissionPolicy.connection(manifest, target: target, candidates: [("lan-wss", "https://192.168.1.3:9443", candidate[0].pin)]) }
    let params = Data("{}".utf8), id = "fixture-original-operation"
    try ok { try R2AdmissionPolicy.request(lease, method: "usage/read", id: id, params: params) }
    try denied { try R2AdmissionPolicy.request(lease, method: "turn/start", id: id, params: params) }
    var model = lease; model.mode = "model"
    model.intents = [.init(method: "turn/start", operationId: id, paramsSHA256: R2AdmissionPolicy.hash(params))]
    try ok { try validate(manifest, model) }
    try ok { try R2AdmissionPolicy.request(model, method: "turn/start", id: id, params: params) }
    try denied { try R2AdmissionPolicy.request(model, method: "queue/enqueue", id: id, params: params) }
    try denied { try R2AdmissionPolicy.request(model, method: "turn/start", id: id + "new", params: params) }
    try denied { try R2AdmissionPolicy.request(model, method: "turn/start", id: id, params: Data("changed".utf8)) }
    var duplicate = model; duplicate.intents += model.intents
    try denied { try validate(manifest, duplicate) }
    model.mode = "read"
    try denied { try validate(manifest, model) }
    let journalRoot = FileManager.default.temporaryDirectory.appendingPathComponent("r2-own-journal-" + UUID().uuidString, isDirectory: true)
    try FileManager.default.createDirectory(at: journalRoot, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: journalRoot) }
    let originalIntent = Data("original-offer-and-target".utf8)
    try ok { try R2AdmissionPolicy.captureEnrollment(root: journalRoot, payload: originalIntent) }
    try denied { try R2AdmissionPolicy.captureEnrollment(root: journalRoot, payload: Data("new-offer".utf8)) }
    try ok { try R2AdmissionPolicy.require(Data(contentsOf: journalRoot.appendingPathComponent("r2-enrollment-intent.json")) == originalIntent, "original enrollment intent changed") }
    print("R2 actual Swift policy: \(checks) checks passed")
  }
}
`;
    await writeFile(join(directory, 'Main.swift'), main);
    const build = spawnSync(
      'xcrun',
      [
        'swiftc',
        '-module-cache-path',
        join(directory, 'modules'),
        join(directory, 'Target.swift'),
        join(ios, 'R2Acceptance/R2AdmissionPolicy.swift'),
        join(directory, 'Main.swift'),
        '-o',
        join(directory, 'policy-test'),
      ],
      { cwd: root, encoding: 'utf8', timeout: 120000, maxBuffer: 2 * 1024 * 1024 },
    );
    assert.equal(build.status, 0, build.stderr);
    const run = spawnSync(join(directory, 'policy-test'), [], {
      cwd: root,
      encoding: 'utf8',
      timeout: 10000,
    });
    assert.equal(run.status, 0, run.stderr);
    process.stdout.write(run.stdout);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('original C2 method and original target/module guard remain unchanged', async () => {
  const original = await readFile(join(ios, 'NativeTests/EasyWorkHostedNativeTests.swift'));
  const method = original
    .toString('utf8')
    .match(/^ {2}@MainActor func testC2SyntheticEncryptedQueueAndReceipt\(\)[\s\S]*?^ {2}}/m)?.[0];
  assert(method);
  assert.equal(sha(method), '7f3fceaee1cd2f811bee189ff150df1f9d67d803d768e783a1ef6a22477b2947');
  const project = spawnSync(
    'plutil',
    ['-convert', 'json', '-o', '-', join(ios, 'CodexManage.xcodeproj/project.pbxproj')],
    { encoding: 'utf8' },
  );
  assert.equal(project.status, 0, project.stderr);
  const { objects } = JSON.parse(project.stdout);
  const byName = (name) =>
    Object.entries(objects).find(([, x]) => x.isa === 'PBXNativeTarget' && x.name === name);
  const [appId, app] = byName('R2RealDEVApp');
  const [testId, native] = byName('R2RealDEVNativeTests');
  const projectObject = Object.values(objects).find((x) => x.isa === 'PBXProject');
  assert.equal(projectObject.attributes.TargetAttributes[testId].TestTargetID, appId);
  assert.deepEqual(app.dependencies, []);
  assert(app.buildPhases.every((id) => objects[id].isa !== 'PBXCopyFilesBuildPhase'));
  for (const id of objects[app.buildConfigurationList].buildConfigurations) {
    const settings = objects[id].buildSettings;
    assert.equal(
      settings.PRODUCT_BUNDLE_IDENTIFIER,
      'com.easyai.agenthub.easyworkacceptance.r2task20261112',
    );
    assert.equal(settings.PRODUCT_MODULE_NAME, 'R2RealDEVApp');
    assert.match(settings.SWIFT_ACTIVE_COMPILATION_CONDITIONS, /R2_REAL_DEV_ACCEPTANCE/);
  }
  for (const id of objects[native.buildConfigurationList].buildConfigurations) {
    assert.equal(objects[id].buildSettings.PRODUCT_MODULE_NAME, 'R2RealDEVNativeTests');
    assert.equal(
      objects[id].buildSettings.TEST_HOST,
      '$(BUILT_PRODUCTS_DIR)/R2RealDEVApp.app/R2RealDEVApp',
    );
  }
  assert.match(
    objects.C427044599444F1026B505A9.shellScript,
    /!= "com.easyai.agenthub.easyworkacceptance"/,
  );
  for (const name of ['CodexManage', 'EasyWorkNativeTests']) {
    const [, old] = byName(name);
    for (const id of objects[old.buildConfigurationList].buildConfigurations) {
      assert(
        !objects[id].buildSettings.SWIFT_ACTIVE_COMPILATION_CONDITIONS?.includes(
          'R2_REAL_DEV_ACCEPTANCE',
        ),
      );
    }
  }
  const plist = async (file) => {
    const parsed = spawnSync('plutil', ['-convert', 'json', '-o', '-', join(ios, file)], {
      encoding: 'utf8',
    });
    assert.equal(parsed.status, 0, parsed.stderr);
    return JSON.parse(parsed.stdout);
  };
  const device = await plist('R2Acceptance/Device.entitlements');
  assert.deepEqual(device, {
    'keychain-access-groups': ['$(AppIdentifierPrefix)$(PRODUCT_BUNDLE_IDENTIFIER)'],
  });
  const simulator = await plist('R2Acceptance/Simulator.entitlements');
  assert.deepEqual(simulator, {
    'application-identifier': '$(PRODUCT_BUNDLE_IDENTIFIER)',
    'keychain-access-groups': ['$(PRODUCT_BUNDLE_IDENTIFIER)'],
  });
  const info = await plist('R2Acceptance/Info.plist');
  for (const excluded of [
    'NotificationAppGroup',
    'NotificationEnvironment',
    'UIBackgroundModes',
    'CFBundleURLTypes',
    'CFBundleDocumentTypes',
    'NSBonjourServices',
  ])
    assert.equal(info[excluded], undefined);
  assert.equal(info.UIFileSharingEnabled, false);
  const sourcePhase = app.buildPhases
    .map((id) => objects[id])
    .find((x) => x.isa === 'PBXSourcesBuildPhase');
  const refs = sourcePhase.files.map((id) => objects[id].fileRef);
  for (const file of ['R2Acceptance/R2Admission.swift', 'R2Acceptance/R2AdmissionPolicy.swift']) {
    assert(refs.some((id) => objects[id].path === file));
  }
});

test('actual Swift usage acceptance verifier rejects unavailable/foreign source and redacts receipts', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'w4-r2-usage-'));
  try {
    const targets = await readFile(join(ios, 'Sources/NotificationModels.swift'), 'utf8');
    const targetStart = targets.indexOf('struct AgentRuntimeTarget:');
    const targetEnd = targets.indexOf('// Machine identity is distinct');
    assert(targetStart >= 0 && targetEnd > targetStart);
    await writeFile(
      join(directory, 'Target.swift'),
      'import Foundation\n' + targets.slice(targetStart, targetEnd),
    );
    const native = await readFile(
      join(ios, 'R2AcceptanceTests/R2RealDEVAdmissionTests.swift'),
      'utf8',
    );
    const start = native.indexOf('// BEGIN: R2 usage verification');
    const end = native.indexOf('// END: R2 usage verification');
    assert(start >= 0 && end > start);
    await writeFile(
      join(directory, 'Verifier.swift'),
      'import Foundation\nimport CoreFoundation\n' + native.slice(start, end),
    );
    // Projection fixtures exercise the acceptance/source-proof helper only.
    // Native calls the actual UsageRules framework before invoking this helper.
    await writeFile(
      join(directory, 'Main.swift'),
      String.raw`
import Foundation
@main enum UsageEvidenceRegression {
  static func main() throws {
    var checks = 0
    func check(_ value: Bool, _ label: String) throws {
      guard value else { throw NSError(domain: "R2 usage verifier: " + label, code: checks) }
      checks += 1
    }
    let target = AgentRuntimeTarget(agentId: "easywork", hostId: "private-host", workspaceId: "private-workspace", runtimeId: "private-runtime", account: AgentAccount(authority: "private-authority", subject: "private-user"))
    let hash = String(repeating: "a", count: 64)
    let proof = R2UsageVerification.Proof(version: 1, manifestSHA256: hash, caseId: "fixture-case", target: target, adapterId: "fixture-adapter", scopeRef: "private-source-scope", observationSHA256: String(repeating: "b", count: 64))
    try proof.validate(target: target, manifestSHA256: hash, caseId: "fixture-case")
    func denied(_ value: R2UsageVerification.Proof, _ label: String) throws {
      do { try value.validate(target: target, manifestSHA256: hash, caseId: "fixture-case") }
      catch is R2AdmissionPolicy.Denied { checks += 1; return }
      throw NSError(domain: label + " proof was admitted", code: checks)
    }
    var changed = proof; changed.caseId = "other"; try denied(changed, "case")
    changed = proof; changed.target.account?.subject = "other"; try denied(changed, "account")
    changed = proof; changed.manifestSHA256 = "other"; try denied(changed, "manifest")
    changed = proof; changed.scopeRef = ""; try denied(changed, "missing scope")
    let machine: [String: Any] = ["agentId": target.agentId, "hostId": target.hostId, "workspaceId": target.workspaceId, "runtimeId": target.runtimeId]
    let source: [String: Any] = ["adapterId": proof.adapterId, "scopeRef": proof.scopeRef, "scopeLabel": "private-label", "planLabel": "private-plan"]
    let raw: [String: Any] = ["schemaVersion": 1, "target": machine, "requestId": "private-request", "status": "ok",
      "snapshot": ["snapshotId": "private-snapshot", "sampledAt": "2026-11-13T00:00:00Z", "source": source, "coverage": "partial", "issues": [], "buckets": []]]
    let projection: [String: Any] = ["status": "ok", "source": source, "partial": true, "stale": false,
      "metrics": [["kind": "balance", "state": "amount", "value": "12345.6789", "label": "private-wallet"]]]
    func data(_ value: [String: Any]) throws -> Data { try JSONSerialization.data(withJSONObject: value, options: .sortedKeys) }
    func evaluate(_ r: [String: Any], _ p: [String: Any]) throws -> [String: Any] {
      try R2UsageVerification.evaluate(data(r), projection: data(p), target: target, proof: proof)
    }
    let result = try evaluate(raw, projection)
    try check(result["availabilityPassed"] as? Bool == true && result["sourceVerified"] as? Bool == true, "available")
    try check(result["classification"] as? String == "available-partial-source-verified" && result["partial"] as? Bool == true, "partial preserved")
    let redacted = String(decoding: try data(result), as: UTF8.self)
    try check(!redacted.contains("private-") && !redacted.contains("12345.6789"), "no identities/labels/amounts in receipt")
    try check(result["rawResponseSHA256"] as? String == R2AdmissionPolicy.hash(data(raw)), "exact original response hash")
    var unavailable = raw; unavailable["status"] = "unavailable"; unavailable.removeValue(forKey: "snapshot"); unavailable["reason"] = "not-authenticated"
    let absent = try evaluate(unavailable, ["status": "unavailable", "reason": "not-authenticated"])
    try check(absent["availabilityPassed"] as? Bool == false && absent["sourceVerified"] as? Bool == false && absent["classification"] as? String == "unavailable-not-source-verified", "legal unavailable fails acceptance")
    try check(absent["rawStatus"] as? String == "unavailable" && absent["reason"] as? String == "not-authenticated", "unavailable status retained")
    var wrong = raw; var foreign = machine; foreign["hostId"] = "foreign"; wrong["target"] = foreign
    try check(try evaluate(wrong, projection)["availabilityPassed"] as? Bool == false, "foreign target")
    wrong = raw; wrong["schemaVersion"] = 2
    try check(try evaluate(wrong, projection)["availabilityPassed"] as? Bool == false, "wrong version")
    wrong = raw; wrong["schemaVersion"] = true
    try check(try evaluate(wrong, projection)["availabilityPassed"] as? Bool == false, "boolean version")
    for key in ["adapterId", "scopeRef"] {
      wrong = raw; var snapshot = raw["snapshot"] as! [String: Any]; var bad = source; bad[key] = "other"; snapshot["source"] = bad; wrong["snapshot"] = snapshot
      let value = try evaluate(wrong, projection)
      try check(value["sourceVerified"] as? Bool == false && value["availabilityPassed"] as? Bool == false, "foreign " + key)
    }
    var projected = projection; projected["stale"] = true
    try check(try evaluate(raw, projected)["availabilityPassed"] as? Bool == false, "stale sample")
    projected = projection; projected["metrics"] = []
    try check(try evaluate(raw, projected)["availabilityPassed"] as? Bool == false, "empty metrics")
    projected = projection; projected["metrics"] = [["kind": "balance", "state": "unavailable"]]
    try check(try evaluate(raw, projected)["availabilityPassed"] as? Bool == false, "unavailable metrics")
    projected = projection; projected["source"] = ["adapterId": "other", "scopeRef": "other"]
    try check(try evaluate(raw, projected)["sourceVerified"] as? Bool == false, "projection/source mismatch")
    print("R2 actual Swift usage evidence verifier: \(checks) checks passed; projection fixtures only, no KMP/native execution")
  }
}
`,
    );
    const build = spawnSync(
      'xcrun',
      [
        'swiftc',
        '-module-cache-path',
        join(directory, 'modules'),
        join(directory, 'Target.swift'),
        join(ios, 'R2Acceptance/R2AdmissionPolicy.swift'),
        join(directory, 'Verifier.swift'),
        join(directory, 'Main.swift'),
        '-o',
        join(directory, 'usage-test'),
      ],
      { cwd: root, encoding: 'utf8', timeout: 120000, maxBuffer: 2 * 1024 * 1024 },
    );
    assert.equal(build.status, 0, build.stderr);
    const run = spawnSync(join(directory, 'usage-test'), [], {
      cwd: root,
      encoding: 'utf8',
      timeout: 10000,
    });
    assert.equal(run.status, 0, run.stderr);
    process.stdout.write(run.stdout);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
