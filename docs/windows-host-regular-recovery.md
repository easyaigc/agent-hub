# Windows regular Host recovery

Status: the Windows delivery through `6446e9e8` reports real formal Host activation without restarting Windows or Codex. The maintaining Mac independently verified both original certificate-pinned LAN and Tailscale routes: anonymous health 401, authenticated health/workspace 200, exact Host identity and desktop-runtime. The native Windows preparation/activation receipts remain on that machine; the peer read does not prove Task rollback or future login autostart. The original synthetic operation remains pending and historically unconfirmed. Suggestion collection and Trace retention remain stopped. See [the integration evidence and limits](acceptance/windows-regular-host-recovery-20261103.md).

The P0/P1/P2 descriptions below preserve their stage-specific evidence: P0 event queries did not prove Restart; P1 elevated preparation did not prove Limited execution; the P2 launch failed its non-admin prerequisite. The no-restart branch later qualifies the reviewed synthetic implementation's separate scope rather than inventing historical process-exit evidence.

The direct service failure is the registered Scheduler executable being absent (`0x80070002`), before the Host can listen. The former AppData ACL rejection and unconfirmed synthetic migration are independent recovery constraints. A Limited-token experiment and unrelated smoke-test fixture failures are not additional causes of that missing executable. The candidate places the launcher and frozen runtime in a stable protected profile namespace, preserving the original Host state and Interactive task semantics.

An interrupted preparation can now be queried with the same `status` operation before its plan/seal exists. It reports the recorded phase artifacts and `replayAllowed=false`; this never resumes a write. Rollback validates and retains this operation's installation pointer, restores the original task, then archives that exact pointer so ordinary service commands cannot route to the rolled-back program. Unknown or changed pointers still reject before rollback mutation.

Native preparation exposed a Scheduler serialization difference: restoring a definition with its SDDL adds `RegistrationInfo/SecurityDescriptor` to the XML. The actual SDDL was unchanged and removing only that inserted line reproduced the exact pre-restore SHA. Comparisons now normalize only this field when it matches the independently read actual descriptor; all other definition fields and the actual descriptor remain checked. The narrow `continue-prepare` action binds the existing operation, successful startup report, exited probe and disabled zero-instance fixture to that exact difference, then finishes cleanup and seals the plan without replaying Task Run. Any changed evidence or previous continuation intent rejects. A corrected frozen controller may manage an unchanged frozen deployment via `--deployment-runtime-sha256`; both manifests are verified and the deployment is resolved only beneath the fixed protected profile root. Controller and deployed runtime fingerprints are recorded separately.

An older installed service script does not acquire routing support merely because `current.json` exists. Until the integrated distribution updates that script, use the verified frozen program returned in `plan.json` with its `bin/node.exe` and `scripts/windows-desktop-service.mjs` for `status`, `start`, `stop` or `restart`. Record this exact management entry during delivery; do not claim the old installation command was upgraded.

Local Windows continuation: the P2 task actually launched, but this Windows installation has UAC disabled. `RunLevel=Limited` therefore did not produce the required non-administrator token; the P2 matrix did not run. A separate explicit restricted-token probe observed the same user/session, non-administrator membership, Medium integrity and Job membership, but its fixed PowerShell child exited during initialization with `0xC0000142`. The cause of that initialization failure is unconfirmed. Neither result is a production Host acceptance or permission to change UAC, rights or the user's desktop ACLs.

The regular-service recovery path preserves the existing same-user/Interactive/LeastPrivilege task semantics. It does not claim UAC-independent task ACL isolation. `windows-host-regular-reconcile.ps1` is a bounded candidate for closing only a recorded arguments-phase synthetic failure after checking the original task, exact plan/image/fixture contracts, no child receipts, no active instance and no matching runner. It preserves all reservations and failure evidence; it cannot retire historical T1 or release pending state.

`windows-host-regular-runtime.mjs` freezes the compiled Host, workspace aliases, declared discovery/QR dependency closure, native helper sources, worker scripts and the exact Node executable. It includes no account or Host state. Every path, byte length and SHA is inventoried and the source commit is fixed. A protected program lives at the current user's `.config/agent-hub/windows-host-v1/programs/<manifest-sha256>`; the worker selects profile-v1 before its first private-file check and forces suggestion observation/retention off again after applying private configuration. The native launcher checks and retains the manifest and code-file read leases for this layout, strips Node preload environment and keeps the owned child Job until exit. The existing Host state directory stays separate. Deployment, native launch, task replacement/rollback and outside-machine connectivity remain separately required acceptance steps.

