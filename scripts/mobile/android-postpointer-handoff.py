"""Run two admitted Android post-publication migration handoff checks.

Usage: python3 android-postpointer-handoff.py ADMISSION_JSON PRIVATE_OUTPUT_PARENT
An operator must first admit the exact SDK artifacts, exclusive acceptance
installation and source hashes. This tool does not install, clear, stop, push,
retry or replay an Android operation. The first original PID must independently
exit before the separate read-only cold check. Unknown execution stops the flow.
"""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import selectors
import shlex
import stat
import subprocess
import sys
import time
import uuid

FIRST = 'actualStrictPostPointerFirstPrepareReconcilesAndTransfersOnce'
CONTROLLER = 'actualControllerStrictPostPointerFirstPrepareReconcilesAndTransfersOnce'
FIRSTS = {FIRST, CONTROLLER}
COLD = 'distinctPidReadOnlyAuditsExactModernCandidateReceiptAndPostRestoreBaseline'
TEST_CLASS = 'com.agenthub.android.NativeMigrationPostPointerStrictHandoffTest'
PACKAGE = 'com.easyai.agenthub.android.acceptance'
HEX = re.compile(r'[a-f0-9]{64}\Z')
BIND_KEYS = {'appApkSha256','testApkSha256','testSourceSha256','sourceGuardSha256','sourceAdmissionSha256','bootUuid','package','uid','run'}
OUTER_KEYS = {'schemaVersion','binding','originalPid','originalStartTicks','firstMethod','coldMethod','runnerRawSha256',
              'fixtureIntentSha256','handoffSha256','cleanupSha256','sourceAdmissionSha256',
              'originalProcessExitObserved','bodyAndCleanupRunnerSucceeded','verification'}
ADMISSION_KEYS = {'schemaVersion','kind','run','serial','adb','package','runner','uid','appApk','testApk','appApkSha256',
                  'testApkSha256','testSource','testSourceSha256','sourceRoot','sourceFiles','sourceGuardSha256',
                  'compileReceipt','compileReceiptSha256','testClass','firstMethod','coldMethod','sdkAdmitted','exclusivePackageFixture'}
REQUIRED_SOURCE_FILES = {'apps/android/src/main/kotlin/com/agenthub/android/'+name+'.kt' for name in (
    'NativeSessionStorage','NativeMigrationLifecycle','NativeMigrationExistingView','NativeMigrationExistingPolicy',
    'NativeMigrationActivation','MobileSession','NativeMigrationReadLifetime','NativeMigrationOriginals',
    'NativeDocumentRecovery','NativeRecentPhotos','NativeRecentPhotoLibrary','NativePhotoAuthorization','NativeInboxOwnership')}

def first_method(binding):
    method=binding.get('firstMethod',FIRST)
    demand(method in FIRSTS,'Explicit first-method allowlist')
    return method


def controller_mode(binding):return first_method(binding)==CONTROLLER

def evidence_version(binding):return 3 if controller_mode(binding) else 2


def demand(ok, why):
    if not ok:
        raise ValueError(why)


def exact(value, kind):
    demand(type(value) is kind, 'JSON type mismatch: '+kind.__name__)
    return value


def keys(value, expected):
    exact(value, dict)
    demand(set(value) == expected, 'Closed schema mismatch')
    return value


def no_duplicates(items):
    out = {}
    for key, value in items:
        demand(key not in out, 'Duplicate JSON member')
        out[key] = value
    return out


def decode(raw):
    demand(0 < len(raw) <= 262144, 'JSON size bound')
    return json.loads(raw.decode('utf-8'), object_pairs_hook=no_duplicates,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError('Non-JSON number')))


def canonical(value):
    def check(v):
        if type(v) is dict:
            for key, child in v.items():
                exact(key,str);check(child)
        else:
            demand(type(v) in (str,int,bool), 'Canonical outer subset only')
    check(value)
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',',':')).encode('utf-8')


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def pin(value):
    exact(value,str);demand(HEX.fullmatch(value) is not None,'SHA-256 required');return value


def file_hash(path):
    digest = hashlib.sha256()
    with open(path,'rb') as source:
        while True:
            chunk=source.read(65536)
            if not chunk:break
            digest.update(chunk)
    return digest.hexdigest()


def private_read(path):
    info=os.lstat(path)
    demand(stat.S_ISREG(info.st_mode) and info.st_uid==os.getuid() and info.st_nlink==1 and
           stat.S_IMODE(info.st_mode)==0o600 and 0<info.st_size<=262144,'Private regular immutable admission required')
    fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
    try:
        before=os.fstat(fd);demand((before.st_dev,before.st_ino)==(info.st_dev,info.st_ino),'Admission changed')
        raw=os.read(fd,262145)
        demand(len(raw)==info.st_size and os.read(fd,1)==b'','Admission size drift')
    finally:os.close(fd)
    after=os.lstat(path)
    demand((after.st_dev,after.st_ino,after.st_size)==(info.st_dev,info.st_ino,info.st_size),'Admission drift')
    return raw


