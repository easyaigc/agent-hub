/** Ordered display projection for one workspace. IDs never authorize execution. */
export interface WorkspaceSidebar {
  version: 1;
  pinned: { kind: 'thread' | 'project'; id: string }[];
  recentThreadIds: string[];
  projectIds: string[];
  projectThreadIds: Record<string, string[]>;
}
