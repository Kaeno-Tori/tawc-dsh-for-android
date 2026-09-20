//! Shared native base for the tawc app process.
//!
//! Holds the pieces that must be alive for as long as the app process is,
//! independently of any display backend — the split was made when a
//! compositor / unified-display backend shared this process, and the crate
//! outlived the compositor's removal unchanged:
//!
//! - [`ando`] — the per-distro broker that lets rootfs guests run plain
//!   Android commands (see notes/ando.md).
//! - [`logging`] — the process-wide `android_logger` + panic-hook setup
//!   that every JNI entry point depends on.
//!
//! **Deliberately free of any display dependency.** Nothing here refers
//! to Smithay, Wayland, EGL, or a compositor event loop, so the only
//! cdylib that links it now — `andobridge` — links none of that code.
//! (That independence is what the split was for; the compositor side of it
//! is gone, but the invariant is kept.) The JNI surface lives in
//! `andobridge/src/lib.rs`, which keeps this crate testable and free of a
//! JNI ABI to keep stable.
//!
//! ando's module doc has the protocol/security model; notes/ando.md has
//! the design rationale and the reasons it must stay a standalone
//! thread rather than becoming part of an event loop.

pub mod ando;
pub mod logging;
