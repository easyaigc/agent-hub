import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

// This checks recorded submission inputs. Apple approval and real-device review
// access require independent evidence; a successful build cannot satisfy them.
const root = fileURLToPath(new URL('../../', import.meta.url));
const options = {};
for (let i = 2; i < process.argv.length; i += 2) {
  const name = process.argv[i];
  assert(
    ['--app', '--submission'].includes(name) && process.argv[i + 1],
    'Use --app <exported .app> and/or --submission <private evidence.json>',
  );
  assert(!options[name], 'Duplicate argument: ' + name);
  options[name] = resolve(process.argv[i + 1]);
}
const metadata = await Promise.all(
  ['en-US', 'zh-CN'].map(async (locale) =>
    JSON.parse(
      await readFile(join(root, 'apps/ios/AppStore/store-metadata-' + locale + '.json'), 'utf8'),
    ),
  ),
);
const appleNames =
  /\b(?:apple|iphone|ipad|ipod|macos|ios|watchos|tvos|visionos|imac|macbook|airpods|siri|icloud|itunes)\b|苹果/iu;
for (const item of metadata) {
  for (const field of ['name', 'subtitle']) {
    assert(typeof item[field] === 'string' && item[field].trim(), field + ' is required');
    assert([...item[field]].length <= 30, field + ' exceeds 30 characters');
    assert(!appleNames.test(item[field]), field + ' contains an Apple product term; review 5.2.5');
  }
  assert(
    typeof item.keywords === 'string' && [...item.keywords].length <= 100,
    'Keywords exceed 100 characters',
  );
  assert(
    !appleNames.test(item.keywords) &&
      !/\b(?:codex|openai|chatgpt|easywork)\b/iu.test(item.keywords),
    'Keep third-party brands out of discovery keywords; describe actual integrations in the description',
  );
  assert(
    typeof item.description === 'string' && [...item.description].length <= 4000,
    'Invalid description length',
  );
  for (const field of ['supportUrl', 'privacyPolicyUrl'])
    assert(new URL(item[field]).protocol === 'https:', field + ' must use HTTPS');
  assert(item.releaseMode === 'manual', 'Preserve manual release');
  assert(
    item.availabilityPolicy?.excludedTerritories?.includes('CHN'),
    'Preserve the approved mainland China exclusion',
  );
  assert(
    item.bundleId === 'com.easyai.agenthub' && /^\d+$/.test(item.releaseBuild),
    'Unexpected release target',
  );
}
for (const key of ['version', 'bundleId', 'sku', 'appleId', 'releaseBuild'])
  assert(metadata[0][key] === metadata[1][key], 'Locale mismatch: ' + key);
const notes = await readFile(join(root, 'apps/ios/AppStore/review-notes.txt'), 'utf8');
assert(
  notes.startsWith(
    `Review information for Agent Hub ${metadata[0].version} (${metadata[0].releaseBuild})`,
  ),
  'Review notes point to a different build',
);
assert(notes.length <= 4000, 'Review notes exceed the App Store Connect limit');

function plist(path) {
  return JSON.parse(
    execFileSync('plutil', ['-convert', 'json', '-o', '-', path], {
      encoding: 'utf8',
      maxBuffer: 1024 * 1024,
    }),
  );
}
function reasons(path, required) {
  const value = plist(path);
  assert(value.NSPrivacyTracking === false, 'Unexpected tracking declaration: ' + path);
  const rows = value.NSPrivacyAccessedAPITypes ?? [];
  for (const [category, codes] of Object.entries(required)) {
    const row = rows.find(
      (row) => row.NSPrivacyAccessedAPIType === 'NSPrivacyAccessedAPICategory' + category,
    );
    for (const code of codes)
      assert(
        row?.NSPrivacyAccessedAPITypeReasons?.includes(code),
        'Missing privacy reason ' + category + '/' + code + ': ' + path,
      );
  }
}
const privacy = {
  Resources: {
    UserDefaults: ['CA92.1', '1C8F.1'],
    FileTimestamp: ['C617.1', '3B52.1'],
    SystemBootTime: ['35F9.1'],
  },
  NotificationContent: { UserDefaults: ['1C8F.1'] },
  NotificationService: { UserDefaults: ['1C8F.1'], SystemBootTime: ['35F9.1'] },
  AttachmentAction: { FileTimestamp: ['C617.1', '3B52.1'] },
};
if (process.platform === 'darwin') {
  for (const [folder, required] of Object.entries(privacy))
    reasons(join(root, 'apps/ios', folder, 'PrivacyInfo.xcprivacy'), required);
} else {
  console.log('Source privacy plist checks skipped: requires macOS plutil');
}

