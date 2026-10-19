package dev.qualia.qualia2fa

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.ColorDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Applies [background] and the matching bar icon appearance to the activity's window. */
@Composable
actual fun PlatformWindowTheme(background: Color, dark: Boolean) {
    val view = LocalView.current
    val window = view.context.findActivity()?.window ?: return
    // A configuration change can reset the window decorations even when the application's
    // choice did not move; reapply them after one arrives.
    val configuration = LocalConfiguration.current

    DisposableEffect(background, dark, window, configuration.uiMode) {
        window.setBackgroundDrawable(ColorDrawable(background.toArgb()))
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
        onDispose {}
    }
}

/** The activity behind a composition's context, which may be wrapped by a theme. */
private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
