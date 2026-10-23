/** Current desktop profile's built-in server-owned pinned section. */
export const desktopPinnedSection = '01984de2-8f74-7c91-a3b2-5c5e937cf318';

import { readFileSync } from 'node:fs';
import { dirname, isAbsolute, normalize, sep } from 'node:path';
import type { WorkspaceSidebar } from '@codex-manage/protocol';
import type { ReadProject, ReadThread } from './history-reader.js';

type RecordValue = Record<string, unknown>;
const record = (value: unknown): RecordValue =>
  value && typeof value === 'object' && !Array.isArray(value) ? (value as RecordValue) : {};

/** Read only the desktop's local display assignments. Never write its state file. */
export class DesktopSidebar {
  private readonly state: RecordValue;
  private readonly projectIds: RecordValue;
  private readonly atoms: RecordValue;

  constructor(private readonly path: string) {
    try {
      this.state = record(JSON.parse(readFileSync(path, 'utf8')));
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT')
        throw new Error('Desktop sidebar metadata unavailable', { cause: error });
      this.state = {};
    }
    this.projectIds = record(
      record(this.state['app-server-project-id-by-legacy-project-id-by-host'])[
        `local:${dirname(path)}`
      ],
    );
    this.atoms = record(this.state['electron-persisted-atom-state']);
  }

  /** The desktop catalog, not every project left in the app-server database. */
  projects(projects: ReadProject[]): ReadProject[] {
    if (!Object.hasOwn(this.state, 'local-projects')) return projects;
    const catalog = record(this.state['local-projects']);
    const byId = new Map(projects.map((p) => [p.id, p]));
    const visible = Object.entries(catalog).flatMap(([id, value]) => {
      const nativeId = this.nativeProjectId(id);
      const project = byId.get(nativeId);
      const row = record(value);
      return project
        ? [{ ...project, name: typeof row.name === 'string' ? row.name : project.name }]
        : [];
    });
    const order = strings(this.atoms['unified-sidebar-project-order-v1'])
      .filter((id) => id.startsWith('codex:project:'))
      .map((id) => this.nativeProjectId(id.slice('codex:project:'.length)));
    return ordered(
      visible,
      order.length
        ? order
        : strings(this.state['project-order']).map((id) => this.nativeProjectId(id)),
    );
  }

  private nativeProjectId(id: string): string {
    return typeof this.projectIds[id] === 'string' ? this.projectIds[id] : id;
  }

  get hasProjectCatalog(): boolean {
    return Object.hasOwn(this.state, 'local-projects');
  }

  /** Match the desktop's one owner for shared roots instead of duplicating rows. */
  projectForDirectory(threadId: string, cwd: unknown, projects: ReadProject[]): string | null {
    const hint = record(this.state['thread-workspace-root-hints'])[threadId];
    const path = typeof hint === 'string' ? hint : cwd;
    if (typeof path !== 'string' || !isAbsolute(path)) return null;
    const normalized = (value: string) => {
      const p = normalize(value).replace(/[\\/]+$/, '') || sep;
      return process.platform === 'win32' ? p.toLowerCase() : p;
    };
    const directory = normalized(path);
    const catalog = record(this.state['local-projects']);
    const candidates = projects.flatMap((project) => {
      const legacy =
        Object.keys(this.projectIds).find((id) => this.projectIds[id] === project.id) ?? project.id;
      const row = record(catalog[legacy]);
      const roots = strings(row.rootPaths).length ? strings(row.rootPaths) : project.roots;
      return roots.filter(isAbsolute).flatMap((root) => {
        const value = normalized(root);
        return directory === value || directory.startsWith(value === sep ? sep : value + sep)
          ? [
              {
                id: project.id,
                legacy,
                root: value,
                roots,
                primary: normalized(roots[0] ?? '') === value,
                createdAt: Number(row.createdAt ?? 0),
              },
            ]
          : [];
      });
    });
    candidates.sort(
      (a, b) =>
        b.root.length - a.root.length ||
        Number(b.roots.length === 1) - Number(a.roots.length === 1) ||
        Number(b.primary) - Number(a.primary) ||
        a.createdAt - b.createdAt ||
        b.legacy.localeCompare(a.legacy),
    );
    return candidates[0]?.id ?? null;
  }

