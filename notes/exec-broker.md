# Dev exec broker

A debug-build-only in-app service — compiled into debug APKs only, not
merely disabled in release — that lets the host run commands as the
app uid/domain — same SELinux context (`untrusted_app`) the production
launch path uses. Replaces all `run-as` and most `su` use in dev/test
workflows with a path that genuinely mirrors how things execute when a
user launches the app. Its production sibling for rootfs guests (not
the host) is the ando broker — see [ando.md](ando.md).

## Why this exists

Without the broker, host-side test/dev scripts had two ways to enter the
app's environment:

1. `adb shell run-as io.github.kaeno_tori.tawc_dsh <cmd>` — works on debug
   builds but transitions into the `runas_app` SELinux domain, which
   is **not** the domain the running app actually uses
   (`untrusted_app`). Subtle policy differences between the two have
   bitten us before (e.g. the missing `system_file:execmod` on
   `runas_app` we worked around for libhybris;
   see `notes/proot.md`). PDEATHSIG also doesn't fire across the
   `shell → runas_app` transition, so killing the host script orphans
   any guest processes — fixed by routing through this broker.

2. `adb shell su -c <cmd>` — works on rooted devices, runs as root
   (uid 0, domain `su`). Even further from production.

The broker fixes both: the app process itself fork-execs the requested
command, so it inherits the app's `untrusted_app:s0:cXXX,cYYY` context
exactly as a user-launched run does. PDEATHSIG works (same-domain
parent → child). No `runas_app` weirdness to paper over. No root needed.

## Architecture

```
host                                        device (app process)
+--------------+                            +-----------------------+
| tawc-exec    |       adb forward          | ExecBroker            |
| (Rust bin)   | ─── localabstract:foo ───→ | LocalServerSocket     |
| stdio relay  |       ↔ TCP loopback       | accept loop           |
+--------------+                            |   ↓                   |
       ↑                                    | per-connection thread |
       │                                    |   ↓ spawn             |
   user shell,                              | child (any cmd, runs  |
   test scripts                             | in untrusted_app)     |
                                            +-----------------------+
```

The host helper (`tawc-exec`) opens an `adb forward` to the device-side
`LocalServerSocket`, sends a small header describing argv/env/cwd,
multiplexes local stdio over the connection in framed binary form, and
exits with the child's exit code. The device-side accept loop spawns
the child — `ProcessBuilder` on the ARGV path,
`InstallationMethod.startInside` on the RUNINSIDE path — with stdio
piped, runs three relay threads, and closes the socket when the child
exits.

## Wire protocol

### Header (text, line-oriented)

UTF-8, LF-terminated lines, terminated by an empty line. Three
mutually exclusive header forms — pick one:

**ARGV form** — fork-exec a child process. Used for raw command exec
(file copies, `cat /proc/foo`, etc.), and by the tawcroot prod-env
device tests (`tests/integration/tests/tawcroot_prodenv.rs`), which
ARGV-exec production `libtawcroot.so` from the APK's
`nativeLibraryDir`: the child forks from the app process, so it
inherits uid, `untrusted_app`, and the real zygote seccomp filter —
bit-for-bit the production launch condition (see
notes/tawcroot/testing.md "Prod-env device layer").

```
TAWCEXEC 1
ARGV /system/bin/sh
ARGV -c
ARGV echo hello; cat
ENV PATH=/system/bin:/system/xbin
ENV HOME=/data/local/tmp
CWD /data/local/tmp
OP_TITLE Repair distro X

```

`OP_TITLE` is optional and may appear on ARGV or RUNINSIDE forms (not
on ACTION — those handlers manage their own log screen). When set,
the broker registers a [me.phie.tawc.ops.Operation] in
`OperationsRegistry`, opens `LogScreenActivity` so the panel attaches,
and tees every chunk of process stdout/stderr into the op's log flow
(line-buffered) for the in-app surface. The host TTY still sees the
raw byte stream — the mirror is purely additive.

**ACTION form** — invoke an in-process broker action. Used by
`tawc-exec --foreground-app --action install --arg id=arch ...`.
The broker looks the action name up in
`me.phie.tawc.dev.ActionRegistry`; the handler runs in-process and
streams its output back over the same stream-frame protocol. See
`me.phie.tawc.install.InstallActions` for the install/uninstall
handlers.

```
TAWCEXEC 1
ACTION install
ARG id=arch
ARG method=proot
ARG mirrorProxy=http://127.0.0.1:8080/proxy/

```

