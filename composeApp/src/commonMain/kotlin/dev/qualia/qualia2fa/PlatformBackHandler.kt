package dev.qualia.qualia2fa

import androidx.compose.runtime.Composable

/**
 * Takes the platform's "go back" request while [enabled].
 *
 * On Android this is the system back gesture or button. A screen that is one step below another
 * passes its own way back here, so a back request returns to the screen that opened it instead
 * of leaving the application. The root screens do not install a handler: there, back means what
 * the platform says it means.
 *
 * The desktop has no equivalent request; its implementation does nothing and the window's own
 * controls keep their meaning.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
