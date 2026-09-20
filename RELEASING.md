# Releasing NetScope

## One-time: create a signing key

An APK must be signed by a key you keep. **If the key is lost, no future release can
upgrade an installed build in place** — Android refuses an update signed by a different
key, and users would have to uninstall and reinstall, losing their scan history.

```bash
keytool -genkeypair -v \
  -keystore netscope-release.jks \
  -alias netscope -keyalg RSA -keysize 4096 -validity 10000
```

Keep `netscope-release.jks` somewhere safe and out of the repository. `.gitignore`
already excludes `*.jks`, `*.keystore` and `keystore.properties`.

## One-time: configure GitHub Actions

Add four repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 netscope-release.jks` |
| `KEYSTORE_PASSWORD` | the store password |
| `KEY_ALIAS` | `netscope` |
| `KEY_PASSWORD` | the key password |

Without these the release workflow still builds and tests, but publishes the APK as a
workflow artifact instead of attaching it to the release, so a debug-signed build can
never be mistaken for an official one.

## Cutting a release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Commit that bump.
3. Tag and push:

   ```bash
   git tag -a v0.2.0 -m "NetScope v0.2.0"
   git push origin v0.2.0
   ```

Pushing a `v*` tag triggers `.github/workflows/release.yml`, which runs the unit tests,
assembles the signed release APK and attaches it to the GitHub release.

## Building a release APK locally

```bash
cat > keystore.properties <<'PROPS'
storeFile=/absolute/path/to/netscope-release.jks
storePassword=...
keyAlias=netscope
keyPassword=...
PROPS

./gradlew :app:assembleRelease
```

The APK lands at `app/build/outputs/apk/release/app-release.apk`.

Verify what you are about to publish before publishing it:

```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk

$ANDROID_HOME/build-tools/35.0.0/aapt2 dump badging \
  app/build/outputs/apk/release/app-release.apk | grep -E '^package|uses-permission'
```

The permission list should contain exactly `INTERNET`, `ACCESS_NETWORK_STATE`,
`ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `NEARBY_WIFI_DEVICES` (with
`neverForLocation`), `ACCESS_FINE_LOCATION` (capped at API 32), `ACCESS_LOCAL_NETWORK`
and `POST_NOTIFICATIONS`. Anything else has been pulled in by a dependency's manifest
merge and should be investigated rather than accepted — that is exactly how
`WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` and `FOREGROUND_SERVICE` once appeared, via a
WorkManager dependency for a feature the app did not yet have.

`ACCESS_LOCAL_NETWORK` is inert on platforms that do not define it and is only enforced
for apps targeting the level that introduced it. When `targetSdk` is raised to that
level, re-test a denial path: LAN scanning must report `PERMISSION REQUIRED` and name
the features that still work, and the rest of the app must stay usable.
