//! Shared test helpers used by the per-group submodules under `tests/`.
//!
//! This module used to be dominated by compositor helpers — launching the
//! wayland-debug-app in its many scenes, waiting for toplevels/AHB
//! surfaces, asserting the compositor came back clean. All of that went
//! away with the display stack (TAWC_DSH_DESIGN.md §11), and so did
//! the only remaining reason for the launch-time "is the compositor up?"
//! precondition check.

use crate::adb;

/// Reset app-side mutable test state. Call this at the start of every
/// integration test so persisted device settings and previous test
/// state cannot leak in.
pub fn test_init() {
    adb::test_init().expect("test-init action");
}
