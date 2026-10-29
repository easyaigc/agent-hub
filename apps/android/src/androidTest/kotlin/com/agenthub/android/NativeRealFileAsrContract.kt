package com.agenthub.android

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Process
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal object NativeRealFileAsrContract {
    const val PACKAGE="com.easyai.agenthub.android.acceptance"
    const val DIRECTORY="acceptance/P7-real-file-asr"
    const val MODEL_MANIFEST_SHA256="70003a068c5ebc69e7babc77e5c3009a36ec4bd0e68714d0e37d96e6c9e92f42"
    val cases=setOf("probe-empty","cancel-download","install","decode","cancel-model","cancel-feed","corrupt","release-cache")
    data class Input(val lease: String,val locale: String,val audioSha256: String)
    fun parse(values: Map<String,String?>): Input {
        require(values.keys==setOf("fileAsrLease","fileAsrLocale","fileAsrAudioSha256"))
        val lease=requireNotNull(values["fileAsrLease"]);require(UUID.fromString(lease).toString()==lease)
        val locale=requireNotNull(values["fileAsrLocale"]);require(locale in setOf("en-US","zh-CN"))
        val sha=requireNotNull(values["fileAsrAudioSha256"]);require(sha.matches(Regex("[a-f0-9]{64}")))
        return Input(lease,locale,sha)
    }
    fun phrase(locale: String)=when(locale) {"en-US"->"open the project and show the latest message";"zh-CN"->"打开项目显示最新消息";else->error("Unsupported closed locale")}
    fun phraseId(locale: String)=when(locale) {"en-US"->"en-open-project-v1";"zh-CN"->"zh-open-project-v1";else->error("Unsupported closed locale")}
    fun normalize(text: String)=text.lowercase(java.util.Locale.ROOT).filter {it.isLetterOrDigit()}
    fun sha(file: File): String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->val bytes=ByteArray(65536);while(true){val n=input.read(bytes);if(n<0)break;digest.update(bytes,0,n)}}
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
    fun privateDirectory(parent: File,name: String): File {
        require(name.matches(Regex("[A-Za-z0-9._-]{1,100}")))
        val file=File(parent,name);require(!java.nio.file.Files.isSymbolicLink(file.toPath()))
        val created=file.mkdir();check(created||file.isDirectory);require(file.canonicalFile==File(parent.canonicalFile,name));if(created)Os.chmod(file.path,0x1c0);return file
    }
    fun ownedFile(parent: File,name: String,maximum: Long): File {
        val file=File(parent,name);require(!java.nio.file.Files.isSymbolicLink(file.toPath())&&file.canonicalFile==File(parent.canonicalFile,name))
        val stat=Os.lstat(file.path);require(file.isFile&&stat.st_uid==Process.myUid()&&stat.st_nlink==1L&&stat.st_mode and 0x3f==0)
        require(file.length() in 1..maximum);return file
    }
    fun atomic(file: File,value: JSONObject) {
        val bytes=value.toString().toByteArray(Charsets.UTF_8);require(bytes.size<=128*1024)
        val atomic=android.util.AtomicFile(file);val out=atomic.startWrite()
        try {Os.fchmod(out.fd,0x180);out.write(bytes);out.fd.sync();atomic.finishWrite(out)}catch(failure: Throwable){atomic.failWrite(out);throw failure}
    }
    fun immutable(file: File,value: JSONObject) {
        require(!file.exists());check(file.createNewFile());Os.chmod(file.path,0x180)
        file.outputStream().use {out->out.write(value.toString().toByteArray(Charsets.UTF_8));out.fd.sync()}
    }
}

/** Only storage directories change. Assets, class loader, actual JNI and system services remain real. */
internal class NativeRealFileAsrContext(base: Context,private val runtime: File): ContextWrapper(base) {
    override fun getApplicationContext(): Context=this
    override fun getNoBackupFilesDir()=NativeRealFileAsrContract.privateDirectory(runtime,"no-backup")
}

internal class NativeRealFileAsrLease(val target: Context,val runner: Context,val input: NativeRealFileAsrContract.Input) {
    val root: File
    val runtime: File
    val context: NativeRealFileAsrContext
    val audio: File
    val declaration: JSONObject
    val declarationSha256: String
    private val expires: Long
    init {
        require(target.packageName==NativeRealFileAsrContract.PACKAGE&&runner.packageName==target.packageName+".test")
        require(target.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0)
        val a=NativeRealFileAsrContract.privateDirectory(target.filesDir,"acceptance")
        val p=NativeRealFileAsrContract.privateDirectory(a,"P7-real-file-asr")
        root=File(p,input.lease);require(root.isDirectory&&!java.nio.file.Files.isSymbolicLink(root.toPath())&&root.canonicalFile==File(p.canonicalFile,input.lease))
        require(Os.lstat(root.path).st_uid==Process.myUid()&&Os.lstat(root.path).st_mode and 0x3f==0)
        val source=NativeRealFileAsrContract.ownedFile(root,"lease.json",16384)
        declaration=StrictMigrationJson.parse(source.readBytes());declarationSha256=NativeRealFileAsrContract.sha(source)
        val fields=setOf("version","lease","package","sdk","abi","locale","audioSha256","inputKind","phraseId","appSha256","runnerSha256","createdAtMs","expiresAtMs","allowedCases")
        require(declaration.keys().asSequence().toSet()==fields&&declaration.get("version")==1)
        require(declaration.get("lease")==input.lease&&declaration.get("package")==target.packageName&&declaration.get("locale")==input.locale&&declaration.get("audioSha256")==input.audioSha256)
        require(declaration.get("inputKind")=="declared-tts"&&declaration.get("phraseId")==NativeRealFileAsrContract.phraseId(input.locale))
        val sdk=declaration.get("sdk") as Int;require(sdk in setOf(26,37)&&sdk==Build.VERSION.SDK_INT)
        require(declaration.get("abi")=="arm64-v8a"&&Build.SUPPORTED_ABIS.firstOrNull()=="arm64-v8a"&&Process.is64Bit())
        fun time(key: String): Long {val value=declaration.get(key);require(value is Long||value is Int);return (value as Number).toLong()}
        val created=time("createdAtMs");expires=time("expiresAtMs");val now=System.currentTimeMillis()
        require(created<=now+60_000&&expires>now&&expires-created in 1..7_200_000)
        val cases=declaration.getJSONArray("allowedCases");val names=(0 until cases.length()).map {cases.get(it) as String}
        require(names.isNotEmpty()&&names.distinct()==names&&names.all {it in NativeRealFileAsrContract.cases})
        for(key in listOf("appSha256","runnerSha256"))require((declaration.get(key)as String).matches(Regex("[a-f0-9]{64}")))
        require(declaration.get("appSha256")==NativeRealFileAsrContract.sha(File(target.applicationInfo.sourceDir)))
        require(declaration.get("runnerSha256")==NativeRealFileAsrContract.sha(File(runner.applicationInfo.sourceDir)))
        audio=NativeRealFileAsrContract.ownedFile(root,"input.wav",960_044)
        require(NativeRealFileAsrContract.sha(audio)==input.audioSha256)
        audio.readBytes().let {try{SavedVoicePcm.validate(it);require(it.size>=3244)}finally{it.fill(0)}}
        runtime=NativeRealFileAsrContract.privateDirectory(root,"runtime");context=NativeRealFileAsrContext(target,runtime)
    }
    fun checkpoint() {check(System.currentTimeMillis()<expires){"Canonical lease expired; actual resource exit still required"}}
    fun permits(case: String)=declaration.getJSONArray("allowedCases").strings().contains(case)
}
