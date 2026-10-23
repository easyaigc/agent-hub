import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdir, mkdtemp, rm } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { generateKeyPairSync } from 'node:crypto';
import {
  NotificationObserver,
  sealNotification,
} from '../packages/notification-runtime/dist/index.js';

if (process.platform !== 'darwin')
  throw new Error('Native notification check requires macOS Swift and AVFoundation');
const root = resolve('.local/task-notifications');
await mkdir(root, { recursive: true });
const directory = await mkdtemp(join(root, 'native-'));
const env = {
  ...process.env,
  DEVELOPER_DIR: process.env.DEVELOPER_DIR || '/Applications/Xcode.app/Contents/Developer',
  CODEX_NOTIFICATION_TEST_DIRECTORY: directory,
};
try {
  const executable = join(directory, 'notification-native-check');
  execFileSync(
    'xcrun',
    [
      'swiftc',
      '-D',
      'DEBUG',
      'apps/ios/Sources/Localization.swift',
      'apps/ios/Sources/NotificationModels.swift',
      'apps/ios/Sources/NotificationHostMonitor.swift',
      'apps/ios/Sources/ThreadReading.swift',
      'apps/ios/Sources/NotificationSounds.swift',
      'apps/ios/Sources/EncryptedMigration.swift',
      'scripts/notification-native-smoke.swift',
      '-o',
      executable,
    ],
    { env, stdio: 'pipe' },
  );
  console.log(execFileSync(executable, { env, encoding: 'utf8' }).trim());
  execFileSync(
    'ffmpeg',
    [
      '-hide_banner',
      '-loglevel',
      'error',
      '-y',
      '-i',
      join(directory, 'input.wav'),
      '-codec:a',
      'libmp3lame',
      join(directory, 'input.mp3'),
    ],
    { env, stdio: 'pipe' },
  );
  console.log(
    execFileSync(executable, ['import', join(directory, 'input.mp3')], {
      env,
      encoding: 'utf8',
    }).trim(),
  );
  const host = generateKeyPairSync('x25519'),
    phone = generateKeyPairSync('x25519');
  const target = {
    agentId: 'easywork',
    hostId: 'native-host',
    workspaceId: 'native-workspace',
    runtimeId: 'native-runtime',
    account: { authority: 'https://account.example', subject: 'native-user' },
  };
  const observer = new NotificationObserver(target);
  const observation = {
    threadId: 'native-thread',
    title: 'Native check',
    live: true,
    runId: 'native-run',
    taskSummary: '原始任务摘要',
    requests: [
      {
        id: 'native-request',
        runId: 'native-run',
        kind: 'question',
        taskSummary: '请选择本次交付格式',
      },
    ],
    uncertainOperations: [],
    readTarget: target,
  };
  const question = observer.observe(observation).events[0];
  assert.equal(question.taskSummary, '请选择本次交付格式');
  const completed = observer
    .observe({
      ...observation,
      requests: [],
      outcome: 'completed',
      readPosition: { revision: 2, messageId: 'native-reply', contentVersion: 'a'.repeat(64) },
    })
    .events.find((value) => value.kind === 'completed');
  assert(completed?.readPosition);
  const uncertain = observer.observe({
    ...observation,
    requests: [],
    runId: 'later-run',
    outcome: 'unknown',
    taskSummary: '后来的任务不能成为原操作摘要',
    uncertainOperations: ['native-operation'],
    uncertainOperationDetails: [
      { id: 'native-operation', taskSummary: '核对已提交消息是否执行：优化通知' },
    ],
  }).events[0];
  assert.equal(uncertain.taskSummary, '核对已提交消息是否执行：优化通知');
  assert.equal(uncertain.operationId, 'native-operation');
  assert.equal(uncertain.runId, undefined);
  const raw = (key) =>
    key.publicKey.export({ format: 'der', type: 'spki' }).subarray(-32).toString('base64');
  for (const event of [question, completed, uncertain]) {
    const envelope = {
      version: 1,
      bindingId: 'native-binding',
      eventId: event.id,
      ...sealNotification(
        event,
        'native-binding',
        host.privateKey.export({ format: 'pem', type: 'pkcs8' }).toString(),
        raw(phone),
      ),
    };
    const input = {
      key: phone.privateKey
        .export({ format: 'der', type: 'pkcs8' })
        .subarray(-32)
        .toString('base64'),
      binding: {
        target,
        clientName: 'Native computer',
        publicKey: raw(host),
        relayURL: 'https://notifications.example',
        managementToken: 'fixture-token',
      },
      envelope,
    };
    const decoded = JSON.parse(
      execFileSync(executable, ['decrypt'], {
        env,
        input: JSON.stringify(input),
        encoding: 'utf8',
      }),
    );
    assert.deepEqual(decoded, event);
    assert.throws(() =>
      execFileSync(executable, ['decrypt'], {
        env,
        input: JSON.stringify({ ...input, envelope: { ...envelope, eventId: '0'.repeat(64) } }),
        stdio: ['pipe', 'pipe', 'pipe'],
      }),
    );
  }
  console.log(
    'Host Node → native CryptoKit: target, request identity, reading version and tamper rejection passed',
  );
} finally {
  await rm(directory, { recursive: true, force: true });
}
