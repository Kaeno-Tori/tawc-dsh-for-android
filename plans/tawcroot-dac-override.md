# tawcroot: emulate CAP_DAC_OVERRIDE for the fake root

Upstream report: https://github.com/wmww/tawc/issues/12 — `whoami` says
`root`, `mkdir /test` says `Permission denied`. Release build, so
nothing ever ran as real root.

## Problem (verified 2026-09-09, physical target, arch install)

tawcroot fakes uid 0 (`getuid`/`geteuid` → 0, stat decorated
root-owned) but the kernel sees the app uid on every real syscall and
`untrusted_app` never holds CAP_DAC_OVERRIDE. A directory whose mode
denies the *owner* write blocks the guest's "root" too:

```
chmod 555 /   →  mkdir: cannot create directory '/probe_b': Permission denied
chmod 700 /   →  mkdir succeeds
```

How the bit gets lost:

- Not ownership. `chown` needs CAP_CHOWN, which we never have, so
  tawcroot's chown is cosmetic (`syscalls_fs.c` fchownat handler) and
  every inode in a production rootfs is owned by the app uid. Mode is
  the only lever.
- Not the installer. `ProotArchiveExtractor.kt:124` skips the
  archive's root entry, so the rootfs dir keeps `mkdirs()`'s 0700
  under Android's 0077 umask. On device: ALARM `/` is 0700, Debian
  sid `/` is 0755 (`base-files`' `./` entry applied by dpkg inside
  the guest). Both writable.
- The guest itself. `chmod` needs only ownership, not DAC override,
  so guest root can `chmod 555 /` and it sticks; only the next write
  fails. `tar -x` of an archive with a restrictive `./`, a package
  shipping an odd `./` mode, a script copying modes from another
  tree — all do it with no privilege involved.

Ruled out: a read-only bind returns EROFS (`path.c`), not EACCES, and
`mkdirat` is a straight passthrough (`syscalls_fs.c:1136`,
`DECLARE_AT_PASS`). `fchmodat`/`fchmod` only swallow EPERM/EACCES from
the chmod itself; they never widen anything.

**This is a regression from the proot method, not a distro property.**
proot `-0` emulates CAP_DAC_OVERRIDE eagerly: fake_id0's `HOST_PATH`
hook calls `override_permissions`
(`deps/proot/src/extension/fake_id0/fake_id0.c:372`), which chmods
every path component to u+rw (+u+x for directories) before the
syscall and restores the old mode via talloc destructors at syscall
exit. Our proot build does not define `USERLAND`, so that path was
active. `mkdir /test` worked under proot and broke when tawcroot
became the default.

The same class already shows up elsewhere: the ALARM bootstrap's
0500 `/etc/ca-certificates/extracted/cadir` (why the extractor defers
directory modes) and `RootfsCleaner.kt:120`'s `chmod -R u+rwX`.

Not covered by the report: `access(W_OK)`. `[ -w / ]` on a 0555 root
answers no under tawcroot, and scripts act on that. Real root gets
yes.

## Design: lazy DAC override in the path handlers

Do what proot does, but on the error path only. proot stats every
component of every path syscall; that per-syscall overhead is exactly
what tawcroot exists to avoid. On the happy path nothing changes.

1. **Rescue helper around the host call.** On EACCES with virtual
   euid 0 (`tawcroot_identity_euid() == 0`), walk the translated
   route: `t.fd` plus each prefix of `t.path`. For every inode the
   app owns (`st_uid == real uid`) that is a directory lacking
   `u+rwx`, or the leaf file lacking `u+rw`, chmod it wider and record
   the old mode in a small fixed-size array (no malloc in the handler;
   bound = path component cap, refuse beyond it). Retry the syscall
   once. Restore in reverse order regardless of the retry's result.
2. **Never add x to files.** That is CAP_DAC_OVERRIDE's exact rule:
   root cannot exec a file with no exec bit, and `access(X_OK)` on
   such a file is EACCES for root too.
