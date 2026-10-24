#!/bin/bash
# Install only in the private CI SDK beside the unmodified vendor sdkmanager.real.
# Translate the obsolete package spelling only for explicitly allowed revisions.
translated=()
legacy=false
if [ "${GITHUB_REPOSITORY:-}" = BCAI/agent-hub-app ]; then
  revision=$(git rev-parse HEAD 2>/dev/null) || revision=
  for allowed in ${AGENT_HUB_CI_LEGACY_SHAS:-}; do
    if [ -n "$revision" ] && [ "$revision" = "$allowed" ]; then
      legacy=true
      break
    fi
  done
fi
for argument in "$@"; do
  if [ "$legacy" = true ] && [ "$argument" = 'platforms;android-37' ]; then
    printf 'Legacy SDK package spelling: %s -> platforms;android-37.0\n' "$argument" >&2
    argument='platforms;android-37.0'
  fi
  translated+=("$argument")
done
exec "${BASH_SOURCE[0]}.real" "${translated[@]}"
