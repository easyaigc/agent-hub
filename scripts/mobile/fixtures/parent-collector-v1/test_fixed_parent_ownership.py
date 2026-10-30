import fcntl, json, os, subprocess, sys, tempfile, time, unittest
from pathlib import Path
from unittest.mock import patch
import fixed_parent_ownership as o
U1='00000000-0000-0000-0000-000000000007'; U2='00000000-0000-0000-0000-000000000008'; H='a'*64
class Ownership(unittest.TestCase):
 def setUp(self): self.tmp=tempfile.TemporaryDirectory(); self.root=Path(self.tmp.name).resolve(); self.owned=[]; self.roots=[]
 def fence(self,name):
  fd=os.open(self.root/name,os.O_CREAT|os.O_EXCL|os.O_RDWR,0o600); fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB); self.roots.append(fd); return fd
 def acquire(self,serial='fake',invocation=U1,name='root-fence',fd=None):
  if fd is None: fd=self.fence(name)
  a=o.OnceAdmission.acquire(self.root,serial,'PS',invocation,H,name,fd); self.owned.append(a); return a
 def tearDown(self):
  for a in self.owned+list(o.OnceAdmission._held):
   a._close()
   if a in o.OnceAdmission._held: o.OnceAdmission._held.remove(a)
  for fd in self.roots: os.close(fd)
  self.tmp.cleanup()
 def worker(self,code):
  cmd=[sys.executable,'-c',code,str(self.root)]; start=time.time_ns(); p=subprocess.Popen(cmd,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,env={**os.environ,'PYTHONDONTWRITEBYTECODE':'1'})
  try: stdout,stderr=p.communicate(timeout=3)
  finally:
   if p.poll() is None: p.kill(); p.communicate()
   if os.environ.get('P9_WORKER_LOG'):
    with open(os.environ['P9_WORKER_LOG'],'a') as f: f.write(json.dumps({'pid':p.pid,'startTimeNs':start,'command':cmd,'returncode':p.returncode,'reaped':p.poll() is not None})+'\n')
  return p.returncode,stdout,stderr
 def script(self,invocation):
  return "import sys,os,fcntl; from pathlib import Path; from fixed_parent_ownership import OnceAdmission; p=Path(sys.argv[1]); fd=os.open(p/'root-fence',os.O_RDWR); fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB); OnceAdmission.acquire(p,'fake','PS','"+invocation+"','"+H+"','root-fence',fd)"
 def receipt(self): return {'kind':'PS','bindingSha256':H,'invocationId':U1,'argv':o.OnceAdmission.COMMANDS['PS'].split(' '),'hostTransportSettled':True,'stdoutTransportEof':True,'stderrTransportEof':True,'remoteExitPacketStatus':0,'outcome':'capture-complete-unverified'}
 def test_flock_and_independence(self):
  a=self.acquire(); b=self.acquire('other',U2,'other-fence'); rc,_,err=self.worker(self.script(U2)); self.assertNotEqual(rc,0); self.assertIn('BlockingIOError',err); self.assertTrue(a.active and b.active)
 def test_exit_pending(self):
  fd=self.fence('root-fence'); os.close(fd); self.roots.remove(fd)
  rc,_,err=self.worker(self.script(U1)); self.assertEqual(rc,0,err)
  fd=os.open(self.root/'root-fence',os.O_RDWR); fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB); self.roots.append(fd)
  with self.assertRaises(o.AdmissionRefused): self.acquire(invocation=U2,fd=fd)
 def test_settled_binding_and_root_retained(self):
  fd=self.fence('root-fence'); a=self.acquire(fd=fd)
  for key,value in (('bindingSha256','b'*64),('invocationId',U2),('argv',[])):
   receipt=self.receipt(); receipt[key]=value
   with self.assertRaises(o.AdmissionRefused): a.settle(receipt)
  a.settle(self.receipt()); self.assertIsNone(a.lock_fd); os.fstat(fd)
  rc,_,err=self.worker(self.script(U2)); self.assertNotEqual(rc,0); self.assertIn('BlockingIOError',err)
  os.close(fd); self.roots.remove(fd)
  fd=os.open(self.root/'root-fence',os.O_RDWR); fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB); self.roots.append(fd)
  with self.assertRaises(o.AdmissionRefused): self.acquire(fd=fd)
 def test_private_directory(self):
  fd=self.fence('root-fence'); self.root.chmod(0o755)
  try:
   with self.assertRaises(o.AdmissionRefused): self.acquire(fd=fd)
  finally: self.root.chmod(0o700)
 def test_wrong_fd_and_no_alternate_creation(self):
  fd=self.fence('real'); wrong=self.fence('wrong'); before=set(self.root.iterdir())
  with self.assertRaises(o.AdmissionRefused): self.acquire(name='real',fd=wrong)
  with self.assertRaises(FileNotFoundError): self.acquire(name='missing',fd=fd)
  self.assertEqual(before,set(self.root.iterdir()))
 def test_failed_durable_intent_seals_pending(self):
  fd=self.fence('root-fence')
  with patch.object(o.OnceAdmission,'_exclusive',side_effect=OSError('durability')):
   with self.assertRaises(OSError): self.acquire(fd=fd)
  self.assertEqual(len(o.OnceAdmission._held),1); a=o.OnceAdmission._held[0]; self.assertTrue(a.active); self.assertIsNotNone(a.lock_fd); self.assertTrue((self.root/a.pending_name).exists())
  with self.assertRaises(o.AdmissionRefused): self.acquire(invocation=U2,fd=fd)
 def test_failure_after_unlink_retains_once_and_ownership(self):
  fd=self.fence('root-fence'); a=self.acquire(fd=fd); real=os.fsync; calls=[]
  def fail_second_directory_sync(handle):
   if handle==a.directory_fd:
    calls.append(handle)
    if len(calls)==2: raise OSError('post-unlink sync')
   return real(handle)
  with patch.object(o.os,'fsync',side_effect=fail_second_directory_sync):
   with self.assertRaises(OSError): a.settle(self.receipt())
  self.assertTrue(a.active); self.assertFalse(a.settled); self.assertIsNotNone(a.lock_fd); self.assertTrue((self.root/a.settled_name).exists())
  with self.assertRaises(o.AdmissionRefused): self.acquire(invocation=U2,fd=fd)
 def test_settlement_write_failure_stays_unknown(self):
  fd=self.fence('root-fence'); a=self.acquire(fd=fd)
  with patch.object(a,'_exclusive',side_effect=OSError('durability')):
   with self.assertRaises(OSError): a.settle(self.receipt())
  self.assertTrue(a.active); self.assertFalse(a.settled); self.assertIsNotNone(a.lock_fd); self.assertTrue((self.root/a.pending_name).exists())
  with self.assertRaises(o.AdmissionRefused): self.acquire(invocation=U2,fd=fd)
 def test_failed_marker_restore_keeps_original_physical_owner(self):
  fd=self.fence('root-fence'); a=self.acquire(fd=fd); real_sync=os.fsync; real_exclusive=a._exclusive; calls=[]
  def fail_second_sync(handle):
   if handle==a.directory_fd:
    calls.append(handle)
    if len(calls)==2: raise OSError('post-unlink sync')
   return real_sync(handle)
  def fail_restore(name,raw):
   if name==a.pending_name: raise OSError('restore failure')
   return real_exclusive(name,raw)
  with patch.object(o.os,'fsync',side_effect=fail_second_sync), patch.object(a,'_exclusive',side_effect=fail_restore):
   with self.assertRaises(OSError): a.settle(self.receipt())
  self.assertIn(a,o.OnceAdmission._held); self.assertTrue(a.active); self.assertFalse(a.settled)
  self.assertIsNotNone(a.lock_fd); os.fstat(a.lock_fd); os.fstat(fd)
  self.assertIn('restore failure',a.marker_restore_error); self.assertFalse((self.root/a.pending_name).exists())
  self.assertEqual((self.root/a.intent_name).read_bytes(),a.intent)
  with self.assertRaises(o.AdmissionRefused): self.acquire(invocation=U2,fd=fd)
  rc,_,err=self.worker(self.script(U2)); self.assertNotEqual(rc,0); self.assertIn('BlockingIOError',err)
if __name__=='__main__': unittest.main()
