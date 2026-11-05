#!/bin/sh
set -eu
ios_core_script_directory=$(CDPATH= cd "$(dirname "$0")" && pwd)
cd "$ios_core_script_directory/../.."
# Xcode exports SDK_NAME, ARCHS, CONFIGURATION and framework signing settings.
# Kotlin's official integration task selects and merges the matching targets.
"${NODE_BINARY:-node}" scripts/mobile/gradle.mjs :mobile-core:embedAndSignAppleFrameworkForXcode
