# Mouse / Pointer Input Plan

tawc has no `wl_pointer` input path. A Bluetooth mouse can click and drag
today only because Android launders mouse buttons through `onTouchEvent`
and tawc turns those into `wl_touch`; the wheel, hover, right/middle
buttons and cursor shapes are all missing. Reported upstream as
wmww/tawc#10 ("Mousewheel scrolling is not working"), confirmed by a
second user.

This plan is for wiring real pointer input, end to end. It is not a
touch-to-pointer emulation plan — `notes/input.md` already reserves that
line ("if real pointer hardware is added, wire it as real pointer
input") and it should stay reserved. Touchscreen input keeps going to
`wl_touch`.

## Verified Current State

Checked against the tree at the time of writing:

- `CompositorActivity` installs only an `OnTouchListener`
  (`app/src/main/java/me/phie/tawc/compositor/CompositorActivity.kt:129`
  → `dispatchTouchToCompositor`, `:406`). Android delivers mouse
  *buttons* as `ACTION_DOWN`/`ACTION_MOVE`/`ACTION_UP` through the touch
  path, so clicks work by accident, as touches. Wheel (`ACTION_SCROLL`)
  and hover (`ACTION_HOVER_*`) go through `onGenericMotionEvent` /
  `onHoverEvent`, neither of which `TawcSurfaceView` (`:363`)
  overrides, so they are dropped before JNI. There is no `SOURCE_MOUSE`
  check anywhere in the app.
- `nativeOnTouchEvent` (`compositor/src/lib.rs:382`) and
  `compositor/src/input.rs` carry `TouchEvent` only.
- The seat adds a keyboard and a touch
  (`compositor/src/compositor.rs:320`, `:322`) and *no* pointer, except
  the contained GTK3 menubar workaround
  (`compositor/src/gtk3_menus_workaround.rs:27`, `:52` — the only
  `add_pointer`/`remove_pointer` calls in the tree). That workaround is
  **on by default**, so in the default configuration the seat already
  advertises `wl_pointer`; clients bind it and get nothing but the
  priming enter/leave. Nothing anywhere calls `PointerHandle::axis`.
- `SeatHandler::cursor_image` is an empty stub
  (`compositor/src/compositor.rs:1137`); nothing renders or maps a
  cursor.
- `PopupPointerGrab` is already installed on `xdg_popup` grab
  (`compositor/src/compositor.rs:993`) — dead today, live as soon as a
  pointer exists.
- Smithay's `wl_seat` global is version 9 (`deps/smithay`
  `src/wayland/seat/mod.rs:175`), so `wl_pointer` v9 is available:
  `frame`, `axis_source`, `axis_value120` and
  `axis_relative_direction` are all usable. Given `AxisFrame::v120`,
  smithay sends `axis_value120` to v8+ clients and accumulates into
  `axis_discrete` for older ones by itself
  (`src/wayland/seat/pointer.rs:132`).
- Reverse JNI already exists: Rust calls `@JvmStatic` methods on
  `NativeBridge` via `call_static_method` (`lib.rs:700` region;
  `setActivityFullscreen` is the model for a per-Activity UI-thread
  hop). `minSdk` is 29, so `PointerIcon` (API 24) is available
  unconditionally.
- `wayland-debug-app` binds `wl_seat` at **version 5**
  (`tests/apps/wayland-debug-app/wayland-debug-app.c:2368`), already
  attaches a `wl_pointer` listener (`:2191`) that emits `POINTER_ENTER`
  and `POINTER_MOTION` (x only), and its `pointer_button` is fatal for
  any button other than `0x110`/`0x14a` (`:2143`). It treats removal
  of any seat capability as fatal (`:2320`).

## Repro (emulator, no mouse hardware needed)

Android's `input` command injects real mouse-source events, so the bug
reproduces on a stock AVD. Verified on the x86_64 emulator, Android 16,
Debian sid rootfs, gfxstream:

```sh
export ANDROID_SERIAL=emulator-5554
scripts/rootfs-run.sh 'lxterminal' &
# once the window is up, fill the scrollback:
adb shell input text 'seq%s1%s500'; adb shell input keyevent 66

adb shell screencap -p /data/local/tmp/tawc-dev/a.png
adb shell input mouse scroll 300 900 --axis VSCROLL,3   # wheel up
adb shell screencap -p /data/local/tmp/tawc-dev/b.png   # byte-identical
adb shell input swipe 300 700 300 1300 300              # touch: scrolls
```

