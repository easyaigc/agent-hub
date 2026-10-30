import test from 'node:test';
import assert from 'node:assert/strict';
import { realpathSync, readFileSync, rmSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import {
  closeWindowsPrivateStateBackend,
  writePrivateFileSync,
  makePrivateDirectorySync,
} from '../packages/codex-adapter/dist/desktop-platform.js';
import { verifyLegacySessionAttachments } from './fixtures/runtime-private-attachment-layout.mjs';

test('Session restores runtime.json while arbitrary persistence state stays separate; registered legacy attachment resolves and downloads', async () => {
  const directory = join(realpathSync(tmpdir()), 'private-attachment-layout-' + randomUUID());
  makePrivateDirectorySync(directory);
  try {
    writePrivateFileSync(
      join(directory, 'runtime-state.json'),
      JSON.stringify({ hostId: 'decoy-persistence', threads: [] }),
    );
    const result = await verifyLegacySessionAttachments(directory);
    assert.equal(result.sessionStateFilename, 'runtime.json');
    assert.equal(result.arbitraryPersistenceStateUnchanged, true);
    assert.equal(result.legacyAttachmentProductResolveAndDownload, true);
    assert.equal(result.legacyAttachmentDownloadBytes, 16 * 1024 * 1024);
    assert.equal(
      JSON.parse(readFileSync(join(directory, 'runtime.json'), 'utf8')).hostId,
      'fixture-only',
    );
  } finally {
    closeWindowsPrivateStateBackend();
    rmSync(directory, { recursive: true, force: true });
  }
});
