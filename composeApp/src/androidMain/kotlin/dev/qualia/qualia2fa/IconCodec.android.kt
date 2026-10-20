package dev.qualia.qualia2fa

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Decodes an icon with the platform's own decoder.
 *
 * `BitmapFactory` is what Android uses everywhere else, and the alternative - pulling Skia into
 * the APK - would add a second image stack for a 40 dp square. It returns `null` for bytes it does
 * not recognise, which is exactly the fallback the badge needs; SVG is skipped by the caller
 * before this is reached because `BitmapFactory` has no SVG codec either.
 */
internal actual fun decodeIconImage(bytes: ByteArray): ImageBitmap? =
    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