## Two independent boundaries

The former repair-task operation rejected an AppData ancestor with a nontrusted effective write ACE. That rejection remains valid under its existing policy. A capability SID name, package association or matching RID tail does not add a trusted principal. The missing MSIX launcher must not be recreated, substituted with the ordinary LocalAppData launcher or invoked through an alternate entry.

The separate synthetic Task-domain Run attempt retains an unconfirmed host-migrations pending record without historical process creation anchors. It is not the legacy task-recovery pending record. Absence of fixture folders, a matched script log, a process-name snapshot or a later timestamp does not establish whole-subtree closure. Preserve both operation histories and their original results; never delete a marker to permit another operation.

## P0: executable read-only next step

Use `windows-host-regular-recovery.mjs preflight` from a verified frozen package. Supply the original T1.1 payload, installed program, exact expected Host ID and legacy operation. The wrapper accepts only these fixed fields and rejects mutation names, duplicate arguments, relative/UNC/ADS paths and unknown options. The original manifest/source fingerprint must match before the PowerShell observation starts.

The existing native WindowsPowerShell 5.1 runs with its inherited PSModulePath removed from the child environment only. It does not compile code. It reads only the exact original Task and two named fixture folders, current user identity/context, selected private-leaf ownership/DACL metadata, the bound legacy intent/Run/pending hashes and selected configuration metadata. Token content is never read. Public installed JavaScript inventories and three launcher source bytes are hashed without importing the installed code. Missing inventories and unstable snapshots are reported; an inventory is not proof of the complete resolved execution closure or approval to execute it.

System-event reads are limited to two existing records per named provider/filter: Kernel-General 12/13, Kernel-Boot 27, User32 1074 and Eventlog 104, from the existing System channel. Only provider/ID/record ID/time and recognized BootType are returned. Raw Message/EventData, command lines, user names, tokens and credentials are not exported; no logging or auditing is enabled. No SACL, token membership inventory or broad registry/process scan is requested.

The read-only snapshot shares source read/write/delete access and detects selected length/write-time changes. It does not claim a pinned Win32 namespace or historical file identity. Existing execution guards are not relaxed by this observational helper. Output is bounded and includes `mutationAuthorized=false`, `pendingReleaseImplemented=false` and `historicalSubtreeVerified=false`; neither a timestamp nor this report can authorize pending deletion.

```powershell
& $node (Join-Path $p0Root 'scripts\windows-host-regular-recovery.mjs') preflight `
  --installed-program $originalInstalledProgram `
  --legacy-bundle $originalT11Payload `
  --host-id $originalHostId `
  --legacy-operation-id $originalPendingOperation
```

This step adds fresh current code, Task/security and system-boot record anchors for the recovery design. It does not repeat SID-source research or rerun D6 event correlation. Return only the bounded JSON plus exit/time and independent unchanged-state checks. A failed observation is not a permission to rerun a rejected mutation.

## P1: protected helper and checkpoint preparation

P1 implements the first preparation transaction, not formal activation. `CODEX_MANAGE_WINDOWS_NATIVE_STORAGE=profile-v1` selects exactly the current OS user profile's `.config/agent-hub/windows-native-v1/native-helpers/<source-digest>`. There is no arbitrary root argument, LOCALAPPDATA substitution, AppData alias exception or new trusted SID. The C# compiler independently derives and checks the same fixed root. Unknown mode values fail closed. Default legacy behavior is unchanged, and its existing task/action checks still reject an unrelated action.

In this package, only the independent preparation PS/native process uses that opt-in. It compiles `private-state` and `host-launcher` from frozen canonical sources. It never executes the Host launcher, installed worker, desktop CLI or suggestion/Trace reader. Scoped CodeDom temporary files use a newly created private scratch directory after checking the existing profile/agent-hub anchor; existing directory ACLs are not repaired. Native compilation retains the original source, namespace, file-identity, publication and compiler-Job checks.

Extract the verified package into a new private directory below the existing user profile's `.config/agent-hub` anchor, outside the installed program and AppData. Supply the exact private P0 stdout file and its independently known SHA-256, the package manifest SHA-256, original T1.1 payload directory and a new stable preparation UUID. Do not copy a private receipt into the public package or edit a manifest to pass a mismatch.

