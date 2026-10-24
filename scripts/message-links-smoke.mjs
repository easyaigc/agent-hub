import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm, symlink } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { pathToFileURL } from 'node:url';
import { messageLinks, localMessagePath } from '../packages/codex-adapter/dist/message-links.js';
import { renderItem } from '../packages/codex-adapter/dist/runtime.js';
import { RuntimeSession } from '../packages/codex-adapter/dist/runtime-session.js';

// These non-file URL forms use the same parser branch on either host OS.
const windowsPath = String.raw`C:\fixture\验证 (记录).md`;
const uncPath = String.raw`\\server\share\report.md`;
for (const [reference, expected] of [
  [encodeURI(windowsPath) + ':12:3', windowsPath],
  ['sandbox:' + encodeURI(windowsPath) + '#L2-L4', windowsPath],
  [windowsPath + '#L2', windowsPath],
  ['C:%2Ffixture%2Freport%23L2.md#L3', 'C:/fixture/report#L2.md'],
  ['/fixture/%E9%AA%8C%E8%AF%81%20%23L2.md#L3', '/fixture/验证 #L2.md'],
  ['report%23L2#L3', 'report#L2'],
  ['%23report.md', '#report.md'],
  ['literal%ZZ.md', 'literal%ZZ.md'],
  ['100%.md', '100%.md'],
  // Parsing UNC/native separators does not grant authorization to that path.
  [uncPath, uncPath],
  [encodeURI(uncPath), uncPath],
  ['/%5Cserver/share/report.md', String.raw`/\server/share/report.md`],
])
  assert.equal(localMessagePath(reference), expected, reference);
for (const reference of [
  'https%3A%2F%2Fexample.com/report.md',
  '%68ttps://example.com/report.md',
  'custom%3Aopen',
  'data%3Atext/plain,hello',
  'file%3A%2F%2F%2Ftmp/report.md',
  'sandbox:https%3A%2F%2Fexample.com/report.md',
  '%2F%2Fexample.com/report.md',
  '/%2Fexample.com/report.md',
  'C:relative.md',
  'C:%255Cfixture%255Creport.md',
  'C:%5Cfixture%5C%00secret',
])
  assert.equal(localMessagePath(reference), undefined, reference);

