"""Bounded, read-only observation of the already-configured macOS notifier.

Watch original desktop executors with kqueue; inspect argv only after an exact
Sky notifier executable match. No NOTE_TRACK, process attachment, model request,
configuration mutation, UI access, or original executor stdio access is used.
"""
import argparse
import ctypes as C
import datetime as D
import errno
import hashlib
import json
import os
from pathlib import Path
import re
import select
import signal
import stat
import struct
import subprocess
import sys
import time
from typing import NamedTuple


UTC = D.timezone.utc
ROOT = None
STREAM_ONLY = False
SHARED_STATE = None
CONFIG = Path.home() / '.codex/config.toml'
NOTIFIER = str(Path.home() / '.codex/computer-use/Codex Computer Use.app/Contents/'
               'SharedSupport/SkyComputerUseClient.app/Contents/MacOS/SkyComputerUseClient')
EXECUTOR = '/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex'
DESKTOP = '/Applications/ChatGPT.app/Contents/MacOS/ChatGPT'
UUID = re.compile(r'^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$', re.I)
RETRY_INTERVAL = 0.005
RETRY_WINDOW = 0.250
IDLE_WAIT = 0.020
DISCOVERY_INTERVAL = 1.0


class ProcessIdentity(NamedTuple):
    pid: int
    ppid: int
    uid: int
    started_seconds: int
    started_microseconds: int


class ProcBSDInfo(C.Structure):
    # Fixed macOS SDK sys/proc_info.h layout for PROC_PIDTBSDINFO (flavor 3).
    _fields_ = [(name, C.c_uint32) for name in (
        'flags', 'status', 'xstatus', 'pid', 'ppid', 'uid', 'gid', 'ruid',
        'rgid', 'svuid', 'svgid', 'reserved')]
    _fields_ += [('comm', C.c_char * 16), ('name', C.c_char * 32)]
    _fields_ += [(name, C.c_uint32) for name in (
        'nfiles', 'pgid', 'pjobc', 'tdev', 'tpgid')]
    _fields_ += [('nice', C.c_int32), ('started_seconds', C.c_uint64),
                ('started_microseconds', C.c_uint64)]


def process_matches(native, identity, path):
    return (identity is not None and identity.uid == os.getuid()
            and native.identity(identity.pid) == identity
            and native.path(identity.pid) == path
            and native.identity(identity.pid) == identity)


def process_identity(native, pid, path):
    identity = native.identity(pid)
    return identity if process_matches(native, identity, path) else None


def utc_now():
    return D.datetime.now(UTC).isoformat()


def sha256(data):
    return hashlib.sha256(data).hexdigest()


