# Building Blaze locally (no GitHub Actions)

Written 2026-11-01 for the 0.49.0 build, when the GitHub mirror was unavailable. The build agent's sandbox is
**Linux aarch64** (2 CPUs, ~7 GB RAM, no `/dev/kvm`), which shapes every step below.

## Toolchain (kept in the Hark workspace under `work/toolchain/`)

| What | Where | Notes |
|---|---|---|
| JDK 17 (Temurin, **aarch64**) | `work/toolchain/jdk-17.0.21+9` | `https://api.adoptium.net/v3/binary/latest/17/ga/linux/aarch64/jdk/hotspot/normal/eclipse` (CI uses 21; 17 is enough for AGP 8.5.2) |
| Android cmdline-tools | `work/toolchain/sdk/cmdline-tools/latest` | `sdkmanager` failed to fetch its manifests through the sandbox proxy, so packages were installed by hand: |
| `platforms;android-34` | `sdk/platforms/android-34` | unzip `https://dl.google.com/android/repository/platform-34-ext7_r03.zip` |
| `build-tools;34.0.0` | `sdk/build-tools/34.0.0` | unzip `build-tools_r34-linux.zip` (dir `android-14` → `34.0.0`), then **replace the x86-64 binaries** `aapt aapt2 aidl dexdump split-select zipalign` with the aarch64 static builds from `https://github.com/lzhiyong/android-sdk-tools/releases/download/34.0.3/android-sdk-tools-static-aarch64.zip` (`work/toolchain/arm64tools/build-tools/`). `apksigner` and `d8` are jars and work as is. |
| aapt2 for AGP | `-Pandroid.aapt2FromMavenOverride=work/toolchain/arm64tools/build-tools/aapt2` | the aapt2 AGP downloads from Maven is x86-64 only |
| Go 1.26.10 (linux-arm64) | `work/toolchain/go` | for `libwgproxy.so` / `libblazerouter.so` (gitignored jniLibs) |

`work/toolchain/env.sh` exports `JAVA_HOME`, `ANDROID_HOME`, `GRADLE_USER_HOME=/tmp/gh` (local disk; re-downloads after a
sandbox reset) and `AAPT2`.

## Scripts

- `work/toolchain/gradle.sh <log> <tasks…>` — copies `work/blaze` (without `.git`, `build/`) to `/tmp/bb` and runs
  Gradle there with the CI flags (`-Xmx3g`, in-process Kotlin, the aapt2 override). Build outputs stay out of the
  workspace (a workspace build dir floods the file watcher and is slow on the network filesystem).
  Run it detached (`nohup … &`) and poll the log; a cold `compileDebugKotlin` takes ~12 min on 2 CPUs.
- `work/toolchain/golibs.sh` — builds the two Go libraries into `app/src/main/jniLibs/*` exactly like `mobile-build.yml`.

Typical sequence:

```
work/toolchain/golibs.sh
work/toolchain/gradle.sh /tmp/unit.log :app:testDebugUnitTest --continue
BLAZE_SHOTS=1 work/toolchain/gradle.sh /tmp/shots.log :app:testDebugUnitTest --tests 'com.blaze.assistant.ui.ComposerShotsTest' …   # see mobile-build.yml for the list
work/toolchain/gradle.sh /tmp/rel.log :app:assembleRelease
```

## Test-signed APK (never the production key)

```
BT=$ANDROID_HOME/build-tools/34.0.0
$BT/zipalign -f -p 4 /tmp/bb/app/build/outputs/apk/release/*-unsigned.apk /tmp/aligned.apk
keytool -genkeypair -keystore /tmp/test.p12 -storetype PKCS12 -storepass testonly -keypass testonly -alias test \
  -keyalg RSA -keysize 2048 -validity 30 -dname "CN=Blaze test"
java -jar $BT/lib/apksigner.jar sign --ks /tmp/test.p12 --ks-pass pass:testonly --out work/out/blaze-<ver>-<tag>-<sha8>-test-signed.apk /tmp/aligned.apk
```

A phone with the real (production-signed) Blaze must uninstall it before installing a test-signed APK.

## Low-RAM gate

The gate (`tools/smoke/run-smoke.sh`, `lowram-smoke.yml`) needs an x86-64 Android emulator with KVM. This sandbox has
no `/dev/kvm` and is aarch64, so the gate **cannot run here**; run it on the GitHub mirror (`smoke/**` branches) or
any x86-64 machine with KVM.
