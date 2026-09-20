DSH on Android (this fork of tawc) is an Android app that runs the DSH
agent: a WebView talking HTTP to a `dsh web` harness living in a Linux
container, with a GPU-backed container for local inference. There is no
desktop GUI and no display stack — see TAWC_DSH_DESIGN.md.

## Quick Reference
- Build APK: `scripts/build-app.sh`
- Build/install/launch: `scripts/app-build-install.sh` (`--no-build`, `--no-launch` supported)
- Run in rootfs: `scripts/rootfs-run.sh '<command>'` or interactive with no command
- Integration tests: `scripts/run-integration-tests.sh [filter]` (builds/installs test deps as needed; `--no-build` reuses existing deploy)
- tawcroot tests: `tawcroot/test.sh [--host|--device] [--no-build] [FILTER...]`
- App unit tests: `./gradlew :app:testDebugUnitTest`
- Logs: `adb logcat -s tawc-native` for Rust, `adb logcat -s tawc` for Kotlin
- Screenshot: use `/data/local/tmp/tawc-dev/`; analyze screenshots with a sub-agent, then delete device and host copies.

## Current Project Shape
- Install methods: `tawcroot` is default and the only release-supported method. `proot` and `chroot` are debug-only dev-loop options.
- Supported distros are **Arch Linux ARM** and **Debian sid** (Arch x86_64 stands in for ALARM on the emulator). There is no dev-only tier and no "Other distros" expander: Manjaro ARM and Void Linux are gone — see [notes/distro-options.md](notes/distro-options.md).
- Graphics: `libhybris` and `turnip` are the two shipped drivers, both **compute-only** — Turnip is headless (built without WSI) and libhybris reaches the vendor blob through our own null Vulkan platform plugin. The third member, `none`, is not a backend: it is the "no driver provisioned" state (and the emulator's x86_64 default). `cpu` was renamed to `none` because it shipped nothing and fell back to nothing — do not describe it as a software/CPU backend. There is no compositor, so `gfxstream` and `libhybris-zink` are gone with it. See [notes/gpu-strategy.md](notes/gpu-strategy.md) and TAWC_DSH_DESIGN.md §11.
- The debug exec broker is the normal host-to-app command path. Host helper binary: `tests/integration/src/bin/tawc-exec.rs`; wrapper: `scripts/tawc-exec.sh`; protocol notes: [notes/exec-broker.md](notes/exec-broker.md).

## Operating Rules
- Keep docs compact here. Put durable design/build details in `notes/`; start with [notes/README.md](notes/README.md).
- This is an agent-written project. Existing code and notes may be wrong; verify against source/scripts before trusting old prose.
- When `.tawctarget` names a usable device target, verify implemented app/device-facing changes on that target before calling them done.
- If you add or change a build dependency, host package, vendored repo, env var, or toolchain version, update [notes/building.md](notes/building.md) in the same change.
- Use existing scripts instead of one-off adb/chroot commands when possible, if the scripts are broken fix them (or at least open an issue).
- Only commit, amend, tag, or push when explicitly asked. Git push may hang without user approval.
- Do not run formatting tools (`cargo fmt`, `rustfmt`, etc.) unless explicitly asked.
- Do not edit `app/icon.svg` unless explicitly asked — it is the hand-drawn source of truth for the app icon. Every other form of the mark is generated from it by `scripts/gen-icon.sh`; after an asked-for icon change, run that script and commit the SVG plus all three generated files together. See [notes/building.md](notes/building.md) ("App icon").
- Keep prose, comments, errors, and commit messages short unless extra detail is genuinely useful.
- Keep production logging sparse. Do not log per-frame work, per-input events, test-only milestones, or high-volume protocol chatter; prefer explicit query/debug surfaces for tests.

## Releases
- Full process: [notes/release.md](notes/release.md). Versions are `major.minor[.patch]`, starting at `0.1`; `versionName` in `app/build.gradle.kts` is the single source and `versionCode` is derived from it there. Nothing else in the repo names a version statically.
- Releases are GitHub release assets only — there is no app-store distribution, and the store machinery was removed (§13.5 of TAWC_DSH_DESIGN.md). Do not reintroduce fastlane metadata or a store recipe.
- When asked to prep a release: bump `versionName`, draft release notes (they become the GitHub release body), run `scripts/check-version-sync.sh`, commit `release: v<versionName>`, tag `v<versionName>` (no push), then hand off — the sign step needs the keystore password, so end by telling the user to run `scripts/build-release-apk.sh` and the remaining publish steps from the notes.

## Issues
- Issues live in `issues/`. Do not solve them unless asked or the fix falls out of current work.
- Create/update issues for nontrivial problems discovered during other work.
- Delete confirmed-solved issues; move still-useful context into notes first.

## Plans
- Future plans live in `plans/`. Do not execute them unless asked.
- Do not write new plans unless asked.
- Like issues, delete them and integrate their contents into your notes when they are complete.

## Device Safety
- The physical phone is precious: do not reboot it unless explicitly asked.
- Emulator management is fair game, but always use `scripts/emulator.sh start|stop [rooted|rootless]`; never launch qemu/emulator directly.
- The standing device target is `./.tawctarget` (`physical`, `emulator`, or `none`). Never edit it. For one command, use `TAWC_TARGET=physical|emulator` or `ANDROID_SERIAL=<serial>`.
- Missing/`none` target means no device interaction. If a requested task needs a device, ask which target to use.
- No silent target fallback: if `.tawctarget=emulator` and only a phone is connected, do not substitute the phone.
- If `su` is available on a phone, prefer it over `adb root`.

## On-Device Files
- Rootfs installs live under `/data/data/io.github.kaeno_tori.tawc_dsh/distros/<id>/rootfs/`.
- Test/debug scratch outside app-private data may only use `/data/local/tmp/tawc-dev/`, exposed by `scripts/lib/tawc-scratch.sh` and the Rust integration crate. Delete screenshots/debug artifacts when done.
- Production code must not write `/data/local/...` or `/sdcard/...`; app-owned runtime state should live under `/data/data/io.github.kaeno_tori.tawc_dsh/` so uninstall removes it.
- Do not clone external repos into `$HOME`. Use `deps/` for vendored/tooling checkouts, and delete temporary checkouts before finishing.

## Vendored Deps
- `deps/deps.list` is the single source of truth for pinned git deps. Build scripts call `dep_ensure <name>` from `scripts/lib/deps.sh`, cloning if missing and erroring if HEAD differs from the pin. Uncommitted edits are tolerated; wrong HEAD is not. Every `dep_ensure` (and the Gradle `verifyDeps` task on `preBuild`) also verifies all *other* existing checkouts against their pins, so any build fails on drift anywhere.
- Dep-artifact Gradle tasks also fingerprint checkout *content* via `ensure-deps.sh --tree-state` (HEAD + tracked-edit hash), so tracked edits — and their later discard — rebuild the artifact. Untracked files are not fingerprinted.
- To follow manifest changes, run `scripts/update-deps.sh [name...]`. It is the only command that should mutate dep checkouts behind your back. On an actual pin move it also `git clean -fdx`s the checkout (untracked WIP included).
- When updating a vendored git dep, update its commit in `deps/deps.list` in the same change. Tarball deps such as `talloc`/`libmd` are versioned by URLs in build scripts, not `deps.list`.

## Cache Proxy
- Always use the dev mirror cache for install/test paths that download distro packages: pass `--arg mirrorProxy=http://127.0.0.1:8080/proxy/` to install actions and equivalent install tests.
- Never start the cache proxy yourself. If `127.0.0.1:8080` is refused, ask the user to run `scripts/cache-proxy.sh run`.
- A `404` from `/` or `/proxy/` means the proxy is up; upstream URLs are appended after `/proxy/`.
- Never wipe `build/cache-proxy/cache/`; ask the user if manual cache cleanup is needed.

## Libhybris Fork
- Fork: https://github.com/wmww/libhybris, pinned in `deps/deps.list`, local checkout `deps/libhybris`, docs in `deps/libhybris/TAWC_FORK.md`.
- Build: `scripts/build-libhybris.sh [--clean]`. Output is packed as `assets/libhybris/arm64-v8a.tar`; runtime extraction plus `LibhybrisInstallProvider` copy it into each rootfs at `/usr/lib/hybris/` as real files.
- Treat post-upstream libhybris history as a clean patch stack, not chronological history. Combine logical changes; old commits may be edited.
- The final libhybris commit is always the `TAWC_FORK.md` commit. Changes to `TAWC_FORK.md` are amended into that final commit, not added as a new commit.
- Only commit to libhybris when asked. After rewriting/amending the fork, bump the `libhybris` pin in `deps/deps.list` in the same change. When updating the fork, tag it `tawc-<DD-Mon-YYYY>-<n>`.
