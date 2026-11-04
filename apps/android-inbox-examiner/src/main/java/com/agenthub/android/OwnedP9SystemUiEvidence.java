package com.agenthub.android;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Examiner-only official UiAutomation evidence; no target launch or target-private state write. */
final class OwnedP9SystemUiEvidence {
    private static final String APP = "com.easyai.agenthub.android.acceptance";
    private static final String SYSTEM = "com.android.systemui", ROW = SYSTEM + ":id/expandableNotificationRow";
    private static final String AX_V1 = "android-accessibility-owned-systemui-row-v1", AX_V2 = "android-accessibility-owned-systemui-row-v2";
    private static final String BACKGROUND_NORMAL = SYSTEM + ":id/backgroundNormal", BACKGROUND_DIMMED = SYSTEM + ":id/backgroundDimmed";
    private static final String CLIP_POLICY = "api36-direct-background-family-noninteractive-v3";
    enum Scene { FIRST_MAIN, LATER_DRAFT }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static String sha(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder value = new StringBuilder();
        for (byte b : digest) value.append(String.format(Locale.ROOT, "%02x", b)); return value.toString();
    }
    private static String text(CharSequence value) {
        String result = value == null ? "" : value.toString(); check(result.length() <= 4096, "synthetic UI field cap"); return result;
    }
    private static void original(File folder, String name, byte[] bytes, int cap) throws Exception {
        check(Arrays.asList("first-main.json", "first-main.png", "later-draft.json", "later-draft.png", "writer-identity.json", "first-consumer-identity.json", "later-consumer-identity.json").contains(name), "fixed UI role");
        check(folder.equals(folder.getCanonicalFile()) && Files.isDirectory(folder.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
            && !Files.isSymbolicLink(folder.toPath()) && bytes.length > 0 && bytes.length <= cap, "owned bounded UI evidence");
        File file = new File(folder, name); check(file.createNewFile(), "original UI evidence retained; no overwrite");
        android.system.Os.chmod(file.getPath(), 0600);
        try (FileOutputStream stream = new FileOutputStream(file)) { stream.write(bytes); stream.getFD().sync(); }
        java.io.FileDescriptor fd = android.system.Os.open(folder.getPath(), android.system.OsConstants.O_RDONLY | android.system.OsConstants.O_NOFOLLOW, 0);
        try { android.system.Os.fsync(fd); } finally { android.system.Os.close(fd); }
    }
    private static Throwable merge(Throwable first, Throwable later) {
        if (first == null) return later;
        if (later != null && first != later) first.addSuppressed(later);
        return first;
    }
    private static void rethrow(Throwable error) throws Exception {
        if (error instanceof Exception) throw (Exception) error;
        if (error instanceof Error) throw (Error) error;
        throw new AssertionError(error);
    }
    private static String type(Throwable error) { return error == null ? "none" : error.getClass().getName(); }
    private static Object actual(CharSequence value, int[] characters) {
        if (value == null) return JSONObject.NULL;
        String s = value.toString(); characters[0] += s.length();
        check(s.length() <= 4096 && characters[0] <= 32768, "owned row actual text cap"); return s;
    }
    private static JSONObject box(Rect r) throws Exception {
        return new JSONObject().put("left", r.left).put("top", r.top).put("right", r.right).put("bottom", r.bottom)
            .put("width", r.width()).put("height", r.height());
    }
    private static JSONObject actualNode(AccessibilityNodeInfo node, Rect r, int[] characters) throws Exception {
        JSONArray actions = new JSONArray();
        check(node.getActionList() != null && node.getActionList().size() <= 32, "owned row action cap");
        for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList())
            actions.put(new JSONObject().put("id", action.getId()).put("label", actual(action.getLabel(), characters)));
        return new JSONObject().put("package", actual(node.getPackageName(), characters)).put("resourceId", actual(node.getViewIdResourceName(), characters))
            .put("class", actual(node.getClassName(), characters)).put("text", actual(node.getText(), characters))
            .put("contentDescription", actual(node.getContentDescription(), characters)).put("boundsInScreen", box(r))
            .put("windowId", node.getWindowId()).put("visible", node.isVisibleToUser()).put("clickable", node.isClickable())
            .put("enabled", node.isEnabled()).put("childCount", node.getChildCount()).put("actions", actions);
    }
    private static final class RowSnapshot {
        final Rect bounds; final int window; final String fingerprint; final JSONObject selected;
        RowSnapshot(Rect bounds, int window, String fingerprint, JSONObject selected) {
            this.bounds = new Rect(bounds); this.window = window; this.fingerprint = fingerprint; this.selected = selected;
        }
    }
    private static String boundedDiagnosticName(CharSequence value) {
        return value == null ? "<none>" : value.subSequence(0, Math.min(value.length(), 128)).toString();
    }
    private static boolean emptyActual(Object value) {
        return value == null || value == JSONObject.NULL || value instanceof String && ((String) value).isEmpty();
    }
    private static final class DecorativeClip {
        final JSONObject node, clip;
        DecorativeClip(JSONObject node, JSONObject clip) { this.node = node; this.clip = clip; }
    }
    /** A nonnull info object is retained only as its observed presence, never as an inferred default. */
    private static Object infoPresence(Object value) throws Exception {
        return value == null ? JSONObject.NULL : new JSONObject().put("present", true);
    }
    /** API36 formal getters on this same freshly refreshed node; no fallback or action invocation.
     * Populate the already-attached diagnostic object incrementally so a failed getter cannot
     * erase earlier facts or turn unknown fields into success defaults.
     */
    private static void readDecorativeSemantics(AccessibilityNodeInfo node, int[] characters, JSONObject facts) throws Exception {
        facts.put("selected", node.isSelected()).put("focused", node.isFocused()).put("accessibilityFocused", node.isAccessibilityFocused())
            .put("movementGranularities", node.getMovementGranularities()).put("textSelectionStart", node.getTextSelectionStart())
            .put("textSelectionEnd", node.getTextSelectionEnd()).put("inputType", node.getInputType()).put("password", node.isPassword())
            .put("contentInvalid", node.isContentInvalid()).put("dismissable", node.isDismissable()).put("canOpenPopup", node.canOpenPopup())
            .put("rangeInfo", infoPresence(node.getRangeInfo())).put("collectionInfo", infoPresence(node.getCollectionInfo()))
            .put("collectionItemInfo", infoPresence(node.getCollectionItemInfo())).put("hintText", actual(node.getHintText(), characters))
            .put("stateDescription", actual(node.getStateDescription(), characters)).put("paneTitle", actual(node.getPaneTitle(), characters))
            .put("tooltipText", actual(node.getTooltipText(), characters));
    }
    private static boolean defaultDecorativeSemantics(JSONObject facts) throws Exception {
        return facts.length() == 18 && !facts.getBoolean("selected") && !facts.getBoolean("focused") && !facts.getBoolean("accessibilityFocused")
            && facts.getInt("movementGranularities") == 0 && facts.getInt("textSelectionStart") == -1 && facts.getInt("textSelectionEnd") == -1
            && facts.getInt("inputType") == 0 && !facts.getBoolean("password") && !facts.getBoolean("contentInvalid")
            && !facts.getBoolean("dismissable") && !facts.getBoolean("canOpenPopup")
            && facts.get("rangeInfo") == JSONObject.NULL && facts.get("collectionInfo") == JSONObject.NULL && facts.get("collectionItemInfo") == JSONObject.NULL
            && emptyActual(facts.get("hintText")) && emptyActual(facts.get("stateDescription"))
            && emptyActual(facts.get("paneTitle")) && emptyActual(facts.get("tooltipText"));
    }
    private static int backgroundIndex(String resourceId) {
        return BACKGROUND_NORMAL.equals(resourceId) ? 2 : BACKGROUND_DIMMED.equals(resourceId) ? 3 : -1;
    }
    /** A bounded AX role observation policy; it does not prove a concrete runtime View subclass. */
    private static boolean qualifiedBackground(JSONObject observed, JSONObject clip, Rect row, int window) throws Exception {
        int roleIndex = backgroundIndex(observed.getString("resourceId"));
        JSONObject raw = observed.getJSONObject("boundsInScreen"), intersection = clip.getJSONObject("cropIntersection");
        boolean allowed = roleIndex > 0 && observed.getInt("index") == roleIndex && clip.getInt("nodeIndex") == roleIndex
            && observed.getInt("parent") == 0 && observed.getInt("depth") == 1 && observed.getInt("childOrdinal") == roleIndex - 1
            && SYSTEM.equals(observed.getString("package")) && observed.getInt("windowId") == window
            && "android.view.View".equals(observed.get("class")) && observed.getInt("childCount") == 0
            && observed.getBoolean("visible") && !observed.getBoolean("clickable")
            && !clip.getBoolean("longClickable") && !clip.getBoolean("contextClickable") && !clip.getBoolean("focusable")
            && !clip.getBoolean("editable") && !clip.getBoolean("scrollable") && !clip.getBoolean("checkable")
            && defaultDecorativeSemantics(clip.getJSONObject("semanticProperties"))
            && emptyActual(observed.get("text")) && emptyActual(observed.get("contentDescription"))
            && CLIP_POLICY.equals(clip.getString("policy")) && clip.length() == 10
            && raw.getInt("left") == row.left && raw.getInt("right") == row.right && raw.getInt("top") == row.top && raw.getInt("bottom") > row.bottom
            && !row.isEmpty() && raw.getInt("width") == row.width() && raw.getInt("height") == raw.getInt("bottom") - row.top
            && raw.getInt("height") <= 2L * row.height() && (long) raw.getInt("width") * raw.getInt("height") <= 4194304
            && intersection.length() == 6 && intersection.getInt("left") == row.left && intersection.getInt("top") == row.top
            && intersection.getInt("right") == row.right && intersection.getInt("bottom") == row.bottom
            && intersection.getInt("width") == row.width() && intersection.getInt("height") == row.height();
        HashSet<Integer> actionIds = new HashSet<>(); JSONArray actions = observed.getJSONArray("actions");
        for (int i = 0; i < actions.length(); i++) {
            JSONObject action = actions.getJSONObject(i); int id = action.getInt("id");
            // These are observations only. SELECT/CLEAR_SELECTION and the other IDs are never executed here.
            allowed &= (id == AccessibilityNodeInfo.ACTION_SELECT || id == AccessibilityNodeInfo.ACTION_CLEAR_SELECTION
                || id == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS || id == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                || id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId())
                && actionIds.add(id) && emptyActual(action.get("label"));
        }
        return allowed && actions.length() <= 5;
    }
    /** Exactly the normal/index2/ordinal1 and dimmed/index3/ordinal2 API36 roles.
     * All properties are freshly observed; the original rectangle is never replaced by its intersection.
     * A failed candidate remains in partial diagnostic AX, which is not a valid success schema.
     */
    private static DecorativeClip backgroundClip(AccessibilityNodeInfo node, Rect raw, Rect row, Rect activeBounds,
            int index, int parent, int depth, int ordinal, int window, int[] characters, JSONObject hierarchy) throws Exception {
        if (Build.VERSION.SDK_INT != 36 || !AX_V2.equals(hierarchy.getString("format")) || backgroundIndex(node.getViewIdResourceName()) < 0) return null;
        JSONObject observed = actualNode(node, raw, characters).put("index", index).put("parent", parent).put("depth", depth).put("childOrdinal", ordinal);
        JSONObject facts = new JSONObject(), semanticProperties = new JSONObject();
        Rect intersection = new Rect(raw); boolean intersects = intersection.intersect(row);
        hierarchy.put("rejectedDecorativeClip", new JSONObject().put("node", observed).put("actualProperties", facts)
            .put("semanticProperties", semanticProperties).put("intersection", intersects ? box(intersection) : JSONObject.NULL).put("eligible", false));
        facts.put("longClickable", node.isLongClickable()).put("contextClickable", node.isContextClickable()).put("focusable", node.isFocusable())
            .put("editable", node.isEditable()).put("scrollable", node.isScrollable()).put("checkable", node.isCheckable());
        readDecorativeSemantics(node, characters, semanticProperties);
        if (!intersects || !intersection.equals(row) || !activeBounds.contains(row) || !activeBounds.contains(raw)) return null;
        JSONObject clip = new JSONObject().put("nodeIndex", index).put("policy", CLIP_POLICY).put("cropIntersection", box(intersection))
            .put("longClickable", facts.getBoolean("longClickable")).put("contextClickable", facts.getBoolean("contextClickable")).put("focusable", facts.getBoolean("focusable"))
            .put("editable", facts.getBoolean("editable")).put("scrollable", facts.getBoolean("scrollable")).put("checkable", facts.getBoolean("checkable"))
            .put("semanticProperties", semanticProperties);
        if (!qualifiedBackground(observed, clip, row, window)) return null;
        return new DecorativeClip(observed, clip);
    }
    /** Recheck the complete family envelope at every fingerprint and actual source-frame gate.
     * Each clip carries its own fresh facts; no defaults or geometry are borrowed from its sibling.
     */
    private static void decorativeFamily(JSONObject hierarchy) throws Exception {
        check(AX_V2.equals(hierarchy.getString("format")) && Build.VERSION.SDK_INT == 36
            && hierarchy.getInt("api") == 36 && CLIP_POLICY.equals(hierarchy.getString("clipPolicy")), "closed API36 family policy");
        JSONArray clips = hierarchy.getJSONArray("decorativeClips"), nodes = hierarchy.getJSONArray("nodes");
        check(clips.length() <= 2 && nodes.length() > 0, "at most two exact background roles");
        JSONObject root = nodes.getJSONObject(0), bounds = root.getJSONObject("boundsInScreen");
        check(SYSTEM.equals(root.getString("package")) && ROW.equals(root.getString("resourceId")), "same owned SystemUI row for family");
        Rect row = new Rect(bounds.getInt("left"), bounds.getInt("top"), bounds.getInt("right"), bounds.getInt("bottom"));
        HashSet<Integer> indices = new HashSet<>(); HashSet<String> roles = new HashSet<>(); int previous = 0;
        for (int i = 0; i < clips.length(); i++) {
            JSONObject clip = clips.getJSONObject(i); int index = clip.getInt("nodeIndex");
            check(index > previous && index < nodes.length() && indices.add(index), "unique ascending background clip indices");
            JSONObject observed = nodes.getJSONObject(index);
            check(roles.add(observed.getString("resourceId")) && qualifiedBackground(observed, clip, row, root.getInt("windowId")),
                "unique exact background role with all own default facts and geometry");
            previous = index;
        }
    }
    private static String rowFingerprint(JSONObject hierarchy) throws Exception {
        if (AX_V1.equals(hierarchy.getString("format"))) return sha(hierarchy.getJSONArray("nodes").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        decorativeFamily(hierarchy);
        return sha(new JSONObject().put("format", AX_V2).put("api", 36).put("clipPolicy", CLIP_POLICY)
            .put("nodes", hierarchy.getJSONArray("nodes")).put("decorativeClips", hierarchy.getJSONArray("decorativeClips"))
            .toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static void decorativeSourceFrame(JSONObject hierarchy, int width, int height) throws Exception {
        if (!AX_V2.equals(hierarchy.getString("format"))) return;
        decorativeFamily(hierarchy);
        JSONArray clips = hierarchy.getJSONArray("decorativeClips"), nodes = hierarchy.getJSONArray("nodes");
        for (int i = 0; i < clips.length(); i++) {
            JSONObject raw = nodes.getJSONObject(clips.getJSONObject(i).getInt("nodeIndex")).getJSONObject("boundsInScreen");
            check(raw.getInt("left") >= 0 && raw.getInt("top") >= 0 && raw.getInt("right") <= width && raw.getInt("bottom") <= height,
                "original decorative AX rectangle stays inside actual screenshot source");
        }
    }
    /** Failure-only getter observations. This object is never a clip or a successful AX node.
     * Attach each field before the next getter so unavailable facts remain absent, not defaults.
     */
    private static void rejectedNodeObservation(AccessibilityNodeInfo node, Rect raw, int[] characters, JSONObject observed,
            JSONObject facts, JSONObject semanticProperties) throws Exception {
        observed.put("package", actual(node.getPackageName(), characters)).put("resourceId", actual(node.getViewIdResourceName(), characters))
            .put("class", actual(node.getClassName(), characters)).put("text", actual(node.getText(), characters))
            .put("contentDescription", actual(node.getContentDescription(), characters)).put("boundsInScreen", box(raw))
            .put("windowId", node.getWindowId()).put("visible", node.isVisibleToUser()).put("clickable", node.isClickable())
            .put("enabled", node.isEnabled()).put("childCount", node.getChildCount());
        JSONArray actions = new JSONArray(); observed.put("actions", actions);
        List<AccessibilityNodeInfo.AccessibilityAction> actualActions = node.getActionList();
        check(actualActions != null && actualActions.size() <= 32, "failure-only owned row action cap");
        for (AccessibilityNodeInfo.AccessibilityAction action : actualActions) {
            JSONObject value = new JSONObject(); actions.put(value);
            value.put("id", action.getId()).put("label", actual(action.getLabel(), characters));
        }
        facts.put("longClickable", node.isLongClickable()).put("contextClickable", node.isContextClickable()).put("focusable", node.isFocusable())
            .put("editable", node.isEditable()).put("scrollable", node.isScrollable()).put("checkable", node.isCheckable());
        readDecorativeSemantics(node, characters, semanticProperties);
    }
    /** Observe at most the rejected node and its immediately following direct row child.
     * No new selection, class/ID qualification, child traversal, ancestor or notification-sibling serialization or action.
     * The second node is a sibling of the rejected node INSIDE the selected owned row, not a
     * sibling notification row. Both references already belong to readRow's bounded queue.
     * Regardless of diagnostics, the original rejection is thrown and no screenshot is reached.
     */
    private static void rejectedOutsideDiagnostics(List<AccessibilityNodeInfo> queue, List<Integer> parents, List<Integer> depths,
            List<Integer> ordinals, int first, Rect rejectedRaw, Rect row, Rect activeBounds, int window, int[] characters,
            JSONObject hierarchy, AssertionError originalFailure) throws Exception {
        if (Build.VERSION.SDK_INT != 36) return;
        JSONArray samples = new JSONArray();
        JSONObject diagnostic = new JSONObject().put("format", "android-owned-row-rejected-node-diagnostic-v1")
            .put("purpose", "failure-only own getter observations; no clip eligibility or success evidence")
            .put("maxNodes", 2).put("descendantsTraversed", false).put("eligible", false)
            .put("snapshotClaim", "non-atomic getter observations; no screenshot or layout-stability claim")
            .put("complete", false).put("firstRejectedBoundsInScreen", box(rejectedRaw)).put("samples", samples);
        hierarchy.put("rejectedOutsideDiagnostics", diagnostic);
        int last = first;
        if (parents.get(first) == 0 && depths.get(first) == 1 && first + 1 < queue.size()
                && parents.get(first + 1) == 0 && depths.get(first + 1) == 1
                && ordinals.get(first + 1) == ordinals.get(first) + 1) last = first + 1;
        diagnostic.put("nextDirectChild", last > first ? "queued immediate next ordinal only" : "not available; no search or inference");
        boolean complete = true;
        for (int index = first; index <= last; index++) {
            JSONObject observed = new JSONObject(), facts = new JSONObject(), semanticProperties = new JSONObject();
            JSONObject sample = new JSONObject().put("role", index == first ? "first-rejected-descendant" : "next-direct-row-child")
                .put("index", index).put("parent", parents.get(index)).put("depth", depths.get(index)).put("childOrdinal", ordinals.get(index))
                .put("observed", observed).put("actualProperties", facts).put("semanticProperties", semanticProperties)
                .put("complete", false).put("beginElapsed", SystemClock.elapsedRealtime());
            samples.put(sample);
            try {
                AccessibilityNodeInfo node = queue.get(index);
                // The first node was refreshed by the failed ordinary read. Refresh only the
                // not-yet-visited second reference; never reselect or obtain a different node.
                check((index == first || node.refresh()) && node.getWindowId() == window && SYSTEM.contentEquals(node.getPackageName())
                    && !ROW.equals(node.getViewIdResourceName()), "failure-only same owned row/window node");
                // Compare the current official parent reference only; never serialize its data.
                AccessibilityNodeInfo actualParent = node.getParent();
                try {
                    check(parents.get(index) >= 0 && actualParent != null && actualParent.equals(queue.get(parents.get(index))),
                        "failure-only original owned parent identity");
                    sample.put("originalParentMatched", true);
                } finally {
                    boolean alreadyHeld = false;
                    for (AccessibilityNodeInfo held : queue) if (actualParent == held) alreadyHeld = true;
                    if (actualParent != null && !alreadyHeld) actualParent.recycle();
                }
                Rect raw = new Rect(); node.getBoundsInScreen(raw); Rect intersection = new Rect(raw);
                sample.put("rawBoundsInScreen", box(raw)).put("insideSelectedRow", row.contains(raw))
                    .put("insideObservedActiveWindow", activeBounds.contains(raw))
                    .put("cropIntersection", intersection.intersect(row) ? box(intersection) : JSONObject.NULL);
                rejectedNodeObservation(node, raw, characters, observed, facts, semanticProperties);
                sample.put("complete", true);
            } catch (Throwable diagnosticFailure) {
                complete = false; sample.put("failureType", type(diagnosticFailure));
                try { originalFailure.addSuppressed(diagnosticFailure); } catch (Throwable ignored) { /* preserve original rejection */ }
            } finally { sample.put("endElapsed", SystemClock.elapsedRealtime()); }
        }
        diagnostic.put("complete", complete);
    }
    /** Only the selected row and its descendants. No SystemUI root/sibling/ancestor serialization. */
    private static RowSnapshot readRow(Instrumentation owner, AccessibilityNodeInfo selected, JSONObject hierarchy) throws Exception {
        if (Build.VERSION.SDK_INT >= 34) owner.getUiAutomation().clearCache();
        check(selected.refresh() && SYSTEM.contentEquals(selected.getPackageName()) && ROW.equals(selected.getViewIdResourceName()), "same actual SystemUI row required");
        int window = selected.getWindowId(); Rect bounds = new Rect(); selected.getBoundsInScreen(bounds);
        check(!bounds.isEmpty() && selected.isVisibleToUser() && selected.isClickable() && selected.isEnabled()
            && selected.getActionList().stream().anyMatch(a -> a.getId() == AccessibilityNodeInfo.ACTION_CLICK), "actual selected row click contract");
        AccessibilityNodeInfo active = owner.getUiAutomation().getRootInActiveWindow(); Rect activeBounds = new Rect();
        try {
            check(active != null && active.getWindowId() == window && SYSTEM.contentEquals(active.getPackageName()) && active.isVisibleToUser(), "selected row remains in active SystemUI window");
            if (Build.VERSION.SDK_INT == 36) {
                // Bounds are read only for admission, never serialized with ancestor/sibling content.
                active.getBoundsInScreen(activeBounds);
                check(!activeBounds.isEmpty() && activeBounds.left >= 0 && activeBounds.top >= 0 && activeBounds.right <= 8192 && activeBounds.bottom <= 8192,
                    "bounded actual active SystemUI window");
            }
        }
        finally { if (active != null && active != selected) active.recycle(); }
        JSONArray nodes = hierarchy.getJSONArray("nodes"); List<AccessibilityNodeInfo> queue = new ArrayList<>();
        List<Integer> parents = new ArrayList<>(), depths = new ArrayList<>(), ordinals = new ArrayList<>();
        queue.add(selected); parents.add(-1); depths.add(0); ordinals.add(-1); int[] characters = {0};
        try {
            for (int index = 0; index < queue.size(); index++) {
                AccessibilityNodeInfo node = queue.get(index); int depth = depths.get(index);
                check(index < 256 && depth <= 16 && (index == 0 || node.refresh()), "bounded fresh owned row AX");
                check(node.getWindowId() == window && SYSTEM.contentEquals(node.getPackageName()), "owned SystemUI subtree only");
                check(index == 0 || !ROW.equals(node.getViewIdResourceName()), "nested notification rows are outside owned row evidence");
                Rect r = new Rect(); node.getBoundsInScreen(r); JSONObject observed = null;
                if (node.isVisibleToUser() && !r.isEmpty() && !bounds.contains(r)) {
                    DecorativeClip decoration = backgroundClip(node, r, bounds, activeBounds, index, parents.get(index), depth, ordinals.get(index), window, characters, hierarchy);
                    if (decoration != null) {
                        JSONArray clips = hierarchy.getJSONArray("decorativeClips");
                        check(clips.length() < 2 && (clips.length() == 0 || clips.getJSONObject(clips.length() - 1).getInt("nodeIndex") < index),
                            "at most two qualified roles in unique ascending index order");
                        observed = decoration.node; clips.put(decoration.clip); hierarchy.remove("rejectedDecorativeClip");
                    } else {
                        AssertionError failure = new AssertionError("visible descendants stay inside selected row crop");
                        try {
                            failure = new AssertionError("visible descendants stay inside selected row crop: index=" + index
                                + " parent=" + parents.get(index) + " depth=" + depth + " childOrdinal=" + ordinals.get(index)
                                + " row=" + bounds.toShortString() + " child=" + r.toShortString()
                                + " id=" + boundedDiagnosticName(node.getViewIdResourceName())
                                + " class=" + boundedDiagnosticName(node.getClassName()));
                        } catch (Throwable diagnosticFailure) {
                            try { failure.addSuppressed(diagnosticFailure); } catch (Throwable ignored) { /* preserve base guard */ }
                        }
                        try { rejectedOutsideDiagnostics(queue, parents, depths, ordinals, index, r, bounds, activeBounds, window, characters, hierarchy, failure); }
                        catch (Throwable diagnosticFailure) {
                            try { failure.addSuppressed(diagnosticFailure); } catch (Throwable ignored) { /* preserve original rejection */ }
                        }
                        throw failure;
                    }
                }
                int children = node.getChildCount(); check(children >= 0 && children <= 64 && (children == 0 || depth < 16), "owned row AX child/depth cap");
                if (observed == null) observed = actualNode(node, r, characters);
                if (Build.VERSION.SDK_INT == 36) check(observed.getInt("childCount") == children, "actual child count stable during node read");
                nodes.put(observed.put("index", index).put("parent", parents.get(index))
                    .put("depth", depth).put("childOrdinal", ordinals.get(index)));
                for (int child = 0; child < children; child++) {
                    check(queue.size() < 256, "owned row AX node cap"); AccessibilityNodeInfo next = node.getChild(child);
                    check(next != null, "missing actual owned row AX child");
                    if (queue.contains(next)) { if (next != selected) next.recycle(); throw new AssertionError("repeated owned row AX node"); }
                    queue.add(next); parents.add(index); depths.add(depth + 1); ordinals.add(child);
                }
            }
            check(selected.refresh() && selected.getWindowId() == window, "selected row lifetime after AX read");
            Rect endBounds = new Rect(); selected.getBoundsInScreen(endBounds);
            JSONObject endRoot = actualNode(selected, endBounds, new int[] {0}).put("index", 0).put("parent", -1).put("depth", 0).put("childOrdinal", -1);
            check(bounds.equals(endBounds) && endRoot.toString().equals(nodes.getJSONObject(0).toString()), "actual selected root changed during AX read");
            return new RowSnapshot(bounds, window, rowFingerprint(hierarchy), new JSONObject(nodes.getJSONObject(0).toString()));
        } finally { for (int n = 1; n < queue.size(); n++) queue.get(n).recycle(); }
    }
    private static JSONObject hierarchy() throws Exception {
        JSONObject value = new JSONObject().put("format", Build.VERSION.SDK_INT == 36 ? AX_V2 : AX_V1).put("scope", "selected owned row subtree only; no ancestors or siblings")
            .put("maxNodes", 256).put("maxDepth", 16).put("maxFieldCharacters", 4096).put("maxTotalCharacters", 32768).put("nodes", new JSONArray());
        if (Build.VERSION.SDK_INT == 36) value.put("api", 36).put("clipPolicy", CLIP_POLICY).put("decorativeClips", new JSONArray());
        return value;
    }
    private static final class PngPrefix extends ByteArrayOutputStream {
        private static final int CAP = 2097152;
        @Override public synchronized void write(int value) { check(count < CAP, "owned row PNG cap"); super.write(value); }
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            int keep = Math.min(length, CAP - count); if (keep > 0) super.write(bytes, offset, keep);
            check(keep == length, "owned row PNG prefix cap");
        }
    }
    /** Publish each reference before I/O; retain any created partial and report actual stored bytes. */
    private static void retainPreClick(File folder, String name, byte[] bytes, int cap, JSONObject artifact) throws Exception {
        artifact.put("relativePath", name).put("capturedBytes", bytes.length).put("capturedSha256", sha(bytes));
        Throwable failure = null; int written = 0; boolean created = false, forced = false, closed = false, directorySynced = false;
        FileChannel channel = null; File file = new File(folder, name);
        try {
            check(name.matches("systemui-preclick-[a-f0-9-]{36}\\.(ax\\.json|png|after-frame-failed\\.ax\\.json|pre-action-failed\\.ax\\.json)") && bytes.length > 0 && bytes.length <= cap, "closed bounded pre-click fixture");
            check(folder.equals(folder.getCanonicalFile()) && Files.isDirectory(folder.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(folder.toPath())
                && ((Number) Files.getAttribute(folder.toPath(), "unix:uid", LinkOption.NOFOLLOW_LINKS)).intValue() == android.os.Process.myUid(), "owned pre-click directory");
            channel = FileChannel.open(file.toPath(), new HashSet<OpenOption>(Arrays.asList(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            created = true; artifact.put("createdByThisCapture", true); ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) { int n = channel.write(buffer); check(n > 0, "pre-click write progress"); written += n; }
            channel.force(true); forced = true;
        } catch (Throwable error) { failure = error; }
        finally { if (channel != null) try { channel.close(); closed = true; } catch (Throwable error) { failure = merge(failure, error); } }
        if (created) {
            try {
                check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath()), "original pre-click file type");
                MessageDigest digest = MessageDigest.getInstance("SHA-256"); int stored = 0;
                try (java.io.InputStream input = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    byte[] chunk = new byte[4096]; int n;
                    while ((n = input.read(chunk)) != -1) { check(n > 0 && stored + n <= cap, "bounded actual pre-click readback"); stored += n; digest.update(chunk, 0, n); }
                }
                StringBuilder hash = new StringBuilder(); for (byte b : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", b));
                artifact.put("storedBytes", stored).put("size", stored).put("sha256", hash.toString());
                check(stored == bytes.length && sha(bytes).equals(hash.toString()), "actual original pre-click bytes/hash match capture");
            } catch (Throwable error) { failure = merge(failure, error); }
            java.io.FileDescriptor fd = null;
            try { fd = android.system.Os.open(folder.getPath(), android.system.OsConstants.O_RDONLY | android.system.OsConstants.O_NOFOLLOW, 0); android.system.Os.fsync(fd); directorySynced = true; }
            catch (Throwable error) { failure = merge(failure, error); }
            finally { if (fd != null) try { android.system.Os.close(fd); } catch (Throwable error) { failure = merge(failure, error); } }
        }
        artifact.put("createdByThisCapture", created).put("writtenBytes", written).put("fileFsyncCompleted", forced).put("fileCloseCompleted", closed)
            .put("directoryFsyncCompleted", directorySynced).put("durabilityConfirmed", failure == null).put("state", failure == null ? "saved" : created ? "partial" : "missing")
            .put("saveErrorType", type(failure));
        if (failure == null) artifact.remove("reason");
        if (failure != null) rethrow(failure);
    }
    private static Throwable failedRecheck(File folder, String stem, String role, JSONObject tree, boolean complete, JSONObject record, Throwable failure) {
        try {
            check(Arrays.asList("after-frame-failed", "pre-action-failed").contains(role), "fixed failed recheck role");
            JSONObject artifact = new JSONObject().put("relativePath", stem + "." + role + ".ax.json").put("state", "missing")
                .put("contentComplete", complete).put("scope", "actual failed recheck of original selected owned row only");
            record.put("failedRecheckHierarchy", artifact);
            tree.put("sourceCaptureStem", stem).put("recheckComplete", complete).put("failed", true).put("failureType", type(failure)).put("elapsed", SystemClock.elapsedRealtime());
            retainPreClick(folder, stem + "." + role + ".ax.json", tree.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), 262144, artifact);
        } catch (Throwable error) { failure = merge(failure, error); }
        return failure;
    }
    static final class PreClick implements AutoCloseable {
        private final AccessibilityNodeInfo selected, identity;
        private final RowSnapshot baseline; private final JSONObject record; private final String binding;
        private final File folder; private final String stem;
        private boolean verified;
        private PreClick(AccessibilityNodeInfo selected, AccessibilityNodeInfo identity, RowSnapshot baseline, JSONObject record, JSONObject binding, File folder, String stem) {
            this.selected = selected; this.identity = identity; this.baseline = baseline; this.record = record; this.binding = binding.toString();
            this.folder = folder; this.stem = stem;
        }
        JSONObject intentEvidence() throws Exception { return new JSONObject(record.toString()); }
        void verifyBeforeClick(Instrumentation owner, AccessibilityNodeInfo actualSelected, JSONObject currentBinding) throws Exception {
            JSONObject verification = new JSONObject().put("state", "started").put("elapsed", SystemClock.elapsedRealtime()); record.put("preActionVerification", verification);
            JSONObject current = null; boolean complete = false;
            try {
                check(!verified && actualSelected == selected && selected.equals(identity), "same original selected AX object/source; never reselect");
                check(binding.equals(currentBinding.toString()) && currentBinding.getInt("bootCount") == android.provider.Settings.Global.getInt(owner.getTargetContext().getContentResolver(), android.provider.Settings.Global.BOOT_COUNT), "same original run/writer/boot binding immediately before click");
                current = hierarchy(); RowSnapshot observed = readRow(owner, selected, current); complete = true;
                verification.put("observedWindowId", observed.window).put("observedHierarchySha256", observed.fingerprint).put("actualSelectedNode", observed.selected);
                check(selected.equals(identity) && observed.window == baseline.window && observed.bounds.equals(baseline.bounds) && observed.fingerprint.equals(baseline.fingerprint), "selected node/identity/owned row layout drift; no click or replacement");
                check(currentBinding.getInt("bootCount") == android.provider.Settings.Global.getInt(owner.getTargetContext().getContentResolver(), android.provider.Settings.Global.BOOT_COUNT), "same boot after final selected row check");
                verified = true; verification.put("state", "matched").put("endElapsed", SystemClock.elapsedRealtime()); record.put("state", "verified-before-action").put("clickBlocked", false);
            } catch (Throwable error) {
                record.put("state", "failed-before-action").put("clickBlocked", true); verification.put("state", "failed").put("failureType", type(error));
                if (current != null) error = failedRecheck(folder, stem, "pre-action-failed", current, complete, verification, error);
                else verification.put("recheckAxState", "missing; identity/boot gate failed before AX read");
                rethrow(error);
            }
        }
        @Override public void close() { identity.recycle(); }
    }
    /** Publication values bind the attempt only; every AX field comes from the selected SDK node. */
    static JSONObject preClickBinding(String lease, String attempt, String publicationSha256, JSONObject writerLive) throws Exception {
        check(UUID.fromString(lease).toString().equals(lease) && UUID.fromString(attempt).toString().equals(attempt)
            && publicationSha256.matches("[a-f0-9]{64}"), "original pre-click run identity");
        return new JSONObject().put("lease", lease).put("attempt", attempt).put("publicationSha256", publicationSha256)
            .put("bootCount", writerLive.getInt("bootCount")).put("bootId", writerLive.getString("bootId"))
            .put("bootIdBasis", "joined original independent live writer sample; current BOOT_COUNT checked")
            .put("writerLiveIdentity", new JSONObject(writerLive.toString()));
    }
    /** A separately selected API36 diagnostic lane. Only readRow/getters and original-file I/O.
     * It never calls captureBeforeClick, capture, takeScreenshot or any accessibility action.
     * Even a complete ordinary row read is explicitly incomplete as product acceptance evidence.
     */
    static JSONObject diagnoseRejectedOutsideOnly(Instrumentation owner, File folder, AccessibilityNodeInfo selected,
            JSONObject binding, String diagnosticId, String selector, JSONObject record) throws Exception {
        check(Build.VERSION.SDK_INT == 36 && "first-rejection-next-direct-row-child-v1".equals(selector), "fixed API36 diagnostic selector");
        check(UUID.fromString(diagnosticId).toString().equals(diagnosticId)
            && !diagnosticId.equals(binding.getString("lease")) && !diagnosticId.equals(binding.getString("attempt")), "new distinct diagnostic operation UUID");
        check(binding.getInt("bootCount") == android.provider.Settings.Global.getInt(owner.getTargetContext().getContentResolver(), android.provider.Settings.Global.BOOT_COUNT), "same admitted boot before diagnostic getters");
        JSONObject tree = hierarchy(), artifact = new JSONObject();
        record.put("format", "p9-systemui-rejected-node-diagnostic-result-v1").put("status", "DIAGNOSTIC_ONLY")
            .put("selector", selector).put("diagnosticId", diagnosticId).put("outcome", "REFUSED")
            .put("sameSelectedRowAfter", false).put("sameBootAfter", false).put("screenshotCaptures", 0).put("actionInvocations", 0)
            .put("productPassed", false).put("artifact", artifact);
        long begin = SystemClock.elapsedRealtime(); String outcome = "REFUSED", failureType = "none";
        Rect initial = new Rect(); selected.getBoundsInScreen(initial);
        int window = selected.getWindowId(); boolean sameSelectedRow = false;
        check(!initial.isEmpty() && initial.left >= 0 && initial.top >= 0 && initial.right <= 8192 && initial.bottom <= 8192
            && (long) initial.width() * initial.height() <= 4194304, "bounded diagnostic owned row rectangle");
        AccessibilityNodeInfo identity = new AccessibilityNodeInfo(selected);
        try {
            try {
                readRow(owner, selected, tree);
                outcome = "NO_REJECTION"; // still diagnostic-only: no screenshot/action or product success
            } catch (Throwable refusal) {
                failureType = type(refusal);
                if (tree.has("rejectedOutsideDiagnostics"))
                    outcome = tree.getJSONObject("rejectedOutsideDiagnostics").getBoolean("complete") ? "OBSERVED_REJECTION" : "PARTIAL_REJECTION";
            }
            Rect end = new Rect();
            if (selected.refresh()) {
                selected.getBoundsInScreen(end);
                sameSelectedRow = selected.equals(identity) && selected.getWindowId() == window && initial.equals(end)
                    && SYSTEM.contentEquals(selected.getPackageName()) && ROW.equals(selected.getViewIdResourceName());
            }
            if (!sameSelectedRow) outcome = "UNSTABLE_ROW";
        } catch (Throwable diagnosticFailure) {
            outcome = "REFUSED"; failureType = type(diagnosticFailure);
        } finally { identity.recycle(); }
        boolean sameBoot = false;
        try { sameBoot = binding.getInt("bootCount") == android.provider.Settings.Global.getInt(owner.getTargetContext().getContentResolver(), android.provider.Settings.Global.BOOT_COUNT); }
        catch (Throwable bootFailure) { failureType = type(bootFailure); }
        if (!sameBoot) outcome = "REFUSED";
        record.put("outcome", outcome).put("sameSelectedRowAfter", sameSelectedRow).put("sameBootAfter", sameBoot);
        tree.put("diagnosticOnly", true).put("diagnosticSelector", selector).put("diagnosticId", diagnosticId)
            .put("captureNonce", diagnosticId).put("runBinding", new JSONObject(binding.toString()))
            .put("observerPid", android.os.Process.myPid()).put("observerUid", android.os.Process.myUid())
            .put("beginElapsed", begin).put("endElapsed", SystemClock.elapsedRealtime())
            .put("complete", false).put("stableAcrossScreenshot", false).put("failureStage", "DIAGNOSTIC_ONLY").put("failureType", failureType);
        String name = "systemui-preclick-" + diagnosticId + ".ax.json";
        retainPreClick(folder, name, tree.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), 262144, artifact);
        return record;
    }
    static PreClick captureBeforeClick(Instrumentation owner, File folder, AccessibilityNodeInfo selected, JSONObject binding, JSONObject record) throws Exception {
        String nonce = UUID.randomUUID().toString(), stem = "systemui-preclick-" + nonce;
        JSONObject axFile = new JSONObject().put("relativePath", stem + ".ax.json").put("state", "missing").put("reason", "not saved"),
            pngFile = new JSONObject().put("relativePath", stem + ".png").put("state", "missing").put("reason", "not captured"), tree = hierarchy();
        record.put("format", "p9-systemui-preclick-evidence-v1").put("state", "capturing").put("clickBlocked", true).put("captureNonce", nonce)
            .put("runBinding", new JSONObject(binding.toString())).put("observerPid", android.os.Process.myPid()).put("observerUid", android.os.Process.myUid())
            .put("beginElapsed", SystemClock.elapsedRealtime()).put("api", Build.VERSION.SDK_INT).put("hierarchy", axFile).put("screenshot", pngFile).put("requiresFinalSameNodeVerification", true);
        AccessibilityNodeInfo identity = null; RowSnapshot baseline = null; byte[] png = null; JSONObject afterTree = null; boolean afterComplete = false;
        boolean axComplete = false, pngComplete = false, stable = false; Throwable failure = null; String stage = "initial-actual-AX";
        try {
            check(binding.getInt("bootCount") == android.provider.Settings.Global.getInt(owner.getTargetContext().getContentResolver(), android.provider.Settings.Global.BOOT_COUNT), "same boot at pre-click capture");
            // Public API30 copy constructor, available in both API31-33 and API34+ lanes.
            identity = new AccessibilityNodeInfo(selected); baseline = readRow(owner, selected, tree); axComplete = true;
            record.put("actualSelectedNode", baseline.selected).put("windowId", baseline.window).put("hierarchyFingerprint", baseline.fingerprint);
            stage = "actual-selected-row-PNG"; long frameBeginElapsed = SystemClock.elapsedRealtime();
            Bitmap full = owner.getUiAutomation().takeScreenshot(); long frameEndElapsed = SystemClock.elapsedRealtime(); check(full != null, "pre-click native screenshot unavailable");
            try {
                Rect r = baseline.bounds;
                record.put("source_fullscreen", new JSONObject().put("captureApi", "UiAutomation.takeScreenshot").put("width", full.getWidth()).put("height", full.getHeight())
                    .put("beginElapsed", frameBeginElapsed).put("endElapsed", frameEndElapsed).put("persisted", false).put("storedBytes", 0).put("sha256", JSONObject.NULL)
                    .put("scope", "in-memory source frame only; no full-frame file/hash claim; other notifications never saved"));
                pngFile.put("scope", "actual selected row crop; not fullscreen").put("cropInSourcePixels", box(r));
                check(full.getWidth() > 0 && full.getHeight() > 0 && full.getWidth() <= 8192 && full.getHeight() <= 8192
                    && !r.isEmpty() && r.left >= 0 && r.top >= 0 && r.right <= full.getWidth() && r.bottom <= full.getHeight()
                    && (long) r.width() * r.height() <= 4194304, "actual selected row crop inside bounded source frame");
                decorativeSourceFrame(tree, full.getWidth(), full.getHeight());
                Bitmap crop = Bitmap.createBitmap(full, r.left, r.top, r.width(), r.height()); PngPrefix stream = new PngPrefix();
                try { check(crop.compress(Bitmap.CompressFormat.PNG, 100, stream), "owned pre-click PNG encode"); pngComplete = true; }
                finally { png = stream.toByteArray(); stream.close(); if (crop != full) crop.recycle(); }
            } finally { full.recycle(); }
            stage = "same-row-after-screenshot"; afterTree = hierarchy(); RowSnapshot after = readRow(owner, selected, afterTree); afterComplete = true;
            check(selected.equals(identity) && after.window == baseline.window && after.bounds.equals(baseline.bounds) && after.fingerprint.equals(baseline.fingerprint), "actual selected row changed during screenshot; no click");
            check(binding.getInt("bootCount") == android.provider.Settings.Global.getInt(owner.getTargetContext().getContentResolver(), android.provider.Settings.Global.BOOT_COUNT), "same boot after pre-click capture"); stable = true;
        } catch (Throwable error) { failure = error; }
        tree.put("captureNonce", nonce).put("runBinding", new JSONObject(binding.toString())).put("observerPid", android.os.Process.myPid()).put("observerUid", android.os.Process.myUid())
            .put("beginElapsed", record.getLong("beginElapsed")).put("endElapsed", SystemClock.elapsedRealtime()).put("complete", axComplete)
            .put("stableAcrossScreenshot", stable).put("failureStage", failure == null ? "none" : stage).put("failureType", type(failure));
        axFile.put("axComplete", axComplete).put("contentComplete", axComplete).put("captureState", axComplete ? "full" : tree.getJSONArray("nodes").length() > 0 ? "partial" : "missing")
            .put("scope", "actual selected owned row subtree only");
        pngFile.put("pngEncodeComplete", pngComplete).put("contentComplete", pngComplete).put("captureState", pngComplete ? "full" : png != null && png.length > 0 ? "partial" : "missing");
        if (failure != null && afterTree != null) failure = failedRecheck(folder, stem, "after-frame-failed", afterTree, afterComplete, record, failure);
        // Each save is attempted independently. No failure can discard the other original/prefix.
        try { retainPreClick(folder, stem + ".ax.json", tree.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), 262144, axFile); }
        catch (Throwable error) { if (failure == null) stage = "AX-file-save"; failure = merge(failure, error); }
        if (png != null && png.length > 0) {
            try { retainPreClick(folder, stem + ".png", png, 2097152, pngFile); } catch (Throwable error) { if (failure == null) stage = "PNG-file-save"; failure = merge(failure, error); }
        } else pngFile.put("reason", "PNG bytes unavailable; original capture failed; no second screenshot");
        record.put("endElapsed", SystemClock.elapsedRealtime()).put("axComplete", axComplete).put("pngEncodeComplete", pngComplete).put("stableAcrossScreenshot", stable)
            .put("state", failure == null && axComplete && pngComplete && stable ? "captured" : "failed").put("failureStage", failure == null ? "none" : stage).put("failureType", type(failure));
        if (failure != null || !axComplete || !pngComplete || !stable) {
            if (identity != null) identity.recycle(); if (failure != null) rethrow(failure); throw new AssertionError("missing complete original pre-click evidence; no click");
        }
        return new PreClick(selected, identity, baseline, record, binding, folder, stem);
    }
    static JSONObject capture(Instrumentation owner, File folder, String lease, Scene scene) throws Exception {
        check(UUID.fromString(lease).toString().equals(lease), "canonical synthetic lease");
        String role = scene == Scene.FIRST_MAIN ? "first-main" : "later-draft";
        String draft = (scene == Scene.FIRST_MAIN ? "Owned cold original draft " : "Owned natural OS new draft ") + lease;
        if (Build.VERSION.SDK_INT >= 34) owner.getUiAutomation().clearCache();
        AccessibilityNodeInfo root = owner.getUiAutomation().getRootInActiveWindow();
        check(root != null && APP.contentEquals(root.getPackageName()) && root.isVisibleToUser(), "exact owned App active window");
        int window = root.getWindowId(), matchingEditors = 0;
        List<AccessibilityNodeInfo> queue = new ArrayList<>(); List<Integer> parents = new ArrayList<>();
        queue.add(root); parents.add(-1); JSONArray tree = new JSONArray(); Rect bounds = new Rect(); root.getBoundsInScreen(bounds);
        try {
            for (int index = 0; index < queue.size(); index++) {
                check(queue.size() <= 512, "bounded actual UI hierarchy"); AccessibilityNodeInfo node = queue.get(index); Rect r = new Rect(); node.getBoundsInScreen(r);
                if (node.isVisibleToUser() && node.isEditable() && draft.contentEquals(node.getText() == null ? "" : node.getText())) matchingEditors++;
                tree.put(new JSONObject().put("index", index).put("parent", parents.get(index)).put("package", text(node.getPackageName()))
                    .put("class", text(node.getClassName())).put("resourceId", node.getViewIdResourceName()).put("text", text(node.getText()))
                    .put("description", text(node.getContentDescription())).put("bounds", r.flattenToString()).put("visible", node.isVisibleToUser())
                    .put("editable", node.isEditable()).put("enabled", node.isEnabled()).put("clickable", node.isClickable()));
                for (int child = 0; child < node.getChildCount(); child++) {
                    AccessibilityNodeInfo next = node.getChild(child); if (next != null) { check(queue.size() < 512, "UI node cap"); queue.add(next); parents.add(index); }
                }
            }
            check(matchingEditors == 1 && root.refresh() && APP.contentEquals(root.getPackageName()), "one exact synthetic draft editor");
            Bitmap full = owner.getUiAutomation().takeScreenshot(); check(full != null, "native screenshot required"); byte[] png;
            try {
                check(!bounds.isEmpty() && bounds.left >= 0 && bounds.top >= 0 && bounds.right <= full.getWidth() && bounds.bottom <= full.getHeight(), "owned window crop bounds");
                Bitmap crop = Bitmap.createBitmap(full, bounds.left, bounds.top, bounds.width(), bounds.height());
                try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) { check(crop.compress(Bitmap.CompressFormat.PNG, 100, stream), "PNG encode"); png = stream.toByteArray(); }
                finally { if (crop != full) crop.recycle(); }
            } finally { full.recycle(); }
            check(root.refresh() && root.getWindowId() == window && APP.contentEquals(root.getPackageName()), "same owned window after screenshot");
            JSONObject hierarchy = new JSONObject().put("format", "android-accessibility-tree-v1").put("lease", lease).put("scene", role)
                .put("package", APP).put("windowId", window).put("elapsed", SystemClock.elapsedRealtime()).put("bounds", bounds.flattenToString())
                .put("matchingEditors", matchingEditors).put("nodes", tree);
            byte[] json = hierarchy.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            original(folder, role + ".json", json, 262144); original(folder, role + ".png", png, 2097152);
            return new JSONObject().put("hierarchyFile", role + ".json").put("hierarchySha256", sha(json)).put("hierarchyBytes", json.length)
                .put("screenshotFile", role + ".png").put("screenshotSha256", sha(png)).put("screenshotBytes", png.length).put("nodeCount", tree.length());
        } finally { for (AccessibilityNodeInfo node : queue) node.recycle(); }
    }
    private static byte[] proc(Instrumentation owner, File folder, JSONObject diagnostics, NativeRweProcessCapture.IdentityRole role,
                               NativeRweProcessCapture.ProcFile file, int pid, long deadline) throws Exception {
        NativeRweProcessCapture capture = new NativeRweProcessCapture(owner.getUiAutomation(), folder);
        try { return capture.proc(role, file, pid, deadline); }
        finally { synchronized (diagnostics) { diagnostics.getJSONObject(role.name()).put(file.name(), capture.evidence()); } }
    }
    private static String procText(byte[] bytes) throws Exception {
        String s = NaturalProcessVisibility.utf8(bytes);
        check(s.length() > 0 && s.indexOf('\0') < 0 && s.indexOf('\r') < 0 && s.endsWith("\n"), "complete fixed proc text");
        return s;
    }
    private static long startTicks(byte[] raw, int pid) throws Exception {
        String stat = procText(raw); int boundary = stat.lastIndexOf(") ");
        check(stat.startsWith(pid + " (") && boundary > 0, "exact proc stat PID and final comm boundary");
        String[] fields = stat.substring(boundary + 2).trim().split("\\s+");
        check(fields.length >= 20 && fields.length <= 64 && fields[0].length() == 1, "complete proc stat fields");
        long ticks = Long.parseLong(fields[19]); check(ticks > 0, "positive actual stat field22"); return ticks;
    }
    static JSONObject liveIdentity(Instrumentation owner, File folder, JSONObject diagnostics, NativeRweProcessCapture.IdentityRole role,
                                   int pid, int uid, String expectedNonce, int expectedBootCount) throws Exception {
        check(pid > 1 && pid <= 4194304 && uid > 0 && UUID.fromString(expectedNonce).toString().equals(expectedNonce), "canonical expected live identity");
        long begin = SystemClock.elapsedRealtime(), deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        synchronized (diagnostics) { check(!diagnostics.has(role.name()), "one original live identity sample per role"); diagnostics.put(role.name(), new JSONObject()); }
        int bootBefore = android.provider.Settings.Global.getInt(owner.getTargetContext().getContentResolver(), android.provider.Settings.Global.BOOT_COUNT);
        check(bootBefore == expectedBootCount, "same expected boot before live proc sample");
        long ticks = startTicks(proc(owner, folder, diagnostics, role, NativeRweProcessCapture.ProcFile.STAT_BEFORE, pid, deadline), pid);
        String status = procText(proc(owner, folder, diagnostics, role, NativeRweProcessCapture.ProcFile.UID_STATUS, pid, deadline));
        java.util.regex.Matcher uids = java.util.regex.Pattern.compile("(?m)^Uid:[\\t ]+([0-9]+)[\\t ]+([0-9]+)[\\t ]+([0-9]+)[\\t ]+([0-9]+)[\\t ]*$").matcher(status);
        check(uids.find(), "independent live proc UID line required");
        for (int n = 1; n <= 4; n++) check(Integer.parseInt(uids.group(n)) == uid, "exact live real/effective/saved/fs UID");
        check(!uids.find(), "unique proc UID line");
        java.util.regex.Matcher pids = java.util.regex.Pattern.compile("(?m)^Pid:[\\t ]+([0-9]+)[\\t ]*$").matcher(status);
        check(pids.find() && Integer.parseInt(pids.group(1)) == pid && !pids.find(), "same exact proc status PID");
        byte[] cmdline = proc(owner, folder, diagnostics, role, NativeRweProcessCapture.ProcFile.CMDLINE, pid, deadline);
        byte[] expected = (APP + "\0").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        check(cmdline.length >= expected.length && cmdline.length <= 4096, "bounded exact main-process cmdline");
        for (int n = 0; n < cmdline.length; n++) check(cmdline[n] == (n < expected.length ? expected[n] : 0), "independent exact main-process cmdline");
        String bootId = procText(proc(owner, folder, diagnostics, role, NativeRweProcessCapture.ProcFile.BOOT_ID, pid, deadline)).trim();
        check(UUID.fromString(bootId).toString().equals(bootId), "canonical live kernel boot ID");
        check(startTicks(proc(owner, folder, diagnostics, role, NativeRweProcessCapture.ProcFile.STAT_AFTER, pid, deadline), pid) == ticks, "same live PID lifetime around UID/cmdline/boot reads");
        check(android.provider.Settings.Global.getInt(owner.getTargetContext().getContentResolver(), android.provider.Settings.Global.BOOT_COUNT) == bootBefore, "same boot after live proc sample");
        JSONObject sample = new JSONObject().put("pid", pid).put("uid", uid).put("startTicks", ticks).put("expectedNonce", expectedNonce)
            .put("bootId", bootId).put("bootCount", bootBefore).put("cmdline", APP).put("sampleBeginElapsed", begin)
            .put("sampleEndElapsed", SystemClock.elapsedRealtime()).put("sameStatBeforeAfter", true).put("independentFixedProcRead", true);
        String name = role == NativeRweProcessCapture.IdentityRole.WRITER ? "writer-identity.json" : role == NativeRweProcessCapture.IdentityRole.FIRST_CONSUMER ? "first-consumer-identity.json" : "later-consumer-identity.json";
        byte[] details;
        synchronized (diagnostics) { details = diagnostics.getJSONObject(role.name()).put("sample", sample).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8); }
        original(folder, name, details, 32768);
        JSONObject savedSample = new JSONObject(sample.toString()).put("diagnosticsFile", name).put("diagnosticsSha256", sha(details)).put("diagnosticsBytes", details.length);
        synchronized (diagnostics) { diagnostics.put(role.name(), new JSONObject(savedSample.toString())); }
        return savedSample;
    }
    static void matchProof(JSONObject proof, JSONObject live, String expectedNonce) throws Exception {
        check(proof.getInt("pid") == live.getInt("pid") && proof.getLong("startTicks") == live.getLong("startTicks")
            && expectedNonce.equals(proof.getString("nonce")) && expectedNonce.equals(live.getString("expectedNonce"))
            && proof.getString("bootId").equals(live.getString("bootId")) && proof.getInt("bootCount") == live.getInt("bootCount"),
            "proof nonce/PID/startTicks/boot must match independent same-lifetime UID/cmdline sample");
    }
}
