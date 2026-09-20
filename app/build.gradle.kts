plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Per-build enabled install methods. Defaults: debug ships all three
// (tawcroot/proot/chroot for dev-loop coverage), release ships only
// tawcroot (the default and only officially supported method —
// chroot/proot are dev-only). Override either side with
// `-PtawcMethods=tawcroot[,proot[,chroot]]`. tawcroot must always be
// enabled — it's the default for new installs and the fallback for
// uninstalls of legacy slots that recorded a now-disabled method.
val explicitTawcMethods: Set<String>? = (project.findProperty("tawcMethods") as String?)
    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
val debugMethods: Set<String> = explicitTawcMethods ?: setOf("tawcroot", "proot", "chroot")
val releaseMethods: Set<String> = explicitTawcMethods ?: setOf("tawcroot")
val knownMethods = setOf("tawcroot", "proot", "chroot")
run {
    val unknown = (debugMethods + releaseMethods) - knownMethods
    require(unknown.isEmpty()) { "Unknown tawcMethods: $unknown (allowed: $knownMethods)" }
    require("tawcroot" in debugMethods && "tawcroot" in releaseMethods) {
        "tawcroot must be enabled (got tawcMethods=$explicitTawcMethods)"
    }
}

// Per-build enabled graphics backends. Default: all three unless the
// caller passes a production set (scripts/build-release-apk.sh does).
// Override with `-PtawcGraphics=libhybris,turnip,none`.
//
// `none` is not a driver — it is the "no driver provisioned" state, which
// keeps a key here so a build can drop even that. See
// `me.phie.tawc.GraphicsBackend.NONE`.
//
// The display-only backends (`gfxstream`, `libhybris-zink`) are gone
// with the compositor (TAWC_DSH_DESIGN.md §11) — gfxstream's kumquat
// server only ever existed as a thread of that process, and Zink only
// existed to present GL through it. The Turnip cross-build is
// independent (its own Mesa pin, `mesa-turnip` in deps/deps.list). See
// `me.phie.tawc.install.EnabledGraphicsBackends`.
val explicitTawcGraphics: Set<String>? = (project.findProperty("tawcGraphics") as String?)
    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
val enabledGraphics: Set<String> = explicitTawcGraphics
    ?: setOf("libhybris", "turnip", "none")
val knownGraphics = setOf("libhybris", "turnip", "none")
run {
    val unknown = enabledGraphics - knownGraphics
    require(unknown.isEmpty()) { "Unknown tawcGraphics: $unknown (allowed: $knownGraphics)" }
    require(enabledGraphics.isNotEmpty()) {
        "tawcGraphics must enable at least one backend (got empty set)"
    }
}
val libhybrisEnabled: Boolean = "libhybris" in enabledGraphics
val turnipEnabled: Boolean = "turnip" in enabledGraphics

fun booleanProjectPropertyOrNull(name: String): Boolean? {
    val raw = project.findProperty(name) as String? ?: return null
    return when (raw.trim().lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> error("Invalid $name=$raw (expected true or false)")
    }
}

fun booleanProjectProperty(name: String, default: Boolean): Boolean =
    booleanProjectPropertyOrNull(name) ?: default

// Per-build enabled bootstrap flavors (notes/installation.md
// "Bootstrap flavors"). `tarball` is always shipped and is the default
// for every distro; the on-device `packages` flavor (Debian sid
// debootstrap) is a dev-only experiment — debug ships it, release does
// not, so a production APK has no flavor option at all. Override both
// sides with `-PtawcBootstrapPackages=true|false`. Same shape as the
// tawcMethods gate above; see `me.phie.tawc.install.EnabledBootstrapFlavors`.
val explicitPackagesBootstrap: Boolean? = booleanProjectPropertyOrNull("tawcBootstrapPackages")
val debugPackagesBootstrap: Boolean = explicitPackagesBootstrap ?: true
val releasePackagesBootstrap: Boolean = explicitPackagesBootstrap ?: false
val anyVariantPacksDebootstrap: Boolean = debugPackagesBootstrap || releasePackagesBootstrap

