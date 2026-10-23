/** Read actual persisted tool spans, never a model's narrative about an execution. */
export function persistedToolEvidence(rows, turnId) {
  let currentTurn;
  const calls = [];
  for (const row of rows) {
    if (row.type === 'turn_context') currentTurn = row.payload?.turn_id;
    const payload = row.payload;
    if (
      row.type === 'response_item' &&
      ['custom_tool_call', 'function_call'].includes(payload?.type) &&
      (payload.internal_chat_message_metadata_passthrough?.turn_id ?? currentTurn) === turnId
    )
      calls.push(payload);
  }
  const ids = new Set(calls.map((call) => call.call_id));
  const outputs = rows
    .filter(
      (row) =>
        row.type === 'response_item' &&
        ['custom_tool_call_output', 'function_call_output'].includes(row.payload?.type) &&
        ids.has(row.payload.call_id),
    )
    .map((row) => row.payload);
  const results = outputs.flatMap((output) =>
    (Array.isArray(output.output)
      ? output.output.filter((part) => part.type === 'input_text').map((part) => part.text)
      : [output.output]
    ).flatMap((text) => {
      try {
        const result = JSON.parse(text);
        return typeof result?.exit_code === 'number' && typeof result?.output === 'string'
          ? [result]
          : [];
      } catch {
        return [];
      }
    }),
  );
  return { calls, outputs, results };
}
/** A child's fork contains old parent contexts; those do not describe its own execution. */
export function ownChildContexts(rows, parentTurnIds) {
  return rows
    .filter(
      (row) =>
        row.type === 'turn_context' &&
        typeof row.payload?.turn_id === 'string' &&
        !parentTurnIds.has(row.payload.turn_id),
    )
    .map((row) => row.payload);
}

/** Interpret only an actual nonzero process result, including native interpreter errors. */
export function isPermissionDeniedResult(result) {
  return (
    Number.isSafeInteger(result?.exit_code) &&
    result.exit_code !== 0 &&
    typeof result.output === 'string' &&
    /EPERM|EACCES|denied|拒绝|Permission(?:Denied|Error)|Operation not permitted|UnauthorizedAccess/i.test(
      result.output,
    )
  );
}
