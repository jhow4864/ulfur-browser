package com.jamhowman.beastbrowser.autofill

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.jamhowman.beastbrowser.passwords.PasswordVault

/**
 * 2.10 Ulfur #2: one fingerprint/face/PIN check before EVERY fill, even while the vault is already unlocked.
 * Locked vault: the vault's own crypto unlock is that check. Unlocked: a fresh prompt (strong biometric or the device
 * PIN/pattern/password). [onResult] runs on the main thread.
 */
object FillAuth {
    // WEAK | DEVICE_CREDENTIAL: STRONG | DEVICE_CREDENTIAL is unsupported on Android 9-10 (see PrivateAuth).
    const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun authorize(activity: FragmentActivity, site: String, onResult: (Boolean) -> Unit) {
        if (!PasswordVault.isUnlocked()) {
            PasswordVault.unlock(activity, "Fill saved login", site) { ok, _ -> onResult(ok) }
            return
        }
        if (BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS) {
            onResult(false); return
        }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
                override fun onAuthenticationError(code: Int, errString: CharSequence) = onResult(false)
            })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Fill saved login")
                .setSubtitle(site)
                .setAllowedAuthenticators(AUTHENTICATORS)
                .setConfirmationRequired(false)
                .build()
        )
    }
}
