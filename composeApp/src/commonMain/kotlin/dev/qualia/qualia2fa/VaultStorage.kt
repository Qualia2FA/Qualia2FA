package dev.qualia.qualia2fa

/**
 * The files that belong to the application lock, beside the vault.
 *
 * The names are part of the on-disk contract. The two wraps are independent ways into the
 * same master key; `lock.marker` and `lock.pending` say whether the lock exists and whether
 * an operation on it was interrupted.
 */
enum class LockFile(val fileName: String) {
    /** The platform-authentication wrap (`master.key.bio`). */
    BiometricWrap("master.key.bio"),

    /** The password wrap (`master.key.pw`). */
    PasswordWrap("master.key.pw"),

    /** The lock is enabled; contains no secret. */
    Marker("lock.marker"),

    /** A lock operation is in progress; contains no secret. */
    Pending("lock.pending"),
}

/**
 * Where the vault file and the master key live.
 *
 * The user interface works through this interface, so the same screens run on any
 * platform once a platform implementation exists.
 */
interface VaultStorage {
    /** The vault file contents, or `null` when the device has no vault yet. */
    fun loadVault(): ByteArray?

    /** Writes the vault file, replacing any previous contents. */
    fun saveVault(data: ByteArray)

    /** Reads one of the lock files, or `null` when it is absent. */
    fun readLockFile(file: LockFile): ByteArray?

    /**
     * Writes one of the lock files atomically.
     *
     * An interrupted write leaves either the previous contents or the new ones, never half
     * of each: a truncated wrap is indistinguishable from a damaged one and would send the
     * user to a recovery path they may not need.
     */
    fun writeLockFile(file: LockFile, data: ByteArray)

    /** Deletes one of the lock files; deleting an absent file is not an error. */
    fun deleteLockFile(file: LockFile)

    /** Deletes every lock file. */
    fun deleteLockState() {
        LockFile.entries.forEach(::deleteLockFile)
    }

    /**
     * Removes the vault file, the legacy master key and every lock file, leaving this device
     * as it was before it was registered.
     *
     * Removing something that is absent is not an error: a device that was never registered
     * has nothing to remove, and asking for a reset is still a reasonable thing to have done.
     */
    fun reset()

    /**
     * The master key store of this device, or `null` when this machine has none.
     *
     * `null` is a machine whose platform key store cannot hold a key-encryption key: there is
     * nowhere to keep a master key, so there is nothing to register and nothing to unlock. The
     * user interface says so rather than offering a vault that could never be opened again.
     */
    val masterKeyStore: MasterKeyStore?
}

/**
 * What protects the master key, as the login and settings screens show it.
 *
 * [backend] is the platform key store's own name for itself — `"tpm"`, `"android-keystore"`,
 * `"software"`, `"secure-enclave"` — and [hardwareBacked] says whether that name is hardware
 * protection. Both are reported as they are, because the user is being asked to trust them.
 */
data class MasterKeyProtection(
    val backend: String,
    val hardwareBacked: Boolean,
)
