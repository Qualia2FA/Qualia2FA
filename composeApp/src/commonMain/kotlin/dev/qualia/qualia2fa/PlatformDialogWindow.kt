package dev.qualia.qualia2fa

import androidx.compose.runtime.Composable

/**
 * Lets a dialog's platform window take the whole available height.
 *
 * Compose's Android dialog window is created with a wrap-content height even when the width is
 * made to match the screen, so a floating surface inside it can end well above the bottom edge
 * and leave a scrim margin. On Android the actual implementation asks the window for a
 * match-parent height; the desktop dialog already sizes itself from its content and does
 * nothing here.
 */
@Composable
expect fun PlatformDialogWindow()
