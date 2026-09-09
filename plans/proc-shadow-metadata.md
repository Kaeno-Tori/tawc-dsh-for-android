# /proc shadows: metadata surface + `version`, `uptime`, `loadavg`

Fixes LibreOffice's startup exit and the class of bug behind it. Read
first:

- `tawcroot/src/proc_shadow.c` — the existing shadow table
  (`tawcroot_proc_shadow_open`, its four private matchers, the memfd
  synthesizers) and `tawcroot_compose_fd_relative` /
  `tawcroot_could_be_proc_relative`.
- `tawcroot/src/syscalls_fs.c` — `handle_openat` (the only caller of
  the shadow table today), `handle_newfstatat`, `handle_statx`,
  `handle_faccessat`, and the shm intercept block each of them already
  carries (`classify_shm` + `tawcroot_shm_{stat,statx,access}_name`).
- `tawcroot/src/shm.c` `tawcroot_shm_stat_name` /
  `tawcroot_shm_statx_name` — the stat-synthesis precedent to copy.
- [notes/tawcroot/sigsys-handler.md](../notes/tawcroot/sigsys-handler.md)
  "Refactored conventions every fs-handler follows" — single guest
  fetch, classify against the local copy, then translate the same bytes.

## Problem

Upstream report: https://github.com/wmww/tawc/issues/2 (LineageOS 22.2 /
Android 15, Arch and Debian sid). Reproduced 2026-09-09 on the physical
target (OnePlus 9, LineageOS Android 14, kernel 5.4.284, tawcroot, Arch
Linux ARM, `libreoffice-fresh 26.8.0-2`), so it is not device- or
distro-specific:

```
$ scripts/rootfs-run.sh 'libreoffice --version'
ERROR: /proc not mounted - LibreOffice is unlikely to work well if at all
RC=1
```

`oosplash` (`desktop/unx/source/start.cxx`, `system_checks()`) does one
probe and hard-exits on it:

```c
if (stat("/proc/version", &buf) != 0) { fprintf(stderr, "ERROR: /proc not mounted ..."); exit(1); }
```

Android labels the file `u:object_r:proc_version:s0`; `untrusted_app`
has neither `read` nor `getattr` on it, so `cat`, `stat` and `test -e`
all fail with `EACCES` in the guest. The denial is `dontaudit`ed, so
logcat shows nothing. `uname(2)` is unaffected and returns the real
kernel strings.

### The wider gap: shadows cover `open`, not metadata

The shadow table is consulted from exactly one place, `handle_openat`.
`handle_newfstatat`, `handle_statx` and `handle_faccessat` translate the
path and hand it to the kernel, so metadata calls still hit the real,
denied inode:

| guest path                     | `cat`            | `stat`   |
| ------------------------------ | ---------------- | -------- |
| `/proc/sys/kernel/overflowuid` | `65534` (shadow) | `EACCES` |
| `/proc/bus/pci/devices`        | empty (shadow)   | `EACCES` |
| `/proc/stat`                   | synthesized      | `EACCES` |
| `/proc/self/maps`              | rewritten        | ok       |
| `/proc/version`                | `EACCES`         | `EACCES` |

Adding `/proc/version` to the table alone would not fix LibreOffice:
`oosplash` never opens the file, it only stats it. Both halves are
needed, and a path that stats fine but fails to open is a worse lie than
one that fails both ways, so the two surfaces must share one classifier.

Other `/proc` entries denied on this device and unshadowed: `cmdline`,
`uptime`, `loadavg`, `filesystems`, `sys/kernel/osrelease`, `kallsyms`,
`modules`, `vmstat`. (`cpuinfo`, `meminfo`, `mounts`, `self/maps`,
`self/status` read fine.) `uptime` and `loadavg` are the next
complaints in line — `uptime`, `top`, `htop`, `w` and procps in general
want them — and become one-liners once the classifier exists, so this
plan adds them too.

## Design

### 1. One classifier

Replace the four private matchers with a single kind enum in
`include/proc_shadow.h`:

```c
#define TAWCROOT_PROC_SHADOW_NONE        0
#define TAWCROOT_PROC_SHADOW_MAPS        1  /* /proc/<own>/maps */
#define TAWCROOT_PROC_SHADOW_OVERFLOWUID 2
#define TAWCROOT_PROC_SHADOW_OVERFLOWGID 3
#define TAWCROOT_PROC_SHADOW_PCI_DEVICES 4
#define TAWCROOT_PROC_SHADOW_STAT        5
#define TAWCROOT_PROC_SHADOW_VERSION     6
#define TAWCROOT_PROC_SHADOW_UPTIME      7
#define TAWCROOT_PROC_SHADOW_LOADAVG     8
int tawcroot_proc_shadow_classify(const char *path);
```

