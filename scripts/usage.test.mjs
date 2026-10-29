import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFile, mkdtemp, writeFile, chmod, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { CodexHistoryReader } from '../packages/codex-adapter/dist/history-reader.js';
import Ajv from 'ajv';
import {
  normalizeUsageDecimal,
  parseUsageResponse,
  parseUsageTarget,
} from '../packages/protocol/dist/index.js';
import { mapCodexUsage, readCodexUsage } from '../packages/codex-adapter/dist/usage.js';
import {
  parseExactUsageJSON,
  mapEasyWorkUsage,
  readEasyWorkUsage,
} from '../packages/easywork-adapter/dist/index.js';
const vectors = JSON.parse(
  await readFile('packages/protocol/fixtures/mobile/usage-v1.json', 'utf8'),
);
const schema = JSON.parse(await readFile('packages/protocol/schemas/usage-v1.schema.json', 'utf8'));
const validate = new Ajv({ strict: true }).compile(schema);
const base = vectors.cases[0].response,
  clone = () => structuredClone(base),
  target = vectors.target,
  now = new Date(vectors.nowMillis);
test('shared usage wire vectors, schema and tolerant element boundary', () => {
  for (const row of vectors.cases) {
    assert.equal(parseUsageResponse(row.response, true).status, 'ok', row.name);
    assert.equal(
      validate(row.response),
      row.name !== 'unknown-v1-child',
      JSON.stringify(validate.errors),
    );
  }
});
test('known malformed data, envelope versions, duplicate children and size fail closed', () => {
  const bad = [
    (v) => (v.schemaVersion = 2),
    (v) => (v.target.account = {}),
    (v) => (v.snapshot.buckets[0].metrics[0].reading.usage.usedPercent = -1),
    (v) => (v.snapshot.buckets[0].metrics[0].reading.usage.usedPercent = Infinity),
    (v) => (v.snapshot.buckets[0].metrics[1].reading.amount = '01'),
    (v) => (v.snapshot.buckets[0].metrics[1].reading.amount = '-0'),
    (v) => v.snapshot.buckets[0].metrics.push(v.snapshot.buckets[0].metrics[0]),
    (v) => (v.snapshot.buckets[0].metrics[1].observedAt = '2026-02-30T00:00:00Z'),
    (v) => (v.snapshot.source.secret = 'no'),
    (v) => (v.snapshot.source.scopeLabel = 'x'.repeat(262145)),
    (v) => (v.snapshot.buckets[0].metrics[1].unit.code = '码'.repeat(30)),
  ];
  bad.push((v) => {
    v.snapshot.buckets[0].metrics.push({ id: 'unknown', kind: 'future-limit' });
  });
  bad.push((v) => {
    v.snapshot.buckets[0].metrics.push({
      ...v.snapshot.buckets[0].metrics[0],
      id: 'unknown',
      kind: 'future-limit',
    });
    v.snapshot.buckets[0].metrics[0].reading = { state: 'unavailable', reason: 'not-returned' };
  });
  for (const change of bad) {
    const v = clone();
    change(v);
    assert.throws(() => parseUsageResponse(v, true));
  }
  assert.throws(() => parseUsageTarget({ ...target, account: { authority: 'x', subject: 'y' } }));
  assert.equal(normalizeUsageDecimal('00062311.6799500000'), '62311.67995');
  assert.equal(normalizeUsageDecimal('-0.000'), '0');
  assert.equal(normalizeUsageDecimal('1.25e-3'), '0.00125');
  assert.throws(() => normalizeUsageDecimal('1e999'));
});
test('Codex slots use durations; absent 5h remains absent, credits survive hasCredits=false', () => {
  const result = mapCodexUsage(
    {
      rateLimitsByLimitId: {
        codex: {
          planType: 'pro',
          primary: { usedPercent: 9, windowDurationMins: 10080, resetsAt: 1800000000 },
          secondary: null,
          credits: { hasCredits: false, unlimited: true, balance: '9007199254740993.12500' },
        },
      },
    },
    target,
    'request',
    'scope',
    now,
  );
  assert.equal(result.status, 'ok');
  assert.equal(validate(result), true, JSON.stringify(validate.errors));
  const metrics = result.snapshot.buckets[0].metrics;
  assert.equal(metrics.length, 2);
  assert.equal(metrics[0].window.durationSeconds, 604800);
  assert.equal(metrics[1].reading.amount, '9007199254740993.125');
  assert.equal(metrics[1].reading.entitlement, 'unlimited');
  const multi = mapCodexUsage(
    {
      rateLimits: { primary: { usedPercent: 99, windowDurationMins: 300 } },
      rateLimitsByLimitId: {
        a: { primary: { usedPercent: 1, windowDurationMins: 60 } },
        b: { secondary: { usedPercent: 2, windowDurationMins: 1440 } },
      },
    },
    target,
    'request',
    'scope',
    now,
  );
  assert.equal(multi.snapshot.buckets.length, 2);
  assert.equal(multi.snapshot.buckets[0].metrics[0].window.durationSeconds, 3600);
});
test('Codex account switch during read invalidates sample without affecting any conversation', async () => {
  let accounts = 0;
  const methods = [];
  const result = await readCodexUsage(
    async (method) => {
      methods.push(method);
      return method === 'account/read'
        ? {
            account: {
              type: 'chatgpt',
              email: ++accounts === 1 ? 'first@example.invalid' : 'second@example.invalid',
            },
          }
        : { rateLimits: {} };
    },
    target,
    'request',
  );
  assert.equal(result.status, 'unavailable');
  assert.equal(result.reason, 'not-authenticated');
  assert.deepEqual(methods, ['account/read', 'account/rateLimits/read', 'account/read']);
});
test('EasyWork preserves raw numeric source lexemes, maps actual resource unit and available balance', () => {
  const raw =
    '{"balance":{"amount":9007199254740993.123456789012345678901234,"availableAmount":1.2300e2,"currency":"resource"},"membership":{"name":"Pro"},"text":' +
    JSON.stringify('escaped " 123') +
    '}';
  const parsed = parseExactUsageJSON(raw);
  assert.equal(parsed.balance.amount, '9007199254740993.123456789012345678901234');
  assert.equal(parsed.text, 'escaped " 123');
  const response = mapEasyWorkUsage(
    parsed,
    { ...target, agentId: 'easywork' },
    'request',
    'scope',
    now,
  );
  assert.equal(validate(response), true, JSON.stringify(validate.errors));
  const metric = response.snapshot.buckets[0].metrics[0];
  assert.equal(metric.unit.kind, 'compute');
  assert.equal(metric.reading.amount, '123');
  assert.equal(metric.expiresAt, null);
  const zero = mapEasyWorkUsage(
    { balance: { amount: '0', currency: 'USD' } },
    target,
    'request',
    'scope',
    now,
  );
  assert.equal(zero.snapshot.buckets[0].metrics[0].reading.amount, '0');
});
test('EasyWork only makes the fixed authenticated GET and rejects source changes or oversized data', async () => {
  let count = 0;
  const auth = {
    async ensurePlatformAccessContext() {
      return {
        platformBaseUrl: 'https://platform.example.invalid/api',
        accessToken: 'test-token',
        userId: ++count > 1 ? 'other' : 'fixture',
      };
    },
  };
  const result = await readEasyWorkUsage(auth, target, 'request', async (url, options) => {
    assert.equal(
      url.href,
      'https://platform.example.invalid/api/workbench-platform/v1/account-summary',
    );
    assert.equal(options.redirect, 'error');
    assert.equal(options.method, undefined);
    return new Response('{"balance":{"amount":1,"currency":"resource"}}');
  });
  assert.equal(result.reason, 'not-authenticated');
  assert.throws(() => parseExactUsageJSON(' '.repeat(262145)));
});

test('metadata reader retains unsupported-method codes without exposing upstream error payloads', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'agent-usage-reader-'));
  const executable = join(directory, 'reader.mjs');
  await writeFile(
    executable,
    `#!/usr/bin/env node
import {createInterface} from 'node:readline';
createInterface({input:process.stdin}).on('line',line=>{const m=JSON.parse(line);if(m.id!==undefined)process.stdout.write(JSON.stringify(m.method==='initialize'?{id:m.id,result:{}}:{id:m.id,error:{code:-32601,message:'private upstream payload'}})+'\\n')});
`,
  );
  await chmod(executable, 0o700);
  const reader = new CodexHistoryReader('fixture-host', 'Fixture', executable);
  try {
    await reader.connect();
    await assert.rejects(
      reader.read('account/rateLimits/read', {}),
      (error) => error.code === -32601 && !error.message.includes('private upstream'),
    );
  } finally {
    reader.close();
    await rm(directory, { recursive: true, force: true });
  }
});
