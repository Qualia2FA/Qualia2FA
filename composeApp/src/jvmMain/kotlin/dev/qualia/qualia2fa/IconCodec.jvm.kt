package dev.qualia.qualia2fa

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/**
 * Decodes an icon with Skia, the image library Compose Multiplatform uses on the desktop.
 *
 * A decoding failure is not an error: the vault may hold an icon this build cannot render, and the
 * card falls back to the letter badge. SVG is not decoded - Skia's codec does not render it -
 * which is why the caller falls back to initials for that format before calling here.
 */
internal actual fun decodeIconImage(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
