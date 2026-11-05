# Home sequence and composer branch reconciliation

**Date:** 2026-11-05  
**Target:** `main`  
**Branches:** `feat/home-sequence` and `feat/ui-composer`

## Resolution

Both feature branches were compared against their common ancestor
`21de64812f6f1d36bcbaffd91e1c221b2edd7b06` and the current `main`
(`e83411002cb3c2d435a598a9d85a7cc9fa8cbfee`).

The review found that the current `main` tree already contains the home and
composer work from these branches, in many cases with later implementations.
The home branch's older versions would roll back current ARGUS changes if
applied wholesale. The composer branch is also an earlier iteration than the
composer implementation already present on `main`.

The reconciliation therefore keeps the current `main` source tree unchanged
and records both feature branch tips as merged parents. In particular, it
preserves the current package identity, signing setup, version 0.52.1,
ARGUS branding, newer agent routing and memory safeguards, and the current
composer/model-choice implementation. It deliberately does not restore the
feature branches' older 0.48.0 version metadata or older workflow/build
configuration.

## Verification

This record captures the source reconciliation decision. The GitHub Actions
build, unit-test, and home UI emulator checks must pass on the resulting
integration commit before it is promoted to `main`.
