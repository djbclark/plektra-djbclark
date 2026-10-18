# GitHub Actions CI for Plektra djb (`ci/base`)

Fork-only. These files live on branch `ci/base` and are overlaid onto a target
ref by `scripts/github-test-ref`, which pushes `origin ci/run/<slug>`.

## Invocation

```bash
# From a checkout that has scripts/github-test-ref (this branch) and origin → github.com/djbclark/plektra-djbclark
scripts/github-test-ref [--no-wait] REF [GRADLE_ARGS...]
scripts/github-test-ref --release plk/main
scripts/github-install-latest [TAG]
```

Default Gradle task: `:app:testStableDebugUnitTest`.

## Signing

GitHub Actions secret `DJB_DEBUG_KEYSTORE_B64` is the base64 of this Mac's
`~/.android/debug.keystore` (same bytes as Forgejo secret of that name; cert
SHA-256 `5ca8d5fcce4bad08206eb7e112b5c9d63b35b0a8874e3d25af48e8e332a9596a`).

## Releases

Tag `djb-YYYYMMDD.HHMM-<sha8>`, assets `plektra-djb-<sha8>-stable-debug.apk` and
`.sha256`. Install with `scripts/github-install-latest` (Forgejo parallel:
`scripts/djb-install-latest`).

## Overlay rule

Every overlay commit **deletes** all other `.github/workflows/*` from the target
tree so upstream `ci.yml` / `release.yml` / `debug.yml` / `play.yml` do not run
on `ci/run/**` pushes.
