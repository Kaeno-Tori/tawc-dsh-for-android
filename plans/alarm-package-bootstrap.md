# ALARM packages bootstrap flavor

Add a `packages` bootstrap flavor for Arch Linux ARM, following the
Debian sid one that landed 2026-08-11 (commit `d15df66`). Read these
first — they are the instructions this plan builds on, not background:

- [notes/installation.md](../notes/installation.md) **"Bootstrap
  flavors"** — the flavor infrastructure (already built, reused as-is),
  the Kotlin-owns-trust / real-tool-owns-package-logic division of
  labour, and the workspace-guest mechanics.
- [notes/installation.md](../notes/installation.md) **"Bootstrap
  integrity"** hard rules — every one applies here too.
- Code to pattern-match: `app/src/main/java/me/phie/tawc/install/
  pkgbootstrap/PackageBootstrapInstaller.kt` (the Debian orchestrator),
  the sealed-type dispatch in `Installer.install`, and the tests that
  refuse to build until a new flavor states its trust root
  (`BootstrapVerificationFailClosedTest`, `ShippedPgpKeysTest`).

Why bother, given ALARM already has a PGP-verified tarball: the
packages flavor genuinely **shrinks** the install — the ALARM tarball
hauls a kernel + firmware the app never uses and then deletes as cruft
— and extends the shipped-key signature check from "the one tarball
blob" to every package byte that lands in the rootfs. It is **not** a
trust upgrade of the same magnitude as Debian's (see threat model);
sell it as size + per-package provenance, not freshness.

## Verified upstream facts (2026-08-11 — re-verify at implementation)

Checked against `fl.us.mirror.archlinuxarm.org/aarch64/core/`:

- **Sync DBs are unsigned.** `core.db.sig` → 404. This is the upstream
  gap the old plan flagged; nothing on the wire authenticates the
  *index*, only individual packages.
- **Every package carries a detached `.sig`**, and the sample checked
  (`tzdata-2026c-1-aarch64.pkg.tar.xz.sig`) is issued by keyid
  `77193F152BDBE6A6` — the *same* Arch Linux ARM Build System key the
  app already ships at `res/raw/archlinuxarm_signing_key.asc` and
  verifies the tarball with. `gpgv` against that shipped key: Good
  signature. ALARM's build system signs everything with one key, so no
  new key material should be needed — but sample more than one package
  (and one from `extra`/`community`) before relying on that.
- The db's `desc` stanzas carry `%SHA256SUM%` and `%PGPSIG%` (embedded
  base64 sig). Both live inside the **unsigned** db, so they are
  resolution metadata, not trust inputs — the detached `.sig` fetched
  next to the package, verified in Kotlin against the shipped key, is
  the integrity barrier.
- **`base` (metapackage, currently `base-3-3`) has no kernel
  dependency** — `linux` is only an optdepend ("bare metal support").
  The size win is real without any exclusion hacks. `base` does depend
  on `systemd` and `pacman` (fine — the existing ALARM tarball installs
  contain both and run under tawcroot today) and on `archlinux-keyring`
  (ALARM also wants `archlinuxarm-keyring`; mirror what the tarball
  ships so `pacman-key --populate archlinuxarm` keeps working).

## Threat model — decide and document before writing code

Debian's chain was: one clearsigned `InRelease` → hashes for the index
→ hashes for every deb, plus `Valid-Until` replay protection. ALARM
has **no signed index and no expiry**, so the best achievable chain is:

