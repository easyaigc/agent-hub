package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Explicit, task-private credentials only; this test never creates a task or dispatches an Agent. */
@RunWith(AndroidJUnit4::class)
class TaskHubLiveSessionTest {
    @get:org.junit.Rule val timeout=org.junit.rules.Timeout.seconds(120)

    @Test fun realHumanSessionRestoresAndRevokesOnlyThisDevice()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val name=InstrumentationRegistry.getArguments().getString("taskHubPrivateFixture")
            ?: error("An explicit private DEV session fixture is required")
        require(name.matches(Regex("task-hub-live-[a-f0-9-]{36}\\.json")))
        val file=File(context.filesDir,name)
        val input=try { JSONObject(file.readText()) } finally { check(file.delete()) }
        val binding=TaskHubBinding.decode(input.getJSONObject("binding"))
        val namespace="um06-live-"+UUID.randomUUID()
        // Preserve the registered TLS hostname and HTTP Host while routing this
        // task-owned emulator through its explicit adb reverse to the local service.
        val makeController={ TaskHubController(context,namespace) { selected,device,token ->
            TaskHubClient(selected,device,token,okhttp3.Dns { host ->
                require(host==java.net.URI(binding.endpoint).host)
                listOf(java.net.InetAddress.getByName("127.0.0.1"))
            })
        } }
        val controller=makeController()
        try {
            val original=controller.login(binding,input.getString("username"),input.getString("password"),
                "Snapshot integration native DEV",TaskHubActionAuthorizer { _,_-> error("No business writes authorized by this session test") })
            original.verify()
            val current=original.deviceSessions().single { it.current }
            assertEquals(original.deviceId,current.deviceId)
            assertEquals("active",current.status)
            original.detach()
            val restoredController=makeController()
            try {
                val restored=restoredController.restore(TaskHubActionAuthorizer { _,_-> error("No business writes authorized") })
                    ?: error("The actual encrypted human session did not restore")
                assertEquals(original.scope,restored.scope)
                assertEquals(original.deviceId,restored.deviceId)
                restored.verify()
                assertEquals(current.id,restored.deviceSessions().single { it.current }.id)
                assertTrue("The explicit logout must confirm remote revocation",restoredController.logout())
                assertNull(makeController().restore(TaskHubActionAuthorizer { _,_-> error("No writes") }))
                var rejected=false
                try { restored.verify() } catch(error: TaskHubHTTPError) { rejected=error.status==401 }
                assertTrue("The revoked original credential must no longer authenticate",rejected)
            } finally { restoredController.logout() }
        } finally { controller.logout() }
    }
}
