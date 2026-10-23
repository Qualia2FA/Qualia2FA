package dev.qualia.qualia2fa

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The Android file picker, built on the system's own pickers.
 *
 * # Why the answer arrives later
 *
 * Nothing here blocks. A picker is a separate activity, so the result arrives on the main
 * thread after the call that asked for it has returned; [FilePicker]'s callbacks are where it
 * is handed back. The pending callback lives on [MainActivity], which is also where the
 * classic activity-result callback is delivered.
 *
 * # Why the classic result API, and not `rememberLauncherForActivityResult`
 *
 * [MainActivity] is a `FragmentActivity`, because the biometric prompt attaches a fragment of
 * its own. `FragmentActivity.startActivityForResult` accepts only request codes in the lower
 * 16 bits, while Compose's activity-result registry launches with codes above that range and
 * throws `IllegalArgumentException: Can only use lower 16 bits for requestCode` on every
 * picker. The classic API with small codes is the one the two requirements can share.
 *
 * # What is used
 *
 * * Images and other documents go through the Storage Access Framework (`ACTION_OPEN_DOCUMENT`
 *   and `ACTION_CREATE_DOCUMENT`), whose grant is per file and lasts as long as the operation
 *   does.
 *
 * An image is decoded here because that is the one part with no portable API, and it is
 * decoded **downscaled**: a picture straight out of a modern camera is larger than the
 * decoder needs to read a code out of it, and decoding it whole is how such a screen runs out
 * of memory.
 */
@Composable
actual fun rememberFilePicker(): FilePicker {
    val activity = LocalContext.current.findMainActivity()
    return remember(activity) { AndroidFilePicker(activity) }
}

/** The activity around [this] context, or `null` outside a real window (a preview, a test). */
internal tailrec fun Context.findMainActivity(): MainActivity? =
    when (this) {
        is MainActivity -> this
        is ContextWrapper -> baseContext.findMainActivity()
        else -> null
    }

/** The type a document with no registered type of its own is offered and read as. */
internal const val OCTET_STREAM = "application/octet-stream"

/** The tag diagnostics about a picked file are written under. */
private const val TAG = "Qualia2FA"

/** The filter that matches every file: an application-specific extension has no type. */
internal const val ANY_TYPE = "*/*"

/** The longest edge an image is decoded at, in pixels. */
private const val MAX_IMAGE_EDGE = 2048

/**
 * The largest file this picker reads into memory.
 *
 * The core refuses a vault payload above 64 MiB and another application's export above
 * 8 MiB, so this bound lets every file the library would accept through, and stops a picked
 * file from being read until the process runs out of memory. On a phone the bytes are the
 * whole file, so the limit has to be here as well as in the library.
 */
private const val MAX_FILE_BYTES = 64 * 1024 * 1024 + 64 * 1024

private class AndroidFilePicker(private val activity: MainActivity?) : FilePicker {
    override fun pickGrayImage(dialogTitle: String, imageFilesLabel: String, onResult: (GrayPixels?) -> Unit) {
        if (activity == null) onResult(null) else activity.pickImage(onResult)
    }

    override fun saveBytes(
        suggestedName: String,
        dialogTitle: String,
        filterLabel: String,
        extension: String,
        bytes: ByteArray,
        onResult: (String?) -> Unit,
    ) {
        if (activity == null) {
            onResult(null)
            return
        }
        val name = if (suggestedName.lowercase().endsWith(".${extension.lowercase()}")) suggestedName else "$suggestedName.$extension"
        activity.createDocument(name, bytes, onResult)
    }

    override fun pickBytes(
        dialogTitle: String,
        filterLabel: String,
        extension: String?,
        onResult: (ByteArray?) -> Unit,
    ) {
        // The Storage Access Framework filters by MIME type, and an application-specific
        // export has none. Listing every file keeps the one the user exported selectable, and
        // what the file really is gets decided from its bytes.
        if (activity == null) onResult(null) else activity.readDocument(onResult)
    }
}

/** Reads the whole document at [uri], or `null` when it cannot be opened, read or is too large. */
internal fun read(context: Context, uri: Uri): ByteArray? =
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_FILE_BYTES) return null
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    } catch (_: Exception) {
        null
    }

/** Writes [bytes] to the document at [uri], and answers with where they went. */
internal fun write(context: Context, uri: Uri, bytes: ByteArray): String? =
    try {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
        uri.toString()
    } catch (_: Exception) {
        null
    }

/**
 * Decodes the image at [uri] into grayscale pixels, downscaled to [MAX_IMAGE_EDGE].
 *
 * The conversion is the simple luma weighting (0.299/0.587/0.114) rather than a plain
 * average: a code's dark and light modules can differ in color as much as in brightness,
 * and averaging channels makes colored modules harder to tell apart, not easier.
 *
 * The size is read first, without decoding anything, so that a picture far larger than the
 * decoder needs is sampled down on the way in instead of being held in memory whole.
 */
internal fun decodeGray(context: Context, uri: Uri): GrayPixels? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try {
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    } catch (error: Exception) {
        Log.w(TAG, "the chosen image could not be opened", error)
        return null
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        Log.w(TAG, "the chosen file has no decodable image in it: ${bounds.outWidth}x${bounds.outHeight}")
        return null
    }

    val options =
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    val bitmap =
        try {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (error: Exception) {
            Log.w(TAG, "the chosen image could not be decoded", error)
            null
        } ?: run {
        Log.w(TAG, "the chosen image decoded to nothing")
        return null
    }

    val width = bitmap.width
    val height = bitmap.height
    val colors = IntArray(width * height)
    bitmap.getPixels(colors, 0, width, 0, 0, width, height)
    bitmap.recycle()

    val gray = ByteArray(width * height)
    for (index in colors.indices) {
        val rgb = colors[index]
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        gray[index] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
    }
    return GrayPixels(width, height, gray)
}

/** The power of two a [width]×[height] image is decoded at, so that neither edge exceeds the limit. */
private fun sampleSize(width: Int, height: Int): Int {
    var sample = 1
    while (width / sample > MAX_IMAGE_EDGE || height / sample > MAX_IMAGE_EDGE) {
        sample *= 2
    }
    return sample
}
