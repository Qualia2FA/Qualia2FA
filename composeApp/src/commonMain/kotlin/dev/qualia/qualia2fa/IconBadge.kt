package dev.qualia.qualia2fa

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/** The side of the square an entry's icon is drawn in. */
internal val IconBadgeSize = 40.dp

/** The corner radius of that square. */
private val IconBadgeCorner = 12.dp

/**
 * The seed colours a badge without a picture can take.
 *
 * Deliberately few and dark: the initials are drawn in white over them, and a large palette would
 * make two neighbouring entries look like different services when they are not. The index comes
 * from the entry's **secret**, not from its name, so the same account has the same colour on
 * every device and after a rename.
 */
private val SeedColors =
    listOf(
        Color(0xFF3E5C76),
        Color(0xFF2F6F62),
        Color(0xFF6B4E71),
        Color(0xFF8C3B3B),
        Color(0xFF3C6E2F),
        Color(0xFF7A5C2E),
        Color(0xFF4A4A55),
        Color(0xFF2E5E7A),
    )

/**
 * The square drawn at the start of an entry's title: its picture, or its initials.
 *
 * # What decides between the two
 *
 * A picture is used only when it decodes **and** its format is one this version renders. SVG is
 * stored and exported but this build has no renderer, so an SVG falls back to the badge rather
 * than drawing nothing. A picture that no longer decodes falls back the same way: the fallback is
 * drawn from the entry, so a broken icon never leaves a hole in the card.
 *
 * # Decoding happens once per entry and icon
 *
 * [remember] is keyed on both, so a card recomposing for a new code does not decode the picture
 * again. The list only composes visible cards, so nothing decodes off screen.
 */
@Composable
internal fun EntryIconBadge(entry: Entry, modifier: Modifier = Modifier) {
    val picture =
        remember(entry.id, entry.icon) {
            entry.icon?.takeIf { it.mime != IconMime.Svg }?.let { decodeIconImage(it.bytes) }
        }
    val shape = RoundedCornerShape(IconBadgeCorner)
    val seed =
        SeedColors[(seedColorIndex(entry.secretBase32) and Int.MAX_VALUE) % SeedColors.size]

    Box(
        modifier =
            modifier
                .size(IconBadgeSize)
                .clip(shape)
                .background(if (picture == null) seed else Color.Transparent)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (picture != null) {
            Image(
                bitmap = picture,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = iconInitials(entry.name),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}

/**
 * The one or two characters a badge without a picture shows.
 *
 * The first character of each word, up to two columns. A wide character (CJK, Hangul, a full-width
 * form, an emoji) counts as two columns by itself, so a name written in Chinese shows one
 * character rather than two overlapping ones. Control characters and words that start with
 * punctuation are dropped: "CurseForce (QiuShui1012)" should read C, not C(.
 */
internal fun iconInitials(name: String): String {
    val out = StringBuilder()
    var columns = 0
    for (word in name.trim().split(Regex("\\s+"))) {
        if (word.isEmpty()) continue
        val codePoint = firstCodePoint(word)
        if (isControl(codePoint) || !startsWithALetterOrDigit(word)) continue
        val width = if (isWide(codePoint)) 2 else 1
        if (columns + width > 2) break
        // A code point outside the basic plane is two UTF-16 units; taking one would split a
        // surrogate pair and draw a replacement character.
        out.append(word.substring(0, if (codePoint > 0xFFFF) 2 else 1))
        columns += width
        if (columns >= 2) break
    }
    val text = out.toString().uppercase()
    return text.ifEmpty { "?" }
}

/** Whether [word] begins with something worth putting in a badge. */
private fun startsWithALetterOrDigit(word: String): Boolean {
    val first = word[0]
    // A surrogate pair is an emoji or a rare ideograph; both are worth a badge and neither
    // answers `isLetterOrDigit` on the unit it was split into.
    return first.isLetterOrDigit() || first.isHighSurrogate()
}

/** The first Unicode code point of [text], as an integer. */
private fun firstCodePoint(text: String): Int {
    val high = text[0]
    if (high.isHighSurrogate() && text.length > 1 && text[1].isLowSurrogate()) {
        return 0x10000 + ((high.code - 0xD800) shl 10) + (text[1].code - 0xDC00)
    }
    return high.code
}

/** Whether a code point is a control character, which has no visible shape. */
private fun isControl(codePoint: Int): Boolean =
    codePoint < 0x20 || codePoint in 0x7F..0x9F

/** Whether a code point occupies two terminal columns in the usual width tables. */
private fun isWide(codePoint: Int): Boolean =
    codePoint in 0x1100..0x115F || // Hangul Jamo
        codePoint in 0x2E80..0xA4CF || // CJK radicals, kana, ideographs
        codePoint in 0xAC00..0xD7A3 || // Hangul syllables
        codePoint in 0xF900..0xFAFF || // CJK compatibility ideographs
        codePoint in 0xFE30..0xFE4F || // CJK compatibility forms
        codePoint in 0xFF00..0xFF60 || // full-width forms
        codePoint in 0xFFE0..0xFFE6 ||
        codePoint in 0x1F300..0x1FAFF || // emoji
        codePoint in 0x20000..0x3FFFD // CJK extension planes

/**
 * Decodes the bytes of an [EntryIcon] into a bitmap, or `null` when they are not an image this
 * platform can read.
 *
 * The caller passes the original file bytes; nothing here re-encodes them.
 */
internal expect fun decodeIconImage(bytes: ByteArray): ImageBitmap?

/**
 * The index of the seed colour for an entry.
 *
 * Derived from the **secret**, so the colour follows the account rather than the name, and it is
 * the same on every device that holds it. Implementations may return `Int.MIN_VALUE`; callers
 * mask it before indexing a palette.
 */
internal expect fun seedColorIndex(secretBase32: String): Int
