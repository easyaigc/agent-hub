import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdir, mkdtemp, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateRawSync } from 'node:zlib';
import test from 'node:test';
import { correctZipUtf8Flags, packageHostConnect } from './package-host-connect.mjs';
import { createHash } from 'node:crypto';

function fixture({ name = Buffer.from('snow ☃/文件🙂.txt'), descriptor = false } = {}) {
  const body = Buffer.from('unchanged payload'),
    data = descriptor ? deflateRawSync(body) : body;
  let crc = 0xffffffff;
  for (const byte of body) {
    crc ^= byte;
    for (let i = 0; i < 8; i++) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
  }
  crc = (crc ^ 0xffffffff) >>> 0;
  const local = Buffer.alloc(30),
    central = Buffer.alloc(46),
    trailer = Buffer.alloc(descriptor ? 16 : 0),
    end = Buffer.alloc(22);
  local.writeUInt32LE(0x04034b50);
  local.writeUInt16LE(20, 4);
  local.writeUInt16LE(descriptor ? 8 : 0, 6);
  local.writeUInt16LE(descriptor ? 8 : 0, 8);
  local.writeUInt32LE(descriptor ? 0 : crc, 14);
  local.writeUInt32LE(descriptor ? 0 : data.length, 18);
  local.writeUInt32LE(descriptor ? 0 : body.length, 22);
  local.writeUInt16LE(name.length, 26);
  central.writeUInt32LE(0x02014b50);
  central.writeUInt16LE(0x0314, 4);
  central.writeUInt16LE(20, 6);
  central.writeUInt16LE(descriptor ? 8 : 0, 8);
  central.writeUInt16LE(descriptor ? 8 : 0, 10);
  central.writeUInt32LE(crc, 16);
  central.writeUInt32LE(data.length, 20);
  central.writeUInt32LE(body.length, 24);
  central.writeUInt16LE(name.length, 28);
  central.writeUInt32LE((0o120777 << 16) >>> 0, 38); // Preserve a Unix symlink mode.
  if (descriptor) {
    trailer.writeUInt32LE(0x08074b50);
    trailer.writeUInt32LE(crc, 4);
    trailer.writeUInt32LE(data.length, 8);
    trailer.writeUInt32LE(body.length, 12);
  }
  const centralOffset = local.length + name.length + data.length + trailer.length;
  end.writeUInt32LE(0x06054b50);
  end.writeUInt16LE(1, 8);
  end.writeUInt16LE(1, 10);
  end.writeUInt32LE(central.length + name.length, 12);
  end.writeUInt32LE(centralOffset, 16);
  return { bytes: Buffer.concat([local, name, data, trailer, central, name, end]), centralOffset };
}

for (const descriptor of [false, true]) {
  test('UTF-8 flags preserve every other byte with descriptor=' + descriptor, () => {
    const { bytes, centralOffset } = fixture({ descriptor }),
      before = Buffer.from(bytes),
      expected = Buffer.from(bytes);
    expected.writeUInt16LE((descriptor ? 8 : 0) | 0x800, 6);
    expected.writeUInt16LE((descriptor ? 8 : 0) | 0x800, centralOffset + 8);
    assert.deepEqual(correctZipUtf8Flags(bytes), expected);
    assert.deepEqual(correctZipUtf8Flags(bytes), expected); // Idempotent.
    assert.equal(before.readUInt32LE(centralOffset + 38), bytes.readUInt32LE(centralOffset + 38));
  });
}

for (const [name, mutate, pattern] of [
  ['invalid UTF-8', (f) => (f.bytes[f.centralOffset + 46] = 0xff), /UTF-8/],
  ['header filename mismatch', (f) => (f.bytes[30] = 0x78), /filenames differ/],
  ['header flag mismatch', (f) => f.bytes.writeUInt16LE(0x800, 6), /flags or compression/],
  ['CRC mismatch', (f) => f.bytes.writeUInt32LE(0, f.centralOffset + 16), /CRC or size/],
  [
    'central count mismatch',
    (f) => {
      f.bytes.writeUInt16LE(2, f.bytes.length - 14);
      f.bytes.writeUInt16LE(2, f.bytes.length - 12);
    },
    /central header|truncated/,
  ],
  ['ZIP64 sentinel', (f) => f.bytes.writeUInt32LE(0xffffffff, f.centralOffset + 20), /ZIP64/],
  ['overlapping payload', (f) => f.bytes.writeUInt32LE(1, f.centralOffset + 42), /local header/],
  ['encrypted entry', (f) => f.bytes.writeUInt16LE(1, f.centralOffset + 8), /encrypted/],
]) {
  test('rejects ' + name + ' without changing input', () => {
    const f = fixture();
    mutate(f);
    const before = Buffer.from(f.bytes);
    assert.throws(() => correctZipUtf8Flags(f.bytes), pattern);
    assert.deepEqual(f.bytes, before);
  });
}

