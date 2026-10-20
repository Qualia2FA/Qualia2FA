package dev.qualia.qualia2fa

/**
 * The `.q2fa` backup formats, and the one path that opens them.
 *
 * # One container, two shapes
 *
 * A `.q2fa` file carries the **master key** and the **vault ciphertext** together, because
 * neither is useful alone: the key without the vault opens nothing, and the vault without the key
 * can never be read. There are two shapes of it:
 *
 * - `保险库` (vault): the master key in clear. It restores on a clean device with nothing else,
 *   and whoever holds the file holds the account.
 * - `加密保险库` (encrypted vault): the same pair with the master key wrapped by a passphrase.
 *   This is the shape the 0.2.x "recovery file" wrote, so those files are still read.
 *
 * The old **key-only** file (the core's 110-byte recovery file) is still recognized. It carries
 * the master key but no entries, so it can only reopen a vault this device already has; it writes
 * nothing when there is none, because replacing a working key with one that opens nothing is worse
 * than doing nothing.
 *
 * Nothing is encrypted by this application layer any more: [Qualia.restorePortable] hands the file
 * to the library, which parses the container and opens the vault. What remains here is the
 * key-only path, which has to look at the device's own vault and roll the key back.
 */

/** The extension a `.q2fa` backup carries. */
internal const val PORTABLE_FILE_EXTENSION = "q2fa"

/** The default name a `.q2fa` backup is offered under; the user may change it. */
internal const val PORTABLE_FILE_NAME = "qualia2fa-vault.q2fa"

/**
 * Restores an account from a portable file, whichever of the three shapes it is.
 *
 * [currentVaultData] is the vault file this device already has, when it has one. It is what an
 * old key-only file can open: that file carries no entries of its own.
 *
 * The recovered master key is written to [store] before the vault is opened (the vault can only
 * be opened by reading the key back from the store). If the vault then fails to open for any
 * reason, the previous key is put back - or removed, when there was none - so a damaged or
 * mismatched file cannot strand the device on a key that opens nothing.
 *
 * The `.q2fa` containers do that rollback inside the library, on the same principle; only the
 * key-only path has to do it here, because it needs the local vault file as well.
 */
internal fun restorePortableFile(
    file: ByteArray,
    passphrase: String,
    store: MasterKeyStore,
    currentVaultData: ByteArray?,
): PortableRestore {
    val kind = Qualia.detectPortableKind(file)
    if (kind != PortableKind.KeyOnly) {
        // The library parses, recovers and opens, and puts the previous key back itself. A bare
        // vault file is refused there with its own case.
        return Qualia.restorePortable(file, passphrase, store)
    }

    // An old key-only file has no entries and no vault to fall back on: writing its key would only
    // replace a working key with one that has nothing to open.
    if (currentVaultData == null) return PortableRestore.KeyOnly

    val previous = runCatching { store.load()?.copyOf() }.getOrNull()
    val recovered =
        runCatching { Qualia.importRecoveryFile(file, passphrase, store) }.getOrNull()
    if (recovered == null) {
        previous?.let(::wipe)
        return PortableRestore.Rejected
    }

    val vault = Qualia.openVault(currentVaultData, store)
    if (vault == null) {
        if (previous != null) {
            runCatching { store.store(previous) }
        } else {
            runCatching { store.delete() }
        }
        previous?.let(::wipe)
        return PortableRestore.KeyOnly
    }

    previous?.let(::wipe)
    return PortableRestore.Recovered(vault)
}
