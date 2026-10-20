# Releasing

Plektra uses calendar versions.

- Stable: `YY.MM`, for example `26.10`
- Stable correction: `YY.MM.N`, for example `26.10.1`
- Nightly: `YY.MM-nightly.DD.HHmmssZ`
- Nightly tag: `nightly/vYY.MM-nightly.DD.HHmmssZ`

Canonical Stable and Nightly APKs are signed with separate hardware-backed PIV keys. Play builds use separate app IDs and separate hardware-backed upload keys; Google Play applies its own app-signing key to delivered packages.

Release work must verify the package name, version, channel icon, update source, signature, SBOM, source archive, checksums, and release notes before publication. Signing and publication remain explicit maintainer actions.

## Local Google Play API access

The local Play helper uses the `Plektra Play` Cloud project (`pkb-rocks-plektra-play`).
Each channel has its own service account and access to its own app:

| Channel | Play package | Service account |
| --- | --- | --- |
| Nightly | `rocks.pkb.plektra.unsovereign.nightly` | `plektra-nightly-release` |
| Stable | `rocks.pkb.plektra.unsovereign` | `plektra-stable-release` |

Configure both accounts with app-level permissions to read app and quality information,
manage drafts, release to test and production tracks, manage testers, edit store
listings, manage policy declarations, and manage deep links. Google's production
permission also includes device exclusions and Play App Signing. Store-listing
permission also includes pricing and in-app product configuration.
Do not grant account-wide permissions, administrator access, financial-data
access, order management, or review-response permissions.

Store the JSON keys outside the checkout at:

```text
~/.config/pkb-rocks/google-play/plektra-nightly-service-account.json
~/.config/pkb-rocks/google-play/plektra-stable-service-account.json
```

The containing directory must have mode `0700`, and the key file must have mode
`0600`. The helper checks the account identity and these permissions before
authentication. The API credential is separate from the AAB upload signing key.

### Hardware-backed upload keys

Both RSA4096 upload keys were generated on the maintainer's YK1 token:

| Channel | PIV slot | PIN policy | Touch policy |
| --- | --- | --- | --- |
| Stable | `83` | Once per session | Every signature |
| Nightly | `84` | Never | Never |

Nightly can sign unattended on the local Mac while the token is connected.
Stable requires the maintainer's PIN and physical touch. The private keys remain
on the token. Existing APK signing keys retain their original policies.

Public certificates and the signing manifest are stored at
`~/.config/pkb-rocks/google-play/upload-signing/`. The manifest records the token
serial, certificate fingerprints, policies and PKCS#11 aliases. Verify the
expected token and certificate when configuring bundle signing; do not rely on
a fixed PKCS#11 slot index. The bundle-signing helper uses these identities; each channel is registered in
Play by its first signed bundle upload.

Read the current tracks and bundles:

```sh
uv run --script scripts/play-release.py --channel nightly status
uv run --script scripts/play-release.py --channel stable status
```

This creates a temporary API edit to read the app's state, then discards that
edit. It does not commit app changes.

Prepare release notes as a JSON object mapping language codes to text, with at
most 500 characters per language. Preview an upload without accessing Google:

```sh
uv run --script scripts/play-release.py --channel nightly upload path/to/play-nightly.aab \
  --track alpha --name 26.10-nightly.07.214711Z --notes path/to/notes.json
```

After reviewing the bundle, account, track, notes, and SHA-256, add `--execute`
to upload the bundle and prepare a draft test release in an API edit. The helper
validates the edit and records it under `~/.local/state/pkb-rocks/google-play/`.
It preserves existing releases, fills an empty draft placeholder, and refuses
to replace a draft that already contains release information.

`--channel` selects the package and matching service-account key together.
The default remains Nightly. `play-nightly.py` is a compatibility symlink to the
same helper, so existing Nightly commands keep working.

The edit remains **uncommitted** and expires at the time reported by Google.
Saving it to Play Console requires a separate edit commit after review. The
helper does not submit a release for review or publish it. Do not edit the same
app in Play Console during an API upload; console changes can invalidate API
edits. The helper never retries a failed mutation automatically.

## Preparing an unsigned Nightly

Build the canonical release APK locally with a single UTC timestamp:

```sh
timestamp=$(date -u +'%Y%m%d.%H%M%S')
scripts/build-nightly-release-unsigned.sh 26.10 \
  ".local-work/releases/26.10/plektra-nightly-unsigned.apk" \
  --timestamp "$timestamp"
```

The script checks the application ID, CalVer version, version code, non-debuggable build and absence of an APK signature. Release APKs include a CycloneDX SBOM generated from that variant's runtime dependencies.

When the relevant unit checks have already passed, `--skip-unit-tests` builds without repeating the complete release unit suite. Device acceptance remains a separate check.

The next Nightly's user-facing notes are in [next-nightly.md](../.github/release-templates/next-nightly.md). Keep [nightly-highlights.md](../.github/release-templates/nightly-highlights.md) within F-Droid's 500-character limit. The first release's notes remain in [first-nightly.md](../.github/release-templates/first-nightly.md). Prepare a source archive from the exact release commit, extract the embedded SBOM and record SHA-256 checksums alongside the unsigned APK.

