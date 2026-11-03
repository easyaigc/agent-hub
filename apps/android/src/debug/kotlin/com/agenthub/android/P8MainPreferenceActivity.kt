package com.agenthub.android

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** SOURCE-ONLY candidate: actual Main lifecycle subclass; native integrations intentionally excluded.
 * Requires pre-init-seams.patch. Not the shipping Main component or full runtime assembly. */
class P8MainPreferenceActivity : MainActivity() {
    companion object { const val RUN="p8-main-preference-owned-run" }
    internal lateinit var ownedContext: P8MainFilesContext; private set
    internal lateinit var ownedStorage: NativeSessionStorage; private set
    internal val deny=P8MainDenyPorts()
    internal val ownership=P8MainActivityOwners.observe(this,deny)
    internal var preInitDefaultFamiliesAbsent=false; private set
    internal var afterCreateDefaultFamiliesAbsent=false; private set
    // Proposal-only short-viewport fixture; no run/SDK/device has been allocated.
    // The resource override belongs only to this Activity instance.
    // It does not call Settings.System, wm, setRequestedOrientation or mutate application resources.
    override fun attachBaseContext(base: Context) {
        P8MainActivityOwners.requirePrepared(ownership)
        val config=android.content.res.Configuration(base.resources.configuration).apply {fontScale=2f}
        super.attachBaseContext(base.createConfigurationContext(config))
    }
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        P8MainActivityOwners.beginCreate(ownership,savedInstanceState,lastCustomNonConfigurationInstance)
        try {
        val actual=applicationContext
        check(BuildConfig.DEBUG&&BuildConfig.APPLICATION_ID=="com.easyai.agenthub.android.acceptance"&&actual.packageName==BuildConfig.APPLICATION_ID)
        check(actual.applicationInfo.uid==android.os.Process.myUid()&&actual.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0)
        check(intent.action==Intent.ACTION_MAIN&&intent.data==null&&intent.clipData==null&&intent.extras?.keySet()==setOf(RUN))
        val run=checkNotNull(intent.getStringExtra(RUN));check(UUID.fromString(run).toString()==run)
        // The first point in this Activity, before super/Main NativeNaturalLaunch/finishCreation.
        // This is not a claim about Application/ContentProvider/instrumentation initialization.
        P8MainFilesContext.assertOrdinaryOwner(actual)
        preInitDefaultFamiliesAbsent=P8MainFilesContext.assertDefaultFamiliesAbsent(actual)
        check(run==ownership.launch?.run)
        ownership.contextAttempted=true
        ownedContext=P8MainFilesContext.open(actual,run);ownership.context=ownedContext
        ownership.mainAttempted=true
        super.onCreate(null)
        P8MainActivityOwners.captureMain(ownership,session,migration)
        P8MainActivityOwners.completeCreate(ownership)
        afterCreateDefaultFamiliesAbsent=P8MainFilesContext.assertDefaultFamiliesAbsent(actual)
        } catch(error: Throwable) {P8MainActivityOwners.failed(ownership,error);throw error}
        finally {
            // Retain every returned handle even when Main/restore/launch fails before onActivity.
            P8MainActivityOwners.captureMain(ownership,runCatching {session}.getOrNull(),runCatching {migration}.getOrNull())
        }
    }
    override fun onNewIntent(intent: Intent) {
        P8MainActivityOwners.reject(ownership,"New intent is outside this finite fixture")
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        P8MainActivityOwners.reject(ownership,"configuration change is outside this finite fixture")
    }
    override fun onRetainCustomNonConfigurationInstance(): Any? =
        P8MainActivityOwners.reject(ownership,"retained-state request is outside this finite fixture")
    override fun onDestroy() {
        P8MainActivityOwners.captureMain(ownership,runCatching {session}.getOrNull(),runCatching {migration}.getOrNull())
        ownership.destroyEntered=true
        if(isChangingConfigurations)P8MainActivityOwners.failed(ownership,IllegalStateException("Configuration destruction"))
        try {super.onDestroy();ownership.destroyConfirmed=true}
        catch(error: Throwable){P8MainActivityOwners.failed(ownership,error);throw error}
    }
    override fun runtimeContext(): Context=ownedContext
    override fun createInitialSession(context: Context): InitialSession {
        P8MainActivityOwners.requirePrepared(ownership)
        check(context===ownedContext&&!ownership.storageAttempted&&!ownership.sessionAttempted)
        ownership.migration=migration
        ownership.storageAttempted=true
        ownedStorage=NativeSessionStorage(context,"session",ownedContext.alias,existingKeyOnly=true,existingNamespaceOnly=true)
        ownership.storage=ownedStorage
        ownership.sessionAttempted=true
        val isolated=MobileSession(ownedStorage)
        val binding=P8MainActivityOwners.Binding(isolated,ownedStorage,ownedContext,migration,deny)
        ownership.bindings.add(binding)
        // Both provider IDs are fenced; resetAcceptance(reader,commands) alone retains production EasyWork.
        isolated.resetAcceptance(deny,deny,acceptanceEasyWorkPorts=deny to deny)
        binding.portsInstalled=true
        return InitialSession(isolated,needsRestore=true,nativeRuntime=false)
    }
}