The install action also takes `--arg externalBinds=<json>` — a JSON
`ExternalBind` array (omitted = none; there is no default bind set).
See notes/external-binds.md. And `--arg ando=true|false` (default
false) sets the initial per-distro ando enablement — see notes/ando.md.

**RUNINSIDE form** — run a command inside an installed chroot. The
broker reads the install's recorded method from `metadata.json` and
calls `InstallationMethod.startInside`, the single Kotlin entry point
for "enter the chroot" (notes/rootfs-sessions.md); it starts no
session wrapper and waits for no Wayland socket. Used by
`rootfs-run.sh`, `run-integration-tests.sh`, and the integration
test crate. Omit `CMD` for interactive `bash -l`. `OP_TITLE` is
optional and behaves the same as on ARGV form. `GRAPHICS` is
optional and overrides the in-rootfs `GraphicsBackend` for this one
spawn (libhybris / turnip / cpu); without it `RootfsEnv.defaultBackend()`
is used, which derives the backend from the Vulkan pick in Settings
(TAWC_DSH_DESIGN.md §11.4). This header is the only remaining way to
pin a backend by hand; the host helper exposes it as `--graphics`
(`adb::rootfs_run_with`).

```
TAWCEXEC 1
RUNINSIDE arch
CMD pacman -Syu
GRAPHICS libhybris
OP_TITLE arch: pacman -Syu

```

- `TAWCEXEC 1` — magic + version. Must be the first line.
- `ARGV <s>` (ARGV-form only) — one per arg. At least one required.
  `argv[0]` is the program path; the broker passes it through to
  ProcessBuilder unchanged after decoding. Strings are UTF-8.
- `ENV <KEY=VALUE>` (ARGV-form only) — zero or more. **Replaces** the
  inherited environment entirely (no merge). Omit for an empty env.
  `KEY` is split off the first `=`; `VALUE` is decoded.
- `CWD <path>` (ARGV-form only) — optional. Default: the app process's
  cwd.
- `ACTION <name>` (ACTION-form only) — exactly one. Must be a name
  registered at app startup; unknown names get a STREAM_ERR + exit
  −1.
- `ARG <key>=<value>` (ACTION-form only) — zero or more. Per-action
  semantics; see the handler's docstring.
- `RUNINSIDE <install-id>` (RUNINSIDE-form only) — exactly one.
  Resolves to `<distros>/<id>/`; unknown id gets STREAM_ERR + exit −1.
- `CMD <command>` (RUNINSIDE-form only) — optional. The command runs
  via `bash -lc <command>` inside the rootfs. Omit for interactive
  `bash -l`.
- `GRAPHICS <key>` (RUNINSIDE-form only) — optional. One of
  `libhybris` / `turnip` / `none`; unknown keys are rejected. `none` is
  the "no driver provisioned" state rather than a driver — see
  [gpu-strategy.md](gpu-strategy.md). When
  set, the broker passes this through to `InstallationMethod.startInside`
  and `RootfsEnv` uses it instead of `RootfsEnv.defaultBackend()` for
  this spawn only. The host helper exposes it as `--graphics`
  (`adb::rootfs_run_with`).
- `OP_TITLE <title>` (ARGV-form / RUNINSIDE-form only) — optional. When
  set, mirrors process stdio into a `LogScreenActivity` panel titled
  with `<title>`. See [BrokerOpMirror].
- ARGV / ACTION / RUNINSIDE are mutually exclusive in one header;
  combining is a protocol error.
- The empty line terminates the header. Frame stream begins
  immediately after.

#### Value encoding

Every value-bearing field — `ARGV` arg, `ENV` value half (after the
`=`), `CWD`, `ARG` value, `CMD`, `OP_TITLE` — is encoded so a `\n` in
user-supplied data doesn't end the header line early. The encoding is
small and reversible:

```
\\  ⇒ \\\\
\n  ⇒ \\n
\r  ⇒ \\r
```

It's a no-op for any value that contains none of those three chars
(the common case for ASCII shell commands), so normal text passes
through unchanged. See `tests/integration/src/exec_broker.rs::encode_value`
(host) and `ExecBrokerSession.kt::decodeValue` (device).

Programmatic identifiers — `ACTION` name, `RUNINSIDE` install id, and
the `KEY` half of `ENV K=V` / `ARG k=v` — are not encoded; they're
matched against registered handlers / regex-validated slugs / shell
identifier rules and never carry control chars.