Frame-diffing the terminal content area gave 0% changed pixels for the
wheel and 42% for the touch swipe. `adb shell input mouse motionevent
DOWN/UP 40 165` *does* open lxterminal's File menu, reproducing the
reporter's "mouse works, wheel doesn't". Control that the injection is
real: the same `input mouse scroll` scrolls the Android Settings list.
Delete the screenshots when done.

Two limits of this repro worth knowing before using it for cursor work:

- `--axis VSCROLL,<n>` is the wheel-detent axis (positive = away from
  the user); `HSCROLL` is the tilt wheel, `SCROLL` the generic/rotary
  one.
- `input mouse ...` synthesizes mouse-source `MotionEvent`s without
  creating a mouse `InputDevice`. `dumpsys input` on the AVD shows an
  empty `MousePointerControllers:` and no `MOUSE`-class device, so
  there is no system cursor sprite and no
  `InputManager.InputDeviceListener` callback. Event *delivery* is
  faithful; device *presence* and cursor rendering are not. Those need
  the emulator's own host mouse (interacting with the AVD window
  creates a real mouse device) or a physical device with a Bluetooth
  mouse.

## Design Decisions

### 1. Real pointer, separate from touch

Once a pointer exists, mouse-source events must stop going down the
touch path, or a single click delivers both a `wl_touch.down` and a
`wl_pointer.button` and clients double-handle it. So
`dispatchTouchToCompositor` gains a source split, and that split has to
land in the same change as pointer buttons — not before, or mouse
clicks regress to nothing.

- `SOURCE_TOUCHSCREEN`, `SOURCE_STYLUS` → `wl_touch` (unchanged).
  Stylus stays on touch; a real `zwp_tablet_v2` path is out of scope.
- `SOURCE_MOUSE` (which is also what Android reports for touchpads
  driving a cursor) → `wl_pointer`.
- Anything else (`SOURCE_ROTARY_ENCODER`, gamepads) → ignored.

Use `event.isFromSource(InputDevice.SOURCE_MOUSE)` rather than a
`SOURCE_CLASS_POINTER` test, which stylus also matches.

### 2. One owner for the pointer seat capability

`wl_seat.capabilities` must be advertised honestly, and two things now
want to control it: a physically attached mouse, and the GTK3 broken
menus workaround (`notes/gtk3-broken-menus-workaround.md`), which is on
by default and today calls `add_pointer`/`remove_pointer` directly.

Give `TawcState` a small pointer-capability owner with two independent
reasons — `mouse_attached` and `gtk3_workaround` — that calls
`seat.add_pointer()` / `seat.remove_pointer()` only on the 0↔1
transition. This is not just tidiness: smithay's `add_pointer` on a
seat that already has one *replaces* the `PointerHandle`
(`deps/smithay/src/input/mod.rs:437`), dropping focus and any live
grab, so the two reasons must never each call it.
`gtk3_menus_workaround::set_enabled` and `init_seat` stop touching the
seat and just flip their reason.

Mouse presence comes from Android: enumerate `InputDevice` ids at
Activity start, filter to `SOURCE_MOUSE`, and subscribe to
`InputManager.InputDeviceListener` for add/remove. Send the aggregate
"a mouse is attached" boolean over the existing surface-event channel
(`SurfaceEvent`, `compositor/src/host.rs:147`) so it is ordered with
focus changes the way hardware keys are.

With the workaround at its default the capability is on regardless, so
the owner only changes observable behaviour for users who disabled the
workaround. That is fine; it is still the honest shape.

Caveat to test for, not to assume away: capability *removal* is legal
but rare in the wild, and our own test client treats it as fatal
(`wayland-debug-app.c:2320`). If real clients turn out to mishandle
unplug, the fallback is to make the capability sticky for the session
once a mouse has ever been seen — cheap, and strictly better than never
advertising it. Decide with a real Bluetooth mouse, not on the emulator.

### 3. Android draws the cursor; map shapes to `PointerIcon`

When a real mouse is attached Android draws the pointer sprite itself,
above the app's surfaces. tawc should not render a second cursor into
the Wayland scene. Instead, map the client's cursor request onto the
Android `PointerIcon` for the `SurfaceView`:

- Implement `SeatHandler::cursor_image`
  (`compositor/src/compositor.rs:1137`) and push the result to Kotlin
  through a new `@JvmStatic NativeBridge.setPointerIcon(activityId,
  …)` in the `setActivityFullscreen` style, posted to the UI thread
  (`View.setPointerIcon` must run there).
