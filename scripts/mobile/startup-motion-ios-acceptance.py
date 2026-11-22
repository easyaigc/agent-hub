"""Run only the two incremental motion cases on a disposable iOS simulator.

Invoke under the task's iOS lease. No shared device or formal App is touched.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

p = argparse.ArgumentParser()
p.add_argument('--output', type=Path, required=True)
p.add_argument('--build-directory', type=Path, help='Reuse this lane owned existing disposable build only')
p.add_argument('--case', choices=['both', 'reduce', 'background'], default='both')
a = p.parse_args()
a.output.mkdir(parents=True, exist_ok=False, mode=0o700)
root = Path(__file__).resolve().parents[2]
build = a.build_directory or a.output / 'DerivedData'
summary = {'startedAt': time.time(), 'source': {}}
for source in ['apps/ios/Sources/CodexManageApp.swift', 'apps/ios/Tests/CodexManageUITests.swift']:
    summary['source'][source] = hashlib.sha256((root / source).read_bytes()).hexdigest()
summary['freeBytesBefore'] = shutil.disk_usage(a.output).free
if summary['freeBytesBefore'] < 10 * 1024 ** 3:
    raise SystemExit('Need at least 10GiB free before native build')
env = dict(os.environ)
env.setdefault('KOTLIN_NATIVE_HOME', str(Path.home() / '.konan/kotlin-native-prebuilt-macos-aarch64-2.4.21'))
env.setdefault('JAVA_HOME', subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip())
device = None
try:
    device = subprocess.check_output(['xcrun', 'simctl', 'create', 'P4-StartupMotion',
        'com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro', 'com.apple.CoreSimulator.SimRuntime.iOS-27-0'], text=True).strip()
    summary['simulatorId'] = device
    (a.output / 'resource.json').write_text(json.dumps(summary, indent=2))
    subprocess.run(['xcrun', 'simctl', 'boot', device], check=True)
    subprocess.run(['xcrun', 'simctl', 'bootstatus', device, '-b'], check=True)
    result = a.output / 'motion.xcresult'
    command = ['xcodebuild', '-project', 'apps/ios/CodexManage.xcodeproj', '-scheme', 'CodexManage',
        '-destination', 'platform=iOS Simulator,id=' + device, '-derivedDataPath', str(build),
        '-resultBundlePath', str(result), '-parallel-testing-enabled', 'NO', '-jobs', '2',
        '-collect-test-diagnostics', 'never', 'NOTIFICATION_APP_BUNDLE_ID=com.easyai.agenthub.p4motion',
        'CODE_SIGNING_ALLOWED=YES', 'CODE_SIGN_IDENTITY=-', 'ONLY_ACTIVE_ARCH=YES',
        '-only-testing:CodexManageUITests/StartupBrandUITests/testSystemReduceMotionStartup',
        '-only-testing:CodexManageUITests/StartupBrandUITests/testStartupBackgroundPausesAndForegroundResumes', 'test']
    if a.case != 'both':
        case = 'testSystemReduceMotionStartup' if a.case == 'reduce' else 'testStartupBackgroundPausesAndForegroundResumes'
        command = [v for v in command if not v.startswith('-only-testing:') or v.endswith('/' + case)]
    summary['command'] = command
    with (a.output / 'xcodebuild.log').open('w') as log:
        summary['exitCode'] = subprocess.run(command, cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT).returncode
    if result.exists():
        with (a.output / 'xcresult-summary.json').open('w') as f:
            subprocess.run(['xcrun', 'xcresulttool', 'get', 'test-results', 'summary', '--path', str(result), '--format', 'json'], stdout=f)
        subprocess.run(['xcrun', 'xcresulttool', 'export', 'attachments', '--path', str(result), '--output-path', str(a.output / 'attachments')])
finally:
    if device:
        subprocess.run(['xcrun', 'simctl', 'shutdown', device])
        summary['simulatorDeleteExit'] = subprocess.run(['xcrun', 'simctl', 'delete', device]).returncode
    summary['elapsedSeconds'] = time.time() - summary['startedAt']
    (a.output / 'run.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary))
raise SystemExit(summary.get('exitCode', 1))
