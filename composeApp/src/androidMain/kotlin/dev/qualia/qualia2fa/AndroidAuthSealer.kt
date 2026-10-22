// Copyright (C) 2026 Qualia2FA contributors
//
// This library is free software; you can redistribute it and/or modify it under
// the terms of the GNU Lesser General Public License as published by the Free
// Software Foundation; either version 3 of the License, or (at your option) any
// later version.
//
// This library is distributed in the hope that it will be useful, but WITHOUT
// ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
// FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
// details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with this library; if not, see <https://www.gnu.org/licenses/>.

package dev.qualia.qualia2fa

import androidx.fragment.app.FragmentActivity
import com.qiushui1012.qualia.bioauth.Availability as LibraryAvailability
import com.qiushui1012.qualia.bioauth.BiometricSealer
import com.qiushui1012.qualia.bioauth.KeystoreBiometricSealer
import kotlinx.coroutines.runBlocking

/**
 * The Android half of the biometric path: Android Keystore plus `BiometricPrompt`.
 *
 * The library's calls suspend while the prompt is up, and the Rust side calls back on its
 * own thread, so each call is bridged with `runBlocking` here. That is safe only because the
 * caller already moved off the main thread: the prompt itself is posted to the main looper
 * by the library, which is where Android requires it.
 *
 * [activity] is held for the life of the sealer, and the sealer is created per screen by the
 * activity that owns it.
 */
class AndroidAuthSealer(
    private val activity: FragmentActivity,
) : AuthSealer {
    private val sealer: BiometricSealer = KeystoreBiometricSealer(activity)

    override val backendName: String = "android-keystore"

    override fun availability(): AuthAvailability =
        when (val value = runBlocking { sealer.availability() }) {
            is LibraryAvailability.Available ->
                AuthAvailability.Available(backendName, value.hardwareBacked)
            is LibraryAvailability.Unavailable -> AuthAvailability.Unavailable(value.reason)
            is LibraryAvailability.Unsupported -> AuthAvailability.Unsupported(value.reason)
        }

    override fun seal(plaintext: ByteArray): ByteArray? =
        try {
            runBlocking { sealer.seal(plaintext) }
        } catch (_: Exception) {
            // The failure is reported as a `null`, and the caller shows one message: the
            // distinction between "cancelled" and "the platform refused" changes what the
            // user is told, not what this layer has to do about it.
            null
        }

    override fun unseal(envelope: ByteArray): ByteArray? =
        try {
            runBlocking { sealer.unseal(envelope) }
        } catch (_: Exception) {
            null
        }

    override fun delete(): Boolean =
        try {
            sealer.delete()
            true
        } catch (_: Exception) {
            false
        }
}
