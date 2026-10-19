package dev.qualia.qualia2fa

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.action_cancel
import dev.qualia.qualia2fa.resources.action_continue
import dev.qualia.qualia2fa.resources.action_export
import dev.qualia.qualia2fa.resources.action_export_recovery
import dev.qualia.qualia2fa.resources.action_import_export
import dev.qualia.qualia2fa.resources.action_import_recovery
import dev.qualia.qualia2fa.resources.action_reset
import dev.qualia.qualia2fa.resources.action_reset_master_key
import dev.qualia.qualia2fa.resources.app_name
import dev.qualia.qualia2fa.resources.dialog_import_recovery_text
import dev.qualia.qualia2fa.resources.dialog_import_recovery_title
import dev.qualia.qualia2fa.resources.dialog_open_recovery
import dev.qualia.qualia2fa.resources.dialog_reset_text
import dev.qualia.qualia2fa.resources.dialog_reset_title
import dev.qualia.qualia2fa.resources.dialog_save_recovery
import dev.qualia.qualia2fa.resources.error_passphrase_mismatch
import dev.qualia.qualia2fa.resources.error_recovery_export
import dev.qualia.qualia2fa.resources.error_recovery_key_only
import dev.qualia.qualia2fa.resources.error_recovery_open
import dev.qualia.qualia2fa.resources.error_recovery_rejected
import dev.qualia.qualia2fa.resources.error_recovery_save
import dev.qualia.qualia2fa.resources.error_recovery_vault_failed
import dev.qualia.qualia2fa.resources.field_passphrase
import dev.qualia.qualia2fa.resources.field_passphrase_again
import dev.qualia.qualia2fa.resources.filter_recovery_files
import dev.qualia.qualia2fa.resources.message_recovery_saved
import dev.qualia.qualia2fa.resources.nav_settings
import dev.qualia.qualia2fa.resources.qualia2fa_logo
import dev.qualia.qualia2fa.resources.settings_about
import dev.qualia.qualia2fa.resources.settings_core_version
import dev.qualia.qualia2fa.resources.settings_device_identity
import dev.qualia.qualia2fa.resources.settings_hardware_backed
import dev.qualia.qualia2fa.resources.settings_key_store
import dev.qualia.qualia2fa.resources.settings_language
import dev.qualia.qualia2fa.resources.settings_license
import dev.qualia.qualia2fa.resources.settings_theme
import dev.qualia.qualia2fa.resources.settings_lock_now
import dev.qualia.qualia2fa.resources.settings_master_key_software
import dev.qualia.qualia2fa.resources.settings_no_device_identity
import dev.qualia.qualia2fa.resources.settings_recovery
import dev.qualia.qualia2fa.resources.settings_recovery_explainer
import dev.qualia.qualia2fa.resources.settings_reset
import dev.qualia.qualia2fa.resources.settings_reset_explainer
import dev.qualia.qualia2fa.resources.settings_software_backed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Where an import is in its two steps.
 *
 * Both steps carry the chosen file, and both compare it by content: the `equals` a data class
 * generates compares an array member by reference, which would make two steps holding the same
 * file two different values.
 */
private sealed interface ImportStep {
    /** The file is chosen; the user is asked whether to replace this device's vault. */
    data class Confirm(val file: ByteArray) : ImportStep {
        override fun equals(other: Any?): Boolean = other is Confirm && file.contentEquals(other.file)

        override fun hashCode(): Int = file.contentHashCode()
    }

    /** Confirmed; the passphrase is being asked for. */
    data class Passphrase(val file: ByteArray) : ImportStep {
        override fun equals(other: Any?): Boolean = other is Passphrase && file.contentEquals(other.file)

        override fun hashCode(): Int = file.contentHashCode()
    }
}

/**
 * Everything that is not an entry: the language, what this device holds, the recovery key and
 * the way out.
 *
 * # What the two facts are for
 *
 * "Where the master key lives" and "what backs the device identity" are shown as they really
 * are — a software key store is reported as one. A user is entitled to know whether the thing
 * that protects the account is hardware or a file, and the alternative is a reassurance that
 * is not true.
 *
 * # Recovery keys
 *
 * Exporting writes a file protected by a passphrase; importing replaces this device's vault
 * with the account that file belongs to, which is why it asks twice — once about replacing,
 * once for the passphrase.
 */
