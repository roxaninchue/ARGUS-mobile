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

`work/toolchain/env.sh` exports `JAVA_HOME`, `ANDROID_HOME`, `GRADLE_USER_HOME=work/toolchain/gradle-home` and `AAPT2`.

**Memory:** `/` and `/tmp` are RAM-backed (overlay on tmpfs) in this sandbox. Keeping the Gradle cache (~2 GB) and the
build copy there left ~3 GB for Gradle and the daemon was OOM-killed. Everything heavy lives on the workspace disk
(`work/toolchain/gradle-home`, `work/toolchain/bb`). Note: `pkill -f GradleDaemon` inside a shell command also kills
that shell (its own command line matches); use `pkill -f '[G]radleDaemon'`.

## Scripts

- `work/toolchain/gradle.sh <log> <tasks…>` — copies `work/blaze` (without `.git`, `build/`) to `work/toolchain/bb`
  and runs Gradle there with the CI flags (`-Xmx3g`, in-process Kotlin, the aapt2 override), so build outputs never
  land in the git tree.
  Run it detached (`nohup … &`) and poll the log; a cold `compileDebugKotlin` takes ~12 min on 2 CPUs.
  It leaves out the paths the CI mirror never has (`gateway/`, `keystore/`, `HANDOFF*.md`, `STATUS.md` and the
  private-repo workflows, the `EXCL` list of `work/gh_push.py`), because `OwnerName19Test` and `UpdateKeysTest` scan the
  tree and fail on them by design.
- `work/toolchain/jdk21-tests.gradle` — init script that runs the unit-test JVM on JDK 21 (`work/toolchain/jdk21`,
  Temurin aarch64), as CI does. On JDK 17, `LocalLanguagesTest.cuttingNeverSplitsALetterOrAnEmoji` fails: JDK 17's
  `BreakIterator` splits ZWJ emoji sequences; JDK 20+ follows Unicode extended grapheme clusters. The build stays on 17.
- `work/toolchain/units.py` — totals and failures from `bb/app/build/test-results/testDebugUnitTest/*.xml` (the CI summary).
- `work/toolchain/golibs.sh` — builds the two Go libraries into `app/src/main/jniLibs/*` exactly like `mobile-build.yml`.

Typical sequence:

```
work/toolchain/golibs.sh
work/toolchain/gradle.sh work/toolchain/logs/unit.log --init-script work/toolchain/jdk21-tests.gradle :app:testDebugUnitTest --continue
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

## Robolectric native graphics on aarch64 (screenshot tests)

`app/build.gradle.kts` runs every Robolectric test with `robolectric.graphicsMode=NATIVE`, and Robolectric 4.13's native
runtime has **no Linux aarch64 build** ("The Robolectric native runtime is not supported on Linux (aarch64)"). Tests
that draw (Compose rules, activities, every *ShotsTest) therefore run on an **x86-64 JVM under qemu-user**:

```
sudo dpkg --add-architecture amd64 && sudo apt-get update
sudo apt-get install -y qemu-user libc6:amd64 libstdc++6:amd64 zlib1g:amd64 libgcc-s1:amd64
# x86-64 Temurin 17 unpacked in work/toolchain/jdk17-x64; wrapper work/toolchain/qemu-java.sh
BLAZE_SHOTS=1 work/toolchain/gradle.sh /tmp/shots.log --init-script work/toolchain/qemu-tests.gradle :app:testDebugUnitTest --tests '…ShotsTest'
```

`qemu-tests.gradle` only sets the Test tasks' `executable` to the wrapper (nothing in the repo changes). It is slow
(emulated JIT), so run the native aarch64 suite first and only the drawing tests under qemu.

## Low-RAM gate

The gate (`tools/smoke/run-smoke.sh`, `lowram-smoke.yml`) needs an x86-64 Android emulator with KVM. This sandbox has
no `/dev/kvm` and is aarch64, so the gate **cannot run here**; run it on the GitHub mirror (`smoke/**` branches) or
any x86-64 machine with KVM.
