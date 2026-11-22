import {
  closeSync,
  constants,
  fstatSync,
  lstatSync,
  openSync,
  readSync,
  type BigIntStats,
} from 'node:fs';
import { createHash } from 'node:crypto';
import { types } from 'node:util';
import { beginCpuStage } from './cpu-stages.js';

type Primitive = string | number | boolean | null | undefined;
type Value = Primitive | Shape;
interface Shape {
  prototype: object | null;
  length: number | undefined;
  keys: string[];
  values: Value[];
}
interface Snapshot {
  value: Value;
  bytes: number;
  nodes: number;
}
type Stamp = ReturnType<typeof stamp>;
interface Entry extends Snapshot {
  file: string;
  stamp: NonNullable<Stamp>;
  sha256: string;
}

const maxEntryBytes = 96 * 1024 * 1024;
const maxBytes = 192 * 1024 * 1024;
const maxNodes = 524_288;
const maxEntryNodes = 262_144;
const maxProperties = 1_000_000;

function fileStamp(s: BigIntStats) {
  if (!s.isFile() || s.nlink !== 1n || s.mode & 0o077n || s.uid !== BigInt(process.getuid!()))
    return;
  return [s.dev, s.ino, s.size, s.mtimeNs, s.ctimeNs, s.mode, s.uid, s.gid].join(':');
}
function stamp(file: string) {
  try {
    const s = lstatSync(file, { bigint: true });
    return fileStamp(s);
  } catch {
    // A miss uses the original write/validation path, never a cached permission.
    return;
  }
}

/** Last written data only. Every match walks current values, including in-place edits. */
export class PersistenceSnapshots {
  private readonly entries = new Map<string, Entry>();
  private bytes = 0;
  private nodes = 0;

  has(key: string): boolean {
    return this.entries.has(key);
  }

  capture(input: unknown): Snapshot | undefined {
    // A conservative retained-data weight, not a content limit or an RSS claim.
    // Capture precedes JSON: getters/toJSON must not run twice or self-modify
    // an input into a snapshot different from the bytes actually serialized.
    let bytes = 0;
    let nodes = 0;
    let properties = 0;
    const copy = (value: unknown, depth: number): Value => {
      if (
        value === null ||
        value === undefined ||
        typeof value === 'string' ||
        typeof value === 'number' ||
        typeof value === 'boolean'
      ) {
        bytes += typeof value === 'string' ? value.length * 2 : 8;
        if (bytes > maxEntryBytes) throw new Error('ineligible snapshot');
        return value as Primitive;
      }
      if (
        typeof value !== 'object' ||
        types.isProxy(value) ||
        depth > 64 ||
        ++nodes > maxEntryNodes
      )
        throw new Error('ineligible snapshot');
      const prototype: object | null = Object.getPrototypeOf(value);
      if (
        (prototype !== Object.prototype && prototype !== Array.prototype && prototype !== null) ||
        'toJSON' in value
      )
        throw new Error('ineligible snapshot');
      const keys = Object.keys(value);
      const array = Array.isArray(value);
      // JSON arrays also read non-enumerable and inherited indices. Only
      // dense own enumerable indices are eligible for structural reuse.
      if (array && keys.length !== value.length) throw new Error('ineligible snapshot');
      properties += keys.length;
      bytes += 64;
      if (properties > maxProperties) throw new Error('ineligible snapshot');
      const values = keys.map((key, index) => {
        if (array && key !== String(index)) throw new Error('ineligible snapshot');
        bytes += key.length * 2 + 32;
        if (bytes > maxEntryBytes) throw new Error('ineligible snapshot');
        const descriptor = Object.getOwnPropertyDescriptor(value, key)!;
        if (!Object.hasOwn(descriptor, 'value')) throw new Error('ineligible snapshot');
        return copy(descriptor.value, depth + 1);
      });
      return { prototype, length: array ? value.length : undefined, keys, values };
    };
    try {
      const value = copy(input, 0);
      return { value, bytes, nodes };
    } catch {
      beginCpuStage('persistence.snapshot.declined')?.();
      return;
    }
  }

  matches(key: string, input: unknown, file: string, verifyBytes = false): boolean {
    const entry = this.entries.get(key);
    if (!entry || entry.file !== file || stamp(file) !== entry.stamp) return false;
    const same = (value: unknown, saved: Value): boolean => {
      if (saved === null || typeof saved !== 'object') return Object.is(value, saved);
      if (
        value === null ||
        typeof value !== 'object' ||
        types.isProxy(value) ||
        Object.getPrototypeOf(value) !== saved.prototype ||
        'toJSON' in value ||
        (Array.isArray(value) ? value.length : undefined) !== saved.length
      )
        return false;
      const keys = Object.keys(value);
      if (keys.length !== saved.keys.length) return false;
      for (let i = 0; i < keys.length; i++) {
        if (keys[i] !== saved.keys[i]) return false;
        const descriptor = Object.getOwnPropertyDescriptor(value, keys[i]!);
        if (
          !descriptor ||
          !Object.hasOwn(descriptor, 'value') ||
          !same(descriptor.value, saved.values[i])
        )
          return false;
      }
      return true;
    };
    if (!same(input, entry.value)) return false;
    if (verifyBytes) {
      let fd: number | undefined;
      const end = beginCpuStage('persistence.sha256');
      try {
        fd = openSync(file, constants.O_RDONLY | constants.O_NOFOLLOW);
        const size = Number(entry.stamp.split(':')[2]);
        if (
          !Number.isSafeInteger(size) ||
          size < 0 ||
          size > 512 * 1024 * 1024 ||
          fileStamp(fstatSync(fd, { bigint: true })) !== entry.stamp
        )
          return false;
        const bytes = Buffer.allocUnsafe(Math.min(size, 256 * 1024) || 1);
        const hash = createHash('sha256');
        let offset = 0;
        while (offset < size) {
          const count = readSync(fd, bytes, 0, Math.min(bytes.length, size - offset), offset);
          if (!count) return false;
          hash.update(bytes.subarray(0, count));
          offset += count;
        }
        if (
          readSync(fd, bytes, 0, 1, size) !== 0 ||
          hash.digest('hex') !== entry.sha256 ||
          fileStamp(fstatSync(fd, { bigint: true })) !== entry.stamp
        )
          return false;
      } catch {
        return false;
      } finally {
        if (fd !== undefined) closeSync(fd);
        end?.();
      }
    }
    this.entries.delete(key);
    this.entries.set(key, entry);
    return true;
  }

  remember(key: string, file: string, value: Snapshot | undefined, sha256: string): void {
    this.forget(key);
    const current = value && stamp(file);
    if (!value || !current) return;
    this.entries.set(key, { ...value, file, stamp: current, sha256 });
    this.bytes += value.bytes;
    this.nodes += value.nodes;
    while (this.bytes > maxBytes || this.nodes > maxNodes || this.entries.size > 65)
      this.forget(this.entries.keys().next().value!);
  }

  forget(key: string): void {
    const entry = this.entries.get(key);
    if (!entry) return;
    this.bytes -= entry.bytes;
    this.nodes -= entry.nodes;
    this.entries.delete(key);
  }

  clear(): void {
    this.entries.clear();
    this.bytes = this.nodes = 0;
  }
}