class Native:
    def __init__(self):
        if sys.platform != 'darwin':
            raise RuntimeError('This observer requires macOS')
        self.lib = C.CDLL('/usr/lib/libproc.dylib', use_errno=True)
        self.lib.proc_listchildpids.argtypes = [C.c_int, C.c_void_p, C.c_int]
        self.lib.proc_listchildpids.restype = C.c_int
        self.lib.proc_pidpath.argtypes = [C.c_int, C.c_void_p, C.c_uint32]
        self.lib.proc_pidpath.restype = C.c_int
        self.lib.proc_pidinfo.argtypes = [C.c_int, C.c_int, C.c_uint64, C.c_void_p, C.c_int]
        self.lib.proc_pidinfo.restype = C.c_int
        self.libc = C.CDLL(None, use_errno=True)
        self.libc.sysctl.argtypes = [C.POINTER(C.c_int), C.c_uint, C.c_void_p,
                                    C.POINTER(C.c_size_t), C.c_void_p, C.c_size_t]
        self.libc.sysctl.restype = C.c_int
        self.stats = {'childListCalls': 0, 'pidPathCalls': 0, 'pidIdentityCalls': 0, 'notifierArgvReads': 0,
                      'notifierArgvReadFailures': 0}

    def identity(self, pid):
        self.stats['pidIdentityCalls'] += 1
        info = ProcBSDInfo()
        size = C.sizeof(info)
        if size != 136 or self.lib.proc_pidinfo(pid, 3, 0, C.byref(info), size) != size:
            return None
        if info.pid != pid or not info.started_seconds or info.started_microseconds >= 1000000:
            return None
        return ProcessIdentity(info.pid, info.ppid, info.uid,
                               info.started_seconds, info.started_microseconds)

    def path(self, pid):
        self.stats['pidPathCalls'] += 1
        buf = C.create_string_buffer(4096)
        if self.lib.proc_pidpath(pid, buf, len(buf)) <= 0:
            return None
        return buf.value.decode('utf-8', 'strict')

    def children(self, pid):
        self.stats['childListCalls'] += 1
        capacity = 4096
        while capacity <= 65536:
            buf = (C.c_int * capacity)()
            C.set_errno(0)
            count = self.lib.proc_listchildpids(pid, buf, C.sizeof(buf))
            if count < 0:
                err = C.get_errno()
                raise OSError(err, 'proc_listchildpids failed')
            # libproc returns the NUMBER OF PIDS, not the number of bytes.
            if count < capacity:
                return {int(buf[i]) for i in range(count) if buf[i] > 0}
            capacity *= 2
        raise RuntimeError('Child process list exceeded the bounded buffer')

    def _argv(self, pid):
        # CTL_KERN, KERN_PROCARGS2. 2 MiB is rejected on this host; use 1 MiB.
        mib = (C.c_int * 3)(1, 49, pid)
        buf = C.create_string_buffer(1024 * 1024)
        size = C.c_size_t(len(buf))
        if self.libc.sysctl(mib, 3, buf, C.byref(size), None, 0) != 0:
            return None
        raw = buf.raw[:size.value]
        if len(raw) < 5:
            return None
        argc = struct.unpack_from('i', raw)[0]
        if not 1 <= argc <= 256:
            return None
        end = raw.find(b'\0', 4)
        if end < 0:
            return None
        pos = end + 1
        while pos < len(raw) and raw[pos] == 0:
            pos += 1
        args = []
        for _ in range(argc):
            end = raw.find(b'\0', pos)
            if end < 0:
                return None
            args.append(raw[pos:end].decode('utf-8', 'strict'))
            pos = end + 1
        return args

    def notifier_argv(self, identity):
        if not process_matches(self, identity, NOTIFIER):
            return None
        self.stats['notifierArgvReads'] += 1
        args = self._argv(identity.pid)
        if args is None or not process_matches(self, identity, NOTIFIER):
            self.stats['notifierArgvReadFailures'] += 1
            return None
        return args

    def own_argv_for_self_check(self):
        # The only non-notifier argv read is the observer's own API self-check.
        return self._argv(os.getpid())


def verify_config():
    raw = CONFIG.read_bytes()
    # Fail closed on a changed/multiline setting; do not need a TOML dependency.
    preamble = re.split(r'^\s*\[', raw.decode('utf-8'), maxsplit=1, flags=re.M)[0]
    matches = re.findall(r'^\s*notify\s*=\s*(.+)$', preamble, re.M)
    if len(matches) != 1 or json.loads(matches[0]) != [NOTIFIER, 'turn-ended']:
        raise RuntimeError('Existing top-level notify configuration does not match the fixed Sky notifier')
    if not Path(NOTIFIER).is_file():
        raise RuntimeError('Configured notifier executable is unavailable')
    return sha256(raw)


