import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { test } from 'node:test';

// Exercise the actual pure production functions without starting a Host, reading
// desktop history, or requiring a workspace build. The override lets an isolated
// integration candidate run the same assertions before it is adopted.
const source = readFileSync(
  process.env.DESKTOP_PLAN_EXPIRY_SOURCE ||
    new URL('../packages/codex-adapter/src/desktop-ipc.ts', import.meta.url),
  'utf8',
);
const runtime = readFileSync(
  new URL('../packages/codex-adapter/src/runtime.ts', import.meta.url),
  'utf8',
);
function exportedFunction(text, name) {
  const start = text.indexOf(`export function ${name}(`);
  assert(start >= 0, `Missing production function ${name}`);
  const end = text.indexOf('\n}\n', start);
  assert(end > start, `Missing production function boundary for ${name}`);
  return text.slice(start, end + 2);
}
const helperStart = runtime.indexOf('export const object =');
const helperEnd = runtime.indexOf('\n\n', helperStart);
assert(helperStart >= 0 && helperEnd > helperStart, 'Missing production wire helpers');
const helpers = runtime.slice(helperStart, helperEnd);
const moduleSource = stripTypeScriptTypes(
  [
    helpers,
    exportedFunction(source, 'desktopTurns'),
    exportedFunction(source, 'desktopProposedPlan'),
  ].join('\n'),
);
const { desktopProposedPlan } = await import(
  'data:text/javascript,' + encodeURIComponent(moduleSource)
);
const sha = (text) => createHash('sha256').update(text).digest('hex');

const request = (extra = {}) => ({
  id: 'plan-original',
  method: 'item/plan/requestImplementation',
  params: {
    threadId: 'thread-original',
    turnId: 'turn-original',
    planContent: 'original plan\nsecond line',
  },
  ...extra,
});
const state = (requests = [request()]) => ({
  id: 'thread-original',
  threadRuntimeStatus: { type: 'idle' },
  turns: [{ turnId: 'turn-original', status: 'completed', items: [] }],
  requests,
});
const expected = {
  id: 'plan-original',
  turnId: 'turn-original',
  text: 'original plan\nsecond line',
};
const synthetic = (completed) => ({
  id: 'synthetic-plan',
  type: 'planImplementation',
  turnId: 'turn-original',
  planContent: 'synthetic original',
  isCompleted: completed,
});

const cases = [
  [
    'pending legacy plan preserves its original ID, Turn and full body',
    () => state([request({ completed: false })]),
    expected,
  ],
  ['legacy plan without a completion marker remains pending', () => state(), expected],
  [
    'desktop-first completion expires the original plan',
    () => state([request({ completed: true })]),
    undefined,
  ],
  [
    'completing the latest plan cannot resurrect an older unresolved request',
    () => state([request({ id: 'older' }), request({ completed: true })]),
    undefined,
  ],
  [
    'a request for another thread cannot replace the original plan',
    () =>
      state([
        request(),
        request({
          params: { threadId: 'other', turnId: 'turn-original', planContent: 'other' },
        }),
      ]),
    expected,
  ],
  [
    'a request for another Turn cannot replace the original plan',
    () =>
      state([
        request(),
        request({
          params: { threadId: 'thread-original', turnId: 'other', planContent: 'other' },
        }),
      ]),
    expected,
  ],
  [
    'an active runtime cannot offer legacy plan confirmation',
    () => ({ ...state(), threadRuntimeStatus: { type: 'active' } }),
    undefined,
  ],
  [
    'a running Turn cannot offer plan confirmation',
    () => {
      const value = state();
      value.turns[0].status = 'inProgress';
      return value;
    },
    undefined,
  ],
  [
    'a missing current Turn cannot offer confirmation',
    () => ({ ...state(), turns: [] }),
    undefined,
  ],
  [
    'a pending synthetic plan takes precedence with its original body',
    () => {
      const value = state();
      value.turns[0].items = [synthetic(false)];
      return value;
    },
    { id: 'synthetic-plan', turnId: 'turn-original', text: 'synthetic original' },
  ],
  [
    'a completed synthetic plan cannot resurrect a legacy request',
    () => {
      const value = state();
      value.turns[0].items = [synthetic(true)];
      return value;
    },
    undefined,
  ],
  [
    'a synthetic plan from another Turn cannot authorize confirmation',
    () => {
      const value = state();
      value.turns[0].items = [{ ...synthetic(false), turnId: 'other' }];
      return value;
    },
    undefined,
  ],
  [
    'canonical Turn history uses the same original pending request',
    () => {
      const value = state();
      value.turnHistory = {
        kind: 'canonical',
        history: {
          entitiesByKey: { one: value.turns[0] },
          islands: [{ entries: [{ value: 'one' }] }],
        },
      };
      delete value.turns;
      return value;
    },
    expected,
  ],
  [
    'a newer completed Turn expires the prior plan request',
    () => {
      const value = state();
      value.turns.push({ turnId: 'later', status: 'completed', items: [] });
      return value;
    },
    undefined,
  ],
];

for (const [index, [name, build, result]] of cases.entries()) {
  test(name, (t) => {
    if (index === 0)
      t.diagnostic(`production source SHA256=${sha(source)}; wire helpers SHA256=${sha(helpers)}`);
    const input = build();
    const original = structuredClone(input);
    assert.deepEqual(desktopProposedPlan(input), result);
    assert.deepEqual(input, original, 'Projection must preserve original request evidence');
  });
}
