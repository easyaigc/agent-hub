package com.agenthub.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Each scenario submits through the real native UI and checks the desktop independently. */
internal class EasyWorkLiveScenarios(
    private val ui: ComposeTestRule,
    private val session: MobileSession,
    private val config: JSONObject,
    private val folder: File,
    private val evidence: ()->JSONObject,
    private val shot: (String)->Unit,
) {
    val passed=JSONArray()
    private var nextOwnerCheck=0L
    private val suffix=UUID.randomUUID().toString().take(8)
    private fun wait(timeout: Long=180_000,condition: ()->Boolean) = ui.waitUntil(timeout) {
        check(session.error==null) { "Native operation failed: ${session.error}" }
        if(System.currentTimeMillis()>=nextOwnerCheck) {
            nextOwnerCheck=System.currentTimeMillis()+5000
            check(evidence().optBoolean("desktopOwnerAlive",true)) { "Owned original Electron exited during real acceptance" }
        }
        condition()
    }
    private fun mark(name: String,details: JSONObject=JSONObject()) {
        passed.put(JSONObject().put("scenario",name).put("outcome","passed").put("proof",details))
        File(folder,"scenarios.json").writeText(passed.toString())
    }
    private fun control(operation: String,threadId: String?=null,bindingId: String?=null) {
        val http=URL("http://127.0.0.1:${config.getInt("observerPort")}/control").openConnection() as HttpURLConnection
        http.requestMethod="POST";http.connectTimeout=3000;http.readTimeout=40_000;http.doOutput=true
        http.setRequestProperty("Content-Type","application/json")
        http.setRequestProperty("x-easywork-acceptance",config.getString("authorization"))
        try {
            http.outputStream.use { out->out.write(JSONObject().put("operation",operation).apply { threadId?.let { put("threadId",it) };bindingId?.let { put("bindingId",it) } }.toString().toByteArray()) }
            val result=JSONObject(http.inputStream.bufferedReader().use { it.readText() })
            assertTrue("Real Desktop control must be acknowledged",result.getBoolean("accepted"))
        } finally { http.disconnect() }
    }
    private fun submit(text: String) {
        wait(30_000) { session.canCompose&&session.transferStatus==null }
        ui.onNodeWithTag("message-input").performTextReplacement(text)
        if(ui.onAllNodesWithTag("composer-dismiss-keyboard").fetchSemanticsNodes().isNotEmpty())ui.onNodeWithTag("composer-dismiss-keyboard").performClick()
        wait(30_000) { ui.onAllNodes(hasTestTag("send-message") and isEnabled()).fetchSemanticsNodes().size==1 }
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        wait(60_000) { session.transferStatus==null&&session.operationRecords.any { it.kind=="reply"&&JSONObject(it.intent).optString("text")==text } }
    }
    private fun completed(marker: String,approveRemainingTools: Boolean=false,initiallyAnswered: Set<String> = emptySet()): MobileMessage {
        val answered=initiallyAnswered.toMutableSet()
        wait(480_000) {
            if(approveRemainingTools) session.runtime?.approvals?.firstOrNull { it.kind!="question"&&it.id !in answered&&session.canAnswer(it) }?.let { request ->
                val button=hasTestTag("approval-approve") and hasAnyAncestor(hasTestTag("approval-${request.id}"))
                if(ui.onAllNodes(button).fetchSemanticsNodes().size==1) {
                    ui.onNode(button).performScrollTo().assertIsEnabled().performClick()
                    answered.add(request.id)
                }
            }
            !session.running&&session.transferStatus==null&&session.messages.any { it.role=="assistant"&&it.text.contains(marker)&&it.status in listOf("complete","completed") }
        }
        val message=session.messages.last { it.role=="assistant"&&it.text.contains(marker) }
        assertFalse(message.runId.isNullOrBlank())
        var run: JSONObject?=null
        wait(30_000) {
            run=evidence().getJSONObject("independentDesktop").getJSONArray("executions").objects().firstOrNull { it.getString("id")==message.runId }
            run?.getJSONObject("run")?.optString("status")=="completed"
        }
        assertTrue("Real model usage required",run!!.getJSONArray("providerUsage").length()>0)
        if(ui.onAllNodesWithTag("composer-dismiss-keyboard").fetchSemanticsNodes().size==1)ui.onNodeWithTag("composer-dismiss-keyboard").performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("message-${message.id}"))
        return message
    }
    private fun newThread() {
        ui.runOnIdle { session.newChat();session.configure(GenerationSettings(model="easyai/GLM-5.2",effort="high",permissionMode="allow_all",collaborationMode="default")) }
    }
    fun resourcesAndProject() {
        assertTrue(session.canCreateProject)
        val name="Android A5 $suffix"
        // Filesystem consent is a real Desktop decision. No project write occurs before it.
        val consent=runBlocking { runCatching { withContext(Dispatchers.Main) { session.createProject(name,".") } } }
        if(consent.isFailure) {
            assertTrue(consent.exceptionOrNull() is AgentOperationNotSubmitted)
            val rejected=session.operationRecords.last { it.kind=="project-create" }
            assertEquals("rejected",rejected.phase)
            assertFalse(evidence().getJSONArray("requests").objects().any { it.getJSONObject("request").getString("method")=="project/create"&&it.getJSONObject("request").getJSONObject("params").optString("clientRequestId")==rejected.id })
            control("desktop.filesystem-approve")
        }
        if(consent.isFailure) {
            ui.onNodeWithTag("open-sidebar").performClick()
            ui.onNodeWithTag("add-project").performScrollTo().performClick()
            ui.onNodeWithTag("project-name").performTextReplacement(name)
            ui.onNodeWithTag("project-name").performImeAction()
            ui.onNodeWithTag("confirm-project").performScrollTo().performClick()
            wait(60_000) { session.projects.any { it.name==name }&&!session.projectBusy }
            ui.onNodeWithTag("close-sidebar").performClick()
        }
        val project=session.projects.single { it.name==name }
        val projectRecord=session.operationRecords.last { it.kind=="project-create"&&it.phase=="confirmed" }
        val projectWrites=evidence().getJSONArray("requests").objects().filter { it.getJSONObject("request").getString("method")=="project/create"&&it.getJSONObject("request").getJSONObject("params").optString("clientRequestId")==projectRecord.id }
        assertEquals(1,projectWrites.size)
        mark("project-create-with-desktop-directory-consent",JSONObject().put("projectId",project.id).put("operationId",projectRecord.id))
        ui.runOnIdle { session.newChat(project.id);session.configure(GenerationSettings(model="easyai/GLM-5.2",effort="high",permissionMode="allow_all",collaborationMode="default")) }
        val imageCode="IMG_${UUID.randomUUID().toString().take(8)}"
        val fileCode="TXT_${UUID.randomUUID().toString().take(8)}"
        val bitmap=Bitmap.createBitmap(600,140,Bitmap.Config.ARGB_8888)
        val png=try {
            Canvas(bitmap).apply { drawColor(Color.WHITE);drawText(imageCode,20f,92f,Paint().apply { color=Color.BLACK;textSize=60f;typeface=Typeface.MONOSPACE;isAntiAlias=true }) }
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
        } finally { bitmap.recycle() }
        val txt="Verification code: $fileCode\n".toByteArray()
        runBlocking { withContext(Dispatchers.Main) { session.addAttachment("image-proof.png","image/png",png);session.addAttachment("file-proof.txt","text/plain",txt) } }
        shot("native-mixed-attachments")
        val prompt="Read both attachments. Visually read the random verification code in the PNG and use a file-reading tool to read the TXT code. Use a filesystem tool to create generated-$suffix.txt containing exactly the two codes, one per line. Reply with both codes and a Markdown link [generated-$suffix.txt](generated-$suffix.txt)."
        assertFalse(prompt.contains(imageCode));assertFalse(prompt.contains(fileCode))
        submit(prompt)
        val result=completed(imageCode)
        assertTrue(result.text.contains(fileCode))
        wait(30_000) { session.messages.flatMap { it.attachments }.any { it.name=="generated-$suffix.txt" } }
        val generated=session.messages.flatMap { it.attachments }.first { it.name=="generated-$suffix.txt" }
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val downloaded=runBlocking { session.downloadResource(generated,File(context.cacheDir,"a5-resource-examiner")) }
        val actual=downloaded.readBytes()
        assertEquals(generated.sha256,easyHash(actual));assertEquals(generated.sizeBytes,actual.size.toLong())
        assertEquals(listOf(imageCode,fileCode),downloaded.readText().trim().lines())
        shot("native-generated-resource")
        mark("mixed-image-file-upload-real-tool-generated-download",JSONObject().put("threadId",session.selectedThread).put("runId",result.runId).put("resourceId",generated.id).put("sha256",generated.sha256).put("codes",JSONArray(listOf(imageCode,fileCode))))
    }
    fun questions() {
        for(multiple in listOf(false,true)) {
            newThread()
            val marker="QUESTION_${if(multiple)"MULTI" else "SINGLE"}_$suffix"
            val rule=if(multiple)"multiple=true, custom=false. Offer Alpha and Beta and accept both selections" else "single choice with Compact and Detailed options"
            submit("Before the final answer, call the question tool with header Delivery and question Choose delivery format. Use $rule. After I answer, reply only $marker.")
            wait(240_000) { session.runtime?.approvals?.any { it.kind=="question" }==true }
            val approval=session.runtime!!.approvals.single { it.kind=="question" }
            val q=approval.questions.single()
            assertEquals(multiple,q.multiple==true)
            val selected=if(multiple)q.options else q.options.take(1)
            selected.forEach { ui.onNodeWithTag("question-option-${q.id}-${it.label}").performScrollTo().performClick() }
            if(multiple) { assertEquals(false,q.custom);ui.onNodeWithTag("question-custom-${q.id}").assertDoesNotExist() }
            shot("native-question-${if(multiple)"multiple" else "single"}")
            ui.onNodeWithTag("answer-question").performScrollTo().assertIsEnabled().performClick()
            val result=completed(marker)
            val writes=evidence().getJSONArray("requests").objects().filter { it.getJSONObject("request").getString("method")=="serverRequest/resolve"&&it.getJSONObject("request").getJSONObject("params").optString("runId")==approval.turnId }
            assertEquals(1,writes.size)
            mark("question-${if(multiple)"multiple-no-custom" else "single"}",JSONObject().put("threadId",session.selectedThread).put("runId",result.runId).put("requestId",writes.single().getJSONObject("request").getString("requestId")))
        }
    }
    fun controls() {
        newThread()
        val origin="ORIGIN_$suffix"
        submit("Use a terminal tool to wait 120 seconds. Then answer the newest user instruction in this conversation. If no newer instruction arrives, reply only $origin. A newer instruction replaces this fallback answer.")
        wait(90_000) { session.running&&session.messages.any { it.kind=="tool"&&it.status=="inProgress" } }
        val thread=session.selectedThread
        val run=session.runtime!!.turnId!!
        shot("native-running-tool")
        ui.runOnIdle { session.setReplyMode("queue") }
        val queued=(1..3).map { "QUEUE_${it}_$suffix" }
        queued.forEach { submit("This is a new independent request. Reply only $it, without tools. Replace any previous requested answer.") }
        wait(30_000) { session.queuedReplies.size==3 }
        val receipts=session.queuedReplies.toList()
        ui.onNodeWithTag("cancel-queued-${receipts[1].operationId}").performScrollTo().performClick()
        wait(30_000) { session.runtime?.receipts?.any { it.operationId==receipts[1].operationId&&it.state=="cancelled" }==true }
        ui.runOnIdle { session.setReplyMode("steer") }
        val steer="STEER_$suffix"
        submit("After the currently running terminal command finishes, replace the original final answer with $steer. Do not start another tool.")
        val steering=session.operationRecords.single { it.kind=="reply"&&JSONObject(it.intent).optString("mode")=="steer" }
        wait(360_000) { !session.running&&session.queuedReplies.isEmpty()&&session.messages.any { it.role=="assistant"&&it.text.trim()==queued[2] } }
        val observed=evidence()
        fun write(id: String)=observed.getJSONArray("requests").objects().filter { val request=it.getJSONObject("request");request.getJSONObject("params").let { p->p.optString("clientRequestId")==id||p.optString("guidanceId")==id } }
        for(receipt in receipts)assertEquals(1,write(receipt.operationId).size)
        val desktopUsers=observed.getJSONObject("independentDesktop").getJSONArray("snapshots").objects().flatMap { it.getJSONArray("messages").objects() }.filter { it.optString("role")=="user" }
        for(marker in listOf(queued[0],queued[2]))assertTrue("Executed queued inputs must be visible in the independent witness",desktopUsers.any { it.toString().contains(marker) })
        val cancelled=desktopUsers.filter { it.toString().contains(queued[1]) }
        assertTrue("Cancelled queued input must never execute",cancelled.isEmpty())
        assertEquals(1,write(steering.id).size);assertEquals(run,write(steering.id).single().getJSONObject("request").getJSONObject("params").getString("turnId"))
        assertTrue(session.messages.any { it.role=="assistant"&&it.runId==run&&it.text.contains(steer) })
        val q1=session.messages.indexOfFirst { it.role=="assistant"&&it.text.trim()==queued[0] }
        val q3=session.messages.indexOfFirst { it.role=="assistant"&&it.text.trim()==queued[2] }
        assertTrue(q1>=0&&q3>q1)
        shot("native-queue-steer-completed")
        mark("fifo-queue-cancel-middle-steer-same-run",JSONObject().put("threadId",thread).put("runId",run).put("steerOperationId",steering.id).put("queueOperationIds",JSONArray(receipts.map { it.operationId })))
        submit("Use a terminal tool to wait 90 seconds, then reply STOP_RESUMED_$suffix.")
        wait(60_000) { session.running }
        val stoppedRun=session.runtime!!.turnId!!
        ui.onNodeWithTag("stop-turn").assertIsEnabled().performClick()
        wait(60_000) { !session.running&&session.transferStatus==null&&session.canRunAgain }
        shot("native-stopped-real-run")
        val stops=evidence().getJSONArray("requests").objects().filter { val r=it.getJSONObject("request");r.getString("method")=="turn/cancel"&&r.getJSONObject("params").optString("turnId")==stoppedRun }
        assertEquals(1,stops.size);assertTrue(stops.single().getJSONObject("response").getJSONObject("result").getBoolean("cancelled"))
        ui.onNodeWithTag("resume-task").assertIsEnabled().performClick()
        val resumed=completed("STOP_RESUMED_$suffix")
        assertNotEquals(stoppedRun,resumed.runId)
        mark("stop-and-explicit-resume-real-model",JSONObject().put("threadId",thread).put("stoppedRunId",stoppedRun).put("resumedRunId",resumed.runId))
    }
    fun plans() {
        newThread()
        ui.runOnIdle { session.setPlanMode(true) }
        val file="plan-$suffix.txt"
        submit("Make a plan to create $file containing PLAN_INITIAL_$suffix using a file tool. Do not implement yet. Present an execution plan.")
        wait(480_000) { !session.running&&session.runtime?.proposedPlan!=null }
        val initial=session.runtime!!.proposedPlan!!
        assertTrue(initial.text.contains(file));shot("native-initial-plan")
        ui.onNodeWithTag("revise-plan").performScrollTo().performClick()
        ui.onNodeWithTag("plan-feedback").performTextReplacement("Revise the plan: the file must contain PLAN_REVISED_$suffix instead. Keep the same filename, do not implement yet.")
        ui.onNodeWithTag("send-plan-feedback").performScrollTo().performClick()
        wait(480_000) { !session.running&&session.runtime?.proposedPlan?.let { it.id!=initial.id&&it.text.contains("PLAN_REVISED_$suffix") }==true }
        val revised=session.runtime!!.proposedPlan!!;shot("native-revised-plan")
        ui.onNodeWithTag("implement-plan").performScrollTo().assertIsEnabled().performClick()
        wait(480_000) { !session.running&&session.transferStatus==null&&session.runtime?.proposedPlan==null&&session.messages.any { it.role=="assistant"&&it.runId!=revised.turnId&&it.text.contains(file) } }
        val planWrites=evidence().getJSONArray("requests").objects().filter { it.getJSONObject("request").getString("method")=="plan/action"&&it.getJSONObject("request").getJSONObject("params").optString("threadId")==session.selectedThread }
        assertEquals(1,planWrites.size)
        assertEquals("approve-current",planWrites.single().getJSONObject("request").getJSONObject("params").getString("action"))
        completed(file)
        shot("native-plan-implemented")
        mark("versioned-plan-revise-and-implement-once",JSONObject().put("threadId",session.selectedThread).put("initialPlanId",initial.id).put("revisedPlanId",revised.id).put("fileName",file).put("expectedLine","PLAN_REVISED_$suffix"))
    }
    fun recovery() {
        val initial=evidence()
        val target=session.selectedClient.scope
        val thread=session.selectedThread
        val beforeRuns=initial.getJSONObject("independentDesktop").getJSONArray("executions").objects().map { it.getString("id") }.toSet()
        val storage=NativeSessionStorage(InstrumentationRegistry.getInstrumentation().targetContext)
        val identity=runBlocking { EasyWorkIdentity.load(storage) }
        val devToken="easyai-ios-simulator-v1."+easyBase64(easyCanonical(JSONObject().put("deviceId",identity.deviceId).put("publicKey",identity.publicKey)).toByteArray())
        if(config.optString("pairingProtocol")!="EWDAP/1")runBlocking { withContext(Dispatchers.Main) {
            session.connectEasyWorkRelay("EasyWork 双通道恢复","http://127.0.0.1:${config.getInt("relayPort")}",initial.getJSONObject("relayOffer").getString("pairingCode"),devToken)
        } }
        assertEquals(1,session.clients.count { it.scope?.agentId=="easywork" })
        assertTrue(sameScope(target,session.selectedClient.scope))
        fun currentWrites()=evidence().getJSONObject("independentDesktop").getJSONArray("executions").objects().map { it.getString("id") }.toSet()
        fun refreshAndVerify(transport: String) {
            val current=evidence();val seen=current.getJSONArray("requests").objects().map { it.getJSONObject("request").getString("requestId") }.toSet()
            // Restart faults are automatic recovery, so exercise the same silent read path
            // as foreground reconnection. User-initiated refresh correctly reports downtime.
            ui.runOnIdle { session.error=null;session.refresh();session.loadHistory() }
            wait(90_000) {
                session.selectedClient.online&&!session.loading&&!session.historyLoading&&evidence().getJSONArray("requests").objects().any {
                    val request=it.getJSONObject("request")
                    it.getString("transportId")==transport&&request.getString("method")=="runtime/context"&&request.getString("requestId") !in seen&&it.getJSONObject("response").optBoolean("ok")
                }
            }
            assertTrue(sameScope(target,session.selectedClient.scope));assertEquals(thread,session.selectedThread)
        }
        ui.runOnIdle { session.setEasyWorkPreference(session.selectedClient,"relay-only") }
        wait(60_000) { session.selectedClient.online&&!session.loading }
        refreshAndVerify("relay-http")
        control("relay.restart")
        refreshAndVerify("relay-http")
        assertEquals(beforeRuns,currentWrites());shot("native-relay-restart-no-input")
        mark("relay-restart-reconnect-no-model-replay",JSONObject().put("threadId",thread))
        val epoch=evidence().getString("hostEpoch")
        control("host.restart")
        wait(120_000) { val current=evidence();current.optString("status")=="ready"&&current.optString("hostEpoch")!=epoch }
        refreshAndVerify("relay-http")
        assertEquals(beforeRuns,currentWrites());shot("native-host-restart-same-target")
        mark("host-restart-same-target-and-history-no-input",JSONObject().put("threadId",thread))
        ui.runOnIdle { session.setEasyWorkPreference(session.selectedClient,"automatic") }
        wait(60_000) { session.selectedClient.online&&!session.loading }
        refreshAndVerify("lan-wss")
        control("lan.disable")
        refreshAndVerify("relay-http")
        assertEquals(beforeRuns,currentWrites());shot("native-automatic-relay-fallback")
        mark("automatic-lan-loss-relay-fallback-no-input",JSONObject().put("threadId",thread))
        control("lan.enable")
        ui.runOnIdle { session.setEasyWorkPreference(session.selectedClient,"lan-only") }
        wait(60_000) { session.selectedClient.online&&!session.loading }
        ui.runOnIdle { session.setEasyWorkPreference(session.selectedClient,"automatic") }
        wait(60_000) { session.selectedClient.online&&!session.loading }
        refreshAndVerify("lan-wss")
        control("lan.switch-next-turn")
        val marker="SWITCH_ONCE_$suffix"
        val filename="switch-$suffix.txt"
        val prompt="Use a terminal tool to append exactly one line $marker to $filename. Do this only once. Reply $marker and the Markdown link [$filename]($filename)."
        submit(prompt)
        val reply=completed(marker)
        val operation=session.operationRecords.single { it.kind=="reply"&&JSONObject(it.intent).optString("text")==prompt }
        val observed=evidence()
        val requests=observed.getJSONArray("requests").objects()
        assertEquals(1,requests.count { val r=it.getJSONObject("request");r.getString("method")=="turn/start"&&r.getJSONObject("params").optString("clientRequestId")==operation.id })
        assertTrue(requests.any { val r=it.getJSONObject("request");it.getString("transportId")=="relay-http"&&r.getString("method")=="command/status"&&r.getJSONObject("params").optString("commandId")==operation.id })
        wait(30_000) { session.messages.flatMap { it.attachments }.any { it.name==filename } }
        val resource=session.messages.flatMap { it.attachments }.first { it.name==filename }
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val downloaded=runBlocking { session.downloadResource(resource,File(context.cacheDir,"a5-recovery-examiner")) }
        assertEquals(listOf(marker),downloaded.readText().trim().lines())
        shot("native-switch-during-send-once")
        mark("lan-response-loss-query-relay-original-operation-once",JSONObject().put("threadId",thread).put("runId",reply.runId).put("operationId",operation.id).put("fileSha256",easyHash(downloaded.readBytes())))
        val draft="RESTORED_DRAFT_$suffix"
        ui.runOnIdle { session.text=draft }
        wait(30_000) { storage.metadata()?.optJSONObject("drafts")?.optString(session.targetKey)==draft }
        File(folder,"restart-input.json").writeText(JSONObject().put("threadId",thread).put("target",CodexCommandCodec.target(target!!)).put("draft",draft).put("operationId",operation.id).put("runs",JSONArray(currentWrites().sorted())).toString())
    }
    fun receive() {
        val thread=session.selectedThread
        val messages=session.messages.map { it.id to it.text }
        val runs=evidence().getJSONObject("independentDesktop").getJSONArray("executions").objects().map { it.getString("id") }.toSet()
        repeat(2) {
            ui.runOnIdle { session.loadHistory(userInitiated=true) }
            wait(60_000) { !session.historyLoading }
            assertEquals(messages,session.messages.map { it.id to it.text })
        }
        assertEquals(runs,evidence().getJSONObject("independentDesktop").getJSONArray("executions").objects().map { it.getString("id") }.toSet())
        assertEquals(session.messages.size,session.messages.map { it.id }.toSet().size)
        shot("native-real-delta-refresh-retains-messages")
        mark("real-delta-refresh-preserves-complete-history-no-model-replay",JSONObject().put("threadId",thread).put("messageCount",messages.size).put("explicitAdditionalModelInputs",0))
    }
    fun history() {
        newThread()
        val first="HISTORY_${suffix}_1"
        val operationIds=mutableListOf<String>()
        for(index in 1..26) {
            val marker="HISTORY_${suffix}_$index"
            val prompt="Only reply $marker. Do not use tools or modify files."
            submit(prompt)
            completed(marker)
            operationIds.add(session.operationRecords.single { it.kind=="reply"&&JSONObject(it.intent).optString("text")==prompt }.id)
        }
        verifyPagedHistory(first,operationIds)
    }
    fun historyRead() {
        val thread=config.getString("historyThreadId")
        val first=config.getString("historyFirstMarker")
        ui.runOnIdle { session.selectThread(session.threads.single { it.id==thread&&it.hostId==session.selectedClient.id }) }
        wait(60_000) { !session.historyLoading }
        verifyPagedHistory(first,emptyList())
    }
    private fun verifyPagedHistory(first: String,operationIds: List<String>) {
        wait(30_000) { session.historyCursor!=null }
        assertFalse(session.messages.any { it.role=="assistant"&&it.text.trim()==first })
        repeat(5) {
            if(session.historyCursor!=null) {
                ui.onNodeWithTag("message-list").performScrollToIndex(0)
                val tag=if(session.timeline.hasEarlierMessages)"history-local-more" else "history-more"
                ui.onNodeWithTag(tag).assertIsEnabled().performClick()
                wait(60_000) { !session.historyLoading }
            }
        }
        assertNull(session.historyCursor)
        assertTrue(session.messages.any { it.role=="assistant"&&it.text.trim()==first })
        val ids=session.messages.map { it.id }
        assertEquals(ids.size,ids.toSet().size)
        ui.onNodeWithTag("message-list").performScrollToNode(hasText(first))
        shot("native-earlier-real-history")
        val before=evidence().getJSONObject("independentDesktop").getJSONArray("executions").objects().map { it.getString("id") }.toSet()
        ui.runOnIdle { session.loadHistory(userInitiated=true) }
        wait(60_000) { !session.historyLoading }
        assertNull(session.historyCursor)
        assertTrue(session.messages.any { it.role=="assistant"&&it.text.trim()==first })
        // Wait for the next authoritative event snapshot, which also carries only the latest window.
        val syncs=evidence().getJSONArray("requests").objects().filter { it.getJSONObject("request").getString("method")=="thread/sync" }.map { it.getJSONObject("request").getString("requestId") }.toSet()
        wait(45_000) { evidence().getJSONArray("requests").objects().any { val r=it.getJSONObject("request");r.getString("method")=="thread/sync"&&r.getString("requestId") !in syncs } }
        assertNull(session.historyCursor)
        assertTrue(session.messages.any { it.role=="assistant"&&it.text.trim()==first })
        assertEquals(before,evidence().getJSONObject("independentDesktop").getJSONArray("executions").objects().map { it.getString("id") }.toSet())
        ui.onNodeWithTag("message-list").performScrollToNode(hasText(first))
        shot("native-earlier-retained-after-live-refresh")
        val requests=evidence().getJSONArray("requests").objects()
        for(id in operationIds)assertEquals(1,requests.count { val request=it.getJSONObject("request");request.getString("method")=="turn/start"&&request.getJSONObject("params").optString("clientRequestId")==id })
        mark("real-history-earlier-paging-retained-after-refresh-and-events",JSONObject().put("threadId",session.selectedThread).put("firstMarker",first).put("explicitModelInputs",operationIds.size).put("messagesAfterPaging",ids.size).put("noModelReplay",true))
    }
    fun observability() {
        control("desktop.observability-policy-enable")
        try {
            newThread()
            val operationIds=mutableListOf<String>()
            for(index in 1..3) {
                val marker="COMPRESSION_${suffix}_$index"
                val prompt="使用 terminal_exec 执行 python3 -c \"print('native-compression-memory ' * 6000)\"，读取真实工具输出后只回复 $marker"
                submit(prompt);completed(marker)
                operationIds.add(session.operationRecords.single { it.kind=="reply"&&JSONObject(it.intent).optString("text")==prompt }.id)
            }
            verifyCompaction(session.selectedThread,3)
            val requests=evidence().getJSONArray("requests").objects()
            for(id in operationIds)assertEquals(1,requests.count { val r=it.getJSONObject("request");r.optString("method")=="turn/start"&&r.getJSONObject("params").optString("clientRequestId")==id })
        } finally { control("desktop.observability-policy-restore") }
        mark("real-runtime-compaction-three-inputs-policy-restored",JSONObject().put("threadId",session.selectedThread).put("explicitModelInputs",3))
    }
    fun observabilityRead() {
        val thread=config.getString("observabilityThreadId")
        ui.runOnIdle { session.selectThread(session.threads.single { it.id==thread&&it.hostId==session.selectedClient.id }) }
        wait(60_000) { !session.historyLoading }
        val runs=evidence().getJSONObject("independentDesktop").getJSONArray("executions").objects().map { it.getString("id") }.toSet()
        verifyCompaction(thread,3)
        assertEquals(runs,evidence().getJSONObject("independentDesktop").getJSONArray("executions").objects().map { it.getString("id") }.toSet())
        mark("real-compaction-history-read-no-model-input",JSONObject().put("threadId",thread).put("explicitModelInputs",0))
    }
    private fun verifyCompaction(thread: String,count: Int) {
        wait(60_000) { session.messages.any { it.kind=="contextCompaction"&&it.status=="completed" } }
        val message=session.messages.first { it.kind=="contextCompaction"&&it.status=="completed" }
        ui.onNodeWithTag("message-list").performScrollToNode(hasText("已压缩上下文"))
        ui.onAllNodesWithText("已压缩上下文").onFirst().assertIsDisplayed()
        val desktop=evidence().getJSONObject("independentDesktop")
        val runs=desktop.getJSONArray("executions").objects().filter { it.getJSONObject("run").optString("threadId")==thread }
        assertEquals(count,runs.size);assertTrue(runs.all { it.getJSONObject("run").optString("status")=="completed"&&it.getJSONArray("providerUsage").length()>0 })
        // The bounded phone witness omits diagnostic message bodies. The runner
        // independently verifies canonical compression projections on the host.
        assertFalse(message.id.isBlank());shot("native-real-context-compression")
    }
    fun approvals() {
        val permissionSuffix=config.getString("permissionSuffix")
        val permissionFileHash=config.getString("permissionFileHash")
        require(permissionSuffix.matches(Regex("[a-f0-9]{8}")))
        require(permissionFileHash.matches(Regex("[a-f0-9]{16}")))
        for(action in listOf("approve","deny","race")) {
            newThread()
            ui.onNodeWithTag("permission-settings").performClick()
            ui.onNodeWithTag("permission-confirm_every_operation").performScrollTo().performClick()
            ui.onNodeWithTag("sheet-done").performClick()
            assertEquals("confirm_every_operation",session.selectedPermission)
            control("permissions.confirm-next-turn")
            val marker="PERMISSION_${action}_$permissionSuffix"
            val file="permission-${action}-$permissionSuffix.txt"
            submit("The existing file $file is empty; the actual read_file CLI independently reports file_hash:$permissionFileHash. Use terminal_exec to run apply_patch append --path '$file' --file-hash $permissionFileHash with stdin exactly '$marker' followed by a newline, once. Do not create or overwrite the file. Do not bypass tool permission confirmation. If denied, stop without using alternative tools. If approved, reply only $marker.")
            wait(420_000) { session.runtime?.approvals?.any { it.kind!="question" }==true }
            val approval=session.runtime!!.approvals.single { it.kind!="question" }
            shot("native-tool-permission-$action")
            if(action=="race")control("desktop.approval-race-next-response")
            ui.onNodeWithTag("approval-${if(action=="deny")"deny" else "approve"}").performScrollTo().assertIsEnabled().performClick()
            val reply=if(action=="deny") {
                wait(180_000) { !session.running&&session.transferStatus==null&&session.runtime?.approvals?.isEmpty()==true }
                null
            } else completed(marker,approveRemainingTools=true,initiallyAnswered=setOf(approval.id))
            val desktop=evidence()
            val replies=desktop.getJSONArray("requests").objects().filter {
                val r=it.getJSONObject("request");r.getString("method")=="approval/respond"&&r.getJSONObject("params").optString("serverRequestId")==approval.id
            }
            assertEquals(1,replies.size)
            val allReplies=desktop.getJSONArray("requests").objects().filter { val r=it.getJSONObject("request");r.getString("method")=="approval/respond"&&r.getJSONObject("params").optString("threadId")==session.selectedThread }
            assertEquals(allReplies.size,allReplies.map { it.getJSONObject("request").getJSONObject("params").getString("serverRequestId") }.distinct().size)
            val result=replies.single().getJSONObject("response").getJSONObject("result")
            assertTrue(result.optBoolean("accepted")||result.optBoolean("denied"))
            var settledExecution: JSONObject?=null
            wait(120_000) {
                settledExecution=evidence().getJSONObject("independentDesktop").getJSONArray("executions").objects().single { it.getString("id")==approval.turnId }
                settledExecution!!.getJSONObject("run").optString("status") in setOf("completed","cancelled","failed")&&settledExecution!!.getJSONArray("providerUsage").length()>0
            }
            assertTrue(settledExecution!!.getJSONArray("providerUsage").length()>0)
            mark("tool-permission-$action-once",JSONObject().put("threadId",session.selectedThread).put("runId",reply?.runId?:approval.turnId).put("approvalId",approval.id).put("canonicalToolApprovals",allReplies.size).put("fileName",file).put("expectedLine",marker).put("expectedLineCount",if(action=="deny")0 else 1))
        }
    }
    fun deviceSecurity() {
        val storage=NativeSessionStorage(InstrumentationRegistry.getInstrumentation().targetContext)
        val client=session.selectedClient;val connection=client.agentConnection!!
        val credential=EasyWorkCredential.decode(JSONObject(storage.read("easywork-credential:${connection.credentialRef}")!!))
        val ref=credential.grantRef?:error("Actual scoped grant required")
        val identity=runBlocking { EasyWorkIdentity.load(storage) };val signer=EasyWorkDPoP.load(identity)
        val saved=JSONObject(storage.read(ref)!!);val origin=saved.getString("origin")
        val authorization=EasyWorkDeviceAuthorization(storage)
        val listUrl="$origin/v2/remote-access/bindings"
        fun headers(method: String,url: String,body: ByteArray?=null)=runBlocking { authorization.headers(ref,credential.binding,method,url,body) }
        fun http(method: String,url: String,proof: Map<String,String>,body: ByteArray?=null): Pair<Int,JSONObject> {
            val request=URL(url).openConnection() as HttpURLConnection
            request.requestMethod=method;request.connectTimeout=5000;request.readTimeout=15000;request.instanceFollowRedirects=false
            proof.forEach { (key,value)->request.setRequestProperty(key,value) }
            if(body!=null) { request.doOutput=true;request.setRequestProperty("Content-Type","application/json");request.outputStream.use { it.write(body) } }
            return try { val status=request.responseCode;val stream=if(status in 200..299)request.inputStream else request.errorStream
                status to (stream?.bufferedReader()?.use { JSONObject(it.readText()) }?:JSONObject())
            } finally { request.disconnect() }
        }
        val repeated=headers("GET",listUrl)
        val first=http("GET",listUrl,repeated);assertEquals(200,first.first)
        assertEquals(listOf(credential.binding.id),first.second.getJSONArray("bindings").objects().map { it.getString("bindingId") })
        assertEquals("A used DPoP jti must not authorize a second request",401,http("GET",listUrl,repeated).first)
        val otherSigner=EasyWorkDPoP.load(identity.copy(deviceId="acceptance-other-key-$suffix"))
        val token=repeated.getValue("Authorization").removePrefix("DPoP ")
        assertEquals(401,http("GET",listUrl,mapOf("Authorization" to "DPoP $token","DPoP" to otherSigner.proof("GET",listUrl,token))).first)
        val refreshUrl="$origin/v2/remote-access/device-grants/refresh"
        val grant=EasyWorkDeviceGrant(JSONObject(storage.read(ref)!!).getJSONObject("grant").toString())
        val intended=easyCanonical(JSONObject().put("operationId",UUID.randomUUID().toString())).toByteArray()
        val changed=easyCanonical(JSONObject().put("operationId",UUID.randomUUID().toString())).toByteArray()
        val tampered=http("POST",refreshUrl,mapOf("Authorization" to "DPoP ${grant.refreshToken}","DPoP" to signer.proof("POST",refreshUrl,grant.refreshToken,body=intended)),changed)
        assertEquals(401,tampered.first)
        val foreign=config.getString("foreignBindingId");assertNotEquals(credential.binding.id,foreign)
        val direct=connection.candidates.first { it.transportId=="lan-wss"&&java.net.URI(it.endpoint).host in setOf("127.0.0.1","localhost") }
        val pin=direct.peerFingerprint!!
        val badPin=(if(pin.first()=='0')"1" else "0")+pin.drop(1)
        val wrongCertificate=runBlocking {
            val http=EasyWorkHttp.client(direct.endpoint,badPin)
            try { runCatching { EasyWorkHttp.request(http,CodexEndpoints.local(direct.endpoint)+"/healthz") }.exceptionOrNull() }
            finally { EasyWorkHttp.dispose(http) }
        }
        assertNotNull("Changed paired certificate must fail before application traffic",wrongCertificate)
        assertTrue(generateSequence(wrongCertificate) { it.cause }.any { it is java.security.cert.CertificateException })
        val socketUrl=CodexEndpoints.local(direct.endpoint)+"/channel?bindingId="+foreign
        val refused=CompletableDeferred<Int>()
        val socketClient=EasyWorkHttp.client(direct.endpoint,pin)
        try {
            val request=okhttp3.Request.Builder().url(socketUrl).apply { headers("GET",socketUrl).forEach { (key,value)->header(key,value) } }.build()
            val socket=socketClient.newWebSocket(request,object: okhttp3.WebSocketListener() {
                override fun onMessage(webSocket: okhttp3.WebSocket,text: String) { refused.complete(-1);webSocket.close(1000,"unexpected authorization") }
                override fun onClosing(webSocket: okhttp3.WebSocket,code: Int,reason: String) { refused.complete(code);webSocket.close(code,null) }
                override fun onFailure(webSocket: okhttp3.WebSocket,t: Throwable,response: okhttp3.Response?) { refused.complete(response?.code?:0) }
            })
            val status=runBlocking { withTimeout(20_000) { refused.await() } };socket.cancel()
            assertEquals("Another scoped binding must close before encrypted runtime traffic",4003,status)
            mark("actual-lan-changed-certificate-and-other-binding-refused",JSONObject().put("changedCertificateRejected",true).put("foreignBindingUpgradeStatus",status))
        } finally { EasyWorkHttp.dispose(socketClient) }
        val envelope=JSONObject(String(EasyWorkCrypto(credential,credential.binding).seal(JSONObject().put("kind","key-confirmation").put("value","not-a-runtime-command"),1),Charsets.UTF_8)).put("bindingId",foreign)
        val body=easyCanonical(envelope).toByteArray();val envelopesUrl="$origin/v2/remote-access/relay/envelopes"
        assertEquals(403,http("POST",envelopesUrl,headers("POST",envelopesUrl,body),body).first)
        mark("device-proof-replay-key-body-and-binding-isolation",JSONObject().put("usedProofStatus",401).put("wrongKeyStatus",401).put("tamperedBodyStatus",401).put("otherActiveBindingStatus",403))
        val runs=evidence().getJSONObject("independentDesktop").getJSONArray("executions").objects().map { it.getString("id") }.toSet()
        val revokeHeaders=headers("GET",listUrl)
        control("desktop.device-binding-revoke",bindingId=credential.binding.id)
        val revoked=http("GET",listUrl,revokeHeaders);assertEquals(403,revoked.first)
        assertEquals("grant_inactive",revoked.second.getJSONObject("error").getString("code"))
        ui.runOnIdle { session.refresh(userInitiated=true) }
        ui.waitUntil(60_000) { !session.selectedClient.online&&!session.loading }
        assertFalse(session.canCompose);assertTrue("Native refusal must distinguish revocation from network reconnection",session.error?.contains("撤销设备授权")==true);shot("native-device-authorization-revoked")
        mark("desktop-revokes-exact-grant-native-composer-disabled",JSONObject().put("bindingId",credential.binding.id).put("accessStatus",403))
        fun step(name: String) { File(folder,"rejection-checkpoints.txt").appendText(name+"\n") }
        step("before-remove-client")
        ui.runOnIdle { session.removeClient(client.id) }
        step("removed-client")
        control("desktop.device-offer-create")
        step("desktop-offer-created")
        val offer=evidence().getJSONObject("deviceOffer").getString("deepLink")
        step("before-open-form")
        ui.onNodeWithTag("onboarding-add").performClick();ui.onNodeWithTag("agent-provider-easywork").performClick()
        step("form-opened")
        ui.onNodeWithTag("easywork-connection-method").performClick();ui.onNodeWithTag("easywork-method-扫码连接").performClick()
        step("scan-mode-selected")
        ui.onNodeWithTag("easywork-qr-description").performTextReplacement(offer);ui.onNodeWithTag("easywork-qr-description").performImeAction()
        ui.onNodeWithTag("easywork-pair-lan").performScrollTo().performClick()
        step("anonymous-claim-started")
        ui.waitUntil(60_000) { ui.onAllNodesWithTag("easywork-connecting").fetchSemanticsNodes().any { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.any { t->t.text.contains("确认此设备") } } }
        step("pending-visible")
        control("desktop.device-claim-reject")
        step("desktop-rejected")
        ui.waitUntil(60_000) { ui.onAllNodesWithTag("easywork-pair-error").fetchSemanticsNodes().any { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.any { t->t.text.contains("拒绝") } } }
        step("native-rejection-observed")
        assertFalse(session.hasClient);ui.onNodeWithTag("easywork-pair-lan").assertIsEnabled();shot("native-device-authorization-rejected")
        ui.onNodeWithTag("easywork-pair-error").assertIsDisplayed()
        assertEquals(runs,evidence().getJSONObject("independentDesktop").getJSONArray("executions").objects().map { it.getString("id") }.toSet())
        mark("desktop-rejects-native-claim-no-binding-no-extra-model-input")
    }
    fun deviceGrants() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val storage=NativeSessionStorage(context)
        val client=session.selectedClient;val connection=client.agentConnection!!
        val credential=EasyWorkCredential.decode(JSONObject(storage.read("easywork-credential:${connection.credentialRef}")!!))
        val ref=credential.grantRef?:error("A real EWDAP grant is required")
        val identity=runBlocking { EasyWorkIdentity.load(storage) };val signer=EasyWorkDPoP.load(identity)
        var grant=EasyWorkDeviceGrant(JSONObject(storage.read(ref)!!).getJSONObject("grant").toString()).verify(identity,signer,credential.binding)
        assertNull("Phone has no account bearer token",credential.accessToken)
        assertTrue("Encrypted runtime/context must provide authenticated direct candidates",connection.candidates.any { it.transportId=="lan-wss"&&it.peerFingerprint!=null })
        val thread=session.selectedThread
        ui.runOnIdle { session.setEasyWorkPreference(client,"lan-only") }
        wait(60_000) { session.selectedClient.online&&!session.loading&&session.canCompose }
        val marker="DEVICE_LAN_$suffix";val prompt="Only reply $marker. Do not call tools or modify files."
        submit(prompt);val reply=completed(marker)
        val operation=session.operationRecords.single { it.kind=="reply"&&JSONObject(it.intent).optString("text")==prompt }
        val requests=evidence().getJSONArray("requests").objects().filter { val r=it.getJSONObject("request");r.getString("method")=="turn/start"&&r.getJSONObject("params").optString("clientRequestId")==operation.id }
        assertEquals(1,requests.size);assertEquals("lan-wss",requests.single().getString("transportId"));assertEquals(thread,session.selectedThread)
        shot("native-device-grant-direct-reply")
        mark("scoped-device-grant-lan-and-relay-same-thread",JSONObject().put("threadId",thread).put("runId",reply.runId).put("operationId",operation.id).put("phoneAccountLoginRequired",false))
        ui.runOnIdle { session.setEasyWorkPreference(session.selectedClient,"relay-only") }
        wait(60_000) { session.selectedClient.online&&!session.loading&&session.canCompose }
        grant=EasyWorkDeviceGrant(JSONObject(storage.read(ref)!!).getJSONObject("grant").toString()).verify(identity,signer,credential.binding)
        val draft="DEVICE_RESTORED_DRAFT_$suffix";ui.runOnIdle { session.text=draft }
        wait(30_000) { storage.metadata()?.optJSONObject("drafts")?.optString(session.targetKey)==draft }
        val runs=evidence().getJSONObject("independentDesktop").getJSONArray("executions").objects().map { it.getString("id") }.toSet()
        File(folder,"restart-input.json").writeText(JSONObject().put("threadId",thread).put("target",CodexCommandCodec.target(client.scope!!)).put("draft",draft).put("operationId",operation.id).put("runs",JSONArray(runs.sorted())).put("grantRef",ref).put("credentialRef",connection.credentialRef).put("accessExpiresAt",grant.json.getString("accessExpiresAt")).toString())
    }
}
