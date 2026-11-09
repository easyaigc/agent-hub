# Mobile new conversation destination

The Android new-chat flow now presents project names and their computer folder roots, then opens an identifiable local draft. The first message creates the actual desktop conversation through the existing staged broker. Opening or cancelling the picker never starts a thread or model turn.

## Confirmed defects and behavior

- Android opened the picker over a mounted sidebar, then dismissed only the picker. The sidebar could continue covering the new draft. The new-chat action now closes the sidebar before presenting the picker.
- The empty Android composer discarded the visible destination context. It now displays the selected project and roots, or an explicit projectless draft, and lets the user change the destination.
- Project and projectless rows appeared actionable even when their callbacks would silently reject. Disabled choices now reflect the latest capability, with connection/history/Host capability guidance and a refresh action.
- The picker exposed only its loaded project page. The next project page is now reachable from the picker.
- Choice validation occurs before voice/draft mutation, and dismissal requires successful local draft navigation. Captured choices are bound to Host, runtime scope, endpoint, client navigation and connection revision; withdrawn capabilities and removed/foreign projects cannot redirect a draft.
- iOS already closed the drawer and showed the chosen project. Its picker now also displays folder roots, disabled choices, empty guidance and refresh. Its existing first-send creation broker is unchanged.

## Folder and Host boundaries

A project row selects that project's registered computer roots. This does not add arbitrary file-system access or implement a new desktop project-creation API. Desktop-runtime connections must add previously unregistered folders in Codex, then refresh. Connections advertising `projects:create` retain their existing Add Project action.

A live read-only probe on the affected Windows Host confirmed project/history access but no `desktop:creations` capability. UI fixes alone cannot enable desktop creation there. The independent Windows bridge startup repair in [upstream PR #1](https://github.com/easyaigc/agent-hub/pull/1) addresses that Host configuration separately. This change does not advertise fake capabilities, switch execution runtimes, replay inputs or alter the installed service.

## Validation

This record separates build and synthetic-provider checks from real desktop execution and physical-phone acceptance.

| Scenario                                                                             | Current result                                                                                                                                                                                          |
| ------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Android acceptance APK and instrumentation APK                                       | Built successfully                                                                                                                                                                                      |
| Sidebar → new chat → project/folder → draft → first send                             | Passed on API 26 emulator with synthetic provider                                                                                                                                                       |
| Projectless, cancel/draft preservation, empty catalog refresh and project pagination | Passed on API 26 emulator                                                                                                                                                                               |
| Old navigation and withdrawn creation capability                                     | Passed on API 26 emulator; existing recent-project stale-client tests also passed                                                                                                                       |
| Exact original desktop owner and project after first native send                     | Passed: API 26 native UI → isolated Windows Host built from PR #1 → real Electron/model → independent original-owner verification; one new conversation, one user input, exact existing desktop project |
| Android physical device                                                              | No ADB device was attached at initial inspection; not accepted                                                                                                                                          |
| iOS build/device                                                                     | Xcode is unavailable on this Windows host; not accepted                                                                                                                                                 |
| Production upgrade / phone installation                                              | Not performed by this PR                                                                                                                                                                                |

Native regressions live in `NativeNewChatDestinationTest.kt`. Existing recent-project ordering, cancellation and stale-client tests remain applicable.
Native live acceptance lives in `NativeNewChatLiveTest.kt`, requires a private loopback fixture and submits one explicit first message. The current run used the Host from PR #1 (`5ba50a2`); it does not prove that the unmodified production Host can create conversations.

The eight native UI/session regressions, one original-desktop live scenario and 15 shared `SessionPoliciesTest` JVM cases passed. Workspace build, formatting, lint, parity/localization, TypeScript and history checks passed within `pnpm check` using the public base `173e9b7`. The full check stopped at the existing Windows managed-runtime writable-root assertion in `scripts/runtime-smoke.mjs:899`. That assertion was not bypassed. iOS source changes have not been compiled or run on this Windows machine.

Two earlier isolated live attempts failed before any model submission: a metadata-only test project was absent from the desktop sidebar's authoritative project catalog. Those failed attempts remain in private evidence. The successful run selected an existing desktop project and independently confirmed the new conversation and exact project assignment. No business conversation was reused or messaged.

After the live scenario, the destination's secondary text contrast was increased without changing navigation or creation logic. All eight UI/session tests passed again on the final APK. The recent-project fixture now waits for encrypted restore and stops its own network monitor before resetting synthetic state; earlier fixture-readiness failures remain recorded separately.

## Visual review

The existing native design system is retained. Screenshots were reviewed locally; private project names, paths, credentials and screenshots are not published with this contribution.

| #   | Item                             | Verdict | Evidence                                                                                                                                                                                                         |
| --- | -------------------------------- | ------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 01  | Point of view, not a template    | ✅      | Continuity: show the exact desktop project and roots before the first write.                                                                                                                                     |
| 02  | Typography works                 | ✅      | Existing native system font and user scaling; 22sp title, 17sp rows, 14sp context and 12sp roots.                                                                                                                |
| 03  | Restrained palette               | ✅      | Existing background and grouped surface; primary text, secondary context and disabled controls use the native theme.                                                                                             |
| 04  | Hierarchy breathes               | ✅      | Project names precede roots; recent choices precede the full catalog.                                                                                                                                            |
| 05  | Images are meaningful            | ✅      | Existing folder, clock and new-chat vector icons identify actions; no decorative images.                                                                                                                         |
| 06  | Motion whispers                  | ✅      | Existing drawer and sheet transitions; close the drawer before presenting the picker.                                                                                                                            |
| 07  | Mobile is designed, not squeezed | ✅      | Scrollable choices, wrapping roots and minimum 48dp Android targets; cancellation preserves the draft.                                                                                                           |
| 08  | The invisible is expensive       | ✅      | Native semantics, disabled unsupported actions, scoped targets, bilingual catalogs and increased destination-text contrast; eight final native UI/session checks passed. iOS runtime acceptance remains pending. |