// Ship MANAGE_EXTERNAL_STORAGE (the external-storage binds feature,
// notes/external-binds.md)? Default yes; `-PtawcAllFilesAccess=false`
// strips the permission via a build-type manifest overlay for
// distribution channels that can't carry it (Google Play review). The
// app detects the stripped permission at runtime and hides the binds
// UI, so no code changes ride on this flag.
val allFilesAccess: Boolean = booleanProjectProperty("tawcAllFilesAccess", true)

// Build the native pieces for one or both Android ABIs and copy the
// resulting binaries into jniLibs/. Override the default by setting the
// `tawcAbis` Gradle property: `-PtawcAbis=arm64-v8a` or
// `-PtawcAbis=x86_64` or `-PtawcAbis=arm64-v8a,x86_64`.
val tawcAbis: List<String> = (project.findProperty("tawcAbis") as String?
    ?: "arm64-v8a").split(",").map { it.trim() }.filter { it.isNotEmpty() }

// The version. `versionName` is the single source — nothing else in the repo
// names a version statically — and `versionCode` is *derived* here rather than
// written down a second time, so the two cannot drift.
//
// Format `major.minor[.patch]`; code = major*10000 + minor*100 + patch:
// 0.1 → 100, 0.1.1 → 101, 0.2 → 200, 1.0 → 10000. Android only asks that the
// code increase, and this leaves 100 patches per minor and 100 minors per
// major. The `0` lower bound of each part is what lets the code stay
// monotonic while the name goes wherever it likes.
//
// scripts/check-version-sync.sh mirrors the arithmetic in bash, but only to
// report — the copy that stamps an APK is this one, so a disagreement cannot
// change a build. It also rejects a `versionName` this parse would throw on,
// which is a second instead of a build.
val tawcVersionName = "0.1"
val tawcVersionCode: Int = run {
    val parts = tawcVersionName.split(".").map { it.toInt() }
    require(parts.size in 2..3) { "versionName must be major.minor[.patch]: $tawcVersionName" }
    require(parts.all { it in 0..99 }) { "each versionName part must be 0..99: $tawcVersionName" }
    parts[0] * 10000 + parts[1] * 100 + parts.getOrElse(2) { 0 }
}

