package dev.qualia.qualia2fa

import androidx.compose.runtime.Composable

/**
 * The desktop has no system back request; the window's close control keeps its meaning.
 *
 * This exists so that the common screens can install their back handling without knowing which
 * platform they are running on.
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // A desktop window's back is its own control; there is nothing to intercept here.
}
