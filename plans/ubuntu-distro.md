# Add Ubuntu as a distro (signed ubuntu-base tarball)

> **Note (fork state):** this plan is modeled on the Void Linux distro the
> repo used to ship. Void and its signed-manifest resolver
> (`VoidSha256Resolver`) were removed, so every `Void` /
> `VoidSha256Resolver` reference below is historical and the cited files and
> notes it names no longer exist. The design it describes (signed
> `SHA256SUMS` → `BootstrapVerification.Sha256`) is unchanged.

An apt distro with an app-shipped root of trust, on both ABIs, using only
existing installer machinery. Ubuntu is "just another distro": one registry
entry, one resolver, one shipped PGP key. It does not touch the Debian sid
path. Independent of the bootstrap-flavor infrastructure that landed for
Debian sid (notes/installation.md "Bootstrap flavors") — Ubuntu can grow a
packages flavor like any other apt distro once its zstd-compressed debs
are handled (busybox in the bootstrap workspace can't decompress them;
the Kotlin side already can).

## Verified upstream facts (2026-08-10)

- `https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/` publishes
  `ubuntu-base-24.04.N-base-<arch>.tar.gz` for amd64 and arm64 (arm64
  24.04.4 is 29.9 MB — smaller than the 49.4 MB Debian sid tarball), plus
  `SHA256SUMS` and a detached binary `SHA256SUMS.gpg`.
- The signature verifies as a good signature from
  `Ubuntu CD Image Automatic Signing Key (2012) <cdimage@ubuntu.com>`,
  rsa4096, fingerprint `8439 38DF 228D 22F7 B374 2BC0 D94A A3F0 EFE2 1092`.
  This is **not** the cloud-image `UEC Image Automatic Signing Key`
  (`…7DB8 7C81`); cdimage and cloud-images sign with different keys.
- One `SHA256SUMS` covers every published point release (currently 24.04.3
  and 24.04.4) and every arch. A single signed file yields both the newest
  filename and its digest — structurally identical to Void's
  `sha256sum.txt` + minisign sig.

Do **not** use the 217 MB `ubuntu-24.04-server-cloudimg-*-root.tar.xz` this
plan's predecessor cited; ubuntu-base is the artifact made for chroots.

## Design

Copy the Void pattern end to end: a resolver fetches the signed manifest,
verifies the signature *before* parsing, picks the newest matching entry,
and returns a `BootstrapVerification.Sha256` — so the static `bootstrap`
field declares `ResolvedAtInstallTime` and the tarball itself goes through
the existing single-download/verify/extract pipeline unchanged.

## Steps

1. **Ship the key.** `app/src/main/res/raw/ubuntu_cdimage_signing_key.asc`
   (ASCII-armored export of `8439 38DF …`). Obtain it from two independent
   origins and diff the fingerprints (keyserver.ubuntu.com, plus the
   `ubuntu-keyring` archive package / Canonical's published verification
   docs) — same discipline as `alarm-bootstrap-pgp.md` step 2. Register
   `"ubuntu_cdimage_signing_key" -> R.raw.ubuntu_cdimage_signing_key` in the
   hand-written `when` at `SignatureVerifier.loadKeyRing`
   (`SignatureVerifier.kt:339-346`).
2. **Factor a bytes-level PGP helper.** `verifyPgp` (`SignatureVerifier.kt:123-161`)
   is tarball+URL shaped. Extract a reusable
   `verifyDetached(keyRing: InputStream, sig: ByteArray, data: ByteArray)`
   the resolver can call, keeping the `InputStream` seam so the parse/verify
   half is unit-testable without a `Context` (the known `res/raw` testability
   gap recorded in `plans/alarm-bootstrap-pgp.md:114-125`).
3. **`UbuntuSha256Resolver`**, modeled line-for-line on
   `VoidSha256Resolver` (`VoidSha256Resolver.kt:43-126`) including its
   pure `resolveFromManifest`-style seam:
   - Fetch `SHA256SUMS` as **raw bytes** (re-encoding a decoded String can
     break verification — same caveat as `VoidSha256Resolver.kt:49-51`) and
     `SHA256SUMS.gpg`, both proxy-wrapped in dev builds.
   - Verify the signature; refuse to proceed unsigned.
   - Parse lines
     `^([0-9a-f]{64}) \*(ubuntu-base-(24\.04(?:\.\d+)?)-base-(amd64|arm64)\.tar\.gz)$`,
     filter to the distro's arch, pick the highest point release.
   - Return url + `Sha256(digest)`, format `GZIP`.
