import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { DesktopSidebar } from '../packages/codex-adapter/dist/desktop-sidebar.js';

test('desktop display catalog, recency, optional project recents and pin slots', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'sidebar-projection-'));
  try {
    const path = join(dir, '.codex-global-state.json');
    const state = {
      'local-projects': { legacy: { name: 'Visible project' }, second: { name: 'Second' } },
      'app-server-project-id-by-legacy-project-id-by-host': {
        ['local:' + dir]: { legacy: 'p', second: 'q' },
        'local:another-profile': { legacy: 'unrelated' },
      },
      'electron-persisted-atom-state': {
        'unified-sidebar-project-order-v1': ['codex:project:second', 'codex:project:legacy'],
        'flat-project-sidebar-preferences-v1': { mode: 'project' },
      },
    };
    const projects = ['p', 'q', 'deleted'].map((id) => ({ id, hostId: 'h', name: id, roots: [] }));
    const threads = [
      { id: 'background-update', projectId: 'p', recencyAt: 1, updatedAt: 999 },
      { id: 'recent-input', projectId: 'p', recencyAt: 20, updatedAt: 21 },
      { id: 'unassigned', projectId: null, recencyAt: 10 },
      { id: 'removed-project', projectId: 'deleted', recencyAt: 5 },
      { id: 'pin-b', projectId: 'p', recencyAt: 4 },
      { id: 'pin-a', projectId: 'q', recencyAt: 3 },
      { id: 'archived', projectId: 'p', recencyAt: 100, archived: true },
    ].map((t) => ({ ...t, hostId: 'h', title: t.id, state: 'unknown' }));
    const read = async () => {
      await writeFile(path, JSON.stringify(state));
      const sidebar = new DesktopSidebar(path);
      const visible = sidebar.projects(projects);
      return {
        visible,
        display: sidebar.presentation(
          visible,
          threads,
          ['pin-b', 'missing', 'archived', 'pin-a'],
          threads,
        ),
      };
    };
    let { visible, display } = await read();
    assert.deepEqual(
      visible.map((p) => [p.id, p.name]),
      [
        ['q', 'Second'],
        ['p', 'Visible project'],
      ],
    );
    assert.deepEqual(display.recentThreadIds, [
      'recent-input',
      'unassigned',
      'removed-project',
      'background-update',
    ]);
    assert.deepEqual(display.projectThreadIds.p, ['recent-input', 'background-update']);
    assert.deepEqual(display.pinned, [
      { kind: 'thread', id: 'pin-b' },
      { kind: 'thread', id: 'pin-a' },
    ]);
    state['electron-persisted-atom-state']['sidebar-show-projects-in-recents'] = false;
    ({ display } = await read());
    assert.deepEqual(display.recentThreadIds, ['unassigned', 'removed-project']);
    state['pinned-project-ids'] = ['legacy'];
    state['electron-persisted-atom-state']['unified-sidebar-pinned-order-v1'] = [
      'codex:thread:local:pin-a',
      'codex:project:legacy',
      'codex:thread:local:pin-b',
    ];
    ({ display } = await read());
    assert.deepEqual(display.pinned, [
      { kind: 'thread', id: 'pin-b' },
      { kind: 'project', id: 'p' },
      { kind: 'thread', id: 'pin-a' },
    ]);
    assert.deepEqual(display.projectIds, ['q']);
    state['electron-persisted-atom-state']['pinned-sidebar-sort-mode-v1'] = 'updated_at';
    threads.find((t) => t.id === 'pin-a').recencyAt = 50;
    ({ display } = await read());
    assert.deepEqual(display.pinned, [
      { kind: 'thread', id: 'pin-a' },
      { kind: 'thread', id: 'pin-b' },
      { kind: 'project', id: 'p' },
    ]);
    state['electron-persisted-atom-state']['flat-project-sidebar-preferences-v1'] = {
      mode: 'list',
    };
    ({ display } = await read());
    assert.deepEqual(display.projectIds, []);
    assert.deepEqual(display.recentThreadIds, ['unassigned', 'removed-project']);
  } finally {
    await rm(dir, { recursive: true });
  }
});

test('manual sorting refreshes independently, and legacy manual settings normalize to recency', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'sidebar-manual-'));
  try {
    const path = join(dir, '.codex-global-state.json');
    const state = {
      'sidebar-project-thread-orders': { p: { threadIds: ['old', 'new'] } },
      'electron-persisted-atom-state': {
        'flat-project-sidebar-preferences-v1': {
          mode: 'project',
          manualSortVersion: 1,
          chatSortMode: 'manual',
          projectSortMode: 'manual',
        },
        'unified-sidebar-chat-order-v1': ['codex:thread:local:old', 'codex:thread:local:new'],
      },
    };
    const threads = [
      { id: 'old', recencyAt: 1 },
      { id: 'new', recencyAt: 3 },
      { id: 'unsorted-new-input', recencyAt: 2 },
    ].map((t) => ({ ...t, hostId: 'h', projectId: 'p', title: t.id, state: 'unknown' }));
    const read = async () => {
      await writeFile(path, JSON.stringify(state));
      return new DesktopSidebar(path).presentation(
        [{ id: 'p', hostId: 'h', name: 'p', roots: [] }],
        threads,
        [],
        threads,
      );
    };
    let display = await read();
    assert.deepEqual(display.recentThreadIds, ['unsorted-new-input', 'old', 'new']);
    assert.deepEqual(display.projectThreadIds.p, ['unsorted-new-input', 'old', 'new']);
    state['electron-persisted-atom-state'][
      'flat-project-sidebar-preferences-v1'
    ].manualSortVersion = 0;
    display = await read();
    assert.deepEqual(display.recentThreadIds, ['new', 'unsorted-new-input', 'old']);
    assert.deepEqual(display.projectThreadIds.p, ['new', 'unsorted-new-input', 'old']);
  } finally {
    await rm(dir, { recursive: true });
  }
});

test('shared desktop roots have one owner, with bounded directory and explicit workspace hints', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'sidebar-roots-'));
  try {
    const path = join(dir, '.codex-global-state.json');
    await writeFile(
      path,
      JSON.stringify({
        'local-projects': {
          multi: { rootPaths: ['/work', '/other'], createdAt: 1 },
          single: { rootPaths: ['/work'], createdAt: 2 },
          nested: { rootPaths: ['/work/nested'], createdAt: 3 },
        },
        'thread-workspace-root-hints': { hinted: '/other' },
      }),
    );
    const sidebar = new DesktopSidebar(path);
    const projects = [
      { id: 'multi', hostId: 'h', name: 'multi', roots: ['/work', '/other'] },
      { id: 'single', hostId: 'h', name: 'single', roots: ['/work'] },
      { id: 'nested', hostId: 'h', name: 'nested', roots: ['/work/nested'] },
    ];
    assert.equal(sidebar.projectForDirectory('plain', '/work', projects), 'single');
    assert.equal(sidebar.projectForDirectory('child', '/work/child', projects), 'single');
    assert.equal(
      sidebar.projectForDirectory('nested-child', '/work/nested/child', projects),
      'nested',
    );
    assert.equal(sidebar.projectForDirectory('unrelated', '/work-elsewhere', projects), null);
    assert.equal(sidebar.projectForDirectory('hinted', '/unrelated', projects), 'multi');
    assert.equal(sidebar.projectForDirectory('relative', 'work', projects), null);
  } finally {
    await rm(dir, { recursive: true });
  }
});
