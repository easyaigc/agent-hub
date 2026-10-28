import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const directory = new URL('./', import.meta.url);
const root = new URL('../../../../../', directory);
const read = async (path) => JSON.parse(await readFile(new URL(path, root), 'utf8'));
const git = (args) =>
  execFileSync('git', args, { cwd: fileURLToPath(root), encoding: 'utf8' }).trim();
const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
const scope = JSON.parse(await readFile(new URL('scope.json', directory)));
const ledgerPath = 'docs/acceptance/mobile-evidence/A5-execution-progress.json';
const ledger = await read(ledgerPath);
const manifest = await read('docs/product/mobile-parity.manifest.json');
const frozen = await read('docs/plans/android-alignment-20261022/parity.json');
const commits = await read('docs/plans/android-alignment-20261022/commits.json');
const plans = JSON.parse(await readFile(new URL('acceptance-intents.json', directory)));
const canonical = (value) => {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === 'object')
    return Object.fromEntries(
      Object.keys(value)
        .sort()
        .map((key) => [key, canonical(value[key])]),
    );
  return value;
};
assert.equal(plans.intents.length, 9);
assert.equal(new Set(plans.intents.map((p) => p.intent.intentId)).size, plans.intents.length);
for (const plan of plans.intents) {
  assert.equal(
    hash(JSON.stringify(canonical(plan.intent))),
    plan.planSha256,
    'Acceptance plan changed without a new immutable record',
  );
  assert.equal(plan.scheduling.state, 'unscheduled-not-submitted');
  assert.equal(plan.scheduling.executable, false);
  for (const key of ['device', 'bundle', 'hostTarget', 'lease'])
    assert.equal(plan.scheduling[key], null);
}
const prefix = 'docs/plans/android-alignment-20261022/parallel/p8-easywork/';
const android = (name) => 'apps/android/src/main/kotlin/com/agenthub/android/' + name;
const ios = (name) => 'apps/ios/Sources/' + name;
const unique = (list) => [...new Set(list)].sort();
const featureIds = unique(scope.groups.flatMap((g) => g.features));
assert.equal(scope.groups.length, 7);
assert.deepEqual(
  scope.groups.map((g) => g.id),
  Array.from({ length: 7 }, (_, n) => 'A5-' + (n + 1)),
);
assert(
  ledger.features.every((id) => featureIds.includes(id)),
  'Every old A5 feature remains covered',
);
assert(
  featureIds.every((id) => !['F65', 'F66', 'F67'].includes(id)),
  'Deferred business work excluded',
);
const features = featureIds.map((id) => {
  const current = manifest.features.find((f) => f.id === id);
  const previous = frozen.features.find((f) => f.id === id);
  assert(current && previous, id);
  return {
    id,
    commonAssertion: current.assertion,
    ui: current.ui,
    scenarios: current.scenarios,
    iosSources: previous.iosSourceFiles,
    historicalChangeNodes: previous.changeNodes,
    baselineImplementation: current.implementation,
    baselineVerification: current.verification,
    sourceMappingMeaning:
      'Frozen feature-to-source map; current hashes and last-changing commits below, not UI or DEV acceptance',
  };
});
const paths = unique([
  ...scope.groups.flatMap((g) => [...g.android.map(android), ...g.ios.map(ios)]),
  ...features.flatMap((f) => f.iosSources),
  ...Object.keys(ledger.sources),
  'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/RuntimeIdentity.kt',
  'packages/protocol/schemas/agent-management-v1.schema.json',
  'docs/integrations/easywork-ewdap-1/implementation.patch',
  'docs/integrations/easywork-ewdap-1/manifest.json',
]);
const sourceFiles = {};
for (const path of paths) {
  const bytes = await readFile(new URL(path, root));
  const base = execFileSync('git', ['show', scope.sourceBase + ':' + path], {
    cwd: fileURLToPath(root),
  });
  assert(bytes.equals(base), 'Audited production input drifted from assigned base: ' + path);
  sourceFiles[path] = {
    sha256: hash(bytes),
    lastChangingCommit: git(['log', '-1', '--format=%H', scope.sourceBase, '--', path]),
    matchesAssignedBase: true,
  };
}
const sourceAudit = {
  version: 1,
  taskId: scope.taskId,
  sourceBase: scope.sourceBase,
  auditedSourceSetSha256: hash(JSON.stringify(sourceFiles)),
  sourceFiles,
  historicalDeclaredSources: Object.fromEntries(
    Object.entries(ledger.sources).map(([path, historicalSha256]) => [
      path,
      {
        historicalSha256,
        currentSha256: sourceFiles[path].sha256,
        matches: historicalSha256 === sourceFiles[path].sha256,
      },
    ]),
  ),
  dependencyPatch: {
    declaredSha256: ledger.dependencyDelivery.patchSha256,
    actualSha256: sourceFiles['docs/integrations/easywork-ewdap-1/implementation.patch'].sha256,
    matches:
      ledger.dependencyDelivery.patchSha256 ===
      sourceFiles['docs/integrations/easywork-ewdap-1/implementation.patch'].sha256,
    meaning: 'Delivery integrity only; no current Desktop adoption or deployment claimed',
  },
  history: {
    ledgerPath,
    ledgerSha256: hash(await readFile(new URL(ledgerPath, root))),
    ledgerDate: ledger.date,
    closingInput: ledger.currentClosingInput,
    scopedDeviceAuthorization: {
      status: ledger.currentScopedDeviceAuthorization.status,
      apkSha256: ledger.currentScopedDeviceAuthorization.apkSha256,
      productionSourceSha256: ledger.currentScopedDeviceAuthorization.productionSourceSha256,
      environments: ledger.currentScopedDeviceAuthorization.environments,
      scope:
        'Historical isolated DEV only; its combined native+Desktop hash is not reproduced by this audit',
    },
    priorAcceptedFreeze: ledger.priorAcceptedFreeze,
    preservedRecoveryFailure: {
      status: ledger.currentNewFreezeRecoveryInterference.status,
      scope: ledger.currentNewFreezeRecoveryInterference.scope,
      reportSha256: ledger.currentNewFreezeRecoveryInterference.failedFullReportSha256,
    },
    reuseDecision:
      'No current-native reuse claim. Missing matching whole source/APK/runner/environment and original proof availability prevents promoting historical passes.',
  },
  externalWipAdopted: false,
  currentDeviceOrDesktopProofProduced: false,
};
const p8Nodes = commits.entries
  .filter((e) => e.workPackages.includes('P8'))
  .map((e) => ({
    id: e.recordId,
    commit: e.sha,
    title: e.title,
    iosChangedPaths: e.changedPaths.filter((p) => p.startsWith('apps/ios/')),
    interpretation: e.workPackages.includes('DEFER')
      ? 'EasyWork subset only; deferred business scope not resumed'
      : 'Historical implementation/source node, not current acceptance',
  }));
