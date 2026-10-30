import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { renameSync } from 'node:fs';
import {
  chmod,
  mkdtemp,
  readFile,
  realpath,
  rename,
  rm,
  stat,
  utimes,
  writeFile,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { setTimeout as delay } from 'node:timers/promises';
import { AttachmentFiles } from '../packages/codex-adapter/dist/attachment-files.js';

const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');
async function fixture(t) {
  const root = await realpath(await mkdtemp(join(tmpdir(), 'attachment-metadata-')));
  t.after(() => rm(root, { recursive: true, force: true }));
  const path = join(root, 'report.txt');
  await writeFile(path, 'original', { mode: 0o600 });
  // An exactly representable mtime also covers a writer restoring its old timestamp.
  await utimes(path, 1_700_000_000, 1_700_000_000);
  const retries = [];
  const files = new AttachmentFiles(8, (event) => retries.push(event));
  const before = await stat(path, { bigint: true });
  return { root, path, files, retries, before };
}
function injectCheck(files, mutate) {
  const checked = files.checked.bind(files);
  let calls = 0;
  files.checked = async (...args) => {
    await mutate(++calls);
    return checked(...args);
  };
  return () => calls;
}
function contentStat(value) {
  return [value.dev, value.ino, value.size, value.mtimeNs, value.birthtimeNs];
}
async function mutateWithCtimeChange(path, mutate) {
  const before = await stat(path, { bigint: true });
  // Linux can coalesce chmod or a same-size rewrite into the same clock tick.
  // Inject an observed metadata change before asserting the production retry.
  for (let attempt = 0; attempt < 100; attempt += 1) {
    await mutate();
    const after = await stat(path, { bigint: true });
    assert.deepEqual(contentStat(after), contentStat(before));
    if (after.ctimeNs !== before.ctimeNs) return;
    await delay(2);
  }
  assert.fail('file mutation fixture did not produce a distinct ctime within 100 attempts');
}
function chmodWithCtimeChange(path, mode) {
  return mutateWithCtimeChange(path, () => chmod(path, mode));
}

test('ctime-only change during download re-reads a stable snapshot without changing bytes', async (t) => {
  const { root, path, files, retries, before } = await fixture(t);
  injectCheck(files, async (call) => {
    if (call === 2) await chmodWithCtimeChange(path, 0o400);
  });
  const bytes = await files.read(path, [root], sha('original'));
  const after = await stat(path, { bigint: true });
  assert.equal(bytes.toString(), 'original');
  assert.deepEqual(contentStat(after), contentStat(before));
  assert.notEqual(after.ctimeNs, before.ctimeNs);
  assert.equal(retries.length, 1);
  assert.equal(retries[0].operation, 'read');
  assert.equal(retries[0].change.kind, 'metadata-only');
  assert.equal(retries[0].change.stage, 'path-stat');
});

test('a ctime change caused between path stat and opening is retried before reading', async (t) => {
  const { root, path, files, retries } = await fixture(t);
  const checked = files.checked.bind(files);
  let first = true;
  files.checked = async (...args) => {
    const result = await checked(...args);
    if (first) {
      first = false;
      await chmodWithCtimeChange(path, 0o400);
    }
    return result;
  };
  assert.equal((await files.read(path, [root], sha('original'))).toString(), 'original');
  assert.equal(retries.length, 1);
  assert.equal(retries[0].change.stage, 'open-stat');
});

test('current-version inspection retries ctime drift and retains ctime cache invalidation', async (t) => {
  const { root, path, files, retries } = await fixture(t);
  injectCheck(files, async (call) => {
    if (call === 3) await chmodWithCtimeChange(path, 0o400);
  });
  const metadata = await files.inspect(path, [root]);
  assert.equal(metadata.sha256, sha('original'));
  assert.equal(retries.length, 1);
  assert.equal(retries[0].operation, 'inspect');
  assert.deepEqual(await files.inspect(path, [root]), metadata);
  const beforeRewrite = await stat(path, { bigint: true });
  await mutateWithCtimeChange(path, async () => {
    await chmod(path, 0o600);
    await writeFile(path, 'modified');
    await utimes(path, 1_700_000_000, 1_700_000_000);
  });
  const afterRewrite = await stat(path, { bigint: true });
  assert.notEqual(afterRewrite.ctimeNs, beforeRewrite.ctimeNs);
  assert.equal((await files.inspect(path, [root])).sha256, sha('modified'));
  await assert.rejects(files.read(path, [root], metadata.sha256), { code: 'FILE_CHANGED' });
});

test('continuously changing metadata is bounded and has a distinct diagnostic', async (t) => {
  const { root, path, files, retries } = await fixture(t);
  let writable = false;
  const calls = injectCheck(files, async (call) => {
    if (call % 2 === 0) {
      await chmodWithCtimeChange(path, writable ? 0o600 : 0o400);
      writable = !writable;
    }
  });
  await assert.rejects(files.read(path, [root], sha('original')), (error) => {
    assert.equal(error.status, 409);
    assert.equal(error.code, 'FILE_METADATA_UNSTABLE');
    assert.equal(error.fileChange.kind, 'metadata-only');
    return true;
  });
  assert.equal(calls(), 6, 'at most three complete scan attempts');
  assert.equal(retries.length, 2);
  assert.equal((await readFile(path)).toString(), 'original');
});

test('same-size rewrite with restored mtime still rejects the original content digest', async (t) => {
  const { root, path, files, before } = await fixture(t);
  injectCheck(files, async (call) => {
    if (call === 2) {
      await mutateWithCtimeChange(path, async () => {
        await writeFile(path, 'modified');
        await utimes(path, 1_700_000_000, 1_700_000_000);
      });
    }
  });
  await assert.rejects(files.read(path, [root], sha('original')), { code: 'FILE_CHANGED' });
  assert.deepEqual(contentStat(await stat(path, { bigint: true })), contentStat(before));
  assert.equal((await files.inspect(path, [root])).sha256, sha('modified'));
});

test('replacing an inode during download is not retried as metadata drift', async (t) => {
  const { root, path, files, retries } = await fixture(t);
  await writeFile(path + '.new', 'original');
  await utimes(path + '.new', 1_700_000_000, 1_700_000_000);
  injectCheck(files, async (call) => {
    if (call === 2) await rename(path + '.new', path);
  });
  await assert.rejects(files.read(path, [root], sha('original')), { code: 'FILE_CHANGED' });
  assert.equal(retries.length, 0);
});

test('a retry cannot follow a replacement file even when its bytes are identical', async (t) => {
  const { root, path } = await fixture(t);
  await writeFile(path + '.new', 'original');
  const files = new AttachmentFiles(8, () => renameSync(path + '.new', path));
  injectCheck(files, async (call) => {
    if (call === 2) await chmodWithCtimeChange(path, 0o400);
  });
  await assert.rejects(files.read(path, [root], sha('original')), (error) => {
    assert.equal(error.code, 'FILE_CHANGED');
    assert.equal(error.fileChange.stage, 'retry-target');
    return true;
  });
});

test('alternative missing roots do not defeat an exact or valid per-file authorization', async (t) => {
  const { root, path, files } = await fixture(t);
  const missing = join(root, 'obsolete-project');
  const canonical = await realpath(path);
  assert.equal((await files.read(path, [missing, root], sha('original'))).toString(), 'original');
  assert.equal(
    (await files.read(canonical, [missing], sha('original'), [canonical], true)).toString(),
    'original',
  );
  const sibling = join(root, 'not-referenced.txt');
  await writeFile(sibling, 'private');
  await assert.rejects(
    files.inspect(sibling, [missing], [path], true),
    (e) => e.status === 403 && e.code === 'RESOURCE_NOT_AUTHORIZED',
  );
  await rm(path);
  await assert.rejects(
    files.inspect(path, [missing], [path], true),
    (e) => e.status === 410 && e.code === 'ENOENT',
  );
});
