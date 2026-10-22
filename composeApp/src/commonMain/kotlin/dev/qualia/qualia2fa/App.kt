package dev.qualia.qualia2fa

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.dialog_pick_image
import dev.qualia.qualia2fa.resources.error_biometric_blob_unreadable
import dev.qualia.qualia2fa.resources.error_biometric_failed
import dev.qualia.qualia2fa.resources.error_no_platform_key_store
import dev.qualia.qualia2fa.resources.error_lock_pending
import dev.qualia.qualia2fa.resources.error_lock_unreadable
import dev.qualia.qualia2fa.resources.error_old_password_rejected
import dev.qualia.qualia2fa.resources.error_password_wrap_failed
import dev.qualia.qualia2fa.resources.error_recovery_bare_vault
import dev.qualia.qualia2fa.resources.error_recovery_key_only
import dev.qualia.qualia2fa.resources.error_recovery_rejected
import dev.qualia.qualia2fa.resources.error_recovery_vault_failed
import dev.qualia.qualia2fa.resources.error_serialize_failed
import dev.qualia.qualia2fa.resources.error_set_password_required
import dev.qualia.qualia2fa.resources.error_unlock_password_rejected
import dev.qualia.qualia2fa.resources.error_vault_does_not_match_password
import dev.qualia.qualia2fa.resources.filter_image_files
import dev.qualia.qualia2fa.resources.message_master_key_reset
import dev.qualia.qualia2fa.resources.message_biometric_disabled
import dev.qualia.qualia2fa.resources.message_biometric_enabled
import dev.qualia.qualia2fa.resources.message_password_changed
import dev.qualia.qualia2fa.resources.set_password_after_import_explainer
import dev.qualia.qualia2fa.resources.settings_biometric_unavailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Where the application is.
 *
 * A value rather than a set of flags: "loading", "at the login screen", "logging in" and
 * "inside the main screens" are mutually exclusive, and a state that says so cannot end up
 * showing two of them.
 */
private sealed interface Phase {
    /** Working out whether this device has a vault, and opening it if it does. */
    data object Loading : Phase

    /**
     * The login screen, in the state that matches this device.
     *
     * [reason] is why the vault is locked, when the user is looking at a lock rather than at
     * a first run.
     */
    data class Login(val mode: LoginMode, val reason: Status?, val passwordAvailable: Boolean = true) : Phase

    /** Logging in on a new device: the pairing handshake, without navigation. */
    data object Joining : Phase

    /** Inside the main screens, on [tab]. */
    data class Main(val tab: Tab) : Phase

    /**
     * A vault exists but has no unlock password yet.
     *
     * The master key is in memory because the legacy platform store (or a fresh registration)
     * produced it. The vault is not written until the password is confirmed, so cancelling
     * leaves the device as it was.
     */
    data class SetPassword(
        val vault: Vault,
        val masterKey: ByteArray,
        val migrating: Boolean = false,
        /**
         * The vault that was open before this setup began, when it began from the settings.
         *
         * Cancelling then returns to that vault instead of to a first run: an import never
         * writes anything before the password is confirmed, so the vault the user had is
         * still on disk and still opens with the password it already had.
         */
        val resume: Vault? = null,
        /** Whether the vault came from a portable file, which the password screen says. */
        val fromImport: Boolean = false,
    ) : Phase
}

/**
 * The application window.
 *
 * It owns the two things every screen depends on: **which language** everything is read in,
 * and **whether the vault is open**. Everything else belongs to the screens.
 *
 * # Locking
 *
 * Two things lock the vault: [SESSION_TIMEOUT_SECONDS] without the phase changing, and the
 * user asking for it from the settings. Locking drops the only reference to the key material,
 * which the Rust side wipes when it is dropped, and clears the clipboard. Unlocking reads the
 * master key out of the platform key store again — there is no password to type, the store
 * *is* the protection.
 *
 * Losing focus deliberately does **not** lock: the file dialog is a window of its own, so a
 * lock on focus loss fires while the user is still choosing the image to read a QR code out
 * of, which is one of the normal ways to add an entry.
 *
 * # The language wraps the screens, not the vault
 *
 * The vault state lives **outside** the key that a language change recreates: switching the
 * language must not lock the vault, read the master key again, or restart the timeout.
 * Everything below the key is rebuilt, so a screen's transient messages are dropped as well —
 * they were resolved in the language that is no longer in effect.
 */
