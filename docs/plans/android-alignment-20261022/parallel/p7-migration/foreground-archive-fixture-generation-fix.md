# Archive fixture after generation replacement

Operation: `parent-p7-archive-stale-fixture-20261029-1`.

The parent reported the authority producer pair and independent-PID cold writer/reader passing with source fingerprint `141053e3656483e0f2bead46e00d2e4a4373eda46fee650d18e01f3994a6d4bc`. Its current Archive + Foreground batch passed 18 of 19 tests. These are parent-run native results, not new device execution by the migration owner.

The single failure was at line 81 of `nonemptyDestinationIdentityJournalsAuthorityAndUnknownStayExactAcrossRollbackAndPublication`. That test had already published a new generation, reopened `current`, checked retained originals and verified that the retired root rejects a late write. Its subsequent independent destination-only scenario incorrectly derived two new child stores from the retired `owned.storage`. The existing `child` fence correctly captured the retired-generation failure and rejected the later write.

The fixture helper now accepts an explicit parent, and those two later stores derive from `current`. All original rollback, ciphertext, authority, unknown outcome and rejected retired-write assertions remain. Production storage and activation behavior are unchanged.

Validation: inspected the exact parent failure stack and source; no device, Host, model task or old cold-writer replay was performed here. The parent should run the corrected native test on the new combination; the original 18/19 result remains preserved.

N035/N036/N064/N069 scope and the Android system migration entry remain unchanged by this fixture correction. The system entry, large-text streaming and EasyWork fence archive work remain open; no full P7 or cross-platform acceptance claim follows from this correction.
