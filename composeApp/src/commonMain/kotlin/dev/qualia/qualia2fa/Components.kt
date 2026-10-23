package dev.qualia.qualia2fa

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.action_cancel
import dev.qualia.qualia2fa.resources.cascadia_code
import dev.qualia.qualia2fa.resources.action_copy
import dev.qualia.qualia2fa.resources.cd_hide_secret
import dev.qualia.qualia2fa.resources.cd_show_secret
import dev.qualia.qualia2fa.resources.code_frames_counter
import dev.qualia.qualia2fa.resources.code_frames_explainer
import dev.qualia.qualia2fa.resources.code_frames_progress
import dev.qualia.qualia2fa.resources.error_passphrase_mismatch
import dev.qualia.qualia2fa.resources.field_passphrase
import dev.qualia.qualia2fa.resources.field_passphrase_again
import dev.qualia.qualia2fa.resources.icon_visibility
import dev.qualia.qualia2fa.resources.icon_visibility_off
import dev.qualia.qualia2fa.resources.theme_dark
import dev.qualia.qualia2fa.resources.theme_light
import dev.qualia.qualia2fa.resources.theme_system
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The pieces every screen is built from.
 *
 * They exist so that a screen reads as its own layout rather than as a pile of styling: the
 * spacing, the corner radius, and the way a heading sits above its content are decided here
 * once, and the screens below compose them.
 */

/**
 * The typeface used for verification codes, pairing codes and other machine-read strings.
 *
 * Cascadia Code is bundled with the application under the SIL Open Font License 1.1; the
 * license text ships beside it in the resources and is reproduced in the repository's NOTICE.
 * Its zeros are slashed and its `1`/`l`/`I` are distinguishable, which is exactly what a code
 * that is read aloud or compared across two screens needs.
 */
@Composable
fun CodeFontFamily(): FontFamily = FontFamily(Font(Res.font.cascadia_code))

/** The heading of a screen. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}

/** A heading with its actions on the right, which is what a screen's header is. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScreenTitle(title)
        Spacer(Modifier.weight(1f))
        actions()
    }
}

/** An icon button with a label for the accessibility tree. */
@Composable
fun IconAction(
    painter: Painter,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            painter = painter,
            contentDescription = description,
            modifier = Modifier.size(Sizes.actionIcon),
            tint = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/**
 * The one shadow every surface in the interface uses.
 *
 * The layer has the same width and height as [content], sits at the same x and is offset down
 * by [CardShadowOffset]; its softness is [CardShadowBlur], both derived from the 2FA card's
 * corner radius. The color is the theme's shadow at two thirds of the previous opacity, so it
 * is lighter without becoming a different color. A bare box is drawn first so the surface on
 * top keeps its own shape and does not draw a second Material shadow.
 */
@Composable
fun ShadowedSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val color = qualiaShadowColor()
    Box(modifier = modifier) {
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .offset(y = CardShadowOffset)
                    // A true gaussian blur, not a Material elevation silhouette: the same-size
                    // shape is blurred by the card-radius constant, so no hard rectangle appears
                    // under the surface. Unbounded lets the soft edge draw outside the parent.
                    .blur(CardShadowBlur, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                    .background(color, shape),
        )
        content()
    }
}

/** The shadow color of the current scheme, lightened by one third from the previous value. */
@Composable
private fun qualiaShadowColor(): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // A dark shadow is invisible on a dark plate; the dark scheme gets the same faint edge as
    // a highlight instead, which is the equivalent visual weight. Both are deliberately very
    // faint: the shadow is a soft edge, not a second card-shaped plate.
    return if (dark) {
        Color(red = 1f, green = 1f, blue = 1f, alpha = 0.04f)
    } else {
        Color(red = 0f, green = 0f, blue = 0f, alpha = 0.06f)
    }
}

/**
 * A titled card: the shape of every section below a screen's header.
 *
 * [title] is optional because some sections are one control whose own label is enough.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable () -> Unit,
) {
    ShadowedSurface(shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Space.l),
                verticalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                title?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                content()
            }
        }
    }
}

/** A label above a value, the way a settings row reads. */
@Composable
fun LabeledValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * One code that has to be carried to another device: the drawing when it fits, the text
 * always, and a way to copy it.
 *
 * The text stays underneath the code on purpose. A code too dense to scan — which is what a
 * full vault produces — must still leave the user a way to carry it across, and a code too
 * large to draw at all leaves only the text.
 *
 * # One code or several
 *
 * The drawing comes from the multi-frame path even when the payload fits in one code: the
 * transport answers a payload that fits with a sequence of one, drawn module for module as the
 * single-code path draws it, so there is one path here rather than two and a payload that
 * outgrows one symbol is drawn instead of being silently left out.
 */
