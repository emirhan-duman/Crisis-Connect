# Release verification

Crisis Connect release evidence lets operators verify that a downloaded Android package is the
same artifact published by this repository and inspect the software components identified inside
the release. Perform verification before distributing a build to responders or pilot devices.

## Evidence published with each Android release

The `Sign release assets` workflow runs from the protected `main` branch and publishes:

- the original `.apk` and `.aab` release assets;
- a keyless Sigstore bundle for every Android asset;
- a CycloneDX JSON software bill of materials (SBOM) generated from Gradle's resolved dependency
  graph at the release tag;
- a SHA-256 checksum manifest for the Android assets;
- a keyless Sigstore bundle for the SBOM and checksum manifest; and
- a GitHub artifact attestation that binds the SBOM to the asset digests.

The SBOM describes components selected by Gradle after dependency resolution. It does not prove
that every native binary is reproducible from this repository. In particular, the checked-in
Android MLS worker libraries and the separately produced iOS frameworks still require independent
source-to-binary provenance.

## Verify an Android asset

Install [GitHub CLI](https://cli.github.com/) and
[cosign](https://docs.sigstore.dev/cosign/system_config/installation/), then download the release:

```bash
tag=v1.2.0
gh release download "$tag" --repo emirhan-duman/Crisis-Connect --dir "release-$tag"
cd "release-$tag"
version="${tag#v}"
asset="$(find . -maxdepth 1 -type f -name '*.apk' -print -quit)"
```

Release evidence described here is available for tags created after this policy was introduced.
Older releases must be rebuilt under the current release workflow before pilot distribution.

Verify the artifact attestation recorded by GitHub:

```bash
gh attestation verify "$asset" \
  --repo emirhan-duman/Crisis-Connect
```

Verify the keyless Sigstore bundle. The certificate identity is restricted to the release workflow
on `main`, and the issuer is GitHub Actions:

```bash
cosign verify-blob \
  --bundle "${asset}.sigstore.json" \
  --certificate-identity \
    "https://github.com/emirhan-duman/Crisis-Connect/.github/workflows/sign-release-assets.yml@refs/heads/main" \
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com" \
  "$asset"
```

Check the downloaded package against the release checksum manifest:

```bash
sha256sum --check "crisis-connect-${version}.assets.sha256"
```

Use the equivalent platform command (`shasum -a 256 -c`) on systems without GNU `sha256sum`.
Verify the SBOM's own `.sigstore.json` bundle with the same cosign identity before using it for
dependency inventory or vulnerability review.

## Operational policy

- Treat a missing or failed signature, attestation, or checksum as a release-blocking event.
- Verify evidence from a clean workstation or CI runner rather than from the build machine alone.
- Record the verified tag, asset SHA-256, attestation URL, and deployment date in the pilot change
  record.
- Roll back and revoke distribution immediately if published evidence changes unexpectedly.
