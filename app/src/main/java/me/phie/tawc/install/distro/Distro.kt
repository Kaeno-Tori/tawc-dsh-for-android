package me.phie.tawc.install.distro

import me.phie.tawc.R
import me.phie.tawc.install.BootstrapFormat
import me.phie.tawc.install.BootstrapVerification
import me.phie.tawc.install.EnabledBootstrapFlavors
import me.phie.tawc.install.InstallationMethod
import me.phie.tawc.install.MirrorProbe
import me.phie.tawc.install.MirrorProxy
import java.io.IOException

/**
 * Per-distro policy: bootstrap tarball, `/etc` configuration,
 * package-manager init, base package install. The generic
 * `Installer` orchestrates these in a fixed order
 * (`download → extract → configure → init pkgmgr → install pkgs`)
 * and one `Distro` per (distro family × Linux arch) plugs in here.
 *
 * Today's set includes Arch, Manjaro ARM, Void glibc, and Debian sid.
 * Adding e.g. Ubuntu is a fresh file in `distro/ubuntu/` plus shared
 * apt-family helpers; nothing in `Installer` / `InstallationService`
 * cares.
 */
interface Distro {
    /**
     * Stable identifier written to `metadata.json`. Used together with
     * [androidAbi] by [DistroRegistry] to resolve a record back to the
     * implementation that produced it. Existing on-disk records use
     * `"arch"` for both Arch Linux and Arch Linux ARM, so both
     * implementations share that value and are disambiguated by arch.
     */
    val key: String

    /** Human-readable name for UI titles, e.g. `"Arch Linux ARM"`. */
    val displayName: String

    /**
     * Whether this distro is one we actually support for users.
     * Supported distros are Arch Linux ARM and Debian sid (plus Arch
     * Linux x86_64, the emulator-only stand-in for ALARM); everything
     * else ships but is dev/experimental and hides behind the install
     * form's "Other distros" expander. Purely a UI/policy label — the
     * install pipeline treats every distro identically. See
     * notes/distro-options.md.
     */
    val supported: Boolean get() = false

    /**
     * Short label used as the install-form Label default and as the
     * basis of the on-disk id slug (e.g. `"Arch"`, `"Manjaro"`).
     * Must be slugifiable via [Installation.slugifyLabel] so the
     * derived id matches [Installation.isValidId]. Distinct from
     * [displayName] because the full name typically has spaces and
     * arch suffixes that produce a long, ugly directory name.
     */
    val defaultLabel: String

    /**
     * Linux `uname -m` name (`"x86_64"`, `"aarch64"`). Used for
     * tarball URLs, the [BootstrapCache] filename, and the UI
     * "Architecture:" row. Distinct from [androidAbi] because pacman
     * et al. do not speak Android ABI names.
     */
    val linuxArch: String

    /**
     * `Build.SUPPORTED_ABIS` value matching this distro
     * (`"x86_64"`, `"arm64-v8a"`). Used for host detection
     * ([DistroRegistry.defaultForHost]) and stored in
     * `Installation.arch` for back-compat with the metadata schema
     * that predates this abstraction.
     */
    val androidAbi: String

    /**
     * Cache filename component for the bootstrap tarball. Two distros
     * sharing one [linuxArch] (Arch Linux ARM and Manjaro ARM both at
     * `aarch64`) can't share a cache slot — the [BootstrapCache]
     * filename is `bootstrap-<cacheKey>.tar.<ext>`, so they need
     * distinct keys. Default is `"$key-$linuxArch"` which is
     * already-unique without per-distro overrides.
     */
    val cacheKey: String get() = "$key-$linuxArch"

    /**
     * Static bootstrap tarball metadata. For most distros this is the
     * single source of truth — [resolveBootstrap] just returns it. For
     * distros where the URL or expected digest is only known at install
     * time (e.g. GitHub Releases "latest" with a server-side
     * SHA-256 in the API response), this is a placeholder carrying
     * [BootstrapVerification.ResolvedAtInstallTime] and
     * [resolveBootstrap] does the runtime lookup. The placeholder
     * fails closed: if it reaches the verify stage the install throws.
     */
    val bootstrap: TarballBootstrap

