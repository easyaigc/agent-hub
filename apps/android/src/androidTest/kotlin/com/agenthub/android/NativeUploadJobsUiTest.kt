package com.agenthub.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agenthub.core.RuntimeIdentity
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Production cards/center/codec, synthetic port and bytes only. The rule's empty Activity
 * never creates MainActivity, MobileSession, storage, keys or a real upload. */
@RunWith(AndroidJUnit4::class)
class NativeUploadJobsUiTest {
    @get:Rule(order=0) val timeout=org.junit.rules.Timeout.seconds(90)
    @get:Rule(order=1) val ui=createComposeRule()
    private lateinit var f: Fixture
    private var visible by mutableStateOf(true)
    private var rendered=false

    private class Journal : NativeUploadJournal {
        @Volatile var value:String?=null
        override fun read():String? = value
        override fun write(value:String) { this.value=value }
        override fun requireNetworkOwner() {} // Only this in-memory fixture, never a storage owner.
        fun rows()=NativeUploadJournalCodec.decode(checkNotNull(value))
    }
    private data class Call(val action:NativeUploadAction,val offset:Long,
        val manifest:NativeUploadManifest,val key:String,val sourceId:String?)
    private class Fixture {
        val bytes="ABCDEF".toByteArray()
        val original=bytes.copyOf()
        val client=MobileClient("upload-ui-host","Synthetic upload UI",
            TargetScope("codex",null,null,"upload-ui-host","ui-workspace","desktop-runtime"),
            "https://synthetic.invalid",isDemo=false)
        val target=NativeUploadTarget.capture(client)
        val journal=Journal()
        val job=SupervisorJob()
        val scope=CoroutineScope(job+Dispatchers.Main.immediate)
        val cap=NativeUploadCapability(2,false,"codex-upload-v1")
        val calls=CopyOnWriteArrayList<Call>()
        var expected:NativeUploadManifest?=null
        var committed=0L
        var remotePhase="uploading"
        var blockFirstChunk=false
        val chunkEntered=CompletableDeferred<Unit>()
        val chunkCancelled=CompletableDeferred<Unit>()
        var sourceReads=0

        fun row(name:String,purpose:String="file-transfer",owner:NativeUploadTarget=target,
            batch:String?=null,index:Int=0,count:Int=1):NativeUploadRow {
            val manifest=NativeUploadManifest(ComputerFileManifest(client.id,UUID.randomUUID().toString(),
                name,"application/octet-stream",bytes.size.toLong(),ComputerFileCodec.hash(bytes)),
                purpose,threadId=if(purpose=="attachment")"synthetic-thread" else null)
            return NativeUploadRow(NativeUploadRow.key(owner,manifest),owner,manifest,"source-$name",
                phase="uploading",capability=cap,batchId=batch,index=index,count=count,
                batchBytes=if(batch!=null)18L else null)
        }
        fun receipt(manifest:NativeUploadManifest,phase:String=remotePhase,n:Long=committed):NativeUploadReceipt {
            val result=if(phase=="completed")ComputerFileReceipt(manifest.file,"completed",
                "synthetic-destination","2026-01-01T00:00:00Z").json() else JSONObject.NULL
            return NativeUploadReceipt.decode(JSONObject().put("version",1).put("manifest",manifest.json())
                .put("phase",phase).put("committedBytes",n).put("maxChunkBytes",2)
                .put("createdAt","2026-01-01T00:00:00Z").put("updatedAt","2026-01-01T00:00:00Z")
                .put("publicationStarted",phase=="verifying").put("result",result),manifest)
        }
        val port=object:AgentCommands by NativeCodexCommands({error("No credential or unrelated command")}) {
            override suspend fun uploadCapability(client:MobileClient,manifest:NativeUploadManifest):NativeUploadCapability {
                assertEquals(this@Fixture.client,client);assertEquals(expected,manifest);return cap
            }
            override suspend fun uploadRequest(client:MobileClient,manifest:NativeUploadManifest,
                action:NativeUploadAction,offset:Long,bytes:ByteArray?):NativeUploadReceipt {
                assertEquals(this@Fixture.client,client);assertEquals(expected,manifest)
                val saved=journal.rows().single {it.manifest.id==manifest.id}
                assertEquals(manifest,saved.manifest)
                assertTrue(RuntimeIdentity.matches(target.scope,saved.target.scope))
                assertEquals(target.clientId,saved.target.clientId);assertEquals(target.certificate,saved.target.certificate)
                if(action!=NativeUploadAction.STATUS)assertEquals(action.name.lowercase(),saved.pending)
                calls.add(Call(action,offset,manifest,saved.key,saved.sourceFileId))
                when(action) {
                    NativeUploadAction.CHUNK -> {
                        assertEquals(committed,offset)
                        assertArrayEquals(original.copyOfRange(offset.toInt(),offset.toInt()+checkNotNull(bytes).size),bytes)
                        if(blockFirstChunk) {
                            blockFirstChunk=false;chunkEntered.complete(Unit)
                            try { awaitCancellation() } finally { chunkCancelled.complete(Unit) }
                        }
                        committed+=checkNotNull(bytes).size
                    }
                    NativeUploadAction.COMPLETE -> {
                        remotePhase="verifying";throw IOException("Synthetic lost publication response")
                    }
                    NativeUploadAction.CANCEL -> error("remoteCancel=false must never dispatch CANCEL")
                    else -> Unit
                }
                return receipt(manifest)
            }
        }
        val center=NativeUploadCenter(journal,scope,{if(it.matches(client))client else null},{{true}},
            {_,row ->
                assertEquals(expected,row.manifest);sourceReads++
                val reader:(Long,Int)->ByteArray={offset,n->bytes.copyOfRange(offset.toInt(),offset.toInt()+n)}
                reader
            },{port})
    }

