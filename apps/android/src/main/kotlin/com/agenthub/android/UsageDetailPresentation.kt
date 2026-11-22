package com.agenthub.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.agenthub.core.UsageRules
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Display only. Time never changes an observed allowance or initiates a read. */
internal object UsageDetailPresentation {
    fun resets(data: JSONObject): List<String> {
        val metrics = data.optJSONArray("metrics") ?: return emptyList()
        return (0 until metrics.length()).map { metrics.getJSONObject(it) }
            .filter { it.optString("kind") == "window-limit" && !it.isNull("resetAt") }
            .map { it.getString("resetAt") }.distinct()
    }

    fun needsTick(resets: List<String>, now: Long): Boolean =
        resets.any { (UsageRules.resetSeconds(it, now) ?: 0) > 0 }

    fun remaining(metric: JSONObject, translate: (String) -> String): String? =
        if (metric.optString("kind") == "extension") null
        else usageRemaining(metric, translate) ?: translate("暂不可用")

    fun countdown(metric: JSONObject, now: Long, translate: (String) -> String): String? =
        if (metric.optString("kind") != "window-limit") null
        else if (metric.isNull("resetAt")) translate("重置时间暂不可用")
        else usageResetCountdown(metric.getString("resetAt"), now, translate = translate)
}

/** Scoped to the open details and complete target identity; no Store/network dependency. */
@Composable
internal fun usageDetailTime(
    identity: String,
    foreground: Boolean,
    data: JSONObject,
    clock: () -> Long = System::currentTimeMillis
): Long {
    val resets = UsageDetailPresentation.resets(data)
    // Re-entering foreground/new target reads wall time immediately, without the previous tick.
    var now by remember(identity, foreground, resets) { mutableLongStateOf(clock()) }
    LaunchedEffect(identity, foreground, resets) {
        if (foreground) {
            do {
                now = clock()
                if (!UsageDetailPresentation.needsTick(resets, now)) break
                delay(1000)
            } while (true)
        }
    }
    return now
}

/** Stacked labels/values stay readable at large font sizes in narrow dialogs. */
@Composable
internal fun UsageDetailMetric(metric: JSONObject, now: Long) {
    val text = currentProductText()
    val translate: (String) -> String = { text.tr(it) }
    val id = metric.getString("id")
    UsageDetailPresentation.countdown(metric, now, translate)?.let { value ->
        Column(Modifier.testTag("usage-reset-countdown-$id"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text.tr("距离下次重置"), style = MaterialTheme.typography.labelMedium)
            Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
        }
    }
    UsageDetailPresentation.remaining(metric, translate)?.let { value ->
        Column(Modifier.testTag("usage-remaining-$id"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text.tr("剩余额度"), style = MaterialTheme.typography.labelMedium)
            SelectionContainer { Text(value, style = MaterialTheme.typography.bodyLarge) }
        }
    }
}
