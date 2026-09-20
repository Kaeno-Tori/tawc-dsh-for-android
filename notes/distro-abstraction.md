# Distro abstraction

The install pipeline is split into a distro-agnostic [Installer] plus
one [Distro] implementation per (distro family × Linux arch). Adding
a new distro family is a fresh file in `install/distro/<family>/`
implementing the four-method [Distro] interface; nothing in
[Installer] / [InstallationService] / the activities cares.

This note documents the design rationale and the as-landed structure.
For day-to-day reference (state machine, on-disk layout, install
pipeline stages, mount lifecycle, …) see
[installation.md](installation.md).

## Goal

One generic `Installer` that runs `download → extract → configure →
init pkgmgr → install pkgs`, plus one `Distro` per (distro family ×
Linux arch) that owns the policy:

- bootstrap tarball URL / format / strip-prefix
- `/etc` configuration (pacman.conf vs apt sources, mirrorlists, …)
- package-manager init (pacman-key vs apt-key vs nothing)
- base package list

`InstallationService` looks up the right `Distro` from a registry based
on `(metadata.distro, metadata.arch)` and constructs the generic
`Installer` with it. Adding Ubuntu = drop a new file in
`distro/ubuntu/`; nothing else changes.

## File layout (as landed)

```
me.phie.tawc.install/
  Installation.kt                 # data model; fromJson tolerates missing distro/method
  InstallationStore.kt
  InstallationService.kt          # gate; rejects on no-Distro-for-host or
                                  #   bad distro key before disk write
  InstallProgress.kt              # stages: ..., PKG_KEYRING, PKG_INSTALL, ...
  InstallActivity.kt              # form has distro radio when >1 distro matches host
  DistroInfoActivity.kt           # title + rows resolved via DistroRegistry
  InstallActions.kt               # broker `install`/`uninstall` action handlers (debug)
  Installer.kt                    # generic pipeline (replaces ArchInstaller);
                                  #   calls Distro.resolveBootstrap() before download
  SignatureVerifier.kt            # PGP (Arch) / Sha256 (Debian)
  Su, Downloader, BootstrapCache, Archive, RootfsCleaner,
    ChrootMounter                   # unchanged distro-agnostic primitives
  util/
    HostArch.kt                   # primaryAbi() + linuxArchFor()
    HumanSize.kt
    AppOwnership.kt               # chownAppDirNonRecursive
  distro/
    Distro.kt                     # interface + DistroBootstrap data class +
                                  #   cacheKey + resolveBootstrap() override hook
    DistroRegistry.kt             # (distro, arch) -> Distro; availableForHost();
                                  #   forKey() for InstallActivity radio resolution
    arch/
      ArchPacmanCommon.kt         # shared pacman.conf / mirrorlist write /
                                  #   profile.d / pacman-key / pacman -Syu /
                                  #   pacman -S --needed; DEFAULT_BASE_PACKAGES
      ArchLinuxX86_64.kt          # geo.mirror.pkgbuild.com zstd, archlinux keyring
      ArchLinuxArm.kt             # archlinuxarm.org gz, archlinuxarm keyring,
                                  #   curated multi-mirror failover list
    apt/
      AptCommon.kt                # shared apt/deb822 sources, apt.conf, dpkg
                                  #   path-exclude, apt-get update/install helpers
    debian/
      DebianDockerResolver.kt     # debuerreotype docker artifact manifest ->
                                  #   rootfs.tar.gz URL + OCI layer SHA-256
      DebianSid.kt                # sid on x86_64/aarch64; apt-family hooks
```

