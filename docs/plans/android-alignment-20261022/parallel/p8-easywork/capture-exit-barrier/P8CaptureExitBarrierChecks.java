package com.agenthub.android;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Actual Java owner/barrier tests with dummy context/resources; not Android or JUnit execution. */
public final class P8CaptureExitBarrierChecks {
    private static int scenarios;
    private static final class Value { final AtomicInteger releases = new AtomicInteger(); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    private static void until(BooleanSupplier predicate) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!predicate.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Bounded observation timed out");
            Thread.sleep(1);
        }
    }
    private static void raise(Throwable original) throws Throwable { throw original; }

    private static final class Frame {
        final Value value = new Value();
        final Object window = new Object();
        final CountDownLatch done = new CountDownLatch(1);
        final Throwable first;
        final P8WindowPixelCopyDestination<Value> owner;
        final AtomicReference<Throwable> terminal = new AtomicReference<>();
        final AtomicReference<P8WindowPixelCopyDestination.ExitWait> wait = new AtomicReference<>();
        final AtomicInteger after = new AtomicInteger();
        final AtomicInteger laterProductInput = new AtomicInteger();
        final Thread caller;
        volatile boolean interruptRestored;

        Frame(Throwable first, Thread callbackThread, boolean initialInterrupt) {
            this.first = first;
            owner = new P8WindowPixelCopyDestination<>(value, window, item -> check(item.releases.incrementAndGet() == 1));
            caller = new Thread(() -> {
                try {
                    owner.enterDispatch();
                    try {
                        raise(first); // Represents the original timeout/dispatch failure, with identity preserved.
                        laterProductInput.incrementAndGet();
                    } finally {
                        owner.closeCaller();
                        if (initialInterrupt) Thread.currentThread().interrupt();
                        wait.set(owner.awaitCaptureExit(done, first, callbackThread));
                        interruptRestored = Thread.currentThread().isInterrupted();
                    }
                } catch (Throwable failure) { terminal.set(failure); }
                finally { after.incrementAndGet(); }
            }, "dummy-capture-frame");
            caller.setDaemon(true);
        }

        void startAndObserveBlocked() throws Exception {
            caller.start(); until(() -> owner.snapshot().exitWaiters == 1);
            check(caller.isAlive() && after.get() == 0 && laterProductInput.get() == 0);
            check(owner.requestContext() == window && !owner.released() && value.releases.get() == 0);
        }

        void callback(int status) {
            try { owner.onCompletion(status); } finally { done.countDown(); }
        }

        void finished() throws Exception {
            caller.join(3000); check(!caller.isAlive()); check(terminal.get() == first);
            check(after.get() == 1 && laterProductInput.get() == 0 && value.releases.get() == 1);
            check(owner.snapshot().exitWaiters == 0); P8WindowPixelCopyDestination.requireNoPending(); scenarios++;
        }
    }

    public static void main(String[] args) throws Exception {
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        ExecutorService callbacks = Executors.newSingleThreadExecutor(action -> {
            Thread thread = new Thread(action, "dummy-callback-main"); thread.setDaemon(true); callbackThread.set(thread); return thread;
        });
        callbacks.submit(() -> { }).get(3, TimeUnit.SECONDS);
        try {
            // No callback means no capture/JUnit-like finally exit, even after observed interruptions.
            Frame pending = new Frame(new AssertionError("original three-second wait failure"), callbackThread.get(), false);
            pending.startAndObserveBlocked();
            callbacks.submit(() -> check(pending.after.get() == 0)).get(3, TimeUnit.SECONDS); // Callback executor stays free.
            pending.caller.interrupt(); until(() -> pending.first.getSuppressed().length == 1);
            check(pending.after.get() == 0 && pending.caller.isAlive());
            pending.caller.interrupt(); until(() -> pending.first.getSuppressed().length == 2);
            check(pending.owner.requestContext() == pending.window && pending.after.get() == 0);
            callbacks.submit(() -> pending.callback(0)).get(3, TimeUnit.SECONDS); pending.finished();
            check(pending.wait.get().laterInterruptions == 2 && pending.wait.get().interruptStatusRestored && pending.interruptRestored);

            // A request throw after entry must wait for the same owner/callback, then rethrow the original error.
            Frame dispatchFailure = new Frame(new IllegalArgumentException("unknown dispatch failure"), callbackThread.get(), false);
            dispatchFailure.startAndObserveBlocked(); callbacks.submit(() -> dispatchFailure.callback(3)).get(3, TimeUnit.SECONDS); dispatchFailure.finished();

            // An initial interrupt flag is recorded and restored without inventing a later exception.
            Frame preInterrupted = new Frame(new AssertionError("first failure"), callbackThread.get(), true);
            preInterrupted.startAndObserveBlocked(); callbacks.submit(() -> preInterrupted.callback(0)).get(3, TimeUnit.SECONDS); preInterrupted.finished();
            check(preInterrupted.wait.get().initialInterruptFlag && preInterrupted.wait.get().laterInterruptions == 0 && preInterrupted.interruptRestored);

            // Original InterruptedException identity survives even though its wait already cleared the flag.
            Frame interruptedFirst = new Frame(new InterruptedException("original wait interrupted"), callbackThread.get(), false);
            interruptedFirst.startAndObserveBlocked(); callbacks.submit(() -> interruptedFirst.callback(1)).get(3, TimeUnit.SECONDS); interruptedFirst.finished();
            check(interruptedFirst.interruptRestored && interruptedFirst.terminal.get() == interruptedFirst.first);

            // An unrelated/premature latch cannot fake this owner's completion/disposal.
            Frame falseLatch = new Frame(new AssertionError("first failure"), callbackThread.get(), false);
            falseLatch.startAndObserveBlocked(); falseLatch.done.countDown();
            callbacks.submit(() -> { check(falseLatch.after.get() == 0); check(!falseLatch.owner.callbackSeen()); }).get(3, TimeUnit.SECONDS);
            check(falseLatch.caller.isAlive()); callbacks.submit(() -> falseLatch.callback(0)).get(3, TimeUnit.SECONDS); falseLatch.finished();

            // Callback/disposal alone also cannot replace the exact callback-finally latch.
            Frame missingExactLatch = new Frame(new AssertionError("first failure"), callbackThread.get(), false);
            missingExactLatch.startAndObserveBlocked(); callbacks.submit(() -> missingExactLatch.owner.onCompletion(0)).get(3, TimeUnit.SECONDS);
            check(missingExactLatch.value.releases.get() == 1 && missingExactLatch.after.get() == 0 && missingExactLatch.caller.isAlive());
            missingExactLatch.done.countDown(); missingExactLatch.finished();

            // Proven pre-dispatch rejection can finish without a callback; late dispatch remains rejected.
            Value untouched = new Value(); Object context = new Object(); Throwable beforeDispatch = new IllegalStateException("local pre-dispatch refusal");
            P8WindowPixelCopyDestination<Value> noRequest = new P8WindowPixelCopyDestination<>(untouched, context, value -> value.releases.incrementAndGet());
            noRequest.closeCaller(); P8WindowPixelCopyDestination.ExitWait noWait = noRequest.awaitCaptureExit(new CountDownLatch(1), beforeDispatch, callbackThread.get());
            check(!noWait.dispatched && !noWait.waited && untouched.releases.get() == 1 && noRequest.requestContext() == context);
            try { noRequest.enterDispatch(); throw new AssertionError(); } catch (IllegalStateException expected) { }
            P8WindowPixelCopyDestination.requireNoPending(); scenarios++;

            // Never block the callback executor itself. Native source checks off-Main before any request.
            Value guarded = new Value(); P8WindowPixelCopyDestination<Value> mainGuard = new P8WindowPixelCopyDestination<>(guarded, value -> value.releases.incrementAndGet());
            mainGuard.enterDispatch(); mainGuard.closeCaller(); CountDownLatch guardedDone = new CountDownLatch(1);
            callbacks.submit(() -> {
                try { mainGuard.awaitCaptureExit(guardedDone, new AssertionError(), Thread.currentThread()); throw new AssertionError(); }
                catch (IllegalStateException expected) { }
                try { mainGuard.onCompletion(0); } finally { guardedDone.countDown(); }
            }).get(3, TimeUnit.SECONDS);
            check(guarded.releases.get() == 1); P8WindowPixelCopyDestination.requireNoPending(); scenarios++;

            // Spurious/latch-order/close-completion interleavings execute the actual delivered barrier.
            final int raceIterations = 500;
            for (int index = 0; index < raceIterations; index++) {
                Frame race = new Frame(new AssertionError("same original failure"), callbackThread.get(), false);
                race.caller.start(); callbacks.submit(() -> {
                    try { until(() -> race.owner.dispatchEntered()); } catch (Exception error) { throw new RuntimeException(error); }
                    race.callback(0);
                }).get(3, TimeUnit.SECONDS);
                race.caller.join(3000); check(!race.caller.isAlive() && race.terminal.get() == race.first);
                check(race.after.get() == 1 && race.laterProductInput.get() == 0 && race.value.releases.get() == 1);
                P8WindowPixelCopyDestination.requireNoPending();
            }
            scenarios++;

            // Last: a known disposer error settles the write barrier but stays retained, never a success.
            Value failed = new Value(); Object failedContext = new Object(); CountDownLatch failedDone = new CountDownLatch(1);
            P8WindowPixelCopyDestination<Value> bad = new P8WindowPixelCopyDestination<>(failed, failedContext, value -> { throw new IllegalStateException("disposer error"); });
            Throwable original = new AssertionError("original failure"); bad.enterDispatch(); bad.closeCaller();
            callbacks.submit(() -> { try { bad.onCompletion(0); } finally { failedDone.countDown(); } }).get(3, TimeUnit.SECONDS);
            P8WindowPixelCopyDestination.ExitWait settled = bad.awaitCaptureExit(failedDone, original, callbackThread.get());
            check(settled.dispatched && !bad.released() && bad.phase().equals("release-failed-retained"));
            try { bad.throwIfReleaseFailed(); throw new AssertionError(); } catch (IllegalStateException error) { original.addSuppressed(error); }
            check(original instanceof AssertionError && original.getSuppressed().length == 1); scenarios++;
            System.out.println("{\"passed\":true,\"barrierScenarios\":" + scenarios + ",\"barrierRaceIterations\":" + raceIterations
                    + ",\"actualDeliveredJavaOwnerAndBarrier\":true,\"androidPixelCopyBitmapJUnitOrLooperExecuted\":false,\"nativePasses\":0}");
        } finally {
            callbacks.shutdown(); check(callbacks.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
