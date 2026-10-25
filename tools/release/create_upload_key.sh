#!/usr/bin/env bash
# Creates a local Play upload key and keystore.properties. Both are git-ignored.
# Keep a backup of the keystore somewhere safe: losing it means asking Play support to reset the upload key.
set -euo pipefail
cd "$(dirname "$0")/../.."
if [[ -f upload-keystore.jks ]]; then
  echo "upload-keystore.jks already exists; refusing to overwrite." >&2
  exit 1
fi
read -r -s -p "Keystore password (min 6 chars): " PASS; echo
DNAME=${HATCHSLIDE_KEY_DNAME:-"CN=HATCHSLIDE Upload, O=HATCHSLIDE"}
keytool -genkeypair -v -keystore upload-keystore.jks -alias upload -keyalg RSA -keysize 4096 \
  -validity 10000 -storepass "$PASS" -keypass "$PASS" -dname "$DNAME"
cat > keystore.properties <<PROPS
storeFile=upload-keystore.jks
storePassword=$PASS
keyAlias=upload
keyPassword=$PASS
PROPS
chmod 600 keystore.properties upload-keystore.jks
echo "Created upload-keystore.jks and keystore.properties."
