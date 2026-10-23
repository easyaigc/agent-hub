import { object, string, type WireObject } from './runtime.js';

type RpcId = string | number;
interface Pending {
  originalId: RpcId;
  origin: 'desktop' | 'probe';
  method: string;
  deliver: (message: WireObject) => void;
}
const isId = (value: unknown): value is RpcId =>
  typeof value === 'string' || (typeof value === 'number' && Number.isSafeInteger(value));

/**
 * Experimental transport only. One original desktop initialization and executor.
 * This does not establish desktop UI ownership or advertise creation to iOS.
 */
export class DesktopStdioRouter {
  private counter = 0;
  private pending = new Map<number, Pending>();
  private serverRequests = new Set<RpcId>();
  private initializedResult?: WireObject;
  private initializedNotification = false;
  private closed = false;
  private desktopInitializationStarted = false;
  constructor(
    private readonly upstream: (message: WireObject) => void,
    private readonly desktop: (message: WireObject) => void,
  ) {}
  get ready(): boolean {
    return !this.closed && !!this.initializedResult && this.initializedNotification;
  }
  status(): WireObject {
    return {
      ready: this.ready,
      userAgent: string(this.initializedResult?.userAgent),
      pending: this.pending.size,
      realDesktopCreationVerified: false,
    };
  }
  fromDesktop(value: unknown): void {
    if (this.closed) throw new Error('Desktop transport closed');
    const message = object(value);
    const method = string(message.method);
    if (!method && isId(message.id)) {
      // Only the original desktop can answer upstream approvals/dynamic tools.
      if (!this.serverRequests.delete(message.id)) throw new Error('Unknown desktop response');
      this.upstream(message);
      return;
    }
    if (!method) throw new Error('Invalid desktop message');
    if (method === 'initialize') {
      if (this.desktopInitializationStarted) throw new Error('Duplicate desktop initialization');
      this.desktopInitializationStarted = true;
    }
    if (!isId(message.id)) {
      if (method === 'initialized') {
        if (!this.initializedResult || this.initializedNotification)
          throw new Error('Invalid initialization order');
        this.initializedNotification = true;
      }
      this.upstream(message);
      return;
    }
    this.forward(message, 'desktop', this.desktop);
  }
  private forward(
    message: WireObject,
    origin: Pending['origin'],
    deliver: Pending['deliver'],
  ): void {
    const id = ++this.counter;
    this.pending.set(id, {
      originalId: message.id as RpcId,
      origin,
      method: string(message.method),
      deliver,
    });
    try {
      this.upstream({ ...message, id });
    } catch (error) {
      this.pending.delete(id);
      throw error;
    }
  }
  fromUpstream(value: unknown): void {
    if (this.closed) return;
    const message = object(value);
    const method = string(message.method);
    if (method) {
      if (isId(message.id)) {
        if (this.serverRequests.has(message.id)) throw new Error('Duplicate upstream request');
        this.serverRequests.add(message.id);
      }
      // Real server notifications reach the desktop, including thread/started.
      // A probe response can never fabricate a desktop notification or tool call.
      this.desktop(message);
      return;
    }
    if (typeof message.id !== 'number') throw new Error('Invalid upstream response');
    const pending = this.pending.get(message.id);
    if (!pending) throw new Error('Unknown upstream response');
    this.pending.delete(message.id);
    if (pending.origin === 'desktop' && pending.method === 'initialize' && !message.error)
      this.initializedResult = object(message.result);
    pending.deliver({ ...message, id: pending.originalId });
  }
  probe(message: WireObject, deliver: Pending['deliver']): void {
    if (!this.ready) throw new Error('Original desktop not initialized');
    if (!isId(message.id) || !string(message.method)) throw new Error('Invalid probe request');
    // This class routes only; the local entry owns authentication and method/target policy.
    if (['initialize', 'initialized'].includes(string(message.method)))
      throw new Error('Probe cannot initialize the original executor');
    this.forward(message, 'probe', deliver);
  }
  close(): void {
    if (this.closed) return;
    this.closed = true;
    for (const pending of this.pending.values()) {
      try {
        pending.deliver({
          id: pending.originalId,
          error: { code: -32001, message: 'Original executor disconnected; outcome unknown' },
        });
      } catch {
        /* A disconnected recipient must not prevent transport cleanup. */
      }
    }
    this.pending.clear();
    this.serverRequests.clear();
  }
}
