package dev.qualia.qualia2fa

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * Draws the 2FA screen into a PNG and leaves it in a temporary directory.
 *
 * # Why the screen is rendered at all
 *
 * The card's header is a layout, and a layout that shows nothing still compiles and still
 * passes every check in the suite: text that a container has squeezed to zero height is
 * simply absent from the picture. What this renders is therefore meant to be **looked at** —
 * the path is printed when it runs:
 *
 * ```text
 * ./gradlew :composeApp:jvmTest --tests "*ScreenRenderFixture*"
 * ```
 *
 * # What it does assert
 *
 * That the screen composed at all, which is not free here: the 2FA screen reads the window's
 * code scanner, so it cannot be composed outside one. The frame is then checked for having
 * drawn something other than a flat background, which is the one failure a picture of it
 * would show at a glance anyway.
 */
class ScreenRenderFixture {
    @Test
    fun rendersTheTwoFactorScreen() {
        val directory = Files.createTempDirectory("qualia2fa-render")
        val storage = requireNotNull(FileVaultStorage.open(directory)) { "a file-backed store must open" }
        val vault = requireNotNull(Qualia.createLocalVault(requireNotNull(storage.masterKeyStore)))

        // The shapes that read differently: a title that is the service with the account under
        // it, an entry that carries no account at all, one whose name is its own account, a
        // scheme with a chip of its own, and a name long enough to be cut off.
        val secret = "JBSWY3DPEHPK3PXP"
        val now = 1_700_000_000L
        fun add(name: String, issuer: String, account: String, kind: EntryKind = EntryKind.Totp(30), digits: Int = 6) =
            vault.addEntry(name, issuer, account, secret, OtpAlgorithm.Sha1, digits, kind, "", now)

        add("Modrinth", "Modrinth", "2956913609@qq.com")
        add("Parsec", "Parsec", "3394608504@qq.com")
        add("qiushui1012@gmail.com", "GitHub", "")
        add("CurseForce (QiuShui1012)", "CurseForce (QiuShui1012)", "")
        add("Steam Guard", "", "anvilcraft", EntryKind.Steam, digits = 5)
        add(
            "AnvilCraft Translate Enterprise Edition With A Long Name",
            "AnvilCraft",
            "qiushui1012+anvilcraft-translate@foxmail.com",
        )

        val settings = FileSettingsStore(directory.resolve("settings.properties"))
        val scene =
            ImageComposeScene(width = 1200, height = 2800, density = Density(2f)) {
                // The scanner is provided by the window the screen is shown in, and a fixture
                // has no camera; this one is never asked for anything.
                CompositionLocalProvider(LocalCodeScanner provides CodeScanner { }) {
                    QualiaTheme(AppTheme.Light) { TwoFactorScreen(vault, onPersist = {}) }
                }
            }
        val frame = scene.render()
        val encoded = requireNotNull(frame.encodeToData(EncodedImageFormat.PNG)) { "the frame must encode" }
        val output = directory.resolve("screen.png")
        Files.write(output, encoded.bytes)
        println("RENDERED: $output")
        scene.close()

        val bitmap = Bitmap.makeFromImage(Image.makeFromEncoded(encoded.bytes))
        val background = bitmap.getColor(0, 0)
        var drawn = 0
        var sampled = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                sampled++
                if (bitmap.getColor(x, y) != background) drawn++
                x += 8
            }
            y += 8
        }
        assertTrue(
            drawn * 100 / sampled > 1,
            "the screen drew nothing but its background ($drawn of $sampled sampled pixels)",
        )
    }
}