- `CursorImageStatus::Named(CursorIcon)` → `PointerIcon.getSystemIcon`:
  `Default`→`TYPE_ARROW`, `Text`→`TYPE_TEXT`, `Pointer`→`TYPE_HAND`,
  `Wait`/`Progress`→`TYPE_WAIT`, `Crosshair`→`TYPE_CROSSHAIR`,
  `Grab`→`TYPE_GRAB`, `Grabbing`→`TYPE_GRABBING`,
  `NotAllowed`→`TYPE_NO_DROP`, `AllScroll`→`TYPE_ALL_SCROLL`, the
  resize family → the `*_DOUBLE_ARROW` types, anything else →
  `TYPE_ARROW`.
- `CursorImageStatus::Hidden` → `TYPE_NULL`.
- `CursorImageStatus::Surface(surface)` is what legacy
  `wl_pointer.set_cursor` clients (including Xwayland) send. Read the
  attached SHM buffer into a `Bitmap` and use
  `PointerIcon.create(bitmap, hotspotX, hotspotY)`. Non-SHM cursor
  buffers (wlegl/AHB) are not worth importing — fall back to
  `TYPE_ARROW`.

Named shapes only exist if we advertise `wp_cursor_shape_v1`; smithay
has `CursorShapeManagerState` (`deps/smithay/src/wayland/cursor_shape.rs`)
and it is a small global to add. Its device dispatch requires
`TawcState: TabletSeatHandler` (`cursor_shape.rs:239`) — an empty impl
is enough — and the existing `delegate_dispatch2!(TawcState)` covers
the rest. Worth doing in the same change: it turns the common case into
an enum instead of a bitmap upload, and GTK4 and recent Firefox use it.

### 4. Focus rules

