#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
if [[ "${1:-}" != "--skip-build" ]]; then
  mvn clean verify
fi

VERSION_OUTPUT="$(java -jar target/regnexe-cli.jar --version)"
if [[ ! "$VERSION_OUTPUT" =~ ^rex\ v([0-9]+\.[0-9]+\.[0-9]+)$ ]]; then
  echo "error: expected a release version, received: $VERSION_OUTPUT" >&2
  exit 1
fi
RELEASE_NAME="regnexe-cli-${BASH_REMATCH[1]}"
RELEASE_DIR="$ROOT_DIR/target/release"
STAGING_DIR="$(mktemp -d "${TMPDIR:-/tmp}/regnexe-package.XXXXXX")"
trap 'rm -rf "$STAGING_DIR"' EXIT
mkdir -p "$STAGING_DIR/$RELEASE_NAME" "$RELEASE_DIR"
cp target/regnexe-cli.jar install.sh README.md README_zh.md LICENSE "$STAGING_DIR/$RELEASE_NAME/"
cp -R docs scripts "$STAGING_DIR/$RELEASE_NAME/"
(
  cd "$STAGING_DIR/$RELEASE_NAME"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum regnexe-cli.jar install.sh README.md README_zh.md LICENSE > SHA256SUMS
  else
    shasum -a 256 regnexe-cli.jar install.sh README.md README_zh.md LICENSE > SHA256SUMS
  fi
)
COPYFILE_DISABLE=1 tar -czf "$RELEASE_DIR/$RELEASE_NAME.tar.gz" -C "$STAGING_DIR" "$RELEASE_NAME"
(
  cd "$RELEASE_DIR"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$RELEASE_NAME.tar.gz" > "$RELEASE_NAME.tar.gz.sha256"
  else
    shasum -a 256 "$RELEASE_NAME.tar.gz" > "$RELEASE_NAME.tar.gz.sha256"
  fi
)
echo "Prepared $RELEASE_DIR/$RELEASE_NAME.tar.gz"
echo "Prepared $RELEASE_DIR/$RELEASE_NAME.tar.gz.sha256"
