# Security policy

## Reporting a vulnerability

Report privately through GitHub's
[security advisories](https://github.com/haithamassoli/NaqiHalalVideoFilter/security/advisories/new).
Please do not open a public issue for anything exploitable. Expect a first reply within a week.

## Supported versions

Only the latest release. Naqi has an in-app updater that installs from GitHub Releases, so fixes
reach existing installs without a store.

## What is in scope

Naqi is an offline media processor, so the interesting surface is narrow and mostly around the parts
that do touch the network:

- **The in-app updater** — it fetches an APK over HTTPS and hands it to the package installer. TLS
  handling and release-asset selection are in scope. Note that Naqi itself does not verify the
  download: the platform installer rejects a signing-key mismatch, and that is the whole defence.
- **Model downloads** — fetched over HTTPS and verified against a hard-coded SHA-256 before the file
  is moved into place. A way past that check is in scope.
- **The link downloader** — Naqi drives yt-dlp, which fetches and executes updated Python at
  runtime. Problems in yt-dlp itself belong upstream; how Naqi invokes it, quarantines the output
  and publishes the result is in scope.
- **Shared and imported files** — anything an untrusted `content://` URI can make the decoders,
  parsers or `queue.json` reader do.

## Out of scope

- That the app requests `REQUEST_INSTALL_PACKAGES` and `INTERNET` at all — both are documented in
  the README and load-bearing.
- The accuracy of the models. Missed or over-eager censoring is a quality issue, not a
  vulnerability — open a normal issue.
- Anything requiring physical access to an unlocked device.
