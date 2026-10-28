import assert from 'node:assert/strict';
import { createHash, generateKeyPairSync, randomUUID, sign } from 'node:crypto';
import test from 'node:test';
import { ColdContract } from './fixture.mjs';
import {
  canonical,
  MOBILE_PUBLIC,
  openPublicDelivery,
} from '../authorization-boundaries/fixture.mjs';

// Fake PIDs below validate the contract only; these are not Android cold-process evidence.
const digest = (s) => createHash('sha256').update(s).digest('base64url');
function prepared() {
  const c = new ColdContract(randomUUID()),
    keys = generateKeyPairSync('ec', { namedCurve: 'prime256v1' }),
    jwk = keys.publicKey.export({ format: 'jwk' });
  function request(method, path, body, token) {
    const url = 'http://127.0.0.1:12345' + path,
      raw = body ? canonical(body) : '';
    const header = { typ: 'dpop+jwt', alg: 'ES256', jwk },
      payload = {
        htm: method,
        htu: url,
        iat: Math.floor(Date.now() / 1000),
        jti: randomUUID(),
        ...(token ? { ath: digest(token) } : {}),
        ...(raw ? { bht: digest(raw) } : {}),
      };
    const input =
      Buffer.from(canonical(header)).toString('base64url') +
      '.' +
      Buffer.from(canonical(payload)).toString('base64url');
    const signature = sign('sha256', Buffer.from(input), {
      key: keys.privateKey,
      dsaEncoding: 'ieee-p1363',
    });
    return c.dispatch({
      method,
      url,
      raw,
      authorization: token ? 'DPoP ' + token : undefined,
      dpop: input + '.' + signature.toString('base64url'),
    });
  }
  const claim = request('POST', '/v2/remote-access/pairing/claims', {
    operationId: randomUUID(),
    connectionCode: '0123456789ABCDEF',
    device: {
      deviceId: 'p8-fence-' + c.caseId + '-a',
      platform: 'android',
      publicKey: MOBILE_PUBLIC,
      authPublicKey: jwk,
    },
  }).reply;
  const pending = openPublicDelivery(claim.sealedDelivery, claim.claimId);
  const approved = request(
    'GET',
    '/v2/remote-access/pairing/claims/' + claim.claimId,
    null,
    pending.pendingToken,
  ).reply;
  const grant = openPublicDelivery(approved.sealedDelivery, claim.claimId);
  const refreshId = randomUUID();
  assert(
    request(
      'POST',
      '/v2/remote-access/device-grants/refresh',
      { operationId: refreshId },
      grant.refreshToken,
    ).hold,
  );
  const revokeId = randomUUID();
  assert(
    request(
      'DELETE',
      '/v2/remote-access/device-grants/' + grant.grantId,
      { operationId: revokeId },
      grant.accessToken,
    ).drop,
  );
  const row = c.issuer.requests.find((x) => x.method === 'DELETE'),
    proof = {
      caseId: c.caseId,
      phase: 'writer-frozen',
      pid: 10001,
      processStartElapsed: 1000,
      sourceSha256: 'a'.repeat(64),
      appSha256: 'b'.repeat(64),
      runnerSha256: 'c'.repeat(64),
      fenceSha256: 'd'.repeat(64),
      savedSha256: 'e'.repeat(64),
      ticketSha256: 'f'.repeat(64),
      revokeOperationId: revokeId,
      revokeBodyHash: row.bodyHash,
      revokeAuthorizationHash: row.tokenHash,
      signerThumbprint: row.thumbprint,
    };
  const writer = c.freeze(proof);
  const reader = {
    writerSha256: writer.sha256,
    pid: 20002,
    processStartElapsed: 2000,
    sourceSha256: proof.sourceSha256,
    appSha256: proof.appSha256,
    runnerSha256: proof.runnerSha256,
  };
  return { c, request, grant, revokeId, proof, writer, reader };
}
test('frozen writer survives reader transition and only the original DELETE resumes', () => {
  const { c, request, grant, revokeId, proof, writer, reader } = prepared();
  const before = canonical(writer);
  c.beginReader(reader, true);
  request(
    'DELETE',
    '/v2/remote-access/device-grants/' + grant.grantId,
    { operationId: revokeId },
    grant.accessToken,
  );
  const finish = {
    ...reader,
    phase: 'reader-complete',
    writerPid: proof.pid,
    revokeOperationId: revokeId,
    signerThumbprint: proof.signerThumbprint,
    writerReceiptUnchanged: true,
    pendingLateSaveRejected: true,
    confirmedLateSaveRejected: true,
    grantRemainsAbsent: true,
  };
  const complete = c.finish(finish);
  assert.equal(c.phase, 'complete');
  assert.equal(canonical(c.writer), before);
  assert.equal(c.finish(finish).sha256, complete.sha256);
});
test('writer proof cannot be replaced and changed build cannot begin a reader', () => {
  const { c, proof, writer, reader } = prepared();
  assert.equal(c.freeze(proof).sha256, writer.sha256);
  assert.throws(() => c.freeze({ ...proof, pid: 99999 }));
  assert.throws(() => c.beginReader({ ...reader, appSha256: '0'.repeat(64) }, true));
  assert.equal(c.phase, 'writer-frozen');
});
test('same PID, old process start or still-open writer socket cannot claim cold recovery', () => {
  const { c, proof, reader } = prepared();
  for (const changed of [{ pid: proof.pid }, { processStartElapsed: proof.processStartElapsed }])
    assert.throws(() => c.beginReader({ ...reader, ...changed }, true));
  assert.throws(() => c.beginReader(reader, false));
  assert.equal(c.issuer.requests.length, 4);
  assert.equal(c.phase, 'writer-frozen');
});
test('reader cannot create another claim, refresh, ID or token', () => {
  const { c, reader, request, grant, revokeId } = prepared();
  c.beginReader(reader, true);
  for (const [method, path, body, token] of [
    ['POST', '/v2/remote-access/pairing/claims', { operationId: randomUUID() }, null],
    [
      'POST',
      '/v2/remote-access/device-grants/refresh',
      { operationId: randomUUID() },
      grant.refreshToken,
    ],
    [
      'DELETE',
      '/v2/remote-access/device-grants/' + grant.grantId,
      { operationId: randomUUID() },
      grant.accessToken,
    ],
    [
      'DELETE',
      '/v2/remote-access/device-grants/' + grant.grantId,
      { operationId: revokeId },
      'wrong-original-token',
    ],
  ])
    assert.throws(() => request(method, path, body, token));
  assert.equal(c.issuer.requests.length, 4);
  assert.equal(c.issuer.peers.length, 1);
});
