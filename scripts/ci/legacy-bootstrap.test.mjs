import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const bootstrap = fileURLToPath(new URL('./legacy-bootstrap.bash', import.meta.url));

test('legacy prerequisite build preserves revision scope and real command failures', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'ci-bootstrap-'));
  try {
    const git = (...args) => {
      const result = spawnSync('git', args, { cwd: root, encoding: 'utf8' });
      assert.equal(result.status, 0, result.stderr);
      return result.stdout.trim();
    };
    git('init', '--quiet');
    git(
      '-c',
      'user.name=CI fixture',
      '-c',
      'user.email=ci@example.invalid',
      'commit',
      '--quiet',
      '--allow-empty',
      '--no-gpg-sign',
      '-m',
      'fixture',
    );
    const revision = git('rev-parse', 'HEAD');
    await mkdir(path.join(root, 'bin'));
    await writeFile(
      path.join(root, 'bin/pnpm'),
      '#!/bin/sh\nprintf "%s\\n" "$1" >> "$COMMAND_LOG"\n' +
        'if [ "$1" = build ]; then exit "${BUILD_EXIT:-0}"; fi\nexit "${CHECK_EXIT:-0}"\n',
      { mode: 0o700 },
    );
    for (const row of [
      { name: 'allowed', expected: ['build', 'check'] },
      { name: 'other revision', allow: '0'.repeat(40), expected: ['check'] },
      { name: 'other repo', repo: 'example/other', expected: ['check'] },
      { name: 'existing build', built: true, expected: ['check'] },
      { name: 'failed build', buildExit: '19', status: 19, expected: ['build'] },
      { name: 'failed check', checkExit: '23', status: 23, expected: ['build', 'check'] },
    ]) {
      const dist = path.join(root, 'packages/protocol/dist');
      await rm(path.join(root, 'packages'), { recursive: true, force: true });
      if (row.built) {
        await mkdir(dist, { recursive: true });
        await writeFile(path.join(dist, 'index.js'), '');
      }
      const log = path.join(root, 'commands');
      await writeFile(log, '');
      const result = spawnSync('bash', ['-c', 'pnpm check'], {
        cwd: root,
        encoding: 'utf8',
        // Linux bash treats Node's default stdin socket as a remote shell and
        // reads .bashrc instead of BASH_ENV. CI run steps have no interactive stdin.
        stdio: ['ignore', 'pipe', 'pipe'],
        env: {
          ...process.env,
          PATH: path.join(root, 'bin') + path.delimiter + process.env.PATH,
          BASH_ENV: bootstrap,
          GITHUB_REPOSITORY: row.repo ?? 'BCAI/agent-hub-app',
          AGENT_HUB_CI_LEGACY_SHAS: row.allow ?? revision,
          COMMAND_LOG: log,
          BUILD_EXIT: row.buildExit ?? '0',
          CHECK_EXIT: row.checkExit ?? '0',
        },
      });
      assert.equal(result.status, row.status ?? 0, row.name + ': ' + result.stderr);
      assert.deepEqual((await readFile(log, 'utf8')).trim().split('\n'), row.expected, row.name);
    }
    const sdkmanager = path.join(root, 'bin/sdkmanager');
    await writeFile(
      sdkmanager,
      await readFile(new URL('./legacy-sdkmanager.bash', import.meta.url)),
      { mode: 0o700 },
    );
    await writeFile(
      sdkmanager + '.real',
      '#!/bin/sh\nprintf "%s\\0" "$@" > "$COMMAND_LOG"\nexit "${SDK_EXIT:-0}"\n',
      { mode: 0o700 },
    );
    for (const row of [
      {
        name: 'allowed SDK spelling',
        args: ['platforms;android-37'],
        expected: ['platforms;android-37.0'],
      },
      {
        name: 'other SDK revision',
        allow: '0'.repeat(40),
        args: ['platforms;android-37'],
        expected: ['platforms;android-37'],
      },
      {
        name: 'other SDK repo',
        repo: 'example/other',
        args: ['platforms;android-37'],
        expected: ['platforms;android-37'],
      },
      {
        name: 'canonical SDK package',
        args: ['platforms;android-37.0'],
        expected: ['platforms;android-37.0'],
      },
      {
        name: 'SDK arguments preserved',
        args: ['--sdk_root=path with spaces', 'platforms;android-37', 'build-tools;37.0.0'],
        expected: ['--sdk_root=path with spaces', 'platforms;android-37.0', 'build-tools;37.0.0'],
      },
      {
        name: 'SDK failure propagated',
        args: ['platforms;android-37'],
        sdkExit: '31',
        status: 31,
        expected: ['platforms;android-37.0'],
      },
    ]) {
      const log = path.join(root, 'sdk-commands');
      const result = spawnSync(sdkmanager, row.args, {
        cwd: root,
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'pipe'],
        env: {
          ...process.env,
          BASH_ENV: '',
          GITHUB_REPOSITORY: row.repo ?? 'BCAI/agent-hub-app',
          AGENT_HUB_CI_LEGACY_SHAS: row.allow ?? revision,
          COMMAND_LOG: log,
          SDK_EXIT: row.sdkExit ?? '0',
        },
      });
      assert.equal(result.status, row.status ?? 0, row.name + ': ' + result.stderr);
      assert.deepEqual(
        (await readFile(log, 'utf8')).split('\0').slice(0, -1),
        row.expected,
        row.name,
      );
    }
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
