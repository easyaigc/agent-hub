import { createHash } from 'node:crypto';
import { readFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';

type Row = Record<string, unknown>;
const object = (value: unknown): Row => {
  if (!value || typeof value !== 'object' || Array.isArray(value))
    throw new Error('Invalid desktop display state');
  return value as Row;
};
const globalKeys = [
  'local-projects',
  'app-server-project-id-by-legacy-project-id-by-host',
  'project-order',
  'pinned-project-ids',
  'projectless-thread-ids',
  'thread-project-assignments',
  'thread-project-membership-host-ids',
  'sidebar-project-thread-orders',
  'pinned-thread-ids',
  'electron-persisted-atom-state',
  'electron-renderer-content-owner',
] as const;
const ownerKeys = [
  'thread-workspace-root-hints',
  'app-server-migrated-pinned-thread-ids-by-host',
  'app-server-pending-project-deletions-by-host',
] as const;
const atomKeys = [
  'flat-project-sidebar-preferences-v1',
  'codex-sidebar-sort-mode-v1',
  'pinned-sidebar-sort-mode-v1',
  'sidebar-show-projects-in-recents',
  'unified-sidebar-project-order-v1',
  'unified-sidebar-chat-order-v1',
  'unified-sidebar-pinned-order-v1',
] as const;

function missing(path: string): boolean {
  try {
    statSync(path);
    return false;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') return true;
    throw error;
  }
}

/** A narrow, read-only projection of the desktop's own store, including committed WAL data. */
export function readDesktopDisplayState(jsonPath: string): Row {
  const sqlitePath = join(dirname(jsonPath), '.codex-global-state.sqlite');
  if (missing(sqlitePath)) {
    let state: Row;
    try {
      state = object(JSON.parse(readFileSync(jsonPath, 'utf8')));
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error;
      state = {};
    }
    // Migration may commit while the old JSON is being read. Never publish it
    // as latest after the new store has appeared.
    if (missing(sqlitePath)) {
      if (Object.hasOwn(state, 'local-projects'))
        state['local-projects'] = object(state['local-projects'] ?? {});
      return state;
    }
  }
  const database = new DatabaseSync(sqlitePath, { readOnly: true, allowExtension: false });
  try {
    database.exec('BEGIN');
    const select = database.prepare('SELECT value FROM settings WHERE scope = ? AND key = ?');
    const read = (scope: string, key: string): unknown => {
      const row = select.get(scope, key);
      if (!row) return undefined;
      if (typeof row.value !== 'string') throw new Error('Invalid desktop setting value');
      return JSON.parse(row.value) as unknown;
    };
    const state: Row = {};
    for (const key of globalKeys) {
      const value = read('global', key);
      if (value !== undefined) state[key] = value;
    }
    // The official accessor treats an uninitialized/null catalog as empty.
    state['local-projects'] = object(state['local-projects'] ?? {});
    const owner = state['electron-renderer-content-owner'];
    const atoms: Row = {};
    for (const key of atomKeys) {
      const owned =
        typeof owner === 'string' ? read('electron-owner-atoms:' + owner, key) : undefined;
      const live = read('atom', key);
      const value = live === undefined ? owned : live;
      if (value !== undefined) atoms[key] = value;
    }
    state['electron-persisted-atom-state'] = atoms;
    // These are owner-scoped ordinary values, not atoms. Missing values must
    // not inherit an old owner's or a stale global value.
    if (typeof owner === 'string') {
      for (const key of ownerKeys) {
        const value = read('global', `electron-owner-value:${key}:${owner}`);
        if (value !== undefined) state[key] = value;
      }
    }
    database.exec('COMMIT');
    return state;
  } finally {
    database.close();
  }
}

export function desktopDisplayFingerprint(state: Row): string {
  const projection: Row = {};
  for (const key of [...globalKeys, ...ownerKeys]) {
    if (key === 'electron-persisted-atom-state' || key === 'electron-renderer-content-owner')
      continue;
    if (Object.hasOwn(state, key)) projection[key] = state[key];
  }
  const atoms = state['electron-persisted-atom-state'];
  if (atoms && typeof atoms === 'object' && !Array.isArray(atoms)) {
    projection['electron-persisted-atom-state'] = Object.fromEntries(
      atomKeys.flatMap((key) => (Object.hasOwn(atoms, key) ? [[key, (atoms as Row)[key]]] : [])),
    );
  }
  const projects = projection['local-projects'];
  if (projects && typeof projects === 'object' && !Array.isArray(projects)) {
    projection['local-projects'] = Object.fromEntries(
      Object.entries(projects).map(([id, value]) => {
        if (!value || typeof value !== 'object' || Array.isArray(value)) return [id, value];
        return [
          id,
          Object.fromEntries(
            ['name', 'rootPaths', 'createdAt'].flatMap((key) =>
              Object.hasOwn(value, key) ? [[key, (value as Row)[key]]] : [],
            ),
          ),
        ];
      }),
    );
  }
  return createHash('sha256').update(JSON.stringify(projection)).digest('hex');
}
