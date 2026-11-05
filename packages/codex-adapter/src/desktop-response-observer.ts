import { parentPort } from 'node:worker_threads';
import {
  brotliDecompressSync,
  createInflateRaw,
  gunzipSync,
  inflateSync,
  constants,
  type InflateRaw,
} from 'node:zlib';
import type {
  DesktopResponseForwarderEvent,
  PredictionIdentity,
  ResponseTransport,
} from './desktop-response-forwarder-types.js';

const MAX = 4 * 1024 * 1024;
const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
type Json = Record<string, unknown>;
function object(value: unknown): Json {
  if (!value || typeof value !== 'object' || Array.isArray(value))
    throw new Error('invalid object');
  return value as Json;
}
interface Decoder {
  buffered: Buffer;
  fragments: Buffer[];
  size: number;
  compressed: boolean;
  inflater?: InflateRaw;
}
interface Active {
  identity: PredictionIdentity | null;
  responseId: string | null;
  items: string[];
  bytes: number;
  complete: boolean;
  textOnly: boolean;
}
interface Compression {
  request: { windowBits: number; noContextTakeover: boolean };
  response: { windowBits: number; noContextTakeover: boolean };
}
interface State {
  id: string;
  transport: ResponseTransport;
  seq: number;
  active?: Active;
  warmup: boolean;
  invalid: boolean;
  compression?: Compression | null;
  request?: Decoder;
  response?: Decoder;
}
interface Message {
  kind: string;
  id: string;
  transport?: ResponseTransport;
  direction?: 'request' | 'response';
  chunk?: Uint8Array;
  body?: Uint8Array;
  bytes: number;
  encoding?: string;
  compression?: Compression | null;
}
const states = new Map<string, State>();
const port = parentPort;
if (!port) throw new Error('Observer requires a worker');
function emit(event: DesktopResponseForwarderEvent): void {
  port!.postMessage(event);
}
function release(state: State): void {
  state.request?.inflater?.destroy();
  state.response?.inflater?.destroy();
  delete state.request;
  delete state.response;
}
function result(
  state: State,
  status: 'completed' | 'empty' | 'failed' | 'cancelled' | 'unverified',
  text: string | null = null,
): void {
  const active = state.active;
  if (!active || active.complete) return;
  active.complete = true;
  if (active.identity)
    emit({
      kind: 'prediction-result',
      ...active.identity,
      responseId: active.responseId,
      text,
      state: status,
      completedAt: Date.now(),
    });
}
function invalidate(state: State): void {
  if (state.invalid) return;
  result(state, 'unverified');
  state.invalid = true;
  release(state);
  emit({ kind: 'observation-unavailable', connectionId: state.id });
}
function begin(state: State, value: Json): void {
  if ((state.active && !state.active.complete) || state.warmup)
    throw new Error('overlapping request');
  if (value.generate === false) {
    state.warmup = true;
    return;
  }
  const encoded =
    value.client_metadata == null
      ? undefined
      : object(value.client_metadata)['x-codex-turn-metadata'];
  const metadata = typeof encoded === 'string' ? object(JSON.parse(encoded) as unknown) : {};
  const thread = metadata.prediction_source_thread_id,
    turn = metadata.prediction_source_turn_id;
  const identity =
    typeof thread === 'string' &&
    uuid.test(thread) &&
    typeof turn === 'string' &&
    uuid.test(turn) &&
    thread.toLowerCase() !== turn.toLowerCase()
      ? {
          connectionId: state.id,
          requestId: `${state.id}:${++state.seq}`,
          sourceThreadId: thread.toLowerCase(),
          sourceTurnId: turn.toLowerCase(),
          requestedAt: Date.now(),
        }
      : null;
  state.active = {
    identity,
    responseId: null,
    items: [],
    bytes: 0,
    complete: false,
    textOnly: true,
  };
  if (identity) emit({ kind: 'prediction-start', ...identity });
}
function event(state: State, value: Json): void {
  const type = typeof value.type === 'string' ? value.type : '';
  if (state.warmup) {
    if (['response.completed', 'response.failed', 'response.incomplete', 'error'].includes(type))
      state.warmup = false;
    return;
  }
  const active = state.active;
  if (!active || active.complete) return;
  const response = value.response == null ? {} : object(value.response);
  const item = value.item == null ? {} : object(value.item);
  if (value.type === 'response.created') {
    if (
      typeof response.id !== 'string' ||
      !response.id ||
      response.id.length > 512 ||
      active.responseId
    )
      throw new Error('invalid response identity');
    active.responseId = response.id;
  }
  if (value.type === 'error' || value.type === 'response.failed') {
    result(state, 'failed');
    return;
  }
  if (value.type === 'response.incomplete') {
    result(state, 'cancelled');
    return;
  }
  if (
    value.type === 'response.output_item.added' &&
    ['function_call', 'custom_tool_call', 'local_shell_call', 'tool_search_call'].includes(
      typeof item.type === 'string' ? item.type : '',
    )
  )
    active.textOnly = false;
  if (value.type === 'response.output_item.done') {
    if (item?.type === 'message' && item.phase === 'commentary') return;
    if (item?.type === 'reasoning') return;
    if (item?.type !== 'message' || item.role !== 'assistant' || !Array.isArray(item.content)) {
      active.textOnly = false;
      return;
    }
    for (const content of item.content) {
      const part = object(content);
      if (part?.type !== 'output_text' || typeof part.text !== 'string') {
        active.textOnly = false;
        continue;
      }
      active.bytes += Buffer.byteLength(part.text);
      if (active.bytes > MAX) throw new Error('output budget');
      active.items.push(part.text);
    }
  }
  if (value.type !== 'response.completed') return;
  if (!active.responseId || active.responseId !== response.id) {
    result(state, 'unverified');
    return;
  }
  if (!active.textOnly || !active.items.length) {
    result(state, 'empty');
    return;
  }
  try {
    const parsed: unknown = JSON.parse(active.items.join(''));
    if (
      !parsed ||
      typeof parsed !== 'object' ||
      Array.isArray(parsed) ||
      !Object.hasOwn(parsed, 'suggestion')
    ) {
      result(state, 'unverified');
      return;
    }
    const suggestion = (parsed as Json).suggestion;
    if (suggestion !== null && typeof suggestion !== 'string') {
      result(state, 'unverified');
      return;
    }
    const text = typeof suggestion === 'string' ? suggestion.trim() : '';
    if (text.length > 16000) {
      result(state, 'unverified');
      return;
    }
    result(state, text ? 'completed' : 'empty', text || null);
  } catch {
    result(state, 'unverified');
  }
}
async function inflate(
  decoder: Decoder,
  data: Buffer,
  options: Compression['request'],
): Promise<Buffer> {
  const inflater = (decoder.inflater ??= createInflateRaw({ windowBits: options.windowBits }));
  const chunks: Buffer[] = [];
  let size = 0;
  const output = await new Promise<Buffer>((resolve, reject) => {
    const cleanup = () => {
      inflater.removeListener('data', onData);
      inflater.removeListener('error', onError);
    };
    const onError = () => {
      cleanup();
      reject(new Error('inflate failed'));
    };
    const onData = (chunk: Buffer) => {
      size += chunk.length;
      if (size > MAX) {
        inflater.destroy();
        onError();
      } else chunks.push(chunk);
    };
    inflater.on('data', onData);
    inflater.once('error', onError);
    inflater.write(data);
    inflater.write(Buffer.from([0, 0, 255, 255]));
    inflater.flush(constants.Z_SYNC_FLUSH, () => {
      cleanup();
      resolve(Buffer.concat(chunks));
    });
  });
  if (options.noContextTakeover) {
    inflater.close();
    delete decoder.inflater;
  }
  return output;
}
async function frames(
  state: State,
  direction: 'request' | 'response',
  chunk: Uint8Array,
): Promise<void> {
  const decoder = (state[direction] ??= {
    buffered: Buffer.alloc(0),
    fragments: [],
    size: 0,
    compressed: false,
  });
  decoder.buffered = Buffer.concat([decoder.buffered, chunk]);
  if (decoder.buffered.length > MAX) throw new Error('frame budget');
  while (decoder.buffered.length >= 2) {
    const data = decoder.buffered,
      first = data[0]!,
      second = data[1]!,
      opcode = first & 15;
    if (first & 0x30) throw new Error('reserved frame');
    const compressed = !!(first & 0x40);
    if (compressed && (!state.compression || opcode !== 1)) throw new Error('compression');
    let length = second & 127,
      offset = 2;
    if (length === 126) {
      if (data.length < 4) return;
      length = data.readUInt16BE(2);
      offset = 4;
    }
    if (length === 127) {
      if (data.length < 10) return;
      const n = data.readBigUInt64BE(2);
      if (n > BigInt(MAX)) throw new Error('frame budget');
      length = Number(n);
      offset = 10;
    }
    const masked = !!(second & 128);
    if ((direction === 'request') !== masked) throw new Error('invalid masking');
    if (length > MAX) throw new Error('frame budget');
    if (data.length < offset + (masked ? 4 : 0) + length) return;
    const mask = masked ? data.subarray(offset, offset + 4) : null;
    if (masked) offset += 4;
    const body = Buffer.from(data.subarray(offset, offset + length));
    decoder.buffered = Buffer.from(data.subarray(offset + length));
    if (mask) for (let i = 0; i < body.length; i++) body[i] = body[i]! ^ mask[i % 4]!;
    if (opcode >= 8) {
      if (![8, 9, 10].includes(opcode) || !(first & 128) || length > 125 || compressed)
        throw new Error('invalid control frame');
      continue;
    }
    if (
      (opcode !== 0 && opcode !== 1) ||
      (opcode === 0 && decoder.fragments.length === 0) ||
      (opcode === 1 && decoder.fragments.length > 0)
    )
      throw new Error('frame sequence');
    if (opcode === 1) decoder.compressed = compressed;
    decoder.fragments.push(body);
    decoder.size += body.length;
    if (decoder.size > MAX) throw new Error('message budget');
    if (!(first & 128)) continue;
    let message: Buffer = Buffer.concat(decoder.fragments);
    decoder.fragments = [];
    decoder.size = 0;
    if (decoder.compressed)
      message = await inflate(decoder, message, state.compression![direction]);
    const value = object(
      JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(message)) as unknown,
    );
    if (direction === 'request') {
      if (value.type === 'response.create') begin(state, value);
    } else event(state, value);
  }
}
let sequence = Promise.resolve();
port.on('message', (message: Message) => {
  sequence = sequence.then(() => handle(message));
});
port.postMessage({ kind: 'ready' });
async function handle(message: Message): Promise<void> {
  try {
    if (message.kind === 'drop') {
      const state = states.get(message.id);
      if (state) {
        result(state, 'cancelled');
        release(state);
        states.delete(message.id);
      }
      return;
    }
    if (message.kind === 'invalidate') {
      const state = states.get(message.id);
      if (state) invalidate(state);
      return;
    }
    if (message.kind === 'http-failure') {
      const state = states.get(message.id);
      if (state) result(state, 'failed');
      return;
    }
    let state = states.get(message.id);
    if (!state) {
      if (states.size >= 64 || !message.transport) {
        emit({ kind: 'observation-unavailable', connectionId: message.id });
        return;
      }
      state = {
        id: message.id,
        transport: message.transport,
        seq: 0,
        warmup: false,
        invalid: false,
      };
      states.set(message.id, state);
    }
    if (state.invalid) return;
    if (message.kind === 'ws-config') {
      state.compression = message.compression ?? null;
      return;
    }
    if (message.kind === 'frames' && message.direction && message.chunk)
      await frames(state, message.direction, message.chunk);
    else if (message.kind === 'http-request' && message.body)
      begin(
        state,
        object(
          JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(message.body)) as unknown,
        ),
      );
    else if (message.kind === 'http-response' && message.body) {
      let body = Buffer.from(message.body);
      const opts = { maxOutputLength: MAX };
      if (message.encoding === 'gzip') body = gunzipSync(body, opts);
      else if (message.encoding === 'br') body = brotliDecompressSync(body, opts);
      else if (message.encoding === 'deflate') body = inflateSync(body, opts);
      else if (message.encoding && message.encoding !== 'identity') throw new Error('encoding');
      for (const block of new TextDecoder('utf-8', { fatal: true })
        .decode(body)
        .split(/\r?\n\r?\n/)) {
        const data = block
          .split(/\r?\n/)
          .filter((line) => line.startsWith('data:'))
          .map((line) => line.slice(5).trimStart())
          .join('\n');
        if (data && data !== '[DONE]') event(state, object(JSON.parse(data) as unknown));
      }
      if (state.active && !state.active.complete) result(state, 'unverified');
    }
    let total = 0;
    for (const candidate of states.values()) {
      const retained =
        (candidate.request?.buffered.length ?? 0) +
        (candidate.request?.size ?? 0) +
        (candidate.response?.buffered.length ?? 0) +
        (candidate.response?.size ?? 0) +
        (candidate.active?.bytes ?? 0);
      if (retained > MAX) invalidate(candidate);
      else total += retained;
    }
    if (total > 8 * 1024 * 1024) invalidate(state);
  } catch {
    const state = states.get(message.id);
    if (state) invalidate(state);
    else emit({ kind: 'observation-unavailable', connectionId: message.id });
  } finally {
    port!.postMessage({
      kind: 'ack',
      bytes: message.bytes,
      id: message.id,
      operation: message.kind,
    });
  }
}
