# Release Process

Releases are signed APKs published as GitHub release assets. There is no app-store distribution, and there will not be one — the store machinery (fastlane metadata, an F-Droid recipe, the `plans/f-droid.md` publication plan) was removed on that basis; see TAWC_DSH_DESIGN.md §13.5.

## Versioning

- `versionName` is `major.minor[.patch]`. There is no breaking/non-breaking distinction for users — every release is expected to migrate existing installs forward — so the number tracks "how much app there is", not compatibility. It started at `0.1`; before the first release it was a bare counter (`1`).
- `versionName` in `app/build.gradle.kts` is the single source of truth, and `versionCode` is *derived* from it there: `major*10000 + minor*100 + patch` (`0.1` → 100, `0.2` → 200, `1.0` → 10000), so the two cannot drift and Android's monotonic-versionCode requirement holds. Deriving it is also why the first release could move `1` → `0.1` while the code went *up* (`1` → `100`) and installed builds still upgraded.
- Each release commit is tagged `v<versionName>` (annotated) — `v0.1`, not `v1`.

**Nothing else in the repo names a version statically**, which is why there is no
sync to perform on a bump: the app reads its version from `PackageManager` at
runtime, and `build-release-apk.sh` reads it back out of the built APK with
aapt2. `scripts/check-version-sync.sh` reports what the source says and validates
its shape — the name is a leftover from when it also checked an F-Droid changelog
file and recipe.

## Prep steps (agent)

When asked to prep a release:

1. Bump `versionName` in `app/build.gradle.kts` to the next version; the
   derived `versionCode` follows it (nothing else writes either down).
   Also check the shipped archive keyrings: none of the keys in
   `app/src/main/res/raw/debian_archive_keyring.asc` may be nearing
   expiry (currently 12/bookworm expires 2031, 13/trixie 2035), and if
   Debian has published a new suite key (14/forky…), add it — obtain
   from two independent origins and diff, per notes/installation.md
   "Bootstrap integrity". `ShippedPgpKeysTest` pins the expected
   fingerprints.
2. Re-run `scripts/gen-third-party-licenses.sh` and commit any change to
   `app/src/main/assets/licenses.json`. The APK is GPLv3 and
   carries permissive third-party notices, so the in-app attribution
   text must match what the release actually ships — see
   [licensing.md](licensing.md).
3. Draft release notes from `git log <last-tag>..` (first release: summarize the feature set instead). Keep them user-facing: features, fixes, known limitations. These become the GitHub release body — there is no in-repo changelog and no store listing to feed (see "Release notes" below).
4. Run `scripts/check-version-sync.sh`. It reports the version and rejects a malformed one, which is cheaper than finding out when a build fails at configuration time.
5. Commit as `release: v<versionName>`, then tag `v<versionName>` (the prep request counts as the explicit ask to commit/tag; do not push).
6. Hand off: print the human steps below with the concrete version filled in, plus the drafted notes (e.g. as a `--notes-file` in scratch or inline for copy/paste).

The sign step needs the keystore password, which only the maintainer holds —
`build-release-apk.sh` takes it from `KEYSTORE_PASS` or prompts for it. An
agent *can* run the whole script when handed the password in the environment;
what it must never do is write the password into a file. See "Keystore" below
for the one this project uses.

## Publish steps (human, as the key-owning user)