    /**
     * Prefix of [bootstrap]'s download URLs that a mirror replaces, or
     * null when this distro's bootstrap cannot be pointed at a mirror.
     *
     * A mirror is not an origin swap. Upstream layouts are rooted
     * somewhere specific and mirrors re-root them under their own path:
     * ALARM serves `/os/ArchLinuxARM-aarch64-latest.tar.gz` at the
     * mirror root while Tsinghua serves the same file under `/alarm/`,
     * and Debian's `archiveRoot` already carries `/debian`. Swapping
     * scheme+host would therefore drop `/alarm` for ALARM and double
     * `/debian` for Debian. So the substitution is a prefix
     * replacement, which needs to know where upstream's root ends —
     * that is what this property records.
     *
     * Null is the honest default: a distro whose bootstrap is resolved
     * live ([me.phie.tawc.install.distro.debian.DebianSid] walks the
     * registry for its current layer) has no mirrorable URL, and
     * claiming otherwise would corrupt the URL rather than mirror it.
     * [me.phie.tawc.install.BootstrapMirror] leaves such a distro alone
     * even when the user has set a mirror.
     */
    val bootstrapMirrorPrefix: String? get() = null

    /**
     * Directory a [MirrorPreset] serves this distro's bootstrap under,
     * or null when the distro isn't mirrorable at all.
     *
     * Composed with a preset's [MirrorPreset.origin] to build the mirror
     * base the user picks from the dropdown. Non-null implies a
     * non-null [bootstrapMirrorPrefix] — a mirror path with nothing to
     * re-root would be meaningless — and the reverse isn't required:
     * the prefix describes the *upstream* layout, which is what the
     * rewrite needs, while this describes the *mirror* layout.
     */
    val bootstrapMirrorPath: String? get() = null

    /**
     * Every bootstrap flavor this distro implements, whether or not
     * this APK ships it. Default: just the tarball path, wrapping
     * [bootstrap] — distros with a single flavor change nothing. A
     * distro adding a `packages` flavor overrides this with both
     * entries; the map's [DistroBootstrap] values are static
     * descriptors ([resolveBootstrap] may substitute live data at
     * install time, e.g. a resolved tarball digest).
     *
     * Callers outside the build gate and its tests want
     * [bootstrapFlavors] instead.
     */
    val declaredBootstrapFlavors: Map<BootstrapFlavor, DistroBootstrap>
        get() = mapOf(BootstrapFlavor.TARBALL to bootstrap)

    /**
     * The flavors of [declaredBootstrapFlavors] this build actually
     * ships ([me.phie.tawc.install.EnabledBootstrapFlavors]): the
     * install form, the service gate, and [resolveBootstrap] all go
     * through here, so a release APK sees tarball only. Not meant to
     * be overridden — override [declaredBootstrapFlavors].
     */
    val bootstrapFlavors: Map<BootstrapFlavor, DistroBootstrap>
        get() = declaredBootstrapFlavors.filterKeys { EnabledBootstrapFlavors.isEnabled(it) }

    /**
     * The one release-supported flavor, always shipped. Anything else
     * is a debug-only experiment, excluded from release builds at
     * build time by [me.phie.tawc.install.EnabledBootstrapFlavors] and
     * rejected by [me.phie.tawc.install.InstallationService] if some
     * caller names it anyway.
     */
    val supportedFlavor: BootstrapFlavor get() = BootstrapFlavor.TARBALL

    /**
     * Resolve the bootstrap descriptor at install time. Default impl
     * returns the static [bootstrapFlavors] entry. Override for
     * distros whose URL or [BootstrapVerification] digest must be
     * looked up live — e.g. [me.phie.tawc.install.distro.debian.DebianSid]
     * walks the Docker registry for the current image layer and its
     * digest.
     * Runs before download; failures throw so we never attempt a
     * download whose verification can't be set up.
     *
     * @param mirrorProxy debug-builds-only knob: when non-null,
     *   implementations that fetch over HTTP for resolution (GitHub
     *   Releases API, Void's `sha256sum.txt`) should route through it
     *   so the dev cache stays coherent with the proxied tarball
     *   download. See `notes/cache-proxy.md`.
     * @param flavor which of [bootstrapFlavors] to resolve; the
     *   service has already validated it against the map and the
     *   release gate by the time an install reaches this.
     */
    fun resolveBootstrap(
        log: (String) -> Unit,
        mirrorProxy: MirrorProxy? = null,
        flavor: BootstrapFlavor = supportedFlavor,
    ): DistroBootstrap = bootstrapFlavors[flavor]
        ?: throw IOException("distro $key has no ${flavor.id} bootstrap flavor")

    /** Base packages to `pacman -S --needed` (or equivalent) at install time. */
    val basePackages: List<String>

