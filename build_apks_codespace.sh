#!/usr/bin/env bash
set -e

echo "============================================================"
echo "🛡️ Souly Child Companion v4.3.0 — Automated APK Builder"
echo "============================================================"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_HOME}"

if [ ! -d "${ANDROID_HOME}/platforms/android-35" ]; then
    echo "⏳ Installing Android SDK Platform 35 & Build-Tools 35.0.0..."
    mkdir -p "${ANDROID_HOME}/cmdline-tools"
    if [ ! -x "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" ]; then
        curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" -o /tmp/cmdtools.zip
        unzip -q -o /tmp/cmdtools.zip -d "${ANDROID_HOME}/cmdline-tools"
        mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest"
        rm -f /tmp/cmdtools.zip
    fi
    yes | "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true
    "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
fi

echo "sdk.dir=${ANDROID_HOME}" > local.properties

if [ ! -f "app/souly-release-key.jks" ]; then
    echo "🔐 Generating Release Signing Keystore (app/souly-release-key.jks)..."
    keytool -genkeypair -v \
        -keystore app/souly-release-key.jks \
        -alias souly_shield_key \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -storepass SoulyShield2026 \
        -keypass SoulyShield2026 \
        -dname "CN=Souly Digital Citadel, OU=Parental Hub, O=Souly, L=Cairo, S=Cairo, C=EG"
fi

chmod +x ./gradlew
echo "🚀 Building All Release APK Editions (v4.3.0)..."
./gradlew assembleRelease --stacktrace

mkdir -p dist-apk
cp app/build/outputs/apk/universalApi26To35/release/app-universalApi26To35-release.apk dist-apk/Souly-Child-Companion-v4.3-Universal-Android8-to-15.apk
cp app/build/outputs/apk/legacyApi26To29/release/app-legacyApi26To29-release.apk dist-apk/Souly-Child-Companion-v4.3-Android8-to-10.apk
cp app/build/outputs/apk/modernApi30To33/release/app-modernApi30To33-release.apk dist-apk/Souly-Child-Companion-v4.3-Android11-to-13.apk
cp app/build/outputs/apk/nextGenApi34To35/release/app-nextGenApi34To35-release.apk dist-apk/Souly-Child-Companion-v4.3-Android14-to-15.apk

echo "✅ All Signed Release APKs Ready inside ./dist-apk/ :"
ls -lh dist-apk/*.apk