def shared_wrapper(native, desktops):
    """Pin the installed private launcher without inspecting any process argv."""
    if SHARED_STATE is None:
        return None

    def private(path, directory=False):
        path = Path(path)
        if not path.is_absolute() or path.resolve() != path:
            raise RuntimeError('Shared desktop path must be canonical')
        info = path.lstat()
        if (info.st_uid != os.getuid() or info.st_mode & 0o077
                or not (stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode))
                or (not directory and info.st_nlink != 1)):
            raise RuntimeError('Shared desktop state must remain private')
        return path

    state = private(SHARED_STATE, True)
    manifest = json.loads(private(state / 'environment.json').read_text())
    wrapper_path = state / 'codex-shared.mjs'
    owner_directory = private(state / 'service', True)
    owner = json.loads(private(owner_directory / 'desktop-service-owner.json').read_text())
    if (manifest.get('schemaVersion') not in (1, 2)
            or manifest.get('label') != 'com.codexmanage.shared-desktop-environment'
            or manifest.get('state') != str(state)
            or manifest.get('ownerDirectory') != str(owner_directory)
            or manifest.get('executable') != EXECUTOR
            or manifest.get('wrapper') != str(wrapper_path)
            or manifest.get('environment', {}).get('CODEX_CLI_PATH') != str(wrapper_path)
            or manifest.get('wrapperSha256') != sha256(private(wrapper_path).read_bytes())
            or owner.get('url') != manifest.get('url')
            or not re.fullmatch(r'ws://127\.0\.0\.1:[1-9][0-9]{0,4}', owner.get('url', ''))
            or not re.fullmatch(r'[0-9a-f]{64}', owner.get('fingerprint', ''))):
        raise RuntimeError('Installed shared desktop identity changed')
    electron_pid, wrapper_pid = owner.get('electronPid'), owner.get('ownerPid')
    if (type(electron_pid) is not int or type(wrapper_pid) is not int
            or electron_pid not in desktops):
        raise RuntimeError('Shared launcher must belong to the original desktop')
    node = str(Path(DESKTOP).parents[1] / 'Resources/cua_node/bin/node')
    wrapper = process_identity(native, wrapper_pid, node)
    if (wrapper is None or wrapper.ppid != electron_pid
            or not process_matches(native, desktops[electron_pid], DESKTOP)):
        raise RuntimeError('Original shared launcher process changed')
    return wrapper


def executor_valid(native, identities, pid, desktop_pid):
    executor = identities[pid]
    if (not process_matches(native, executor, EXECUTOR)
            or not process_matches(native, identities[desktop_pid], DESKTOP)):
        return False
    if executor.ppid == desktop_pid:
        return True
    wrapper = identities.get(executor.ppid)
    node = str(Path(DESKTOP).parents[1] / 'Resources/cua_node/bin/node')
    return (wrapper is not None and wrapper.ppid == desktop_pid
            and process_matches(native, wrapper, node))


def discover(native):
    # Process names/parent relationships only; never request ps args/command.
    output = subprocess.run(['ps', '-axo', 'pid=,ppid=,comm='], check=True,
                            capture_output=True, text=True, timeout=5).stdout
    rows = []
    for line in output.splitlines():
        match = re.match(r'\s*(\d+)\s+(\d+)\s+(.*)', line)
        if match:
            rows.append((int(match[1]), int(match[2]), match[3]))
    identities = {}
    for pid, _, name in rows:
        if name == DESKTOP:
            identity = process_identity(native, pid, DESKTOP)
            if identity is not None:
                identities[pid] = identity
    desktops = dict(identities)
    wrapper = shared_wrapper(native, desktops)
    if wrapper is not None:
        identities[wrapper.pid] = wrapper
    parents = {}
    for pid, ppid, name in rows:
        desktop_pid = ppid if ppid in desktops else (
            wrapper.ppid if wrapper is not None and ppid == wrapper.pid else None)
        if desktop_pid is None or name != EXECUTOR:
            continue
        identity = process_identity(native, pid, EXECUTOR)
        if (identity is not None and identity.ppid == ppid
                and process_matches(native, desktops[desktop_pid], DESKTOP)):
            parents[pid] = desktop_pid
            identities[pid] = identity
    if not parents:
        raise RuntimeError('Original desktop executors are unavailable')
    # Only pin desktops that own the selected executors.
    selected = set(parents) | set(parents.values())
    if wrapper is not None:
        selected.add(wrapper.pid)
    return parents, rows, {pid: identity for pid, identity in identities.items() if pid in selected}


