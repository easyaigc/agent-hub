import assert from 'node:assert/strict';
import { test } from 'node:test';
import { verifyIOSResultDevice } from './ios-result-device.mjs';

const destination = (deviceId) => ({
  device: { deviceId, deviceName: 'Same iPhone name', modelName: 'iPhone', platform: 'iOS' },
});

test('a successful test on a same-name second phone cannot satisfy the requested phone', () => {
  const summary = {
    passedTests: 1,
    failedTests: 0,
    devicesAndConfigurations: [destination('other-phone')],
  };
  assert.throws(() => verifyIOSResultDevice(summary, 'requested-phone'), /different device/);
});

test('every test configuration must belong to the requested device', () => {
  assert.throws(
    () =>
      verifyIOSResultDevice(
        {
          devicesAndConfigurations: [destination('requested-phone'), destination('other-phone')],
        },
        'requested-phone',
      ),
    /different device/,
  );
  const verified = verifyIOSResultDevice(
    {
      devicesAndConfigurations: [destination('requested-phone'), destination('requested-phone')],
    },
    'requested-phone',
  );
  assert.equal(verified.length, 2);
  assert(verified.every((device) => device.deviceId === 'requested-phone'));
  assert(verified.every((device) => !('deviceName' in device)));
});

test('missing device evidence cannot become an acceptance pass', () => {
  for (const summary of [{}, { devicesAndConfigurations: [] }, { devicesAndConfigurations: [{}] }])
    assert.throws(() => verifyIOSResultDevice(summary, 'requested-phone'));
});
