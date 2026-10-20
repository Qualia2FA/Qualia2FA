package dev.qualia.qualia2fa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.action_apply
import dev.qualia.qualia2fa.resources.action_close
import dev.qualia.qualia2fa.resources.action_import_vault
import dev.qualia.qualia2fa.resources.decision_add
import dev.qualia.qualia2fa.resources.decision_keep_both
import dev.qualia.qualia2fa.resources.decision_replace
import dev.qualia.qualia2fa.resources.decision_skip
import dev.qualia.qualia2fa.resources.entry_type_hotp
import dev.qualia.qualia2fa.resources.entry_type_motp
import dev.qualia.qualia2fa.resources.entry_type_steam
import dev.qualia.qualia2fa.resources.entry_type_totp
import dev.qualia.qualia2fa.resources.entry_type_yandex
import dev.qualia.qualia2fa.resources.field_pin
import dev.qualia.qualia2fa.resources.format_html
import dev.qualia.qualia2fa.resources.format_json
import dev.qualia.qualia2fa.resources.format_text
import dev.qualia.qualia2fa.resources.import_choose_format
import dev.qualia.qualia2fa.resources.import_done
import dev.qualia.qualia2fa.resources.import_dropped
import dev.qualia.qualia2fa.resources.import_filter
import dev.qualia.qualia2fa.resources.import_identical
import dev.qualia.qualia2fa.resources.import_no_entries
import dev.qualia.qualia2fa.resources.import_not_recognised
import dev.qualia.qualia2fa.resources.import_open_title
import dev.qualia.qualia2fa.resources.import_password_hint
import dev.qualia.qualia2fa.resources.import_password_title
import dev.qualia.qualia2fa.resources.import_preview_title
import dev.qualia.qualia2fa.resources.import_problems
import dev.qualia.qualia2fa.resources.import_replace_with
import dev.qualia.qualia2fa.resources.import_unreadable
import dev.qualia.qualia2fa.resources.import_wrong_password
import org.jetbrains.compose.resources.stringResource

/**
 * Where the import is.
 *
 * A value per stage rather than a set of flags: the file has been chosen or it has not, it is
 * waiting for a password or it is ready to be answered, and a state that says which cannot end up
 * showing a preview of a file that was never read.
 */
private sealed interface ImportStage {
    /** Nothing chosen yet. */
    data object Choosing : ImportStage

    /** The file needs the password it was exported with. */
    data class Password(val bytes: ByteArray, val format: ImportFormat) : ImportStage

    /** The file was read: what it holds, and what the answers are so far. */
    data class Preview(
        val parsed: Import,
        val matches: List<ImportMatch>,
        val decisions: Map<Int, ImportDecision>,
    ) : ImportStage

    /** The import was applied. */
    data class Done(val applied: ImportApplied) : ImportStage
}

/**
 * Importing another authenticator's export.
 *
 * # What the user is shown, and when
 *
 * The file is read **before** anything is written, and what comes back is shown as a preview: the
 * entries the export holds, the rows that could not be used, and what the export could not carry
 * across. Only then can the import be applied. A file that is applied as it is read would give the
 * user no chance to see that the wrong file was picked, and no way back from it.
 *
 * # What a user is asked about
 *
 * An entry the vault already holds **unchanged** is not a question: it is skipped, and the preview
 * says so. An entry that collides - the same secret, different fields - always is one: the reader
 * never decides on its own which of two versions of a note to lose, so the four answers are
 * offered with the entry that collides named. An entry with nothing to collide with is added, and
 * needs no answer.
 *
 * The identifiers in [ImportMatch.existing] are resolved to names here, against the vault being
 * imported into: an identifier is what a replacement is expressed in, and a name is what a user
 * can read.
 *
 * # The preview scrolls with the page, it is not a list of its own
 *
 * Every stage is one column with one scroll. A `LazyColumn` inside it would be handed the
 * infinite height a vertical scroll gives its children, which Compose refuses to measure; the
 * preview is therefore a plain column, bounded by the import's own entry limit.
 */