android {
    namespace = "me.phie.tawc"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        // The application id is this fork's identity; `namespace` above is
        // the Kotlin/Java package and deliberately stays upstream's. Keeping
        // them apart is what lets the tree keep upstream's class names
        // verbatim while installing as a distinct app — its own
        // /data/data/<id>/, no shared state with a `me.phie.tawc` install
        // alongside, and no class moves.
        applicationId = "io.github.kaeno_tori.tawc_dsh"
        minSdk = 29
        targetSdk = 36
        // `versionName` is the source; `versionCode` is derived from it above.
        // See notes/release.md.
        versionName = tawcVersionName
        versionCode = tawcVersionCode
        ndk {
            abiFilters.addAll(tawcAbis)
        }
    }

    buildTypes {
        getByName("debug") {
            // LogScreenActivity is exported in debug only — the export
            // exists so `am start … --es operationId` from adb can
            // attach to a running op; release keeps it app-internal.
            manifestPlaceholders["logScreenExported"] = "true"
            buildConfigField("boolean", "METHOD_TAWCROOT_ENABLED", "${"tawcroot" in debugMethods}")
            buildConfigField("boolean", "METHOD_PROOT_ENABLED",    "${"proot" in debugMethods}")
            buildConfigField("boolean", "METHOD_CHROOT_ENABLED",   "${"chroot" in debugMethods}")
            buildConfigField("boolean", "BOOTSTRAP_PACKAGES_ENABLED", "$debugPackagesBootstrap")
            buildConfigField("boolean", "GRAPHICS_LIBHYBRIS_ENABLED", "${"libhybris" in enabledGraphics}")
            buildConfigField("boolean", "GRAPHICS_TURNIP_ENABLED",    "${"turnip" in enabledGraphics}")
            buildConfigField("boolean", "GRAPHICS_NONE_ENABLED",      "${"none" in enabledGraphics}")
        }
        getByName("release") {
            manifestPlaceholders["logScreenExported"] = "false"
            // Deliberately unminified: R8 would roughly halve the APK
            // (~13 vs ~29 MB, mostly BouncyCastle dex), but obfuscated
            // crash traces need per-release mapping.txt juggling and we
            // don't care about size below ~50 MB. Debugging beats
            // megabytes. If this is ever flipped on, proguard-rules.pro
            // already carries the keep rules R8 needs (zstd-jni JNI,
            // line-number attributes). Native libs also ship unstripped
            // — see packaging {} below.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField("boolean", "METHOD_TAWCROOT_ENABLED", "${"tawcroot" in releaseMethods}")
            buildConfigField("boolean", "METHOD_PROOT_ENABLED",    "${"proot" in releaseMethods}")
            buildConfigField("boolean", "METHOD_CHROOT_ENABLED",   "${"chroot" in releaseMethods}")
            buildConfigField("boolean", "BOOTSTRAP_PACKAGES_ENABLED", "$releasePackagesBootstrap")
            buildConfigField("boolean", "GRAPHICS_LIBHYBRIS_ENABLED", "${"libhybris" in enabledGraphics}")
            buildConfigField("boolean", "GRAPHICS_TURNIP_ENABLED",    "${"turnip" in enabledGraphics}")
            buildConfigField("boolean", "GRAPHICS_NONE_ENABLED",      "${"none" in enabledGraphics}")
        }
    }

    // Drop the proot binaries from any APK that doesn't ship the proot
    // method. The Gradle build still produces them when *some* enabled
    // variant uses proot (the debug variant by default), so the per-
    // variant exclusion is the bit that actually matters for the
    // shipped APK size / surface area.
    androidComponents {
        onVariants { variant ->
            val variantMethods = if (variant.buildType == "release") releaseMethods else debugMethods
            if ("proot" !in variantMethods) {
                variant.packaging.jniLibs.excludes.addAll(
                    "**/libproot.so",
                    "**/libproot-loader.so",
                )
            }
        }
    }

    // BuildConfig.DEBUG gates dev-only paths (e.g. mirror cache plumbing
    // in InstallationService); off by default in AGP 8.
    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    // TerminalSessionsTest constructs real TerminalSessions, whose
    // android.os.Handler field must no-op (not throw) on the plain JVM.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
        // Build-type manifests merge with higher priority than main's,
        // so pointing them at the shared `tools:node="remove"` overlay
        // strips MANAGE_EXTERNAL_STORAGE from every variant. Neither
        // build type has a manifest of its own otherwise.
        if (!allFilesAccess) {
            getByName("debug") {
                manifest.srcFile("src/overlays/no-all-files-access/AndroidManifest.xml")
            }
            getByName("release") {
                manifest.srcFile("src/overlays/no-all-files-access/AndroidManifest.xml")
            }
        }
    }

    // BouncyCastle's three jars (bcpg, bcprov, bcutil) each carry the
    // same MR-jar OSGI manifest at META-INF/versions/9/OSGI-INF/MANIFEST.MF
    // and the Android packager refuses to merge identically-named
    // resources by default. Picking the first is safe — the manifests
    // are OSGi metadata, irrelevant at Android runtime.
    //
    // jniLibs.useLegacyPackaging=true pairs with
    // android:extractNativeLibs="true" in AndroidManifest.xml. AGP 8
    // prefers page-aligned-in-APK loading by default, but our proot
    // binary needs to be a real on-disk executable for execve(2);
    // legacy packaging forces extraction at install time.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // AGP strips jniLibs by default; keep symbol tables instead
            // so debuggerd emits symbolized native tombstones straight
            // from the device — no hunting for the matching unstripped
            // artifact. Same debugging-beats-megabytes call as the
            // unminified release block above.
            keepDebugSymbols.add("**/*.so")
        }
        resources {
            pickFirsts.add("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
            // bcprov data blobs for classes we never reach (R8 strips the
            // classes but not their java resources): ~1.2 MB of picnic
            // post-quantum matrices + CertPathReviewer message catalogs.
            // The PGP verify path (SignatureVerifier) touches neither.
            excludes.add("org/bouncycastle/pqc/**")
            excludes.add("org/bouncycastle/x509/*.properties")
        }
    }

    if (!turnipEnabled) {
        androidResources {
            ignoreAssetsPatterns.add("turnip")
        }
    }
    if (!libhybrisEnabled) {
        androidResources {
            ignoreAssetsPatterns.add("libhybris")
        }
    }
}

