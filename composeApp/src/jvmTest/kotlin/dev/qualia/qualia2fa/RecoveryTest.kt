package dev.qualia.qualia2fa

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exporting and restoring a recovery file, including the vault the entries live in.
 *
 * # What went wrong before these tests
 *
 * The core's recovery file wraps the **master key** and nothing else. The first version of the
 * settings screen wrote that file and, on import, built a brand-new empty vault from the
 * recovered key and saved it over the vault on disk: importing a file that had just been
 * exported therefore produced an empty account. These tests pin both halves of the repair:
 *
 * - a new recovery file carries the encrypted vault as well, and brings its entries back;
 * - an old key-only file can only reopen the vault already on the device, and is refused
 *   without touching anything when there is no such vault.
 */
class RecoveryTest {
    /** The passphrase the files below are exported with. */
    private val passphrase = "correct horse battery staple"

    /** A vault with one entry, and the store holding its master key. */
    private fun vaultWithEntry(): Pair<Vault, MemoryStore> {
        val store = MemoryStore()
        val vault = assertNotNull(Qualia.createLocalVault(store), "the local vault must open")
        assertNotNull(
            vault.addEntry(
                name = "Aurora",
                issuer = "ACME",
                account = "alice",
                secretBase32 = "JBSWY3DPEHPK3PXP",
                algorithm = OtpAlgorithm.Sha1,
                digits = 6,
                kind = EntryKind.Totp(30),
                note = "",
                unixTime = 1_700_000_000L,
            ),
            "the entry must be accepted",
        )
        return vault to store
    }

    /** A fresh store with no key, which is what a device that has lost its key has. */
    private fun emptyStore(): MemoryStore = MemoryStore()

    @Test
    fun aNewRecoveryFileBringsItsEntriesBack() {
        val (vault, _) = vaultWithEntry()
        val file = assertNotNull(buildRecoveryBackup(vault, passphrase), "the backup must be written")
        val store = emptyStore()

        val outcome = restoreRecoveryFile(file, passphrase, store, currentVaultData = null)

        val restored = assertIs<RecoveryRestore.Recovered>(outcome, "a full backup must restore, got $outcome")
        assertEquals(listOf("Aurora"), restored.vault.entries().map { it.name })
        assertTrue(store.exists, "the recovered master key must be written to the store")
    }

    @Test
    fun anOldKeyOnlyFileReopensTheVaultAlreadyOnTheDevice() {
        val (vault, _) = vaultWithEntry()
        val recovery = assertNotNull(vault.recoveryFile(passphrase), "the core recovery file must be written")
        assertEquals(110, recovery.size, "the legacy recovery file is the core's fixed-size one")
        val current = assertNotNull(vault.serialize(), "the current vault must serialize")
        val store = emptyStore()

        val outcome = restoreRecoveryFile(recovery, passphrase, store, currentVaultData = current)

        val restored = assertIs<RecoveryRestore.Recovered>(outcome, "the matching vault must open, got $outcome")
        assertEquals(listOf("Aurora"), restored.vault.entries().map { it.name })
    }

    @Test
    fun anOldKeyOnlyFileWithoutAVaultChangesNothing() {
        val (vault, _) = vaultWithEntry()
        val recovery = assertNotNull(vault.recoveryFile(passphrase), "the core recovery file must be written")
        val store = emptyStore()

        val outcome = restoreRecoveryFile(recovery, passphrase, store, currentVaultData = null)

        assertEquals(RecoveryRestore.KeyOnly, outcome)
        assertFalse(store.exists, "a key-only file with nothing to open must not write a key")
    }

    @Test
    fun aWrongPassphraseChangesNothing() {
        val (vault, _) = vaultWithEntry()
        val file = assertNotNull(buildRecoveryBackup(vault, passphrase), "the backup must be written")
        val store = emptyStore()

        val outcome = restoreRecoveryFile(file, "not the passphrase", store, currentVaultData = null)

        assertEquals(RecoveryRestore.Rejected, outcome)
        assertFalse(store.exists, "a rejected backup must not leave a key behind")
    }

    /**
     * A full file whose vault does not open is refused **and the previous key is put back**.
     *
     * This is the state a damaged download or a file belonging to another account produces.
     * Importing must not leave the device on a key that opens nothing.
     */
    @Test
    fun aDamagedFullBackupRollsTheKeyBack() {
        val (other, _) = vaultWithEntry()
        val file = assertNotNull(buildRecoveryBackup(other, passphrase), "the other backup must be written")
        val parsed = assertIs<RecoveryBackup.Full>(RecoveryBackupFormat.parse(file), "the fixture must be a full backup")
        // A payload that is not a vault at all is a deterministic "does not open" case; a
        // one-byte flip in a real vault could land in a part the format does not authenticate.
        val damaged =
            assertNotNull(
                RecoveryBackupFormat.pack(parsed.recovery, byteArrayOf(1, 2, 3)),
                "the damaged file must pack",
            )

        val (_, existingStore) = vaultWithEntry()
        val previousKey = assertNotNull(existingStore.load(), "the existing store must hold a key").copyOf()

        val outcome = restoreRecoveryFile(damaged, passphrase, existingStore, currentVaultData = null)

        assertEquals(RecoveryRestore.VaultFailed, outcome)
        assertContentEquals(previousKey, existingStore.load(), "the previous key must be restored")
    }

    @Test
    fun theContainerRoundTripsAndRejectsExtraneousBytes() {
        val recovery = ByteArray(110) { it.toByte() }
        val vault = ByteArray(64) { (it + 1).toByte() }
        val packed = assertNotNull(RecoveryBackupFormat.pack(recovery, vault), "the container must pack")
        val parsed = assertIs<RecoveryBackup.Full>(RecoveryBackupFormat.parse(packed), "the container must parse")
        assertContentEquals(recovery, parsed.recovery)
        assertContentEquals(vault, parsed.vault)

        val trailing = packed + byteArrayOf(0)
        assertEquals(RecoveryBackup.Invalid, RecoveryBackupFormat.parse(trailing))
        assertEquals(RecoveryBackup.Invalid, RecoveryBackupFormat.parse(byteArrayOf(1, 2, 3)))
        assertEquals(RecoveryBackup.Invalid, RecoveryBackupFormat.parse(ByteArray(110)))
    }

    @Test
    fun aLegacyFileIsRecognizedByItsMagicAndLength() {
        val legacy = ByteArray(110)
        "Q2FAREC\u0000".encodeToByteArray().copyInto(legacy, 0)
        assertIs<RecoveryBackup.KeyOnly>(RecoveryBackupFormat.parse(legacy), "the old file shape must be recognized")
    }
}
