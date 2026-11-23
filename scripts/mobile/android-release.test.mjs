import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { X509Certificate } from 'node:crypto';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { certificateDetails, verifyApkOutput } from './android-release.mjs';

test('APK verification rejects absent, unexpected and multiple signing identities', () => {
  const expected = 'A'.repeat(64);
  const line = (number, value) => `Signer #${number} certificate SHA-256 digest: ${value}\n`;
  verifyApkOutput(line(1, expected.toLowerCase()), expected);
  assert.throws(() => verifyApkOutput('DOES NOT VERIFY\n', expected));
  assert.throws(() => verifyApkOutput(line(1, 'B'.repeat(64)), expected));
  assert.throws(() => verifyApkOutput(line(1, expected) + line(2, expected), expected));
});

test('filing export uses certificate digest and RSA modulus rather than APK hash or PEM', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'agenthub-release-cert-'));
  try {
    execFileSync(
      'openssl',
      [
        'req',
        '-x509',
        '-newkey',
        'rsa:2048',
        '-nodes',
        '-days',
        '1',
        '-subj',
        '/CN=Disposable release export test',
        '-keyout',
        join(dir, 'key.pem'),
        '-out',
        join(dir, 'cert.pem'),
      ],
      { stdio: 'ignore' },
    );
    const pem = await readFile(join(dir, 'cert.pem'));
    const cert = new X509Certificate(pem);
    const details = certificateDetails(pem);
    const digest = execFileSync(
      'openssl',
      ['x509', '-in', join(dir, 'cert.pem'), '-noout', '-fingerprint', '-md5'],
      { encoding: 'utf8' },
    );
    assert.equal(details.certificateMd5, digest.split('=')[1].trim().replaceAll(':', ''));
    const modulus = execFileSync(
      'openssl',
      ['x509', '-in', join(dir, 'cert.pem'), '-noout', '-modulus'],
      { encoding: 'utf8' },
    );
    assert.equal(details.rsaModulusHex, modulus.split('=')[1].trim());
    assert.equal(details.certificateSha256, cert.fingerprint256.replaceAll(':', ''));
    assert.match(details.publicKeyPem, /^-----BEGIN PUBLIC KEY-----/);
    assert.equal(details.certificateMd5.length, 32);
  } finally {
    await rm(dir, { recursive: true });
  }
});
