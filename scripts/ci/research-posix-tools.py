#!/usr/bin/python3
"""Real Linux metadata for the opt-in synthetic stdio research fixture only."""
import json
import os
from pathlib import Path
import plistlib
import re
import stat
import sys

REAL_PS = '/usr/local/libexec/agent-hub-ps.real'


def private_research_profile():
    if (os.environ.get('AGENT_HUB_CI_RESEARCH_TOOLS') != '1'
            or os.environ.get('GITHUB_REPOSITORY') != 'BCAI/agent-hub-app'):
        return None
    name = os.environ.get('CODEX_MANAGE_STDIO_PROFILE', '')
    if not os.path.isabs(name):
        return None
    try:
        info = os.lstat(name)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
            return None
        with open(name, encoding='utf8') as source:
            profile = json.load(source)
    except (OSError, ValueError):
        return None
    if (not isinstance(profile, dict) or profile.get('kind') != 'transport-poc'
            or not isinstance(profile.get('desktopExecutable'), str)
            or not os.path.isabs(profile['desktopExecutable'])):
        return None
    return profile


def run(tool, args):
    profile = private_research_profile()
    if tool == 'ps':
        if (profile is None or len(args) != 4 or args[0] != '-p'
                or args[2:] != ['-o', 'comm='] or not re.fullmatch(r'[1-9][0-9]*', args[1])):
            os.execv(REAL_PS, [REAL_PS, *args])
        process = Path('/proc') / args[1]
        if process.stat().st_uid != os.getuid():
            raise PermissionError('Process owner mismatch')
        # Read the kernel's actual executable; never echo argv0 or the expected profile path.
        print(os.readlink(process / 'exe'))
        return
    if tool != 'PlistBuddy' or profile is None:
        raise ValueError('Research metadata context required')
    if len(args) != 3 or args[:2] != ['-c', 'Print :CFBundleShortVersionString']:
        raise ValueError('Unsupported metadata operation')
    expected = Path(profile['desktopExecutable']).parent.parent / 'Info.plist'
    if os.path.abspath(args[2]) != os.path.abspath(expected):
        raise ValueError('Metadata target mismatch')
    # Parse the real XML or binary plist; a mismatching version must reach the caller unchanged.
    with expected.open('rb') as source:
        version = plistlib.load(source)['CFBundleShortVersionString']
    if not isinstance(version, str) or not version:
        raise ValueError('Invalid bundle version')
    print(version)


if __name__ == '__main__':
    tool = Path(sys.argv[0]).name
    args = sys.argv[1:]
    if tool not in ['ps', 'PlistBuddy'] and args:
        tool, args = args[0], args[1:]
    try:
        run(tool, args)
    except Exception as error:
        # Keep CI diagnostics useful without printing profile contents or paths.
        print('Research metadata operation failed: ' + type(error).__name__, file=sys.stderr)
        sys.exit(1)
