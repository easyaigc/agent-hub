import { isMainThread } from 'node:worker_threads';
import { Session } from 'node:inspector';
import { createHash } from 'node:crypto';
import { readFileSync, realpathSync, writeFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

// Private, one-use diagnostic import for the existing Windows Host child.
// No socket is opened and no other process or Electron session is inspected.
if (isMainThread && process.platform === 'win32') {
  const directory = fileURLToPath(new URL('.', import.meta.url));
  const planPath = join(directory, 'host-cpu-profile-plan.private.json');
  if (existsSync(planPath)) {
    const plan = JSON.parse(readFileSync(planPath, 'utf8'));
    const fields = [
      'schemaVersion',
      'operationId',
      'hostId',
      'nodePath',
      'nodeSha256',
      'workerPath',
      'workerSha256',
      'configurationPath',
      'configurationSha256',
    ];
    const digest = (path) => createHash('sha256').update(readFileSync(path)).digest('hex');
    const canonical = (path) => realpathSync(path).toLowerCase();
    const valid =
      Object.keys(plan).length === fields.length &&
      Object.keys(plan).every((key) => fields.includes(key)) &&
      plan.schemaVersion === 1 &&
      /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(plan.operationId) &&
      /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(plan.hostId) &&
      process.argv.length === 4 &&
      process.argv[3] === '--host-child' &&
      canonical(process.execPath) === canonical(plan.nodePath) &&
      canonical(process.argv[1]) === canonical(plan.workerPath) &&
      canonical(process.argv[2]) === canonical(plan.configurationPath) &&
      digest(plan.nodePath) === plan.nodeSha256 &&
      digest(plan.workerPath) === plan.workerSha256 &&
      digest(plan.configurationPath) === plan.configurationSha256;
    if (valid) {
      const configuration = JSON.parse(readFileSync(plan.configurationPath, 'utf8'));
      const runtime = JSON.parse(
        readFileSync(join(configuration.environment.HOST_AGENT_STATE_DIR, 'runtime.json'), 'utf8'),
      );
      if (
        configuration.environment.CODEX_RUNTIME_MODE !== 'desktop-runtime' ||
        runtime.hostId !== plan.hostId
      )
        throw Error('HOST_CPU_PROFILE_TARGET_MISMATCH');
      const session = new Session();
      session.connect();
      const post = (method, parameters = {}) =>
        new Promise((resolve, reject) =>
          session.post(method, parameters, (error, result) =>
            error ? reject(error) : resolve(result),
          ),
        );
      const stamp = () => ({
        utc: new Date().toISOString(),
        monotonicNs: String(process.hrtime.bigint()),
        performanceNow: performance.now(),
        cpu: process.cpuUsage(),
        elu: performance.eventLoopUtilization(),
      });
      const before = stamp();
      const metadata = {
        schemaVersion: 1,
        operationId: plan.operationId,
        hostId: plan.hostId,
        pid: process.pid,
        scope: 'current Host child Main only',
        startedBefore: before,
      };
      const metaPath = join(directory, `host-cpu-profile-${process.pid}.meta.private.json`);
      const profilePath = join(directory, `host-cpu-profile-${process.pid}.cpuprofile`);
      const save = () =>
        writeFileSync(metaPath, JSON.stringify(metadata, null, 2), { mode: 0o600 });
      let ending = false;
      let poll;
      let maximum;
      const stop = async (reason) => {
        if (ending) return;
        ending = true;
        clearInterval(poll);
        clearTimeout(maximum);
        metadata.stopRequested = stamp();
        metadata.stopReason = reason;
        try {
          const { profile } = await post('Profiler.stop');
          const bytes = Buffer.from(JSON.stringify(profile));
          writeFileSync(profilePath, bytes, { mode: 0o600, flag: 'wx' });
          metadata.profile = {
            bytes: bytes.length,
            sha256: createHash('sha256').update(bytes).digest('hex'),
            nodeCount: profile.nodes.length,
            sampleCount: profile.samples?.length ?? 0,
            startTime: profile.startTime,
            endTime: profile.endTime,
          };
          metadata.stopped = stamp();
        } catch {
          metadata.error = 'HOST_CPU_PROFILE_STOP_FAILED';
        } finally {
          session.disconnect();
          save();
        }
      };
      await post('Profiler.enable');
      await post('Profiler.setSamplingInterval', { interval: 1000 });
      await post('Profiler.start');
      metadata.startedAfter = stamp();
      save();
      poll = setInterval(() => {
        try {
          const value = JSON.parse(
            readFileSync(join(directory, 'host-cpu-profile-stop.private.json'), 'utf8'),
          );
          if (Object.keys(value).length === 1 && value.operationId === plan.operationId)
            void stop('owned-stop-marker');
        } catch {
          /* An absent or mismatched marker cannot stop another run. */
        }
      }, 500);
      poll.unref();
      maximum = setTimeout(() => void stop('maximum-240-seconds'), 240000);
      maximum.unref();
    }
  }
}
