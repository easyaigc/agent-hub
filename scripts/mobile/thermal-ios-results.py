"""Summarize numeric physical-device evidence; never derive watts from impact units."""
import argparse
import datetime
import hashlib
import json
import math
from pathlib import Path
import plistlib
import re
import xml.etree.ElementTree as ET


def native_action_identity(action, bundle, log):
    """Bind install URL, exact Bundle ID and PID in one Xcode launch section."""
    matches = []

    def visit(node):
        messages = node.get('messages', [])
        titles = [m.get('title', '') for m in messages]
        if f'Launching bundle with identifier {bundle}' in titles:
            installs = [a.get('title', '') for m in messages for a in m.get('annotations', [])]
            urls = [m.group(1) for s in installs if (m := re.match(
                r'AppInstallationResult\(applicationBundleId: "' + re.escape(bundle) +
                r'", installationURL: (file://[^,\n]+),', s))]
            pids = [int(m.group(1)) for s in titles if (m := re.fullmatch(r'Process with pid (\d+) launched', s))]
            assert len(urls) == len(pids) == 1, 'Ambiguous native App launch identity'
            assert any(t.startswith('Successfully launched ') for t in titles)
            matches.append((urls[0], pids[0]))
        for child in node.get('subsections', []):
            visit(child)

    visit(action)
    assert len(matches) == 1, 'One exact Bundle launch section is required'
    url, pid = matches[0]
    assert f'accessibility hierarchy for app with pid {pid}' in log, 'XCTest AX PID must match launch PID'
    return {'bundleId': bundle, 'pid': pid, 'installationURL': url}


def reconcile_native_identity(directory, report, log):
    """Supplement a retained failed path check with original Xcode evidence."""
    actual = native_action_identity(json.loads((directory / 'action-log.json').read_text()), report['bundleId'], log)
    template = Path(report['runner']['template'])
    configuration = plistlib.loads(template.read_bytes())
    targets = []

    def visit(value):
        if isinstance(value, dict):
            if 'UITargetAppPath' in value:
                targets.append(value['UITargetAppPath'])
            for child in value.values():
                visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)

    visit(configuration)
    assert len(targets) == 1
    app = Path(targets[0].replace('__TESTROOT__', str(template.parent)))
    info = plistlib.loads((app / 'Info.plist').read_bytes())
    assert info['CFBundleIdentifier'] == report['bundleId']
    binary = app / info['CFBundleExecutable']
    assert hashlib.sha256(binary.read_bytes()).hexdigest() == report['appExecutableSha256'], 'Measured artifact changed'
    return {'method': 'original Xcode action launch section plus frozen xctestrun artifact and AX PID',
            'bundleId': actual['bundleId'], 'pid': actual['pid'],
            'installationURLSha256': hashlib.sha256(actual['installationURL'].encode()).hexdigest(),
            'installationURLChangedByXCTest': actual['installationURL'] != report['installation']['installationURL'],
            'appExecutableSha256Matched': True, 'originalReportPreserved': True}


def window_scroll(evidence, begin, end):
    """Count only moving frames from gesture starts inside the measured window."""
    clock = evidence['clock'][-1]
    offset = clock['utc'] - clock['uptime']
    assert all(math.isfinite(x) for x in [offset, begin, end]) and end > begin
    gestures = [g for g in evidence['gestureTimes'] if begin <= g['at'] + offset <= end]
    ids = {g['id'] for g in gestures}
    assert len(ids) == len(gestures), 'Duplicate gesture identities'
    frames = [f for f in evidence['frames'] if f['gesture'] in ids and
              begin <= f['start'] + offset and f['end'] + offset <= end]
    for f in frames:
        assert all(math.isfinite(f[k]) for k in ['start', 'end', 'ms'])
        assert f['ms'] > 0 and abs((f['end'] - f['start']) * 1000 - f['ms']) < 0.05
    values = sorted(f['ms'] for f in frames)
    assert values, 'No measured moving frames'
    return {'scope': 'gesture starts and moving frames wholly inside measurement window',
            'gestures': len(ids), 'movingFrames': len(values),
            'p95FrameMS': values[min(len(values) - 1, int(len(values) * .95))],
            'p99FrameMS': values[min(len(values) - 1, int(len(values) * .99))],
            'maxFrameMS': values[-1], 'framesOver50MS': sum(x > 50 for x in values),
            'movingSeconds': sum(values) / 1000}