    @Before fun setup():Unit { f=Fixture();visible=true }
    @After fun finish():Unit {
        try {
            if(rendered) {ui.runOnIdle {visible=false};ui.waitForIdle()}
        } finally {
            if(::f.isInitialized) {
                runBlocking {withTimeout(5_000) {f.job.cancelAndJoin()}}
                assertFalse(f.job.isActive);assertTrue(f.job.children.none())
                assertArrayEquals(f.original,f.bytes)
            }
        }
    }
    private fun main(block:suspend ()->Unit):Unit {
        runBlocking {withTimeout(5_000) {withContext(Dispatchers.Main.immediate) {block()}}}
    }
    private fun restore(rows:List<NativeUploadRow>):Unit {
        f.journal.value=NativeUploadJournalCodec.encode(rows);main {f.center.restore()}
    }
    private fun render():Unit {
        rendered=true
        ui.setContent {if(visible)ProductTextProvider("zh-Hans") {
            MaterialTheme {NativeUploadJobs(f.center,f.client,setOf("file-transfer"))}
        }}
    }
    private fun until(check:()->Boolean):Unit {ui.waitUntil(5_000) {ui.runOnIdle(check)}}
    private fun click(action:String,row:NativeUploadRow):Unit {
        ui.onNodeWithTag("upload-$action-${row.key}").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
    }
    private fun assertOriginal(row:NativeUploadRow):Unit {
        val saved=f.journal.rows().single {it.key==row.key}
        assertEquals(row.manifest,saved.manifest)
        assertTrue(RuntimeIdentity.matches(row.target.scope,saved.target.scope))
        assertEquals(row.target.clientId,saved.target.clientId);assertEquals(row.target.certificate,saved.target.certificate)
        assertEquals(row.sourceFileId,saved.sourceFileId)
        assertEquals(row.manifest.file.sha256,ComputerFileCodec.hash(f.bytes))
    }