def proc_event(pid, flags, token):
    return select.kevent(pid, filter=select.KQ_FILTER_PROC,
                         flags=select.KQ_EV_ADD | select.KQ_EV_ENABLE | select.KQ_EV_CLEAR,
                         fflags=flags, udata=token)


def self_check(native, parents, rows, identities, config_hash):
    own_identity = native.identity(os.getpid())
    if own_identity is None or own_identity.ppid != os.getppid() or own_identity.uid != os.getuid():
        raise RuntimeError('PROC_PIDTBSDINFO process identity self-check failed')
    own_parent_children = native.children(os.getppid())
    if os.getpid() not in own_parent_children:
        raise RuntimeError('proc_listchildpids count/self membership check failed')
    own_args = native.own_argv_for_self_check()
    if not own_args or not any(Path(arg).name == Path(__file__).name for arg in own_args):
        raise RuntimeError('KERN_PROCARGS2 self argv parsing check failed')
    counts = []
    queue = select.kqueue()
    try:
        for token, (pid, desktop_pid) in enumerate(sorted(parents.items()), 1):
            queue.control([proc_event(pid, select.KQ_NOTE_FORK | select.KQ_NOTE_EXIT, token)], 0, 0)
            if not executor_valid(native, identities, pid, desktop_pid):
                raise RuntimeError('Original process identity changed during self-check')
            counts.append({'executorPid': pid, 'desktopPid': desktop_pid,
                           'nativeChildCount': len(native.children(pid)),
                           'psSnapshotChildCount': sum(ppid == pid for _, ppid, _ in rows)})
        # Verify the child filter API on this observer without creating a child.
        queue.control([proc_event(os.getpid(), select.KQ_NOTE_EXEC | select.KQ_NOTE_EXIT, 999999)], 0, 0)
    finally:
        queue.close()
    print(json.dumps({'phase': 'self-check-passed', 'executors': counts,
                      'childListReturnUnit': 'pid-count', 'sysctlBufferBytes': 1024 * 1024,
                      'processIdentityStructBytes': C.sizeof(ProcBSDInfo),
                      'processIdentityVerified': True,
                      'noteTrackUsed': False, 'otherProcessArgvRead': False,
                      'configSha256': config_hash, 'listenerStarted': False}), flush=True)