    /**
     * Packages providing the **Node.js runtime and npm**, installed by
     * the provisioning stage
     * ([me.phie.tawc.install.NodeProvisioner]) once the base set is in.
     * Without them there is no `dsh` and the harness starts into
     * `exit=127` with no way out (see `TAWC_DSH_DESIGN.md` §9.7).
     *
     * Per-distro rather than one shared list because the packaging
     * differs: Arch and Debian ship npm as its own package, Void
     * bundles it into `nodejs`. `pnpm` is deliberately **not** here —
     * it is installed from the npm registry instead, which is the same
     * name on every distro and the thing DSH's plugin manager actually
     * invokes.
     */
    val runtimePackages: List<String>

    /**
     * The package-repository URLs to measure on this network, one per
     * [bases] entry, or empty when this distro has nothing to measure.
     *
     * [configure]'s `mirrorBases` is normally the mirror list the
     * bootstrap download just proved out, and for an install that
     * downloads one that is also the answer the package manager wants.
     * An install that downloads **nothing** — an importer handing over a
     * pack the user already has — has no race to run, so nothing carries
     * over, and the package manager is left on the distro's own list:
     * ALARM ships six US/EU hosts, which on a Chinese network is the
     * 11 KB/s `pacman -Syu` that [me.phie.tawc.install.MirrorProbe]
     * exists to prevent. The measurement is still owed to the *package
     * manager* even when there is no bootstrap to pick — so this hook
     * makes the package repository itself the thing that gets measured,
     * rather than a stand-in for it.
     *
     * Empty is the honest default: a distro whose repository isn't
     * mirrored the way its bootstrap is (or isn't mirrored at all) keeps
     * its own curated list rather than being handed URLs this app
     * guessed from its bootstrap layout.
     *
     * @param bases mirror bases to re-root onto, from
     *   [MirrorPresets] composed with [bootstrapMirrorPath].
     */
    fun packageProbeUrls(bases: List<String>): List<MirrorProbe.Source> = emptyList()

    /**
     * Write `/etc` configuration into the freshly-extracted [rootfs]:
     * DNS, package-manager config, mirrorlist, profile.d. Runs via
     * [method].runOutside (which is `su` for chroot installs and a
     * plain app-uid shell for proot installs — the latter works
     * because the rootfs is app-uid-owned in proot mode).
     *
     * @param mirrorProxy when non-null, every package-mirror URL the
     *   implementation writes into the rootfs (pacman mirrorlist,
     *   xbps repository conf, apt sources.list) must be rewritten
     *   through it via [MirrorProxy.wrap]. Verification endpoints
     *   (`.sig` and friends) are **not** proxied here — see
     *   `notes/cache-proxy.md`.
     * @param mirrorBases the mirror bases to write, best first — either
     *   what the bootstrap download just proved out on this network
     *   ([me.phie.tawc.install.MirrorProbe]) or the one the user pinned
     *   by hand. Distros that write a package-mirror list should use
     *   these and **nothing else**: the bootstrap and the packages it
     *   installs come from the same hosts, so a mirror good enough for
     *   an 800 MB archive is the answer for the package manager too —
     *   picking one only for the download is how `pacman -Syu` ends up
     *   crawling against the archive host the user was routing around.
     *   Empty when there is nothing to say (a distro with nothing to
     *   probe, an install that measured nothing), in which case the
     *   distro's own list stands.
     *
     *   A base is only ever passed by the caller that knows where it came
     *   from; implementations must not reach for a persisted setting of
     *   their own. See [me.phie.tawc.install.Installer] for why.
     */
    fun configure(
        method: InstallationMethod,
        rootfs: String,
        mirrorProxy: MirrorProxy?,
        log: (String) -> Unit,
        mirrorBases: List<String> = emptyList(),
    )

    /**
     * Bootstrap the package manager inside the chroot at [rootfs]
     * (e.g. `pacman-key --init && pacman-key --populate <keyring> &&
     * pacman -Syu`). Runs via [method].runInside.
     */
    fun initPackageManager(method: InstallationMethod, rootfs: String, log: (String) -> Unit)

    /**
     * Install [packages] inside the chroot at [rootfs]. Runs via
     * [method].runInside. The caller passes the set it wants
     * ([basePackages] at install time, [runtimePackages] at
     * provisioning) rather than this method picking one, so the two
     * installs stay visibly two transactions.
     */
    fun installPackages(
        method: InstallationMethod,
        rootfs: String,
        packages: List<String>,
        log: (String) -> Unit,
    )
}

/**
 * How a rootfs is assembled. One of the sealed shapes below; each
 * carries its trust root in the type — there is deliberately no
 * verification-free variant to accidentally reach (see
 * notes/installation.md "Bootstrap integrity").
 */
sealed interface DistroBootstrap

