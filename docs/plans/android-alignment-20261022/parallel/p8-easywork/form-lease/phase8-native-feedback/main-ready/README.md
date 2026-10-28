# Actual Main preparation inside the instrumentation process

The previous parent failure occurred before input/adoption: an external am start ran
before am instrument created its target process, so that process's lifecycle monitor
had no resumed MainActivity. It is not evidence that onNewIntent or the capability failed.
Original failure: stage8-pairing-native/actions-1793217422026-b3fb35cb/instrumentation.log,
parent source64f4c3ec, App3f93361a, runner611a6070. No original body/authorization was started.

The exact parent lease still requires p8ActualMainReady=1 and installed binary hashes.
An additional explicit p8ActualMainStart=1 opts into the existing native-foreground
activation pattern after instrumentation/package/build checks, inside this process.
It starts only the actual acceptance Main component with MAIN/LAUNCHER, then observes
actual RESUMED and actual completed restore before adopting the independent Session.
It never calls a fake dispatcher, resets data, reinstalls the App or asks the user to
prepare anything. Without the extra opt-in it only accepts an already resumed Main.

Before launch, every mobile-v1 encrypted namespace is stream-hashed. AtomicFile backup/
new files or original document/photo/camera result records prevent this test-owned
preparation. Read-only metadata/private-vault preflight requires no saved real connection,
and itself must leave the hashes unchanged. No token/value is logged. Startup and restore
must also preserve those hashes and leave an idle actual Session without recovery, real
clients, running operation or recording. A private preparation receipt records process,
startup status hash and before/after encrypted-file hashes even when readiness fails.
After the normal owned-session adoption, the original same-Activity/no STOP/DESTROY,
null-link/target/navigation/draft and capability write-refusal assertions stay unchanged.
The old Session is restored and default files checked after the case. New native execution
is still parent-owned and unverified here; shared Maine9/Session2c8 stay byte-identical.
