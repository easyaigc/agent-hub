import test from 'node:test';
import assert from 'node:assert/strict';
import {
  mkdtempSync,
  mkdirSync,
  readFileSync,
  writeFileSync,
  rmSync,
  renameSync,
  readdirSync,
  statSync,
} from 'node:fs';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  makePrivateDirectorySync,
  writePrivateFileSync,
} from '../packages/codex-adapter/dist/index.js';
import { responseSuggestionsConfig } from './windows-response-suggestions-config.mjs';

const legacyBinding = '99a5bb6d-2166-4efe-b5d0-f0a6a4fa40c0';
const legacyUrl = (port) => `http://localhost:${port}/_agent_hub/${legacyBinding}/backend-api`;
function seedLegacyUrl(state, target, original, port = 19001, newline = '\r\n') {
  const hasBom = original[0] === 0xef;
  const offset = hasBom ? 3 : 0;
  const contents = Buffer.concat([
    original.subarray(0, offset),
    Buffer.from(`openai_base_url = "${legacyUrl(port)}"${newline}`),
    original.subarray(offset),
  ]);
  writeFileSync(target, contents);
  const receipts = join(state, 'response-config-state');
  makePrivateDirectorySync(receipts);
  writePrivateFileSync(
    join(receipts, 'ownership.json'),
    JSON.stringify({ key: 'openai_base_url', bindingId: legacyBinding, applied: true }),
  );
}

test('the retired install command fails before touching any configuration', async () => {
  await assert.rejects(responseSuggestionsConfig({ operation: 'install' }), /retired/);
});