const iosPaths = paths.filter((path) => path.startsWith('apps/ios/'));
const incrementalLog = git([
  'log',
  '--no-merges',
  '--format=%H%x00%s',
  frozen.frozenCommit + '..' + scope.sourceBase,
  '--',
  ...iosPaths,
]);
const currentIosIncrementalNodes = incrementalLog
  ? incrementalLog.split('\n').map((line) => {
      const [commit, title] = line.split('\0');
      const changedPaths = git(['show', '--format=', '--name-only', commit, '--', ...iosPaths])
        .split('\n')
        .filter(Boolean);
      return {
        commit,
        title,
        changedPaths,
        meaning:
          'Committed source lineage through assigned base only; no current DEV or external WIP adoption',
      };
    })
  : [];
const groups = scope.groups.map((g) => ({
  ...g,
  androidSources: g.android.map(android),
  iosSources: g.ios.map(ios),
  featureRows: features.filter((f) => g.features.includes(f.id)),
  iosNodeRefs: p8Nodes
    .filter((n) => n.iosChangedPaths.some((p) => g.ios.map(ios).includes(p)))
    .map((n) => n.id),
  postFrozenIosIncrementalNodeRefs: currentIosIncrementalNodes
    .filter((node) => node.changedPaths.some((path) => g.ios.map(ios).includes(path)))
    .map((node) => node.commit),
  lastChangingIosNodes: Object.fromEntries(
    g.ios.map(ios).map((p) => [p, sourceFiles[p].lastChangingCommit]),
  ),
  implementation: 'Code present; per-feature completeness and corrections remain with owners',
  currentNativeVerification: 'not-performed',
  matchedHistoricalReuse: false,
  sealed: false,
}));
const matrix = {
  version: 1,
  taskId: scope.taskId,
  sourceBase: scope.sourceBase,
  featureIds,
  currentSourceEvidence: 'source-audit.json',
  historicalIosAndIntegrationNodes: p8Nodes,
  frozenComparisonCommit: frozen.frozenCommit,
  currentIosIncrementalNodes,
  groups,
  explicitlySeparateGates: [
    'Host-JVM synthetic boundaries',
    'API26 native',
    'API37 native',
    'Physical Android',
    'iOS',
    'Original Electron and actual model',
    'Public Desktop OIDC',
    'Deployed cross-network relay',
    'Windows',
  ],
  paused: ['F65-F67/UI14-UI19 business', 'UM06 later business/UM07/UM08 business', 'iOS A6'],
};
const auxiliaryPaths = [
  'audit.mjs',
  'scope.json',
  'acceptance-intents.json',
  'boundaries.init.gradle',
  'ewdap-vector.mjs',
  'ewdap-vector.json',
  'provider-descriptor.schema.json',
  'schema.test.mjs',
  'migration-contract.md',
  'jvm/android/util/Base64.java',
  'jvm/com/agenthub/android/P8EasyWorkBoundaryTest.kt',
];
const auxiliaryFiles = Object.fromEntries(
  await Promise.all(
    auxiliaryPaths.map(async (p) => [prefix + p, hash(await readFile(new URL(p, directory)))]),
  ),
);
const delta = {
  version: 1,
  id: 'android-p8-easywork-audit-20261028',
  taskId: scope.taskId,
  baseCommit: scope.sourceBase,
  summary:
    'Seven-group reconciliation and isolated production Android session/crypto auxiliary boundaries. No product behavior, UI or production source changes; no current A5 group closure.',
  features: featureIds,
  commonAssertions: Object.fromEntries(features.map((f) => [f.id, f.commonAssertion])),
  dualPlatformImpact: {
    android:
      'Production unchanged; current JVM boundary proof only. Native device/real DEV gates remain open.',
    ios: 'Production unchanged; current source nodes audited. Historical iOS proof cannot certify Android.',
  },
  implementation: 'audit-and-auxiliary-tests-only',
  verification: {
    androidNative: 'not-performed',
    iosNative: 'not-performed',
    realDev: 'not-performed',
    auxiliary: 'See verification.json; independent of product closure',
  },
  sourceFilesSha256: Object.fromEntries(Object.entries(sourceFiles).map(([p, v]) => [p, v.sha256])),
  files: auxiliaryFiles,
  productionFilesChanged: [],
  uiCopyDelta: [],
  integratorAction:
    'Merge this delta by feature/change ID without overwriting central WIP or raising product acceptance; rerun source audit against the integrated source candidate and retain individual real gates.',
};
for (const [name, value] of Object.entries({
  'source-audit.json': sourceAudit,
  'matrix.json': matrix,
  'parity-delta.json': delta,
})) {
  const url = new URL(name, directory);
  if (process.argv.includes('--write')) await writeFile(url, JSON.stringify(value, null, 2) + '\n');
  else
    assert.deepEqual(
      JSON.parse(await readFile(url)),
      value,
      name + ' drifted; regenerate only in this workline',
    );
}
console.log(
  JSON.stringify({
    groups: groups.length,
    features: featureIds.length,
    auditedSourceFiles: paths.length,
    matchedHistoricalDeclaredFiles: Object.values(sourceAudit.historicalDeclaredSources).filter(
      (x) => x.matches,
    ).length,
    historicalDeclaredFiles: Object.keys(ledger.sources).length,
    historicalP8Nodes: p8Nodes.length,
    currentNativeReuse: false,
  }),
);
