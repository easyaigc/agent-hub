import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm, symlink } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { pathToFileURL } from 'node:url';
import { messageLinks, localMessagePath } from '../packages/codex-adapter/dist/message-links.js';
import { renderItem } from '../packages/codex-adapter/dist/runtime.js';
import { RuntimeSession } from '../packages/codex-adapter/dist/runtime-session.js';

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
  await session.upsert(thread, { id: 'message', type: 'agentMessage', text });
  const updated = thread.messages[0].attachments[0];
  assert.notEqual(updated.id, id);
  assert(
    thread.messages[0].text.includes('codex-attachment://' + updated.id),
    'same-path rewrites must select the fresh digest',
  );
  assert.equal((await session.download(thread.id, updated.id)).data.toString(), 'version two');
  await assert.rejects(session.download('another-thread', updated.id));
  const secret = join(directory, 'secret.md');
  await writeFile(secret, 'private');
  await symlink(secret, join(root, 'escape.md'));
  const denied = `[outside](${secret}) [symlink](escape.md) [missing](missing.md) [web](https://example.com/report.md) [code](javascript:alert(1))`;
  await session.upsert(thread, { id: 'denied', type: 'agentMessage', text: denied });
  assert.equal(thread.messages[1].attachments.length, 0);
  assert.equal(
    thread.messages[1].text,
    denied,
    'unauthorized paths and URLs are never exposed as attachments',
  );
  console.log(
    'Message links: 7 local forms, exact version download, stale bytes, cross-thread and root boundaries passed.',
  );
} finally {
  await session?.close();
  await rm(directory, { recursive: true, force: true });
}
