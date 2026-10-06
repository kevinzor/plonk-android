#!/usr/bin/env bash
# Build the Plonk APK. Usage: [FETCH_BUNDLE=1] [BUNDLE_FROM=<game>/public] scripts/build.sh [debug|release]
#
# FETCH_BUNDLE=1 first refreshes the game files packed into the APK (tools/fetch-bundle.mjs, see
# docs/BUNDLE.md) from the live server, or from a local public/ dir when BUNDLE_FROM is set.
# Without it the existing bundle is kept; an empty bundle is fine (the app streams everything).
#
# The build box also runs the live game server, so Gradle runs inside a systemd scope with a
# hard memory cap and low CPU priority: if the build runs out of room, the BUILD is killed,
# never the game. Temp files go to disk (on this box /tmp is RAM).
set -euo pipefail
cd "$(dirname "$0")/.."
KIND="${1:-release}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/root/android-sdk}"
SCRATCH="${PLONK_BUILD_TMP:-/root/plonk-android-scratch/tmp}"
mkdir -p "$SCRATCH"
export GRADLE_OPTS="-Xmx256m -Djava.io.tmpdir=$SCRATCH"
echo "sdk.dir=$ANDROID_HOME" > local.properties

PROPS=()
if [[ "$KIND" == release ]]; then
  SIGNING_ENV="${PLONK_SIGNING_ENV:-/root/plonk-android-keys/signing.env}"
  if [[ -f "$SIGNING_ENV" ]]; then
    set -a; source "$SIGNING_ENV"; set +a
    PROPS+=("-PSOLANA_MOBILE_KEYSTORE_PATH=$SOLANA_MOBILE_KEYSTORE_PATH" "-PSOLANA_MOBILE_KEYSTORE_ALIAS=$SOLANA_MOBILE_KEYSTORE_ALIAS")
  else
    echo "WARNING: no signing env at $SIGNING_ENV; release APK will be unsigned" >&2
  fi
fi
TASK=assembleRelease; [[ "$KIND" == debug ]] && TASK=assembleDebug

if [[ "${FETCH_BUNDLE:-0}" == 1 ]]; then
  BUNDLE_ARGS=()
  [[ -n "${BUNDLE_FROM:-}" ]] && BUNDLE_ARGS+=(--from-dir "$BUNDLE_FROM")
  nice -n 19 node tools/fetch-bundle.mjs "${BUNDLE_ARGS[@]}"
elif command -v node >/dev/null; then
  node tools/fetch-bundle.mjs --status
fi

# One Gradle build at a time on this box (each may use ~2.6 GB): later callers wait for the lock.
LOCK="${PLONK_BUILD_LOCK:-/root/plonk-android-scratch/gradle.lock}"
mkdir -p "$(dirname "$LOCK")"
exec 9>"$LOCK"
flock 9

CAP=(systemd-run --scope --quiet -p MemoryMax=2600M -p MemorySwapMax=0 -p CPUWeight=20 --)
command -v systemd-run >/dev/null || CAP=()
"${CAP[@]}" nice -n 19 ./gradlew --no-daemon --console=plain -Dorg.gradle.jvmargs="-Xmx1280m -XX:MaxMetaspaceSize=512m -Djava.io.tmpdir=$SCRATCH -Dfile.encoding=UTF-8" "${PROPS[@]}" "$TASK"

OUT=app/build/outputs/apk/$KIND
ls -la "$OUT"/*.apk
