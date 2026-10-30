import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, lstatSync, readFileSync, realpathSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { RuntimeSession } from '../../packages/codex-adapter/dist/runtime-session.js';
import {
  assertPrivateFile,
  makePrivateDirectorySync,
  writePrivateFileSync,
} from '../../packages/codex-adapter/dist/desktop-platform.js';

const digest = (bytes) => createHash('sha256').update(bytes).digest('hex');
const fileStamp = (path) => {
  const s = lstatSync(path, { bigint: true });
  return [s.dev, s.ino, s.size, s.mtimeNs, s.ctimeNs, s.birthtimeNs].join(':');
};

// Shared by the normal Main Windows acceptance and a platform-common regression.
// Only this disposable fixture is written; no original desktop is connected.
export async function verifyLegacySessionAttachments(directory, inspectSecurity) {
  directory = realpathSync(directory);
  const sessionStatePath = join(directory, 'runtime.json');
  // An unrelated RuntimePersistence test may use this arbitrary filename. It
  // must never be mistaken for the production RuntimeSession restoration path.
  const persistencePath = join(directory, 'runtime-state.json');
  const persistenceBefore = existsSync(persistencePath)
    ? [fileStamp(persistencePath), digest(readFileSync(persistencePath))]
    : undefined;
  const legacyDirectory = join(directory, 'attachments');
  makePrivateDirectorySync(legacyDirectory); // User-owned root, including elevated tokens.
  const legacyFile = join(legacyDirectory, 'old-upload.bin');
  const bytes = Buffer.alloc(16 * 1024 * 1024, 0x41);
  // Deliberately ordinary old Node creation: no private create/owner/ACL repair.
  // Under the source's elevated token this leaf defaults to Administrators,
  // inherits the trusted parent ACL, and is not DACL-protected.
  writeFileSync(legacyFile, bytes, { flag: 'wx', mode: 0o600 });
  const oldSecurity = inspectSecurity?.(legacyFile);
  const directorySecurity = inspectSecurity?.(legacyDirectory);
  const fileBefore = fileStamp(legacyFile);
  const directoryBefore = fileStamp(legacyDirectory);
  if (oldSecurity) {
    assert.equal(directorySecurity.ownerIsUser, true);
    assert.equal(oldSecurity.ownerMatchesDefaultOwner, true);
    assert.equal(oldSecurity.protected, false);
    assert.equal(oldSecurity.inheritedAllowOnly, true);
    assert.equal(oldSecurity.trustedAllowOnly, true);
    if (oldSecurity.defaultOwnerIsAdministrators) {
      assert.equal(oldSecurity.ownerIsAdministrators, true);
      assert.equal(oldSecurity.ownerIsUser, false);
    }
  }
  const hostId = 'fixture-only';
  const thread = { id: 'fixture-thread', hostId, projectId: null, state: 'idle', messages: [] };
  const other = { ...thread, id: 'other-thread', messages: [] };
  const attachment = {
    id: 'old-attachment',
    hostId,
    threadId: thread.id,
    path: legacyFile,
    name: 'old-upload.bin',
    mediaType: 'application/octet-stream',
    sizeBytes: bytes.length,
    sha256: digest(bytes),
  };
  writePrivateFileSync(
    sessionStatePath,
    JSON.stringify({
      hostId,
      projects: [],
      threads: [thread, other],
      attachments: [attachment],
      receipts: [],
      creations: {},
    }),
    { flag: 'wx' },
  );
  const options = {
    directory,
    root: directory,
    name: 'Synthetic private state',
    mode: 'desktop-runtime',
  };
  const session = new RuntimeSession(options);
  try {
    assert.equal(session.hostId, hostId);
    assert.deepEqual(session.state.attachments[0], attachment);
    // Actual product authorization, fresh hashing and byte download, not fs-only
    // evidence. Reads need no execution readiness or fake desktop owner.
    const resolved = await session.resolveAttachment(thread.id, attachment.id);
    assert.equal(resolved.sourceAttachmentId, attachment.id);
    assert.equal(resolved.attachment.sha256, attachment.sha256);
    assert.equal(resolved.attachment.sizeBytes, attachment.sizeBytes);
    const downloaded = await session.download(thread.id, attachment.id);
    assert.equal(downloaded.file.id, attachment.id);
    assert.equal(downloaded.data.byteLength, attachment.sizeBytes);
    assert.equal(digest(downloaded.data), attachment.sha256);
    await assert.rejects(session.download(other.id, attachment.id), (e) => e.status === 404);
    await assert.rejects(
      session.resolveAttachment(other.id, attachment.id),
      (e) => e.status === 404,
    );
    await assert.rejects(session.download(thread.id, 'unregistered-file'), (e) => e.status === 404);
    if (oldSecurity?.ownerIsAdministrators) {
      // A registered ordinary attachment does not become a private state file.
      // The original strict User-only Native boundary must still reject it.
      assert.throws(() => assertPrivateFile(legacyFile));
      assertPrivateFile(sessionStatePath);
    }
    // Synthetic upload readiness only. No IPC/reader/model/approval is started.
    session.requireReady = () => {};
    const uploadBytes = Buffer.from('synthetic-new-upload');
    const uploaded = session.upload(
      thread.id,
      'new.txt',
      'text/plain',
      uploadBytes,
      digest(uploadBytes),
    );
    const stored = session.state.attachments.find((entry) => entry.id === uploaded.id);
    const expectedDirectory = join(
      directory,
      process.platform === 'win32' ? 'attachments-v2' : 'attachments',
    );
    assert.equal(resolve(stored.path, '..'), expectedDirectory);
    assert.equal(digest(readFileSync(stored.path)), digest(uploadBytes));
    assertPrivateFile(stored.path);
    const image = Buffer.from('synthetic-inline-image');
    const message = {
      id: 'fixture-image',
      text: '',
      kind: 'imageGeneration',
      role: 'assistant',
      attachments: [],
    };
    await session.projectMessageAttachments(thread, message, [], {
      result: image.toString('base64'),
    });
    const storedImage = session.state.attachments.find(
      (entry) => entry.id === message.attachments[0]?.id,
    );
    assert(storedImage);
    assert.equal(resolve(storedImage.path, '..'), expectedDirectory);
    assert.equal(digest(readFileSync(storedImage.path)), digest(image));
    assertPrivateFile(storedImage.path);
    if (inspectSecurity) {
      for (const path of [stored.path, storedImage.path]) {
        const security = inspectSecurity(path);
        assert.equal(security.ownerIsUser, true);
        assert.equal(security.protected, true);
      }
    }
    assert.deepEqual(
      session.state.attachments.find((entry) => entry.id === attachment.id),
      attachment,
    );
    assert.equal(fileStamp(legacyFile), fileBefore);
    assert.equal(digest(readFileSync(legacyFile)), attachment.sha256);
    if (inspectSecurity) {
      assert.deepEqual(inspectSecurity(legacyFile), oldSecurity);
      assert.deepEqual(inspectSecurity(legacyDirectory), directorySecurity);
      assert.equal(fileStamp(legacyDirectory), directoryBefore);
    }
  } finally {
    session.close();
  }
  const restored = JSON.parse(readFileSync(sessionStatePath, 'utf8'));
  assert.deepEqual(
    restored.attachments.find((entry) => entry.id === attachment.id),
    attachment,
  );
  if (persistenceBefore)
    assert.deepEqual(
      [fileStamp(persistencePath), digest(readFileSync(persistencePath))],
      persistenceBefore,
    );
  return {
    legacyAttachmentProductResolveAndDownload: true,
    legacyAttachmentDownloadBytes: bytes.length,
    legacyAttachmentBytesOwnerAndReferencesUnchanged: true,
    privateStateBoundaryStillStrict: oldSecurity?.ownerIsAdministrators ? true : undefined,
    newUploadAndInlineImageUserOwnerAndProtected: inspectSecurity ? true : undefined,
    legacyAttachmentRootOwnerIsUser: directorySecurity?.ownerIsUser,
    legacyAdminOwnedLeafExercised: oldSecurity?.ownerIsAdministrators ?? false,
    legacyInheritedLeafDaclObserved: oldSecurity
      ? !oldSecurity.protected && oldSecurity.inheritedAllowOnly
      : undefined,
    sessionStateFilename: 'runtime.json',
    arbitraryPersistenceStateUnchanged: !!persistenceBefore,
  };
}
