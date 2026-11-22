import { parseArgs } from 'node:util';
import { existsSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { resolve, dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const packaged = resolve(here, '../assets/codex-manage/apps/host-agent/dist');
const source = resolve(here, '../../../apps/host-agent/dist');
const compiled = existsSync(join(packaged, 'cpu-profile-control.js')) ? packaged : source;
const { values, positionals } = parseArgs({
  allowPositionals: true,
  options: {
    'state-dir': { type: 'string' },
    'host-id': { type: 'string' },
    entry: { type: 'string' },
    pid: { type: 'string' },
    'operation-id': { type: 'string' },
    seconds: { type: 'string' },
  },
});
try {
  if (!values['state-dir'] || positionals.length !== 1)
    throw new Error(
      'Usage: profile-host.mjs arm|disarm|status|start|receipt|stop --state-dir <private Host state> [--host-id UUID --entry <runtime-entry.js>] [--pid PID --operation-id UUID --seconds 30]',
    );
  if (!existsSync(join(compiled, 'cpu-profile-control.js')))
    throw new Error(
      'CPU_PROFILE_NOT_IN_THIS_PROGRAM: this version cannot collect JS from an existing Host; no SIGUSR1 or debug port will be opened',
    );
  const control = await import(pathToFileURL(join(compiled, 'cpu-profile-control.js')).href);
  const files = await import(pathToFileURL(join(compiled, 'cpu-profile-files.js')).href);
  const state = resolve(values['state-dir']);
  let result;
  switch (positionals[0]) {
    case 'arm':
      if (!values['host-id'] || !values.entry)
        throw new Error('arm requires --host-id and --entry');
      result = control.armCpuProfile(state, values['host-id'], resolve(values.entry));
      break;
    case 'status':
      result = control.cpuProfileStatus(state);
      break;
    case 'disarm':
      result = control.disarmCpuProfile(state, values['operation-id']);
      break;
    case 'start':
      result = control.requestCpuProfile(
        state,
        Number(values.pid),
        values['operation-id'],
        Number(values.seconds ?? 30) * 1000,
      );
      break;
    case 'receipt':
      if (!files.uuid(values['operation-id']))
        throw new Error('receipt requires --operation-id UUID');
      result = files.readPrivateJSON(
        join(files.profileDirectory(state), `receipt-${values['operation-id']}.json`),
      );
      break;
    case 'stop':
      result = control.stopCpuProfile(state, Number(values.pid));
      break;
    default:
      throw new Error('Unknown command');
  }
  console.log(JSON.stringify(result, null, 2));
} catch (error) {
  console.error(error instanceof Error ? error.message : 'CPU_PROFILE_FAILED');
  process.exitCode = 1;
}
