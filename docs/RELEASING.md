# Releasing

GitHub Actions (`.github/workflows/build.yml`) runs every test on each push and builds a signed
release APK. A GitHub Release `v<versionName>` with the APK is published when the push is:

- to `main`,
- a `v*` tag,
- a manual run ("Run workflow" in the Actions tab), or
- any commit whose message contains `[release]`.

Publishing the same version again replaces that release. Bump `versionCode` and `versionName` in
`app/build.gradle.kts` for a new version.

## Signing key (do this once)

Android only installs an update if it is signed with the same key as the installed app. Until the
repository has a signing key in its secrets, every CI build is signed with a one-off key, so going
from one release to the next means export → uninstall → install → import (the release notes say
so). Set up a permanent key once and every later update installs over the previous one:

1. Create a keystore on your computer (keep the file and passwords safe and private; never commit them):

   ```bash
   keytool -genkeypair -v -keystore smartkeyboard-release.jks -storetype PKCS12 \
     -alias smartkeyboard -keyalg RSA -keysize 4096 -validity 36500
   base64 -w0 smartkeyboard-release.jks > keystore.b64   # macOS: base64 -i smartkeyboard-release.jks -o keystore.b64
   ```

2. In GitHub: **Settings → Secrets and variables → Actions → New repository secret**, add:

   | Secret | Value |
   |---|---|
   | `SK_KEYSTORE_B64` | contents of `keystore.b64` |
   | `SK_KEYSTORE_PASSWORD` | the keystore password |
   | `SK_KEY_ALIAS` | `smartkeyboard` |
   | `SK_KEY_PASSWORD` | the key password (same as the keystore password if you didn't set a separate one) |

3. Re-run the workflow. From then on all releases share this key.

## Language packs

`tools/build_dictionaries.py` rebuilds `app/src/main/assets/dict/*.dict` from FrequencyWords and
Tatoeba (downloaded into `tools/.cache/`), plus the curated slang and swear lists in
`tools/wordlists/`. Add words to those lists and re-run:

```bash
python3 tools/build_dictionaries.py          # all languages + emoji
python3 tools/build_dictionaries.py de       # just German
```
