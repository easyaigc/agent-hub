import { object, list, string, type WireObject } from './runtime.js';
import { applyDesktopPatches } from './desktop-ipc.js';

function boundedItem(item: WireObject): boolean {
  let budget = 256 * 1024,
    nodes = 0;
  const seen = new WeakSet<object>();
  const fits = (value: unknown, depth: number): boolean => {
    if (++nodes > 8192 || depth > 64 || budget < 0) return false;
    if (typeof value === 'string') {
      if (value.length > budget) return false;
      budget -= Buffer.byteLength(value) + 2;
    } else if (value && typeof value === 'object') {
      if (seen.has(value)) return false;
      seen.add(value);
      for (const key in value) {
        budget -= key.length + 4;
        if (!fits((value as WireObject)[key], depth + 1)) return false;
      }
    } else if (value === null || typeof value === 'number' || typeof value === 'boolean')
      budget -= 8;
    else return false;
    return budget >= 0;
  };
  // Reject large strings/containers before materializing another JSON copy.
  return (
    Boolean(string(item.id) && string(item.type)) &&
    fits(item, 0) &&
    Buffer.byteLength(JSON.stringify(item)) <= 256 * 1024
  );
}

export interface DesktopLiveObservation {
  revision: number;
  receivedAt: number;
  seeded: boolean;
  items: { turnId: string; item: WireObject; receivedAt: number }[];
}

/** A bounded observation of complete new items, never a substitute stream base.
 * Opaque canonical entities are bound to independently read item/turn IDs.
 * An unknown item/ancestor is deliberately
 * ignored until its complete value arrives; text alone cannot establish identity.
 */
