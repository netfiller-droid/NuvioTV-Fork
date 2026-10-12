# GitHub Secrets für Android TV Release

Diese Secrets müssen im Repository unter **Settings → Secrets and variables → Actions** hinzugefügt werden.

## Erforderliche Secrets

| Secret Name | Beschreibung | Beispiel-Wert |
|-------------|--------------|---------------|
| `LOCAL_PROPERTIES_BASE64` | Base64-kodierte `local.properties` Datei | siehe unten |
| `LOCAL_DEV_PROPERTIES_BASE64` | (Optional) Base64-kodierte `local.dev.properties` | siehe unten |
| `NUVIO_RELEASE_KEYSTORE_BASE64` | Base64-kodiertes Release-Keystore (.jks) | `base64 -w0 release.keystore` |
| `NUVIO_RELEASE_KEY_ALIAS` | Key-Alias im Keystore | `nuvio-release-key` |
| `NUVIO_RELEASE_KEY_PASSWORD` | Passwort für den Key | `******` |
| `NUVIO_RELEASE_STORE_PASSWORD` | Passwort für das Keystore | `******` |

---

## local.properties Vorlage

Erstelle eine Datei `local.properties` mit folgendem Inhalt (Werte anpassen!):

```properties
# Android SDK (wird von GitHub Actions automatisch gesetzt, aber als Fallback)
sdk.dir=/opt/android-sdk

# Release Signing (werden zur Build-Zeit überschrieben, aber als Platzhalter nötig)
NUVIO_RELEASE_STORE_FILE=/tmp/placeholder.jks
NUVIO_RELEASE_KEY_ALIAS=placeholder
NUVIO_RELEASE_KEY_PASSWORD=placeholder
NUVIO_RELEASE_STORE_PASSWORD=placeholder

# API Keys (für TMDB, Trakt, etc.)
TMDB_API_KEY=your_tmdb_api_key
TRAKT_CLIENT_ID=your_trakt_client_id
TRAKT_CLIENT_SECRET=your_trakt_client_secret
SIMKL_CLIENT_ID=your_simkl_client_id
MDBLIST_CLIENT_ID=your_mdblist_client_id

# Optional: IMDb Ratings
IMDB_RATINGS_API_BASE_URL=https://your-api.example.com

# Optional: Parental Guide
PARENTAL_GUIDE_API_URL=https://your-api.example.com

# Optional: Trailer API
TRAILER_API_URL=https://your-api.example.com

# Optional: IntroDB
INTRODB_API_URL=https://api.introdb.com

# Optional: TV Login Web URLs
TV_LOGIN_WEB_BASE_URL=https://nuvio.tv/tv-login
DEVICE_LOGIN_WEB_BASE_URL=https://nuvio.tv/link
```

**Dann base64 encoden:**
```bash
base64 -w0 local.properties
# Ausgabe als LOCAL_PROPERTIES_BASE64 Secret speichern
```

---

## local.dev.properties Vorlage (Optional)

Für Development-spezifische Werte (überschreibt local.properties):

```properties
# Dev-spezifische API Keys (falls anders als Release)
TMDB_API_KEY=dev_tmdb_key
TRAKT_CLIENT_ID=dev_trakt_id
TRAKT_CLIENT_SECRET=dev_trakt_secret
```

**Base64 encoden:**
```bash
base64 -w0 local.dev.properties
# Als LOCAL_DEV_PROPERTIES_BASE64 speichern
```

---

## Release Keystore erstellen

```bash
# Keystore generieren (einmalig!)
keytool -genkeypair \
  -v \
  -keystore nuvio-release.keystore \
  -storepass YOUR_STORE_PASSWORD \
  -alias nuvio-release-key \
  -keypass YOUR_KEY_PASSWORD \
  -dname "CN=Nuvio TV, O=Nuvio, C=DE" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000

# Base64 encoden
base64 -w0 nuvio-release.keystore
# Ausgabe als NUVIO_RELEASE_KEYSTORE_BASE64 speichern
```

**WICHTIG:** Keystore-Datei sicher aufbewahren! Ohne sie können keine Updates mehr veröffentlicht werden.

---

## Secrets in GitHub hinzufügen

1. Repository öffnen → **Settings** → **Secrets and variables** → **Actions**
2. **New repository secret** für jeden Eintrag oben
3. Name exakt wie in der Tabelle (Großbuchstaben, Unterstriche)
4. Wert = base64-String (ohne Zeilenumbrüche)

---

## Testen

Nach Hinzufügen aller Secrets:
1. Actions → **Build Android TV Release** → **Run workflow**
2. Mode: `dry-run` (testet nur Metadaten)
3. Dann `draft` (baut APKs, erstellt Draft-Release)
4. Bei Erfolg: `publish` (veröffentlicht Release)