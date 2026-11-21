import copy
import importlib.util
from pathlib import Path
import unittest
import json
import os
import subprocess
import sys
import tempfile
import time

spec = importlib.util.spec_from_file_location('plan', Path(__file__).with_name('real-dev-generation-command-plan.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def fixture():
    u = lambda n: '00000000-0000-4000-8000-' + str(n).zfill(12)
    p = dict(version=1, scenario='q03', run=u(7), leaseId=u(8), controlHostId=u(9),
             startupMode='new-isolated-installation', isolatedTargetApproved=True, newBindingAuthorized=True,
             modelBudgetApproved=True, nativeAiSharingApproved=True, expiresAtEpochMs=100000,
             nativeOperationIdMode='journal-generated-once', onUnknown='query-original-only', provider='codex',
             target=dict(agentId='codex', hostId=u(9), workspaceId=u(9), runtimeId='desktop-runtime'),
             excludedCodingThreadIds=[u(i) for i in range(1, 7)], threadId=u(10), businessChatAuthorized=True, baselineTurnIds=[],
             endpoint='https://local.invalid', allowedEndpoints=['https://local.invalid'],
             settings=dict(model='gpt-6.1-sol', effort='xhigh', permissionMode='full-access'),
             maxTextSubmissions=1, maxChildStarts=0, expectedFinalMarker='owned-marker')
    for key in ('installationAndroidIdSha256', 'appSha256', 'runnerSha256', 'signerSha256', 'localMappingSha256',
                'certificateSha256', 'pairingFileSha256', 'pairingIntentSha256', 'desktopRuntimeFingerprint', 'desktopExecutableSha256', 'productDirectoryProofSha256', 'w2InstallationSourceSha256'):
        p[key] = '0'*64
    p['slots'] = dict(q03=dict(slotId=u(11), text='Owned once', textSha256=module.digest(b'Owned once'), attachments=[]))
    return p


def mapping_fixture(p):
    p['desktopExecutable'] = '/trusted/desktop'
    identity = dict(hostId=p['target']['hostId'], runtimeId=p['target']['runtimeId'], pid=1, uid=501,
                    startedAtText='Fri Nov 13 01:00:00 2026', executable=p['desktopExecutable'], device='17', inode='33', size='71',
                    executableSha256=p['desktopExecutableSha256'], socket='/fixture/.codex/ipc/ipc.sock', socketDevice='17', socketInode='34')
    p['desktopRuntimeFingerprint'] = module.digest(json.dumps(identity, separators=(',', ':')).encode())
    mapping = dict(version=1, sourceAuthority='independent-local-desktop-and-host', controlHostId=p['controlHostId'],
                   actualTarget=copy.deepcopy(p['target']), threadId=p['threadId'], endpoint=p['endpoint'], expiresAtEpochMs=p['expiresAtEpochMs'],
                   environment=dict(identity=identity, fingerprint=p['desktopRuntimeFingerprint'], threadId=p['threadId'],
                                    originalOwnerVerified=True, processAndInstallVerified=True))
    return mapping


class Boundaries(unittest.TestCase):
    def test_frozen_local_once_plan(self):
        module.validate(fixture(), now_ms=0)

    def reject(self, edit):
        p = fixture(); edit(p)
        with self.assertRaises((ValueError, KeyError)):
            module.validate(p, now_ms=0)

    def test_coding_thread_never_business(self):
        self.reject(lambda p: p.update(threadId=p['excludedCodingThreadIds'][0]))

    def test_unknown_cannot_retry(self):
        self.reject(lambda p: p.update(onUnknown='send-again'))

    def test_host_speed_never_implicit(self):
        for field in ('speed', 'serviceTier', 'service_tier'):
            self.reject(lambda p: p['settings'].update({field: 'fast'}))

    def test_body_cannot_change_under_hash(self):
        self.reject(lambda p: p['slots']['q03'].update(text='Changed'))

    def test_attachment_and_extra_input_rejected(self):
        self.reject(lambda p: p['slots']['q03'].update(attachments=['unexpected']))
        self.reject(lambda p: p['slots'].update(extra=copy.deepcopy(p['slots']['q03'])))

    def test_ui_id_must_not_be_spoofed(self):
        self.reject(lambda p: p['slots']['q03'].update(operationId='pretend-native-id'))

    def test_expired_or_unapproved_budget(self):
        self.reject(lambda p: p.update(expiresAtEpochMs=1))
        self.reject(lambda p: p.update(modelBudgetApproved=False))

    def test_remote_target_rejected(self):
        p = fixture(); mapping = mapping_fixture(p); p['target']['hostId'] = 'unmapped-provider'
        with self.assertRaises(ValueError): module.validate_mapping(p, mapping)

    def test_q04_window_and_distinct_bodies(self):
        p = fixture(); p.update(scenario='q04', maxTextSubmissions=5, maxToolSeconds=45,
                                toolCommandSha256=module.digest(module.COMMAND.encode()), modelToolWorkingDirectory='/task-empty-workdir')
        p['slots'] = {}
        for i, name in enumerate(module.SLOTS['q04']):
            body = name + ': ' + (module.COMMAND + ' workdir=/task-empty-workdir' if name in ('origin', 'stopOrigin') else 'No new tools')
            p['slots'][name] = dict(slotId='00000000-0000-4000-8000-' + str(20+i).zfill(12),
                                    text=body, textSha256=module.digest(body.encode()), attachments=[], toolWorkdir='/task-empty-workdir' if name in ('origin', 'stopOrigin') else None)
        module.validate(p, now_ms=0)
        long_window = copy.deepcopy(p); long_window['maxToolSeconds'] = 90
        with self.assertRaises(ValueError): module.validate(long_window, now_ms=0)
        under_budget = copy.deepcopy(p); under_budget['maxToolSeconds'] = 39
        with self.assertRaises(ValueError): module.validate(under_budget, now_ms=0)
        duplicate = copy.deepcopy(p)
        duplicate['slots']['queueExecute'].update(text=duplicate['slots']['queueCancel']['text'],
                                                  textSha256=duplicate['slots']['queueCancel']['textSha256'])
        with self.assertRaises(ValueError): module.validate(duplicate, now_ms=0)

    def test_freeze_cannot_overwrite_prior_intent(self):
        with tempfile.TemporaryDirectory(prefix='w3-plan-boundary-') as folder:
            root = Path(folder).resolve(); os.chmod(root, 0o700)
            source = root / 'draft.json'; output = root / 'frozen.json'; mapping_path = root / 'mapping.json'
            p = fixture(); p['expiresAtEpochMs'] = int(time.time()*1000) + 120000
            mapping = mapping_fixture(p); mapping_bytes = json.dumps(mapping).encode(); mapping_path.write_bytes(mapping_bytes); os.chmod(mapping_path, 0o600); p['localMappingSha256'] = module.digest(mapping_bytes)
            source.write_text(json.dumps(p)); os.chmod(source, 0o600)
            command = [sys.executable, str(Path(__file__).with_name('real-dev-generation-command-plan.py')),
                       'freeze', '--input', str(source), '--mapping', str(mapping_path), '--output', str(output)]
            first = subprocess.run(command, capture_output=True)
            self.assertEqual(first.returncode, 0, first.stderr)
            original = output.read_bytes()
            second = subprocess.run(command, capture_output=True)
            self.assertNotEqual(second.returncode, 0)
            self.assertEqual(output.read_bytes(), original)
            self.assertEqual(output.stat().st_mode & 0o777, 0o600)

    def test_provider_control_split_needs_exact_mapping(self):
        p = fixture(); p['target'].update(hostId='00000000-0000-4000-8000-000000000099', workspaceId='independent-dev-workspace')
        mapping = mapping_fixture(p)
        self.assertNotEqual(p['controlHostId'], p['target']['hostId'])
        self.assertNotEqual(p['desktopExecutableSha256'], p['desktopRuntimeFingerprint'])
        module.validate(p, now_ms=0); module.validate_mapping(p, mapping)
        wrong = copy.deepcopy(mapping); wrong['controlHostId'] = 'other-control'
        with self.assertRaises(ValueError): module.validate_mapping(p, wrong)
        wrong = copy.deepcopy(mapping); wrong['actualTarget']['hostId'] = 'other-provider'
        with self.assertRaises(ValueError): module.validate_mapping(p, wrong)

    def test_control_budget_not_free_or_recursive(self):
        b = module.budget(True)
        self.assertEqual(b['q03']['childStarts'], 1)
        self.assertEqual(b['q04']['textSubmissions'], 5)
        self.assertLessEqual(b['q04']['toolSecondsPerOrigin'], 45)
        self.assertEqual(b['q04']['resumeRestartReplay'], 0)
        self.assertFalse(b['executeNow'])


if __name__ == '__main__':
    unittest.main()
