import test from 'node:test';
import assert from 'node:assert/strict';
import { chmod, mkdir, mkdtemp, readFile, realpath, rm, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { save } from '../skills/codex-host-connect/scripts/task-hub-core.mjs';
import {
  taskHubAcceptanceState,
  taskHubAcceptanceOutput,
  taskHubAcceptanceReport,
} from './task-hub-acceptance-state.mjs';

test('isolated checkout selects the explicit existing private DEV state without copying it', async () => {
  const root = await realpath(await mkdtemp(join(tmpdir(), 'task-hub-acceptance-state-')));
  try {
    const checkout = join(root, 'isolated');
    const installed = join(root, 'primary', '.local/task-management/service');
    await mkdir(checkout);
    await mkdir(installed, { recursive: true, mode: 0o700 });
    await assert.rejects(taskHubAcceptanceState(checkout));
    assert.equal(await taskHubAcceptanceState(checkout, installed), installed);
    const originalDefault = join(checkout, '.local/task-management/service');
    await mkdir(originalDefault, { recursive: true, mode: 0o700 });
    assert.equal(await taskHubAcceptanceState(checkout), originalDefault);
  } finally {
    await rm(root, { recursive: true });
  }
});

test('outside evidence uses unique private canonical directories, including a child native run', async () => {
  const root = await realpath(await mkdtemp(join(tmpdir(), 'task-hub-evidence-')));
  try {
    const checkout = join(root, 'checkout');
    const evidence = join(root, 'evidence');
    await mkdir(evidence, { mode: 0o700 });
    const parent = await taskHubAcceptanceOutput(checkout, evidence, 'network-acceptance');
    const child = await taskHubAcceptanceOutput(checkout, parent, 'acceptance');
    assert.ok(parent.startsWith(evidence + '/network-acceptance-'));
    assert.ok(child.startsWith(parent + '/acceptance-'));
    assert.equal(await taskHubAcceptanceState(checkout, child), child);
    await save(join(parent, 'report.json'), { child });
    assert.deepEqual(JSON.parse(await readFile(join(parent, 'report.json'), 'utf8')), { child });
    await symlink(evidence, join(root, 'linked-output'));
    await assert.rejects(
      taskHubAcceptanceOutput(checkout, join(root, 'linked-output'), 'acceptance'),
      /canonical/,
    );
    await assert.rejects(taskHubAcceptanceOutput(checkout, evidence, '../unsafe'));
  } finally {
    await rm(root, { recursive: true });
  }
});

test('state selection rejects readable directories, links and linked ancestors', async () => {
  const root = await realpath(await mkdtemp(join(tmpdir(), 'task-hub-acceptance-state-')));
  try {
    const state = join(root, 'state');
    await mkdir(state, { mode: 0o700 });
    await chmod(state, 0o755);
    await assert.rejects(taskHubAcceptanceState(root, state), /private/);
    await chmod(state, 0o700);
    await symlink(state, join(root, 'state-link'));
    await assert.rejects(taskHubAcceptanceState(root, join(root, 'state-link')), /canonical/);
    await symlink(root, join(root, 'parent-link'));
    await assert.rejects(
      taskHubAcceptanceState(root, join(root, 'parent-link', 'state')),
      /canonical/,
    );
  } finally {
    await rm(root, { recursive: true });
  }
});

test('parent reads its native report outside the checkout and rejects another run or linked report', async () => {
  const root = await realpath(await mkdtemp(join(tmpdir(), 'task-hub-child-report-')));
  try {
    const parent = await taskHubAcceptanceOutput(root, root, 'network-acceptance');
    const child = await taskHubAcceptanceOutput(root, parent, 'acceptance');
    const report = join(child, 'report.json');
    await save(report, { phase: 'passed', originalOperation: 'stable-id' });
    const verified = await taskHubAcceptanceReport(parent, report);
    assert.deepEqual(JSON.parse(await readFile(verified, 'utf8')), {
      phase: 'passed',
      originalOperation: 'stable-id',
    });
    const other = await taskHubAcceptanceOutput(root, root, 'network-acceptance');
    await assert.rejects(taskHubAcceptanceReport(other, report), /belong to this network run/);
    await assert.rejects(
      taskHubAcceptanceReport(parent, join(parent, 'report.json')),
      /belong to this network run/,
    );
    await chmod(report, 0o644);
    await assert.rejects(taskHubAcceptanceReport(parent, report), /private/);
    await chmod(report, 0o600);
    const linkedChild = await taskHubAcceptanceOutput(root, parent, 'acceptance');
    await symlink(report, join(linkedChild, 'report.json'));
    await assert.rejects(
      taskHubAcceptanceReport(parent, join(linkedChild, 'report.json')),
      /canonical/,
    );
  } finally {
    await rm(root, { recursive: true });
  }
});
