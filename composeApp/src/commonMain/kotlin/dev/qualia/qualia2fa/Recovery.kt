package dev.qualia.qualia2fa

/**
 * The recovery file the application writes, and the two ways it can be read.
 *
 * # Why this container exists
 *
 * The core's recovery file is a self-contained, 110-byte wrapping of the **master key** only:
 * it deliberately carries no entries (see `Core/src/recovery.rs`). The master key is useless
 * without the encrypted vault file, and a vault file is useless without the key. A recovery
 * file that carries one but not the other therefore cannot restore an account on its own.
 *
 * The application therefore stores both together:
 *
 * ```text
 * magic "Q2FABK01" (8) | version (1) | recovery length (4, big-endian)
 * | vault length (4, big-endian) | core recovery file | vault file
 * ```
 *
 * Nothing new is encrypted here. The first half is the core's passphrase-protected master key;
 * the second half is the vault ciphertext, already encrypted under that master key. An attacker
 * still has to break the passphrase to get either plaintext.
 *
 * # Old files are still read
 *
 * The first release of this screen wrote the core's 110-byte key-only file under the same
 * `.q2fa` name. [RecoveryBackup.KeyOnly] is that shape: it can still recover the master key and
 * open the vault already on this device if the two match, but it carries no entries, so it
 * cannot bring them to a device that does not have that vault.
 */

/** The name a recovery file is offered under; the user may change it. */
internal const val RECOVERY_FILE_NAME = "qualia2fa-recovery.q2fa"

/** The extension a recovery file is offered under. */
internal const val RECOVERY_FILE_EXTENSION = "q2fa"

/** What a file chosen as a recovery file turned out to be. */
internal sealed interface RecoveryBackup {
    /** The application's bundle: the core recovery file plus the encrypted vault. */
    class Full(val recovery: ByteArray, val vault: ByteArray) : RecoveryBackup

    /** The old, key-only core recovery file. */
    class KeyOnly(val recovery: ByteArray) : RecoveryBackup

    /** Not a recovery file of either shape, or one this version cannot read. */
    data object Invalid : RecoveryBackup
}

/** The outcome of trying to restore an account from a recovery file. */
internal sealed interface RecoveryRestore {
    /** The master key was recovered and [vault] holds the entries. */
    data class Recovered(val vault: Vault) : RecoveryRestore

    /**
     * The file is an old key-only recovery file and there is no vault it can open here.
     *
     * The device's key and vault are left as they were: there is nothing this file can add.
     */
    data object KeyOnly : RecoveryRestore

    /** The passphrase, the file, or the format is wrong. Nothing changed. */
    data object Rejected : RecoveryRestore

    /**
     * The master key was recovered, but the vault the file carries did not open with it.
     *
     * The previous key was put back, so the device is left as it was.
     */
    data object VaultFailed : RecoveryRestore
}

/** Reads and writes the application's recovery container. */
internal object RecoveryBackupFormat {
    /** The application container's magic. */
    private val MAGIC = "Q2FABK01".encodeToByteArray()

    /** The core's key-only recovery file magic and fixed length. */
    private val LEGACY_MAGIC = "Q2FAREC\u0000".encodeToByteArray()
    private const val LEGACY_FILE_LEN = 110

    /** The only container version this build writes. */
    private const val VERSION = 1

    /** The fixed part before the two payloads: magic, version and two lengths. */
    private const val HEADER_LEN = 8 + 1 + 4 + 4

    /** The largest vault payload accepted, in bytes. */
    private const val MAX_VAULT_BYTES = 64 * 1024 * 1024

    /** The largest key-only recovery payload accepted, in bytes. */
    private const val MAX_RECOVERY_BYTES = 4 * 1024

    /**
     * Packs the core recovery file and the vault ciphertext into one container.
     *
     * Returns `null` when either payload is empty or too large; the caller reports the export
     * as failed rather than writing a container this application would not read back.
     */
    fun pack(recovery: ByteArray, vault: ByteArray): ByteArray? {
        if (recovery.isEmpty() || recovery.size > MAX_RECOVERY_BYTES) return null
        if (vault.isEmpty() || vault.size > MAX_VAULT_BYTES) return null

        val out = ByteArray(HEADER_LEN + recovery.size + vault.size)
        MAGIC.copyInto(out, 0)
        out[8] = VERSION.toByte()
        writeLength(out, 9, recovery.size)
        writeLength(out, 13, vault.size)
        recovery.copyInto(out, HEADER_LEN)
        vault.copyInto(out, HEADER_LEN + recovery.size)
        return out
    }

