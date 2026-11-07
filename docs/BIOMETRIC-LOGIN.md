# Biometric login, keys and reinstall: what survives what (0.52.1)

Why this doc: testers reinstalled test APKs signed with a new throwaway key every build, so every install wiped the
app's data and every "Unlock with fingerprint/face" key. This is what Android actually guarantees, and what Argus does.

## 1. BiometricPrompt: STRONG vs WEAK

| Authenticator (androidx.biometric) | Android class | Can unlock a Keystore key (CryptoObject)? | Typical sensors |
|---|---|---|---|
| `BIOMETRIC_STRONG` | Class 3 | **yes** | almost every fingerprint sensor; face only on phones with secure face hardware/certification (e.g. Pixel 4 IR face, Pixel 8 and later face unlock; some others) |
| `BIOMETRIC_WEAK` | Class 2 | no (yes/no answer only) | most camera-based face unlock (many Samsung, Xiaomi, Tecno, Infinix…), some iris |
| (convenience) | Class 1 | not offered to apps | basic face unlock on many budget phones |
| `DEVICE_CREDENTIAL` | PIN / pattern / password | yes (with `setUserAuthenticationParameters(…, AUTH_DEVICE_CREDENTIAL)`) | every phone with a screen lock |

Consequences:
- **Face is Class 3 only on some phones.** On a phone whose face unlock is Class 2/1, `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)`
  succeeds only through the fingerprint; the prompt never offers face. Do not promise "face unlock" in copy; say "fingerprint or face (where your phone allows)".
- A login that **decrypts a secret** (vault, token) must use `BIOMETRIC_STRONG` (+ `DEVICE_CREDENTIAL` as fallback) with a
  `CryptoObject`; a WEAK result is only a "user is present" check and must never gate a secret on its own.
- Check with `canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)` (API 30+; on API 28–29 STRONG and DEVICE_CREDENTIAL can't be combined in one prompt: show the PIN path separately).
- `setInvalidatedByBiometricEnrollment(true)` (the default for auth-bound keys): adding a fingerprint/face makes the key throw
  `KeyPermanentlyInvalidatedException`; catch it, delete the key, and ask the user to sign in once more. Never crash on it.

## 2. Android Keystore keys are wiped on uninstall

- Keys in the Android Keystore belong to the app's UID. **Uninstall deletes them**; "Clear storage" deletes them too.
- Auto Backup / device-to-device restore brings back files and SharedPreferences, **never Keystore keys**. Data encrypted with a
  Keystore key is restored as unreadable bytes: treat a decrypt failure after restore as "signed out", wipe that blob, and re-ask.
  (Argus: backup_rules.xml keeps the "argus" prefs, which hold the Keystore-sealed account / gateway tokens, out of cloud backup; data_rules.xml device transfer copies everything except saved logins.)
- So a biometric unlock built on a Keystore key can never survive a reinstall. That is by design.

## 3. Credential Manager passkeys survive reinstall

- A passkey created through `androidx.credentials` (Credential Manager) lives in the user's **credential provider** (Google Password
  Manager, Samsung Pass, 1Password…), synced to their account, not in the app. Uninstall, reinstall or a new phone: the passkey is still
  there and sign-in is one biometric tap (the provider's own prompt).
- Requirements: the relying-party domain serves `/.well-known/assetlinks.json` listing the app's package and the **SHA-256 of every
  signing certificate that should be allowed** (production key AND the test key below if test builds should use passkeys), and the
  server (gateway) verifies WebAuthn assertions for that RP ID.
- Saved passwords via Credential Manager (`CreatePasswordRequest`) also live in the provider and survive reinstall.
- Recommended Argus flow: passkey (or Google sign-in via Credential Manager) to get the account back after a reinstall; a STRONG-bound
  Keystore key only as the fast local unlock for this install.

## 4. Upgrade in place (no uninstall, data kept)

Android installs an APK over an existing one only when:
1. it is signed with **the same signing certificate** (or a v3 key-rotation lineage that includes the old one), and
2. its **versionCode is not lower** than the installed one (use a higher one; equal works only for `adb install -r`).

Otherwise the install fails (`INSTALL_FAILED_UPDATE_INCOMPATIBLE` / "App not installed") and the tester has to uninstall, which
wipes the Keystore keys and all app data (section 2).

### The persistent test key (0.52.1)
- `work/toolchain/sign-test.sh <out.apk> [in.apk]` now signs every test APK with **one** throwaway key, created once at
  `work/toolchain/argus-test-upgrade.p12` (alias `argus-test`, store/key password `testonly`, RSA 3072, valid to 2054) and reused.
- Cert SHA-256: `99:E1:B0:AD:D5:F1:0F:C9:24:6B:78:3D:6E:9A:C1:BC:BE:57:35:DC:91:55:D3:5A:D7:50:78:85:58:AD:37:6E`
  (add it to assetlinks.json only if test builds should use passkeys; owner's call, not done).
- It is a TEST key: not secret, never used for production/Play builds, never committed to the repo. Don't delete it: a new key means
  one more uninstall for every tester.
- Testers who still have an APK signed with an older throwaway key must uninstall once; from then on every test APK upgrades in place
  as long as versionCode goes up (0.52.1 = 188).
