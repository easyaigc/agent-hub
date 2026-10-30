import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { createServer } from 'node:http';
import { setTimeout as delay } from 'node:timers/promises';
import { Worker } from 'node:worker_threads';
import test from 'node:test';
import {
  DirectBonjourPublisher,
  directBonjourInstanceName,
} from '../apps/host-agent/dist/direct-bonjour.js';

const target = {
  name: '测试电脑'.repeat(30),
  hostId: '88be8046-11f4-4eb0-baff-ccf36ea81491',
  port: 8796,
};
const until = async (condition) => {
  for (let i = 0; i < 100; i++) {
    if (condition()) return;
    await delay(10);
  }
  assert.fail('Publication lifecycle did not settle');
};
class ControlledWorker extends EventEmitter {
  sent = [];
  terminated = 0;
  postMessage(message) {
    this.sent.push(message);
  }
  terminate() {
    this.terminated++;
    return new Promise(() => {});
  }
  unref() {}
}

test('instance labels fit DNS UTF-8 bytes and vary independently of the machine target', () => {
  const a = directBonjourInstanceName(target, '00112233');
  const b = directBonjourInstanceName(target, '44556677');
  assert(Buffer.byteLength(a) <= 63);
  assert(a.endsWith('-00112233-8796'));
  assert.notEqual(a, b);
  assert.equal(Buffer.from(a).toString(), a);
  assert(!directBonjourInstanceName({ ...target, name: 'bad.name\n' }, '00112233').includes('.'));
  assert.throws(() => directBonjourInstanceName({ ...target, port: 0 }, '00112233'));
});

test('inactive publication waits for worker exit, changes hint only, and fences late results', async () => {
  const workers = [],
    inputs = [],
    reports = [];
  const publisher = new DirectBonjourPublisher(
    target,
    (value) => {
      reports.push(value);
      throw Error('sink');
    },
    {
      createWorker(input) {
        const worker = new ControlledWorker();
        workers.push(worker);
        inputs.push(input);
        return worker;
      },
      retryDelayMs: 5,
      shutdownGraceMs: 1000,
    },
  );
  publisher.start();
  workers[0].emit('message', {
    instanceName: inputs[0].instanceName,
    phase: 'failed',
    code: 'SECRET_SENTINEL',
  });
  assert.deepEqual(workers[0].sent, [{ kind: 'close' }]);
  await delay(20);
  assert.equal(workers.length, 1, 'a submitted stop is not confirmed retirement');
  workers[0].emit('exit', 0);
  await until(() => workers.length === 2);
  assert.notEqual(inputs[0].instanceName, inputs[1].instanceName);
  assert.equal(inputs[1].hostId, target.hostId);
  assert.equal(inputs[1].port, target.port);
  assert.equal(inputs[1].dnsHost, inputs[0].dnsHost);
  workers[0].emit('message', { instanceName: inputs[0].instanceName, phase: 'announced-local' });
  assert.equal(publisher.snapshot().phase, 'starting');
  workers[1].emit('message', { instanceName: inputs[1].instanceName, phase: 'announced-local' });
  assert.equal(publisher.snapshot().phase, 'announced-local');
  assert.equal(publisher.snapshot().localPublicationOnly, true);
  assert(!JSON.stringify(reports).includes('SECRET_SENTINEL'));
  const closed = publisher.close();
  workers[1].emit('exit', 0);
  await closed;
  await delay(20);
  assert.equal(workers.length, 2);
  assert.equal(publisher.snapshot().phase, 'closed');
});

test('an unconfirmed worker termination never starts another publisher', async () => {
  const worker = new ControlledWorker();
  worker.terminate = () => {
    worker.terminated++;
    return Promise.reject(Error('SECRET_STOP_DETAIL'));
  };
  let count = 0,
    input;
  const publisher = new DirectBonjourPublisher(target, undefined, {
    createWorker(value) {
      count++;
      input = value;
      return worker;
    },
    retryDelayMs: 5,
    shutdownGraceMs: 5,
  });
  publisher.start();
  worker.emit('message', { instanceName: input.instanceName, phase: 'failed', code: 'EADDRINUSE' });
  await until(() => publisher.snapshot().phase === 'stop-unconfirmed');
  assert.equal(count, 1);
  const closed = publisher.close();
  worker.emit('exit', 1);
  await closed;
  assert.equal(publisher.snapshot().phase, 'closed');
  assert(!JSON.stringify(publisher.snapshot()).includes('SECRET_STOP_DETAIL'));
});

test('an actual DNS worker crash does not close the independently owned HTTP listener', async () => {
  const server = createServer((_request, response) => response.end('still available'));
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  let starts = 0;
  const publisher = new DirectBonjourPublisher(target, undefined, {
    createWorker() {
      starts++;
      return new Worker("setTimeout(() => { throw Error('SECRET_NATIVE_DETAIL'); }, 5)", {
        eval: true,
      });
    },
    retryDelayMs: 1000,
  });
  try {
    publisher.start();
    await until(() => publisher.snapshot().phase === 'retry-wait');
    const response = await fetch(`http://127.0.0.1:${server.address().port}`);
    assert.equal(await response.text(), 'still available');
    assert.equal(starts, 1);
    assert.equal(publisher.snapshot().lastFailureCode, 'MDNS_WORKER_FAILED');
    assert(!JSON.stringify(publisher.snapshot()).includes('SECRET_NATIVE_DETAIL'));
  } finally {
    await publisher.close();
    await new Promise((resolve) => server.close(resolve));
  }
});
