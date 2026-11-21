import assert from 'node:assert/strict';
import { test } from 'node:test';
import { ESLint } from 'eslint';

const eslint = new ESLint();
const messages = async (source, filePath) =>
  (await eslint.lintText(source, { filePath }))[0].messages;

test('CommonJS artwork scripts can use Node APIs while unknown names still fail', async () => {
  const file = 'apps/ios/AppStore/screenshots/fixture/render.cjs';
  assert.deepEqual(
    await messages(
      "const crypto = require('node:crypto'); console.log(crypto, __dirname, process.pid, Buffer.alloc(0));",
      file,
    ),
    [],
  );
  assert((await messages('missingArtworkInput();', file)).some((m) => m.ruleId === 'no-undef'));
  assert(
    (await messages('var local = 1; var local = 2; console.log(local);', file)).some(
      (m) => m.ruleId === 'no-redeclare',
    ),
  );
});

test('ESM and TypeScript continue to reject CommonJS imports', async () => {
  for (const file of ['scripts/fixture.mjs', 'apps/host-agent/src/fixture.ts']) {
    assert(
      (await messages("const fs = require('node:fs'); console.log(fs);", file)).some(
        (m) => m.ruleId === '@typescript-eslint/no-require-imports',
      ),
    );
  }
});
