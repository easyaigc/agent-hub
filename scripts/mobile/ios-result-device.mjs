import assert from 'node:assert/strict';

// A display name is not a device identity: two paired phones may share it.
export function verifyIOSResultDevice(summary, expectedDeviceId) {
  assert(typeof expectedDeviceId === 'string' && expectedDeviceId.length > 0);
  const destinations = summary.devicesAndConfigurations;
  assert(Array.isArray(destinations) && destinations.length > 0, 'Missing XCTest device evidence');
  for (const configuration of destinations) {
    assert.equal(
      configuration.device?.deviceId,
      expectedDeviceId,
      'XCTest result belongs to a different device; do not accept or deploy this run',
    );
  }
  return destinations.map(({ device }) => ({
    deviceId: device.deviceId,
    modelName: device.modelName,
    osVersion: device.osVersion,
    osBuildNumber: device.osBuildNumber,
    platform: device.platform,
    architecture: device.architecture,
  }));
}
