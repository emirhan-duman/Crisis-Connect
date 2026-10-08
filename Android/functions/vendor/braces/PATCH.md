# Local braces security backport

This package starts from the published `braces@3.0.3` source (MIT license).
It backports depth limits from [micromatch/braces#72](https://github.com/micromatch/braces/pull/72/files)
(final proposal commit `28d440b5dd449dbf1fe6f3506cf94ecca4d02660`)
for [GHSA-vfj7-8cjw-p6xm](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm).
The upstream pull request was closed without a release as of 2026-10-08.

`3.0.4-crisis.0` identifies this repository's patched fork; it is not an
upstream npm release. Keep the local override until an official fixed release
is available and verified with the Functions and emulator tests.