// Synthetic boundary regression; real desktop/native/model acceptance is separate.
const directory = await mkdtemp(join(tmpdir(), 'message-links-'));
let session;
try {
  const root = join(directory, 'workspace');
  await mkdir(root);
  const path = join(root, '验证 (记录).md');
  await writeFile(path, 'version one');
  const references = [
    pathToFileURL(path).href,
    pathToFileURL(path).href + '#L2',
    encodeURI(path) + ':12:3',
    'sandbox:' + encodeURI(path),
    encodeURI('验证 (记录).md') + '#L2-L4',
    '<' + path + '>',
    path.replaceAll('(', '\\(').replaceAll(')', '\\)').replaceAll(' ', '%20'),
  ];
  const text = references.map((ref, i) => `[link ${i}](${ref} "title ${i}")`).join('\n');
  const links = messageLinks(text);
  assert.equal(links.length, references.length);
  for (const link of links) {
    const local = localMessagePath(link.reference);
    assert(local === path || local === '验证 (记录).md', link.reference);
  }
  for (const reference of [
    'https://example.com/a.md',
    'HTTPS://example.com/a.md',
    '//example.com/a.md',
    'javascript:alert(1)',
    'data:text/plain,hello',
    'codex://review',
    '#section',
    '?query',
    'file:///tmp/a.md?download=1',
    '/tmp/%00secret',
  ])
    assert.equal(localMessagePath(reference), undefined, reference);
  assert.equal(messageLinks('`[inline](/secret)`\n```md\n[fenced](/secret)\n```').length, 0);
  assert.deepEqual(
    messageLinks('[broken](<no end)\n[valid](./ok.md)').map((v) => v.reference),
    ['./ok.md'],
  );
  const rendered = renderItem({ id: 'message', type: 'agentMessage', text });
  assert.equal(rendered.paths.length, references.length, 'file URLs must be registered');
  session = new RuntimeSession({
    mode: 'managed-runtime',
    directory: join(directory, 'state'),
    root,
    name: 'Link boundary fixture',
  });
  const thread = {
    id: 'thread',
    hostId: session.hostId,
    projectId: 'agent-workspace',
    title: 'Link boundary fixture',
    state: 'idle',
    turnId: null,
    messages: [],
    plan: [],
    error: null,
  };
  session.state.threads.push(thread);
  await session.upsert(thread, { id: 'message', type: 'agentMessage', text });
  const first = thread.messages[0];
  assert.equal(first.attachments.length, 1, 'all path forms share one checked attachment');
  const id = first.attachments[0].id;
  assert.equal(
    messageLinks(first.text).filter((v) => v.reference === 'codex-attachment://' + id).length,
    references.length,
  );
  assert(first.text.includes('"title 0"'), 'rewrites preserve the title');
  assert.equal((await session.download(thread.id, id)).data.toString(), 'version one');
  await writeFile(path, 'version two');
  await assert.rejects(session.download(thread.id, id), (e) => e.status === 409);
  const original = structuredClone(first);
  const resolved = await session.resolveAttachment(thread.id, id);
  assert.equal(resolved.sourceAttachmentId, id);
  assert.equal(resolved.hostId, session.hostId);
  assert.equal(resolved.threadId, thread.id);
  assert(!('path' in resolved.attachment), 'resolution must not disclose a computer path');
  assert.notEqual(resolved.attachment.id, id);
  assert.equal(resolved.attachment.resourceId, first.attachments[0].resourceId);
  assert.equal(
    (await session.download(thread.id, resolved.attachment.id)).data.toString(),
    'version two',
  );
  assert.deepEqual(first, original, 'resolving does not mutate the old message or attachment');
  await assert.rejects(session.download(thread.id, id), (e) => e.status === 409);
  await assert.rejects(session.resolveAttachment('another-thread', id));
  assert.equal(
    (await session.resolveAttachment(thread.id, id)).attachment.id,
    resolved.attachment.id,
  );
  await session.upsert(thread, { id: 'message', type: 'agentMessage', text });
  const updated = thread.messages[0].attachments[0];
  assert.notEqual(updated.id, id);
  assert.equal(
    updated.resourceId,
    first.attachments[0].resourceId,
    'same-file UI identity survives a new content version',
  );
  assert(
    thread.messages[0].text.includes('codex-attachment://' + updated.id),
    'same-path rewrites must select the fresh digest',
  );
  assert.equal((await session.download(thread.id, updated.id)).data.toString(), 'version two');
  await assert.rejects(session.download('another-thread', updated.id));
  await writeFile(path, 'version three');
  const latest = (await session.resolveAttachment(thread.id, id)).attachment;
  assert.equal((await session.download(thread.id, latest.id)).data.toString(), 'version three');
  await rm(path);
  await assert.rejects(
    session.resolveAttachment(thread.id, id),
    (e) => e.status === 410 && e.code === 'ENOENT',
  );
  const secret = join(directory, 'secret.md');
  await writeFile(secret, 'private');
  await symlink(secret, join(root, 'escape.md'));
  await symlink(secret, path);
  await assert.rejects(session.resolveAttachment(thread.id, id), (e) => e.status === 403);
  const denied = `[outside](${secret}) [symlink](escape.md) [missing](missing.md) [web](https://example.com/report.md) [code](javascript:alert(1))`;
  await session.upsert(thread, { id: 'denied', type: 'agentMessage', text: denied });
  assert.equal(thread.messages[1].attachments.length, 0);
  assert.equal(
    thread.messages[1].text,
    denied,
    'unauthorized paths and URLs are never exposed as attachments',
  );
  await mkdir(join(root, 'other'));
  const other = join(root, 'other', '验证 (记录).md');
  await writeFile(other, 'different source');
  await session.upsert(thread, {
    id: 'same-name',
    type: 'agentMessage',
    text: `[other](<${other}>)`,
  });
  assert.notEqual(
    thread.messages.at(-1).attachments[0].resourceId,
    latest.resourceId,
    'matching filenames never merge different resources',
  );
  await rm(path);
  await writeFile(path, 'version three');
  const options = session.options;
  await session.close();
  session = new RuntimeSession(options);
  const restored = (await session.resolveAttachment(thread.id, id)).attachment;
  assert.equal(restored.id, latest.id);
  assert.equal(restored.resourceId, latest.resourceId, 'resource identity survives Host restart');
  console.log(
    'Message links: 7 local forms, exact version download, current-version resolution without history refresh, unchanged original records, stale bytes, cross-thread and root boundaries passed.',
  );
} finally {
  await session?.close();
  await rm(directory, { recursive: true, force: true });
}