4. **Distro objects.** `sealed class Ubuntu2404` with singletons
   `Ubuntu2404X86_64` (ubuntuArch `amd64`, repo
   `http://archive.ubuntu.com/ubuntu`) and `Ubuntu2404Aarch64` (`arm64`,
   `http://ports.ubuntu.com/ubuntu-ports` — arm64 lives on ports, not
   archive). `key = DISTRO_UBUNTU = "ubuntu"` (new constant at
   `Installation.kt:144-147`), `displayName "Ubuntu 24.04"`, `defaultLabel
   "Noble"`, static bootstrap `ResolvedAtInstallTime`.
5. **Generalize `AptCommon.configure` minimally.** It hardcodes one suite
   and `Components: main` (`AptCommon.kt:74-80`). Add `suites: List<String>`
   and `components: String` params; Debian callers pass `["sid"]` / `"main"`
   unchanged; Ubuntu passes `["noble", "noble-updates", "noble-security"]`
   and whatever components step 6's package check demands (`main` alone if
   possible, `main universe` if not).
6. **Check the assumptions against the real tarball** before wiring
   anything: (a) top-level layout — expected rooted at `./` with
   `stripPrefix = null`, verify with `tar tzf`; (b)
   `/usr/share/keyrings/ubuntu-archive-keyring.gpg` exists inside (from the
   `ubuntu-keyring` package) — that path becomes the `Signed-By` value;
   (c) every entry in `AptCommon.DEFAULT_BASE_PACKAGES` exists in noble
   (the `systemd-standalone-*` pair and `dbus-x11` are the suspects; if any
   is universe-only or absent, override `basePackages` on the Ubuntu
   objects rather than bending the shared default).
7. **Registry + tests.** Append both objects to `DistroRegistry.all`; add
   `DISTRO_UBUNTU` to the live-resolving key set in
   `BootstrapVerificationFailClosedTest.kt:60` (biconditional — forgetting
   this fails the build, which is the point). Check `DistroInfoActivity` /
   strings for per-distro prose that needs an Ubuntu entry.
8. **Unit vectors.** Check in the real `SHA256SUMS` + `SHA256SUMS.gpg`
   under `app/src/test/resources/ubuntu-base/` (upstream filenames
   verbatim, directory per scheme — the `minisign/` convention). Tests:
   good-signature parse resolves the expected newest arm64 filename; amd64
   selection; forged digest rejected; missing/garbage `.gpg` rejected;
   tampered SUMS body rejected; key parses via the step-2 seam.
9. **Docs + on-device verify.** Add a row to the verification table at
   `notes/installation.md:756-763` and a short trust-profile paragraph
   (see Risks for the rollback wording). Verify a real install on the
   `.tawctarget` device through the cache proxy before calling it done.

## Risks and things that will bite

- **Rollback.** An attacker with origin control can serve an older,
  genuinely-signed `SHA256SUMS` plus its matching tarball. Same accepted
  residual as Void (`notes/installation.md:849-857`); apt's own
  `Valid-Until` then bounds staleness after first `apt update`. Document
  next to the Void note, don't build machinery.
- **Point-release rollover.** The SUMS file and the cache slot
  (`bootstrap-ubuntu-<arch>.tar.gz`) both move when 24.04.5 lands. The
  existing evict-and-retry loop (`Installer.kt:172-236`) plus
  Content-Length reuse already handle the stale-cache case — same story as
  Void's dated tarballs. No new code, but don't "fix" a mid-rollover
  mismatch by weakening anything.
- **New LTS (26.04) is a deliberate bump**: URL directory, suite names,
  and likely a re-check of step 6. The 2012 signing key has no published
  expiry, but re-verify it still signs the new release's SUMS.
- **cdimage.ubuntu.com is a single origin** and can be slow. Fine for the
  trust story (the signature makes the mirror untrusted anyway); if speed
  hurts, any ubuntu-base mirror can serve the tarball later without
  touching verification — same reasoning as
  `issues/void-bootstrap-uses-slow-repo-default-mirror.md`.
- **Suites/components param change touches Debian's config path.** Keep the
  Debian-emitted `tawc.sources` byte-identical before/after the refactor
  (assert in a unit test or by manual diff) so this plan can't regress the
  existing distro.
