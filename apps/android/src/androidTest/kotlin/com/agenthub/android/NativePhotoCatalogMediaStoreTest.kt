package com.agenthub.android

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Owned SDK only: real MediaStore pages and exact created-URI cleanup; no model/upload. */
class NativePhotoCatalogMediaStoreTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    @Test fun allAccessiblePagesMatchNativeStoreOrderAndExcludeUnpublishedRows()=runBlocking {
        require(Build.VERSION.SDK_INT>=29&&Build.HARDWARE in listOf("ranchu","goldfish"))
        ui.waitUntil(30_000) {!ui.activity.session.restoring}
        lateinit var owned: OwnedControlAcceptance
        withContext(Dispatchers.Main) {owned=OwnedControlAcceptance(ui.activity,"catalogue-media")}
        val context=ui.activity;val resolver=context.contentResolver;val source=NativeRecentPhotos(context);val created=mutableListOf<Uri>();val nonce=java.util.UUID.randomUUID().toString()
        assertEquals(NativePhotoAccess.FULL,source.access())
        fun metadata(): Map<Long,List<Long?>> {
            val rows=mutableMapOf<Long,List<Long?>>()
            val columns=arrayOf(MediaStore.Images.Media._ID,MediaStore.Images.Media.DATE_ADDED,MediaStore.Images.Media.IS_PENDING)+if(Build.VERSION.SDK_INT>=30)arrayOf(MediaStore.Images.Media.IS_TRASHED) else emptyArray()
            resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,columns,Bundle().apply {if(Build.VERSION.SDK_INT>=30){putInt(MediaStore.QUERY_ARG_MATCH_TRASHED,MediaStore.MATCH_INCLUDE);putInt(MediaStore.QUERY_ARG_MATCH_PENDING,MediaStore.MATCH_INCLUDE)}},null)!!.use {c->while(c.moveToNext())rows[c.getLong(0)]=listOf(if(c.isNull(1))null else c.getLong(1),c.getLong(2),if(Build.VERSION.SDK_INT>=30)c.getLong(3) else 0L)}
            return rows
        }
        val baseline=metadata();require(baseline.size<=64) {"Large pre-existing library is outside this owned fixture scope"}
        val bitmap=Bitmap.createBitmap(4,3,Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.BLUE)
        val bytes=java.io.ByteArrayOutputStream().also {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();bitmap.recycle()
        try {
            repeat(131) {index->
                val uri=checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {put(MediaStore.Images.Media.DISPLAY_NAME,"owned-catalogue-$nonce-$index.png");put(MediaStore.Images.Media.MIME_TYPE,"image/png");put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/AgentHubCatalogue-$nonce");put(MediaStore.Images.Media.IS_PENDING,1)}));created.add(uri)
                java.io.File(owned.folder,"created-uris.json").writeText(org.json.JSONArray(created.map {it.toString()}).toString())
                resolver.openOutputStream(uri)!!.use {it.write(bytes)}
                if(index<130)resolver.update(uri,ContentValues().apply {put(MediaStore.Images.Media.IS_PENDING,0)},null,null)
            }
            val hidden=mutableSetOf(created.last().lastPathSegment!!.toLong())
            if(Build.VERSION.SDK_INT>=30) {val trash=created[129];assertEquals(1,resolver.update(trash,ContentValues().apply {put(MediaStore.Images.Media.IS_TRASHED,1)},null,null));hidden.add(trash.lastPathSegment!!.toLong())}
            val expected=metadata().filter {it.key !in hidden&&it.value[1]==0L&&it.value[2]==0L}.entries.sortedWith(compareByDescending<Map.Entry<Long,List<Long?>>> {it.value[0]}.thenByDescending {it.key}).map {it.key}
            assertTrue(expected.size>120);val photos=mutableListOf<NativeRecentPhoto>();var after: NativePhotoCursor?=null;var pages=0
            do {
                val page=source.page(after,60);pages++;assertTrue(page.photos.size<=60);assertTrue(page.photos.none {row->photos.any {it.id==row.id}});photos.addAll(page.photos);after=page.next
            }while(after!=null&&pages<10)
            assertNull(after);assertEquals((expected.size+59)/60,pages);assertEquals(expected,photos.map {it.uri.lastPathSegment!!.toLong()});assertEquals(expected.take(30),source.snapshot(30).map {it.uri.lastPathSegment!!.toLong()})
            assertTrue(source.page(NativePhotoCursor(null,Long.MAX_VALUE),60).photos.isEmpty())
            java.io.File(owned.folder,"actual-paged-media-proof.json").writeText(JSONObject().put("nativeMediaStore",true).put("createdRows",created.size).put("baselineRowsPreserved",baseline.size).put("accessibleRows",photos.size).put("pages",pages).put("exactDateAndIdOrder",true).put("pendingAndApplicableTrashExcluded",true).put("recentRemainsThirty",true).put("nativeNullCursorQuery",true).put("newModelInputs",0).put("uploadOrSubmit",false).toString())
        }finally {
            val cleanupFailures=mutableListOf<String>()
            for(uri in created) {
                val removed=if(Build.VERSION.SDK_INT>=30)resolver.delete(uri,Bundle().apply {putInt(MediaStore.QUERY_ARG_MATCH_TRASHED,MediaStore.MATCH_INCLUDE);putInt(MediaStore.QUERY_ARG_MATCH_PENDING,MediaStore.MATCH_INCLUDE)}) else resolver.delete(uri,null,null)
                if(removed!=1)cleanupFailures.add(uri.toString())
            }
            assertTrue("Every exact created URI is removed",cleanupFailures.isEmpty())
            val after=metadata();for((id,values)in baseline)assertEquals("Original media metadata remains untouched",values,after[id])
            assertTrue(created.none {it.lastPathSegment!!.toLong() in after})
            withContext(Dispatchers.Main) {owned.session.leaveForeground();owned.verifyDefault();assertTrue(owned.session.operationRecords.isEmpty())}
        }
    }
}
