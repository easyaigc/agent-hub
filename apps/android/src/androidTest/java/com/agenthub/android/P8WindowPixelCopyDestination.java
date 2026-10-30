package com.agenthub.android;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/**
 * Owns a Window PixelCopy destination until both callback completion and caller release.
 * Pure JDK ownership code: no claim that a timeout, request return or exception cancels PixelCopy.
 */
public final class P8WindowPixelCopyDestination<T> {
    private static final Object LOCK = new Object();
    // Strong references intentionally prevent unknown destinations being finalized/reused early.
    private static final Set<P8WindowPixelCopyDestination<?>> PENDING =
            Collections.newSetFromMap(new IdentityHashMap<P8WindowPixelCopyDestination<?>, Boolean>());

    private T resource;
    private final Object requestContext;
    private final Consumer<? super T> release;
    private boolean dispatchEntered;
    private boolean callbackSeen;
    private boolean callerClosed;
    private boolean borrowed;
    private boolean releaseStarted;
    private boolean released;
    private Throwable releaseFailure;
    private int status;
    private int exitWaiters;

    public P8WindowPixelCopyDestination(T resource, Consumer<? super T> release) {
        this(resource, resource, release);
    }

    public P8WindowPixelCopyDestination(T resource, Object requestContext, Consumer<? super T> release) {
        this.resource = Objects.requireNonNull(resource);
        this.requestContext = Objects.requireNonNull(requestContext);
        this.release = Objects.requireNonNull(release);
    }

    public Object requestContext() {
        return requestContext;
    }

    public static void requireNoPending() {
        synchronized (LOCK) {
            if (!PENDING.isEmpty()) {
                throw new IllegalStateException("Window PixelCopy destination lifetime unresolved; no new input or capture");
            }
        }
    }

    public static int pendingCount() {
        synchronized (LOCK) {
            return PENDING.size();
        }
    }

    /** Invoke immediately before the real request. A subsequent throw is conservatively unknown. */
    public void enterDispatch() {
        synchronized (LOCK) {
            if (dispatchEntered || callerClosed || released || releaseStarted) {
                throw new IllegalStateException("Destination cannot be dispatched again");
            }
            if (!PENDING.isEmpty()) {
                throw new IllegalStateException("Another Window PixelCopy destination is retained");
            }
            dispatchEntered = true;
            PENDING.add(this);
        }
    }

    /** Real completion callback only. It never writes files, probes, inputs, or retries. */
    public void onCompletion(int completionStatus) {
        T discard;
        synchronized (LOCK) {
            if (!dispatchEntered) {
                throw new IllegalStateException("No dispatched request can have completed");
            }
            if (callbackSeen) {
                return; // A duplicate callback cannot replace the first status or dispose twice.
            }
            callbackSeen = true;
            status = completionStatus;
            discard = reserveReleaseLocked();
        }
        releaseOutsideLock(discard);
    }

    public int completedStatus() {
        synchronized (LOCK) {
            if (!callbackSeen || callerClosed || releaseStarted) {
                throw new IllegalStateException("No caller-owned completed status");
            }
            return status;
        }
    }

    /** A borrow, not a transfer: closeCaller releases it only after the caller finishes using it. */
    public T completedResource() {
        synchronized (LOCK) {
            if (!callbackSeen || callerClosed || borrowed || releaseStarted || released) {
                throw new IllegalStateException("No caller-owned completed destination");
            }
            borrowed = true;
            return resource;
        }
    }

    public void closeCaller() {
        T discard;
        synchronized (LOCK) {
            callerClosed = true;
            discard = reserveReleaseLocked();
        }
        releaseOutsideLock(discard);
    }

    private T reserveReleaseLocked() {
        if (!callerClosed || releaseStarted || released || (dispatchEntered && !callbackSeen)) {
            return null;
        }
        releaseStarted = true;
        return resource;
    }

    private void releaseOutsideLock(T discard) {
        if (discard == null) {
            return;
        }
        try {
            release.accept(discard);
        } catch (Throwable failure) {
            synchronized (LOCK) {
                releaseFailure = failure;
                PENDING.add(this); // A failed disposer is also retained, never silently declared released.
                LOCK.notifyAll();
            }
            return; // A late callback must not crash the UI thread; the stored failure remains observable.
        }
        synchronized (LOCK) {
            resource = null;
            released = true;
            PENDING.remove(this);
            LOCK.notifyAll();
        }
    }

    public static final class ExitWait {
        public final boolean dispatched;
        public final boolean waited;
        public final boolean initialInterruptFlag;
        public final long laterInterruptions;
        public final boolean interruptStatusRestored;

