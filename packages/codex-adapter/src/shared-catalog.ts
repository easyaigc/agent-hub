import { list, object, string, type CodexRuntime, type WireObject } from './runtime.js';

type Cursor = { active: string | null; archived: string | null };
const prefix = 'shared-v1:';

/** Both official catalogs share one opaque domain cursor; neither page hides the other. */
export async function sharedCatalogPage(runtime: Pick<CodexRuntime, 'call'>, cursor?: string) {
  let position: Cursor | undefined;
  if (cursor) {
    if (!cursor.startsWith(prefix) || cursor.length > 16384)
      throw new Error('Invalid shared catalog cursor');
    position = JSON.parse(Buffer.from(cursor.slice(prefix.length), 'base64url').toString());
    if (
      !position ||
      Object.keys(position).sort().join(',') !== 'active,archived' ||
      ![position.active, position.archived].every((v) => v === null || typeof v === 'string')
    )
      throw new Error('Invalid shared catalog cursor');
  }
  const pages = await Promise.all(
    [false, true].map(async (archived) => {
      const after = position?.[archived ? 'archived' : 'active'];
      if (after === null) return { rows: [] as WireObject[], next: null };
      const page = await runtime.call('thread/list', {
        archived,
        modelProviders: [],
        sortKey: 'updated_at',
        limit: 100,
        ...(after ? { cursor: after } : {}),
      });
      return {
        rows: list(page.data).map((entry) => ({ ...object(entry), archived })),
        next: string(page.nextCursor) || null,
      };
    }),
  );
  const next: Cursor = { active: pages[0]!.next, archived: pages[1]!.next };
  return {
    threads: pages.flatMap((page) => page.rows),
    nextCursor:
      next.active || next.archived
        ? prefix + Buffer.from(JSON.stringify(next)).toString('base64url')
        : null,
  };
}
