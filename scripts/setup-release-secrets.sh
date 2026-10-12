#!/usr/bin/env bash
# Helper-Skript: Generiert Keystore und encodet Properties für GitHub Secrets
# Ausführung: ./scripts/setup-release-secrets.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPTS_DIR="$ROOT/scripts"
KEYSTORE_DIR="$ROOT/keystore"
KEYSTORE_FILE="$KEYSTORE_DIR/nuvio-release.keystore"
LOCAL_PROPS="$ROOT/local.properties"
LOCAL_DEV_PROPS="$ROOT/local.dev.properties"

echo "=== Nuvio TV Release Secrets Setup ==="
echo ""

# 1. Keystore generieren
if [[ ! -f "$KEYSTORE_FILE" ]]; then
    echo "📝 Keystore erstellen..."
    mkdir -p "$KEYSTORE_DIR"
    
    read -sp "Keystore-Passwort (STORE_PASSWORD): " STORE_PASS
    echo
    read -sp "Key-Passwort (KEY_PASSWORD): " KEY_PASS
    echo
    read -p "Key-Alias [nuvio-release-key]: " KEY_ALIAS
    KEY_ALIAS="${KEY_ALIAS:-nuvio-release-key}"
    read -p "CN (Name) [Nuvio TV]: " CN
    CN="${CN:-Nuvio TV}"
    read -p "O (Organisation) [Nuvio]: " ORG
    ORG="${ORG:-Nuvio}"
    read -p "C (Land) [DE]: " COUNTRY
    COUNTRY="${COUNTRY:-DE}"
    
    keytool -genkeypair \
        -v \
        -keystore "$KEYSTORE_FILE" \
        -storepass "$STORE_PASS" \
        -alias "$KEY_ALIAS" \
        -keypass "$KEY_PASS" \
        -dname "CN=$CN, O=$ORG, C=$COUNTRY" \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000
    
    echo "✅ Keystore erstellt: $KEYSTORE_FILE"
else
    echo "ℹ️  Keystore existiert bereits: $KEYSTORE_FILE"
fi

# 2. local.properties Template
if [[ ! -f "$LOCAL_PROPS" ]]; then
    echo ""
    echo "📝 local.properties Template erstellen..."
    cat > "$LOCAL_PROPS" << 'EOF'
# Android SDK (wird von GitHub Actions automatisch gesetzt)
sdk.dir=/opt/android-sdk

# Release Signing (Platzhalter, werden zur Build-Zeit überschrieben)
NUVIO_RELEASE_STORE_FILE=/tmp/placeholder.jks
NUVIO_RELEASE_KEY_ALIAS=placeholder
NUVIO_RELEASE_KEY_PASSWORD=placeholder
NUVIO_RELEASE_STORE_PASSWORD=placeholder

# === API Keys (HIER DEINE ECHTEN KEYS EINTRAGEN) ===
TMDB_API_KEY=
TRAKT_CLIENT_ID=
TRAKT_CLIENT_SECRET=
SIMKL_CLIENT_ID=
MDBLIST_CLIENT_ID=

# Optional APIs
IMDB_RATINGS_API_BASE_URL=
PARENTAL_GUIDE_API_URL=
TRAILER_API_URL=
INTRODB_API_URL=

# Web URLs
TV_LOGIN_WEB_BASE_URL=https://nuvio.tv/tv-login
DEVICE_LOGIN_WEB_BASE_URL=https://nuvio.tv/link
EOF
    echo "✅ Template erstellt: $LOCAL_PROPS"
    echo "⚠️  Bitte API Keys in $LOCAL_PROPS eintragen!"
else
    echo "ℹ️  local.properties existiert bereits"
fi

# 3. local.dev.properties Template
if [[ ! -f "$LOCAL_DEV_PROPS" ]]; then
    echo ""
    echo "📝 local.dev.properties Template erstellen..."
    cat > "$LOCAL_DEV_PROPS" << 'EOF'
# Dev-spezifische Werte (optional, überschreibt local.properties)
# TMDB_API_KEY=
# TRAKT_CLIENT_ID=
# TRAKT_CLIENT_SECRET=
EOF
    echo "✅ Template erstellt: $LOCAL_DEV_PROPS"
fi

# 4. Base64 Ausgaben für GitHub Secrets
echo ""
echo "=== GitHub Secrets Werte (kopieren & in GitHub einfügen) ==="
echo ""

if [[ -f "$KEYSTORE_FILE" ]]; then
    echo "--- NUVIO_RELEASE_KEYSTORE_BASE64 ---"
    base64 -w0 "$KEYSTORE_FILE"
    echo ""
    echo ""
fi

if [[ -f "$LOCAL_PROPS" ]]; then
    echo "--- LOCAL_PROPERTIES_BASE64 ---"
    base64 -w0 "$LOCAL_PROPS"
    echo ""
    echo ""
fi

if [[ -f "$LOCAL_DEV_PROPS" ]]; then
    echo "--- LOCAL_DEV_PROPERTIES_BASE64 ---"
    base64 -w0 "$LOCAL_DEV_PROPS"
    echo ""
    echo ""
fi

echo "--- Weitere Secrets (manuell eintragen) ---"
echo "NUVIO_RELEASE_KEY_ALIAS=$KEY_ALIAS"
echo "NUVIO_RELEASE_KEY_PASSWORD=<dein-key-passwort>"
echo "NUVIO_RELEASE_STORE_PASSWORD=<dein-store-passwort>"

echo ""
echo "✅ Fertig! Secrets in GitHub unter Settings → Secrets and variables → Actions hinzufügen."
echo "📖 Siehe auch: GITHUB_SECRETS_SETUP.md"