/**
 * One mirror that can stand in for a distro's upstream bootstrap origin.
 *
 * [origin] is a bare origin — no path. The directory the distro actually
 * lives under is per-distro ([Distro.bootstrapMirrorPath]) because
 * mirrors don't agree on it even for the same upstream: ALARM is at
 * `/archlinuxarm` on most Chinese mirrors but `/alarm` on Huawei's, and
 * Arch x86_64 is at `/archlinux` on all of them. Keeping the two apart
 * means one list of mirrors serves every distro, and a mirror that
 * re-roots differently is a one-line path override rather than a
 * duplicated URL.
 */
data class MirrorPreset(
    val origin: String,
    @androidx.annotation.StringRes val nameRes: Int,
)

/**
 * Mirrors offered as one-tap choices in the install form, and the
 * candidate pool [me.phie.tawc.install.MirrorProbe] measures at install
 * time.
 *
 * Curated, not scraped: every entry was checked to serve both the
 * bootstrap archive **and** its detached `.sig` for each distro that
 * declares a [Distro.bootstrapMirrorPath]. A mirror that carries the
 * tarball but not the signature is worse than no mirror at all — the
 * PGP check fails closed after an 800 MB download. So the list is short
 * on purpose, and adding to it means checking the signature too.
 *
 * Tsinghua and BFSU are deliberately absent: both return 403 for every
 * ArchLinuxARM path we could construct. They mirror ALARM's *package
 * tree* (`/archlinuxarm/$arch/$repo`) but not its release directory
 * (`/archlinuxarm/os/`), which is where the bootstrap tarball lives —
 * and that distinction is the whole reason the probe measures the real
 * artifact instead of trusting a mirror's reputation. HUST, Huawei
 * Cloud and WSYU are absent for reasons of the same class (404 on
 * `/os/`).
 *
 * JLU serves both files but ignores `Range` — a 1 KiB range request
 * gets the whole body back. The probe copes (it counts its own bytes),
 * so this is a policy call rather than a technical limit: a mirror that
 * can't be ranged can't be throttled either.
 */
object MirrorPresets {
    val ALL: List<MirrorPreset> = listOf(
        MirrorPreset("https://mirrors.aliyun.com", R.string.mirror_preset_aliyun),
        MirrorPreset("https://mirrors.ustc.edu.cn", R.string.mirror_preset_ustc),
        MirrorPreset("https://mirrors.163.com", R.string.mirror_preset_163),
        MirrorPreset("https://mirror.sjtu.edu.cn", R.string.mirror_preset_sjtu),
        MirrorPreset("https://mirrors.nju.edu.cn", R.string.mirror_preset_nju),
        MirrorPreset("https://mirrors.hit.edu.cn", R.string.mirror_preset_hit),
        MirrorPreset("https://mirrors.xjtu.edu.cn", R.string.mirror_preset_xjtu),
        MirrorPreset("https://mirrors.qlu.edu.cn", R.string.mirror_preset_qlu),
        MirrorPreset("https://mirror.nyist.edu.cn", R.string.mirror_preset_nyist),
    )

    /**
     * Every preset as a usable mirror base for a distro whose mirror
     * layout is [path]: `origin + path`, e.g.
     * `https://mirrors.aliyun.com/archlinuxarm`.
     *
     * The one place that composition happens, so the install form's
     * dropdown and [me.phie.tawc.install.MirrorProbe]'s candidate pool
     * can't drift apart — a preset offered in the UI but missing from
     * the probe would be a mirror the user can pick and the app would
     * never measure.
     */
    fun basesFor(path: String): List<String> = ALL.map { it.origin + path }
}

/**
 * Identifier for one entry of [Distro.bootstrapFlavors]. [id] is the
 * wire/persist form: the broker `--arg bootstrap=` value and the
 * `metadata.json` `bootstrapFlavor` field.
 */
enum class BootstrapFlavor(val id: String) {
    TARBALL("tarball"),
    PACKAGES("packages");

