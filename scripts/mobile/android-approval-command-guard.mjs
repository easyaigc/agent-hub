import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

/** Mac native examiner only: a model request never expands the authorized printf case. */
export function assertExactApprovalCommand({
  request,
  approval,
  threadId,
  turnId,
  projectRoot,
  toolMarker,
}) {
  assert.match(toolMarker, /^ANDROID_P4_APPROVAL_[a-f0-9]{8}_(?:DENY|ALLOW|CANCEL|SESSION)_TOOL$/);
  assert.equal(request.method, 'item/commandExecution/requestApproval');
  assert(
    (typeof request.id === 'string' && request.id.length > 0) ||
      (typeof request.id === 'number' && Number.isSafeInteger(request.id)),
  );
  const params = request.params;
  const expected = `/bin/zsh -lc "printf '${toolMarker}'"`;
  assert.equal(params.threadId, threadId);
  assert.equal(params.turnId, turnId);
  assert(params.command === expected, 'Only the exact authorized printf command is approvable');
  if (params.cwd !== undefined) assert.equal(params.cwd, projectRoot);
  assert.equal(approval.id, threadId + ':' + String(request.id));
  assert.equal(approval.threadId, threadId);
  assert.equal(approval.turnId, turnId);
  assert.equal(approval.kind, 'approval');
  assert(approval.title === expected, 'The native projection must match the authorized command');
  assert(Array.isArray(approval.questions) && approval.questions.length === 0);
  return {
    method: request.method,
    requestId: String(request.id),
    commandSha256: createHash('sha256').update(expected).digest('hex'),
    cwdVerified: params.cwd !== undefined,
  };
}
