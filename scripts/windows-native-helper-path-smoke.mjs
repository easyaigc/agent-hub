import assert from 'node:assert/strict';
import childProcess from 'node:child_process';
import fs from 'node:fs';
import { syncBuiltinESMExports } from 'node:module';
import { tmpdir } from 'node:os';
import { dirname, join, relative, sep } from 'node:path';
import { installWindowsPrivateStateFixture } from './fixtures/windows-private-state-boundary.mjs';

// Real files and production helper/configuration code; MSIX realpath, launcher
// compilation/bootstrap ACL and the permission transport are synthetic.
// No private-state Worker/C# physical executable, desktop or task is exercised.
const native = fs.realpathSync.native;
const directory = native(fs.mkdtempSync(join(tmpdir(), 'native-helper-msix-')));
const exec = childProcess.execFileSync;
const previous = { LOCALAPPDATA: process.env.LOCALAPPDATA, SystemRoot: process.env.SystemRoot };
const local = join(directory, 'user/AppData/Local');
const logicalRoot = join(local, 'CodexManage/native-helpers');
const physicalRoot = join(
  local,
  'Packages/OpenAI.Codex_2p2nqsd0c76g0/LocalCache/Local/CodexManage/native-helpers',
);
const bootstrapChecks = [];
const aliasChecks = [];
const permissions = [];
let first;
let second;
let packaged = true;
let deny;
const onPermissionCall = ({ action, path }) => {
  assert.equal(action, 'check', 'Alias recognition must not repair permissions');
  aliasChecks.push(path);
  if (path === deny)
    throw Object.assign(Error('unsafe fixture ACL'), { code: 'UNSAFE_WINDOWS_PRIVATE_STATE' });
};
try {
  process.env.LOCALAPPDATA = local;
  process.env.SystemRoot = join(directory, 'system');
  const compiler = join(process.env.SystemRoot, 'Microsoft.NET/Framework64/v4.0.30319/csc.exe');
  fs.mkdirSync(dirname(compiler), { recursive: true });
  fs.writeFileSync(compiler, 'synthetic compiler');
  fs.realpathSync.native = (path, ...options) => {
    if (packaged && typeof path === 'string' && path.startsWith(logicalRoot + sep)) {
      const physical = join(physicalRoot, relative(logicalRoot, path));
      fs.mkdirSync(dirname(physical), { recursive: true });
      fs.copyFileSync(path, physical);
      return native(physical, ...options);
    }
    return native(path, ...options);
  };
  childProcess.execFileSync = (file, args, options) => {
    assert.equal(options.windowsHide, true);
    if (file.endsWith('csc.exe')) {
      assert(args.includes('/target:winexe'), 'Only the launcher is compiled in this fixture');
      assert(args.at(-1).endsWith('host-launcher.cs'));
      fs.writeFileSync(args.find((a) => a.startsWith('/out:')).slice(5), 'synthetic helper');
      return '';
    }
    assert(file.endsWith('powershell.exe'), 'Unexpected native executable in synthetic fixture');
    assert(args[args.indexOf('-File') + 1].endsWith('private-state.ps1'));
    const action = args[args.indexOf('-Action') + 1];
    const path = args[args.indexOf('-Path') + 1];
    if (action === 'check') bootstrapChecks.push(path);
    return 'PRIVATE_STATE_OK';
  };
  syncBuiltinESMExports();
  const packagedURL = new URL(
    '../packages/codex-adapter/dist/desktop-platform.js?msix=packaged',
    import.meta.url,
  );
  permissions.push(
    installWindowsPrivateStateFixture(directory, packagedURL, { onCall: onPermissionCall }),
  );
  first = await import(packagedURL.href);
  const inside = first.windowsHostLauncherConfiguration();
  assert.equal(dirname(dirname(inside.executable)), native(physicalRoot));
  assert(inside.roots.includes(logicalRoot) && inside.roots.includes(native(physicalRoot)));
  assert.equal(
    first.windowsHostLauncherExecutable(),
    inside.executable,
    'Cache lost physical path',
  );
  assert(
    bootstrapChecks.includes(inside.executable),
    'Launcher bootstrap did not check its physical executable',
  );
  assert(
    bootstrapChecks.includes(join(logicalRoot, relative(physicalRoot, inside.executable))),
    'Launcher bootstrap did not check its logical executable',
  );
  assert(permissions[0].calls.length > 0, 'Packaged module bypassed its exact permission fixture');
  assert(aliasChecks.includes(inside.executable), 'Physical alias check did not reach its fixture');
  assert(
    aliasChecks.includes(dirname(inside.executable)),
    'Physical alias directory was not checked',
  );

  // An external scheduler/terminal sees the ordinary folder, not the packaged
  // overlay. Keep the old physical helper, remove only this fixture's overlay.
  packaged = false;
  fs.rmSync(logicalRoot, { recursive: true });
  const externalURL = new URL(
    '../packages/codex-adapter/dist/desktop-platform.js?msix=external',
    import.meta.url,
  );
  permissions.push(
    installWindowsPrivateStateFixture(directory, externalURL, { onCall: onPermissionCall }),
  );
  second = await import(externalURL.href);
  const outside = second.windowsHostLauncherConfiguration();
  assert.equal(dirname(dirname(outside.executable)), native(logicalRoot));
  assert(
    outside.roots.includes(native(physicalRoot)),
    'External caller lost the old physical root',
  );
  assert(permissions[1].calls.length > 0, 'External module bypassed its exact permission fixture');

  const foreign = join(
    local,
    'Packages/Other.Agent_publisher/LocalCache/Local/CodexManage/native-helpers',
  );
  const foreignFile = join(foreign, relative(logicalRoot, outside.executable));
  fs.mkdirSync(dirname(foreignFile), { recursive: true });
  fs.writeFileSync(foreignFile, 'unrelated helper');
  assert(!second.windowsHostLauncherConfiguration().roots.includes(foreign));
  assert(
    ![...bootstrapChecks, ...aliasChecks].some((p) => p.startsWith(foreign)),
    'Other package directories were scanned',
  );
  deny = inside.executable;
  assert.throws(
    () => second.windowsHostLauncherConfiguration(),
    /unsafe fixture ACL/,
    'Unsafe alias must fail closed',
  );
  console.log(
    JSON.stringify({
      result: 'passed',
      evidence: 'synthetic MSIX mapping, launcher compiler/bootstrap ACL and permission transport',
      physicalRegistrationPath: true,
      launcherBootstrapPhysicalAclCheck: true,
      aliasPermissionTransportFixture: true,
      privateStateWorkerPhysicalExecutable: 'not-covered',
      externalContextRecognizesVerifiedLegacy: true,
      foreignPackageExcluded: true,
      unsafeAliasRejected: true,
    }),
  );
} finally {
  try {
    first?.closeWindowsPrivateStateBackend();
  } finally {
    try {
      second?.closeWindowsPrivateStateBackend();
    } finally {
      for (const fixture of permissions.reverse()) fixture.restore();
      fs.realpathSync.native = native;
      childProcess.execFileSync = exec;
      syncBuiltinESMExports();
      for (const [key, value] of Object.entries(previous))
        if (value === undefined) delete process.env[key];
        else process.env[key] = value;
      fs.rmSync(directory, { recursive: true, force: true });
    }
  }
}
