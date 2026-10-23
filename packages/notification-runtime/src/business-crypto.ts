import {
  createCipheriv,
  createDecipheriv,
  createPrivateKey,
  createPublicKey,
  diffieHellman,
  hkdfSync,
  randomBytes,
} from 'node:crypto';
import { parseBusinessNotice, type BusinessNotice } from '@codex-manage/protocol';

/** Strict domain-separated cipher; callers own private-key storage and authorization. */
export function businessNotificationCipher(privateKey: string) {
  const own = createPrivateKey(privateKey);
  if (own.asymmetricKeyType !== 'x25519')
    throw new Error('Business notification key must be X25519');
  const publicKey = createPublicKey(own)
    .export({ format: 'der', type: 'spki' })
    .subarray(-32)
    .toString('base64');
  const key = (bindingId: string, recipient: string) => {
    if (!/^[0-9a-f-]{36}$/.test(bindingId) || !/^[A-Za-z0-9+/]{43}=$/.test(recipient))
      throw new Error('Invalid business cipher binding');
    const peer = createPublicKey({
      key: Buffer.concat([
        Buffer.from('302a300506032b656e032100', 'hex'),
        Buffer.from(recipient, 'base64'),
      ]),
      format: 'der',
      type: 'spki',
    });
    return Buffer.from(
      hkdfSync(
        'sha256',
        diffieHellman({ privateKey: own, publicKey: peer }),
        Buffer.from(bindingId),
        Buffer.from('agenthub/taskhub.business/v1'),
        32,
      ),
    );
  };
  return {
    publicKey,
    validateRecipient(recipient: string) {
      key('00000000-0000-4000-8000-000000000000', recipient);
    },
    seal(value: BusinessNotice, bindingId: string, recipient: string) {
      const event = parseBusinessNotice(value),
        nonce = randomBytes(12);
      const cipher = createCipheriv('aes-256-gcm', key(bindingId, recipient), nonce);
      cipher.setAAD(Buffer.from('taskhub.business:' + event.eventId));
      return {
        nonce: nonce.toString('base64'),
        ciphertext: Buffer.concat([
          cipher.update(JSON.stringify(event), 'utf8'),
          cipher.final(),
        ]).toString('base64'),
        tag: cipher.getAuthTag().toString('base64'),
      };
    },
    open(
      envelope: { eventId: string; nonce: string; ciphertext: string; tag: string },
      bindingId: string,
      sender: string,
    ) {
      const decipher = createDecipheriv(
        'aes-256-gcm',
        key(bindingId, sender),
        Buffer.from(envelope.nonce, 'base64'),
      );
      decipher.setAAD(Buffer.from('taskhub.business:' + envelope.eventId));
      decipher.setAuthTag(Buffer.from(envelope.tag, 'base64'));
      const event = parseBusinessNotice(
        JSON.parse(
          Buffer.concat([
            decipher.update(Buffer.from(envelope.ciphertext, 'base64')),
            decipher.final(),
          ]).toString('utf8'),
        ),
      );
      if (event.eventId !== envelope.eventId) throw new Error('Business event identity mismatch');
      return event;
    },
  };
}
