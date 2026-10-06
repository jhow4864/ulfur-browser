package com.jamhowman.beastbrowser.downloads

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Fingerprint / face (class 2 "weak" or better) with the screen-lock PIN, pattern or password as
 * fallback. BIOMETRIC_STRONG | DEVICE_CREDENTIAL isn't supported on Android 9-10, so WEAK is the
 * combination that works on every supported version.
 */
object PrivateAuth {
    val AUTHENTICATORS: Int = BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun isAvailable(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun create(activity: FragmentActivity, onUnlocked: () -> Unit, onFailed: (noScreenLock: Boolean, msg: CharSequence) -> Unit): BiometricPrompt =
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    PrivateLock.unlock()
                    onUnlocked()
                }

                override fun onAuthenticationError(code: Int, errString: CharSequence) {
                    val noLock = code == BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL
                        || code == BiometricPrompt.ERROR_NO_BIOMETRICS
                        || code == BiometricPrompt.ERROR_HW_NOT_PRESENT
                    onFailed(noLock, errString)
                }
            })

    fun prompt(bp: BiometricPrompt) {
        bp.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock private downloads")
                .setSubtitle("Use your fingerprint, face or screen lock")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .setConfirmationRequired(false)
                .build()
        )
    }
}