Reuse the touch rules rather than inventing parallel ones — they were
chosen deliberately (`notes/input.md`, "Touch-down moves both keyboard
focus AND text-input-v3 focus … they are conceptually one focus").

- Factor `touch_focus_at` (`compositor/src/event_loop.rs:55`) into a
  shared `surface_at`, keeping the `desktop_visible_host_id` guard and
  the `WindowSurfaceType::ALL` hit test that honours
  `wl_surface.set_input_region`. Pointer motion uses the same lookup;
  the input-region behaviour matters for the same Firefox/WebRender
  reason.
- Pointer **motion** must *not* move keyboard focus. Hover is not
  activation.
- Pointer **button press** takes the touch-down path: reuse
  `resolve_touch_down`'s policy (`:101`) and
  `dismiss_host_popups_if_touch_is_outside_popup` (`:261`), so a click
  outside a menu dismisses it and a click in a toplevel moves keyboard
  and text-input focus.
- Smithay's default pointer grab already keeps focus while a button is
  held, and `PopupPointerGrab` is already wired at
  `compositor/src/compositor.rs:993`, so menu click-drag-release should
  work once events flow.

### 5. GTK3 priming with a real pointer present

`prime_toplevel` ends with `pointer.motion(None)`, i.e. an
unconditional `wl_pointer.leave`. With a real mouse hovering a window,
that would yank the pointer out of its current surface on every new
toplevel commit. When the pointer already has a focus, the prime must
finish by restoring that focus and location instead of leaving to
`None`. Whether the workaround is still needed at all with a mouse
attached is an open question below; this rule is needed either way.

## Implementation

### Phase 1 — plumbing, scroll, hover, buttons (fixes the reported bug)

1. `input.rs`: add a `PointerEvent` enum next to `TouchEvent`
   (`Motion { x, y, time, activity_id }`,
   `Button { code, pressed, time, activity_id }`,
   `Axis { dx, dy, v120_x, v120_y, source, time, activity_id }`,
   `Leave { time, activity_id }`) and a second calloop channel with
   the same `Mutex<Option<Sender>>` replace-on-restart shape.
2. `lib.rs`: `nativeOnPointerEvent(...)` JNI entry, doing the Android→
   Wayland translation that needs Android constants (see units below).
   Keep `nativeOnTouchEvent` untouched.
3. `CompositorActivity` / `TawcSurfaceView` (`:363`):
   - Override `onGenericMotionEvent` for `ACTION_SCROLL`.
   - Override `onHoverEvent` for `ACTION_HOVER_ENTER`/`MOVE`/`EXIT`.
     Android routes hover actions to `onHoverEvent` first and only
     falls through to `onGenericMotionEvent` when that returns false;
     override the hover hook explicitly rather than relying on the
     fall-through.
   - In `dispatchTouchToCompositor`, split on
     `isFromSource(SOURCE_MOUSE)`. Mouse-source `ACTION_DOWN`/`MOVE`/
     `UP` carry both motion (moves while a button is held arrive here,
     *not* as `HOVER_MOVE`) and button state: send motion, then diff
     `event.buttonState` against the previous mask and emit one button
     event per changed bit. Ignore `ACTION_BUTTON_PRESS`/`RELEASE`
     themselves; the mask diff already covers them.
4. `event_loop.rs`: a calloop source mirroring the touch one (`:377`) —
   resolve focus, `pointer.motion(...)` / `pointer.button(...)` /
   `pointer.axis(...)`, then `pointer.frame()` for **every** event
   group, then the same immediate `flush_clients`.
5. Capability owner from decision 2, plus the `InputDevice` /
   `InputDeviceListener` wiring, and the prime-restore rule from
   decision 5.

### Phase 2 — cursor

`wp_cursor_shape_v1` global (+ empty `TabletSeatHandler`),
`SeatHandler::cursor_image`, the `PointerIcon` mapping, and the
SHM-buffer→`Bitmap` fallback.

### Phase 3 — follow-ons, each optional

- `zwp_relative_pointer_v1` + `zwp_pointer_constraints_v1`
  (`deps/smithay/src/wayland/relative_pointer.rs`,
  `pointer_constraints.rs`) for games and 3D apps. Android's
  `View.requestPointerCapture()` is the matching side; locked-pointer
  without capture would let the system cursor drift out of the window.
- Pointer-initiated `wl_data_device` drag-and-drop. `clipboard.rs`
  handles selections only today.
- `pointer_gestures` (pinch/swipe) from Android touchpad gestures.
- Xwayland move/resize grabs, currently stubbed with an explicit "we
  don't have an X11-aware seat path yet"
  (`compositor/src/xwayland.rs:528`). X11 clients get pointer events
  for free through Xwayland's own seat binding once the capability
  exists and the shared hit test finds their surfaces; only the WM-side
  grabs need work.

## Units, Directions, and Other Places to Get It Wrong

- **Direction.** Android `AXIS_VSCROLL` is positive for scrolling *up*
  (away from the user, -1.0 down … 1.0 up); Wayland vertical axis is
  positive for *down*. Android `AXIS_HSCROLL` is -1.0 left … 1.0 right,
  which already matches Wayland's positive-right. So **negate vertical
  only**. Do not apply any "natural scrolling" inversion — that is the
  client's business.
- **v120.** `v120_y = -round(vscroll * 120.0)`,
  `v120_x = round(hscroll * 120.0)`. One wheel detent is
  `AXIS_VSCROLL == 1.0`, so one detent is 120, which is exactly what
  `axis_value120` wants. Fractional values from precision wheels and
  touchpads fall out correctly. Set `AxisFrame::v120` and let smithay
  downgrade for old clients.
- **Legacy value.** Clients below `wl_pointer` v8 use the plain `axis`
  value in surface-local logical pixels. Use 15.0 logical px per detent
  (`dy = -vscroll * 15.0`, `dx = hscroll * 15.0`), matching libinput's
  wheel-click angle, which is what desktop clients are tuned against.
  Deliberately *not* `ViewConfiguration.getScaledVerticalScrollFactor()`:
  that is in physical pixels and density-scaled, so it would make scroll
  distance depend on the phone.
- **Coordinates.** Physical pixels arrive from Android; divide by
  `data.output_scale` exactly as the touch source does
  (`event_loop.rs:397`). Pass the global logical point plus the focus
  `(surface, origin)` to smithay; it subtracts the origin itself, as it
  does for touch.
- **Axis source.** `AxisSource::Wheel` for a mouse. If the originating
  `InputDevice` also reports `SOURCE_TOUCHPAD`, use
  `AxisSource::Finger` and send `axis_stop` when the gesture ends —
  that is what makes kinetic scrolling behave in GTK.
  `AxisFrame::new` already defaults `relative_direction` to
  `Identical`; leave it.
- **Buttons.** Android `BUTTON_PRIMARY`/`SECONDARY`/`TERTIARY`/`BACK`/
  `FORWARD` → evdev `BTN_LEFT` 0x110, `BTN_RIGHT` 0x111, `BTN_MIDDLE`
  0x112, `BTN_SIDE` 0x113, `BTN_EXTRA` 0x114. Android reports a
  bitmask, not a per-button action, hence the diff in step 3. A mouse
  back button *also* raises `KEYCODE_BACK` — make sure it does not both
  send `BTN_SIDE` and run the Android Back policy
  (`handle_back_pressed`, `event_loop.rs:242`, dispatched from `:995`).
- **Time.** `event.eventTime` truncated to `u32`, as touch does.
- **Hover exit is not leave.** Android synthesizes `ACTION_HOVER_EXIT`
  before every mouse `ACTION_DOWN` and `ACTION_HOVER_ENTER` after the
  matching `ACTION_UP`. Mapping `HOVER_EXIT` to `wl_pointer.leave`
  would wrap every click in leave/enter, which closes GTK menus and
  breaks drags. Do not map it. Send `pointer.motion(..., None, ...)` +
  `frame()` (→ `wl_pointer.leave`) only on Activity focus loss, surface
  destroy, and host switch; treat `HOVER_ENTER` as ordinary motion.
  Confirm the synthesis with the regression test below rather than from
  memory.
- **Host scoping.** Pointer events carry `activityId` like touch, and
  are dropped for anything but the visible host.

## Testing

- **Debug broker.** Add `inject-pointer` beside `inject-touch`
  (`app/src/debug/java/me/phie/tawc/dev/InputActions.kt:117`) with
  `kind=move|button|scroll|hscroll|hover-exit` and logical x/y, building
  `MotionEvent`s with `TOOL_TYPE_MOUSE`, `SOURCE_MOUSE`, and
  `PointerCoords.setAxisValue(AXIS_VSCROLL, …)`, dispatched through the
  `SurfaceView` so the Activity's decoding is exercised. Add the
  matching `adb::inject_pointer` helper in
  `tests/integration/src/adb.rs`.
- **Test client.** `wayland-debug-app` needs three changes before it
  can observe any of this:
  - Bind `wl_seat` at 9 instead of 5 (`:2368`). That widens
    `wl_pointer` to v9 and `wl_touch` to v9 too, so the listener
    structs must gain `axis_value120`, `axis_relative_direction`,
    `shape` and `orientation` entries — libwayland dereferences missing
    slots. The keymap path is unaffected (it closes the fd without
    mapping it).
  - Relax `pointer_button` (`:2143`) so right/middle/side are reported
    rather than fatal, while keeping the existing "left click moves the
    text cursor" behaviour the text-input tests rely on.
  - Emit tagged `POINTER_LEAVE`, `POINTER_BUTTON`, `POINTER_AXIS`,
    `POINTER_AXIS_V120`, `POINTER_AXIS_SOURCE` and `POINTER_FRAME`
    lines, and extend `POINTER_MOTION` to `x:y`.

  Then a `tests/integration/tests/pointer_input.rs` modelled on
  `touch_input.rs`, reusing the existing `touch`/`subsurface`/
  `subsurface-input-empty`/`popup` scenes — surface-local coordinates,
  correct target through subsurface/popup/input-region scenes, one
  frame per group, sign and v120 magnitude of a detent, buttons mapping
  to 0x110/0x111/0x112.
- **Regression guards.** Assert a mouse-source `ACTION_DOWN` produces
  `POINTER_BUTTON` and *no* `SURFACE_TOUCH_DOWN`; that a touchscreen tap
  still produces only the touch event; and that a click sequence
  produces no `POINTER_LEAVE` (the hover-exit rule above).
- **Query surface, not logs.** Extend the `state_query` payload
  (`compositor/src/event_loop.rs:600`) with `pointer_present=`,
  `pointer_x=`, `pointer_y=`, `pointer_focus=`. Per the project rule,
  no per-event logging: motion and axis are high-volume.
- **Manual.** The emulator repro above, plus Firefox and lxterminal
  with a real Bluetooth mouse on the physical target — the emulator
  cannot cover cursor sprite, `PointerIcon`, or hotplug.

## Docs to Update

- `notes/input.md` — the touch-only framing and the "if real pointer
  hardware is added" note both become stale; document the source split,
  the capability owner, the hover-exit rule, and the cursor mapping.
- `notes/gtk3-broken-menus-workaround.md` — it currently describes the
  workaround as the sole owner of `wl_pointer` ("Implementation" and
  "Removal Map" both name `add_pointer`/`remove_pointer`).
- `notes/testing.md` — the `wayland-debug-app` section and the
  test-file table gain `pointer_input` and `inject-pointer`.

## Open Questions

- Does the GTK3 menubar workaround still earn its keep once a real
  pointer exists? Its whole premise is priming GTK3's cold crossing
  state on a touch-only seat. With a mouse attached the crossing events
  are real; with no mouse the workaround's reason keeps the capability
  on, unchanged. Re-test both, and consider retiring the workaround only
  with evidence.
- Capability removal on unplug versus sticky-for-session (decision 2).
- Whether `PointerIcon.create` from a client SHM cursor is worth the
  buffer read, or whether `wp_cursor_shape_v1` plus a `TYPE_ARROW`
  fallback covers enough clients in practice. Xwayland is the main
  surface-cursor user.