```powershell
& $node (Join-Path $packageRoot 'scripts\windows-host-regular-prepare.mjs') prepare `
  --installed-program $installedProgram --legacy-bundle $originalT11Payload `
  --host-id $expectedHostId --legacy-operation-id $originalT11OperationId `
  --operation-id $preparationOperationId --p0-report $privateP0File `
  --p0-sha256 $expectedP0Sha256 --manifest-sha256 $expectedPackageManifestSha256
```

Preparation first reserves `.config/agent-hub/host-regular-recovery/reservation.json` exclusively. This is a new transaction reservation, distinct from both existing pending files. Any existing reservation requires querying the same operation rather than rerunning preparation or choosing another UUID. Compilation failure, timeout or an incomplete capsule never permits reservation deletion. `status` and `resume` accept the same arguments, inspect an incomplete transaction or revalidate a completed capsule, and do not retry helper compilation, register tasks or retire the old operation. `resume` only observes boot consistency; a changed timestamp does not prove a complete Restart.

The private capsule stores separate native snapshots of the current registered `task.Xml`, `Definition.XmlText`, owner/group/DACL descriptors and root descriptors. It does not use a newly exported XML file as the original registered definition. It copies five original configuration/ledger inputs with native read handles: all namespace components remain non-reparse and pinned without DELETE sharing, and the source file remains pinned without write/delete sharing while its identity, bytes and hash are captured. Each destination is a new current-user-owned protected file created with CREATE_NEW. No existing file is overwritten or renamed. Separate access-token, TLS private-key and pairing files are not read or copied by P1.

The preparation tests file write, file rename and parent-directory rename denial while a native lease is held on its own synthetic fixture, then verifies the same operations succeed after closing it. Its only executed helper action is `private-state.exe check` on that synthetic file, with a retained Process object, bounded wait and confirmed exit. This is not Limited Task or whole-Host acceptance. New helper identity/hash and original task/configuration/ledger bindings are revalidated before the completion seal is written. Original mutable configuration is leased only during this bounded transaction, not throughout a Host lifetime.

Keep the private capsule, reservation and compiled resources for the responsible next phase. A verified rollback capsule is data for a future guarded rollback, not an implemented Task restore. P1 does not implement formal Task activation, Task rollback, old pending disposition or permission to restart Windows. The next implementation must first close the Limited Scheduler/resource/containment and rollback gates below. Do not restart merely to exercise `resume`. Source and auxiliary checks do not replace the actual Windows P1 receipt.

## P2: isolated Limited Scheduler execution

P2 binds the exact original P0 bytes, actual native P1 receipt and P1 checkpoint, including original file identities. The preparation UUID comes from that receipt, not a previously suggested but unexecuted UUID. Both refusal histories, configuration/ledger inputs and P1 compiled resources remain unchanged. This phase does not release either pending namespace.

Use the verified `windows-host-regular-domain.mjs verify` entry from its frozen package beneath the private profile anchor. Add the exact original P1 stdout file and SHA-256 to the P0 arguments and use one new stable domain operation UUID. `status` inspects this same operation; it never retries compilation, replays Run, removes fixtures or clears a reservation. There is no force, generic command, PID or cleanup override.

```powershell
& $node (Join-Path $p2Root 'scripts\windows-host-regular-domain.mjs') verify `
  --installed-program $installedProgram --legacy-bundle $originalT11Payload `
  --host-id $expectedHostId --legacy-operation-id $originalT11OperationId `
  --operation-id $domainOperationId --p0-report $privateP0File `
  --p0-sha256 $expectedP0Sha256 --p1-report $privateP1File `
  --p1-sha256 $expectedP1Sha256 --manifest-sha256 $expectedP2ManifestSha256
