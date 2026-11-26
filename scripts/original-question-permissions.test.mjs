import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtemp, mkdir, writeFile, rm, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  originalPermissionSettings,
  readOriginalQuestionPermissions,
} from '../packages/codex-adapter/dist/original-question-permissions.js';
import { replyIntentFingerprint } from '../packages/codex-adapter/dist/runtime-session.js';

const policy = () => ({
  sandboxPolicy: {
    type: 'workspaceWrite',
    writableRoots: ['/workspace/only'],
    networkAccess: false,
    excludeTmpdirEnvVar: true,
    excludeSlashTmp: true,
  },
  approvalPolicy: 'on-request',
  approvalsReviewer: 'auto_review',
});
const context = () => ({
  turn_id: 'original-turn',
  approval_policy: 'on-request',
  approvals_reviewer: 'auto_review',
  sandbox_policy: {
    type: 'workspace-write',
    writable_roots: ['/workspace/only'],
    network_access: false,
    exclude_tmpdir_env_var: true,
    exclude_slash_tmp: true,
  },
});
async function fixture(run) {
  const home = await mkdtemp(join(tmpdir(), 'original-question-permissions-'));
  const directory = join(home, 'sessions');
  await mkdir(directory);
  const path = join(directory, 'rollout.jsonl');
  const write = async (contexts, id = 'thread') =>
    writeFile(
      path,
      [
        JSON.stringify({ type: 'session_meta', payload: { id } }),
        ...contexts.map((payload) => JSON.stringify({ type: 'turn_context', payload })),
      ].join('\n') + '\n',
      { mode: 0o600 },
    );
  try {
    await run({ home, path, write });
  } finally {
    await rm(home, { recursive: true });
  }
}

test('concrete policy preserves custom roots, network, tmp restrictions and exact auto reviewer', () => {
  assert.deepEqual(originalPermissionSettings(policy()), policy());
  assert.equal(
    originalPermissionSettings({ ...policy(), permissions: 'custom-profile' }),
    undefined,
  );
  assert.equal(
    originalPermissionSettings({ ...policy(), permissions: ':danger-full-access' }),
    undefined,
  );
  assert.equal(
    originalPermissionSettings({
      ...policy(),
      sandboxPolicy: { ...policy().sandboxPolicy, unknownRestriction: true },
    }),
    undefined,
  );
  assert.equal(
    originalPermissionSettings({ ...policy(), approvalsReviewer: undefined }),
    undefined,
  );
});

test('shared rollout recovers exactly the requested original turn, never the latest/default policy', async () =>
  fixture(async ({ home, path, write }) => {
    await write([
      context(),
      {
        ...context(),
        turn_id: 'new-turn',
        approval_policy: 'never',
        sandbox_policy: { type: 'danger-full-access' },
      },
    ]);
    assert.deepEqual(
      await readOriginalQuestionPermissions(home, path, 'thread', 'original-turn'),
      policy(),
    );
    assert.equal(
      await readOriginalQuestionPermissions(home, path, 'different-thread', 'original-turn'),
      undefined,
    );
    assert.equal(
      await readOriginalQuestionPermissions(home, path, 'thread', 'missing-turn'),
      undefined,
    );
  }));

test('conflicting original contexts, unknown restrictions and a replaced/symlinked source fail closed', async () =>
  fixture(async ({ home, path, write }) => {
    await write([context(), { ...context(), approvals_reviewer: 'user' }]);
    assert.equal(
      await readOriginalQuestionPermissions(home, path, 'thread', 'original-turn'),
      undefined,
    );
    await write([
      {
        ...context(),
        sandbox_policy: { type: 'workspace-write', writable_roots: ['relative-root'] },
      },
    ]);
    assert.equal(
      await readOriginalQuestionPermissions(home, path, 'thread', 'original-turn'),
      undefined,
    );
    const alias = join(home, 'sessions', 'alias.jsonl');
    await symlink(path, alias);
    assert.equal(
      await readOriginalQuestionPermissions(home, alias, 'thread', 'original-turn'),
      undefined,
    );
    assert.equal(
      await readOriginalQuestionPermissions(
        home,
        join(home, 'outside.jsonl'),
        'thread',
        'original-turn',
      ),
      undefined,
    );
  }));

test('permissions are part of immutable reply intent while legacy reply hashes remain stable', () => {
  const target = {
    agentId: 'codex',
    hostId: 'host',
    workspaceId: 'host',
    runtimeId: 'desktop-runtime',
  };
  const base = {
    hostId: 'host',
    threadId: 'thread',
    clientMessageId: 'message',
    text: 'answer',
    attachmentIds: [],
    mode: 'next-turn',
  };
  const reply = {
    ...base,
    originalQuestionPermissions: { turnId: 'original-turn', settings: policy() },
  };
  const changed = structuredClone(reply);
  changed.originalQuestionPermissions.settings.sandboxPolicy.networkAccess = true;
  assert.notEqual(replyIntentFingerprint(reply, target), replyIntentFingerprint(changed, target));
  assert.notEqual(replyIntentFingerprint(base, target), replyIntentFingerprint(reply, target));
  assert.equal(
    replyIntentFingerprint(base, target),
    replyIntentFingerprint({ ...base, originalQuestionPermissions: undefined }, target),
  );
});

test('legacy managed profile retains original cwd and rejects nonrepresentable custom restrictions', async () =>
  fixture(async ({ home, path, write }) => {
    const ctx = {
      ...context(),
      cwd: '/workspace/only',
      permission_profile: {
        type: 'managed',
        network: 'restricted',
        file_system: {
          type: 'restricted',
          entries: [
            { path: { type: 'special', value: { kind: 'root' } }, access: 'read' },
            { path: { type: 'path', path: '/workspace/only' }, access: 'write' },
            {
              path: { type: 'path', path: '/workspace/only/.git' },
              access: 'read',
              missing_path_behavior: 'skip',
            },
          ],
        },
      },
    };
    await write([ctx]);
    assert.deepEqual(await readOriginalQuestionPermissions(home, path, 'thread', 'original-turn'), {
      ...policy(),
      cwd: '/workspace/only',
    });
    ctx.permission_profile.file_system.entries.push({
      path: { type: 'path', path: '/workspace/only/private' },
      access: 'read',
      missing_path_behavior: 'skip',
    });
    await write([ctx]);
    assert.equal(
      await readOriginalQuestionPermissions(home, path, 'thread', 'original-turn'),
      undefined,
    );
  }));
