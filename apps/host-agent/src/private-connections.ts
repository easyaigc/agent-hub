import {
  createCipheriv,
  createHash,
  randomUUID,
  createPublicKey,
  diffieHellman,
  generateKeyPairSync,
  hkdfSync,
  randomBytes,
  timingSafeEqual,
} from 'node:crypto';
import {
  readSync,
  lstatSync,
  openSync,
  fstatSync,
  fsyncSync,
  closeSync,
  unlinkSync,
  constants,
} from 'node:fs';
import { join } from 'node:path';
import {
  codexPrivateConnectionContext,
  machineRuntimeTargetKey,
  parseCodexPrivateConnectionClaim,
  type CodexPrivateConnectionReply,
  type RuntimeTarget,
} from '@codex-manage/protocol';
import {
  codexPrivateMachineTarget,
  codexPrivateConnectionRevokeIntent,
  parseCodexPrivateConnectionSelector,
  parseCodexPrivateConnectionRevoke,
  parseCodexPrivateConnectionOperation,
  type CodexPrivateConnectionStatus,
  type CodexPrivateConnectionRevoke,
  type CodexPrivateConnectionRevokeReceipt,
  type CodexPrivateConnectionUnknownReceipt,
} from './private-connection-contract.js';
import {
  assertPrivateFile,
  writePrivateFileSync,
  renamePrivateFileSync,
  protectWindowsStateFile,
  RuntimeError,
} from '@codex-manage/codex-adapter';

type Binding = {
  target: RuntimeTarget;
  publicKey: string;
  token: string;
  revoked: boolean;
  hostKeyAuthorized?: boolean;
};

/** Host-Key-authorized issuance, with a separate persistent credential per phone key.
 * The original Host access-token is never returned, rotated or copied to the phone.
 */
