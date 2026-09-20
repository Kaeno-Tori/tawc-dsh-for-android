What changed, and why.

Please say what you actually verified rather than what should work — this
project is written overwhelmingly by agents, and a plausible-sounding "should
be fine" is the failure mode to avoid. If you ran something, paste what it
printed.

Before opening:

- `./gradlew :app:testDebugUnitTest` and `./gradlew :app:lintDebug` — lint is
  expected to be at its existing issue count, not merely "no new errors".
- If the change touches a build dependency, host package, vendored repo, env
  var, or toolchain version: `notes/building.md` is updated in the same change
  (see AGENTS.md, "Operating Rules").
- No version numbers written outside `app/build.gradle.kts`; nothing from the
  app-store tooling reintroduced (it was removed on purpose — see
  `notes/release.md`).
