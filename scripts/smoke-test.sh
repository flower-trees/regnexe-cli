#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INSTALL_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/regnexe-install-test.XXXXXX")"

cd "$ROOT_DIR"

echo "==> mvn -q -o package -DskipTests"
mvn -q -o package -DskipTests

echo "==> INSTALL_DIR=$INSTALL_ROOT/opt BIN_DIR=$INSTALL_ROOT/bin bash install.sh"
INSTALL_DIR="$INSTALL_ROOT/opt" BIN_DIR="$INSTALL_ROOT/bin" bash install.sh

echo "==> $INSTALL_ROOT/bin/rex --version"
"$INSTALL_ROOT/bin/rex" --version

echo "Install smoke test passed."
echo "Temporary install root: $INSTALL_ROOT"

$INSTALL_ROOT/bin/rex
