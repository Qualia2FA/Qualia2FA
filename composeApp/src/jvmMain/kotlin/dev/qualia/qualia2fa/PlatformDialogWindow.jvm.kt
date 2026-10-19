package dev.qualia.qualia2fa

import androidx.compose.runtime.Composable

/** The desktop dialog sizes itself from its content; there is no wrap-height window to fix. */
@Composable
actual fun PlatformDialogWindow() {
    // Nothing to adjust on the desktop.
}
