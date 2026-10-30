package com.agenthub.android

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Looper
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ

/** Existing default + two declared diagnostic originals. No Session/store/seal/key generation. */
internal class NativeSavedVoiceExistingProtection(val context: Context,val plan: NativeSavedVoiceExistingPlan) {
    companion object {
        /** Exact ASR original-proof profile; EasyWork's wire encoder is a different contract. */
        internal fun asrProofCanonical(value: Any?): String=when(value) {
            null,JSONObject.NULL->"null"
            is JSONObject->value.keys().asSequence().toList().sorted().joinToString(",","{","}"){JSONObject.quote(it)+":"+asrProofCanonical(value.get(it))}
            is JSONArray->(0 until value.length()).joinToString(",","[","]"){asrProofCanonical(value.get(it))}
            is String->JSONObject.quote(value)
            is Boolean,is Int,is Long->value.toString()
            else->error("Unexpected original JSON value")
        }
        fun fromArguments(context: Context): NativeSavedVoiceExistingProtection? {
            val args=InstrumentationRegistry.getArguments();val encoded=args.getString("savedVoiceExistingPlanBase64");val hash=args.getString("savedVoiceExistingPlanSha256")
            if(encoded==null&&hash==null)return null
            require(encoded!=null&&hash!=null&&encoded.length in 1..44_000)
            val raw=java.util.Base64.getDecoder().decode(encoded);require(java.util.Base64.getEncoder().encodeToString(raw)==encoded)
            return NativeSavedVoiceExistingProtection(context,NativeSavedVoiceExistingPlan.parse(raw,hash))
        }
        private fun sha(b: ByteArray)=NativeSavedVoiceExistingPlan.sha(b)
        private fun directory(f: File) {
            val s=Os.lstat(f.path);require(OsConstants.S_ISDIR(s.st_mode)&&s.st_uid==Process.myUid()&&s.st_mode and 0x3f==0&&!Files.isSymbolicLink(f.toPath()))
        }
        /** Only exact framework Context roots may use Android's standard 0771 mode.
         * The app data parent and every submitted descendant remain strictly private. */
        private fun contextRoot(context: Context,root: File) {
            val expected=when(root.name){"files"->context.filesDir;"no_backup"->context.noBackupFilesDir;else->error("Not an Android Context root")}
            val data=File(context.applicationInfo.dataDir)
            directory(data)
            require(root==expected.canonicalFile&&root.parentFile==data.canonicalFile)
            require(!Files.isSymbolicLink(expected.toPath()))
            val parent=Os.lstat(data.path);val observed=Os.lstat(expected.path)
            require(OsConstants.S_ISDIR(observed.st_mode)&&observed.st_uid==Process.myUid()&&observed.st_gid==Process.myUid())
            require((observed.st_mode and 0xfff) in setOf(0x1c0,0x1f9)) // exact 0700/0771, no special bits
            val after=Os.lstat(data.path)
            require(parent.st_dev==after.st_dev&&parent.st_ino==after.st_ino&&parent.st_uid==after.st_uid&&parent.st_gid==after.st_gid&&parent.st_mode==after.st_mode)
        }
        private fun contextChild(context: Context,root: File,vararg parts: String): File {
            contextRoot(context,root);var f=root
            for(part in parts){require(part.matches(Regex("[a-zA-Z0-9_.-]{1,128}"))&&part !in setOf(".",".."));f=File(f,part);directory(f)}
            return f
        }
        /** One pre-existing diagnostic container is public-mode inside two private enclosures.
         * This exception is only for original(); the container is never called private. */
        private fun enclosedLegacyOriginal(context: Context,lease: String): Pair<File,()->Unit> {
            NativeSavedVoiceExistingPlan.uuid(lease)
            val files=context.filesDir.canonicalFile
            val data=File(context.applicationInfo.dataDir)
            val acceptance=File(files,"acceptance")
            val legacy=File(acceptance,"P7-real-file-asr")
            val root=File(legacy,lease)
            fun privateEnclosure(f: File) {
                directory(f)
                val s=Os.lstat(f.path)
                require(s.st_gid==Process.myUid()&&(s.st_mode and 0xfff)==0x1c0) // exact private0700
            }
            fun checkEnclosure() {
                contextRoot(context,files)
                privateEnclosure(data);privateEnclosure(acceptance)
                require(acceptance.parentFile==files&&acceptance.canonicalFile==acceptance)
                require(legacy.parentFile==acceptance&&legacy.canonicalFile==legacy&&!Files.isSymbolicLink(legacy.toPath()))
                val s=Os.lstat(legacy.path)
                require(OsConstants.S_ISDIR(s.st_mode)&&s.st_uid==Process.myUid()&&s.st_gid==Process.myUid())
                require((s.st_mode and 0xfff) in setOf(0x1c0,0x1ff)) // named legacy0700/0777 only, no special bits
                require(root.parentFile==legacy&&root.canonicalFile==root)
                directory(root) // original strict case-root rule, never0777
            }
            fun identity(f: File): List<Long> {
                require(!Files.isSymbolicLink(f.toPath()))
                val s=Os.lstat(f.path)
                return listOf(s.st_dev,s.st_ino,s.st_mode.toLong(),s.st_uid.toLong(),s.st_gid.toLong(),s.st_nlink,s.st_mtime,s.st_ctime)
            }
            checkEnclosure()
            val before=listOf(data,files,acceptance,legacy,root).map {it to identity(it)}
            val verify: ()->Unit={
                checkEnclosure()
                for((file,expected)in before)require(identity(file)==expected) {"Legacy enclosure identity changed"}
            }
            return Pair(root,verify)
        }
        /** Every submitted descendant is checked before opening; no mkdir, repair, chmod or write. */
        private fun child(root: File,vararg parts: String): File {
            directory(root);var f=root
            for(part in parts){require(part.matches(Regex("[a-zA-Z0-9_.-]{1,128}"))&&part !in setOf(".",".."));f=File(f,part);directory(f)}
            return f
        }
        private fun bytes(f: File,maximum: Int,minimum: Int=1): ByteArray {
            require(!Files.isSymbolicLink(f.toPath()));val fd=Os.open(f.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW,0)
            var raw: ByteArray?=null
            try {
                val before=Os.fstat(fd);require(OsConstants.S_ISREG(before.st_mode)&&before.st_uid==Process.myUid()&&before.st_nlink==1L&&before.st_mode and 0x3f==0&&before.st_size in minimum.toLong()..maximum.toLong())
                val data=ByteArray(before.st_size.toInt());raw=data;var offset=0
                while(offset<data.size){val n=Os.read(fd,data,offset,data.size-offset);require(n>0);offset+=n}
                require(Os.read(fd,ByteArray(1),0,1)==0)
                val after=Os.fstat(fd);val current=Os.lstat(f.path)
                require(current.st_dev==before.st_dev&&current.st_ino==before.st_ino&&current.st_mode==before.st_mode&&current.st_uid==before.st_uid&&current.st_nlink==1L&&current.st_size==before.st_size)
                require(after.st_size==before.st_size&&after.st_mtime==before.st_mtime)
                return data
            }catch(t: Throwable){raw?.fill(0);throw t}finally{Os.close(fd)}
        }
        private fun pinned(f: File,hash: String,maximum: Int): ByteArray=bytes(f,maximum).also {require(sha(it)==hash)}
        private fun sameInstalled(a: JSONObject,b: JSONObject) {
            NativeSavedVoiceExistingPlan.installed(a);NativeSavedVoiceExistingPlan.installed(b)
            for(k in listOf("uid","appSha256","runnerSha256","signerSetSha256"))require(a.get(k)==b.get(k))
        }
    }
    private fun boot()=android.provider.Settings.Global.getInt(context.contentResolver,android.provider.Settings.Global.BOOT_COUNT)
    private fun original(o: JSONObject): JSONObject {
        val lease=o.getString("lease");val id=o.getString("originalId")
        val enclosure=enclosedLegacyOriginal(context,lease);val root=enclosure.first
        val sources=linkedMapOf("lease.json" to o.getString("declarationSha256"),"original.json" to o.getString("baselineSha256"),
            "original-intent.json" to o.getString("intentSha256"),"submission.json" to o.getString("submissionSha256"))
        val records=sources.mapValues {(name,hash)->pinned(File(root,name),hash,65_536)}
        val declaration=StrictMigrationJson.parse(records.getValue("lease.json"))
        NativeSavedVoiceExistingPlan.keys(declaration,"version","lease","package","sdk","abi","locale","audioSha256","inputKind","phraseId","appSha256","runnerSha256","createdAtMs","expiresAtMs","allowedCases")
        require(declaration.getString("lease")==lease&&declaration.getString("package")==context.packageName&&declaration.getString("locale")==o.getString("locale"))
        require(declaration.get("version")==1&&declaration.get("sdk")==26&&declaration.getString("abi")=="arm64-v8a"&&declaration.getString("inputKind")=="declared-tts"&&declaration.getString("audioSha256")==o.getString("audioSha256"))
        // Expired diagnostic declarations are immutable evidence, never renewed/admitted as execution leases.
        val submission=StrictMigrationJson.parse(records.getValue("submission.json"))
        NativeSavedVoiceExistingPlan.keys(submission,"version","lease","declarationSha256")
        require(submission.get("version")==1&&submission.getString("lease")==lease&&submission.getString("declarationSha256")==sources.getValue("lease.json"))
        val baseline=StrictMigrationJson.parse(records.getValue("original.json"));NativeSavedVoiceExistingPlan.keys(baseline,"canonicalOriginalJson","persistedRow")
        val raw=baseline.getString("persistedRow");val canonical=baseline.getString("canonicalOriginalJson")
        require(sha(raw.toByteArray(Charsets.UTF_8))==o.getString("rowSha256")&&sha(canonical.toByteArray(Charsets.UTF_8))==o.getString("canonicalSha256"))
        val expected=NativeVoiceRecordingRecord.decode(StrictMigrationJson.parse(raw.toByteArray(Charsets.UTF_8)))
        require(expected.id==id&&asrProofCanonical(expected.json())==canonical&&expected.manifest!!.sha256==o.getString("audioSha256")&&expected.manifest.sizeBytes==o.getInt("audioBytes"))
        val originalIntent=StrictMigrationJson.parse(records.getValue("original-intent.json"))
        NativeSavedVoiceExistingPlan.keys(originalIntent,"id","fingerprint","manifestSha256","canonicalOriginalSha256","audioSha256","rowSha256","exactCanonicalOriginalAndRowVerified","fullWavByteEqualityVerified","prepareAttempted","audioAttempted")
        require(originalIntent.getString("id")==id&&originalIntent.getString("fingerprint")==NativeVoiceDiscardRequest(expected).fingerprint&&originalIntent.getString("manifestSha256")==sha(expected.manifest.json().toString().toByteArray(Charsets.UTF_8)))
        for((key,value)in mapOf("canonicalOriginalSha256" to o.getString("canonicalSha256"),"audioSha256" to o.getString("audioSha256"),"rowSha256" to o.getString("rowSha256")))require(originalIntent.getString(key)==value)
        require(originalIntent.get("exactCanonicalOriginalAndRowVerified")==true&&originalIntent.get("fullWavByteEqualityVerified")==true&&originalIntent.get("prepareAttempted")==expected.prepareAttempted&&originalIntent.get("audioAttempted")==expected.audioAttempted)
        val namespaceParent=child(root,"runtime","no-backup");val namespace=child(namespaceParent,"original")
        val expectedFiles=o.getJSONObject("files")
        fun checkFiles(): String {
            directory(namespace);require(namespace.list()!!.toSet()==expectedFiles.keys().asSequence().toSet()) {"Missing, pending or unknown original file"}
            val rows=mutableListOf<String>()
            for(name in expectedFiles.keys().asSequence().sorted()) {
                val f=expectedFiles.getJSONObject(name);val data=pinned(File(namespace,name),f.getString("sha256"),13*1024*1024)
                try {require(data.size==f.getInt("bytes"));require(if(f.getInt("format")==3)data[0]==3.toByte()else data.size>=4&&ByteBuffer.wrap(data).int==4)}finally{data.fill(0)}
                rows.add(name+":"+f.getString("sha256"))
            }
            return sha(rows.joinToString("\n").toByteArray())
        }
        val before=checkFiles()
        val local=object: ContextWrapper(context){override fun getNoBackupFilesDir()=namespaceParent}
        val alias="agent-hub-real-asr-"+lease
        NativeSessionStorage.ExistingReader.open(local,"original",alias).use {reader->
            require(reader.text("voice-recording:"+id,1_048_576)==raw)
            val index=StrictMigrationJson.value(reader.text("voice-index-v1",16_384).toByteArray(Charsets.UTF_8)) as JSONArray
            require(index.length()==1&&index.get(0)==id)
            val wav=reader.binary("voice-audio:"+id,o.getInt("audioBytes"));val input=pinned(File(root,"input.wav"),o.getString("audioSha256"),9_600_044)
            try {expected.manifest!!.verify(wav);require(wav.contentEquals(input)&&sha(wav)==o.getString("audioSha256"))}finally{wav.fill(0);input.fill(0)}
        }
        require(checkFiles()==before)
        for((name,data)in records){val reread=pinned(File(root,name),sources.getValue(name),65_536);try{require(reread.contentEquals(data))}finally{reread.fill(0);data.fill(0)}}
        enclosure.second.invoke()
        return JSONObject().put("originalIdSha256",sha(id.toByteArray())).put("rowSha256",o.getString("rowSha256")).put("canonicalSha256",o.getString("canonicalSha256"))
            .put("audioSha256",o.getString("audioSha256")).put("audioBytes",o.getInt("audioBytes")).put("ciphertextSetDigest",before).put("completeByteEquality",true)
    }
    fun verify(): JSONObject {
        check(Looper.myLooper()!=Looper.getMainLooper());NativeChatNotificationNaturalStore.guard(context);require(Build.VERSION.SDK_INT==26)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val monitor=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            require(androidx.test.runner.lifecycle.Stage.values().flatMap {monitor.getActivitiesInStage(it)}.none {it is MainActivity})
        }
        sameInstalled(NativeNaturalPreflightEvidence.installed(context),plan.installed)
        val boot=boot();require(boot==plan.value.getInt("currentBootCount"))
        val base=context.noBackupFilesDir.canonicalFile;contextRoot(context,base)
        val before=NativeNaturalFamilyInventory.scan(base,true)
        val b=plan.bootstrap;val current=plan.currentDefaultFamily
        val currentRoots=current.getJSONArray("roots").let {a->(0 until a.length()).map {a.getString(it)}}
        require(before.complete&&before.contentHashes&&before.families==currentRoots.toSet()&&before.directories==current.getInt("directories")&&
            before.files==current.getInt("files")&&before.bytes==current.getLong("bytes")&&before.digest==current.getString("familyDigest"))
        require(before.entries==before.directories+before.files&&before.maximumDepth==1)
        val root=contextChild(context,base,"mobile-v1");val filename=NativeSavedVoiceExistingPlan.recordName("p9-native-local-bootstrap-v1")
        require(before.filePaths==setOf("mobile-v1/"+filename))
        val encrypted=pinned(File(root,filename),b.getString("ciphertextSha256"),8192)
        require(encrypted.size==b.getInt("ciphertextBytes")&&encrypted[0]==3.toByte())
        require(NativeBootstrapCiphertextProof.familyDigest(encrypted)==b.getString("familyDigest"))
        val fence=contextChild(context,base,"P9-natural-bootstrap-v1");require(fence.list()!!.toSet()==setOf("write-intent.json","completed.json","owner.lock"))
        val lock=File(fence,"owner.lock");require(bytes(lock,1,0).isEmpty())
        val payloadHash=FileChannel.open(lock.toPath(),READ,NOFOLLOW_LINKS).use {channel->
            checkNotNull(channel.tryLock(0,Long.MAX_VALUE,true)).use {
                val intentBytes=pinned(File(fence,"write-intent.json"),b.getString("intentSha256"),32_768)
                val completedBytes=pinned(File(fence,"completed.json"),b.getString("completedSha256"),32_768)
                val intent=StrictMigrationJson.parse(intentBytes);val completed=StrictMigrationJson.parse(completedBytes)
                NativeSavedVoiceExistingPlan.keys(intent,"version","bootstrapId","phase","pid","nonce","installed","familiesBefore","keyCountsBefore","coldNaturalQueueRoots","normalProductWritesPlanned","scope")
                NativeSavedVoiceExistingPlan.keys(completed,"version","bootstrapId","phase","intentSha256","pid","installed","familiesBefore","familiesAfter","keyCountsAfter","normalProductWrites","localRecordNameSha256","boundary")
                require(intent.get("version")==1&&completed.get("version")==1&&intent.getString("bootstrapId")==b.getString("bootstrapId")&&completed.getString("bootstrapId")==b.getString("bootstrapId"))
                require(intent.getString("phase")=="normal-write-intent"&&completed.getString("phase")=="complete"&&intent.get("normalProductWritesPlanned")==1&&completed.get("normalProductWrites")==1)
                require(completed.getString("intentSha256")==b.getString("intentSha256"));sameInstalled(intent.getJSONObject("installed"),b.getJSONObject("originalInstalled"));sameInstalled(completed.getJSONObject("installed"),b.getJSONObject("originalInstalled"))
                require(completed.getJSONObject("familiesAfter").getString("familyDigest")==b.getString("familyDigest")&&completed.getString("localRecordNameSha256")==filename.removeSuffix(".bin"))
                val payload=NativeSessionStorage.ExistingReader.open(context,"mobile-v1","agent-hub-mobile-v1").use {it.text("p9-native-local-bootstrap-v1",8192)}
                val v=StrictMigrationJson.parse(payload.toByteArray(Charsets.UTF_8));NativeSavedVoiceExistingPlan.keys(v,"version","purpose","bootstrapId")
                require(v.get("version") is Int&&v.getInt("version")==1&&v.getString("purpose")=="p9-native-key-bootstrap"&&v.getString("bootstrapId")==b.getString("bootstrapId"))
                for((name,data,hash)in listOf(Triple("write-intent.json",intentBytes,b.getString("intentSha256")),Triple("completed.json",completedBytes,b.getString("completedSha256")))) {
                    val reread=pinned(File(fence,name),hash,32_768);try{require(reread.contentEquals(data))}finally{data.fill(0);reread.fill(0)}
                }
                sha(payload.toByteArray(Charsets.UTF_8))
            }
        }
        val originals=JSONArray(plan.originals.map(::original))
        val reread=pinned(File(root,filename),b.getString("ciphertextSha256"),8192);try{require(reread.contentEquals(encrypted))}finally{reread.fill(0);encrypted.fill(0)}
        val after=NativeNaturalFamilyInventory.scan(base,true);require(after==before) // all current roots, paths, counts, bytes, depth and content digest remain equal
        require(boot()==boot);sameInstalled(NativeNaturalPreflightEvidence.installed(context),plan.installed)
        return JSONObject().put("version",2).put("planSha256",plan.sha256).put("lease",plan.lease).put("postwriteRun",plan.postwriteRun).put("pid",Process.myPid())
            .put("currentObservationSha256",plan.value.getString("currentObservationSha256")).put("currentBootId",plan.value.getString("currentBootId")).put("observedBootCount",boot)
            .put("kernelBootIdCheckedByNative",false).put("parentCurrentBootPrePostRequired",true)
            .put("currentDefaultFamily",JSONObject().put("roots",JSONArray(before.families.sorted())).put("directories",before.directories)
                .put("files",before.files).put("bytes",before.bytes).put("familyDigest",before.digest))
            .put("historicalBootstrapFamily",JSONObject().put("roots",JSONArray(listOf("mobile-v1"))).put("directories",1)
                .put("files",1).put("bytes",b.getLong("ciphertextBytes")).put("familyDigest",b.getString("familyDigest")))
            .put("nonemptyDefaultPayloadSha256",payloadHash).put("existingKeyAuthenticated",true).put("metadataContentClaimed",false).put("originals",originals)
            .put("sourceWrites",0).put("keysCreatedOrImported",0).put("originalOperationsInvoked",0).put("noSessionOrVoiceStoreConstructed",true)
    }
}
