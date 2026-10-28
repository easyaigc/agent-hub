import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createHash } from 'node:crypto';
import { join, win32 } from 'node:path';
import test from 'node:test';
import { preparedTraceFixture } from './fixtures/windows-prepared-trace.mjs';

const items = (calls) => calls.flatMap((call) => (call.kind === 'B' ? call.items : [call]));
const manifests = (snapshot, paths) =>
  snapshot.discoveryFiles(
    paths.map((path) => ({ path: win32.join(path, 'manifest.json'), maximum: 16384 })),
  );
const projection = (snapshot) =>
  snapshot.guards
    .map(({ stat, ...guard }) => ({
      ...guard,
      stat: Object.fromEntries(
        ['dev', 'ino', 'mode', 'uid', 'gid', 'nlink', 'size', 'mtimeNs', 'ctimeNs'].map((key) => [
          key,
          stat[key],
        ]),
      ),
    }))
    .sort((a, b) => a.path.localeCompare(b.path));

test('time-filtered candidates fill bounded discovery batches without losing any fresh proof', () =>
  preparedTraceFixture(
    ({ reader, TraceSnapshot, root, create, remote, local, data, calls, reset, batch }) => {
      const fixtures = Array.from({ length: 64 }, () => create()).sort((a, b) =>
        a.fixture.directory.localeCompare(b.fixture.directory),
      );
      for (const [index, value] of fixtures.entries()) {
        value.fixture.manifest.started_at_unix_ms = index % 8 === 0 ? 10 : 0;
        data.writeJson(join(value.fixture.directory, 'manifest.json'), value.fixture.manifest);
      }
      const names = fixtures.map((value) => win32.basename(remote(value.fixture.directory))).sort();
      const obligations = () =>
        items(calls)
          .map(({ path, maximum }) => [
            path,
            maximum ? Math.min(maximum, Number(fs.statSync(local(path)).size)) : 0,
          ])
          .sort((a, b) => JSON.stringify(a).localeCompare(JSON.stringify(b)));
      // The scalar transport is an independent reference for every original
      // directory/file receipt, including manifests excluded by the time filter.
      batch(false);
      const reference = new TraceSnapshot(true);
      reference.root(root);
      const expected = [...reader.discoverCandidates(reference, names, 5)];
      const expectedGuards = projection(reference);
      const expectedPrepareItems = obligations();
      reset();
      reference.commit();
      const expectedCommitItems = obligations();
      reset();
      batch(true);
      const current = new TraceSnapshot(true);
      current.root(root);
      assert.deepEqual([...reader.discoverCandidates(current, names, 5)], expected);
      assert.equal(expected.length, 8);
      assert.deepEqual(projection(current), expectedGuards);
      assert.deepEqual(obligations(), expectedPrepareItems);
      assert.equal(
        calls.filter((call) => call.kind === 'S').length,
        1,
        'Only the original root stays scalar; sparse prefixes and payloads fill one batch each',
      );
      assert(calls.filter((call) => call.kind === 'B').every((call) => call.items.length <= 64));
      reset();
      current.commit();
      assert.deepEqual(
        obligations(),
        expectedCommitItems,
        'Filtered-out manifests and every original directory still participate in commit',
      );
      reference.dispose();
      current.dispose();
    },
  ));

test('a filtered-out manifest becoming eligible after dense discovery still rejects commit', () =>
  preparedTraceFixture(({ reader, TraceSnapshot, root, create, remote, data }) => {
    const fixtures = Array.from({ length: 17 }, () => create()).sort((a, b) =>
      a.fixture.directory.localeCompare(b.fixture.directory),
    );
    for (const [index, value] of fixtures.entries()) {
      value.fixture.manifest.started_at_unix_ms = index % 8 === 0 ? 10 : 0;
      data.writeJson(join(value.fixture.directory, 'manifest.json'), value.fixture.manifest);
    }
    const names = fixtures.map((value) => win32.basename(remote(value.fixture.directory))).sort();
    const snapshot = new TraceSnapshot(true);
    snapshot.root(root);
    assert.equal([...reader.discoverCandidates(snapshot, names, 5)].length, 3);
    const excluded = fixtures[1];
    excluded.fixture.manifest.started_at_unix_ms = 6;
    data.writeJson(join(excluded.fixture.directory, 'manifest.json'), excluded.fixture.manifest);
    assert.throws(
      () => snapshot.commit(),
      'Negative classification is an input receipt, never a reusable authorization cache',
    );
    snapshot.dispose();
  }));

