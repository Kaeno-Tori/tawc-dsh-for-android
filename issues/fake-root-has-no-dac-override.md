# Fake root has no DAC override: a lost owner-write bit is a dead end

Upstream report: https://github.com/wmww/tawc/issues/12 — `whoami` says
`root`, `mkdir /test` says `Permission denied`. The reporter used a
release build, so no part of the install ever ran as real root.

tawcroot fakes uid 0 (`getuid`/`geteuid` → 0, stat results decorated
root-owned) but the kernel still sees the app uid on every real syscall,
and `untrusted_app` never holds CAP_DAC_OVERRIDE. So a directory whose
mode denies the *owner* write blocks the guest's "root" too. On real
Linux the same mode is harmless — root ignores it — so the guest walks
into this doing something completely ordinary and gets a wall that looks
like a tawc bug.

## Reproduced (physical target, arch install)

```
chmod 555 /   →  mkdir: cannot create directory '/probe_b': Permission denied
chmod 700 /   →  mkdir succeeds
```

## How the bit gets lost

Not from ownership. `chown` to another uid needs CAP_CHOWN, which we
never have, so tawcroot's chown is cosmetic and **every inode in a
production rootfs is owned by the app uid**. Mode is the only lever.

Not from the installer either. `ProotArchiveExtractor.kt:124` skips the
archive's root entry (`rel.isEmpty() || rel == "/" || rel == "."`), so
the rootfs dir keeps `mkdirs()`'s mode — 0700 under Android's 0077 app
umask — and nothing in `app/` or `tawcroot/` sets a umask. On-device:
ALARM `/` is 0700, Debian sid `/` is 0755, both writable. The 0755 comes
from `base-files`' `./` entry applied by dpkg *inside* the guest.

So the write bit is dropped after install, by the guest, and that is
easy because **`chmod` needs only ownership, not DAC override**: guest
root can chmod `/` to 0555 and it sticks perfectly; only the next write
fails. `tar -x` of an archive carrying a restrictive `./`, a package
shipping an odd `./` mode, a script copying modes from another tree —
any of them do it with no privilege involved.

Ruled out while narrowing this down: a read-only bind returns EROFS
(`path.c:216`, `671`), not EACCES, and `mkdirat` is a straight
passthrough (`syscalls_fs.c:1136`), so the EACCES is the kernel's own
DAC verdict on the real rootfs directory.

## Scope: bigger than `/`

Same class, already papered over three times:

- `ProotArchiveExtractor`'s entire mode-deferral design exists for the
  0500 `/etc/ca-certificates/extracted/cadir` in the Arch bootstrap.
- `RootfsCleaner.kt:120` does `chmod -R u+rwX` before wiping.
- `notes/tawcroot/overview.md:216` documents it as by-design.

## Workaround

`chmod 755 /` from inside the guest fixes it — chmod needs only
ownership. Users have no way to guess this; the error names no cause.

## Fix

Emulate DAC override for the app-owned tree: on EACCES from a path
syscall under virtual euid 0, retry with a temporary mode widen on the
offending directory. That retires all three workarounds above.

## Also

`notes/tawcroot/overview.md:222` claims the ALARM bootstrap "ships `/`
as mode 0555" and tells the reader "don't go looking for a missing
handler". The ALARM tarball has no `./` entry at all, and the extractor
would skip it anyway — checked upstream, along with Arch x86_64
(`root.x86_64/` 0750), Manjaro, Debian sid and Void (`./` 0755). Someone
recorded a symptom and guessed the cause; the note should be corrected.

Open: reporter has not said which distro, or what `ls -ld /` shows. The
mode is real rather than faked, so that one command names the culprit.

Found 2026-09-09 investigating issue #12.
