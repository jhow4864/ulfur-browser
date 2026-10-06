# Password vault review (2.3.0 build, 5 Oct 2026)

These findings come from reading the code only. Nothing here was run on a device.

## Fixed
- data_extraction_rules.xml now excludes files/ (passwords.vault, passwords.meta.json, private downloads) and external files, in both the cloud-backup and device-transfer sections.
- PasswordVault.kt:110-111: the parse-failure log could include the decrypted vault. It now logs only the error type.
- SavedLogin.toString() now redacts the username and password.
- Added PasswordSecurityTest.kt.

## Needs an owner
1. Engineer: DONE — `Prompts.onLoginSave` / `onLoginSelect`; private tabs dismiss without saving (`HelperSessions.isPrivate`). Confirm lives only in the prompt; StorageDelegate just unlocks+persists.
2. Jamh: VaultCrypto.kt:34-48 and PasswordVault.kt:84-132 combine a 5-minute auth-validity window with a BiometricPrompt CryptoObject. Because of that:
   - the prompt doesn't actually unlock the key;
   - cipher init fails if the device was unlocked more than 5 minutes ago;
   - invalidatedByBiometricEnrollment has no effect.
   Fix: either use per-use auth (timeout 0, AUTH_BIOMETRIC_STRONG) with the CryptoObject, or keep the window, drop the CryptoObject and re-prompt when UserNotAuthenticatedException is thrown.