test('rejects malformed descriptor, truncated ZIP and unsafe names', () => {
  const f = fixture({ descriptor: true });
  f.bytes.writeUInt32LE(0, f.centralOffset - 12);
  assert.throws(() => correctZipUtf8Flags(f.bytes), /descriptor/);
  assert.throws(() => correctZipUtf8Flags(f.bytes.subarray(0, 12)), /central-directory/);
  assert.throws(
    () => correctZipUtf8Flags(fixture({ name: Buffer.from('../escape') }).bytes),
    /unsafe/,
  );
  assert.throws(() => correctZipUtf8Flags(fixture({ name: Buffer.from([0xff]) }).bytes), /UTF-8/);
});

test(
  'real ditto Unicode archive is decoded by the default ZIP reader with intact symlinks and CRCs',
  { skip: process.platform !== 'darwin' },
  async () => {
    const root = fileURLToPath(
      new URL('../.local/task-management/final-package-checks/', import.meta.url),
    );
    await mkdir(root, { recursive: true, mode: 0o700 });
    const tmp = await mkdtemp(join(root, 'zip-boundary-'));
    try {
      const directory = join(tmp, 'fixture'),
        archive = join(tmp, 'fixture.zip');
      await mkdir(directory);
      await writeFile(join(directory, '雪☃🙂.txt'), 'actual ditto payload');
      await symlink('雪☃🙂.txt', join(directory, 'link'));
      execFileSync('/usr/bin/ditto', [
        '-c',
        '-k',
        '--norsrc',
        '--noqtn',
        '--keepParent',
        directory,
        archive,
      ]);
      await writeFile(archive, correctZipUtf8Flags(await readFile(archive)));
      execFileSync('/usr/bin/python3', [
        '-c',
        'import sys,zipfile,stat; z=zipfile.ZipFile(sys.argv[1]); assert z.testzip() is None; assert z.read("fixture/雪☃🙂.txt")==b"actual ditto payload"; i=z.getinfo("fixture/link"); assert stat.S_ISLNK(i.external_attr>>16); assert z.read(i).decode()=="雪☃🙂.txt"; assert all(i.flag_bits & 0x800 for i in z.infolist())',
        archive,
      ]);
    } finally {
      await rm(tmp, { recursive: true, force: true });
    }
  },
);

