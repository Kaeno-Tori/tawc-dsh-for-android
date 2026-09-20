package me.phie.tawc.dev

import me.phie.tawc.AndoBrokers
import me.phie.tawc.install.InstallationStore

/**
 * Broker actions for per-distro settings that integration tests need to
 * flip without a UI. Registered from [me.phie.tawc.TawcApplication.onCreate]
 * (debug builds only).
 *
 * | Action | Args | Effect |
 * |--------|------|--------|
 * | `set-ando` | `installId`, `enabled` ∈ true|false | set the test-mode ando override for the install and reconcile the broker |
 * | `get-ando` | `installId` | prints true/false (override or metadata) |
 *
 * `set-ando`/`get-ando` are per-distro (notes/ando.md), so unlike the
 * `[Settings]`-backed actions that used to live here they don't touch
 * persisted settings: `set-ando` writes an in-memory override in
 * [InstallationStore] (mirroring `Settings.enterTestMode` — no durable
 * metadata write, discarded on process death) and reconciles the native
 * listeners via [AndoBrokers.refresh].
 *
 * The compositor-era actions are gone with the display stack
 * (TAWC_DSH_DESIGN.md §11): `set-output-scale`, `set-xwayland` and
 * `set-gtk3-broken-menus-workaround` all pushed state into a compositor
 * that no longer exists.
 */
internal object SettingsActions {

    fun registerAll() {
        ActionRegistry.register("set-ando", SetAndoAction)
        ActionRegistry.register("get-ando", GetAndoAction)
    }

    private object SetAndoAction : BrokerAction {
        override fun run(args: Map<String, String>, ctx: ActionContext): Int {
            val id = args["installId"]
                ?: return ctx.fail("set-ando: --arg installId=<id> required")
            val raw = args["enabled"] ?: args["value"]
                ?: return ctx.fail("set-ando: --arg enabled=true|false required")
            val enabled = raw.toBooleanStrictOrNull()
                ?: return ctx.fail("set-ando: invalid boolean '$raw'")
            InstallationStore.setAndoOverride(id, enabled)
            // Bring the per-distro listener up/down now (enable) or tear
            // it down + kill in-flight children (disable). Next rootfs
            // spawn picks up the matching bind.
            AndoBrokers.refresh(ctx.appContext)
            ctx.out(enabled.toString())
            return 0
        }
    }

    private object GetAndoAction : BrokerAction {
        override fun run(args: Map<String, String>, ctx: ActionContext): Int {
            val id = args["installId"]
                ?: return ctx.fail("get-ando: --arg installId=<id> required")
            ctx.out(InstallationStore(ctx.appContext).andoEnabled(id).toString())
            return 0
        }
    }

    private fun ActionContext.fail(msg: String): Int {
        err(msg)
        return 2
    }
}
