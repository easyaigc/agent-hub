package com.agenthub.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual API26 diagnostic producer. It never supplies a same-Surface/native success. */
internal class P8Api26CompositeProducer(
    private val instrumentation: Instrumentation,
    private val folder: File,
    private val method: String,
    private val locale: String,
    private val dark: Boolean,
    private val build: JSONObject,
    private val popup: (Long) -> View,
    private val liveChoice: () -> String?,
    private val touchCount: () -> Int,
    private val probe: (JSONObject) -> Unit,
) {
    companion object {
        const val KIND = "uiautomation-display-composite-popup-diagnostic-v2"
        const val OPT_IN = "p8Api26CompositeDiagnostic"
        val METHODS = setOf("api26ChineseLightCompositePreferenceDiagnostic", "api26EnglishDarkCompositePreferenceDiagnostic")
        private val activeScreenshot = AtomicReference<Thread?>()
        fun requireNoUnresolvedScreenshot() {
            check(activeScreenshot.get() == null) { "Previous screenshot IPC remains unresolved; no further capture or input" }
        }
        fun screenshotUnresolved(): Boolean = activeScreenshot.get() != null
        fun awaitScreenshotReleaseBeforeTeardown() {
            // Do not tear down the controlled UI while an un-cancellable screenshot can still complete.
            // This wait can remain unbounded: admission timeout is not an IPC cancellation guarantee.
            var interrupted = false
            while (true) {
                val worker = activeScreenshot.get() ?: break
                try { worker.join() } catch (_: InterruptedException) { interrupted = true }
                if (worker.state == Thread.State.TERMINATED) activeScreenshot.compareAndSet(worker, null)
                else Thread.yield() // A concurrently admitted NEW thread has not started yet; do not release its slot.
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        private val phases = listOf("known-initial-automatic", "after-click-and-form-reopen-lan-only",
            "after-click-and-form-reopen-relay-only", "after-click-and-form-reopen-automatic")
        private val choices = listOf("automatic", "lan-only", "relay-only", "automatic")
        private fun box(r: Rect) = JSONObject().put("left", r.left).put("top", r.top).put("right", r.right).put("bottom", r.bottom)
        private fun whole(a: Rect, b: Rect) = a.width() > 0 && a.height() > 0 && b.contains(a)
    }

    private val captures = JSONArray()
    private var processedPixels = 0L
    private var cropPixels = 0L
    private var pngBytes = 0L
    private var failed = false
    private val targetPackage get() = instrumentation.targetContext.packageName
    private val main = Handler(Looper.getMainLooper())
    private fun budget(deadline: Long) { check(SystemClock.uptimeMillis() < deadline) { "Composite deadline exceeded" } }
    private fun remaining(deadline: Long) = (deadline - SystemClock.uptimeMillis()).also { check(it > 0) }
    private fun <T> onMain(deadline: Long, read: () -> T): T {
        budget(deadline)
        if (Looper.myLooper() == Looper.getMainLooper()) return read().also { budget(deadline) }
        val task = FutureTask<T> { budget(deadline); read() }
        check(main.post(task))
        try { return task.get(remaining(deadline), TimeUnit.MILLISECONDS).also { budget(deadline) } }
        catch (failure: java.util.concurrent.ExecutionException) { throw failure.cause ?: failure }
        finally { main.removeCallbacks(task); task.cancel(false) }
    }

    private data class ScreenResult(val bitmap: Bitmap?, val failure: Throwable?)
    private fun screenshot(deadline: Long): Bitmap {
        requireNoUnresolvedScreenshot()
        val delivery = P8PixelDelivery<ScreenResult> { result -> result.bitmap?.let { if (!it.isRecycled) it.recycle() } }
        val done = CountDownLatch(1)
        val worker = Thread({
            try {
                // This synchronous IPC has no public cancellation guarantee. Only this worker owns its result.
                budget(deadline)
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                delivery.offer(ScreenResult(bitmap, null))
            } catch (failure: Throwable) { delivery.offer(ScreenResult(null, failure)) }
            finally { done.countDown() }
        }, "p8-api26-single-screenshot").apply { isDaemon = true }
        check(activeScreenshot.compareAndSet(null, worker))
        try {
            try { worker.start() } catch (failure: Throwable) { activeScreenshot.compareAndSet(worker, null); throw failure }
            check(done.await(remaining(deadline), TimeUnit.MILLISECONDS)) { "Screenshot result unknown; abandon acceptance and retain IPC ownership" }
            worker.join(remaining(deadline))
            check(worker.state == Thread.State.TERMINATED) { "Screenshot worker has not actually exited" }
            check(activeScreenshot.compareAndSet(worker, null))
            val result = delivery.take()
            if (result.failure != null) throw result.failure
            // No budget check between taking and returning: caller receives ownership even on a late boundary.
            return checkNotNull(result.bitmap) { "Public screenshot returned null" }
        } finally { delivery.close() }
    }

    private fun origin(view: View, token: IBinder, windowId: Int, deadline: Long): JSONObject = onMain(deadline) {
        check(Build.VERSION.SDK_INT == 26)
        check(view.isAttachedToWindow && view.rootView === view && view.windowToken === token)
        check(view.javaClass.name == "androidx.compose.ui.window.PopupLayout" && view.context.packageName == targetPackage)
        check((view.layoutParams as WindowManager.LayoutParams).type == WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL)
        check(view.isShown && view.hasWindowFocus())
        val info = checkNotNull(view.createAccessibilityNodeInfo())
        try { check(info.windowId == windowId) } finally { @Suppress("DEPRECATION") info.recycle() }
        val display = checkNotNull(view.display)
        check(display.displayId == Display.DEFAULT_DISPLAY && display.rotation == Surface.ROTATION_0)
        val size = Point(); @Suppress("DEPRECATION") display.getRealSize(size)
        val screen = IntArray(2); val window = IntArray(2)
        view.getLocationOnScreen(screen); view.getLocationInWindow(window)
        check(listOf(size.x, size.y, view.width, view.height).all { it in 1..4096 })
        check(screen[0] >= 0 && screen[1] >= 0 && window[0] >= 0 && window[1] >= 0)
        val bounds = Rect(screen[0], screen[1], screen[0] + view.width, screen[1] + view.height)
        check(whole(bounds, Rect(0, 0, size.x, size.y)))
        val visible = Rect(); view.getWindowVisibleDisplayFrame(visible); check(whole(bounds, visible))
        JSONObject().put("screenX", screen[0]).put("screenY", screen[1]).put("windowX", window[0]).put("windowY", window[1])
            .put("width", view.width).put("height", view.height).put("displayId", display.displayId)
            .put("displayWidth", size.x).put("displayHeight", size.y).put("rotation", display.rotation)
    }

    private fun screenRect(origin: JSONObject) = Rect(origin.getInt("screenX"), origin.getInt("screenY"),
        origin.getInt("screenX") + origin.getInt("width"), origin.getInt("screenY") + origin.getInt("height"))

    /** Observation is narrower than complete compositor inspection; unknown reported roots fail before capture. */
    private fun observedWindows(windowId: Int, crop: Rect, deadline: Long): JSONArray {
        check(instrumentation.uiAutomation.serviceInfo.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0)
        val windows = instrumentation.uiAutomation.windows
        try {
            check(windows.size in 1..16 && windows.map { it.id }.distinct().size == windows.size)
            val ownedPopup = windows.single { it.id == windowId }
            check(ownedPopup.type == AccessibilityWindowInfo.TYPE_APPLICATION && ownedPopup.isFocused && ownedPopup.isActive)
            val actualPopupBounds = Rect(); ownedPopup.getBoundsInScreen(actualPopupBounds); check(whole(crop, actualPopupBounds))
            val result = JSONArray(); var totalNodes = 0
            for (window in windows.sortedWith(compareByDescending<AccessibilityWindowInfo> { it.layer }.thenBy { it.id })) {
                budget(deadline)
                check(window.id >= 0 && window.layer >= 0)
                val bounds = Rect(); window.getBoundsInScreen(bounds)
                if (window.id != windowId && window.layer >= ownedPopup.layer) check(!Rect.intersects(bounds, crop)) { "Reported upper window overlaps Popup" }
                val root = checkNotNull(window.root) { "Unknown reported window root" }
                var count = 0
                try {
                    fun walk(node: AccessibilityNodeInfo, depth: Int = 0) {
                        budget(deadline); check(++count <= 2048 && ++totalNodes <= 2048 && depth <= 64)
                        // Do not store text, descriptions or pixels from foreign reported windows.
                        check(node.packageName?.toString() == targetPackage) { "Unapproved reported window/content; no screenshot" }
                        for (index in 0 until node.childCount) {
                            val child = checkNotNull(node.getChild(index)) { "Incomplete reported window tree" }
                            try { walk(child, depth + 1) } finally { @Suppress("DEPRECATION") child.recycle() }
                        }
                    }
                    check(root.refresh()); walk(root)
                } finally { @Suppress("DEPRECATION") root.recycle() }
                result.put(JSONObject().put("id", window.id).put("type", window.type).put("layer", window.layer)
                    .put("active", window.isActive).put("focused", window.isFocused).put("boundsScreen", box(bounds))
                    .put("allReportedNodesTargetPackage", true).put("visitedNodes", count))
            }
            return result
        } finally { windows.forEach { @Suppress("DEPRECATION") it.recycle() } }
    }

    private fun snapshot(view: View, token: IBinder, rows: List<P8PreferenceOptionAx>, labels: Map<String, String>, expected: String, deadline: Long): JSONObject {
        check(popup(deadline) === view) { "Three actual semantics roots must retain the same Popup View" }
        val windowId = rows.map { it.windowId }.distinct().single(); check(windowId >= 0)
        val origin = origin(view, token, windowId, deadline); val crop = screenRect(origin)
        val windows = observedWindows(windowId, crop, deadline)
        val actualLiveChoice = onMain(deadline) { liveChoice() }; check(actualLiveChoice == expected)
        val root = checkNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        val nodes = mutableMapOf<Int, AccessibilityNodeInfo>()
        val expectedIds = rows.flatMap { listOf(it.ownerId, it.textId) }.toSet()
        val labelCounts = labels.values.associateWith { 0 }.toMutableMap(); var visited = 0
        fun owned(node: AccessibilityNodeInfo) = node.windowId == windowId && node.packageName?.toString() == targetPackage
        fun nativeId(node: AccessibilityNodeInfo): Int? {
            budget(deadline); check(owned(node)); val key = "androidx.compose.ui.semantics.id"
            if (!node.availableExtraData.contains(key)) { check(node.refresh() && owned(node)); return null }
            check(node.refreshWithExtraData(key, android.os.Bundle()) && owned(node) && node.extras.containsKey(key))
            return node.extras.getInt(key)
        }
        val options = JSONArray()
        try {
            check(root.refresh() && owned(root))
            fun walk(node: AccessibilityNodeInfo, depth: Int = 0) {
                budget(deadline); check(++visited <= 2048 && depth <= 64); val id = nativeId(node)
                if (id in expectedIds) { check(!nodes.containsKey(id)); nodes[checkNotNull(id)] = AccessibilityNodeInfo.obtain(node) }
                node.text?.toString()?.let { if (it in labelCounts) labelCounts[it] = checkNotNull(labelCounts[it]) + 1 }
                for (index in 0 until node.childCount) {
                    val child = checkNotNull(node.getChild(index))
                    try { walk(child, depth + 1) } finally { @Suppress("DEPRECATION") child.recycle() }
                }
            }
            walk(root); check(nodes.keys == expectedIds && expectedIds.size == 6)
            for (row in rows) {
                val owner = checkNotNull(nodes[row.ownerId]); val text = checkNotNull(nodes[row.textId])
                val actual = row.selection.copy(nativeClass = owner.className?.toString(), checkable = owner.isCheckable, checked = owner.isChecked, selected = owner.isSelected)
                check(p8ExactPreferenceSelection(actual, row.value == expected))
                check(owner.isEnabled && owner.isVisibleToUser && owner.isClickable && text.isVisibleToUser)
                check(owner.className?.toString() == "android.view.View" && text.className?.toString() == "android.widget.TextView")
                val label = checkNotNull(text.text).toString(); check(label == labels[row.value] && labelCounts[label] == 1)
                check(easyHash(label.toByteArray()) == row.labelHash)
                val tagKey = "androidx.compose.ui.semantics.testTag"
                check(owner.availableExtraData.contains(tagKey) && owner.refreshWithExtraData(tagKey, android.os.Bundle()) && owned(owner))
                check(owner.extras.getCharSequence(tagKey)?.toString() == row.selection.tag)
                val actionIds = owner.actionList.map { it.id }.sorted(); check(actionIds.size <= 64 && actionIds.distinct() == actionIds && AccessibilityNodeInfo.ACTION_CLICK in actionIds)
                val path = mutableListOf<Int>(); var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(text)
                try {
                    for (index in row.nativeTextToOwnerPath.indices) {
                        val node = checkNotNull(current); path.add(checkNotNull(nativeId(node)))
                        if (index == row.nativeTextToOwnerPath.lastIndex) { check(node == owner); break }
                        val parent = checkNotNull(node.parent); @Suppress("DEPRECATION") node.recycle(); current = parent
                    }
                } finally { current?.let { @Suppress("DEPRECATION") it.recycle() } }
                check(path == row.nativeTextToOwnerPath && path.size in 2..16 && path.distinct() == path)
                val ownerBounds = Rect(); val textBounds = Rect(); owner.getBoundsInScreen(ownerBounds); text.getBoundsInScreen(textBounds)
                check(whole(ownerBounds, crop) && whole(textBounds, ownerBounds))
                options.put(JSONObject().put("value", row.value).put("ownerId", row.ownerId).put("textId", row.textId).put("windowId", owner.windowId)
                    .put("path", JSONArray(path)).put("actualText", label).put("nativeClass", owner.className?.toString()).put("textClass", text.className?.toString())
                    .put("checkable", owner.isCheckable).put("checked", owner.isChecked).put("selected", owner.isSelected)
                    .put("enabled", owner.isEnabled).put("visible", owner.isVisibleToUser).put("clickable", owner.isClickable)
                    .put("actionIds", JSONArray(actionIds)).put("ownerBoundsScreen", box(ownerBounds)).put("textBoundsScreen", box(textBounds)))
            }
        } finally { nodes.values.forEach { @Suppress("DEPRECATION") it.recycle() }; @Suppress("DEPRECATION") root.recycle() }
        // A second independent read brackets AX observation; no origin comes from a desired crop.
        check(origin(view, token, windowId, deadline).toString() == origin.toString())
        check(popup(deadline) === view && onMain(deadline) { liveChoice() } == expected)
        val actualPopup = onMain(deadline) { JSONObject().put("class", view.javaClass.name).put("lpType", (view.layoutParams as WindowManager.LayoutParams).type)
            .put("windowId", windowId).put("targetPackage", view.context.packageName == targetPackage).put("attached", view.isAttachedToWindow)
            .put("shown", view.isShown).put("focused", view.hasWindowFocus()).put("boundsScreen", box(crop)) }
        return JSONObject().put("origin", origin).put("popup", actualPopup)
            .put("options", options).put("liveChoice", actualLiveChoice).put("reportedWindows", windows)
            .put("sameRetainedViewAndToken", true).put("globalUniqueOwnerProved", false).put("allCompositedLayersObserved", false)
    }

    private fun report(status: String) = JSONObject().put("kind", KIND).put("method", method).put("locale", locale).put("dark", dark)
        .put("build", JSONObject(build.toString())).put("captures", captures).put("status", status)
        .put("nativeAcceptanceEligible", false).put("sameSurfaceProof", false).put("screenshotUnresolved", screenshotUnresolved())
        .put("commonTraceValidation", "separate original triplet/saved/touch/protection records; not certified here")
        .put("limits", JSONObject().put("maxStatePixels", 1_048_576).put("maxCaseProcessedPixels", 4_194_304).put("maxStatePngBytes", 2_097_152)
            .put("maxCasePngBytes", 8_388_608).put("maxLiveArgbBytes", 4_194_304).put("maxArgbPlusPngBytes", 6_291_456).put("captureBudgetMs", 3000).put("originalTripletBudgetMs", 5000))
        .put("provenance", JSONObject().put("source", "UiAutomation.takeScreenshot()").put("pixelScope", "default-display-system-composite-crop")
            .put("surfaceBinding", "not-proved").put("frameCommitProof", "not-proved").put("uniqueOwnedPopupAcquisition", "not-proved")
            .put("frameworkPeakAllocation", "not-proved").put("callCancellation", "not-guaranteed").put("occlusionAndUnknownContentExclusion", "not-proved-by-interactive-window-samples"))

    private fun persistMetadata(status: String) {
        val bytes = report(status).toString().toByteArray(Charsets.UTF_8); check(bytes.size <= 262_144)
        val file = android.util.AtomicFile(File(folder, "composite-diagnostic.json")); val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (failure: Throwable) { file.failWrite(stream); throw failure }
    }

    fun capture(expected: String, labels: Map<String, String>, rows: List<P8PreferenceOptionAx>, phase: String) {
        check(!failed && Build.VERSION.SDK_INT == 26 && method in METHODS)
        requireNoUnresolvedScreenshot()
        val index = captures.length(); check(index in 0..3 && phases[index] == phase && choices[index] == expected)
        check(rows.map { it.value } == listOf("automatic", "lan-only", "relay-only"))
        check(p8ExclusivePreferenceState(expected, labels.mapValues { easyHash(it.value.toByteArray()) }, rows))
        val start = SystemClock.uptimeMillis(); val deadline = start + 3000; val beforeTouches = touchCount()
        var screen: Bitmap? = null; var cropped: Bitmap? = null; var committedFile: File? = null
        try {
            val view = popup(deadline); val token = onMain(deadline) { checkNotNull(view.windowToken) }
            val before = snapshot(view, token, rows, labels, expected, deadline); val origin = before.getJSONObject("origin"); val crop = screenRect(origin)
            val fullWidth = origin.getInt("displayWidth"); val fullHeight = origin.getInt("displayHeight")
            val pixels = fullWidth.toLong() * fullHeight + crop.width().toLong() * crop.height()
            check(pixels <= 1_048_576 && processedPixels + pixels <= 4_194_304)
            // Both owned-window observations must be admitted immediately before the single screenshot.
            check(observedWindows(rows.first().windowId, crop, deadline).toString() == before.getJSONArray("reportedWindows").toString())
            check(touchCount() == beforeTouches); budget(deadline)
            screen = screenshot(deadline); val source = checkNotNull(screen); budget(deadline)
            check(source.width == fullWidth && source.height == fullHeight && source.config == Bitmap.Config.ARGB_8888)
            val screenAllocation = source.allocationByteCount.toLong()
            check(screenAllocation >= fullWidth.toLong() * fullHeight * 4 && screenAllocation <= 4_194_304)
            cropped = Bitmap.createBitmap(source, crop.left, crop.top, crop.width(), crop.height())
            val image = checkNotNull(cropped); check(image !== source) { "Full/crop Bitmap alias rejected" }
            check(image.width == crop.width() && image.height == crop.height() && image.config == Bitmap.Config.ARGB_8888)
            val cropAllocation = image.allocationByteCount.toLong(); val allocation = screenAllocation + cropAllocation
            check(cropAllocation >= crop.width().toLong() * crop.height() * 4 && allocation <= 4_194_304)
            source.recycle(); screen = null
            val after = snapshot(view, token, rows, labels, expected, deadline)
            check(before.toString() == after.toString() && touchCount() == beforeTouches)
            val encodedLimit = minOf(2_097_152, (8_388_608 - pngBytes).toInt(), ((6_291_456 - allocation) / 3).toInt())
            check(encodedLimit > 0)
            val output = P8BoundedPng(encodedLimit) { SystemClock.uptimeMillis() < deadline }
            check(image.compress(Bitmap.CompressFormat.PNG, 100, output)); val bytes = output.bytes(); budget(deadline)
            check(bytes.isNotEmpty() && allocation + 3L * bytes.size <= 6_291_456) // Reserve buffer growth and byte-array copy before encoding.
            val file = File(folder, "composite-preference-$phase.png"); check(file.createNewFile()); committedFile = file
            file.outputStream().use { it.write(bytes) }; check(file.length() == bytes.size.toLong())
            val hash = easyHash(bytes); check(EasyWorkPresentationFixture.hash(file) == hash); budget(deadline)
            check(popup(deadline) === view && onMain(deadline) { view.windowToken === token } && touchCount() == beforeTouches)
            image.recycle(); cropped = null
            val capture = JSONObject().put("phase", phase).put("expected", expected).put("before", before).put("after", after)
                .put("cropRectScreen", box(crop)).put("windowProjectionRect", box(Rect(origin.getInt("windowX"), origin.getInt("windowY"), origin.getInt("windowX") + crop.width(), origin.getInt("windowY") + crop.height())))
                .put("bitmap", JSONObject().put("screenWidth", fullWidth).put("screenHeight", fullHeight).put("screenAllocationBytes", screenAllocation).put("cropAllocationBytes", cropAllocation)
                    .put("config", "ARGB_8888").put("distinctObjects", true).put("screenRecycledBeforeEncoding", true).put("cropRecycledAfterPersistence", true))
                .put("png", JSONObject().put("file", file.name).put("bytes", bytes.size).put("sha256", hash))
                .put("clock", JSONObject().put("elapsedMs", SystemClock.uptimeMillis() - start).put("sourceReturned", true))
                .put("inputWitness", JSONObject().put("beforeTouches", beforeTouches).put("afterTouches", touchCount()).put("dispatchedInputs", touchCount() - beforeTouches))
                .put("counters", JSONObject().put("stateIndex", index + 1).put("caseProcessedPixels", processedPixels + pixels)
                    .put("caseCropPixels", cropPixels + crop.width().toLong() * crop.height()).put("casePngBytes", pngBytes + bytes.size))
            budget(deadline)
            captures.put(capture); processedPixels += pixels; cropPixels += crop.width().toLong() * crop.height(); pngBytes += bytes.size
            persistMetadata(if (captures.length() == 4) "diagnostic-collected-not-native-accepted" else "diagnostic-incomplete")
            budget(deadline)
            probe(JSONObject().put("kind", KIND).put("phase", phase).put("file", file.name).put("sha256", hash).put("nativeAcceptanceEligible", false).put("sameSurfaceProof", false))
            budget(deadline)
            committedFile = null
        } catch (failure: Throwable) {
            failed = true
            // Only this newly-created partial crop is deleted. No screenshot or late callback persists pixels.
            committedFile?.let { check(it.parentFile == folder && it.name == "composite-preference-$phase.png"); check(it.delete() || !it.exists()) }
            persistMetadata(if (screenshotUnresolved()) "failed-screenshot-result-unknown-stop-input" else "failed-not-native-accepted")
            throw failure
        } finally {
            val source = screen; val image = cropped
            if (image != null && image !== source && !image.isRecycled) image.recycle()
            if (source != null && !source.isRecycled) source.recycle()
        }
    }
}
