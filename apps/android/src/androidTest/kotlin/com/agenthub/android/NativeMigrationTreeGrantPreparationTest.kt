package com.agenthub.android

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.AtomicFile
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Pure readiness decision; only a refreshed exact default Home root can pass. */
internal object NativeTreeHomeReadiness {
    data class Sample(val owner: String?,val windowReady: Boolean)
    data class Result(val outcome: String,val actualOwner: String?,val waitedMs: Long,val samples: Int,val unavailableSamples: Int,val windowReady: Boolean) {
        val ready get()=outcome=="exact-home-ready"
    }
    fun await(expectedHome: String,budgetMs: Long,now: ()->Long,pause: (Long)->Unit,read: ()->Sample): Result {
        require(expectedHome.isNotBlank()&&budgetMs in 1..5_000)
        val start=now();var samples=0;var unavailable=0
        while(true) {
            val sample=read();samples++;val elapsed=(now()-start).coerceAtLeast(0)
            if(sample.owner!=null&&sample.owner!=expectedHome)
                return Result("different-owner",sample.owner,elapsed,samples,unavailable,sample.windowReady)
            if(elapsed>budgetMs)return Result("ax-readiness-timeout",sample.owner,elapsed,samples,unavailable,sample.windowReady)
            if(sample.windowReady&&sample.owner==expectedHome)return Result("exact-home-ready",sample.owner,elapsed,samples,unavailable,true)
            unavailable++
            if(elapsed>=budgetMs)return Result("ax-readiness-timeout",sample.owner,elapsed,samples,unavailable,sample.windowReady)
            pause(minOf(40,budgetMs-elapsed))
        }
    }
}

/** App-scoped resolver validation; visible candidates never replace the resolved default. */
internal object NativeTreeHomeResolution {
    data class Candidate(val packageName: String,val activityName: String,val uid: Int,val enabled: Boolean,val exported: Boolean)
    fun accepted(resolved: Candidate?,visible: List<Candidate>,app: String,appUid: Int): Boolean {
        if(resolved==null||visible.isEmpty()||visible.size>16)return false
        if(resolved.packageName.isBlank()||resolved.activityName.isBlank()||resolved.uid<0||!resolved.enabled||!resolved.exported)return false
        if(resolved.uid==appUid||resolved.packageName in setOf(app,"$app.test","com.android.settings","android"))return false
        if(resolved.activityName.substringAfterLast('.') in setOf("FallbackHome","ResolverActivity","ChooserActivity"))return false
        return visible.any{it==resolved}
    }
}

/** Ownership and geometry gates for a bounded diagnostic, never permission or click authority. */
internal object NativeTreeConfirmationObservation {
    data class Bounds(val left: Int,val top: Int,val right: Int,val bottom: Int) {
        val nonempty get()=left<right&&top<bottom
        fun contains(other: Bounds)=nonempty&&other.nonempty&&left<=other.left&&top<=other.top&&right>=other.right&&bottom>=other.bottom
    }
    fun scoped(owner: String?,expected: String,originalSeen: Boolean)=originalSeen&&owner==expected
    fun folderVisible(folder: String,text: String?,visible: Boolean,password: Boolean,bounds: Bounds,root: Bounds,screen: Bounds)=
        folder.isNotEmpty()&&visible&&!password&&text?.contains(folder)==true&&root.contains(bounds)&&screen.contains(bounds)
}

/** Environment preparation, NEVER product migration acceptance. No automatic Activity rule,
 * shell adoption, synthetic result, replacement registry, provider fixture or product command. */
