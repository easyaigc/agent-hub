import { createHash, randomUUID } from 'node:crypto';
import { mkdir, open, readFile, readdir, rename, rm, lstat, realpath } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import {
  VOICE_MAX_BYTES,
  VOICE_MAX_DURATION_MS,
  parseVoiceTranscriptionRequest,
  type VoiceTranscriptionRequest,
  type VoiceTranscriptionReceipt,
} from '@codex-manage/protocol';
import {
  RuntimeError,
  assertPrivateFile,
  protectWindowsStateDirectory,
  protectWindowsStateFile,
} from '@codex-manage/codex-adapter';

export interface VoiceTranscriber {
  readiness(): Promise<{ available: boolean; reason: string | null; principal: string | null }>;
  transcribe(audio: Buffer, principal: string): Promise<string>;
}
interface StoredReceipt extends VoiceTranscriptionReceipt {
  principal: string;
}
function publicReceipt({
  principal: _principal,
  ...receipt
}: StoredReceipt): VoiceTranscriptionReceipt {
  return receipt;
}

/** Fixed audio operation. Save intent before upstream I/O; never replay an unknown outcome. */
export class VoiceTranscriptions {
  private readonly directory: string;
  private readonly active = new Set<string>();
  private readonly locks = new Set<string>();
  constructor(
    private readonly hostId: string,
    stateDirectory: string,
    private readonly transcriber: VoiceTranscriber,
  ) {
    this.directory = join(stateDirectory, 'voice-transcriptions');
  }
  private path(id: string, suffix = '.json') {
    if (!/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(id))
      throw new RuntimeError(400, '无效转写操作 ID');
    return join(this.directory, id + suffix);
  }
  private async initialize() {
    await mkdir(this.directory, { recursive: true, mode: 0o700 });
    if (
      !(await lstat(this.directory)).isDirectory() ||
      (await realpath(this.directory)) !== resolve(this.directory)
    )
      throw new RuntimeError(409, '录音目录不安全');
    protectWindowsStateDirectory(this.directory);
  }
  private async save(receipt: StoredReceipt) {
    const destination = this.path(receipt.operationId),
      temporary = destination + '.' + randomUUID();
    const file = await open(temporary, 'wx', 0o600);
    try {
      await file.writeFile(JSON.stringify(receipt));
      await file.sync();
    } finally {
      await file.close();
    }
    protectWindowsStateFile(temporary);
    try {
      await rename(temporary, destination);
      if (process.platform !== 'win32') {
        const directory = await open(this.directory, 'r');
        try {
          await directory.sync();
        } finally {
          await directory.close();
        }
      }
    } finally {
      await rm(temporary, { force: true });
    }
  }
  private async read(id: string): Promise<StoredReceipt> {
    const path = this.path(id);
    try {
      assertPrivateFile(path);
      const receipt = JSON.parse(await readFile(path, 'utf8')) as StoredReceipt;
      const { phase, text, error, expiresAt, principal, ...manifest } = receipt;
      parseVoiceTranscriptionRequest(manifest);
      if (
        typeof principal !== 'string' ||
        !/^[a-f0-9]{64}$/.test(principal) ||
        manifest.hostId !== this.hostId ||
        manifest.operationId !== id ||
        !Number.isFinite(Date.parse(expiresAt)) ||
        !['awaiting-audio', 'transcribing', 'completed', 'failed', 'outcome-unknown'].includes(
          phase,
        ) ||
        !(text === null || typeof text === 'string') ||
        !(error === null || typeof error === 'string')
      )
        throw new Error('Invalid receipt');
      if (Date.parse(expiresAt) <= Date.now()) {
        // Keep an immutable ID tombstone. Expiration must never turn an unknown
        // submission into a missing operation which could be submitted again.
        if (receipt.text !== null || receipt.error !== '转写回执已过期') {
          receipt.text = null;
          receipt.error = '转写回执已过期';
          await this.save(receipt);
        }
        await rm(this.path(id, '.wav'), { force: true });
        throw new RuntimeError(410, '转写回执已过期，请保留手机原录音');
      }
      return receipt;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'ENOENT')
        throw new RuntimeError(404, '转写记录不存在');
      if (error instanceof RuntimeError) throw error;
      throw new RuntimeError(409, '转写记录无法读取');
    }
  }
  async prune() {
    await this.initialize();
    for (const name of await readdir(this.directory)) {
      if (!/^[a-f0-9-]+\.json$/.test(name)) continue;
      try {
        await this.read(name.slice(0, -5));
      } catch (error) {
        if (!(error instanceof RuntimeError) || ![404, 410].includes(error.status)) throw error;
      }
    }
  }
  async readiness() {
    const { available, reason } = await this.transcriber.readiness();
    return {
      hostId: this.hostId,
      available,
      reason,
      maxBytes: VOICE_MAX_BYTES,
      maxDurationMs: VOICE_MAX_DURATION_MS,
    };
  }
  async prepare(input: unknown) {
    let manifest: VoiceTranscriptionRequest;
    try {
      manifest = parseVoiceTranscriptionRequest(input);
    } catch (error) {
      throw new RuntimeError(400, (error as Error).message);
    }
    if (manifest.hostId !== this.hostId) throw new RuntimeError(403, '电脑身份不一致');
    await this.initialize();
    if (this.locks.has(manifest.operationId))
      throw new RuntimeError(409, '原转写操作正在保存，请查询回执');
    this.locks.add(manifest.operationId);
    try {
      try {
        const existing = await this.read(manifest.operationId);
        await this.validatePrincipal(existing);
        if (
          Object.entries(manifest).some(
            ([key, value]) => existing[key as keyof VoiceTranscriptionRequest] !== value,
          )
        )
          throw new RuntimeError(409, '同一转写 ID 的录音或目标已变化');
        return publicReceipt(await this.recordStatus(manifest.operationId));
      } catch (error) {
        if (!(error instanceof RuntimeError) || error.status !== 404) throw error;
      }
      const files = (await readdir(this.directory)).filter((name) =>
        /^[a-f0-9-]+\.json$/.test(name),
      );
      let count = 0;
      for (const name of files) {
        try {
          await this.read(name.slice(0, -5));
          count++;
        } catch (error) {
          if (!(error instanceof RuntimeError) || ![404, 410].includes(error.status)) throw error;
        }
      }
      if (count >= 64) throw new RuntimeError(429, '录音操作过多，请等待旧回执过期');
      const ready = await this.transcriber.readiness();
      if (!ready.available || !ready.principal)
        throw new RuntimeError(503, ready.reason ?? '电脑转写暂不可用');
      const receipt: StoredReceipt = {
        ...manifest,
        principal: ready.principal,
        phase: 'awaiting-audio',
        text: null,
        error: null,
        expiresAt: new Date(Date.now() + 86400_000).toISOString(),
      };
      await this.save(receipt);
      return publicReceipt(receipt);
    } finally {
      this.locks.delete(manifest.operationId);
    }
  }
  async status(id: string) {
    const receipt = await this.recordStatus(id);
    await this.validatePrincipal(receipt);
    return publicReceipt(receipt);
  }
  private async validatePrincipal(receipt: StoredReceipt) {
    const current = await this.transcriber.readiness();
    if (!current.available)
      throw new RuntimeError(401, current.reason ?? '请恢复原 Codex 登录再查询转写');
    if (current.principal !== receipt.principal)
      throw new RuntimeError(403, 'Codex 登录账户已变化，不能读取或提交原账户的录音');
  }
  private async recordStatus(id: string) {
    let receipt = await this.read(id);
    if (receipt.phase === 'transcribing' && !this.active.has(id)) {
      // The first snapshot may predate run's terminal save and active.delete.
      // Re-read durable state before recovering an actually abandoned submission.
      receipt = await this.read(id);
      if (receipt.phase === 'transcribing' && !this.active.has(id)) {
        receipt.phase = 'outcome-unknown';
        receipt.error = '电脑重启或转写连接中断，结果未知；原录音已保留，未重复提交';
        await this.save(receipt);
      }
    }
    return receipt;
  }
  async upload(id: string, audio: Buffer) {
    if (this.locks.has(id)) throw new RuntimeError(409, '原录音正在保存，请查询回执');
    this.locks.add(id);
    try {
      const receipt = await this.recordStatus(id);
      await this.validatePrincipal(receipt);
      if (
        audio.length !== receipt.sizeBytes ||
        createHash('sha256').update(audio).digest('hex') !== receipt.sha256
      )
        throw new RuntimeError(409, '录音文件与原指纹不一致');
      if (receipt.phase !== 'awaiting-audio') return publicReceipt(receipt);
      validateWave(audio, receipt.durationMs);
      if (this.active.size >= 4)
        throw new RuntimeError(429, '电脑正在转写其他录音，请稍后查询并上传原操作');
      try {
        const file = await open(this.path(id, '.wav'), 'wx', 0o600);
        try {
          await file.writeFile(audio);
          await file.sync();
        } finally {
          await file.close();
        }
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== 'EEXIST') throw error;
        // A crash may have left the original upload before submission. Never
        // follow a symlink or overwrite another file to recover it.
        try {
          assertPrivateFile(this.path(id, '.wav'));
          if (
            createHash('sha256')
              .update(await readFile(this.path(id, '.wav')))
              .digest('hex') !== receipt.sha256
          )
            throw new Error('Changed audio', { cause: error });
        } catch (error) {
          const failure = new RuntimeError(409, '电脑上的原录音不安全或不完整，已停止提交');
          failure.cause = error;
          throw failure;
        }
      }
      protectWindowsStateFile(this.path(id, '.wav'));
      receipt.phase = 'transcribing';
      this.active.add(id);
      try {
        await this.save(receipt);
      } catch (error) {
        this.active.delete(id);
        throw error;
      }
      void this.run(receipt, audio);
      return publicReceipt(receipt);
    } finally {
      this.locks.delete(id);
    }
  }
  private async run(receipt: StoredReceipt, audio: Buffer) {
    try {
      const text = await this.transcriber.transcribe(audio, receipt.principal);
      receipt.text = text;
      receipt.phase = 'completed';
    } catch (error) {
      // Only a definite upstream rejection is safe to describe as failed.
      receipt.phase =
        error instanceof RuntimeError && error.status >= 400 && error.status < 500
          ? 'failed'
          : 'outcome-unknown';
      receipt.error =
        error instanceof RuntimeError
          ? error.message
          : '转写结果未知，请查询原回执；原录音已保留，未重复提交';
    }
    try {
      await this.save(receipt);
      if (receipt.phase === 'completed' || receipt.phase === 'failed')
        await rm(this.path(receipt.operationId, '.wav'), { force: true });
    } catch {
      /* Persisted submitting state recovers as unknown; never replay. */
    } finally {
      this.active.delete(receipt.operationId);
    }
  }
}