3. **Retry only if something was widened.** A real SELinux denial, or
   a bind the app does not own (`/dev`, `/sdcard`), returns the
   original EACCES with no second syscall.
4. **Restore, do not widen permanently.** The guest keeps seeing the
   mode it set: `pacman -Qkk`, sudo's 0440 sudoers check, ssh
   StrictModes all stay clean. A concurrent thread may briefly see the
   widened mode; harmless, the rootfs is one security principal
   (notes/tawcroot/overview.md). A crash between widen and restore
   leaves the wider mode; also harmless.
5. **Gate on virtual euid 0.** A guest that dropped privileges gets
   the real EACCES, matching the existing fchmodat/fchownat gating.
6. **Coverage.** Fold into `DECLARE_AT_PASS` and the hand-written
   handlers: openat, mkdirat, mknodat, unlinkat, renameat2, linkat,
   symlinkat, utimensat, truncate, faccessat, chdir, execve. For
   two-path syscalls (rename, link) rescue both routes. `faccessat`
   goes through the same helper, no special casing: widening covers
   R_OK/W_OK, and X_OK stays honest because files never gain x.

Known limit, write it into the notes: the symlink walker propagates
EACCES from `readlinkat` (`path_resolve.c:177`), so a directory with
no owner *search* bit fails inside translation before the helper
runs. Directories with x but no w (the `/` and cadir cases) are the
only ones seen in practice; do not extend the walker for this unless
a real workload needs it.

Alternative considered and rejected: clamp modes at chmod/mkdir/open
time so the tree never holds a restrictive owner mode. Changes what
the guest sees, does not fix modes already on disk, does not cover
the faithful 0500 cadir, and diverges from proot's semantics.

## Tests

- Hosted tests (`tawcroot/tests/hosted/test_fs_handlers.c`) run as
  the dev user, so DAC is real: create a 0555 dir under the rootfs,
  `mkdirat` inside it succeeds, its mode is still 0555 afterwards,
  `faccessat(W_OK)` returns 0, and a 0444 leaf opens O_WRONLY. Add a
  dropped-identity variant that still gets EACCES, and a not-owned
  case (a dir owned by another uid is not creatable on the host, so
  cover it with a bind to a non-app path where chmod fails and
  assert the original EACCES with no retry).
- `rootfs_smoke.c`: `chmod 555 /; mkdir /x; ls -ld /` shows 555. Skip
  under real uid 0 (rooted emulator), following the pattern in
  notes/tawcroot/testing.md "Device-environment sensitivities".
- Integration: a tawcroot session on the standing target running
  `chmod 555 / && mkdir /probe && rmdir /probe && chmod 700 /`.

## Docs and cleanup

- **Rewrite** `notes/tawcroot/overview.md` "not a full
  userland-namespace replacement": drop the "ALARM ships `/` as
  0555" claim (added in a1a63fe with no evidence; on-device ALARM `/`
  is 0700) and the "don't go looking for a missing handler" sentence.
  State that DAC override is emulated lazily and name the
  no-search-bit limit.
- **Add** the contract to the chmod/chown list in
  `notes/tawcroot/path-translation.md` (§"Translation rules", next to
  the chmod entry), and fold the existing `mknodat`/`fchmodat`
  swallow comments into the same story.
- **Keep** `ProotArchiveExtractor`'s mode deferral. It runs in the
  JVM where DAC is real, and it is the same delayed-directory-
  permissions logic GNU tar uses. Its doc comment still says "for the
  proot install method"; it is used by tawcroot installs too, fix the
  framing while there.
- **Keep** `RootfsCleaner`'s `chmod -R u+rwX`. The wipe runs as the
  app uid through toybox find, outside tawcroot, and every ALARM
  install faithfully contains a 0500 cadir.
- Reply on upstream #12 once released: fixed in tawcroot; the
  interim workaround is `chmod 755 /` inside the guest.