    /** Reads a file chosen as a recovery file. */
    fun parse(bytes: ByteArray): RecoveryBackup {
        if (bytes.size == LEGACY_FILE_LEN && bytes.startsWith(LEGACY_MAGIC)) {
            return RecoveryBackup.KeyOnly(bytes.copyOf())
        }
        if (bytes.size < HEADER_LEN || !bytes.startsWith(MAGIC)) return RecoveryBackup.Invalid
        if ((bytes[8].toInt() and 0xFF) != VERSION) return RecoveryBackup.Invalid

        val recoveryLength = readLength(bytes, 9)
        val vaultLength = readLength(bytes, 13)
        if (recoveryLength <= 0 || recoveryLength > MAX_RECOVERY_BYTES) return RecoveryBackup.Invalid
        if (vaultLength <= 0 || vaultLength > MAX_VAULT_BYTES) return RecoveryBackup.Invalid

        val total = HEADER_LEN.toLong() + recoveryLength + vaultLength
        if (total != bytes.size.toLong()) return RecoveryBackup.Invalid

        val recoveryStart = HEADER_LEN
        val vaultStart = recoveryStart + recoveryLength
        return RecoveryBackup.Full(
            recovery = bytes.copyOfRange(recoveryStart, vaultStart),
            vault = bytes.copyOfRange(vaultStart, bytes.size),
        )
    }

    /** Whether [bytes] begins with [prefix]. */
    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    /** Reads a big-endian 32-bit length. */
    private fun readLength(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)

    /** Writes a big-endian 32-bit length. */
    private fun writeLength(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value ushr 24).toByte()
        bytes[at + 1] = (value ushr 16).toByte()
        bytes[at + 2] = (value ushr 8).toByte()
        bytes[at + 3] = value.toByte()
    }
}

/**
 * Builds the file written by "export a recovery file".
 *
 * The master key is wrapped by the core with [passphrase]; the vault bytes are the encrypted
 * file the core already writes to disk. Both are needed and neither is useful alone.
 */
internal fun buildRecoveryBackup(vault: Vault, passphrase: String): ByteArray? {
    val recovery = vault.recoveryFile(passphrase) ?: return null
    val data = vault.serialize()
    if (data == null) {
        wipe(recovery)
        return null
    }
    val bundle = RecoveryBackupFormat.pack(recovery, data)
    wipe(recovery)
    return bundle
}

/**
 * Restores an account from [file].
 *
 * [currentVaultData] is the vault file this device already has, when it has one. It is what an
 * old key-only recovery file can open: that file carries no entries of its own. A full bundle
 * ignores it and opens the vault the bundle carries.
 *
 * The recovered master key is written to [store] before the vault is opened (the vault can only
 * be opened by reading the key back from the store). If the vault then fails to open for any
 * reason, the previous key is put back — or removed, when there was none — so a damaged or
 * mismatched file cannot strand the device on a key that opens nothing.
 */
internal fun restoreRecoveryFile(
    file: ByteArray,
    passphrase: String,
    store: MasterKeyStore,
    currentVaultData: ByteArray?,
): RecoveryRestore {
    val backup = RecoveryBackupFormat.parse(file)
    if (backup is RecoveryBackup.Invalid) return RecoveryRestore.Rejected

    // An old key-only file has no entries and no vault to fall back on: writing its key would
    // only replace a working key with one that has nothing to open.
    if (backup is RecoveryBackup.KeyOnly && currentVaultData == null) return RecoveryRestore.KeyOnly

    val previous = runCatching { store.load()?.copyOf() }.getOrNull()
    val recovered =
        Qualia.importRecoveryFile(
            file =
                when (backup) {
                    is RecoveryBackup.Full -> backup.recovery
                    is RecoveryBackup.KeyOnly -> backup.recovery
                    RecoveryBackup.Invalid -> return RecoveryRestore.Rejected
                },
            passphrase = passphrase,
            store = store,
        )
    if (recovered == null) {
        previous?.let(::wipe)
        return RecoveryRestore.Rejected
    }

    val vaultData =
        when (backup) {
            is RecoveryBackup.Full -> backup.vault
            is RecoveryBackup.KeyOnly -> currentVaultData
            RecoveryBackup.Invalid -> null
        }
    val vault = vaultData?.let { Qualia.openVault(it, store) }
    if (vault == null) {
        if (previous != null) {
            runCatching { store.store(previous) }
        } else {
            runCatching { store.delete() }
        }
        previous?.let(::wipe)
        return if (backup is RecoveryBackup.KeyOnly) RecoveryRestore.KeyOnly else RecoveryRestore.VaultFailed
    }

    previous?.let(::wipe)
    return RecoveryRestore.Recovered(vault)
}
