import assert from 'node:assert/strict';
import { createHash, generateKeyPairSync } from 'node:crypto';
import { createRequire } from 'node:module';
import { cp, mkdir, mkdtemp, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { buildBusinessCrypto } from './task-hub-build.mjs';

const repository = fileURLToPath(new URL('..', import.meta.url));
const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
test('canonical compile is reproducible, self-contained and relocatable without node_modules', async () => {
  const root = await mkdtemp(join(tmpdir(), 'business-crypto-build-aux-'));
  try {
    const first = join(root, 'first'),
      second = join(root, 'second');
    const one = await buildBusinessCrypto({ repository, destination: first });
    const two = await buildBusinessCrypto({ repository, destination: second });
    assert.deepEqual(one, two);
    assert.equal(one.compiler.name, 'esbuild');
    assert.equal(one.sha256, hash(await readFile(join(first, one.entry))));
    for (const [source, sha] of Object.entries(one.sources)) {
      assert.equal(hash(await readFile(join(first, 'source', source))), sha);
      assert.equal(hash(await readFile(join(repository, source))), sha);
    }
    const moved = join(root, 'relocated');
    await cp(first, moved, { recursive: true });
    const require = createRequire(join(moved, 'package.json'));
    const { businessNotificationCipher } = require('./business-crypto.cjs');
    const key = generateKeyPairSync('x25519')
      .privateKey.export({ format: 'pem', type: 'pkcs8' })
      .toString();
    const cipher = businessNotificationCipher(key);
    assert.match(cipher.publicKey, /^[A-Za-z0-9+/]{43}=$/);
    cipher.validateRecipient(cipher.publicKey);
    await assert.rejects(buildBusinessCrypto({ repository, destination: first }), /already exists/);
    assert.equal(hash(await readFile(join(first, one.entry))), one.sha256);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
test('canonical source symlink is rejected before compiler loading', async () => {
  const root = await mkdtemp(join(tmpdir(), 'business-crypto-link-aux-'));
  try {
    const source = 'packages/notification-runtime/src/business-crypto.ts';
    await mkdir(dirname(join(root, source)), { recursive: true });
    await symlink(join(repository, source), join(root, source));
    await mkdir(join(root, 'packages/protocol/src'), { recursive: true });
    await writeFile(join(root, 'packages/protocol/src/index.ts'), 'export {};');
    await assert.rejects(
      buildBusinessCrypto({ repository: root, destination: join(root, 'output') }),
      /Invalid canonical business crypto source/,
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