if (options['--app']) {
  assert(process.platform === 'darwin', 'Distribution app verification requires macOS Apple tools');
  const app = options['--app'];
  const bundles = [
    [app, 'com.easyai.agenthub', privacy.Resources],
    [
      join(app, 'PlugIns/CodexNotificationService.appex'),
      'com.easyai.agenthub.NotificationService',
      privacy.NotificationService,
    ],
    [
      join(app, 'PlugIns/AgentHubNotificationContent.appex'),
      'com.easyai.agenthub.NotificationContent',
      privacy.NotificationContent,
    ],
    [
      join(app, 'PlugIns/AgentHubAttachmentAction.appex'),
      'com.easyai.agenthub.AttachmentAction',
      privacy.AttachmentAction,
    ],
  ];
  for (const [path, identifier, required] of bundles) {
    const info = plist(join(path, 'Info.plist'));
    assert(info.CFBundleIdentifier === identifier, 'Unexpected bundle: ' + identifier);
    assert(
      String(info.CFBundleVersion) === metadata[0].releaseBuild &&
        info.CFBundleShortVersionString === metadata[0].version,
      'Bundle version mismatch: ' + identifier,
    );
    reasons(join(path, 'PrivacyInfo.xcprivacy'), required);
    execFileSync('codesign', ['--verify', '--strict', path], { stdio: 'pipe' });
    const raw = execFileSync('codesign', ['-d', '--entitlements', ':-', path], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    const entitlements = JSON.parse(
      execFileSync('plutil', ['-convert', 'json', '-o', '-', '--', '-'], {
        input: raw,
        encoding: 'utf8',
      }),
    );
    assert(entitlements['get-task-allow'] !== true, 'Debug signing is not a distribution artifact');
    if (path === app)
      assert(
        entitlements['aps-environment'] === 'production',
        'Distribution APNs must use production',
      );
  }
  assert(
    Number(plist(join(app, 'Info.plist')).DTSDKName?.match(/^iphoneos(\d+)/)?.[1]) >= 26,
    'Recheck current Apple SDK submission requirements',
  );
}

if (options['--submission']) {
  assert(
    !/\[PENDING_|\bTODO\b|\bTBD\b/.test(notes),
    'Review notes still contain unresolved placeholders',
  );
  const path = options['--submission'];
  const record = JSON.parse(await readFile(path, 'utf8'));
  assert(
    record.build === metadata[0].releaseBuild,
    'Submission evidence points to a different build',
  );
  for (const gate of [
    'binary',
    'physicalDemo',
    'storeScreenshots',
    'reviewAccess',
    'privacyPublished',
    'privacyLabels',
    'availability',
    'accountFlows',
    'thirdPartyRights',
    'ageRating',
    'exportCompliance',
  ]) {
    const evidence = record.gates?.[gate];
    assert(
      ['passed', 'matched-reuse'].includes(evidence?.status) && evidence.evidence,
      'Submission blocked or unverified: ' + gate,
    );
  }
  assert(Array.isArray(record.artifacts) && record.artifacts.length > 0, 'No submission artifacts');
  for (const artifact of record.artifacts) {
    assert(artifact.path && /^[a-f0-9]{64}$/.test(artifact.sha256), 'Invalid artifact evidence');
    const bytes = await readFile(resolve(dirname(path), artifact.path));
    assert(
      createHash('sha256').update(bytes).digest('hex') === artifact.sha256,
      'Artifact changed: ' + artifact.path,
    );
  }
}
console.log(
  'App Store input checks passed' +
    (options['--app'] ? '; exported distribution bundles checked' : '') +
    (options['--submission']
      ? '; recorded submission gates and artifact hashes checked'
      : '; complete review access and other submission gates still require independent evidence'),
);
