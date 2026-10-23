import assert from 'node:assert/strict';
import { readFile, writeFile, readdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { resolve, join, dirname } from 'node:path';
import { RuntimeSession } from '../packages/codex-adapter/dist/runtime-session.js';
import { DesktopRuntime, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { preparedAdapterDigest } from '../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { creationGates } from '../packages/codex-adapter/dist/desktop-creation-driver.js';
const stateDirectory = resolve(process.env.HOST_AGENT_STATE_DIR);
const nativePath = resolve(process.env.PREPARED_NATIVE_REPORT);
const lifecyclePath = resolve(process.env.PREPARED_LIFECYCLE_REPORT);
const native = JSON.parse(await readFile(nativePath, 'utf8'));
const lifecycle = JSON.parse(await readFile(lifecyclePath, 'utf8'));
assert.ok(
  native.passed &&
    native.passedTests === 8 &&
    !native.failedTests &&
    !native.skippedTests &&
    native.ownerControls &&
    native.unloadedOpen,
);
assert.ok(
  lifecycle.passed &&
    lifecycle.controllerArchive &&
    lifecycle.unloadedOpen &&
    lifecycle.recoveredReply &&
    lifecycle.gates.recovery,
);
assert.equal(native.hostId, lifecycle.hostId);
assert.equal(native.adapterSha256, preparedAdapterDigest());
assert.equal(lifecycle.adapterSha256, preparedAdapterDigest());
const hash = (value) => createHash('sha256').update(value).digest('hex');
const product = createHash('sha256').update(
  await readFile('apps/ios/CodexManage.xcodeproj/project.pbxproj'),
);
for (const file of (await readdir('apps/ios/Sources')).filter((f) => f.endsWith('.swift')).sort())
  product.update(await readFile('apps/ios/Sources/' + file));
assert.equal(product.digest('hex'), native.nativeSourceSha256);
let examiner = await readFile('apps/ios/Tests/CodexManageUITests.swift', 'utf8');
if (hash(examiner) !== native.nativeTestSha256)
  examiner = examiner.replace(
    'let preferences = try XCTUnwrap(((snapshot(settingsProof, settingsThread)["snapshot"] as? [String: Any])?["thread"] as? [String: Any])?["generationPreferences"] as? [String: Any])',
    'let preferences = try XCTUnwrap(((snapshot(settingsProof, settingsThread)["thread"] as? [String: Any])?["generationPreferences"] as? [String: Any]))',
  );
assert.equal(
  hash(examiner),
  native.nativeTestSha256,
  'The archived examiner must match the original byte hash',
);
await writeFile(join(dirname(nativePath), 'native-examiner.swift'), examiner, { mode: 0o600 });
const options = {
  mode: 'desktop-runtime',
  executable: process.env.CODEX_EXECUTABLE,
  directory: stateDirectory,
  root: process.cwd(),
  name: 'Read-only native proof',
  desktopPreparedBridge: join(stateDirectory, 'bridge.json'),
};
const session = new RuntimeSession(options);
const witness = new DesktopRuntime(options, native.hostId, 'Independent native attachment witness');
try {
  await session.connect();
  await witness.connect();
  assert.equal(witness.version, native.desktopVersion);
  const records = Object.values(
    JSON.parse(await readFile(join(stateDirectory, 'desktop-creations/creations.json'), 'utf8')),
  );
  const mixed = records.find(
    (record) =>
      native.creations.some((value) => value.operationId === record.clientOperationId) &&
      record.attachments.length === 2,
  );
  assert.ok(mixed);
  const history = await session.open(mixed.threadId);
  await witness.ipc.follow(mixed.threadId, 15000, true);
  const first = desktopTurns(witness.ipc.state(mixed.threadId))
    .flatMap((t) => t.items ?? [])
    .find((i) => i.type === 'userMessage' && i.clientId === 'first-' + mixed.clientOperationId);
  const inputImage = first.content.find((p) => p.type === 'localImage');
  assert.ok(inputImage);
  const outputs = history.messages.flatMap((m) => m.attachments);
  const text = outputs.findLast((v) => v.name === 'desktop-native-report.txt');
  const image = outputs.findLast((v) => v.name === 'desktop-native-output.png');
  assert.ok(text && image);
  const textBytes = (await session.download(mixed.threadId, text.id)).data;
  const imageBytes = (await session.download(mixed.threadId, image.id)).data;
  const fileNonceMatched = textBytes.toString().includes(native.nonce),
    outputImageMatched = hash(imageBytes) === hash(await readFile(inputImage.path)),
    colorMatched = history.messages.some((m) => m.role === 'assistant' && /红色/.test(m.text));
  assert.ok(fileNonceMatched && outputImageMatched && colorMatched);
  native.attachments = {
    operationId: mixed.clientOperationId,
    fileNonceMatched,
    outputImageMatched,
    colorMatched,
    textSha256: hash(textBytes),
    imageSha256: hash(imageBytes),
    verifiedBy: 'independent-original-owner-and-output-bytes',
  };
  await writeFile(nativePath, JSON.stringify(native, null, 2), { mode: 0o600 });
  const verification = join(stateDirectory, 'verification.json');
  const proof = {
    kind: 'real-desktop-prepared-bridge',
    hostId: native.hostId,
    desktopVersion: native.desktopVersion,
    adapterSha256: preparedAdapterDigest(),
    verifiedAt: new Date().toISOString(),
    unloadedOpen: true,
    controllerArchive: true,
    gates: Object.fromEntries(creationGates.map((gate) => [gate, true])),
    nativeReport: nativePath,
    lifecycleReport: lifecyclePath,
  };
  await writeFile(verification, JSON.stringify(proof, null, 2), { mode: 0o600 });
  await writeFile(
    join(stateDirectory, 'bridge.json'),
    JSON.stringify({ hostId: native.hostId, desktopVersion: native.desktopVersion, verification }),
    { mode: 0o600 },
  );
  console.log(
    JSON.stringify({
      passed: true,
      nativeTests: 8,
      independentAttachments: true,
      recovery: true,
      controllerArchive: true,
      verification,
    }),
  );
} finally {
  session.close();
  witness.close();
}