/** Finite fixture ownership fence, not shipping configuration support. All attempts and returned
 * handles stay strongly reachable in this original PID; there is no reset/retry/forget API. */
internal object P8MainActivityOwners {
    class Launch(val run: String) {
        val pid=android.os.Process.myPid();val nonce=NativeChatNotificationColdStart.processNonce
        val instances=mutableListOf<Instance>();var closed=false;var problem: Throwable?=null
    }
    class Binding(val session: MobileSession,val storage: NativeSessionStorage,val context: P8MainFilesContext,
        val migration: NativeMigrationController,val ports: P8MainDenyPorts) {var portsInstalled=false}
    class Instance(val launch: Launch?,val activity: P8MainPreferenceActivity,val ports: P8MainDenyPorts) {
        val bindings=mutableListOf<Binding>();val observedSessions=mutableListOf<MobileSession>()
        val observedMigrations=mutableListOf<NativeMigrationController>()
        var context: P8MainFilesContext?=null;var storage: NativeSessionStorage?=null;var migration: NativeMigrationController?=null
        var createEntered=false;var contextAttempted=false;var mainAttempted=false;var storageAttempted=false;var sessionAttempted=false
        var createConfirmed=false;var destroyEntered=false;var destroyConfirmed=false;var problem: Throwable?=null
    }
    private val launches=mutableListOf<Launch>();private val allInstances=mutableListOf<Instance>()
    private var active: Launch?=null;private var unexpected: Throwable?=null
    private fun main(){check(android.os.Looper.myLooper()===android.os.Looper.getMainLooper())}
    fun prepare(run: String): Launch {
        main();check(UUID.fromString(run).toString()==run&&active==null&&unexpected==null)
        return Launch(run).also {launches.add(it);active=it}
    }
    fun observe(activity: P8MainPreferenceActivity,ports: P8MainDenyPorts): Instance {
        main();val launch=active;val row=Instance(launch,activity,ports)
        allInstances.add(row);launch?.instances?.add(row)
        if(launch==null||launch.closed||launch.instances.size!=1)failed(row,IllegalStateException("Unexpected Activity creation/recreation"))
        return row
    }
    fun failed(row: Instance,error: Throwable) {
        main();if(row.problem==null)row.problem=error
        val launch=row.launch;if(launch==null){if(unexpected==null)unexpected=error}else if(launch.problem==null)launch.problem=error
    }
    fun reject(row: Instance,reason: String): Nothing {
        val error=IllegalStateException(reason);failed(row,error);throw error
    }
    fun requirePrepared(row: Instance) {
        main();val launch=checkNotNull(row.launch)
        check(unexpected==null&&active===launch&&!launch.closed&&launch.problem==null&&row.problem==null&&
            launch.instances.size==1&&launch.instances.single()===row&&!row.destroyEntered)
        check(launch.pid==android.os.Process.myPid()&&launch.nonce==NativeChatNotificationColdStart.processNonce)
    }
    fun beginCreate(row: Instance,saved: Bundle?,retained: Any?) {
        main();if(row.createEntered)reject(row,"Repeated onCreate")
        row.createEntered=true
        if(saved!=null||retained!=null)reject(row,"Saved/retained recreation is outside this finite fixture")
        requirePrepared(row)
    }
    fun captureMain(row: Instance,session: MobileSession?,migration: NativeMigrationController?) {
        main()
        if(session!=null&&row.observedSessions.none {it===session})row.observedSessions.add(session)
        if(migration!=null&&row.observedMigrations.none {it===migration})row.observedMigrations.add(migration)
        if(session!=null&&row.bindings.none {it.session===session})failed(row,IllegalStateException("Main returned an unbound Session"))
        if(migration!=null&&row.migration!==migration)failed(row,IllegalStateException("Main returned an unbound migration owner"))
    }
    fun completeCreate(row: Instance) {
        requirePrepared(row);val binding=row.bindings.single()
        check(row.contextAttempted&&row.mainAttempted&&row.storageAttempted&&row.sessionAttempted&&binding.portsInstalled)
        check(row.context===binding.context&&row.storage===binding.storage&&row.migration===binding.migration&&row.ports===binding.ports)
        check(row.observedSessions.size==1&&row.observedSessions.single()===binding.session&&
            row.observedMigrations.size==1&&row.observedMigrations.single()===binding.migration&&binding.session.migrationStorage===binding.storage)
        row.createConfirmed=true
    }
    fun requireCurrent(row: Instance) {requirePrepared(row);check(row.createConfirmed&&!row.destroyConfirmed)}
    fun close(launch: Launch) {
        main();check(active===launch&&unexpected==null&&launch.problem==null&&!launch.closed)
        val row=launch.instances.single()
        check(row.createConfirmed&&row.destroyEntered&&row.destroyConfirmed&&row.problem==null)
        launch.closed=true;active=null
    }
    fun complete(owned: List<Launch>): Boolean {
        main()
        return unexpected==null&&active==null&&owned.all {it.closed&&it.problem==null&&it.instances.size==1}&&
            owned.all {row->launches.any {it===row}}&&launches.all {it.closed&&it.problem==null&&it.instances.size==1}&&
            allInstances.size==launches.size&&allInstances.all {row->launches.any {it===row.launch}&&row.createConfirmed&&row.destroyConfirmed&&row.problem==null}
    }
    fun summary(launch: Launch?)=launch?.let {value->mapOf("pid" to value.pid,"nonce" to value.nonce,"closed" to value.closed,
        "problem" to value.problem?.javaClass?.simpleName,"instances" to value.instances.map {row->mapOf(
            "activityIdentity" to System.identityHashCode(row.activity),"createEntered" to row.createEntered,"createConfirmed" to row.createConfirmed,
            "contextAttempted" to row.contextAttempted,"mainAttempted" to row.mainAttempted,"storageAttempted" to row.storageAttempted,
            "sessionAttempted" to row.sessionAttempted,"bindings" to row.bindings.size,"observedSessions" to row.observedSessions.size,
            "observedMigrations" to row.observedMigrations.size,"destroyEntered" to row.destroyEntered,"destroyConfirmed" to row.destroyConfirmed,
            "problem" to row.problem?.javaClass?.simpleName)})}
}