def mixed_steps(log):
    assert re.findall(r'THERMAL_MIXED_PREFLIGHT_VERIFIED (\d+)', log) == ['3'], 'Native client preflight required'
    steps = [json.loads(value) for value in re.findall(r'THERMAL_MIXED_STEP (\{[^\n]+\})', log)]
    expected = ['new-chat-and-reply-running', 'read-long-history',
                'switch-client', 'read-existing-chat', 'read-existing-chat',
                'switch-client', 'read-existing-chat', 'read-existing-chat',
                'switch-client', 'reopen-chat-and-followup-running',
                'new-chat-and-reply-running', 'read-long-history',
                'switch-client', 'read-existing-chat', 'switch-client',
                'reopen-completed-reply', 'reopen-completed-reply']
    assert [s['sequence'] for s in steps] == list(range(1, len(expected) + 1))
    assert [s['action'] for s in steps] == expected, 'Incomplete mixed interaction workload'
    assert [s['client'] for s in steps] == [0, 0, 1, 1, 1, 2, 2, 2, 0, 0, 0, 0, 1, 1, 0, 0, 0]
    running = re.findall(r'THERMAL_MIXED_RUNNING (\S+)', log)
    assert len(running) == len(set(running)) == 3, 'Three distinct running original replies are required'
    assert all(s['endUTC'] >= s['startUTC'] for s in steps)
    return [dict(s, elapsedSeconds=s['endUTC'] - s['startUTC']) for s in steps]


def table(path):
    root = ET.parse(path).getroot()
    references = {node.attrib['id']: node for node in root.iter() if 'id' in node.attrib}
    columns = [col.findtext('mnemonic') for col in root.findall('.//schema/col')]

    def value(node):
        while 'ref' in node.attrib:
            node = references[node.attrib['ref']]
        if node.tag == 'process':
            return int(value(node.find('pid')))
        if node.tag == 'sentinel':
            return None
        try:
            return float(node.text)
        except (ValueError, TypeError):
            return node.text

    rows = [dict(zip(columns, map(value, row))) for row in root.findall('.//row')]
    assert all(len(row) == len(columns) for row in root.findall('.//row'))
    return rows