Sign the reviewed APK with `scripts/sign-plektra-nightly-apk-piv.sh` in the maintainer's interactive terminal. Final verification and publication use the signed APK and its checksum. Record its exact source commit and CalVer tag in the release metadata.

## Building and signing Play bundles

Install Google's `bundletool.jar` at
`~/.local/share/agent-tools/android/bundletool.jar`. Use JDK 21 and the installed
YubiKey Manager Python runtime, which provides the official Yubico SDK.

```sh
scripts/build-play-bundle-unsigned.sh nightly 26.10 /path/to/nightly-unsigned.aab 20261007.214711
/opt/homebrew/opt/ykman/libexec/bin/python scripts/sign-play-bundle-piv.py \
  --channel nightly /path/to/nightly-unsigned.aab /path/to/nightly.aab

scripts/build-play-bundle-unsigned.sh stable 26.10 /path/to/stable-unsigned.aab
/opt/homebrew/opt/ykman/libexec/bin/python scripts/sign-play-bundle-piv.py \
  --channel stable /path/to/stable-unsigned.aab /path/to/stable.aab
```

Stable signing runs in the maintainer's Terminal for the PIN and touch. Nightly
signing requires neither. No PIN is stored or passed in command arguments.
The JDK `JarSigner` builds the JAR signature; the official Yubico SDK performs
the RSA operation on the token. This avoids the login requested by Java's
PKCS#11 keystore even for a PIN Never key.

The build checks the package, version, non-debuggable manifest and matching
embedded SBOM. Signing checks the channel, token, certificate and hardware
policies. Every bundle entry is verified against the expected upload certificate
before the signed output is saved. Existing output files are not overwritten.

A first bundle upload registers its signing certificate as the app's upload
certificate. Check its SHA-256 in Play Console's App signing page against the
local manifest. Google's app-signing certificates are separate.

A new API edit created by the same account invalidates its previous edit. The
status helper therefore refuses to create another edit while an upload is
pending locally. Save or explicitly discard that upload before running status.
For initial draft apps, Google may reject `changesNotSentForReview=true`; use
Play Console's **Save as draft** to retain the draft without submitting it for
review. Keep the upload certificate and saved draft status as verification
records before proceeding with publication.

## Stable APK variants

The `stable` and `stableNoAccessibility` flavors share the canonical Stable
package ID, version and PIV signing configuration. They replace each other while
preserving app data. The reduced variant omits the Clicks launcher-button
accessibility service; normal keyboard features and TalkBack remain available.

Build both variants with the same version and version code:

```sh
scripts/build-stable-release-unsigned.sh 26.10 26100000 --variant full --output-apk /path/to/Plektra-26.10-full-unsigned.apk
scripts/build-stable-release-unsigned.sh 26.10 26100000 --variant no-accessibility --output-apk /path/to/Plektra-26.10-no-accessibility-unsigned.apk
```

The helper runs each variant's Debug unit tests and verifies the unsigned Release
APK's identity, version and service presence. Use `--skip-unit-tests` only after
the corresponding suite has passed for the same source and version parameters.
Both APKs contain a variant-specific runtime SBOM. Sign both with
`scripts/sign-plektra-stable-apk-piv.sh`; final asset names are
`Plektra-VERSION-full.apk` and `Plektra-VERSION-no-accessibility.apk`.

## Nightly APK variants

The `nightly` and `nightlyNoAccessibility` flavors share the canonical Nightly
package ID, version and PIV signing configuration. They replace each other when
installed; they do not create separate apps. Build both with the same timestamp:

```sh
scripts/build-nightly-release-unsigned.sh 26.10 /path/to/Plektra-VERSION-full.apk --timestamp 20261008.100000
scripts/build-nightly-release-unsigned.sh 26.10 /path/to/Plektra-VERSION-no-accessibility.apk --variant no-accessibility --timestamp 20261008.100000
```

Replace `VERSION` with the generated `full_version`. The release assets must be
named exactly `Plektra-VERSION-full.apk` and
`Plektra-VERSION-no-accessibility.apk`. Both use
`scripts/sign-plektra-nightly-apk-piv.sh` and the same public signing lineage.
The build helper verifies the unsigned APK's package, version and service
presence before copying it to the requested output.

The reduced APK omits the Clicks accessibility service from its merged manifest
and hides its setup and launcher-button remapping controls. Normal keyboard
features and TalkBack support remain available. Clicks launcher-button
interception requires the full APK.

The installed build selects its own variant during GitHub update checks. Missing,
ambiguous or insecure matching assets leave the release-page link available
without a direct APK download. The full build also accepts the previous exact
`Plektra-VERSION-github.apk` filename; the reduced build never accepts that file.

For the transition, first publish a full-only Nightly containing the new selector,
then offer both variants. Older installed builds choose the first APK in GitHub's
asset list: keep the full APK first in every two-APK release and verify the asset
order returned by the GitHub API before publication. Never publish both the
legacy and the new full filename together; that is an ambiguous full download.
