#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INSTALL_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/regnexe-install-test.XXXXXX")"
trap 'rm -rf "$INSTALL_ROOT"' EXIT

cd "$ROOT_DIR"

if [[ "${1:-}" != "--skip-build" ]]; then
  echo "==> mvn -q -o verify"
  mvn -q -o verify
fi

echo "==> INSTALL_DIR=$INSTALL_ROOT/opt BIN_DIR=$INSTALL_ROOT/bin bash install.sh"
(
  cd "$INSTALL_ROOT"
  INSTALL_DIR="$INSTALL_ROOT/opt" BIN_DIR="$INSTALL_ROOT/bin" bash "$ROOT_DIR/install.sh"
)

echo "==> Check --version"
VERSION_OUTPUT="$("$INSTALL_ROOT/bin/rex" --version)"
echo "$VERSION_OUTPUT"
[[ "$VERSION_OUTPUT" =~ ^rex\ v[0-9]+\.[0-9]+\.[0-9]+ ]]

echo "==> Check --help"
HELP_OUTPUT="$("$INSTALL_ROOT/bin/rex" --help)"
[[ "$HELP_OUTPUT" == *"Usage:"* && "$HELP_OUTPUT" == *"--session"* ]]

echo "==> Check JAVA_OPTS forwarding"
JAVA_OPTS="-Drex.smoke=true" "$INSTALL_ROOT/bin/rex" --version

echo "Install smoke test passed."
