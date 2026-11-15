import { parseFileTransferManifest, type FileTransferManifest } from './file-transfers.js';

/** Private, immutable file delivery. Completion never submits a model input. */
export interface UploadManifest extends FileTransferManifest {
  version: 1;
  purpose: 'file-transfer' | 'attachment' | 'creation-attachment';
  threadId: string | null;
  creationId: string | null;
  attachmentId: string | null;
}
export interface UploadReceipt {
  version: 1;
  manifest: UploadManifest;
  phase: 'uploading' | 'verifying' | 'completed' | 'cancelled';
  committedBytes: number;
  maxChunkBytes: number;
  createdAt: string;
  updatedAt: string;
  result: Record<string, unknown> | null;
  reason?: 'expired';
  /** A crash in publication must reconcile before an abort can discard source bytes. */
  publicationStarted?: boolean;
}
export const UPLOAD_CHUNK_BYTES = 256 * 1024;
export const UPLOAD_CAPABILITY = 'files:resumable-upload-v1';
export function parseUploadManifest(input: unknown): UploadManifest {
  if (!input || typeof input !== 'object' || Array.isArray(input))
    throw new TypeError('无效上传清单');
  const v = input as Record<string, unknown>;
  const { version, purpose, threadId, creationId, attachmentId, ...file } = v;
  parseFileTransferManifest(file);
  const identifier = (value: unknown) =>
    typeof value === 'string' && /^[a-zA-Z0-9_-]{1,200}$/.test(value);
  if (
    version !== 1 ||
    !['file-transfer', 'attachment', 'creation-attachment'].includes(String(purpose)) ||
    (purpose === 'file-transfer' &&
      (threadId !== null || creationId !== null || attachmentId !== null)) ||
    (purpose === 'attachment' &&
      (!identifier(threadId) || creationId !== null || attachmentId !== null)) ||
    (purpose === 'creation-attachment' &&
      (threadId !== null || !identifier(creationId) || !identifier(attachmentId)))
  )
    throw new TypeError('上传用途或原目标无效');
  return input as UploadManifest;
}
