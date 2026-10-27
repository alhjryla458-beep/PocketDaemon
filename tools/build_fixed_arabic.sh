#!/usr/bin/env bash
set -Eeuo pipefail

cd "$(dirname "$0")/.."

echo "PocketDaemon: checking build tools..."
command -v flutter >/dev/null || { echo "ERROR: Flutter SDK is missing."; exit 10; }
command -v java >/dev/null || { echo "ERROR: Java is missing."; exit 11; }
command -v zip >/dev/null || { echo "ERROR: zip is missing."; exit 12; }
command -v sha256sum >/dev/null || { echo "ERROR: sha256sum is missing."; exit 13; }

echo "Flutter version:"
flutter --version

VERSION_LINE="$(grep -E '^version:[[:space:]]*[0-9]+\.[0-9]+\.[0-9]+\+[0-9]+' pubspec.yaml | head -n 1 || true)"
if [[ -z "$VERSION_LINE" ]]; then
  echo "ERROR: Could not read app version from pubspec.yaml."
  exit 14
fi
VERSION="$(printf '%s' "$VERSION_LINE" | sed -E 's/^version:[[:space:]]*([0-9]+\.[0-9]+\.[0-9]+)\+[0-9]+/\1/')"
VERSION_CODE="$(printf '%s' "$VERSION_LINE" | sed -E 's/^version:[[:space:]]*[0-9]+\.[0-9]+\.[0-9]+\+([0-9]+)/\1/')"
ARTIFACT_VERSION="${VERSION}-${VERSION_CODE}"

echo "Resolving Flutter dependencies..."
flutter pub get

echo "Building release APK..."
flutter build apk --release

APK_SOURCE="build/app/outputs/flutter-apk/app-release.apk"
if [[ ! -s "$APK_SOURCE" ]]; then
  echo "ERROR: Flutter did not produce the expected APK: $APK_SOURCE"
  exit 20
fi

RELEASE_DIR="releases"
mkdir -p "$RELEASE_DIR"
RELEASE_DIR_ABS="$(cd "$RELEASE_DIR" && pwd)"
APK_OUT="$RELEASE_DIR_ABS/PocketDaemon-$ARTIFACT_VERSION.apk"
ZIP_OUT="$RELEASE_DIR_ABS/PocketDaemon-$ARTIFACT_VERSION-magisk.zip"
SUMS_OUT="$RELEASE_DIR_ABS/SHA256SUMS.txt"
cp "$APK_SOURCE" "$APK_OUT"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
cp -a magisk "$TMP_DIR/module"
mkdir -p "$TMP_DIR/module/system/priv-app/PocketDaemon"
cp "$APK_SOURCE" "$TMP_DIR/module/system/priv-app/PocketDaemon/PocketDaemon.apk"

if [[ -f "$TMP_DIR/module/module.prop" ]]; then
  sed -i -E \
    -e "s/^version=.*/version=$VERSION/" \
    -e "s/^versionCode=.*/versionCode=$VERSION_CODE/" \
    "$TMP_DIR/module/module.prop"
else
  echo "ERROR: magisk/module.prop is missing."
  exit 21
fi

rm -f "$ZIP_OUT"
(
  cd "$TMP_DIR/module"
  zip -qr "$ZIP_OUT" .
)

sha256sum "$APK_OUT" "$ZIP_OUT" > "$SUMS_OUT"

echo
echo "BUILD SUCCESS: artifacts created in $RELEASE_DIR/"
echo " - $APK_OUT"
echo " - $ZIP_OUT"
echo " - $SUMS_OUT"
echo
echo "Important: successful compilation does NOT prove that cellular-call audio works."
