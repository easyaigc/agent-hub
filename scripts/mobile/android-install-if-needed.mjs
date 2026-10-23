import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { promisify } from 'node:util';

const run = promisify(execFile);
const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');

/** Compare actual installed bytes, preserving app data and avoiding repeated OEM install prompts. */
export async function installAndroidApkIfNeeded({
  adb,
  serial,
  packageName,
  apk,
  allowPhysical = false,
}) {
  assert(
    serial && (serial.startsWith('emulator-') || allowPhysical),
    'Physical installation requires an explicitly selected authorized device',
  );
  assert(
    ['com.easyai.agenthub.android.debug', 'com.easyai.agenthub.android.debug.test'].includes(
      packageName,
    ),
  );
  const call = async (...args) =>
    run(adb, ['-s', serial, ...args], {
      timeout: 60_000,
      maxBuffer: 32 * 1024 * 1024,
      encoding: 'buffer',
    });
  const state = (await call('get-state')).stdout.toString().trim();
  assert.equal(state, 'device');
  const expected = sha(await readFile(apk));
  const installed = async () => {
    let result;
    try {
      result = await call('shell', 'pm', 'path', packageName);
    } catch (error) {
      if (error.code === 1 && !error.stdout?.length && !error.stderr?.length) return null;
      throw error;
    }
    const paths = result.stdout.toString().trim().split('\n').filter(Boolean);
    if (paths.length === 0) return null;
    assert.equal(paths.length, 1, 'This full-APK examiner does not replace split installations');
    assert(paths[0].startsWith('package:/data/app/') && paths[0].endsWith('/base.apk'));
    return sha((await call('exec-out', 'cat', paths[0].slice('package:'.length))).stdout);
  };
  if ((await installed()) === expected)
    return { packageName, sha256: expected, installed: false, reusedMatching: true };
  const result = await call('install', '--no-streaming', '-r', apk);
  assert(/\bSuccess\b/.test(result.stdout.toString()), 'Installation must actually succeed');
  assert.equal(await installed(), expected, 'Installed package must match the requested APK');
  return { packageName, sha256: expected, installed: true, reusedMatching: false };
}
