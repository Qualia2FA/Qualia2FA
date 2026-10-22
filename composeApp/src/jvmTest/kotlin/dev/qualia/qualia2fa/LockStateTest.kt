package dev.qualia.qualia2fa

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * What the lock files say about a device, and the fail-closed rule around them.
 *
 * A lock state is read before the vault is; getting it wrong either opens a vault that should
 * have asked for a password or tells a 0.3.0 user that their device is broken. These tests
 * write the actual files a real device would have and ask [detectLockState] what they mean.
 */
class LockStateTest {
    /** A storage with its own temporary directory and an in-memory legacy key store. */
    private fun storage(): FileVaultStorage =
        FileVaultStorage(
            dataDirectory = Files.createTempDirectory("qualia2fa-lock"),
            masterKeyStore = MemoryStore(),
        )

    /** A password wrap for a throwaway master key. */
    private fun passwordWrap(): ByteArray =
        requireNotNull(
            Qualia.wrapMasterKeyWithPassword(ByteArray(Qualia.MASTER_KEY_BYTES) { 7 }, "password123"),
        )

    /** A marker that names the given wraps. */
    private fun marker(password: Boolean, biometric: Boolean): ByteArray =
        requireNotNull(Qualia.lockMarker(password, biometric, 1_700_000_000))

    /** Nothing on disk is a legacy device, not a broken lock. */
    @Test
    fun noLockFilesIsLegacy() {
        assertIs<LockState.Legacy>(detectLockState(storage()))
    }

    /** A marker with a usable password wrap is enabled with the password path. */
    @Test
    fun markerAndPasswordWrapIsEnabled() {
        val storage = storage()
        storage.writeLockFile(LockFile.PasswordWrap, passwordWrap())
        storage.writeLockFile(LockFile.Marker, marker(password = true, biometric = false))

        assertEquals(
            LockState.Enabled(password = true, biometric = false),
            detectLockState(storage),
        )
    }

    /** A device whose only usable wrap is the platform one reports that path only. */
    @Test
    fun markerAndBiometricWrapEnablesTheBiometricPath() {
        val storage = storage()
        storage.writeLockFile(LockFile.BiometricWrap, byteArrayOf(1, 2, 3))
        storage.writeLockFile(LockFile.Marker, marker(password = false, biometric = true))

        assertEquals(
            LockState.Enabled(password = false, biometric = true),
            detectLockState(storage),
        )
    }

    /** A marker with no usable wrap is broken, not legacy. */
    @Test
    fun markerWithoutWrapsIsBroken() {
        val storage = storage()
        storage.writeLockFile(LockFile.Marker, marker(password = true, biometric = false))

        assertEquals(
            LockState.Broken(BrokenLock.MissingWraps),
            detectLockState(storage),
        )
    }

    /** A corrupt password wrap does not count as a usable path. */
    @Test
    fun aCorruptPasswordWrapIsNotUsable() {
        val storage = storage()
        storage.writeLockFile(LockFile.PasswordWrap, byteArrayOf(1, 2, 3))
        storage.writeLockFile(LockFile.Marker, marker(password = true, biometric = false))

        assertEquals(
            LockState.Broken(BrokenLock.MissingWraps),
            detectLockState(storage),
        )
    }

    /** A marker that is not a marker fails closed. */
    @Test
    fun anUnreadableMarkerFailsClosed() {
        val storage = storage()
        storage.writeLockFile(LockFile.Marker, byteArrayOf(1, 2, 3))
        storage.writeLockFile(LockFile.PasswordWrap, passwordWrap())

        assertEquals(
            LockState.Broken(BrokenLock.MarkerUnreadable),
            detectLockState(storage),
        )
    }

    /** An interrupted operation is reported as such, never as legacy. */
    @Test
    fun pendingIsReportedAsInProgress() {
        val storage = storage()
        storage.writeLockFile(
            LockFile.Pending,
            requireNotNull(Qualia.lockPending(1_700_000_000)),
        )

        assertEquals(
            LockState.Broken(BrokenLock.PendingInProgress),
            detectLockState(storage),
        )
    }

    /**
     * A pending operation whose wrap already exists is recoverable.
     *
     * The password still opens the vault; finishing the operation (writing the marker and
     * deleting the pending file) is what the next successful unlock does.
     */
    @Test
    fun pendingWithAWrapIsRecoverable() {
        val storage = storage()
        storage.writeLockFile(LockFile.PasswordWrap, passwordWrap())
        storage.writeLockFile(
            LockFile.Pending,
            requireNotNull(Qualia.lockPending(1_700_000_000)),
        )

        assertEquals(
            LockState.Enabled(password = true, biometric = false, pending = true),
            detectLockState(storage),
        )
    }

    /** A wrap with no marker is a partial state, not a legacy device. */
    @Test
    fun anOrphanWrapFailsClosed() {
        val storage = storage()
        storage.writeLockFile(LockFile.PasswordWrap, passwordWrap())

        assertEquals(
            LockState.Broken(BrokenLock.OrphanLockFile),
            detectLockState(storage),
        )
    }

    /** Reset removes every lock file along with the vault and the legacy key. */
    @Test
    fun resetRemovesTheLockFiles() {
        val storage = storage()
        storage.writeLockFile(LockFile.PasswordWrap, passwordWrap())
        storage.writeLockFile(LockFile.BiometricWrap, byteArrayOf(1))
        storage.writeLockFile(LockFile.Marker, marker(password = true, biometric = true))
        storage.writeLockFile(LockFile.Pending, requireNotNull(Qualia.lockPending(1)))

        storage.reset()

        LockFile.entries.forEach { file ->
            assertNull(storage.readLockFile(file), "${file.fileName} must be gone after a reset")
        }
        assertIs<LockState.Legacy>(detectLockState(storage))
    }
}
