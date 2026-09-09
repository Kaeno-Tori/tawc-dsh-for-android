# Plans Index

Future work and speculative implementation plans live here. Keep current-state
design, build, and operational notes in [`../notes/`](../notes/).

- [usecase_tests/](usecase_tests/README.md) - one-shot agent-run usecase test plans; see that README for the procedure.
- [alarm-package-bootstrap.md](alarm-package-bootstrap.md) - packages bootstrap flavor for Arch Linux ARM (pacman as the workspace guest, per-package sigs against the shipped build-system key; smaller install, weaker index trust than Debian's — documented).
- [ando-am.md](ando-am.md) - termux-am-style in-app `am` behind broker interception of `ando am`, so unrooted guests can launch activities/broadcasts/services.
- [audio.md](audio.md) - planned PipeWire/PulseAudio bridge to Android audio.
- [f-droid.md](f-droid.md) - publish on F-Droid: verified current state, repo fixes (tarball checksums, toolchain pins), fastlane metadata, fdroiddata recipe; human-only steps (store copy, MR, reviewer contact) explicitly marked.
- [clipboard-paste-focus-gate.md](clipboard-paste-focus-gate.md) - only serve the Android clipboard to the focused client; today any X11 client on the shared Xwayland can read it.
- [gfxstream-bridge-remaining-work.md](gfxstream-bridge-remaining-work.md) - remaining GL/GLES and x86_64 AVD work for the gfxstream bridge backend.
- [gl-on-gles-translator.md](gl-on-gles-translator.md) - possible in-house GL 3.3-core-on-ES 3.2 translator (glslang/SPIRV-Cross shader pipeline) for the modern-GL gap zink can't cover on Vulkan 1.1 devices.
- [proc-shadow-metadata.md](proc-shadow-metadata.md) - give tawcroot's `/proc` shadows a stat/statx/access surface driven by one classifier, and add `version`/`uptime`/`loadavg` shadows (fixes LibreOffice's "/proc not mounted" exit, wmww/tawc#2).
- [tawcroot-landlock.md](tawcroot-landlock.md) - kernel-enforced path containment for tawcroot via Landlock (probe-and-enable, kernel 5.13+).
- [ubuntu-distro.md](ubuntu-distro.md) - add Ubuntu 24.04 as a distro from the signed ubuntu-base tarball (cdimage PGP over SHA256SUMS), both ABIs.
- [x86-box64.md](x86-box64.md) - per-program x86_64 emulation in existing arm64 distros via box64 (native-library wrapping; 16K-page hedge).
- [x86-fex-glibc.md](x86-fex-glibc.md) - FEX installed inside an arm64 glibc distro; x86_64 RootFS; tawcroot exec dispatch; whole-x86-distro stretch.
- [x86-fex-bionic.md](x86-fex-bionic.md) - speculative bionic-built FEX with no glibc layer, thunking straight to NDK graphics; gated on the glibc FEX plan.
