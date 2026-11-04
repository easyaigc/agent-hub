# Synthetic notification sound fixtures

The ten mono, lossless files listed in `task-sound-fixtures.manifest.json` contain the deterministic built-in chime: 660 Hz then 880 Hz, 0.24 seconds per part, 22050 samples/second, with the same fade/decay formula as `scripts/mobile/generate-notification-sounds.mjs`. Every sample was independently compared, including higher-precision source data. They contain no recorded user audio.

The original Android sound-import tests consume this directory as `androidTest` assets; migrating those consumers is a separate integration group. This data commit does not establish current Android runtime test coverage. The stereo and compressed-reference candidates remain excluded because their original generation/encoding chain was not confirmed. No microphone, user library or real notification configuration is part of this corpus.
