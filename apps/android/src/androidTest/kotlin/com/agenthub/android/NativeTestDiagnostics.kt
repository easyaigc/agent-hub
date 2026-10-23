package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.util.Timer
import java.util.TimerTask

/** Opt-in, app-local stack evidence for a stalled synthetic acceptance case. */
internal class NativeTestDiagnostics: TestRule {
    override fun apply(base: Statement,description: Description): Statement=object: Statement() {
        override fun evaluate() {
            if(InstrumentationRegistry.getArguments().getString("nativeThreadSnapshots")!="1") { base.evaluate();return }
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            check(instrumentation.targetContext.packageName=="com.easyai.agenthub.android.acceptance")
            val timer=Timer("native-read-diagnostics",true)
            timer.schedule(object: TimerTask() {
                override fun run() {
                    runCatching {
                        val name=(description.className+"-"+description.methodName).replace(Regex("[^A-Za-z0-9_.-]"),"_")
                        val trace=Thread.getAllStackTraces().entries.sortedBy { it.key.id }.joinToString("\n\n") { (thread,frames)->
                            "Thread ${thread.id} ${thread.name} ${thread.state}\n"+frames.joinToString("\n") { "  at $it" }
                        }
                        trace.chunked(3000).forEach { android.util.Log.i("NativeTestDiagnostics",it) }
                        val folder=File(instrumentation.targetContext.getExternalFilesDir(null),"acceptance/P2-read-diagnostics").apply { mkdirs() }
                        folder.resolve("$name-${System.currentTimeMillis()}.txt").writeText(trace)
                    }.onFailure { android.util.Log.w("NativeTestDiagnostics","Native stack snapshot unavailable",it) }
                }
            },45_000)
            try { base.evaluate() } finally { timer.cancel() }
        }
    }
}