test(
  'canonical packager rejects an orphan compiled JS instead of publishing a mismatched bundle',
  { skip: process.platform !== 'darwin' },
  async () => {
    const root = fileURLToPath(
      new URL('../.local/task-management/final-package-checks/', import.meta.url),
    );
    await mkdir(root, { recursive: true, mode: 0o700 });
    const tmp = await mkdtemp(join(root, 'emit-boundary-'));
    const repository = join(tmp, 'source'),
      taskHubBundle = join(tmp, 'task-hub');
    const write = async (relative, body) => {
      const path = join(repository, relative);
      await mkdir(join(path, '..'), { recursive: true });
      await writeFile(path, body, { mode: 0o600 });
    };
    try {
      for (const name of [
        'AGENTS.md',
        'README.md',
        'LICENSE',
        'package.json',
        'pnpm-lock.yaml',
        'pnpm-workspace.yaml',
        'turbo.json',
        'eslint.config.mjs',
        '.editorconfig',
        '.gitignore',
        '.node-version',
        '.prettierignore',
        '.prettierrc.json',
        'settings.gradle.kts',
        'build.gradle.kts',
        'gradle.properties',
        'gradlew',
        'gradlew.bat',
      ])
        await write(name, name === 'package.json' ? '{"type":"module"}' : 'synthetic fixture\n');
      for (const directory of ['tooling', 'docs', '.github', 'gradle'])
        await mkdir(join(repository, directory), { recursive: true });
      const components = [
        'apps/host-agent',
        ...['codex-adapter', 'protocol', 'service-runtime', 'notification-runtime'].map(
          (name) => 'packages/' + name,
        ),
      ];
      const emitted = 'export const synthetic = true;\n';
      for (const component of components) {
        await write(
          component + '/package.json',
          JSON.stringify({
            name: '@codex-manage/' + component.split('/').at(-1),
            type: 'module',
            dependencies: {},
          }),
        );
        await write(component + '/src/index.ts', emitted);
        await write(component + '/src/types.d.ts', 'export declare const value: string;\n');
        await write(component + '/dist/index.js', emitted);
      }
      await mkdir(join(repository, 'packages/codex-adapter/windows'));
      await write('scripts/desktop-completion-notify-observer.py', '# synthetic fixture\n');
      const ciSources = {
        'scripts/ci/Dockerfile': 'FROM scratch\n',
        'scripts/ci/legacy-bootstrap.bash': '# synthetic bootstrap\n',
        'scripts/ci/legacy-sdkmanager.bash': '# synthetic sdk compatibility\n',
        'apps/ios/CodexManage.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved':
          '{"pins":[],"version":3}\n',
        'apps/ios/FilePreviewKit/Package.resolved': '{"pins":[],"version":3}\n',
        ...Object.fromEntries(
          [
            'apps/android/src/androidTest/java/com/agenthub/android/NativeSoundForeignWitnessProvider.java',
            'scripts/mobile/fixtures/android/os/Looper.java',
            'docs/plans/android-alignment-20261022/parallel/p8-easywork/jvm/android/util/Base64.java',
            'docs/plans/android-alignment-20261022/parallel/p8-easywork/authorization-fence/phase1/validation.init.gradle',
            'docs/plans/android-alignment-20261022/parallel/p8-easywork/authorization-fence/phase2/validation.init.gradle',
            'docs/plans/android-alignment-20261022/parallel/p8-easywork/authorization-fence/phase3/validation.init.gradle',
            'docs/plans/android-alignment-20261022/parallel/p8-easywork/boundaries.init.gradle',
            'docs/plans/android-alignment-20261022/parallel/p8-easywork/form-lease/phase1/validation.init.gradle',
            'docs/plans/android-alignment-20261022/parallel/p8-easywork/form-lease/phase10-canonical/validation.init.gradle',
            'docs/plans/android-alignment-20261022/parallel/p8-easywork/form-lease/phase2/resource-overlay.init.gradle',
            'docs/plans/android-alignment-20261022/parallel/p8-easywork/form-lease/phase5/validation.init.gradle',
            'docs/plans/android-alignment-20261022/parallel/p8-easywork/form-lease/phase9-text-visibility/validation.init.gradle',
            'docs/plans/android-alignment-20261022/parallel/p8-easywork/native-boundaries/numeric-probe.init.gradle',
            'docs/plans/android-alignment-20261022/parallel/p8-easywork/numeric-fix/validation.init.gradle',
            'apps/android/src/androidTest/java/com/agenthub/android/OwnedAcceptanceDocuments.java',
            'apps/android/src/androidTest/java/com/agenthub/android/OwnedExternalSourceActivity.java',
            'apps/android/src/androidTest/java/com/agenthub/android/OwnedPhotoMediaActivity.java',
            'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java',
            'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java',
            'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedExternalInboxColdDriver.java',
            'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java',
          ].map((name) => [name, '// reviewed test-only source fixture\n']),
        ),
      };
      for (const [name, body] of Object.entries(ciSources)) await write(name, body);
      const soundSources = [
        'apps/android/src/main/assets/chat-notification-sounds/v1/attention.wav',
        'apps/android/src/main/assets/chat-notification-sounds/v1/chime.wav',
        'apps/android/src/main/assets/chat-notification-sounds/v1/soft.wav',
        'apps/android/src/androidTest/assets/chat-notification-sounds/synthetic-tone.m4a',
        'apps/android/src/androidTest/assets/chat-notification-sounds/synthetic-tone.mp3',
      ];
      const soundBytes = new Map();
      for (const name of soundSources) {
        const bytes = await readFile(new URL('../' + name, import.meta.url));
        soundBytes.set(name, bytes);
        await write(name, bytes);
      }
      await write('skills/codex-host-connect/SKILL.md', 'Synthetic packaging fixture.\n');
      for (const name of ['bonjour-service', 'qrcode']) {
        await write(
          'node_modules/' + name + '/package.json',
          JSON.stringify({ name, version: '1.0.0', main: 'index.js' }),
        );
        await write('node_modules/' + name + '/index.js', 'module.exports = {};\n');
      }
      await mkdir(taskHubBundle);
      await writeFile(
        join(taskHubBundle, 'manifest.json'),
        JSON.stringify({
          format: 1,
          contractVersion: '1',
          files: {},
          payloadSha256: createHash('sha256').update('{}').digest('hex'),
          primaryPlatforms: ['darwin'],
          architecture: process.arch,
        }),
      );
      const valid = await packageHostConnect({
        repository,
        taskHubBundle,
        output: join(tmp, 'valid.zip'),
      });
      assert.match(valid.sha256, /^[a-f0-9]{64}$/);
      for (const [name, bytes] of soundBytes)
        assert.deepEqual(
          execFileSync('/usr/bin/unzip', [
            '-p',
            valid.archive,
            'codex-host-connect/assets/codex-manage/' + name,
          ]),
          bytes,
        );
      for (const [name, body] of Object.entries(ciSources))
        assert.equal(
          execFileSync(
            '/usr/bin/unzip',
            ['-p', valid.archive, 'codex-host-connect/assets/codex-manage/' + name],
            { encoding: 'utf8' },
          ),
          body,
        );
      for (const location of ['packages/codex-adapter', 'node_modules/@codex-manage/codex-adapter'])
        assert.equal(
          execFileSync(
            '/usr/bin/unzip',
            [
              '-p',
              valid.archive,
              'codex-host-connect/assets/codex-manage/' + location + '/dist/index.js',
            ],
            { encoding: 'utf8' },
          ),
          emitted,
        );
      const changedSound = soundSources[0];
      await write(changedSound, Buffer.from('changed unreviewed bytes'));
      const changedSoundOutput = join(tmp, 'changed-sound.zip');
      await assert.rejects(
        packageHostConnect({ repository, taskHubBundle, output: changedSoundOutput }),
        /Reviewed synthetic source fingerprint changed/,
      );
      await assert.rejects(readFile(changedSoundOutput), { code: 'ENOENT' });
      await write(changedSound, soundBytes.get(changedSound));
      const unknownSound = 'apps/android/src/androidTest/assets/private-recording.m4a';
      await write(unknownSound, soundBytes.get(changedSound));
      const unknownSoundOutput = join(tmp, 'unknown-sound.zip');
      await assert.rejects(
        packageHostConnect({ repository, taskHubBundle, output: unknownSoundOutput }),
        /Review unknown source file.*private-recording\.m4a/,
      );
      await assert.rejects(readFile(unknownSoundOutput), { code: 'ENOENT' });
      await rm(join(repository, unknownSound));
      const unknown = 'apps/android/src/androidTest/java/Unknown.java';
      await write(unknown, '// not reviewed for distribution\n');
      const unknownOutput = join(tmp, 'unknown.zip');
      await assert.rejects(
        packageHostConnect({ repository, taskHubBundle, output: unknownOutput }),
        /Review unknown source file.*Unknown\.java/,
      );
      await assert.rejects(readFile(unknownOutput), { code: 'ENOENT' });
      await rm(join(repository, unknown));
      const orphan = 'packages/codex-adapter/dist/orphan.js';
      await write(orphan, 'export const stale = true;\n');
      const output = join(tmp, 'rejected.zip');
      await assert.rejects(
        packageHostConnect({ repository, taskHubBundle, output }),
        /Compiled runtime inventory.*packages\/codex-adapter.*orphan\.js.*clean frozen source/,
      );
      await assert.rejects(readFile(output), { code: 'ENOENT' });
      await assert.rejects(readFile(output + '.sha256'), { code: 'ENOENT' });
      assert.equal(
        await readFile(join(repository, orphan), 'utf8'),
        'export const stale = true;\n',
      );
    } finally {
      await rm(tmp, { recursive: true, force: true });
    }
  },
);
