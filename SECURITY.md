# Security

This app runs a Linux container on your device and starts DSH's agent inside
it, with `DSH_PERMISSION_MODE=danger-full-access`. That is a lot of trust in one
app, so it is worth being precise about what is and is not a vulnerability here.

## Reporting

Use GitHub's private vulnerability reporting:

**https://github.com/Kaeno-Tori/tawc-dsh-for-android/security/advisories/new**

Please do not open a public issue with exploit details. If private reporting is
unavailable for any reason, open an issue that says only that you have a
security report and asks for a private channel — no details.

This is a hobby project with one maintainer and no paid support. There is no
bug bounty and no response-time promise; you will get a reply when there is
something to say.

## In scope

- Escaping the container to reach data the app is not supposed to have, or to
  reach the host Android system, in a way that the app's own design does not
  already admit (see below).
- The bootstrap path: a downloaded rootfs or package index that can be
  tampered with, or signature/checksum verification that can be bypassed.
  See `notes/installation.md` ("Bootstrap integrity").
- Leaking app-private data to another app, or to files the user did not choose
  to expose.
- Anything that lets a malicious *server* (a mirror, an npm registry, DSH's
  backend) escalate past what the app intends to grant it.

## Known properties — not vulnerabilities

These are documented limits of the design, not bugs. Reporting them is fine,
but they will be answered by pointing here.

- **There is no sandbox inside the container.** DSH's own confinement chain
  (`bwrap` → Landlock) is unavailable on Android, so DSH runs with
  full access *inside the container*. The container is the isolation boundary.
- **`tawcroot` is not a security boundary against a hostile process inside the
  container.** Upstream says so too: it is a single-process tracer, and a
  traced process can escape it. Treat the container as "running someone else's
  code with your data in reach", and treat anything the agent does inside it as
  something you asked it to do.
- **The agent can read and write anything the app's own storage holds**, and
  anything you grant through Android's file access. That is the point of it.
- **The app holds no root by default** and does not ask for it. The debug-only
  `chroot` install method does use root, and release builds do not ship it.

If you are unsure whether something is in scope, report it privately anyway and
say which part you think is the design.

## Upstream

The container runtime (`tawcroot`, `ando`, the terminal integration, the
libhybris plumbing) comes from [tawc](https://github.com/wmww/tawc). If a
problem reproduces without this fork's changes — that is, in upstream's own
tree — it belongs upstream, and please report it there too. This fork removed
the compositor and the whole display stack, so anything about Wayland,
Xwayland, or rendering is not this project's code any more.
