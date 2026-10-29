#!/usr/bin/env python3
"""Compile actual lifetime/common-owner sources and run controlled tests; no SDK run."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['repo', 'registry-source', 'output', 'gradle-cache', 'build-slot', 'java']:
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    root = Path(args.repo).resolve()
    output = Path(args.output).resolve()
    output.mkdir(mode=0o700)  # A fresh result, never overwrite an older run.
    manifest = json.loads((Path(__file__).parent / 'source-overlay.json').read_text())
    digest = lambda raw: hashlib.sha256(raw).hexdigest()
    registry = Path(args.registry_source).resolve()
    assert digest(registry.read_bytes()) == manifest['registryBaseSha256']
    sources = [root / 'apps/android/src/main/kotlin/com/agenthub/android/NativeNsdLifetime.kt',
               root / 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeNsdControlledLifetimeTest.kt', registry]
    inputs = {str(path): digest(path.read_bytes()) for path in sources}
    for path in sources[:2]:
        assert inputs[str(path)] == manifest['newSourceFiles'][str(path.relative_to(root))]
    cache = Path(args.gradle_cache)

    def jar(group, name, version):
        paths = list((cache / group / name / version).glob('*/*.jar'))
        assert len(paths) == 1, (name, paths)
        return paths[0]

    std = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.21')
    coroutines = jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.11.0')
    annotations = jar('org.jetbrains', 'annotations', '23.0.0')
    compiler = [jar('org.jetbrains.kotlin', name, version) for name, version in [
        ('kotlin-compiler-embeddable', '2.4.21'), ('kotlin-script-runtime', '2.4.21'),
        ('kotlin-reflect', '1.6.10'), ('kotlin-daemon-embeddable', '2.4.21')]] + [std, coroutines, annotations]
    runtime = [std, coroutines, annotations, jar('junit', 'junit', '4.13.2'), jar('org.hamcrest', 'hamcrest-core', '1.3')]
    classes = output / 'classes'
    classes.mkdir(mode=0o700)
    cp = lambda paths: os.pathsep.join(map(str, paths))

    def run(name, argv):
        with (output / (name + '.log')).open('wb') as stream:
            return subprocess.run([sys.executable, args.build_slot, *argv], stdout=stream, stderr=subprocess.STDOUT, timeout=180).returncode

    compiled = run('compile', [args.java, '-cp', cp(compiler), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                   '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', cp(runtime), '-d', str(classes), *map(str, sources)])
    tested = run('junit', [args.java, '-cp', cp([classes] + runtime), 'org.junit.runner.JUnitCore',
                         'com.agenthub.android.NativeNsdControlledLifetimeTest']) if compiled == 0 else None
    assert all(digest(Path(path).read_bytes()) == sha for path, sha in inputs.items())
    result = {'sourceInputs': inputs, 'dependencies': {str(path): digest(path.read_bytes()) for path in compiler + runtime},
              'compileExit': compiled, 'junitExit': tested,
              'controlledTests': len(re.findall(r'@Test\s+fun ', sources[1].read_text())),
              'actualSdkTestsExecuted': 0, 'deviceActions': 0,
              'scope': 'Exact production lifetime and frozen common owner; controlled platform outcomes on host JVM'}
    (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({key: result[key] for key in ['compileExit', 'junitExit', 'controlledTests', 'actualSdkTestsExecuted']}))
    return compiled or tested or 0


if __name__ == '__main__':
    raise SystemExit(main())
