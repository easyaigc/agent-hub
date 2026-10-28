# Actual native storage-wait stack correction

The parent native failure was in the test's exact-name stack probe, before Main revocation.
The compiled JVM method is operationTransaction$android(Function0), confirmed with javap;
the previous comparison required the unmangled operationTransaction name. The original
physical-device failure remains in stage8-pairing-native/actions-1793217336245-b4b4d780.
Parent source64f4c3ec, App3f93361a and runner611a6070 bind that attempt; its other after-write
revocation/rollback case passed. No new device proof is claimed here.

The probe now resolves the exact declared transaction method from this actual storage
class, then requires that exact class/method, actual credential wrapper.write, a native
java.util.concurrent.locks stack frame and WAITING/BLOCKED thread state simultaneously.
It records bounded class/method-only frames and held-latch state before releasing IO,
including on failure. The existing2s deadline is unchanged. The worker must still be
unfinished while the storage holder is entered and release latch held. Main revocation
and draft edit must still return before releasing IO; exact rollback, unchanged target,
new draft and original grant/claim/unknown assertions are retained. No product lock,
shared hook, timeout, IO scheduling or user data is changed by this test correction.
