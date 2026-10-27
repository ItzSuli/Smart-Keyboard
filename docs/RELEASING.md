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

Android only installs an update if it is signed with the same key as the installed app; then the
update keeps everything (settings, learned words, downloaded offline models). Until the repository
has a signing key in its secrets, every CI build is signed with a one-off key, so going from one
release to the next means backup → uninstall → install → restore (the release notes say so). Set up
a permanent key once and every later update installs over the previous one, also from inside the
app (Settings → Updates & backup):

1. Create a keystore (keep the file and password safe and private; never commit them):

   ```bash
   PW="$(openssl rand -hex 16)"; echo "$PW" > password.txt
   keytool -genkeypair -keystore smart-keyboard-release.p12 -storetype PKCS12 -alias smartkeyboard \
     -keyalg EC -groupname secp256r1 -sigalg SHA256withECDSA -validity 36500 \
     -storepass "$PW" -keypass "$PW" -dname "CN=Smart Keyboard, O=ItzSuli"
   base64 -w0 smart-keyboard-release.p12 > keystore-base64.txt   # macOS: base64 -i … -o …
   ```

2. In GitHub: **Settings → Secrets and variables → Actions → New repository secret**, add two:

   | Secret | Value |
   |---|---|
   | `SK_KEYSTORE_B64` | contents of `keystore-base64.txt` |
   | `SK_KEYSTORE_PASSWORD` | contents of `password.txt` |

   (`SK_KEY_ALIAS` defaults to `smartkeyboard` and `SK_KEY_PASSWORD` to the keystore password;
   set them only for a keystore made differently.)

3. Re-run the workflow ("Run workflow" in the Actions tab). From then on all releases share this
   key; the release notes show its SHA-256 fingerprint.

## Language packs

`tools/build_dictionaries.py` rebuilds `app/src/main/assets/dict/*.dict` from FrequencyWords and
Tatoeba (downloaded into `tools/.cache/`), plus the curated slang and swear lists in
`tools/wordlists/`. Add words to those lists and re-run:

```bash
python3 tools/build_dictionaries.py          # all languages + emoji
python3 tools/build_dictionaries.py de       # just German
```
