import struct
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import fixed_parent_collector as c
import fixed_parent_ownership as o
import os
import subprocess
import sys
import fcntl

def record_worker(proc,start):
 path=os.environ.get('P9_WORKER_LOG')
 if path:
  import json
  with open(path,'a') as f: f.write(json.dumps({'pid':proc.pid,'startTimeNs':start,'command':proc.args,'returncode':proc.returncode,'reaped':proc.poll() is not None})+'\n')

def pkt(ch, b): return struct.pack('<BI',ch,len(b))+b
def cap(k,b): return c.Capture(k,b,b'',True,True,0,True,'capture-complete-unverified',None)

class Vectors(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory(); self.directory=Path(self.tmp.name).resolve(); self.admissions=[]; self.roots=[]
 def admission(self, serial='fake'):
  name='root-'+serial; fd=os.open(self.directory/name,os.O_CREAT|os.O_EXCL|os.O_RDWR,0o600); fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB); self.roots.append(fd)
  a=o.OnceAdmission.acquire(self.directory,serial,'PS','00000000-0000-0000-0000-000000000007','a'*64,name,fd); self.admissions.append(a); return a
 def tearDown(self):
  # These are offline fake sockets and private test records only; no runtime exists.
  for a in self.admissions:
   a._close()
   if a in o.OnceAdmission._held: o.OnceAdmission._held.remove(a)
  for fd in self.roots: os.close(fd)
  self.tmp.cleanup()

 def test_streams(self):
  for err in (b'',b'err\0\xff'):
   raw=pkt(1,b'a\0\xff')+(pkt(2,err) if err else b'')+pkt(1,b'bc')+pkt(3,b'\0')
   for size in (1,2,3,5,7,len(raw)):
    d=c.ShellV2(c.Diagnostic.PS)
    for i in range(0,len(raw),size): d.feed(raw[i:i+size])
    self.assertFalse(d.result(True).stdout_eof)
    d.eof(); r=d.result(True)
    self.assertEqual((r.stdout,r.stderr,r.remote_exit),(b'a\0\xffbc',err,0))
    self.assertTrue(r.stdout_eof and r.stderr_eof)
    self.assertEqual(r.outcome,'known-diagnostic-failure' if err else 'capture-complete-unverified')
 def test_failure_and_incomplete(self):
  for code in (1,126,255):
   d=c.ShellV2(c.Diagnostic.PS); d.feed(pkt(3,bytes([code]))); r=d.result(False,'timeout')
   self.assertEqual(r.outcome,'known-shell-service-failure'); self.assertFalse(r.host_settled or r.stdout_eof); self.assertEqual(r.remote_exit,code)
  for raw in (b'',b'\1\3'):
   d=c.ShellV2(c.Diagnostic.PS)
   if raw: d.feed(raw)
   d.eof(); self.assertEqual(d.result(True).outcome,'unknown-incomplete')
  d=c.ShellV2(c.Diagnostic.PS); d.feed(pkt(3,b'\0')); d.eof()
  self.assertEqual(d.result(False).outcome,'unknown-incomplete'); self.assertEqual(d.result(True).outcome,'capture-complete-unverified')
 def test_protocol_errors(self):
  for raw in (pkt(3,b'\0')+pkt(3,b'\0'),pkt(3,b'\0')+pkt(1,b'x'),pkt(9,b'x'),struct.pack('<BI',1,c.Diagnostic.PS.cap+1)):
   d=c.ShellV2(c.Diagnostic.PS)
   with self.assertRaises(c.Refused): d.feed(raw)
   self.assertIsNotNone(d.protocol_error)
 def test_lock_sealed(self):
  class Lease:
   serial='fake'; immutable_binding_sha256='a'*64; invocation_ids=('00000000-0000-0000-0000-000000000007','00000000-0000-0000-0000-000000000008')
   def check(self): raise c.Refused('controlled')
  self.assertFalse(c.FixedCollector._owner.locked()); saved=list(c.FixedCollector._sealed)
  try:
   obj=c.FixedCollector(Lease(),self.admission())
   with patch.object(c.time,'monotonic',return_value=10):
    r=obj.capture(c.Diagnostic.PS,12)
    self.assertEqual(r.outcome,'unknown-incomplete'); self.assertTrue(obj.unresolved and c.FixedCollector._owner.locked()); self.assertIn(obj,c.FixedCollector._sealed)
    with self.assertRaises(c.Refused): obj.capture(c.Diagnostic.PS,12)
    with self.assertRaises(c.Refused): c.FixedCollector(Lease(),obj.admission).capture(c.Diagnostic.AM,12)
  finally:
   c.FixedCollector._sealed[:]=saved
   if c.FixedCollector._owner.locked(): c.FixedCollector._owner.release()
 def test_success_fake_sockets(self):
  class Lease:
   serial='fake'; immutable_binding_sha256='a'*64; invocation_ids=('00000000-0000-0000-0000-000000000007','00000000-0000-0000-0000-000000000008')
   def check(self): pass
  class Fake:
   def __init__(self,parts): self.parts=list(parts); self.closed=False
   def settimeout(self,x): pass
   def sendall(self,x): pass
   def close(self): self.closed=True
   def recv(self,n):
    p=self.parts.pop(0)
    if len(p)>n: self.parts.insert(0,p[n:])
    return p[:n]
  a=Fake([b'OKAY',b'0008',b'shell_v2']); b=Fake([b'OKAY',b'OKAY',pkt(1,b'ok\n')+pkt(3,b'\0'),b'']); obj=c.FixedCollector(Lease(),self.admission())
  def connect(deadline):
   h=[a,b][len(obj.handles)]; obj.handles.append(h); return h
  with patch.object(c.time,'monotonic',return_value=10), patch.object(obj,'_connect',side_effect=connect): r=obj.capture(c.Diagnostic.PS,12)
  self.assertEqual(r.outcome,'capture-complete-unverified'); self.assertTrue(a.closed and b.closed); self.assertFalse(c.FixedCollector._owner.locked())
 def test_ps(self):
  raw=b'UID PID NAME\n0 1 init\n1000 2 system_server\n10001 3 witness\n'
  self.assertEqual(c.parse_ps(cap(c.Diagnostic.PS,raw),10001,3,'witness')[3],(10001,'witness'))
  for bad in (raw.replace(b'0 1 init\n',b''),raw.replace(b'1000 2 system_server\n',b''),raw.replace(b'10001 3 witness\n',b''),raw+b'10002 3 another\n',raw+b'bad\n'):
   with self.assertRaises(c.Refused): c.parse_ps(cap(c.Diagnostic.PS,bad),10001,3,'witness')
 def test_am(self):
  header=b'ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n'
  for raw in (header,header+b'  Active instrumentation:\n',header+b'  ProcessRecord{abc 3:pkg/u0a1}\n  Active instrumentation:\n    Instrumentation #0: invalid\n'):
   with self.assertRaises(c.Refused): c.parse_am(cap(c.Diagnostic.AM,raw),{3:(10001,'witness')},10001,3,'pkg','pkg.Runner')
 def test_proc(self):
  status=b'Pid: 3\nUid: 10001 10001 10001 10001\nGid: 10001 10001 10001 10001\nGroups: 1 2\n'; stat=b'3 (x) S '+b'0 '*18+b'42\n'; context=b'u:r:untrusted_app:s0\n'; mount=b'1 2 0:1 / /proc rw - proc proc rw\n'; args=[status,stat,stat,context,mount]
  r=c.parse_proc_identity(*args,3); self.assertFalse(r['nativePassed'] or r['diagnosticCallerBound'])
  for i in range(5):
   bad=list(args); bad[i]=b''
   with self.assertRaises(c.Refused): c.parse_proc_identity(*bad,3)
  for bad in ([status.replace(b'Groups: 1 2\n',b''),stat,stat,context,mount],[status,stat,stat.replace(b'42\n',b'43\n'),context,mount],[status.replace(b'Pid: 3',b'Pid: 4'),stat,stat,context,mount]):
   with self.assertRaises(c.Refused): c.parse_proc_identity(*bad,3)
 def test_json_unbound_and_files(self):
  for raw in (b'{"x":1,"x":2}',b'{"x":NaN}'):
   with self.assertRaises(c.Refused): c.json_bytes(raw)
  blank=dict.fromkeys(c.JOIN_KEYS)
  with self.assertRaises(c.Refused): c.readonly_join(blank,blank,blank,b'{}',c.sha(b'{}'),cap(c.Diagnostic.PS,b'x\n'),cap(c.Diagnostic.AM,b'x\n'))
  with tempfile.TemporaryDirectory() as directory:
   p=Path(directory).resolve()/'proof'; p.write_bytes(b'proof'); self.assertEqual(c.pinned_file(p,c.sha(b'proof'),100),b'proof')
   link=Path(directory).resolve()/'link'; link.symlink_to(p)
   with self.assertRaises(c.Refused): c.pinned_file(link,c.sha(b'proof'),100)
   p.write_bytes(b'changed')
   with self.assertRaises(c.Refused): c.pinned_file(p,c.sha(b'proof'),100)

 def test_join_exact_links(self):
  import copy, json
  u=lambda n: '00000000-0000-0000-0000-%012d'%n
  h='a'*64
  binding={'device':{'serial':'s','avd':'a','api':33,'abi':'arm64','bootId':u(1),'bootCount':1,'buildFingerprint':'f'},'sourceApkSignerTuple':dict.fromkeys(('sourceSha256','appSha256','runnerSha256','examinerSha256','signerSetSha256'),h),'roleUids':{'app':10001,'runnerPackage':10002,'examiner':10003,'systemUi':1000,'runnerExecution':10001},'cfgUuid':u(2),'nativeNonce':u(3),'immutableIntent':{'lease':u(4),'attempt':u(5),'ticket':u(6),'target':dict.fromkeys(('agentId','hostId','workspaceId','runtimeId'),'x'),'thread':'t','operationId':'o','argv':{k.name:k.command.decode().split(' ') for k in c.Diagnostic},'firstProofSha256':h,'newDraftProofSha256':h,'planSha256':h,'receiptSha256':h},'protectionBaseline':{'families':h,'roots':h,'keys':h,'leaseAttemptTokenClaim':h,'inventory':{'complete':True,'countsAreLowerBounds':False,'kind':'content-hash','familyCount':0,'directoryCount':0,'fileCount':0,'byteCount':0,'entryCount':0,'maximumDepth':0,'fullEntriesSha256':h}},'captureInvocationIds':{'PS':u(7),'AM':u(8)},'positiveWitness':{'uid':10003,'pid':3,'name':'witness','package':'pkg','component':'pkg.Runner'}}
  bound=c.sha(json.dumps(binding,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode())
  import dataclasses
  psraw=b'UID PID NAME\n0 1 init\n1000 2 system_server\n10003 3 witness\n'
  amraw=(b'ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n  All known processes:\n    ProcessRecord{abc 3:pkg/u0a3}\n  Active instrumentation:\n    Instrumentation #0: ActiveInstrumentation{abc {pkg/pkg.Runner} 1 procs}\n      mClass=ComponentInfo{pkg/pkg.Runner} mFinished=false\n      mRunningProcesses:\n        #0: ProcessRecord{abc 3:pkg/u0a3}\n      mTargetProcesses=[pkg]\n      mTargetInfo=ApplicationInfo{abc pkg}\n        uid=10003\n      mArguments={}\n')
  ps=dataclasses.replace(cap(c.Diagnostic.PS,psraw),binding_sha256=bound,invocation_id=u(7),ownership_settled=True); am=dataclasses.replace(cap(c.Diagnostic.AM,amraw),binding_sha256=bound,invocation_id=u(8),ownership_settled=True)
  proof={'version':2,'source':'native-postconsumer-readonly-v2-full-parent-binding','binding':binding,'parentCaptureReceipts':{'PS':ps.receipt(),'AM':am.receipt()},'protectionBaselineBefore':binding['protectionBaseline'],'protectionBaselineAfter':binding['protectionBaseline'],'allOriginalBytesUnchanged':True,'transportCalls':0,'mutationAttempts':0,'passed':True}
  raw=json.dumps(proof).encode()
  def join(b=binding, p=ps, a=am, data=raw, digest=None): return c.readonly_join(binding,b,binding,data,digest or c.sha(data),p,a)
  result=join(); self.assertFalse(result['nativePassed']); self.assertEqual(result['structuralJoin'],'consistent-unverified')
  self.assertFalse(result['visibilityReview']['globalAmStructureVerified']); self.assertFalse(result['visibilityReview']['nativePassed'])
  for receipt in (ps.receipt(),am.receipt()):
   self.assertEqual(receipt['stderrBytes'],0); self.assertEqual(receipt['stderrSha256'],c.sha(b'')); self.assertTrue(receipt['stdoutTransportEof'] and receipt['stderrTransportEof']); self.assertIsNone(receipt['remoteStdoutPipeEof']); self.assertIsNone(receipt['remoteStderrPipeEof'])
  for path,value in ((('device','bootId'),u(9)),(('sourceApkSignerTuple','appSha256'),'b'*64),(('roleUids','examiner'),10004),(('immutableIntent','argv'),{}),(('protectionBaseline','families'),'b'*64),(('device','api'),True),(('protectionBaseline','inventory','complete'),False)):
   changed=copy.deepcopy(binding); target=changed
   for key in path[:-1]: target=target[key]
   target[path[-1]]=value
   with self.assertRaises(c.Refused): join(b=changed)
  changed=copy.deepcopy(binding); changed['cfgUuid']=u(9)
  with self.assertRaises(c.Refused): join(b=changed)
  for key,value in (('source','old'),('transportCalls',False),('mutationAttempts',True),('passed',False),('protectionBaselineAfter',{})):
   changed=copy.deepcopy(proof); changed[key]=value
   with self.assertRaises(c.Refused): join(data=json.dumps(changed).encode())
  for bad in (amraw.replace(b'  All known processes:\n',b''),amraw.replace(b'ProcessRecord{abc 3:pkg/u0a3}',b'broken',1),amraw.replace(b'mFinished=false',b'mFinished=maybe')):
   with self.assertRaises(c.Refused): c.visibility_review(ps,dataclasses.replace(am,stdout=bad),binding['positiveWitness'],10001)
  drift=copy.deepcopy(binding); drift['device']['bootCount']=2
  with self.assertRaises(c.Refused): join(b=drift)
  with self.assertRaises(c.Refused): join(p=dataclasses.replace(ps,stdout=b'changed\n'))
  with self.assertRaises(c.Refused): join(a=dataclasses.replace(am,invocation_id=u(7)))
  with self.assertRaises(c.Refused): join(digest='b'*64)
  altered=copy.deepcopy(proof); altered['parentCaptureReceipts']['PS']['stdoutTransportEof']=False
  with self.assertRaises(c.Refused): join(data=json.dumps(altered).encode())

 def test_submitted_timeout_and_exception_seal(self):
  class Lease:
   serial='fake'; immutable_binding_sha256='a'*64; invocation_ids=('00000000-0000-0000-0000-000000000007','00000000-0000-0000-0000-000000000008')
   def check(self): pass
  class Fake:
   def __init__(self,parts): self.parts=list(parts); self.closed=False; self.sent=[]
   def settimeout(self,x): pass
   def sendall(self,x): self.sent.append(x)
   def close(self): self.closed=True
   def recv(self,n):
    item=self.parts.pop(0)
    if isinstance(item,Exception): raise item
    if len(item)>n: self.parts.insert(0,item[n:])
    return item[:n]
  for i, terminal in enumerate((TimeoutError('timeout'),RuntimeError('unexpected'))):
   a=Fake([b'OKAY',b'0008',b'shell_v2']); b=Fake([b'OKAY',b'OKAY',pkt(3,b'\x7e'),terminal]); lease=Lease(); lease.serial='fake'+str(i); obj=c.FixedCollector(lease,self.admission(lease.serial)); saved=list(c.FixedCollector._sealed)
   def connect(deadline):
    h=[a,b][len(obj.handles)]; obj.handles.append(h); return h
   try:
    with patch.object(c.time,'monotonic',return_value=10), patch.object(obj,'_connect',side_effect=connect):
     if isinstance(terminal,RuntimeError):
      with self.assertRaises(RuntimeError): obj.capture(c.Diagnostic.PS,12)
     else:
      r=obj.capture(c.Diagnostic.PS,12); self.assertEqual(r.outcome,'known-shell-service-failure'); self.assertFalse(r.stdout_eof or r.host_settled)
     self.assertTrue(obj.unresolved and c.FixedCollector._owner.locked()); self.assertIs(c.FixedCollector._sealed[-1],obj); self.assertIs(obj.handles[-1],b); self.assertFalse(b.closed); self.assertIsNotNone(obj.admission.lock_fd); self.assertTrue((self.directory/obj.admission.pending_name).exists()); self.assertIn(obj.admission,o.OnceAdmission._held)
     self.assertTrue(any(b'shell,v2,raw:' in msg for msg in b.sent)); before=list(b.sent)
     with self.assertRaises(c.Refused): obj.capture(c.Diagnostic.PS,12)
     self.assertEqual(b.sent,before)
   finally:
    c.FixedCollector._sealed[:]=saved
    if c.FixedCollector._owner.locked(): c.FixedCollector._owner.release()
 def test_pinned_ancestor_and_hardlink(self):
  import os
  with tempfile.TemporaryDirectory() as directory:
   root=Path(directory).resolve(); real=root/'real'; real.mkdir(); p=real/'proof'; p.write_bytes(b'original'); link=root/'alias'; link.symlink_to(real, target_is_directory=True)
   with self.assertRaises(c.Refused): c.pinned_file(link/'proof',c.sha(b'original'),100)
   hard=root/'hard'; os.link(p,hard)
   with self.assertRaises(c.Refused): c.pinned_file(p,c.sha(b'original'),100)
   hard.unlink(); p.write_bytes(b'changed')
   with self.assertRaises(c.Refused): c.pinned_file(p,c.sha(b'original'),100)
 def test_fifo_final_open_is_bounded(self):
  fifo=self.directory/'fifo'; os.mkfifo(fifo)
  script="import sys; from pathlib import Path; import fixed_parent_collector as c; c.pinned_file(Path(sys.argv[1]), 'a'*64, 100)"
  proc=subprocess.Popen([sys.executable,'-c',script,str(fifo)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,env={**os.environ,'PYTHONDONTWRITEBYTECODE':'1'}); start=__import__('time').time_ns()
  try: stdout,stderr=proc.communicate(timeout=3)
  finally:
   if proc.poll() is None: proc.kill(); proc.communicate()
   record_worker(proc,start)
  self.assertNotEqual(proc.returncode,0)
 def test_partial_overflow_and_eof(self):
  for raw in (pkt(1,b'abc')[:-1], b'\x01\x03'):
   d=c.ShellV2(c.Diagnostic.PS); d.feed(raw); d.eof(); self.assertEqual(d.result(True).outcome,'unknown-incomplete')
  d=c.ShellV2(c.Diagnostic.PS); d.eof(); self.assertEqual(d.result(True).outcome,'unknown-incomplete')
  d=c.ShellV2(c.Diagnostic.PS); d.stdout.extend(b'x'*c.Diagnostic.PS.cap)
  with self.assertRaises(c.Refused): d.feed(pkt(2,b'x'))

if __name__=='__main__': unittest.main()
