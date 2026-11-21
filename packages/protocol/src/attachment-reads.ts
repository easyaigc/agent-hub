/** Optional, authenticated reads of a prepared fixed attachment version. */
import type { AttachmentRef } from './index.js';

export const ATTACHMENT_READ_CAPABILITY = 'attachments:range-read:v1' as const;
export const ATTACHMENT_READ_MODE = 'range-v1' as const;
export const ATTACHMENT_READ_CHUNK_BYTES = 256 * 1024;

export type AttachmentReadRange = { start: number; end: number };
export type AttachmentReadState = {
  version: 1;
  readId: string;
  expiresAt: string;
  /** Relative lease budget; optional for existing range-v1 clients/Hosts. */
  remainingSeconds?: number;
} & (
  | { phase: 'preparing'; preparedBytes: number; totalBytes: number | null }
  | { phase: 'ready'; attachment: AttachmentRef; etag: string }
);
export class AttachmentRangeError extends Error {
  constructor(readonly status: 400 | 416) {
    super(status === 416 ? '请求范围超出文件长度' : '仅支持一个有效字节范围');
  }
}

/** A strict single-range profile; never silently turn a bad range into a full download. */
export function attachmentReadRange(value: string | undefined, size: number): AttachmentReadRange {
  if (!Number.isSafeInteger(size) || size < 0) throw new AttachmentRangeError(400);
  if (!value || value.length > 160) throw new AttachmentRangeError(400);
  const match = /^bytes=(\d*)-(\d*)$/.exec(value);
  if (!match || (!match[1] && !match[2])) throw new AttachmentRangeError(400);
  const length = BigInt(size);
  if (length === 0n) throw new AttachmentRangeError(416);
  let start: bigint, end: bigint;
  if (!match[1]) {
    const suffix = BigInt(match[2]!);
    if (suffix === 0n) throw new AttachmentRangeError(416);
    start = suffix >= length ? 0n : length - suffix;
    end = length - 1n;
  } else {
    start = BigInt(match[1]);
    end = match[2] ? BigInt(match[2]) : length - 1n;
    if (start >= length || end < start) throw new AttachmentRangeError(416);
    if (end >= length) end = length - 1n;
  }
  return { start: Number(start), end: Number(end) };
}
