import { createHash } from 'node:crypto';
import { machineRuntimeTargetKey, type RuntimeTarget } from '@codex-manage/protocol';
import type { ReadProject } from './history-reader.js';
import type { ProjectCreationRecord } from './desktop-project-creation.js';

type Visibility = 'waiting-for-desktop' | 'desktop-observed' | 'removed' | 'unknown';
type Entry = Record<string, unknown> & { state: Visibility };
const object = (value: unknown): Record<string, unknown> => {
  if (!value || typeof value !== 'object' || Array.isArray(value))
    throw new Error('Invalid project visibility provenance');
  return value as Record<string, unknown>;
};

/** Identity mappings alone never prove that a project was adopted by the desktop. */
export class DesktopProjectVisibility {
  constructor(
    private readonly load: () => unknown,
    private readonly save: (value: unknown) => void,
  ) {}

  private read() {
    const value = this.load();
    const state = value === undefined ? { version: 1, entries: {} } : object(value);
    if (
      typeof state.version !== 'number' ||
      !Number.isSafeInteger(state.version) ||
      state.version < 1
    )
      throw new Error('Invalid project visibility version');
    return { ...state, entries: { ...object(state.entries) } };
  }

  private key(target: RuntimeTarget, profile: string, id: string): string {
    return createHash('sha256')
      .update(JSON.stringify([machineRuntimeTargetKey(target), profile, id]))
      .digest('hex');
  }

  created(target: RuntimeTarget, profile: string, id: string): void {
    const state = this.read();
    const key = this.key(target, profile, id);
    if (Object.hasOwn(state.entries, key)) return;
    state.entries[key] = { state: 'waiting-for-desktop' };
    this.save(state);
  }

  observe(
    target: RuntimeTarget,
    profile: string,
    fingerprint: string,
    native: ReadProject[],
    visible: ReadProject[],
    pendingDeletionIds: ReadonlySet<string>,
    creations: readonly ProjectCreationRecord[],
  ): ReadProject[] {
    const state = this.read();
    const prior = JSON.stringify(state);
    const current = new Set(visible.map((project) => project.id));
    const result = visible.slice();
    const createdIds = new Set(
      creations.flatMap((record) => {
        if (record.state !== 'completed' || !record.projectId) return [];
        try {
          return machineRuntimeTargetKey(record.target) === machineRuntimeTargetKey(target)
            ? [record.projectId]
            : [];
        } catch {
          // An unidentifiable old receipt never becomes today's target.
          return [];
        }
      }),
    );
    for (const project of native) {
      if (!current.has(project.id) && !createdIds.has(project.id)) continue;
      const key = this.key(target, profile, project.id);
      const raw = state.entries[key];
      const entry = raw === undefined ? { state: 'unknown' as Visibility } : object(raw);
      let next: Entry;
      if (current.has(project.id)) {
        next =
          entry.state === 'desktop-observed' && typeof entry.adoptedFingerprint === 'string'
            ? (entry as Entry)
            : { ...entry, state: 'desktop-observed', adoptedFingerprint: fingerprint };
      } else if (
        pendingDeletionIds.has(project.id) ||
        (entry.state === 'desktop-observed' && typeof entry.adoptedFingerprint === 'string') ||
        entry.state === 'removed'
      ) {
        next =
          entry.state === 'removed'
            ? (entry as Entry)
            : {
                ...entry,
                state: 'removed',
                removedFingerprint: fingerprint,
                removalEvidence: pendingDeletionIds.has(project.id)
                  ? 'desktop-pending-deletion'
                  : 'complete-desktop-catalog',
              };
      } else {
        next = {
          ...entry,
          state: entry.state === 'waiting-for-desktop' ? 'waiting-for-desktop' : 'unknown',
        };
      }
      state.entries[key] = next;
      if (!current.has(project.id) && next.state !== 'removed')
        result.push({
          ...project,
          desktopVisibility: next.state === 'waiting-for-desktop' ? 'pending' : 'unknown',
        });
    }
    // Publish an adopted/removed result only after its independent evidence is durable.
    if (JSON.stringify(state) !== prior) this.save(state);
    return result;
  }
}
