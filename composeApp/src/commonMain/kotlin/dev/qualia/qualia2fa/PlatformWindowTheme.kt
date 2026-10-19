package dev.qualia.qualia2fa

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Tells the platform window which background and bar treatment the current theme wants.
 *
 * The composition paints its own surface, but the strip behind the system bars belongs to the
 * window, outside anything Compose lays out. On Android this sets that strip to [background]
 * and flips the bar icons to match [dark]; without it, choosing Light or Dark in the
 * application would leave the platform's own theme showing at the edges. The desktop window has
 * no such strip, so its implementation does nothing.
 */
@Composable
expect fun PlatformWindowTheme(background: Color, dark: Boolean)
