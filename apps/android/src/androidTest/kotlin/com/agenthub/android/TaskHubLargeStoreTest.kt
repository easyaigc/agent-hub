package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Multi-MiB projections must survive the actual Android cursor and process boundary. */
@RunWith(AndroidJUnit4::class)
class TaskHubLargeStoreTest {
    @get:org.junit.Rule val timeout=org.junit.rules.Timeout.seconds(120)
    @org.junit.Before fun processEvidence() {
        InstrumentationRegistry.getInstrumentation().sendStatus(2,android.os.Bundle().apply { putString("taskHubStorePid",android.os.Process.myPid().toString()) })
    }
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace get()=InstrumentationRegistry.getArguments().getString("taskHubStoreNamespace")?.also {
        require(it.startsWith("um06-large-") && it.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
    } ?: error("A task-private namespace is required")
    private val original get()="原缓存 中文 😀\n"+"x".repeat((InstrumentationRegistry.getArguments().getString("taskHubLargeBytes")?.toInt() ?: (4*1024*1024)).also { require(it in 1024..4*1024*1024) })
    @Test fun createOriginalLargeProjection() {
        val db=TaskHubSQLitePersistence(context,namespace)
        check(db.readAll().isEmpty()) { "Do not overwrite an existing producer snapshot" }
        db.transaction { it["large-projection"]=original;it["draft"]="original draft" }
        assertEquals(original,db.readAll()["large-projection"])
    }
    @Test fun readOriginalAndCommitNewDraft() {
        val db=TaskHubSQLitePersistence(context,namespace)
        assertEquals(original,db.readAll()["large-projection"])
        assertEquals("original draft",db.readAll()["draft"])
        db.transaction { it["draft"]="new draft after process restart" }
        assertEquals(original,db.readAll()["large-projection"])
        val file=java.io.File(context.noBackupFilesDir,"$namespace/business.sqlite")
        android.database.sqlite.SQLiteDatabase.openDatabase(file.path,null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { sql ->
            sql.rawQuery("SELECT MAX(length(payload)) FROM frames",null).use { c -> assertTrue(c.moveToFirst());assertTrue(c.getLong(0)<=65536+29) }
        }
    }
    @Test fun verifyBothAfterAnotherProcessRestart() {
        val values=TaskHubSQLitePersistence(context,namespace).readAll()
        assertEquals(original,values["large-projection"])
        assertEquals("new draft after process restart",values["draft"])
    }
    @Test fun invalidOriginalIsRejectedWithoutReset() {
        val file=java.io.File(context.noBackupFilesDir,"$namespace/business.sqlite")
        check(file.isFile);val before=file.readBytes();var rejected=false
        try { TaskHubSQLitePersistence(context,namespace).readAll() } catch(_: Exception) { rejected=true }
        assertTrue("Unauthenticated original must remain quarantined",rejected)
        assertArrayEquals(before,file.readBytes())
    }
}