        private ExitWait(boolean dispatched, boolean waited, boolean initialInterruptFlag,
                         long laterInterruptions, boolean interruptStatusRestored) {
            this.dispatched = dispatched;
            this.waited = waited;
            this.initialInterruptFlag = initialInterruptFlag;
            this.laterInterruptions = laterInterruptions;
            this.interruptStatusRestored = interruptStatusRestored;
        }
    }

    /**
     * Called inline by capture's failure finally, before returning to JUnit/@After.
     * The caller must be outside the real callback thread (Android Main Looper).
     * There is deliberately no timeout or synthetic completion. Both the exact callback latch
     * and this owner's callback/disposal state must settle; request return alone is insufficient.
     */
    public ExitWait awaitCaptureExit(CountDownLatch exactCallbackDone, Throwable firstFailure,
                                     Thread callbackThread) {
        Objects.requireNonNull(exactCallbackDone);
        Objects.requireNonNull(firstFailure);
        Objects.requireNonNull(callbackThread);
        if (Thread.currentThread() == callbackThread) {
            throw new IllegalStateException("Capture exit barrier must not block the callback/Main thread");
        }
        final boolean dispatched;
        synchronized (LOCK) {
            if (!callerClosed) throw new IllegalStateException("Caller must abandon pixel consumption before exit wait");
            dispatched = dispatchEntered;
            exitWaiters++;
        }
        boolean initialInterrupt = Thread.interrupted();
        boolean interrupted = initialInterrupt || firstFailure instanceof InterruptedException;
        long laterInterruptions = 0;
        boolean waited = false;
        try {
            if (dispatched) {
                boolean latchObserved = false;
                while (true) {
                    try {
                        // Never hold LOCK here: the actual callback must remain free to complete.
                        if (!latchObserved) {
                            if (exactCallbackDone.getCount() != 0) waited = true;
                            exactCallbackDone.await();
                            latchObserved = true;
                        }
                        synchronized (LOCK) {
                            if (callbackSeen && (released || releaseFailure != null)) break;
                            waited = true;
                            LOCK.wait(); // Releases LOCK; covers spurious wakes or a premature duplicate latch.
                        }
                    } catch (InterruptedException observed) {
                        interrupted = true;
                        laterInterruptions++;
                        if (observed != firstFailure) {
                            try { firstFailure.addSuppressed(observed); } catch (Throwable ignored) { }
                        }
                    }
                }
            }
            return new ExitWait(dispatched, waited, initialInterrupt, laterInterruptions, interrupted);
        } finally {
            synchronized (LOCK) { exitWaiters--; }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public void throwIfReleaseFailed() {
        synchronized (LOCK) {
            if (releaseFailure != null) {
                throw new IllegalStateException("Window PixelCopy destination release failed", releaseFailure);
            }
        }
    }

    public boolean callbackSeen() {
        synchronized (LOCK) {
            return callbackSeen;
        }
    }

    public boolean dispatchEntered() {
        synchronized (LOCK) {
            return dispatchEntered;
        }
    }

    public boolean released() {
        synchronized (LOCK) {
            return released;
        }
    }

    public static final class Snapshot {
        public final boolean dispatchEntered;
        public final boolean callbackSeen;
        public final boolean released;
        public final String phase;
        public final int retainedDestinations;
        public final int exitWaiters;

        private Snapshot(boolean dispatchEntered, boolean callbackSeen, boolean released,
                         String phase, int retainedDestinations, int exitWaiters) {
            this.dispatchEntered = dispatchEntered;
            this.callbackSeen = callbackSeen;
            this.released = released;
            this.phase = phase;
            this.retainedDestinations = retainedDestinations;
            this.exitWaiters = exitWaiters;
        }
    }

    public Snapshot snapshot() {
        synchronized (LOCK) {
            return new Snapshot(dispatchEntered, callbackSeen, released, phaseLocked(), PENDING.size(), exitWaiters);
        }
    }

    public String phase() {
        synchronized (LOCK) {
            return phaseLocked();
        }
    }

    private String phaseLocked() {
        if (releaseFailure != null) return "release-failed-retained";
        if (released) return "released";
        if (releaseStarted) return "release-in-progress";
        if (!dispatchEntered) return "allocated-not-dispatched";
        if (!callbackSeen) return callerClosed ? "callback-pending-caller-abandoned" : "callback-pending";
        return borrowed ? "completed-caller-using" : "completed-awaiting-caller";
    }
}
