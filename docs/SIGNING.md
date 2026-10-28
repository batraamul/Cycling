# App signing

Android only installs an update over an existing app if **both APKs are signed with the same key**.
Lose the key and you can never update the installed app again (you would have to uninstall, losing data).

## The release key
- File: `cycling-release.jks` (PKCS12, RSA 4096, alias `cycling`, valid until 2054).
- Certificate SHA-256: `A8:7E:BD:7C:22:12:2F:7D:02:AB:91:00:85:31:2D:15:69:52:23:57:D0:1F:88:37:4D:E3:A5:74:AC:6C:70:92`
- The key and its password are **never** committed. `.gitignore` blocks `*.jks`, `*.keystore` and `keystore.properties`.
- Keep two offline backups (e.g. password manager + encrypted USB drive).

## Local release builds
Create `keystore.properties` in the project root (git-ignored):
```properties
storeFile=/absolute/path/to/cycling-release.jks
storePassword=...
keyAlias=cycling
keyPassword=...
```
Then `./gradlew assembleRelease`. Without this file the release build is produced unsigned (not installable) —
use the debug build instead.

## GitHub Actions secrets (for automatic releases)
Repository → **Settings › Secrets and variables › Actions › New repository secret**:

| Secret | Value |
|---|---|
| `CYCLING_KEYSTORE_BASE64` | output of `base64 -w0 cycling-release.jks` (macOS: `base64 -i cycling-release.jks`) |
| `CYCLING_KEYSTORE_PASSWORD` | the store password |
| `CYCLING_KEY_ALIAS` | `cycling` |
| `CYCLING_KEY_PASSWORD` | the key password |

Then release a new version:
1. Bump `appVersionCode` and `appVersionName` in `app/build.gradle.kts`.
2. Add `docs/release-notes/vX.Y.Z.md`.
3. `git tag vX.Y.Z && git push origin vX.Y.Z` — the Release workflow builds, verifies the signature and publishes.
