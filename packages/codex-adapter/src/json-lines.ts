import { EventEmitter } from 'node:events';
import type { Readable } from 'node:stream';
import { StringDecoder } from 'node:string_decoder';

/** JSONL uses LF, not the Unicode text-line boundaries accepted by node:readline. */
class JsonLineReader extends EventEmitter<{ line: [string]; error: [Error]; close: [] }> {
  private readonly decoder = new StringDecoder('utf8');
  private parts: string[] = [];
  private closed = false;

  constructor(private readonly input: Readable) {
    super();
    input.on('data', this.data);
    input.once('end', this.end);
    input.once('close', this.close);
    input.on('error', this.error);
  }

  private data = (chunk: Buffer | string): void => {
    this.consume(typeof chunk === 'string' ? chunk : this.decoder.write(chunk));
  };

  private consume(text: string): void {
    let start = 0;
    for (let end = text.indexOf('\n'); end !== -1; end = text.indexOf('\n', start)) {
      this.parts.push(text.slice(start, end));
      let line = this.parts.join('');
      this.parts = [];
      if (line.endsWith('\r')) line = line.slice(0, -1);
      this.emit('line', line);
      if (this.closed) return;
      start = end + 1;
    }
    if (start < text.length) this.parts.push(text.slice(start));
  }

  private end = (): void => {
    this.consume(this.decoder.end());
    if (this.closed) return;
    if (this.parts.length) this.emit('line', this.parts.join(''));
    this.close();
  };

  private error = (error: Error): void => {
    this.close();
    this.emit('error', error);
  };

  close = (): void => {
    if (this.closed) return;
    this.closed = true;
    this.parts = [];
    this.input.off('data', this.data);
    this.input.off('end', this.end);
    this.input.off('close', this.close);
    this.input.off('error', this.error);
    this.emit('close');
  };
}

export function createJsonLineReader({ input }: { input: Readable }): JsonLineReader {
  return new JsonLineReader(input);
}
