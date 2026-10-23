import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';

const root = new URL('../', import.meta.url);
const services = [
  { name: 'host-agent', variable: 'HOST_AGENT_PORT' },
  { name: 'relay', variable: 'RELAY_PORT' },
];

for (const service of services) {
  const child = spawn(
    process.execPath,
    [fileURLToPath(new URL(`apps/${service.name}/dist/index.js`, root))],
    {
      cwd: fileURLToPath(root),
      env: {
        ...process.env,
        [service.variable]: '0',
        [service.name === 'relay' ? 'RELAY_BIND_ADDRESS' : 'HOST_AGENT_BIND_ADDRESS']: '127.0.0.1',
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    },
  );
  const exited = once(child, 'exit');
  let diagnostics = '';
  child.stderr.setEncoding('utf8').on('data', (chunk) => (diagnostics += chunk));

  try {
    const address = await new Promise((resolve, reject) => {
      let output = '';
      const timeout = setTimeout(() => reject(new Error('Service startup timed out')), 10000);
      child.once('error', (error) => {
        clearTimeout(timeout);
        reject(error);
      });
      child.once('exit', (code) => {
        clearTimeout(timeout);
        reject(new Error(`Service exited before startup (${code}): ${diagnostics}`));
      });
      child.stdout.setEncoding('utf8').on('data', (chunk) => {
        output += chunk;
        if (output.includes('\n')) {
          clearTimeout(timeout);
          try {
            resolve(JSON.parse(output.split('\n')[0]));
          } catch (error) {
            reject(error);
          }
        }
      });
    });

    assert.equal(address.address, '127.0.0.1');
    const origin = `http://${address.address}:${address.port}`;
    const response = await fetch(`${origin}/healthz`, { signal: AbortSignal.timeout(5000) });
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), {
      service: service.name,
      status: 'ok',
      protocolVersion: '0.1',
      capabilities: ['health'],
    });
    const unavailable = await fetch(`${origin}/threads`, { signal: AbortSignal.timeout(5000) });
    assert.equal(unavailable.status, 404, 'Bootstrap must not advertise an implemented Codex API');
    console.log(`${service.name}: startup, health contract and unavailable route passed`);
  } finally {
    child.kill('SIGTERM');
    const forceStop = setTimeout(() => child.kill('SIGKILL'), 7000);
    await exited;
    clearTimeout(forceStop);
  }
}
