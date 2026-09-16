#!/usr/bin/env bash
# Creates the local signing keystore used for release builds.
# The keystore is deliberately not committed; every checkout makes its own.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/keystore/narrate-release.jks"
PASSWORD="${NARRATE_KEYSTORE_PASSWORD:-narrate}"
ALIAS="${NARRATE_KEY_ALIAS:-narrate}"

if [ -f "$OUT" ]; then
  echo "Keystore already exists at $OUT"
  exit 0
fi

mkdir -p "$ROOT/keystore"
keytool -genkeypair -v \
  -keystore "$OUT" \
  -storetype PKCS12 \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias "$ALIAS" \
  -storepass "$PASSWORD" \
  -keypass "${NARRATE_KEY_PASSWORD:-$PASSWORD}" \
  -dname "CN=Narrate, OU=Narrate, O=Narrate, C=US"

echo "Wrote $OUT"
