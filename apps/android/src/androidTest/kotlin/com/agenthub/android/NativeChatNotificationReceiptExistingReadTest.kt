package com.agenthub.android

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.UUID

/** Separate invocation readback only. No Main, OS post, Host, model input or physical-death claim. */
class NativeChatNotificationReceiptExistingReadTest {
    companion object {
        // Existing Store APIs take now explicitly. Never change the system clock or renew a ticket.
        private const val QUERY_NOW=1_793_000_000_000L
        private val processNonce=UUID.randomUUID().toString()
    }
    private class Fixture {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val run=requireNotNull(InstrumentationRegistry.getArguments().getString("p9ExistingReceiptRun")).also {require(UUID.fromString(it).toString()==it)}
        val folder=File(app.filesDir,"acceptance/P9-existing-receipt-$run")
        val alias="p9-existing-receipt-$run"
        val target=NativeChatNoticeTarget("source81-host-$run","source81-workspace","source81-runtime")
        val scope=TargetScope("codex",null,null,target.hostId,target.workspaceId,target.runtimeId)
        val client=MobileClient(target.hostId,"Owned Source81",scope,endpoint="https://source81.example.invalid")
        val epoch=UUID.nameUUIDFromBytes((run+":epoch").toByteArray(Charsets.UTF_8)).toString()
        val recoveryOperation=UUID.nameUUIDFromBytes((run+":recovery-original").toByteArray(Charsets.UTF_8)).toString()
        val pendingOperation=UUID.nameUUIDFromBytes((run+":pending-notice-original").toByteArray(Charsets.UTF_8)).toString()
        val terminalOperation=UUID.nameUUIDFromBytes((run+":terminal-notice-original").toByteArray(Charsets.UTF_8)).toString()
        val draftKey="source81-independent-later-draft"
        val draft="Source81 independent later draft $run"
        init {
            require(BuildConfig.DEBUG&&BuildConfig.APPLICATION_ID=="com.easyai.agenthub.android.acceptance"&&app.packageName==BuildConfig.APPLICATION_ID&&app.applicationInfo.uid==Process.myUid())
            require(InstrumentationRegistry.getInstrumentation().context.packageName==app.packageName+".test")
            NativeChatNotificationColdStart.requireUnusedAcceptanceOwner(app)
        }
        fun fresh() {
            val parent=requireNotNull(folder.parentFile)
            require(parent.isDirectory&&!Files.isSymbolicLink(parent.toPath())&&parent.canonicalFile==File(app.filesDir.canonicalFile,"acceptance"))
            val parentStat=Os.lstat(parent.path);require(OsConstants.S_ISDIR(parentStat.st_mode)&&parentStat.st_uid==Process.myUid()&&parentStat.st_mode and 0x3f==0)
            require(!java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.containsAlias(alias)) {"Existing owned key preserved; do not replay this writer"}
            require(Files.notExists(folder.toPath(),NOFOLLOW_LINKS));check(folder.mkdirs());Os.chmod(folder.path,0x1c0)
        }
        fun base(): File=folder.also {require(it.isDirectory&&!Files.isSymbolicLink(it.toPath()))}
        fun context(): Context=object:ContextWrapper(app){override fun getNoBackupFilesDir()=base();override fun getApplicationContext(): Context=this}
        fun file(name:String)=File(base(),"source81-$name.json")
        fun publish(name:String,value:JSONObject) {
            require(name in setOf("intent","writer","consumer"))
            val fd=Os.open(file(name).path,OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW,0x180)
            FileOutputStream(fd).use {it.write(value.toString().toByteArray(Charsets.UTF_8));it.fd.sync()}
        }
        fun snapshot()=NativeChatNotificationSnapshot(client,"source81-synthetic-credential-"+"x".repeat(32),"source81-credential-epoch")
    }
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun process()=JSONObject().put("pid",Process.myPid()).put("nonce",processNonce).put("startTicks",File("/proc/self/stat").readText().substringAfterLast(") ").trim().split(Regex("\\s+"))[19].toLong().also {require(it>0)})
    private fun header(f:Fixture,phase:String)=JSONObject().put("version",1).put("kind","p9-existing-receipt-read-v1").put("run",f.run).put("phase",phase).put("process",process())
        .put("syntheticNowArguments",true).put("queryNow",QUERY_NOW).put("realClockExpiryAccepted",false).put("priorProcessExitProved",false)
        .put("systemNotificationPosted",false).put("HostOrModelInput",false).put("MainActivityUsed",false).put("physicalDevAccepted",false).put("UiAccepted",false).put("NativeAcceptance",false)
    private fun tone()=NativeChatNotificationPcm.builtin("soft.caf").also {it[60]=(it[60].toInt() xor 1).toByte()}
    private fun names(id:String)=setOf("metadata",NativeChatNotificationStore.RECORD,NativeChatNotificationSoundLibrary.RECORD,
        NativeChatNotificationSoundLibrary.binaryName(id),NativeChatNotificationIdentity.RECORD,NativeChatNotificationRecoveryStore.RECORD)
    private fun stamp(file:File)=Os.lstat(file.path).let {s->listOf(s.st_dev,s.st_ino,s.st_mode.toLong(),s.st_nlink,s.st_uid.toLong(),s.st_gid.toLong(),s.st_size,s.st_mtime,s.st_ctime)}
    private fun tree(folder:File):JSONObject {
        require(folder.isDirectory&&!Files.isSymbolicLink(folder.toPath()))
        val out=JSONObject()
        folder.walkTopDown().forEach {file->
            require(!Files.isSymbolicLink(file.toPath()));val s=Os.lstat(file.path)
            require(s.st_uid==Process.myUid()&&(OsConstants.S_ISDIR(s.st_mode)||OsConstants.S_ISREG(s.st_mode)))
            val row=JSONObject().put("stat",JSONArray(stamp(file)))
            if(OsConstants.S_ISREG(s.st_mode)){require(s.st_nlink==1L);row.put("sha256",sha(file.readBytes()))}
            out.put(file.relativeTo(folder).path.ifEmpty{"."},row)
        }
        return out
    }
    private fun sameTree(expected:JSONObject,folder:File)=assertEquals(NativeChatRecoveryCanonical.value(expected),NativeChatRecoveryCanonical.value(tree(folder)))
    private fun requireNames(folder:File,id:String) {
        val rows=requireNotNull(folder.listFiles())
        assertEquals(names(id).map {sha(it.toByteArray(Charsets.UTF_8))+".bin"}.toSet(),rows.map {it.name}.toSet())
        rows.forEach {file->val s=Os.lstat(file.path);require(!Files.isSymbolicLink(file.toPath())&&OsConstants.S_ISREG(s.st_mode)&&s.st_uid==Process.myUid()&&s.st_nlink==1L&&s.st_size in 1L..262_144L)}
    }
    private fun receipt(store:NativeSessionStorage,ticket:String):NativeChatNoticeReceipt {
        val rows=JSONObject(requireNotNull(store.read(NativeChatNotificationStore.RECORD))).getJSONArray("receipts")
        assertEquals(2,rows.length())
        return (0 until rows.length()).map {NativeChatNotificationCodec.receipt(rows.getJSONObject(it))}.single {it.ticket==ticket}
    }
    private fun receiptHash(receipt:NativeChatNoticeReceipt)=NativeChatRecoveryCanonical.hash(NativeChatNotificationCodec.receipt(receipt))
    @Test fun writerPersistsPendingNavigationAndUnknownRecoveryWithImportedSound()=runBlocking(Dispatchers.IO) {
        val f=Fixture();f.fresh()
        f.publish("intent",header(f,"intent").put("epoch",f.epoch).put("recoveryOperationId",f.recoveryOperation)
            .put("pendingOperationId",f.pendingOperation).put("terminalOperationId",f.terminalOperation).put("noReplay",true))
        val storage=NativeSessionStorage(f.context(),"installation",f.alias)
        try {
            Os.chmod(File(f.base(),"installation").path,0x1c0)
            storage.write("metadata",JSONObject().put("version",1).put("clients",JSONArray()).put("drafts",JSONObject().put(f.draftKey,f.draft)).toString())
            val sounds=NativeChatNotificationSoundLibrary(f.context(),storage);val item=sounds.importNormalized("Source81 imported audit sound","wav",tone())
            val notices=NativeChatNotificationStore(storage);assertNotNull(sounds.select(notices,null,item.id))
            val preferences=notices.update {it.copy(enabled=true,showThreadTitle=false,badgeEnabled=false,kinds=setOf(NativeChatNoticeKind.UNCERTAIN,NativeChatNoticeKind.APPROVAL,NativeChatNoticeKind.QUESTION),categorySounds=mapOf(NativeChatNoticeKind.UNCERTAIN to item.id,NativeChatNoticeKind.QUESTION to "none"))}
            val lease=notices.bind(f.target,"source81-credential-epoch")
            fun event(label:String,kind:NativeChatNoticeKind,operation:String)=NativeChatNotificationEvent(sha((f.run+":"+label).toByteArray()),f.target,kind,java.time.Instant.ofEpochMilli(QUERY_NOW).toString(),"source81-original-thread",operationId=operation,threadTitle="Source81 private title",taskSummary="Source81 private summary")
            val pending=checkNotNull(notices.claim(event("pending",NativeChatNoticeKind.UNCERTAIN,f.pendingOperation),lease,QUERY_NOW))
            notices.finish(pending.ticket,false)
            val pendingClaim=checkNotNull(notices.claimNavigation(pending.ticket,QUERY_NOW){true});assertNotNull(pendingClaim.navigationClaim)
            val terminal=checkNotNull(notices.claim(event("terminal",NativeChatNoticeKind.APPROVAL,f.terminalOperation),lease,QUERY_NOW))
            notices.finish(terminal.ticket,false)
            assertTrue(notices.acknowledgeNavigation(checkNotNull(notices.claimNavigation(terminal.ticket,QUERY_NOW){true})))
            val terminalClaim=receipt(storage,terminal.ticket);assertTrue(terminalClaim.consumed);assertNull(terminalClaim.navigationClaim)
            val identity=NativeChatNotificationIdentity.load(storage);val journal=NativeChatNotificationRecoveryStore(storage)
            val original=journal.reserve(identity,f.snapshot(),f.epoch,preferences,"register",{true},operationId=f.recoveryOperation)
            val saved=journal.view(identity,f.target).operations.single();assertEquals("unknown",saved.state);assertNull(saved.receipt)
            assertTrue(JSONObject(requireNotNull(storage.read(NativeChatNotificationIdentity.RECORD))).getBoolean("journalBound"))
            assertEquals(original.encoded(),saved.original.encoded());assertEquals(f.snapshot().authorizationFingerprint,saved.authorization)
            val folder=File(f.base(),"installation");requireNames(folder,item.id)
            val rawHashes=JSONObject()
            names(item.id).filter {it!=NativeChatNotificationSoundLibrary.binaryName(item.id)}.sorted().forEach {name->rawHashes.put(name,sha(requireNotNull(storage.read(name)).toByteArray(Charsets.UTF_8)))}
            f.publish("writer",header(f,"writer").put("namespace","installation").put("keyAlias",f.alias).put("recordNames",JSONArray(names(item.id).sorted()))
                .put("pendingTicket",pending.ticket).put("terminalTicket",terminal.ticket).put("pendingClaim",pendingClaim.navigationClaim)
                .put("pendingReceiptSha256",receiptHash(receipt(storage,pending.ticket))).put("terminalReceiptSha256",receiptHash(terminalClaim))
                .put("preferencesSha256",NativeChatRecoveryCanonical.hash(NativeChatNotificationCodec.preferences(preferences)))
                .put("soundId",item.id).put("soundSha256",item.sha256).put("soundBytes",item.bytes).put("identityDeviceId",identity.deviceId).put("identityFingerprint",identity.fingerprint)
                .put("recoveryOperationId",original.operationId).put("recoveryIntentSha256",sha(original.encoded().toByteArray(Charsets.UTF_8))).put("recoveryAuthorization",saved.authorization).put("recoveryEpoch",original.epoch)
                .put("draftSha256",sha(f.draft.toByteArray(Charsets.UTF_8))).put("rawTextSha256",rawHashes).put("installationTree",tree(folder))
                .put("noticeClaims",2).put("navigationClaims",2).put("navigationAcknowledgements",1).put("recoveryReserves",1).put("HttpCalls",0).put("notificationPosts",0))
        } finally {storage.retireWriter()}
    }
    @Test fun differentPidReadOnlyConsumerPreservesOriginalClaimsOperationsPrivacyAndSound()=runBlocking(Dispatchers.IO) {
        val f=Fixture();require(Files.notExists(f.file("consumer").toPath(),NOFOLLOW_LINKS))
        require(f.file("intent").isFile&&f.file("writer").isFile&&f.file("intent").length() in 1L..16_384L&&f.file("writer").length() in 1L..65_536L)
        val originalProofBytes=f.file("writer").readBytes();val originalIntentBytes=f.file("intent").readBytes();val writer=JSONObject(String(originalProofBytes,Charsets.UTF_8))
        require(writer.getInt("version")==1&&writer.getString("kind")=="p9-existing-receipt-read-v1"&&writer.getString("phase")=="writer"&&writer.getString("run")==f.run)
        require(writer.getString("namespace")=="installation"&&writer.getString("keyAlias")==f.alias&&writer.getLong("queryNow")==QUERY_NOW&&writer.getBoolean("syntheticNowArguments"))
        assertNotEquals(Process.myPid(),writer.getJSONObject("process").getInt("pid"));assertNotEquals(processNonce,writer.getJSONObject("process").getString("nonce"))
        val folder=File(f.base(),"installation");val expectedTree=writer.getJSONObject("installationTree");sameTree(expectedTree,folder)
        val soundId=writer.getString("soundId");requireNames(folder,soundId)
        assertEquals(names(soundId),writer.getJSONArray("recordNames").let {a->(0 until a.length()).map {a.getString(it)}.toSet()})
        val storage=NativeSessionStorage(f.context(),"installation",f.alias,existingKeyOnly=true,existingNamespaceOnly=true,existingReadOnly=true)
        try {
            val rawHashes=writer.getJSONObject("rawTextSha256")
            assertEquals(names(soundId)-NativeChatNotificationSoundLibrary.binaryName(soundId),rawHashes.keys().asSequence().toSet())
            rawHashes.keys().forEach {name->assertEquals(rawHashes.getString(name),sha(requireNotNull(storage.read(name)).toByteArray(Charsets.UTF_8)))}
            val notices=NativeChatNotificationStore(storage);val preferences=notices.preferences()
            assertEquals(writer.getString("preferencesSha256"),NativeChatRecoveryCanonical.hash(NativeChatNotificationCodec.preferences(preferences)))
            assertFalse(preferences.showThreadTitle);assertFalse(preferences.badgeEnabled);assertEquals(soundId,preferences.soundFor(NativeChatNoticeKind.UNCERTAIN));assertEquals("none",preferences.soundFor(NativeChatNoticeKind.QUESTION))
            val pending=receipt(storage,writer.getString("pendingTicket"));val terminal=receipt(storage,writer.getString("terminalTicket"))
            assertEquals(writer.getString("pendingReceiptSha256"),receiptHash(pending));assertEquals(writer.getString("terminalReceiptSha256"),receiptHash(terminal))
            assertEquals("unknown",pending.state);assertEquals("unknown",terminal.state);assertEquals(f.target,pending.event.target);assertEquals(f.target,terminal.event.target)
            assertEquals(f.pendingOperation,pending.event.operationId);assertEquals(f.terminalOperation,terminal.event.operationId)
            assertEquals("source81-credential-epoch",pending.lease.credentialEpoch);assertEquals(pending.lease,terminal.lease)
            assertTrue(pending.consumed);assertEquals(writer.getString("pendingClaim"),pending.navigationClaim);assertTrue(terminal.consumed);assertNull(terminal.navigationClaim)
            assertNull(notices.ticket(pending.ticket,QUERY_NOW+1));assertNull(notices.ticket(terminal.ticket,QUERY_NOW+1))
            assertEquals(pending,notices.navigationTicket(pending.ticket,QUERY_NOW+1));assertNull(notices.navigationTicket(terminal.ticket,QUERY_NOW+1))
            assertNull(notices.navigationTicket(pending.ticket,pending.expiresAt));assertTrue(notices.current(pending.lease))
            val presentation=NativeChatNoticePresentation.create(pending.event,"Owned Source81",preferences.showThreadTitle){it}
            assertEquals(pending.event.kind.title,presentation.title);assertEquals("",presentation.summary);assertEquals(pending.event.kind.title,presentation.publicTitle)
            assertFalse(presentation.title.contains("private"));assertFalse(presentation.summary.contains("private"))
            val sounds=NativeChatNotificationSoundLibrary(f.context(),storage);val item=checkNotNull(sounds.find(soundId));assertEquals(4,sounds.catalog().size)
            val catalogRaw=requireNotNull(storage.read(NativeChatNotificationSoundLibrary.RECORD));val catalog=JSONObject(catalogRaw)
            assertEquals(1,catalog.getJSONArray("items").length());assertEquals(0,catalog.getJSONArray("staged").length());assertEquals(0,catalog.getJSONArray("retired").length())
            assertEquals(setOf(NativeChatNotificationSoundLibrary.binaryName(soundId)),NativeChatNotificationSoundLibrary.requiredBinaries(catalogRaw))
            assertTrue(item.imported);assertEquals(writer.getString("soundSha256"),item.sha256);assertEquals(writer.getInt("soundBytes"),item.bytes)
            assertArrayEquals(tone(),sounds.read(soundId));assertEquals(item,sounds.byHash(item.sha256))
            assertTrue(JSONObject(requireNotNull(storage.read(NativeChatNotificationIdentity.RECORD))).getBoolean("journalBound"))
            val identity=NativeChatNotificationIdentity.load(storage);assertEquals(writer.getString("identityDeviceId"),identity.deviceId);assertEquals(writer.getString("identityFingerprint"),identity.fingerprint)
            val journal=NativeChatNotificationRecoveryStore(storage);val view=journal.view(identity,f.target);val saved=view.operations.single()
            assertEquals("unknown",saved.state);assertNull(saved.receipt);assertEquals(f.recoveryOperation,saved.original.operationId);assertEquals(f.epoch,saved.original.epoch)
            assertEquals(1L,saved.original.generation);assertEquals("register",saved.original.kind);assertEquals(f.target,saved.original.target)
            assertNull(storage.read(NativeChatNotificationForegroundStore.RECORD));assertNull(storage.read(NativeChatNotificationAuthorizations.RECORD))
            assertEquals(writer.getString("recoveryIntentSha256"),sha(saved.original.encoded().toByteArray(Charsets.UTF_8)));assertEquals(writer.getString("recoveryAuthorization"),saved.authorization);assertEquals(f.snapshot().authorizationFingerprint,saved.authorization)
            assertEquals(mapOf(f.target to listOf(saved.authorization)),journal.pendingByTarget());assertNull(view.applied);assertNull(view.pending);assertTrue(view.outcomes.isEmpty())
            assertEquals(writer.getString("draftSha256"),sha(requireNotNull(storage.metadata()).getJSONObject("drafts").getString(f.draftKey).toByteArray(Charsets.UTF_8)))
            sameTree(expectedTree,folder);assertArrayEquals(originalProofBytes,f.file("writer").readBytes());assertArrayEquals(originalIntentBytes,f.file("intent").readBytes())
            f.publish("consumer",header(f,"consumer").put("writerProofSha256",sha(originalProofBytes)).put("intentSha256",sha(originalIntentBytes)).put("writerProcess",writer.getJSONObject("process"))
                .put("keyAlias",f.alias).put("namespace","installation").put("installationUnchanged",true).put("existingKeyOnly",true).put("existingNamespaceOnly",true).put("existingReadOnly",true)
                .put("pendingClaim",pending.navigationClaim).put("recoveryIntentSha256",writer.getString("recoveryIntentSha256")).put("soundSha256",item.sha256)
                .put("receiptPrivacyAndSoundCompared",true).put("explicitSyntheticExpiryBoundaryCompared",true).put("noticeClaims",0).put("navigationClaims",0).put("navigationAcknowledgements",0)
                .put("recoveryReserves",0).put("HttpCalls",0).put("notificationPosts",0).put("KeyRepairOrGenerationAttempted",false))
        } finally {storage.retireWriter()}
    }
}
