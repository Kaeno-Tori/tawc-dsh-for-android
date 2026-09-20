//! JNI bindings for the ando broker (`libandobridge.so`).
//!
//! Deliberately tiny: it translates the one Kotlin call
//! (`NativeAndoBridge.nativeSyncAndoBrokers`) into
//! [`ando_broker::ando::sync`] and does nothing else. All the interesting
//! logic — the listener set, the wire protocol, the peercred gate — lives
//! in the `ando-broker` rlib that this library wraps.
//!
//! It links *no* display code, which keeps the ando listener path
//! independent of whatever else gets linked into the app. That separation
//! was originally about staying clear of the in-app compositor; the
//! compositor is gone and the separation stays (see
//! `me.phie.tawc.ando.NativeAndoBridge`). Its whole DT_NEEDED list is the
//! platform libs plus the Rust runtime.

use jni::JNIEnv;
use jni::objects::{JClass, JObjectArray, JString};

use ando_broker::logging::init_native_logging;

/// Reconcile the per-distro ando broker listeners to exactly the enabled
/// set. `ids[i]` is an install id and `paths[i]` its host-side socket path
/// (`<appData>/distros/<id>/ando/ando.sock`); the two arrays are parallel.
/// Called from `AndoBrokers.refresh` at startup and on every ando
/// install/uninstall/toggle. Idempotent.
///
/// Protocol, security model, and the reason this is native rather than
/// Kotlin: notes/ando.md.
#[unsafe(no_mangle)]
pub extern "system" fn Java_me_phie_tawc_ando_NativeAndoBridge_nativeSyncAndoBrokers(
    mut env: JNIEnv,
    _class: JClass,
    ids: JObjectArray,
    paths: JObjectArray,
) {
    // This can be the first native call in the process
    // (`TawcApplication.onCreate`) when no compositor is running, so it
    // installs the logcat logger and the panic hook. Both are
    // once-per-process and share one log filter with the compositor, so
    // whichever library gets here first wins — which is exactly why the
    // setup lives in the shared crate rather than in either .so.
    init_native_logging();

    let ids = match jstring_array(&mut env, &ids) {
        Ok(v) => v,
        Err(e) => {
            log::error!("nativeSyncAndoBrokers: bad ids array: {}", e);
            return;
        }
    };
    let paths = match jstring_array(&mut env, &paths) {
        Ok(v) => v,
        Err(e) => {
            log::error!("nativeSyncAndoBrokers: bad paths array: {}", e);
            return;
        }
    };
    if ids.len() != paths.len() {
        log::error!("nativeSyncAndoBrokers: length mismatch {} != {}", ids.len(), paths.len());
        return;
    }
    ando_broker::ando::sync(ids, paths);
}

/// Read a Java `String[]` into a `Vec<String>`.
fn jstring_array(env: &mut JNIEnv, arr: &JObjectArray) -> Result<Vec<String>, jni::errors::Error> {
    let len = env.get_array_length(arr)?;
    let mut out = Vec::with_capacity(len as usize);
    for i in 0..len {
        let obj = env.get_object_array_element(arr, i)?;
        let s: String = env.get_string(&JString::from(obj))?.into();
        out.push(s);
    }
    Ok(out)
}
