#!/bin/sh
set -eu
cd "$SRCROOT/../.."
# Xcode exports SDK_NAME, ARCHS, CONFIGURATION and framework signing settings.
# Kotlin's official integration task selects and merges the matching targets.
node scripts/mobile/gradle.mjs :mobile-core:embedAndSignAppleFrameworkForXcode