### Frames (binary, multiplexed)

Each frame: `[u8 stream] [u32 length BE] [length bytes...]`.

Streams used **client → server**:

| ID | Meaning              | Length     |
|----|----------------------|------------|
| 0  | stdin data           | 1..=65536  |
| 4  | stdin EOF            | 0          |

Streams used **server → client**:

| ID | Meaning              | Length     |
|----|----------------------|------------|
| 1  | stdout data          | 1..=65536  |
| 2  | stderr data          | 1..=65536  |
| 3  | exit                 | 4 (i32 BE) |
| 5  | broker error string  | UTF-8      |

Exit payload is a signed 32-bit integer big-endian: `>=0` for normal
exit, `<0` for `-signal_number` if killed by a signal. (Java's
`Process.exitValue()` doesn't distinguish; we always report normal
exit unless we can confirm a signal — current implementation: always
the value `Process.exitValue()` returned, signed.)

After sending the exit frame the server closes the socket.

If the broker hits an internal error (e.g. ProcessBuilder failed to
launch), it sends a `5` frame with the error message, then a `3` frame
with `-1`, then closes.

### Cancellation

If the client closes the socket without sending a stdin EOF + exit
handshake, the server interprets it as cancellation and
`destroyForcibly()`s the child (SIGKILL on Android).

This is the property that fixes orphan-on-parent-death: `tawc-exec`
running on the host gets SIGPIPE'd / Ctrl-C'd / killed → the TCP
forward closes → adbd closes the device-side abstract socket → the
broker thread's read returns EOF → SIGKILL goes to the child. Same
domain, no PDEATHSIG quirks.

## Security model

The broker exists in dev builds and exposes execution-as-the-app to
anything that can connect to its `LocalServerSocket`. We have to make
sure "anything" is just the dev workflow, not random apps on the device.

Two layers of defense:

### 1. Source-set gate

**Release APKs don't contain the broker at all.** `ExecBroker`,
`ExecBrokerSession`, `ActionRegistry` and every `BrokerAction` live in
`app/src/debug/java`, which only the debug variant compiles. The
release variant sees the same call site — `DevHooks.start(this)` in
`TawcApplication.onCreate` — but resolves it to the empty
`src/release/java` twin of `me.phie.tawc.DevHooks`.

This is stronger than the old `if (BuildConfig.DEBUG)` guard: there is
no socket-binding code in the artifact to be reached by a bug, a
reflection call, or a future refactor. `scripts/check-no-dev-code.sh`
asserts it against the compiled release classes (Gradle
`:app:checkNoDevCode`, wired into `check`) and against the finished APK's
dex (run from `scripts/build-release-apk.sh`). Verify by hand with:

```
./gradlew :app:assembleRelease
apkanalyzer dex packages app/build/outputs/apk/release/app-release-unsigned.apk |
  grep 'me\.phie\.tawc\.dev'   # prints nothing
```

Tradeoff: a *release*-type build can never drive the broker — e.g. to
profile a release APK on device with the test actions. That would need
a third build type (or a product flavor) that compiles the debug source
set; nothing here supports it today.

### 2. `SO_PEERCRED` on every connection