@Composable
fun App(
    storage: VaultStorage?,
    settings: SettingsStore,
    /**
     * Whether the application is in the foreground.
     *
     * The timeout counts time **in the application**, not time in a system dialog: opening a
     * file picker pauses the activity, and locking while it is open would end the composition
     * that is waiting for the picked file - the picker would still create the document, but the
     * bytes would never be written and the user would be left with an empty file. The timer
     * therefore stops while this is false and restarts from the beginning when it is true again.
     */
    active: Boolean = true,
    /**
     * The platform half of the biometric path, or `null` on a platform that has none.
     *
     * The Android build passes a Keystore/Prompt implementation; the desktop build passes
     * nothing, so the settings screen reports the path as unavailable instead of offering
     * a button that cannot work.
     */
    authSealer: AuthSealer? = null,
) {
    var language by remember { mutableStateOf(settings.language()) }
    var theme by remember { mutableStateOf(settings.theme()) }
    var phase by remember { mutableStateOf<Phase>(Phase.Loading) }
    var vault by remember { mutableStateOf<Vault?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    // The application lock is enabled once a marker is seen or written. `passwordAvailable`
    // is what the lock actually holds now, not what the marker recorded.
    var lockEnabled by remember { mutableStateOf(false) }
    var passwordAvailable by remember { mutableStateOf(true) }
    // The platform-authenticated path (`master.key.bio`). `biometricEnabled` mirrors the lock
    // files; the availability is what the platform reports, asked when it is needed.
    var biometricEnabled by remember { mutableStateOf(false) }
    var biometricAvailability by remember { mutableStateOf<AuthAvailability?>(null) }
    // A lock operation that was interrupted after its wrap was written. The next successful
    // password unlock finishes it (writes the marker, deletes the pending file and the legacy
    // platform key) before the vault is shown.
    var pendingLockSetup by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    // Unlocking again from the login screen starts the same read the window started with, and
    // that read waits for the loading screen to have been up long enough: it is a coroutine.
    val scope = rememberCoroutineScope()

    val selectLanguage: (AppLanguage) -> Unit = { chosen ->
        language = chosen
        settings.setLanguage(chosen)
    }

    val selectTheme: (AppTheme) -> Unit = { chosen ->
        theme = chosen
        settings.setTheme(chosen)
    }

    /**
     * Makes [opened] the vault of this session.
     *
     * The 0.2.x group tree is migrated **before** the screen sees the vault: it used to live
     * beside the preferences, and the vault is where it belongs now. The migrated vault is
     * written to disk at once, because the write is what makes the setting safe to delete - the
     * migration already dropped it only after the vault accepted the tree, but the file on disk
     * still holds the flat arrangement until this save.
     */
    fun adopt(opened: Vault) {
        if (migrateLegacyGroupLayout(settings, opened)) {
            opened.serialize()?.let { storage?.saveVault(it) }
        }
        vault = opened
        message = null
        phase = Phase.Main(Tab.TwoFactor)
    }

    // Resolved here because the callbacks below cannot read a resource; the messages they
    // produce are the ones that outlive a screen change.
    val serializeFailed = stringResource(Res.string.error_serialize_failed)
    val noKeyStore = stringResource(Res.string.error_no_platform_key_store)
    val masterKeyReset = stringResource(Res.string.message_master_key_reset)
    val recoveryKeyOnly = stringResource(Res.string.error_recovery_key_only)
    val recoveryVaultFailed = stringResource(Res.string.error_recovery_vault_failed)
    val recoveryRejected = stringResource(Res.string.error_recovery_rejected)
    val recoveryBareVault = stringResource(Res.string.error_recovery_bare_vault)
    val unlockRejected = stringResource(Res.string.error_unlock_password_rejected)
    val lockUnreadable = stringResource(Res.string.error_lock_unreadable)
    val lockPending = stringResource(Res.string.error_lock_pending)
    val vaultDoesNotMatchPassword = stringResource(Res.string.error_vault_does_not_match_password)
    val passwordWrapFailed = stringResource(Res.string.error_password_wrap_failed)
    val setPasswordRequired = stringResource(Res.string.error_set_password_required)
    val setPasswordAfterImport = stringResource(Res.string.set_password_after_import_explainer)
    val passwordChanged = stringResource(Res.string.message_password_changed)
    val oldPasswordRejected = stringResource(Res.string.error_old_password_rejected)
    val biometricBlobUnreadable = stringResource(Res.string.error_biometric_blob_unreadable)
    val biometricFailed = stringResource(Res.string.error_biometric_failed)
    val biometricEnabledMessage = stringResource(Res.string.message_biometric_enabled)
    val biometricDisabledMessage = stringResource(Res.string.message_biometric_disabled)
    val biometricUnavailable = stringResource(Res.string.settings_biometric_unavailable)

    val picker = rememberFilePicker()
    val camera = hasCamera()
    val pickImageTitle = stringResource(Res.string.dialog_pick_image)
    val imageFilesLabel = stringResource(Res.string.filter_image_files)

    /** The code being asked for, while the scanner is open. */
    var scanning by remember { mutableStateOf<ScanRequest?>(null) }

    /**
     * The way in that every screen is given.
     *
     * A device with a camera is shown the scanner, because that is the way in that needs
     * nothing prepared in advance; a device without one goes straight to picking an image,
     * since a screen holding nothing but a black rectangle would be a step in the way.
     */
    val scanner =
        remember(picker, camera, pickImageTitle, imageFilesLabel) {
            CodeScanner { request ->
                if (camera) {
                    scanning = request
                } else {
                    picker.readCodeFromImage(
                        dialogTitle = pickImageTitle,
                        imageFilesLabel = imageFilesLabel,
                        unreadable = request.unreadable,
                        noCode = request.noCode,
                        onText = request.onText,
                        onMessage = request.onMessage,
                    )
                }
            }
        }

    /**
     * Reads the vault this device already has, and goes wherever that leads.
     *
     * The loading screen stays up for at least [LOADING_MINIMUM], whether there was anything
     * to wait for or not: opening a vault is a key store access and a file read, and on a
     * warm device both are over before the first frame is drawn. A screen that appears and
     * vanishes inside a frame reads as a flicker rather than as progress.
     */
    suspend fun openExisting() {
        phase = Phase.Loading
        val shown = TimeSource.Monotonic.markNow()

        val device = storage
        val store = device?.masterKeyStore
        val data = device?.loadVault()
        val hasKey = store != null && Qualia.hasMasterKey(store)
        val lockState = device?.let(::detectLockState)

        val target =
            when {
                // The application lock comes before the platform key store. A password wrap
                // needs no platform store at all, and a machine whose platform store failed
                // must still be able to open a locked vault with its password.
                lockState is LockState.Enabled -> {
                    lockEnabled = true
                    passwordAvailable = lockState.password
                    biometricEnabled = lockState.biometric
                    pendingLockSetup = lockState.pending
                    vault = null
                    message = null
                    Phase.Login(
                        LoginMode.PasswordLocked,
                        null,
                        passwordAvailable = lockState.password,
                    )
                }

                // A lock file exists but the state cannot be trusted. This is deliberately
                // **not** the legacy path: opening the vault without authentication here would
                // be exactly the bypass the lock exists to prevent.
                lockState is LockState.Broken -> {
                    lockEnabled = true
                    passwordAvailable = false
                    biometricEnabled = false
                    vault = null
                    message =
                        if (lockState.reason == BrokenLock.PendingInProgress) {
                            lockPending
                        } else {
                            lockUnreadable
                        }
                    Phase.Login(LoginMode.Unreadable, null)
                }

                // Nowhere to keep a master key: this machine has no platform key store that
                // can hold a key-encryption key. Said out loud, because every action the
                // login screen offers would fail on it.
                device == null || store == null -> {
                    vault = null
                    message = noKeyStore
                    Phase.Login(LoginMode.FirstRun, null)
                }

                // Nothing here at all: the first run. This is the only state in which creating a
                // vault is safe, because there is no master key to replace.
                data == null && !hasKey -> {
                    vault = null
                    Phase.Login(LoginMode.FirstRun, null)
                }

                // A vault file whose master key is gone. The wrapped bytes may be there and no
                // longer unwrap — the platform key that protected them is lost by a hardware
                // reset, a restored backup or a reset of this device's credentials — and what is
                // left can never be opened again. Reporting the first run here would invite the
                // user to create a vault over the one they still have.
                !hasKey -> {
                    vault = null
                    Phase.Login(LoginMode.Unreadable, null)
                }

                // A key without a vault file: the file is gone, so there is nothing to open and
                // nothing that could be unlocked.
                data == null -> {
                    vault = null
                    Phase.Login(LoginMode.VaultMissing, null)
                }

                else -> {
                    val opened = Qualia.openVault(data, store)
                    if (opened == null) {
                        vault = null
                        Phase.Login(LoginMode.Unreadable, null)
                    } else {
                        // A 0.3.0 device: the vault opened through the legacy platform
                        // store. The master key must become a password wrap before the
                        // vault is shown again, so the old no-prompt path is removed in
                        // the same operation that creates the lock.
                        val master = store.load()?.copyOf()
                        if (master == null) {
                            vault = null
                            message = lockUnreadable
                            Phase.Login(LoginMode.Unreadable, null)
                        } else {
                            vault = null
                            Phase.SetPassword(opened, master, migrating = true)
                        }
                    }
                }
            }

        val remaining = LOADING_MINIMUM - shown.elapsedNow()
        if (remaining.isPositive()) delay(remaining)
        phase = target
    }

    /** Drops the key material and returns to the login screen, which now says why. */
    fun lock(reason: Status) {
        vault = null
        phase =
            if (lockEnabled) {
                Phase.Login(LoginMode.PasswordLocked, reason, passwordAvailable)
            } else {
                Phase.Login(LoginMode.Locked, reason)
            }
        clipboard.setText(AnnotatedString(""))
    }

    /**
     * Completes a lock operation that was interrupted after its wrap was written.
     *
     * The password has just opened the vault, so the wrap exists and is usable. The legacy
     * platform key is deleted before the marker is written: if that fails, the pending file
     * stays and the next unlock tries again, instead of leaving a no-prompt key behind a lock
     * that claims to be enabled.
     */
    fun finishPendingLock(device: VaultStorage): Boolean {
        val marker = Qualia.lockMarker(true, false, currentTimeSeconds()) ?: return false
        return try {
            device.masterKeyStore?.delete()
            if (device.masterKeyStore?.load() != null) return false
            device.writeLockFile(LockFile.Marker, marker)
            device.deleteLockFile(LockFile.Pending)
            lockEnabled = true
            passwordAvailable = true
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Opens the vault with the unlock password.
     *
     * The derivation is deliberately off the main thread: Argon2id with the recommended
     * parameters takes long enough to freeze the screen. The master key exists only inside
     * [Qualia.openVaultWithPassword]; nothing is written to the platform key store, so locking
     * again really means the key is gone.
     */
    fun unlockWithPassword(password: String) {
        val device = storage
        if (device == null) {
            message = noKeyStore
            return
        }
        val wrap = device.readLockFile(LockFile.PasswordWrap)
        val data = device.loadVault()
        if (wrap == null || data == null) {
            message = lockUnreadable
            return
        }

        scope.launch {
            val outcome =
                withContext(Dispatchers.Default) {
                    Qualia.openVaultWithPassword(data, wrap, password)
                }
            when (outcome) {
                is Qualia.PasswordOpen.Opened -> {
                    if (pendingLockSetup && finishPendingLock(device)) {
                        pendingLockSetup = false
                    }
                    adopt(outcome.vault)
                }
                Qualia.PasswordOpen.WrongPassword -> message = unlockRejected
                Qualia.PasswordOpen.Unreadable -> message = lockUnreadable
                Qualia.PasswordOpen.VaultMismatch -> message = vaultDoesNotMatchPassword
            }
        }
    }

    /**
     * Opens the vault through the platform authentication path.
     *
     * The gesture runs off the main thread: on Windows it is a Hello prompt with its own
     * message loop, and on Android it is a `BiometricPrompt`. A failure never changes any
     * lock file — the blob stays and the password path is still there.
     */
    fun unlockWithBiometric() {
        val device = storage
        val blob = device?.readLockFile(LockFile.BiometricWrap)
        val data = device?.loadVault()
        if (device == null || blob == null || data == null) {
            message = biometricBlobUnreadable
            return
        }

        scope.launch {
            val outcome =
                try {
                    withContext(Dispatchers.Default) {
                        val store = Qualia.authBoundKeyStore(authSealer)
                        Qualia.openVaultWithAuthBound(store, data, blob)
                    }
                } catch (_: Exception) {
                    // A platform half that fails before it can answer — a prompt that cannot
                    // be shown, a key store that refuses to open — is reported like a refused
                    // gesture rather than taking the application down with it.
                    message = biometricBlobUnreadable
                    return@launch
                }
            when (outcome) {
                is Qualia.PasswordOpen.Opened -> {
                    if (pendingLockSetup && finishPendingLock(device)) {
                        pendingLockSetup = false
                    }
                    adopt(outcome.vault)
                }
                Qualia.PasswordOpen.WrongPassword,
                Qualia.PasswordOpen.Unreadable -> message = biometricBlobUnreadable
                Qualia.PasswordOpen.VaultMismatch -> message = vaultDoesNotMatchPassword
            }
        }
    }

    /**
     * Adds the platform-authenticated wrap of the open vault.
     *
     * The order is the safe one: the blob is written only after the platform released a key
     * that wrapped this vault's master key, and the marker is updated last. A cancel or a
     * platform failure leaves the device exactly as it was.
     */
    fun enableBiometric(onResult: (String?, Boolean) -> Unit) {
        val device = storage
        val current = vault
        if (device == null || current == null) {
            onResult(biometricFailed, true)
            return
        }

        scope.launch {
            val outcome =
                try {
                    withContext(Dispatchers.Default) {
                        val store = Qualia.authBoundKeyStore(authSealer)
                        val availability = store.availability
                        if (availability !is AuthAvailability.Available) {
                            return@withContext null to availability
                        }
                        if (!store.create()) return@withContext null to availability
                        current.wrapMasterForAuthBound(store) to availability
                    }
                } catch (_: Exception) {
                    onResult(biometricFailed, true)
                    return@launch
                }
            val (blob, availability) = outcome
            if (availability !is AuthAvailability.Available) {
                onResult(
                    if (availability is AuthAvailability.Unavailable) {
                        "$biometricUnavailable ${availability.reason}"
                    } else {
                        biometricUnavailable
                    },
                    true,
                )
                return@launch
            }
            if (blob == null) {
                onResult(biometricFailed, true)
                return@launch
            }
            val marker = Qualia.lockMarker(true, true, currentTimeSeconds())
            val written =
                try {
                    device.writeLockFile(LockFile.BiometricWrap, blob)
                    if (marker != null) device.writeLockFile(LockFile.Marker, marker)
                    true
                } catch (_: Exception) {
                    false
                }
            if (written) {
                biometricEnabled = true
                onResult(biometricEnabledMessage, false)
            } else {
                runCatching { device.deleteLockFile(LockFile.BiometricWrap) }
                onResult(biometricFailed, true)
            }
        }
    }

    /**
     * Removes the platform-authenticated wrap.
     *
     * The platform credential is deleted by the blob's own credential id, so this works
     * after a restart when the store no longer remembers it. The marker is rewritten with
     * `biometric = false` only after the blob is gone.
     */
    fun disableBiometric(onResult: (String?, Boolean) -> Unit) {
        val device = storage
        if (device == null) {
            onResult(biometricFailed, true)
            return
        }
        val blob = device.readLockFile(LockFile.BiometricWrap)

        scope.launch {
            val deleted =
                try {
                    withContext(Dispatchers.Default) {
                        val store = Qualia.authBoundKeyStore(authSealer)
                        when {
                            blob == null -> true
                            store.deleteBlob(blob) -> true
                            else -> false
                        }
                    }
                } catch (_: Exception) {
                    false
                }
            val marker = Qualia.lockMarker(passwordAvailable, false, currentTimeSeconds())
            val written =
                try {
                    device.deleteLockFile(LockFile.BiometricWrap)
                    if (marker != null) device.writeLockFile(LockFile.Marker, marker)
                    true
                } catch (_: Exception) {
                    false
                }
            if (deleted && written) {
                biometricEnabled = false
                onResult(biometricDisabledMessage, false)
            } else {
                onResult(biometricFailed, true)
            }
        }
    }

    /**
     * Rotates the unlock password of the open vault.
     *
     * The native layer checks the old password against the wrap and this vault's master key;
     * the new wrap is written atomically over the old one. A failed write leaves the old
     * password working, which is the only safe direction.
     */
    fun changePassword(
        oldPassword: String,
        newPassword: String,
        onResult: (String?) -> Unit,
    ) {
        val device = storage
        val current = vault
        val wrap = device?.readLockFile(LockFile.PasswordWrap)
        if (device == null || current == null || wrap == null) {
            onResult(lockUnreadable)
            return
        }

        scope.launch {
            val outcome =
                withContext(Dispatchers.Default) {
                    current.rewrapLockPassword(wrap, oldPassword, newPassword)
                }
            when (outcome) {
                is PasswordChange.Wrapped -> {
                    val written =
                        try {
                            device.writeLockFile(LockFile.PasswordWrap, outcome.wrap)
                            true
                        } catch (_: Exception) {
                            false
                        }
                    onResult(if (written) passwordChanged else passwordWrapFailed)
                }
                PasswordChange.WrongPassword -> onResult(oldPasswordRejected)
                PasswordChange.Failed -> onResult(passwordWrapFailed)
            }
        }
    }

    /**
     * Removes what this device holds and returns to the first run.
     *
     * One function for the two places that ask for it — the settings, where the vault is open,
     * and the login screen, where it cannot be opened at all — because what it removes is the
     * same in both cases and a second copy would be a second thing to keep in step with
     * [VaultStorage.reset]. The phase is set here rather than left to a re-read: there is no
     * vault to show afterwards, and the login screen is where a new one is made.
     */
    fun resetDevice() {
        storage?.reset()
        vault = null
        lockEnabled = false
        passwordAvailable = true
        message = masterKeyReset
        phase = Phase.Login(LoginMode.FirstRun, null)
    }

    /**
     * Creates the master key and the vault object in memory, then asks for the unlock password.
     *
     * The platform store is deliberately **not** used for the master key: registration writes
     * through an ephemeral store, and the bytes are handed to the password screen. Nothing is
     * written to disk until that screen confirms, so a crash or a cancel leaves no half-locked
     * vault behind.
     */
    fun prepareNewVault() {
        val device = storage
        if (device == null || device.masterKeyStore == null) {
            message = noKeyStore
            return
        }

        val ephemeral = Qualia.ephemeralMasterKeyStore()
        val registration = Qualia.register(ephemeral, DEVICE_LABEL)
        val created = registration?.vault ?: Qualia.createLocalVault(ephemeral)
        val master = ephemeral.load()?.copyOf()
        ephemeral.delete()

        if (created == null || master == null) {
            master?.let(::wipe)
            message = noKeyStore
            return
        }
        message = null
        phase = Phase.SetPassword(created, master)
    }

    /** Abandons the setup; the in-memory master key is wiped and nothing was written. */
    fun cancelLockSetup() {
        val current = phase as? Phase.SetPassword ?: return
        wipe(current.masterKey)
        val previous = current.resume
        if (previous != null) {
            // An import started from the settings: nothing was written, so the vault that was
            // open is still the one on disk and still opens with its old password.
            vault = previous
            message = null
            phase = Phase.Main(Tab.Settings)
            return
        }
        vault = null
        if (current.migrating) {
            // The legacy key and vault are untouched. The user can try the migration again
            // (Unlock), import a vault, or reset; the vault must not be usable without a
            // password, so this is deliberately not the first-run screen.
            message = setPasswordRequired
            phase = Phase.Login(LoginMode.Unreadable, null)
        } else {
            message = null
            phase = Phase.Login(LoginMode.FirstRun, null)
        }
    }

    /**
     * Writes the lock and, only after it verifies, the vault.
     *
     * The order is "failed setup leaves either the old state or a recoverable pending state".
     * The password wrap is computed before any file is touched, so a crash during Argon2id
     * leaves nothing; after that the pending file marks the window in which the next start
     * must finish the operation.
     */
    fun completeLockSetup(password: String) {
        val current = phase as? Phase.SetPassword ?: return
        val device = storage ?: return
        val data = current.vault.serialize()
        if (data == null) {
            message = serializeFailed
            return
        }

        scope.launch {
            val written =
                withContext(Dispatchers.Default) {
                    writeLockFiles(device, data, current.masterKey, password)
                }
            if (!written) {
                message = passwordWrapFailed
                return@launch
            }
            wipe(current.masterKey)
            lockEnabled = true
            passwordAvailable = true
            adopt(current.vault)
        }
    }

    /**
     * Opens a portable file and makes its vault the account of this session, once a password is set.
     *
     * The file may be either `.q2fa` shape or the old key-only recovery file: the library
     * recognizes all three, and this path only has to say what to do with the answer. A plain
     * `.q2fa` needs no passphrase, which is why the screen accepts an empty one.
     *
     * The recovered master key goes into an **ephemeral** store, never the platform one: the
     * password screen writes the lock and only then is the vault saved. That is what makes a
     * failed or cancelled restore leave the device exactly as it was, and it is also why
     * importing never has to touch the platform key store before the password is known.
     * Argon2id is deliberately expensive, hence the background dispatcher.
     *
     * [resume] is the vault that was open before this began, when the import started in the
     * settings; cancelling the password screen returns to it. [currentVaultData] is the vault
     * this device already has, serialized from memory when the import started in the settings,
     * so an old key-only recovery file opens the vault the user is looking at rather than a
     * slightly older copy on disk. [onFailure] receives the text to show when the file is not
     * accepted, because the screen that asked is not necessarily the login screen.
     */
    fun restorePortable(
        file: ByteArray,
        passphrase: String,
        resume: Vault?,
        currentVaultData: ByteArray?,
        onFailure: (String) -> Unit,
    ) {
        val device = storage
        if (device == null) {
            onFailure(noKeyStore)
            return
        }

        scope.launch {
            val ephemeral = Qualia.ephemeralMasterKeyStore()
            val outcome =
                try {
                    withContext(Dispatchers.Default) {
                        restorePortableFile(
                            file,
                            passphrase,
                            ephemeral,
                            currentVaultData ?: device.loadVault(),
                        )
                    }
                } catch (_: Exception) {
                    // A file read or a platform store that fails before it can answer is a
                    // refusal to restore, not a reason to take the application down with it.
                    ephemeral.delete()
                    onFailure(recoveryVaultFailed)
                    return@launch
                }
            when (outcome) {
                is PortableRestore.Recovered -> {
                    val master = ephemeral.load()?.copyOf()
                    ephemeral.delete()
                    if (master == null) {
                        onFailure(recoveryVaultFailed)
                    } else {
                        message = null
                        phase =
                            Phase.SetPassword(
                                outcome.vault,
                                master,
                                resume = resume,
                                fromImport = true,
                            )
                    }
                }

                PortableRestore.KeyOnly -> {
                    ephemeral.delete()
                    onFailure(recoveryKeyOnly)
                }
                PortableRestore.BareVault -> {
                    ephemeral.delete()
                    onFailure(recoveryBareVault)
                }
                PortableRestore.VaultFailed -> {
                    ephemeral.delete()
                    onFailure(recoveryVaultFailed)
                }
                PortableRestore.Rejected -> {
                    ephemeral.delete()
                    onFailure(recoveryRejected)
                }
            }
        }
    }

    /** Restores an account from a portable file chosen on the login screen. */
    fun restoreFrom(file: ByteArray, passphrase: String) {
        restorePortable(file, passphrase, resume = null, currentVaultData = null) { message = it }
    }

    /**
     * Restores an account from a portable file chosen in the settings.
     *
     * The vault that was open is kept as the way back: the import is not written until the
     * password screen confirms, so cancelling must return to that vault rather than to a first
     * run that would invite the user to create over it.
     */
    fun restoreFromSettings(file: ByteArray, passphrase: String, onResult: (String?) -> Unit) {
        val current = vault
        restorePortable(
            file,
            passphrase,
            resume = current,
            currentVaultData = current?.serialize(),
        ) { onResult(it) }
    }

    /**
     * Where a master key is written, or `null` when this machine has no platform key store.
     *
     * Read once and handed to the screens rather than read again by each of them: it is the
     * same store for the life of this composition, and a machine without one is a state the
     * login screen says out loud instead of offering actions that would fail.
     */
    val store = storage?.masterKeyStore

    LaunchedEffect(Unit) { openExisting() }

    // A launch starts with the platform gesture rather than with a tap: the user opened the
    // application in order to get in, and the password is the way back in, not the first step.
    //
    // A lock taken while the application is running does not do this. Those states carry a
    // [Status] — the lock button and the idle timeout both produce one — while a launch has
    // no reason at all, which is exactly the difference. A prompt that appears under the
    // user's hands while they are already looking at the screen is a surprise rather than a
    // convenience, and the timeout in particular fires when they have walked away.
    var launchGestureAttempted by remember { mutableStateOf(false) }
    LaunchedEffect(phase, active) {
        if (launchGestureAttempted || !active) return@LaunchedEffect
        val current = phase as? Phase.Login ?: return@LaunchedEffect
        val locked = current.mode == LoginMode.PasswordLocked || current.mode == LoginMode.Locked
        if (!locked || current.reason != null || !biometricEnabled) return@LaunchedEffect
        launchGestureAttempted = true
        // Asked without prompting: a device whose credential was removed (a new enrolment, a
        // hardware reset) would otherwise open the application with a failure message the user
        // has not asked for. The button on the screen still reports that case when they tap it.
        val available =
            withContext(Dispatchers.Default) {
                Qualia.authBoundKeyStore(authSealer).availability is AuthAvailability.Available
            }
        if (available) unlockWithBiometric()
    }

    LaunchedEffect(phase, active) {
        // A locked device is not a vault to time out, and a picker in front of the application
        // is not the user leaving it: in both cases the countdown either does not start or
        // starts again when the application is back in front.
        if (phase !is Phase.Main || !active) return@LaunchedEffect
        delay(SESSION_TIMEOUT_SECONDS.seconds)
        lock(Status.LockedAfterTimeout(SESSION_TIMEOUT_SECONDS))
    }

    // Read once per vault: opening the platform identity is a key store access, and a
    // hardware one on the machines that have it.
    val identity = remember(vault) { vault?.let { Qualia.deviceIdentity(DEVICE_LABEL) } }

    // What protects the master key, as the platform store reports it rather than as this
    // build assumes: the first-run warning follows the answer. It comes from the store, so a
    // machine whose store cannot hold a key-encryption key is reported as unprotected — which
    // is what the user is being asked to accept, and what every action on that screen fails
    // on anyway.
    val protection =
        remember(store) {
            store?.let { MasterKeyProtection(it.backend, it.isHardwareBacked) }
                ?: MasterKeyProtection(backend = "unavailable", hardwareBacked = false)
        }

    CompositionLocalProvider(
        LocalAppLocale provides language.tag,
        LocalCodeScanner provides scanner,
    ) {
        key(language) {
            QualiaTheme(theme = theme) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .clearFocusOnTap(),
                ) {
                    when (val current = phase) {
                        Phase.Loading -> LoadingScreen()

                        is Phase.Login ->
                            LoginScreen(
                                mode = current.mode,
                                reason = current.reason,
                                hardwareBacked = protection.hardwareBacked,
                                message = message,
                                language = language,
                                onSelectLanguage = selectLanguage,
                                onCreateVault = ::prepareNewVault,
                                onJoin = {
                                    if (storage == null || store == null) {
                                        message = noKeyStore
                                    } else {
                                        message = null
                                        phase = Phase.Joining
                                    }
                                },
                                onUnlock = { scope.launch { openExisting() } },
                                onUnlockWithPassword = ::unlockWithPassword,
                                passwordAvailable = current.passwordAvailable,
                                biometricAvailable = biometricEnabled,
                                onUnlockWithBiometric = ::unlockWithBiometric,
                                // The way out of the two states where this device holds
                                // something it cannot use: the settings cannot be reached
                                // without an open vault, and those states are exactly the ones
                                // that have none.
                                onReset = { resetDevice() },
                                onRecover = ::restoreFrom,
                            )

                        is Phase.SetPassword ->
                            SetPasswordScreen(
                                message = message,
                                onConfirm = ::completeLockSetup,
                                onCancel = ::cancelLockSetup,
                                explainer = if (current.fromImport) setPasswordAfterImport else null,
                            )

                        Phase.Joining ->
                            JoinAccountScreen(
                                storage = storage,
                                onJoined = { joined, master ->
                                    // The account's entries arrive by a sync rather than by the
                                    // login itself, but the lock is written before the vault is:
                                    // a joined device must not spend even one session without
                                    // the password it will need after a restart.
                                    phase = Phase.SetPassword(joined, master)
                                },
                                onCancel = {
                                    vault = null
                                    phase = Phase.Login(LoginMode.FirstRun, null)
                                },
                            )

                        is Phase.Main -> {
                            val currentVault = vault
                            if (currentVault == null) {
                                // The vault is dropped only by locking, which also changes the
                                // phase; this is unreachable and says so rather than showing an
                                // empty screen.
                                phase = Phase.Login(LoginMode.Locked, null)
                            } else {
                                MainScreen(
                                    tab = current.tab,
                                    onSelectTab = { selected -> phase = Phase.Main(selected) },
                                ) {
                                    when (current.tab) {
                                        Tab.TwoFactor ->
                                            TwoFactorScreen(
                                                vault = currentVault,
                                                onPersist = {
                                                    currentVault.serialize()?.let { storage?.saveVault(it) }
                                                },
                                            )

                                        Tab.Sync ->
                                            SyncScreen(
                                                vault = currentVault,
                                                deviceId = identity?.id,
                                                onChanged = {
                                                    currentVault.serialize()?.let { storage?.saveVault(it) }
                                                },
                                            )

                                        Tab.Settings ->
                                            SettingsScreen(
                                                vault = currentVault,
                                                protection = protection,
                                                identity = identity,
                                                lockEnabled = lockEnabled,
                                                biometricEnabled = biometricEnabled,
                                                language = language,
                                                onSelectLanguage = selectLanguage,
                                                theme = theme,
                                                onSelectTheme = selectTheme,
                                                onLock = { lock(Status.Locked) },
                                                onEnableBiometric = ::enableBiometric,
                                                onDisableBiometric = ::disableBiometric,
                                                onChangePassword = ::changePassword,
                                                onRestorePortable = ::restoreFromSettings,
                                                onImported = { imported ->
                                                    // The account changed under this device: the
                                                    // file on disk belongs to the old one, so the
                                                    // new vault is written before anything reads it.
                                                    imported.serialize()?.let { storage?.saveVault(it) }
                                                    vault = imported
                                                    phase = Phase.Main(Tab.TwoFactor)
                                                },
                                                onReset = { resetDevice() },
                                            )
                                    }
                                }
                            }
                        }
                    }

                    // The scanner covers the screen that asked for a code, rather than
                    // replacing it: what that screen is holding — a half-typed code, a
                    // message — is still there when the scanner closes.
                    scanning?.let { request ->
                        ScanScreen(
                            request = request,
                            picker = picker,
                            onDismiss = { scanning = null },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Writes the password wrap, the vault and the marker for a vault that is being set up.
 *
 * Returns `true` only when the wrap was verified, the legacy platform key is gone and the
 * marker and pending files are in their final state. The password wrap is computed before any
 * file is touched, so a crash during Argon2id leaves nothing behind; after that the pending
 * file marks the window in which the next start must finish the operation.
 */
internal fun writeLockFiles(
    device: VaultStorage,
    vaultData: ByteArray,
    masterKey: ByteArray,
    password: String,
): Boolean {
    val now = currentTimeSeconds()
    val pending = Qualia.lockPending(now) ?: return false
    val wrap = Qualia.wrapMasterKeyWithPassword(masterKey, password) ?: return false
    return try {
        device.writeLockFile(LockFile.Pending, pending)
        device.writeLockFile(LockFile.PasswordWrap, wrap)
        if (!Qualia.verifyPasswordWrap(wrap, password, masterKey)) {
            device.deleteLockFile(LockFile.PasswordWrap)
            device.deleteLockFile(LockFile.Pending)
            return false
        }
        device.saveVault(vaultData)
        device.masterKeyStore?.delete()
        if (device.masterKeyStore?.load() != null) return false
        val marker = Qualia.lockMarker(true, false, now) ?: return false
        device.writeLockFile(LockFile.Marker, marker)
        device.deleteLockFile(LockFile.Pending)
        true
    } catch (_: Exception) {
        false
    }
}

/** How long an open vault stays open with no way for the user to extend it. */
private const val SESSION_TIMEOUT_SECONDS = 900

/**
 * How long the loading screen stays up, at least.
 *
 * Long enough to be read as a screen rather than as a flicker, and short enough not to be a
 * wait of its own.
 */
private val LOADING_MINIMUM = 1.seconds

/**
 * The label this device's identity is kept under in the platform key store.
 *
 * A device has one identity, and it is the same one whatever label is used to look it up;
 * the label exists so that the store's entries can be told apart from other applications'.
 */
internal const val DEVICE_LABEL = "qualia2fa-device"
