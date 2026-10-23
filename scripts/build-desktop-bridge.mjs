import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

if (process.platform !== 'darwin') throw Error('Desktop UI bridge requires macOS and Xcode');
mkdirSync('.build', { recursive: true });
execFileSync(
  '/usr/bin/xcrun',
  [
    'swiftc',
    'apps/host-agent/native/DesktopCreationBridge.swift',
    '-o',
    '.build/desktop-creation-bridge',
  ],
  { stdio: 'inherit' },
);
console.log(
  JSON.stringify({
    compiled: true,
    helper: resolve('.build/desktop-creation-bridge'),
    realDesktopOperated: false,
  }),
);