export class PrivateConnections {
  private readonly path: string;
  private bindings: Binding[] = [];
  private readonly directory: string;
  private operations: CodexPrivateConnectionRevokeReceipt[] = [];
  private uncertain: CodexPrivateConnectionRevoke | undefined;
  private unavailable = false;
  private static readonly maximumBytes = 1024 * 1024;
  private static readonly maximumOperations = 1024;
  constructor(directory: string) {
    this.directory = directory;
    this.path = join(directory, 'private-connections-v1.json');
    this.load(); // Legacy v1 without operations is read without rewriting it.
  }
  private stateError(): RuntimeError {
    return new RuntimeError(
      503,
      '私有设备绑定保存结果待核对，请仅查询原操作',
      'PRIVATE_CONNECTION_STATE_UNCERTAIN',
    );
  }
  private certain(): void {
    if (this.unavailable) throw this.stateError();
  }
  private keySha(publicKey: string): string {
    const raw = Buffer.from(publicKey, 'base64url');
    if (raw.length !== 32 || raw.toString('base64url') !== publicKey)
      throw Error('Invalid private connection public key');
    return createHash('sha256').update(raw).digest('hex');
  }
  private digest(input: CodexPrivateConnectionRevoke): string {
    return createHash('sha256').update(codexPrivateConnectionRevokeIntent(input)).digest('hex');
  }
  private projection(binding: Binding): CodexPrivateConnectionStatus {
    return {
      target: codexPrivateMachineTarget(binding.target),
      publicKeySha256: this.keySha(binding.publicKey),
      revoked: binding.revoked,
      hostKeyAuthorized: binding.hostKeyAuthorized === true,
    };
  }
  private current(input: RuntimeTarget, actual: RuntimeTarget): void {
    if (machineRuntimeTargetKey(input) !== machineRuntimeTargetKey(actual))
      throw new RuntimeError(409, '连接目标已变化');
  }
  private selected(sha: string, target: RuntimeTarget): Binding {
    const found = this.bindings.filter((binding) => this.keySha(binding.publicKey) === sha);
    if (found.length > 1) throw new RuntimeError(409, '设备绑定不唯一，请保留原状态');
    if (!found.length) throw new RuntimeError(404, '设备绑定不存在');
    const binding = found[0]!;
    this.current(binding.target, target);
    return binding;
  }
  private parse<T>(read: () => T): T {
    try {
      return read();
    } catch {
      throw new RuntimeError(400, '无效私有设备绑定请求');
    }
  }
  status(input: unknown, target: RuntimeTarget): CodexPrivateConnectionStatus {
    const selector = this.parse(() => parseCodexPrivateConnectionSelector(input));
    this.current(selector.target, target);
    this.certain();
    return this.projection(this.selected(selector.publicKeySha256, selector.target));
  }
  operation(
    input: unknown,
    target: RuntimeTarget,
  ): CodexPrivateConnectionRevokeReceipt | CodexPrivateConnectionUnknownReceipt {
    const query = this.parse(() => parseCodexPrivateConnectionOperation(input));
    this.current(query.target, target);
    if (this.unavailable) {
      // Read back the same atomic file, then re-sync it. A missing operation is not permission to replay.
      const unknown = this.uncertain;
      if (!unknown || unknown.clientOperationId !== query.clientOperationId)
        throw this.stateError();
      this.current(unknown.target, query.target);
      try {
        this.load(true);
        const saved = this.operations.find(
          (entry) => entry.clientOperationId === query.clientOperationId,
        );
        if (saved && saved.inputSha256 === this.digest(unknown)) {
          this.current(saved.binding.target, query.target);
          this.unavailable = false;
          this.uncertain = undefined;
          return structuredClone(saved);
        }
      } catch {
        /* Keep the original unknown ID and fail closed. */
      }
      return {
        clientOperationId: query.clientOperationId,
        inputSha256: this.digest(unknown),
        state: 'unknown',
      };
    }
    const saved = this.operations.find(
      (entry) => entry.clientOperationId === query.clientOperationId,
    );
    if (!saved) throw new RuntimeError(404, '原撤销操作尚无持久回执；不能据此重放');
    this.current(saved.binding.target, query.target);
    return structuredClone(saved);
  }
  revoke(input: unknown, target: RuntimeTarget): CodexPrivateConnectionRevokeReceipt {
    const intent = this.parse(() => parseCodexPrivateConnectionRevoke(input));
    this.current(intent.target, target);
    this.certain();
    const inputSha256 = this.digest(intent);
    const prior = this.operations.find(
      (entry) => entry.clientOperationId === intent.clientOperationId,
    );
    if (prior) {
      if (prior.inputSha256 !== inputSha256) throw new RuntimeError(409, '原操作标识的输入已改变');
      return structuredClone(prior);
    }
    const binding = this.selected(intent.publicKeySha256, intent.target);
    const next = this.bindings.map((value) =>
      value === binding ? { ...value, revoked: true } : value,
    );
    const receipt: CodexPrivateConnectionRevokeReceipt = {
      clientOperationId: intent.clientOperationId,
      inputSha256,
      state: 'confirmed',
      binding: { ...this.projection(binding), revoked: true },
    };
    if (this.operations.length >= PrivateConnections.maximumOperations)
      throw new RuntimeError(429, '撤销回执数量已满；保留原回执');
    this.save(next, [...this.operations, receipt], intent);
    return structuredClone(receipt);
  }
  private load(sync = false): void {
    let before;
    try {
      before = lstatSync(this.path);
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'ENOENT' && !sync) {
        this.bindings = [];
        this.operations = [];
        return;
      }
      throw error;
    }
    assertPrivateFile(this.path);
    if (
      !before.isFile() ||
      before.isSymbolicLink() ||
      before.nlink !== 1 ||
      before.size > PrivateConnections.maximumBytes
    )
      throw Error('Invalid private connection state file');
    const fd = openSync(
      this.path,
      constants.O_RDONLY | constants.O_NONBLOCK | (constants.O_NOFOLLOW ?? 0),
    );
    let data: Buffer;
    try {
      const opened = fstatSync(fd);
      if (opened.dev !== before.dev || opened.ino !== before.ino || !opened.isFile())
        throw Error('Private state identity changed');
      data = Buffer.alloc(before.size);
      let offset = 0;
      while (offset < data.length) {
        const count = readSync(fd, data, offset, data.length - offset, null);
        if (!count) throw Error('Private state truncated');
        offset += count;
      }
      if (readSync(fd, Buffer.alloc(1), 0, 1, null) !== 0) throw Error('Private state grew');
      const after = fstatSync(fd);
      const current = lstatSync(this.path);
      if (
        [after, current].some(
          (entry) =>
            entry.dev !== before.dev ||
            entry.ino !== before.ino ||
            entry.size !== before.size ||
            entry.mtimeMs !== before.mtimeMs ||
            entry.ctimeMs !== before.ctimeMs ||
            entry.nlink !== 1,
        )
      )
        throw Error('Private state changed');
      assertPrivateFile(this.path);
      if (sync) {
        if (process.platform === 'win32') {
          // FlushFileBuffers needs a write-capable handle. Open existing only; never create/truncate here.
          const writer = openSync(this.path, constants.O_WRONLY);
          try {
            const actual = fstatSync(writer);
            if (
              actual.dev !== before.dev ||
              actual.ino !== before.ino ||
              actual.size !== before.size
            )
              throw Error('Private state changed before sync');
            assertPrivateFile(this.path);
            fsyncSync(writer);
          } finally {
            closeSync(writer);
          }
        } else fsyncSync(fd);
      }
    } finally {
      closeSync(fd);
    }
    const state = JSON.parse(data.toString()) as {
      version?: number;
      bindings?: Binding[];
      operations?: CodexPrivateConnectionRevokeReceipt[];
    };
    if (
      Object.keys(state).some((key) => !['version', 'bindings', 'operations'].includes(key)) ||
      state.version !== 1 ||
      !Array.isArray(state.bindings) ||
      state.bindings.length > 128 ||
      (state.operations !== undefined &&
        (!Array.isArray(state.operations) ||
          state.operations.length > PrivateConnections.maximumOperations))
    )
      throw Error('Invalid private connection state');
    for (const binding of state.bindings) {
      parseCodexPrivateConnectionClaim({
        version: 1,
        target: binding.target,
        publicKey: binding.publicKey,
      });
      this.keySha(binding.publicKey);
      if (
        !/^[A-Za-z0-9_-]{43}$/.test(binding.token) ||
        typeof binding.revoked !== 'boolean' ||
        (binding.hostKeyAuthorized !== undefined && typeof binding.hostKeyAuthorized !== 'boolean')
      )
        throw Error('Invalid private connection credential');
    }
    const operations = state.operations ?? [];
    if (new Set(operations.map((entry) => entry.clientOperationId)).size !== operations.length)
      throw Error('Duplicate private revoke operation');
    for (const receipt of operations) {
      if (
        Object.keys(receipt).sort().join(',') !== 'binding,clientOperationId,inputSha256,state' ||
        receipt.state !== 'confirmed' ||
        Object.keys(receipt.binding).sort().join(',') !==
          'hostKeyAuthorized,publicKeySha256,revoked,target' ||
        receipt.binding.revoked !== true ||
        typeof receipt.binding.hostKeyAuthorized !== 'boolean'
      )
        throw Error('Invalid private revoke receipt');
      const intent = parseCodexPrivateConnectionRevoke({
        target: receipt.binding.target,
        publicKeySha256: receipt.binding.publicKeySha256,
        clientOperationId: receipt.clientOperationId,
      });
      if (receipt.inputSha256 !== this.digest(intent)) throw Error('Private revoke input changed');
      const matches = state.bindings.filter(
        (binding) => this.keySha(binding.publicKey) === receipt.binding.publicKeySha256,
      );
      if (
        matches.length !== 1 ||
        !matches[0]!.revoked ||
        receipt.binding.hostKeyAuthorized !== (matches[0]!.hostKeyAuthorized === true) ||
        machineRuntimeTargetKey(matches[0]!.target) !== machineRuntimeTargetKey(intent.target)
      )
        throw Error('Private revoke receipt not bound to revoked device');
    }
    if (sync) this.syncDirectory();
    this.bindings = state.bindings;
    this.operations = operations;
  }
  private syncDirectory(): void {
    // Windows uses the existing private file native owner/DACL writer and file flush; directory fsync is POSIX-only.
    if (process.platform === 'win32') return;
    const fd = openSync(
      this.directory,
      constants.O_RDONLY | constants.O_DIRECTORY | constants.O_NOFOLLOW,
    );
    try {
      if (!fstatSync(fd).isDirectory()) throw Error('Invalid private state directory');
      fsyncSync(fd);
    } finally {
      closeSync(fd);
    }
  }
  private save(
    bindings: Binding[],
    operations: CodexPrivateConnectionRevokeReceipt[],
    intent?: CodexPrivateConnectionRevoke,
  ): void {
    this.certain();
    const bytes = Buffer.from(JSON.stringify({ version: 1, bindings, operations }));
    if (
      bytes.length > PrivateConnections.maximumBytes ||
      operations.length > PrivateConnections.maximumOperations
    )
      throw new RuntimeError(429, '私有设备状态已达上限；保留原回执');
    const temporary = this.path + '.tmp-' + randomUUID();
    try {
      writePrivateFileSync(temporary, bytes, {
        mode: 0o600,
        flag: 'wx',
        flush: true,
      });
      protectWindowsStateFile(temporary);
      assertPrivateFile(temporary);
      renamePrivateFileSync(temporary, this.path);
      this.syncDirectory();
      this.bindings = bindings;
      this.operations = operations;
    } catch {
      this.unavailable = true;
      this.uncertain = intent;
      // Even an exception from rename can follow a completed publication. Never keep accepting old RAM tokens.
      try {
        this.load();
      } catch {
        /* Remain unavailable; Host access-key still reaches operation queries. */
      }
      throw this.stateError();
    } finally {
      try {
        unlinkSync(temporary);
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== 'ENOENT') this.unavailable = true;
      }
    }
  }
  accepts(token: Buffer, target: RuntimeTarget): boolean {
    this.certain(); // 503 is transient; an uncertain save does not invalidate a normal device credential.
    return this.bindings.some((binding) => {
      const expected = Buffer.from(binding.token);
      return (
        !binding.revoked &&
        this.bindings.filter(
          (value) => this.keySha(value.publicKey) === this.keySha(binding.publicKey),
        ).length === 1 &&
        binding.hostKeyAuthorized === true &&
        token.length === expected.length &&
        timingSafeEqual(token, expected) &&
        machineRuntimeTargetKey(target) === machineRuntimeTargetKey(binding.target)
      );
    });
  }
  claim(input: unknown, target: RuntimeTarget): CodexPrivateConnectionReply {
    this.certain();
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
    let publicKeySha256;
    try {
      publicKeySha256 = this.keySha(claim.publicKey);
    } catch {
      throw new RuntimeError(400, '无效设备公钥');
    }
    const matches = this.bindings.filter(
      (binding) => this.keySha(binding.publicKey) === publicKeySha256,
    );
    if (matches.length > 1) throw new RuntimeError(409, '设备绑定不唯一，请保留原状态');
    const existing = matches[0];
    if (existing?.revoked) throw new RuntimeError(403, '此设备已撤销');
    if (existing && machineRuntimeTargetKey(existing.target) !== machineRuntimeTargetKey(target))
      throw new RuntimeError(409, '设备属于其他运行时身份');
    if (!existing && this.bindings.length >= 128) throw new RuntimeError(429, '设备连接数量已满');
    const binding: Binding = existing
      ? { ...existing, hostKeyAuthorized: true }
      : {
          target: claim.target,
          publicKey: claim.publicKey,
          token: randomBytes(32).toString('base64url'),
          revoked: false,
          hostKeyAuthorized: true,
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
    if (!existing || !existing.hostKeyAuthorized) {
      const next = existing
        ? this.bindings.map((value) => (value === existing ? binding : value))
        : [...this.bindings, binding];
      this.save(next, this.operations);
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