test(
  'Windows config ownership, preservation and conflicts',
  { skip: process.platform !== 'win32' },
  async () => {
    const root = mkdtempSync(join(tmpdir(), 'agent-hub-config-test-'));
    try {
      const state = join(root, 'private'),
        home = join(root, 'home');
      makePrivateDirectorySync(state);
      makePrivateDirectorySync(home);
      const privateConfig = join(state, 'config.json'),
        target = join(home, 'config.toml');
      writePrivateFileSync(
        privateConfig,
        JSON.stringify({
          version: 1,
          port: 19001,
          bindingId: '99a5bb6d-2166-4efe-b5d0-f0a6a4fa40c0',
        }),
        { flag: 'wx' },
      );
      const original = Buffer.from(
        '\ufeffmodel = "test"\r\nitems = [\r\n  "first",\r\n  "second",\r\n]\r\nnote = """first line\r\nsecond line"""\r\n[projects."D:\\\\work space"]\r\ntrust_level = "trusted"\r\n[features]\r\nfoo = true\r\n',
      );
      writeFileSync(target, original);
      const run = (operation) =>
        responseSuggestionsConfig({
          operation,
          configPath: privateConfig,
          codexHome: home,
        });
      await assert.rejects(run('install'), /retired/);
      assert.deepEqual(readFileSync(target), original);
      seedLegacyUrl(state, target, original);
      assert.equal((await run('status')).owned, true);
      assert.equal((await run('disable')).changed, true);
      assert.deepEqual(readFileSync(target), original);
      assert.equal((await run('disable')).changed, false);
      seedLegacyUrl(state, target, original);
      writeFileSync(target, Buffer.concat([readFileSync(target), Buffer.from('extra = true\r\n')]));
      await run('disable');
      assert.deepEqual(
        readFileSync(target),
        Buffer.concat([original, Buffer.from('extra = true\r\n')]),
      );
      writeFileSync(target, 'openai_base_url = "https://example.com"\n');
      assert.equal((await run('disable')).changed, false);
      assert.equal(readFileSync(target, 'utf8'), 'openai_base_url = "https://example.com"\n');
      writeFileSync(target, '"openai_base_url" = "https://example.com"\n');
      assert.equal((await run('disable')).changed, false);
      assert.equal(readFileSync(target, 'utf8'), '"openai_base_url" = "https://example.com"\n');
      writeFileSync(target, '[profiles."quoted profile"]\n"model_provider" = "other"\n');
      assert.equal((await run('disable')).changed, false);
      assert.equal(
        readFileSync(target, 'utf8'),
        '[profiles."quoted profile"]\n"model_provider" = "other"\n',
      );
      const moved = `model = "test"\nopenai_base_url = "${legacyUrl(19001)}"\n`;
      writeFileSync(target, moved);
      await assert.rejects(run('disable'), /Conflicting/);
      assert.equal(readFileSync(target, 'utf8'), moved);
      writeFileSync(target, `openai_base_url = "${legacyUrl(19001)}"\nmodel = "test"\n`);
      const unowned = readFileSync(target);
      await assert.rejects(run('disable'), /no ownership receipt/);
      assert.deepEqual(readFileSync(target), unowned);
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  },
);

test(
  'ordinary home and cross-volume private metadata with explicit no-change resolution',
  { skip: process.platform !== 'win32' },
  async () => {
    const cRoot = mkdtempSync(join(tmpdir(), 'agent-hub-config-home-'));
    const dRoot = mkdtempSync('D:/projects/agent-hub-app/.local/agent-hub-config-state-');
    try {
      const home = join(cRoot, 'ordinary-home'),
        state = join(dRoot, 'private');
      mkdirSync(home);
      makePrivateDirectorySync(state);
      assert.notEqual(statSync(home, { bigint: true }).dev, statSync(state, { bigint: true }).dev);
      const configPath = join(state, 'config.json'),
        target = join(home, 'config.toml');
      const bindingId = '99a5bb6d-2166-4efe-b5d0-f0a6a4fa40c0';
      writePrivateFileSync(configPath, JSON.stringify({ version: 1, port: 19001, bindingId }), {
        flag: 'wx',
      });
      const original = Buffer.from('model = "test"\r\n');
      seedLegacyUrl(state, target, original);
      const options = { configPath, codexHome: home };
      assert.equal(
        (await responseSuggestionsConfig({ ...options, operation: 'disable' })).changed,
        true,
      );
      assert.deepEqual(readFileSync(target), original);
      const id = '6f545f3b-5c0d-45f1-8b5b-cef3e5f96c31';
      const receipt = join(state, 'response-config-state', `${id}.json`);
      writePrivateFileSync(
        receipt,
        JSON.stringify({
          version: 1,
          bindingId,
          applied: false,
          beforeSha256: createHash('sha256').update(original).digest('hex'),
          stage: 'prepare',
        }),
        { flag: 'wx' },
      );
      await assert.rejects(
        responseSuggestionsConfig({ ...options, operation: 'status' }),
        /Unresolved/,
      );
      const legacyPrepared = join(home, `.agent-hub-${id}.tmp`);
      writeFileSync(legacyPrepared, 'pending');
      await assert.rejects(
        responseSuggestionsConfig({ ...options, operation: 'inspect-no-change', receiptId: id }),
        /conditions/,
      );
      rmSync(legacyPrepared);
      assert.deepEqual(
        await responseSuggestionsConfig({
          ...options,
          operation: 'inspect-no-change',
          receiptId: id,
        }),
        { noChange: true, resolved: false },
      );
      assert.deepEqual(
        await responseSuggestionsConfig({
          ...options,
          operation: 'resolve-no-change',
          receiptId: id,
        }),
        { noChange: true, resolved: true },
      );
      assert.equal(
        (await responseSuggestionsConfig({ ...options, operation: 'status' })).owned,
        false,
      );
      assert.deepEqual(readFileSync(target), original);
    } finally {
      rmSync(cRoot, { recursive: true, force: true });
      rmSync(dRoot, { recursive: true, force: true });
    }
  },
);

test(
  'conflicting replacement preserves the displaced version and blocks replay',
  { skip: process.platform !== 'win32' },
  async () => {
    const root = mkdtempSync(join(tmpdir(), 'agent-hub-config-race-'));
    try {
      const state = join(root, 'private'),
        home = join(root, 'home');
      makePrivateDirectorySync(state);
      makePrivateDirectorySync(home);
      const configPath = join(state, 'config.json'),
        target = join(home, 'config.toml');
      writePrivateFileSync(
        configPath,
        JSON.stringify({
          version: 1,
          port: 19002,
          bindingId: '99a5bb6d-2166-4efe-b5d0-f0a6a4fa40c0',
        }),
        { flag: 'wx' },
      );
      seedLegacyUrl(state, target, Buffer.from('model = "test"\n'), 19002, '\n');
      const concurrent = 'model = "test"\nconcurrent = true\n';
      const options = {
        operation: 'disable',
        configPath,
        codexHome: home,
      };
      await assert.rejects(
        responseSuggestionsConfig({
          ...options,
          testReplace: async ({ target, prepared, backup }) => {
            writeFileSync(target, concurrent);
            renameSync(target, backup);
            renameSync(prepared, target);
            return createHash('sha256').update(concurrent).digest('hex');
          },
        }),
        (error) => /conflict/i.test(error.message) && /conflict/i.test(error.cause?.message),
      );
      const dir = join(home, '.agent-hub-response-staging', '99a5bb6d-2166-4efe-b5d0-f0a6a4fa40c0');
      const backup = readdirSync(dir).find((name) => name.endsWith('.backup'));
      assert.ok(backup);
      assert.equal(readFileSync(join(dir, backup), 'utf8'), concurrent);
      await assert.rejects(responseSuggestionsConfig(options), /Unresolved/);
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  },
);
