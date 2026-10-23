package dev.qualia.qualia2fa

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Modifier
import com.qiushui1012.qualia.bioauth.KeystoreBiometricSealer
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.biometric_prompt_cancel
import dev.qualia.qualia2fa.resources.biometric_prompt_subtitle
import dev.qualia.qualia2fa.resources.biometric_prompt_title
import java.nio.file.Path
import org.jetbrains.compose.resources.stringResource

/**
 * The Android entry point.
 *
 * It wires the storage and the preferences to the same user interface the desktop build
 * shows, and nothing else: every decision lives in the shared code or in the Rust core.
 *
 * # Where the files live
 *
 * [android.content.Context.getFilesDir] is the application's own private directory: no other
 * application can read it, and it is removed when this one is uninstalled. What is written
 * there for the master key is the wrapped form; the key that can unwrap it is a keystore
 * entry, which never leaves the keystore.
 *
 * # The window
 *
 * The application draws behind the system bars, and the composition is inset by the area they
 * and the display's cutout occupy. The window itself is the plate color, so what shows at the
 * edges is the application's own background rather than a flash of the platform's.
 *
 * The keyboard is deliberately **not** part of that inset. The activity asks the platform to
 * resize it when the keyboard appears (`adjustResize` in the manifest), so the composition is
 * handed a smaller window and everything in it — including an open dialog — moves up on its
 * own. Layering the keyboard's insets on top of that would say the same thing twice, and it
 * also breaks the dialogs: with the keyboard's insets in the layout, Android's dialog windows
 * are dismissed the moment a text field is touched, taking the typed input with them. Only the
 * system bars and the cutout are therefore consumed here.
 *
 * # Configuration changes
 *
 * The activity handles orientation and screen size itself rather than being recreated. The
 * interface is built for a window that changes shape, and recreating the activity would take
 * the vault's in-memory state with it while the user is reading a code.
 *
 * The strip behind the system bars is outside the composition, so the current theme's
 * background and bar-icon appearance are pushed to the window by `PlatformWindowTheme` from
 * the shared interface. That covers both a system theme that changes and a light or dark
 * choice made in the settings, which no platform configuration change would report.
 *
 * # Why the Rust library is loaded here
 *
 * The device identity is a key in the platform keystore, and the platform keystore is reached
 * from Rust over JNI. That code needs a `JavaVM`, and it takes one in `JNI_OnLoad`, which the
 * runtime calls when the **JVM** loads the library.
 *
 * JNA, which the generated bindings use to reach everything else, does not load it that way:
 * it opens the file itself, and a library opened that way never sees `JNI_OnLoad`. Without
 * this line the device identity reports that it was loaded without a Java VM, which is true,
 * and the vault can neither pair nor sync.
 */
class MainActivity : FragmentActivity() {
    /** The answer to the image picker, while it is open. */
    private var pendingImage: ((GrayPixels?) -> Unit)? = null

    /** The answer to the document picker, while it is open. */
    private var pendingRead: ((ByteArray?) -> Unit)? = null

    /** The file being written, and the answer to hand it to, while the save dialog is open. */
    private var pendingWrite: PendingWrite? = null

    /** The answer to the camera permission request, while the system dialog is up. */
    private var pendingCameraPermission: ((Boolean) -> Unit)? = null