class Observer:
    def __init__(self, native, parents, identities, config_hash, duration):
        self.native = native
        self.parents = parents
        self.identities = identities
        self.config_hash = config_hash
        self.duration = duration
        self.queue = select.kqueue()
        self.watches = {}
        self.parent_tokens = {}
        self.next_token = 0
        self.stop_requested = False
        self.start = time.monotonic()
        self.started = utc_now()
        self.cpu_start = time.process_time()
        self.out = ROOT if STREAM_ONLY else ROOT / (
            'kqueue-notify-' + D.datetime.now(UTC).strftime('%Y%m%dT%H%M%S.%fZ') + '-' + str(os.getpid()))
        if not STREAM_ONLY:
            self.out.mkdir(mode=0o700)
            self.out.chmod(0o700)
        self.report = {
            'kind': 'existing-original-notifier-kqueue-observation',
            'startedAt': self.started, 'originalExecutorPids': sorted(parents),
            'originalDesktopPids': sorted(set(parents.values())),
            'durationLimitSeconds': duration, 'retryWindowSeconds': RETRY_WINDOW,
            'executorDiscoveryIntervalSeconds': DISCOVERY_INTERVAL,
            'retryIntervalSeconds': RETRY_INTERVAL, 'idleWaitSeconds': IDLE_WAIT,
            'configSha256': config_hash, 'notifierSha256': sha256(Path(NOTIFIER).read_bytes()),
            'noteTrackUsed': False, 'childListReturnUnit': 'pid-count',
            'sourceBindingVerified': False, 'realSuggestionExtracted': False,
            'processesModified': False, 'configurationModified': False, 'uiAccessed': False,
            'modelTurnsSubmitted': 0, 'completedNotifications': [], 'suggestionCandidates': 0,
            'statistics': {'kqueueWaits': 0, 'kqueueEvents': 0, 'parentForkEvents': 0,
                           'parentExitEvents': 0, 'childExecEvents': 0, 'childExitEvents': 0,
                           'baselineChildren': 0, 'newChildren': 0, 'registrationRaces': 0,
                           'staleEvents': 0, 'notifierPidSightings': 0, 'invalidJson': 0,
                           'rejectedPayloads': 0, 'retryChecks': 0, 'peakWatchedChildren': 0,
                           'identityRejects': 0, 'executorDiscoveryChecks': 0},
            'limitations': ['Very short child lifetimes can end before discovery or argv inspection.',
                            'A prediction request and suggestion JSON identify a candidate; source Thread/Turn is unverified.',
                            'Existing baseline children are watched for exit only.'],
        }

    def write_json(self, name, value):
        if STREAM_ONLY:
            return
        destination = self.out / name
        temporary = destination.with_suffix(destination.suffix + '.tmp')
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, 'w', encoding='utf-8') as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write('\n')
        os.replace(temporary, destination)

    def save(self):
        self.report['elapsedSeconds'] = round(time.monotonic() - self.start, 3)
        self.report['cpuSeconds'] = round(time.process_time() - self.cpu_start, 3)
        self.report['statistics'].update(self.native.stats)
        self.report['statistics']['watchedChildren'] = len(self.watches)
        self.write_json('result.json', self.report)

    def token(self):
        self.next_token += 1
        return self.next_token

    def parent_valid(self, pid):
        desktop = self.parents[pid]
        return executor_valid(self.native, self.identities, pid, desktop)

    def topology_valid(self):
        self.report['statistics']['executorDiscoveryChecks'] += 1
        if any(not self.parent_valid(parent) for parent in self.parents):
            return False
        try:
            parents, _, identities = discover(self.native)
        except (RuntimeError, OSError, subprocess.SubprocessError):
            return False
        return parents == self.parents and identities == self.identities

    def stop(self, reason):
        self.report.setdefault('stoppedReason', reason)
        self.stop_requested = True

    def child_valid(self, watch):
        identity = watch['identity']
        return (self.parent_valid(watch['parent']) and identity.ppid == watch['parent']
                and self.native.identity(watch['pid']) == identity)

    def add_child(self, pid, parent, baseline=False):
        if not self.parent_valid(parent):
            self.stop('original-process-identity-changed')
            return
        identity = self.native.identity(pid)
        if identity is None or identity.ppid != parent or identity.uid != os.getuid():
            self.report['statistics']['identityRejects'] += 1
            return
        existing = self.watches.get(pid)
        if existing is not None and existing['identity'] == identity:
            return
        self.watches.pop(pid, None)
        token = self.token()
        flags = select.KQ_NOTE_EXIT | (0 if baseline else select.KQ_NOTE_EXEC)
        try:
            self.queue.control([proc_event(pid, flags, token)], 0, 0)
        except OSError as error:
            if error.errno == errno.ESRCH:
                self.report['statistics']['registrationRaces'] += 1
                return
            raise
        now = time.monotonic()
        watch = {'pid': pid, 'parent': parent, 'token': token, 'baseline': baseline,
                 'identity': identity,
                 'deadline': now + RETRY_WINDOW, 'nextCheck': now, 'captured': False}
        if not self.child_valid(watch):
            self.report['statistics']['identityRejects'] += 1
            return
        self.watches[pid] = watch
        key = 'baselineChildren' if baseline else 'newChildren'
        self.report['statistics'][key] += 1
        self.report['statistics']['peakWatchedChildren'] = max(
            self.report['statistics']['peakWatchedChildren'], len(self.watches))
        if not baseline:
            self.inspect(watch)

    def scan_new_children(self, parent):
        if not self.parent_valid(parent):
            self.stop('original-process-identity-changed')
            return
        for pid in self.native.children(parent):
            self.add_child(pid, parent)

    def inspect(self, watch):
        if self.stop_requested or watch['baseline'] or watch['captured']:
            return
        watch['nextCheck'] = time.monotonic() + RETRY_INTERVAL
        self.report['statistics']['retryChecks'] += 1
        if not self.child_valid(watch):
            self.report['statistics']['identityRejects'] += 1
            watch['deadline'] = 0
            return
        if self.native.path(watch['pid']) != NOTIFIER:
            return
        args = self.native.notifier_argv(watch['identity'])
        if args is None or not self.child_valid(watch):
            return
        watch['captured'] = True
        self.report['statistics']['notifierPidSightings'] += 1
        if len(args) != 3 or args[0] != NOTIFIER or args[1] != 'turn-ended':
            self.report['statistics']['rejectedPayloads'] += 1
            return
        try:
            payload = json.loads(args[2])
        except (ValueError, TypeError):
            self.report['statistics']['invalidJson'] += 1
            return
        if (not isinstance(payload, dict) or payload.get('type') != 'agent-turn-complete'
                or not all(isinstance(payload.get(key), str) and UUID.fullmatch(payload[key])
                           for key in ('thread-id', 'turn-id'))):
            self.report['statistics']['rejectedPayloads'] += 1
            return
        text = payload.get('last-assistant-message', '')
        if not isinstance(text, str):
            text = ''
        receipt = {'pid': watch['pid'], 'parentPid': watch['parent'], 'watchToken': watch['token'],
                   'observedAt': utc_now(), 'threadId': payload['thread-id'], 'turnId': payload['turn-id'],
                   'payloadKeys': sorted(payload), 'textBytes': len(text.encode('utf-8')),
                   'textSha256': sha256(text.encode('utf-8')), 'suggestion': False,
                   'sourceBindingVerified': False}
        stem = payload['thread-id'] + '-' + payload['turn-id'] + '-' + str(watch['token'])
        try:
            candidate = json.loads(text)
        except ValueError:
            candidate = None
        inputs = payload.get('input-messages')
        is_prediction_request = (payload.get('client') == 'Codex Desktop'
            and isinstance(inputs, list) and len(inputs) == 1
            and isinstance(inputs[0], str) and inputs[0].startswith(
                'This is an app-generated request for a suggested next user message.'))
        if (is_prediction_request and isinstance(candidate, dict) and set(candidate) == {'suggestion'}
                and isinstance(candidate['suggestion'], str) and 0 < len(candidate['suggestion']) <= 16000):
            receipt['suggestion'] = True
            receipt['candidateFile'] = stem + '.candidate.json'
            self.report['suggestionCandidates'] += 1
            observation = {
                'kind': 'original-notify-suggestion-candidate', **receipt,
                'text': candidate['suggestion'],
                'inputMessagesSha256': sha256(json.dumps(payload.get('input-messages'), ensure_ascii=False).encode()),
                'appGeneratedPredictionRequest': is_prediction_request,
                'client': payload.get('client'), 'cwd': payload.get('cwd'), 'sourceBindingVerified': False}
            self.write_json(receipt['candidateFile'], observation)
            if STREAM_ONLY:
                print(json.dumps({'phase': 'suggestion-observed', 'observation': observation},
                                 ensure_ascii=False), flush=True)
        self.report['completedNotifications'].append(receipt)
        self.save()
        print(json.dumps({'phase': 'candidate-captured' if receipt['suggestion'] else 'ordinary-completion-captured',
                          'threadId': receipt['threadId'], 'turnId': receipt['turnId'],
                          'textBytes': receipt['textBytes'], 'sourceBindingVerified': False}), flush=True)

    def handle_events(self, events):
        parents_to_scan = set()
        # Process EXIT first so a coalesced parent FORK cannot hide a reused PID.
        for event in sorted(events, key=lambda item: not bool(item.fflags & select.KQ_NOTE_EXIT)):
            pid = int(event.ident)
            if event.flags & select.KQ_EV_ERROR:
                raise OSError(int(event.data), 'kqueue process event error')
            if pid in self.parent_tokens and event.udata == self.parent_tokens[pid]:
                if event.fflags & select.KQ_NOTE_EXIT:
                    self.report['statistics']['parentExitEvents'] += 1
                    self.stop('original-executor-exited')
                if event.fflags & select.KQ_NOTE_FORK:
                    self.report['statistics']['parentForkEvents'] += 1
                    parents_to_scan.add(pid)
                continue
            watch = self.watches.get(pid)
            if watch is None or event.udata != watch['token']:
                self.report['statistics']['staleEvents'] += 1
                continue
            if event.fflags & select.KQ_NOTE_EXEC:
                self.report['statistics']['childExecEvents'] += 1
                # Renew the small retry window for an exec observed after fork.
                watch['deadline'] = time.monotonic() + RETRY_WINDOW
                self.inspect(watch)
            if event.fflags & select.KQ_NOTE_EXIT:
                self.report['statistics']['childExitEvents'] += 1
                self.inspect(watch)
                self.watches.pop(pid, None)
        if not self.stop_requested:
            for parent in parents_to_scan:
                self.scan_new_children(parent)

    def on_sigint(self, _signum, _frame):
        self.report['stoppedReason'] = 'sigint'
        self.stop_requested = True

    def run(self):
        old_handler = signal.signal(signal.SIGINT, self.on_sigint)
        heartbeat = self.start
        discovery = self.start
        try:
            for parent in sorted(self.parents):
                token = self.token()
                self.queue.control([proc_event(parent, select.KQ_NOTE_FORK | select.KQ_NOTE_EXIT, token)], 0, 0)
                self.parent_tokens[parent] = token
                if not self.parent_valid(parent):
                    raise RuntimeError('Original process identity changed during listener setup')
                for pid in self.native.children(parent):
                    self.add_child(pid, parent, baseline=True)
            if self.stop_requested or not self.topology_valid():
                raise RuntimeError('Original executor set changed during listener setup')
            self.save()
            print(json.dumps({'phase': 'kqueue-listener-started', 'output': str(self.out),
                              'executors': sorted(self.parents), 'durationLimitSeconds': self.duration,
                              'desktopStartedAtUnixMs': max(
                                  self.identities[pid].started_seconds * 1000
                                  + self.identities[pid].started_microseconds / 1000
                                  for pid in set(self.parents.values()))}), flush=True)
            while not self.stop_requested and time.monotonic() - self.start < self.duration:
                now = time.monotonic()
                if now - discovery >= DISCOVERY_INTERVAL:
                    if not self.topology_valid():
                        self.stop('original-executor-set-changed')
                        break
                    discovery = now
                retrying = [watch for watch in self.watches.values()
                            if not watch['baseline'] and not watch['captured'] and now <= watch['deadline']]
                for watch in retrying:
                    if now >= watch['nextCheck']:
                        self.inspect(watch)
                timeout = min(IDLE_WAIT, max(0, self.duration - (time.monotonic() - self.start)))
                if retrying:
                    timeout = min(timeout, max(0, min(watch['nextCheck'] for watch in retrying) - time.monotonic()))
                self.report['statistics']['kqueueWaits'] += 1
                events = self.queue.control(None, 512, timeout)
                self.report['statistics']['kqueueEvents'] += len(events)
                self.handle_events(events)
                if self.stop_requested:
                    break
                now = time.monotonic()
                if now - heartbeat >= 30:
                    if any(not self.parent_valid(parent) for parent in self.parents):
                        self.stop('original-process-identity-changed')
                    if sha256(CONFIG.read_bytes()) != self.config_hash:
                        self.stop('notifier-configuration-changed')
                    if self.stop_requested:
                        break
                    self.save()
                    print(json.dumps({'phase': 'listening', 'elapsedSeconds': self.report['elapsedSeconds'],
                                      'cpuSeconds': self.report['cpuSeconds'],
                                      'completedNotifications': len(self.report['completedNotifications']),
                                      'suggestionCandidates': self.report['suggestionCandidates']}), flush=True)
                    heartbeat = now
            self.report.setdefault('stoppedReason', 'duration-limit')
        except Exception as error:
            self.report['stoppedReason'] = 'observer-error'
            self.report['error'] = {'type': type(error).__name__, 'message': str(error)}
            raise
        finally:
            self.queue.close()
            signal.signal(signal.SIGINT, old_handler)
            self.report['finishedAt'] = utc_now()
            try:
                self.report['configSha256Unchanged'] = self.config_hash == sha256(CONFIG.read_bytes())
            except OSError:
                self.report['configSha256Unchanged'] = None
            self.save()
            print(json.dumps({'phase': 'finished', 'output': str(self.out),
                              'completedNotifications': len(self.report['completedNotifications']),
                              'suggestionCandidates': self.report['suggestionCandidates'],
                              'sourceBindingVerified': False, 'stoppedReason': self.report['stoppedReason']}), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, required=True,
                        help='Private evidence directory; must not be committed')
    parser.add_argument('--application-path', type=Path, default=Path('/Applications/ChatGPT.app'))
    parser.add_argument('--codex-home', type=Path, default=Path.home() / '.codex')
    parser.add_argument('--self-check', action='store_true', help='Check real local APIs without starting observation')
    parser.add_argument('--duration-seconds', type=int, default=900)
    parser.add_argument('--shared-desktop-state', type=Path,
                        help='Installed private shared launcher state; no arbitrary process attachment')
    parser.add_argument('--stream-only', action='store_true',
                        help='Stream prediction observations to the owning Host; do not write evidence files')
    args = parser.parse_args()
    if not 1 <= args.duration_seconds <= 900:
        parser.error('--duration-seconds must be between 1 and 900')
    os.umask(0o077)
    global ROOT, CONFIG, NOTIFIER, EXECUTOR, DESKTOP, STREAM_ONLY, SHARED_STATE
    SHARED_STATE = args.shared_desktop_state
    STREAM_ONLY = args.stream_only
    ROOT = args.output_dir.expanduser().resolve()
    ROOT.mkdir(parents=True, exist_ok=True, mode=0o700)
    if ROOT.is_symlink() or ROOT.stat().st_uid != os.getuid() or ROOT.stat().st_mode & 0o077:
        parser.error('--output-dir must be owned by the current user with permissions 0700')
    codex_home = args.codex_home.expanduser().resolve()
    app = args.application_path.expanduser().resolve()
    CONFIG = codex_home / 'config.toml'
    NOTIFIER = str(codex_home / 'computer-use/Codex Computer Use.app/Contents/SharedSupport/'
                   'SkyComputerUseClient.app/Contents/MacOS/SkyComputerUseClient')
    EXECUTOR = str(app / 'Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex')
    DESKTOP = str(app / 'Contents/MacOS/ChatGPT')
    native = Native()
    config_hash = verify_config()
    parents, rows, identities = discover(native)
    if args.self_check:
        self_check(native, parents, rows, identities, config_hash)
    else:
        Observer(native, parents, identities, config_hash, args.duration_seconds).run()


if __name__ == '__main__':
    main()
