import assert from 'node:assert/strict';
import childProcess from 'node:child_process';
import { syncBuiltinESMExports } from 'node:module';

// Used by subprocess integration fixtures: inspect the actual launch boundary,
// then let the real child exercise framing, exit and operation semantics.
const spawn = childProcess.spawn;
childProcess.spawn = (executable, args, options) => {
  if (args?.[0] === 'app-server' || options?.stdio?.includes?.('ipc')) {
    assert.equal(options?.windowsHide, true, 'Background child must not allocate a console');
    assert.notEqual(options?.detached, true, 'Background child must retain its owner');
  }
  return spawn(executable, args, options);
};
syncBuiltinESMExports();
