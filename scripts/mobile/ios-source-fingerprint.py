#!/usr/bin/env python3
"""Freeze iOS sources and the complete shared-contract generation inputs."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys


ROOTS = [
    'apps/ios',
    'packages/mobile-core',
    # Include all schemas/fixtures, not just the contract being tested. Gradle
    # generates several contracts in one task before the native framework build.
    'packages/protocol',
    'scripts/mobile/build-ios-core.sh',
    'scripts/mobile/gradle.mjs',
    'scripts/mobile/ios-source-fingerprint.py',
    'gradle', 'gradlew', 'gradlew.bat',
    'settings.gradle.kts', 'build.gradle.kts', 'gradle.properties',
    'package.json', 'pnpm-lock.yaml', 'pnpm-workspace.yaml', '.npmrc',
    'turbo.json', 'tsconfig.base.json',
]


def collect(repo):
    raw = subprocess.check_output(
        ['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z', '--', *ROOTS],
        cwd=repo,
    )
    # Also cover literal generation inputs if a later Gradle change places one
    # outside the broad source roots. Never silently omit a dynamic/external input.
    gradle = (repo / 'packages/mobile-core/build.gradle.kts').read_text()
    declared = re.findall(r'rootProject\.file\("([^\"]+)"\)', gradle)
    if any('$' in name or Path(name).is_absolute() or '..' in Path(name).parts
           for name in declared):
        raise ValueError('Dynamic or external Gradle inputs require explicit review')
    names = sorted({os.fsdecode(name) for name in raw.split(b'\0') if name} | set(declared))
    files, links = {}, {}
    for name in names:
        file = repo / name
        if not file.resolve().is_relative_to(repo):
            raise ValueError('Source link outside repository: ' + name)
        if not file.is_file():
            raise ValueError('Missing or unsupported source entry: ' + name)
        if file.is_symlink():
            links[name] = os.readlink(file)
        files[name] = hashlib.sha256(file.read_bytes()).hexdigest()
    # Fail if a required source root was accidentally narrowed or unavailable.
    required = [
        'packages/mobile-core/build.gradle.kts',
        'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/DomainContract.kt',
        'packages/protocol/schemas/agent-management-v1.schema.json',
        'packages/protocol/schemas/codex-mobile-http-v1.schema.json',
        'packages/protocol/schemas/codex-host-discovery-v1.schema.json',
        'packages/protocol/schemas/task-hub-mobile-v1.schema.json',
        'packages/protocol/schemas/usage-v1.schema.json',
    ]
    missing = sorted(set(required) - files.keys())
    if missing:
        raise ValueError('Missing shared generation inputs: ' + ', '.join(missing))
    return {'files': files, 'symlinks': links}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[2])
    destination = parser.add_mutually_exclusive_group(required=True)
    destination.add_argument('--output', type=Path)
    destination.add_argument('--verify', type=Path)
    parser.add_argument('--task-id')
    args = parser.parse_args()
    repo = args.repo.resolve()
    current = collect(repo)
    if args.verify:
        previous = json.loads(args.verify.read_text())
        differences = [
            name for name in sorted(previous['files'].keys() | current['files'].keys())
            if previous['files'].get(name) != current['files'].get(name)
        ]
        links_changed = previous.get('symlinks', {}) != current['symlinks']
        print(json.dumps({'matched': not differences and not links_changed,
                          'files': len(current['files']), 'changed': differences,
                          'symlinkTargetsChanged': links_changed}))
        return 1 if differences or links_changed else 0
    output = args.output.resolve()
    if output.is_relative_to(repo):
        relative = str(output.relative_to(repo))
        if any(relative == root or relative.startswith(root + '/') for root in ROOTS):
            raise ValueError('Manifest must be outside the selected source inputs')
    value = {'version': 1, 'taskId': args.task_id,
             'capturedAt': datetime.now(timezone.utc).isoformat(),
             'selection': ROOTS, **current}
    output.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with output.open('x', encoding='utf-8') as stream:
        os.chmod(output, 0o600)
        json.dump(value, stream, ensure_ascii=True, indent=2)
        stream.write('\n')
    print(json.dumps({'captured': True, 'files': len(current['files'])}))
    return 0


if __name__ == '__main__':
    sys.exit(main())
