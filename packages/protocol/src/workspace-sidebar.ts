/** Ordered display projection for one workspace. IDs never authorize execution. */
export interface WorkspaceSidebar {
  version: 1;
  pinned: { kind: 'thread' | 'project'; id: string }[];
  recentThreadIds: string[];
  projectIds: string[];
  /** Confirmed phone creations not present in the current desktop display catalog. */
  pendingProjectIds?: string[];
  projectThreadIds: Record<string, string[]>;
}
