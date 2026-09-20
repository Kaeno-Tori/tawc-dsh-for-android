# Module Layout

DSH on Android runs an agent harness inside a Linux rootfs, with a
terminal-first UI. There is no display stack: no Wayland compositor, no
renderer, no input/IME bridges, no Xwayland, no desktop launcher. See
TAWC_DSH_DESIGN.md §11.5 for what was removed and why.

## Kotlin (`app/src/main/java/me/phie/tawc/`)

**Agent surface**

- **MainActivity.kt** -- The app's entry point (the only Activity in
  `category.LAUNCHER`) and a single-container state machine over
  `store.list().firstOrNull()`: setup (one-tap default install, the
  custom form, or import-a-pack), live install progress inline,
  failure with start-over, a completion receipt, then straight into
  `dsh/DshActivity` once the container is READY.
- **dsh/DshActivity.kt** / **dsh/DshService.kt** -- The agent dock.
  `DshService` is a foreground service (`specialUse`, subtype
  `ai_agent`) that owns the in-rootfs harness process and the four
  dock screens (terminal / tasks / containers / settings). It calls
  `InstallationMethod.startInside` directly and never involves a
  compositor or a session wrapper.
- **terminal/** -- Per-distro terminal, built on the vendored termux
  terminal-emulator/terminal-view modules. `TerminalActivity` +
  `TerminalSessions` + `TerminalTabBar`. Spawns through
  `TawcrootMethod.ptyShellExec` so the shell gets a real controlling
  tty.
- **tasks/** -- Task manager: `ProcessScanner` (with `AppUidProcfsScanner`
  and `SuProcfsScanner` backends) + `ProcessInfo` + `TaskManagerActivity`.
- **ui/FloatingDock.kt** -- The dock widget shared by the DSH screens.
- **ui/Scaffold.kt** -- Toolbar/content-column helpers and the
  `primaryButton` / `destructiveButton` factories.

**Install pipeline** (`install/`)

- **Actors** -- `InstallActivity` (form), `InstallationService`
  (foreground driver), `Installer` (step pipeline), `InstallationStore`
  (per-install metadata on disk), `Installation`, `InterruptedInstalls`,
  `DistroInfoActivity`, `ManageBindsActivity`, `DirectoryPickerActivity`.
- **Methods** -- [InstallationMethod] with `TawcrootMethod` (the
  default and only officially supported one), `ProotMethod` and
  `ChrootMethod` (debug-only). `MethodRunHelper` implements the shared
  `runInside` wrapper. `ChrootMounter` builds the chroot's mount
  script; `RootShell` / `Su` / `Sh` wrap root command execution.
- **Env** -- `RootfsEnv` is the single source of the in-rootfs
  environment, applied via `/usr/bin/env -i` on every spawn.
- **Assets** -- `TawcAssets` extracts the shipped GPU assets
  (libhybris, Turnip) and owns the stamp gating; the per-asset
  `*InstallProvider`s bind them into the rootfs.
- **Distros** (`install/distro/`) -- `Distro` + `DistroRegistry`
  declare the shipped flavours; `apt/`, `arch/` and `debian/` hold the
  per-family bootstrap logic, `pkgbootstrap/` the .deb-based path.
- **Integrity** -- `SignatureVerifier`, `BootstrapCache`,
  `BootstrapMirror`, `MirrorProbe`, `MirrorProxy`, `Downloader`,
  `Archive`, `LinkerConfig`.

**Shared services**

- **TawcApplication.kt** -- startup chores on a background thread
  (bootstrap-cache sweep, rootfs `/tmp` sweep, ando reconcile, dev
  exec broker in debug builds).
- **AppPaths.kt** -- app-owned filesystem layout derived from Android
  runtime dirs.
- **AppVisibility.kt**, **Settings.kt** / **SettingsActivity.kt**.
- **ops/** -- `Operation` + `OperationsRegistry` +
  `OperationsNotificationCenter` + `LogScreenActivity`: the shared
  long-running-operation and log-screen abstraction (notes/log-screen.md).
- **ando/** -- `NativeAndoBridge` (JNI to `libandobridge.so`) driven by
  **AndoBrokers.kt**; lets the guest run Android commands
  (notes/ando.md).
- **licenses/** -- the third-party licence browser.

**Debug-only** (`app/src/debug/`) -- the dev exec broker
(`dev/ExecBroker.kt`, `dev/ExecBrokerSession.kt`), the action registry
(`dev/ActionRegistry.kt`, `dev/BrokerAction.kt`), and the action
implementations (`dev/InputActions.kt`, `dev/SettingsActions.kt`,
`install/InstallActions.kt`). Stripped from release by
`scripts/check-no-dev-code.sh`.

## Native crates

| Crate | Role |
|---|---|
| `tawcroot/` | The systrap-based rootless rootfs runtime (C; see notes/tawcroot/). |
| `ando-broker/` | Broker library behind `ando <cmd>`; `andobridge/` is its JNI shell. |

Kotlin reaches the rootfs exclusively through
`InstallationMethod.startInside` — see notes/rootfs-sessions.md for the
session invariant every entry point has to uphold.