@Composable
fun CodeBlock(
    code: String,
    onCopied: () -> Unit,
    qrSizeDp: Int = 240,
    copyLabel: String? = null,
    showText: Boolean = true,
) {
    val frames = remember(code) { Qualia.qrFrames(code) }

    if (frames != null && frames.count > 1) {
        FramedCode(
            frames = frames,
            code = code,
            onCopied = onCopied,
            qrSizeDp = qrSizeDp,
            copyLabel = copyLabel,
            showText = showText,
        )
    } else {
        // `null` is a payload too large for the format at any version this facade draws, so
        // there is nothing to show but the text; a sequence of one is that one image.
        SingleCode(
            code = code,
            image = frames?.image(0),
            onCopied = onCopied,
            qrSizeDp = qrSizeDp,
            copyLabel = copyLabel,
            showText = showText,
        )
    }
}

/**
 * The drawing of a code on its own plate.
 *
 * A code is read against a high-contrast surface, and the plate is what separates it from the
 * card around it: the card is white in the light scheme, which would otherwise leave the code
 * floating in the same color. The plate hugs the drawing, and the drawing scales down with the
 * column so a two-column screen still holds a whole code.
 */
@Composable
private fun QrPanel(image: QrImage, qrSizeDp: Int) {
    Surface(
        modifier = Modifier.widthIn(max = (qrSizeDp + 16).dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Box(modifier = Modifier.padding(Space.s), contentAlignment = Alignment.Center) {
            QrCodeView(image, maxSize = qrSizeDp.dp)
        }
    }
}

/**
 * The drawing of a code that is one image: the code, its text, and the way to copy it.
 *
 * [qrSizeDp] is the caller's decision because the same payload is shown in a full-width card
 * and in one column of a two-column screen, and a code drawn at the full width would not fit
 * the latter.
 */
@Composable
private fun SingleCode(
    code: String,
    image: QrImage?,
    onCopied: () -> Unit,
    qrSizeDp: Int,
    copyLabel: String?,
    showText: Boolean,
) {
    val clipboard = LocalClipboardManager.current

    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        image?.let {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                QrPanel(it, qrSizeDp)
            }
        }
        if (showText) {
            Text(
                code,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = CodeFontFamily(),
            )
        }
        TextButton(onClick = {
            clipboard.setText(AnnotatedString(code))
            onCopied()
        }) {
            Text(copyLabel ?: stringResource(Res.string.action_copy))
        }
    }
}

/**
 * A code that needs several images, shown one after another.
 *
 * The counter is not decoration: a user holding a camera at the screen has to know how far
 * along the sequence is and that it starts over, or a code that cycles looks like one that
 * flickers — and the image they missed is the one that matters. The line above the code says
 * the same thing in words, because the counter alone does not say that the rest of the payload
 * is in the images that follow.
 */
@Composable
fun FramedCode(
    frames: QrFrames,
    code: String,
    onCopied: () -> Unit,
    qrSizeDp: Int = 240,
    copyLabel: String? = null,
    showText: Boolean = true,
) {
    val clipboard = LocalClipboardManager.current
    var shown by remember(frames) { mutableStateOf(0) }

    LaunchedEffect(frames) {
        while (true) {
            delay(FRAME_INTERVAL)
            shown = (shown + 1) % frames.count
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Hint(stringResource(Res.string.code_frames_explainer))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            QrPanel(frames.image(shown), qrSizeDp)
        }
        Text(stringResource(Res.string.code_frames_counter, shown + 1, frames.count))
        if (showText) {
            Text(code, style = MaterialTheme.typography.bodySmall, fontFamily = CodeFontFamily())
        }
        TextButton(onClick = {
            clipboard.setText(AnnotatedString(code))
            onCopied()
        }) {
            Text(copyLabel ?: stringResource(Res.string.action_copy))
        }
    }
}

/**
 * How long each image of a sequence is shown before the next one.
 *
 * Long enough for a camera to find and read it: the frame caught while the screen is still
 * changing reads as nothing, and the one behind it needs the time that follows. A code replaced
 * before that has happened is a code the reader never sees, and the user is left holding the
 * device at a screen that cycles faster than it can be read.
 */
private val FRAME_INTERVAL = 1200.milliseconds

