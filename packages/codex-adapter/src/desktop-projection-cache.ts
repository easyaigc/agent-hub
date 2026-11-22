import { performance } from 'node:perf_hooks';

/** Presentation reuse only. Never stores authorization or a source wire object. */
export class DesktopProjectionCache<T> {
  private entries = new Map<
    string,
    {
      threadId: string;
      fingerprint: string;
      value: { message: T; referenceText: string };
      bytes: number;
      expires: number;
    }
  >();
  private bytes = 0;
  constructor(
    private readonly maxEntries = 2048,
    private readonly maxBytes = 16 * 1024 * 1024,
    private readonly now: () => number = () => performance.now(),
  ) {}
  get(key: string, fingerprint: string) {
    const entry = this.entries.get(key);
    if (!entry) return undefined;
    if (entry.fingerprint !== fingerprint || entry.expires <= this.now()) {
      this.remove(key);
      return undefined;
    }
    this.entries.delete(key);
    this.entries.set(key, entry);
    return structuredClone(entry.value);
  }
  set(
    key: string,
    threadId: string,
    fingerprint: string,
    value: { message: T; referenceText: string },
  ) {
    this.remove(key);
    const bytes = Buffer.byteLength(JSON.stringify(value), 'utf8') + key.length * 2;
    if (bytes > Math.min(this.maxBytes, 256 * 1024)) return;
    this.entries.set(key, {
      threadId,
      fingerprint,
      value: structuredClone(value),
      bytes,
      expires: this.now() + 60_000,
    });
    this.bytes += bytes;
    while (this.entries.size > this.maxEntries || this.bytes > this.maxBytes)
      this.remove(this.entries.keys().next().value!);
  }
  private remove(key: string) {
    const entry = this.entries.get(key);
    if (entry) this.bytes -= entry.bytes;
    this.entries.delete(key);
  }
  invalidate(threadId: string) {
    for (const [key, entry] of this.entries) if (entry.threadId === threadId) this.remove(key);
  }
  clear() {
    this.entries.clear();
    this.bytes = 0;
  }
}

/** Decline large/complex input before JSON fingerprinting allocates a full copy. */
export function boundedProjectionInput(input: unknown): boolean {
  const pending: unknown[] = [input];
  const seen = new Set<object>();
  let units = 64 * 1024;
  let nodes = 512;
  while (pending.length) {
    if (--nodes < 0 || units < 0) return false;
    const value = pending.pop();
    if (typeof value === 'string') units -= value.length;
    else if (value && typeof value === 'object') {
      if (seen.has(value)) return false;
      seen.add(value);
      if (Array.isArray(value)) {
        if (value.length > nodes) return false;
        pending.push(...value);
      } else {
        const prototype = Object.getPrototypeOf(value);
        if (prototype !== Object.prototype && prototype !== null) return false;
        for (const key in value) {
          if (!Object.hasOwn(value, key)) continue;
          if (--nodes < 0) return false;
          units -= key.length;
          if (units < 0) return false;
          pending.push((value as Record<string, unknown>)[key]);
        }
      }
    } else if (!['undefined', 'boolean', 'number'].includes(typeof value) && value !== null)
      return false;
  }
  return units >= 0;
}
