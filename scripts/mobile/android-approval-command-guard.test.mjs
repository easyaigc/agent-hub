import assert from 'node:assert/strict';
import { test } from 'node:test';
import { assertExactApprovalCommand } from './android-approval-command-guard.mjs';

function original() {
  const toolMarker = 'ANDROID_P4_APPROVAL_ab12cd34_ALLOW_TOOL';
  const threadId = 'owned-thread';
  const turnId = 'owned-turn';
  const command = `/bin/zsh -lc "printf '${toolMarker}'"`;
  return {
    threadId,
    turnId,
    projectRoot: '<owned-project-root>',
    toolMarker,
    request: {
      id: 137,
      method: 'item/commandExecution/requestApproval',
      params: { threadId, turnId, command, cwd: '<owned-project-root>' },
    },
    approval: {
      id: threadId + ':137',
      threadId,
      turnId,
      kind: 'approval',
      title: command,
      questions: [],
    },
  };
}

test('the exact observed Mac printf request is scoped before a native approval lease', () => {
  const value = original();
  const proof = assertExactApprovalCommand(value);
  assert.equal(proof.requestId, '137');
  assert.equal(proof.cwdVerified, true);
  assert.match(proof.commandSha256, /^[a-f0-9]{64}$/);
  value.request.id = 'wire-id';
  value.approval.id = 'owned-thread:wire-id';
  delete value.request.params.cwd;
  assert.equal(assertExactApprovalCommand(value).cwdVerified, false);
});

for (const decision of ['DENY', 'CANCEL', 'SESSION']) {
  test(`${decision} examiner command must retain the same exact marker and original target`, () => {
    const value = original();
    value.toolMarker = value.toolMarker.replace('_ALLOW_', `_${decision}_`);
    value.request.params.command = `/bin/zsh -lc "printf '${value.toolMarker}'"`;
    value.approval.title = value.request.params.command;
    assertExactApprovalCommand(value);
    value.request.params.command += ' && printf extra';
    assert.throws(() => assertExactApprovalCommand(value));
  });
}

for (const [name, change] of [
  ['another request family', (v) => (v.request.method = 'item/fileChange/requestApproval')],
  ['another original thread', (v) => (v.request.params.threadId = 'other-thread')],
  ['another original turn', (v) => (v.request.params.turnId = 'other-turn')],
  ['another project directory', (v) => (v.request.params.cwd = '<other-project-root>')],
  ['a request ID mismatch', (v) => (v.approval.id = 'owned-thread:138')],
  ['a displayed command mismatch', (v) => (v.approval.title = 'printf another-marker')],
  ['a different command', (v) => (v.request.params.command = 'echo ' + v.toolMarker)],
  ['an appended shell action', (v) => (v.request.params.command += '; touch unowned-file')],
  ['an appended newline action', (v) => (v.request.params.command += '\ncat unowned-file')],
  ['a model-expanded marker', (v) => (v.toolMarker += "' ; exit 1")],
  [
    'a shell wrapper change',
    (v) => (v.request.params.command = v.approval.title.replace('-lc', '-c')),
  ],
  ['an unrelated question', (v) => v.approval.questions.push({ id: 'unrelated' })],
]) {
  test(name + ' cannot acquire command approval authority', () => {
    const value = original();
    change(value);
    assert.throws(() => assertExactApprovalCommand(value));
  });
}

test('a rejected command is not echoed into examiner errors', () => {
  const value = original();
  const privateText = 'SYNTHETIC_PRIVATE_COMMAND_VALUE';
  value.request.params.command = privateText;
  assert.throws(
    () => assertExactApprovalCommand(value),
    (error) => {
      assert(!error.message.includes(privateText));
      return true;
    },
  );
});
