"""Synthetic offline plan continuity vectors; no Android/native evidence."""
import base64, importlib.util, json, pathlib, tempfile, unittest, uuid

HERE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('prepare_upgrade', HERE / 'prepare-upgrade-plan.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PlanVectors(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='p9-upgrade-vectors-')
        self.root = pathlib.Path(self.temp.name)
        self.bid = str(uuid.uuid4())
        self.old = {'uid': 10123, 'appSha256': 'a'*64, 'runnerSha256': 'b'*64, 'signerSetSha256': 'c'*64}
        self.family = {'complete': True, 'kind': 'content-hash', 'countsAreLowerBounds': False, 'familyDigest': 'd'*64}
        self.intent = {'version': 1, 'bootstrapId': self.bid, 'phase': 'normal-write-intent', 'normalProductWritesPlanned': 1, 'installed': self.old}
        ref = self.put('intent', self.intent)
        self.done = {'version': 1, 'bootstrapId': self.bid, 'phase': 'complete', 'normalProductWrites': 1, 'installed': self.old, 'intentSha256': ref['sha256'], 'familiesAfter': self.family}
        self.proof = {'case': 'initializeEmptyDefaultThroughProductWriteOnce', 'passed': True, 'bodyEntered': True, 'normalProductWriteAttempted': True, 'status': 'passed', 'details': {'normalProductWrites': 1}, 'lease': self.bid, 'installed': self.old, 'familiesAfter': self.family, 'api': 37}
        self.config = {'version': 1, 'originalIntent': ref, 'originalCompleted': self.put('done', self.done), 'originalDevice': self.put('device', {'serial': 'synthetic', 'avd': 'synthetic', 'api': 37, 'bootCount': 1, 'bootId': str(uuid.uuid4())}), 'originalBootstrapLog': self.log(), 'currentInstalled': {**self.old, 'runnerSha256': 'e'*64}, 'upgradeRun': str(uuid.uuid4())}

    def tearDown(self):
        self.temp.cleanup()

    def put(self, name, value):
        raw = json.dumps(value).encode()
        path = self.root / name
        path.write_bytes(raw)
        return {'path': str(path), 'sha256': module.sha(raw)}

    def log(self):
        raw = ('INSTRUMENTATION_STATUS: p9NaturalEvidence=' + json.dumps(self.proof) + '\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n').encode()
        path = self.root / 'bootstrap.log'
        path.write_bytes(raw)
        return {'path': str(path), 'sha256': module.sha(raw)}

    def reject(self):
        with self.assertRaises((ValueError, KeyError)):
            module.prepare(self.config)

    def test_runner_upgrade_keeps_old_bytes_and_separate_identity(self):
        before = {p.name: p.read_bytes() for p in self.root.iterdir()}
        value = module.prepare(self.config)
        raw = base64.b64decode(value['planBase64'], validate=True)
        self.assertEqual(module.sha(raw), value['planSha256'])
        self.assertEqual(json.loads(raw)['originalInstalled'], self.old)
        self.assertNotEqual(value['plan']['originalInstalled'], value['plan']['currentInstalled'])
        self.assertEqual(before, {p.name: p.read_bytes() for p in self.root.iterdir()})
        self.assertFalse(value['nativeExecuted'])

    def test_no_upgrade(self):
        self.config['currentInstalled'] = self.old; self.reject()

    def test_uid_drift(self):
        self.config['currentInstalled']['uid'] += 1; self.reject()

    def test_signer_drift(self):
        self.config['currentInstalled']['signerSetSha256'] = 'f'*64; self.reject()

    def test_raw_receipt_changed(self):
        pathlib.Path(self.config['originalCompleted']['path']).write_bytes(b'{}'); self.reject()

    def test_wrong_receipt_link(self):
        self.done['intentSha256'] = 'f'*64; self.config['originalCompleted'] = self.put('done', self.done); self.reject()

    def test_old_tuple_relabelled(self):
        self.done['installed'] = self.config['currentInstalled']; self.config['originalCompleted'] = self.put('done', self.done); self.reject()

    def test_failed_original(self):
        self.proof['passed'] = False; self.config['originalBootstrapLog'] = self.log(); self.reject()

    def test_incomplete_family(self):
        self.done['familiesAfter'] = {**self.family, 'complete': False}; self.config['originalCompleted'] = self.put('done', self.done); self.reject()

    def test_wrong_original_selector(self):
        self.proof['case'] = 'verifyOriginalBootstrapReadOnly'; self.config['originalBootstrapLog'] = self.log(); self.reject()

    def test_wrong_boot_api(self):
        self.config['originalDevice'] = self.put('device', {'serial': 'synthetic', 'avd': 'synthetic', 'api': 26, 'bootCount': 1, 'bootId': str(uuid.uuid4())}); self.reject()

    def test_unknown_input(self):
        self.config['writeAgain'] = True; self.reject()

    def test_reused_bootstrap_id(self):
        self.config['upgradeRun'] = self.bid; self.reject()

    def test_duplicate_original_proof(self):
        path = pathlib.Path(self.config['originalBootstrapLog']['path']); path.write_bytes(path.read_bytes()*2); self.config['originalBootstrapLog']['sha256'] = module.sha(path.read_bytes()); self.reject()


if __name__ == '__main__':
    unittest.main(verbosity=1)
