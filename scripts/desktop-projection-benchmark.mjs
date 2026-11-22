import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile, mkdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { performance } from 'node:perf_hooks';

if (!process.env.BASELINE_RUNTIME_MODULE)
  throw Error('Set BASELINE_RUNTIME_MODULE to baseline runtime-session.js');
const modules = [
  process.env.BASELINE_RUNTIME_MODULE,
  new URL('../packages/codex-adapter/dist/runtime-session.js', import.meta.url).pathname,
];
const dir = await mkdtemp(join(tmpdir(), 'projection-benchmark-'));
const root = join(dir, 'project');
await mkdir(root);
await writeFile(join(root, 'synthetic.txt'), 'Synthetic attachment contents');
await writeFile(join(dir, 'outside.txt'), 'Unauthorized synthetic attachment');
const image = Buffer.from('synthetic image bytes').toString('base64');
const scenarios = [
  {
    name: 'text',
    count: 1000,
    iterations: 20,
    item: (i) => ({
      id: `item-${i}`,
      type: 'agentMessage',
      text: `Synthetic message ${i} ` + 'content '.repeat(20),
    }),
  },
  {
    name: 'paths',
    count: 80,
    iterations: 10,
    item: (i) => ({
      id: `item-${i}`,
      type: 'userMessage',
      content: [
        {
          type: 'localImage',
          path: i % 2 ? join(dir, 'outside.txt') : join(root, 'synthetic.txt'),
        },
      ],
    }),
  },
  {
    name: 'inline-image',
    count: 80,
    iterations: 10,
    item: (i) => ({
      id: `item-${i}`,
      type: 'agentMessage',
      text: `Image ${i}`,
      output: [{ type: 'image', mimeType: 'image/png', data: image }],
    }),
  },
];
try {
  for (const scenario of scenarios) {
    const snapshot = {
      turns: [
        {
          turnId: 'turn',
          status: 'completed',
          items: Array.from({ length: scenario.count }, (_, i) => scenario.item(i)),
        },
      ],
    };
    let baseline;
    for (const [index, path] of modules.entries()) {
      const { RuntimeSession } = await import(pathToFileURL(resolve(path)).href);
      const session = new RuntimeSession({
        root,
        directory: join(dir, `${scenario.name}-${index}`),
        name: 'benchmark',
        mode: 'desktop-runtime',
        socket: join(dir, 'absent.sock'),
      });
      const thread = {
        id: 'thread',
        hostId: session.hostId,
        projectId: null,
        title: 'bench',
        state: 'unknown',
        turnId: null,
        messages: [],
        plan: [],
        error: null,
        desktopDirectory: root,
      };
      session.state.threads = [thread];
      let calls = 0,
        registers = 0;
      const upsert = session.upsert.bind(session),
        register = session.registerMessageFile.bind(session);
      session.upsert = async (...args) => {
        calls++;
        return upsert(...args);
      };
      session.registerMessageFile = async (...args) => {
        registers++;
        return register(...args);
      };
      try {
        await session.projectDesktopMessages(thread, snapshot);
        if (scenario.name === 'paths') {
          assert.equal(
            thread.messages.filter((message) => message.attachments.length).length,
            scenario.count / 2,
          );
        }
        calls = 0;
        registers = 0;
        const cpu = process.cpuUsage(),
          start = performance.now();
        for (let i = 0; i < scenario.iterations; i++)
          await session.projectDesktopMessages(thread, snapshot);
        const result = {
          scenario: scenario.name,
          variant: index ? 'optimized' : 'baseline',
          items: scenario.count,
          iterations: scenario.iterations,
          elapsedMs: performance.now() - start,
          cpuMicroseconds: process.cpuUsage(cpu),
          upsertCalls: calls,
          registerMessageFileCalls: registers,
          messages: thread.messages.length,
        };
        // Generated attachment IDs and timestamps are session-specific. Compare wire-visible
        // message content and stable attachment metadata rather than those random values.
        const semantic = thread.messages.map(({ id, text, role, kind, status, attachments }) => ({
          id,
          text,
          role,
          kind,
          status,
          attachments: attachments.map(({ name, mediaType, sizeBytes, sha256 }) => ({
            name,
            mediaType,
            sizeBytes,
            sha256,
          })),
        }));
        if (!index) baseline = semantic;
        else assert.deepEqual(semantic, baseline, `${scenario.name} projected messages differ`);
        console.log(JSON.stringify(result));
      } finally {
        session.close();
      }
    }
  }

  // Both production schedulers inspect the same native paths. Pause the first
  // real inspection, replace the snapshot, and measure how much stale work runs.
  for (const [index, path] of modules.entries()) {
    const { RuntimeSession } = await import(pathToFileURL(resolve(path)).href);
    const session = new RuntimeSession({
      root,
      directory: join(dir, `superseded-${index}`),
      name: 'benchmark',
      mode: 'desktop-runtime',
      socket: join(dir, 'absent.sock'),
    });
    const thread = {
      id: 'thread',
      hostId: session.hostId,
      projectId: null,
      title: 'bench',
      state: 'unknown',
      turnId: null,
      messages: [],
      plan: [],
      error: null,
      desktopDirectory: root,
    };
    session.state.threads = [thread];
    session.desktopControls = () => {};
    session.runtime.ipc.hasOwner = () => true;
    session.desktopReadReply = () => undefined;
    let publishes = 0,
      inspections = 0,
      registers = 0;
    session.emit = (_id, event) => {
      if (event === 'desktop/snapshot') publishes++;
    };
    let release, started;
    const ready = new Promise((done) => {
      started = done;
    });
    const gate = new Promise((done) => {
      release = done;
    });
    const inspect = session.attachmentFiles.inspect.bind(session.attachmentFiles);
    const register = session.registerMessageFile.bind(session);
    session.registerMessageFile = async (...args) => {
      registers++;
      return register(...args);
    };
    session.attachmentFiles.inspect = async (...args) => {
      if (++inspections === 1) {
        started();
        await gate;
      }
      return inspect(...args);
    };
    const snapshot = (label) => ({
      turns: [
        {
          turnId: 'turn',
          status: 'completed',
          items: Array.from({ length: 80 }, (_, i) => ({
            id: `item-${i}`,
            type: 'agentMessage',
            text: `${label} ${i}`,
            path: join(root, 'synthetic.txt'),
          })),
        },
      ],
    });
    try {
      const cpu = process.cpuUsage(),
        start = performance.now();
      const pending = session.desktopSnapshot(thread, snapshot('old'));
      await ready;
      const newer = session.desktopSnapshot(thread, snapshot('latest'));
      assert.equal(newer, pending);
      release();
      await pending;
      assert.equal(thread.messages.length, 80);
      assert(thread.messages.every((message, i) => message.text === `latest ${i}`));
      assert.equal(publishes, 1);
      console.log(
        JSON.stringify({
          scenario: 'superseded-native-paths',
          variant: index ? 'optimized' : 'baseline',
          items: 80,
          elapsedMs: performance.now() - start,
          cpuMicroseconds: process.cpuUsage(cpu),
          inspectCalls: inspections,
          registerMessageFileCalls: registers,
          publishes,
          latestMessages: thread.messages.length,
        }),
      );
    } finally {
      release?.();
      session.close();
    }
  }
} finally {
  await rm(dir, { recursive: true, force: true });
}
