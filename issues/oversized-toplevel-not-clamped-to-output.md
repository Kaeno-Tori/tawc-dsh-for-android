# Toplevels larger than the output are clipped, not clamped

A client that sizes its own window bigger than the output gets the
window drawn from the top-left corner with everything past the right
edge simply cut off. There is no way to see, move, or resize the
hidden part, so buttons in the bottom-right of a dialog are
unreachable.

Repro (emulator, Debian sid, tawcroot):

```sh
scripts/rootfs-run.sh 'apt-get install -y --no-install-recommends libreoffice-writer libreoffice-gtk3'
scripts/rootfs-run.sh 'SAL_USE_VCLPLUGIN=gtk3 libreoffice --writer'
```

LibreOffice's first-run wizard comes up. It renders correctly — real
text, real widgets, clean fonts — but sits flush at x=0 and runs off
the right edge. Its "Cancel"/"Next" button row is sliced mid-button,
and the wizard cannot be dismissed. The bottom ~57% of the screen is
empty compositor background.

Compositor state while it is up:

```
clients=1 toplevels=2 rendered_toplevels=2 surfaces_shm=2
output_scale=2.00 output_physical_w=1080 output_physical_h=2072
                  output_logical_w=540   output_logical_h=1036
```

So the usable logical width is 540. GTK dialogs are laid out at their
natural size, and LibreOffice's wizard wants roughly 800 logical px —
it never negotiates down to what the output can show.

The magenta tint over the window is the intentional SHM fallback
marker (`surfaces_shm=2`, expected on the emulator where libhybris is
unavailable), not part of this bug.

## Also reproduces on the physical device (OnePlus 9, Arch, tawcroot)

```sh
TAWC_INSTALL_ID=arch scripts/rootfs-run.sh 'SAL_USE_VCLPLUGIN=gtk3 libreoffice --writer'
```

LibreOffice's "Tip of the Day" dialog comes up instead of the wizard,
and clips the same way: content runs to x=1079 with no right edge, the
body text is cut mid-word, and the button right of "Next Tip" is
sliced to ~27px, so the dialog cannot be dismissed. It is 424px tall,
leaving the bottom ~77% of the screen as empty background.

```
clients=1 toplevels=2 rendered_toplevels=2 surfaces_wlegl=2 surfaces_shm=0
last_wlegl_width=1268 last_wlegl_height=424
output_scale=2.00 output_physical_w=1080 output_physical_h=2169
                  output_logical_w=540   output_logical_h=1085
```

1268 > 1080, so the overflow is visible directly in the buffer size.
x11_surfaces=0 here too — GTK3 came up as a native Wayland client.

The chartreuse gutter previously reported from the physical device is
not a bug either: it is the lime libhybris/AHB buffer-type tint
(`render.rs` `SurfaceKind::tint_color`), the counterpart to the
emulator's magenta, faded over `TINT_EDGE_FADE` (10% of the buffer)
from each edge inward. It shows on the top, bottom and left edges and
not the right — which is itself a tell that the right edge is off
screen rather than at x=1079.

## Not yet investigated

- Whether the compositor should send a bounding `xdg_toplevel`
  configure (`set_bounds`, xdg-shell v4) so clients size themselves to
  fit, or clamp/scale oversized surfaces at composite time, or offer
  panning. `set_bounds` is the protocol-correct nudge but is advisory
  — GTK honours it for some window types only, so it likely needs a
  fallback.
- Whether this is specific to dialogs sized from their natural request
  (GTK) or also hits toplevels that ask for an explicit large size.
- Whether the same thing happens under XWayland (`x11_surfaces=0`
  here — the wizard came up as a native Wayland surface).
