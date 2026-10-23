/** A stored snapshot cannot erase or regress verified live items from the same connection. */
export function mergeSharedMessages<T extends { id: string; status: string }>(
  snapshot: T[],
  observed: T[],
  liveIds: ReadonlySet<string>,
  changedDuringRead: boolean,
): T[] {
  const live = new Map(
    observed.filter((item) => liveIds.has(item.id)).map((item) => [item.id, item]),
  );
  const merged = snapshot.map((item) =>
    live.has(item.id) && (changedDuringRead || item.status === 'inProgress')
      ? live.get(item.id)!
      : item,
  );
  const ids = new Set(merged.map((item) => item.id));
  for (let index = 0; index < observed.length; index++) {
    const item = observed[index]!;
    if (!liveIds.has(item.id) || ids.has(item.id)) continue;
    const next = observed.slice(index + 1).find((entry) => ids.has(entry.id));
    const before = next ? merged.findIndex((entry) => entry.id === next.id) : -1;
    if (before >= 0) merged.splice(before, 0, item);
    else merged.push(item);
    ids.add(item.id);
  }
  return merged;
}
