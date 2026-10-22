package dev.qualia.qualia2fa

/**
 * Which of the lock files a device holds.
 *
 * The distinction is **fail-closed**: a device with a marker and no usable wrap is not a
 * legacy device, and it must not be opened without authentication. The states below exist so
 * that "never had a lock" and "had one and lost its material" cannot be confused.
 */
sealed interface LockState {
    /**
     * No lock file at all: a 0.3.0 installation or a device that never set a lock.
     *
     * The legacy platform key store (or a first run) decides what happens next.
     */
    data object Legacy : LockState

    /**
     * The lock is enabled and at least one wrap is usable.
     *
     * [password] and [biometric] describe what is actually present now, not what the marker
     * recorded: a wrap deleted after the marker was written shows as absent here.
     */
    data class Enabled(
        val password: Boolean,
        val biometric: Boolean,
        val pending: Boolean = false,
    ) : LockState

    /**
     * A lock file exists but the state cannot be trusted.
     *
     * The application reports this and offers restore/reset; it must never open the vault
     * without authentication.
     */
    data class Broken(val reason: BrokenLock) : LockState
}

/** Why a locked device cannot be trusted to have a usable way in. */
enum class BrokenLock {
    /** `lock.marker` is not a marker this build understands. */
    MarkerUnreadable,

    /** `lock.pending` is not a pending marker this build understands. */
    PendingUnreadable,

    /** An operation was interrupted; the next start must finish or roll it back. */
    PendingInProgress,

    /** The marker says the lock is enabled, but neither wrap is usable. */
    MissingWraps,

    /** A wrap exists with no marker: a partial state that must not be treated as legacy. */
    OrphanLockFile,
}

/**
 * Reads the lock files from [storage] and decides which state the device is in.
 *
 * This only **reads**: it does not prompt, does not unwrap anything and does not touch the
 * platform key store. In particular it never runs Argon2id; [Qualia.isLockPasswordWrap] is the
 * cheap header check.
 */
fun detectLockState(storage: VaultStorage): LockState {
    val marker = storage.readLockFile(LockFile.Marker)
    val pending = storage.readLockFile(LockFile.Pending)
    val passwordWrap = storage.readLockFile(LockFile.PasswordWrap)
    val biometricWrap = storage.readLockFile(LockFile.BiometricWrap)

    if (pending != null) {
        if (!Qualia.isLockPending(pending)) {
            return LockState.Broken(BrokenLock.PendingUnreadable)
        }
        // A crash after a wrap was written but before the marker is recoverable: the wrap
        // still opens the vault, and finishing the operation is what the next unlock does.
        // Without a usable wrap there is nothing to finish, and the state fails closed.
        val password = passwordWrap != null && Qualia.isLockPasswordWrap(passwordWrap)
        val biometric = biometricWrap != null && biometricWrap.isNotEmpty()
        return if (password || biometric) {
            LockState.Enabled(password = password, biometric = biometric, pending = true)
        } else {
            LockState.Broken(BrokenLock.PendingInProgress)
        }
    }

    if (marker != null) {
        if (!Qualia.isLockMarker(marker)) {
            return LockState.Broken(BrokenLock.MarkerUnreadable)
        }
        val password = passwordWrap != null && Qualia.isLockPasswordWrap(passwordWrap)
        val biometric = biometricWrap != null && biometricWrap.isNotEmpty()
        return if (password || biometric) {
            LockState.Enabled(password = password, biometric = biometric)
        } else {
            LockState.Broken(BrokenLock.MissingWraps)
        }
    }

    if (passwordWrap != null || biometricWrap != null) {
        return LockState.Broken(BrokenLock.OrphanLockFile)
    }

    return LockState.Legacy
}