`MainActivity` also gained a `DistroRegistry` import so the home-row
label uses the canonical display name + Linux arch ("Arch Linux ARM
(aarch64)" rather than "Arch (arm64-v8a)").

## `Distro` interface

```kotlin
interface Distro {
    val key: String                 // metadata.json value, e.g. "arch", "debian-sid"
    val displayName: String         // UI title, e.g. "Arch Linux ARM"
    val linuxArch: String           // uname -m: "x86_64" / "aarch64"
    val androidAbi: String          // Build.SUPPORTED_ABIS: "x86_64" / "arm64-v8a"
    val cacheKey: String            // bootstrap cache filename component;
                                    //   default "$key-$linuxArch" (disambiguates
                                    //   arch-aarch64 vs arch-x86_64)
    val bootstrap: DistroBootstrap  // URL, BootstrapFormat, stripPrefix, verification

    /**
     * Resolved at install time. Default: the [supportedFlavor] entry of
     * [bootstrapFlavors], throwing if this build ships no such flavor.
     */
    fun resolveBootstrap(
        log: (String) -> Unit,
        mirrorProxy: MirrorProxy? = null,
        flavor: BootstrapFlavor = supportedFlavor,
    ): DistroBootstrap

    val basePackages: List<String>

    fun configure(rootfs: String, log: (String) -> Unit)
    fun initPackageManager(rootfs: String, log: (String) -> Unit)
    fun installPackages(rootfs: String, packages: List<String>, log: (String) -> Unit)
}
```

Both Arch variants share `ArchPacmanCommon` for
pacman.conf munging, profile.d, the `pacman-key --init / --populate`
and `pacman -Syu` command bodies. They differ in `bootstrap` (URL +
format + stripPrefix), the keyring name (`archlinux` /
`archlinuxarm`), and the mirrorlist contents.

`resolveBootstrap` is for distros whose URL or expected digest is
only known at install time. `DebianSid` overrides it to resolve the
current Docker image layer through the registry
(`DebianDockerResolver`) and take that layer's digest as the
`BootstrapVerification.Sha256` argument. The Arch impls don't
override; they keep their static PGP verification.

## Supported vs other

`Distro.supported` still exists as a UI/policy label, but **every
entry in the registry is now supported** — the dev-only Manjaro ARM
and Void Linux entries are gone — so the install form lists them
flat, with no "Other distros" expander any more. See
[distro-options.md](distro-options.md) → *Which distros are
supported* for the policy.

`DistroRegistry.availableForHost()` sorts supported-first, so
`defaultForHost()` (broker installs without `distro=`, and the form's
initial pick) lands on a supported distro.

The picker drives its radios by hand instead of using a `RadioGroup`:
the selection spans two containers and a `RadioGroup` only un-checks
its own direct children.

## `DistroRegistry`

```kotlin
object DistroRegistry {
    val all: List<Distro> = listOf(
        ArchLinuxX86_64,
        ArchLinuxArm,
        DebianSidX86_64,
        DebianSidAarch64,
    )

    fun forInstallation(inst: Installation): Distro? =
        all.firstOrNull { it.key == inst.distro && it.androidAbi == inst.arch }

    /** Distros installable on this host (Android ABI match). */
    fun availableForHost(): List<Distro> =
        all.filter { it.androidAbi == HostArch.primaryAbi() }

    fun defaultForHost(): Distro? = availableForHost().firstOrNull()

    /** Resolve the Activity's distro radio pick. */
    fun forKey(distroKey: String): Distro? =
        availableForHost().firstOrNull { it.key == distroKey }
}
```

Existing `metadata.distro = "arch"` records resolve correctly for both
arches because the lookup is on `(distro, arch)`.

## Generic `Installer`

Same pipeline shape as today's `ArchInstaller.install()`:

1. `setState(INSTALLING)` (after `mkdir` + chown of the install dir)
2. `BootstrapCache.download(distro.linuxArch, distro.bootstrap.url, distro.bootstrap.format)`
3. `Archive.extractAsRoot(..., stripPrefix = distro.bootstrap.stripPrefix)`
4. `distro.configure(rootfs, log)` (CONFIGURING)
5. `writeEnterScript(rootfs)` (still part of CONFIGURING)
6. `distro.initPackageManager(rootfs, log)` (PKG_KEYRING)
7. `distro.installPackages(rootfs, distro.basePackages, log)` (PKG_INSTALL)
8. `NodeProvisioner.install(...)` (PROVISIONING; skipped for a pack)
9. `setState(READY)`

`uninstall()` is unchanged (just `RootfsCleaner.wipe`).

## UI changes

- Activities use `Distro.displayName` for titles and `Distro.linuxArch`
  for the architecture row, instead of the raw Android ABI.
- `InstallStage` rename: `PACMAN_INIT → PKG_KEYRING`, `PACMAN_INSTALL
  → PKG_INSTALL`. Activities only render `progress.message`, so this is
  a string-table change.

## Validation tightening

Today an unsupported ABI throws inside the install job *after*
`setState(INSTALLING)`, parking the slot in FAILED. With the registry
in place, `InstallationService.startInstall` rejects the request via
the existing `reject(...)` path if `DistroRegistry.defaultForHost()`
returns null, before any disk state is written.

## Bugs / gotchas the refactor folded in

1. **`primaryArch()` duplicated** in `ArchInstaller` and
   `InstallActivity` → single `HostArch.primaryAbi()`.
2. **`Installation.arch` is the Android ABI** but is shown to users as
   the architecture label. Keep ABI on disk for back-compat; use
   `Distro.linuxArch` for the UI.
3. **Dead `if (mirrorList != null)` branch** in `ArchInstaller.configure`
   — removed when configure moves into the per-arch Distro impl.
4. **Unsupported-ABI path** lands in FAILED; registry-level reject
   moves it to a clean "rejected" event with no on-disk side-effects.
5. **`IgnorePkg` line carries names for both arches** — split into
   `Distro.ignoredPackages` so x86 doesn't carry ALARM-only names.
6. **Generic helpers in `ArchInstaller`** (`writeEnterScript`,
   `chownAppDirNonRecursive`, `humanSize`) → `util/`.
7. **`Installation.fromJson` mandatory `distro` field** — relax to
   `optString("distro", DISTRO_ARCH)` so any pre-distro-field record
   loads as a default-Arch install.

## Out of scope (deliberately, in this pass)

- Multi-install / multiple ids on one device. Still implicit "id =
  arch" in `scripts/rootfs-run.sh` (env-overridable),
  `tests/integration/src/adb.rs`, and
  `scripts/run-integration-tests.sh`
  preflight paths. The on-disk layout, [Installation],
  [InstallationStore] and [InstallationService] are already
  id-parameterised, so the only thing missing is "how do
  clients/tests pick which id".
- proot / rootless installations. `Installation.method` exists for
  this; switching to a `MountStrategy` strategy interface alongside
  [ChrootMounter] would be the natural seam.
- Adding Ubuntu. The abstraction supports it cleanly, but no actual
  Ubuntu Distro is added — the test was the existence of clean
  policy hooks, not a second family.

## Verification (2026-04-27)

Full install/uninstall flow exercised on the emulator (x86_64
ALARM... no, x86_64 Arch via the `pkgbuild.com` zstd bootstrap):

- `scripts/tawc-exec.sh --foreground-app --action install --arg id=arch --arg method=tawcroot` → stages
  `DOWNLOADING → EXTRACTING → CONFIGURING → PKG_KEYRING → PKG_INSTALL
  → DONE`.
- Resulting `metadata.json` contains `distro="arch"`, `arch="x86_64"`,
  `state="READY"`.
- `scripts/tawc-exec.sh --foreground-app --action uninstall --arg id=arch` → `UNMOUNTING → DELETING →
  DONE`. `gpg-agent` killed by the rootfs dev:inode sweep, mount-master
  unmount, `find -xdev -depth -delete` cleared the dir.
- `<distros>/arch/` is gone (`ls` returns no such file or directory).
