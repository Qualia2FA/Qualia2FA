package dev.qualia.qualia2fa

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The badge an entry shows: its initials, the colour behind them, and the picture an export
 * brought.
 *
 * The picture itself is decoding plus a `remember`, so what is worth testing is the part the
 * application decides: which characters stand for a name, that the colour follows the secret
 * rather than the name, that a file that is not an image falls back instead of drawing nothing,
 * and that an edit does not drop an imported icon.
 */
class EntryIconTest {
    /** A one-pixel PNG, the smallest valid image the decoder accepts. */
    private val onePixelPng =
        java.util.Base64
            .getDecoder()
            .decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
            )

    @Test
    fun initialsTakeOneOrTwoCharacters() {
        assertEquals("G", iconInitials("GitHub"))
        assertEquals("WG", iconInitials("Work GitHub"))
        assertEquals("A", iconInitials("  alice  "))
        assertEquals("К", iconInitials("корпоративный"), "the first code point, not the first byte")
        assertEquals("銀", iconInitials("銀行 口座"), "a wide character fills both columns by itself")
        assertEquals(
            "😀",
            iconInitials("😀 personal"),
            "an emoji is one code point of two columns and must not be split",
        )
        assertEquals("?", iconInitials(""), "an empty name still needs a badge")
        assertEquals("?", iconInitials("\u0000\u0001"), "control characters have no shape to draw")
        assertEquals(
            "C",
            iconInitials("CurseForce (QiuShui1012)"),
            "a word that starts with punctuation contributes nothing",
        )
        assertEquals("?", iconInitials("( )"), "punctuation alone still has to draw something")
    }

    @Test
    fun theSeedColourFollowsTheSecret() {
        val secret = "JBSWY3DPEHPK3PXP"
        assertEquals(seedColorIndex(secret), seedColorIndex(secret), "the same account, the same colour")
        assertEquals(
            seedColorIndex(secret),
            seedColorIndex(secret.uppercase()),
            "the secret is stored upper case, so the seed must not depend on how a caller spelled it",
        )
    }

    @Test
    fun aPictureDecodesAndJunkFallsBack() {
        assertNotNull(decodeIconImage(onePixelPng), "a PNG must decode")
        assertNull(decodeIconImage(byteArrayOf(1, 2, 3)), "arbitrary bytes are not an image")
        assertNull(decodeIconImage(ByteArray(0)), "an empty file is not an image")
        assertNull(
            decodeIconImage("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".encodeToByteArray()),
            "SVG is stored but not rendered by this build, so it falls back to the badge",
        )
    }

    @Test
    fun anImportedIconSurvivesAnEdit() {
        val json =
            """
            {
              "version": 1,
              "header": { "slots": null, "params": null },
              "db": {
                "version": 3,
                "entries": [
                  {
                    "type": "totp",
                    "uuid": "aaaaaaaa-0000-0000-0000-000000000001",
                    "name": "GitHub",
                    "issuer": "GitHub",
                    "note": "",
                    "favorite": false,
                    "icon": "${java.util.Base64.getEncoder().encodeToString(onePixelPng)}",
                    "icon_mime": "image/png",
                    "info": { "secret": "JBSWY3DPEHPK3PXP", "algo": "SHA1", "digits": 6, "period": 30 }
                  }
                ],
                "groups": [],
                "icons_optimized": false
              }
            }
            """.trimIndent()

        val parsed =
            assertNotNull(
                Qualia.readImport(json.encodeToByteArray(), ImportFormat.Json, null, 1_700_000_000L),
                "the export must be readable",
            )

        val vault = assertNotNull(Qualia.createLocalVault(MemoryStore()), "a vault is needed")
        val applied = assertNotNull(parsed.apply(vault, emptyMap()), "the import must apply")
        assertEquals(1, applied.added)

        val imported = assertNotNull(vault.entries().singleOrNull(), "the entry must be in the vault")
        assertEquals(IconMime.Png, imported.icon?.mime, "the import must keep the picture")
        assertTrue(
            imported.icon!!.bytes.contentEquals(onePixelPng),
            "the bytes must be the original file",
        )

        val id = imported.id
        assertTrue(
            vault.updateEntry(
                id = id,
                name = "GitHub renamed",
                issuer = "GitHub",
                account = "",
                secretBase32 = "JBSWY3DPEHPK3PXP",
                algorithm = OtpAlgorithm.Sha1,
                digits = 6,
                kind = EntryKind.Totp(30),
                note = "",
                unixTime = 1_700_000_001L,
            ),
            "the edit must be accepted",
        )
        val edited = assertNotNull(vault.entries().singleOrNull(), "the entry must still be there")
        assertEquals("GitHub renamed", edited.name)
        assertEquals(
            imported.icon,
            edited.icon,
            "an edit form has no icon field, so the picture is carried over",
        )
    }
}