  /** Resolve all three lists once, using the desktop's persisted display rules. */
  presentation(
    projects: ReadProject[],
    threads: ReadThread[],
    pinnedIds: string[],
    metadata: RecordValue[],
  ): WorkspaceSidebar {
    const preferences = record(this.atoms['flat-project-sidebar-preferences-v1']);
    const manual = (key: string) =>
      this.atoms['codex-sidebar-sort-mode-v1'] == null &&
      preferences.manualSortVersion === 1 &&
      preferences[key] === 'manual';
    const recency = new Map(
      metadata.map((row) => [row.id, Number(row.recencyAt ?? row.updatedAt ?? row.createdAt ?? 0)]),
    );
    const active = threads.filter((thread) => !thread.archived);
    const sorted = active
      .slice()
      .sort((a, b) => (recency.get(b.id) ?? 0) - (recency.get(a.id) ?? 0));
    const activeIds = new Set(active.map((t) => t.id));
    const pinnedThreadIds = [...new Set(pinnedIds)].filter((id) => activeIds.has(id));
    const manualPins = !['updated_at', 'priority'].includes(
      String(this.atoms['pinned-sidebar-sort-mode-v1'] ?? 'manual'),
    );
    const displayedPins = manualPins
      ? pinnedThreadIds
      : pinnedThreadIds.slice().sort((a, b) => (recency.get(b) ?? 0) - (recency.get(a) ?? 0));
    const pinnedThreads = new Set(pinnedThreadIds);
    const projectIds = new Set(projects.map((p) => p.id));
    const pinnedProjectIds = strings(this.state['pinned-project-ids'])
      .map((id) => this.nativeProjectId(id))
      .filter((id) => projectIds.has(id));
    const pinnedProjects = new Set(pinnedProjectIds);
    const orders = record(this.state['sidebar-project-thread-orders']);
    const projectThreadIds: Record<string, string[]> = {};
    for (const project of projects) {
      const children = sorted.filter(
        (t) =>
          !pinnedThreads.has(t.id) &&
          (t.projectId === project.id || t.projectIds?.includes(project.id)),
      );
      const legacyId =
        Object.keys(this.projectIds).find((id) => this.projectIds[id] === project.id) ?? project.id;
      projectThreadIds[project.id] = (
        manual('projectSortMode')
          ? manualOrdered(children, strings(record(orders[legacyId]).threadIds))
          : children
      ).map((t) => t.id);
    }
    const mode = preferences.mode ?? 'project';
    const showProjectsInRecents = this.atoms['sidebar-show-projects-in-recents'] !== false;
    const recent = sorted.filter(
      (t) =>
        !pinnedThreads.has(t.id) &&
        !(t.projectIds ?? (t.projectId ? [t.projectId] : [])).some(
          (id) =>
            projectIds.has(id) &&
            ((mode === 'project' && !showProjectsInRecents) || pinnedProjects.has(id)),
        ),
    );
    const recentOrder = strings(this.atoms['unified-sidebar-chat-order-v1'])
      .filter((id) => id.startsWith('codex:thread:local:'))
      .map((id) => id.slice('codex:thread:local:'.length));
    const pinned: WorkspaceSidebar['pinned'] = [
      ...displayedPins.map((id) => ({ kind: 'thread' as const, id })),
      ...pinnedProjectIds.map((id) => ({ kind: 'project' as const, id })),
    ];
    const pinOrder = strings(this.atoms['unified-sidebar-pinned-order-v1']).flatMap((key) => {
      if (key.startsWith('codex:project:'))
        return ['project:' + this.nativeProjectId(key.slice('codex:project:'.length))];
      if (key.startsWith('codex:thread:local:'))
        return ['thread:' + key.slice('codex:thread:local:'.length)];
      return [];
    });
    const ranked = ordered(
      pinned.map((item) => ({ ...item, id: item.kind + ':' + item.id })),
      manualPins ? pinOrder : [],
    );
    // App-server thread pin order owns the thread slots, including migrated pins.
    let nextPin = 0;
    return {
      version: 1,
      pinned: ranked.map((item) =>
        item.kind === 'thread'
          ? { kind: 'thread', id: displayedPins[nextPin++]! }
          : { kind: 'project', id: item.id.slice('project:'.length) },
      ),
      recentThreadIds: (manual('chatSortMode') ? manualOrdered(recent, recentOrder) : recent).map(
        (t) => t.id,
      ),
      projectIds:
        mode === 'project'
          ? projects.filter((p) => !pinnedProjects.has(p.id)).map((p) => p.id)
          : [],
      projectThreadIds,
    };
  }

  /** Undefined means no desktop override; null is an explicit unassigned chat. */
  assignment(threadId: string): string | null | undefined {
    const membershipHost = record(this.state['thread-project-membership-host-ids'])[threadId];
    if (membershipHost !== undefined && membershipHost !== 'local') return undefined;
    const projectless = this.state['projectless-thread-ids'];
    if (Array.isArray(projectless) && projectless.includes(threadId)) return null;
    const assignments = record(this.state['thread-project-assignments']);
    if (!Object.hasOwn(assignments, threadId)) return undefined;
    const assignment = record(assignments[threadId]);
    if (assignment.projectKind !== 'local' || typeof assignment.projectId !== 'string') return null;
    const mapped = this.projectIds[assignment.projectId];
    return typeof mapped === 'string' ? mapped : assignment.projectId;
  }

  /** Desktop order, scoped to this computer's app-server metadata namespace. */
  pinnedThreadIds(): string[] {
    const byHost = record(this.state['app-server-migrated-pinned-thread-ids-by-host']);
    const local = `local:${dirname(this.path)}`;
    const value = Object.hasOwn(byHost, local) ? byHost[local] : this.state['pinned-thread-ids'];
    return Array.isArray(value)
      ? [...new Set(value.filter((id): id is string => typeof id === 'string' && id.length > 0))]
      : [];
  }
}

const strings = (value: unknown): string[] =>
  Array.isArray(value)
    ? [...new Set(value.filter((id): id is string => typeof id === 'string'))]
    : [];
function ordered<T extends { id: string }>(items: T[], ids: string[]): T[] {
  const ranks = new Map(ids.map((id, rank) => [id, rank]));
  return items
    .slice()
    .sort((a, b) => (ranks.get(a.id) ?? ids.length) - (ranks.get(b.id) ?? ids.length));
}
function manualOrdered<T extends { id: string }>(items: T[], ids: string[]): T[] {
  const saved = new Set(ids);
  return [
    ...items.filter((item) => !saved.has(item.id)),
    ...ordered(
      items.filter((item) => saved.has(item.id)),
      ids,
    ),
  ];
}
