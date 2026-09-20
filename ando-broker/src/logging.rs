//! Process-wide logcat + panic-hook setup.
//!
//! Moved here from `compositor/src/lib.rs` when the ando broker became
//! its own crate: the setup is process-global (`android_logger::init_once`
//! and `set_hook` are once-per-process), so it belongs to the shared
//! base rather than to whichever cdylib happens to make the first native
//! call.

use std::sync::OnceLock;

/// android_logger + panic hook setup for the JNI entry point that can be
/// the first native call in the process (`nativeSyncAndoBrokers` from
/// `TawcApplication.onCreate`). Idempotent.
pub fn init_native_logging() {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Debug)
            .with_tag("tawc-native")
            // Dependencies are noisy below warn: the jni crate logs every
            // thread attach/detach (one per reverse-JNI call, i.e. per
            // tap), which drowns out ando's own `info!`/`warn!` lines.
            // Default everything to warn and keep only our own modules at
            // debug.
            //
            // The filter matches on the log *target*, which is the module
            // path: ando's lines are tagged `ando_broker::ando` now that it
            // lives in this crate, so that is the name that has to be
            // listed. The `compositor=debug` directive beside it is a
            // leftover from the deleted display stack — nothing logs under
            // that target any more, so it matches nothing. It is kept only
            // so the shared filter string is left unchanged. `init_once`
            // makes whichever caller gets here first the one that installs
            // this filter for the whole process.
            .with_filter(
                android_logger::FilterBuilder::new()
                    .parse("warn,compositor=debug,ando_broker=debug")
                    .build(),
            ),
    );
    // The default Rust panic handler writes to stderr, which Bionic
    // routes to /dev/null for app processes — so a panic in a native
    // thread vanishes silently and is misdiagnosed as a hang. Route
    // panics through `error!` (i.e. android_logger / logcat).
    //
    // Then: abort, with one exception. The JNI entry point and the
    // listener sync run on the caller's thread, and a panic there means
    // the broker's native state is unusable, so we'd rather show up as a
    // clean SIGABRT with a useful message than leave the JVM running on
    // a dead native worker. The per-connection broker threads
    // (`ando-*`) instead unwind into their own `catch_unwind` — a
    // malformed request must not take down the app.
    static PANIC_HOOK: OnceLock<()> = OnceLock::new();
    PANIC_HOOK.get_or_init(|| {
        std::panic::set_hook(Box::new(|info| {
            let location = info.location()
                .map(|l| format!("{}:{}", l.file(), l.line()))
                .unwrap_or_else(|| "<unknown>".into());
            let msg = info.payload()
                .downcast_ref::<&'static str>().copied()
                .or_else(|| info.payload().downcast_ref::<String>().map(|s| s.as_str()))
                .unwrap_or("<non-string panic payload>");
            let thread = std::thread::current();
            let name = thread.name().unwrap_or("<unnamed>");
            log::error!("panic in thread {} at {}: {}", name, location, msg);
            if !name.starts_with("ando-") {
                std::process::abort();
            }
        }));
    });
}
