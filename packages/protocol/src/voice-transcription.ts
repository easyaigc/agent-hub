/** Audio capture is local; transcription is a separate Host operation, never a chat Turn. */
export const VOICE_MAX_BYTES = 12 * 1024 * 1024;
export const VOICE_MAX_DURATION_MS = 300_000;
export interface VoiceTranscriptionRequest {
  hostId: string;
  threadId: string | null;
  draftId: string;
  operationId: string;
  mediaType: 'audio/wav';
  sizeBytes: number;
  sha256: string;
  durationMs: number;
}
export interface VoiceTranscriptionReceipt extends VoiceTranscriptionRequest {
  phase: 'awaiting-audio' | 'transcribing' | 'completed' | 'failed' | 'outcome-unknown';
  text: string | null;
  error: string | null;
  expiresAt: string;
}
export interface VoiceTranscriptionReadiness {
  hostId: string;
  available: boolean;
  reason: string | null;
  maxBytes: number;
  maxDurationMs: number;
}
export function parseVoiceTranscriptionRequest(value: unknown): VoiceTranscriptionRequest {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('无效录音请求');
  const row = value as Record<string, unknown>;
  const fields = [
    'hostId',
    'threadId',
    'draftId',
    'operationId',
    'mediaType',
    'sizeBytes',
    'sha256',
    'durationMs',
  ];
  if (
    Object.keys(row).some((key) => !fields.includes(key)) ||
    !['hostId', 'draftId'].every(
      (key) =>
        typeof row[key] === 'string' &&
        (row[key] as string).trim().length > 0 &&
        (row[key] as string).length <= 2048,
    ) ||
    !(
      row.threadId === null ||
      (typeof row.threadId === 'string' && row.threadId.length > 0 && row.threadId.length <= 256)
    ) ||
    typeof row.operationId !== 'string' ||
    !/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(row.operationId) ||
    row.mediaType !== 'audio/wav' ||
    typeof row.sha256 !== 'string' ||
    !/^[a-f0-9]{64}$/.test(row.sha256) ||
    !Number.isSafeInteger(row.sizeBytes) ||
    (row.sizeBytes as number) < 44 ||
    (row.sizeBytes as number) > VOICE_MAX_BYTES ||
    !Number.isSafeInteger(row.durationMs) ||
    (row.durationMs as number) < 1 ||
    (row.durationMs as number) > VOICE_MAX_DURATION_MS
  )
    throw new Error('录音目标、大小、时长或指纹无效');
  return row as unknown as VoiceTranscriptionRequest;
}
