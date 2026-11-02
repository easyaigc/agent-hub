package com.agenthub.android
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileDescriptor
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.Locale
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
/** Source proposal; fixed retired synthetic pair only. */
class NativeRetiredSavedSelectionFixturePairTest {
 private companion object {
  const val PACKAGE="com.easyai.agenthub.android.acceptance"
  const val RUN="cfa02692-bc4f-4356-8290-2b1f44fdae9f"
  const val ORIGINAL_OPERATION="2ef76759-a613-4071-a40f-7cd7d414262c"
  const val METHOD="originalQueuedReadCannotSelectAfterTargetSwitchOrSavedConnectionReplacement"
  const val SOURCE="61d63d0a5ff963c15671a7a37e8579c760206ab504f4bae873c103aa0e32182e"
  const val APP="83f1fd78e33f985113a5a91dbec884db6d09d1b89710bf96f431586f2ab4967c"
  const val RUNNER="1053e07b4d4eed5ede4f2b348882a21f734b757f842e1393c2e5c834082a1596"
  const val ARG="p8RetiredSavedSelectionAdmission"
  const val PROFILE="p8-retired-saved-selection-pair-v1"
  val LABELS=listOf("stale-target","stale-replacement")
  val STAT=setOf("dev","ino","mode","nlink","uid","gid","size","mtimeSec","mtimeNsec","ctimeSec","ctimeNsec")
  val CORE=setOf("dev","ino","mode","uid","gid")
 }
 private fun keys(o: JSONObject)=o.keys().asSequence().toSet()
 private fun exact(o: JSONObject,vararg k: String) {require(keys(o)==k.toSet())}
 private fun text(o: JSONObject,k: String)=requireNotNull(o.get(k) as? String)
 private fun number(o: JSONObject,k: String): Long {
  val n=o.get(k);require(n is Int||n is Long);return (n as Number).toLong()
 }
 private fun flag(o: JSONObject,k: String,v: Boolean) {require(o.get(k) is Boolean&&o.get(k)==v)}
 private fun uuid(s: String)=s.also {require(UUID.fromString(it).toString()==it)}
 private fun hash(s: String)=s.also {require(it.matches(Regex("[a-f0-9]{64}")))}
 private fun sha(b: ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString("") {"%02x".format(it)}
 private fun canonical(v: Any?): String=when(v) {
  null,JSONObject.NULL->"null"
  is JSONObject->keys(v).sorted().joinToString(",","{","}") {JSONObject.quote(it)+":"+canonical(v.get(it))}
  is JSONArray->(0 until v.length()).joinToString(",","[","]") {canonical(v.get(it))}
  is String->JSONObject.quote(v)
  is Boolean,is Int,is Long->v.toString()
  else->error("Unsupported JSON scalar")
 }
 private fun obj(s: String)=JSONObject(s).also {require(canonical(it)==s)}
 private fun array(s: String)=JSONArray(s).also {require(canonical(it)==s)}
 private fun bytes(o: JSONObject)=canonical(o).toByteArray(Charsets.UTF_8)
 private fun stamp(s: StructStat)=JSONObject().put("dev",s.st_dev).put("ino",s.st_ino).put("mode",s.st_mode)
  .put("nlink",s.st_nlink).put("uid",s.st_uid).put("gid",s.st_gid).put("size",s.st_size)
  .put("mtimeSec",s.st_mtim.tv_sec).put("mtimeNsec",s.st_mtim.tv_nsec)
  .put("ctimeSec",s.st_ctim.tv_sec).put("ctimeNsec",s.st_ctim.tv_nsec)
 private fun same(a: JSONObject,b: JSONObject,fields: Set<String> = STAT) {require(fields.all {number(a,it)==number(b,it)})}
 private data class Node(val file: File,val fd: FileDescriptor,val initial: JSONObject,val dir: SecureDirectoryStream<Path>?)
 private data class Fixture(val label: String,val id: String,val nodes: Map<String,Node>) {
  val alias get()="p8-saved-selection-$id"
  val leaf get()="P8-saved-selection-$id"
 }
 private val held=mutableListOf<Node>()
 private fun checkNode(n: Node,full: Boolean=true) {
  val fd=stamp(Os.fstat(n.fd));val path=stamp(Os.lstat(n.file.path));same(fd,path)
  same(n.initial,fd,if(full)STAT else CORE)
  if(n.dir!=null) {
   val a=requireNotNull(n.dir.getFileAttributeView(BasicFileAttributeView::class.java)).readAttributes()
   val b=Files.readAttributes(n.file.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS)
   require(a.isDirectory&&!a.isSymbolicLink&&a.fileKey()!=null&&a.fileKey()==b.fileKey())
  }
 }
 private fun open(file: File,parent: Node?=null): Node {
  require(file.absoluteFile==file.canonicalFile)
  val fd=Os.open(file.path,OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW,0)
  var stream: java.nio.file.DirectoryStream<Path>?=null
  try {
   val s=Os.fstat(fd);require(OsConstants.S_ISDIR(s.st_mode)&&s.st_uid==10174)
   stream=if(parent==null)Files.newDirectoryStream(file.toPath())
    else requireNotNull(parent.dir).newDirectoryStream(Paths.get(file.name),NOFOLLOW_LINKS)
   require(stream is SecureDirectoryStream<*>) {"Android provider has no SecureDirectoryStream; no fallback"}
   @Suppress("UNCHECKED_CAST") val secure=stream as SecureDirectoryStream<Path>
   val n=Node(file,fd,stamp(s),secure);checkNode(n);held.add(n);return n
  }catch(t: Throwable){try {stream?.close()}finally {Os.close(fd)};throw t}
 }
 private fun regular(file: File,limit: Int,owner: Boolean=true): Pair<Node,ByteArray> {
  require(file.absoluteFile==file.canonicalFile)
  val fd=Os.open(file.path,OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW,0)
  try {
   val s=Os.fstat(fd)
   require(OsConstants.S_ISREG(s.st_mode)&&s.st_nlink==1L&&s.st_size in 1..limit.toLong())
   if(owner)require(s.st_uid==10174)
   val n=Node(file,fd,stamp(s),null);checkNode(n)
   val out=ByteArray(s.st_size.toInt());var offset=0
   while(offset<out.size){val read=Os.read(fd,out,offset,out.size-offset);require(read>0);offset+=read}
   require(Os.read(fd,ByteArray(1),0,1)==0);checkNode(n);held.add(n);return n to out
  }catch(t: Throwable){Os.close(fd);throw t}
 }
 private fun apk(file: File): String {
  val fd=Os.open(file.path,OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW,0)
  try {
   val before=stamp(Os.fstat(fd));val s=Os.fstat(fd)
   require(OsConstants.S_ISREG(s.st_mode)&&s.st_nlink==1L&&s.st_size in 1..536_870_912L)
   val digest=MessageDigest.getInstance("SHA-256");val b=ByteArray(65536);var total=0L
   while(true){val r=Os.read(fd,b,0,b.size);if(r==0)break;require(r>0);digest.update(b,0,r);total+=r}
   require(total==s.st_size);same(before,stamp(Os.fstat(fd)));same(before,stamp(Os.lstat(file.path)))
   return digest.digest().joinToString("") {"%02x".format(it)}
  }finally {Os.close(fd)}
 }
 private fun absent(file: File): Boolean=try {Os.lstat(file.path);false}catch(e: ErrnoException) {
  if(e.errno==OsConstants.ENOENT)true else throw e
 }
 private fun missing(parent: Node,name: String): Boolean=try {
  requireNotNull(parent.dir!!.getFileAttributeView(Paths.get(name),BasicFileAttributeView::class.java,NOFOLLOW_LINKS)).readAttributes();false
 }catch(_: NoSuchFileException){true}
 private fun originalBuild(o: JSONObject) {
  exact(o,"sourceSha256","appSha256","runnerSha256")
  require(text(o,"sourceSha256")==SOURCE&&text(o,"appSha256")==APP&&text(o,"runnerSha256")==RUNNER)
 }
 private fun evidence(raw: ByteArray,f: Fixture,actual: Map<String,String>) {
  val e=JSONObject(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(raw)).toString())
  exact(e,"version","run","fixture","method","label","build","keyAlias","namespace","initializationCompleted",
   "protectedCiphertexts","sessionAndOwnedWorkJoined","writerRetired","cleanupPassed","cleanupFailureType",
   "MainActivityUsed","applicationRestoreStarted","discoveryInstanceCreated","defaultFamilyEnumeratedByFixture",
   "defaultFamilyIndependentlyVerified","typedFixtureReadCalls","networkOrModelAcceptance","physicalUiAcceptance",
   "fixtureAndKeyRetainedForIndependentReadback")
  require(number(e,"version")==1L&&text(e,"run")==RUN&&text(e,"fixture")==f.id&&text(e,"method")==METHOD)
  require(text(e,"label")==f.label&&text(e,"keyAlias")==f.alias&&text(e,"namespace")=="installation")
  originalBuild(e.getJSONObject("build"));require(e.get("cleanupFailureType")==JSONObject.NULL)
  for(k in listOf("initializationCompleted","sessionAndOwnedWorkJoined","writerRetired","cleanupPassed","fixtureAndKeyRetainedForIndependentReadback"))flag(e,k,true)
  for(k in listOf("MainActivityUsed","applicationRestoreStarted","discoveryInstanceCreated","defaultFamilyEnumeratedByFixture",
   "defaultFamilyIndependentlyVerified","networkOrModelAcceptance","physicalUiAcceptance"))flag(e,k,false)
  require(number(e,"typedFixtureReadCalls") in 1L..32L)
  val p=e.getJSONObject("protectedCiphertexts");val names=keys(p)
  require(names.size==3&&names.containsAll(listOf("easywork-credential:fixture-ref-${f.id}","easywork-device-grant:${f.id}")))
  require(names.count {it.startsWith("operations:")&&it.length in 12..4096}==1)
  for(k in names)require(actual["secure/installation/"+sha(k.toByteArray(Charsets.UTF_8))+".bin"]==hash(text(p,k)))
 }
 private fun capture(n: Node,path: String,h: String?): JSONObject {
  val s=n.initial
  fun time(k: String)=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSSSSS xx",Locale.ROOT).format(
   Instant.ofEpochSecond(number(s,k+"Sec"),number(s,k+"Nsec")).atOffset(ZoneOffset.ofHours(8)))
  val o=JSONObject().put("path",path).put("type",if(n.dir!=null)"directory" else "file")
   .put("mode","0o"+(number(s,"mode").toInt() and 4095).toString(8)).put("mtime",time("mtime")).put("ctime",time("ctime"))
  for(k in listOf("dev","ino","nlink","uid","size"))o.put(k,number(s,k))
  if(n.dir==null)o.put("sha256",requireNotNull(h));return o
 }
 private fun preflight(acceptance: Node,admitted: JSONObject,label: String): Fixture {
  exact(admitted,"label","evidenceSha256","inventorySha256","inventoryJson")
  require(text(admitted,"label")==label);hash(text(admitted,"evidenceSha256"));hash(text(admitted,"inventorySha256"))
  val raw=text(admitted,"inventoryJson");require(raw.toByteArray().size<=32768&&sha(raw.toByteArray())==text(admitted,"inventorySha256"))
  val inv=array(raw);require(inv.length()==9)
  val expected=linkedMapOf<String,JSONObject>()
  for(i in 0 until inv.length()) {
   val e=inv.getJSONObject(i);val path=text(e,"path")
   require(path in setOf(".","secure","secure/installation","fixture-evidence.json")||path.matches(Regex("secure/installation/[a-f0-9]{64}\\.bin")))
   require(expected.put(path,e)==null)
   val fields=setOf("path","type","mode","mtime","ctime","dev","ino","nlink","uid","size")
   require(keys(e)==fields+(if(path in setOf(".","secure","secure/installation"))emptySet()else setOf("sha256")))
  }
  require(expected.keys.toList()==expected.keys.sorted()&&expected.keys.containsAll(listOf(".","secure","secure/installation","fixture-evidence.json")))
  val id=UUID.nameUUIDFromBytes((RUN+":"+METHOD+":"+label).toByteArray(Charsets.UTF_8)).toString()
  val root=open(File(acceptance.file,"P8-saved-selection-$id"),acceptance)
  val secure=open(File(root.file,"secure"),root);val installation=open(File(secure.file,"installation"),secure)
  val nodes=linkedMapOf("." to root,"secure" to secure,"secure/installation" to installation)
  val actual=JSONArray();val hashes=mutableMapOf<String,String>();var total=0L;var proof=ByteArray(0)
  for(path in expected.keys) {
   val n=if(path in nodes)nodes.getValue(path)else {
    val (file,data)=regular(File(root.file,path),if(path=="fixture-evidence.json")16384 else 32768)
    hashes[path]=sha(data);if(path=="fixture-evidence.json")proof=data
    nodes[path]=file;file
   }
   if(n.dir==null)total+=number(n.initial,"size");require(total<=262144)
   actual.put(capture(n,path,hashes[path]))
  }
  require(canonical(actual)==raw&&sha(proof)==text(admitted,"evidenceSha256"))
  for((path,n)in nodes.filterValues {it.dir!=null}) {
   val children=expected.keys.filter {it!="."&&it.substringBeforeLast('/',".")==path}.map {it.substringAfterLast('/')}.toSet()
   val listed=mutableSetOf<String>();for(p in n.dir!!) {require(listed.size<9&&listed.add(p.fileName.toString()))}
   require(listed==children)
  }
  for((path,n)in nodes)if(path!=".") {
   val parent=nodes.getValue(path.substringBeforeLast('/',"."))
   val a=requireNotNull(parent.dir!!.getFileAttributeView(Paths.get(n.file.name),BasicFileAttributeView::class.java,NOFOLLOW_LINKS)).readAttributes()
   val b=Files.readAttributes(n.file.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS)
   require(a.fileKey()!=null&&a.fileKey()==b.fileKey())
  }
  val f=Fixture(label,id,nodes);evidence(proof,f,hashes);nodes.values.forEach {checkNode(it)};return f
 }
 @Test fun collectRetiredSavedSelectionFixturePair() {
  val args=InstrumentationRegistry.getArguments();val raw=args.getString(ARG)
  assumeTrue("Root must explicitly schedule the fixed retired pair collector",raw!=null)
  require(args.keySet().filter {it.startsWith("p8RetiredSavedSelection")}.toSet()==setOf(ARG))
  require(args.getString("class")==javaClass.name+"#collectRetiredSavedSelectionFixturePair")
  require(raw!!.toByteArray().size<=73728)
  val admission=obj(raw);exact(admission,"version","profile","cleanupOperation","original","current","fixtures","externalCaptureSha256","retirementBoundary")
  require(number(admission,"version")==1L&&text(admission,"profile")==PROFILE)
  val op=uuid(text(admission,"cleanupOperation"));require(op!=RUN&&op!=ORIGINAL_OPERATION)
  hash(text(admission,"externalCaptureSha256"))
  require(text(admission,"retirementBoundary")=="joined-retired-external-uid-quiescent-unique-collector")
  val original=admission.getJSONObject("original");exact(original,"run","operationId","method","build")
  require(uuid(text(original,"run"))==RUN&&uuid(text(original,"operationId"))==ORIGINAL_OPERATION&&text(original,"method")==METHOD);originalBuild(original.getJSONObject("build"))
  val current=admission.getJSONObject("current")
  exact(current,"sdk","appPackage","runnerPackage","appUid","runnerUid","appSha256","runnerSha256","sdkSourceSha256","collectorSourceSha256")
  require(number(current,"sdk")==33L&&Build.VERSION.SDK_INT==33)
  require(text(current,"appPackage")==PACKAGE&&text(current,"runnerPackage")==PACKAGE+".test")
  require(number(current,"appUid")==10174L&&number(current,"runnerUid")==10175L&&Process.myUid()==10174)
  for(k in listOf("appSha256","runnerSha256","sdkSourceSha256","collectorSourceSha256"))hash(text(current,k))
  val instrumentation=InstrumentationRegistry.getInstrumentation();val target=instrumentation.targetContext;val runner=instrumentation.context
  require(target.packageName==PACKAGE&&runner.packageName==PACKAGE+".test")
  require(target.applicationInfo.uid==10174&&runner.applicationInfo.uid==10175)
  require(target.packageManager.getApplicationInfo(PACKAGE,0).uid==10174&&target.packageManager.getApplicationInfo(PACKAGE+".test",0).uid==10175)
  require(target.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE!=0)
  require(target.applicationInfo.className==null)
  require(apk(File(target.applicationInfo.sourceDir))==text(current,"appSha256"))
  require(apk(File(runner.applicationInfo.sourceDir))==text(current,"runnerSha256"))
  val inputs=admission.getJSONArray("fixtures");require(inputs.length()==2)
  var acceptance: Node?=null;var intent: File?=null;var result: File?=null;var journal: FileDescriptor?=null
  var mayRecord=false;var passed=false;var stage="open-owned-acceptance";var failure: Throwable?=null
  val confirmed=JSONArray()
  fun record(event: JSONObject) {
   val b=(canonical(event)+"\n").toByteArray(Charsets.UTF_8);val fd=requireNotNull(journal);var at=0
   while(at<b.size){val n=Os.write(fd,b,at,b.size-at);require(n>0);at+=n};Os.fsync(fd)
  }
  fun exclusive(file: File,payload: ByteArray): FileDescriptor {
   checkNode(requireNotNull(acceptance),false)
   val fd=Os.open(file.path,OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW,384)
   try {
    val s=Os.fstat(fd);require(OsConstants.S_ISREG(s.st_mode)&&s.st_uid==10174&&s.st_nlink==1L)
    var at=0;while(at<payload.size){val n=Os.write(fd,payload,at,payload.size-at);require(n>0);at+=n}
    Os.fsync(fd);same(stamp(Os.fstat(fd)),stamp(Os.lstat(file.path)));Os.fsync(acceptance!!.fd)
    return fd
   }catch(t: Throwable){Os.close(fd);throw t}
  }
  fun action(kind: String,subject: String,body: ()->Unit) {
   stage="$kind:$subject";record(JSONObject().put("stage",stage).put("state","started"))
   body();confirmed.put(stage);record(JSONObject().put("stage",stage).put("state","confirmed"))
  }
  try {
   val files=open(target.filesDir.canonicalFile)
   acceptance=open(File(files.file,"acceptance"),files)
   val stem="P8-saved-selection-cleanup-$op"
   intent=File(acceptance!!.file,"$stem.json");result=File(acceptance!!.file,"$stem.result.json")
   val log=File(acceptance!!.file,"$stem.journal.jsonl")
   require(listOf(intent!!,result!!,log).all {absent(it)}) {"Existing operation receipt; inspect it, never replay"};mayRecord=true
   stage="preflight-both-fixtures"
   val pair=LABELS.mapIndexed {index,label->preflight(acceptance!!,inputs.getJSONObject(index),label)}
   val keystore=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
   for(f in pair)require(keystore.containsAlias(f.alias))
   held.forEach {checkNode(it,it.file!=acceptance!!.file&&it.file!=files.file)}
   stage="persist-collect-intent"
   Os.close(exclusive(intent!!,bytes(JSONObject().put("version",1).put("profile",PROFILE).put("cleanupOperation",op)
    .put("admission",admission).put("admissionSha256",sha(raw.toByteArray())).put("preflightPassed",true)
    .put("currentApkHashesVerified",true).put("sourceHashesExternallyAdmitted",true).put("quiescenceExternallyAdmitted",true))))
   journal=exclusive(log,ByteArray(0))
   for(f in pair)action("delete-exact-alias",f.alias) {
    f.nodes.values.forEach {checkNode(it)};require(keystore.containsAlias(f.alias))
    keystore.deleteEntry(f.alias);require(!keystore.containsAlias(f.alias))
   }
   for(f in pair) {
    for((path,n)in f.nodes.filterValues {it.dir==null})action("delete-file",f.label+"/"+path) {
     val parent=f.nodes.getValue(path.substringBeforeLast('/',"."));checkNode(parent,false);checkNode(n)
     parent.dir!!.deleteFile(Paths.get(n.file.name));Os.fsync(parent.fd);require(missing(parent,n.file.name))
    }
    for(path in listOf("secure/installation","secure","."))action("delete-directory",f.label+"/"+path) {
     val n=f.nodes.getValue(path);val parent=if(path==".")acceptance!! else f.nodes.getValue(path.substringBeforeLast('/',"."))
     checkNode(parent,false);checkNode(n,false);parent.dir!!.deleteDirectory(Paths.get(n.file.name))
     Os.fsync(parent.fd);require(missing(parent,n.file.name))
    }
   }
   stage="verify-exact-pair-absent"
   for(f in pair)require(!keystore.containsAlias(f.alias)&&missing(acceptance!!,f.leaf)&&absent(f.nodes.getValue(".").file))
   record(JSONObject().put("stage",stage).put("state","confirmed"));passed=true
  }catch(t: Throwable){failure=t}
  finally {
   if(mayRecord)try {
    val out=JSONObject().put("version",1).put("profile",PROFILE).put("cleanupOperation",op)
     .put("admissionSha256",sha(raw.toByteArray())).put("intentPresent",intent?.let {!absent(it)}?:false)
     .put("cleanupPassed",passed).put("pairAbsenceVerified",passed).put("stage",stage).put("confirmedActions",confirmed)
     .put("failureType",failure?.javaClass?.name?:JSONObject.NULL).put("automaticRetryAllowed",false)
    Os.close(exclusive(requireNotNull(result),bytes(out)))
   }catch(t: Throwable){passed=false;if(failure==null)failure=t else failure!!.addSuppressed(t)}
   try {journal?.let {Os.close(it)}}catch(t: Throwable){if(failure==null)failure=t else failure!!.addSuppressed(t)}
   for(n in held.asReversed()) {
    try {n.dir?.close()}catch(t: Throwable){if(failure==null)failure=t else failure!!.addSuppressed(t)}
    try {Os.close(n.fd)}catch(t: Throwable){if(failure==null)failure=t else failure!!.addSuppressed(t)}
   }
  }
  failure?.let {throw it};check(passed)
 }
}
