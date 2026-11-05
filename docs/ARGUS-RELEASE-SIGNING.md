# Argus Android release signing

## Identity

- Android application ID: `com.blazeinchue.argus`
- Kotlin namespace: `com.blaze.assistant` (intentionally retained to avoid a broad source-package migration)
- Release alias: `argus-release`
- Key format: PKCS#12

This is a new Android application identity. Existing Blaze installs remain separate and cannot be upgraded in place by Argus. Do not reuse the legacy Blaze key or create a v3 signing lineage from it.

## Existing production key

The Argus production key has already been generated on the trusted Ubuntu machine:

- Keystore: `$HOME/ArgusSigning/argus-release.p12`
- Alias: `argus-release`
- Format: PKCS#12
- Key: 4096-bit RSA, SHA256withRSA
- Certificate subject: `CN=Argus Release, O=Argus, L=Ndola, ST=Copperbelt, C=ZM`
- SHA-256 certificate fingerprint: `FB:5F:E1:FB:52:C2:F9:71:2C:0F:EB:12:49:DF:2D:CD:82:AB:D5:13:C2:B3:03:3C:5B:6A:DD:F6:8F:B4:78:C7`
- Validity: 5 November 2026 through 23 March 2054

Use this existing key; do not generate another one, reuse the legacy Blaze key, or create a signing lineage. Keep the private `.p12` on the Ubuntu machine with its password stored locally/in a password manager and maintain encrypted offline backups. The public certificate (`argus-release-cert.pem`) can be distributed for verification; it cannot sign APKs. Never commit or upload the private keystore or passwords.

## Configure a trusted release build

For a local release on the Ubuntu machine, set the file and alias, then read both passwords interactively so they are not written into shell history:

```sh
export ARGUS_KEYSTORE_FILE="$HOME/ArgusSigning/argus-release.p12"
export ARGUS_KEY_ALIAS="argus-release"
read -rsp "Keystore password: " ARGUS_KEYSTORE_PASSWORD; echo; export ARGUS_KEYSTORE_PASSWORD
read -rsp "Private-key password: " ARGUS_KEY_PASSWORD; echo; export ARGUS_KEY_PASSWORD
./gradlew :app:assembleRelease
unset ARGUS_KEYSTORE_PASSWORD ARGUS_KEY_PASSWORD
```

For a protected CI release, set these same variables only in a trusted protected secret store; never expose them to untrusted merge-request pipelines.

Required variables:

- `ARGUS_KEYSTORE_FILE`: `$HOME/ArgusSigning/argus-release.p12`
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
