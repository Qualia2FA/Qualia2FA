package dev.qualia.qualia2fa

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/** The Android system back request, handed to [onBack] while [enabled]. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}
