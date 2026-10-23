import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { createHash, randomUUID } from 'node:crypto';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';

// Explicit real-model acceptance, using only owned threads and files.
const directory = resolve('.local/live-runtime');
await mkdir(join(directory, 'workspace'), { recursive: true });
Object.assign(process.env, {
  CODEX_RUNTIME_MODE: 'managed-runtime',
  HOST_AGENT_PORT: '0',
  HOST_AGENT_ROOT: join(directory, 'workspace'),
  HOST_AGENT_STATE_DIR: join(directory, 'runtime'),
});
let service;
try {
  service = await startRuntimeServer();
  const workspace = await service.session.workspace();
  const thread = await service.session.createThread(workspace.projects[0].id, randomUUID());
  const nonce = 'REOPENED_EMPTY_' + randomUUID();
  const bytes = Buffer.from(nonce);
  const file = service.session.upload(
    thread.id,
    'input-before-restart.txt',
    'text/plain',
    bytes,
    createHash('sha256').update(bytes).digest('hex'),
  );
  service.close();
  await new Promise((done) => setTimeout(done, 300));
  service = await startRuntimeServer();
  const opened = await service.session.open(thread.id);
  assert.equal(opened.threadId, thread.id);
  assert.equal(opened.messages.length, 0);
  assert(!('codexId' in opened.thread));
  const clientMessageId = randomUUID();
  await service.session.submit({
    hostId: workspace.hostId,
    threadId: thread.id,
    clientMessageId,
    text: '先调用 update_plan 创建“读取附件”和“校验结果”两个步骤。用工具读取附件，完成后调用 update_plan 将两个步骤标记 completed；最终只回复附件中的完整校验码，不添加格式。不要写任何文件。',
    attachmentIds: [file.id],
    mode: 'next-turn',
    effort: 'low',
  });
  let epoch = '',
    cursor = 0,
    completed;
  const deadline = Date.now() + 150_000;
  while (Date.now() < deadline) {
    const batch = await service.session.poll(cursor, epoch, new AbortController().signal);
    epoch = batch.epoch;
    cursor = batch.cursor;
    const snapshot = batch.events.find((e) => e.threadId === thread.id)?.snapshot;
    if (!snapshot) continue;
    if (snapshot.runtime.approvals.length)
      throw Error('Unexpected approval; no request was automatically accepted');
    if (snapshot.runtime.state === 'failed') throw Error('Real model task failed');
    if (
      snapshot.runtime.state === 'idle' &&
      snapshot.messages.some((m) => m.role === 'assistant' && m.text.trim() === nonce)
    ) {
      completed = snapshot;
      break;
    }
  }
  assert(completed, 'Model must read the actual pre-restart attachment');
  assert.equal(completed.runtime.plan.length, 2, 'Real Codex plan must contain two steps');
  assert(completed.runtime.plan.every((step) => step.status === 'completed'));
  assert.equal(service.session.receipt(clientMessageId).state, 'completed');
  assert(
    completed.messages.some((m) =>
      m.attachments.some((a) => a.id === file.id && a.threadId === thread.id),
    ),
  );
  await writeFile(
    join(directory, 'summary.json'),
    JSON.stringify(
      {
        passed: true,
        emptyThreadRecovered: true,
        preRestartAttachmentRead: true,
        realPlanVerified: true,
      },
      null,
      2,
    ),
  );
  console.log(
    'Real runtime acceptance passed: empty-thread restart recovery, stable logical target, pre-restart attachment read and completed receipt.',
  );
} finally {
  service?.close();
}
