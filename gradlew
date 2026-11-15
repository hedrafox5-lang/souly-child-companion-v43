#!/usr/bin/env bash
set -e

GRADLE_VERSION="8.7"
GRADLE_CACHE_DIR="${HOME}/.souly_gradle_dist"
GRADLE_BIN="${GRADLE_CACHE_DIR}/gradle-${GRADLE_VERSION}/bin/gradle"

if command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
fi

if [ ! -x "${GRADLE_BIN}" ]; then
    mkdir -p "${GRADLE_CACHE_DIR}"
    curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o "${GRADLE_CACHE_DIR}/gradle.zip"
    unzip -q -o "${GRADLE_CACHE_DIR}/gradle.zip" -d "${GRADLE_CACHE_DIR}"
    rm -f "${GRADLE_CACHE_DIR}/gradle.zip"
    chmod +x "${GRADLE_BIN}"
fi

exec "${GRADLE_BIN}" "$@"
