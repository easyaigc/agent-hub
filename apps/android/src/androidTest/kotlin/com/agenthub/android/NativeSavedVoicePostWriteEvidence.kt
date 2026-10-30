package com.agenthub.android

import android.Manifest
import android.content.Context
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/** The legacy delegate is constructed only by the absent-plan branch. */
internal interface NativeSavedVoicePostWriteEvidence {
    val folder: File
    fun write(name: String,body: JSONObject)
    fun verify()
    companion object {
        fun legacy(context: Context): NativeSavedVoicePostWriteEvidence {
            val old=NativeSavedVoiceSessionEvidence(context)
            return object: NativeSavedVoicePostWriteEvidence {
                override val folder get()=old.folder
                override fun write(name: String,body: JSONObject)=old.write(name,body)
                override fun verify()=old.verify()
            }
        }
        fun protected(context: Context,readMetadata: ()->String?): NativeSavedVoicePostWriteEvidence =
            NativeSavedVoiceProtectedEvidence(context,readMetadata)
    }
}

/** Only evidence directories/files are created; default access is entirely the supplied strict reader. */
private class NativeSavedVoiceProtectedEvidence(private val context: Context,readMetadata: ()->String?): NativeSavedVoicePostWriteEvidence {
    init {require(context.packageName=="com.easyai.agenthub.android.acceptance")}
    private val witness=NativeSavedVoiceMetadataWitness(readMetadata){context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)}
    override val folder: File
    init {
        val run=InstrumentationRegistry.getArguments().getString("ownerCallbacksRun") ?: "owned-model"
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        var current=context.filesDir.canonicalFile
        for(name in listOf("acceptance","P4-owner-callbacks",run,"saved-voice-session-"+UUID.randomUUID())) {
            val next=File(current,name)
            if(Files.notExists(next.toPath(),NOFOLLOW_LINKS))Files.createDirectory(next.toPath(),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            require(Files.isDirectory(next.toPath(),NOFOLLOW_LINKS)&&!Files.isSymbolicLink(next.toPath()))
            current=next
        }
        folder=current
    }
    override fun write(name: String,body: JSONObject) {
        require(name.matches(Regex("[a-z0-9-]+")))
        File(folder,name+".json").writeText(body.put("pid",Process.myPid()).put("realSession",true).put("realEncryptedWriter",true)
            .put("realAcousticEngine",false).put("modelTasks",0).put("protectedDefaultReader","existing-no-recovery").toString())
    }
    override fun verify() {
        witness.verify()
        write("default-storage-proof",JSONObject().put("defaultDraftsAndAttachmentsPreserved",true).put("completeDefaultMetadataUnchanged",true).put("microphonePermissionPreserved",true))
    }
}