for (const batch of [true, false]) {
  test(`manifest full-chain receipts retain identical directory guards and commit reads; batch=${batch}`, () =>
    preparedTraceFixture(
      ({ TraceSnapshot, root, create, remote, calls, reset, batch: setBatch }) => {
        setBatch(batch);
        const paths = Array.from({ length: 8 }, () => remote(create().fixture.directory));
        const old = new TraceSnapshot(true);
        old.root(root);
        old.directoryGroup(paths);
        const expected = old.files(
          paths.map((path) => ({ path: win32.join(path, 'manifest.json'), maximum: 16384 })),
        );
        const guards = projection(old);
        const oldPrepare = items(calls).length;
        reset();
        old.commit();
        const oldCommit = items(calls).map(({ path, maximum }) => ({ path, maximum }));
        reset();
        const next = new TraceSnapshot(true);
        next.root(root);
        assert.deepEqual(manifests(next, paths), expected);
        assert.deepEqual(projection(next), guards);
        assert.equal(oldPrepare - items(calls).length, paths.length);
        assert(!items(calls).some((item) => paths.includes(item.path) && item.maximum === 0));
        reset();
        next.commit();
        assert.deepEqual(
          items(calls)
            .map(({ path, maximum }) => ({ path, maximum }))
            .sort((a, b) => a.path.localeCompare(b.path)),
          oldCommit.sort((a, b) => a.path.localeCompare(b.path)),
          'Every original standalone directory and file is still freshly verified at commit',
        );
        old.dispose();
        next.dispose();
      },
    ));
}

test('current-instance source recovery uses manifest receipts and preserves exact committed prediction', () =>
  preparedTraceFixture(({ reader, root: _root, create, calls, reset }) => {
    const f = create();
    for (let i = 0; i < 8; i++) create();
    const expected = reader.readSourcePredictions(
      f.fixture.sourceThreadId,
      f.fixture.sourceTurnId,
      1,
      true,
    );
    assert.equal(expected.length, 1);
    reset();
    const pending = reader.prepareSourcePredictions(
      f.fixture.sourceThreadId,
      f.fixture.sourceTurnId,
      1,
      true,
    );
    assert(pending);
    assert.deepEqual(pending.commit(), expected);
    assert(calls.some((call) => call.kind === 'B'));
    assert.equal(pending.commit(), null);
  }));

test('a fresh native parent identity must describe the exact pre-read directory inode', () =>
  preparedTraceFixture(({ TraceSnapshot, root, create, remote, batchReply }) => {
    const paths = Array.from({ length: 2 }, () => remote(create().fixture.directory));
    const snapshot = new TraceSnapshot(true);
    snapshot.root(root);
    batchReply((values) =>
      values.map((value) => {
        const chain = [...value.chain];
        const fields = chain.at(-2).split(':');
        fields[2] = String(BigInt(fields[2]) + 1n);
        chain[chain.length - 2] = fields.join(':');
        return {
          ...value,
          chain,
          identity: createHash('sha256').update(chain.join('|')).digest('base64'),
        };
      }),
    );
    assert.throws(() => manifests(snapshot, paths));
    assert(paths.every((path) => !snapshot.directories.has(path)));
    snapshot.dispose();
  }));

