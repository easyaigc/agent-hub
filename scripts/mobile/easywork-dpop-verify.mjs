import assert from 'node:assert/strict';
import { createPublicKey, createHash, verify } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';

// Independent Node/OpenSSL verification of Android's system key signatures.
const file = process.argv[2];
assert(file, 'Pass the native public-proofs.json path');
const source = JSON.parse(await readFile(file, 'utf8'));
const key = createPublicKey({ key: source.jwk, format: 'jwk' });
const ids = new Set();
for (const [index, proof] of source.proofs.entries()) {
  const parts = proof.split('.');
  assert.equal(parts.length, 3);
  const header = JSON.parse(Buffer.from(parts[0], 'base64url'));
  const payload = JSON.parse(Buffer.from(parts[1], 'base64url'));
  assert.equal(header.typ, 'dpop+jwt');
  assert.equal(header.alg, 'ES256');
  assert.deepEqual(header.jwk, source.jwk);
  assert.equal(header.jwk.d, undefined);
  const signature = Buffer.from(parts[2], 'base64url');
  assert.equal(signature.length, 64);
  assert(
    verify(
      'sha256',
      Buffer.from(parts[0] + '.' + parts[1]),
      { key, dsaEncoding: 'ieee-p1363' },
      signature,
    ),
  );
  assert.equal(payload.htm, 'GET');
  assert.equal(payload.htu, 'https://relay.example.test/v1/remote-access/relay/pending');
  assert.equal(
    payload.ath,
    createHash('sha256').update(source.accessToken, 'ascii').digest('base64url'),
  );
  assert(Number.isSafeInteger(payload.iat));
  assert(Math.abs(Date.now() / 1000 - payload.iat) < 600, 'Verify fresh native proofs');
  assert.match(
    payload.jti,
    /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/,
  );
  assert(!ids.has(payload.jti));
  ids.add(payload.jti);
  assert.equal(payload.nonce, index === 1 ? 'public-server-nonce' : undefined);
  assert(
    !verify(
      'sha256',
      Buffer.from(parts[0] + '.' + parts[1] + 'tampered'),
      { key, dsaEncoding: 'ieee-p1363' },
      signature,
    ),
  );
}
const canonicalJwk = JSON.stringify({
  crv: source.jwk.crv,
  kty: source.jwk.kty,
  x: source.jwk.x,
  y: source.jwk.y,
});
assert.equal(source.thumbprint, createHash('sha256').update(canonicalJwk).digest('base64url'));
assert(source.persistedKeyVerified);
const report = {
  status: 'passed',
  api: source.api,
  independentNodeSignatures: source.proofs.length,
  tamperedSignaturesRejected: source.proofs.length,
  persistedSystemKey: true,
  actualRelayAuthorization: 'not covered by this auxiliary boundary',
};
await writeFile(
  file.replace(/public-proofs\.json$/, 'node-verification.json'),
  JSON.stringify(report, null, 2) + '\n',
);
console.log(JSON.stringify(report));
