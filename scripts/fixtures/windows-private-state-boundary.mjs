import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import {
  closeSync,
  existsSync,
  fstatSync,
  lstatSync,
  mkdirSync,
  openSync,
  realpathSync,
} from 'node:fs';
import { registerHooks } from 'node:module';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';

const fixtures = new Map();

// Policy/service fixtures replace only the OS permission transport. Production
// file/type checks, discovery, configuration publication and auth stay intact.
// Real DACL/Worker behavior is covered by windows-private-state-native.test.mjs.
export function installWindowsPrivateStateFixture(directory, platformURL, { onCall } = {}) {
  assert(onCall === undefined || typeof onCall === 'function');
  const root = realpathSync(directory);
  const token = randomUUID();
  const calls = [];
  fixtures.set(token, { root, calls, onCall });
  const replacement =
    'data:text/javascript,' +
    encodeURIComponent(
      `import {createFixtureBackend} from ${JSON.stringify(import.meta.url)};
export {parseWindowsPrivateFileIdentity} from ${JSON.stringify(new URL('./desktop-private-state-native.js', platformURL).href)};
export const createWindowsPrivateStateBackend=(...args)=>createFixtureBackend(${JSON.stringify(token)},...args);`,
    );
  const hook = registerHooks({
    resolve(specifier, context, next) {
      if (
        context.parentURL === platformURL.href &&
        specifier === './desktop-private-state-native.js'
      )
        return { url: replacement, shortCircuit: true };
      return next(specifier, context);
    },
  });
  return {
    calls,
    restore() {
      hook.deregister();
      fixtures.delete(token);
    },
  };
}

// Called only by the module installed for one explicit desktop-platform URL.
export function createFixtureBackend(token, ...args) {
  assert.equal(args.length, 0, 'Permission fixture does not accept custom worker entries');
  const fixture = fixtures.get(token);
  assert(fixture, 'Permission fixture is no longer registered');
  let closed = false;
  const leases = new Set();
  return {
    get status() {
      return { closed, gone: closed };
    },
    call(action, path) {
      assert(!closed, 'Permission fixture is closed');
      assert(
        [
          'protect',
          'protect-file',
          'check',
          'create-directory',
          'create-file',
          'release-file',
        ].includes(action),
      );
      assert.equal(typeof path, 'string');
      if (action === 'release-file') {
        assert(leases.delete(path), 'Reservation must belong to this fixture channel');
        fixture.calls.push({ action, path });
        return;
      }
      // Canonicalize the existing ancestor before the containment check: macOS
      // /var may alias /private/var even for this fixture's own temporary root.
      const absolute = resolve(path);
      let ancestor = absolute;
      while (!existsSync(ancestor)) ancestor = dirname(ancestor);
      const canonical = resolve(realpathSync(ancestor), relative(ancestor, absolute));
      const requested = relative(fixture.root, canonical);
      assert(
        requested !== '..' && !requested.startsWith('..' + sep) && !isAbsolute(requested),
        'Permission fixture cannot access or create outside its disposable root',
      );
      if (action === 'create-directory') mkdirSync(path, { recursive: true, mode: 0o700 });
      let created;
      if (action === 'create-file') {
        const file = openSync(path, 'wx', 0o600);
        const stat = fstatSync(file, { bigint: true });
        const reservation = randomUUID().replaceAll('-', '');
        leases.add(reservation);
        created = {
          reservation,
          volume: stat.dev.toString(16).padStart(8, '0'),
          fileId: stat.ino.toString(16).padStart(16, '0'),
        };
        closeSync(file);
      }
      const info = lstatSync(path);
      assert(!info.isSymbolicLink(), 'Permission fixture cannot follow a link');
      assert(
        action === 'check'
          ? info.isDirectory() || info.isFile()
          : ['protect', 'create-directory'].includes(action)
            ? info.isDirectory()
            : info.isFile(),
      );
      const contained = relative(fixture.root, realpathSync(resolve(path)));
      assert(
        contained !== '..' && !contained.startsWith('..' + sep) && !isAbsolute(contained),
        'Permission fixture cannot access paths outside its disposable root',
      );
      fixture.calls.push({ action, path });
      // Observe or reject only a real, root-contained file/directory call.
      // The observer cannot mutate the fixture's recorded call.
      fixture.onCall?.(Object.freeze({ action, path }));
      return created;
    },
    async callAsync(action, path) {
      return this.call(action, path);
    },
    close() {
      closed = true;
      leases.clear();
    },
  };
}
