import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFile, writeFile, access } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import path from 'node:path';
import prettier from 'prettier';

const manifestPath = 'docs/product/mobile-parity.manifest.json';
const templatePath = 'docs/product/mobile-parity.template.md';
const outputPath = 'docs/product/mobile-parity.md';
const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
const recordKeys = (object, allowed) => {
  assert(object && typeof object === 'object' && !Array.isArray(object));
  assert(
    Object.keys(object).every((key) => allowed.includes(key)),
    'Unknown manifest field',
  );
};
const list = (value) => {
  assert(Array.isArray(value) && value.length > 0 && new Set(value).size === value.length);
  return value;
};
const text = (value) => assert(typeof value === 'string' && value.trim() && !/[|\r\n]/.test(value));
export function safeSource(value) {
  assert(typeof value === 'string' && /^(apps|packages|scripts|design)\//.test(value));
  assert(!value.split('/').some((part) => ['..', '.', ''].includes(part)));
  assert(!value.includes('\\') && !path.posix.isAbsolute(value));
  return value;
}

export function validateManifest(manifest) {
  recordKeys(manifest, [
    'version',
    'product',
    'baselineCommit',
    'sourceAliases',
    'groups',
    'features',
    'changes',
  ]);
  assert.equal(manifest.version, 1);
  assert(/^[a-f0-9]{7,64}$/.test(manifest.baselineCommit));
  text(manifest.product);
  list(manifest.groups).forEach(text);
  assert(manifest.sourceAliases && Object.keys(manifest.sourceAliases).length);
  for (const [alias, files] of Object.entries(manifest.sourceAliases)) {
    assert(/^[A-Za-z]+$/.test(alias));
    list(files).forEach(safeSource);
  }
  const seen = new Set();
  for (const feature of list(manifest.features)) {
    recordKeys(feature, [
      'id',
      'group',
      'assertion',
      'sourceAliases',
      'androidStages',
      'scenarios',
      'ui',
      'revision',
      'implementation',
      'verification',
      'sharedImplementation',
      'sourceNotes',
    ]);
    assert(
      /^F\d{2,}$/.test(feature.id) && !seen.has(feature.id),
      'Duplicate or invalid feature ID',
    );
    seen.add(feature.id);
    assert(manifest.groups.includes(feature.group));
    text(feature.assertion);
    if (feature.sourceNotes) text(feature.sourceNotes);
    list(feature.androidStages).forEach((stage) => assert(/^A[0-8]$/.test(stage)));
    list(feature.sourceAliases).forEach((alias) => assert(manifest.sourceAliases[alias]));
    list(feature.scenarios).forEach((id) => assert(/^S(?:0[1-9]|1[0-8])$/.test(id)));
    list(feature.ui).forEach((id) => assert(/^UI(?:0[1-9]|1[0-9]|2[01])$/.test(id)));
    assert(Number.isSafeInteger(feature.revision) && feature.revision > 0);
    recordKeys(feature.implementation, ['ios', 'android']);
    recordKeys(feature.verification, ['ios', 'android']);
    for (const platform of ['ios', 'android']) {
      assert(
        [
          'code-present',
          'planned',
          'in-progress',
          'implemented',
          'platform-not-applicable',
        ].includes(feature.implementation[platform]),
      );
      assert(
        [
          'not-verified-for-current-product',
          'not-verified',
          'passed',
          'reused-matching',
          'failed',
        ].includes(feature.verification[platform]),
      );
      if (['passed', 'reused-matching'].includes(feature.verification[platform]))
        assert.equal(
          feature.implementation[platform],
          'implemented',
          'Code presence is not verified delivery',
        );
    }
    if (feature.sharedImplementation) {
      recordKeys(feature.sharedImplementation, ['status', 'scope', 'sources']);
      assert(
        ['planned', 'in-progress', 'implemented'].includes(feature.sharedImplementation.status),
      );
      text(feature.sharedImplementation.scope);
      list(feature.sharedImplementation.sources).forEach(safeSource);
    }
  }
  for (let n = 1; n <= 58; n++)
    assert(seen.has('F' + String(n).padStart(2, '0')), 'Existing scope removed');
  assert(Array.isArray(manifest.changes));
  const changes = new Set();
  for (const change of manifest.changes) {
    recordKeys(change, ['id', 'baseCommit', 'features', 'summary', 'files']);
    text(change.id);
    text(change.summary);
    assert(!changes.has(change.id));
    changes.add(change.id);
    assert(/^[a-f0-9]{7,64}$/.test(change.baseCommit));
    list(change.features).forEach((id) =>
      assert(seen.has(id), 'Change references unknown feature'),
    );
    assert(change.files && Object.keys(change.files).length);
    for (const [file, digest] of Object.entries(change.files)) {
      safeSource(file);
      assert(/^[a-f0-9]{64}$/.test(digest));
    }
  }
  return manifest;
}

export function validateChangeCoverage(manifest, changed, currentHashes) {
  const governed = new Set(Object.values(manifest.sourceAliases).flat());
  for (const feature of manifest.features)
    for (const file of feature.sharedImplementation?.sources ?? []) governed.add(file);
  for (const file of changed) {
    if (
      !governed.has(file) &&
      !/^(apps\/(?:android|host-agent|relay)\/src|packages\/(?:mobile-core|codex-adapter)\/src|packages\/protocol\/(?:src|schemas|fixtures\/mobile))\//.test(
        file,
      ) &&
      !/^apps\/ios\/.*\.(?:swift|plist)$/.test(file) &&
      file !== 'design/mobile/tokens.json'
    )
      continue;
    assert(/^[a-f0-9]{64}$/.test(currentHashes.get(file)), 'Missing current source digest');
    assert(
      manifest.changes.some((change) => change.files[file] === currentHashes.get(file)),
      'Feature change record missing or stale for ' + file,
    );
  }
}

