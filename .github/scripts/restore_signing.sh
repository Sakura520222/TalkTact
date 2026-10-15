#!/usr/bin/env bash
set -euo pipefail

test -n "${KEYSTORE_BASE64:-}" && test -n "${KEYSTORE_PASSWORD:-}" && test -n "${KEYSTORE_ALIAS:-}" || {
  echo '::error::Configure all three FORK_KEYSTORE_* secrets before signing.'
  exit 1
}
umask 077
printf '%s' "$KEYSTORE_BASE64" | base64 --decode > "$RUNNER_TEMP/talktact.p12"
echo "KEYSTORE_PATH=$RUNNER_TEMP/talktact.p12" >> "$GITHUB_ENV"
