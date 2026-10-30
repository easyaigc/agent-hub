import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { creationGates } from '../packages/codex-adapter/dist/desktop-creation-driver.js';
import { preparedAdapterDigest } from '../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import {
  makePrivateDirectorySync,
  openPrivateFile,
  protectWindowsStateDirectory,
  protectWindowsStateFile,
} from '../packages/codex-adapter/dist/desktop-platform.js';

// Derive a desktop/HTTP proof from actual, same-target acceptance evidence.
// This never promotes a service and never asserts that phone UI tests passed.
const files = process.argv.slice(2).map((file) => resolve(file));
assert.ok(files.length >= 4, 'Provide actual creation and lifecycle reports');
const inputs = await Promise.all(
  files.map(async (file) => {
    const bytes = await readFile(file);
    return {
      file,
      sha256: createHash('sha256').update(bytes).digest('hex'),
      data: JSON.parse(bytes),
    };
  }),
);
const first = inputs[0].data;
const gates = {};
let lifecycle;
for (const input of inputs) {
  const report = input.data;
  assert.equal(report.passed, true, 'Failed or unfinished evidence cannot be promoted');
  assert.equal(report.hostId, first.hostId, 'Evidence must be for the same installed Host');
  assert.equal(report.desktopVersion, first.desktopVersion);
  assert.equal(
    report.adapterSha256,
    preparedAdapterDigest(),
    'Evidence must match the loaded build',
  );
  if (report.kind === 'real-desktop-prepared-acceptance') {
    assert.equal(report.originalTools, true);
    assert.equal(report.noInitializerTurn, true);
    assert.equal(report.reply, true);
    assert.ok(report.operations?.[0]?.threadId);
    if (report.gates?.['empty-project']) assert.equal(report.emptyBefore, true);
    if (report.gates?.['owner-controls'])
      assert.ok(report.stopTurnId && report.approval?.requestId);
  } else {
    assert.equal(report.kind, 'real-desktop-prepared-lifecycle');
    assert.equal(report.unloadedBefore, true);
    assert.equal(report.unloadedOpen, true);
    assert.equal(report.controllerArchivedBefore, true);
    assert.equal(report.controllerArchive, true);
    assert.equal(report.gates?.recovery, true);
    assert.equal(report.recoveredReply, true);
    assert.equal(new Set(report.hostPids).size, 3, 'Actual dedicated HTTP Host restarts required');
    assert.deepEqual(report.originalProcessesBefore, report.originalProcessesAfter);
    lifecycle = report;
  }
  for (const gate of creationGates) if (report.gates?.[gate] === true) gates[gate] = true;
}
assert.ok(lifecycle, 'Actual lifecycle evidence required');
assert.ok(
  creationGates.every((gate) => gates[gate] === true),
  'All six implemented desktop gates required',
);
const proof = {
  kind: 'real-desktop-prepared-bridge',
  hostId: first.hostId,
  desktopVersion: first.desktopVersion,
  adapterSha256: preparedAdapterDigest(),
  verifiedAt: new Date().toISOString(),
  gates,
  unloadedOpen: true,
  controllerArchive: true,
  acceptanceLayer: 'original-desktop-and-real-http-host',
  phoneUiVerified: false,
  evidence: inputs.map(({ file, sha256, data }) => ({
    file,
    sha256,
    kind: data.kind,
    scenario: data.scenario ?? 'lifecycle',
  })),
};
const directory = resolve('.local/desktop-prepared-proofs');
makePrivateDirectorySync(directory);
protectWindowsStateDirectory(directory);
const file = join(directory, first.hostId + '-' + Date.now() + '.json');
const output = await openPrivateFile(file, 'w', 0o600);
try {
  await output.writeFile(JSON.stringify(proof, null, 2));
} finally {
  await output.close();
}
protectWindowsStateFile(file);
console.log(
  JSON.stringify({
    file,
    hostId: proof.hostId,
    adapterSha256: proof.adapterSha256,
    gates,
    unloadedOpen: true,
    controllerArchive: true,
    phoneUiVerified: false,
  }),
);
