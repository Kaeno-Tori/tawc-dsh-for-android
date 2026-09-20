# External storage binds

Per-install binds of host directories into the rootfs, so users can
keep selected rootfs data outside app-private storage — surviving
uninstall and visible to other Android apps. Example: shared storage
(`/storage/emulated/0`) mounted at `/mnt/android`.

Tawcroot-only: the bind list rides the same `-b src:dst` table as the
built-in system/share binds (path rewrites, not kernel mounts — so
uninstall's recursive delete never traverses into a bind source). That
invariant — not the wipe engine — is what protects Android-side data
on OS-level uninstall, where no app code runs. At in-app wipe time,
[RootfsCleaner]'s uniform mount gate (refuse to delete while any mount
sits under the install dir) is the backstop if the invariant is ever
broken (e.g. external binds wired into chroot's real mounts). The
chroot debug method uses real mounts and deliberately does not get
these binds until its uninstall interaction is reviewed; proot doesn't
need them for any dev-loop purpose.

## Permission model

Direct path access to shared storage needs `MANAGE_EXTERNAL_STORAGE`
("all files access", Android 11+), granted by the user via a system
settings toggle. Two independent runtime gates in
`install/AllFilesAccess.kt`:

- `declared(context)` — the permission is in the APK manifest at all.
  `-PtawcAllFilesAccess=false` strips it at build time via the
  build-type manifest overlay `app/src/overlays/no-all-files-access/`.
  The flag was added for a distribution channel that treats the
  permission as sensitive; there is no such channel now (releases are
  GitHub assets only), so it stands as a build option for anyone who
  would rather not ship a broad permission. All binds UI hides itself
  when false; nothing else changes, so the same code ships both ways.
- `granted()` — `Environment.isExternalStorageManager()`. The
  manage-binds screen deep-links to the settings toggle
  (`openSettings`).

SAF was rejected as an alternative: a `content://` tree URI has no
filesystem path tawcroot could bind; faking one would mean a multi-week
broker-backed VFS with poor POSIX fidelity.

## Data model

`Installation.externalBinds` — a list of `ExternalBind(hostPath,
guestPath, readOnly)` persisted in `metadata.json` (absent on legacy
records = empty). Entries carry `"kind": "path"`; unknown kinds are
skipped on parse so future bind sources stay forward-compatible (ditto
unknown keys, e.g. the retired `label`).

`readOnly` (JSON `"readOnly"`, absent = false so legacy records stay
writable) makes guest writes/deletes into the bind fail with `EROFS`
via tawcroot's `-b src:dst:ro` (notes/tawcroot/path-translation.md
§"Read-only binds"). The `:ro` suffix is appended only at argv-emit
time in `TawcrootMethod.bindSpecs`, never stored in the paths — the
validator's `:` rejection stays load-bearing. There is no global
default: RO-ness is per-bind (the `/ → /android` suggestion defaults
RO, the save-into-storage binds RW) and user-toggleable. It is the
first per-bind permission; if Landlock-style flags grow
(plans/tawcroot-landlock.md), widen to a flags field — parse already
tolerates new keys.

`ExternalBind.validationError()` is the shared structural validator
(absolute paths, no `..`, no `:` — a colon would split the `-b src:dst`
argv pair — and guest ≠ `/`). Every accepting surface runs it: the
manage-binds dialog, `InstallationService.startInstall`, and the spawn
path.

## Spawn path

`TawcrootMethod` resolves the install id from the rootfs path
(`<distros>/<id>/rootfs`) and loads the bind list from metadata on
every spawn — so the broker's RUNINSIDE, RunCommandOp, the in-app
terminal, and the install pipeline's own in-rootfs steps all pick up
binds without threading `Installation` through their call chains.
External binds append after all built-in binds; tawcroot's longest-
dst-prefix lookup (first-added wins ties) means they can't shadow the
system/share set. Guest target dirs are pre-created in `prepareSpawn`.

Fail closed: a structurally invalid bind, a missing host dir, or a
shared-storage bind (`/storage/...`, `/sdcard/...`) without the
all-files grant throws `IOException` with an actionable message instead
of spawning. Never substitute an empty app-private dir — a session
"writing to shared storage" that actually lands app-private would be
data loss at uninstall.

## Install-time binds

No binds exist by default. Binds configured on the install form are
persisted in the initial metadata write, so they're live during the
installation process and first boot. `InstallationService.startInstall`
takes an optional JSON list (`externalBinds` intent extra / broker
`--arg`): an explicit list is honoured as-is; absent means none. The
install form warns (grant / install anyway) when the pending binds need
a grant that's missing, since the fail-closed error would otherwise hit
mid-install.

One caller fills that list without the user touching a binds screen:
`MainActivity`'s all-files-access card carries a "bind shared storage
automatically" toggle, and both of its install buttons (one-tap and
import-a-pack) go through the same `launchInstall`, so both pick it up.
Three states, stored as one nullable pref: `null` (never touched)
follows the grant — granted means the toggle reads as on, which is the
auto-tick the card exists for — while an explicit `true`/`false` is
the user's own call and is never overwritten by a later grant change.
The toggle renders only when the grant is already held: it maps shared
storage, which is fail-closed, so on a build without the grant setting
it would only produce installs that refuse to start.

What it adds is `AllFilesAccess.sharedStorageBinds()` — i.e. the
shared-storage half, deliberately **not** the Android-root bind. The
card's own wording is about shared storage ("read your Downloads,
Pictures, …"), and handing over a read-only view of the whole Android
filesystem as a side effect of that sentence would grant more than the
sentence asked for. The root bind stays available as a one-tap
suggestion, and both halves are editable afterwards under container
management.

