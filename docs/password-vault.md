# Password vault API (Beast 2.3)

## Goal
Encrypted saved logins. Gecko's `loginAutofillEnabled` may be on, but **only** this vault stores credentials via `Autocomplete.StorageDelegate`.

## Security
- AES-256-GCM in Android Keystore (`beast_password_vault_v2`)
- Per-use auth (`timeout 0` / `AUTH_BIOMETRIC_STRONG`) + `BiometricPrompt.CryptoObject`
- Device PIN alone is not enough — strong biometric required (CryptoObject + timeout 0)
- In-memory vault auto-locks after 5 minutes idle; `PasswordVault.lock()` clears plaintext
- Settings → Passwords does **not** lock app-wide on leave (idle timeout covers browser autofill)
- Meta file (`passwords.meta.json`) has origin + username only (no passwords) for locked UI
- Vault blob: `filesDir/passwords.vault` (IV || ciphertext); excluded from backup/device transfer

## App API (`com.jamhowman.beastbrowser.passwords`)
```kotlin
PasswordVault.init(context)
PasswordVault.unlock(activity) { ok, err -> … }
PasswordVault.lock()
PasswordVault.isUnlocked()
PasswordVault.fetchForOrigin(origin): List<SavedLogin>   // empty if locked
PasswordVault.fetchAll(): List<SavedLogin>
PasswordVault.saveLogin(Autocomplete.LoginEntry)         // no-op if locked
PasswordVault.save(SavedLogin)
PasswordVault.delete(guid)
PasswordVault.markUsed(Autocomplete.LoginEntry)
PasswordVault.toGeckoArray(list): Array<LoginEntry>
PasswordVault.metaForOrigin(origin): List<MetaLogin>  // locked-safe guid/origin/username
SavedLogin.fromGecko(entry) / login.toGecko()
```

## StorageDelegate wiring (Engineer) — DONE

Implemented in `passwords/VaultStorageDelegate.kt`, attached from `MainActivity` via `Engine.attachPasswordVault`. `loginAutofillEnabled(true)` with this as the sole store.

Behaviour:
- **Fetch while locked:** returns empty (no biometric spam on every login form). Unlock in Settings → Passwords first.
- **Save:** confirm sheet → unlock if needed → `saveLogin` → `flushPending` when encrypt needs re-auth.
- **Used:** `markUsed` + optional flush.

Reference snippet:
```kotlin
override fun onLoginFetch(domain: String): GeckoResult<Array<LoginEntry>> {
  // If locked, return empty (or trigger unlock UI then fetch)
  return GeckoResult.fromValue(PasswordVault.toGeckoArray(PasswordVault.fetchForOrigin(domain)))
}
override fun onLoginSave(login: LoginEntry) {
  // Show confirm sheet, then:
  PasswordVault.saveLogin(login)
  if (PasswordVault.needsPersistAuth()) PasswordVault.flushPending(activity) { … }
}
override fun onLoginUsed(login: LoginEntry, usedFields: Int) {
  PasswordVault.markUsed(login)
}
```
Keep credit-card / address delegates empty.

## UI
- Settings → Passwords → unlock → list / delete / lock / **Reset vault**
- Reset vault: deletes Keystore key + `passwords.vault` / meta (recovery after fingerprint change)
- Save prompt + fill sheet: Engineer's side
- `markUsed` updates memory + meta only (no biometric); encrypted write on next save

## Backup / CSV import prompts (2.4.1)
`PasswordVault.importWithAuth(activity, logins) { outcome -> … }` merges imported logins with the fewest
biometric prompts the per-use key allows (`importAuthPlan`):

| Vault state | Prompts | Why |
|---|---|---|
| Unlocked | 1 (save) — 0 if nothing changed | merge in memory, one encrypt |
| No vault file yet (fresh install restoring a backup) | 1 | nothing to decrypt: one encrypt prompt creates the vault with the imported logins and opens it |
| Exists and locked | 2 (labelled "1 of 2" / "2 of 2") | decrypt the old blob + encrypt the merged blob are two Keystore operations; a per-use (timeout 0) auth token covers exactly one |

Getting the locked case to one prompt would need a time-bound key (weaker, new alias) or an in-memory data key
wrapped by the Keystore key (new on-disk format, and every later write would stop asking for a fingerprint), so it
was left at two. If the vault is unlocked while the single "create" prompt is up, the import is merged into the full
in-memory list; if a vault file appears while still locked, the write is refused rather than replacing data it can't read.
