#!/usr/bin/env bash
# Bounded repeat of the 2026-10-14 storage comparison. CI only; no releases.
set -euo pipefail
bench() {
  local label=$1
  shift
  echo "BENCHSTEP start $label $(date +%s.%N)"
  local rc=0
  "$@" || rc=$?
  echo "BENCHSTEP end $label $(date +%s.%N) rc=$rc"
  return "$rc"
}
prepare() {
  case "$STORAGE" in
    loop) bash .forgejo/scripts/fast-disk.sh ;;
    tmpfs)
      mkdir -p /mnt/fast
      mount -t tmpfs -o size=16g,mode=755,noatime tmpfs /mnt/fast
      mkdir -p /mnt/fast/ws /mnt/fast/gradle
      cp -a "$GITHUB_WORKSPACE/." /mnt/fast/ws/
      mount --bind /mnt/fast/ws "$GITHUB_WORKSPACE"
      echo 'GRADLE_USER_HOME=/mnt/fast/gradle' >> "$GITHUB_ENV"
      ;;
    *) return 2 ;;
  esac
}
echo "BENCH variant=$STORAGE sha=$(git rev-parse HEAD) start=$(date -u +%FT%TZ)"
bench storage prepare
cd "$GITHUB_WORKSPACE"
export GRADLE_USER_HOME=/mnt/fast/gradle
findmnt -T /mnt/fast -o TARGET,SOURCE,FSTYPE,OPTIONS
findmnt -T "$GITHUB_WORKSPACE" -o TARGET,SOURCE,FSTYPE,OPTIONS
cp /bin/true /mnt/fast/exec-proof
/mnt/fast/exec-proof
rm /mnt/fast/exec-proof
# Keep the SDK in the pinned image in both arms. Only the workspace and Gradle home move.
echo /usr/local/lib/portshim.so >> /etc/ld.so.preload
chmod +x ./gradlew
java -version
nproc
# libkrunfw can omit swap support and /proc/swaps entirely.
if [ -r /proc/swaps ]; then cat /proc/swaps; else echo "BENCH /proc/swaps absent"; fi
(
  while :; do
    awk '/^(MemTotal|MemAvailable|Shmem|SwapTotal|SwapFree):/ {printf "%s=%s ", $1, $2} END {print ""}' /proc/meminfo
    sleep 5
  done
) > /tmp/disk-ab-memory.log &
monitor_pid=$!
finish() {
  rc=$?
  kill "$monitor_pid" 2>/dev/null || true
  wait "$monitor_pid" 2>/dev/null || true
  echo "BENCH final_rc=$rc end=$(date -u +%FT%TZ)"
  df -kT / /mnt/fast /dev/shm
  du -sk "$GITHUB_WORKSPACE" "$GRADLE_USER_HOME"
  cat /tmp/disk-ab-memory.log
}
trap finish EXIT
gradle() {
  ./gradlew --init-script "$GITHUB_WORKSPACE/.forgejo/known-failures.gradle" \
    --init-script "$GITHUB_WORKSPACE/.forgejo/central-mirror.gradle" "$@"
}
bench compile gradle :app:compileStableDebugKotlin :app:compileStableDebugJavaWithJavac
bench core-modifier gradle :app:testStableDebugUnitTest --tests rocks.pkb.plektra.core.ModifierStateControllerTest
bench router-modifier gradle :app:testStableDebugUnitTest --tests rocks.pkb.plektra.inputmethod.InputEventRouterModifierE2ETest
bench forward-delete gradle :app:testStableDebugUnitTest --tests rocks.pkb.plektra.inputmethod.InputEventRouterForwardDeleteAlternativesTest
bench ime-device gradle :app:testStableDebugUnitTest --tests rocks.pkb.plektra.inputmethod.PhysicalKeyboardInputMethodServiceDeviceBehaviorTest
