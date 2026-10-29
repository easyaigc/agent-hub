from pathlib import Path
import subprocess,hashlib,json,xml.etree.ElementTree as E,gzip,shutil,itertools,copy,os
r=Path.cwd();o=Path(os.environ["P8_VECTOR_OUTPUT"]).resolve();o.mkdir(parents=True,exist_ok=True);s=(r/'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeEasyWorkConnectionPresentationTest.kt').read_text();pure=s[s.index('internal data class P8PreferenceSelectionAx'):s.index('/** Actual AgentHubApp')];values=['automatic','lan-only','relay-only'];labels={'en':dict(zip(values,['Automatic','Direct only','Relay only'])),'zh-Hans':dict(zip(values,['自动','仅直连','仅中继']))};sha=lambda b:hashlib.sha256(b).hexdigest();cases=[]
def baseline(expected='automatic',language='en'):
 hashes={k:sha(v.encode()) for k,v in labels[language].items()};rows=[]
 for j,value in enumerate(values):
  owner=10+j*10;text=owner+1;rows.append({'value':value,'ownerId':owner,'textId':text,'windowId':47,'path':[text,owner],'labelHash':hashes[value],'selection':{'tag':'connection-preference-'+value,'rolePresent':False,'role':None,'toggleableStatePresent':False,'selectedPresent':True,'composeSelected':value==expected,'nativeClass':'android.view.View','checkable':True,'checked':value==expected,'selected':False}})
 return {'name':'baseline','expected':expected,'labels':hashes,'rows':rows,'accepted':True}
for language in labels:
 for expected in values:
  for order in itertools.permutations(range(3)):
   c=baseline(expected,language);c.update(name=language+' '+expected+' order'+''.join(map(str,order)),rows=[c['rows'][j] for j in order]);cases.append(c)
def neg(name,mutate):
 c=baseline();c.update(name=name,accepted=False);mutate(c);cases.append(c)
def rowset(c,key,val,index=1):c['rows'][index][key]=val
def flag(c,key,val,index=1):c['rows'][index]['selection'][key]=val
neg('all unchecked',lambda c:[(flag(c,'checked',False,j),flag(c,'composeSelected',False,j)) for j in range(3)])
neg('two checked',lambda c:(flag(c,'checked',True),flag(c,'composeSelected',True)))
neg('three checked',lambda c:[(flag(c,'checked',True,j),flag(c,'composeSelected',True,j)) for j in range(3)])
for key,val in [('checkable',False),('checkable',None),('checked',None),('selected',True),('selected',None),('nativeClass','android.widget.TextView'),('selectedPresent',False),('composeSelected',None),('toggleableStatePresent',True),('rolePresent',True)]:neg('inactive '+key+' '+str(val),lambda c,k=key,v=val:flag(c,k,v))
for role in ['Tab','Switch','RadioButton']:neg('closed profile rejects '+role,lambda c,v=role:(flag(c,'rolePresent',True),flag(c,'role',v)))
neg('wrong known tag for owner value',lambda c:flag(c,'tag','connection-preference-relay-only'))
neg('unknown tag',lambda c:flag(c,'tag','connection-preference-other'))
neg('unknown value',lambda c:rowset(c,'value','other'))
neg('unknown expected',lambda c:c.update(expected='other'))
neg('missing option',lambda c:c['rows'].pop())
neg('extra option',lambda c:c['rows'].append(copy.deepcopy(c['rows'][1])))
def duplicate_value(c):
 rowset(c,'value','lan-only',2);flag(c,'tag','connection-preference-lan-only',2);rowset(c,'labelHash',c['labels']['lan-only'],2)
