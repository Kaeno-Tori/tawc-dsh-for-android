package me.phie.tawc.dsh

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.phie.tawc.MainActivity
import me.phie.tawc.R
import me.phie.tawc.Settings
import me.phie.tawc.SettingsActivity
import me.phie.tawc.install.DistroInfoActivity
import me.phie.tawc.install.InstallationStore
import me.phie.tawc.tasks.TaskManagerActivity
import me.phie.tawc.terminal.TerminalActivity
import me.phie.tawc.ui.DockAction
import me.phie.tawc.ui.FloatingDock
import me.phie.tawc.ui.applyTawcSystemBarPadding

/**
 * The app's main surface: DSH's own browser UI, rendered in a WebView.
 *
 * The UI is not written here. DSH ships a complete web client (session
 * list, message stream, tool-call cards, approval prompts, model picker),
 * and WebView is a system component — unlike bundling a browser engine,
 * this costs the APK nothing. See `TAWC_DSH_DESIGN.md` §2.
 *
 * This Activity owns no state. The harness lives in [DshService] (so it
 * survives rotation and task switching), and DSH's own session log is the
 * source of truth, so a fresh `loadUrl` after a restart is a complete
 * recovery — there is no "reconnect" protocol to write.
 *
 * The host-side tools — a shell, the task list, the container's own
 * record — live in a floating entry ([FloatingDock]) instead of a
 * toolbar. None of them is part of using DSH, and the agent UI owns
 * this screen: a full-width band to hide three items is a bad trade,
 * worst exactly where the screen is smallest.
 */
class DshActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    /**
     * The window, built here rather than by [me.phie.tawc.ui.buildHomeScreen]:
     * this screen has no chrome to share, and the WebView is meant to own
     * everything below the system bars.
     */
    private lateinit var root: FrameLayout

    /** The host-side tools, floating over the page. */
    private lateinit var dock: FloatingDock

    /**
     * The overlay the user sees until the page takes over: DSH's own boot
     * splash, reproduced natively — see [DshBootOverlay] for why it is a
     * reproduction rather than a design of ours.
     *
     * Opaque, so nothing below it shows through: the WebView paints
     * underneath either way, and a scrim would reveal a half-built shell,
     * which is precisely the flash DSH's splash exists to cover.
     */
    private lateinit var status: DshBootOverlay
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Last URL handed to the WebView, so a state re-emit doesn't reload. */
    private var loadedUrl: String? = null

    /**
     * The pending `<input type="file">` request, while the system picker
     * is in front of us.
     *
     * **One slot, not a queue.** Chromium fires [WebChromeClient.
     * onShowFileChooser] once per click and refuses to fire it again
     * until the previous callback is answered — so a callback that is
     * dropped rather than answered wedges file picking for the rest of
     * the page's life. Every path below answers it, [onDestroy]
     * included. (The user-visible form of getting this wrong is "first
     * click did nothing, now the button never works again".)
     */
    private var fileChooser: ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        root.applyTawcSystemBarPadding()

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            settings.javaScriptEnabled = true
            // DSH's client is a single-page app; without DOM storage it
            // boots to a blank page rather than erroring visibly.
            settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptCookie(true)
            webChromeClient = object : WebChromeClient() {
                // DSH's composer owns a hidden `<input type="file">` and
                // opens the picker by clicking it (its own
                // `installDraftFilePicker`). A click on a file input in a
                // WebView is answered by *us*, not by Chromium: without
                // this override WebView's default is to return false and
                // drop the request, which is what "the upload button does
                // nothing" actually is. Nothing about the file crosses
                // into the container — the page POSTs the bytes to DSH's
                // own upload route, so this needs no path, no bind and no
                // all-files grant.
                override fun onShowFileChooser(
                    view: WebView?,
                    callback: ValueCallback<Array<Uri>>?,
                    params: FileChooserParams?,
                ): Boolean {
                    if (callback == null || params == null) return false
                    // Answer a stale request before arming this one, or
                    // the older callback stays unanswered forever.
                    fileChooser?.onReceiveValue(null)
                    fileChooser = callback
                    return try {
                        // [params.createIntent] carries the page's accept
                        // types and `multiple` through to the picker, and
                        // asks for a content URI the app can already read
                        // — the pick *is* the grant, so no
                        // MANAGE_EXTERNAL_STORAGE anywhere in this path.
                        startActivityForResult(params.createIntent(), REQ_PICK_FILE)
                        true
                    } catch (_: ActivityNotFoundException) {
                        // No documents provider to answer ACTION_GET_CONTENT
                        // (a stripped-down ROM, or a profile with no
                        // storage). Answer the callback — true, because we
                        // did handle it; the answer is "cancelled".
                        fileChooser = null
                        callback.onReceiveValue(null)
                        true
                    }
                }

                // DSH's client is a module-loading SPA: when something fails
                // it renders an empty document rather than an error page, so
                // the console is the only place the reason appears.
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    Log.i(TAG, "console: ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    // Only hide the overlay for the app itself; an
                    // intermediate redirect would otherwise uncover a
                    // still-blank page.
                    if (url != null && !url.contains("token=")) status.visibility = View.GONE
                    // A fresh document has no zoom of its own — the style
                    // this app set on the previous one died with it. Every
                    // navigation goes through here, including DSH's own
                    // reloads, which is why the scale is applied from
                    // [applyZoom] rather than inline at load time.
                    applyZoom()
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    Log.w(TAG, "http ${errorResponse?.statusCode} ${request?.url}")
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    Log.w(TAG, "load error ${error?.errorCode} ${error?.description} ${request?.url}")
                    // The overlay is opaque, so a page that never paints
                    // would leave a spinner turning over a blank screen.
                    // Sub-resource failures are not this case: the client's
                    // own `/api` calls fail routinely while the shell it is
                    // already showing keeps working.
                    if (request?.isForMainFrame == true) showPageFailure(error)
                }
            }
        }

        status = DshBootOverlay(this).apply {
            showLoading(getString(R.string.dsh_status_starting))
        }

        // Everything is a sibling in the root, in paint order: the page,
        // the status overlay over it, and the tools over both — on a
        // failure the tools are still the way out, so they stay on top.
        root.addView(webView)
        root.addView(status, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        dock = buildDock()
        root.addView(dock)

        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    // Disable so the platform handles it (finishes the task).
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        DshService.ensureRunning(this)
    }

    override fun onStart() {
        super.onStart()
        // The button re-reads its saved corner here rather than at
        // construction: a drag on the way out must not be lost because
        // this Activity was only stopped, not recreated.
        dock.applySavedPosition()
        scope.launch {
            DshService.state.collect { render(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        // The settings screen writes the page scale while this document sits
        // stopped behind it, so re-apply on the way back. This is what makes
        // the control feel live: the page is never reloaded for a scale
        // change, which would drop DSH back to its boot splash and lose the
        // scroll position.
        applyZoom()
    }

    /**
     * Push [Settings.dshZoomPercent] into the loaded document as a CSS `zoom`
     * on the root element.
     *
     * A `zoom` and not a WebView font size: the point of the control is to
     * give DSH's desktop-shaped layout more (or fewer) CSS pixels to work
     * with, so the panel widths and the composer box have to move with the
     * text. DSH's own font-size setting covers text alone.
     *
     * The value goes in as a percentage string rather than a float — it is an
     * Int, so there is no decimal separator to get wrong. A locale-formatted
     * float would emit `0,85` on a comma-decimal device, and `zoom` drops an
     * invalid value silently.
     */
    private fun applyZoom() {
        val pct = Settings.dshZoomPercent
        // Clearing the inline declaration is how 100% stays *identical* to a
        // build without this feature, rather than 100% meaning something the
        // page then has to interpret.
        val css = if (pct == 100) "''" else "'$pct%'"
        webView.evaluateJavascript("document.documentElement.style.zoom = $css;", null)
    }

    /**
     * Step the scale and show the result immediately — the floating menu's
     * pair of zoom entries, which exist so the value can be found while
     * looking at the page rather than by editing a setting, going back, and
     * looking. Clamping lives in [Settings.dshZoomPercent]'s setter, so the
     * ends of the range are the same here as in the settings screen.
     */
    private fun adjustZoom(delta: Int) {
        Settings.dshZoomPercent = Settings.dshZoomPercent + delta
        applyZoom()
    }

    private fun render(state: DshState) {
        when (state) {
            // Stopped: either the user stopped it from the notification,
            // or nothing has started it yet. Shown as a failure rather
            // than as "loading", because nothing is loading — the only
            // useful move is to start it again.
            DshState.Idle -> status.showFailed(
                getString(R.string.dsh_notification_stopped),
                listOf(recovery(DshRecovery.RESTART, prominent = true)),
            )

            DshState.Starting -> status.showLoading(getString(R.string.dsh_status_starting))

            is DshState.Ready -> {
                if (state.url != loadedUrl) {
                    loadedUrl = state.url
                    status.showLoading(getString(R.string.dsh_status_loading))
                    webView.loadUrl(state.url)
                }
            }

            // The list is rebuilt every time rather than diffed: a state
            // change that keeps the same buttons is rare, the views are
            // three at most, and a stale button wired to a stale closure
            // is exactly the bug class this screen exists to fix.
            is DshState.Failed -> status.showFailed(
                state.message,
                state.recoveries.mapIndexed { index, recovery ->
                    recovery(recovery, prominent = index == 0)
                },
            )
        }
    }

    /**
     * One button per [DshRecovery]. `prominent` marks the first (the
     * service orders them by priority) so a failure with three options
     * still has an obvious "do this" rather than three equal-weight
     * choices.
     */
    private fun recovery(recovery: DshRecovery, prominent: Boolean): BootAction = BootAction(
        label = getString(
            when (recovery) {
                DshRecovery.RESTART -> R.string.dsh_action_restart
                DshRecovery.INSTALL -> R.string.dsh_action_install
                DshRecovery.REINSTALL -> R.string.dsh_action_reinstall
                DshRecovery.TERMINAL -> R.string.dsh_action_terminal
            }
        ),
        onClick = {
            when (recovery) {
                // Idempotent, and a no-op only while a start is already in
                // flight — which is also when this button isn't on screen.
                DshRecovery.RESTART -> DshService.ensureRunning(this)
                DshRecovery.INSTALL -> openInstall()
                // Also the escape hatch for a broken container: that screen
                // is where a container gets deleted or rebuilt.
                DshRecovery.REINSTALL -> openContainer()
                DshRecovery.TERMINAL -> openShell()
            }
        },
        prominent = prominent,
    )

    /**
     * The WebView gave up on the page itself. The harness is still up, so
     * this is not a [DshState.Failed] and none of [DshRecovery] is the
     * right advice — reloading is the move that matches what broke.
     */
    private fun showPageFailure(error: WebResourceError?) {
        // `description` is normally there ("net::ERR_CONNECTION_REFUSED");
        // the code is the floor so the message can never end on a bare colon.
        val reason = error?.description?.takeIf { it.isNotBlank() }
            ?: error?.errorCode?.toString().orEmpty()
        status.showFailed(
            getString(R.string.dsh_failed_page, reason),
            listOf(
                BootAction(
                    label = getString(R.string.dsh_action_reload),
                    onClick = {
                        status.showLoading(getString(R.string.dsh_status_loading))
                        webView.reload()
                    },
                    prominent = true,
                ),
            ),
        )
    }

    /**
     * Hand off to the setup screen, dropping this one from the stack — it
     * has nothing left to show, and "back" landing on a failure the user
     * just left would be worse than no back at all.
     *
     * Cannot bounce: [DshRecovery.INSTALL] is only offered when no
     * `READY` record exists, and [me.phie.tawc.MainActivity] forwards
     * back here only for a `READY` one.
     */
    private fun openInstall() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    /**
     * The host-side tools, floating over the page. They are places to go
     * *away from* DSH — a shell for poking at the container, the task
     * list for seeing what it is running, the container's own record, and
     * the app's settings — which is also why they are not part of the page.
     *
     * Settings belongs here because this is the only screen a user with a
     * working container ever lands on: [me.phie.tawc.MainActivity] forwards
     * straight to DSH once an install is `READY`, so a MainActivity entry
     * would be unreachable (TAWC_DSH_DESIGN.md §10).
     */
    private fun buildDock(): FloatingDock = FloatingDock(
        context = this,
        actions = listOf(
            DockAction(R.drawable.ic_terminal, getString(R.string.action_terminal)) { openShell() },
            DockAction(R.drawable.ic_list, getString(R.string.title_task_manager)) {
                startActivity(Intent(this, TaskManagerActivity::class.java))
            },
            DockAction(R.drawable.ic_linux_logo, getString(R.string.title_container)) { openContainer() },
            DockAction(R.drawable.ic_remove, getString(R.string.dock_zoom_out)) {
                adjustZoom(-Settings.ZOOM_STEP_PERCENT)
            },
            DockAction(R.drawable.ic_add, getString(R.string.dock_zoom_in)) {
                adjustZoom(Settings.ZOOM_STEP_PERCENT)
            },
            DockAction(R.drawable.ic_settings_gear, getString(R.string.title_settings)) {
                startActivity(Intent(this, SettingsActivity::class.java))
            },
        ),
    )

    /**
     * The container the tools act on. Single-container by design, so
     * this is simply the only record; null only before setup, which this
     * screen is not reachable from — hence the quiet no-op rather than a
     * dead-end message.
     */
    private fun containerId(): String? = InstallationStore(this).list().firstOrNull()?.id

    private fun openShell() {
        val id = containerId() ?: return
        // Per-distro document URI — see the manifest comment on
        // TerminalActivity.
        startActivity(
            Intent(this, TerminalActivity::class.java)
                .putExtra(TerminalActivity.EXTRA_ID, id)
                .setData(Uri.parse("tawc://terminal/$id")),
        )
    }

    private fun openContainer() {
        val id = containerId() ?: return
        startActivity(
            Intent(this, DistroInfoActivity::class.java)
                .putExtra(DistroInfoActivity.EXTRA_ID, id),
        )
    }

    /**
     * The system picker's answer, handed back to the page as the file
     * input's result.
     *
     * [WebChromeClient.FileChooserParams.parseResult] rather than reading
     * `data.data` by hand: it covers the multi-select form (a `ClipData`
     * instead of a `data` URI) and preserves the URI grant the picker
     * attached, which is the only thing that lets the WebView read a
     * document it has no all-files access to.
     */
    @Deprecated("WebView file choosers are driven by startActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_FILE) return
        val callback = fileChooser ?: return
        fileChooser = null
        callback.onReceiveValue(
            if (resultCode == RESULT_OK) {
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            } else {
                null
            },
        )
    }

    override fun onDestroy() {
        scope.cancel()
        // A pick still in front of us when the Activity goes away: answer
        // it, or Chromium never fires onShowFileChooser again for this
        // WebView.
        fileChooser?.onReceiveValue(null)
        fileChooser = null
        // Must run for every WebView, and only after it is detached from
        // the view tree — otherwise the WebView keeps its Activity context
        // and leaks it.
        (webView.parent as? FrameLayout)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "tawc-dsh"

        /** Request code for the `<input type="file">` picker. */
        const val REQ_PICK_FILE = 0x4453
    }
}