class NativeMigrationTreeGrantPreparationTest {
    @get:Rule val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(180)
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val app="com.easyai.agenthub.android.acceptance"
    private val authority="com.android.externalstorage.documents"
    private data class Request(val run: String,val operation: String,val documentId: String,val folder: String,
        val initial: Uri,val expected: Uri,val root: File,val record: File)
    private data class Grant(val uri: Uri,val read: Boolean,val write: Boolean)
    private data class Outcome(val ready: Boolean,val phase: String)
    private var scenario: ActivityScenario<MainActivity>?=null
    private val modes=Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    private fun <T> main(body: ()->T): T {var value: T?=null;i.runOnMainSync{value=body()};@Suppress("UNCHECKED_CAST")return value as T}
    private fun hash(value: String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun apk(runner: Boolean): String {
        val info=if(runner)i.targetContext.packageManager.getApplicationInfo("$app.test",0)else i.targetContext.applicationInfo
        check(info.splitSourceDirs.isNullOrEmpty());val file=File(info.sourceDir);check(file.length() in 1..128L*1024*1024)
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
        file.inputStream().use{input->while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
    private fun request(): Request {
        val context=i.targetContext;val args=InstrumentationRegistry.getArguments()
        check(context.packageName==app&&i.context.packageName=="$app.test"&&context.applicationInfo.uid==Process.myUid())
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE!=0)
        check(context.packageManager.checkSignatures(app,"$app.test")==PackageManager.SIGNATURE_MATCH)
        check(args.getString("migrationTreePreparationLease")=="1"){"Exact parent-held environment-preparation lease required"}
        // Remove any inherited adoption; all grant/persistence calls must use the actual App UID.
        i.uiAutomation.dropShellPermissionIdentity()
        val run=checkNotNull(args.getString("migrationSafRun"));check(UUID.fromString(run).toString()==run)
        val operation=checkNotNull(args.getString("migrationTreeOperation"));check(UUID.fromString(operation).toString()==operation)
        val folder="agent-hub-picker-$run";val documentId="primary:Download/$folder"
        val root=File(context.filesDir,"acceptance/p7-tree-preparation/$run")
        return Request(run,operation,documentId,folder,DocumentsContract.buildDocumentUri(authority,documentId),
            DocumentsContract.buildTreeDocumentUri(authority,documentId),root,File(root,"operation-$operation.json"))
    }
    private fun exact(uri: Uri,r: Request)=uri.scheme=="content"&&uri.authority==authority&&uri.query==null&&uri.fragment==null&&
        uri.pathSegments==listOf("tree",r.documentId)&&DocumentsContract.isTreeUri(uri)&&DocumentsContract.getTreeDocumentId(uri)==r.documentId
    private fun grants()=i.targetContext.contentResolver.persistedUriPermissions.map{Grant(it.uri,it.isReadPermission,it.isWritePermission)}
    private fun grantHash(values: List<Grant>)=hash(values.map{"${it.uri}|${it.read}|${it.write}"}.sorted().joinToString("\n"))
    private fun scopeOnly(r: Request,values: List<Grant>) {
        check(values.size<=1&&values.all{exact(it.uri,r)}){"Unrelated/broader persisted grant preserved; do not launch or revoke it"}
    }
    private fun ready(r: Request): Boolean {
        val values=grants();scopeOnly(r,values)
        return values.singleOrNull()?.let{it.read&&it.write}==true&&
            i.targetContext.checkUriPermission(r.expected,Process.myPid(),Process.myUid(),modes)==PackageManager.PERMISSION_GRANTED
    }
    private fun emptyDirectory(r: Request,uri: Uri) {
        check(exact(uri,r));val resolver=i.targetContext.contentResolver
        check(i.targetContext.checkUriPermission(uri,Process.myPid(),Process.myUid(),modes)==PackageManager.PERMISSION_GRANTED)
        val found=i.targetContext.packageManager.resolveContentProvider(authority,0)
        evidence(r,"provider-resolution-${r.operation}.json",JSONObject().put("kind","provider-metadata-after-real-uri-grant")
            .put("operation",r.operation).put("appUid",Process.myUid()).put("authority",authority).put("actualReadWriteGrant",true)
            .put("providerPackage",found?.packageName?:JSONObject.NULL).put("providerClass",found?.name?:JSONObject.NULL)
            .put("providerUid",found?.applicationInfo?.uid?:JSONObject.NULL).put("mainLaunchedByProbe",false).put("grantMutation",false))
        val provider=checkNotNull(found)
        check(provider.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM!=0&&provider.applicationInfo.uid!=Process.myUid())
        val document=DocumentsContract.buildDocumentUriUsingTree(uri,r.documentId)
        checkNotNull(resolver.query(document,arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE),null,null,null)).use{
            check(it.moveToFirst()&&it.getString(0)==r.folder&&it.getString(1)==DocumentsContract.Document.MIME_TYPE_DIR&&!it.moveToNext())
        }
        val children=DocumentsContract.buildChildDocumentsUriUsingTree(uri,r.documentId)
        checkNotNull(resolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),null,null,null)).use{
            check(!it.moveToFirst()){"Only the exact empty UUID directory is eligible"}
        }
    }
    private fun evidence(r: Request,name: String,value: JSONObject) {
        check(name.matches(Regex("[a-z0-9-]+\\.json")))
        check(r.root.mkdirs()||r.root.isDirectory);check(!java.nio.file.Files.isSymbolicLink(r.root.toPath()))
        android.system.Os.chmod(r.root.path,0x1c0)
        val file=File(r.root,name);val atomic=AtomicFile(file);val output=atomic.startWrite()
        try{output.write(value.toString().toByteArray());atomic.finishWrite(output);android.system.Os.chmod(file.path,0x180)}
        catch(failure: Throwable){atomic.failWrite(output);throw failure}
    }
    private fun existing(r: Request): JSONObject? {
        check(!File(r.record.path+".new").exists()&&!File(r.record.path+".bak").exists()){"Partial original preparation receipt preserved"}
        if(!r.record.exists())return null
        check(r.record.isFile&&!java.nio.file.Files.isSymbolicLink(r.record.toPath())&&r.record.length() in 1..16_384)
        return JSONObject(r.record.readText()).also{
            check(it.getInt("version")==1&&it.getString("app")==app&&it.getString("registryKey")=="p7-owned-tree-${r.operation}")
            check(it.getString("operation")==r.operation&&it.getString("run")==r.run&&it.getString("expectedTree")==r.expected.toString()&&it.getInt("appUid")==Process.myUid())
        }
    }
    private fun refuseOtherPendingOriginals(r: Request) {
        val files=if(r.root.exists())checkNotNull(r.root.listFiles())else emptyArray()
        val originals=files.filter{it.name.startsWith("operation-")};check(originals.size<=16)
        for(file in originals) {
            if(file==r.record)continue
            check(file.name.matches(Regex("operation-[a-f0-9-]{36}\\.json"))&&file.isFile&&!java.nio.file.Files.isSymbolicLink(file.toPath())&&file.length() in 1..16_384){"Partial other original preserved; a different key cannot evade it"}
            val value=JSONObject(file.readText());val id=value.getString("operation");check(UUID.fromString(id).toString()==id&&file.name=="operation-$id.json")
            check(value.getInt("version")==1&&value.getString("run")==r.run&&value.getString("expectedTree")==r.expected.toString()&&value.getInt("appUid")==Process.myUid())
            check(value.getString("phase") in setOf("persisted","cancelled","rejected")){"Another original for this UUID tree is pending/unknown; inspect it, never retry under a new key"}
        }
    }
    private inner class Receipt(val request: Request,val before: List<Grant>) {
        private val original=JSONObject().put("version",1).put("operation",request.operation).put("run",request.run)
            .put("registryKey","p7-owned-tree-${request.operation}").put("app",app).put("appUid",Process.myUid())
            .put("appSha256",apk(false)).put("runnerSha256",apk(true)).put("initialDocument",request.initial.toString()).put("expectedTree",request.expected.toString())
            .put("beforeGrantHash",grantHash(before)).put("productMigrationPass",false)
        private var terminal=false
        private val observed=JSONObject()
        init {
            check(request.root.canonicalFile.path==File(i.targetContext.filesDir.canonicalFile,"acceptance/p7-tree-preparation/${request.run}").absolutePath)
            check(request.root.mkdirs()||request.root.isDirectory)
            check(request.record.createNewFile()){"An original preparation already exists; never replace/replay it"}
            android.system.Os.chmod(request.record.path,0x180)
        }
        @Synchronized fun state(phase: String,extra: JSONObject=JSONObject()) {
            if(terminal)return
            val value=JSONObject(original.toString()).put("phase",phase).put("updatedAt",System.currentTimeMillis())
            extra.keys().forEach{key->check(key !in original.keys().asSequence().toSet());observed.put(key,extra.get(key))}
            observed.keys().forEach{key->value.put(key,observed.get(key))}
            evidence(request,"operation-${request.operation}.json",value)
            terminal=phase in setOf("persisted","cancelled","rejected")
        }
    }
    private fun profile(): JSONObject {
        val raw=InstrumentationRegistry.getArguments().getString("migrationTreeProfile")
        val value=raw?.let(::JSONObject)?:run{
            check(android.os.Build.VERSION.SDK_INT==37){"Observe this device/API tree controls first"}
            // Parent's real external TREE observation, not an App callback or grant proof.
            JSONObject().put("package","com.google.android.documentsui")
                .put("folderTitleId","com.google.android.documentsui:id/breadcrumb_text")
                .put("useFolderId","android:id/button1").put("useFolderText","USE THIS FOLDER")
        }
        check(value.keys().asSequence().all{it in setOf("package","folderTitleId","useFolderId","useFolderText","confirmationId","confirmationText")})
        val pkg=value.getString("package");check(pkg in setOf("com.google.android.documentsui","com.android.documentsui"))
        for(key in listOf("folderTitleId","useFolderId","confirmationId"))if(value.has(key))check(value.getString(key).matches(Regex("(?:${Regex.escape(pkg)}|android):id/[a-zA-Z0-9_]+")))
        check(value.has("confirmationId")==value.has("confirmationText"));return value
    }
    private fun ownerSample(): NativeTreeHomeReadiness.Sample {
        val root=i.uiAutomation.rootInActiveWindow ?: return NativeTreeHomeReadiness.Sample(null,false)
        return try { val fresh=root.refresh();NativeTreeHomeReadiness.Sample(if(fresh)root.packageName?.toString()else null,fresh&&root.windowId>=0) }
        finally { @Suppress("DEPRECATION")root.recycle() }
    }
    private fun owner()=ownerSample().owner
    private fun <T> nodes(pkg: String,root: AccessibilityNodeInfo=checkNotNull(i.uiAutomation.rootInActiveWindow),body: (List<AccessibilityNodeInfo>)->T): T {
        val all=mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo,depth: Int){check(depth<=40&&all.size<2048&&node.packageName?.toString()==pkg);all+=node;for(n in 0 until node.childCount)node.getChild(n)?.let{visit(it,depth+1)}}
        try{check(root.packageName?.toString()==pkg);visit(root,0);return body(all)}finally{@Suppress("DEPRECATION")(if(all.none{it===root})all+root else all).forEach{it.recycle()}}
    }
    private fun click(pkg: String,id: String,text: String,r: Request,folderId: String?) {
        nodes(pkg){all->
            check(all.any{it.isVisibleToUser&&(if(folderId!=null)it.viewIdResourceName==folderId&&it.text?.toString()==r.folder else it.text?.toString()?.contains(r.folder)==true)}){"Exact own folder witness is required before grant input"}
            val button=all.single{it.viewIdResourceName==id&&it.text?.toString()==text&&it.className?.toString()=="android.widget.Button"&&it.isVisibleToUser}
            check(owner()==pkg&&button.refresh()&&button.isEnabled&&button.isClickable)
            check(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
    }
    private fun bounds(node: AccessibilityNodeInfo)=Rect().also{node.getBoundsInScreen(it)}
    private fun box(rect: Rect)=NativeTreeConfirmationObservation.Bounds(rect.left,rect.top,rect.right,rect.bottom)
    private fun rectJson(rect: Rect)=JSONArray(listOf(rect.left,rect.top,rect.right,rect.bottom))
    @Suppress("DEPRECATION") private fun screenBounds(): Rect {
        val display=checkNotNull(i.targetContext.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY))
        val metrics=DisplayMetrics();display.getRealMetrics(metrics);check(metrics.widthPixels>0&&metrics.heightPixels>0)
        return Rect(0,0,metrics.widthPixels,metrics.heightPixels)
    }
    private fun controls(r: Request,pkg: String,originalSeen: Boolean): Boolean {
        val start=SystemClock.elapsedRealtime();val root=i.uiAutomation.rootInActiveWindow;val actual=root?.packageName?.toString()
        val result=JSONObject().put("kind","observation-only").put("operation",r.operation).put("expectedTree",r.expected.toString())
            .put("actualOwner",actual?:JSONObject.NULL).put("startedElapsedRealtimeMs",start).put("originalOwnFolderSeen",originalSeen)
            .put("buttonCap",32).put("buttonTextCap",160).put("visualFullTextVerified",false)
        var valid=false
        if(root!=null&&NativeTreeConfirmationObservation.scoped(actual,pkg,originalSeen)) {
            try { valid=nodes(pkg,root){all->
                check(root.refresh()&&root.packageName?.toString()==pkg)
                val screen=screenBounds();val rootBounds=bounds(root)
                val current=all.filter{it.refresh()&&it.packageName?.toString()==pkg}
                val own=root.isVisibleToUser&&current.any{!it.isPassword&&NativeTreeConfirmationObservation.folderVisible(r.folder,it.text?.toString(),it.isVisibleToUser,false,box(bounds(it)),box(rootBounds),box(screen))}
                result.put("screenBounds",rectJson(screen)).put("screenBoundsMethod","default-display-real-metrics").put("displayId",Display.DEFAULT_DISPLAY)
                    .put("rootBounds",rectJson(rootBounds)).put("rootWindowId",root.windowId).put("rootVisible",root.isVisibleToUser)
                    .put("ownFolderVisible",own).put("folderWitnessMethod","full AX folder text and contained native node bounds;parent screenshot still required")
                if(!own)return@nodes false
                val buttons=current.filter{it.isVisibleToUser&&!it.isPassword&&it.className?.toString()=="android.widget.Button"}
                result.put("visibleButtonCount",buttons.size).put("buttonsTruncated",buttons.size>32)
                    .put("buttons",JSONArray(buttons.take(32).map{node->
                        val rect=bounds(node);val text=node.text?.toString()
                        JSONObject().put("resourceId",node.viewIdResourceName?:JSONObject.NULL).put("class",node.className.toString()).put("visible",node.isVisibleToUser)
                            .put("enabled",node.isEnabled).put("clickable",node.isClickable).put("boundsInScreen",rectJson(rect))
                            .put("fullyInsideRootAndScreen",box(rootBounds).contains(box(rect))&&box(screen).contains(box(rect)))
                            .put("text",text?.takeIf{it.length<=160}?:JSONObject.NULL).put("textWithinCap",text==null||text.length<=160)
                    }))
                true
            }}catch(failure: Exception){result.put("failureClass",failure.javaClass.simpleName)}
        }else { @Suppress("DEPRECATION")root?.recycle() }
        val after=owner();valid=valid&&after==pkg
        if(!valid){result.remove("buttons");result.put("ownFolderVisible",false)}
        val end=SystemClock.elapsedRealtime();result.put("actualOwnerAfter",after?:JSONObject.NULL).put("observationValid",valid)
            .put("endedElapsedRealtimeMs",end).put("readDurationMs",end-start)
        evidence(r,"confirmation-controls-${r.operation}.json",result);return valid
    }
    private fun docsUiResolver(r: Request,p: JSONObject): ActivityInfo {
        val intent=ActivityResultContracts.OpenDocumentTree().createIntent(i.targetContext,r.initial)
        val found=i.targetContext.packageManager.resolveActivity(intent,PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        evidence(r,"docsui-resolution-${r.operation}-${UUID.randomUUID()}.json",JSONObject().put("kind","app-uid-tree-resolver-only")
            .put("operation",r.operation).put("appUid",Process.myUid()).put("expectedPackage",p.getString("package"))
            .put("resolvedPackage",found?.packageName?:JSONObject.NULL).put("resolvedClass",found?.name?:JSONObject.NULL)
            .put("resolvedUid",found?.applicationInfo?.uid?:JSONObject.NULL).put("providerProbeDeferredUntilUriGrant",true)
            .put("mainLaunched",false).put("pickerLaunched",false).put("grantMutation",false))
        val value=checkNotNull(found);check(value.packageName==p.getString("package")&&value.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM!=0&&value.applicationInfo.uid!=Process.myUid())
        return value
    }
    private fun noMainOrP9() {
        // Pure file metadata preflight, no NativeSessionStorage/Keystore/AtomicFile recovery.
        check(!NativeChatNotificationColdRouterFiles.hasReadableMarker(i.targetContext.noBackupFilesDir)){"P9 router remains sealed; no Main launch"}
        check(NativeChatNotificationColdStart.active==null){"P9 runtime binding still active; no Main launch"}
        check(main{Stage.values().filter{it!=Stage.DESTROYED}.flatMap{ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)}.none{it is MainActivity}}){"Existing Main owner is preserved; no launch/reuse"}
    }
    private fun homeCandidate(info: ActivityInfo)=NativeTreeHomeResolution.Candidate(info.packageName.orEmpty(),info.name.orEmpty(),
        info.applicationInfo?.uid?:-1,info.enabled&&info.applicationInfo?.enabled==true,info.exported)
    private fun candidateJson(value: NativeTreeHomeResolution.Candidate?)=value?.let{
        JSONObject().put("package",it.packageName).put("activity",it.activityName).put("uid",it.uid).put("enabled",it.enabled).put("exported",it.exported)
    }?:JSONObject.NULL
    private fun resolvedHome(r: Request): String {
        val pm=i.targetContext.packageManager;val intent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved=pm.resolveActivity(intent,PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.let(::homeCandidate)
        val visible=pm.queryIntentActivities(intent,PackageManager.MATCH_DEFAULT_ONLY).mapNotNull{it.activityInfo?.let(::homeCandidate)}
        // Flag-zero lookup is diagnostic only; it cannot replace a refused default resolver.
        val zero=pm.resolveActivity(intent,0)?.activityInfo?.let(::homeCandidate)
        val accepted=NativeTreeHomeResolution.accepted(resolved,visible,app,Process.myUid())
        evidence(r,"home-resolution-${r.operation}-${UUID.randomUUID()}.json",JSONObject().put("kind","app-uid-home-resolution-only")
            .put("appUid",Process.myUid()).put("resolverFlags",PackageManager.MATCH_DEFAULT_ONLY).put("resolvedHome",candidateJson(resolved))
            .put("zeroFlagHomeDiagnosticOnly",candidateJson(zero)).put("visibleHomeCount",visible.size)
            .put("visibleHomes",JSONArray(visible.take(16).map(::candidateJson))).put("candidateListTruncated",visible.size>16)
            .put("resolutionAccepted",accepted).put("shellIdentityUsed",false).put("mainLaunched",false).put("pickerLaunched",false).put("grantMutation",false))
        check(accepted){"App-scoped Home unresolved/fallback/incorrect resolver refused; no Main/picker launch"}
        return checkNotNull(resolved).packageName
    }
    private fun beforeMain(r: Request) {
        check(InstrumentationRegistry.getArguments().getString("migrationTreeMainLaunchReleased")=="1"){"Parent must first release P9 cold ownership; Main stays unopened"}
        noMainOrP9();scopeOnly(r,grants())
        val home=resolvedHome(r)
        val start=SystemClock.elapsedRealtime()
        val result=try {NativeTreeHomeReadiness.await(home,5_000,SystemClock::elapsedRealtime,SystemClock::sleep,::ownerSample)}
        catch(failure: Exception) {
            evidence(r,"home-readiness-${r.operation}-${UUID.randomUUID()}.json",JSONObject().put("kind","exact-home-readiness-only")
                .put("expectedHome",home).put("actualOwner",JSONObject.NULL).put("outcome","ax-read-failed").put("failureClass",failure.javaClass.simpleName)
                .put("waitedMs",SystemClock.elapsedRealtime()-start).put("mainLaunched",false).put("pickerLaunched",false).put("grantMutation",false))
            throw failure
        }
        evidence(r,"home-readiness-${r.operation}-${UUID.randomUUID()}.json",JSONObject().put("kind","exact-home-readiness-only")
            .put("expectedHome",home).put("actualOwner",result.actualOwner?:JSONObject.NULL).put("outcome",result.outcome)
            .put("waitedMs",result.waitedMs).put("budgetMs",5_000).put("samples",result.samples).put("unavailableSamples",result.unavailableSamples)
            .put("windowReady",result.windowReady).put("mainLaunched",false).put("pickerLaunched",false).put("grantMutation",false))
        check(result.ready){"Exact Home preflight ${result.outcome}; existing owner preserved; no Main/picker launch"}
        // Waiting cannot borrow a gate that changed while AX connected.
        noMainOrP9();scopeOnly(r,grants())
    }
    private fun await(ms: Long,condition: ()->Boolean): Boolean {
        val end=SystemClock.elapsedRealtime()+ms
        while(SystemClock.elapsedRealtime()<end){if(condition())return true;SystemClock.sleep(40)}
        return condition()
    }
    @Test fun auditOnlyExactPersistedTreeWithoutOpeningMainOrChangingPermissions() {
        val r=request();val before=grants();val prior=existing(r)
        val isReady=runCatching{ready(r)&&run{emptyDirectory(r,r.expected);true}}.getOrDefault(false)
        val original=prior?.optString("phase")=="persisted"&&prior.optBoolean("realActivityResult")&&prior.optBoolean("systemOwnerObserved")
        evidence(r,"audit-${UUID.randomUUID()}.json",JSONObject().put("kind","read-only-grant-audit").put("operation",r.operation)
            .put("expectedTree",r.expected.toString()).put("grantReady",isReady).put("originalCallbackReceipt",original)
            .put("mainLaunched",false).put("pickerLaunched",false).put("grantMutation",false).put("productMigrationPass",false)
            .put("grantHashUnchanged",grantHash(before)==grantHash(grants())))
        check(isReady&&original){"Exact grant and original App callback receipt are not both ready; audit is not a grant/product pass"}
    }
    @Test fun auditExactHomeReadinessWithoutOpeningMainOrChangingPermissions() {
        val r=request();val before=grantHash(grants());beforeMain(r)
        check(before==grantHash(grants())){"Home readiness audit changed no grants"}
    }
    @Test fun auditTreeResolverReadinessWithoutOpeningMainOrChangingPermissions() {
        val r=request();val before=grantHash(grants());beforeMain(r);docsUiResolver(r,profile())
        check(before==grantHash(grants())){"Resolver audit changed no grants"}
    }
    @Test fun acquireExactOwnedTreeThroughActualAcceptanceActivityResultRegistry() {
        val r=request();val prior=existing(r);val baseline=grants();scopeOnly(r,baseline)
        if(prior!=null){
            check(prior.optString("phase")=="persisted"&&prior.optBoolean("realActivityResult")&&prior.optBoolean("systemOwnerObserved")&&ready(r)){"Original preparation is pending/cancelled/rejected; inspect it, never replay its operation ID"}
            emptyDirectory(r,r.expected)
            evidence(r,"reuse-${UUID.randomUUID()}.json",JSONObject().put("kind","matching-original-grant-reuse").put("operation",r.operation).put("pickerLaunched",false).put("productMigrationPass",false));return
        }
        refuseOtherPendingOriginals(r)
        check(!ready(r)){"Permission alone is not original App-callback provenance; audit before any new attempt"}
        beforeMain(r);val p=profile();val pkg=p.getString("package");val contract=ActivityResultContracts.OpenDocumentTree()
        docsUiResolver(r,p)
        val receipt=Receipt(r,baseline);receipt.state("prepared")
        val result=CompletableFuture<Outcome>();val claimed=AtomicBoolean(false);val seenSystemOwner=AtomicBoolean(false);val executor=Executors.newSingleThreadExecutor()
        var launcher: ActivityResultLauncher<Uri?>?=null;var unknown=false;var submitted=false;var observeConfirmation=false;val previousFlags=i.uiAutomation.serviceInfo.flags
        try {
            scenario=ActivityScenario.launch(Intent(i.targetContext,MainActivity::class.java))
            lateinit var activity: MainActivity;scenario!!.onActivity{activity=it}
            check(await(30_000){main{!activity.session.restoring}})
            check(activity.packageName==app&&activity.applicationInfo.uid==Process.myUid())
            val viewIds=i.uiAutomation.serviceInfo;viewIds.flags=viewIds.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;i.uiAutomation.serviceInfo=viewIds
            main {
                launcher=activity.activityResultRegistry.register("p7-owned-tree-${r.operation}",contract){selected->
                    if(claimed.compareAndSet(false,true))executor.execute{
                        try {
                            if(selected==null){check(grantHash(baseline)==grantHash(grants()));receipt.state("cancelled",JSONObject().put("realActivityResult",true));result.complete(Outcome(false,"cancelled"))}
                            else {
                                check(exact(selected,r)){"Wrong directory result; never persist or inspect it"}
                                check(seenSystemOwner.get()){"Actual exact-folder DocsUI owner was not observed"}
                                receipt.state("callback",JSONObject().put("realActivityResult",true).put("systemOwnerObserved",true).put("docsUiPackage",pkg).put("returnedTree",selected.toString()))
                                check(i.targetContext.checkUriPermission(selected,Process.myPid(),Process.myUid(),modes)==PackageManager.PERMISSION_GRANTED)
                                emptyDirectory(r,selected)
                                i.targetContext.contentResolver.takePersistableUriPermission(selected,modes)
                                check(ready(r));emptyDirectory(r,selected)
                                receipt.state("persisted",JSONObject().put("realActivityResult",true).put("systemOwnerObserved",true).put("docsUiPackage",pkg).put("returnedTree",selected.toString()).put("persistedRead",true).put("persistedWrite",true).put("emptyDirectory",true).put("afterGrantHash",grantHash(grants())))
                                result.complete(Outcome(true,"persisted"))
                            }
                        }catch(failure: Throwable){receipt.state("rejected",JSONObject().put("realActivityResult",true).put("reasonClass",failure.javaClass.simpleName));result.complete(Outcome(false,"rejected"))}
                    }
                }
                receipt.state("submitted");submitted=true;launcher!!.launch(r.initial)
            }
            check(await(15_000){
                if(owner()==pkg&&nodes(pkg){all->all.any{it.viewIdResourceName==p.getString("folderTitleId")&&it.text?.toString()==r.folder&&it.isVisibleToUser}})seenSystemOwner.set(true)
                result.isDone||seenSystemOwner.get()
            })
            if(!result.isDone){
                val info=i.uiAutomation.serviceInfo;info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;i.uiAutomation.serviceInfo=info
                click(pkg,p.getString("useFolderId"),p.getString("useFolderText"),r,p.getString("folderTitleId"))
                if(!await(500){result.isDone}){
                    val observed=controls(r,pkg,seenSystemOwner.get())
                    observeConfirmation=observed&&!p.has("confirmationId")
                    if(observed&&p.has("confirmationId")){
                        check(await(15_000){result.isDone||owner()==pkg&&nodes(pkg){all->all.any{it.viewIdResourceName==p.getString("confirmationId")&&it.text?.toString()==p.getString("confirmationText")&&it.isVisibleToUser}}})
                        if(!result.isDone)click(pkg,p.getString("confirmationId"),p.getString("confirmationText"),r,null)
                    } else receipt.state("awaiting-confirmation-observation")
                }
            }
            val wait=InstrumentationRegistry.getArguments().getString("migrationTreeConfirmationWaitMs")?.toLongOrNull()?:60_000L;check(wait in 1000..120_000)
            var nextObservation=SystemClock.elapsedRealtime()+2_000
            if(!await(wait){
                if(!result.isDone&&observeConfirmation&&SystemClock.elapsedRealtime()>=nextObservation) {
                    observeConfirmation=controls(r,pkg,seenSystemOwner.get())
                    nextObservation=SystemClock.elapsedRealtime()+2_000
                }
                result.isDone
            }){
                unknown=true;receipt.state("unknown-wait",JSONObject().put("actualOwner",owner()))
                error("Original caller/picker retained for parent observation; unknown result is never replayed")
            }
            val outcome=result.get(1,TimeUnit.SECONDS);check(outcome.ready){"Environment grant did not complete; not a product failure/pass"}
            check(ready(r));emptyDirectory(r,r.expected)
        } finally {
            if(submitted&&!result.isDone){unknown=true;receipt.state("unknown-wait",JSONObject().put("actualOwner",owner()))}
            val info=i.uiAutomation.serviceInfo;info.flags=previousFlags;i.uiAutomation.serviceInfo=info
            // Unknown confirmation retains the exact original registration/caller for a possible
            // late real result. Record its runtime separately; survival after runner exit is NOT assumed.
            if(!unknown){main{launcher?.unregister()};scenario?.close();executor.shutdown()}
        }
    }
}
