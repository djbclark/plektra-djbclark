#!/usr/bin/env bash
# fast-disk.sh — run as root right after checkout in a Hyades job. Moves the workspace and the
# Gradle home off the job's virtiofs root (small-file operations at 0.03-0.2x of a laptop) onto an
# ext4 image on a loop device. Same commit, 2026-10-14: 458 s as-is, 393 s on a guest tmpfs, 324 s
# on the loop image. Not /dev/shm (noexec: aapt2 would not run); not podman --tmpfs (served by the
# host through the same virtiofs). Details: docs/comparison.md and docs/hyades-disk-research.md in
# https://forgejo.mit.edu/djbclark/loopback-port-shim
set -euo pipefail

img=/root/fast.img
fast=/mnt/fast
command -v mkfs.ext4 > /dev/null || { apt-get update -qq && apt-get install -y -qq e2fsprogs; }
truncate -s 40G "$img"
mkfs.ext4 -q -F -E lazy_itable_init=1,lazy_journal_init=1 "$img"
mkdir -p "$fast"
mount -o loop,noatime "$img" "$fast"
mkdir -p "$fast/ws" "$fast/gradle"
cp -a "$GITHUB_WORKSPACE/." "$fast/ws/"
# Later steps start in $GITHUB_WORKSPACE, so they land on the loop image.
mount --bind "$fast/ws" "$GITHUB_WORKSPACE"
echo "GRADLE_USER_HOME=$fast/gradle" >> "$GITHUB_ENV"
df -hT "$GITHUB_WORKSPACE" "$fast"
