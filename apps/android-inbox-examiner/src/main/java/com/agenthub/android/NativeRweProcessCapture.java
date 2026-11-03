package com.agenthub.android;

import android.app.UiAutomation;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import org.json.JSONObject;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/** Examiner-only capture. PS/AM retain the unchanged closed script. Proc reads are exact readonly
 * /system/bin/cat argv for a validated PID and a closed file enum, never user supplied shell.
 * Five seconds bounds polling/checks, not synchronous Binder launch/stdin/close/fsync calls. An
 * external owned-worker deadline must reject an unreturned call and any unresolved resources. */
final class NativeRweProcessCapture {
    enum IdentityRole { WRITER, FIRST_CONSUMER, LATER_CONSUMER }
    enum ProcFile { STAT_BEFORE, UID_STATUS, CMDLINE, BOOT_ID, STAT_AFTER }
    private static final ReentrantLock OWNER = new ReentrantLock();
    private static volatile boolean unresolved;
    private final UiAutomation automation;
    private final File folder;
    private final String nonce = UUID.randomUUID().toString();
    private final Stream out = new Stream(), err = new Stream();
    private JSONObject record = new JSONObject();
    private int cap, retained;
    private String role;
    private boolean totalOverflow;
    private static final class Stream {
        final ByteArrayOutputStream prefix = new ByteArrayOutputStream();
        long observed;
        boolean expected, started, eof, overflow;
        Throwable readError, closeError;
    }
    private static final class Snapshot {
        final byte[] bytes; final long observed;
        final boolean expected, started, eof, overflow;
        final Throwable readError, closeError;
        Snapshot(Stream s) {
            bytes = s.prefix.toByteArray(); observed = s.observed; expected = s.expected;
            started = s.started; eof = s.eof; overflow = s.overflow;
            readError = s.readError; closeError = s.closeError;
        }
    }
    NativeRweProcessCapture(UiAutomation automation, File folder) { this.automation = automation; this.folder = folder; }
    JSONObject evidence() { return record; }
    private static void check(boolean value, String code) { NaturalProcessVisibility.require(value, code); }
    private static Throwable merge(Throwable first, Throwable later) {
        if (first == null) return later;
        if (later != null && later != first) first.addSuppressed(later);
        return first;
    }
    private static void rethrow(Throwable error) throws Exception {
        if (error instanceof Exception) throw (Exception) error;
        if (error instanceof Error) throw (Error) error;
        throw new AssertionError(error);
    }
    private static String type(Throwable error) { return error == null ? "none" : error.getClass().getName(); }
    private static String sha(byte[] bytes) throws Exception {
        StringBuilder s = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) s.append(String.format(Locale.ROOT, "%02x", b));
        return s.toString();
    }
    private synchronized void bytes(Stream s, byte[] bytes, int n) {
        check(n > 0 && n <= 4096, "fixed read chunk");
        s.observed += n;
        int keep = Math.min(n, cap - retained);
        if (keep > 0) { s.prefix.write(bytes, 0, keep); retained += keep; }
        if (keep != n) { s.overflow = true; totalOverflow = true; }
        check(!totalOverflow, "combined raw prefix cap exceeded");
    }
    private synchronized Snapshot snapshot(Stream s) { return new Snapshot(s); }
    private void drain(ParcelFileDescriptor fd, Stream s) throws Exception {
        InputStream input = null; Throwable failure = null;
        try {
            input = new ParcelFileDescriptor.AutoCloseInputStream(fd);
            synchronized (this) { s.started = true; }
            byte[] bytes = new byte[4096]; int n;
            while ((n = input.read(bytes)) != -1) bytes(s, bytes, n);
            synchronized (this) { s.eof = true; }
        } catch (Throwable error) {
            synchronized (this) { s.readError = error; }
            failure = error;
        } finally {
            if (input != null) try { input.close(); } catch (Throwable error) {
                synchronized (this) { s.closeError = error; }
                failure = merge(failure, error);
            }
        }
        if (failure != null) rethrow(failure);
    }
    private JSONObject persistOne(String streamRole, Snapshot s) throws Exception {
        JSONObject v = new JSONObject().put("expected", s.expected).put("readerStarted", s.started)
            .put("available", s.started).put("captureState", !s.started ? "not-started" : s.eof && !s.overflow && s.observed == s.bytes.length && s.readError == null ? "full" : "partial")
            .put("capturedLength", s.bytes.length).put("bytesObservedLowerBound", s.observed)
            .put("byteCountIsLowerBound", !s.eof).put("eof", s.eof).put("overflow", s.overflow)
            .put("completeBytes", s.expected && s.eof && !s.overflow && s.observed == s.bytes.length && s.readError == null)
            .put("readErrorType", type(s.readError)).put("readCloseErrorType", type(s.closeError))
            .put("saved", false).put("durabilityConfirmed", false).put("writtenLength", 0)
            .put("unsavedCapturedLength", s.bytes.length);
        record.getJSONObject("rawEvidence").put(streamRole, v); // Published before any I/O, independently per stream.
        if (!s.expected) return v;
        String name = "diagnostic-" + role + "-" + nonce + "-" + streamRole + ".bin";
        v.put("plannedFile", name).put("capturedPrefixSha256", sha(s.bytes));
        FileChannel channel = null; Throwable failure = null; int written = 0; boolean created = false, forced = false, closed = false;
        try {
            check(folder.equals(folder.getCanonicalFile()) && Files.isDirectory(folder.toPath(), LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(folder.toPath())
                && ((Number) Files.getAttribute(folder.toPath(), "unix:uid", LinkOption.NOFOLLOW_LINKS)).intValue() == android.os.Process.myUid(), "owned raw directory");
            channel = FileChannel.open(new File(folder, name).toPath(), new HashSet<OpenOption>(Arrays.asList(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            created = true; v.put("file", name).put("createdByThisCapture", true);
            ByteBuffer buffer = ByteBuffer.wrap(s.bytes);
            while (buffer.hasRemaining()) { int n = channel.write(buffer); check(n > 0, "raw write progress"); written += n; }
            channel.force(true); forced = true;
        } catch (Throwable error) { failure = error; }
        finally {
            if (channel != null) try { channel.close(); closed = true; } catch (Throwable error) { failure = merge(failure, error); }
            v.put("createdByThisCapture", created).put("writtenLength", written).put("fileFsyncCompleted", forced).put("fileCloseCompleted", closed);
        }
        if (failure == null) {
            java.io.FileDescriptor fd = null;
            try {
                fd = android.system.Os.open(folder.getPath(), android.system.OsConstants.O_RDONLY | android.system.OsConstants.O_NOFOLLOW, 0);
                android.system.Os.fsync(fd); v.put("directoryFsyncCompleted", true);
            } catch (Throwable error) { failure = error; }
            finally { if (fd != null) try { android.system.Os.close(fd); } catch (Throwable error) { failure = merge(failure, error); } }
        }
        if (failure != null) { v.put("saveErrorType", type(failure)); rethrow(failure); }
        return v.put("saved", true).put("durabilityConfirmed", true).put("savedLength", s.bytes.length)
            .put("sha256", sha(s.bytes)).put("unsavedCapturedLength", 0);
    }
    NaturalProcessVisibility.Completed capture(NaturalProcessVisibility.Diagnostic kind) throws Exception {
        check(kind != null, "closed PS/AM kind");
        return (NaturalProcessVisibility.Completed) run(kind, null, 0, null, Long.MAX_VALUE);
    }
    byte[] proc(IdentityRole identity, ProcFile file, int pid, long deadline) throws Exception {
        check(identity != null && file != null && pid > 1 && pid <= 4194304, "exact validated live PID and fixed proc role");
        return (byte[]) run(null, file, pid, identity, deadline);
    }
    private Object run(NaturalProcessVisibility.Diagnostic kind, ProcFile proc, int pid, IdentityRole identity, long deadline) throws Exception {
        final boolean rwe = kind != null;
        cap = rwe ? NaturalProcessVisibility.budget(kind) : 16384;
        role = rwe ? kind.name() : "PROC-" + identity.name() + "-" + proc.name();
        out.expected = true; err.expected = rwe;
        record = new JSONObject().put("kind", role).put("commandNonce", nonce).put("capBytes", cap)
            .put("rawEvidence", new JSONObject()).put("processWaitAvailable", false)
            .put("deadlineScope", "poll/check; synchronous launch/stdin/close/fsync are not hard wall-clock bounded")
            .put("completionBasis", rwe ? "both EOF plus unique fixed-script exit marker" : "fixed cat stdout EOF; no exit-status claim");
        long workEnd = Math.min(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
        ParcelFileDescriptor[] io = null; ExecutorService workers = null;
        List<Future<Integer>> futures = new ArrayList<>(); Throwable failure = null, cleanup = null;
        Object value = null; boolean locked = false, independentStderr = false, commandVerified = false, drainsStopped = true;
        boolean restoreInterrupt = false; int descriptorsClosed = 0;
        try {
            check(Build.VERSION.SDK_INT >= (rwe ? 34 : 31), "official API lane");
            check(!unresolved, "prior descriptor/drain cleanup unresolved; capture sealed");
            long left = workEnd - System.nanoTime(); check(left > 0, "capture lock deadline");
            locked = OWNER.tryLock(left, TimeUnit.NANOSECONDS); check(locked && !unresolved, "capture ownership or prior cleanup unresolved");
            workers = Executors.newFixedThreadPool(rwe ? 2 : 1, runnable -> { Thread t = new Thread(runnable, "p9-owned-raw-drain"); t.setDaemon(true); return t; });
            CompletionService<Integer> done = new ExecutorCompletionService<>(workers);
            if (rwe) {
                io = automation.executeShellCommandRwe("/system/bin/sh");
                check(io != null && io.length == 3 && io[0] != null && io[1] != null && io[2] != null, "independent RWE descriptors required");
                independentStderr = true;
            } else {
                String path;
                switch (proc) {
                    case STAT_BEFORE: case STAT_AFTER: path = "/proc/" + pid + "/stat"; break;
                    case UID_STATUS: path = "/proc/" + pid + "/status"; break;
                    case CMDLINE: path = "/proc/" + pid + "/cmdline"; break;
                    case BOOT_ID: path = "/proc/sys/kernel/random/boot_id"; break;
                    default: throw new IllegalArgumentException("closed proc file");
                }
                io = new ParcelFileDescriptor[] { automation.executeShellCommand("/system/bin/cat " + path) };
                check(io[0] != null, "fixed proc stdout required");
            }
            final ParcelFileDescriptor[] streams = io;
            futures.add(done.submit(() -> { drain(streams[0], out); return 0; }));
            if (rwe) {
                futures.add(done.submit(() -> { drain(streams[2], err); return 1; }));
                // Keep the original PS/AM script and its allowlist unchanged.
                try (OutputStream input = new ParcelFileDescriptor.AutoCloseOutputStream(io[1])) { input.write(NaturalProcessVisibility.script(kind, nonce)); }
            }
            for (int n = 0; n < (rwe ? 2 : 1); n++) {
                left = workEnd - System.nanoTime(); check(left > 0, "diagnostic poll deadline");
                Future<Integer> completed = done.poll(left, TimeUnit.NANOSECONDS); check(completed != null, "diagnostic drain deadline");
                try { completed.get(); } catch (ExecutionException error) { rethrow(error.getCause()); }
            }
            Snapshot stdout = snapshot(out), stderr = snapshot(err);
            check(stdout.eof && !stdout.overflow && stdout.observed == stdout.bytes.length && (!rwe || stderr.eof && !stderr.overflow && stderr.observed == stderr.bytes.length), "complete bounded actual streams required");
            value = rwe ? NaturalProcessVisibility.complete(kind, nonce, stdout.bytes, stderr.bytes) : stdout.bytes;
            check(System.nanoTime() <= workEnd, "diagnostic completion check deadline"); commandVerified = true;
        } catch (Throwable error) { failure = error; restoreInterrupt = error instanceof InterruptedException; }
        finally {
            // Clear temporarily so an original interruption cannot skip cleanup, raw persistence or the record.
            restoreInterrupt |= Thread.interrupted();
            if (io != null) for (ParcelFileDescriptor fd : io) if (fd != null) try { fd.close(); descriptorsClosed++; } catch (Throwable error) { cleanup = merge(cleanup, error); unresolved = true; }
            for (Future<?> future : futures) try { future.cancel(true); } catch (Throwable error) { cleanup = merge(cleanup, error); }
            if (workers != null) {
                try { workers.shutdownNow(); } catch (Throwable error) { cleanup = merge(cleanup, error); unresolved = true; }
                long cleanupEnd = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!workers.isTerminated()) {
                    long left = cleanupEnd - System.nanoTime(); if (left <= 0) break;
                    try { workers.awaitTermination(left, TimeUnit.NANOSECONDS); }
                    catch (InterruptedException error) { restoreInterrupt = true; cleanup = merge(cleanup, error); }
                    catch (Throwable error) { cleanup = merge(cleanup, error); unresolved = true; break; }
                }
                drainsStopped = workers.isTerminated();
                if (!drainsStopped) { unresolved = true; cleanup = merge(cleanup, new IllegalStateException("owned raw drain unresolved; new captures sealed")); }
            }
            Snapshot stdout = snapshot(out), stderr = snapshot(err);
            // Saving one stream never prevents the other attempt or removes its already-published reference.
            try { persistOne("stdout", stdout); } catch (Throwable error) { cleanup = merge(cleanup, error); }
            try { persistOne("stderr", stderr); } catch (Throwable error) { cleanup = merge(cleanup, error); }
            try {
                record.put("stdoutBytesObserved", stdout.observed).put("stderrBytesObserved", stderr.observed)
                    .put("combinedBytesObservedLowerBound", stdout.observed + stderr.observed).put("combinedCapturedLength", stdout.bytes.length + stderr.bytes.length)
                    .put("stdoutEof", stdout.eof).put("stderrEof", stderr.eof).put("byteCountsAreLowerBounds", !stdout.eof || rwe && !stderr.eof)
                    .put("overflow", totalOverflow).put("independentStderrAvailable", independentStderr).put("descriptorsClosed", descriptorsClosed)
                    .put("drainsStopped", drainsStopped).put("snapshotAfterBoundedCleanup", true).put("unresolvedCaptureSealed", unresolved)
                    .put("failureType", type(failure)).put("cleanupErrorType", type(cleanup)).put("interrupted", restoreInterrupt)
                    .put("commandCompletionVerified", rwe && commandVerified).put("rawReadVerified", commandVerified)
                    .put("commandState", rwe ? commandVerified ? "framed-complete" : "unknown" : "cat-exit-not-claimed")
                    .put("completionVerified", commandVerified && cleanup == null && !unresolved).put("externalWorkerExitGateRequired", true);
            } catch (Throwable error) { cleanup = merge(cleanup, error); }
            finally { if (locked) OWNER.unlock(); if (restoreInterrupt) Thread.currentThread().interrupt(); }
        }
        failure = merge(failure, cleanup);
        if (failure != null) rethrow(failure);
        return value;
    }
}
