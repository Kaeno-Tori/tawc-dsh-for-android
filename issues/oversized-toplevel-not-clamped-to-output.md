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
unavailable), not part of this bug. A report from the physical device
described a chartreuse gutter around the same oversized window; that
was not reproduced here.

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
