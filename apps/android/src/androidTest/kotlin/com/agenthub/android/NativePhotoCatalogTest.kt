package com.agenthub.android

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

/** Actual native SQLite and asynchronous source boundaries; no real library/model substitute. */
class NativePhotoCatalogTest {
    private fun photo(id: Int,added: Long?=1000)=NativeRecentPhoto(Uri.parse("content://media/external/images/media/$id"),added?.toDouble(),"$id.png",added)
    private fun source(page: suspend (NativePhotoCursor?,Int)->NativePhotoPage)=object: NativeRecentPhotoSource {
        override fun access()=NativePhotoAccess.FULL
        override fun permissions()=emptyArray<String>()
        override fun observe(changed: ()->Unit): ()->Unit = {}
        override suspend fun snapshot(limit: Int)=error("Catalogue never falls back to a recent snapshot")
        override suspend fun page(after: NativePhotoCursor?,limit: Int)=page.invoke(after,limit)
        override suspend fun thumbnail(photo: NativeRecentPhoto): android.graphics.Bitmap?=error("Metadata never reads pixels")
        override suspend fun input(photo: NativeRecentPhoto): LocalAttachmentInput=error("Metadata never imports")
    }
    private suspend fun waitFor(condition: ()->Boolean)=withTimeout(10_000) {while(!condition())delay(5)}
    @Test fun completePagesKeepStableOrderAndRejectDuplicateOrNonprogressingCursors()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);val all=(131 downTo 1).map {photo(it)};var malformed=false
        val catalog=NativePhotoCatalog(source {after,limit->
            val start=if(after==null)0 else all.indexOfFirst {it.cursor==after}+1
            if(malformed)NativePhotoPage(all.take(3),after) else all.drop(start).take(limit).let {NativePhotoPage(it,if(start+it.size<all.size)it.last().cursor else null)}
        },scope)
        try {
            withContext(Dispatchers.Main) {catalog.refresh()};waitFor {!catalog.loading};assertEquals(all.take(60),catalog.photos)
            malformed=true;withContext(Dispatchers.Main) {catalog.loadMore()};waitFor {!catalog.loading};assertTrue(catalog.failed);assertEquals(all.take(60),catalog.photos)
            malformed=false;withContext(Dispatchers.Main) {catalog.loadMore(true)};waitFor {!catalog.loading};assertEquals(120,catalog.photos.size)
            withContext(Dispatchers.Main) {catalog.loadMore()};waitFor {!catalog.loading};assertEquals(all,catalog.photos);assertTrue(catalog.finished)
        }finally {withContext(Dispatchers.Main) {catalog.close()};scope.cancel()}
    }
    @Test fun refreshAndCloseIgnoreExactLateReadsWithoutClearingTheNewCatalogue()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);val original=CompletableDeferred<NativePhotoPage>();var calls=0
        val catalog=NativePhotoCatalog(source {_,_->if(calls++==0)withContext(NonCancellable){original.await()} else NativePhotoPage(listOf(photo(2)),null)},scope)
        try {
            withContext(Dispatchers.Main) {catalog.refresh()};waitFor {calls==1}
            withContext(Dispatchers.Main) {catalog.refresh()};waitFor {!catalog.loading};assertEquals(listOf(photo(2)),catalog.photos)
            original.complete(NativePhotoPage(listOf(photo(1)),null));delay(50);assertEquals(listOf(photo(2)),catalog.photos)
            withContext(Dispatchers.Main) {catalog.close()};assertFalse(catalog.loading)
        }finally {scope.cancel()}
    }
    @Test fun sqliteKeysetHandlesSameDateNullZeroNegativeAndPendingBoundaries() {
        val sdk=android.os.Build.VERSION.SDK_INT
        SQLiteDatabase.create(null).use {db->
            db.execSQL("CREATE TABLE photos (_id INTEGER PRIMARY KEY, date_added INTEGER, is_pending INTEGER, is_trashed INTEGER)")
            val dates=listOf<Long?>(8,8,8,0,0,-1,null,null,null)
            dates.forEachIndexed {i,date->db.execSQL("INSERT INTO photos VALUES (?, ?, 0, 0)",arrayOf<Any?>(i+1,date))}
            db.execSQL("INSERT INTO photos VALUES (10, 10, 1, 0)");db.execSQL("INSERT INTO photos VALUES (11, 9, 0, 1)")
            val result=mutableListOf<Long>();var cursor: NativePhotoCursor?=null
            do {
                val q=NativePhotoQuery.selection(cursor,sdk);val page=mutableListOf<NativePhotoCursor>()
                db.query("photos",arrayOf("_id","date_added"),q.first,q.second,null,null,NativePhotoQuery.ORDER,"2").use {rows->while(rows.moveToNext())page.add(NativePhotoCursor(if(rows.isNull(1))null else rows.getLong(1),rows.getLong(0)))}
                assertTrue(page.none {it.id in result});result.addAll(page.map {it.id});cursor=page.lastOrNull()
            }while(cursor!=null)
            val expected=(if(sdk<29)listOf(10L) else emptyList())+(if(sdk<30)listOf(11L) else emptyList())+listOf(3L,2L,1L,5L,4L,6L,9L,8L,7L)
            assertEquals(expected,result)
        }
    }
}
