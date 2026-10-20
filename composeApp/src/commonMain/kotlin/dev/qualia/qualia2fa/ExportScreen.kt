package dev.qualia.qualia2fa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.action_close
import dev.qualia.qualia2fa.resources.action_save
import dev.qualia.qualia2fa.resources.export_drops_groups
import dev.qualia.qualia2fa.resources.export_encrypted_json_explainer
import dev.qualia.qualia2fa.resources.export_failed
import dev.qualia.qualia2fa.resources.export_filter
import dev.qualia.qualia2fa.resources.export_html_explainer
import dev.qualia.qualia2fa.resources.export_json_explainer
import dev.qualia.qualia2fa.resources.export_keeps_tree
import dev.qualia.qualia2fa.resources.export_keeps_flat_groups
import dev.qualia.qualia2fa.resources.export_needs_password
import dev.qualia.qualia2fa.resources.export_password_hint
import dev.qualia.qualia2fa.resources.export_password_required
import dev.qualia.qualia2fa.resources.export_password_title
import dev.qualia.qualia2fa.resources.export_plaintext_warning
import dev.qualia.qualia2fa.resources.export_save_failed
import dev.qualia.qualia2fa.resources.export_save_title
import dev.qualia.qualia2fa.resources.export_saved
import dev.qualia.qualia2fa.resources.export_vault_explainer
import dev.qualia.qualia2fa.resources.export_encrypted_vault_explainer
import dev.qualia.qualia2fa.resources.export_text_explainer
import dev.qualia.qualia2fa.resources.export_title
import dev.qualia.qualia2fa.resources.format_encrypted_json
import dev.qualia.qualia2fa.resources.format_html
import dev.qualia.qualia2fa.resources.format_json
import dev.qualia.qualia2fa.resources.format_vault
import dev.qualia.qualia2fa.resources.format_encrypted_vault
import dev.qualia.qualia2fa.resources.format_text
import org.jetbrains.compose.resources.stringResource

/** The name an export is offered under, before the extension of its format is added. */
private const val EXPORT_FILE_NAME = "qualia2fa-export"

/**
 * Writing the vault out, in one of six formats.
 *
 * # What the user is told before a file exists
 *
 * The six are not equally safe and not equally complete. The two `.q2fa` containers restore the
 * whole account - entries, the group tree and the icons - and the **plain one does it because the
 * master key is inside it in clear**. The four external formats are shapes another authenticator
 * reads; only the encrypted JSON protects anything, the other three hold every secret as it
 * stands, and the HTML export draws each entry's own link - secret included - into a QR code.
 * That is said above the formats, and each one carries its own sentence and its marks, so the
 * choice is made knowing what it writes.
 *
 * # Where the empty password is stopped
 *
 * Here, not in the library. The library refuses it too, but it refuses by returning `null`,
 * which this screen reports as "the export could not be written" - and the refusal has to be
 * said in the terms the user can act on. It is also the cheaper place: a password the library
 * rejects after the save dialog has been answered looks like a file that was written and lost.
 *
 * # Why the whole vault, and nothing narrower
 *
 * The library writes the vault it holds; there is no "selected entries" export to offer. How much
 * of the group tree each format keeps is [ExportFormat.groupCarrying], and the marks on every
 * option say it before the file exists.
 */
