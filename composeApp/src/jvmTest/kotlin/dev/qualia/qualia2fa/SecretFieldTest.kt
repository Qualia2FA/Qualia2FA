package dev.qualia.qualia2fa

import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The one property a secret field must have: hidden means hidden.
 *
 * A visual transformation that is accidentally the identity would pass every screenshot taken
 * while the field is empty, so the hidden and revealed branches are pinned on their own.
 */
class SecretFieldTest {
    private val typed = "JBSWY3DPEHPK3PXP"

    @Test
    fun aHiddenSecretIsNotTheTypedText() {
        val shown = secretVisualTransformation(visible = false).filter(AnnotatedString(typed)).text.text

        assertNotEquals(typed, shown, "a hidden field must not show the secret")
        assertTrue(shown.isNotEmpty(), "the mask must draw something")
        assertTrue(shown.all { it == '\u2022' }, "the default transformation draws bullets, got $shown")
    }

    @Test
    fun aRevealedSecretIsTheTypedText() {
        val shown = secretVisualTransformation(visible = true).filter(AnnotatedString(typed)).text.text

        assertEquals(typed, shown, "the reveal button must show exactly what was typed")
    }
}