/**
 * A code on its way in: the field it is pasted into, and the way to read one out of an image.
 *
 * The three screens that take a code ask for the same two controls in the same order, and what
 * each of them does with the text differs, so the callbacks come from the screen. So do the two
 * failures of the scanner: they are worded for what the screen was looking for.
 *
 * # A code that is one of several
 *
 * A payload too large for one symbol arrives as several codes, and this is where they are
 * collected: the scanner stays open while the sequence is incomplete and says how far along it
 * is, and what reaches [onScanned] is the reassembled payload rather than the last piece of it.
 * A code that is not part of a sequence is unaffected — a sequence of one completes on the
 * first read — and so is text typed or pasted into the field.
 */
@Composable
fun CodeField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    scanLabel: String,
    unreadable: String,
    noCode: String,
    onScanned: (String) -> Unit,
    onMessage: (String?) -> Unit,
) {
    val scanner = LocalCodeScanner.current
    val collector = remember { Qualia.frameCollector() }
    // How many codes were taken and how many the set has, which is what the screen says while
    // the user works through the sequence; the count is unknown until one of them says it.
    var collected by remember { mutableStateOf<Pair<Int, Int?>?>(null) }
    var completed by remember { mutableStateOf<String?>(null) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(onClick = {
        scanner.readCode(
            unreadable = unreadable,
            noCode = noCode,
            onFrame = { scanned ->
                when (val step = collector?.push(scanned)) {
                    is FrameStep.Completed -> {
                        // Held rather than handed over here: the scanner closes with the text of
                        // the code it just read, and that text is one piece of the payload.
                        completed = step.payload.decodeToString()
                        collected = collector?.let { it.received to it.expected }
                        null
                    }

                    is FrameStep.Taken -> {
                        collected = collector?.let { it.received to it.expected }
                        FrameCount(step.index, step.count)
                    }

                    else -> null
                }
            },
            onText = { scanned ->
                val payload = completed
                completed = null
                collected = null
                // The code carries this application's envelope; what belongs in the field is what
                // was put into it. A code that is one of several was reassembled above, so the
                // envelope to take off is the payload's rather than the last frame's.
                onScanned(payload?.let { Qualia.unwrapQrText(it) ?: it } ?: Qualia.unwrapQrText(scanned) ?: scanned)
            },
            onMessage = onMessage,
        )
    }) {
        Text(scanLabel)
    }
    collected?.let { (received, expected) ->
        Hint(stringResource(Res.string.code_frames_progress, received, expected ?: received))
    }
}

/** A step's short instruction. */
@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall)
}

/**
 * Drops the keyboard and the field's focus when a tap lands anywhere in this subtree.
 *
 * A tap on another field moves the focus there on its own; this is for the rest of the
 * surface. The event is observed in the **initial** pass and never consumed, so the control
 * under the pointer still receives its own click — a button press clears the caret before the
 * action runs, which is the order a user expects.
 */
fun Modifier.clearFocusOnTap(): Modifier =
    composed {
        val focusManager = LocalFocusManager.current
        pointerInput(focusManager) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                focusManager.clearFocus()
            }
        }
    }

/**
 * Asks for a passphrase, twice when it is being chosen rather than entered.
 *
 * Twice on export because a typo there is discovered when the file is the only way back, and
 * never before; once on import or restore because a wrong passphrase is reported by the core,
 * which is the only thing that can tell.
 *
 * This lives beside [SecretField] because both the settings screen and the login screen need
 * the same prompt: the login screen is where a recovery file is the only way into an account
 * that this device cannot open.
 */
