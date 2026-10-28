# P9 native fixture corrections

The parent supplied two actual native failures. These corrections change tests only; production navigation and delivery guards remain intact. The original failed cases are preserved and must not be rerun as if they were new attempts.

## Cold reader catalog contract

The stage15 writer passed for a new original lease. The original ticket, duplicate new Intent and retained new draft assertions then passed in the reader. Its last direct `selectThread(other)` failed the production Session membership guard because the notification locator had correctly adopted its exact page-twelve workspace, containing only the original thread. The test separately called the reader for another row but never published that response to Session. A standalone reader result is not authority for Session navigation.

The corrected reader calls the actual `MobileSession.refresh(userInitiated=true)`, waits for a published catalog containing the exact target's other row with loading complete, and selects that row from `session.threads`. It checks the original selection, new draft and twelve locator cursors before navigation, then verifies the other draft. There is no change to `MobileSession.selectThread`, target/credential checks, claim/consume or production routing. No sleep or guard relaxation is added.

Parent execution requires a new fresh lease and writer/reader processes after the separate failed-lease maintenance review. Keep the failed original lease and all its tickets/drafts/proofs. The observer and remaining cold cases were not run in stage15. The corrected source still needs the parent's new cold5 matrix and fingerprint; compilation does not convert the old reader failure to a pass.

## Foreground presentation terminal contract

The stage17 native failure at `system.intents.single()` showed an empty list after a wait on `system.posts.size`. The synthetic Main `post` callback appends to posts first, then creates/appends the opaque Intent. The wait observed an intermediate callback product. This failure is compatible with a scheduling race and is not evidence of a production delivery failure.

The corrected test waits for the actual durable recovery outcome to become terminal and asserts `submitted-to-system`, which is recorded after the post callback and receipt finish. It then inspects the single post and Intent on Main, checks the exact event was delivered through the reading port, and verifies only the opaque original ticket appears in the Intent. The existing system double still asserts P7's unread conversation count is exactly three. No sleep, callback reordering, production suppression bypass or weakened assertion is introduced.

Parent execution uses this selector as a new candidate result, retaining the original stage17 failure. Source preparation and combined assembly are auxiliary evidence only. F52/F53, UI11, S15/S16 mappings remain; iOS source/evidence and paused A6 are unaffected.
