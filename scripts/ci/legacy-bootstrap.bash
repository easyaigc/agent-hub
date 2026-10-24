# Compatibility for immutable revisions whose CI checked generated protocol imports
# before the workspace build. The runner supplies an explicit revision allowlist.
# Current workflows build prerequisites themselves and never enter this branch.
pnpm() {
  if [ "${1:-}" = check ] &&
    [ "${GITHUB_REPOSITORY:-}" = BCAI/agent-hub-app ] &&
    [ ! -f packages/protocol/dist/index.js ]; then
    local revision allowed
    revision=$(git rev-parse HEAD) || return
    for allowed in ${AGENT_HUB_CI_LEGACY_SHAS:-}; do
      if [ "$revision" = "$allowed" ]; then
        printf 'Legacy CI prerequisite build for checked-out revision %s\n' "$revision"
        command pnpm build || return
        break
      fi
    done
  fi
  command pnpm "$@"
}
