import {
  createCipheriv,
  createPublicKey,
  diffieHellman,
  generateKeyPairSync,
  hkdfSync,
  randomBytes,
  timingSafeEqual,
} from 'node:crypto';
import { existsSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  codexPrivateConnectionContext,
  machineRuntimeTargetKey,
  parseCodexPrivateConnectionClaim,
  type CodexPrivateConnectionReply,
  type RuntimeTarget,
} from '@codex-manage/protocol';
import {
  assertPrivateFile,
  protectWindowsStateFile,
  RuntimeError,
} from '@codex-manage/codex-adapter';

type Binding = { target: RuntimeTarget; publicKey: string; token: string; revoked: boolean };

/** Automatic private-network policy, with a separate persistent credential per phone key.
 * The original Host access-token is never returned, rotated or copied to the phone.
 */
export class PrivateConnections {
  private readonly path: string;
  private bindings: Binding[] = [];
  constructor(directory: string) {
    this.path = join(directory, 'private-connections-v1.json');
    if (!existsSync(this.path)) return;
    assertPrivateFile(this.path);
    const data = readFileSync(this.path);
    if (data.length > 256 * 1024) throw Error('Private connection state too large');
    const state = JSON.parse(data.toString()) as { version?: number; bindings?: Binding[] };
    if (state.version !== 1 || !Array.isArray(state.bindings) || state.bindings.length > 128)
      throw Error('Invalid private connection state');
    for (const binding of state.bindings) {
      parseCodexPrivateConnectionClaim({
        version: 1,
        target: binding.target,
        publicKey: binding.publicKey,
      });
      if (!/^[A-Za-z0-9_-]{43}$/.test(binding.token) || typeof binding.revoked !== 'boolean')
        throw Error('Invalid private connection credential');
    }
    this.bindings = state.bindings;
  }
  accepts(token: Buffer, target: RuntimeTarget): boolean {
    return this.bindings.some((binding) => {
      const expected = Buffer.from(binding.token);
      return (
        !binding.revoked &&
        token.length === expected.length &&
        timingSafeEqual(token, expected) &&
        machineRuntimeTargetKey(target) === machineRuntimeTargetKey(binding.target)
      );
    });
  }
  claim(input: unknown, target: RuntimeTarget): CodexPrivateConnectionReply {
    let claim;
    try {
      claim = parseCodexPrivateConnectionClaim(input);
    } catch {
      throw new RuntimeError(400, '无效连接请求');
    }
    if (machineRuntimeTargetKey(target) !== machineRuntimeTargetKey(claim.target))
      throw new RuntimeError(409, '连接目标已变化');
    // Preserve v1 HKDF/AAD bytes for existing clients. Account fields authenticate
    // the submitted claim as metadata; they do not identify the machine.
    const context = Buffer.from(codexPrivateConnectionContext(claim.target, claim.publicKey));
    const existing = this.bindings.find((binding) => binding.publicKey === claim.publicKey);
    if (existing?.revoked) throw new RuntimeError(403, '此设备已撤销');
    if (existing && machineRuntimeTargetKey(existing.target) !== machineRuntimeTargetKey(target))
      throw new RuntimeError(409, '设备属于其他运行时身份');
    if (!existing && this.bindings.length >= 128) throw new RuntimeError(429, '设备连接数量已满');
    const binding: Binding = existing ?? {
      target: claim.target,
      publicKey: claim.publicKey,
      token: randomBytes(32).toString('base64url'),
      revoked: false,
    };
    const ephemeral = generateKeyPairSync('x25519');
    let secret;
    try {
      const raw = Buffer.from(claim.publicKey, 'base64url');
      if (raw.length !== 32 || raw.toString('base64url') !== claim.publicKey)
        throw Error('Invalid key');
      secret = diffieHellman({
        privateKey: ephemeral.privateKey,
        publicKey: createPublicKey({
          key: Buffer.concat([Buffer.from('302a300506032b656e032100', 'hex'), raw]),
          format: 'der',
          type: 'spki',
        }),
      });
    } catch {
      throw new RuntimeError(400, '无效设备公钥');
    }
    const key = Buffer.from(hkdfSync('sha256', secret, Buffer.alloc(0), context, 32));
    const nonce = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', key, nonce);
    cipher.setAAD(context);
    const ciphertext = Buffer.concat([
      cipher.update(binding.token),
      cipher.final(),
      cipher.getAuthTag(),
    ]);
    if (!existing) {
      const next = [...this.bindings, binding];
      const temporary = this.path + '.tmp';
      writeFileSync(temporary, JSON.stringify({ version: 1, bindings: next }), { mode: 0o600 });
      protectWindowsStateFile(temporary);
      renameSync(temporary, this.path);
      this.bindings = next;
    }
    return {
      version: 1,
      target: claim.target,
      publicKey: claim.publicKey,
      ephemeralPublicKey: ephemeral.publicKey
        .export({ format: 'der', type: 'spki' })
        .subarray(-32)
        .toString('base64url'),
      nonce: nonce.toString('base64url'),
      ciphertext: ciphertext.toString('base64url'),
    };
  }
}
