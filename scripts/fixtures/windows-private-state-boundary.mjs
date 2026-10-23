import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { lstatSync, realpathSync } from 'node:fs';
import { registerHooks } from 'node:module';
import { isAbsolute, relative, resolve, sep } from 'node:path';

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
  return {
    get status() {
      return { closed, gone: closed };
    },
    call(action, path) {
      assert(!closed, 'Permission fixture is closed');
      assert(['protect', 'protect-file', 'check'].includes(action));
      assert.equal(typeof path, 'string');
      const info = lstatSync(path);
      assert(!info.isSymbolicLink(), 'Permission fixture cannot follow a link');
      assert(
        action === 'check'
          ? info.isDirectory() || info.isFile()
          : action === 'protect'
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
    },
    close() {
      closed = true;
    },
  };
}
