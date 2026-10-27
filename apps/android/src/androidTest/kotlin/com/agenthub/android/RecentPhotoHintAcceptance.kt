package com.agenthub.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic photo adapter only; original reads still use a real owned FileProvider. */
internal class HintPhotoSource(private val activity: MainActivity): NativeRecentPhotoSource {
    var permission=NativePhotoAccess.FULL
    var rows=emptyList<NativeRecentPhoto>()
    val inputCalls=AtomicInteger()
    val snapshotCalls=AtomicInteger()
    var lastLimit=0
    @Volatile var gate: CompletableDeferred<Unit>?=null
    @Volatile var failOnceId: String?=null
    @Volatile var failure: NativeFileIntakeFailure?=null
    private val files=ConcurrentHashMap<String,Uri>()
    private val paths=mutableListOf<File>()
    private val observers=mutableSetOf<()->Unit>()
    val pageCalls=AtomicInteger()
    @Volatile var pageFailOnceAfter: Long?=null
    override fun access()=permission
    override fun permissions()=emptyArray<String>()
    override fun observe(changed: ()->Unit): ()->Unit {observers.add(changed);return {observers.remove(changed);Unit}}
    override suspend fun snapshot(limit: Int): List<NativeRecentPhoto> { snapshotCalls.incrementAndGet();lastLimit=limit;return rows.take(limit) }
    override suspend fun page(after: NativePhotoCursor?,limit: Int): NativePhotoPage {
        pageCalls.incrementAndGet()
        if(after!=null&&pageFailOnceAfter==after.id) {pageFailOnceAfter=null;throw IllegalStateException("owned catalogue failure")}
        val offset=if(after==null)0 else rows.indexOfFirst {it.cursor==after}.also {check(it>=0)}+1
        val values=rows.drop(offset).take(limit)
        return NativePhotoPage(values,if(offset+values.size<rows.size)values.last().cursor else null)
    }
    override suspend fun thumbnail(photo: NativeRecentPhoto): Bitmap? = withContext(Dispatchers.IO) {
        files[photo.id]?.let { activity.contentResolver.openInputStream(it)?.use(BitmapFactory::decodeStream) }
    }
    override suspend fun input(photo: NativeRecentPhoto): LocalAttachmentInput {
        val original=checkNotNull(files[photo.id]);inputCalls.incrementAndGet();gate?.await();failure?.let { throw it }
        if(failOnceId==photo.id) {failOnceId=null;throw NativeFileIntakeFailure(NativePhotoIntake.UNREADABLE)}
        return NativePhotoIntake.read(activity.contentResolver,original)
    }
    fun photo(id: Int,added: Double=System.currentTimeMillis()/1000.0): NativeRecentPhoto {
        val uri=Uri.parse("content://media/external/images/media/$id")
        if(!files.containsKey(uri.toString())) {
            val directory=File(activity.cacheDir,"resources/hint-${UUID.randomUUID()}").apply { mkdirs() };paths.add(directory)
            val file=File(directory,"hint-$id.png");paths.add(0,file)
            val bitmap=Bitmap.createBitmap(4,3,Bitmap.Config.ARGB_8888).apply { eraseColor(color(id)) }
            try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } }finally { bitmap.recycle() }
            files[uri.toString()]=FileProvider.getUriForFile(activity,activity.packageName+".resources",file)
        }
        return NativeRecentPhoto(uri,added)
    }
    fun publish(next: List<NativeRecentPhoto>) { rows=next;observers.toList().forEach {it()} }
    fun cleanup() { paths.forEach { assertTrue(!it.exists()||it.delete()) } }
    companion object { fun color(id: Int)=Color.rgb(id*37%256,80,170) }
}

internal class RecentPhotoHintAcceptance(val activity: MainActivity,private val wait: (()->Boolean)->Unit,source: NativeRecentPhotoSource?=null) {
    val owned=OwnedControlAcceptance(activity,"recent-photo-hint")
    val session get()=owned.session
    val fake=source as? HintPhotoSource ?: if(source==null)HintPhotoSource(activity)else null
    val reader=source ?: fake!!
    val library get()=session.recentPhotos!!
    val preferences=owned.storage.child(activity,"recent-photos")
    private lateinit var previous: MainActivity.AcceptanceSessionState
    private val server: CommandFixture
    fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    init {
        val target=JSONObject().put("agentId","codex").put("hostId","hint-host").put("workspaceId","hint-workspace").put("runtimeId","hint-runtime")
        server=CommandFixture { r -> when(r.path) {
            "/v1/workspace"->WireResponse(body=JSONObject().put("hostId","hint-host").put("name","Hint original target").put("connectionMode","desktop-runtime").put("readTarget",target).put("agentConnectionState",JSONObject().put("runtimeConnected",true))
                .put("capabilities",JSONArray(listOf("reply","commands:target-bound:v1"))).put("projects",JSONArray()).put("threads",JSONArray(listOf("first","second").map { JSONObject().put("id",it).put("hostId","hint-host").put("title",it).put("state","idle") })).toString())
            "/v1/history"->WireResponse(body=JSONObject().put("hostId","hint-host").put("threadId",Uri.parse(r.pathWithQuery).getQueryParameter("threadId")).put("readTarget",target).put("messages",JSONArray())
                .put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("canControl",true).put("plan",JSONArray()).put("approvals",JSONArray()).put("receipts",JSONArray())).toString())
            else->WireResponse(404)
        } }
        main {
            fake?.let { it.rows=listOf(it.photo(1)) }
            session.resetAcceptance();session.changeLanguage("en");session.changeAppearance(InstrumentationRegistry.getArguments().getString("viewportTheme")?:"light")
            session.configureRecentPhotos(activity,reader);previous=activity.adoptSessionForAcceptance(session)
            session.connect("Hint original target",server.endpoint,"");session.selectThread(session.threads.first { it.id=="first" });session.text="最近图片原稿 100% %@ 😀"
        }
        wait { !session.historyLoading&&session.canCompose&&!session.attachmentRecoveryBusy&&library.ready&&(library.access==NativePhotoAccess.NONE||library.revision>0)&&!library.loading&&library.recent==fake?.rows.orEmpty() }
    }
    fun publish(id: Int): NativeRecentPhoto {
        lateinit var photo: NativeRecentPhoto
        main { photo=fake!!.photo(id);fake.publish(listOf(photo)+fake.rows.filter { it.id!=photo.id }) }
        wait { library.suggestion?.photo?.id==photo.id };return photo
    }
    fun finish() {
        fake?.gate?.complete(Unit)
        wait { !session.importing&&!library.saving }
        main { session.leaveForeground();activity.restoreSessionForAcceptance(previous) }
        owned.verifyDefault();assertTrue(server.requests.all { it.method=="GET" });assertTrue(session.operationRecords.isEmpty());server.close();fake?.cleanup()
    }
    fun assertPixels(file: CreationFile,id: Int) {
        val bytes=runBlocking { NativeDraftFiles(owned.storage).bytes(file) };val image=checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
        try { assertEquals(4,image.width);assertEquals(3,image.height);assertEquals(HintPhotoSource.color(id),image.getPixel(2,1)) }finally { image.recycle() }
    }
}
