from pathlib import Path
import subprocess,hashlib,json,xml.etree.ElementTree as E,gzip,shutil,os
r=Path.cwd();o=Path(os.environ["P8_PIXEL_BOUNDARY_OUTPUT"]).resolve();o.mkdir(parents=True,exist_ok=True);s=(r/'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeEasyWorkConnectionPresentationTest.kt').read_text();pure=s[s.index('internal fun p8MenuPixelBudget'):s.index('/** Actual AgentHubApp')];scratch=o/'pixel-jvm';assert not scratch.exists();scratch.mkdir();classes=scratch/'classes';classes.mkdir();source=scratch/'PixelBoundaries.kt'
harness=pure+r'''
private class Frame {val disposed=java.util.concurrent.atomic.AtomicInteger(0);fun recycle(){check(disposed.incrementAndGet()==1)}}
fun main(){
 var cases=0
 fun test(name:String,run:()->Unit){try{run();cases++}catch(t:Throwable){throw AssertionError(name,t)}}
 val phases=linkedMapOf("known-initial-automatic" to "automatic","after-click-and-form-reopen-lan-only" to "lan-only","after-click-and-form-reopen-relay-only" to "relay-only","after-click-and-form-reopen-automatic" to "automatic")
 for((phase,expected) in phases){test("valid exact phase "+phase){check(p8MenuPixelBudget(phase,expected,294,420,emptySet(),0,0))};test("wrong selected for phase "+phase){check(!p8MenuPixelBudget(phase,"other",294,420,emptySet(),0,0))};test("duplicate phase "+phase){check(!p8MenuPixelBudget(phase,expected,294,420,setOf(phase),0,0))}}
 fun ok(w:Int=294,h:Int=420,done:Set<String> =emptySet(),pixels:Long=0,bytes:Long=0,phase:String="known-initial-automatic")=p8MenuPixelBudget(phase,"automatic",w,h,done,pixels,bytes)
 test("zero width"){check(!ok(w=0))};test("negative height"){check(!ok(h=-1))};test("axis cap"){check(!ok(w=4097,h=1))}
 test("Int overflow dimensions rejected"){check(!ok(w=Int.MAX_VALUE,h=Int.MAX_VALUE))};test("state pixel cap"){check(!ok(w=1025,h=1024))};test("exact state pixel cap"){check(ok(w=1024,h=1024))}
 test("case pixel cap"){check(!ok(pixels=4_194_304))};test("exact final case pixel cap"){check(ok(w=1024,h=1024,done=setOf("after-click-and-form-reopen-lan-only","after-click-and-form-reopen-relay-only","after-click-and-form-reopen-automatic"),pixels=3_145_728))}
 test("negative pixels"){check(!ok(pixels=-1))};test("Long overflow pixels rejected"){check(!ok(pixels=Long.MAX_VALUE))};test("negative bytes"){check(!ok(bytes=-1))};test("case byte cap"){check(!ok(bytes=8_388_609))};test("unknown phase"){check(!ok(phase="unknown"))};test("unknown previous phase"){check(!ok(done=setOf("unknown")))};test("all four states already done"){check(!ok(done=phases.keys))}
 test("bounded PNG exact bytes"){val out=P8BoundedPng(4){true};out.write(byteArrayOf(1,2));out.write(3);out.write(4);check(out.bytes().contentEquals(byteArrayOf(1,2,3,4)))}
 test("bounded PNG array overrun rejected without partial write"){val out=P8BoundedPng(2){true};out.write(1);try{out.write(byteArrayOf(2,3));error("accepted")}catch(t:IllegalStateException){};check(out.bytes().contentEquals(byteArrayOf(1)))}
 test("bounded PNG single byte overrun"){val out=P8BoundedPng(1){true};out.write(1);try{out.write(2);error("accepted")}catch(t:IllegalStateException){};check(out.bytes().size==1)}
 test("PNG stale budget prevents write"){var active=true;val out=P8BoundedPng(8){active};out.write(1);active=false;try{out.write(byteArrayOf(2));error("accepted")}catch(t:IllegalStateException){};try{out.bytes();error("accepted")}catch(t:IllegalStateException){}}
 test("PNG zero capacity rejects output"){val out=P8BoundedPng(0){true};try{out.write(1);error("accepted")}catch(t:IllegalStateException){}}
 test("PNG invalid slice"){val out=P8BoundedPng(8){true};try{out.write(byteArrayOf(1),-1,1);error("accepted")}catch(t:IllegalStateException){};check(out.bytes().isEmpty())}
 test("successful take transfers ownership"){val f=Frame();val slot=P8PixelDelivery<Frame>{it.recycle()};slot.offer(f);val got=slot.take();slot.close();check(got===f&&f.disposed.get()==0);got.recycle();slot.close();check(f.disposed.get()==1)}
 test("timeout before callback releases late result"){val f=Frame();val slot=P8PixelDelivery<Frame>{it.recycle()};slot.close();slot.offer(f);slot.close();check(f.disposed.get()==1)}
 test("completion before timeout disposed once"){val f=Frame();val slot=P8PixelDelivery<Frame>{it.recycle()};slot.offer(f);slot.close();slot.close();check(f.disposed.get()==1)}
 test("distinct duplicate callback disposed"){val a=Frame();val b=Frame();val slot=P8PixelDelivery<Frame>{it.recycle()};slot.offer(a);slot.offer(b);check(b.disposed.get()==1&&a.disposed.get()==0);slot.take().recycle();slot.close();check(a.disposed.get()==1)}
 test("late callback after consumed result"){val a=Frame();val b=Frame();val slot=P8PixelDelivery<Frame>{it.recycle()};slot.offer(a);slot.take().recycle();slot.offer(b);slot.close();check(a.disposed.get()==1&&b.disposed.get()==1)}
 test("take before callback rejected"){val slot=P8PixelDelivery<Frame>{it.recycle()};try{slot.take();error("accepted")}catch(t:IllegalStateException){};slot.close()}
 test("take after timeout rejected"){val slot=P8PixelDelivery<Frame>{it.recycle()};slot.close();try{slot.take();error("accepted")}catch(t:IllegalStateException){}}
 test("racing timeout/completion 250 rounds"){repeat(250){val f=Frame();val slot=P8PixelDelivery<Frame>{it.recycle()};val start=java.util.concurrent.CountDownLatch(1);val t=Thread{start.await();slot.offer(f)};t.start();start.countDown();slot.close();t.join(1000);check(!t.isAlive);slot.close();check(f.disposed.get()==1)}}
 test("racing take/close 250 rounds"){repeat(250){val f=Frame();val slot=P8PixelDelivery<Frame>{it.recycle()};slot.offer(f);val start=java.util.concurrent.CountDownLatch(1);val t=Thread{start.await();slot.close()};t.start();start.countDown();try{slot.take().recycle()}catch(t:IllegalStateException){};t.join(1000);check(!t.isAlive);slot.close();check(f.disposed.get()==1)}}
 println("passed="+cases+"; deliveryRaceRounds=500; nativePixels=0")
}
'''
source.write_text(harness);cache=Path(os.environ['GRADLE_USER_HOME'])/'caches/modules-2/files-2.1';jar=lambda g,n,v:next((cache/g/n/v).glob('*/*.jar'));compiler=jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.4.21');pom=next((cache/'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.4.21').glob('*/*.pom'));ns={'m':'http://maven.apache.org/POM/4.0.0'};cp=[compiler]
for x in E.parse(pom).findall('.//m:dependency',ns):cp.append(jar(x.findtext('m:groupId',namespaces=ns),x.findtext('m:artifactId',namespaces=ns),x.findtext('m:version',namespaces=ns)))
stdlib=jar('org.jetbrains.kotlin','kotlin-stdlib','2.4.21');annotations=jar('org.jetbrains','annotations','13.0');cp.append(annotations);java=Path(subprocess.check_output(['/usr/libexec/java_home','-v','17'],text=True).strip())/'bin/java';logs=[]
def run(a):
 q=subprocess.run([str(x) for x in a],capture_output=True);logs.append((' '.join(str(x) for x in a)+'\n').encode()+q.stdout+q.stderr);assert q.returncode==0,q.stderr.decode();return q.stdout
run([java,'-cp',':'.join(str(x) for x in cp),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',f'{stdlib}:{annotations}','-d',classes,source]);result=run([java,'-cp',f'{classes}:{stdlib}','PixelBoundariesKt']).decode().strip();count=int(result.split(';')[0].split('=')[1]);assert count==42
for name,b in [('pixel-boundaries-source.kt.gz',harness.encode()),('pixel-boundaries.log.gz',b'\n'.join(logs))]:z=gzip.compress(b,mtime=0);(o/name).write_bytes(z);assert gzip.decompress(z)==b
size=sum(x.stat().st_size for x in scratch.rglob('*') if x.is_file());q=subprocess.run(['lsof','-Fpn','+D',str(scratch)],capture_output=True);assert q.returncode==1 and not q.stdout and not q.stderr;shutil.rmtree(scratch);v={'status':'passed','cases':count,'deliveryRaceRounds':500,'actualExtractedPixelHelpersSha256':hashlib.sha256(pure.encode()).hexdigest(),'result':result,'kind':'Actual pure pixel budget/PNG output/late-delivery ownership JVM checks; no Android framework/Bitmap/native pixel execution','scratchBytesRemoved':size,'scratchRemoved':not scratch.exists(),'newNativeExecutions':0,'newImages':0};(o/'pixel-boundaries.json').write_text(json.dumps(v,indent=2)+'\n');print(json.dumps(v))