```

The existing elevated controller creates only its named fixture folders/tasks. It does not elevate a worker or alter the shared Scheduler root DACL. A new `domain-reservation.json` inside the existing P1 operation prevents unknown-result replay. The fixed `host-domain` executable is compiled before registration using the native compiler Job/publication path. This name is rejected outside explicit `profile-v1`; it is not a general Adapter command. The Task does not compile C# or use Reflection.Emit at startup.

The controller creates a private, non-inheritable, kill-on-close Job before Run. The Limited executable verifies its retained controller process handle and creation time, joins this Job before spawning a worker, and closes its temporary Job handle. It pins its own image plus the frozen Limited/common PowerShell scripts. Its only child is the fixed system PowerShell with that script; the script's only child sleeps. The controller retains three exact process handles and 100 ns creation anchors before Stop, requires Job active-process count zero and all retained handles signaled, then checks Scheduler instances are absent. This is a new synthetic subtree, never the old unconfirmed subtree. Early failure retains metadata and an unconfirmed/rejected result; later PID snapshots do not replace missing anchors.

The Task definition and executable must retain an 11,264-character synthetic argument. The script requires the expected user and a genuinely non-admin token. It performs two positive update/delete controls and six exact access-denied controls for task/folder creation, update, deletion and DACL changes. The controller checks negative objects/descriptors, positive outcome and unchanged original task/root descriptors. Two manual Run attempts must leave one stable observed instance; S_OK alone is not a start and the second returned COM interface is not assumed to have the first GUID. This bounded check does not prove future logon/periodic triggers. `allSubjectsCovered=false` remains explicit; it is not a privilege proof for every SID.

Successful cleanup disables/stops the exact Runner, confirms its new subtree and deletes only verified named fixture objects. Write/rename attempts target new resources while native leases are held. Actual Windows must confirm these controls; Mac parsing, C# compilation and pure argument checks cannot. No Node/Host runtime, metadata reader, original Codex, model Turn or suggestion reader starts in P2. A full immutable Host/Node closure and formal service migration/rollback remain the next boundary; P2's small synthetic closure is not that production closure. No restart is authorized by P2.

Normal and exceptional cleanup resolve the current folder and Task before each mutation, validating the DACL, principal, action, arguments and fixture settings. A changed, denied, missing or active object is retained. A PowerShell COM-adapter regression covers these mutation paths and successful owned-object controls; it is not native Scheduler evidence. Windows PowerShell 5 and the fixed Framework compiler explicitly reference System.Xml and System.Runtime.Serialization for the domain JSON contract.

Frozen execution scripts use a separate, SHA-bound code lease for exactly the two domain PowerShell filenames beneath the private profile anchor. Code ownership may be current user, SYSTEM or Administrators, matching compiler-source trust; private configuration/ledger leases still require the current user owner. Both leases pin the namespace and file without write/delete sharing, reject links and check the allowed DACL subjects. The code lease additionally verifies the frozen bytes before returning.

`resume` is restricted to the recorded first local candidate's code-lease failure before any Task mutation or Run. It requires the original failure bytes/SHA and prior manifest SHA, the same operation/checkpoint, an empty existing operation directory and both exact fixture folders absent. It preserves the original reservation and adds a CREATE_NEW `resume-intent.json` before continuing with a new verified package. Any earlier intent, task/run record or other operation content prevents resume; uncertain execution cannot use this path. A second resume is not allowed. This is not retirement of either historical pending operation.

Platform basis: Microsoft's [nested Jobs](https://learn.microsoft.com/en-us/windows/win32/procthread/nested-jobs), [Job lifetime](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects), [Run contract](https://learn.microsoft.com/en-us/windows/win32/api/taskschd/nf-taskschd-iregisteredtask-run), [IgnoreNew RPC](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-tsch/77f2250d-500a-40ee-be18-c82f7079c4f0) and [registration flags](https://learn.microsoft.com/en-us/windows/win32/api/taskschd/nf-taskschd-itaskfolder-registertaskdefinition). Job assignment can fail actual hierarchy/session/security checks; refusal is preserved. Last-handle termination assumes no other Job handle is inherited or duplicated; this implementation requests neither and members close their temporary handles.

## Responsible production continuation candidate

1. Bind an immutable recovery/checkpoint and rollback plan to the exact expected Host identity, original private configuration identity, Task XML/SD and original pending/intent/Run hashes. First explain each changed object and reject all mismatches or competing operations. Do not copy or rotate keys. The original rollback data remains private.
2. With no historical anchors or retained Job handle, the presently documented path to close the old-boot survival risk requires a human normal **Restart**, after the responsible checkpoint is ready and work is saved. This is not authorized or performed by P0. Record independent confirmation that a full Restart completed after all old launch windows closed; boot events and time comparisons are supporting consistency evidence only. Shutdown/Fast Startup, hibernation, timestamp comparison and resetting a pending file are not equivalent proof. If this premise cannot be established, keep the old state unconfirmed.
3. The post-Restart candidate verifies the same target, original operation/Task/config anchors and absence of recreated old fixture execution. It combines explicit human confirmation of normal Restart, the same machine/user/Host binding, changed OS boot time and a decrease in the Win32 64-bit uptime counter. This is qualified old-boot retirement under trustworthy local records and no system rollback or clock tampering; it is not a cryptographic boot identifier, a historical test pass or historical process-exit evidence. The native primitive opens the exact original marker with retained read/delete access, checks its original identity/hash/owner/DACL and atomically renames that same handle to `pending.retired.json` in its private historical operation. It does not reopen by pathname for deletion, overwrite another archive or remove the failure record.
4. The AppData rejection remains independent. Executable resources use the product's private profile namespace, verified immutable manifest and fixed task action. The local read-only Scheduler startup proved the actual worker/bootstrap path without starting a Host. On this UAC-disabled machine it ran with the existing high-integrity user context; no Limited ACL-isolation claim is made. Preparation additionally rehearses definition/security restoration on a disabled private fixture. It does not alter UAC, user rights, the desktop or the rejected AppData namespace.
5. Commit the formal Task only after its exact before/after state, code/config identity, Principal/Interactive/Limited, triggers, enabled state and rollback are fixed and reviewable. Force observation/retention off in the new Host process tree, without altering Codex/global/User environment or credentials. Reconcile timeouts by stable operation ID; do not replay an uncertain start.
6. Windows independently verifies loopback and LAN HTTPS authentication, exact Host/workspace/desktop-runtime/original owner, existing-thread control and persistent auto-start. The authorized Mac may then make a read-only connection check to that exact Host. Public release/download checks and local fixtures do not replace these native proofs.

`windows-host-regular-activation.mjs prepare` reserves a new transaction bound to P1 and the explicitly closed failed P2 fixture, copies/verifies the frozen runtime, proves the retained marker/disposition target without renaming it, creates a separate User-owned instance configuration, runs the exact read-only worker and rehearses Task definition/SD restoration while disabled. A sealed private plan records original six-file identities/hashes, complete Task snapshots, helper identities, code manifest, original Host identity and pre-Restart witness. It leaves the original formal Task and marker untouched.

`activate --restart-confirmed` requires that prepared plan and the independently qualified new boot before marker disposition or formal Task changes. It first registers the fixed action disabled with the original principal, triggers, settings and SD, then records a run intent before enabling it. The authenticated loopback workspace must return the original Host ID and `desktop-runtime`; failures attempt only the bound Task rollback and preserve uncertain results. Reusing a retirement/write/run intent cannot replay activation. `status` is observational; it does not retry writes. A new private `current.json` routes routine service controls to the active protected code and original state. `rollback` uses the bound current action and the existing retained-process stop path before restoring the original snapshot. Actual production activation, rollback and peer HTTPS remain unverified until separately executed.

The boot-witness interpretation follows Microsoft's [Win32 time](https://learn.microsoft.com/en-us/windows/win32/sysinfo/windows-time), [OS boot-time property](https://learn.microsoft.com/en-us/windows/win32/cimwin32prov/win32-operatingsystem) and [Restart/Fast Startup](https://learn.microsoft.com/en-us/troubleshoot/windows-client/setup-upgrade-and-drivers/fast-startup-causes-system-hibernation-shutdown-fail) documentation. The five-second counter margin is a product check, not a Microsoft guarantee. No system Restart is authorized by preparation, tests or this document.

## Validation boundary

Local auxiliary tests cover strict command/path/identity contracts, child-only PS5 environment isolation, no module execution during public inventory, missing/linked/changed resource handling and bounded shared snapshots in the actual memory-defined PowerShell function scope. Native ACL, Task/boot providers and WindowsPowerShell 5.1 results remain separately pending. The package does not introduce mobile UI/wire behavior; F03 tracks a Windows-only observational tool, not recovered connectivity.

# Independent activation with a preserved synthetic test

The reviewed legacy domain test is confined to its UUID Scheduler fixtures and
test directories. It does not update the formal Host task or the new stable
runtime. `qualify-no-restart` reconstructs the historical sealed command as data,
checks its digest against the retained intent, pins the reviewed implementation
manifest and key execution sources, and performs the prepared activation's
original task, configuration, helper and free-port checks. It is an observation;
it neither starts Host nor records an activation attempt.

For that narrowly recognized implementation, `activate --preserve-legacy-test`
holds the original pending marker read lease during activation and leaves the old
operation unresolved. It never archives the marker or claims the old subtree
exited. A separate immutable activation intent prevents replay after an unknown
result. Rollback and active receipts distinguish preserved from archived pending
state. Unknown implementations still require their own scope analysis; this is
not a force option. The post-Restart retirement path remains separate.

This adds a candidate path without requiring a Windows or Codex restart. Native
qualification, actual service operation, peer connectivity and login autostart
must each be recorded separately when exercised. Mobile protocols and UI remain
unchanged.
