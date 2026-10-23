package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Opt-in, read-only encrypted repository audit. Never exports credentials or conversation/draft text. */
@RunWith(AndroidJUnit4::class)
class MobileOrdinaryRepositoryAuditTest {
    @Test fun inspectOriginalAndKnownFixtureMetadataWithoutWriting() {
        val args=InstrumentationRegistry.getArguments()
        require(args.getString("UM05MetadataAudit")=="true")
        val namespace=args.getString("auditNamespace")!!
        require(namespace=="mobile-v1" || namespace.matches(Regex("um05-original-review-[a-f0-9-]{36}")))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        require(File(context.noBackupFilesDir,namespace).isDirectory)
        val metadata=NativeSessionStorage(context,namespace).metadata() ?: error("Original metadata missing")
        val clients=metadata.getJSONArray("clients").objects().map { it.getString("id") }
        val drafts=metadata.getJSONObject("drafts")
        fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val result=JSONObject().put("namespace",namespace).put("metadataSha256",hash(metadata.toString().toByteArray()))
            .put("clientIds",JSONArray(clients)).put("selectedClientId",metadata.getString("selected"))
            .put("onlyKnownSharingFixture",clients.isNotEmpty() && clients.all { it=="fixture-host" })
            .put("draftCount",drafts.length()).put("draftsSha256",hash(drafts.toString().toByteArray()))
            .put("credentialsExported",false).put("draftTextExported",false)
        val folder=File(context.getExternalFilesDir(null),"acceptance/UM05-data-audit").apply { mkdirs() }
        File(folder,"$namespace.json").writeText(result.toString(2))
    }
}
