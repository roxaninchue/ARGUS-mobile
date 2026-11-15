#!/usr/bin/env bash
# Installs the Android SDK pieces this repo builds with (compileSdk 34, JDK 17). Runs once when the Codespace is created.
set -euo pipefail
SDK="${ANDROID_HOME:-$HOME/android-sdk}"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$SDK/cmdline-tools"
  cd /tmp
  curl -fsSLo clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q clt.zip && rm clt.zip
  mv cmdline-tools "$SDK/cmdline-tools/latest"
fi
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "platforms;android-34" "build-tools;34.0.0"
echo "sdk.dir=$SDK" > "${CODESPACE_VSCODE_FOLDER:-$PWD}/local.properties" 2>/dev/null || true
echo "Android SDK ready. Desktop: open the forwarded port 6080 (password: vscode)."
