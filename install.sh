#!/usr/bin/env bash
set -euo pipefail

APP_NAME="regnexe-cli"
JAR_NAME="${APP_NAME}.jar"
SOURCE_JAR="${SOURCE_JAR:-target/${JAR_NAME}}"
INSTALL_DIR="${INSTALL_DIR:-/opt/regnexe}"
BIN_DIR="${BIN_DIR:-/usr/local/bin}"
BIN_NAME="${BIN_NAME:-rex}"

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  cat <<EOF
Usage: bash install.sh

Environment overrides:
  SOURCE_JAR   Path to the packaged jar. Default: target/regnexe-cli.jar
  INSTALL_DIR  Directory for the installed jar. Default: /opt/regnexe
  BIN_DIR      Directory for the rex wrapper. Default: /usr/local/bin
  BIN_NAME     Wrapper command name. Default: rex

Example without sudo:
  INSTALL_DIR="\$HOME/.local/opt/regnexe" BIN_DIR="\$HOME/.local/bin" bash install.sh
EOF
  exit 0
fi

if ! command -v java >/dev/null 2>&1; then
  echo "error: java is required but was not found on PATH" >&2
  exit 1
fi

if [[ ! -f "$SOURCE_JAR" ]]; then
  echo "error: $SOURCE_JAR not found" >&2
  echo "run: mvn package -DskipTests" >&2
  exit 1
fi

mkdir -p "$INSTALL_DIR" "$BIN_DIR"
cp "$SOURCE_JAR" "$INSTALL_DIR/$JAR_NAME"

cat > "$BIN_DIR/$BIN_NAME" <<EOF
#!/usr/bin/env bash
exec java \${JAVA_OPTS:-} -jar "$INSTALL_DIR/$JAR_NAME" "\$@"
EOF

chmod +x "$BIN_DIR/$BIN_NAME"

echo "Installed $APP_NAME to $INSTALL_DIR/$JAR_NAME"
echo "Installed wrapper to $BIN_DIR/$BIN_NAME"