1. `scripts/build-release-apk.sh` — builds `assembleRelease` (default graphics `libhybris,turnip,none`), zipaligns, signs, verifies, and renames the artifact to `app/build/outputs/apk/release/tawc-dsh-v<versionName>.apk` (version read from the APK via aapt2). Add `--no-build` to sign an `app-release-unsigned.apk` that was already built, which skips the Gradle run.
2. Smoke-test that exact APK on the physical phone. **Uninstalling is part of it**: debug and release share the application id but not their signing key, so `adb install` refuses to overwrite, and uninstalling deletes `<app data>/distros/` — the container and the DSH install inside it. Salvage anything worth keeping first, e.g. with root:
   `adb shell "su -c 'tar -C /data/data/<id> -cf /sdcard/distros-backup.tar distros'"`
   Then `adb install -r app/build/outputs/apk/release/tawc-dsh-v<versionName>.apk`, launch, install a distro, wait for the WebView to reach DSH's own client (that means `dsh web` came up and the token in its URL was accepted), open the terminal from the floating menu, and flip the graphics backend in Settings. Note there is no desktop here: upstream's "run an app (e.g. lxterminal)" does not apply to this fork (TAWC_DSH_DESIGN.md §2) — what is being smoked is the harness, not a Wayland client. Worth also checking what release *removes*: install methods other than tawcroot are absent, and `LogScreenActivity` is app-internal, so an `am start` at it must be denied.
   For later releases add an install *over* the previous release, which is what catches signing/versionCode upgrade breakage. The release build differs from the dev loop (no debug methods, production graphics set), so dev-loop testing does not cover it.
3. Push `main` and the tag.
4. `gh release create v0.1 app/build/outputs/apk/release/tawc-dsh-v0.1.apk --title "tawc-dsh v0.1" --notes-file <notes>`.

## Debuggability over size

Release builds are deliberately NOT minified, obfuscated, or stripped (release block + `packaging.jniLibs.keepDebugSymbols` in `app/build.gradle.kts`): user-reported Java stack traces are readable as-is, and native tombstones come out of the device symbolized — no mapping.txt archiving, no unstripped-artifact hunting. This roughly doubles the APK (~29 vs ~13 MB R8-minified); anything under ~50 MB is an acceptable trade. `proguard-rules.pro` stays correct regardless, so minifying is a one-flag change if a size ceiling ever appears.

## Keystore

- The signing key is load-bearing: every release must be signed with the same key or users cannot upgrade without uninstalling — which deletes their app-private distro installs (real data loss).
- Keystore lives at `$KEYSTORE_PATH` (default `~/Android/keystore.jks`) in the key-owning user's account. Keep it and its password backed up somewhere durable.

The key this project uses was created 2026-09-19 for the first release, i.e.
before anything had been published — the only moment a signing key can be
picked for free:

| | |
|---|---|
| Path / type | `~/Android/keystore.jks`, PKCS12, mode 600, a single `PrivateKeyEntry` |
| Alias | `tawc-dsh`; the script auto-picks a sole entry, so `KEY_ALIAS` is unneeded |
| Key | RSA 4096, SHA384withRSA, `-validity 10000` → expires 2054-02-04 |
| DN | `CN=tawc-dsh, O=Kaeno-Tori, C=CN` |
| Cert SHA-256 | `05337d207e9c37aa5c0906f8746a3ad6f6cf7559bb22ba90e86a60b784b7639e` |

The password is deliberately **not** recorded here or anywhere else in the repo
— that is the whole point of `KEYSTORE_PASS` being an environment variable. Note
PKCS12 cannot hold a key password different from the store password, which is
why `KEY_PASS` defaults to it. Losing the keystore *and* its password means every
existing user has to uninstall to update, so back up both in two durable places.

Signing a minSdk-29 app makes `apksigner verify` report **v3 only** (v1 and v2
`false`): v2 exists for Android 7–8, and v3 covers everything from Android 9 on,
which is all this app can install on anyway. Not a misconfiguration.

Releases here start from a clean slate: the application id changed the same day
(`io.github.yangfei.dsh` → `io.github.kaeno_tori.tawc_dsh`, TAWC_DSH_DESIGN.md
§13.1), so there is no earlier install whose upgrade path this key has to keep
alive.

## Release notes

No in-repo `CHANGELOG.md`; GitHub release notes are the changelog. That is now
the only place release prose lives — it used to be mirrored into a 500-character
F-Droid changelog file, which is gone with the rest of the store machinery.
