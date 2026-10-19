package dev.qualia.qualia2fa

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Draws a QR code from the grayscale pixels the facade produces.
 *
 * Dark runs are merged per row rather than one rectangle per pixel: a version 20 code is
 * 97×97 modules, and a few hundred rectangles is a very different thing to draw every
 * frame than ten thousand.
 *
 * The drawing fills the width it is given, up to [maxSize] and down to whatever the caller's
 * layout allows, and is always square. A code in a card that shares a row with a form is
 * therefore smaller than one on a page of its own, without the caller having to measure the
 * page to decide.
 */
@Composable
fun QrCodeView(image: QrImage, modifier: Modifier = Modifier, maxSize: Dp = 240.dp) {
    Canvas(
        modifier
            .widthIn(max = maxSize)
            .fillMaxWidth()
            .aspectRatio(1f),
    ) {
        val scaleX = size.width / image.width
        val scaleY = size.height / image.height

        for (y in 0 until image.height) {
            var x = 0
            while (x < image.width) {
                val dark = image.pixels[y * image.width + x].toInt() and 0xFF < 128
                var run = 1
                while (
                    x + run < image.width &&
                    ((image.pixels[y * image.width + x + run].toInt() and 0xFF) < 128) == dark
                ) {
                    run += 1
                }
                if (dark) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(x * scaleX, y * scaleY),
                        size = Size(run * scaleX, scaleY),
                    )
                }
                x += run
            }
        }
    }
}