@Composable
fun PassphraseDialog(
    title: String,
    confirmLabel: String,
    askTwice: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }
    val mismatchText = stringResource(Res.string.error_passphrase_mismatch)

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogDefaults,
        title = { Text(title, modifier = Modifier.fillMaxWidth().clearFocusOnTap()) },
        text = {
            Column(
                modifier = Modifier.clearFocusOnTap(),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                SecretField(
                    value = passphrase,
                    onValueChange = {
                        passphrase = it
                        mismatch = false
                    },
                    label = stringResource(Res.string.field_passphrase),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (askTwice) {
                    SecretField(
                        value = repeated,
                        onValueChange = {
                            repeated = it
                            mismatch = false
                        },
                        label = stringResource(Res.string.field_passphrase_again),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (mismatch) MessageLine(mismatchText)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (askTwice && passphrase != repeated) {
                        mismatch = true
                    } else {
                        onConfirm(passphrase)
                    }
                },
                enabled = passphrase.isNotEmpty(),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

/**
 * A text field whose value is hidden until the user asks to see it.
 *
 * # What is hidden, and why the button is next to it
 *
 * Passphrases, entry secrets and PINs are values a bystander can use, so they are drawn as
 * bullets by default. Hiding them without a way to look is not an option either: a user who
 * has just pasted a long secret, or who is checking a recovery passphrase they typed twice,
 * has to be able to verify what is in the field before committing it. The eye button is the
 * smallest control that answers both: the value stays covered until asked for, and one press
 * reveals it without leaving the form.
 *
 * The masking is a display transformation, not storage: the value itself is untouched. The
 * password keyboard keeps the platform from offering completions for a secret.
 *
 * [supportingText] is the same slot the Material field has, so an explanation below the field
 * stays where it was before the field was wrapped.
 */
/**
 * The transformation a secret is drawn through: bullets, or the value itself while revealed.
 *
 * Extracted so a test can prove the hidden branch is not accidentally the identity
 * transformation — the one mistake that would show every secret while still looking right in
 * a screenshot of an empty field.
 */
internal fun secretVisualTransformation(visible: Boolean): VisualTransformation =
    if (visible) VisualTransformation.None else PasswordVisualTransformation()

@Composable
fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 3,
    supportingText: (@Composable () -> Unit)? = null,
) {
    var visible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        maxLines = maxLines,
        visualTransformation = secretVisualTransformation(visible),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    painter =
                        painterResource(
                            // The icon shows the state of the value, not the action: a hidden
                            // value carries the crossed-out eye, a revealed one the open eye.
                            if (visible) Res.drawable.icon_visibility else Res.drawable.icon_visibility_off,
                        ),
                    contentDescription =
                        stringResource(
                            if (visible) Res.string.cd_hide_secret else Res.string.cd_show_secret,
                        ),
                )
            }
        },
        supportingText = supportingText,
        modifier = modifier,
    )
}

/**
 * A row of mutually exclusive options that wraps instead of squeezing.
 *
 * # Options keep their width
 *
 * The row is a flow container, so it first tries every option side by side. When they no
 * longer fit, the options that remain move to the next line; only a label wider than the
 * whole container is allowed to wrap inside its own option. That is the opposite of dividing
 * the row into equal shares: a share-based segment can become narrower than its label on a
 * small window, and the label then breaks into a tall, thin button that is hard to read and
 * hard to hit.
 *
 * # Why not a row of chips
 *
 * A chip draws its hover over a container that is transparent when it is not selected, so
 * what the pointer highlights is a state layer rather than the control's own surface: the
 * color around the label and the color the rest of the chip takes come from two different
 * places, and they do not match. Here each option is **one** surface with **one** background
 * — the label sits on it, so there is nothing to mismatch.
 *
 * The highlight ramps **linearly** over [CHOICE_FADE_MS]. The shape of that ramp is what makes
 * it look smooth, not its length: an eased curve spends the first frames barely moving and
 * then crosses most of the color in the middle, so at sixty frames a second it arrives in a
 * handful of visible steps. A longer tween only spreads the same steps out, and an eased one
 * is worse the shorter it is; a constant rate gives every frame an equal share of the change,
 * which is the smoothest a fade this small can be.
 */
@Composable
fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.small

    FlowRow(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        options.forEach { option ->
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            val highlight by animateFloatAsState(
                targetValue = if (hovered) 1f else 0f,
                animationSpec = tween(durationMillis = CHOICE_FADE_MS, easing = LinearEasing),
                label = "choice-highlight",
            )

            val isSelected = option == selected
            val background =
                when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    else ->
                        // One color for the whole segment: the surface, moved towards the
                        // ink by the faded amount.
                        lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primary, highlight * HOVER_TINT)
                }

            Box(
                modifier =
                    Modifier
                        // No `weight`: a weighted segment is squeezed to a share of the row
                        // however narrow that makes it, and its label then wraps into a tall,
                        // thin button. An option keeps the width its label asks for, and the
                        // row wraps as a whole when the options no longer fit side by side.
                        .widthIn(min = CHOICE_MIN_WIDTH)
                        .clip(shape)
                        .background(background)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = shape,
                        )
                        .hoverable(interaction)
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            interactionSource = interaction,
                            indication = null,
                            onClick = { onSelect(option) },
                        )
                        .padding(horizontal = Space.m, vertical = Space.s),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** How long an option takes to reach its hover color, at a constant rate. */
