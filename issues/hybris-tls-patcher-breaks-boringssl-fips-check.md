# hybris TLS patcher aborts Android libcrypto (BoringSSL FIPS check)

Upstream report: https://github.com/wmww/tawc/issues/11 — Debian sid,
`gtk4-widget-factory` dies at startup with

    FIPS integrity test failed.
    Expected:   025d846f...
    Calculated: 20ed2e6c...
    Aborted

Reporter notes the CPU backend has no such error (it loads no Android
libraries at all, so neither the patcher nor BoringSSL is involved).

Any Android library that ends up in the hybris namespace and pulls
`libcrypto.so` will abort the whole process, so on an affected device
this kills every hybris-backed GL client, not just GTK.

## Root cause (2026-09-09, fully diagnosed)

Android's `libcrypto.so` is a BoringSSL FIPS build: at load it HMACs its
own text and rodata and compares against a hash injected at link time.

`hybris/common/tls_patcher_aarch64.c:118` (`hybris_patch_tls_arch`,
called from `linker_phdr.cpp:796` before constructors run) scans every
executable segment of every Android library hybris loads and rewrites
each `mrs Xn, tpidr_el0` into `b <thunk>` — the workaround for glibc
owning `TPIDR_EL0`. Android's libcrypto has **170** such instructions
inside `[BORINGSSL_bcm_text_start, BORINGSSL_bcm_text_end)`, so the
integrity check can never pass.

Two independent confirmations on `50f4ca18` (Qualcomm/Adreno, Debian
sid rootfs, libhybris backend):

- The printed *Expected* value is exactly
  `HMAC-SHA256(key = 64 zero bytes)` over
  `len(text) ‖ text ‖ len(rodata) ‖ rodata` taken from the **pristine
  on-disk file** — the baseline is right, memory is what changed.
- Dumping the mapped text from a `SIGABRT` handler and diffing it
  against the file gives **170 differing words, all 170 `mrs Xn,
  tpidr_el0` on disk and `b` in memory**. Nothing else inside the
  checked region differs.

## Why it did not reproduce through GTK here

`gtk4-widget-factory` runs fine on the Adreno phone. Nothing in the
Qualcomm EGL/gralloc chain pulls libcrypto — `/proc/<pid>/maps` shows
~50 Android libs loaded and no libcrypto, and the only `/vendor/lib64`
users of it are keymaster/wifi/DRM/curl, none of which the graphics
stack touches. Reproduced directly instead with a rootfs test program:

    hybris_dlopen("/system/lib64/libcrypto.so", RTLD_LAZY);

which aborts with the same message. Which vendors drag libcrypto into
the graphics namespace is device-specific; the failure itself is not.

## Fix options

### 1. Neuter the self-test (recommended)

`BORINGSSL_integrity_test` is an exported dynsym. After patching a
library's text, if that symbol is present, overwrite its first
instruction with `ret` (`0xd65f03c0`). Four bytes, targeted, and it
changes no crypto behaviour — the check attests that the module was not
modified on disk, which is not the property we are violating.

Cheapest to implement: hybris already has the segment made writable at
that point in `linker_phdr.cpp`, and the symbol lookup is a normal
`dlsym`-style query on the just-loaded soinfo.

Risk: BoringSSL versions vary in whether the abort path lives only in
`BORINGSSL_integrity_test`. Older builds call it from
`BORINGSSL_bcm_power_on_self_test`, which is not exported here — if a
device ships such a build, the `ret` patch silently misses and the
abort stays. Detectable by the symbol simply not existing.

### 2. Re-inject the hash

After patching, recompute the HMAC over the now-patched text+rodata and
rewrite the embedded constant, so the check runs and passes honestly.

The blocker is locating the constant: `BORINGSSL_bcm_text_hash` is not
exported (it is a hidden const in `.rodata`, necessarily *outside* the
hashed rodata range since the hash cannot cover itself). Would need a
scan of `.rodata` for the 32 pristine bytes — computable, because the
pre-patch bytes are still on disk. Also needs an `mprotect` of a
read-only page, and an HMAC implementation inside hybris (or a
pre-patch snapshot plus one from the host libcrypto).

More faithful than option 1, meaningfully more code, and still a
BoringSSL-specific special case.

### 3. Stop patching text at all

The real fix for this whole class of problem: make `TPIDR_EL0`
bionic-compatible under glibc so `mrs` needs no rewriting, and retire
the patcher. Removes not just the FIPS breakage but every future
self-checksumming or W^X-sensitive Android library.

Much bigger: glibc owns `TPIDR_EL0` and its TLS block layout, so this
means either a shadow-TLS scheme the hooked libc keeps in sync or
carrying bionic's static-TLS layout inside glibc's. See
`hybris/common/q/linker_tls.cpp` and the `tls_patcher` commits for what
the current approach already has to cover.

### 4. Do nothing, document it

Keep the abort and note it as an unsupported-device symptom. Cheap, but
the failure mode is opaque (a FIPS message from a library the user
never asked for) and fatal to the whole graphics stack on affected
devices, so at minimum it wants a recognisable hybris-side log line.

## Notes for whoever picks this up

- Any fix lands in the libhybris fork (`deps/libhybris`) and needs a
  `deps/deps.list` pin bump in the same change.
- Repro program: build in a rootfs with `gcc`, link
  `-L/usr/lib/hybris -lhybris-common`, call `hybris_dlopen` on
  `/system/lib64/libcrypto.so`. Add a `SIGABRT` handler that diffs the
  mapping against the file to re-derive the 170-word delta.
- No physical device we have reproduces it through a real GL client, so
  verification of a fix should use the direct `hybris_dlopen` repro
  plus a request to the reporter.