@Composable
fun ExportScreen(
    vault: Vault,
    onClose: () -> Unit,
) {
    val picker = rememberFilePicker()
    var format by remember { mutableStateOf(ExportFormat.Vault) }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var savedTo by remember { mutableStateOf<String?>(null) }

    val saveTitle = stringResource(Res.string.export_save_title)
    val filterLabel = stringResource(Res.string.export_filter)
    val exportFailed = stringResource(Res.string.export_failed)
    val saveFailed = stringResource(Res.string.export_save_failed)
    val passwordRequired = stringResource(Res.string.export_password_required)
    // Resolved while composing: the callback that writes the file runs outside composition,
    // where no resource can be read.
    val needsPasswordMark = stringResource(Res.string.export_needs_password)
    val keepsTreeMark = stringResource(Res.string.export_keeps_tree)
    val keepsFlatGroupsMark = stringResource(Res.string.export_keeps_flat_groups)
    val dropsGroupsMark = stringResource(Res.string.export_drops_groups)
    val formatLabels =
        mapOf(
            ExportFormat.Vault to stringResource(Res.string.format_vault),
            ExportFormat.EncryptedVault to stringResource(Res.string.format_encrypted_vault),
            ExportFormat.Text to stringResource(Res.string.format_text),
            ExportFormat.Json to stringResource(Res.string.format_json),
            ExportFormat.EncryptedJson to stringResource(Res.string.format_encrypted_json),
            ExportFormat.Html to stringResource(Res.string.format_html),
        )
    val formatExplanations =
        mapOf(
            ExportFormat.Vault to stringResource(Res.string.export_vault_explainer),
            ExportFormat.EncryptedVault to stringResource(Res.string.export_encrypted_vault_explainer),
            ExportFormat.Text to stringResource(Res.string.export_text_explainer),
            ExportFormat.Json to stringResource(Res.string.export_json_explainer),
            ExportFormat.EncryptedJson to stringResource(Res.string.export_encrypted_json_explainer),
            ExportFormat.Html to stringResource(Res.string.export_html_explainer),
        )

    /** The facts about [option] a user has to know before choosing it. */
    fun marksOf(option: ExportFormat): String =
        listOfNotNull(
            if (option.needsPassword) needsPasswordMark else null,
            when (option.groupCarrying) {
                GroupCarrying.FullTree -> keepsTreeMark
                GroupCarrying.FlatGroups -> keepsFlatGroupsMark
                GroupCarrying.None -> dropsGroupsMark
            },
        ).joinToString(" · ")

    /** Writes the vault in the chosen format to the file the user picks. */
    fun save() {
        if (format.needsPassword && password.isEmpty()) {
            message = passwordRequired
            savedTo = null
            return
        }
        val bytes = vault.export(format, password.takeIf { format.needsPassword })
        if (bytes == null) {
            message = exportFailed
            savedTo = null
            return
        }
        picker.saveBytes(
            if (format == ExportFormat.Vault || format == ExportFormat.EncryptedVault) {
                PORTABLE_FILE_NAME
            } else {
                "$EXPORT_FILE_NAME.${format.extension}"
            },
            saveTitle,
            filterLabel,
            format.extension,
            bytes,
        ) { path ->
            if (path == null) {
                message = saveFailed
                savedTo = null
            } else {
                message = null
                savedTo = path
            }
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = Space.xl, top = Space.l, end = Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        ScreenTitle(stringResource(Res.string.export_title))
        Hint(stringResource(Res.string.export_plaintext_warning))

        ExportFormat.entries.forEach { option ->
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = option == format,
                        onClick = {
                            format = option
                            // The previous answer was about another format, so what it said
                            // about a file is no longer what the next save would write.
                            message = null
                            savedTo = null
                        },
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Text(formatLabels.getValue(option), style = MaterialTheme.typography.titleMedium)
                        Text(marksOf(option), style = MaterialTheme.typography.bodySmall)
                        Hint(formatExplanations.getValue(option))
                    }
                }
            }
        }

        if (format.needsPassword) {
            Text(stringResource(Res.string.export_password_title), style = MaterialTheme.typography.titleMedium)
            SecretField(
                value = password,
                onValueChange = {
                    password = it
                    message = null
                },
                label = stringResource(Res.string.export_password_hint),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        MessageLine(message)
        savedTo?.let { path -> MessageLine(stringResource(Res.string.export_saved, path), isError = false) }

        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Button(onClick = { save() }) { Text(stringResource(Res.string.action_save)) }
            TextButton(onClick = onClose) { Text(stringResource(Res.string.action_close)) }
        }

        Spacer(Modifier.height(LocalNavigationInset.current))
    }
}
