# Testing Infrastructure

## Overview

Automated integration tests for the rootfs runtime, the install
pipeline, and the ando broker. Each `tests/<name>.rs` file is a
submodule of a single `tests/integration.rs` test binary, so `cargo
test` produces one combined libtest summary and selecting a subset is
just a libtest substring filter (`<module>::test_name`).

There is no display stack (TAWC_DSH_DESIGN.md §11.5), so nothing
here drives a window, a renderer, or an input/IME bridge: the suite
covers rootfs semantics, the bionic linker config, ando, and
uninstall.

```
tests/
  apps/
    libhybris-tls-repro/        sources for the repro in
                                issues/hybris-tls-patcher-breaks-boringssl-fips-check.md
  integration/                Rust tests on host
    src/                        harness modules; see "Key Modules"
    src/bin/tawc-exec.rs        host helper binary for the exec broker
    tests/integration.rs        single test binary, declares the submodules
    tests/<module>.rs           one file per group; see its docstring
scripts/
  run-integration-tests.sh    Build everything, deploy, run integration tests
```

Each test module's own docstring documents what it covers and what its
prerequisites are. As of writing the modules are:

| Module           | Scope |
|------------------|-------|
| `ando`           | ando broker ([ando.md](ando.md)): Android commands from the guest, env hygiene, exit-code and signal forwarding, cwd and `-D` translation, option parsing, the disabled path, and disable tearing down in-flight children. |
| `linker_config`  | The bionic linker config libhybris reads: `/linkerconfig` is no longer bind-mounted into the guest (its SELinux label granted `dir search` but not `dir getattr`, so an `ls` that statted every entry of `/` failed on it); the one file libhybris wants is copied to `/usr/lib/hybris-config/ld.config.txt` instead. |
| `tawcroot`       | Wraps the cleat-driven tawcroot device suite (`tawcroot/test.sh --device --no-build`) as a single case so the run script stays the one command that exercises everything. |
| `tawcroot_prodenv` | Production `libtawcroot.so` spawned through the exec broker's ARGV form, so guests run in the real production sandbox — app uid, `untrusted_app`, the zygote-installed seccomp filter. |
| `uninstall_wipe` | Wipe-engine (`RootfsCleaner`) edge cases against fabricated KB-scale slots: the uniform mount gate and the one-`su`-retry ladder. The gate/retry case is Magisk-rooted-target only. |

**Persistent-state policy.** Integration tests must not mutate state
that outlives the test run: no real distro installs (nothing through
the cache proxy — package deps are installed once, up-front, by the
run script), no `appops` / permission flips, no writes to the standing
install's metadata, no persisted-Settings changes (`test-init` resets
in-memory state only). Fabricated throwaway slots (mkdir +
metadata.json, KBs) that a test creates and removes itself are fine —
`uninstall_wipe` is the pattern. The old `external_binds` lifecycle
test violated all of this (multi-GB proxy install + persistent appop
flips that broke the app for later tests when it died mid-run) and was
deleted; accepted coverage gap, see
[external-binds.md](external-binds.md) "Testing".

A backend pin is still expressible per spawn: `adb::rootfs_run_with`
takes a `GraphicsBackend` (`libhybris` / `turnip` / `none`) and the
broker carries it through to `InstallationMethod.startInside` as a
`GRAPHICS <key>` header on the RUNINSIDE form (see
[exec-broker.md](exec-broker.md)). The surviving suites don't pin —
they run under whatever `adb::rootfs_run` derives from the app's Vulkan
pick — but the mechanism is there for a test that needs one backend.

## Running

`scripts/run-integration-tests.sh` is the recommended entry point. It
picks the device via `scripts/lib/select-device.sh` (resolves
`./.tawctarget` / `TAWC_TARGET`; no auto-fallback to a single
connected target — see [emulator.md](emulator.md)), builds and
deploys everything, then runs cargo.

The script takes one optional positional arg — a libtest substring
filter forwarded to `cargo test` — plus `--no-build` / `-n`.

```bash
scripts/run-integration-tests.sh                 # everything
scripts/run-integration-tests.sh <module>::      # one module's tests
scripts/run-integration-tests.sh <test_name>     # one test by name
scripts/run-integration-tests.sh --no-build ...  # skip rebuild/redeploy
```

Direct `cargo test` invocations work too — they just need
`source scripts/lib/select-device.sh` first so cargo inherits
`ANDROID_SERIAL`. The source step also enforces the standing target,
so a stale `cargo test` from a `.tawctarget=none` checkout fails fast
instead of attaching to the wrong target.

