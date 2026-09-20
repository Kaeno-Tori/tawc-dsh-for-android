//! Host-side helpers for driving the device.
//!
//! Two things live here: thin wrappers over the dev exec broker (run a
//! command in a rootfs, run a broker action), and the handful of typed
//! action wrappers the surviving suites need.
//!
//! This module used to carry a large input/IME/clipboard surface too —
//! `ic_*`, `hardware_key_*`, `inject_touch` / `inject_pointer_*`,
//! `clipboard_*`, `query_state`, `back`, the output-scale / Xwayland /
//! GTK3 settings setters, plus screenshot pixel helpers. All of it drove
//! the compositor and its clients, which are gone
//! (TAWC_DSH_DESIGN.md §11.5).

use std::io;
use std::process::{Command, Output};

use crate::exec_broker::{self, Invocation, Request};
use crate::GraphicsBackend;

/// Run an adb shell command, wait for completion, return output.
pub fn shell(cmd: &str) -> io::Result<Output> {
    Command::new("adb").args(["shell", cmd]).output()
}

/// Execute a command on the device as the app uid (no `su`, no
/// `run-as`) via the dev exec broker. Same address-space + SELinux
/// domain (`untrusted_app`) as the running app.
///
/// `argv[0]` must be an absolute path or one resolvable on the
/// (empty-by-default) child PATH; pass `--cwd` / `--env` separately
/// if needed by using the broker directly. For these tests the helper
/// is mainly used to copy files into the rootfs.
pub fn rootfs_host_exec(argv: &[&str]) -> io::Result<Output> {
    exec_broker::run_capture(Invocation {
        foreground_app: false,
        request: Request::Exec {
            argv: argv.iter().map(|s| (*s).to_string()).collect(),
            env: Vec::new(),
            cwd: None,
            op_title: None,
        },
    })
}

/// Run a command inside the in-app Linux install on the phone.
///
/// Routed through the broker's RUNINSIDE handler, which dispatches
/// to the install's `InstallationMethod.startInside` (see
/// notes/exec-broker.md, notes/rootfs-sessions.md). Single entry point
/// for every install method — chroot handles its own `su` internally.
///
/// Uses whatever backend the app's Vulkan pick derives
/// (`RootfsEnv.defaultBackend`). Tests that want a specific backend
/// should call [rootfs_run_with] instead.
pub fn rootfs_run(cmd: &str) -> io::Result<Output> {
    rootfs_run_inner(None, cmd)
}

/// Same as [rootfs_run] but pins the in-rootfs [GraphicsBackend] for
/// this one spawn. Travels over the broker as a `GRAPHICS <key>` header
/// on the RUNINSIDE form (see notes/exec-broker.md), and is the only
/// remaining override — the persisted `Settings.graphicsBackend` this
/// used to leave alone is gone.
pub fn rootfs_run_with(backend: GraphicsBackend, cmd: &str) -> io::Result<Output> {
    rootfs_run_inner(Some(backend), cmd)
}

fn rootfs_run_inner(backend: Option<GraphicsBackend>, cmd: &str) -> io::Result<Output> {
    exec_broker::run_capture(Invocation {
        foreground_app: false,
        request: Request::RunInside {
            install_id: crate::install_id(),
            cmd: cmd.to_string(),
            op_title: None,
            graphics: backend.map(|b| b.as_key().to_string()),
        },
    })
}

/// Run a broker action. `args` are sent as `ARG key=value` header lines.
/// Returns the broker exit status + captured stdio,
/// same shape as [shell].
fn broker_action_raw(name: &str, args: &[(&str, &str)]) -> io::Result<Output> {
    exec_broker::run_capture(Invocation {
        foreground_app: false,
        request: Request::Action {
            name: name.to_string(),
            args: args
                .iter()
                .map(|(k, v)| ((*k).to_string(), (*v).to_string()))
                .collect(),
        },
    })
}

/// Run a broker action and fail the caller if the broker returned non-zero.
fn broker_action(name: &str, args: &[(&str, &str)]) -> io::Result<Output> {
    let output = broker_action_raw(name, args)?;
    if output.status.success() {
        Ok(output)
    } else {
        let stderr = String::from_utf8_lossy(&output.stderr);
        let stdout = String::from_utf8_lossy(&output.stdout);
        Err(io::Error::other(format!(
            "broker action {name} failed with {} stdout={:?} stderr={:?}",
            output.status, stdout, stderr
        )))
    }
}

// ---- Test-mode setup -----------------------------------------------------

/// Reset app-side test state before an integration test runs: enter
/// in-memory test settings (factory defaults, no SharedPreferences
/// writes), close any op-log screen a previous broker action left on
/// top of the task, drop per-distro ando overrides, and kill this
/// install's process tree.
pub fn test_init() -> io::Result<()> {
    let install_id = crate::install_id();
    broker_action("test-init", &[("installId", &install_id)])?;
    Ok(())
}

/// The app's `nativeLibraryDir` (where the APK's jniLibs land on this
/// device), via the broker `app-info` action. The tawcroot prod-env
/// tests exec `libtawcroot.so` from there — the one app-readable
/// location `untrusted_app` may execve.
pub fn native_lib_dir() -> io::Result<String> {
    let output = broker_action("app-info", &[])?;
    let stdout = String::from_utf8_lossy(&output.stdout);
    stdout
        .lines()
        .find_map(|line| line.strip_prefix("nativeLibraryDir="))
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .ok_or_else(|| io::Error::other(format!("app-info missing nativeLibraryDir: {stdout:?}")))
}

/// Set the per-distro ando enable state for the standing install via the
/// test-mode `set-ando` override (notes/ando.md). Reconciles the broker
/// synchronously: enable brings the listener up (the next rootfs spawn
/// gets the bind); disable tears it down and SIGKILLs in-flight ando
/// children. In-memory only — discarded on app-process death and cleared
/// by `test-init`.
pub fn set_ando(enabled: bool) -> io::Result<Output> {
    let id = crate::install_id();
    let enabled = if enabled { "true" } else { "false" };
    broker_action("set-ando", &[("installId", &id), ("enabled", enabled)])
}

/// Read the effective ando enable state for the standing install
/// (override if set, else metadata).
pub fn get_ando() -> io::Result<bool> {
    let id = crate::install_id();
    let output = broker_action("get-ando", &[("installId", &id)])?;
    let stdout = String::from_utf8_lossy(&output.stdout);
    match stdout.trim() {
        "true" => Ok(true),
        "false" => Ok(false),
        other => Err(io::Error::other(format!("parse ando setting: stdout={other:?}"))),
    }
}
