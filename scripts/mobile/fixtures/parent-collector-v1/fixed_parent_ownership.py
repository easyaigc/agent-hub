"""Root-owned local fencing only. No device access, UUID allocation or authority.

Root supplies one existing private directory shared by all workers for this host,
its existing common target-lock FD/name, the exact serial and immutable invocation.
This module duplicates that actual FD; Root retains ownership of the original
and must not unlock it while this collector is unresolved. Never copy/rotate the directory to
evade an existing pending/once record. No recovery/delete/retry API is provided.
"""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import threading
import uuid


class AdmissionRefused(ValueError):
    pass


def need(value, message):
    if not value:
        raise AdmissionRefused(message)


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode()


class OnceAdmission:
    """Actual flock FD plus durable pending/once intent; strong process ownership.

    Acquire precedes collector construction and every network operation. The
    pending record survives worker death, so losing flock on process exit cannot
    authorize another attempt. Root must still keep an unresolved original
    worker alive: a sentinel is not a substitute for its sockets/decoder/FDs.
    """
    _held = []
    _admitting = threading.Lock()
    COMMANDS = {"PS": "/system/bin/ps -A -n -o UID,PID,NAME",
                "AM": "/system/bin/dumpsys activity processes"}

    @classmethod
    def acquire(cls, private_directory: Path, serial: str, kind: str,
                invocation_id: str, binding_sha256: str,
                parent_fence_name: str, parent_fence_fd: int):
        with cls._admitting:
            return cls._acquire(private_directory, serial, kind, invocation_id, binding_sha256,
                                parent_fence_name, parent_fence_fd)

    @classmethod
    def _acquire(cls, private_directory: Path, serial: str, kind: str,
                 invocation_id: str, binding_sha256: str,
                 parent_fence_name: str, parent_fence_fd: int):
        need(type(private_directory) is Path or isinstance(private_directory, Path), "absolute existing Root private directory")
        need(private_directory.is_absolute() and ".." not in private_directory.parts, "absolute directory without traversal")
        need(type(serial) is str and re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", serial), "exact serial")
        need(kind in cls.COMMANDS, "closed diagnostic kind")
        need(type(invocation_id) is str and str(uuid.UUID(invocation_id)) == invocation_id, "canonical existing invocation UUID")
        need(type(binding_sha256) is str and re.fullmatch(r"[0-9a-f]{64}", binding_sha256), "immutable binding hash")
        need(type(parent_fence_name) is str and re.fullmatch(r"[A-Za-z0-9_.-]{1,160}", parent_fence_name) and
             parent_fence_name not in (".", "..") and type(parent_fence_fd) is int and parent_fence_fd >= 0,
             "existing Root common target-fence pathname and actual FD required")
        self = cls()
        self.directory_fd = None
        self.lock_fd = None
        self.active = False
        self.settled = False
        self.serial, self.kind = serial, kind
        self.invocation_id, self.binding_sha256 = invocation_id, binding_sha256
        target = hashlib.sha256(encoded({"scope": "p9-fixed-parent-device-v1", "serial": serial})).hexdigest()
        # Reuse the embedding Root worker's real common target fence. Never
        # create a parallel lock or silently fall back to a task-private lock.
        self.lock_name = parent_fence_name
        self.pending_name = target + ".pending.json"
        self.intent_name = target + "." + invocation_id + ".intent.json"
        self.settled_name = target + "." + invocation_id + ".settled.json"
        self.intent = encoded({"version": 1, "kind": kind, "serial": serial,
                               "invocationId": invocation_id, "bindingSha256": binding_sha256,
                               "command": cls.COMMANDS[kind], "source": "Root-fixed-parent-once-intent"})
        created_pending = False
        try:
            current = os.open("/", os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            try:
                for part in private_directory.parts[1:]:
                    child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=current)
                    os.close(current)
                    current = child
                self.directory_fd, current = current, None
            finally:
                if current is not None:
                    os.close(current)
            info = os.fstat(self.directory_fd)
            need(stat.S_ISDIR(info.st_mode) and info.st_uid == os.geteuid() and stat.S_IMODE(info.st_mode) == 0o700, "Root-owned 0700 directory required; never chmod shared data")
            self.lock_fd = os.dup(parent_fence_fd)
            self._regular(self.lock_fd, 0)
            parent_identity = os.fstat(self.lock_fd)
            directory_identity = os.fstat(self.directory_fd)
            for held in cls._held:
                if not held.active:
                    continue
                other = os.fstat(held.lock_fd)
                other_directory = os.fstat(held.directory_fd)
                same_lock = (other.st_dev, other.st_ino) == (parent_identity.st_dev, parent_identity.st_ino)
                same_target = held.serial == serial and (other_directory.st_dev, other_directory.st_ino) == (directory_identity.st_dev, directory_identity.st_ino)
                need(not same_lock and not same_target, "original in-process target owner unresolved; inherited flock FD is not a second admission")
            fcntl.flock(self.lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self._lock_identity()
            # Pending blocks a different invocation too, including after a crash.
            for name in (self.pending_name, self.intent_name, self.settled_name):
                try:
                    os.stat(name, dir_fd=self.directory_fd, follow_symlinks=False)
                except FileNotFoundError:
                    continue
                raise AdmissionRefused("existing pending/once/settled evidence; reconcile original, never replay")
            # Marker first: after this point any partial failure seals the worker.
            fd = os.open(self.pending_name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_NONBLOCK, 0o600, dir_fd=self.directory_fd)
            created_pending = True
            self.active = True
            cls._held.append(self)
            self._write(fd, self.intent)
            os.fsync(self.directory_fd)
            self._exclusive(self.intent_name, self.intent)
            os.fsync(self.directory_fd)
            self._lock_identity()
            return self
        except BaseException:
            if not created_pending:
                self._close()
            # Otherwise exact FD and pending state remain held in this process.
            raise

    def _regular(self, fd, maximum):
        value = os.fstat(fd)
        need(stat.S_ISREG(value.st_mode) and value.st_nlink == 1 and value.st_uid == os.geteuid() and
             stat.S_IMODE(value.st_mode) == 0o600 and 0 <= value.st_size <= maximum, "bounded owned 0600 single-link regular file")
        return value

    def _lock_identity(self):
        need(self.directory_fd is not None and self.lock_fd is not None, "actual ownership handles required")
        a = self._regular(self.lock_fd, 0)
        b = os.stat(self.lock_name, dir_fd=self.directory_fd, follow_symlinks=False)
        need((a.st_dev, a.st_ino) == (b.st_dev, b.st_ino), "target flock pathname replaced")

    def _write(self, fd, raw):
        try:
            self._regular(fd, len(raw))
            position = 0
            while position < len(raw):
                n = os.write(fd, raw[position:])
                need(n > 0, "complete once record write")
                position += n
            os.fsync(fd)
        finally:
            os.close(fd)

    def _exclusive(self, name, raw):
        fd = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_NONBLOCK, 0o600, dir_fd=self.directory_fd)
        self._write(fd, raw)

    def _same(self, name, raw):
        fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=self.directory_fd)
        try:
            a = self._regular(fd, 16384)
            need(a.st_size == len(raw) and os.read(fd, len(raw) + 1) == raw, "immutable pending/once bytes changed")
            b = os.fstat(fd)
            c = os.stat(name, dir_fd=self.directory_fd, follow_symlinks=False)
            fields = lambda s: (s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns, s.st_ctime_ns, s.st_nlink)
            need(fields(a) == fields(b) == fields(c), "pending/once identity changed")
        finally:
            os.close(fd)

    def require_capture(self, serial, kind, invocation_id, binding_sha256):
        need(self.active and not self.settled and
             (serial, kind, invocation_id, binding_sha256) ==
             (self.serial, self.kind, self.invocation_id, self.binding_sha256), "original target/kind/invocation/binding required")
        self._lock_identity()
        self._same(self.pending_name, self.intent)
        self._same(self.intent_name, self.intent)

    def settle(self, capture_receipt):
        self.require_capture(self.serial, self.kind, self.invocation_id, self.binding_sha256)
        need(type(capture_receipt) is dict and capture_receipt.get("kind") == self.kind and
             capture_receipt.get("bindingSha256") == self.binding_sha256 and capture_receipt.get("invocationId") == self.invocation_id and
             capture_receipt.get("argv") == self.COMMANDS[self.kind].split(" ") and
             capture_receipt.get("hostTransportSettled") is True and
             capture_receipt.get("stdoutTransportEof") is True and capture_receipt.get("stderrTransportEof") is True and
             type(capture_receipt.get("remoteExitPacketStatus")) is int and 0 <= capture_receipt["remoteExitPacketStatus"] <= 255 and
             capture_receipt.get("outcome") in ("capture-complete-unverified", "known-shell-service-failure", "known-diagnostic-failure"), "only observed physical settlement clears own pending marker")
        record = encoded({"version": 1, "source": "owned-host-collector-settlement-not-native",
                          "intentSha256": hashlib.sha256(self.intent).hexdigest(), "capture": capture_receipt})
        need(len(record) <= 16384, "bounded settlement receipt")
        self._exclusive(self.settled_name, record)
        os.fsync(self.directory_fd)
        self._same(self.pending_name, self.intent)
        os.unlink(self.pending_name, dir_fd=self.directory_fd)
        try:
            os.fsync(self.directory_fd)
        except BaseException:
            # No command retry. Recreate only this same immutable local marker
            # if possible. Even if storage itself fails, _held and the actual
            # inherited Root flock/worker remain sealed; original once survives.
            try:
                self._exclusive(self.pending_name, self.intent)
                os.fsync(self.directory_fd)
            except BaseException as error:
                self.marker_restore_error = type(error).__name__ + ": " + str(error)
            raise
        self.settled = True
        self.active = False
        self._close()
        self._held.remove(self)

    def _close(self):
        if self.lock_fd is not None:
            os.close(self.lock_fd)
            self.lock_fd = None
        if self.directory_fd is not None:
            os.close(self.directory_fd)
            self.directory_fd = None
