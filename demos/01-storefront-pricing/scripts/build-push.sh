#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="${IMAGE:-quay.io/bharathappali/jafra-demo:01-storefront-pricing}"
PUSH="${PUSH:-true}"
BUILDER="${BUILDER:-podman}"

cd "${ROOT}"

if [[ "${BUILDER}" == "buildx" ]]; then
    if [[ "${PUSH}" == "true" ]]; then
        docker buildx build --platform linux/amd64,linux/arm64 -f Containerfile -t "${IMAGE}" --push .
    else
        docker buildx build --platform linux/amd64,linux/arm64 -f Containerfile -t "${IMAGE}" .
    fi
    echo "${IMAGE}"
    exit 0
fi

if [[ "${BUILDER}" != "podman" ]]; then
    echo "BUILDER must be podman or buildx" >&2
    exit 1
fi

podman manifest rm "${IMAGE}" >/dev/null 2>&1 || true
podman manifest create "${IMAGE}"
podman build --platform linux/amd64,linux/arm64 --manifest "${IMAGE}" -f Containerfile .
if [[ "${PUSH}" == "true" ]]; then
    podman manifest push --all "${IMAGE}" "docker://${IMAGE}"
fi
echo "${IMAGE}"
