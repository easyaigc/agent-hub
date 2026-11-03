# Windows regular Host recovery integration (2026-11-03)

Scope: review the Windows delivery through `6446e9e8157845b15175565638c14e5213997897`, integrate it with the canonical main branch, and independently read the one authorized Host from the maintaining Mac. The currently running Windows installation is retained; this integration does not deploy, restart, send a model input or resume suggestion research.

## Root cause and recovery boundary

The Windows operator reported that the formal Scheduler action referenced a missing launcher and failed before worker startup with `0x80070002`. The deletion's actor, date and cause were not established. `EnableLUA=0` explains why the attempted Limited fixture did not establish a non-admin token on that machine; it is separate from the missing-launcher root cause. No UAC or old AppData capability ACE change is part of this recovery.

The new fixed profile runtime and launcher retain the original Host identity, state, credentials and TLS material. Executable files are manifest-bound and leased; configuration remains mutable through the existing private-state contract. The Host process tree forces suggestion observation and Trace retention off after configuration merge. This is recovery of ordinary Host connectivity, not recovery of suggestion collection.

The no-restart branch recognizes one reviewed legacy synthetic implementation by manifest, source and reconstructed sealed-action digests. It binds the same checkpoint, original files and Task before activation, retains the exact old pending marker's read lease, and records a separate immutable intent. It neither retires the unknown legacy operation nor asserts historical process exit or a passed legacy test. Uncertain activation cannot be replayed. Other implementations do not acquire a generic force exception. The old AppData namespace is not made trusted; the new execution namespace has its own guards.

Rollback checks the active route, current Task, retained-process stop result and original definition/security snapshot. Scheduler's matching embedded security-descriptor projection is normalized separately from substantive Task changes. Restoring the original missing-launcher definition restores the prior offline state; it is not a claim of a working older installation.

## Evidence and versions

| Evidence | Result and limits |
| --- | --- |
| Windows delivery | Eight commits after `dae1c3a015eb9e06e504dffe7b2d7c604732655f`; exact delivered head `6446e9e8157845b15175565638c14e5213997897` fetched and checked on the maintaining Mac. |
| Windows native activation, reported by its operator | One qualification and one activation under the same immutable operation; active Task/worker/Host tree, original identity and credentials retained, local LAN/Tailscale authentication checked. Native receipts remain private on Windows; the Mac connection check does not replace those receipts or a login/restart test. |
| Running Windows runtime | Manifest `d71d882fac38a9a22d60b381e52b67dee41b150af746fd5dc1582d70161f7e64`, built from source `65e9de29`; controller `8273d17e9976ee4e4eae325f248ff84ed4ca8448635185676615f4f675e53a3f`, source `3be1bc01`. The final delivered commit changes test fixtures, not that running deployment. |
| Mac peer read, 2026-11-03 23:44:53 +08 | Both existing LAN and Tailscale routes passed original certificate-pin validation. Anonymous health returned 401; authenticated health and workspace returned 200; exact expected Host ID and `desktop-runtime` matched. Workspace contained 3 projects and 446 threads including the specified source project/thread. No history/body, model input or service mutation was sent. |
| Integration auxiliary checks, Mac 2026-11-04 | `pnpm build` passed. A complete `pnpm check` exited 0 in 807 seconds on frozen input; targeted recovery/socket checks also passed 16/16. Existing Node 24, JDK 17 and Xcode were selected only for check processes; the temporary path was short and canonical. Fake COM/XML fixtures use only fixed synthetic SID mappings outside Windows, where .NET Windows SID APIs are unavailable. Production and native Windows identity checks are unchanged. Earlier environment/fixture failures remain recorded privately; this final whole run is not assembled from their successful portions. Windows-only native cases are explicitly skipped on Mac. |
| Prior Windows whole gate | The owner fixture was subsequently corrected; an isolated smoke retry does not complete the interrupted Windows whole gate. The Mac whole run does not upgrade that Windows result. |

The local integration adds the same flat TypeScript-to-JavaScript inventory requirement already used by the canonical distributor. New runtime collection rejects stale, missing or nested emitted JS before freezing a closure. Directory fixtures exercise these failures; the check does not prove compiled bytes correspond to source without a clean frozen build.

Native Windows rollback, actual login/restart autostart, external phone controls and a newly deployed integrated runtime are not claimed by the Mac read-only check. iOS and Android UI/wire behavior remain unchanged. Original keys/configuration, previous failures, unresolved legacy operation and existing work-in-progress branches are preserved.
