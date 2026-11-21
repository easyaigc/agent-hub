package com.agenthub.android

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.roundToInt

/** Observed bytes only. Cache bytes are never counted as network throughput. */
enum class ResourceDownloadPhase { Preparing, Downloading, Verifying, Cached }
data class ResourceDownloadProgress(
    val phase: ResourceDownloadPhase = ResourceDownloadPhase.Preparing,
    val receivedBytes: Long? = null, val totalBytes: Long? = null,
    val downloadStartedAtNanos: Long? = null,
    val observedAtNanos: Long? = null, val sampledBytesPerSecond: Double? = null,
) {
    init {
        require(receivedBytes == null || receivedBytes >= 0)
        require(totalBytes == null || totalBytes >= 0)
        require(receivedBytes == null || totalBytes == null || receivedBytes <= totalBytes)
        require(sampledBytesPerSecond == null || sampledBytesPerSecond.isFinite() && sampledBytesPerSecond >= 0)
    }
    val fraction: Float? get() = totalBytes?.let { total -> receivedBytes?.let { count ->
        if(total > 0) (count.toDouble() / total).toFloat()
        else if(phase in setOf(ResourceDownloadPhase.Verifying, ResourceDownloadPhase.Cached)) 1f else null
    } }
    fun bytesPerSecond(atNanos: Long): Double? {
        if(phase != ResourceDownloadPhase.Downloading)return null
        val observed=observedAtNanos ?: return null
        val speed=sampledBytesPerSecond ?: return null
        // A stalled read emits no new sample. The visible clock must expire its last rate.
        return if(atNanos-observed >= 1_500_000_000L) 0.0 else speed
    }
}
typealias ResourceDownloadProgressHandler = (ResourceDownloadProgress) -> Unit
/** Binds the actual resolved version to the verified bytes returned by the adapter. */
data class ResourceDownloadSnapshot(val file: MobileFile, val local: File)

internal class ResourceDownloadReporter(private val total: Long, private val callback: ResourceDownloadProgressHandler?,
    private val clock: ()->Long = System::nanoTime) {
    private val started=clock()
    private var last=started
    private var lastBytes=0L
    fun downloading(received: Long, force: Boolean=false) {
        val now=clock()
        require(received>=lastBytes && received<=total)
        if(force || now-last>=100_000_000L) {
            val elapsed=now-last
            val speed=if(elapsed>0)(received-lastBytes).toDouble()*1_000_000_000.0/elapsed else 0.0
            last=now;lastBytes=received
            callback?.invoke(ResourceDownloadProgress(ResourceDownloadPhase.Downloading,received,total,started,now,speed))
        }
    }
    fun verifying(received: Long) { callback?.invoke(ResourceDownloadProgress(ResourceDownloadPhase.Verifying,received,total)) }
}

/** Shared projection for attachment cards, inline links and document-link loads. */
@Composable internal fun NativeResourceDownloadProgress(progress: ResourceDownloadProgress?, tag: String) {
    val value=progress ?: ResourceDownloadProgress()
    val context=LocalContext.current
    var clock by remember(value) { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(value) {
        if(value.phase==ResourceDownloadPhase.Downloading)while(true) { delay(500);clock=System.nanoTime() }
    }
    val title=productText(when(value.phase) {
        ResourceDownloadPhase.Preparing -> "正在准备文件…"
        ResourceDownloadPhase.Downloading -> "正在下载…"
        ResourceDownloadPhase.Verifying -> "正在校验文件…"
        ResourceDownloadPhase.Cached -> "正在打开缓存文件…"
    })
    Column(Modifier.fillMaxWidth().testTag(tag),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(title,Modifier.weight(1f).testTag("$tag-phase"),style=MaterialTheme.typography.bodySmall)
            value.fraction?.let { Text("${(it*100).roundToInt()}%",Modifier.testTag("$tag-percent"),style=MaterialTheme.typography.bodySmall) }
        }
        if(value.phase!=ResourceDownloadPhase.Preparing) {
            val fraction=value.fraction
            if(fraction==null)LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress={fraction},modifier=Modifier.fillMaxWidth())
            val count=value.receivedBytes?.let { Formatter.formatShortFileSize(context,it) } ?: "—"
            val total=value.totalBytes?.let { Formatter.formatShortFileSize(context,it) } ?: "—"
            Text("$count / $total",Modifier.testTag("$tag-bytes"),style=MaterialTheme.typography.labelSmall)
            value.bytesPerSecond(clock)?.takeIf { it.isFinite() && it <= Long.MAX_VALUE.toDouble() }?.let {
                Text(Formatter.formatShortFileSize(context,it.toLong())+"/s",Modifier.testTag("$tag-speed"),style=MaterialTheme.typography.labelSmall)
            }
        }
    }
}
