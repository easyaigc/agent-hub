package com.agenthub.android

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.os.Process
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID

/** Test-only observation before original assertions. No service/flag policy, Binder call or retry. */
internal object NativeNaturalProbeDiagnostics {
    fun parcel(value: Parcel) = runCatching { JSONObject().put("position", value.dataPosition()).put("size", value.dataSize())
        .put("available", value.dataAvail()).put("hasFileDescriptors", value.hasFileDescriptors()) }.getOrElse { JSONObject().put("snapshotErrorType",it.javaClass.name).put("snapshotErrorMessage",it.message ?: JSONObject.NULL) }
    fun observation(kind: String, guard: String, code: Int, error: Throwable?, request: JSONObject,
        original: Map<String,String>, observed: Result<Map<String,String>>): JSONObject {
        val value = JSONObject().put("version",1).put("kind",kind).put("expectedGuard",guard).put("expectedCode",code)
            .put("exceptionType",error?.javaClass?.name ?: JSONObject.NULL).put("exceptionMessage",error?.message ?: JSONObject.NULL)
            .put("causeType",error?.cause?.javaClass?.name ?: JSONObject.NULL).put("causeMessage",error?.cause?.message ?: JSONObject.NULL)
            .put("clientParcel",request).put("originalFixtureInventory",JSONObject(original))
            .put("observedFixtureInventory",observed.getOrNull()?.let { JSONObject(it) } ?: JSONObject.NULL)
            .put("inventoryComplete",observed.isSuccess).put("inventoryErrorType",observed.exceptionOrNull()?.javaClass?.name ?: JSONObject.NULL)
            .put("inventoryErrorMessage",observed.exceptionOrNull()?.message ?: JSONObject.NULL)
            .put("originalFilesUnchangedAtObservation",observed.getOrNull()?.let { it == original } ?: JSONObject.NULL)
        val match = Regex("P9_NATURAL_PROBE_(UID|FLAGS|REPLY|FDS|INTERFACE|OPERATION|PAYLOAD) code=(-?[0-9]+) flags=(-?[0-9]+) pos=([0-9]+) size=([0-9]+) avail=([0-9]+)").find(error?.message ?: "")
        val numbers = match?.groupValues?.drop(2)?.map { it.toIntOrNull() }
        if(match != null && numbers != null && numbers.all { it != null })value.put("serverGuard",JSONObject()
            .put("reason",match.groupValues[1]).put("code",numbers[0]).put("actualFlags",numbers[1])
            .put("position",numbers[2]).put("size",numbers[3]).put("available",numbers[4]))
        else value.put("serverGuard",JSONObject.NULL)
        return value.put("serverMetadataSource","Parsed only from retained actual exception message; unavailable remains null")
    }
    fun save(context: Context, lease: String, run: String, observation: JSONObject) {
        for(id in listOf(lease,run))require(UUID.fromString(id).toString()==id)
        val nonce=UUID.randomUUID().toString(); val value=JSONObject().put("lease",lease).put("run",run).put("observationNonce",nonce)
            .put("api",Build.VERSION.SDK_INT).put("pid",Process.myPid()).put("uid",Process.myUid()).put("elapsed",SystemClock.elapsedRealtime())
            .put("installed",NativeNaturalPreflightEvidence.installed(context)).put("observation",observation)
            .put("policyChanged",false).put("beforeOriginalAssertions",true).put("nativeNaturalProof",false)
        // Distinct custom status; never a JUnit test pass. In-memory details already retain this object.
        InstrumentationRegistry.getInstrumentation().sendStatus(2,Bundle().apply {putString("p9NaturalProbeDiagnostic",value.toString())})
        val bytes=value.toString().toByteArray(Charsets.UTF_8);require(bytes.size in 1..32768)
        var folder=context.filesDir.canonicalFile
        for(name in listOf("acceptance","P9-natural-store",lease)) {
            val next=File(folder,name)
            if(Files.notExists(next.toPath(),NOFOLLOW_LINKS))Files.createDirectory(next.toPath(),java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")))
            require(Files.isDirectory(next.toPath(),NOFOLLOW_LINKS)&&!Files.isSymbolicLink(next.toPath())&&next.canonicalFile.parentFile==folder);folder=next
        }
        val file=File(folder,"$run-refusal-$nonce.json");require(file.createNewFile());android.system.Os.chmod(file.absolutePath,384)
        java.io.FileOutputStream(file).use {it.write(bytes);it.fd.sync()};NativeNaturalPreflightEvidence.sync(folder)
        require(file.readBytes().contentEquals(bytes))
    }
}