/** Only finite local fixture files are forwarded; app services and content/permissions are not virtualized as real success. */
internal class P8MainFilesContext private constructor(actual: Context,val run: String,val root: File):ContextWrapper(actual) {
    val alias="p8-main-preference-$run"
    val denied=CopyOnWriteArrayList<String>()
    private fun deny(name: String): Nothing {denied.add(name);error("P8 local menu forbids runtime port: $name")}
    override fun getApplicationContext(): Context=this
    override fun getNoBackupFilesDir()=File(root,"no-backup")
    override fun getFilesDir()=File(root,"files")
    override fun getCacheDir()=File(root,"cache")
    override fun getSystemService(name: String): Any?=deny("system-service:$name")
    override fun getContentResolver(): android.content.ContentResolver=deny("content-resolver")
    override fun getSharedPreferences(name: String,mode: Int): android.content.SharedPreferences=deny("shared-preferences")
    override fun getDir(name: String,mode: Int): File=deny("private-directory")
    override fun openFileInput(name: String): java.io.FileInputStream=deny("open-file-input")
    override fun openFileOutput(name: String,mode: Int): java.io.FileOutputStream=deny("open-file-output")
    override fun getDatabasePath(name: String): File=deny("database")
    companion object {
        private fun directory(file: File) {
            val stat=android.system.Os.lstat(file.path)
            check(android.system.OsConstants.S_ISDIR(stat.st_mode)&&stat.st_uid==android.os.Process.myUid()&&stat.st_mode and 0x1ff==0x1c0)
            check(Files.isDirectory(file.toPath(),NOFOLLOW_LINKS)&&!Files.isSymbolicLink(file.toPath()))
        }
        fun assertDefaultFamiliesAbsent(actual: Context): Boolean {
            val value=NativeNaturalFamilyInventory.scan(actual.noBackupFilesDir,true)
            check(value.complete&&value.families.isEmpty()&&value.files==0&&value.bytes==0L) {"Default family is not absent; preserve installation and stop before Main startup"}
            return true
        }
        fun assertOrdinaryOwner(actual: Context) {
            if(NativeChatNotificationColdStart.activityEntries.get()==0)NativeChatNotificationColdStart.requireUnusedAcceptanceOwner(actual)
            check(NativeChatNotificationColdStart.active==null&&!NativeChatNotificationColdStart.naturalModeEntered&&NativeChatNotificationColdStart.defaultSelections.get()==0)
            check(!NativeNaturalEntryFence.hasMaterial(actual.noBackupFilesDir))
            check(Files.notExists(File(actual.noBackupFilesDir,NativeChatNotificationColdRouterFiles.NAMESPACE).toPath(),NOFOLLOW_LINKS))
            check(!NativeChatNotificationGraphColdStart.hasMaterial(actual))
        }
        fun create(actual: Context,run: String): P8MainFilesContext {
            check(UUID.fromString(run).toString()==run);assertOrdinaryOwner(actual);assertDefaultFamiliesAbsent(actual)
            val parent=P8MainMarkerRead.frameworkParent(actual,run)
            val root=File(parent,"p8-main-preference-$run")
            check(Files.notExists(root.toPath(),NOFOLLOW_LINKS));Files.createDirectory(root.toPath());android.system.Os.chmod(root.path,0x1c0)
            for(name in listOf("no-backup","files","cache")){val child=File(root,name);Files.createDirectory(child.toPath());android.system.Os.chmod(child.path,0x1c0)}
            File(root,"owner").apply{check(createNewFile());writeText(run);android.system.Os.chmod(path,0x180)}
            return open(actual,run)
        }
        fun open(actual: Context,run: String): P8MainFilesContext {
            check(UUID.fromString(run).toString()==run)
            val parent=P8MainMarkerRead.frameworkParent(actual,run)
            val root=File(parent,"p8-main-preference-$run");directory(root)
            for(name in listOf("no-backup","files","cache"))directory(File(root,name))
            val owner=File(root,"owner");val stat=android.system.Os.lstat(owner.path)
            check(android.system.OsConstants.S_ISREG(stat.st_mode)&&stat.st_uid==android.os.Process.myUid()&&stat.st_mode and 0x1ff==0x180&&stat.st_nlink==1L)
            // This debug marker must throw after an unknown close, never enter production's permanent hold on Main.
            val marker=P8MainMarkerRead.marker(root,owner,run)
            check(marker==run)
            return P8MainFilesContext(actual,run,root)
        }
    }
}