class Evidence:
    def __init__(self,parent,run):
        parent=Path(parent).resolve(strict=True)
        info=os.lstat(parent)
        demand(stat.S_ISDIR(info.st_mode) and info.st_uid==os.getuid() and stat.S_IMODE(info.st_mode)==0o700,'Private output parent required')
        self.root=parent/run
        os.mkdir(self.root,0o700)  # EEXIST forbids resuming/replaying this UUID, even after failure.
        self.fd=os.open(self.root,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
        os.fsync(self.fd)
        parent_fd=os.open(parent,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
        try:os.fsync(parent_fd)
        finally:os.close(parent_fd)

    def save(self,name,raw):
        demand(re.fullmatch(r'[a-z0-9-]+\.(json|raw)',name) is not None,'Evidence basename only')
        demand(0<len(raw)<=2*1024*1024,'Evidence size bound')
        fd=os.open(name,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600,dir_fd=self.fd)
        try:
            os.fchmod(fd,0o600)
            view=memoryview(raw)
            while view:
                n=os.write(fd,view);demand(n>0,'Short evidence write');view=view[n:]
            os.fsync(fd)
        finally:os.close(fd)
        os.fsync(self.fd)
        saved=(self.root/name).read_bytes();demand(saved==raw,'Evidence readback mismatch')
        return sha(raw)

    def close(self):os.close(self.fd)


class Android:
    def __init__(self,a,e):self.a=a;self.e=e

    def command(self,argv,timeout=30,raw_name=None,budget=262144,status=False):
        proc=subprocess.Popen([self.a['adb'],'-s',self.a['serial']]+argv,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
        poll=selectors.DefaultSelector();poll.register(proc.stdout,selectors.EVENT_READ)
        result=bytearray();deadline=time.monotonic()+timeout
        try:
            while True:
                demand(time.monotonic()<deadline,'ADB timeout; Android outcome UNKNOWN; no cold/replay')
                for key,_ in poll.select(min(0.2,max(0,deadline-time.monotonic()))):
                    chunk=os.read(key.fd, min(65536,budget-len(result)+1))
                    if chunk:
                        available=budget-len(result);result.extend(chunk[:available])
                        demand(len(chunk)<=available,'ADB raw bound; retained sample is incomplete; Android outcome UNKNOWN')
                    else:poll.unregister(key.fileobj)
                if proc.poll() is not None and not poll.get_map():break
            if status:return proc.returncode,bytes(result)
            demand(proc.returncode==0,'ADB/runner nonzero; Android outcome UNKNOWN; no cold/replay')
            return bytes(result)
        finally:
            poll.close()
            if proc.poll() is None:
                proc.terminate()
                try:proc.wait(timeout=3)
                except subprocess.TimeoutExpired:proc.kill();proc.wait()
            proc.stdout.close()
            if raw_name is not None:self.e.save(raw_name,bytes(result) or b'<no output>\n')

    def shell(self,args,**kw):return self.command(['shell',shlex.join(args)],**kw)
    def boot(self):
        value=self.shell(['cat','/proc/sys/kernel/random/boot_id']).decode('ascii').strip()
        demand(str(uuid.UUID(value))==value,'Boot UUID required');return value

    def proc_start(self,pid):
        # Same-UID run-as; only explicit ENOENT from two named public CLI reads admits absence.
        # Permission denial, missing tools, unexpected locale/output, or racing partial stat fails.
        path='/proc/'+str(pid)
        code,raw=self.shell(['run-as',self.a['package'],'sh','-c','LC_ALL=C; export LC_ALL; cat '+path+'/stat'],status=True)
        if code!=0:
            demand(raw==('cat: '+path+'/stat: No such file or directory\n').encode('ascii'),'Original proc unreadable/unknown')
            other,entry=self.shell(['run-as',self.a['package'],'sh','-c','LC_ALL=C; export LC_ALL; ls -d '+path],status=True)
            demand(other!=0 and entry==('ls: '+path+': No such file or directory\n').encode('ascii'),'Original proc absence not independently observed')
            return None
        raw=raw.decode('utf-8').strip()
        demand(re.match(r'^'+str(pid)+r' \(',raw) is not None and len(raw)<=8192,'Unknown exact proc stat')
        fields=raw[raw.rfind(')')+2:].split();demand(len(fields)>=20 and fields[19].isdigit(),'Unknown exact startTicks')
        return fields[19]

    def fixture(self,name):
        return self.command(['exec-out','run-as',self.a['package'],'cat','files/P7-postpointer-'+self.a['run']+'/'+name+'.json'])

    def instrument(self,method,common,extra=None,raw_name=None):
        args=['am','instrument','-w','-r','-e','class',self.a['testClass']+'#'+method]
        for key,value in {**common,'postPointerSelectedMethod':method,**(extra or {})}.items():args+=['-e',key,str(value)]
        args+=[self.a['runner']]
        return self.shell(args,timeout=240,raw_name=raw_name,budget=2*1024*1024)


def runner_success(raw,method):
    text=raw.decode('utf-8');statuses=[];row={};codes=[]
    for line in text.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key,sep,value=line[len('INSTRUMENTATION_STATUS: '):].partition('=')
            demand(sep and key not in row,'Malformed/duplicate runner status');row[key]=value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code=int(line.split(': ',1)[1]);statuses.append((code,row));row={}
        elif line.startswith('INSTRUMENTATION_CODE: '):codes.append(int(line.split(': ',1)[1]))
    demand(codes==[-1] and len(statuses)==2 and [code for code,_ in statuses]==[1,0],'Exact one-test JUnit success required')
    for code,row in statuses:
        demand(row.get('class')==TEST_CLASS and row.get('test')==method and row.get('numtests')=='1','Selected original JUnit method mismatch')
        demand('stack' not in row and 'Error' not in row,'JUnit failure status')
    demand(re.search(r'^OK \(1 test\)\s*$',text,re.M) is not None and 'FAILURES!!!' not in text and
           'INSTRUMENTATION_FAILED' not in text and 'shortMsg=' not in text,'Raw JUnit success trailer required')


def admission(raw):
    a=keys(decode(raw),ADMISSION_KEYS)
    demand(a['firstMethod'] in FIRSTS,'First method allowlist')
    version=3 if a['firstMethod']==CONTROLLER else 2
    demand(exact(a['schemaVersion'],int)==version and a['kind']=='root-sdk-admission-v'+str(version),'Root SDK admission/version required')
    demand(exact(a['sdkAdmitted'],bool) and exact(a['exclusivePackageFixture'],bool),'No unadmitted/shared package run')
    for name in ADMISSION_KEYS-{'schemaVersion','uid','sourceFiles','sdkAdmitted','exclusivePackageFixture'}:exact(a[name],str)
    demand(a['package']==PACKAGE and a['testClass']==TEST_CLASS and a['firstMethod'] in FIRSTS and a['coldMethod']==COLD,'Admission target mismatch')
    demand(str(uuid.UUID(a['run']))==a['run'] and exact(a['uid'],int)>0,'Fresh canonical run/UID required')
    demand(re.fullmatch(r'[A-Za-z0-9._:-]{1,128}',a['serial']) is not None,'Explicit device serial required')
    demand(re.fullmatch(r'[A-Za-z0-9._]+/[A-Za-z0-9._]+',a['runner']) is not None,'Explicit instrumentation runner required')
    for path_key,hash_key in [('appApk','appApkSha256'),('testApk','testApkSha256'),('testSource','testSourceSha256'),('compileReceipt','compileReceiptSha256')]:
        demand(file_hash(Path(a[path_key]).resolve(strict=True))==pin(a[hash_key]),'Admitted file hash mismatch: '+path_key)
    root=Path(a['sourceRoot']).resolve(strict=True);exact(a['sourceFiles'],dict)
    controller_files={'apps/android/src/main/kotlin/com/agenthub/android/'+name+'.kt' for name in (
        'NativeMigrationController','NativeMigrationCandidate','NativeMigrationStreamCandidate','NativeMigrationNotificationArchives','NativeMigrationDocuments')}
    required=REQUIRED_SOURCE_FILES|(controller_files if a['firstMethod']==CONTROLLER else set())
    demand(required <= set(a['sourceFiles']) and len(a['sourceFiles'])<=256,'Source guard lacks required actual boundaries')
    for relative,wanted in a['sourceFiles'].items():
        exact(relative,str);path=Path(relative)
        demand(not path.is_absolute() and '..' not in path.parts,'Source guard relative path required')
        actual=(root/path).resolve(strict=True);demand(actual.is_relative_to(root),'Source escaped admitted root')
        demand(file_hash(actual)==pin(wanted),'Admitted source drift: '+relative)
    demand(sha(canonical(a['sourceFiles']))==pin(a['sourceGuardSha256']),'Canonical source guard mismatch')
    return a


# Schema definitions are derived from immutable Test dddc… marker creation sites.
PATHS={'external-inbox','recent-photos','photo-permission','document-results','photo-results','camera-results',
       'document-results/payloads','photo-results/payloads','camera-results/payloads','camera-results/outputs'}
COMMON={'schemaVersion','binding','selectedMethod','run','pid','startTicks'}
TARGET={'uid','package'}
RECEIPT={'candidateId','sha256','records'}
INTENT_KEYS=COMMON|TARGET|{'namespace','keyAlias','scope','anchor'}
PUBLICATION_KEYS=COMMON|TARGET|RECEIPT|{'anchor','originGeneration','operationId','candidateVersion','sourceRecordsSha256',
    'sourceCiphertext','oldGenerationCiphertext','originalCandidateCiphertext','faultPoint','activationBudget','prepareBudget','originalsRetainedIn'}
PROOF_KEYS=COMMON|TARGET|RECEIPT|set('''anchor originGeneration generation candidateVersion observerActualRecords observerRecordsSha256
    afterPointer beforeConfirmation afterConfirmation activationAttempts namedFaultThrows firstPrepareCalls freshConstructions beforeCommitCalls
    boundValidationReturns completedTransferReturns releaseReturnedBeforeFreshConstruction oldEpochBefore oldEpochFrozen oldSessionReference
    freshSessionReference oldStorageReference freshStorageReference sourceRecordsSha256 sourceCiphertext oldGenerationCiphertext originalCandidateCiphertext
    preparedPaths newDraftTarget aliases portsAttempts controllerInstantiated notificationCoordinatorInstantiated localSystemPortAttempts
    realResolverExternalIsolationVerified actualDataScope sourceDerived'''.split())
CLEAN_KEYS=COMMON|TARGET|set('''bodyCompleted testWorkersJoined sessionWorkersJoined writersRetired quiesceAttempted releaseAttempted
    frozenReleaseReturned controllerInstantiated outerProcessExitRequired fallbackRequired cleanupFailureCount unknownAcquisitions acquisitions
    retainedFor postRestoreCiphertext directoryAnchors'''.split())
COLD_KEYS=COMMON|RECEIPT|set('''originalPid originalStartTicks generation candidateVersion existingOnlyReadOnly newWriterHandles
    newSessionConstructions newFrozenAcquisitions activationCalls prepareRestoreCalls controllerInstantiated
    originalPidExitIndependentlyChecked postRestoreCiphertextUnchanged'''.split())
CONTROLLER_PROOF_KEYS=(PROOF_KEYS-{'activationAttempts','firstPrepareCalls'})|set('''controllerOperationId controllerStates controllerPreview quiesceCalls restoreCalls prepareReturns enrollmentAuditOpened
    enrollmentAuditRetireReturns candidateCachePrepared activationCallsSourceDerived prepareCallsSourceDerived controllerWorkerJoinClaimed controllerScopeDisposed'''.split())
CONTROLLER_CLEAN_KEYS=CLEAN_KEYS|{'controllerCompletionObserved','controllerWorkerJoinClaimed','controllerScopeDisposed','controllerDocumentsInventory'}
SCOPE='actual-strict-postpointer-modern-v4-only'
CONTROLLER_SCOPE='actual-controller-strict-postpointer-modern-v4-only'
CONTROLLER_SOURCE_DERIVED='Controller activate/prepare call counts and cached-child identity are source-backed; scalar retire return is not OS-FD closure; ready/canDismiss is not worker join; no Activity/system UI or natural crash'
RETAIN='exact cold audit and Root collection acknowledgement; no replay'
SOURCE_DERIVED='absence of later observer events is publication source order; no recovery.generation counter observed; Controller mapping not exercised'
ORIGINALS_RETAINED='test-owned authenticated source + exact original candidate; no plaintext proof export'


def text(value,limit=8192,empty=False):
    exact(value,str)
    demand((0 if empty else 1)<=len(value.encode('utf-8'))<=limit and all(ord(c)>=32 and ord(c)!=127 for c in value),'Bounded scalar text required')
    return value


def integer(value,minimum=0,maximum=2147483647):
    exact(value,int);demand(minimum<=value<=maximum,'Integer range');return value


def literal(value,expected):
    exact(value,type(expected));demand(value==expected,'Exact schema literal mismatch');return value


def uuid_text(value):
    text(value,36);demand(str(uuid.UUID(value))==value,'Canonical UUID required');return value


def ticks(value):
    text(value,20);demand(re.fullmatch(r'[1-9][0-9]{0,19}',value) is not None and int(value)<=9223372036854775807,'Canonical startTicks required');return value


def absolute(value):
    text(value,4096);path=PurePosixPath(value)
    demand(path.is_absolute() and '..' not in path.parts and str(path)==value and not value.startswith('//'),'Canonical absolute fixture path required')
    return path


def binding_schema(value,expected):
    keys(value,BIND_KEYS|({'firstMethod'} if controller_mode(expected) else set()))
    if controller_mode(expected):literal(value['firstMethod'],CONTROLLER)
    for name in BIND_KEYS-{'uid','run','bootUuid','package'}:pin(value[name])
    integer(value['uid'],1);uuid_text(value['run']);uuid_text(value['bootUuid']);literal(value['package'],PACKAGE)
    demand(canonical(value)==canonical(expected),'Full source/APK/boot/run/package/UID binding mismatch')


def common_doc(value,expected_keys,binding,method,target=True):
    keys(value,expected_keys);literal(value['schemaVersion'],evidence_version(binding));literal(value['selectedMethod'],method)
    binding_schema(value['binding'],binding);literal(value['run'],binding['run'])
    integer(value['pid'],1);ticks(value['startTicks'])
    if target:literal(value['uid'],binding['uid']);literal(value['package'],binding['package'])


def same_process(value,other):
    demand((value['pid'],value['startTicks'])==(other['pid'],other['startTicks']),'Document PID/start join failed')


def receipt_schema(value,expected=None):
    uuid_text(value['candidateId']);pin(value['sha256']);integer(value['records'],1,1024)
    if expected is not None:
        demand(all(value[name]==expected[name] for name in RECEIPT),'Exact original receipt join failed')


def digest_map(value,expected=None,minimum=0,maximum=4096):
    exact(value,dict);demand(minimum<=len(value)<=maximum,'Digest map count')
    for name,digest in value.items():text(name);pin(digest)
    if expected is not None:demand(set(value)==set(expected),'Closed digest map keys mismatch')
    return value


def source_graph(value):
    digest_map(value,minimum=7,maximum=7)
    fixed={'metadata','codex-private-connections-v1','ai-sharing-v1'}
    def only(prefix):
        names=[name for name in value if name.startswith(prefix)]
        demand(len(names)==1,'One original graph node required: '+prefix)
        return names[0][len(prefix):]
    file_id=uuid_text(only('draft-file:'));operation_id=uuid_text(only('submission:'));target=text(only('operations:'))
    demand(set(value)==fixed|{'draft-file:'+file_id,'draft-thumbnail-v1:'+file_id,'submission:'+operation_id,'operations:'+target},'Closed original source graph')
    return {'fileId':file_id,'operationId':operation_id,'originalTarget':target}


def encrypted_name(name):return sha(name.encode('utf-8'))+'.bin'


def relative_digest_map(value,expected=None,minimum=0,maximum=4096):
    digest_map(value,expected,minimum,maximum)
    for name in value:
        parts=PurePosixPath(name).parts
        demand(not name.startswith('/') and str(PurePosixPath(name))==name and '..' not in parts and
               all(re.fullmatch(r'[A-Za-z0-9_-]+',part) for part in parts[:-1]) and
               re.fullmatch(r'[a-f0-9]{64}\.bin',parts[-1]) is not None,'Closed ciphertext path grammar')
    return value


def stat_id(value,uid=None,private=False):
    text(value,100);demand(re.fullmatch(r'[0-9]+:[0-9]+:[0-9]+:[0-9]+',value) is not None,'Directory identity shape')
    numbers=[int(part) for part in value.split(':')]
    demand(all(0<=n<=9223372036854775807 for n in numbers[:2]) and numbers[1]>0,'Directory device/inode range')
    integer(numbers[2]);integer(numbers[3],0,65535)
    demand(stat.S_IFMT(numbers[3])==stat.S_IFDIR,'Directory type required')
    if uid is not None:
        demand(numbers[2]==uid and stat.S_IMODE(numbers[3]) in ({0o700} if private else {0o700,0o771}),'Owned directory UID/mode mismatch')
    return tuple(numbers)


def anchor_schema(anchor,binding):
    exact(anchor,dict);demand(12<=len(anchor)<=256,'Anchor count bound')
    for key,value in anchor.items():text(key,8192);text(value,131072)
    fixed={'package','uid','dataScopeIdentity','run','filesRoot','runDirectory','no_backup','cache','files'}
    literal(anchor['package'],binding['package']);literal(anchor['uid'],str(binding['uid']));literal(anchor['run'],binding['run'])
    files=absolute(anchor['filesRoot']);data=files.parent
    demand(files.name=='files' and data.name==binding['package'],'Actual app data scope shape')
    folder=files/('P7-postpointer-'+binding['run'])
    ancestors={'ancestor:/'}|{'ancestor:'+str(parent) for parent in [files,*files.parents]}
    mounts={key for key in anchor if key.startswith('observed-mount:')}
    demand('observed-mount:/' in mounts and len(mounts)<=128 and set(anchor)==fixed|ancestors|mounts,'Closed ancestor/mount anchor keys')
    for key in ancestors:stat_id(anchor[key],binding['uid'] if key=='ancestor:'+str(files) else None)
    demand(anchor['dataScopeIdentity']==anchor['ancestor:'+str(data)],'Data scope identity/ancestor join')
    stat_id(anchor['dataScopeIdentity'],binding['uid'])
    for key in ('runDirectory','no_backup','cache','files'):stat_id(anchor[key],binding['uid'],True)
    for key in mounts:
        point=absolute(key[len('observed-mount:'):]);line=anchor[key];fields=line.split(' ')
        demand(len(fields)>=10 and all(fields) and '-' in fields,'Mountinfo structure')
        split=fields.index('-');demand(split>=6 and len(fields)-split==4,'Closed mountinfo tail')
        demand(re.fullmatch(r'[1-9][0-9]*',fields[0]) is not None and fields[1].isdigit() and re.fullmatch(r'[0-9]+:[0-9]+',fields[2]) is not None,'Mount numeric fields')
        observed=fields[4].replace('\\040',' ').replace('\\011','\t').replace('\\134','\\')
        demand(str(point)==observed and (str(point)=='/' or files==point or str(files).startswith(str(point)+'/') or str(point).startswith(str(folder)+'/')),'Unexpected mount scope')
    return str(data),str(folder)


def child_alias(base,suffix,generation=None):
    child='agent-hub-child-'+sha((base+'\0'+suffix).encode('utf-8'))[:48]
    return child if generation is None else 'agent-hub-child-'+sha((child+'\0'+generation).encode('utf-8'))[:48]


def aliases_schema(value,run,origin,generation):
    base='p7-postpointer-'+run;source=base+'-source';expected={base,source}
    for owner,versions in ((base,(None,origin,generation)),(source,(None,))):
        for version in versions:
            for suffix in ('migration-candidates','external-inbox','recent-photos','photo-permission','document-results','photo-results','camera-results'):
                alias=child_alias(owner,suffix,version);expected.add(alias)
                if suffix.endswith('-results'):expected.add(child_alias(alias,'payloads'))
                if suffix=='camera-results':expected.add(child_alias(alias,'outputs'))
    keys(value,expected)
    for present in value.values():exact(present,bool)
    literal(value[base],True);literal(value[source],True);literal(value[child_alias(base,'migration-candidates',origin)],True)


def ledger_expected(controller=False):
    children=['child:'+path for path in sorted(PATHS,key=lambda path:(path.count('/'),path))]
    labels=['source-root','source-candidate-cache','seed-root','published-seed-root']+children+['seed-origin-candidate-cache','strict-original-root','original-Session']+children+[
        'strict-origin-candidate-cache','Frozen+actual-lease','production-helper-root+children','fresh-Session']+children+children
    if controller:
        labels.insert(labels.index('production-helper-root+children'),'controller-enrollment-audit')
        labels.insert(len(labels)-len(children),'controller-generation-candidate-cache')
    return {str(index)+':'+label:'registered' for index,label in enumerate(labels,1)}


def cleanup_schema(value,binding,method,identity,anchor,origin,generation,baseline=None,directories=None):
    common_doc(value,CONTROLLER_CLEAN_KEYS if controller_mode(binding) else CLEAN_KEYS,binding,method);same_process(value,identity)
    for name in ('bodyCompleted','testWorkersJoined','sessionWorkersJoined','writersRetired','outerProcessExitRequired'):literal(value[name],True)
    literal(value['controllerInstantiated'],method==CONTROLLER);literal(value['fallbackRequired'],False)
    if controller_mode(binding):
        literal(value['controllerCompletionObserved'],method==CONTROLLER)
        literal(value['controllerWorkerJoinClaimed'],False);literal(value['controllerScopeDisposed'],False)
    for name in ('quiesceAttempted','releaseAttempted','frozenReleaseReturned'):literal(value[name],method in FIRSTS)
    for name in ('cleanupFailureCount','unknownAcquisitions'):literal(value[name],0)
    literal(value['retainedFor'],RETAIN)
    ledger=ledger_expected(controller_mode(binding)) if method in FIRSTS else {}
    keys(value['acquisitions'],set(ledger))
    for key,expected in ledger.items():literal(value['acquisitions'][key],expected)
    # Every family path belongs to the exact old/new installation or original candidate;
    # no source namespace, unknown generation, other child family or raw payload enters here.
    family=relative_digest_map(value['postRestoreCiphertext'],minimum=3,maximum=4096)
    old_prefix='installation/migration-generation-'+origin+'/'
    active_prefix='installation/migration-generation-'+generation+'/'
    candidate_prefix='installation-'+origin+'-migration-candidates/'
    pointer='installation/'+encrypted_name('migration-active-generation-v1')
    demand(pointer in family,'Actual installation pointer baseline missing')
    demand(all(key==pointer or any(key.startswith(prefix) and '/' not in key[len(prefix):] for prefix in (old_prefix,active_prefix,candidate_prefix)) for key in family),'Closed installation ciphertext families')
    if baseline is not None:demand(family==baseline,'Cold cleanup changed post-restore family baseline')
    dirs=value['directoryAnchors'];exact(dirs,dict)
    required={'','no_backup','cache','files','no_backup/source','no_backup/source-migration-candidates','no_backup/installation',
        'no_backup/installation/migration-generation-'+origin,'no_backup/installation/migration-generation-'+generation}
    required|={'no_backup/installation-'+version+'-'+path.replace('/','-') for version in (origin,generation) for path in PATHS}
    required.add('no_backup/installation-'+origin+'-migration-candidates')
    if controller_mode(binding):required|={'no_backup/installation-'+generation+'-migration-candidates','cache/migration-ui-v1'}
    demand(required<=set(dirs)<=required|{'no_backup/voice-recordings-v1'},'Closed owned directory graph')
    for name,identity_stat in dirs.items():text(name,8192,True);stat_id(identity_stat,binding['uid'],True)
    for name,key in (('','runDirectory'),('no_backup','no_backup'),('cache','cache'),('files','files')):
        demand(dirs[name]==anchor[key],'Cleanup/fixture directory identity join')
    if controller_mode(binding):
        documents=keys(value['controllerDocumentsInventory'],{'path','empty','identity'})
        literal(documents['path'],'cache/migration-ui-v1');literal(documents['empty'],True)
        stat_id(documents['identity'],binding['uid'],True)
        literal(documents['identity'],dirs['cache/migration-ui-v1'])
    if directories is not None:demand(dirs==directories,'Cold directory baseline changed')
    return family,dirs


def document(raw):
    demand(type(raw) is bytes and 0<len(raw)<=131072,'Actual Test marker byte bound')
    return decode(raw)


def controller_proof(proof):
    for name in ('quiesceCalls','restoreCalls','prepareReturns','enrollmentAuditOpened','enrollmentAuditRetireReturns',
                 'candidateCachePrepared','activationCallsSourceDerived','prepareCallsSourceDerived'):literal(proof[name],1)
    for name in ('controllerWorkerJoinClaimed','controllerScopeDisposed'):literal(proof[name],False)
    summary=keys(proof['controllerPreview'],{'computers','drafts','operations','attachments','notificationArchives'})
    for name in ('computers','drafts','operations','attachments'):literal(summary[name],1)
    literal(summary['notificationArchives'],0)
    states=keys(proof['controllerStates'],{'preview','applying','restoring','done','closed'})
    shapes={
        'preview':('PREVIEW','READY',True,False,True,True,True,False,True),
        'applying':('APPLYING','NONE',False,True,False,False,True,False,True),
        'restoring':('RESTORING','IMPORTED',False,True,False,False,True,True,False),
        'done':('DONE','IMPORTED',True,False,True,False,True,False,False),
        'closed':('DONE','IMPORTED',True,False,True,False,False,False,False)}
    names=('phase','result','ready','busy','canDismiss','canImport','presented','needsOwnerRestore','hasPreview')
    for name,expected in shapes.items():
        state=keys(states[name],set(names))
        for key,wanted in zip(names,expected):literal(state[key],wanted)


def validate_docs(raws,binding):
    keys(raws,{'fixture-intent','publication-intent','handoff-complete','case1-cleanup'})
    docs={name:document(raw) for name,raw in raws.items()}
    intent=docs['fixture-intent'];publication=docs['publication-intent'];proof=docs['handoff-complete'];clean=docs['case1-cleanup']
    method=first_method(binding);controller=controller_mode(binding)
    for value,shape in ((intent,INTENT_KEYS),(publication,PUBLICATION_KEYS),(proof,CONTROLLER_PROOF_KEYS if controller else PROOF_KEYS)):
        common_doc(value,shape,binding,method);same_process(value,intent)
    literal(intent['namespace'],'installation');literal(intent['keyAlias'],'p7-postpointer-'+binding['run']);literal(intent['scope'],CONTROLLER_SCOPE if controller else SCOPE)
    data,folder=anchor_schema(intent['anchor'],binding)
    for value in (publication,proof):
        demand(value['anchor']==intent['anchor'],'Publication/handoff anchor drift')
        receipt_schema(value);literal(value['candidateVersion'],4);uuid_text(value['originGeneration'])
        source_graph(value['sourceRecordsSha256']);literal(value['records'],len(value['sourceRecordsSha256']))
        source_expected={'source/'+encrypted_name(name) for name in value['sourceRecordsSha256']}
        relative_digest_map(value['sourceCiphertext'],source_expected,7,7)
        relative_digest_map(value['oldGenerationCiphertext'],{'migration-generation-'+value['originGeneration']+'/'+encrypted_name('metadata')},1,1)
        candidate=relative_digest_map(value['originalCandidateCiphertext'],minimum=value['records']+1,maximum=value['records']+1)
        demand(all('/' not in key for key in candidate) and encrypted_name('candidate-v1') in candidate,'Exact original candidate header and streaming chunk graph')
    receipt_schema(proof,publication)
    for name in ('originGeneration','sourceRecordsSha256','sourceCiphertext','oldGenerationCiphertext','originalCandidateCiphertext'):
        demand(proof[name]==publication[name],'Before/after immutable publication join: '+name)
    if controller:
        uuid_text(publication['operationId']);literal(proof['controllerOperationId'],publication['operationId'])
    else:literal(publication['operationId'],binding['run'])
    literal(publication['faultPoint'],'AFTER_POINTER')
    for name in ('activationBudget','prepareBudget'):literal(publication[name],1)
    literal(publication['originalsRetainedIn'],ORIGINALS_RETAINED)
    generation=uuid_text(proof['generation']);origin=proof['originGeneration'];demand(generation!=origin,'Original/new generation must differ')
    integer(proof['observerActualRecords'],proof['records'],1024);pin(proof['observerRecordsSha256'])
    for name in ('afterPointer','namedFaultThrows','freshConstructions','beforeCommitCalls','boundValidationReturns','completedTransferReturns'):literal(proof[name],1)
    if controller:controller_proof(proof)
    else:
        for name in ('activationAttempts','firstPrepareCalls'):literal(proof[name],1)
    for name in ('beforeConfirmation','afterConfirmation','portsAttempts','localSystemPortAttempts'):literal(proof[name],0)
    literal(proof['releaseReturnedBeforeFreshConstruction'],True)
    literal(proof['controllerInstantiated'],controller)
    for name in ('notificationCoordinatorInstantiated','realResolverExternalIsolationVerified'):literal(proof[name],False)
    integer(proof['oldEpochBefore'],0,9223372036854775806);literal(proof['oldEpochFrozen'],proof['oldEpochBefore']+1)
    for name in ('oldSessionReference','freshSessionReference','oldStorageReference','freshStorageReference'):integer(proof[name],-2147483648,2147483647)
    # identityHashCode is diagnostic; collisions are legal. Actual assertNotSame is
    # justified only by the exact admitted Test's raw JUnit success, not hash inequality.
    literal(proof['actualDataScope'],data);literal(proof['sourceDerived'],CONTROLLER_SOURCE_DERIVED if controller else SOURCE_DERIVED)
    paths=keys(proof['preparedPaths'],PATHS)
    for path in PATHS:literal(paths[path],folder+'/no_backup/installation-'+generation+'-'+path.replace('/','-'))
    aliases_schema(proof['aliases'],binding['run'],origin,generation)
    if controller:literal(proof['aliases'][child_alias('p7-postpointer-'+binding['run'],'migration-candidates',generation)],True)
    graph=source_graph(proof['sourceRecordsSha256']);text(proof['newDraftTarget'])
    demand(proof['newDraftTarget']!=graph['originalTarget'],'Independent draft must not reuse immutable operation target')
    family,dirs=cleanup_schema(clean,binding,method,intent,intent['anchor'],origin,generation)
    old_expected={'installation/'+key:value for key,value in proof['oldGenerationCiphertext'].items()}
    candidate_expected={'installation-'+origin+'-migration-candidates/'+key:value for key,value in proof['originalCandidateCiphertext'].items()}
    old_prefix='installation/migration-generation-'+origin+'/'
    candidate_prefix='installation-'+origin+'-migration-candidates/'
    demand({key:value for key,value in family.items() if key.startswith(old_prefix)}==old_expected,'Cleanup old-generation exact key/digest conservation')
    demand({key:value for key,value in family.items() if key.startswith(candidate_prefix)}==candidate_expected,'Cleanup original candidate exact key/digest conservation')
    active_prefix='installation/migration-generation-'+generation+'/'
    active={key:value for key,value in family.items() if key.startswith(active_prefix)}
    demand(proof['observerActualRecords']<=len(active)<=1024,'Actual published record count/post-restore family conservation')
    demand({active_prefix+encrypted_name(name) for name in proof['sourceRecordsSha256']}<=set(active),'Imported immutable graph/attachment nodes missing from active baseline')
    return {'intent':intent,'publication':publication,'proof':proof,'cleanup':clean,'family':family,'directories':dirs,'graph':graph}


def validate_cold_docs(raws,binding,first,outer,outer_raw,outer_sha,first_raws):
    keys(raws,{'cold-audit-complete','case2-cleanup'})
    audit=document(raws['cold-audit-complete']);clean=document(raws['case2-cleanup']);proof=first['proof'];intent=first['intent']
    common_doc(audit,COLD_KEYS,binding,COLD,target=False);receipt_schema(audit,proof)
    literal(audit['originalPid'],intent['pid']);literal(audit['originalStartTicks'],intent['startTicks'])
    demand(audit['pid']!=intent['pid'],'Cold must be a distinct PID')
    literal(audit['generation'],proof['generation']);literal(audit['candidateVersion'],4)
    for name in ('existingOnlyReadOnly','originalPidExitIndependentlyChecked','postRestoreCiphertextUnchanged'):literal(audit[name],True)
    for name in ('newWriterHandles','newSessionConstructions','newFrozenAcquisitions','activationCalls','prepareRestoreCalls'):literal(audit[name],0)
    literal(audit['controllerInstantiated'],False)
    cleanup_schema(clean,binding,COLD,audit,intent['anchor'],proof['originGeneration'],proof['generation'],first['family'],first['directories'])
    # Rejoin the exact original immutable Host receipt and retained raw originals;
    # app cold flags alone never create this admission.
    keys(outer,OUTER_KEYS);binding_schema(outer['binding'],binding);literal(outer['schemaVersion'],evidence_version(binding))
    literal(outer['originalPid'],intent['pid']);literal(outer['originalStartTicks'],intent['startTicks'])
    literal(outer['firstMethod'],first_method(binding));literal(outer['coldMethod'],COLD)
    literal(outer['verification'],'exact-pid-start-exit-same-boot')
    for name in ('originalProcessExitObserved','bodyAndCleanupRunnerSucceeded'):literal(outer[name],True)
    for name in ('runnerRawSha256','fixtureIntentSha256','handoffSha256','cleanupSha256','sourceAdmissionSha256'):pin(outer[name])
    literal(outer['sourceAdmissionSha256'],binding['sourceAdmissionSha256'])
    for field,name in (('fixtureIntentSha256','fixture-intent'),('handoffSha256','handoff-complete'),('cleanupSha256','case1-cleanup')):
        literal(outer[field],sha(first_raws[name]))
    demand(canonical(outer)==outer_raw and sha(outer_raw)==outer_sha,'Immutable outer canonical receipt changed')
    return audit

def main():
    demand(len(sys.argv)==3,'Usage: runner ADMISSION_JSON PRIVATE_OUTPUT_PARENT')
    raw=private_read(Path(sys.argv[1]).resolve(strict=True));a=admission(raw)
    e=Evidence(sys.argv[2],a['run'])
    try:
        e.save('source-admission.json',raw)
        d=Android(a,e);boot=d.boot()
        demand(d.shell(['run-as',a['package'],'id','-u']).decode('ascii').strip()==str(a['uid']),'run-as exact UID mismatch')
        uid_rows=d.shell(['pm','list','packages','-U',a['package']]).decode('ascii').splitlines()
        uid_exact=[row for row in uid_rows if row.startswith('package:'+a['package']+' uid:')]
        demand(uid_exact==['package:'+a['package']+' uid:'+str(a['uid'])],'Installed target UID mismatch')
        listed=d.shell(['pm','list','instrumentation']).decode('ascii').splitlines()
        demand('instrumentation:'+a['runner']+' (target='+a['package']+')' in listed,'Instrumentation target mismatch')
        binding={key:a[key] for key in ('appApkSha256','testApkSha256','testSourceSha256','sourceGuardSha256','package','uid','run')}
        if a['firstMethod']==CONTROLLER:binding['firstMethod']=CONTROLLER
        binding.update(bootUuid=boot,sourceAdmissionSha256=sha(raw));keys(binding,BIND_KEYS|({'firstMethod'} if a['firstMethod']==CONTROLLER else set()))
        common={'postPointerRun':a['run'],'postPointerAppApkSha256':a['appApkSha256'],'postPointerTestApkSha256':a['testApkSha256'],
            'postPointerTestSourceSha256':a['testSourceSha256'],'postPointerSourceGuardSha256':a['sourceGuardSha256'],'postPointerSourceAdmissionSha256':sha(raw),'postPointerFirstMethod':a['firstMethod']}
        method=a['firstMethod']
        e.save('first-invocation-intent.json',canonical({'schemaVersion':evidence_version(binding),'binding':binding,'selectedMethod':method,'noReplay':True}))
        first=d.instrument(method,common,raw_name='first-junit.raw');runner_success(first,method)
        raws={name:d.fixture(name) for name in ('fixture-intent','publication-intent','handoff-complete','case1-cleanup')}
        for name,value in raws.items():e.save(name+'.json',value)
        first_docs=validate_docs(raws,binding);pid=first_docs['intent']['pid'];ticks=first_docs['intent']['startTicks']
        first_validation=e.save('first-doc-validation.json',canonical({'schemaVersion':evidence_version(binding),'binding':binding,'publicationIntentSha256':sha(raws['publication-intent']),
            'fixtureIntentSha256':sha(raws['fixture-intent']),'handoffSha256':sha(raws['handoff-complete']),'cleanupSha256':sha(raws['case1-cleanup']),
            'runnerRawSha256':sha(first),'originalTarget':first_docs['graph']['originalTarget'],'originalOperationId':first_docs['graph']['operationId'],
            'originalFileId':first_docs['graph']['fileId'],'validated':True}))
        # Only actual same-boot absence/changed start counts; permission/race errors throw.
        deadline=time.monotonic()+30
        while True:
            demand(d.boot()==boot,'Boot changed; no same-run cold admission')
            current=d.proc_start(pid)
            if current is None or current!=ticks:break
            demand(time.monotonic()<deadline,'Original PID still alive; Root exact-process fallback required; no cold')
            time.sleep(0.25)
        outer={'schemaVersion':evidence_version(binding),'binding':binding,'originalPid':pid,'originalStartTicks':ticks,'firstMethod':method,'coldMethod':COLD,
            'runnerRawSha256':sha(first),'fixtureIntentSha256':sha(raws['fixture-intent']),'handoffSha256':sha(raws['handoff-complete']),
            'cleanupSha256':sha(raws['case1-cleanup']),'sourceAdmissionSha256':sha(raw),'originalProcessExitObserved':True,
            'bodyAndCleanupRunnerSucceeded':True,'verification':'exact-pid-start-exit-same-boot'}
        keys(outer,OUTER_KEYS);receipt=canonical(outer);outer_sha=e.save('outer-first-pid-exit.json',receipt)
        # Host receipt stays outside Android. No adb push or Android file fsync is assumed.
        admission(raw)  # No source/APK drift before invocation two; never repairs/replays first.
        cold=d.instrument(COLD,common,extra={'postPointerOuterReceipt':receipt.decode('utf-8'),'postPointerOuterExitSha256':outer_sha},raw_name='cold-junit.raw')
        runner_success(cold,COLD)
        cold_raws={name:d.fixture(name) for name in ('cold-audit-complete','case2-cleanup')}
        for name,value in cold_raws.items():e.save(name+'.json',value)
        audit=validate_cold_docs(cold_raws,binding,first_docs,outer,receipt,outer_sha,raws)
        literal(outer['runnerRawSha256'],sha(first))
        demand(d.boot()==boot,'Post-cold boot changed')
        demand(d.proc_start(pid)!=ticks,'Original PID/start live again after cold')
        current_cold=d.proc_start(audit['pid'])
        demand(current_cold is None or current_cold==audit['startTicks'],'Live cold PID/start mismatch')
        demand(d.boot()==boot,'Boot changed during post-cold proc observation')
        complete={'schemaVersion':evidence_version(binding),'binding':binding,'outerReceiptSha256':outer_sha,'coldRunnerRawSha256':sha(cold),
            'coldAuditRawSha256':sha(cold_raws['cold-audit-complete']),'coldCleanupRawSha256':sha(cold_raws['case2-cleanup']),
            'publicationIntentRawSha256':sha(raws['publication-intent']),'firstValidationSha256':first_validation,
            'firstRunnerRawSha256':sha(first),'originalPid':pid,'originalStartTicks':ticks,'coldPid':audit['pid'],'coldStartTicks':audit['startTicks'],
            'completed':True,'noReplay':True,'postColdOriginalExitRechecked':True,'coldProcIfLiveMatched':True}
        keys(complete,{'schemaVersion','binding','outerReceiptSha256','coldRunnerRawSha256','coldAuditRawSha256','coldCleanupRawSha256',
            'publicationIntentRawSha256','firstValidationSha256','firstRunnerRawSha256','originalPid','originalStartTicks','coldPid','coldStartTicks',
            'completed','noReplay','postColdOriginalExitRechecked','coldProcIfLiveMatched'})
        e.save('outer-run-complete.json',canonical(complete))
        print(str(e.root))
    except BaseException as error:
        # New immutable failure record only. Never alter successful receipt or invoke first again.
        e.save('outer-run-failure.json',canonical({'schemaVersion':2,'run':a['run'],'failureType':type(error).__name__,'coldForbidden':True,
            'noReplay':True,'fallback':'Root must query exact original PID/start/boot; this file is not physical FD close evidence'}))
        raise
    finally:e.close()


if __name__=='__main__':main()