    @Test fun filteredPartialBatchKeepsOriginalBCIndicesWithoutOverallPercentage():Unit {
        val batch=UUID.randomUUID().toString()
        val b=f.row("B.bin",batch=batch,index=1,count=3)
        val c=f.row("C.bin",batch=batch,index=2,count=3)
        val otherScope=TargetScope("codex",null,null,f.client.id,"ui-workspace","other-runtime")
        val foreign=f.row("foreign.bin",owner=f.target.copy(scope=otherScope))
        val otherPin=f.row("other-pin.bin",owner=f.target.copy(certificate="synthetic-other-certificate"))
        val attachment=f.row("attachment.bin",purpose="attachment")
        restore(listOf(b,c,foreign,otherPin,attachment));val originalJournal=f.journal.value;render()
        ui.onNodeWithTag("upload-batch-$batch").assertTextEquals("文件 3/3")
        ui.onNodeWithText("其他文件的进度暂不可用。").assertExists()
        for(row in listOf(b,c)) {
            ui.onNodeWithText(row.manifest.file.name).performScrollTo().assertIsDisplayed()
            ui.onNodeWithTag("upload-job-${row.key}").assertExists();assertOriginal(row)
        }
        for(row in listOf(foreign,otherPin,attachment))ui.onNodeWithTag("upload-job-${row.key}").assertDoesNotExist()
        // Only two per-file indicators: a third one would fabricate a complete batch denominator.
        ui.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
            useUnmergedTree=true).assertCountEquals(2)
        ui.runOnIdle {
            assertEquals(listOf(1,2),listOf(b,c).map {f.center.rows.getValue(it.key).index})
            assertTrue(listOf(b,c).all {f.center.rows.getValue(it.key).count==3})
        }
        assertEquals(originalJournal,f.journal.value);assertTrue(f.calls.isEmpty())
    }

    @Test fun buttonsPauseQueryResumeAndStopTheSameDurableUnknownUpload():Unit {
        val row=f.row("original.bin");f.expected=row.manifest;f.blockFirstChunk=true
        render();lateinit var result:Deferred<JSONObject>
        ui.runOnIdle {result=f.scope.async {
            f.center.transfer(f.client,row.manifest,row.sourceFileId,
                {offset,n->f.bytes.copyOfRange(offset.toInt(),offset.toInt()+n)},f.port) {error("No legacy dispatch")}
        }}
        until {f.chunkEntered.isCompleted}
        click("pause",row);until {f.chunkCancelled.isCompleted&&f.center.rows[row.key]?.paused==true}
        assertOriginal(row);assertTrue(f.journal.rows().single().paused)
        val pauseCalls=f.calls.size
        click("query",row);until {f.calls.size==pauseCalls+1&&f.center.rows[row.key]?.paused==true}
        assertEquals(NativeUploadAction.STATUS,f.calls.last().action)
        click("resume",row);until {f.center.rows[row.key]?.phase=="waiting"}
        assertOriginal(row);assertEquals("complete",f.journal.rows().single().pending)
        assertEquals(1,f.calls.count {it.action==NativeUploadAction.COMPLETE})
        val beforeQuery=f.calls.size
        click("query",row);until {f.center.rows[row.key]?.phase=="unknown"}
        assertEquals(listOf(NativeUploadAction.STATUS),f.calls.drop(beforeQuery).map {it.action})
        ui.onNodeWithTag("upload-dismiss-${row.key}").assertDoesNotExist()
        click("stop",row);until {result.isCompleted&&f.center.rows[row.key]?.stop==true}
        val saved=f.journal.rows().single();assertTrue(saved.stop);assertTrue(saved.paused)
        assertEquals("complete",saved.pending);assertOriginal(row)
        assertEquals(listOf("PREPARE:0","CHUNK:0","STATUS:0","STATUS:0","CHUNK:0",
            "STATUS:2","CHUNK:2","STATUS:4","CHUNK:4","STATUS:6","COMPLETE:6","STATUS:6"),
            f.calls.map {"${it.action}:${it.offset}"})
        assertTrue(f.calls.all {it.manifest==row.manifest&&it.key==row.key&&it.sourceId==row.sourceFileId})
        assertTrue(result.isCancelled);assertEquals(0,f.sourceReads)
    }

    @Test fun terminalDismissDoesNotRemoveUnknownOrOfferUnsupportedRemoteCancel():Unit {
        val unknown=f.row("unknown.bin").copy(phase="unknown",pending="complete")
        val ended=f.row("completed.bin").let {it.copy(phase="completed",
            receiptWire=f.receipt(it.manifest,"completed",f.bytes.size.toLong()).wire)}
        f.expected=unknown.manifest;f.committed=f.bytes.size.toLong();f.remotePhase="verifying"
        restore(listOf(unknown,ended));render();click("query",unknown)
        until {f.center.rows[unknown.key]?.phase=="unknown"}
        ui.onNodeWithTag("upload-phase-${unknown.key}").assertTextEquals("原上传结果待核对")
        ui.onNodeWithTag("upload-dismiss-${unknown.key}").assertDoesNotExist()
        ui.onNodeWithTag("upload-cancel-${unknown.key}").assertDoesNotExist()
        ui.onNodeWithText("取消电脑上传").assertDoesNotExist()
        click("dismiss",ended);until {!f.center.rows.containsKey(ended.key)}
        ui.onNodeWithTag("upload-job-${ended.key}").assertDoesNotExist()
        ui.onNodeWithTag("upload-job-${unknown.key}").assertExists()
        assertEquals(listOf(unknown.key),f.journal.rows().map {it.key});assertOriginal(unknown)
        assertEquals("complete",f.journal.rows().single().pending)
        assertEquals(listOf(NativeUploadAction.STATUS),f.calls.map {it.action})
        assertTrue(f.calls.all {it.manifest==unknown.manifest&&it.sourceId==unknown.sourceFileId})
        assertEquals(0,f.sourceReads)
    }
}
