"""Independent numeric and attribution boundaries for physical evidence summaries."""
import importlib.util
import hashlib
import json
from pathlib import Path
import plistlib
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('thermal_results', Path(__file__).with_name('thermal-ios-results.py'))
results = importlib.util.module_from_spec(spec)
spec.loader.exec_module(results)


class EvidenceBoundaries(unittest.TestCase):
    def test_unsupported_native_capture_fails_before_creating_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory, 'must-not-be-created')
            process = subprocess.run([
                'node', str(Path(__file__).with_name('thermal-ios-comparison.mjs')),
                '--directory', str(target), '--device', 'not-a-device',
                '--variant', 'after', '--source', directory,
                '--test', 'testThermalRealHistoryComparisonDEV', '--native-only', '--work-profile',
            ], capture_output=True, text=True, timeout=10)
            self.assertNotEqual(process.returncode, 0)
            self.assertIn('Native-only capture is supported only', process.stderr)
            self.assertFalse(target.exists())

    def test_known_probe_omitted_backup_category_is_measured_zero(self):
        samples = [dict(utc=100+i*5, uptime=i*5, cpuSeconds=i, usageResult=0, powerResult=0,
                        interruptWakeups=i, idleWakeups=i, thermalState=0, batteryLevel=1,
                        batteryState=3, brightness=0, lowPowerMode=False,
                        sessionWrites=i, sessionWriteBytes=i*100,
                        sessionReads=i*2, sessionReadBytes=i*200,
                        sessionClones=i*2, sessionCloneReferencedBytes=i*200) for i in range(3)]
        metric, _ = results.telemetry_window(samples, 100, 110)
        self.assertEqual(metric['sessionBackupWriteBytes'], 0)
        self.assertEqual(metric['sessionBackupWriteBytesPerSecond'], 0)
        self.assertEqual(metric['sessionStateEncodeWallMS'], 0)
        samples[-1]['sessionBackupWriteBytes'] = 25
        metric, _ = results.telemetry_window(samples, 100, 110)
        self.assertEqual(metric['sessionBackupWriteBytes'], 25)

    def test_legacy_probe_does_not_invent_unmeasured_zero_categories(self):
        with tempfile.TemporaryDirectory() as directory:
            self.fixture(directory)
            samples = [json.loads(x) for x in Path(directory, 'thermal.jsonl').read_text().splitlines()]
            metric, _ = results.telemetry_window(samples, 100, 110)
            self.assertNotIn('sessionBackupWriteBytes', metric)
            self.assertNotIn('sessionStateEncodeWallMS', metric)

    def test_session_clone_references_are_separate_from_written_payloads(self):
        samples = [dict(utc=100+i*5, uptime=i*5, cpuSeconds=i, usageResult=0, powerResult=0,
                        interruptWakeups=i, idleWakeups=i, thermalState=0, batteryLevel=1,
                        batteryState=3, brightness=0, lowPowerMode=False,
                        sessionWrites=i, sessionWriteBytes=i*100,
                        sessionClones=i*2, sessionCloneReferencedBytes=i*200,
                        sessionReadBytes=i*220, sessionStateEncodeWallMS=i*4) for i in range(3)]
        metric, selected = results.telemetry_window(samples, 100, 110)
        self.assertEqual(len(selected), 3)
        self.assertEqual(metric['sessionWriteBytesPerSecond'], 20)
        self.assertEqual(metric['sessionCloneReferencedBytesPerSecond'], 40)
        self.assertEqual(metric['sessionReadBytesPerSecond'], 44)
        self.assertEqual(metric['sessionStateEncodeWallMS'], 8)
        self.assertNotIn('physicalWriteBytes', metric)
        samples[-1]['sessionClones'] = 0
        with self.assertRaisesRegex(AssertionError, 'monotonic'):
            results.telemetry_window(samples, 100, 110)

    def test_quiet_baseline_is_separate_and_requires_complete_coverage(self):
        with tempfile.TemporaryDirectory() as directory:
            self.fixture(directory)
            p = Path(directory)
            sample = json.loads((p / 'thermal.jsonl').read_text().splitlines()[0])
            samples = [dict(sample, utc=utc, uptime=utc, cpuSeconds=utc*.1,
                            sessionWrites=utc, sessionWriteBytes=utc*100) for utc in range(0, 111, 5)]
            (p / 'thermal.jsonl').write_text('\n'.join(map(json.dumps, samples)))
            log = 'THERMAL_IDLE_BEGIN 20\nTHERMAL_IDLE_END 80\nTHERMAL_MEASUREMENT_BEGIN 100\nTHERMAL_MEASUREMENT_END 110\n'
            (p / 'xcodebuild.log').write_text(log)
            metric = results.summarize(directory)
            self.assertEqual(metric['telemetry']['sampledSeconds'], 10)
            self.assertEqual(metric['idleBaseline']['telemetry']['sampledSeconds'], 60)
            (p / 'xcodebuild.log').write_text(log.replace('THERMAL_IDLE_END 80\n', ''))
            with self.assertRaisesRegex(AssertionError, 'Incomplete quiet'):
                results.summarize(directory)
            (p / 'xcodebuild.log').write_text(log)
            (p / 'thermal.jsonl').write_text('\n'.join(map(json.dumps, [s for s in samples if s['utc'] >= 70])))
            with self.assertRaisesRegex(AssertionError, 'quiet baseline coverage'):
                results.summarize(directory)

    def test_xcode_reinstallation_binds_bundle_path_and_ax_pid_in_same_launch(self):
        bundle = 'example.acceptance'
        messages = [
            {'title': 'Launching bundle with identifier ' + bundle},
            {'title': 'Configure with Install Result', 'annotations': [{'title':
                'AppInstallationResult(applicationBundleId: "example.acceptance", installationURL: file:///new/App.app/, databaseUUID: sample)'}]},
            {'title': 'Process with pid 42 launched'}, {'title': 'Successfully launched App'},
        ]
        action = {'subsections': [{'messages': messages}]}
        proof = results.native_action_identity(action, bundle, 'accessibility hierarchy for app with pid 42')
        self.assertEqual(proof['installationURL'], 'file:///new/App.app/')
        self.assertEqual(proof['pid'], 42)
        with self.assertRaisesRegex(AssertionError, 'AX PID'):
            results.native_action_identity(action, bundle, 'accessibility hierarchy for app with pid 99')
        with self.assertRaisesRegex(AssertionError, 'exact Bundle'):
            results.native_action_identity(action, 'example.formal', 'accessibility hierarchy for app with pid 42')
        # Fields in a different launch section must never fill missing identity.
        split = {'subsections': [{'messages': messages[:2]}, {'messages': messages[2:]}]}
        with self.assertRaisesRegex(AssertionError, 'Ambiguous'):
            results.native_action_identity(split, bundle, 'accessibility hierarchy for app with pid 42')

    def fixture(self, directory, pid=17):
        p = Path(directory)
        (p / 'report.json').write_text(json.dumps({
            'variant': 'after', 'test': 'synthetic', 'status': 'this_run_pass',
            'sourceSha256': 'synthetic', 'installation': {'outcome': 'success'},
            'traceTarget': {'pid': 17},
        }))
        (p / 'xcodebuild.log').write_text('THERMAL_MEASUREMENT_BEGIN 100\nTHERMAL_MEASUREMENT_END 110\n')
        samples = [dict(utc=100+i*5, uptime=i*5, cpuSeconds=i, usageResult=0, powerResult=0,
                        interruptWakeups=i*10, idleWakeups=i, thermalState=i, batteryLevel=1,
                        batteryState=2, brightness=0, lowPowerMode=False,
                        sessionWrites=i*6, sessionWriteBytes=i*1000) for i in range(3)]
        (p / 'thermal.jsonl').write_text('\n'.join(map(json.dumps, samples)))
        (p / 'power-toc.xml').write_text('<trace-toc><summary><start-date>1970-01-01T00:01:20+00:00</start-date></summary></trace-toc>')
        columns = ['start', 'duration', 'process', 'cpu-impact', 'gpu-impact', 'networking-impact', 'display-impact']
        schema = ''.join('<col><mnemonic>'+name+'</mnemonic></col>' for name in columns)
        # Window relative to trace is [20,30]. Clip first [18,25] to five
        # seconds and second [25,32] to five: CPU=(2*5+6*5)/10=4.
        rows = f'''<row><time>18000000000</time><duration>7000000000</duration>
          <process id="p"><pid>{pid}</pid></process><impact>2</impact><impact id="g">1</impact><sentinel/><impact id="d">3</impact></row>
          <row><time>25000000000</time><duration>7000000000</duration>
          <process ref="p"/><impact>6</impact><impact ref="g"/><impact>0.01</impact><impact ref="d"/></row>'''
        (p / 'ProcessSubsystemPowerImpact.xml').write_text('<root><schema>'+schema+'</schema>'+rows+'</root>')

    def test_clips_duration_resolves_references_and_excludes_missing_samples(self):
        with tempfile.TemporaryDirectory() as directory:
            self.fixture(directory)
            r = results.summarize(directory)
            self.assertEqual(r['telemetry']['cpuPercentOneCore'], 20)
            self.assertEqual(r['telemetry']['sessionWriteBytesPerSecond'], 200)
            self.assertEqual(r['power']['cpu-impact']['meanImpactUnits'], 4)
            self.assertEqual(r['power']['cpu-impact']['coverage'], 1)
            self.assertEqual(r['power']['gpu-impact']['meanImpactUnits'], 1)
            self.assertEqual(r['power']['networking-impact']['coverage'], 0.5)
            self.assertFalse(r['systemBatteryDrainValid'])

    def test_refuses_another_process_even_with_valid_numbers(self):
        with tempfile.TemporaryDirectory() as directory:
            self.fixture(directory, pid=99)
            with self.assertRaisesRegex(AssertionError, 'verified App PID'):
                results.summarize(directory)

    def test_native_telemetry_does_not_invent_power_impact(self):
        with tempfile.TemporaryDirectory() as directory:
            self.fixture(directory)
            p = Path(directory, 'report.json')
            report = json.loads(p.read_text())
            report['captureMode'] = 'native-telemetry'
            report['traceTarget'].update(mode='native-telemetry', installationPathMatched=True)
            p.write_text(json.dumps(report))
            Path(directory, 'power-toc.xml').unlink()
            actual = results.summarize(directory)
            self.assertIsNone(actual['power'])
            self.assertEqual(actual['telemetry']['cpuPercentOneCore'], 20)
            self.assertFalse(actual['systemBatteryDrainValid'])

    def test_identity_reconciliation_keeps_failed_report_and_rejects_changed_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            self.fixture(directory)
            p = Path(directory)
            app = p / 'App.app'
            app.mkdir()
            (app / 'Info.plist').write_bytes(plistlib.dumps({'CFBundleIdentifier': 'example.acceptance', 'CFBundleExecutable': 'App'}))
            (app / 'App').write_bytes(b'frozen-artifact')
            template = p / 'Test.xctestrun'
            template.write_bytes(plistlib.dumps({'Target': {'UITargetAppPath': '__TESTROOT__/App.app'}}))
            report = json.loads((p / 'report.json').read_text())
            report.update(captureMode='native-telemetry', bundleId='example.acceptance',
                          status='this_run_fail', nativeStatus='this_run_pass',
                          appExecutableSha256=hashlib.sha256(b'frozen-artifact').hexdigest(),
                          runner={'template': str(template)})
            report['installation']['installationURL'] = 'file:///old/App.app/'
            original = json.dumps(report)
            (p / 'report.json').write_text(original)
            (p / 'xcodebuild.log').write_text((p / 'xcodebuild.log').read_text() + 'accessibility hierarchy for app with pid 42\n')
            messages = [{'title': 'Launching bundle with identifier example.acceptance'},
                        {'annotations': [{'title': 'AppInstallationResult(applicationBundleId: "example.acceptance", installationURL: file:///new/App.app/, databaseUUID: example)'}]},
                        {'title': 'Process with pid 42 launched'}, {'title': 'Successfully launched App'}]
            (p / 'action-log.json').write_text(json.dumps({'messages': messages}))
            actual = results.summarize(directory, reconcile_identity=True)
            self.assertEqual(actual['status'], 'native_flow_pass_identity_reconciled')
            self.assertEqual(actual['identityReconciliation']['pid'], 42)
            self.assertTrue(actual['identityReconciliation']['installationURLChangedByXCTest'])
            self.assertEqual((p / 'report.json').read_text(), original)
            self.assertFalse((p / 'metrics.json').exists())
            self.assertTrue((p / 'metrics-reconciled.json').exists())
            (app / 'App').write_bytes(b'another-build')
            with self.assertRaisesRegex(AssertionError, 'artifact changed'):
                results.summarize(directory, reconcile_identity=True)

    def test_missing_end_cannot_become_a_successful_measurement(self):
        with tempfile.TemporaryDirectory() as directory:
            self.fixture(directory)
            Path(directory, 'xcodebuild.log').write_text('THERMAL_MEASUREMENT_BEGIN 100\n')
            with self.assertRaisesRegex(AssertionError, 'Incomplete measurement'):
                results.summarize(directory)

    def test_frame_window_excludes_navigation_warmup_and_boundary_crossing(self):
        evidence = {'clock': [{'utc': 1000, 'uptime': 100}],
                    'gestureTimes': [{'at': 99, 'id': 1}, {'at': 101, 'id': 2}, {'at': 104, 'id': 3}],
                    'frames': [
                        {'start': 99, 'end': 99.8, 'ms': 800, 'gesture': 1},
                        {'start': 101.02, 'end': 101.04, 'ms': 20, 'gesture': 2},
                        {'start': 101.04, 'end': 101.10, 'ms': 60, 'gesture': 2},
                        {'start': 104, 'end': 104.2, 'ms': 200, 'gesture': 3},
                        {'start': 102.99, 'end': 103.05, 'ms': 60, 'gesture': 2}]}
        actual = results.window_scroll(evidence, 1000, 1003)
        self.assertEqual(actual['gestures'], 1)
        self.assertEqual(actual['movingFrames'], 2)
        self.assertEqual(actual['maxFrameMS'], 60)
        self.assertEqual(actual['framesOver50MS'], 1)
        self.assertAlmostEqual(actual['movingSeconds'], .08)
        evidence['frames'][1]['ms'] = 500
        with self.assertRaises(AssertionError):
            results.window_scroll(evidence, 1000, 1003)

    def test_duplicate_gesture_cannot_inflate_workload(self):
        evidence = {'clock': [{'utc': 100, 'uptime': 0}],
                    'gestureTimes': [{'at': 1, 'id': 1}, {'at': 2, 'id': 1}], 'frames': []}
        with self.assertRaisesRegex(AssertionError, 'Duplicate gesture'):
            results.window_scroll(evidence, 100, 104)

    def test_mixed_workload_requires_actual_switches_and_distinct_running_replies(self):
        actions = [('new-chat-and-reply-running', 0), ('read-long-history', 0),
                   ('switch-client', 1), ('read-existing-chat', 1), ('read-existing-chat', 1),
                   ('switch-client', 2), ('read-existing-chat', 2), ('read-existing-chat', 2),
                   ('switch-client', 0), ('reopen-chat-and-followup-running', 0),
                   ('new-chat-and-reply-running', 0), ('read-long-history', 0),
                   ('switch-client', 1), ('read-existing-chat', 1), ('switch-client', 0),
                   ('reopen-completed-reply', 0), ('reopen-completed-reply', 0)]
        lines = ['THERMAL_MIXED_STEP ' + json.dumps(dict(sequence=i+1, action=a, client=c,
                 startUTC=100+i, endUTC=100.5+i)) for i, (a, c) in enumerate(actions)]
        running = ['THERMAL_MIXED_RUNNING ' + marker for marker in ['a', 'f', 'b']]
        prefix = ['THERMAL_MIXED_PREFLIGHT_VERIFIED 3']
        log = '\n'.join(prefix + lines + running)
        self.assertEqual(len(results.mixed_steps(log)), 17)
        self.assertEqual(results.mixed_steps(log)[0]['elapsedSeconds'], .5)
        with self.assertRaisesRegex(AssertionError, 'running original replies'):
            results.mixed_steps('\n'.join(prefix + lines + running[:-1]))
        lines[5] = lines[5].replace('"client": 2', '"client": 1')
        with self.assertRaises(AssertionError):
            results.mixed_steps('\n'.join(prefix + lines + running))


if __name__ == '__main__':
    unittest.main()