neg('duplicate value with distinct native nodes',duplicate_value)
neg('duplicate owner',lambda c:(rowset(c,'ownerId',10),c['rows'][1]['path'].__setitem__(-1,10)))
neg('duplicate text',lambda c:(rowset(c,'textId',11),c['rows'][1]['path'].__setitem__(0,11)))
neg('mixed popup windows',lambda c:rowset(c,'windowId',48))
neg('invalid popup window',lambda c:rowset(c,'windowId',-1))
neg('wrong native label',lambda c:rowset(c,'labelHash','f'*64))
neg('cross-language native label',lambda c:rowset(c,'labelHash',sha('仅直连'.encode())))
neg('duplicate expected labels',lambda c:c['labels'].update({'lan-only':c['labels']['automatic']}))
neg('missing expected label',lambda c:c['labels'].pop('lan-only'))
neg('extra expected label',lambda c:c['labels'].update(other='e'*64))
neg('invalid expected hash',lambda c:c['labels'].update({'lan-only':'not-a-hash'}))
neg('reversed native path',lambda c:rowset(c,'path',[20,21]))
neg('wrong path terminal',lambda c:rowset(c,'path',[21,999]))
neg('wrong path start',lambda c:rowset(c,'path',[999,20]))
neg('path passes sibling owner',lambda c:rowset(c,'path',[21,10,20]))
neg('path cycle',lambda c:rowset(c,'path',[21,22,21,20]))
neg('empty path',lambda c:rowset(c,'path',[]))
neg('self-projected text owner',lambda c:(rowset(c,'textId',20),rowset(c,'path',[20])))
neg('path depth beyond original cap',lambda c:rowset(c,'path',[21,*range(100,115),20]))
neg('negative owner',lambda c:(rowset(c,'ownerId',-1),rowset(c,'path',[21,-1])))
neg('negative text',lambda c:(rowset(c,'textId',-1),rowset(c,'path',[-1,20])))
neg('independent expected differs from otherwise consistent native state',lambda c:c.update(rows=baseline('lan-only')['rows']))
neg('Compose contradicts expected but native otherwise right',lambda c:flag(c,'composeSelected',False,0))
lit=lambda v:'null' if v is None else 'true' if v is True else 'false' if v is False else json.dumps(v,ensure_ascii=False) if isinstance(v,str) else str(v)
def ctor(row):
 sel='P8PreferenceSelectionAx('+','.join(lit(x) for x in row['selection'].values())+')';return 'P8PreferenceOptionAx('+','.join(lit(row[x]) for x in ['value','ownerId','textId','windowId'])+',listOf('+','.join(map(str,row['path']))+'),'+lit(row['labelHash'])+','+sel+')'
header='internal data class V(val name:String,val expected:String,val labels:Map<String,String>,val rows:List<P8PreferenceOptionAx>,val accepted:Boolean)\n';functions=[]
for j,c in enumerate(cases):functions.append('private fun v'+str(j)+'()=V('+lit(c['name'])+','+lit(c['expected'])+',mapOf('+','.join(lit(k)+' to '+lit(v) for k,v in c['labels'].items())+'),listOf('+','.join(ctor(x) for x in c['rows'])+'),'+lit(c['accepted'])+')')
function=pure[pure.index('internal fun p8ExclusivePreferenceState'):];mutations=[('remove closed native profile','p8ExactPreferenceSelection(row.selection,row.value==expected)','true'),('remove same-window gate','options.map {it.windowId}.toSet().size==1','true'),('remove distinct owners','options.map {it.ownerId}.toSet().size==3','true'),('remove native label association','row.labelHash==labelHashes[row.value]','true'),('remove path terminal','row.nativeTextToOwnerPath.last()==row.ownerId&&',''),('derive expected from observed checked','row.value==expected','row.selection.checked==true'),('remove tag/value association','row.selection.tag=="connection-preference-${row.value}"&&',''),('remove exact known option set','options.map {it.value}.toSet()==values','true')];mutants=[]
for j,(name,a,b) in enumerate(mutations):
 assert a in function;f=function.replace('p8ExclusivePreferenceState','exclusiveMutant'+str(j)).replace(a,b);functions.append(f);mutants.append({'name':name,'sourceSha256':sha(f.encode())})