/** Fixed debug fixture only. No production ReadLifetime/hold, blocking retry, borrowed stream or default key access. */
internal object P8MainMarkerRead {
    private class Attempt(val run: String,val path: String) {
        val pid=android.os.Process.myPid();val nonce=NativeChatNotificationColdStart.processNonce
        var descriptor: java.io.FileDescriptor?=null;var openAttempted=false;var opened=false
        var closeAttempted=false;var closeConfirmed=false;var failure: Throwable?=null
    }
    private val unconfirmed=CopyOnWriteArrayList<Attempt>()
    fun hasUnknown(run: String)=unconfirmed.any {it.run==run}
    fun retainedSummary(run: String)=unconfirmed.filter {it.run==run}.take(8).map {row->mapOf(
        "path" to row.path,"pid" to row.pid,"nonce" to row.nonce,"openAttempted" to row.openAttempted,"opened" to row.opened,
        "closeAttempted" to row.closeAttempted,"closeConfirmed" to row.closeConfirmed,
        "descriptorIdentity" to (row.descriptor?.let {System.identityHashCode(it)}?:-1))}
    private fun noUnknown() {check(unconfirmed.isEmpty()) {"Original process retains an unconfirmed marker read; no retry before its exit"}}
    private fun identity(stat: android.system.StructStat,regular: Boolean=false)=listOf(stat.st_dev,stat.st_ino,stat.st_uid.toLong(),stat.st_mode.toLong())+
        if(regular)listOf(stat.st_nlink,stat.st_size,stat.st_mtime,stat.st_ctime)else emptyList()
    private fun <T> raw(run: String,file: File,flags: Int,body: (java.io.FileDescriptor)->T): T {
        val attempt=Attempt(run,file.path);attempt.openAttempted=true
        val fd=try {android.system.Os.open(file.path,flags,0).also {attempt.descriptor=it;attempt.opened=true}}
            catch(error: Throwable){attempt.failure=error;unconfirmed.add(attempt);throw error}
        var first: Throwable?=null
        try {return body(fd)}catch(error: Throwable){first=error;attempt.failure=error;throw error}
        finally {
            attempt.closeAttempted=true // Exact returned descriptor; exactly one close attempt, including body failures.
            try {android.system.Os.close(fd);attempt.closeConfirmed=true}catch(close: Throwable){
                attempt.failure=first?:close;unconfirmed.add(attempt) // Strong ownership before propagating; no hold/await/interrupt swallowing.
                if(first==null)throw close
                if(first!==close)first.addSuppressed(close)
            }
        }
    }
    fun frameworkParent(actual: Context,run: String): File {
        noUnknown();check(UUID.fromString(run).toString()==run)
        check(actual.packageName=="com.easyai.agenthub.android.acceptance"&&actual.applicationInfo.uid==android.os.Process.myUid())
        val parent=actual.noBackupFilesDir.canonicalFile
        check(parent==File(File(actual.applicationInfo.dataDir).canonicalFile,"no_backup"))
        val before=android.system.Os.lstat(parent.path);val mode=before.st_mode and 0x1ff
        // Fixed Android13 ContextImpl's new-directory default is 0771; existing 0700 remains accepted.
        check(android.system.OsConstants.S_ISDIR(before.st_mode)&&before.st_uid==android.os.Process.myUid()&&mode in setOf(0x1c0,0x1f9))
        // O_DIRECTORY is not in the public Android SDK; require the pinned directory on the returned read-only FD before use.
        raw(run,parent,android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NONBLOCK or android.system.OsConstants.O_NOFOLLOW){fd->
            val opened=android.system.Os.fstat(fd)
            check(android.system.OsConstants.S_ISDIR(opened.st_mode)&&identity(opened)==identity(before))
        }
        check(identity(android.system.Os.lstat(parent.path))==identity(before))
        return parent
    }
    fun marker(root: File,file: File,run: String): String {
        noUnknown();check(UUID.fromString(run).toString()==run&&file==File(root,"owner")&&file.parentFile==root)
        val directory=android.system.Os.lstat(root.path)
        check(android.system.OsConstants.S_ISDIR(directory.st_mode)&&directory.st_uid==android.os.Process.myUid()&&directory.st_mode and 0x1ff==0x1c0)
        return raw(run,root,android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NONBLOCK or android.system.OsConstants.O_NOFOLLOW){directoryFd->
            val openedDirectory=android.system.Os.fstat(directoryFd)
            check(android.system.OsConstants.S_ISDIR(openedDirectory.st_mode)&&identity(openedDirectory)==identity(directory))
            val before=android.system.Os.lstat(file.path)
            check(android.system.OsConstants.S_ISREG(before.st_mode)&&before.st_uid==android.os.Process.myUid()&&before.st_mode and 0x1ff==0x180&&before.st_nlink==1L&&before.st_size==36L)
            val bytes=ByteArray(36)
            raw(run,file,android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NONBLOCK or android.system.OsConstants.O_NOFOLLOW){fd->
                check(identity(android.system.Os.fstat(fd),true)==identity(before,true));var count=0
                while(count<bytes.size){val size=android.system.Os.read(fd,bytes,count,bytes.size-count);check(size>0);count+=size}
                check(identity(android.system.Os.fstat(fd),true)==identity(before,true)&&identity(android.system.Os.lstat(file.path),true)==identity(before,true))
            }
            check(identity(android.system.Os.fstat(directoryFd))==identity(directory)&&identity(android.system.Os.lstat(root.path))==identity(directory))
            bytes.toString(Charsets.UTF_8)
        }
    }
}

