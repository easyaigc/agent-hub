package com.agenthub.android

import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** The cancel hook can abort a blocking transport while the owning coroutine still joins its
 * actual read/close/finally. It does not detach a worker or report an early resource release. */
internal suspend fun <T> ownSavedVoiceDownload(cancel: ()->Unit,body: suspend ()->T): T = coroutineScope {
    val cancellation=launch(Dispatchers.Default,start=CoroutineStart.UNDISPATCHED) {
        try {awaitCancellation()}finally {cancel()}
    }
    try {currentCoroutineContext().ensureActive();body()}
    catch(error: Throwable) {currentCoroutineContext().ensureActive();throw error}
    finally {cancellation.cancel()}
}

/** An explicit attempt always copies a fresh full archive. Never append/resume an unverified part.
 * The caller still syncs and hashes the on-disk file before any extraction or publication. */
internal suspend fun copySavedVoiceArchive(input: InputStream,output: OutputStream,bytes: Long,sha256: String,checkpoint: suspend ()->Unit) {
    require(bytes>0&&sha256.matches(Regex("[a-f0-9]{64}")))
    val digest=MessageDigest.getInstance("SHA-256");val chunk=ByteArray(65536);var count=0L
    try {
        while(true) {
            checkpoint();currentCoroutineContext().ensureActive()
            val size=input.read(chunk)
            // A returned read cannot write another chunk after cancellation or loss of authority.
            checkpoint();currentCoroutineContext().ensureActive()
            if(size<0)break
            require(size>0) {"Model stream made no progress"}
            require(size.toLong()<=bytes-count) {"Model archive exceeds its pinned size"}
            output.write(chunk,0,size);digest.update(chunk,0,size);count+=size
        }
        require(count==bytes) {"Model archive is incomplete"}
        require(digest.digest().joinToString(""){"%02x".format(it)}==sha256) {"Model archive checksum mismatch"}
        checkpoint();currentCoroutineContext().ensureActive()
    }finally {chunk.fill(0)}
}
