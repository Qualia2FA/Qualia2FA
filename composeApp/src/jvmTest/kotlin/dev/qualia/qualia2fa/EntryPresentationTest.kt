package dev.qualia.qualia2fa

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The reading of an import that had no account column.
 *
 * Aegis writes the account in `name` and the service in `issuer`; the interface has to show the
 * service as the title and the account in the account field. These are presentation rules, so
 * they are pinned without touching a vault.
 */
class EntryPresentationTest {
    @Test
    fun anAccountOnlyEntryIsPresentedWithItsServiceAsTheTitle() {
        val name = "tester824@spotify.com"
        val issuer = "Spotify"

        assertEquals("Spotify", presentedEntryName(name, issuer, account = ""))
        assertEquals(name, presentedEntryAccount(name, issuer, account = ""))
        assertEquals(name, presentedEntryDetail(name, issuer, account = ""))
    }

    @Test
    fun anEntryThatAlreadyHasAnAccountIsLeftAlone() {
        assertEquals("Aurora", presentedEntryName("Aurora", "aurora.app", "alice"))
        assertEquals("alice", presentedEntryAccount("Aurora", "aurora.app", "alice"))
        assertEquals("aurora.app", presentedEntryDetail("Aurora", "aurora.app", "alice"))
    }

    @Test
    fun aNameThatIsTheServiceIsNotMistakenForAnAccount() {
        assertEquals("GitHub", presentedEntryName("GitHub", "GitHub", account = ""))
        assertNull(presentedEntryDetail("GitHub", "GitHub", account = ""), "there is no second line to show")
    }

    @Test
    fun aNormalizedNameShowsTheAccountInsteadOfRepeatingTheTitle() {
        assertEquals("tester824@spotify.com", presentedEntryDetail("Spotify", "Spotify", "tester824@spotify.com"))
    }
}
