package com.agenthub.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.After
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Native UI/session regression with synthetic HTTP only; real desktop acceptance is separate. */
class ProjectDirectorySyncTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private val session get()=ui.activity.session
    private var admitted=false
    @After fun closeOwnedSession() { if(admitted)ui.runOnIdle {session.resetAcceptance()} }
    private fun requireIsolated() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("projectDirectorySync")=="1")
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        check(session.clients.none { !it.isDemo }) { "Use a fresh dedicated acceptance profile" }
        admitted=true
    }
    private suspend fun waitFor(test: ()->Boolean)=withTimeout(15_000) { while(!withContext(Dispatchers.Main){test()})delay(25) }
    private fun query(r: WireRequest,key: String)=URI(r.pathWithQuery).rawQuery?.split('&')?.firstOrNull {it.substringBefore('=')==key}?.substringAfter('=')

    @Test fun healthyVisibleSidebarPublishesEmptyProjectRenameAndRemovalWithoutEvents()=runBlocking {
        waitFor { !session.restoring }
        withContext(Dispatchers.Main) {requireIsolated()}
        ChatManagementFixture().use { base ->
            val revision=AtomicInteger(0)
            fun workspace(): JSONObject = base.workspace().put("capabilities",JSONArray(listOf("workspace:refresh:v1")))
                .put("threads",JSONArray()).put("projects",JSONArray(if(revision.get()>=2)emptyList<JSONObject>() else listOf(
                    JSONObject().put("id","empty-project").put("hostId",base.target.hostId).put("name",if(revision.get()==0)"Original empty project" else "Renamed empty project").put("roots",JSONArray()))))
            CommandFixture {r->WireResponse(body=when(r.path){"/host","/v1/workspace"->workspace().toString();else->"{}"})}.use { server ->
                withContext(Dispatchers.Main) { session.connect("Directory fixture",server.endpoint,"") }
                ui.onNodeWithTag("open-sidebar").performClick()
                ui.onNodeWithTag("project-empty-project").assertExists()
                revision.set(1)
                waitFor {session.projects.singleOrNull()?.name=="Renamed empty project"}
                ui.onNodeWithText("Renamed empty project").assertExists()
                revision.set(2)
                waitFor {session.projects.isEmpty()&&!session.loading}
                ui.onNodeWithTag("project-empty-project").assertDoesNotExist()
                assertTrue(server.requests.none {it.method=="POST"})
            }
        }
    }

    @Test fun manualSuccessorsCompleteEveryProjectPageAndDoNotPublishLateOldDirectory()=runBlocking {
        waitFor { !session.restoring }
        withContext(Dispatchers.Main) {requireIsolated()}
        ChatManagementFixture().use { base ->
            val fresh=AtomicInteger(0);val revision=AtomicInteger(0)
            val began=Array(2){CountDownLatch(1)};val release=Array(2){CountDownLatch(1)}
            CommandFixture {r->
                if(r.path !in listOf("/host","/v1/workspace"))WireResponse(body="{}") else {
                    val captured=revision.get();val page=query(r,"projectCursor")=="projects-2"
                    if(query(r,"refresh")=="1") {val n=fresh.incrementAndGet();if(n<=2){began[n-1].countDown();check(release[n-1].await(20,TimeUnit.SECONDS))}}
                    val projects=if(page)listOf(JSONObject().put("id","tail").put("hostId",base.target.hostId).put("name","Tail project").put("roots",JSONArray())) else listOf(
                        JSONObject().put("id","head").put("hostId",base.target.hostId).put("name","Revision $captured").put("roots",JSONArray()))
                    WireResponse(body=base.workspace().put("capabilities",JSONArray(listOf("workspace:refresh:v1"))).put("threads",JSONArray())
                        .put("projects",JSONArray(projects)).put("nextProjectCursor",if(page)JSONObject.NULL else "projects-2").toString())
                }
            }.use { server ->
                try {
                    withContext(Dispatchers.Main) {session.connect("Directory fixture",server.endpoint,"")}
                    val a=withContext(Dispatchers.Main){session.refresh(userInitiated=true)!!}
                    assertTrue(withContext(Dispatchers.IO){began[0].await(5,TimeUnit.SECONDS)})
                    revision.set(1)
                    val b=withContext(Dispatchers.Main){session.refresh(userInitiated=true)!!}
                    release[0].countDown()
                    assertTrue(withContext(Dispatchers.IO){began[1].await(5,TimeUnit.SECONDS)})
                    assertFalse(a.isCompleted);assertFalse(b.isCompleted)
                    revision.set(2)
                    val c=withContext(Dispatchers.Main){session.refresh(userInitiated=true)!!}
                    release[1].countDown()
                    withTimeout(15_000){a.join();b.join();c.join()}
                    waitFor { !session.loading }
                    assertEquals(3,fresh.get())
                    assertEquals(listOf("Revision 2","Tail project"),withContext(Dispatchers.Main){session.projects.map {it.name}})
                    assertTrue(server.requests.none {it.method=="POST"})
                } finally {release.forEach {it.countDown()}}
            }
        }
    }
}
