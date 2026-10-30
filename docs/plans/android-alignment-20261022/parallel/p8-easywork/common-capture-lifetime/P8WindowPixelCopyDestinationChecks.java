package com.agenthub.android;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Executes the actual pure-JDK owner class, not Android/PixelCopy/Bitmap or a rewritten model. */
public final class P8WindowPixelCopyDestinationChecks {
    private static int cases;
    private static final class Value { final AtomicInteger releases = new AtomicInteger(); }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    private static void rejects(Runnable action) {
        try { action.run(); throw new AssertionError("Expected rejection"); }
        catch (IllegalStateException expected) { }
    }
    private static P8WindowPixelCopyDestination<Value> owner(Value value) {
        return new P8WindowPixelCopyDestination<>(value, item -> check(item.releases.incrementAndGet() == 1));
    }
    private static void finished(Value value) { check(value.releases.get() == 1); P8WindowPixelCopyDestination.requireNoPending(); cases++; }

    public static void main(String[] args) throws Exception {
        // Never-dispatched failures are known not to be native writers.
        Value first = new Value(); P8WindowPixelCopyDestination<Value> a = owner(first);
        check(a.phase().equals("allocated-not-dispatched")); rejects(a::completedResource); rejects(a::completedStatus);
        rejects(() -> a.onCompletion(0)); a.closeCaller(); a.closeCaller(); rejects(a::enterDispatch); finished(first);

        // Actual callback completion does not release while the caller still uses its destination.
        Value normal = new Value(); P8WindowPixelCopyDestination<Value> b = owner(normal); b.enterDispatch(); b.onCompletion(0);
        check(b.completedStatus() == 0 && normal.releases.get() == 0 && b.phase().equals("completed-awaiting-caller"));
        check(b.completedResource() == normal && b.phase().equals("completed-caller-using")); rejects(b::completedResource);
        b.onCompletion(9); check(b.completedStatus() == 0); rejects(P8WindowPixelCopyDestination::requireNoPending);
        b.closeCaller(); b.onCompletion(0); b.closeCaller(); finished(normal);

        for (int status : new int[]{0, 1, 2, 3, 4, 5}) {
            // A caller timeout/interrupt/exception abandons acceptance, never the native writer's memory.
            Value late = new Value(); P8WindowPixelCopyDestination<Value> c = owner(late); c.enterDispatch(); c.closeCaller();
            check(late.releases.get() == 0 && !c.released() && c.phase().equals("callback-pending-caller-abandoned"));
            P8WindowPixelCopyDestination.Snapshot snap = c.snapshot();
            check(snap.dispatchEntered && !snap.callbackSeen && !snap.released && snap.retainedDestinations == 1);
            rejects(c::completedResource); rejects(P8WindowPixelCopyDestination::requireNoPending);
            c.onCompletion(status); check(c.released()); c.onCompletion(status); finished(late);
        }

        // Public request throw after entry is unknown even if no handler callback ever arrives.
        Value unknown = new Value(); P8WindowPixelCopyDestination<Value> d = owner(unknown); d.enterDispatch();
        try { throw new IllegalArgumentException("simulated request failure after dispatch boundary"); }
        catch (IllegalArgumentException expected) { d.closeCaller(); }
        check(unknown.releases.get() == 0 && P8WindowPixelCopyDestination.pendingCount() == 1);
        rejects(P8WindowPixelCopyDestination::requireNoPending);
        d.onCompletion(3); finished(unknown); // Test cleanup supplies a completion; production never fabricates it.

        // A second dispatch is refused; its untouched allocation can still be disposed safely.
        Value held = new Value(); Value spare = new Value();
        P8WindowPixelCopyDestination<Value> e = owner(held); P8WindowPixelCopyDestination<Value> f = owner(spare);
        e.enterDispatch(); rejects(f::enterDispatch); f.closeCaller(); check(spare.releases.get() == 1 && !f.dispatchEntered());
        check(P8WindowPixelCopyDestination.pendingCount() == 1); e.closeCaller(); e.onCompletion(0); finished(held); cases++;

        // Disposer is outside the global state lock: an observer/callback thread can make progress.
        Value concurrentDispose = new Value(); CountDownLatch releaseEntered = new CountDownLatch(1); CountDownLatch allowRelease = new CountDownLatch(1);
        P8WindowPixelCopyDestination<Value> g = new P8WindowPixelCopyDestination<>(concurrentDispose, value -> {
            releaseEntered.countDown();
            try { allowRelease.await(); } catch (InterruptedException failure) { throw new RuntimeException(failure); }
            check(value.releases.incrementAndGet() == 1);
        });
        g.enterDispatch(); g.onCompletion(0); Thread closer = new Thread(g::closeCaller); closer.start(); releaseEntered.await();
        check(g.snapshot().phase.equals("release-in-progress") && P8WindowPixelCopyDestination.pendingCount() == 1);
        rejects(P8WindowPixelCopyDestination::requireNoPending); allowRelease.countDown(); closer.join(); finished(concurrentDispose);

        // Real Java thread interleavings on the delivered owner, not an Android callback simulation proof.
        final int raceIterations = 1000;
        for (int index = 0; index < raceIterations; index++) {
            Value value = new Value(); P8WindowPixelCopyDestination<Value> lease = owner(value); lease.enterDispatch();
            CountDownLatch start = new CountDownLatch(1); AtomicReference<Throwable> error = new AtomicReference<>();
            Thread callback = new Thread(() -> {
                try { start.await(); lease.onCompletion(0); } catch (Throwable failure) { error.compareAndSet(null, failure); }
            });
            Thread caller = new Thread(() -> {
                try { start.await(); lease.closeCaller(); } catch (Throwable failure) { error.compareAndSet(null, failure); }
            });
            callback.start(); caller.start(); start.countDown(); callback.join(); caller.join();
            if (error.get() != null) throw new AssertionError(error.get());
            lease.closeCaller(); lease.onCompletion(1); check(value.releases.get() == 1 && lease.released());
            P8WindowPixelCopyDestination.requireNoPending();
        }
        cases++;

        // A callback completing on another thread publishes status before the caller borrows the value.
        Value publication = new Value(); P8WindowPixelCopyDestination<Value> h = owner(publication); h.enterDispatch();
        CountDownLatch callbackDone = new CountDownLatch(1);
        Thread callback = new Thread(() -> { h.onCompletion(0); callbackDone.countDown(); }); callback.start(); callbackDone.await(); callback.join();
        check(h.completedStatus() == 0 && h.completedResource() == publication); h.closeCaller(); finished(publication);

        // Last case intentionally retains a dummy resource after disposer failure; isolated JVM exit ends this fixture.
        Value failed = new Value(); AtomicInteger attempts = new AtomicInteger();
        P8WindowPixelCopyDestination<Value> bad = new P8WindowPixelCopyDestination<>(failed, value -> {
            attempts.incrementAndGet(); throw new IllegalStateException("simulated disposer failure");
        });
        bad.enterDispatch(); bad.closeCaller(); bad.onCompletion(0);
        check(!bad.released() && bad.phase().equals("release-failed-retained") && attempts.get() == 1);
        rejects(bad::throwIfReleaseFailed); rejects(P8WindowPixelCopyDestination::requireNoPending);
        bad.closeCaller(); bad.onCompletion(0); check(attempts.get() == 1); cases++;
        System.out.println("{\"passed\":true,\"scenarios\":" + cases + ",\"raceIterations\":" + raceIterations
                + ",\"actualDeliveredPureJdkClass\":true,\"androidPixelCopyOrBitmapExecuted\":false,\"nativePasses\":0}");
    }
}
