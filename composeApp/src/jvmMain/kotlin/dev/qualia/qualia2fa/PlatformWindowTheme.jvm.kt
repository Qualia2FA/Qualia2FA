package dev.qualia.qualia2fa

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The desktop window has no strip outside the composition that belongs to a different theme;
 * the application's own surface covers it.
 */
@Composable
actual fun PlatformWindowTheme(background: Color, dark: Boolean) {
    // Nothing to tell the desktop window.
}