`tawcroot_proc_shadow_open(path, out)` keeps its signature and becomes
`switch (classify(path))`. New surfaces, same classifier:

```c
long tawcroot_proc_shadow_stat(int kind, struct stat *out);
long tawcroot_proc_shadow_statx(int kind, unsigned int mask, struct statx *out);
long tawcroot_proc_shadow_access(int kind, int mode);
```

All three take the kind, not the path, so a handler classifies once.

### 2. Stat synthesis: no memfd

Do **not** build the memfd and `fstat` it. For `maps` that would read
and rewrite the whole maps file on every `stat`, and `ls -l /proc/self`
style walks stat a lot. A procfs regular-file inode is fully describable
without content:

- `st_mode = S_IFREG | 0444`, `st_nlink = 1`, `st_uid = st_gid = 0`
- `st_size = 0`, `st_blksize = 1024`, `st_blocks = 0`
- `st_dev`/`st_ino`: stat `/proc` itself once (readable) and cache
  `st_dev`; `st_ino` a fixed per-kind constant well above real procfs
  numbers is fine. Nothing in the target workloads compares these, but
  keeping `st_dev` on the procfs device costs nothing.
- times: `CLOCK_REALTIME` now for all three (procfs reports "now" for
  its files too).

`statx` mirrors that and sets `stx_mask` for every field it fills
(`STATX_BASIC_STATS`), then calls `tawcroot_statx_fill_mnt_id` with an
O_PATH fd of `/proc` when the guest asked for a mount id, same as the
shm synthesizers. Zero the struct first (`zero_stat` / `zero_statx`).

Treat `self/maps` uniformly. The real file is statable, but the
synthesized answer equals the kernel's once `decorate_stat` has zeroed
uid/gid, and one code path beats a special case.

### 3. Access semantics

`F_OK` and `R_OK` → 0. Any `W_OK` or `X_OK` bit → `-EACCES`. Plain
`faccessat` carries no flags and `faccessat2` with flags is already
turned into `-ENOSYS` by the handler, so there is no NOFOLLOW variant.

### 4. Wire the handlers

In `handle_newfstatat`, `handle_statx` and `handle_faccessat`, directly
after the shm intercept block (i.e. after `fetch_guest_path`, before
`translate_local`), classify `scratch->buf[0]`. On a hit, synthesize
into `local` and copy out (`finish_stat` / `finish_statx` already do the
copy; access returns the errno directly).

Carry the fd-relative compose across from `handle_openat`
(`syscalls_fs.c` ~line 269): if the absolute classify missed, `dirfd !=
AT_FDCWD`, the path is relative and passes
`tawcroot_could_be_proc_relative`, compose via
`tawcroot_compose_fd_relative` into `scratch->buf[2]` and classify
again. Without it `fstatat(proc_dirfd, "version", ...)` still reaches
the denied inode.

**Extend the fast-out gate.** `tawcroot_could_be_proc_relative` admits
first chars `{b,c,s,t,m,e,digit}`; `version`, `uptime` and `loadavg`
start with `v`, `u`, `l`. Add those three and update its comment.
Factor the "classify, else compose and classify" pair into one helper
so the four call sites cannot drift.

For `newfstatat`/`statx` with `AT_SYMLINK_NOFOLLOW`: the shadowed paths
are regular files, not links, so NOFOLLOW makes no difference; classify
regardless of flags. The `AT_EMPTY_PATH` early return stays ahead of the
intercept (an fd to a shadow memfd is a real memfd and fstats fine).

### 5. New content synthesizers

All async-signal-safe, raw syscalls only, built with `tawc_str_append*`
into a stack buffer and handed to `memfd_from_bytes` like
`open_proc_stat_shadow`.

- **`/proc/version`.** Needs `TAWC_SYS_uname` in `include/sysnr.h` (160
  on aarch64, 63 on x86_64; no wrapper exists today). Content:

  ```
  Linux version <utsname.release> (tawcroot@android) (tawcroot) <utsname.version>\n
  ```

  Same shape as the real file (`release`, builder parenthetical,
  compiler parenthetical, `version`), with only the two parentheticals
  invented. `tawcroot@android` doubles as the marker hosted tests grep
  for, since the host's real `/proc/version` is readable and would
  otherwise be indistinguishable from a passthrough.

- **`/proc/uptime`.** `<boottime>.<cs> <idle>.<cs>\n` from
  `CLOCK_BOOTTIME`. Report idle equal to uptime, matching the
  idle-only cpu line the `/proc/stat` shadow already emits; the two
  must agree or procps prints negative CPU usage.

