package com.agenthub.android

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import java.io.File

/** SOURCE PROPOSAL: Root selects corrected shared candidate and P5 binds the exact native window.
 * Shipping MainActivity/default storage/registry/center + actual system picker. No resetAcceptance,
 * injected reader/commands, substituted picker result, direct upload API or model input.
 * One immutable case per fresh upload; reopen is a separate process phase on its original case.
 */
class P1EasyWorkIndependentUploadLiveTest {
    @get:Rule val ui=createEmptyComposeRule()
    private val i=InstrumentationRegistry.getInstrumentation()
    private val context get()=i.targetContext
    private lateinit var cfg:JSONObject
    private lateinit var folder:File
    private lateinit var activity:MainActivity
    private var scenario:ActivityScenario<MainActivity>?=null
    private var deadline=0L
    private fun remaining()=(deadline-SystemClock.elapsedRealtime()).coerceAtLeast(1)
    private fun main(block:()->Unit)=i.runOnMainSync(block)
    private fun waitFor(block:()->Boolean)=ui.waitUntil(remaining()) {var ok=false;main {ok=block()};ok}
    private fun save(name:String,value:JSONObject) {
        val f=File(folder,name);check(f.createNewFile()) {"Existing operation/evidence: query original; never replay"}
        f.setReadable(false,false);f.setWritable(false,false);f.setReadable(true,true);f.setWritable(true,true)
        f.writeText(value.toString());java.io.RandomAccessFile(f,"rw").use {it.fd.sync()}
    }
    private fun shot(name:String) {
        val f=File(folder,name);check(f.createNewFile())
        val bitmap=i.uiAutomation.takeScreenshot();try {f.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally {bitmap.recycle()}
    }
    private fun session()=activity.session
    private fun row():NativeUploadRow?=session().uploadJobs!!.rows.values.singleOrNull {
        it.manifest.file.name==cfg.getJSONObject("source").getString("name")&&it.target.matches(session().selectedClient)
    }
    private fun verifySource(row:NativeUploadRow) {
        val source=cfg.getJSONObject("source")
        require(row.manifest.purpose=="file-transfer"&&row.manifest.threadId==null&&row.manifest.file.name==source.getString("name")&&
            row.manifest.file.sizeBytes==source.getLong("sizeBytes")&&row.manifest.file.sha256==source.getString("sha256"))
    }
    @Before fun boundDefaultInstallation() {
        val path=InstrumentationRegistry.getArguments().getString("p1F59Config")
        Assume.assumeTrue("Root-selected private config path required",path!=null)
        require(path!!.matches(Regex("p1-f59/[a-zA-Z0-9_-]+[.]json")))
        cfg=JSONObject(File(context.filesDir,path).readText())
        require(cfg.keys().asSequence().toSet().all {it in setOf("enabled","rootCorrectedCandidateApproved","applicationId","uid","caseId","phase","source","appSha256","runnerSha256","signerSetSha256","nativeContextProofPath","nativeContextProofSha256","expectedFullEasyWorkTarget","mobileDeviceId","certificateFingerprint","timeoutMs","sourceFolderName","expectedMaximumBytes","previousProcessEnded","sourceCommit")})
        require(cfg.getBoolean("enabled")&&cfg.getBoolean("rootCorrectedCandidateApproved"))
        require(cfg.getString("sourceCommit").matches(Regex("[a-f0-9]{40}")))
        require(context.packageName=="com.easyai.agenthub.android.acceptance"&&context.packageName==cfg.getString("applicationId"))
        require(android.os.Process.myUid()==cfg.getInt("uid"))
        require(cfg.getString("caseId")==java.util.UUID.fromString(cfg.getString("caseId")).toString())
        val source=cfg.getJSONObject("source");ComputerFileCodec.validName(source.getString("name"))
        require(source.getString("name").startsWith("p1-f59-")&&source.getLong("sizeBytes")>0)
        require(source.getString("sha256").matches(Regex("[a-f0-9]{64}")))
        val installed=NativeChatNotificationNaturalStore.installed(context)
        require(installed.appHash==cfg.getString("appSha256")&&installed.runnerHash==cfg.getString("runnerSha256")&&
            installed.signerSetHash==cfg.getString("signerSetSha256"))
        val proofFile=File(context.filesDir,cfg.getString("nativeContextProofPath"))
        require(proofFile.canonicalPath.startsWith(File(context.filesDir,"p1-f59").canonicalPath+"/"))
        val proofBytes=proofFile.readBytes()
        require(ComputerFileCodec.hash(proofBytes)==cfg.getString("nativeContextProofSha256"))
        val proof=JSONObject(String(proofBytes,Charsets.UTF_8))
        require(easyCanonical(proof.getJSONObject("target"))==easyCanonical(cfg.getJSONObject("expectedFullEasyWorkTarget")))
        require(proof.getString("mobileDeviceId")==cfg.getString("mobileDeviceId")&&proof.getString("certificateFingerprint")==cfg.getString("certificateFingerprint"))
        require(proof.getBoolean("authenticated")&&"file-transfers" in proof.getJSONArray("capabilities").strings())
        require(proof.getString("appSha256")==cfg.getString("appSha256"))
        require(System.currentTimeMillis()-proof.getLong("authenticatedAtEpochMs") in 0..900000) {"Native context proof must be fresh for this phase"}
        deadline=SystemClock.elapsedRealtime()+cfg.getLong("timeoutMs").also {require(it in 1000..240000)}
        folder=File(context.filesDir,"p1-f59/evidence/${cfg.getString("caseId")}")
        if(cfg.getString("phase")=="reopen")require(folder.isDirectory&&File(folder,"paused.json").isFile)
        else require(!folder.exists()&&folder.mkdirs())
        save(if(cfg.getString("phase")=="reopen")"reopen-intent.json" else "intent.json",cfg)
        scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        scenario!!.onActivity {activity=it}
        waitFor {!session().restoring&&session().selectedClient.online}
        main {
            val s=session();val c=s.selectedClient;val connection=requireNotNull(c.agentConnection)
            require(sameScope(c.scope,EasyWorkTarget.decode(cfg.getJSONObject("expectedFullEasyWorkTarget")).scope()))
            require(sameScope(connection.target,c.scope)&&connection.candidates.any {it.peerFingerprint==cfg.getString("certificateFingerprint")})
            // Read locally in-process; no credential/key material goes to evidence or tool output.
            val credential=EasyWorkCredential.decode(JSONObject(s.migrationStorage.read("easywork-credential:${connection.credentialRef}")!!))
            require(credential.binding.mobileId==cfg.getString("mobileDeviceId")&&sameScope(credential.binding.target.scope(),c.scope))
            require(s.uploadJobs!=null)
        }
        ui.onNodeWithTag("open-sidebar").performClick()
        ui.onNodeWithTag("sidebar-file-transfers").performClick()
        waitFor {session().computerFiles?.let {!it.busy&&it.catalog!=null&&it.canChoose}==true}
        main {require(session().computerFiles!!.uploads===session().uploadJobs)}
        shot(if(cfg.getString("phase")=="reopen")"reopen-ready.png" else "ready.png")
    }
    @After fun closeOnlyOwnActivity() {scenario?.close()} // No force-stop, profile deletion, grant revoke or source cleanup.

    /** Accessibility click on real DocumentsUI. Never substitutes a URI/result callback. */
    private fun systemClick(labels:Set<String>) {
        fun find(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
            if(n==null)return null
            if(n.text?.toString() in labels||n.contentDescription?.toString() in labels)return n
            for(index in 0 until n.childCount)find(n.getChild(index))?.let {return it}
            return null
        }
        var found:AccessibilityNodeInfo?=null
        while(SystemClock.elapsedRealtime()<deadline) {
            val root=i.uiAutomation.rootInActiveWindow
            if(root?.packageName?.toString() !in setOf("com.google.android.documentsui","com.android.documentsui")) {SystemClock.sleep(100);continue}
            found=find(root);if(found!=null)break;SystemClock.sleep(100)
        }
        var node=checkNotNull(found) {"System picker node missing; retain original case"}
        while(!node.isClickable)node=node.parent?:error("Picker node has no click action")
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun selectOriginalThroughPicker() {
        ui.onNodeWithTag("choose-transfer-files").performClick()
        // Sources are pushed by P5 to this exact task-owned Downloads subdirectory.
        systemClick(setOf("Show roots","显示根目录","显示根目录菜单"))
        systemClick(setOf("Downloads","下载"))
        systemClick(setOf(cfg.getString("sourceFolderName")))
        systemClick(setOf(cfg.getJSONObject("source").getString("name")))
    }
    @Test fun selectedCaseThroughNormalUi() {
        val phase=cfg.getString("phase");require(phase in setOf("complete","overlimit","pause","reopen"))
        if(phase=="reopen") {
            require(cfg.getBoolean("previousProcessEnded")) // P5 independently verifies actual process exit.
            val old=JSONObject(File(folder,"paused.json").readText());val id=old.getString("transferId")
            waitFor {row()!=null}
            main {val r=checkNotNull(row());verifySource(r);require(r.manifest.id==id&&r.paused&&!r.terminal)}
            ui.onNodeWithTag("upload-query-${old.getString("key")}").performClick()
            waitFor {row()?.let {it.terminal||it.paused&&it.error=="已核对原上传；可从确认位置继续。"}==true}
            main {val r=checkNotNull(row());require(r.manifest.id==id);verifySource(r)}
            // Explicit resume only on an authoritative readable, non-verifying original upload.
            var terminal=false;main {val r=checkNotNull(row());terminal=r.terminal
                if(!terminal)require(r.receipt?.phase=="uploading"&&r.pending==null)}
            if(!terminal)ui.onNodeWithTag("upload-resume-${old.getString("key")}").performClick()
        } else {
            main {require(row()==null)}
            if(phase=="overlimit")main {
                val max=requireNotNull(session().computerFiles!!.catalog!!.maxFileBytes)
                require(max==cfg.getLong("expectedMaximumBytes")&&cfg.getJSONObject("source").getLong("sizeBytes")>max)
            }
            selectOriginalThroughPicker()
            if(phase=="overlimit") {
                waitFor {session().computerFiles?.let {!it.busy&&it.error=="本次传输超过电脑支持的文件大小；原文件和原记录已保留。"}==true}
                main {require(row()==null)};shot("overlimit.png");save("result.json",JSONObject().put("overlimitRejected",true));return
            }
            waitFor {row()!=null}
            if(phase=="pause") {
                waitFor {row()?.let {!it.terminal&&it.committed>0&&it.committed<it.manifest.file.sizeBytes}==true}
                var key="";main {val r=checkNotNull(row());verifySource(r);require(!r.terminal);key=r.key}
                ui.onNodeWithTag("upload-pause-$key").performClick()
                waitFor {row()?.let {it.paused&&!it.terminal&&it.committed>0}==true}
                main {val r=checkNotNull(row());save("paused.json",JSONObject().put("transferId",r.manifest.id).put("key",r.key)
                    .put("manifest",r.manifest.json()).put("committedBytes",r.committed))}
                shot("paused.png");return
            }
        }
        waitFor {row()?.terminal==true}
        main {
            val r=checkNotNull(row());verifySource(r);require(r.phase=="completed"&&r.receipt?.phase=="completed")
            save("result.json",JSONObject().put("transferId",r.manifest.id).put("manifest",r.manifest.json())
                .put("committedBytes",r.committed).put("result",r.receipt!!.result))
        }
        shot("completed.png") // Root still independently checks actual desktop name/size/SHA and no model inputs.
    }
}
