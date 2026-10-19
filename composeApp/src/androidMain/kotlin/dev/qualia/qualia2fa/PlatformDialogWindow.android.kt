package dev.qualia.qualia2fa

import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/** Makes the surrounding dialog window fill the screen, so its surface can reach the bottom. */
@Composable
actual fun PlatformDialogWindow() {
    val view = LocalView.current
    LaunchedEffect(view) {
        var provider = view as? DialogWindowProvider
        var parent: ViewParent? = view.parent
        while (provider == null && parent != null) {
            provider = parent as? DialogWindowProvider
            parent = parent.parent
        }
        val window = provider?.window ?: return@LaunchedEffect
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        window.setGravity(android.view.Gravity.CENTER)
    }
}
