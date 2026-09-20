# hybris TLS patcher vs BoringSSL FIPS check: workaround only

> **Fork note:** reported against the desktop/GL build. The display stack
> and its GL clients are gone in this fork, so the `gtk4-widget-factory`
> symptom below is no longer reachable here; the libhybris patcher it
> describes still ships (compute-only libhybris).

Upstream report: https://github.com/wmww/tawc/issues/11 — Debian sid,
`gtk4-widget-factory` aborts at startup with `FIPS integrity test
failed`. Any vendor graphics library that lists Android's `libcrypto.so`
as `DT_NEEDED` kills every hybris-backed GL client on that device.

Root cause and the workaround are documented in
`deps/libhybris/TAWC_FORK.md` ("neuter BoringSSL FIPS integrity test"):
the patcher rewrites the 170 `mrs xN, tpidr_el0` inside the hashed text,
so the linker now overwrites the exported `BORINGSSL_integrity_test`
with `mov w0, #1; ret`. Regression test: probe3 in
`tests/apps/libhybris-tls-repro/repro.c` (direct
`hybris_dlopen("/system/lib64/libcrypto.so")`).

## What is still open

- Reporter has not confirmed the fix, and we do not know which vendor
  library pulls libcrypto on their device. Ask for
  `HYBRIS_LD_DEBUG=1 gtk4-widget-factory 2>&1 | grep -B2 -A2 libcrypto`
  and the device model. If it is an optional helper library, skipping
  that one dependency would be cleaner than patching BoringSSL.
- BoringSSL builds that keep the check inside the unexported
  `BORINGSSL_bcm_power_on_self_test` still abort. The linker prints
  `HYBRIS: <path> has no BORINGSSL_integrity_test` in that case.
- The honest fix is re-injecting a recomputed HMAC-SHA256 over the
  patched text into the hidden `BORINGSSL_bcm_text_hash`; the real fix
  is retiring the text patcher (bionic-compatible `TPIDR_EL0` under
  glibc). Both are large.
