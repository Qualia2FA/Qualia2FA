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
import dev.qualia.qualia2fa.resources.action_export_vault
import dev.qualia.qualia2fa.resources.action_import_vault
import dev.qualia.qualia2fa.resources.action_import_vault
import dev.qualia.qualia2fa.resources.action_reset
import dev.qualia.qualia2fa.resources.action_reset_master_key
import dev.qualia.qualia2fa.resources.app_name
import dev.qualia.qualia2fa.resources.dialog_import_plain_vault_hint
import dev.qualia.qualia2fa.resources.dialog_import_recovery_text
import dev.qualia.qualia2fa.resources.dialog_import_recovery_title
import dev.qualia.qualia2fa.resources.dialog_reset_text
import dev.qualia.qualia2fa.resources.dialog_reset_title
import dev.qualia.qualia2fa.resources.error_recovery_bare_vault
import dev.qualia.qualia2fa.resources.error_recovery_key_only
import dev.qualia.qualia2fa.resources.error_recovery_rejected
import dev.qualia.qualia2fa.resources.error_recovery_vault_failed
import dev.qualia.qualia2fa.resources.filter_portable_files
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
import dev.qualia.qualia2fa.resources.settings_import_export
import dev.qualia.qualia2fa.resources.settings_import_export_explainer
import dev.qualia.qualia2fa.resources.settings_reset
import dev.qualia.qualia2fa.resources.settings_reset_explainer
import dev.qualia.qualia2fa.resources.settings_software_backed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Where restoring a portable file is in its steps.
 *
 * Both steps carry the chosen file, and both compare it by content: the `equals` a data class
 * generates compares an array member by reference, which would make two steps holding the same
 * file two different values.
 */
private sealed interface RestoreStep {
    /** The file is chosen; the user is asked whether to replace this device's vault. */
    data class Confirm(val file: ByteArray) : RestoreStep {
        override fun equals(other: Any?): Boolean = other is Confirm && file.contentEquals(other.file)

        override fun hashCode(): Int = file.contentHashCode()
    }

    /** Confirmed, and the file needs a passphrase before it can be opened. */
    data class Passphrase(val file: ByteArray) : RestoreStep {
        override fun equals(other: Any?): Boolean = other is Passphrase && file.contentEquals(other.file)

        override fun hashCode(): Int = file.contentHashCode()
    }
}

/**
 * Everything that is not an entry: the language, what this device holds, importing and exporting,
 * and the way out.
 *
 * # What the two facts are for
 *
 * "Where the master key lives" and "what backs the device identity" are shown as they really
 * are — a software key store is reported as one. A user is entitled to know whether the thing
 * that protects the account is hardware or a file, and the alternative is a reassurance that
 * is not true.
 *
 * # Import and export are one entry each
 *
 * Importing is one button that opens one file: a `.q2fa` backup restores the whole account, and
 * any other file is read as an export from another authenticator and previewed before anything is
 * written. Exporting is one button with six choices, the two `.q2fa` shapes first and then the
 * four shapes another application reads.
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
    var resetting by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var exportingVault by remember { mutableStateOf(false) }
    var restoreStep by remember { mutableStateOf<RestoreStep?>(null) }
    val scope = rememberCoroutineScope()

    val importLabel = stringResource(Res.string.action_import_vault)
    val exportLabel = stringResource(Res.string.action_export_vault)
    val rejected = stringResource(Res.string.error_recovery_rejected)
    val keyOnly = stringResource(Res.string.error_recovery_key_only)
    val bareVault = stringResource(Res.string.error_recovery_bare_vault)
    val vaultFailed = stringResource(Res.string.error_recovery_vault_failed)

    /** Restores [file] into this device, replacing the vault it carries. */
    fun restore(file: ByteArray, passphrase: String) {
        val target = masterKeyStore
        if (target == null) {
            message = rejected
            return
        }
        val current = vault.serialize()
        scope.launch {
            // The passphrase derivation is deliberately expensive; keep the screen responsive
            // while it runs.
            val outcome =
                withContext(Dispatchers.Default) {
                    restorePortableFile(file, passphrase, target, current)
                }
            when (outcome) {
                is PortableRestore.Recovered -> {
                    message = null
                    onImported(outcome.vault)
                }

                PortableRestore.KeyOnly -> message = keyOnly
                PortableRestore.BareVault -> message = bareVault
                PortableRestore.VaultFailed -> message = vaultFailed
                PortableRestore.Rejected -> message = rejected
            }
        }
    }

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

        SectionCard(title = stringResource(Res.string.settings_import_export)) {
            Hint(stringResource(Res.string.settings_import_export_explainer))
            OutlinedButton(onClick = { importing = true }, modifier = Modifier.fillMaxWidth()) {
                Text(importLabel)
            }
            OutlinedButton(onClick = { exportingVault = true }, modifier = Modifier.fillMaxWidth()) {
                Text(exportLabel)
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
                onPortableFile = { file ->
                    importing = false
                    message = null
                    // Every `.q2fa` is confirmed first: it replaces the whole account, and that
                    // is not something to do because a file picker was answered. The plain form
                    // then needs no passphrase (the file itself is the credential), the other
                    // two ask for one.
                    restoreStep = RestoreStep.Confirm(file)
                },
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

    when (val step = restoreStep) {
        null -> Unit

        is RestoreStep.Confirm -> {
            val plain = Qualia.detectPortableKind(step.file) == PortableKind.PlainVault
            AlertDialog(
                onDismissRequest = { restoreStep = null },
                properties = DialogDefaults,
                title = { Text(stringResource(Res.string.dialog_import_recovery_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        Text(stringResource(Res.string.dialog_import_recovery_text))
                        // Said before the button, because after it the file has been read and
                        // the account replaced: the plain form is the one that needs the warning.
                        if (plain) {
                            Text(
                                stringResource(Res.string.dialog_import_plain_vault_hint),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (plain) {
                                restoreStep = null
                                restore(step.file, "")
                            } else {
                                restoreStep = RestoreStep.Passphrase(step.file)
                            }
                        },
                    ) {
                        Text(stringResource(Res.string.action_continue))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { restoreStep = null }) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                },
            )
        }

        is RestoreStep.Passphrase ->
            PassphraseDialog(
                title = importLabel,
                confirmLabel = importLabel,
                askTwice = false,
                onDismiss = { restoreStep = null },
                onConfirm = { passphrase ->
                    restoreStep = null
                    restore(step.file, passphrase)
                },
            )
    }
}