/** No live delegating client. Calls may be attempted by the real session's directory sweep; they are rejected locally. */
internal class P8MainDenyPorts: HostReads,CodexCommands {
    data class Rejected(val provider: String,val method: String,val clientId: String,val target: com.agenthub.core.TargetScope?,
        val projectCursor: String?=null,val threadCursor: String?=null)
    private val rejected=CopyOnWriteArrayList<Rejected>()
    val overflow=java.util.concurrent.atomic.AtomicInteger()
    fun snapshot(): List<Rejected> =synchronized(rejected){rejected.toList()}
    private fun denied(name: String,client: MobileClient,projectCursor: String?=null,threadCursor: String?=null): Nothing {
        val target=client.agentConnection?.target?:client.scope
        val id=target?.agentId?:"codex"
        val provider=if(id in setOf("codex","easywork"))id else "other"
        synchronized(rejected) {
            if(rejected.size<64)rejected.add(Rejected(provider,name,client.id,target,projectCursor,threadCursor))else overflow.incrementAndGet()
        }
        error("Owned P8 local fixture rejects $provider/$name")
    }
    override fun agentTitle(client: MobileClient)=if(client.agentConnection?.target?.agentId=="easywork")"EasyWork"else"Codex"
    override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace=denied("workspace",client,projectCursor,threadCursor)
    override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory=denied("history",client)
    override suspend fun searchHistory(client: MobileClient,threadId: String,cursor: String?): MobileHistory=denied("searchHistory",client)
    override suspend fun nextTurnSuggestion(client: MobileClient,scope: com.agenthub.core.SuggestionScope): MobileDesktopSuggestionResponse=denied("nextTurnSuggestion",client)
    override suspend fun acknowledgeRead(client: MobileClient,request: NativePendingRead): org.json.JSONObject=denied("acknowledgeRead",client)
    override suspend fun archiveWorkspace(client: MobileClient,request: WorkspaceArchiveRequest): WorkspaceArchiveReceipt=denied("archiveWorkspace",client)
    override suspend fun workspaceArchiveStatus(client: MobileClient,request: WorkspaceArchiveRequest): WorkspaceArchiveReceipt=denied("workspaceArchiveStatus",client)
    override suspend fun chatAction(client: MobileClient,request: ChatActionRequest): ChatActionReceipt=denied("chatAction",client)
    override suspend fun chatActionStatus(client: MobileClient,threadId: String,operationId: String): ChatActionReceipt=denied("chatActionStatus",client)
    override suspend fun voiceReadiness(client: MobileClient): VoiceReadiness=denied("voiceReadiness",client)
    override suspend fun voiceStatus(client: MobileClient,request: VoiceManifest): VoiceReceipt=denied("voiceStatus",client)
    override suspend fun prepareVoice(client: MobileClient,request: VoiceManifest): VoiceReceipt=denied("prepareVoice",client)
    override suspend fun uploadVoice(client: MobileClient,request: VoiceManifest,bytes: ByteArray): VoiceReceipt=denied("uploadVoice",client)
    override suspend fun createProject(client: MobileClient,name: String,directory: String,operationId: String): MobileProject=denied("createProject",client)
    override suspend fun createdProject(client: MobileClient,operationId: String): MobileProject=denied("createdProject",client)
    override suspend fun createdThread(client: MobileClient,operationId: String): MobileThread=denied("createdThread",client)
    override suspend fun forkOptions(client: MobileClient,threadId: String): ThreadForkOptions=denied("forkOptions",client)
    override suspend fun forkThread(client: MobileClient,request: ThreadForkRequest): ThreadForkOperation=denied("forkThread",client)
    override suspend fun forkStatus(client: MobileClient,threadId: String,operationId: String): ThreadForkOperation=denied("forkStatus",client)
    override suspend fun usage(client: MobileClient): String=denied("usage",client)
    override suspend fun models(client: MobileClient): List<CodexModel> =denied("models",client)
    override suspend fun events(client: MobileClient,threadId: String,epoch: String,cursor: Long): EventBatch=denied("events",client)
    override suspend fun send(client: MobileClient,intent: ReplyIntent): DeliveryReceipt=denied("send",client)
    override suspend fun receipt(client: MobileClient,threadId: String,operationId: String): DeliveryReceipt=denied("receipt",client)
    override suspend fun replyDeliveryStatus(client: MobileClient,threadId: String,operationId: String): org.json.JSONObject=denied("replyDeliveryStatus",client)
    override suspend fun createThread(client: MobileClient,projectId: String?,operationId: String): MobileThread=denied("createThread",client)
    override suspend fun prepareCreation(client: MobileClient,intent: CreationIntent): DesktopCreation=denied("prepareCreation",client)
    override suspend fun creation(client: MobileClient,operationId: String,inspectOnly: Boolean): DesktopCreation=denied("creation",client)
    override suspend fun submitCreation(client: MobileClient,operationId: String): DesktopCreation=denied("submitCreation",client)
    override suspend fun detachCreation(client: MobileClient,operationId: String): DesktopCreation=denied("detachCreation",client)
    override suspend fun open(client: MobileClient,threadId: String,operationId: String,queryOnly: Boolean): DesktopOpen=denied("open",client)
    override suspend fun stop(client: MobileClient,threadId: String,turnId: String): Unit=denied("stop",client)
    override suspend fun executeQueue(client: MobileClient,threadId: String,operationId: String,expectedTurnId: String?): Unit=denied("executeQueue",client)
    override suspend fun cancelQueue(client: MobileClient,threadId: String,operationId: String): Unit=denied("cancelQueue",client)
    override suspend fun answer(client: MobileClient,threadId: String,turnId: String,approvalId: String,decision: String,answers: Map<String,List<String>>): Unit=denied("answer",client)
    override suspend fun upload(client: MobileClient,threadId: String,file: CreationFile,bytes: ByteArray): MobileFile=denied("upload",client)
    override suspend fun resolveMessageFile(client: MobileClient,threadId: String,messageId: String,reference: String): MobileFile=denied("resolveMessageFile",client)
    override suspend fun downloadResource(client: MobileClient,file: MobileFile,cache: File): File=denied("downloadResource",client)
    override suspend fun uploadCreation(client: MobileClient,operationId: String,file: CreationFile,bytes: ByteArray): Unit=denied("uploadCreation",client)
}
