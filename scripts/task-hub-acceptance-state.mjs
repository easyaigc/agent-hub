import assert from 'node:assert/strict';
import { lstat, mkdir, realpath } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { dirname, join, relative, resolve } from 'node:path';

// An explicit state selects an existing installation; the source checkout is not
// its owner. Registration, live identity, bundle and certificate checks still run
// before the caller creates a fault proxy or submits any native business input.
export async function taskHubAcceptanceState(repository, selected) {
  const state = resolve(selected ?? join(repository, '.local/task-management/service'));
  assert.equal(await realpath(state), state, 'DEV state must use its canonical path');
  const directory = await lstat(state);
  assert.ok(directory.isDirectory() && !directory.isSymbolicLink());
  assert.equal(directory.mode & 0o077, 0, 'DEV state must be private');
  if (typeof process.getuid === 'function')
    assert.equal(directory.uid, process.getuid(), 'DEV state must belong to this user');
  return state;
}

export async function taskHubAcceptanceOutput(repository, selected, kind) {
  assert.ok(['acceptance', 'network-acceptance'].includes(kind));
  const base = resolve(selected ?? join(repository, '.local/task-management'));
  if (selected === undefined) await mkdir(base, { recursive: true, mode: 0o700 });
  // Evidence may be outside the source tree, but never reached through a link.
  await taskHubAcceptanceState(repository, base);
  const root = join(base, kind + '-' + randomUUID());
  await mkdir(root, { mode: 0o700 });
  return root;
}

// A network run owns exactly one nested native run. Verify its actual private
// report under that parent, even when the evidence lives outside the checkout.
export async function taskHubAcceptanceReport(parent, selected) {
  const root = await taskHubAcceptanceState(parent, parent);
  const report = resolve(selected);
  assert.match(
    relative(root, report),
    /^acceptance-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\/report\.json$/,
    'Native report must belong to this network run',
  );
  await taskHubAcceptanceState(root, dirname(report));
  assert.equal(await realpath(report), report, 'Native report must use its canonical path');
  const file = await lstat(report);
  assert.ok(file.isFile() && !file.isSymbolicLink());
  assert.equal(file.mode & 0o077, 0, 'Native report must be private');
  if (typeof process.getuid === 'function')
    assert.equal(file.uid, process.getuid(), 'Native report must belong to this user');
  return report;
}
