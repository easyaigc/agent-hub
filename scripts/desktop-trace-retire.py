#!/usr/bin/python3
"""Retire one immutable, already quarantined macOS Trace using anchored directories."""
import argparse
import hashlib
import json
import os
import re
import stat
import sys


def require(value):
    if not value:
        raise RuntimeError('TRACE_RETIRE_REJECTED')


def identity(value):
    return str(value.st_dev) + ':' + str(value.st_ino)


def stamp(value):
    return (identity(value), value.st_size, value.st_mtime_ns, value.st_ctime_ns,
            value.st_mode, value.st_uid, value.st_gid, value.st_nlink)


def private(value, directory=False, strict=False):
    # Match the existing native Trace reader: the 0700 root supplies privacy;
    # the original desktop may create nested files with its default umask.
    require(value.st_uid == os.getuid() and value.st_mode & 0o7000 == 0)
    require(value.st_mode & (0o077 if strict else 0o022) == 0)
    require(stat.S_ISDIR(value.st_mode) if directory else stat.S_ISREG(value.st_mode))
    if not directory:
        require(value.st_nlink == 1 and value.st_mode & 0o200)


def run(args, state):
    require(sys.platform == 'darwin')
    root = args.root
    receipt = args.receipt
    require(os.path.isabs(root) and os.path.realpath(root) == root)
    require(os.path.realpath(receipt) == receipt)
    require(receipt.startswith(root + '/.agent-hub-suggestions/'))
    require(re.fullmatch(r'[a-f0-9]{64}', args.digest))
    opened = []
    flags = os.O_RDONLY | os.O_NOFOLLOW
    try:
        file = os.open(receipt, flags)
        try:
            before = os.fstat(file)
            private(before)
            require(before.st_size <= 64 * 1024 * 1024)
            parts = []
            while True:
                part = os.read(file, 65536)
                if not part:
                    break
                parts.append(part)
            raw = b''.join(parts)
            require(stamp(os.fstat(file)) == stamp(before))
        finally:
            os.close(file)
        require(hashlib.sha256(raw).hexdigest() == args.digest)
        record = json.loads(raw)
        require(set(record) == {'version', 'root', 'name', 'files', 'retiredName', 'directories'})
        require(record['version'] == 1 and record['root'] == root)
        require(re.fullmatch(r'trace-[a-f0-9-]{36}-[a-f0-9-]{36}', record['name'], re.I))
        retired = record['retiredName']
        require(re.fullmatch(r'\.agent-hub-retired-[a-f0-9-]{36}', retired))
        proofs = record['directories']
        require(set(proofs) == {'root', 'bundle', 'payloads'})
        rootfd = os.open(root, flags | os.O_DIRECTORY)
        opened.append(rootfd)
        bundlefd = os.open(retired, flags | os.O_DIRECTORY, dir_fd=rootfd)
        opened.append(bundlefd)
        payloadfd = os.open('payloads', flags | os.O_DIRECTORY, dir_fd=bundlefd)
        opened.append(payloadfd)

        def guard():
            require(os.path.realpath(root) == root)
            paths = [os.stat(root, follow_symlinks=False),
                     os.stat(retired, dir_fd=rootfd, follow_symlinks=False),
                     os.stat('payloads', dir_fd=bundlefd, follow_symlinks=False)]
            for key, fd, path in zip(['root', 'bundle', 'payloads'], opened, paths):
                value = os.fstat(fd)
                private(value, True, key == 'root')
                private(path, True, key == 'root')
                proof = proofs[key]
                require(identity(value) == identity(path) == proof['id'])
                require(value.st_mode == proof['mode'] and value.st_uid == proof['uid'])
                require(value.st_gid == proof['gid'] and value.st_dev == paths[0].st_dev)

        guard()
        files = record['files']
        require(isinstance(files, list) and 2 <= len(files) <= 200002)
        names = [item['name'] for item in files]
        require(len(set(names)) == len(names))
        require(all(re.fullmatch(r'(manifest\.json|trace\.jsonl|payloads/[0-9]+\.json)', name)
                    for name in names))
        require(sorted(os.listdir(bundlefd)) == ['manifest.json', 'payloads', 'trace.jsonl'])
        actual = ['manifest.json', 'trace.jsonl'] + ['payloads/' + name for name in os.listdir(payloadfd)]
        require(sorted(actual) == sorted(names))
        for item in files:
            guard()
            directory = payloadfd if item['name'].startswith('payloads/') else bundlefd
            name = item['name'].split('/')[-1]
            file = os.open(name, flags, dir_fd=directory)
            try:
                value = os.fstat(file)
                private(value)
                require(value.st_dev == os.fstat(rootfd).st_dev)
                require(identity(value) == item['id'] and value.st_size == item['size'])
                require(value.st_mode == item['mode'])
                digest = hashlib.sha256()
                while True:
                    data = os.read(file, 65536)
                    if not data:
                        break
                    digest.update(data)
                require(digest.hexdigest() == item['sha256'])
                require(stamp(os.fstat(file)) == stamp(value))
                guard()
                current = os.stat(name, dir_fd=directory, follow_symlinks=False)
                private(current)
                require(identity(current) == identity(value) and current.st_size == value.st_size)
                state['started'] = True
                os.unlink(name, dir_fd=directory)
            finally:
                os.close(file)
        guard()
        require(not os.listdir(payloadfd))
        os.rmdir('payloads', dir_fd=bundlefd)
        require(not os.listdir(bundlefd))
        require(identity(os.stat(retired, dir_fd=rootfd, follow_symlinks=False)) == proofs['bundle']['id'])
        os.rmdir(retired, dir_fd=rootfd)
        print('{"state":"retired"}')
    finally:
        for fd in reversed(opened):
            os.close(fd)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--receipt', required=True)
    parser.add_argument('--root', required=True)
    parser.add_argument('--digest', required=True)
    status = {'started': False}
    try:
        run(parser.parse_args(), status)
    except Exception:
        print('TRACE_RETIRE_REJECTED', file=sys.stderr)
        if not status['started']:
            print('{"state":"not-started"}')
            sys.exit(2)
        sys.exit(1)
