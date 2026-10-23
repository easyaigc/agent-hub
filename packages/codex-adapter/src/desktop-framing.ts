export const desktopFrameLimits = {
  materializedBytes: 256 * 1024 * 1024,
  drainedBytes: 512 * 1024 * 1024,
  drainTimeoutMs: 10_000,
} as const;

type DrainOptions = {
  onOversized?: (bytes: number) => void;
  onError?: (error: Error) => void;
  drainTimeoutMs?: number;
};

/** Desktop IPC: a four-byte little-endian length followed by one JSON payload. */
export class DesktopFrameDecoder {
  private readonly header = Buffer.allocUnsafe(4);
  private headerBytes = 0;
  private size = 0;
  private body: Buffer | undefined;
  private bodyBytes = 0;
  private drainRemaining = 0;
  private drainTimer: ReturnType<typeof setTimeout> | undefined;
  private failure: Error | undefined;
  private readonly drainTimeoutMs: number;

  constructor(
    private readonly accept: (body: Buffer) => void,
    private readonly options: DrainOptions = {},
  ) {
    this.drainTimeoutMs = options.drainTimeoutMs ?? desktopFrameLimits.drainTimeoutMs;
    if (
      !Number.isSafeInteger(this.drainTimeoutMs) ||
      this.drainTimeoutMs <= 0 ||
      this.drainTimeoutMs > desktopFrameLimits.drainTimeoutMs
    )
      throw new RangeError('Invalid IPC frame drain timeout');
  }

  push(chunk: Buffer): void {
    if (this.failure) throw this.failure;
    let offset = 0;
    while (offset < chunk.length) {
      if (this.drainRemaining) {
        const count = Math.min(this.drainRemaining, chunk.length - offset);
        this.drainRemaining -= count;
        offset += count;
        if (!this.drainRemaining) {
          clearTimeout(this.drainTimer);
          this.drainTimer = undefined;
        }
        continue;
      }
      if (!this.size) {
        if (!this.headerBytes && chunk.length - offset >= 4) {
          this.size = chunk.readUInt32LE(offset);
          offset += 4;
        } else {
          const count = Math.min(4 - this.headerBytes, chunk.length - offset);
          chunk.copy(this.header, this.headerBytes, offset, offset + count);
          this.headerBytes += count;
          offset += count;
          if (this.headerBytes < 4) return;
          this.size = this.header.readUInt32LE(0);
          this.headerBytes = 0;
        }
        if (!this.size || this.size > desktopFrameLimits.drainedBytes)
          throw new Error('Invalid IPC frame');
        if (this.size > desktopFrameLimits.materializedBytes) {
          if (!this.options.onOversized) throw new Error('Invalid IPC frame');
          const oversized = this.size;
          this.size = 0;
          this.drainRemaining = oversized;
          // An absolute deadline, not an inactivity timer that a slow peer can renew.
          this.drainTimer = setTimeout(() => {
            this.drainTimer = undefined;
            this.failure = new Error('Oversized IPC frame drain timed out');
            this.options.onError?.(this.failure);
          }, this.drainTimeoutMs);
          this.drainTimer.unref();
          // No prefix/body inspection: skipped bytes cannot grant ownership or prove delivery.
          this.options.onOversized(oversized);
          continue;
        }
      }

      const count = Math.min(this.size - this.bodyBytes, chunk.length - offset);
      if (!this.body && count === this.size) {
        // A complete payload already has contiguous storage. Decode it in place.
        const body = chunk.subarray(offset, offset + count);
        offset += count;
        this.size = 0;
        this.accept(body);
      } else {
        if (!count) return;
        // Allocate once for a fragmented payload and copy each byte exactly once.
        this.body ??= Buffer.allocUnsafe(this.size);
        chunk.copy(this.body, this.bodyBytes, offset, offset + count);
        this.bodyBytes += count;
        offset += count;
        if (this.bodyBytes === this.size) {
          const body = this.body;
          this.reset();
          this.accept(body);
        }
      }
    }
  }

  reset(): void {
    clearTimeout(this.drainTimer);
    this.drainTimer = undefined;
    this.drainRemaining = 0;
    this.failure = undefined;
    this.headerBytes = 0;
    this.size = 0;
    this.body = undefined;
    this.bodyBytes = 0;
  }
}
