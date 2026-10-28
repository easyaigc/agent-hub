"""Synthetic lifecycle boundaries; no real notifier argv or process mutations."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location(
    'completion_observer', Path(__file__).with_name('desktop-completion-notify-observer.py'))
observer_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(observer_module)
m = observer_module


class FakeNative:
    def __init__(self):
        self.identities = {}
        self.paths = {}
        self.stats = {'childListCalls': 0, 'pidPathCalls': 0, 'pidIdentityCalls': 0,
                      'notifierArgvReads': 0, 'notifierArgvReadFailures': 0}
        self.after_argv = lambda: None
        self.add(10, 1, m.DESKTOP)
        self.add(20, 10, m.EXECUTOR)

    def add(self, pid, parent, path, started=100):
        self.identities[pid] = m.ProcessIdentity(pid, parent, os.getuid(), started, 123)
        self.paths[pid] = path

    def identity(self, pid):
        self.stats['pidIdentityCalls'] += 1
        return self.identities.get(pid)

    def path(self, pid):
        self.stats['pidPathCalls'] += 1
        return self.paths.get(pid)

    def children(self, pid):
        self.stats['childListCalls'] += 1
        return {identity.pid for identity in self.identities.values() if identity.ppid == pid}

    def ps(self, *_args, **_kwargs):
        return SimpleNamespace(stdout='\n'.join(
            '{} {} {}'.format(identity.pid, identity.ppid, self.paths[identity.pid])
            for identity in self.identities.values()))

    def _argv(self, _pid):
        result = [m.NOTIFIER, 'turn-ended', json.dumps({
            'type': 'agent-turn-complete',
            'thread-id': '11111111-1111-1111-1111-111111111111',
            'turn-id': '22222222-2222-2222-2222-222222222222',
            'client': 'Codex Desktop', 'cwd': '/synthetic',
            'input-messages': ['This is an app-generated request for a suggested next user message. test'],
            'last-assistant-message': '{"suggestion":"Synthetic prediction"}'})]
        self.after_argv()
        return result

    notifier_argv = m.Native.notifier_argv


class FakeQueue:
    def __init__(self, clock):
        self.clock = clock
        self.on_register = lambda _event: None
        self.on_wait = lambda: []
        self.closed = False

    def control(self, changes, _count, _timeout):
        if changes:
            for event in changes:
                self.on_register(event)
            return []
        self.clock[0] += 1
        return self.on_wait()

    def close(self):
        self.closed = True


class LifecycleTests(unittest.TestCase):
    def setUp(self):
        self.native = FakeNative()
        self.clock = [0]
        self.queue = FakeQueue(self.clock)
        self.output = io.StringIO()
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        for context in (
            patch.object(m, 'ROOT', Path('/synthetic')),
            patch.object(m, 'STREAM_ONLY', True),
            patch.object(m.select, 'kqueue', return_value=self.queue),
            patch.object(m.time, 'monotonic', side_effect=lambda: self.clock[0]),
            patch.object(m.subprocess, 'run', side_effect=self.native.ps),
            patch.object(m.Path, 'read_bytes', return_value=b'synthetic-config'),
            contextlib.redirect_stdout(self.output),
        ):
            self.stack.enter_context(context)
        self.observer = self.new_observer()

    def new_observer(self):
        parents, _, identities = m.discover(self.native)
        return m.Observer(self.native, parents, identities, m.sha256(b'synthetic-config'), 90)

    def watch(self):
        self.native.add(30, 20, m.NOTIFIER)
        self.observer.add_child(30, 20)

    def events(self):
        return [json.loads(line) for line in self.output.getvalue().splitlines()]

    def test_valid_notifier_keeps_execution_ids_and_unbound_source(self):
        self.watch()
        self.assertEqual(self.observer.report['suggestionCandidates'], 1)
        event = next(event for event in self.events() if event['phase'] == 'suggestion-observed')
        self.assertEqual(event['observation']['threadId'], '11111111-1111-1111-1111-111111111111')
        self.assertFalse(event['observation']['sourceBindingVerified'])

    def test_listener_reports_original_desktop_start_not_observer_start(self):
        self.queue.on_wait = lambda: self.observer.stop('test-complete') or []
        self.observer.run()
        event = self.events()[0]
        self.assertEqual(event['phase'], 'kqueue-listener-started')
        self.assertAlmostEqual(event['desktopStartedAtUnixMs'], 100000.123)

    def test_parent_pid_reuse_or_reparenting_rejects_before_argv(self):
        for changed in ((20, 999, m.EXECUTOR, 100), (20, 10, m.EXECUTOR, 200),
                        (10, 1, m.DESKTOP, 200)):
            with self.subTest(changed=changed):
                original = dict(self.native.identities)
                self.native.add(*changed)
                self.assertFalse(self.observer.parent_valid(20))
                self.watch()
                self.assertEqual(self.native.stats['notifierArgvReads'], 0)
                self.assertEqual(self.observer.report['suggestionCandidates'], 0)
                self.native.identities = original
                self.observer.stop_requested = False

    def test_wrong_child_parent_is_never_inspected(self):
        self.native.add(30, 999, m.NOTIFIER)
        self.observer.add_child(30, 20)
        self.assertNotIn(30, self.observer.watches)
        self.assertEqual(self.native.stats['notifierArgvReads'], 0)

    def test_pid_reuse_during_registration_rejects_before_argv(self):
        self.native.add(30, 20, m.NOTIFIER)
        self.queue.on_register = lambda _event: self.native.add(30, 20, m.NOTIFIER, 200)
        self.observer.add_child(30, 20)
        self.assertNotIn(30, self.observer.watches)
        self.assertEqual(self.native.stats['notifierArgvReads'], 0)

    def test_same_notifier_path_after_pid_reuse_during_argv_is_rejected(self):
        self.native.after_argv = lambda: self.native.add(30, 20, m.NOTIFIER, 200)
        self.watch()
        self.assertEqual(self.observer.report['suggestionCandidates'], 0)
        self.assertEqual(self.native.stats['notifierArgvReadFailures'], 1)

    def test_parent_chain_change_during_argv_discards_payload(self):
        self.native.after_argv = lambda: self.native.add(10, 1, m.DESKTOP, 200)
        self.watch()
        self.assertEqual(self.observer.report['suggestionCandidates'], 0)
        self.assertFalse(any(event['phase'] == 'suggestion-observed' for event in self.events()))

    def test_new_executor_stops_old_listener_before_next_heartbeat(self):
        self.queue.on_wait = lambda: self.native.add(21, 10, m.EXECUTOR) or []
        self.observer.run()
        self.assertEqual(self.observer.report['stoppedReason'], 'original-executor-set-changed')
        self.assertEqual([event['phase'] for event in self.events()], ['kqueue-listener-started', 'finished'])
        self.assertLessEqual(self.clock[0], 1)

    def test_desktop_restart_stops_old_listener(self):
        self.queue.on_wait = lambda: self.native.add(10, 1, m.DESKTOP, 200) or []
        self.observer.run()
        self.assertEqual(self.observer.report['stoppedReason'], 'original-executor-set-changed')
        self.assertFalse(any(event['phase'] == 'listening' for event in self.events()))

    def test_setup_topology_change_never_announces_ready(self):
        self.queue.on_register = lambda _event: self.native.add(21, 10, m.EXECUTOR)
        with self.assertRaisesRegex(RuntimeError, 'executor set changed'):
            self.observer.run()
        self.assertEqual([event['phase'] for event in self.events()], ['finished'])

    def test_new_observer_does_not_capture_existing_notifier_children(self):
        self.native.add(21, 10, m.EXECUTOR)
        self.native.add(30, 21, m.NOTIFIER)
        self.observer = self.new_observer()
        self.queue.on_wait = lambda: self.observer.stop('synthetic-stop') or []
        self.observer.run()
        self.assertTrue(self.observer.watches[30]['baseline'])
        self.assertEqual(self.native.stats['notifierArgvReads'], 0)

    def test_old_exit_event_cannot_remove_reused_pid_watch(self):
        self.native.add(30, 20, '/synthetic/pre-exec')
        self.observer.add_child(30, 20)
        old_token = self.observer.watches[30]['token']
        self.native.add(30, 20, '/synthetic/pre-exec', 200)
        self.observer.add_child(30, 20)
        new_token = self.observer.watches[30]['token']
        self.observer.handle_events([SimpleNamespace(
            ident=30, udata=old_token, flags=0, fflags=m.select.KQ_NOTE_EXIT)])
        self.assertEqual(self.observer.watches[30]['token'], new_token)
        self.assertEqual(self.native.stats['notifierArgvReads'], 0)

    def test_executor_exit_fences_same_batch_child_exec(self):
        self.native.add(30, 20, '/synthetic/pre-exec')
        self.observer.add_child(30, 20)
        self.observer.parent_tokens[20] = 123
        self.native.paths[30] = m.NOTIFIER
        self.observer.handle_events([
            SimpleNamespace(ident=30, udata=self.observer.watches[30]['token'], flags=0,
                            fflags=m.select.KQ_NOTE_EXEC),
            SimpleNamespace(ident=20, udata=123, flags=0, fflags=m.select.KQ_NOTE_EXIT),
        ])
        self.assertEqual(self.observer.report['stoppedReason'], 'original-executor-exited')
        self.assertEqual(self.native.stats['notifierArgvReads'], 0)


class SharedLauncherTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        state = Path(self.temp.name).resolve()
        state.chmod(0o700)
        (state / 'service').mkdir(mode=0o700)
        wrapper = state / 'codex-shared.mjs'
        self.node = str(Path(sys.executable).resolve())
        self.node_file = state / 'node'
        self.node_file.write_bytes(b'synthetic-node')
        self.node_file.chmod(0o700)
        self.node = str(self.node_file)
        wrapper.write_bytes(('#!' + self.node + '\nsynthetic-installed-wrapper').encode())
        wrapper.chmod(0o700)
        self.manifest_path = state / 'environment.json'
        self.manifest = {
            'schemaVersion': 1, 'label': 'com.codexmanage.shared-desktop-environment',
            'state': str(state), 'ownerDirectory': str(state / 'service'),
            'executable': m.EXECUTOR, 'wrapper': str(wrapper),
            'environment': {'CODEX_CLI_PATH': str(wrapper)},
            'wrapperSha256': m.sha256(wrapper.read_bytes()), 'url': 'ws://127.0.0.1:54321',
        }
        self.manifest_path.write_text(json.dumps(self.manifest))
        self.manifest_path.chmod(0o600)
        owner = state / 'service/desktop-service-owner.json'
        owner.write_text(json.dumps({'electronPid': 10, 'ownerPid': 15,
                                     'url': self.manifest['url'], 'fingerprint': 'a' * 64}))
        owner.chmod(0o600)
        self.native = FakeNative()
        self.native.add(15, 10, self.node)
        self.native.add(20, 15, m.EXECUTOR)
        stack = contextlib.ExitStack()
        self.addCleanup(stack.close)
        stack.enter_context(patch.object(m, 'SHARED_STATE', state))
        stack.enter_context(patch.object(m.subprocess, 'run', side_effect=self.native.ps))

    def test_installed_shared_parent_is_pinned_without_argv_reads(self):
        parents, _, identities = m.discover(self.native)
        self.assertEqual(parents, {20: 10})
        self.assertIn(15, identities)
        self.assertTrue(m.executor_valid(self.native, identities, 20, 10))
        self.assertEqual(self.native.stats['notifierArgvReads'], 0)
        self.native.add(15, 10, self.node, 200)
        self.assertFalse(m.executor_valid(self.native, identities, 20, 10))

    def test_hash_pinned_nvm_node_is_rechecked_for_later_executor_validation(self):
        parents, _, identities = m.discover(self.native)
        self.assertEqual(identities.wrapper_node, self.node)
        self.assertTrue(m.executor_valid(self.native, identities, 20, 10))
        self.native.paths[15] = '/synthetic/other-node'
        self.assertFalse(m.executor_valid(self.native, identities, 20, 10))
        self.assertEqual(self.native.stats['notifierArgvReads'], 0)

    def test_changed_shebang_or_noncanonical_node_is_rejected(self):
        wrapper = Path(self.temp.name) / 'codex-shared.mjs'
        for shebang in ['#!/usr/bin/env node', '#!relative/node',
                        '#!' + str(self.node_file.parent / 'subdir/../node')]:
            with self.subTest(shebang=shebang):
                wrapper.write_text(shebang + '\nsynthetic-installed-wrapper')
                # Even a newly hash-pinned malformed interpreter must fail.
                self.manifest['wrapperSha256'] = m.sha256(wrapper.read_bytes())
                self.manifest_path.write_text(json.dumps(self.manifest))
                with self.assertRaisesRegex(RuntimeError, 'Node identity changed'):
                    m.discover(self.native)

    def test_arbitrary_node_grandchild_is_not_an_original_executor(self):
        with patch.object(m, 'SHARED_STATE', None):
            with self.assertRaisesRegex(RuntimeError, 'executors are unavailable'):
                m.discover(self.native)

    def test_schema2_local_override_keeps_the_pinned_original_chain(self):
        self.manifest['schemaVersion'] = 2
        self.manifest['environment']['CODEX_APP_SERVER_FORCE_CLI'] = ''
        self.manifest_path.write_text(json.dumps(self.manifest))
        parents, _, identities = m.discover(self.native)
        self.assertEqual(parents, {20: 10})
        self.assertTrue(m.executor_valid(self.native, identities, 20, 10))

    def test_pending_shared_install_keeps_the_actual_direct_executor(self):
        (Path(self.temp.name) / 'service/desktop-service-owner.json').unlink()
        self.native.add(20, 10, m.EXECUTOR)
        parents, _, identities = m.discover(self.native)
        self.assertEqual(parents, {20: 10})
        self.assertNotIn(15, identities)
        self.assertTrue(m.executor_valid(self.native, identities, 20, 10))
        self.assertEqual(self.native.stats['notifierArgvReads'], 0)

    def test_missing_shared_owner_does_not_trust_a_node_grandchild(self):
        (Path(self.temp.name) / 'service/desktop-service-owner.json').unlink()
        with self.assertRaisesRegex(RuntimeError, 'executors are unavailable'):
            m.discover(self.native)

    def test_pending_install_without_service_directory_keeps_direct_executor(self):
        service = Path(self.temp.name) / 'service'
        (service / 'desktop-service-owner.json').unlink()
        service.rmdir()
        self.native.add(20, 10, m.EXECUTOR)
        parents, _, identities = m.discover(self.native)
        self.assertEqual(parents, {20: 10})
        self.assertTrue(m.executor_valid(self.native, identities, 20, 10))

    def test_missing_owner_does_not_ignore_an_invalid_manifest(self):
        (Path(self.temp.name) / 'service/desktop-service-owner.json').unlink()
        self.native.add(20, 10, m.EXECUTOR)
        self.manifest['wrapperSha256'] = '0' * 64
        self.manifest_path.write_text(json.dumps(self.manifest))
        with self.assertRaisesRegex(RuntimeError, 'identity changed'):
            m.discover(self.native)

    def test_present_invalid_owner_does_not_fall_back_to_direct_executor(self):
        self.native.add(20, 10, m.EXECUTOR)
        (Path(self.temp.name) / 'service/desktop-service-owner.json').write_text('{}')
        with self.assertRaisesRegex(RuntimeError, 'identity changed'):
            m.discover(self.native)

    def test_reparented_or_wrong_binary_wrapper_is_rejected(self):
        for parent, binary in [(999, self.node), (10, '/synthetic/other-node')]:
            with self.subTest(parent=parent, binary=binary):
                self.native.add(15, parent, binary)
                with self.assertRaisesRegex(RuntimeError, 'launcher process changed'):
                    m.discover(self.native)

    def test_changed_wrapper_bytes_and_public_manifest_are_rejected(self):
        self.manifest['wrapperSha256'] = '0' * 64
        self.manifest_path.write_text(json.dumps(self.manifest))
        with self.assertRaisesRegex(RuntimeError, 'identity changed'):
            m.discover(self.native)
        self.manifest_path.chmod(0o644)
        with self.assertRaisesRegex(RuntimeError, 'remain private'):
            m.discover(self.native)


if __name__ == '__main__':
    unittest.main()