private const val CHOICE_FADE_MS = 120

/**
 * The narrowest an option is drawn.
 *
 * Wide enough for a four-character label with its padding, so the usual labels never wrap; a
 * row that cannot hold two of these puts them on separate lines instead of shrinking them.
 */
private val CHOICE_MIN_WIDTH = 72.dp

/** How far an unselected option moves towards the ink while the pointer is over it. */
private const val HOVER_TINT = 0.10f

/** A failure or a result, in the color that says which it is. */
@Composable
fun MessageLine(message: String?, isError: Boolean = true) {
    message ?: return
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
    )
}

/**
 * The beginning of a device identifier, which is what tells two devices apart on screen.
 *
 * An identifier is sixty-four hexadecimal characters — the fingerprint of a device's identity
 * key — and no screen has room for it, nor has a user any use for all of it: the first
 * characters are what distinguish the two devices in front of them, and the whole value is
 * shown where comparing it matters.
 */
fun shortDeviceId(deviceId: String): String =
    if (deviceId.length <= SHORT_DEVICE_ID_CHARS) deviceId else deviceId.take(SHORT_DEVICE_ID_CHARS) + "…"

/** How many characters of an identifier [shortDeviceId] keeps. */
private const val SHORT_DEVICE_ID_CHARS = 8

/**
 * The properties every dialog in this application is opened with.
 *
 * # Why a click outside does not close a dialog
 *
 * A dialog here is a form, and a form is not something to throw away: a click that lands
 * outside the window is not a decision to discard what has been typed, so the ways out are
 * Cancel and Back, which are both there and both deliberate. Compose also dismisses a dialog
 * on a motion event it merely *observes* outside one, whether the user meant it or not.
 * Leaving the property at its default would therefore put the typed input at the mercy of a
 * stray event.
 */
internal val DialogDefaults = DialogProperties(dismissOnClickOutside = false)

/**
 * The language selector: the current choice, and a menu with every language.
 *
 * The names in the menu are endonyms (see [AppLanguage]), so a user who cannot read the
 * language in effect can still find their own in the list.
 */
/** The localized name of one theme choice. */
@Composable
private fun themeLabel(option: AppTheme): String =
    when (option) {
        AppTheme.System -> stringResource(Res.string.theme_system)
        AppTheme.Light -> stringResource(Res.string.theme_light)
        AppTheme.Dark -> stringResource(Res.string.theme_dark)
    }

/**
 * A large floating window for a task that needs the room but not a screen of its own.
 *
 * Importing a file, exporting the vault and managing groups all have enough content to need a
 * full page's worth of space, but none of them is a place in the application's hierarchy: they
 * are tasks opened from the settings or the entry list. A dialog keeps that relationship
 * visible — the screen that opened it stays behind the scrim — and the platform's back request
 * dismisses it without a handler of its own.
 *
 * The window is capped on a desktop (where the whole screen would be an unreasonable sheet for
 * a task) and fills the available width, less a margin, on a phone.
 */
@Composable
fun FloatingWindow(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        PlatformDialogWindow()
        Box(
            // No bottom margin: the window's content reaches the bottom of its floating
            // surface. The other three sides keep room for the shadow; the bottom deliberately
            // does not, because the surface is allowed to sit against the edge of the window.
            modifier =
                Modifier
                    .fillMaxSize()
                    .clearFocusOnTap()
                    .padding(start = Space.xl, top = Space.xl, end = Space.xl),
            contentAlignment = Alignment.Center,
        ) {
            ShadowedSurface(
                shape = MaterialTheme.shapes.extraLarge,
                modifier =
                    Modifier
                        .widthIn(max = 1100.dp)
                        .fillMaxWidth()
                        .fillMaxHeight(),
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 0.dp,
                ) {
                    // The navigation is behind the dialog; a task inside it must not leave room
                    // for an island it cannot see.
                    CompositionLocalProvider(LocalNavigationInset provides 0.dp) { content() }
                }
            }
        }
    }
}

/** A button with the current theme, and a menu with the three choices. */
@Composable
fun ThemeSelector(theme: AppTheme, onSelect: (AppTheme) -> Unit) {
    var open by remember { mutableStateOf(false) }

    Box {
        TextButton(onClick = { open = true }) {
            Text(themeLabel(theme))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AppTheme.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(themeLabel(option)) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
fun LanguageSelector(language: AppLanguage, onSelect: (AppLanguage) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(language.label())
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AppLanguage.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label()) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}
