"""Offline explicit cold-boot read-only intent. Never runs commands/devices or changes original evidence."""
import argparse, base64, importlib.util, json, os, pathlib, re

HERE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('sameboot_read_utilities', HERE.parent / 'binder-diagnostics' / 'prepare-upgrade-plan.py')
old = importlib.util.module_from_spec(spec)
spec.loader.exec_module(old)
need, sha, digest, canonical, installed, read_ref = old.need, old.sha, old.digest, old.canonical, old.installed, old.read_ref
RECORD = 'p9-native-local-bootstrap-v1'
PATH = 'mobile-v1/' + sha(RECORD.encode()) + '.bin'


def device(value):
    need(set(value) == {'serial', 'avd', 'api', 'bootCount', 'bootId'}, 'closed device')
    need(isinstance(value['serial'], str) and re.fullmatch('[A-Za-z0-9._:-]{1,120}', value['serial']) and isinstance(value['avd'], str) and re.fullmatch('[A-Za-z0-9._-]{1,120}', value['avd']), 'device identity')
    need(type(value['api']) is int and value['api'] == 37 and type(value['bootCount']) is int and 0 <= value['bootCount'] <= 2147483647, 'API37 integer boot count')
    canonical(value['bootId'])
    return value


def family_digest(cipher):
    rows = ['d:' + sha(b'mobile-v1'), 'f:' + sha(PATH.encode()) + ':' + str(len(cipher)) + ':' + sha(cipher)]
    return sha('\n'.join(sorted(rows)).encode())