    companion object {
        fun fromId(id: String): BootstrapFlavor? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Bootstrap-tarball descriptor — download one archive, verify, extract.
 *
 * @property url HTTP(S) URL of the tarball.
 * @property format compression format ([BootstrapCache] uses this for
 *   the cache filename; [me.phie.tawc.install.Archive] dispatches on
 *   the file extension to either stream zstd through a FIFO or hand
 *   gzip / plain `.tar` straight to toybox tar).
 * @property stripPrefix single top-level directory inside the tarball
 *   to flatten into the rootfs (`"root.x86_64"` for the Arch x86_64
 *   bootstrap; `null` for tarballs that are already flat). Toybox tar
 *   has no `--strip-components`, so `Archive.extractAsRoot` flattens
 *   with `mv` after extraction when this is non-null.
 * @property verification integrity-check policy (PGP detached
 *   signature, etc.) consumed by [me.phie.tawc.install.SignatureVerifier]
 *   between download and extract. Every descriptor that reaches the
 *   verify stage must carry a concrete policy; static placeholders use
 *   [BootstrapVerification.ResolvedAtInstallTime], which throws there,
 *   so a distro cannot end up unverified by omission.
 */
data class TarballBootstrap(
    val url: String,
    val format: BootstrapFormat,
    val stripPrefix: String?,
    val verification: BootstrapVerification,
) : DistroBootstrap

/**
 * A rootfs the user supplied as a local file — a "data pack" — rather
 * than something we fetch.
 *
 * Unlike every other [DistroBootstrap] this is **never declared by a
 * [Distro]**; it's constructed by the caller from a SAF `content://`
 * URI the user picked. A distro still has to be named alongside it,
 * because [Distro.configure] / [Distro.initPackageManager] /
 * [Distro.installPackages] all still run afterwards: a pack is a
 * frozen rootfs, not a frozen device. Skipping them would bake in the
 * build device's DNS and package mirror, which is exactly the class of
 * bug [me.phie.tawc.install.BootstrapMirror] exists to avoid. The one
 * step a pack **does** skip is provisioning — a pack is by definition
 * already provisioned (see `TAWC_DSH_DESIGN.md` §9.8), and
 * re-downloading the Node runtime and DSH on top of it would give back
 * the whole download the pack exists to avoid.
 *
 * **There is no verification policy, deliberately and explicitly.** The
 * other descriptors carry a [BootstrapVerification] because there is an
 * upstream authority to check them against; a hand-picked file has
 * none — the user *is* the authority. This type therefore has no
 * verification field at all, so the omission can't be accidental. What
 * still holds: extraction validates structure (a truncated tar makes
 * toybox fail mid-stream, which is the failure mode that silently
 * produced a short pack during development — see
 * `TAWC_DSH_DESIGN.md` §9.8), and nothing is written to the rootfs
 * until the whole stream has been read.
 *
 * @property uri `content://` URI with a persisted read grant.
 * @property displayName what to show the user and record in
 *   `metadata.json` — the pack's filename, not a URL.
 * @property format inferred from [displayName]'s extension.
 */
data class ImportedPack(
    val uri: String,
    val displayName: String,
    val format: BootstrapFormat,
) : DistroBootstrap {
    companion object {
        /**
         * The format a file named [displayName] would be read as, or
         * null when the name carries no extension we can decompress.
         *
         * Null is a rejection, not a fallback: the format decides which
         * decompressor runs, and guessing wrong turns "you picked the
         * wrong file" into a decompression error several hundred
         * megabytes later. SAF providers are free to hand back a
         * display name with no extension at all, which is exactly why
         * this is checked before anything is copied.
         */
        fun formatFor(displayName: String?): BootstrapFormat? {
            if (displayName == null) return null
            return BootstrapFormat.entries.firstOrNull { displayName.endsWith(".${it.ext}") }
        }
    }
}

/**
 * The address this bootstrap is fetched from, or null when it has none —
 * an [ImportedPack] is a local file the user picked, so there is no
 * location to display or to re-root.
 *
 * Exists so callers don't each write their own `when` over the sealed
 * hierarchy: adding a descriptor type then means handling it here rather
 * than in every site that just wanted a URL.
 */
val DistroBootstrap.origin: String?
    get() = when (this) {
        is TarballBootstrap -> url
        is PackageBootstrap -> archiveRoot
        is ImportedPack -> null
    }

/**
 * Assemble the rootfs from signed repo metadata + individual packages
 * (Debian family today, via vendored debootstrap — see
 * [me.phie.tawc.install.pkgbootstrap.PackageBootstrapInstaller]).
 *
 * The trust root is [keyResource]: a `res/raw` PGP keyring shipped in
 * the APK, against which the suite's clearsigned `InRelease` is
 * verified before any downloaded byte is trusted. Deliberately
 * non-optional — this type has no "skip verification" shape at all.
 *
 * @property archiveRoot repo root, e.g. `http://deb.debian.org/debian`.
 * @property suite e.g. `"sid"`.
 * @property packagesArch dpkg architecture (`"arm64"`, `"amd64"`) —
 *   names the `binary-<arch>` index; distinct from [Distro.linuxArch].
 * @property keyResource `res/raw` keyring name (no extension),
 *   registered in [me.phie.tawc.install.SignatureVerifier]'s key map.
 */
data class PackageBootstrap(
    val archiveRoot: String,
    val suite: String,
    val packagesArch: String,
    val keyResource: String,
) : DistroBootstrap