def telemetry_window(samples, begin, end):
    samples = [sample for sample in samples if begin <= sample['utc'] <= end]
    assert len(samples) >= 3, 'Insufficient device telemetry'
    assert all(sample['usageResult'] == 0 and sample['powerResult'] == 0 for sample in samples)
    assert all(a['uptime'] < b['uptime'] and a['cpuSeconds'] <= b['cpuSeconds'] for a, b in zip(samples, samples[1:]))
    elapsed = samples[-1]['uptime'] - samples[0]['uptime']
    telemetry = {
        'sampleCount': len(samples), 'sampledSeconds': elapsed,
        'cpuSeconds': samples[-1]['cpuSeconds'] - samples[0]['cpuSeconds'],
        'cpuPercentOneCore': (samples[-1]['cpuSeconds'] - samples[0]['cpuSeconds']) / elapsed * 100,
        'interruptWakeupsPerSecond': (samples[-1]['interruptWakeups'] - samples[0]['interruptWakeups']) / elapsed,
        'idleWakeupsPerSecond': (samples[-1]['idleWakeups'] - samples[0]['idleWakeups']) / elapsed,
        'thermalStates': sorted(set(sample['thermalState'] for sample in samples)),
        'initialThermalState': samples[0]['thermalState'],
        'finalThermalState': samples[-1]['thermalState'],
        'batteryLevels': sorted(set(sample['batteryLevel'] for sample in samples)),
        'batteryStates': sorted(set(sample['batteryState'] for sample in samples)),
        'brightness': sorted(set(sample['brightness'] for sample in samples)),
        'lowPowerMode': sorted(set(sample['lowPowerMode'] for sample in samples)),
    }
    if all('sessionWriteBytes' in sample for sample in samples):
        telemetry['sessionWrites'] = samples[-1]['sessionWrites'] - samples[0]['sessionWrites']
        telemetry['sessionWriteBytes'] = samples[-1]['sessionWriteBytes'] - samples[0]['sessionWriteBytes']
        telemetry['sessionWriteBytesPerSecond'] = telemetry['sessionWriteBytes'] / elapsed
    # This probe always emits its read/clone counters, but omits category and
    # timing map entries until first use. Only that complete schema establishes
    # an absent category as zero; legacy recordings have no such measurement.
    detailed_io = all(all(key in sample for key in [
        'sessionReads', 'sessionReadBytes', 'sessionClones', 'sessionCloneReferencedBytes'
    ]) for sample in samples)
    sparse_counters = {'sessionStateWriteBytes', 'sessionBackupWriteBytes',
                       'sessionHistoryWriteBytes', 'sessionOtherWriteBytes',
                       'sessionStateEncodeWallMS', 'sessionStateValidateWallMS'}
    for key in ['sessionReads', 'sessionReadBytes', 'sessionClones', 'sessionCloneReferencedBytes',
                'sessionStateWriteBytes', 'sessionBackupWriteBytes', 'sessionHistoryWriteBytes',
                'sessionOtherWriteBytes', 'sessionStateEncodeWallMS', 'sessionStateValidateWallMS']:
        if any(key in sample for sample in samples) or (detailed_io and key in sparse_counters):
            values = [sample.get(key, 0) for sample in samples]
            assert all(math.isfinite(v) and v >= 0 for v in values)
            assert all(a <= b for a, b in zip(values, values[1:])), 'Session counters must remain monotonic'
            telemetry[key] = values[-1] - values[0]
            telemetry[key + 'PerSecond'] = telemetry[key] / elapsed
    return telemetry, samples


