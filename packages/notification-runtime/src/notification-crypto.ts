import {
  createCipheriv,
  createPrivateKey,
  createPublicKey,
  diffieHellman,
  hkdfSync,
  randomBytes,
} from 'node:crypto';
import type { NotificationEvent, NotificationEnvelope } from '@codex-manage/protocol';

export function notificationPublicKey(raw: string) {
  return createPublicKey({
    key: Buffer.concat([
      Buffer.from('302a300506032b656e032100', 'hex'),
      Buffer.from(raw, 'base64'),
    ]),
    format: 'der',
    type: 'spki',
  });
}
export function sealNotification(
  event: NotificationEvent,
  bindingId: string,
  privateKey: string,
  recipient: string,
): Pick<NotificationEnvelope, 'nonce' | 'ciphertext' | 'tag'> {
  const shared = diffieHellman({
    privateKey: createPrivateKey(privateKey),
    publicKey: notificationPublicKey(recipient),
  });
  const key = Buffer.from(
    hkdfSync(
      'sha256',
      shared,
      Buffer.from(bindingId),
      Buffer.from('codex-manage/notifications/v1'),
      32,
    ),
  );
  const nonce = randomBytes(12),
    cipher = createCipheriv('aes-256-gcm', key, nonce);
  cipher.setAAD(Buffer.from(event.id));
  const ciphertext = Buffer.concat([cipher.update(JSON.stringify(event), 'utf8'), cipher.final()]);
  return {
    nonce: nonce.toString('base64'),
    ciphertext: ciphertext.toString('base64'),
    tag: cipher.getAuthTag().toString('base64'),
  };
}

export function validateNotificationKey(
  privateKey: string,
  publicKey: string,
): ReturnType<typeof notificationPublicKey> {
  const parsed = notificationPublicKey(publicKey);
  diffieHellman({ privateKey: createPrivateKey(privateKey), publicKey: parsed });
  return parsed;
}
export function notificationSenderPublicKey(privateKey: string): string {
  return createPublicKey(createPrivateKey(privateKey))
    .export({ format: 'der', type: 'spki' })
    .subarray(-32)
    .toString('base64');
}