- **`/proc/loadavg`.** `0.00 0.00 0.00 1/1 <getpid()>\n`. Fixed loads
  (we cannot see the scheduler), one running of one, and our own pid
  as "last pid" — every consumer treats the last field as informational.

Existing memfd names (`tawcroot-<file>`) keep their convention:
`tawcroot-version`, `tawcroot-uptime`, `tawcroot-loadavg`.

## Tests

`tawcroot/tests/hosted/test_proc_chroot.c`, next to
`hosted_proc_overflowuid_shadow_content`:

- **Metadata per kind.** For each shadowed absolute path, `newfstatat`,
  `statx` (with `STATX_BASIC_STATS`) and `faccessat(R_OK)` return 0 and
  report `S_IFREG`, uid/gid 0, size 0; `faccessat(W_OK)` returns
  `-EACCES`. Table-driven over the path list so a new shadow is one
  entry.
- **Fd-relative.** `openat("/proc", O_PATH|O_DIRECTORY)` then
  `newfstatat(fd, "version")`, `statx(fd, "uptime")` and
  `faccessat(fd, "loadavg", R_OK)` hit the shadows.
- **Content.** `version` starts with `Linux version ` and contains
  `(tawcroot@android)`; `uptime` parses as two non-negative decimals,
  equal to each other; `loadavg` ends with the test's pid.
- **Open/stat lockstep.** A test that walks the classifier's kinds and
  asserts every kind that opens also stats (both via the handlers), so
  a future shadow added to one surface only fails CI.

Run with `tawcroot/test.sh --host proc` and `--device proc` on the
physical target.

## Acceptance on the physical target

- `scripts/rootfs-run.sh 'libreoffice --version'` prints
  `LibreOffice 26.8.x` instead of the "/proc not mounted" error
  (install `libreoffice-fresh` through the cache proxy; see the repro
  notes below for the stale-db trap).
- `scripts/rootfs-run.sh 'stat /proc/version /proc/uptime /proc/loadavg /proc/stat /proc/sys/kernel/overflowuid /proc/bus/pci/devices'`
  succeeds for all six.
- `scripts/rootfs-run.sh 'uptime; cat /proc/loadavg'` works
  (`procps-ng` on Arch).

## Docs to update in the same change

- `notes/tawcroot/status.md` "More `/proc` shadows": add the metadata
  surface, list `version`/`uptime`/`loadavg`, and drop them from the
  denied list.
- `notes/tawcroot/sigsys-handler.md` "/proc shadow synthesis": now "one
  synthesizer + one classifier kind + one stat kind", and note the
  lockstep rule.
- `notes/tawcroot/bootstrap-and-modules.md` module tree comment for
  `proc_shadow.c`.
- Delete this plan.

## Not in scope

- `cmdline`, `filesystems`, `sys/kernel/osrelease`, `kallsyms`,
  `modules`, `vmstat`: no workload has asked. With the classifier in
  place each is a few lines when one does.
- `fstat` via `AT_EMPTY_PATH` on an `O_PATH` fd of a shadowed path.
  `O_PATH` opens do not need read permission so the open succeeds and
  the later getattr is denied. glibc's `stat` never does this; leave it.
- The LibreOffice window drawing larger than the surface with a
  chartreuse gutter (seen with `SAL_USE_VCLPLUGIN=gtk3`): unrelated.

## Verified workaround (until this lands)

Bypass `oosplash` and run `soffice.bin` directly. It exits 81
(`EXITHELPER_NORMAL_RESTART`) on a first run that creates a profile, so
the caller needs the restart loop `oosplash` normally provides:

```sh
while :; do /usr/lib/libreoffice/program/soffice.bin "$@"; [ $? -eq 81 ] || break; done
```

Confirmed: `--version`, `--headless --convert-to pdf`, and `--writer`
with `gtk3` installed and `SAL_USE_VCLPLUGIN=gtk3`. Arch's
`libreoffice-fresh` does not pull `gtk3`; without it the only VCL
plugins are `gen` (X11, needs Xwayland) and `svp` (headless).

## Repro notes

Installing LibreOffice through the dev cache proxy first 404ed on
`python`/`perl` — the stale-cached-pacman-db problem in
`issues/cache-proxy-stale-pacman-db.md`. Worked around without wiping
the cache by prepending a mirror URL the proxy had never cached
(`https/fr.mirror.archlinuxarm.org`; the cache key is the full upstream
URL, so `https` vs `http` is a different entry), running `pacman -Syy`,
then restoring the mirrorlist. Every mirror looks like one host
(`127.0.0.1:8080`) to pacman, so one stale mirror's 404s trip "too many
errors from ..., skipping" and take the whole mirrorlist down with it.