dependencies {
    // The install package extracts bootstrap tarballs (.tar.gz, .tar.zst,
    // .tar.xz) entirely in-process: commons-compress reads tar/gzip;
    // zstd-jni decodes zstd; xz-java decodes xz/LZMA. Together this keeps
    // the install path tool-free.
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.github.luben:zstd-jni:1.5.6-9@aar")
    implementation("org.tukaani:xz:1.10")

    // BouncyCastle: detached-PGP-signature verification of the Arch
    // x86_64 bootstrap tarball before we extract it as root. See
    // notes/installation.md "Bootstrap integrity". `jdk18on` = JDK 1.8
    // and up (matches our `JavaVersion.VERSION_11`); `bcpg` brings the
    // OpenPGP layer, `bcprov` the underlying crypto provider.
    implementation("org.bouncycastle:bcpg-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ActivityCompat / ServiceCompat / ViewCompat / WindowInsetsCompat
    // and the `edit {}` SharedPreferences extension. Already on the
    // classpath transitively via material; explicit because we compile
    // against it directly.
    implementation("androidx.core:core-ktx:1.15.0")

    // RecyclerView backs the licenses screen, whose ~750 KB attribution
    // text is too large to lay out as one TextView. Already on the
    // classpath transitively via material; pinned to the version that
    // already resolves, so this only makes the existing edge explicit.
    implementation("androidx.recyclerview:recyclerview:1.1.0")

    // Material Components powers the app's chrome: Material3 DayNight
    // theme (auto light/dark), MaterialToolbar with the
    // back-arrow up affordance, and MaterialButton for the accented /
    // destructive button styles. AppCompat is pulled in transitively.
    implementation("com.google.android.material:material:1.12.0")

    // Termux's terminal widget (Apache-2.0): VT emulation + pty spawn
    // (terminal-emulator, pulled in transitively) and the Android View
    // with IME/scroll/selection handling (terminal-view). Vendored from
    // deps/termux-app — see settings.gradle.kts. Used by TerminalActivity.
    implementation(project(":terminal-view"))
    // Termux's extra-keys row (GPLv3 — see termux-extrakeys/build.gradle.kts).
    implementation(project(":termux-extrakeys"))

    // Host-side unit tests (src/test): `./gradlew :app:testDebugUnitTest`.
    // The real org.json artifact shadows the throw-on-use stubs in the
    // mockable android.jar so metadata (de)serialization is testable
    // off-device.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// The exec broker and its actions live in `src/debug/java`, so they are
// structurally absent from release builds rather than merely unstarted
// (notes/exec-broker.md). Assert that on the compiled release classes —
// cheap, since compileReleaseKotlin needs no native/asset work. The same
// script runs over the finished APK's dex from
// scripts/build-release-apk.sh.
val checkNoDevCode = tasks.register<Exec>("checkNoDevCode") {
    dependsOn("compileReleaseKotlin", "compileReleaseJavaWithJavac")
    workingDir = rootProject.projectDir
    commandLine("scripts/check-no-dev-code.sh")
    inputs.file("$rootDir/scripts/check-no-dev-code.sh")
    inputs.dir(layout.buildDirectory.dir("tmp/kotlin-classes/release"))
}

tasks.named("check") {
    dependsOn(checkNoDevCode)
}

val rustTripleFor = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "x86_64" to "x86_64-linux-android",
)

// Working-tree fingerprint (HEAD + tracked-edit hash) for the named deps,
// via `ensure-deps.sh --tree-state`. Declared as an input property on every
// task whose artifact embodies dep *sources*, so local edits — and their
// later discard by update-deps.sh — rebuild the artifact instead of letting
// it ship stale. An arg ending in "/" selects every dep whose deps.list
// dest lives under that prefix (no hardcoded list to drift).
fun depTreeState(vararg deps: String): Provider<String> = providers.exec {
    workingDir(rootDir)
    commandLine(listOf("scripts/ensure-deps.sh", "--tree-state") + deps)
}.standardOutput.asText

tawcAbis.forEach { abi ->
    val triple = rustTripleFor[abi] ?: error("Unsupported ABI: $abi")
    val capAbi = abi.replaceFirstChar { it.uppercase() }

    val tawcRoot = rootProject.projectDir

    // Cross-build proot (Termux fork) and stage libproot.so +
    // libproot-loader.so under jniLibs. Same shape as buildLibhybris:
    // invokes the host script, skipped when the output binaries
    // already exist. The script is itself incremental, so iteration
    // is `scripts/build-proot.sh --abi=...` direct; Gradle just
    // makes a fresh checkout's `assembleDebug` self-contained.
    //
    // Skipped entirely when no enabled variant ships the proot method
    // (e.g. `-PtawcMethods=tawcroot` everywhere) — the per-variant
    // packaging exclusion above also drops the staged .so files from
    // any APK that doesn't use them.
    val abiToScriptArg = mapOf("arm64-v8a" to "aarch64", "x86_64" to "x86_64")
    val scriptAbi = abiToScriptArg[abi] ?: error("Unsupported ABI: $abi")
    val anyVariantUsesProot = "proot" in debugMethods || "proot" in releaseMethods
    if (anyVariantUsesProot) {
        val prootBin = "$tawcRoot/app/src/main/jniLibs/$abi/libproot.so"
        val prootLoader = "$tawcRoot/app/src/main/jniLibs/$abi/libproot-loader.so"
        val buildProotTask = tasks.register<Exec>("buildProot$capAbi") {
            workingDir = tawcRoot
            environment("ANDROID_NDK_HOME", "${android.ndkDirectory}")
            commandLine("scripts/build-proot.sh", "--abi=$scriptAbi")
            inputs.file("$tawcRoot/scripts/build-proot.sh")
            // Pin bumps in deps/deps.list must invalidate the cache.
            inputs.file("$tawcRoot/deps/deps.list")
            inputs.file("$tawcRoot/scripts/lib/deps.sh")
            inputs.property("depTreeState", depTreeState("proot"))
            outputs.files(prootBin, prootLoader)
        }
        tasks.named("preBuild") {
            dependsOn(buildProotTask)
        }
    }

    // Cross-build tawcroot (the systrap-based proot replacement) and
    // stage libtawcroot.so under jniLibs. Same shape as buildProot.
    val tawcrootBin = "$tawcRoot/app/src/main/jniLibs/$abi/libtawcroot.so"
    val buildTawcrootTask = tasks.register<Exec>("buildTawcroot$capAbi") {
        workingDir = tawcRoot
        environment("ANDROID_NDK_HOME", "${android.ndkDirectory}")
        commandLine("tawcroot/build.sh", "--abi=$scriptAbi")
        inputs.file("$tawcRoot/tawcroot/build.sh")
        // Source + header changes must invalidate the cache. Without this,
        // adding a new .c file (and listing it in `tawcroot/build.sh`) is the
        // only kind of edit that gets noticed — pure source/header edits
        // are silently dropped, leaving a stale binary in jniLibs. The
        // chroot.c regression (added in commit 4244bbb but not rebuilt
        // for aarch64 until this fix) was exactly that.
        inputs.dir("$tawcRoot/tawcroot/src")
        inputs.dir("$tawcRoot/tawcroot/include")
        // Pin bumps in deps/deps.list must invalidate the cache (cleat).
        inputs.file("$tawcRoot/deps/deps.list")
        inputs.file("$tawcRoot/scripts/lib/deps.sh")
        inputs.property("depTreeState", depTreeState("cleat"))
        outputs.file(tawcrootBin)
    }
    tasks.named("preBuild") {
        dependsOn(buildTawcrootTask)
    }

    // Cross-build the ando guest client (static bionic) and stage
    // libando.so under jniLibs. Same shape as buildTawcroot.
    val andoBin = "$tawcRoot/app/src/main/jniLibs/$abi/libando.so"
    val buildAndoTask = tasks.register<Exec>("buildAndo$capAbi") {
        workingDir = tawcRoot
        environment("ANDROID_NDK_HOME", "${android.ndkDirectory}")
        commandLine("tawcroot/ando/build.sh", "--abi=$scriptAbi")
        inputs.file("$tawcRoot/tawcroot/ando/build.sh")
        inputs.dir("$tawcRoot/tawcroot/ando/src")
        outputs.file(andoBin)
    }
    tasks.named("preBuild") {
        dependsOn(buildAndoTask)
    }

    // Cross-build the ando JNI bridge and stage libandobridge.so under
    // jniLibs.
    //
    // Why a separate Rust library: `System.loadLibrary` loads a whole
    // .so, so keeping ando's JNI shell in its own library means the
    // broker can start without dragging in whatever else the app links
    // natively. This crate links the `ando-broker` rlib. See
    // notes/ando.md "Components".
    val andoBridgeSo = "$tawcRoot/andobridge/target/$triple/release/libandobridge.so"
    val buildAndoBridgeTask = tasks.register<Exec>("buildAndoBridge$capAbi") {
        workingDir = file("${rootProject.projectDir}/andobridge")
        environment("ANDROID_NDK_HOME", "${android.ndkDirectory}")
        commandLine(
            "cargo", "ndk",
            "--target", abi,
            "--platform", "29",
            "--",
            "build", "--release",
        )
        inputs.files(
            "${rootProject.projectDir}/andobridge/Cargo.toml",
            "${rootProject.projectDir}/andobridge/Cargo.lock",
            "${rootProject.projectDir}/ando-broker/Cargo.toml",
        )
        inputs.dir("${rootProject.projectDir}/andobridge/src")
        // The broker logic itself is a path dep, so editing it has to
        // invalidate this task's cache too.
        inputs.dir("${rootProject.projectDir}/ando-broker/src")
        outputs.file(andoBridgeSo)
    }
    val copyAndoBridgeTask = tasks.register<Copy>("copyAndoBridge$capAbi") {
        dependsOn(buildAndoBridgeTask)
        from(andoBridgeSo)
        into("src/main/jniLibs/$abi/")
    }
    tasks.named("preBuild") {
        dependsOn(copyAndoBridgeTask)
    }
}

// Pack the vendored debootstrap tree (deps/debootstrap, pinned in
// deps/deps.list) into an asset tar for the packages bootstrap flavor
// (me.phie.tawc.install.pkgbootstrap.PackageBootstrapInstaller). A tar
// because the Android asset packager strips symlinks from individual
// assets and `scripts/sid` & friends are symlinks; the runtime
// extracts it with ProotArchiveExtractor, symlinks intact.
// Arch-independent (shell scripts), so this lives outside the per-ABI
// blocks.
//
// Built into a generated dir that is registered as an assets srcDir
// only on the build types that ship the packages flavor (debug by
// default), so a release APK carries no debootstrap at all — and the
// whole build step is skipped when no variant wants it.
if (anyVariantPacksDebootstrap) {
    val tawcRoot = rootProject.projectDir
    val debootstrapDir = "$tawcRoot/deps/debootstrap"
    val debootstrapAssetRoot = layout.buildDirectory.dir("generated/tawc-assets/debootstrap").get().asFile
    val debootstrapAssetFile = File(debootstrapAssetRoot, "debootstrap/debootstrap.tar")

    val ensureDebootstrapTask = tasks.register<Exec>("ensureDebootstrap") {
        workingDir = tawcRoot
        commandLine("scripts/ensure-deps.sh", "debootstrap")
        inputs.file("$tawcRoot/scripts/ensure-deps.sh")
        inputs.file("$tawcRoot/deps/deps.list")
        inputs.file("$tawcRoot/scripts/lib/deps.sh")
        outputs.file("$debootstrapDir/debootstrap")
    }

    val packDebootstrapTask = tasks.register<Exec>("packDebootstrap") {
        dependsOn(ensureDebootstrapTask)
        doFirst { mkdir(debootstrapAssetFile.parentFile) }
        workingDir = file(debootstrapDir)
        // Only what the runtime invokes: the entry script, the shared
        // functions library, and the per-suite scripts dir.
        commandLine("tar", "--format=ustar",
            "-cf", debootstrapAssetFile.absolutePath,
            "debootstrap", "functions", "scripts")
        inputs.file("$tawcRoot/deps/deps.list")
        inputs.property("depTreeState", depTreeState("debootstrap"))
        outputs.file(debootstrapAssetFile)
    }

    val packagesBuildTypes = buildList {
        if (debugPackagesBootstrap) add("debug" to "mergeDebugAssets")
        if (releasePackagesBootstrap) add("release" to "mergeReleaseAssets")
    }
    for ((buildType, mergeAssetsTask) in packagesBuildTypes) {
        android.sourceSets.getByName(buildType).assets.srcDir(debootstrapAssetRoot)
        // The srcDir lives under build/, so the merge task needs the
        // producer as an explicit dependency (preBuild alone doesn't
        // order it).
        tasks.matching { it.name == mergeAssetsTask }.configureEach {
            dependsOn(packDebootstrapTask)
        }
    }
}

// The debootstrap tar used to be generated straight into
// src/main/assets, which shipped it in every APK including release.
// Delete leftovers from such a tree so they don't ride along.
val pruneStaleDebootstrapAssets = tasks.register<Delete>("pruneStaleDebootstrapAssets") {
    delete("src/main/assets/debootstrap")
}
tasks.named("preBuild") {
    dependsOn(pruneStaleDebootstrapAssets)
}

// Cross-compile libhybris for aarch64 glibc on the host and pack it
// (with symlinks preserved) as an APK asset. Extracted at runtime by
// TawcAssets.ensureLibhybrisExtracted into the app's filesDir
// and copied into each rootfs as real files by LibhybrisInstallProvider.
//
// Only aarch64 — libhybris is unsupported on the x86_64 emulator
// (notes/emulator.md). If the user runs with `-PtawcAbis=x86_64`
// only, we silently skip libhybris bundling.
//
// The actual cross-compile lives in scripts/build-libhybris.sh so
// it can be run by hand for development. This Gradle task just invokes
// it and packs the result.
if (libhybrisEnabled && "arm64-v8a" in tawcAbis) {
    val tawcRoot = rootProject.projectDir
    val libhybrisAbi = "arm64-v8a"
    // build-libhybris.sh now passes `--prefix=/usr/lib/hybris
    // --libdir=/usr/lib/hybris` so all the on-device paths libhybris
    // bakes into its .so files (DT_RUNPATH, PKGLIBDIR, LINKER_PLUGIN_DIR)
    // line up with where [LibhybrisInstallProvider] copies them. The
    // DESTDIR install therefore lays files at
    // `install/usr/lib/hybris/{libfoo.so, libhybris/, gl-shims/}` —
    // pack from there.
    val libhybrisInstallDir = "$tawcRoot/build/libhybris-aarch64/install/usr/lib/hybris"
    val libhybrisAssetFile = "src/main/assets/libhybris/$libhybrisAbi.tar"

    val buildLibhybrisTask = tasks.register<Exec>("buildLibhybris") {
        workingDir = tawcRoot
        commandLine("scripts/build-libhybris.sh")
        // The cross-compile script is itself incremental, but Gradle
        // still has to know when to invoke it. Tracked inputs:
        //   - the build script itself
        //   - the dep manifest + helper (so a pin bump invalidates the
        //     cache — otherwise a moved libhybris commit would silently
        //     keep shipping the old .so set; see AGENTS.md "Vendored deps")
        // The output dir snapshot covers the rest.
        inputs.file("$tawcRoot/scripts/build-libhybris.sh")
        inputs.file("$tawcRoot/deps/deps.list")
        inputs.file("$tawcRoot/scripts/lib/deps.sh")
        inputs.property("depTreeState", depTreeState("libhybris", "android-headers"))
        outputs.dir(libhybrisInstallDir)
    }

    val packLibhybrisTask = tasks.register<Exec>("packLibhybris") {
        dependsOn(buildLibhybrisTask)
        // Use tar(1) on the host because the Android packager strips
        // symlinks from individual assets, but happily ships an opaque
        // .tar that the runtime can untar with symlinks intact. We use
        // `--format=ustar` for portability and chdir into the install
        // tree so paths in the tar are relative.
        //
        // Tar contents are flat (libEGL.so, libhybris/, gl-shims/, …
        // at the tar root) — `TawcAssets.ensureLibhybrisExtracted`
        // extracts them into `<filesDir>/libhybris/` and
        // [LibhybrisInstallProvider] walks that dir directly.
        doFirst { mkdir(file(libhybrisAssetFile).parentFile) }
        workingDir = file(libhybrisInstallDir)
        // Exclude:
        //  - `.la`        — libtool archives, reference host paths
        //  - `pkgconfig/` — `.pc` files, also reference host paths
        //  - `include/`   — C headers, not needed at runtime
        //  - `bin/`       — `getprop`/`setprop` utilities, not used
        commandLine("tar", "--format=ustar",
            "--exclude=*.la", "--exclude=pkgconfig",
            "--exclude=include", "--exclude=bin",
            "-cf", "${project.projectDir}/$libhybrisAssetFile", ".")
        inputs.dir(libhybrisInstallDir)
        outputs.file(libhybrisAssetFile)
    }

    tasks.named("preBuild") {
        dependsOn(packLibhybrisTask)
    }
} // end libhybris (arm64-v8a in tawcAbis)

// Cross-compile Mesa's Turnip (freedreno Vulkan, kgsl-only) for aarch64
// and stage the three files the TURNIP graphics backend lays into each
// rootfs: the ICD itself, its manifest, and the Vulkan loader that
// reaches it. aarch64-only — an x86_64 build ships no Turnip asset and
// falls back to the CPU backend there.
//
// Staged straight into src/main/assets/turnip/<abi>/ as three plain
// files: no symlinks anywhere in this set, so unlike libhybris it needs
// no tar wrapper. The runtime extracts them into <filesDir>/turnip/ via
// TawcAssets.ensureTurnipExtracted; TawcrootMethod binds that dir
// read-only at /usr/lib/turnip, proot/chroot get real copies from
// TurnipInstallProvider.
//
// The cross-compile itself lives in scripts/build-turnip.sh so it can be
// run by hand for development; Gradle just invokes it and packs the
// result.
if ("arm64-v8a" in tawcAbis) {
    val tawcRoot = rootProject.projectDir
    val turnipAbi = "arm64-v8a"
    val turnipInstallDir = "$tawcRoot/build/turnip-aarch64/install/usr/lib/turnip"
    val turnipAssetDir = "src/main/assets/turnip/$turnipAbi"

    val buildTurnipTask = if (turnipEnabled) {
        tasks.register<Exec>("buildTurnip") {
            workingDir = tawcRoot
            commandLine("scripts/build-turnip.sh")
            // Same incremental-input contract as buildLibhybris:
            //   - the build script
            //   - dep manifest + helper (the mesa-turnip pin bump has to
            //     invalidate this, or the APK keeps shipping the old
            //     driver; see AGENTS.md "Vendored deps")
            inputs.file("$tawcRoot/scripts/build-turnip.sh")
            inputs.file("$tawcRoot/deps/deps.list")
            inputs.file("$tawcRoot/scripts/lib/deps.sh")
            inputs.property("depTreeState", depTreeState("mesa-turnip"))
            outputs.dir(turnipInstallDir)
        }
    } else {
        null
    }

    // Sync, not Copy: the asset dir is fully generated, and a driver
    // rename would otherwise leave the old .so behind to ship silently.
    val packTurnipTask = if (turnipEnabled) {
        tasks.register<Sync>("packTurnip") {
            dependsOn(buildTurnipTask!!)
            into("${project.projectDir}/$turnipAssetDir")
            from(turnipInstallDir)
        }
    } else {
        tasks.register<Delete>("packTurnip") {
            delete("${project.projectDir}/$turnipAssetDir")
        }
    }

    tasks.named("preBuild") {
        dependsOn(packTurnipTask)
    }
} else {
    // No arm64 target in this build (e.g. `-PtawcAbis=x86_64`): wipe any
    // staged driver from a previous arm64 build so the APK can't smuggle
    // 16 MB of dead weight.
    tasks.register<Delete>("pruneStaleTurnipAssets") {
        delete("src/main/assets/turnip")
    }
    tasks.named("preBuild") {
        dependsOn("pruneStaleTurnipAssets")
    }
} // end turnip (arm64-v8a in tawcAbis)


