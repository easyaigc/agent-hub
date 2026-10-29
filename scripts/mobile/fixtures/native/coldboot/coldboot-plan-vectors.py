"""Synthetic contract boundaries only. No device, Keystore or native acceptance."""
import base64, copy, importlib.util, json, pathlib, tempfile, unittest, uuid

HERE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('coldboot', HERE / 'prepare-coldboot-plan.py')
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)


class ColdBootVectors(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='p9-coldboot-vectors-'); self.root = pathlib.Path(self.temp.name)
        self.bid, self.run, self.observation = (str(uuid.uuid4()) for _ in range(3))
        self.original = {'uid': 10123, 'appSha256': 'a'*64, 'runnerSha256': 'b'*64, 'signerSetSha256': 'c'*64}
        self.device = {'serial': 'synthetic-serial', 'avd': 'synthetic-avd', 'api': 37, 'bootCount': 1, 'bootId': str(uuid.uuid4())}
        self.current_device = {**self.device, 'bootCount': 2, 'bootId': str(uuid.uuid4())}
        self.cipher = b'\x03'+bytes(range(1,81))  # Opaque synthetic shape; NOT an authenticated ciphertext.
        self.family = {'complete': True, 'kind': 'content-hash', 'countsAreLowerBounds': False, 'familyCount': 1, 'directoryCount': 1, 'fileCountObserved': 1, 'byteCountObserved': len(self.cipher), 'familyDigest': m.family_digest(self.cipher)}
        self.intent = {'version': 1, 'bootstrapId': self.bid, 'phase': 'normal-write-intent', 'normalProductWritesPlanned': 1, 'installed': self.original}
        intent_ref = self.put('intent', self.intent)
        self.done = {'version': 1, 'bootstrapId': self.bid, 'phase': 'complete', 'normalProductWrites': 1, 'installed': self.original, 'intentSha256': intent_ref['sha256'], 'familiesAfter': self.family, 'localRecordNameSha256': m.sha(m.RECORD.encode())}
        self.proof = {'case': 'initializeEmptyDefaultThroughProductWriteOnce', 'passed': True, 'bodyEntered': True, 'normalProductWriteAttempted': True, 'status': 'passed', 'details': {'normalProductWrites': 1}, 'api': 37, 'lease': self.bid, 'installed': self.original, 'familiesAfter': self.family}
        self.current_proof = {'version': 1, 'role': 'current-coldboot-readonly', 'source': 'parent-fixed-readonly-device-v1', 'observationId': self.observation, 'device': self.current_device}
        self.config = {'version': 1, 'originalIntent': intent_ref, 'originalCompleted': self.put('done', self.done), 'originalDevice': self.put('original-device', self.device), 'originalBootstrapLog': self.log(), 'originalCiphertext': self.raw('cipher', self.cipher), 'currentInstalled': dict(self.original), 'recoveryRun': self.run}
        self.sync_current()

    def tearDown(self): self.temp.cleanup()
    def raw(self, name, data):
        p = self.root / name; p.write_bytes(data); return {'path': str(p), 'sha256': m.sha(data)}
    def put(self, name, value): return self.raw(name, json.dumps(value).encode())
    def log(self): return self.raw('original.log', ('INSTRUMENTATION_STATUS: p9NaturalEvidence='+json.dumps(self.proof)+'\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n').encode())
    def sync_current(self):
        self.config['currentBootIdRead'] = self.raw('current-boot-id', (self.current_device['bootId']+'\n').encode())
        self.config['currentBootCountRead'] = self.raw('current-count', (str(self.current_device['bootCount'])+'\n').encode())
        self.current_proof['bootIdReadSha256'] = self.config['currentBootIdRead']['sha256']; self.current_proof['bootCountReadSha256'] = self.config['currentBootCountRead']['sha256']
        self.config['currentDeviceProof'] = self.put('current-proof', self.current_proof)
    def reject(self):
        with self.assertRaises((ValueError, KeyError, TypeError, FileNotFoundError)): m.prepare(self.config)
    def test_same_apks_new_boot_and_no_input_mutation(self):
        before = {p.name:p.read_bytes() for p in self.root.iterdir()}; result = m.prepare(self.config)
        raw = base64.b64decode(result['planBase64'], validate=True); self.assertEqual(m.sha(raw), result['planSha256'])
        self.assertEqual(result['parentDevicePrePostMustEqualCurrent'], self.current_device)
        self.assertNotEqual(result['plan']['originalDevice'], result['plan']['currentDevice']); self.assertFalse(result['nativeExecuted'])
        self.assertEqual(result['plan']['originalInstalled'], result['plan']['currentInstalled']); self.assertEqual(before, {p.name:p.read_bytes() for p in self.root.iterdir()})
    def test_upgraded_apks_allowed(self): self.config['currentInstalled']['runnerSha256']='e'*64; m.prepare(self.config)
    def test_same_boot_id(self): self.current_device['bootId']=self.device['bootId']; self.sync_current(); self.reject()
    def test_same_boot_count(self): self.current_device['bootCount']=1; self.sync_current(); self.reject()
    def test_rollback_boot_count(self): self.current_device['bootCount']=0; self.sync_current(); self.reject()
    def test_other_serial(self): self.current_device['serial']='other'; self.sync_current(); self.reject()
    def test_other_avd(self): self.current_device['avd']='other'; self.sync_current(); self.reject()
    def test_other_api(self): self.current_device['api']=36; self.sync_current(); self.reject()
    def test_bool_count(self): self.current_device['bootCount']=True; self.sync_current(); self.reject()
    def test_unbound_new_kernel(self): self.config['currentBootIdRead']=self.raw('current-boot-id', (str(uuid.uuid4())+'\n').encode()); self.reject()
    def test_unbound_count(self): self.config['currentBootCountRead']=self.raw('current-count', b'3\n'); self.reject()
    def test_relabel_old_observation(self): self.current_proof['role']='original'; self.sync_current(); self.reject()
    def test_unknown_observation_source(self): self.current_proof['source']='history'; self.sync_current(); self.reject()
    def test_current_proof_tampered(self): pathlib.Path(self.config['currentDeviceProof']['path']).write_bytes(b'{}'); self.reject()
    def test_current_raw_fingerprint_tampered(self): self.current_proof['bootIdReadSha256']='f'*64; self.config['currentDeviceProof']=self.put('current-proof',self.current_proof); self.reject()
    def test_current_raw_newline_suffix(self): self.config['currentBootCountRead']=self.raw('current-count', b'2\n\n'); self.reject()
    def test_reused_original_id(self): self.config['recoveryRun']=self.bid; self.reject()
    def test_reused_observation_id(self): self.config['recoveryRun']=self.observation; self.reject()
    def test_old_boot_as_new_operation(self): self.config['recoveryRun']=self.device['bootId']; self.reject()
    def test_uid_change(self): self.config['currentInstalled']['uid']+=1; self.reject()
    def test_certificate_change(self): self.config['currentInstalled']['signerSetSha256']='f'*64; self.reject()
    def test_raw_old_receipt_change(self): pathlib.Path(self.config['originalCompleted']['path']).write_bytes(b'{}'); self.reject()
    def test_relinked_fake_receipt(self): self.done['intentSha256']='f'*64; self.config['originalCompleted']=self.put('done',self.done); self.reject()
    def test_relabel_original_tuple(self): self.done['installed']={**self.original,'appSha256':'e'*64}; self.config['originalCompleted']=self.put('done',self.done); self.reject()
    def test_failed_original(self): self.proof['passed']=False; self.config['originalBootstrapLog']=self.log(); self.reject()
    def test_wrong_original_selector(self): self.proof['case']='verifyOriginalBootstrapReadOnly'; self.config['originalBootstrapLog']=self.log(); self.reject()
    def test_original_skip(self): p=pathlib.Path(self.config['originalBootstrapLog']['path']); self.config['originalBootstrapLog']=self.raw('original.log',p.read_bytes()+b'INSTRUMENTATION_STATUS_CODE: -3\n'); self.reject()
    def test_missing_full_inventory(self): self.family['complete']=False; self.config['originalCompleted']=self.put('done',self.done); self.config['originalBootstrapLog']=self.log(); self.reject()
    def test_another_family(self): self.family['familyCount']=2; self.config['originalCompleted']=self.put('done',self.done); self.config['originalBootstrapLog']=self.log(); self.reject()
    def test_changed_cipher_even_with_updated_ref(self): self.config['originalCiphertext']=self.raw('cipher',self.cipher[:-1]+b'X'); self.reject()
    def test_old_encoding(self): self.config['originalCiphertext']=self.raw('cipher',b'\x01'+self.cipher[1:]); self.reject()
    def test_unknown_record(self): self.done['localRecordNameSha256']='f'*64; self.config['originalCompleted']=self.put('done',self.done); self.reject()
    def test_any_boot_option(self): self.config['allowAnyBoot']=True; self.reject()
    def test_original_device_not_rewritten(self): self.config['originalDevice']=self.put('original-device',self.current_device); self.reject()
    def test_exclusive_output(self):
        out=self.root/'new-intent'; result=m.prepare(self.config); m.write_exclusive(out,result); raw=out.read_bytes()
        self.assertEqual(out.stat().st_mode&0o777,0o600)
        with self.assertRaises(FileExistsError): m.write_exclusive(out,result)
        self.assertEqual(raw,out.read_bytes())


if __name__ == '__main__': unittest.main(verbosity=1)
