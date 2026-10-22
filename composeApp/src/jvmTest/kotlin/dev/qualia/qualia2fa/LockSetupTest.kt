package dev.qualia.qualia2fa

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one operation that turns a legacy or fresh device into a locked one.
 *
 * The write order is the whole point: the wrap must verify, the legacy platform key must be
 * gone, and the marker must be last. These tests use the real files and the real native
 * library, so "the lock was written" means "a restart can read it".
 */
class LockSetupTest {
    /** A storage in a fresh directory, with its legacy key store. */
    private fun storage(
        directory: java.nio.file.Path = Files.createTempDirectory("qualia2fa-lock-setup"),
    ): Pair<FileVaultStorage, MemoryStore> {
        val store = MemoryStore()
        return FileVaultStorage(directory, store) to store
    }

    /** A vault and the master key the legacy store holds for it. */
    private fun legacyVault(store: MemoryStore): Triple<Vault, ByteArray, ByteArray> {
        val vault = assertNotNull(Qualia.createLocalVault(store))
        val master = assertNotNull(store.load())
        val data = assertNotNull(vault.serialize())
        return Triple(vault, master, data)
    }

    /** A successful setup leaves the marker, no legacy key, and a vault the password opens. */
    @Test
    fun setupWritesTheLockAndRemovesTheLegacyKey() {
        val (storage, store) = storage()
        val (_, master, data) = legacyVault(store)
        storage.saveVault(data)

        assertTrue(writeLockFiles(storage, data, master, "password123"))

        assertNull(store.load(), "the no-prompt legacy key must be gone")
        assertEquals(
            LockState.Enabled(password = true, biometric = false),
            detectLockState(storage),
        )
        assertNotNull(storage.readLockFile(LockFile.Marker))
        assertNull(storage.readLockFile(LockFile.Pending))
        assertNotNull(storage.readLockFile(LockFile.PasswordWrap))

        val wrap = assertNotNull(storage.readLockFile(LockFile.PasswordWrap))
        assertTrue(Qualia.verifyPasswordWrap(wrap, "password123", master))
        assertFalse(Qualia.verifyPasswordWrap(wrap, "not the password", master))

        val opened = Qualia.openVaultWithPassword(data, wrap, "password123")
        assertIs<Qualia.PasswordOpen.Opened>(opened)
        assertEquals(0, opened.vault.entryCount)

        assertIs<Qualia.PasswordOpen.WrongPassword>(
            Qualia.openVaultWithPassword(data, wrap, "not the password"),
        )
    }

    /** A wrong vault next to a correct wrap is reported separately from a wrong password. */
    @Test
    fun aVaultFromAnotherAccountIsReportedAsAMismatch() {
        val (storage, store) = storage()
        val (_, master, data) = legacyVault(store)
        assertTrue(writeLockFiles(storage, data, master, "password123"))
        val wrap = assertNotNull(storage.readLockFile(LockFile.PasswordWrap))

        val damaged = data.copyOf()
        damaged[damaged.size - 1] = (damaged[damaged.size - 1].toInt() xor 0xFF).toByte()
        assertIs<Qualia.PasswordOpen.VaultMismatch>(
            Qualia.openVaultWithPassword(damaged, wrap, "password123"),
        )
    }

    /** An empty password never writes a wrap or a marker. */
    @Test
    fun anEmptyPasswordLeavesTheDeviceAlone() {
        val (storage, store) = storage()
        val (_, master, data) = legacyVault(store)
        storage.saveVault(data)

        assertFalse(writeLockFiles(storage, data, master, ""))

        assertIs<LockState.Legacy>(detectLockState(storage))
        assertNull(storage.readLockFile(LockFile.Marker))
        assertNull(storage.readLockFile(LockFile.PasswordWrap))
        assertNull(storage.readLockFile(LockFile.Pending))
    }

    /** The wrap and marker survive a reopen through a new storage object. */
    @Test
    fun theLockSurvivesAReopen() {
        val directory = Files.createTempDirectory("qualia2fa-lock-reopen")
        val (storage, store) = storage(directory)
        val (_, master, data) = legacyVault(store)
        assertTrue(writeLockFiles(storage, data, master, "password123"))

        val reopened = FileVaultStorage(directory, MemoryStore())
        assertEquals(
            LockState.Enabled(password = true, biometric = false),
            detectLockState(reopened),
        )
    }

    /** Changing the password replaces the wrap, and the old one stops opening it. */
    @Test
    fun changingThePasswordReplacesTheWrap() {
        val (storage, store) = storage()
        val (vault, master, data) = legacyVault(store)
        assertTrue(writeLockFiles(storage, data, master, "password123"))

        val oldWrap = assertNotNull(storage.readLockFile(LockFile.PasswordWrap))
        val opened =
            assertIs<Qualia.PasswordOpen.Opened>(
                Qualia.openVaultWithPassword(data, oldWrap, "password123"),
            )

        val changed = opened.vault.rewrapLockPassword(oldWrap, "password123", "newpassword")
        val wrapped = assertIs<PasswordChange.Wrapped>(changed)
        storage.writeLockFile(LockFile.PasswordWrap, wrapped.wrap)

        assertIs<Qualia.PasswordOpen.WrongPassword>(
            Qualia.openVaultWithPassword(data, wrapped.wrap, "password123"),
        )
        assertIs<Qualia.PasswordOpen.Opened>(
            Qualia.openVaultWithPassword(data, wrapped.wrap, "newpassword"),
        )

        assertIs<PasswordChange.WrongPassword>(
            opened.vault.rewrapLockPassword(oldWrap, "not the password", "another"),
        )
    }
}
