import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdir, mkdtemp, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import { join, dirname, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { taskHubNativeFingerprint, freezeTaskHubNativeInputs } from './task-hub-native-inputs.mjs';

test('frozen Xcode SRCROOT retains its relative shared build inputs and executable wrapper', async () => {
  const root = await mkdtemp(join(tmpdir(), 'task-hub-native-inputs-'));
  try {
    const source = join(root, 'source'),
      frozen = join(root, 'frozen');
    const inputs = {
      'apps/ios/project.txt': 'project',
      'scripts/mobile/build-ios-core.sh': '#!/bin/sh\nset -eu\ncd "$SRCROOT/../.."\n./gradlew\n',
      gradlew: '#!/bin/sh\ncat packages/mobile-core/rule.txt\n',
      'packages/mobile-core/rule.txt': 'captured shared rule\n',
    };
    for (const [path, text] of Object.entries(inputs)) {
      await mkdir(dirname(join(source, path)), { recursive: true });
      await writeFile(join(source, path), text, { mode: path === 'gradlew' ? 0o700 : 0o600 });
    }
    const snapshot = await taskHubNativeFingerprint(source, Object.keys(inputs));
    await freezeTaskHubNativeInputs(source, frozen, snapshot);
    const srcroot = join(frozen, 'apps/ios');
    const script = resolve(srcroot, '../../scripts/mobile/build-ios-core.sh');
    assert.equal(
      execFileSync('/bin/sh', [script], {
        env: { ...process.env, SRCROOT: srcroot },
        encoding: 'utf8',
      }),
      'captured shared rule\n',
    );
    await writeFile(join(source, 'packages/mobile-core/rule.txt'), 'later workspace change');
    assert.equal(
      await readFile(join(frozen, 'packages/mobile-core/rule.txt'), 'utf8'),
      inputs['packages/mobile-core/rule.txt'],
    );
    await assert.rejects(
      freezeTaskHubNativeInputs(source, join(root, 'changed'), snapshot),
      /changed before freeze/,
    );
  } finally {
    await rm(root, { recursive: true });
  }
});

test('unsafe native input paths and symlinked sources fail closed', async () => {
  const root = await mkdtemp(join(tmpdir(), 'task-hub-native-reject-'));
  try {
    await writeFile(join(root, 'original'), 'input');
    await symlink(join(root, 'original'), join(root, 'link'));
    await assert.rejects(taskHubNativeFingerprint(root, ['link']), /regular file/);
    await assert.rejects(taskHubNativeFingerprint(root, ['../outside']));
  } finally {
    await rm(root, { recursive: true });
  }
});
