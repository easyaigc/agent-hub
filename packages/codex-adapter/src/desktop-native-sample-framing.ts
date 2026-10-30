import { TextDecoder } from 'node:util';

type Direction = 'outbound' | 'inbound';

/** Private, bounded JSON-lines framing beside the untouched original byte pipes. */
export class DesktopNativeSampleFrames {
  private readonly decoder = new TextDecoder('utf-8', { fatal: true });
  private readonly streams = {
    outbound: { bytes: Buffer.alloc(0), size: 0, discard: false },
    inbound: { bytes: Buffer.alloc(0), size: 0, discard: false },
  };
  private buffered = 0;
  private started = false;
  private closed = false;

  constructor(private readonly accept: (direction: Direction, line: string) => void) {}

  begin(boundary: Record<Direction, boolean>): void {
    if (this.closed || this.started) throw new Error('Sampler framing already started');
    this.streams.outbound.discard = !boundary.outbound;
    this.streams.inbound.discard = !boundary.inbound;
    this.started = true;
  }

  push(direction: Direction, bytes: Uint8Array): void {
    if (!this.started || this.closed) throw new Error('Sampler framing unavailable');
    const stream = this.streams[direction];
    const input = Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    let offset = 0;
    try {
      while (offset < input.length) {
        const newline = input.indexOf(10, offset),
          end = newline < 0 ? input.length : newline,
          part = input.subarray(offset, end);
        if (!stream.discard && part.length) {
          if (part.length > 32 * 1024 * 1024 - this.buffered)
            throw new Error('Sampler frame limit');
          if (stream.bytes.length < stream.size + part.length) {
            const capacity = Math.min(
              32 * 1024 * 1024,
              Math.max(stream.size + part.length, stream.bytes.length * 2, 1024),
            );
            const next = Buffer.allocUnsafe(capacity);
            stream.bytes.copy(next, 0, 0, stream.size);
            stream.bytes = next;
          }
          part.copy(stream.bytes, stream.size);
          stream.size += part.length;
          this.buffered += part.length;
        }
        if (newline < 0) break;
        if (!stream.discard) {
          const body = stream.bytes.subarray(0, stream.size);
          this.buffered -= stream.size;
          stream.bytes = Buffer.alloc(0);
          stream.size = 0;
          let line = this.decoder.decode(body);
          if (line.endsWith('\r')) line = line.slice(0, -1);
          if (line.trim()) this.accept(direction, line);
        }
        stream.discard = false;
        offset = newline + 1;
      }
    } catch (error) {
      this.clear();
      throw error;
    }
  }

  clear(): void {
    this.closed = true;
    this.buffered = 0;
    for (const stream of Object.values(this.streams)) {
      stream.bytes = Buffer.alloc(0);
      stream.size = 0;
    }
  }
}