- Every package's *bytes* are authenticated against the shipped
  build-system key (strong — same tier as the tarball's PGP check).
- Package *selection* is not: the unsigned db decides names → versions
  → dependency edges, so a mirror (or on-path attacker beyond TLS) can
  serve stale-but-genuinely-signed packages, mix generations, or
  perturb the dependency graph. TLS to a proper-cert mirror (the
  existing `fl.us` pick, see `ArchLinuxArm.kt`'s mirror rationale) is
  the only lid on this.

That residual must be written down in notes/installation.md's
"What's verified today" table the way Void's rollback residual is —
explicitly, so nobody mistakes it for an oversight. If that residual
feels unacceptable, stop here: it cannot be engineered away until
upstream signs its DBs.

## The "real tool" question (settle first)

Design principle 1 (never reimplement the package manager) meets an
awkward fact: pacman's debootstrap-equivalent is pacman itself
(`pacstrap` is a thin wrapper), it is dynamically linked, and neither
Arch nor ALARM ships an official static build. The Debian answer
generalizes, though — the workspace guest exists precisely so the real
tool can run before the rootfs does:

- Workspace = pacman + its dependency closure, extracted in Kotlin
  from **individually sig-verified** packages (the busybox/perl move,
  just more of it: glibc, libarchive, curl, gpgme, openssl, zstd, xz,
  bzip2, … — hardcode the list like `WORKSPACE_PACKAGES`, fail loudly
  when the archive renames something). `.pkg.tar.xz` extraction is the
  existing commons-compress/xz path; no new formats.
- Resolution = `pacman -Sp --print-format '%n %v %l' base …` against a
  local copy of the db — the `--print-debs` analogue; prints the
  resolved set without installing. Verify early (host dry-run, like
  the Debian plan did) that it works with an empty package cache and a
  `file://`-style local repo section.
- Install = `pacman -r /rootfs -Sy …` in the workspace guest against a
  local repo dir containing the already-verified packages plus the
  fetched db. pacman's scriptlets run via chroot into the target;
  tawcroot's chroot emulation already carries pacman for every
  existing Arch-family install, so this is not a new class of
  operation. There is no `--foreign`/second-stage split to port —
  pacman does the whole thing in one pass.

The sig-check knob: hand pacman a config with `SigLevel = Never` for
the **bootstrap-internal local repo only**, with the same loud
justification as debootstrap's `--no-check-sig` — every file in that
repo was verified in Kotlin against the shipped key *before* pacman
ever ran, and pacman fetches nothing else. This must be squared with
the "SigLevel = Never is gone and stays gone" hard rule in
notes/installation.md, which governs the *rootfs's* `pacman.conf`:
that rule stands untouched (the installed system keeps
`Required DatabaseOptional` + populated keyrings via the unchanged
`ArchPacmanCommon.configure`/`initPackageManager` tail). Write the
carve-out into the hard-rules section explicitly, or a future reader
will "fix" one or the other.

Rejected alternatives: resolving deps in Kotlin (reimplementation,
forbidden); cross-compiling/vendoring a static pacman (new shipped
binary, same reasons the Debian plan refused a vendored busybox).

## Shape of the work

Part 1 infrastructure is done — no service/UI/metadata changes beyond:

1. A new sealed variant in `distro/Distro.kt`. Do **not** shoehorn
   into `PackageBootstrap` (its fields are apt-shaped). Something like
   `PacmanPackageBootstrap(mirrorRoot, repos, arch, keyResource)`;
   the fail-closed tests will force the `keyResource` declaration
   (`archlinuxarm_signing_key` — already shipped and registered).
2. `ArchLinuxArm.declaredBootstrapFlavors` gains the PACKAGES entry;
   `supportedFlavor` stays TARBALL. Nothing to do for the release
   gate: `EnabledBootstrapFlavors` already keeps PACKAGES out of
   release builds for every distro (debug-only until earned).
3. A pacman-family installer beside `PackageBootstrapInstaller`
   (dispatch on the sealed type in `Installer.install`). Reuse
   `Clearsign`-adjacent machinery where it fits: detached-sig
   verification already exists in `SignatureVerifier`
   (`parseDetachedSignature`/`resolveSigningKey`) — per-package
   verification is a loop over that, no new crypto.
4. Package set: `base` minus what the tarball path's cruft list
   removes anyway, plus `archlinuxarm-keyring`; compare against
   `ArchPacmanCommon` before inventing a list. Rejoin the unchanged
   `configure → TawcInstaller → initPackageManager →
   installBasePackages` tail.
5. Pool cache: reuse `BootstrapCache.pkgPoolDir` (content-addressed by
   sha256 of the verified file; the db gives `%SHA256SUM%` for cache
   keying — fine to *key* on untrusted data, the `.sig` check is what
   admits bytes).

Host dry-run first (the Debian plan's cheapest de-risking step): build
the workspace layout on the host from amd64… no — ALARM is
aarch64-only, so host dry-runs need qemu-user or must move to the
device early. Budget for that: the `--print` resolution step can be
exercised on the host only if a static/host pacman is used for the
experiment (host pacman on an Arch workstation is fine for *layout*
validation; syscall behaviour still needs the phone).

## Acceptance (mirror the Debian flavor's)

On the `.tawctarget` phone through the cache proxy: full
packages-flavor ALARM install; `pacman -Qkk` spot checks clean;
`pacman -S` something small works and the installed binary runs (no GUI
program can be used as the acceptance check any more); the ALARM
*tarball* flavor still installs unchanged; failed-install uninstall
reaps `bootstrap-work/`. Note the size delta vs the tarball install in
the commit message — it is the headline.

## Risks

- **Unsigned db** — see threat model; documentation is the mitigation.
- **pacman's workspace closure is much bigger than busybox+perl.**
  If the extracted-closure workspace turns into an unmaintainable
  20-package list, reconsider scope honestly rather than trimming
  verification.
- **gpgme/keyring init inside the workspace**: with SigLevel=Never for
  the bootstrap repo, pacman should not need an initialized gpg
  keyring; verify, and if it does, that machinery belongs in the
  workspace (throwaway), never as a weakening of the rootfs config.
- **db → package race** (archive rotates mid-install): no by-hash
  equivalent exists; a 404 between db fetch and package fetch gets the
  same single clean re-resolve the Debian installer does
  (`PoolRotatedException` shape).