The custom install form is the other entry point, and it *seeds* rather
than decides: a form opened fresh (not restored from a rotation) with an
empty list starts from the same toggle, so ticking the box on the setup
screen and then walking into the form doesn't quietly produce an
unmapped container. It is evaluated once, at `onCreate`, and only when
the list is empty — from then on the list is the user's, and clearing it
in `ManageBindsActivity` has to stay cleared (the rotation path goes
through `KEY_BINDS` instead, which is what keeps an emptied list empty).
While the list is still the seeded one the row says so
(`install_external_binds_auto_label`); the first trip through Manage
clears that flag, so the label can't outlive the provenance it claims.
Unlike MainActivity's card the form keeps *seeding* regardless of the grant —
its grant dialog already covers the fail-closed case, and hiding the
seeding would recreate the very disagreement this removes.

## UI

- `MainActivity`'s setup screen carries the grant card too, and that
  is deliberately the *first* place it appears: the binds screens
  below are reachable only from the custom install form or from an
  existing install's container management, i.e. only for a user who
  already knew to go looking — while the one-tap install is exactly
  the path that never mentions the permission. The card asks for the
  grant and, once held, offers the automatic-bind toggle described
  under *Install-time binds* above; hides
  itself entirely on a build that doesn't declare the permission, and
  is re-rendered from `onResume` because `render()` rebuilds only on a
  screen *change* — without that, returning from the system toggle
  would keep showing "not granted".
- `ManageBindsActivity` — add/edit/remove. Read-only binds show a
  "Read-only" badge on their card; the flag is edited only via the
  add/edit dialog's checkbox. `AllFilesAccess.
  commonDirBinds()` is the suggested set: `/android_root` ⇐ `/` (the
  Android root; much of it unreadable to the app uid — expected;
  read-only by default, it's browse-only), `/mnt/android` ⇐ shared
  storage, and the
  shared-storage folders with
  a standard name on both sides (Download→`/root/Downloads`, Documents,
  Pictures, Music, Movies→`/root/Videos`, plus non-XDG DCIM; all
  writable by default — they exist to be saved into). The convention the
  two halves express: **your own folders live in your home; the phone's
  storage is a mount** — hence `/root/...` for the per-directory binds
  and `/mnt/...` for the wholesale one. `/home/android` was the earlier
  default and was wrong twice over: it named a home for a user that
  doesn't exist (the in-rootfs user is root, home `/root`), and the guest
  path is persisted per install, so changing it is a defaults-only change
  — an install bound at the old path keeps it. The root bind's guest name
  is `/android_root` rather than bare `/android` for the same
  "read it as the wrong thing" reason: `/android` parses as *the Android
  side's storage*, which is what `/mnt/android` already is, whereas this
  is the whole Android filesystem.
  Unbound common dirs (suppressed when **either** the guest path or the
  host path is already bound — the host half is what stops a pre-existing
  `/home/android` install from being offered `/mnt/android` as a second
  exposure of the same directory, and the guest half what stops an
  Add that `validate` would reject on tap; host dirs that verifiably
  don't exist are skipped too) render below the active binds as
  suggestion cards with a one-tap accent Add that carries the
  suggestion's default RO-ness (flagged on the card). Suggestion cards
  show both ends of the pair, exactly as an active bind's card does —
  which Android directory a suggestion would expose is the half worth
  reading before tapping Add (`/android_root` ⇐ `/` hands over the whole
  Android root). A typed guest path may start
  with `~`/`~/`; the save handler expands it to `RootfsEnv.GUEST_HOME`
  (`/root`) so persisted binds stay absolute. Two modes: editing an
  existing install's metadata (from `DistroInfoActivity`, gated to
  READY/FAILED so edits don't race the service's metadata writes;
  FAILED included because editing binds is how a user recovers a
  fail-closed slot), or round-tripping a JSON list via activity result
  (from `InstallActivity`, pre-install). Shows a grant notice with a
  settings deep link whenever the all-files grant is missing.
  The list scrolls between the intro text and a bottom-anchored "Add
  bind" button (weighted `ScrollView`, so they never overlap — every
  card can be scrolled fully clear of the button). The scroll view has
  a vertical fading edge: without it a card clipped at the viewport's
  bottom edge sits flush against the solid button and reads as hidden
  *behind* it — twice misfiled as a layout bug.
- `DirectoryPickerActivity` — minimal in-app browser over real paths
  for picking host dirs (deliberately not SAF; see above). Host paths
  can also be typed, which matters when the grant isn't given yet and
  the picker can't list shared storage.

## Testing

- Unit: `app/src/test/.../InstallationExternalBindsTest.kt` (metadata
  parse/round-trip/validator, `readOnly` defaults) and
  `TawcrootBindSpecsTest.kt` (RO external binds emit `:ro`, writable
  stay 2-field), `./gradlew :app:testDebugUnitTest`.
- Integration: **deliberate coverage gap.** There used to be a full
  lifecycle test (`tests/integration/tests/external_binds.rs`, deleted
  2026-07) covering invalid-binds reject, metadata edits taking effect
  on the next spawn, both-direction shared-storage round-trip,
  revoked-grant fail-closed, and contents surviving uninstall. It was
  removed on purpose: it performed a real multi-GB distro install
  through the dev cache proxy and flipped the persistent
  MANAGE_EXTERNAL_STORAGE appop (`appops set --uid me.phie.tawc ...`),
  which broke the app for later tests/sessions whenever it died
  mid-run. Policy now: integration tests must not install distros, hit
  the cache proxy, or mutate persistent app/device state. If bind
  coverage is wanted again, it needs a design that spawns into a
  fabricated (KB-scale) slot and injects the grant state without
  appops. To exercise this manually: install a disposable slot with
  binds, run the shared-storage round-trip from the old test by hand,
  and flip the grant in Android settings.
