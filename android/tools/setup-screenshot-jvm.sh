#!/usr/bin/env bash
# Recreate the x86-64 JVM used for screenshot tests on this aarch64 host.
#
# Why this exists: Robolectric's native graphics runtime and Paparazzi's layoutlib ship
# x86-64 binaries only. Without an x86-64 JVM there is no way to render a Compose screen to
# pixels on this machine, and the project's UX rules say plainly that an interface nobody
# has seen rendered has not been verified. Rather than accept that, the screenshot task
# forks an emulated JVM.
#
# Ordinary unit tests are unaffected — they run on the host's own aarch64 JVM at full speed.
#
# `docker export` writes the image's filesystem without ever starting a container, so this
# needs no binfmt registration and no root.
set -euo pipefail

TARGET="${SCRIBE_X86_JDK:-$HOME/.local/share/scribe/x86_64-jdk}"
IMAGE="eclipse-temurin:17-jdk"
CONTAINER="scribe-x86-jdk-export"

if [[ -x "$TARGET/opt/java/openjdk/bin/java" ]]; then
  echo "already present at $TARGET"
  exit 0
fi

command -v docker >/dev/null || { echo "docker is required to extract the rootfs" >&2; exit 1; }
[[ -x "$HOME/.local/bin/qemu-x86_64-static" ]] ||
  { echo "qemu-x86_64-static missing from ~/.local/bin" >&2; exit 1; }

docker pull --platform linux/amd64 "$IMAGE"
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
docker create --platform linux/amd64 --name "$CONTAINER" "$IMAGE" >/dev/null

mkdir -p "$TARGET"
docker export "$CONTAINER" | tar -x -C "$TARGET"
docker rm "$CONTAINER" >/dev/null

echo "extracted $(du -sh "$TARGET" | cut -f1) to $TARGET"
"$(dirname "$0")/qemu-x86/x86-jvm/bin/java" -version