export async function renderManifest(manifest, template) {
  validateManifest(manifest);
  const marker = '<!-- mobile-parity:generated-tables -->';
  assert.equal(template.split(marker).length, 2, 'Exactly one generated table marker required');
  const lines = [
    '<!-- Generated from mobile-parity.manifest.json. Do not edit these tables. -->',
    '## 源码与证据入口',
    '',
    '表内缩写为源码核对入口，不能代表实时能力或真实验收通过。',
    '',
    '| 缩写 | 当前 iOS / 协议来源 |',
    '| --- | --- |',
  ];
  for (const [alias, files] of Object.entries(manifest.sourceAliases))
    lines.push(
      '| ' +
        alias +
        ' | ' +
        files.map((file) => '[' + path.posix.basename(file) + '](../../' + file + ')').join('、') +
        ' |',
    );
  for (const group of manifest.groups) {
    lines.push(
      '',
      '## ' + group,
      '',
      '| ID | 功能及必须一致的断言 | iOS 实现来源 | Android 状态／阶段 | 场景 | UI |',
      '| --- | --- | --- | --- | --- | --- |',
    );
    for (const feature of manifest.features.filter((entry) => entry.group === group))
      lines.push(
        '| ' +
          [
            feature.id,
            feature.assertion,
            feature.sourceAliases.join('、') +
              (feature.sourceNotes ? '；' + feature.sourceNotes : ''),
            {
              planned: '待开发',
              'in-progress': '开发中',
              implemented: '已实现',
              'platform-not-applicable': '平台不适用',
              'code-present': '存在源码',
            }[feature.implementation.android] +
              ' ' +
              feature.androidStages.join('、'),
            feature.scenarios.join('、'),
            feature.ui.join('、'),
          ].join(' | ') +
          ' |',
      );
  }
  return prettier.format(template.replace(marker, lines.join('\n')), { parser: 'markdown' });
}

async function main() {
  const manifest = validateManifest(JSON.parse(await readFile(manifestPath, 'utf8')));
  for (const file of new Set(Object.values(manifest.sourceAliases).flat())) await access(file);
  for (const feature of manifest.features)
    for (const file of feature.sharedImplementation?.sources ?? []) await access(file);
  const acceptance = await readFile('docs/acceptance/mobile-parity.md', 'utf8');
  const ui = await readFile('docs/design/mobile-parity.md', 'utf8');
  for (const feature of manifest.features) {
    for (const id of feature.scenarios)
      assert(acceptance.includes('| ' + id + ' '), 'Unknown acceptance scene');
    for (const id of feature.ui)
      assert(new RegExp('\\| ' + id + '\\s+\\|').test(ui), 'Unknown UI page');
  }
  const rendered = await renderManifest(manifest, await readFile(templatePath, 'utf8'));
  if (process.argv.includes('--write')) await writeFile(outputPath, rendered);
  else
    assert.equal(await readFile(outputPath, 'utf8'), rendered, 'Run pnpm generate:mobile-parity');
  const baseIndex = process.argv.indexOf('--base');
  const base =
    baseIndex >= 0
      ? process.argv[baseIndex + 1]
      : process.env.MOBILE_PARITY_BASE_SHA || manifest.baselineCommit;
  if (base) {
    execFileSync('git', ['rev-parse', '--verify', base + '^{commit}']);
    const changed = [
      ...new Set(
        [
          execFileSync('git', ['diff', '--name-only', base, '--'], { encoding: 'utf8' }),
          execFileSync('git', ['ls-files', '--others', '--exclude-standard'], { encoding: 'utf8' }),
        ].flatMap((value) => value.trim().split('\n').filter(Boolean)),
      ),
    ];
    const hashes = new Map();
    for (const file of changed) {
      try {
        hashes.set(file, hash(await readFile(file)));
      } catch {
        hashes.set(file, hash('deleted\0' + file));
      }
    }
    validateChangeCoverage(manifest, changed, hashes);
  }
  console.log(
    manifest.features.length +
      ' feature definitions; source/UI/scene/generated-table checks passed. Product acceptance remains separate.',
  );
}
if (process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url)
  await main();
