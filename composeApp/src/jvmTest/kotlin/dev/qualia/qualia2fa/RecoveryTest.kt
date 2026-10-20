package dev.qualia.qualia2fa

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The `.q2fa` backups: plain, passphrase-protected, and the old key-only file.
 *
 * The container carries the master key **and** the vault, because either alone restores nothing.
 * These tests pin each shape, which shapes need a passphrase, and - the part that is easy to get
 * wrong - that a failed restore leaves the device on the key it had.
 */
class RecoveryTest {
    /** The passphrase the files below are exported with. */
    private val passphrase = "correct horse battery staple"

    private fun vault(): Vault = assertNotNull(Qualia.createLocalVault(MemoryStore()))

    private fun add(vault: Vault, name: String, secret: String): String =
        assertNotNull(
            vault.addEntry(name, "Example", "alice", secret, OtpAlgorithm.Sha1, 6, EntryKind.Totp(30), "", 1_700_000_000L),
        )

    @Test
    fun aPlainVaultBackupRestoresOnACleanDevice() {
        val vault = vault()
        add(vault, "GitHub", "JBSWY3DPEHPK3PXP")
        val file = assertNotNull(vault.export(ExportFormat.Vault), "the plain backup must be written")
        assertEquals(PortableKind.PlainVault, Qualia.detectPortableKind(file))

        val store = MemoryStore()
        val restored = restorePortableFile(file, "", store, currentVaultData = null)
        val recovered = assertIs<PortableRestore.Recovered>(restored, "a plain backup must restore, got $restored")
        assertEquals(1, recovered.vault.entryCount, "the entries come with it")
    }

    @Test
    fun anEncryptedVaultBackupNeedsItsPassphrase() {
        val vault = vault()
        add(vault, "GitHub", "JBSWY3DPEHPK3PXP")
        val file =
            assertNotNull(
                vault.export(ExportFormat.EncryptedVault, passphrase),
                "the encrypted backup must be written",
            )
        assertEquals(PortableKind.EncryptedVault, Qualia.detectPortableKind(file))

        val rejected = restorePortableFile(file, "wrong", MemoryStore(), currentVaultData = null)
        assertIs<PortableRestore.Rejected>(rejected, "a wrong passphrase must not open it, got $rejected")

        val restored = restorePortableFile(file, passphrase, MemoryStore(), currentVaultData = null)
        val recovered = assertIs<PortableRestore.Recovered>(restored, "the right passphrase opens it, got $restored")
        assertEquals(1, recovered.vault.entryCount)
    }

    @Test
    fun anOldKeyOnlyFileReopensTheVaultAlreadyOnTheDevice() {
        val store = MemoryStore()
        val vault = assertNotNull(Qualia.createLocalVault(store))
        add(vault, "GitHub", "JBSWY3DPEHPK3PXP")
        val current = assertNotNull(vault.serialize(), "the local vault must be written")

        val recovery = assertNotNull(vault.recoveryFile(passphrase), "the old key-only file must be written")
        assertEquals(110, recovery.size, "the legacy recovery file is the core's fixed-size one")
        assertEquals(PortableKind.KeyOnly, Qualia.detectPortableKind(recovery))

        val restored = restorePortableFile(recovery, passphrase, store, currentVaultData = current)
        val recovered = assertIs<PortableRestore.Recovered>(restored, "the matching vault must open, got $restored")
        assertEquals(1, recovered.vault.entryCount)
    }

    @Test
    fun anOldKeyOnlyFileWithoutAVaultChangesNothing() {
        val vault = vault()
        val recovery = assertNotNull(vault.recoveryFile(passphrase), "the core recovery file must be written")
        val store = MemoryStore()
        val outcome = restorePortableFile(recovery, passphrase, store, currentVaultData = null)
        assertEquals(PortableRestore.KeyOnly, outcome)
        assertEquals(null, store.load(), "nothing may be written when there is nothing it could open")
    }

    @Test
    fun aBareVaultFileIsRefusedWithItsOwnAnswer() {
        val vault = vault()
        add(vault, "GitHub", "JBSWY3DPEHPK3PXP")
        val bare = assertNotNull(vault.serialize())
        assertEquals(null, Qualia.detectPortableKind(bare), "a bare vault file is not a portable container")
        assertEquals(
            PortableRestore.BareVault,
            restorePortableFile(bare, "", MemoryStore(), currentVaultData = null),
        )
    }

    @Test
    fun aDamagedBackupLeavesTheKeyTheDeviceHad() {
        val other = vault()
        add(other, "Other", "GEZDGNBVGY3TQOJQ")
        val otherFile =
            assertNotNull(other.export(ExportFormat.EncryptedVault, passphrase), "the backup must be written")
        assertEquals(PortableKind.EncryptedVault, Qualia.detectPortableKind(otherFile))

        // Swap the first two bytes of the vault the container carries. The wrapped key still
        // opens, so the restore gets far enough to discover that the pair does not belong
        // together - the case the rollback exists for.
        val headerLength = 8 + 1 + 4 + 4
        val recoveryLength =
            ((otherFile[9].toInt() and 0xFF) shl 24) or
                ((otherFile[10].toInt() and 0xFF) shl 16) or
                ((otherFile[11].toInt() and 0xFF) shl 8) or
                (otherFile[12].toInt() and 0xFF)
        val damaged = otherFile.copyOf()
        damaged[headerLength + recoveryLength] = 1
        damaged[headerLength + recoveryLength + 1] = 2

        val existingStore = MemoryStore()
        val existing = assertNotNull(Qualia.createLocalVault(existingStore))
        add(existing, "Mine", "JBSWY3DPEHPK3PXP")
        val before = assertNotNull(existingStore.load())
        val beforeCopy = before.copyOf()

        val outcome = restorePortableFile(damaged, passphrase, existingStore, currentVaultData = null)
        assertEquals(PortableRestore.VaultFailed, outcome)
        assertTrue(
            beforeCopy.contentEquals(assertNotNull(existingStore.load())),
            "the device's key must be the one it had before the attempt",
        )
    }
}