harness=pure+'\n'+header+'\n'.join(functions)+'\nfun main(){val cases=listOf('+','.join('v'+str(j)+'()' for j in range(len(cases)))+')\nfor(c in cases)check(p8ExclusivePreferenceState(c.expected,c.labels,c.rows)==c.accepted){c.name}\n'
for j in range(len(mutants)):harness+=f'val bad{j}=cases.count{{exclusiveMutant{j}(it.expected,it.labels,it.rows)!=it.accepted}};check(bad{j}>0);println("mutant{j} mismatches="+bad{j})\n'
harness+=f'println("passed={len(cases)}; positives=36; negatives={len(cases)-36}; mutantsRejected=8; native=0")\n}}\n'
scratch=o/'exclusive-jvm';assert not scratch.is_symlink();scratch.mkdir(exist_ok=True);source=scratch/'ExclusiveVectors.kt';source.write_text(harness);classes=scratch/'classes';classes.mkdir(exist_ok=True);cache=Path(os.environ['GRADLE_USER_HOME'])/'caches/modules-2/files-2.1';jar=lambda g,n,v:next((cache/g/n/v).glob('*/*.jar'));compiler=jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.4.21');pom=next((cache/'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.4.21').glob('*/*.pom'));ns={'m':'http://maven.apache.org/POM/4.0.0'};cp=[compiler]
for x in E.parse(pom).findall('.//m:dependency',ns):cp.append(jar(x.findtext('m:groupId',namespaces=ns),x.findtext('m:artifactId',namespaces=ns),x.findtext('m:version',namespaces=ns)))
stdlib=jar('org.jetbrains.kotlin','kotlin-stdlib','2.4.21');annotations=jar('org.jetbrains','annotations','13.0');cp.append(annotations);java=Path(subprocess.check_output(['/usr/libexec/java_home','-v','17'],text=True).strip())/'bin/java';logs=[]
def run(a):
 q=subprocess.run([str(x) for x in a],capture_output=True);logs.append((' '.join(str(x) for x in a)+'\n').encode()+q.stdout+q.stderr);(o/'exclusive-vector-progress.log.gz').write_bytes(gzip.compress(b'\n'.join(logs),mtime=0));assert q.returncode==0,q.stderr.decode();return q.stdout
run([java,'-cp',':'.join(str(x) for x in cp),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',f'{stdlib}:{annotations}','-d',classes,source]);result=run([java,'-cp',f'{classes}:{stdlib}','ExclusiveVectorsKt']).decode().strip();assert f'passed={len(cases)};' in result
for name,b in [('exclusive-vector-source.kt.gz',harness.encode()),('exclusive-vectors.json.gz',json.dumps({'cases':cases,'mutations':mutants},ensure_ascii=False,indent=2).encode()),('exclusive-vectors.log.gz',b'\n'.join(logs))]:z=gzip.compress(b,mtime=0);(o/name).write_bytes(z);assert gzip.decompress(z)==b
assert (o/'exclusive-vector-progress.log.gz').read_bytes()==(o/'exclusive-vectors.log.gz').read_bytes();(o/'exclusive-vector-progress.log.gz').unlink();size=sum(x.stat().st_size for x in scratch.rglob('*') if x.is_file());q=subprocess.run(['lsof','-Fpn','+D',str(scratch)],capture_output=True);assert q.returncode==1 and not q.stdout and not q.stderr;shutil.rmtree(scratch)
v={'status':'passed','vectors':len(cases),'positiveVectors':36,'negativeVectors':len(cases)-36,'mutationControlsRejected':8,'actualPureHelperSha256':sha(pure.encode()),'expandedVectorsRawSha256':sha(json.dumps({'cases':cases,'mutations':mutants},ensure_ascii=False,indent=2).encode()),'result':result,'kind':'Actual extracted role-closed profile and triplet aggregator JVM vectors; no Android UI/persistence/native execution','negativeNames':[x['name'] for x in cases if not x['accepted']],'mutations':mutants,'newNativeExecutions':0,'scratchBytesRemoved':size,'scratchRemoved':not scratch.exists()};(o/'exclusive-vector-verification.json').write_text(json.dumps(v,indent=2)+'\n');print(json.dumps(v))