Prerequisites: a phone (or emulator) connected via adb and an in-app
distro installed (see [installation.md](installation.md)). The runner
builds the APK, skips reinstalling it when the installed APK hash
matches, installs the guest packages the suite needs, and builds the
tawcroot device tests + fixtures. The suite auto-targets the unique
install if there's only one, otherwise pin via `TAWC_INSTALL_ID=<id>`.

`uninstall_wipe::test_wipe_gate_and_su_retry` needs a Magisk-rooted
target; the runner marks it ignored elsewhere via the conditional
`tawc_skip_root_on_target` cfg it sets when `su -c 'id -u'` doesn't
return 0. That is the only conditional-attribute cfg left — the
compositor-era `tawc_skip_gfxstream_on_target` /
`tawc_skip_libhybris_on_target` cfgs are gone with the backends.

## Key Modules

- **`adb.rs`**: `shell`, `rootfs_host_exec` (run as the app uid/domain
  through the broker), `rootfs_run` / `rootfs_run_with` (RUNINSIDE
  form), `test_init`, `native_lib_dir`, and the per-distro ando test
  override (`set_ando` / `get_ando`). Every app-facing call goes
  through the shared broker client.
- **`exec_broker.rs`**: the host driver for the exec broker. Picks a
  free local TCP port, sets up `adb forward` to the device-side
  `LocalServerSocket`, sends the protocol header, multiplexes local
  stdio over the socket, and reports the child's exit code. Header
  forms are ARGV (fork-exec), ACTION (in-process broker action), and
  RUNINSIDE (into an install). Wire protocol:
  [exec-broker.md](exec-broker.md).
- **`helpers.rs`**: now only `test_init()` — the shared per-test reset
  every test calls first.
- **`tawcroot_prodenv.rs`**: stages the prod-env fixture rootfs into app
  cache and runs production `libtawcroot.so` through the broker's ARGV
  form (`env`, `run_guest`, `assert_guest_exit`, `app_sh`).
- **`lib.rs`**: `install_id()` resolution (honours `TAWC_INSTALL_ID`,
  else the unique `distros/*/metadata.json`), `TAWC_SCRATCH`, and the
  `GraphicsBackend` enum.

## Test reset

Per-test isolation goes through the broker `test-init` action:

```bash
scripts/tawc-exec.sh --action test-init
```

It enters in-memory factory settings (no `SharedPreferences` writes),
finishes any lingering op-log screen a prior broker action left on top
of the task, clears the per-distro ando overrides and reconciles the
ando broker, and — when given an `installId` — kills that install's
process tree via `ProcessScanner`. None of it survives app process
death.

The broker actions the suite can use are `test-init`, `cleanup-rootfs`,
`app-info`, `set-ando` / `get-ando`, and `install` / `uninstall`. The
compositor-era input and settings actions (`ic-*`, `hardware-key`,
`query-state`, `set-output-scale`, …) went with the display stack.

## Waits, not sleeps

Where a discrete signal exists, tests poll it instead of sleeping a
fixed grace. `ando::wait_for_proc` polls the Android process table
until a child appears (or until the broker has reaped it), so the
assertions don't race the broker's asynchronous cleanup.

Fixed sleeps that remain are deliberate: the ando signal/death cases
sleep ~1 s so the child is genuinely up before it is killed, and the
disable case sleeps 2 s to confirm the child survived session teardown
*before* the disable under test — otherwise a later "gone" would be a
false pass.

## Adding New Tests

Add to an existing `tests/<module>.rs` if it fits an existing group, or
create a new module: drop `tests/<new>.rs` next to the others and add a
`mod <new>;` line to `tests/integration.rs`. Tests pick up the module
prefix automatically and the run script's substring filter just works.

## Design Decisions

- **One test binary, submodules per group:** Each group lives in its
  own file but they all compile into the same `tests/integration.rs`
  binary, so libtest prints one combined `test result: ...` summary
  and the run script picks subsets via the normal substring filter
  (`<module>::`). Avoids the per-binary summary fragmentation cargo
  produces when each `tests/*.rs` is its own target. `Cargo.toml` has
  `autotests = false` plus an explicit `[[test]]` entry so the
  per-group files aren't auto-discovered as separate binaries.
- **Broker, not adb tricks:** The app process fork-execs host-driven
  work itself, so children inherit the app's uid and `untrusted_app`
  domain — the same sandbox production uses — and PDEATHSIG works. See
  [exec-broker.md](exec-broker.md).
- **App-side reset owns guest cleanup:** `test-init` resets in-memory
  settings and (with an `installId`) sweeps the install's process tree.
  It does not use host pidfiles, `ps`, PGID reads, or host-side `kill`.
- **`--test-threads=1`:** Tests share the phone and the standing install
  and can't run in parallel.