    /**
     * Opens the system picker for an image.
     *
     * The classic result API is deliberate: `FragmentActivity` rejects the request codes the
     * Compose activity-result registry uses (they do not fit in 16 bits), and that rejection
     * is a crash on every picker. The codes below stay inside the range it accepts.
     */
    internal fun pickImage(onResult: (GrayPixels?) -> Unit) {
        pendingImage = onResult
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            },
            PICK_IMAGE_REQUEST,
        )
    }

    /** Opens the system picker for a document of any type. */
    internal fun readDocument(onResult: (ByteArray?) -> Unit) {
        pendingRead = onResult
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = ANY_TYPE
            },
            READ_DOCUMENT_REQUEST,
        )
    }

    /** Opens the system save dialog under [name], writing [bytes] where the user chooses. */
    internal fun createDocument(name: String, bytes: ByteArray, onResult: (String?) -> Unit) {
        pendingWrite = PendingWrite(bytes, onResult)
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = OCTET_STREAM
                putExtra(Intent.EXTRA_TITLE, name)
            },
            CREATE_DOCUMENT_REQUEST,
        )
    }

    /**
     * Delivers the picker's answer to the callback that asked for it.
     *
     * Nothing is read or written when the user cancelled: the callback receives `null`, which
     * is the answer every screen already reports as "nothing was chosen".
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = if (resultCode == Activity.RESULT_OK) data?.data else null
        when (requestCode) {
            PICK_IMAGE_REQUEST -> {
                val answer = pendingImage
                pendingImage = null
                answer?.invoke(uri?.let { decodeGray(this, it) })
            }

            READ_DOCUMENT_REQUEST -> {
                val answer = pendingRead
                pendingRead = null
                answer?.invoke(uri?.let { read(this, it) })
            }

            CREATE_DOCUMENT_REQUEST -> {
                val request = pendingWrite
                pendingWrite = null
                if (request != null) request.answer(uri?.let { write(this, it, request.bytes) })
            }
        }
    }

    /**
     * Asks for the camera, or answers at once when it is already allowed.
     *
     * The classic permission API is deliberate for the same reason the file pickers use the
     * classic result API: `FragmentActivity` rejects the request codes the Compose registry
     * uses, and the rejection is a crash rather than a refusal.
     */
    internal fun requestCameraPermission(onResult: (Boolean) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            onResult(true)
            return
        }
        pendingCameraPermission = onResult
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
    }

    /**
     * Routes a permission answer to the screen that asked for it.
     *
     * The system delivers it here and nowhere else, and the Compose launcher cannot be used by
     * a `FragmentActivity` (see `LanPermissions`), so this is the hop between the two.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            val answer = pendingCameraPermission
            pendingCameraPermission = null
            answer?.invoke(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            return
        }
        LanPermissions.deliver(requestCode, grantResults)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Built once per activity: the prompt is its window, and the sealer holds it.
        val sealer = AndroidAuthSealer(this)

        System.loadLibrary("qualia2fa_ffi")

        val dataDirectory: Path = filesDir.toPath()
        setContent {
            // The prompt is drawn by the system, so its words are handed to it here, where the
            // interface's language is known and can change while the activity lives.
            val promptText =
                KeystoreBiometricSealer.PromptText(
                    title = stringResource(Res.string.biometric_prompt_title),
                    subtitle = stringResource(Res.string.biometric_prompt_subtitle),
                    cancel = stringResource(Res.string.biometric_prompt_cancel),
                )
            SideEffect { sealer.promptText = promptText }

            // The vault's timeout counts time in front of the application. A file picker is a
            // window of the system, so the activity is paused while one is open; handing that
            // fact to the shared code is what keeps the timeout from firing there.
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            var active by remember(lifecycle) {
                mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            }
            DisposableEffect(lifecycle) {
                val observer =
                    LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_RESUME -> active = true
                            Lifecycle.Event.ON_PAUSE -> active = false
                            else -> Unit
                        }
                    }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }

            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
            ) {
                App(
                    // Both platforms reach the master key the same way: the key-encryption
                    // key lives in this device's platform key store, and what is written
                    // beside the vault file is the wrapped form.
                    storage = FileVaultStorage.open(dataDirectory),
                    settings = FileSettingsStore(dataDirectory.resolve("settings.properties")),
                    active = active,
                    authSealer = sealer,
                )
            }
        }
    }

    private companion object {
        /** Request codes below 0x10000: `FragmentActivity` refuses anything wider. */
        const val PICK_IMAGE_REQUEST = 0x5101
        const val READ_DOCUMENT_REQUEST = 0x5102
        const val CREATE_DOCUMENT_REQUEST = 0x5103
        const val CAMERA_PERMISSION_REQUEST = 0x5104
    }
}

/** One file being written, and where to hand the answer. */
internal class PendingWrite(val bytes: ByteArray, val answer: (String?) -> Unit)