3. Jamh: BIOMETRIC_STRONG|DEVICE_CREDENTIAL with a CryptoObject isn't supported below API 30, but minSdk is 26 (PasswordVault.kt:129/132, 289/291).
4. Jamh: there's no re-lock policy after a browser-side unlock, and PasswordsActivity.kt:53-56 locks the vault app-wide when you leave it. The listener added at line 48 is never removed.
5. Lower priority:
   - one IV per whole-vault write, not per item (safe, since it's random each time);
   - PasswordVault.kt:261 ignores the result of the temp-file rename;
   - pending edits are kept in memory only;
   - meta.json keeps sites and usernames in plaintext (by design, now excluded from backup).

## Re-review (5 Oct 2026, 10:05)
Still read-only and not run on a device. The 2.3.0 build stays versionCode 5.

**Confirmed fixed**
- #1 Engineer: `Prompts.onLoginSave` is the only place a save is confirmed, and private tabs dismiss it with a toast. `VaultStorageDelegate` now only unlocks and persists, so there's no double prompt.
- #2 Jamh: v2 key with per-use auth (`setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`), unlocked with a CryptoObject. Enrolment invalidation now has an effect.
- #3 Jamh: prompts are BIOMETRIC_STRONG only (with a Cancel button), which is valid with a CryptoObject on API 26+. With no strong biometric enrolled, unlock shows "Set up a fingerprint…" instead of crashing.
- #4 Jamh: there's a 5-minute idle re-lock; it reuses one Runnable on the main Handler, so it doesn't leak. PasswordsActivity no longer locks the vault on leave and removes its listener in onDestroy.
- #5: the temp-file rename result is now checked.
- Autofill fetch never touches the cipher.

**Fixed in this pass (small, safe)**
- VaultCrypto.kt (API 24–29 branch): used `setUserAuthenticationValidityDurationSeconds(0)`. Below API 30 that makes a time-bound key with a 0-second window rather than a per-use key, so cipher init always throws and unlock could never succeed on Android 8–10. Changed it to `-1`, the documented "auth for every use" value.
- PasswordVault.flushPending: if the idle timer locked the vault while the "Save password vault" prompt was open, a successful auth wrote the now-empty memory list over the vault. It now refuses to write when locked.
- Prompts.onLoginSelect: choosing a GENERATED option makes Gecko call `StorageDelegate.onLoginSave` directly (`GeckoViewAutocomplete._fillSelection`), skipping `onLoginSave` and its private-tab check. A single option was also auto-confirmed. Generated options are now removed in private tabs and never auto-confirmed in normal tabs. The private check also uses `session.settings.usePrivateMode`.
- Added `PasswordSecurityTest.reReviewGuards`.

**Still open**
1. Jamh (high), PasswordVault.kt:108/137/155 and VaultCrypto.kt:31: there's no recovery path.
   - After a biometric enrolment change the key is invalidated, and every unlock fails with a raw exception message (line 155).
   - A vault written with the v1 key (the v1 key is now deleted) fails GCM authentication, giving "Couldn't open vault" forever.
   - Either way the user can never unlock or save again. Fix: catch KeyPermanentlyInvalidatedException / AEADBadTagException and offer "Reset vault" (deleteKey and delete passwords.vault, .tmp and meta.json).
2. Jamh + Engineer (high, UX), VaultStorageDelegate.kt:40-44 with PasswordVault.markUsed (225): every submitted autofilled login triggers a "Save password vault" biometric prompt, because a per-use key needs a prompt for every write. Fix: don't persist or flush for `timesUsed`; write it with the next real save.
3. Jamh (medium, possible data loss), PasswordVault.lock() (69):
   - If the user cancels the flush prompt, the new or deleted login exists only in memory, and lock or idle-lock silently discards it.
   - The toast at VaultStorageDelegate.kt:55 ("unlock again to write to disk") is wrong: unlocking reloads the file.
   - `pendingPersist` isn't reset by lock(), which causes an extra prompt after the next unlock.
4. Jamh (low), PasswordVault.kt:256: `tryPersistEncrypt` always fails with a per-use key (it uses the cipher without a prompt). It's caught, so there's no crash, but it's wasted work and should go straight to `flushPending`.
5. Design note: autofill works only while the vault is unlocked (after a save, or Settings → Passwords) and for up to 5 idle minutes. After that, login forms silently get nothing until the user unlocks in Settings.

## 2.3.1 re-review (5 Oct 2026)
Read-only; nothing run on a device. Version 2.3.1, versionCode 6.

**Confirmed fixed**
- Open #1 (no recovery): `PasswordVault.resetVault()` deletes the Keystore key (v2 and v1 aliases), passwords.vault, passwords.vault.tmp and passwords.meta.json, and needs no fingerprint. It's reachable two ways:
  - Settings → Passwords → overflow menu → "Reset vault" → a confirmation dialog ("Delete all saved passwords and the encryption key? This can't be undone.") with Reset / Cancel;
  - automatically, when unlock fails with KeyPermanentlyInvalidatedException or AEADBadTagException (`ERR_NEEDS_RESET`). That dialog explains why.
  It's never triggered without a confirm tap, and back or tapping outside cancels. A failed save unlock from the browser only shows a toast.
- Open #2 (prompt on every autofill submit): `markUsed` updates memory and the meta file only, and `onLoginUsed` no longer calls `flushPending`.
- Open #4 (silent encrypt attempt): `persistUnlocked` now just queues the write; every write goes through `flushPending` with a CryptoObject prompt.
- `lock()` clears `pendingPersist`. The save-failure toast now points to Settings → Passwords, which flushes after unlock while the vault is still open.
- The meta file (`SavedLogin.toMetaJson`) has no password field, so `markUsed` meta writes can't leak one.
- Earlier guards: `PasswordSecurityTest` has 7 tests, all passing. It now also covers reset deleting every file behind a confirmation, and meta / `markUsed` never carrying passwords or prompting.

**Still open (Jamh, medium-low)**
- Cancelled write prompt: the edit stays in memory and can be retried from Settings → Passwords. But a manual Lock or the 5-minute idle lock discards it silently (`lock()`, PasswordVault.kt:69-76).
- `save()`/`delete()` write the meta file before the encrypted write succeeds (PasswordVault.kt:251, :260). Until the next unlock, the locked screen's count and usernames can show a login that was never saved, or hide one that was never deleted.
- Suggested fix: write meta only in `persistWithCipher`, and tell the user (toast or notification) when a lock discards unsaved changes.

**Notes (not blocking)**
- Reset needs no authentication, so anyone holding the unlocked phone can wipe the vault, but they can't read it. That's the same trade-off as other browsers.
- The meta file also holds usage counts and times (plaintext, excluded from backup).
- Autofill still works only while unlocked: after a save, or Settings → Passwords, for up to 5 idle minutes.

## 2.3.2 note (5 Oct 2026)
Read-only check; nothing run on a device. Version 2.3.2, versionCode 7.
- Fixed (Jamh): `save()`, `delete()` and `clearAll()` no longer write the meta file. Only `persistWithCipher` does, after the encrypted write succeeds.
- Fixed (Jamh): `lock()` with an unsaved write pending (manual Lock or 5-minute idle lock) shows a toast on the main thread instead of dropping the edit silently. The edit is still discarded, by design: no plaintext is kept in memory after a lock.
- Small fixes in this pass:
  - `markUsed` skips the meta write while an encrypted write is pending. Otherwise an autofill submit after a cancelled prompt would put the unsaved add or delete back into the meta file.
  - `flushPending` now reports failure if `persistWithCipher` throws. Before, it showed "Password saved" even when the write failed.
- Guards: `PasswordSecurityTest` has 8 tests, all passing. The new test covers the meta-after-write rule and the lock warning.
- Notes (not blocking):
  - If the 5-minute idle lock fires while the app is in the background, the toast appears over whatever is on screen.
  - The `persistWithCipher` fallback (when the rename fails) writes the vault non-atomically. That path is rare.
- Search suggestions privacy check (Jamh's feature, added to 2.3.2):
  - Private tabs used to send keystrokes to the engine; now they don't (`includeRemote`).
  - New Settings → Search → "Search suggestions" toggle, on by default.
  - URL-like input (a host, an IP, `scheme://`, or no spaces with `. / : @`) is never sent.
  - Startpage no longer falls back to DuckDuckGo; it shows local suggestions only.
  - Requests use HTTPS, no cookies (no app-wide CookieHandler) and `useCaches=false`.
  - Typing waits 180 ms before a request and cancels the previous job, with an `ensureActive()` check before the network call.
  - Known limit: a request already in flight runs until it finishes or hits the 2.5 s timeout. Its result is thrown away.