def summarize(directory, reconcile_identity=False):
    directory = Path(directory)
    report = json.loads((directory / 'report.json').read_text())
    log = (directory / 'xcodebuild.log').read_text()
    starts = list(map(float, re.findall(r'THERMAL_MEASUREMENT_BEGIN ([\d.]+)', log)))
    ends = list(map(float, re.findall(r'THERMAL_MEASUREMENT_END ([\d.]+)', log)))
    assert len(starts) == len(ends) and starts, 'Incomplete measurement; never fabricate a window'
    begin, end = starts[-1], ends[-1]
    assert end > begin
    result = {
        'variant': report['variant'], 'test': report['test'], 'status': report['status'],
        'sourceSha256': report['sourceSha256'],
        'windowUTC': [begin, end], 'wallSeconds': end - begin,
        'warmupWindowsDiscarded': len(starts) - 1,
        'variantAssignmentVerified': report.get('installation', {}).get('outcome') == 'success',
        'appExecutableSha256': report.get('appExecutableSha256'),
        'captureMode': report.get('captureMode', 'instruments'),
    }
    frames = re.findall(r'THERMAL_SCROLL_METRICS (\{[^\n]+\})', log)
    if frames:
        result['scroll'] = json.loads(frames[-1])
    work_path = directory / 'history-work-metrics.json'
    if work_path.exists():
        work = json.loads(work_path.read_text())
        if 'frames' in work:
            mixed = 'MixedInteraction' in report['test']
            if mixed:
                result['interactionSteps'] = mixed_steps(log)
            else:
                assert 'THERMAL_TARGET_VERIFIED 1' in log
            result['scrollCumulativeIncludingNavigationAndWarmup'] = result['scroll']
            result['scroll'] = window_scroll(work, begin, end)
            expected_gestures = 22 if mixed else 24
            assert result['scroll']['gestures'] == expected_gestures and result['scroll']['movingFrames'] > 400, 'Incomplete scoped scroll workload'
    all_samples = [json.loads(line) for line in (directory / 'thermal.jsonl').read_text().splitlines()]
    result['telemetry'], samples = telemetry_window(all_samples, begin, end)
    result['notesSessionIO'] = 'Session-only logical Data.write bytes and successful APFS clone references; clones share old blocks and are not written payload bytes. Read counters include validation of cloned bytes. Excludes ledger, measurement logs and other file users; not physical flash bytes.'
    idle_begin = re.findall(r'THERMAL_IDLE_BEGIN ([\d.]+)', log)
    idle_end = re.findall(r'THERMAL_IDLE_END ([\d.]+)', log)
    if idle_begin or idle_end:
        assert len(idle_begin) == len(idle_end) == 1, 'Incomplete quiet baseline'
        ib, ie = float(idle_begin[0]), float(idle_end[0])
        assert ie - ib >= 60 and ie <= begin, 'Quiet baseline must precede mixed actions'
        assert telemetry_window(all_samples, ib, ie)[0]['sampledSeconds'] >= 50, 'Insufficient quiet baseline coverage'
        result['idleBaseline'] = {'windowUTC': [ib, ie], 'wallSeconds': ie - ib,
                                  'telemetry': telemetry_window(all_samples, ib, ie)[0]}
    if report.get('captureMode') == 'native-telemetry':
        if reconcile_identity:
            result['identityReconciliation'] = reconcile_native_identity(directory, report, log)
            assert report['nativeStatus'] == 'this_run_pass', 'Complete native flow must pass independently'
            result['rawCaptureStatus'] = report['status']
            result['status'] = 'native_flow_pass_identity_reconciled'
        else:
            assert report['traceTarget']['mode'] == 'native-telemetry' and report['traceTarget']['installationPathMatched']
        result['power'] = None
        result['systemBatteryDrainValid'] = all(sample['batteryState'] == 1 for sample in samples)
        result['notes'] = ['Normal App launch with in-process CPU/wakeup/logical-write and moving-frame telemetry.',
                           'No Instruments power measurement in this mixed trial; CPU is not watts or battery drain.',
                           'Public thermal state is a level, not measured degrees Celsius.']
        filename = 'metrics-reconciled.json' if reconcile_identity else 'metrics.json'
        (directory / filename).write_text(json.dumps(result, indent=2))
        return result
    toc = ET.parse(directory / 'power-toc.xml').getroot()
    trace_start = datetime.datetime.fromisoformat(toc.findtext('.//summary/start-date')).timestamp()
    low, high = begin - trace_start, end - trace_start
    rows = table(directory / 'ProcessSubsystemPowerImpact.xml')
    assert rows and all(row['process'] == report['traceTarget']['pid'] for row in rows), 'Power rows must belong to the verified App PID'
    impacts = {}
    for column in ['cpu-impact', 'gpu-impact', 'networking-impact', 'display-impact']:
        weighted, covered = 0, 0
        for row in rows:
            if row[column] is None:
                continue
            start, duration = row['start'] / 1e9, row['duration'] / 1e9
            overlap = max(0, min(high, start + duration) - max(low, start))
            covered += overlap
            weighted += overlap * row[column]
        assert covered <= (high - low) * 1.05, 'Overlapping power rows would bias the mean'
        impacts[column] = {'meanImpactUnits': weighted / covered if covered else None,
                           'coveredSeconds': covered, 'coverage': covered / (high - low)}
    result['power'] = impacts
    result['systemBatteryDrainValid'] = all(sample['batteryState'] == 1 for sample in samples)
    result['notes'] = [
        'Power impact units are relative metrics on this same device, not watts or battery percentage.',
        'Public thermal state is a level, not measured degrees Celsius.',
        'CPU/wakeup sampling excludes up to five seconds at each measurement boundary.',
    ]
    (directory / 'metrics.json').write_text(json.dumps(result, indent=2))
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--reconcile-native-identity', action='store_true', help='Keep raw failed report; verify original Xcode action log and frozen artifact')
    parser.add_argument('directories', nargs='+')
    args = parser.parse_args()
    for directory in args.directories:
        print(json.dumps(summarize(directory, args.reconcile_native_identity), ensure_ascii=False))
