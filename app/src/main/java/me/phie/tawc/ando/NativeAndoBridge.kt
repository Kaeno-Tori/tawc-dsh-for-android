package me.phie.tawc.ando

/**
 * JNI bridge to `libandobridge.so` — the ando broker with no display
 * stack behind it (notes/ando.md "Components").
 *
 * Deliberately a separate object *and* a separate `.so`: the bridge
 * used to share a library with the compositor, and `System.loadLibrary`
 * loads a whole shared library, so going through it dragged in the
 * ~8.6 MB Smithay/Wayland stack even when all the caller wanted was to
 * start the ando listeners from [me.phie.tawc.AndoBrokers] at app
 * startup. The compositor is gone (TAWC_DSH_DESIGN.md §11), but the
 * split stays: it keeps the broker JNI shell independent of whatever
 * else gets linked into the app.
 *
 * The broker code itself is not duplicated — the library links the
 * `ando-broker` rlib.
 */
object NativeAndoBridge {
    init {
        System.loadLibrary("andobridge")
    }

    /** Reconcile the per-distro ando broker listeners to exactly the
     *  enabled set. `ids[i]` is an install id and `paths[i]` its
     *  host-side socket path (`<appData>/distros/<id>/ando/ando.sock`);
     *  the arrays are parallel. Idempotent. */
    external fun nativeSyncAndoBrokers(ids: Array<String>, paths: Array<String>)
}