@Composable
fun ImportScreen(
    vault: Vault,
    onClose: () -> Unit,
    onApplied: () -> Unit,
    /**
     * Called when the chosen file is a `.q2fa` backup.
     *
     * A backup is not an import of entries: it replaces the whole account, so the caller runs the
     * restore path (confirmation, and a passphrase for the encrypted form) instead of a preview.
     * The bytes are handed over as they are; the detection happens here because it is the same
     * file picker that would otherwise say "format not recognised".
     */
    onPortableFile: (ByteArray) -> Unit,
) {
    val picker = rememberFilePicker()
    var stage by remember { mutableStateOf<ImportStage>(ImportStage.Choosing) }
    var message by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }

    val openTitle = stringResource(Res.string.import_open_title)
    val filterLabel = stringResource(Res.string.import_filter)
    val unreadable = stringResource(Res.string.import_unreadable)
    val wrongPassword = stringResource(Res.string.import_wrong_password)
    val noEntries = stringResource(Res.string.import_no_entries)
    // Resolved while composing: the callback that reads the file runs outside composition, where
    // no resource can be read.
    val notRecognised = stringResource(Res.string.import_not_recognised)
    val formatLabels =
        mapOf(
            ImportFormat.Json to stringResource(Res.string.format_json),
            ImportFormat.Html to stringResource(Res.string.format_html),
            ImportFormat.Text to stringResource(Res.string.format_text),
        )
    val kindLabels =
        mapOf<Class<*>, String>(
            EntryKind.Totp::class.java to stringResource(Res.string.entry_type_totp),
            EntryKind.Hotp::class.java to stringResource(Res.string.entry_type_hotp),
            EntryKind.Motp::class.java to stringResource(Res.string.entry_type_motp),
            EntryKind.Yandex::class.java to stringResource(Res.string.entry_type_yandex),
            EntryKind.Steam::class.java to stringResource(Res.string.entry_type_steam),
        )
    val decisionLabels =
        mapOf(
            ImportDecisionChoice.Add to stringResource(Res.string.decision_add),
            ImportDecisionChoice.Skip to stringResource(Res.string.decision_skip),
            ImportDecisionChoice.Replace to stringResource(Res.string.decision_replace),
            ImportDecisionChoice.KeepBoth to stringResource(Res.string.decision_keep_both),
        )

    /** Reads [bytes] as [format], and moves on to the stage the answer belongs to. */
    fun read(bytes: ByteArray, format: ImportFormat, withPassword: String?) {
        val parsed = Qualia.readImport(bytes, format, withPassword, currentTimeSeconds())
        if (parsed != null) {
            val entries = parsed.entries()
            if (entries.isEmpty()) {
                message = noEntries
                return
            }
            stage = ImportStage.Preview(parsed, parsed.compare(vault), emptyMap())
            message = null
            return
        }
        // An encrypted JSON export is the one case where the same bytes become readable once the
        // user answers something, so it is the one case worth asking about: everything else is a
        // refusal, and asking for a password that cannot help only wastes the user's time.
        if (format == ImportFormat.Json) {
            stage = ImportStage.Password(bytes, format)
            message = if (withPassword == null) null else wrongPassword
        } else {
            message = unreadable
        }
    }

    /** Opens a file without saying what it is, and lets the bytes decide. */
    fun openAny() {
        picker.pickBytes(openTitle, filterLabel, extension = null) { file ->
            if (file == null) {
                message = unreadable
                return@pickBytes
            }
            // A `.q2fa` is a whole account, not a list of entries: it goes to the restore path,
            // which asks before replacing anything. Checking this first is what makes "import"
            // one entry in the interface rather than two files to know apart.
            if (Qualia.detectPortableKind(file) != null) {
                onPortableFile(file)
                return@pickBytes
            }
            val detected = Qualia.detectImportFormat(file)
            if (detected == null) {
                message = notRecognised
            } else {
                read(file, detected, null)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(start = Space.xl, top = Space.l, end = Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        ScreenHeader(title = stringResource(Res.string.action_import_vault)) {
            TextButton(onClick = onClose) { Text(stringResource(Res.string.action_close)) }
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            when (val current = stage) {
                ImportStage.Choosing -> {
                    SectionCard(title = openTitle) {
                        Button(onClick = { openAny() }, modifier = Modifier.fillMaxWidth()) {
                            Text(openTitle)
                        }
                        // The format can also be stated outright: a file that holds one link per
                        // line is recognisable, but an HTML export whose content was truncated is
                        // not, and the user is the one who knows what they exported.
                        Text(
                            stringResource(Res.string.import_choose_format),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Space.s),
                            verticalArrangement = Arrangement.spacedBy(Space.s),
                        ) {
                            ImportFormat.entries.forEach { format ->
                                OutlinedButton(onClick = {
                                    picker.pickBytes(openTitle, filterLabel, format.extension) { file ->
                                        if (file != null) read(file, format, null)
                                    }
                                }) {
                                    Text(formatLabels.getValue(format))
                                }
                            }
                        }
                    }
                    MessageLine(message)
                }

                is ImportStage.Password -> {
                    SectionCard(title = stringResource(Res.string.import_password_title)) {
                        SecretField(
                            value = password,
                            onValueChange = { password = it },
                            label = stringResource(Res.string.import_password_hint),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { read(current.bytes, current.format, password) },
                            enabled = password.isNotEmpty(),
                        ) {
                            Text(stringResource(Res.string.action_apply))
                        }
                    }
                    MessageLine(message)
                }

                is ImportStage.Preview -> {
                    PreviewStage(
                        vault = vault,
                        stage = current,
                        kindLabels = kindLabels,
                        decisionLabels = decisionLabels,
                        onDecide = { position, decision ->
                            stage =
                                current.copy(
                                    decisions =
                                        if (decision == null) {
                                            current.decisions - position
                                        } else {
                                            current.decisions + (position to decision)
                                        },
                                )
                        },
                    )
                    MessageLine(message)
                    Button(
                        onClick = {
                            val applied = current.parsed.apply(vault, current.decisions)
                            if (applied == null) {
                                message = unreadable
                            } else {
                                stage = ImportStage.Done(applied)
                                message = null
                                onApplied()
                            }
                        },
                    ) { Text(stringResource(Res.string.action_apply)) }
                }

                is ImportStage.Done -> {
                    SectionCard {
                        Text(
                            stringResource(
                                Res.string.import_done,
                                current.applied.added,
                                current.applied.replaced,
                                current.applied.skipped,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        // The names of the groups the import created or matched. A group the
                        // vault already had is named too: it is where the imported entries were
                        // put, which is what the user needs to find them again.
                        (current.applied.groupsCreated + current.applied.groupsExisting).forEach { name ->
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(onClick = onClose) { Text(stringResource(Res.string.action_close)) }
                    }
                }
            }

            // The navigation island floats over the bottom of the window, so the end of the
            // scroll has to stop above it even though the middle of the page may run under it.
            Spacer(Modifier.height(LocalNavigationInset.current))
        }
    }
}

/**
 * The four answers a colliding entry can be given, in the order they are offered.
 *
 * A separate choice value from [ImportDecision] because "replace" needs the entry it replaces,
 * which is chosen from the ones that collide rather than typed, and because the order here is a
 * presentation decision while the decision itself is the boundary's.
 */
private enum class ImportDecisionChoice {
    Add,
    Skip,
    Replace,
    KeepBoth,
}

/** The entries the export would contribute, and the answers so far. */
@Composable
private fun PreviewStage(
    vault: Vault,
    stage: ImportStage.Preview,
    kindLabels: Map<Class<*>, String>,
    decisionLabels: Map<ImportDecisionChoice, String>,
    onDecide: (Int, ImportDecision?) -> Unit,
) {
    val entries = stage.parsed.entries()
    val problems = stage.parsed.problems()
    val dropped = stage.parsed.dropped()
    // Resolved once per vault and preview: a decision replaces an entry by identifier, and an
    // identifier is not something a user can read.
    val names = remember(vault.entryCount, entries.size) {
        vault.entries().associate { it.id to it.presentedName() }
    }
    val byPosition = stage.matches.associateBy { it.position }

    Text(stringResource(Res.string.import_preview_title), style = MaterialTheme.typography.titleMedium)

    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        entries.forEach { entry ->
            val match = byPosition[entry.position]
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Space.xs),
                        verticalArrangement = Arrangement.spacedBy(Space.xs),
                    ) {
                        Text(entry.presentedName(), style = MaterialTheme.typography.titleMedium)
                        val detail = entry.presentedDetail()
                        if (detail != null) {
                            Text("·", style = MaterialTheme.typography.bodySmall)
                            Text(detail, style = MaterialTheme.typography.bodySmall)
                        }
                        val kindLabel = kindLabels[entry.kind.javaClass]
                        if (kindLabel != null) {
                            Text(kindLabel, style = MaterialTheme.typography.bodySmall)
                        }
                        if (entry.kind is EntryKind.Motp || entry.kind is EntryKind.Yandex) {
                            // The PIN itself stays in the library and is applied from the parsed
                            // export, so what is said here is only that this kind uses one.
                            Text(stringResource(Res.string.field_pin), style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    if (match == null || match.identical) {
                        Hint(stringResource(Res.string.import_identical))
                    } else {
                        val existing = match.existing
                        existing.forEach { id ->
                            Text(
                                stringResource(Res.string.import_replace_with, names[id] ?: shortDeviceId(id)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        val chosen =
                            when (val decision = stage.decisions[entry.position]) {
                                null, ImportDecision.Add -> ImportDecisionChoice.Add
                                ImportDecision.Skip -> ImportDecisionChoice.Skip
                                ImportDecision.KeepBoth -> ImportDecisionChoice.KeepBoth
                                is ImportDecision.Replace -> ImportDecisionChoice.Replace
                            }
                        ChoiceRow(
                            options = ImportDecisionChoice.entries,
                            selected = chosen,
                            label = { decisionLabels.getValue(it) },
                            onSelect = { choice ->
                                onDecide(
                                    entry.position,
                                    when (choice) {
                                        ImportDecisionChoice.Add -> ImportDecision.Add
                                        ImportDecisionChoice.Skip -> ImportDecision.Skip
                                        ImportDecisionChoice.KeepBoth -> ImportDecision.KeepBoth
                                        ImportDecisionChoice.Replace ->
                                            existing.firstOrNull()?.let { ImportDecision.Replace(it) }
                                    },
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    if (problems.isNotEmpty()) {
        Text(stringResource(Res.string.import_problems, problems.size), style = MaterialTheme.typography.bodySmall)
        problems.take(PROBLEMS_SHOWN).forEach { problem -> LabeledValue(problem.position.toString(), problem.reason) }
    }
    if (dropped.icons > 0 || dropped.periodsAssumed > 0 || dropped.duplicates > 0) {
        Hint(stringResource(Res.string.import_dropped, dropped.icons, dropped.periodsAssumed, dropped.duplicates))
    }
}

/** How many unusable rows are listed before the count has to speak for the rest. */
private const val PROBLEMS_SHOWN = 5
