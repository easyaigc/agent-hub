"""Offline, exclusive-output plan builder. Reads local pinned evidence; never runs adb/commands."""
import argparse, base64, hashlib, json, os, pathlib, re, stat, uuid


def need(value, code):
    if not value:
        raise ValueError(code)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def digest(value):
    need(isinstance(value, str) and re.fullmatch('[0-9a-f]{64}', value), 'canonical SHA256')
    return value


def canonical(value):
    need(isinstance(value, str) and str(uuid.UUID(value)) == value, 'canonical UUID')
    return value


def installed(value):
    need(set(value) == {'uid', 'appSha256', 'runnerSha256', 'signerSetSha256'}, 'closed installed identity')
    need(type(value['uid']) is int and value['uid'] >= 10000, 'UID')
    for key in ('appSha256', 'runnerSha256', 'signerSetSha256'):
        digest(value[key])
    return value


def read_ref(value, maximum):
    need(set(value) == {'path', 'sha256'}, 'closed artifact reference')
    path = pathlib.Path(value['path'])
    need(path.is_absolute() and not path.is_symlink() and stat.S_ISREG(path.lstat().st_mode), 'regular absolute artifact')
    need(0 < path.stat().st_size <= maximum, 'artifact bound')
    data = path.read_bytes()
    need(len(data) == path.stat().st_size and sha(data) == digest(value['sha256']), 'artifact SHA/size changed')
    return data


def prepare(config):
    need(set(config) == {'version', 'originalIntent', 'originalCompleted', 'originalDevice', 'originalBootstrapLog', 'currentInstalled', 'upgradeRun'}, 'closed config')
    need(type(config['version']) is int and config['version'] == 1, 'version')
    raw_intent = read_ref(config['originalIntent'], 32768)
    raw_completed = read_ref(config['originalCompleted'], 32768)
    old = json.loads(raw_intent)
    done = json.loads(raw_completed)
    device = json.loads(read_ref(config['originalDevice'], 32768))
    log = read_ref(config['originalBootstrapLog'], 262144).decode('utf-8')
    lines = [s.split('p9NaturalEvidence=', 1)[1] for s in log.splitlines() if s.startswith('INSTRUMENTATION_STATUS: p9NaturalEvidence=')]
    need(len(lines) == 1 and 'OK (1 test)' in log and 'INSTRUMENTATION_CODE: -1' in log and not any(s in log for s in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed', 'INSTRUMENTATION_STATUS_CODE: -3', 'INSTRUMENTATION_STATUS_CODE: -2')), 'original one actual bootstrap pass')
    proof = json.loads(lines[0])
    need(proof['case'] == 'initializeEmptyDefaultThroughProductWriteOnce' and proof['passed'] is True and proof['bodyEntered'] is True and proof['normalProductWriteAttempted'] is True and proof['status'] == 'passed' and proof['details']['normalProductWrites'] == 1, 'original bootstrap body/write')
    bid = canonical(old['bootstrapId'])
    run = canonical(config['upgradeRun'])
    need(run != bid and done['bootstrapId'] == bid and proof['lease'] == bid, 'original/new IDs')
    need(all(type(x) is int and x == 1 for x in (old['version'], done['version'], old['normalProductWritesPlanned'], done['normalProductWrites'])) and old['phase'] == 'normal-write-intent' and done['phase'] == 'complete' and old['normalProductWritesPlanned'] == done['normalProductWrites'] == 1, 'completed original, never retry')
    need(done['intentSha256'] == sha(raw_intent), 'original receipt link')
    original = installed(old['installed'])
    current = installed(config['currentInstalled'])
    need(installed(done['installed']) == original == installed(proof['installed']), 'original tuple exact')
    need(current['uid'] == original['uid'] and current['signerSetSha256'] == original['signerSetSha256'] and current != original, 'upgrade same UID/certificate, changed APK')
    family = done['familiesAfter']
    need(family['complete'] is True and family['kind'] == 'content-hash' and family['countsAreLowerBounds'] is False and proof['familiesAfter']['familyDigest'] == family['familyDigest'], 'original complete default bytes')
    digest(family['familyDigest'])
    need(set(device) == {'serial', 'avd', 'api', 'bootCount', 'bootId'} and type(device['api']) is int and type(proof['api']) is int and device['api'] == proof['api'] == 37 and type(device['bootCount']) is int and device['bootCount'] >= 0, 'original parent device proof')
    canonical(device['bootId'])
    plan = {'version': 1, 'action': 'reconcile-original-after-upgrade-read-only', 'bootstrapId': bid, 'upgradeRun': run, 'intentSha256': sha(raw_intent), 'completedSha256': sha(raw_completed), 'familyDigest': family['familyDigest'], 'originalInstalled': original, 'currentInstalled': current, 'originalDeviceProofSha256': config['originalDevice']['sha256'], 'originalBootCount': device['bootCount'], 'originalBootId': device['bootId']}
    raw = json.dumps(plan, sort_keys=True, separators=(',', ':')).encode()
    need(len(raw) <= 8192, 'plan bound')
    return {'version': 1, 'plan': plan, 'planBase64': base64.b64encode(raw).decode(), 'planSha256': sha(raw), 'sourceProofs': {k: config[k] for k in ('originalIntent', 'originalCompleted', 'originalDevice', 'originalBootstrapLog')}, 'parentDevicePrePostMustEqualOriginal': device, 'nativeExecuted': False, 'normalProductWrites': 0, 'oldInputsModified': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=pathlib.Path, required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    need(not args.input.is_symlink() and 0 < args.input.stat().st_size <= 16384, 'bounded config')
    result = prepare(json.loads(args.input.read_text()))
    data = (json.dumps(result, sort_keys=True, indent=2) + '\n').encode()
    fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as out:
        out.write(data)
        out.flush()
        os.fsync(out.fileno())
    need(args.output.read_bytes() == data, 'exclusive output readback')
    print('Prepared offline plan; no device/native operation')


if __name__ == '__main__':
    main()
