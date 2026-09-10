# Honor `chsh` for the In-App Terminal

wmww/tawc#7: the reporter ran `chsh -s /usr/bin/zsh` and the terminal
kept opening bash. `chsh` itself works under tawcroot — verified on
the emulator's Debian rootfs 2026-09-09 (`chsh -s /usr/bin/dash`
rewrote root's `/etc/passwd` line, exit 0, reverted after). The app
just never reads that field: every spawn hardcodes `/bin/bash`.

Scope: interactive terminal tabs open root's passwd shell. Nothing
else changes. No app-side shell setting (it would duplicate what
`chsh` already expresses), no zsh/fish prompt defaults, no in-app
escape hatch for a broken shell.

## Verified Current State

- `TawcrootMethod.ptyShellExec`
  (`app/src/main/java/me/phie/tawc/install/TawcrootMethod.kt:172`)
  appends `/bin/bash` then `-l` (interactive) or `-lc <command>`
  (launcher `Terminal=true` entries, wrapped in `TerminalActivity`'s
  hold-open trailer, which is bash syntax: `read -rsn1`).
- `TawcrootMethod.startInside` (`:117`) does the same for every
  non-tty spawn: install steps, launcher Exec lines, `RunCommandOp`,
  the exec broker, `rootfs-run.sh`. proot/chroot mirror it
  (`ProotMethod.kt:190`, `ChrootMethod.kt:76`); both are debug-only
  and the terminal is tawcroot-gated (notes/terminal.md), so they are
  out of scope.
- `RootfsEnv.build` sets `HOME=/root`, `USER=root`, `LOGNAME=root`
  and no `SHELL`. bash fills `SHELL` from passwd itself when unset
  (observed `SHELL=/bin/bash`), so today the only thing pinning bash
  is the argv.
- `ShellDefaults` writes `/root/.bashrc` + `/root/.bash_profile`
  once at configure time and ships `/usr/lib/tawc/bashrc`
  (cwd prompt + OSC tab title). All bash-only.
- The rootfs is app-uid-owned under tawcroot, so the app can read
  `<rootfs>/etc/passwd` and stat `<rootfs>/<shell>` directly, no
  rootfs entry needed.

## Design

Mirror what a desktop does: Exec lines run via sh, the terminal opens
the user's login shell.

1. **Resolve the shell host-side.** New `RootShell.resolve(rootfs:
   File): String` in `install/`: parse `<rootfs>/etc/passwd`, take the
   first line whose name field is `root`, return field 7. Fall back to
   `/bin/bash` when: the file or line is missing, the field is blank,
   or `<rootfs>/<shell>` does not resolve to an existing executable
   (`chsh` to a shell later uninstalled is the common case). Resolve
   the path through the rootfs so an absolute symlink target
   (`/usr/bin/zsh -> zsh-5.9`) is checked inside the rootfs, not on
   the host — reuse whatever `PackageBootstrapInstaller`/store code
   already does for in-rootfs symlinks, else `File(rootfs, rel)` plus
   `canExecute()` is enough for a first pass. Pure function, JVM
   unit-tested against a temp dir (`RootShellTest`): bash default,
   custom shell honoured, missing binary → bash, non-executable →
   bash, malformed passwd → bash, `root` matched on the name field
   only (a `roots` user or a `root` substring elsewhere must not
   match).
2. **Use it for interactive tabs only.** In `ptyShellExec`, when
   `command == null` exec `<shell> -l`; when a command is given keep
   `/bin/bash -lc`. `-l` is accepted by bash, zsh, fish, dash, ksh.
   `startInside` is untouched: command paths stay POSIX sh + bash
   because Exec lines, install scripts, the hold-open trailer and
   `rootfs-run.sh` all assume it (fish is not POSIX).
3. **Export `SHELL`.** Add `SHELL=<resolved>` to `RootfsEnv.build` for
   every spawn (tawcroot only needs it, but setting it under all
   methods is harmless and keeps the map uniform). This makes GUI
   terminals launched from the desktop open the same shell, and
   `$SHELL` correct in scripts. `build` currently takes no rootfs;
   thread the resolved value in from the two `TawcrootMethod` call
   sites rather than reading passwd inside `build` (which is also
   called by the JVM tests with no rootfs).
4. **Docs.** notes/terminal.md "Spawn path": one paragraph on the
   passwd lookup, the bash fallback rule, the command-session
   exception, and that `ShellDefaults` prompt/title only apply to
   bash (a zsh tab labels by number, since no OSC title arrives).
   Drop "the shell is the distro's own `/bin/bash`" wording.

## Known Consequences (accepted)

- A non-bash shell gets its distro default prompt and no cwd tab
  title. Users of zsh/fish configure their own prompts; not our job.
- A shell that exists but fails on startup kills every new tab
  immediately, and the user has no in-app terminal left to run `chsh`
  back. Recovery is `rootfs-run.sh 'chsh -s /bin/bash'` from a dev
  box, or reinstalling the distro. Rare enough not to build for.
- Startup cost: one small file read + stat per tab. Negligible.

## Verification

Emulator (`.tawctarget=emulator`, Debian rootfs) — dash is already in
`/etc/shells`, so no package install needed:

```
scripts/rootfs-run.sh 'chsh -s /usr/bin/dash'
```

Open Terminal from the app home screen: prompt should be dash's `#`
with no colour; `echo $0 $SHELL` → `/usr/bin/dash /usr/bin/dash`. A
launcher `Terminal=true` entry must still run under bash (hold-open
trailer works). Then `chsh -s /usr/bin/zsh` without zsh installed →
tab opens bash (fallback). Revert with `chsh -s /bin/bash`. Run
`./gradlew :app:testDebugUnitTest` for `RootShellTest`.

On landing: delete this plan, fold the design into notes/terminal.md,
close wmww/tawc#7.