def prepare(config):
    refs = ('originalIntent', 'originalCompleted', 'originalDevice', 'originalBootstrapLog', 'originalCiphertext', 'currentDeviceProof', 'currentBootIdRead', 'currentBootCountRead')
    need(set(config) == {'version', *refs, 'currentInstalled', 'recoveryRun'}, 'closed cold-boot config')
    need(type(config['version']) is int and config['version'] == 1, 'version')
    raw_intent, raw_done = read_ref(config['originalIntent'], 32768), read_ref(config['originalCompleted'], 32768)
    intent, done = json.loads(raw_intent), json.loads(raw_done)
    original_device = device(json.loads(read_ref(config['originalDevice'], 32768)))
    current_proof = json.loads(read_ref(config['currentDeviceProof'], 32768))
    need(set(current_proof) == {'version', 'role', 'source', 'observationId', 'device', 'bootIdReadSha256', 'bootCountReadSha256'}, 'closed independent current observation')
    need(type(current_proof['version']) is int and current_proof['version'] == 1 and current_proof['role'] == 'current-coldboot-readonly' and current_proof['source'] == 'parent-fixed-readonly-device-v1', 'current observation source, never relabel original')
    current_device = device(current_proof['device'])
    boot_id_bytes, count_bytes = read_ref(config['currentBootIdRead'], 128), read_ref(config['currentBootCountRead'], 32)
    need(boot_id_bytes in (current_device['bootId'].encode(), (current_device['bootId']+'\n').encode()) and count_bytes in (str(current_device['bootCount']).encode(), (str(current_device['bootCount'])+'\n').encode()), 'raw current read values exact')
    need(current_proof['bootIdReadSha256'] == sha(boot_id_bytes) and current_proof['bootCountReadSha256'] == sha(count_bytes), 'current read fingerprints exact')
    need(config['currentDeviceProof']['path'] != config['originalDevice']['path'] and config['currentDeviceProof']['sha256'] != config['originalDevice']['sha256'], 'separate observation artifact')
    need(all(current_device[k] == original_device[k] for k in ('serial', 'avd', 'api')), 'original target exact')
    need(current_device['bootCount'] > original_device['bootCount'] and current_device['bootId'] != original_device['bootId'], 'explicit new kernel boot and increased count; no same/any boot')
    log = read_ref(config['originalBootstrapLog'], 262144).decode('utf-8')
    lines = [s.split('p9NaturalEvidence=', 1)[1] for s in log.splitlines() if s.startswith('INSTRUMENTATION_STATUS: p9NaturalEvidence=')]
    need(len(lines) == 1 and 'OK (1 test)' in log and 'INSTRUMENTATION_CODE: -1' in log and not any(s in log for s in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed', 'INSTRUMENTATION_STATUS_CODE: -3', 'INSTRUMENTATION_STATUS_CODE: -2')), 'original one actual bootstrap pass')
    proof = json.loads(lines[0])
    need(proof['case'] == 'initializeEmptyDefaultThroughProductWriteOnce' and proof['passed'] is True and proof['bodyEntered'] is True and proof['normalProductWriteAttempted'] is True and proof['status'] == 'passed' and type(proof['api']) is int and proof['api'] == 37 and type(proof['details']['normalProductWrites']) is int and proof['details']['normalProductWrites'] == 1, 'original bootstrap write/pass')
    bid, run, observation = canonical(intent['bootstrapId']), canonical(config['recoveryRun']), canonical(current_proof['observationId'])
    need(len({bid, run, observation, original_device['bootId'], current_device['bootId']}) == 5 and done['bootstrapId'] == bid and proof['lease'] == bid, 'distinct original/operation/observation/boot IDs')
    need(all(type(v) is int and v == 1 for v in (intent['version'], done['version'], intent['normalProductWritesPlanned'], done['normalProductWrites'])) and intent['phase'] == 'normal-write-intent' and done['phase'] == 'complete' and done['intentSha256'] == sha(raw_intent), 'complete linked original receipt')
    original, current = installed(intent['installed']), installed(config['currentInstalled'])
    need(installed(done['installed']) == original == installed(proof['installed']), 'original tuple exact')
    need(current['uid'] == original['uid'] and current['signerSetSha256'] == original['signerSetSha256'], 'same UID/certificate; APK upgrade optional')
    family = done['familiesAfter']
    need(family['complete'] is True and family['kind'] == 'content-hash' and family['countsAreLowerBounds'] is False and all(type(family[k]) is int and family[k] == 1 for k in ('familyCount', 'directoryCount', 'fileCountObserved')) and proof['familiesAfter'] == family, 'original complete single-family fixed-record inventory')
    cipher = read_ref(config['originalCiphertext'], 8192)
    need(29 <= len(cipher) <= 8192 and cipher[0] == 3 and done['localRecordNameSha256'] == sha(RECORD.encode()) and family['byteCountObserved'] == len(cipher) and digest(family['familyDigest']) == family_digest(cipher), 'exact original v3 ciphertext anchored by original family root')
    plan = {'version': 1, 'action': 'restore-original-on-explicit-coldboot-read-only', 'bootstrapId': bid, 'recoveryRun': run, 'intentSha256': sha(raw_intent), 'completedSha256': sha(raw_done), 'originalBootstrapLogSha256': config['originalBootstrapLog']['sha256'], 'familyDigest': family['familyDigest'], 'ciphertextSha256': sha(cipher), 'ciphertextBytes': len(cipher), 'originalInstalled': original, 'currentInstalled': current, 'originalDevice': original_device, 'currentDevice': current_device, 'originalDeviceProofSha256': config['originalDevice']['sha256'], 'currentDeviceProofSha256': config['currentDeviceProof']['sha256'], 'currentObservationId': observation, 'currentBootIdReadSha256': sha(boot_id_bytes), 'currentBootCountReadSha256': sha(count_bytes)}
    raw = json.dumps(plan, sort_keys=True, separators=(',', ':')).encode()
    need(len(raw) <= 16384, 'bounded plan')
    return {'version': 1, 'plan': plan, 'planBase64': base64.b64encode(raw).decode(), 'planSha256': sha(raw), 'sourceProofs': {k: config[k] for k in refs}, 'parentDevicePrePostMustEqualCurrent': current_device, 'originalTrust': 'unchanged bootstrap pass + linked raw receipts + original device proof + original family root', 'currentObservationTrust': 'separate parent fixed read-only observation; not native kernel-boot attestation', 'nativeExecuted': False, 'normalProductWrites': 0, 'oldInputsModified': False, 'newNaturalLeaseAndAttemptRequired': True}


def write_exclusive(output, result):
    data = (json.dumps(result, sort_keys=True, indent=2) + '\n').encode()
    fd = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as out:
        out.write(data); out.flush(); os.fsync(out.fileno())
    need(output.read_bytes() == data, 'exclusive output readback')
    fd = os.open(output.parent, os.O_RDONLY | os.O_DIRECTORY)
    try: os.fsync(fd)
    finally: os.close(fd)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=pathlib.Path, required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    need(not args.input.is_symlink() and 0 < args.input.stat().st_size <= 16384, 'bounded config')
    write_exclusive(args.output, prepare(json.loads(args.input.read_text())))
    print('Prepared exclusive offline cold-boot intent; native execution remains pending')


if __name__ == '__main__':
    main()
