package dev.qualia.qualia2fa

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exports that really came out of Aegis, read through this application's facade.
 *
 * # Why these files are here as well as in the core crate
 *
 * The core's own tests prove that its reader agrees with the files Aegis writes. This test
 * proves that **the path the application uses** reaches that reader: the format detection, the
 * UniFFI preview call, and the apply step all run here, with the same build an Android install
 * runs. A reader that works in isolation but whose preview the interface cannot even compose
 * still leaves the user unable to import, which is the failure this pins.
 *
 * # The accounts are disposable
 *
 * The files were exported from two throwaway accounts for this purpose; the secrets protect
 * nothing. The password of the encrypted export is the one the core's fixture records.
 */
class AegisImportTest {
    /** Reads one fixture from the test resources. */
    private fun sample(name: String): ByteArray =
        assertNotNull(javaClass.getResourceAsStream("/aegis/$name"), "$name must be on the classpath")
            .use { it.readBytes() }

    /** Reads one fixture and applies it to an empty vault. */
    private fun importedEntries(bytes: ByteArray, format: ImportFormat, password: String? = null): List<String> {
        val parsed =
            assertNotNull(
                Qualia.readImport(bytes, format, password, NOW),
                "$format must be readable through the facade",
            )
        val vault = assertNotNull(Qualia.createLocalVault(MemoryStore()), "the import needs a vault")
        val applied = assertNotNull(parsed.apply(vault, emptyMap()), "$format must apply")
        assertEquals(2, applied.added, "$format must add both entries")
        return vault.entries().map { it.name }.sorted()
    }

    @Test
    fun theAegisJsonExportIsDetectedAndImported() {
        val bytes = sample("plain.json")
        assertEquals(ImportFormat.Json, Qualia.detectImportFormat(bytes), "the content must be recognised")
        assertEquals(
            listOf("tester68@logrocket.com", "tester824@spotify.com"),
            importedEntries(bytes, ImportFormat.Json),
        )
    }

    @Test
    fun anImportedAccountIsPresentedAsTheServiceWithTheAccountInItsOwnField() {
        val parsed =
            assertNotNull(
                Qualia.readImport(sample("plain.json"), ImportFormat.Json, null, NOW),
                "the plain sample must preview",
            )
        val spotify = parsed.entries().single { it.issuer == "Spotify" }

        assertEquals("Spotify", spotify.presentedName(), "the card title is the service")
        assertEquals(
            "tester824@spotify.com",
            spotify.presentedAccount(),
            "the Aegis name becomes the account shown in the edit form",
        )
    }

    @Test
    fun theAegisHtmlExportIsDetectedAndImported() {
        val bytes = sample("vault.html")
        assertEquals(ImportFormat.Html, Qualia.detectImportFormat(bytes), "the content must be recognised")
        assertEquals(
            listOf("tester68@logrocket.com", "tester824@spotify.com"),
            importedEntries(bytes, ImportFormat.Html),
        )
    }

    @Test
    fun theAegisEncryptedJsonExportOpensWithItsPassword() {
        val bytes = sample("encrypted.json")
        assertEquals(ImportFormat.Json, Qualia.detectImportFormat(bytes), "the content must be recognised")
        assertEquals(
            listOf("tester68@logrocket.com", "tester824@spotify.com"),
            importedEntries(bytes, ImportFormat.Json, PASSWORD),
        )
    }

    @Test
    fun theAegisTextExportIsDetectedAndImported() {
        val bytes = sample("uris.txt")
        assertEquals(ImportFormat.Text, Qualia.detectImportFormat(bytes), "the content must be recognised")
        val parsed = assertNotNull(Qualia.readImport(bytes, ImportFormat.Text, null, NOW))
        assertEquals(2, parsed.entries().size)
        assertTrue(parsed.problems().isEmpty(), "the text export has no unusable rows: ${parsed.problems()}")
    }

    private companion object {
        /** The time the previews are read at; the formats carry no clock. */
        const val NOW = 1_700_000_000L

        /** The password of the encrypted fixture, recorded beside it in the core's test data. */
        const val PASSWORD = "Afl43670P"
    }
}