@Composable
fun SettingsScreen(
    vault: Vault,
    protection: MasterKeyProtection,
    identity: DeviceIdentity?,
    /**
     * Where an imported master key is written, or `null` on a machine with no platform key
     * store — which is also a machine that cannot have reached this screen with a vault open.
     */
    masterKeyStore: MasterKeyStore?,
    language: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit,
    theme: AppTheme,
    onSelectTheme: (AppTheme) -> Unit,
    onLock: () -> Unit,
    onImported: (Vault) -> Unit,
    onReset: () -> Unit,
) {
    var message by remember { mutableStateOf<String?>(null) }
    var savedTo by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var importStep by remember { mutableStateOf<ImportStep?>(null) }
    var importing by remember { mutableStateOf(false) }
    var exportingVault by remember { mutableStateOf(false) }
    val picker = rememberFilePicker()
    val scope = rememberCoroutineScope()

    val saveTitle = stringResource(Res.string.dialog_save_recovery)
    val openTitle = stringResource(Res.string.dialog_open_recovery)
    val filterLabel = stringResource(Res.string.filter_recovery_files)
    val exportLabel = stringResource(Res.string.action_export_recovery)
    val importLabel = stringResource(Res.string.action_import_recovery)
    val exportFailed = stringResource(Res.string.error_recovery_export)
    val saveFailed = stringResource(Res.string.error_recovery_save)
    val openFailed = stringResource(Res.string.error_recovery_open)
    val rejected = stringResource(Res.string.error_recovery_rejected)
    val keyOnly = stringResource(Res.string.error_recovery_key_only)
    val vaultFailed = stringResource(Res.string.error_recovery_vault_failed)
    val importExportLabel = stringResource(Res.string.action_import_export)
    val exportVaultLabel = stringResource(Res.string.action_export)

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(start = Space.xl, top = Space.l, end = Space.xl)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        ScreenHeader(title = stringResource(Res.string.nav_settings))

        SectionCard(title = stringResource(Res.string.settings_language)) {
            LanguageSelector(language = language, onSelect = onSelectLanguage)
        }

        SectionCard(title = stringResource(Res.string.settings_theme)) {
            ThemeSelector(theme = theme, onSelect = onSelectTheme)
        }

        SectionCard {
            LabeledValue(
                label = stringResource(Res.string.settings_key_store),
                value =
                    if (protection.hardwareBacked) {
                        // The name the platform store gives itself, next to what that name
                        // means: "tpm · hardware protected" leaves the user nothing to guess
                        // about which of the two protects the account.
                        "${protection.backend} · ${stringResource(Res.string.settings_hardware_backed)}"
                    } else {
                        // Said the same way, because the backend is the fact and the sentence
                        // is what it means: a software one is the operating system account and
                        // nothing more.
                        "${protection.backend} · ${stringResource(Res.string.settings_master_key_software)}"
                    },
            )
            LabeledValue(
                label = stringResource(Res.string.settings_device_identity),
                value =
                    if (identity == null) {
                        stringResource(Res.string.settings_no_device_identity)
                    } else {
                        val protection =
                            stringResource(
                                if (identity.hardwareBacked) {
                                    Res.string.settings_hardware_backed
                                } else {
                                    Res.string.settings_software_backed
                                },
                            )
                        "${identity.backend} · $protection"
                    },
            )
            LabeledValue(
                label = stringResource(Res.string.settings_core_version),
                value = Qualia.coreVersion(),
            )
        }

        SectionCard(title = stringResource(Res.string.settings_recovery)) {
            Hint(stringResource(Res.string.settings_recovery_explainer))
            OutlinedButton(onClick = { exporting = true }, modifier = Modifier.fillMaxWidth()) {
                Text(exportLabel)
            }
            OutlinedButton(
                onClick = {
                    picker.pickBytes(openTitle, filterLabel, RECOVERY_FILE_EXTENSION) { file ->
                        if (file == null) {
                            message = openFailed
                        } else {
                            message = null
                            importStep = ImportStep.Confirm(file)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(importLabel)
            }
            savedTo?.let { path ->
                MessageLine(stringResource(Res.string.message_recovery_saved, path), isError = false)
            }
        }

        SectionCard {
            OutlinedButton(onClick = { importing = true }, modifier = Modifier.fillMaxWidth()) {
                Text(importExportLabel)
            }
            OutlinedButton(onClick = { exportingVault = true }, modifier = Modifier.fillMaxWidth()) {
                Text(exportVaultLabel)
            }
        }

        SectionCard {
            Button(onClick = onLock, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.settings_lock_now))
            }
        }

        SectionCard(title = stringResource(Res.string.settings_reset)) {
            Hint(stringResource(Res.string.settings_reset_explainer))
            OutlinedButton(
                onClick = { resetting = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.action_reset_master_key))
            }
        }

        SectionCard(title = stringResource(Res.string.settings_about)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                Image(
                    painter = painterResource(Res.drawable.qualia2fa_logo),
                    contentDescription = null,
                    modifier = Modifier.size(Sizes.logoInline),
                )
                Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.titleMedium)
            }
            Hint(stringResource(Res.string.settings_license))
        }

        MessageLine(message)

        // Keep the end of the page above the floating navigation island.
        Spacer(Modifier.height(LocalNavigationInset.current))
    }

    if (importing) {
        FloatingWindow(onDismiss = { importing = false }) {
            ImportScreen(
                vault = vault,
                onClose = { importing = false },
                onApplied = { onImported(vault) },
            )
        }
    }

    if (exportingVault) {
        FloatingWindow(onDismiss = { exportingVault = false }) {
            ExportScreen(
                vault = vault,
                onClose = { exportingVault = false },
            )
        }
    }

    if (exporting) {
        PassphraseDialog(
            title = exportLabel,
            confirmLabel = exportLabel,
            askTwice = true,
            onDismiss = { exporting = false },
            onConfirm = { passphrase ->
                exporting = false
                scope.launch {
                    // The passphrase derivation is deliberately expensive; keep the settings
                    // screen responsive while it runs.
                    val file = withContext(Dispatchers.Default) { buildRecoveryBackup(vault, passphrase) }
                    if (file == null) {
                        message = exportFailed
                    } else {
                        picker.saveBytes(RECOVERY_FILE_NAME, saveTitle, filterLabel, RECOVERY_FILE_EXTENSION, file) { path ->
                            if (path == null) {
                                message = saveFailed
                            } else {
                                message = null
                                savedTo = path
                            }
                        }
                    }
                }
            },
        )
    }

    if (resetting) {
        AlertDialog(
            onDismissRequest = { resetting = false },
            properties = DialogDefaults,
            title = { Text(stringResource(Res.string.dialog_reset_title)) },
            text = { Text(stringResource(Res.string.dialog_reset_text)) },
            confirmButton = {
                Button(
                    onClick = {
                        resetting = false
                        onReset()
                    },
                ) {
                    Text(stringResource(Res.string.action_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { resetting = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }

    when (val step = importStep) {
        null -> Unit

        is ImportStep.Confirm ->
            AlertDialog(
                onDismissRequest = { importStep = null },
                properties = DialogDefaults,
                title = { Text(stringResource(Res.string.dialog_import_recovery_title)) },
                text = { Text(stringResource(Res.string.dialog_import_recovery_text)) },
                confirmButton = {
                    Button(onClick = { importStep = ImportStep.Passphrase(step.file) }) {
                        Text(stringResource(Res.string.action_continue))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { importStep = null }) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                },
            )

        is ImportStep.Passphrase ->
            PassphraseDialog(
                title = importLabel,
                confirmLabel = importLabel,
                askTwice = false,
                onDismiss = { importStep = null },
                onConfirm = { passphrase ->
                    importStep = null
                    val target = masterKeyStore
                    if (target == null) {
                        message = rejected
                    } else {
                        val current = vault.serialize()
                        scope.launch {
                            // See the export: the recovery derivation must not block the UI.
                            val outcome =
                                withContext(Dispatchers.Default) {
                                    restoreRecoveryFile(step.file, passphrase, target, current)
                                }
                            when (outcome) {
                                is RecoveryRestore.Recovered -> {
                                    message = null
                                    onImported(outcome.vault)
                                }

                                RecoveryRestore.KeyOnly -> message = keyOnly
                                RecoveryRestore.VaultFailed -> message = vaultFailed
                                RecoveryRestore.Rejected -> message = rejected
                            }
                        }
                    }
                },
            )
    }
}
