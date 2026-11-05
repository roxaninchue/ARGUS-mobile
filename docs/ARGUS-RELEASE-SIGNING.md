# Argus Android release signing

## Identity

- Android application ID: `com.blazeinchue.argus`
- Kotlin namespace: `com.blaze.assistant` (intentionally retained to avoid a broad source-package migration)
- Release alias: `argus-release`
- Key format: PKCS#12

This is a new Android application identity. Existing Blaze installs remain separate and cannot be upgraded in place by Argus. Do not reuse the legacy Blaze key or create a v3 signing lineage from it.

## Create the production key (on a trusted offline machine)

Run the following and enter a strong, unique keystore password when prompted:

```sh
umask 077
keytool -genkeypair -v \
  -keystore argus-release.p12 \
  -storetype PKCS12 \
  -alias argus-release \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Argus Release, O=Blazeinchue"
```

Keep at least two encrypted, access-controlled backups in separate locations. Never put the keystore, passwords, private key, or an exported private-key file in Git, GitLab/GitHub artifacts, chat, issue trackers, or a CI log. The public certificate may be distributed for verification, but it cannot sign updates.

## Configure a trusted release build

Set these environment variables only in a trusted local release environment or a protected CI secret store:

- `ARGUS_KEYSTORE_FILE`: absolute path to `argus-release.p12`
- `ARGUS_KEYSTORE_PASSWORD`: keystore password
- `ARGUS_KEY_ALIAS`: `argus-release`
- `ARGUS_KEY_PASSWORD`: private-key password

Gradle configures the `release` build type with this key only when all four variables are present. If only some are present, configuration fails closed. If none are present, release output remains unsigned, which is the expected behavior for public build/test CI. Do not add signing secrets to ordinary CI variables or expose them to untrusted merge-request pipelines.

## Verify a signed release

```sh
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --verbose --print-certs path/to/Argus.apk
$ANDROID_HOME/build-tools/34.0.0/aapt2 dump badging path/to/Argus.apk | grep '^package:'
sha256sum path/to/Argus.apk
```

Confirm the package is `com.blazeinchue.argus`, the signer certificate SHA-256 matches the offline-held Argus certificate, and the APK is not debuggable. Record the certificate fingerprint in the private release inventory. Test a fresh install and a same-key update before publishing.

The GitLab CI throwaway test signer is not a production identity. Never publish its APK as an official release.
