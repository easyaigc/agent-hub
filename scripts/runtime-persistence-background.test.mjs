import assert from 'node:assert/strict';
import { test as nodeTest } from 'node:test';
import { createHash } from 'node:crypto';
import { chmod, mkdtemp, readFile, readdir, rm, writeFile, stat, symlink } from 'node:fs/promises';
import { fsyncSync, renameSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { setTimeout as delay } from 'node:timers/promises';
import { RuntimePersistence } from '../packages/codex-adapter/dist/runtime-persistence.js';
import { startCpuStages } from '../packages/codex-adapter/dist/cpu-stages.js';
import { CodexHistoryReader } from '../packages/codex-adapter/dist/history-reader.js';
import { RuntimeSession } from '../packages/codex-adapter/dist/runtime-session.js';

const target = {
  agentId: 'codex',
  hostId: 'cache-host',
  workspaceId: 'cache-host',
  runtimeId: 'desktop-runtime',
};
// The optimization is macOS only; Linux can exercise the POSIX boundary fixture.
const test = (name, run) => nodeTest(name, { skip: process.platform === 'win32' }, run);
async function until(check) {
  const deadline = performance.now() + 10_000;
  while (!check()) {
    assert(performance.now() < deadline, 'fixture deadline');
    await delay(5);
  }
}
async function fixture(run, options = {}) {
  const directory = await mkdtemp(join(tmpdir(), 'persist-cache-'));
  await chmod(directory, 0o700);
  const file = join(directory, 'runtime.json');
  const reports = [];
  let publishes = 0,
    barriers = 0;
  const store = new RuntimePersistence(file, directory, {
    platform: 'darwin',
    retryDelays: [10, 20],
    report: (scope, error) => reports.push({ scope, error }),
    rename: (from, to) => {
      renameSync(from, to);
      publishes++;
      options.afterRename?.(to);
    },
    fsync: (fd) => {
      barriers++;
      fsyncSync(fd);
    },
  });
  const state = {
    hostId: 'cache-host',
    threads: [{ id: 'thread-a', messages: [{ id: 'a', text: 'original' }] }],
    receipts: [{ id: 'immutable-op', text: 'original input', state: 'uncertain' }],
    attachments: [{ path: 'old-path', source: { version: 1 } }],
  };
  const background = async () => {
    store.schedule(() => state);
    await until(() => store.status.backgroundSave === 'idle');
  };
  const history = async () => {
    store.dirty(state.threads[0]);
    await until(() => store.status.historyPending === 0);
  };
  const historyFile = () =>
    join(
      directory,
      'runtime-history-v1',
      createHash('sha256').update('cache-host\0thread-a').digest('hex') + '.json',
    );
  try {
    assert.equal(store.load(), undefined);
    store.acquireReplyWriter(target);
    store.save(state);
    await until(() => store.status.historyPending === 0);
    await run({
      store,
      state,
      file,
      directory,
      reports,
      background,
      history,
      historyFile,
      counts: () => ({ publishes, barriers }),
    });
  } finally {
    try {
      store.close();
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  }
}

test('exact background metadata reuse skips candidate work; synchronous read/save/close barriers still execute', async () => {
  await fixture(async ({ store, state, background, historyFile, counts }) => {
    await background();
    await background(); // Establish a stable no-op, not a changed-state cache.
    const before = counts();
    const finish = startCpuStages();
    await background();
    await background();
    const metrics = finish();
    assert.equal(metrics.stages['persistence.serialize']?.calls ?? 0, 0);
    assert.ok(
      metrics.stages['persistence.sha256'].calls > 0,
      'actual ledger checks still run; tampering is independently rejected below',
    );
    assert.deepEqual(counts(), before);
    const readMetrics = startCpuStages();
    store.saveRead(state);
    assert.equal(
      readMetrics().stages['persistence.serialize'].calls,
      1,
      'explicit read keeps original serializer/barrier path',
    );
    store.save(state);
    assert.equal(counts().publishes, before.publishes + 1);
    assert.equal(counts().barriers, before.barriers + 2);
    state.attachments[0].source.version++;
    await background();
    const changedHistory = startCpuStages();
    store.dirty(state.threads[0]);
    await until(() => store.status.historyPending === 0);
    assert.equal(
      changedHistory().stages['persistence.serialize'].calls,
      1,
      'genuinely changing metadata keeps the original history path',
    );
    store.dirty(state.threads[0]);
    const closing = startCpuStages();
    store.close(true);
    assert.ok(
      closing().stages['persistence.serialize'].calls >= 2,
      'pending history and close fence are written',
    );
    assert.equal(JSON.parse(await readFile(historyFile())).messages[0].text, 'original');
  });
});

test('in-place metadata, receipt, paths, key order, and self-modifying JSON accessors never reuse stale data', async () => {
  await fixture(async ({ state, file, background }) => {
    await background();
    await background();
    state.attachments[0].source.version = 2;
    state.attachments[0].path = 'new-path';
    state.receipts[0].state = 'submitted';
    await background();
    assert.deepEqual(JSON.parse(await readFile(file)).attachments, state.attachments);
    assert.equal(JSON.parse(await readFile(file)).receipts[0].text, 'original input');
    state.attachments[0] = { source: state.attachments[0].source, path: state.attachments[0].path };
    await background();
    assert.deepEqual(Object.keys(JSON.parse(await readFile(file)).attachments[0]), [
      'source',
      'path',
    ]);
    await background();
    await background();
    let calls = 0;
    Object.defineProperty(state.attachments[0], 'ephemeral', {
      enumerable: true,
      configurable: true,
      get() {
        calls++;
        delete this.ephemeral;
        return 'once';
      },
    });
    await background();
    assert.equal(calls, 1, 'optimization must not run JSON getters a second time');
    assert.equal(JSON.parse(await readFile(file)).attachments[0].ephemeral, 'once');
    await background();
    assert.equal(
      JSON.parse(await readFile(file)).attachments[0].ephemeral,
      undefined,
      'self-deletion must reach disk',
    );
    state.extra = [3];
    await background();
    await background();
    await background();
    Object.defineProperty(state.extra, '0', { enumerable: false });
    await background();
    await background();
    state.extra[0] = 7;
    await background();
    assert.deepEqual(JSON.parse(await readFile(file)).extra, [7]);
  });
});

test('cached metadata still detects current ledger tampering and foreign writer nonce', async () => {
  await fixture(async ({ store, state, file, directory, reports, background }) => {
    await background();
    await background();
    const original = await readFile(file);
    await writeFile(file, Buffer.concat([original, Buffer.from(' ')]));
    store.schedule(() => state);
    await until(() => reports.length > 0);
    assert.equal(reports[0].error.stage, 'writer-continuity');
    assert.equal((await readFile(file)).length, original.length + 1);
    await writeFile(file, original);
    store.save(state);
    await background();
    await background();
    const lease = join(directory, 'reply-writer-v2.json');
    const bytes = await readFile(lease);
    await writeFile(lease, JSON.stringify({ ...JSON.parse(bytes), nonce: 'foreign' }));
    store.schedule(() => state);
    await until(() => reports.length > 1);
    assert.equal(reports.at(-1).error.stage, 'writer-continuity');
    await writeFile(lease, bytes);
    store.save(state);
    await background();
    await background();
    await chmod(file, 0o666);
    const priorReports = reports.length;
    store.schedule(() => state);
    await until(() => reports.length > priorReports);
    assert.throws(() => store.saveRead(state));
    await chmod(file, 0o600);
  });
});

test('history no-op verifies real private bytes; same-array edits, removal and post-publish tampering are repaired', async () => {
  let corrupt = false;
  await fixture(
    async ({ state, history, historyFile, counts, directory, background }) => {
      await background();
      await background();
      await history();
      const before = counts();
      const finish = startCpuStages();
      await history();
      assert.equal(finish().stages['persistence.serialize']?.calls ?? 0, 0);
      assert.deepEqual(counts(), before);
      await chmod(historyFile(), 0o666);
      await history();
      assert.equal((await stat(historyFile())).mode & 0o777, 0o600);
      const unrelated = join(directory, 'unrelated-file');
      await writeFile(unrelated, 'keep unrelated bytes', { mode: 0o600 });
      await rm(historyFile());
      await symlink(unrelated, historyFile());
      await history();
      assert.equal(await readFile(unrelated, 'utf8'), 'keep unrelated bytes');
      assert.equal(JSON.parse(await readFile(historyFile())).messages[0].text, 'original');
      state.threads[0].messages[0].text = 'edited in place';
      await history();
      assert.equal(JSON.parse(await readFile(historyFile())).messages[0].text, 'edited in place');
      await rm(historyFile());
      await history();
      assert.equal(JSON.parse(await readFile(historyFile())).messages[0].text, 'edited in place');
      state.threads[0].messages[0].text = 'published original';
      corrupt = true;
      await history();
      assert.equal(JSON.parse(await readFile(historyFile())).corrupt, true);
      await history();
      assert.equal(
        JSON.parse(await readFile(historyFile())).messages[0].text,
        'published original',
      );
      assert.deepEqual(
        (await readdir(join(historyFile(), '..'))).filter((x) => x.endsWith('.tmp')),
        [],
      );
    },
    {
      afterRename: (file) => {
        if (corrupt && file.includes('runtime-history-v1')) {
          corrupt = false;
          writeFileSync(file, '{"corrupt":true}', { mode: 0o600 });
        }
      },
    },
  );
});

test('retained history stays reusable when unrelated metadata changes, while changed or damaged history is published', async () => {
  await fixture(async ({ store, state, background, history, historyFile, counts }) => {
    await background();
    await background();
    await history();
    const original = await readFile(historyFile());
    state.receipts[0].state = 'submitted';
    await background();
    const before = counts();
    await history();
    assert.deepEqual(
      counts(),
      before,
      'metadata changes must not republish identical known history',
    );
    assert.deepEqual(await readFile(historyFile()), original);

    await writeFile(historyFile(), '{"unrelated":"corrupted"}', { mode: 0o600 });
    await history();
    assert.deepEqual(
      await readFile(historyFile()),
      original,
      'current file bytes are still verified',
    );
    for (let tick = 0; tick < 3; tick++) {
      state.counter = tick;
      state.threads[0].messages[0].text = `real history change ${tick}`;
      await background();
      await history();
      assert.equal(
        JSON.parse(await readFile(historyFile())).messages[0].text,
        `real history change ${tick}`,
      );
    }
    store.saveRead(state);
    assert.equal(
      JSON.parse(await readFile(historyFile())).messages[0].text,
      'real history change 2',
    );
  });
});

test('catalog and owner title presentation converge without republishing metadata; real renames remain visible', async () => {
  await fixture(async ({ state, background, counts, file }) => {
    const reader = Object.create(CodexHistoryReader.prototype);
    reader.hostId = state.hostId;
    reader.name = 'fixture';
    reader.knownThreads = new Set();
    reader.knownProjects = new Map();
    reader.projectsForThread = () => ({ projectId: null, projectIds: [] });
    const source = { id: state.threads[0].id, name: '**来自：示例电脑**\n  同一任务', preview: '' };
    const catalogTitle = () =>
      reader.mapWorkspace({ data: [] }, { data: [source] }).threads[0].title;
    const session = Object.create(RuntimeSession.prototype);
    session.desktopSuggestionSources = new Map();
    session.completedDesktopSuggestionSource = () => undefined;
    session.desktopReadReply = () => undefined;
    const thread = state.threads[0];
    const owner = (title) =>
      session.desktopSummary(thread, {
        title,
        turns: [{ turnId: 'running-original-turn', status: 'inProgress', items: [] }],
        threadRuntimeStatus: { type: 'active' },
      });
    thread.title = catalogTitle();
    owner('来自：示例电脑 同一任务');
    await background();
    await background();
    const before = counts();
    for (let tick = 0; tick < 3; tick++) {
      thread.title = catalogTitle();
      await background();
      owner('来自：示例电脑 同一任务');
      await background();
    }
    assert.deepEqual(
      counts(),
      before,
      'equivalent catalog/owner formatting must not churn saved state',
    );
    assert.equal(source.name, '**来自：示例电脑**\n  同一任务', 'native metadata is read-only');
    assert.equal(thread.state, 'running');
    assert.equal(thread.turnId, 'running-original-turn');
    owner('真正重命名 🧪');
    await background();
    assert.equal(JSON.parse(await readFile(file)).threads[0].title, '真正重命名 🧪');
    source.name = '更新后的目录标题';
    thread.title = catalogTitle();
    await background();
    assert.equal(JSON.parse(await readFile(file)).threads[0].title, '更新后的目录标题');
    for (const title of ['unpaired ** marker', '2 * 3 = 6', 'literal __name__', '中文 🧪']) {
      source.name = title;
      assert.equal(catalogTitle(), title, 'ordinary names and unpaired markers retain their text');
    }
  });
});

test('saved metadata retains exact UTF-8 JSON bytes and durability for non-ASCII and surrogate data', async () => {
  await fixture(async ({ store, state, file, counts }) => {
    state.extra = { text: '漢字 🧪', surrogate: '\ud800', escaped: '\u0000\n"\\' };
    const before = counts();
    store.save(state);
    const raw = await readFile(file);
    const persisted = JSON.parse(raw);
    const expected = {
      ...state,
      replyLedger: persisted.replyLedger,
      historyCacheVersion: 1,
      threads: state.threads.map((thread) => ({ ...thread, messages: [] })),
    };
    assert.deepEqual(raw, Buffer.from(JSON.stringify(expected), 'utf8'));
    assert.equal(counts().publishes, before.publishes + 1);
    assert.equal(counts().barriers, before.barriers + 2);
    store.saveRead(state);
    assert.deepEqual(await readFile(file), raw);
  });
});

test('snapshot eligibility does not reduce large-content persistence', async () => {
  await fixture(async ({ state, file, background }) => {
    // Above the per-entry retained-data budget. Serialization still keeps every byte.
    state.large = 'x'.repeat(50 * 1024 * 1024) + 'tail-kept';
    await background();
    await background();
    const finish = startCpuStages();
    await background();
    assert.equal(finish().stages['persistence.serialize'].calls, 1);
    const saved = JSON.parse(await readFile(file));
    assert.equal(saved.large.length, state.large.length);
    assert.ok(saved.large.endsWith('tail-kept'));
  });
});