Even on debug builds, the abstract socket name is in the kernel's
namespace and any process *could* try to connect. The accept loop
rejects every connection whose peer uid isn't in `{0 (root), 2000
(shell)}`:

```kotlin
val cred = client.peerCredentials
if (cred.uid !in allowedUids) { client.close(); continue }
```

`SO_PEERCRED` is populated by the kernel from the connecting task's
real credentials at connect time. **An app cannot spoof its uid** —
no setuid trick, no namespace trick. The kernel just reports
`current->cred->uid` from inside the connect path.

Why these uids:

- `0` (root) — covers `adb shell su -c` and rooted-emulator adbd.
- `2000` (shell) — covers `adb shell` and standard adbd. This is the
  uid `adb forward` connections come in as on user/userdebug builds.

Other apps (`untrusted_app`, uid 10xxx) get rejected. So do isolated
processes, system apps, anything else.

The auth boundary is therefore "anyone with adb access to the device,"
which is exactly the same boundary every other adb-driven dev workflow
already has. The broker doesn't expand the attack surface — it just
gives that pre-existing trust boundary a clean way to drive the app
without the `run-as`/`su` detours.

### What if SELinux blocks the connection?

Android's SELinux policy gates which domains can `connectto` an
abstract socket bound by `untrusted_app`. On userdebug/eng builds and
debug-built apps, `adbd` is generally allowed to connect to abstract
sockets exposed by debuggable apps (the same mechanism LLDB and
profiling tools use). On user builds + release apps the connection
typically wouldn't go through, but the broker isn't compiled into
release builds anyway.

## Lifecycle

The broker is a plain background thread spawned from
`TawcApplication.onCreate` → `DevHooks.start` (debug source set only,
see the security model above) — *not* a Service. We
went through a foreground-service version first, but
`startForegroundService` from `Application.onCreate` is unreliable on
Android 12+ when the cold-start was driven by something other than a
foreground activity (e.g. `am start ...InstallActivity` directly), and
the broker has no UI / notification needs of its own. A daemon thread
plus the kernel keeping the process alive while it has user threads is
enough.

`DevHooks.start` also calls
`me.phie.tawc.install.InstallActions.registerAll()` (install /
uninstall), `me.phie.tawc.dev.InputActions.registerAll()` (test
scaffolding: `app-info` / `cleanup-rootfs` / `test-init`), and
`me.phie.tawc.dev.SettingsActions.registerAll()` (the per-distro ando
get/set actions) to populate `ActionRegistry` before any client
connection arrives. Those three, plus `DevActivityTracker`, are
debug-source-set classes.

A handful of *hooks* the actions call do stay in `src/main`, because
they read or mutate private state of production classes:
`Settings.enterTestMode()` and
`InstallationStore.setAndoOverride` / `clearAndoOverride` /
`clearAndoOverrides`. They ship in the release DEX with no caller. Each
is documented as test-only at its definition.

## Registered actions

| Action | Source | Purpose |
|--------|--------|---------|
| `install` | InstallActions | Run the install state machine; args `id`, `method`, `distro`, `label`, `mirrorProxy`, `externalBinds`, `ando`, `bootstrap`; mirrors the [Operation] log + progress to host stdout/stderr; cancels on disconnect. Use `--foreground-app`. |
| `uninstall` | InstallActions | Same shape, opposite direction. Use `--foreground-app`. |
| `app-info` | InputActions | Prints `nativeLibraryDir=<path>` — where the APK's jniLibs landed on this device. The tawcroot prod-env tests exec `libtawcroot.so` from there (the one app-readable location `untrusted_app` may execve). kv lines, extensible. |
| `cleanup-rootfs` (`installId`) | InputActions | SIGKILL every process rooted in that install's rootfs (`ProcessScanner.killAllInRootfs`, including chroot processes when the install method is chroot). Prints `rootfs_killed=N`. |
| `test-init` (optional `installId`) | InputActions | Per-test reset: enter in-memory factory-default settings (`Settings.enterTestMode()`), finish any lingering `LogScreenActivity` left by broker install/uninstall/run actions (restoring whatever was beneath, normally MainActivity), drop per-distro ando overrides and reconcile the brokers (`InstallationStore.clearAndoOverrides` + `AndoBrokers.refresh`), and — when `installId` is given — run the same cleanup as `cleanup-rootfs`. Prints `rootfs_killed=N`. Does not write `SharedPreferences`; app process death discards it. |
| `set-ando` (`installId`, `enabled` or `value`) | SettingsActions | Set the per-distro ando (notes/ando.md) test override for `installId` and reconcile the broker (`AndoBrokers.refresh`): enable brings the listener up; disable tears it down and SIGKILLs in-flight ando children. In-memory only (never a metadata write); discarded on process death and cleared by `test-init`. Prints `true`/`false`. |
| `get-ando` (`installId`) | SettingsActions | Print the effective ando state for `installId` (override if set, else metadata). |

That is the whole action set. The compositor-era drivers — the `ic-*`
`TawcInputConnection` actions, `hardware-key`, `back`,
`inject-touch` / `inject-pointer`, `query-state`, the `focused-*`
probes, the `clipboard-*` helpers, and the output-scale / Xwayland /
GTK3 settings setters — were deleted with the display stack
(`TAWC_DSH_DESIGN.md` §11.5). There is no compositor to drive, no
client window to focus and no IME bridge to talk to, so nothing
replaces them.

Cold-starting any of the app's entry points (MainActivity,
InstallActivity, DshActivity) brings up the broker. Stopping the
app (`am force-stop io.github.kaeno_tori.tawc_dsh`) tears down the broker
thread and any in-flight children. Children run in `untrusted_app` (same
domain as the app) and PDEATHSIG-respect their parent on exit, so
force-stop kills everything cleanly.

### Cancellation: descendant kill

When the host disconnects mid-command, `destroyForcibly()` SIGKILLs
the immediate child but doesn't propagate to its descendants — bash
running `sleep 600` would leave the sleep behind as an init orphan.
The broker handles this by:

1. Walking `/proc/<pid>/status` to build a pid → ppid map.
2. BFS-ing from the immediate child to enumerate every descendant.
3. SIGKILL-ing each captured pid (via `Os.kill`) before
   `destroyForcibly` runs the immediate child.

We can't use `/proc/<pid>/task/<pid>/children`: it requires kernel
`CONFIG_PROC_CHILDREN`, which Android stock kernels (including the
emulator's) don't enable.

The BFS catches descendants whose `ppid` still chains back to the
immediate child. It does **not** catch processes that have been
deliberately reparented to init (`ppid == 1`) — most commonly the
gpgme/libgpg-error `posix_spawn` "double-fork-prevent-zombies" dance
that pacman's signature verify uses on every package. Those are
caught instead by the **whole-UID kill** that fires when the app
itself dies (e.g. `am force-stop io.github.kaeno_tori.tawc_dsh`): Android SIGKILLs
every process running under the app's uid regardless of parent
chain. Any cleanup that depends on PPid chains alone is incomplete;
the UID-wide kill is the actual safety net for orphaned descendants.

## Host helper

`tawc-exec` is a small Rust binary target in `tests/integration/`. Host
scripts call `scripts/tawc-exec.sh`, which rebuilds the helper when
sources are newer, caches it at `build/tawc-exec/tawc-exec`, and then
execs the cached binary. Use `scripts/tawc-exec.sh --clean` to force a
rebuild.

Usage:

```
tawc-exec [--foreground-app] [--cwd DIR] [--env K=V ...] [--op-title TITLE] -- ARGV0 ARGV1 ...
tawc-exec [--foreground-app] --action NAME [--arg K=V ...]
tawc-exec [--foreground-app] --in-rootfs ID [--graphics KEY] [--op-title TITLE] [-- CMD ...]
```

`--foreground-app` starts `MainActivity` even when the app process is
already running. Install/uninstall actions need it because they start
`InstallationService` as a foreground service. `RUNINSIDE` does this
implicitly in CLI mode (the CLI may hit a cold app); suite mode honors
only the explicit flag (see "Connect modes" below).

`--op-title TITLE` opts into the in-app log-screen mirror — the broker
posts an Operation, opens `LogScreenActivity`, and streams stdout /
stderr lines into it as they come in. `rootfs-run.sh` sets a
sensible default title for non-interactive command invocations
(scriptable callers can override via `TAWC_OP_TITLE=<title>` or set
`TAWC_OP_TITLE=` to suppress); integration tests don't set it (they'd
flicker the screen open hundreds of times per run).

It:

1. Picks a free TCP port.
2. Runs `adb forward tcp:<port> localabstract:io.github.kaeno_tori.tawc_dsh.exec`.
3. Connects to `127.0.0.1:<port>`.
4. Sends the header.
5. Multiplexes local stdin (frame 0) ↔ socket; demultiplexes socket
   frames into local stdout/stderr.
6. On socket close, reads the exit code from the last frame received
   and exits with it. (If no exit frame: exit 255.)
7. Tears down the `adb forward` on exit.

The TCP port is bound to `127.0.0.1` only and lives just long enough
for the one connection.

### Stdio gotchas

- Wrapper scripts in front of the helper must not run stdin-pumping
  commands. `adb shell <cmd>` forwards the *host* script's stdin to the
  remote command and drains whatever is piped/redirected in — the
  install-id probe in `scripts/lib/tawc-install-id.sh` did exactly this
  and made `rootfs-run.sh 'cat > f' < file` arrive empty in-rootfs.
  Guard such calls with `</dev/null` (host `adb devices`/`adb forward`
  don't read stdin; Rust `Command::output()` nulls stdin already).
- Signal deaths propagate faithfully end to end: a SIGSEGV'd guest
  comes back as `128+signum` (139), including crashes deep in
  libhybris/driver code under tawcroot (verified on the OnePlus 9
  against a hybris `eglCreateWindowSurface` fault). If a "crash"
  reports exit 0, suspect the command line, e.g. host-side `$?`
  expansion in double quotes or a trailing pipeline element.
- A crashing guest discards its buffered stdout (glibc block-buffers
  on pipes; SIGSEGV never flushes). Nothing broker-side can recover
  that — run crashy programs under `stdbuf -o0 -e0` when debugging.

### Connect modes: suite port vs CLI

The host side has two connect modes, decided by `TAWC_EXEC_BROKER_PORT`:

- **Suite mode** (env var set): `scripts/run-integration-tests.sh` starts
  the app once, opens a single `adb forward` for the whole run, and
  exports the port. Every broker request just connects to it — no
  per-request `adb forward`, no `pidof` app-running probe, no implicit
  `am start` foregrounding on RUNINSIDE. An explicit `foreground_app`
  request (install/uninstall helpers needing the foreground-app BAL
  allowance) still runs `am start`. A refused connection or header-write
  failure is a loud error ("re-run scripts/run-integration-tests.sh"),
  not a recovery path: no test force-stops the app, so a dead app
  mid-suite is a bug. (adb accepts the host TCP connection before
  dialing the device socket, so a dead app usually surfaces on the
  header write rather than at connect.)
- **CLI mode** (env var unset; `scripts/tawc-exec.sh` from a shell):
  must work against a cold app, so each request probes
  `pidof io.github.kaeno_tori.tawc_dsh`, starts `MainActivity` if needed (and
  always for RUNINSIDE), and opens its own short-lived forward.

Host-transport rule for integration tests: per-request host process
spawns are banned; everything app-facing goes through the broker
(`rootfs_run` / `rootfs_run_with` dispatch through the RUNINSIDE form,
and `test-init` / `app-info` / `set-ando` / `get-ando` are broker
actions). Allowed `Command::new("adb")` exceptions: the CLI/fallback
paths inside `exec_broker.rs`, suite setup/teardown in shell scripts,
the wrapped tawcroot suite (`tawcroot/test.sh --device`), and
`adb::shell` for genuinely shell- or su-side work that cannot run as
the app uid (`ando` process counting, `uninstall_wipe` su sweeps).

Deliberately rejected while killing the old per-request spawns: a
multiplexed long-lived broker protocol (per-request local TCP connects
are cheap).

## What's not yet done

- **No PTY support.** Stdio is plain pipes. `bash`'s readline detects
  `!isatty(0)` and falls back to the simpler line-buffered REPL, which
  works fine for entering commands but doesn't give arrow keys / line
  editing / job control. Add a PTY mode if/when interactive shell UX
  becomes a complaint. The protocol has room for a `USE_PTY` header
  line.
- **No window-size propagation.** Same — protocol can carry a
  `WINSIZE rows cols` header line later.
- **No multi-command pipelining over one connection.** Each connection
  runs exactly one command. This keeps the protocol simple and matches
  every current use case.

## What still uses `su` / `run-as`

After the broker rollout, the only remaining privileged paths in dev
workflows are:

- **`chroot` install method** (`install/ChrootMethod.kt`; the sole
  `Su.run` consumer in the install package, reached from
  `scripts/rootfs-run.sh` through the broker's RUNINSIDE form).
  `chroot(2)` requires `CAP_SYS_CHROOT` — fundamental, not a workaround.
  tawcroot and proot installs all go through the broker.
- **`scripts/emulator.sh` setup**: `setenforce 0`, Magisk policy.
  One-time emulator bootstrap, irrelevant once the AVD exists.

`tawcroot/test.sh --device` runs as adb shell. The FIFO/mknod handler
checks are host-only because Android SELinux denies mknod on
`shell_data_file`; the rest of the device cleat suite stays rootless.

Everything else — fixture installs, integration tests, log probes,
chroot file inspection, the readiness check, the install-id probe,
host-driven shell sessions inside the chroot — runs as the app uid
through the broker.

## See also

- `app/src/debug/java/me/phie/tawc/dev/ExecBroker.kt` — the listener.
- `app/src/debug/java/me/phie/tawc/dev/ExecBrokerSession.kt` — per-
  connection logic (header parsing, frame routing, descendant kill).
- `tests/integration/src/exec_broker.rs` — host helper library.
- `tests/integration/src/bin/tawc-exec.rs` — CLI wrapper for scripts.
- `scripts/tawc-exec.sh` — host-side wrapper that auto-builds the helper.
