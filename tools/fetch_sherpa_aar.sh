#!/usr/bin/env bash
# Fetch the pinned sherpa-onnx Android runtime (AAR) into app/libs/.
#
# Why this and not a Gradle coordinate: sherpa-onnx is not on Maven Central (repo1 returns 404
# for group com.k2fsa) and JitPack only carries the 1.10.x line, so the release asset is the
# only current source. Why not committed: it is a 48 MB binary that changes only when we move
# the engine version deliberately.
#
# Why pinned by sha256: the runtime version decides which models load at all, and the model
# files in app/src/main/java/fi/ak/smartvoice/asr/AsrModels.kt are pinned to a specific
# artifact revision. An unverified download would let the runtime drift away from the model
# without anything failing loudly.
#
# Usage:  tools/fetch_sherpa_aar.sh [--force]
set -euo pipefail

VERSION="1.13.8"
SHA256="633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VERSION}/sherpa-onnx-${VERSION}.aar"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST_DIR="${REPO_ROOT}/app/libs"
DEST="${DEST_DIR}/sherpa-onnx-${VERSION}.aar"

if [[ "${1:-}" == "--force" ]]; then
    rm -f "${DEST}"
fi

if [[ -f "${DEST}" ]] && echo "${SHA256}  ${DEST}" | sha256sum -c --status -; then
    echo "ok: ${DEST} already present and verified (sherpa-onnx ${VERSION})"
    exit 0
fi

mkdir -p "${DEST_DIR}"
echo "downloading sherpa-onnx ${VERSION} (~48 MB) -> ${DEST}"
curl -fL --retry 3 --retry-delay 2 -o "${DEST}.part" "${URL}"
mv "${DEST}.part" "${DEST}"

if ! echo "${SHA256}  ${DEST}" | sha256sum -c --status -; then
    echo "FAIL: sha256 mismatch for ${DEST}" >&2
    echo "  expected ${SHA256}" >&2
    echo "  actual   $(sha256sum "${DEST}" | cut -d' ' -f1)" >&2
    echo "The pinned runtime changed upstream or the download is corrupt. Do not proceed:" >&2
    echo "re-check the release, then update VERSION+SHA256 here and the pins in AsrModels.kt." >&2
    exit 1
fi

echo "ok: ${DEST} verified (sha256 ${SHA256:0:16}…)"