export class DesktopLiveWindow {
  private revision = -1;
  private receivedAt = 0;
  private turns = new Map<string, Map<number, WireObject>>();
  private turnIds = new Map<string, string>();
  private anchorTurnId: string | undefined;
  private seeded = false;
  private itemTimes = new WeakMap<WireObject, number>();
  seed(state: WireObject, revision: number): void {
    this.clear();
    this.revision = revision;
    this.seeded = true;
    const history = object(object(state.turnHistory).history);
    const entities = object(history.entitiesByKey);
    const keys = list(history.islands)
      .flatMap((v) => list(object(v).entries).map((v) => string(object(v).value)))
      .slice(-2);
    for (const key of keys) {
      const turn = object(entities[key]);
      if (!string(turn.turnId)) continue;
      const items = new Map<number, WireObject>(),
        values = list(turn.items);
      for (let index = Math.max(0, values.length - 30); index < values.length; index++) {
        const item = object(values[index]);
        if (boundedItem(item)) {
          items.set(index, item);
          this.itemTimes.set(item, 0);
        }
      }
      this.turns.set(key, items);
      this.turnIds.set(key, string(turn.turnId));
      this.anchorTurnId = string(turn.turnId);
    }
  }
  clear(): void {
    this.revision = -1;
    this.receivedAt = 0;
    this.turns.clear();
    this.turnIds.clear();
    this.anchorTurnId = undefined;
    this.seeded = false;
  }
  apply(change: WireObject): DesktopLiveObservation | undefined {
    const revision = change.revision,
      base = change.baseRevision;
    if (
      change.type !== 'patches' ||
      !Number.isSafeInteger(revision) ||
      !Number.isSafeInteger(base) ||
      Number(revision) <= Number(base)
    )
      return;
    if (Number(revision) <= this.revision) return;
    if (this.revision !== Number(base)) {
      this.turns.clear();
      this.turnIds.clear();
      this.seeded = false;
    }
    this.revision = Number(revision);
    this.receivedAt = Date.now();
    for (const value of list(change.patches)) {
      const patch = object(value),
        path = list(patch.path);
      if (
        path.length < 4 ||
        path[0] !== 'turnHistory' ||
        path[1] !== 'history' ||
        path[2] !== 'entitiesByKey' ||
        typeof path[3] !== 'string'
      )
        continue;
      const turnId = path[3];
      if (turnId.length > 256 || ['__proto__', 'prototype', 'constructor'].includes(turnId))
        continue;
      if (path.length === 4 && ['add', 'replace'].includes(string(patch.op))) {
        const turn = object(patch.value);
        if (!string(turn.turnId) || !Array.isArray(turn.items)) continue;
        this.turnIds.set(turnId, string(turn.turnId));
        const items = new Map<number, WireObject>();
        for (let index = Math.max(0, turn.items.length - 30); index < turn.items.length; index++) {
          const item = object(turn.items[index]);
          if (boundedItem(item)) {
            items.set(index, item);
            this.itemTimes.set(item, this.receivedAt);
          }
        }
        this.turns.set(turnId, items);
        continue;
      }
      if (
        path.length === 5 &&
        path[4] === 'items' &&
        ['add', 'replace'].includes(string(patch.op)) &&
        Array.isArray(patch.value)
      ) {
        const items = new Map<number, WireObject>();
        for (
          let index = Math.max(0, patch.value.length - 30);
          index < patch.value.length;
          index++
        ) {
          const item = object(patch.value[index]);
          if (boundedItem(item)) {
            items.set(index, item);
            this.itemTimes.set(item, this.receivedAt);
          }
        }
        this.turns.set(turnId, items);
        continue;
      }
      if (
        path.length < 6 ||
        path[4] !== 'items' ||
        !Number.isSafeInteger(path[5]) ||
        Number(path[5]) < 0
      )
        continue;
      let items = this.turns.get(turnId);
      if (!items) {
        items = new Map();
        this.turns.set(turnId, items);
      }
      const index = Number(path[5]);
      if (path.length === 6 && ['add', 'remove'].includes(string(patch.op))) {
        const shifted = new Map<number, WireObject>();
        for (const [key, item] of items) {
          if (patch.op === 'remove' && key === index) continue;
          shifted.set(key >= index ? key + (patch.op === 'add' ? 1 : -1) : key, item);
        }
        items = shifted;
        this.turns.set(turnId, items);
      }
      if (path.length === 6) {
        if (['add', 'replace'].includes(string(patch.op))) {
          const item = object(patch.value);
          if (boundedItem(item)) {
            items.set(index, item);
            this.itemTimes.set(item, this.receivedAt);
          } else items.delete(index);
        }
      } else if (items.has(index)) {
        try {
          const item = applyDesktopPatches(items.get(index)!, [{ ...patch, path: path.slice(6) }]);
          if (!boundedItem(item)) items.delete(index);
          else {
            items.set(index, item);
            this.itemTimes.set(item, this.receivedAt);
          }
        } catch {
          items.delete(index);
        }
      }
      while (items.size > 30) items.delete(Math.min(...items.keys()));
    }
    while (this.turns.size > 2) {
      const first = this.turns.keys().next().value!;
      this.turns.delete(first);
      this.turnIds.delete(first);
    }
    return this.observation();
  }
  observation(): DesktopLiveObservation | undefined {
    if (!this.receivedAt) return;
    return {
      revision: this.revision,
      receivedAt: this.receivedAt,
      seeded: this.seeded,
      items: [...this.turns].flatMap(([turnId, items]) =>
        !this.turnIds.has(turnId)
          ? []
          : [...items]
              .sort(([a], [b]) => a - b)
              .map(([, item]) => ({
                turnId: this.turnIds.get(turnId)!,
                item,
                receivedAt: this.itemTimes.get(item)!,
              })),
      ),
    };
  }
  /** Bind through exact item IDs, never text or array position. */
  bind(messages: { id: string; runId?: string }[]): void {
    const tip = messages.at(-1)?.runId;
    if (
      tip &&
      this.anchorTurnId &&
      tip !== this.anchorTurnId &&
      ![...this.turnIds.values()].includes(tip)
    )
      this.clear();
    if (tip) this.anchorTurnId = tip;
    const ids = new Map(messages.filter((v) => v.runId).map((v) => [v.id, v.runId!]));
    for (const [key, items] of this.turns) {
      const candidates = new Set(
        [...items.values()].map((item) => ids.get(string(item.id))).filter(Boolean),
      );
      if (candidates.size === 1) {
        const turn = [...candidates][0]!;
        if (!this.turnIds.has(key) || this.turnIds.get(key) === turn) this.turnIds.set(key, turn);
        else {
          this.turns.delete(key);
          this.turnIds.delete(key);
        }
      } else if (candidates.size > 1) {
        this.turns.delete(key);
        this.turnIds.delete(key);
      }
    }
  }
}
