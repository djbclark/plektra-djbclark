#!/usr/bin/env bash

set -euo pipefail

usage() {
  printf 'Usage: %s <version-name> <version-code> [github-repository] [--variant full|no-accessibility] [--output-apk PATH] [--skip-unit-tests]\n' "$0" >&2
  exit 2
}

fail() {
  printf 'ERROR: %s\n' "$1" >&2
  exit 1
}

[[ $# -ge 2 ]] || usage

version_name=$1
version_code=$2
shift 2
github_repository=pkb-rocks/plektra
if [[ $# -gt 0 && "$1" != --* ]]; then
  github_repository=$1
  shift
fi
variant=full
output_apk=
skip_unit_tests=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --variant)
      [[ $# -ge 2 ]] || usage
      variant=$2
      shift 2
      ;;
    --output-apk)
      [[ $# -ge 2 ]] || usage
      output_apk=$2
      shift 2
      ;;
    --skip-unit-tests)
      skip_unit_tests=true
      shift
      ;;
    *) usage ;;
  esac
done
case "$variant" in
  full) flavor=stable; task_flavor=Stable ;;
  no-accessibility) flavor=stableNoAccessibility; task_flavor=StableNoAccessibility ;;
  *) fail "unknown APK variant: $variant" ;;
esac
[[ "$version_code" =~ ^[1-9][0-9]*$ ]] || fail "version-code must be a positive integer"
[[ "$github_repository" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || \
  fail "github-repository must have the form owner/repository"

root_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
apk="$root_dir/app/build/outputs/apk/$flavor/release/app-$flavor-release-unsigned.apk"
sha_file="$apk.sha256"
sdk_root=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}
apksigner="$sdk_root/build-tools/36.0.0/apksigner"
apkanalyzer="$sdk_root/cmdline-tools/latest/bin/apkanalyzer"

[[ -x "$apksigner" ]] || fail "Android Build Tools 36.0.0 apksigner is unavailable"
[[ -x "$apkanalyzer" ]] || fail "Android SDK apkanalyzer is unavailable"

if [[ -n "$output_apk" ]]; then
  [[ "$output_apk" = /* ]] || output_apk="$PWD/$output_apk"
  [[ ! -e "$output_apk" ]] || fail "output already exists: $output_apk"
  mkdir -p "$(dirname -- "$output_apk")"
fi

cd "$root_dir"
gradle_args=(
  -PPLEKTRA_UNSIGNED_RELEASE_BUILD=true
  -PPLEKTRA_VERSION_CODE="$version_code"
  -PPLEKTRA_VERSION_NAME="$version_name"
  -PPLEKTRA_GITHUB_REPOSITORY="$github_repository"
)
if [[ "$skip_unit_tests" == false ]]; then
  ./gradlew ":app:test${task_flavor}DebugUnitTest" "${gradle_args[@]}"
else
  printf 'Skipping unit tests; use only after the relevant checks have passed.\n'
fi
./gradlew ":app:assemble${task_flavor}Release" "${gradle_args[@]}"

[[ -f "$apk" ]] || fail "Gradle did not create the expected unsigned APK"

if "$apksigner" verify "$apk" >/dev/null 2>&1; then
  fail "Gradle output is already signed"
fi

actual_app_id=$("$apkanalyzer" manifest application-id "$apk")
actual_version_code=$("$apkanalyzer" manifest version-code "$apk")
actual_version_name=$("$apkanalyzer" manifest version-name "$apk")
actual_debuggable=$("$apkanalyzer" manifest debuggable "$apk")

[[ "$actual_app_id" == "rocks.pkb.plektra" ]] || fail "unexpected application ID: $actual_app_id"
[[ "$actual_version_code" == "$version_code" ]] || fail "unexpected version code: $actual_version_code"
[[ "$actual_version_name" == "$version_name" ]] || fail "unexpected version name: $actual_version_name"
[[ "$actual_debuggable" == "false" ]] || fail "release APK is debuggable"

manifest=$("$apkanalyzer" manifest print "$apk")
service_present=false
if [[ "$manifest" == *'android.permission.BIND_ACCESSIBILITY_SERVICE'* ||
      "$manifest" == *'android.accessibilityservice.AccessibilityService'* ]]; then
  service_present=true
fi
if [[ "$variant" == "no-accessibility" && "$service_present" == true ]]; then
  fail "no-accessibility APK still declares an accessibility service"
fi
if [[ "$variant" == "full" && "$service_present" == false ]]; then
  fail "full APK is missing its accessibility service"
fi
if [[ -n "$output_apk" ]]; then
  cp -- "$apk" "$output_apk"
  apk=$output_apk
  sha_file="$apk.sha256"
fi
shasum -a 256 "$apk" >"$sha_file"

printf 'Success: unsigned Plektra stable release built and verified.\n'
printf 'application_id=%s\n' "$actual_app_id"
printf 'apk_variant=%s\n' "$variant"
printf 'version_name=%s\n' "$actual_version_name"
printf 'version_code=%s\n' "$actual_version_code"
printf 'github_repository=%s\n' "$github_repository"
printf 'apk=%s\n' "$apk"
printf 'sha256=%s\n' "$sha_file"