test('a later manifest cannot hide replacement of an earlier directory with its original file inode', () =>
  preparedTraceFixture(({ TraceSnapshot, root, create, remote, change }) => {
    const first = create();
    const second = create();
    const paths = [remote(first.fixture.directory), remote(second.fixture.directory)];
    const snapshot = new TraceSnapshot(true);
    snapshot.root(root);
    let replaced = false;
    change((when, path) => {
      if (replaced || when !== 'after' || path !== win32.join(paths[1], 'manifest.json')) return;
      replaced = true;
      const source = first.fixture.directory;
      const previous = source + '-previous';
      const file = join(source, 'manifest.json');
      const ino = fs.statSync(file, { bigint: true }).ino;
      fs.renameSync(source, previous);
      fs.mkdirSync(source, { mode: 0o700 });
      fs.renameSync(join(previous, 'manifest.json'), file);
      assert.equal(fs.statSync(file, { bigint: true }).ino, ino);
    });
    assert.throws(() => manifests(snapshot, paths));
    assert(replaced);
    assert(paths.every((path) => !snapshot.directories.has(path)));
    snapshot.dispose();
  }));

test('commit still rejects changed native ACL evidence after parent receipt derivation', () =>
  preparedTraceFixture(({ TraceSnapshot, root, create, remote, changeAcl }) => {
    const snapshot = new TraceSnapshot(true);
    snapshot.root(root);
    manifests(snapshot, [remote(create().fixture.directory)]);
    changeAcl();
    assert.throws(() => snapshot.commit());
    snapshot.dispose();
  }));

test('existing request-local directory/file receipts select the original path before I/O', () =>
  preparedTraceFixture(({ TraceSnapshot, root, create, remote, calls, reset }) => {
    const path = remote(create().fixture.directory);
    const snapshot = new TraceSnapshot(true);
    snapshot.root(root);
    snapshot.directory(path);
    const expected = snapshot.file(win32.join(path, 'manifest.json'), 16384);
    const identity = snapshot.directories.get(path).nativeIdentity;
    reset();
    assert.deepEqual(manifests(snapshot, [path]), [expected]);
    assert.equal(calls.length, 0, 'No new authority is minted from a cached file');
    assert.equal(snapshot.directories.get(path).nativeIdentity, identity);
    snapshot.commit();
    snapshot.dispose();
  }));

test('protocol-event payload files also preserve all directory guards and every commit read', () =>
  preparedTraceFixture(({ TraceSnapshot, root, create, remote, calls, reset }) => {
    const files = Array.from({ length: 8 }, () => ({
      path: remote(create().configured.path),
      maximum: 128 * 1024,
    }));
    const directories = files.map((file) => win32.dirname(file.path));
    const old = new TraceSnapshot(true);
    old.root(root);
    old.directoryGroup(directories);
    const expected = old.files(files);
    const guards = projection(old);
    const oldPrepare = items(calls).length;
    reset();
    old.commit();
    const oldCommit = items(calls).map(({ path, maximum }) => ({ path, maximum }));
    reset();
    const next = new TraceSnapshot(true);
    next.root(root);
    assert.deepEqual(next.discoveryFiles(files), expected);
    assert.deepEqual(projection(next), guards);
    assert.equal(oldPrepare - items(calls).length, 8);
    reset();
    next.commit();
    assert.deepEqual(
      items(calls)
        .map(({ path, maximum }) => ({ path, maximum }))
        .sort((a, b) => a.path.localeCompare(b.path)),
      oldCommit.sort((a, b) => a.path.localeCompare(b.path)),
    );
    old.dispose();
    next.dispose();
  }));

test('several discovery files in one parent choose the existing directory path before I/O', () =>
  preparedTraceFixture(({ TraceSnapshot, root, create, remote, calls }) => {
    const f = create();
    const directory = remote(f.fixture.directory);
    const snapshot = new TraceSnapshot(true);
    snapshot.root(root);
    const files = [
      { path: win32.join(directory, 'manifest.json'), maximum: 16384 },
      { path: win32.join(directory, 'trace.jsonl'), maximum: 16384 },
    ];
    assert.equal(snapshot.discoveryFiles(files).length, 2);
    assert(items(calls).some((item) => item.path === directory && item.maximum === 0));
    snapshot.commit();
    snapshot.dispose();
  }));
