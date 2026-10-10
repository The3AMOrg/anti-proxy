# Releasing AntiProxy

The app updates itself from GitHub releases of `The3AMOrg/anti-proxy`, the same way AttendX does:

- On start (and once a day in the background, with a notification) it asks the GitHub API for the
  latest release and compares its tag with `versionName`.
- If newer, the update sheet shows the release notes and downloads the APK for the phone
  (`-arm64-v8a`, `-armeabi-v7a`, `-x86_64`, else `-universal`), then opens the installer.
- After an upgrade it shows the changelog once: the notes of every release up to the installed one.

**The repository must stay public** (it is, since 2026-10-11). The app has no GitHub token (and must
never ship one), so for a private repository the API returns nothing and the app says "No releases
have been published yet". Unauthenticated checks are limited to 60 requests an hour per IP address.

## One-time setup (done 2026-10-11)

Done for The3AMOrg/anti-proxy: keystore created, the five secrets set, release fingerprints in Firebase
and `assetlinks.json` (deployed). The keystore and its password are **outside the repo** in
`C:\Users\jyoti\AntiProxy-release\` on Jyotirmoy's PC; back both up (password manager). Release key:
SHA-1 `BC:54:34:53:0D:2D:13:A9:30:0F:5B:B7:79:CC:D2:A5:1B:A2:BC:8C`,
SHA-256 `8F:A9:AE:15:C4:8E:C8:42:62:DA:C5:E3:B3:97:5A:61:2C:BC:23:9C:1D:67:F2:AD:1B:81:F7:E3:54:0B:0C:37`.

Release builds produce four APKs (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `universal`); the only native
code is Compose's shape library, so they differ by about 50 KB.

For a new repository or a new key, the steps were:

1. Create a release keystore (keep it and its passwords safe; losing it means users must reinstall):
   `keytool -genkeypair -v -keystore antiproxy-release.jks -alias antiproxy -keyalg RSA -keysize 4096 -validity 10000`
2. Add repository secrets (Settings → Secrets and variables → Actions):
   - `KEYSTORE_BASE64`: `base64 -w0 antiproxy-release.jks`
   - `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`antiproxy`), `KEY_PASSWORD`
   - `GOOGLE_SERVICES_JSON`: `base64 -w0 app/google-services.json`
3. Register the release key's SHA-1 and SHA-256 in Firebase (Google sign-in) and add the SHA-256 to
   `hosting/public/.well-known/assetlinks.json` (join links), then `firebase deploy --only hosting`.
   Get them with `keytool -list -v -keystore antiproxy-release.jks -alias antiproxy`.

Phones running a **debug** build can't update to a release build (different signing key): uninstall
the debug build once, then install the release APK.

## Each release

1. Bump `versionCode` (+1) and `versionName` (e.g. `1.1.0`) in `app/build.gradle.kts`.
2. Write the notes in `changelog/v1.1.0.md` (Markdown: `#` headings, `-` bullets, `**bold**`, links, `@mentions`).
3. Commit, then tag and push: `git tag v1.1.0 && git push origin v1.1.0`.
4. The workflow checks the tag matches `versionName`, builds and signs the APK, and publishes the release.
   A tag with a hyphen (`v1.1.0-beta1`) is published as a pre-release: only users who turned on
   "Include pre-releases" are offered it.