/** Parse bounded RIFF chunks; reject mislabeled audio and duration mismatches before upload. */
export function validateWave(audio: Buffer, durationMs: number) {
  if (
    audio.toString('ascii', 0, 4) !== 'RIFF' ||
    audio.toString('ascii', 8, 12) !== 'WAVE' ||
    audio.readUInt32LE(4) + 8 !== audio.length
  )
    throw new RuntimeError(400, '需要完整 WAV 录音');
  let byteRate = 0,
    payload = 0;
  for (let offset = 12; offset + 8 <= audio.length;) {
    const type = audio.toString('ascii', offset, offset + 4),
      size = audio.readUInt32LE(offset + 4),
      start = offset + 8;
    if (start + size > audio.length) throw new RuntimeError(400, 'WAV 录音不完整');
    if (type === 'fmt ') {
      if (
        size < 16 ||
        audio.readUInt16LE(start) !== 1 ||
        audio.readUInt16LE(start + 2) !== 1 ||
        audio.readUInt32LE(start + 4) !== 16000 ||
        audio.readUInt16LE(start + 14) !== 16 ||
        audio.readUInt16LE(start + 12) !== 2 ||
        audio.readUInt32LE(start + 8) !== 32000
      )
        throw new RuntimeError(400, '需要单声道 16kHz PCM16 WAV 录音');
      byteRate = 32000;
    }
    if (type === 'data') payload += size;
    offset = start + size + (size % 2);
  }
  const actual = (payload / byteRate) * 1000;
  if (
    !byteRate ||
    !payload ||
    payload % 2 ||
    actual > VOICE_MAX_DURATION_MS ||
    Math.abs(actual - durationMs) > 500
  )
    throw new RuntimeError(400, '录音时长与文件不一致');
}
