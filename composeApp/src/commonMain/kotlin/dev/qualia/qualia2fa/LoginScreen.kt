package dev.qualia.qualia2fa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.action_cancel
import dev.qualia.qualia2fa.resources.action_continue
import dev.qualia.qualia2fa.resources.action_create_vault
import dev.qualia.qualia2fa.resources.action_import_recovery
import dev.qualia.qualia2fa.resources.action_join_existing_account
import dev.qualia.qualia2fa.resources.action_recover_from_file
import dev.qualia.qualia2fa.resources.action_reset
import dev.qualia.qualia2fa.resources.action_reset_master_key
import dev.qualia.qualia2fa.resources.action_restore
import dev.qualia.qualia2fa.resources.action_unlock
import dev.qualia.qualia2fa.resources.dialog_import_recovery_text
import dev.qualia.qualia2fa.resources.dialog_import_recovery_title
import dev.qualia.qualia2fa.resources.dialog_open_recovery
import dev.qualia.qualia2fa.resources.dialog_reset_title
import dev.qualia.qualia2fa.resources.filter_recovery_files
import dev.qualia.qualia2fa.resources.first_run_intro
import dev.qualia.qualia2fa.resources.first_run_join_explainer
import dev.qualia.qualia2fa.resources.first_run_no_hardware_warning
import dev.qualia.qualia2fa.resources.login_key_unreadable
import dev.qualia.qualia2fa.resources.login_locked_explainer
import dev.qualia.qualia2fa.resources.login_reset_hint
import dev.qualia.qualia2fa.resources.login_reset_text
import dev.qualia.qualia2fa.resources.login_subtitle
import dev.qualia.qualia2fa.resources.login_vault_missing
import dev.qualia.qualia2fa.resources.status_locked
import org.jetbrains.compose.resources.stringResource

/**
 * Which face the login screen shows.
 *
 * The design describes the first-run case. The other three exist because a device can already
 * hold a master key, and then **creating a vault is not a safe action to offer**: it would
 * replace that key and strand every entry the account holds. Which actions are safe is the
 * whole reason these are separate states rather than one screen with conditional buttons.
 */
enum class LoginMode {
    /** Nothing on this device yet: create a vault, or log in to an existing account. */
    FirstRun,

    /** A vault exists and is locked: the only action is unlocking it. */
    Locked,

    /** A master key exists, but the vault cannot be opened with it right now. */
    Unreadable,

    /** A master key exists and the vault file is gone. */
    VaultMissing,
}

/**
 * Whether this state offers the reset.
 *
 * The two states where the device holds something it can no longer use: a master key that will
 * not open the vault, or a vault whose key is gone. Resetting is the way out of both, because
 * neither can be repaired from here — and it is offered **nowhere else**: on a first run there
 * is nothing to remove, and a locked vault is opened rather than destroyed. Creating a vault
 * over an existing key is the one action that must not be offered on those two states, which is
 * why they are separate states at all.
 */
internal fun LoginMode.offersReset(): Boolean = this == LoginMode.Unreadable || this == LoginMode.VaultMissing

/**
 * The first screen with anything to act on: the mark, the name, and what this device can do.
 *
 * The arrangement follows the design: in a wide landscape window the actions sit beside the
 * mark and the name, otherwise they stack under them, and a window too short drops the name
 * and then the mark. [loginLayout] holds those rules.
 *
 * # The language selector is an extension
 *
 * The design does not put one here, but this is the last screen before the vault is open: a
 * user who cannot read the interface has no way to reach the settings that would change it,
 * and reading the interface is exactly what they cannot do yet.
 *
 * # The reset is an extension too
 *
 * It is the only destructive action reachable without an open vault, so it asks before it acts
 * and the dialog says what it deletes.
 */
@Composable
fun LoginScreen(
    mode: LoginMode,
    reason: Status?,
    hardwareBacked: Boolean,
    message: String?,
    language: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit,
    onCreateVault: () -> Unit,
    onJoin: () -> Unit,
    onUnlock: () -> Unit,
    onReset: () -> Unit,
    onRecover: (ByteArray, String) -> Unit,
) {
    var confirmingReset by remember { mutableStateOf(false) }
    var chosenRecovery by remember { mutableStateOf<ByteArray?>(null) }
    var recoveryFile by remember { mutableStateOf<ByteArray?>(null) }
    val picker = rememberFilePicker()
    val openRecoveryTitle = stringResource(Res.string.dialog_open_recovery)
    val recoveryFilter = stringResource(Res.string.filter_recovery_files)

    /** Opens a recovery file, and asks for its passphrase once one is chosen. */
    fun chooseRecoveryFile() {
        picker.pickBytes(openRecoveryTitle, recoveryFilter, RECOVERY_FILE_EXTENSION) { file ->
            if (file != null) chosenRecovery = file
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val layout = loginLayout(maxWidth, maxHeight)

        if (layout.actionsBesideBrand) {
            Row(
                modifier = Modifier.fillMaxSize().padding(Space.xxl),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.xxl),
            ) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Brand(showTitle = layout.showTitle)
                }
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    // The actions gained a recovery button, and a short landscape window has
                    // to keep every action reachable rather than clip the last one.
                    Column(
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    ) {
                        Actions(
                            mode,
                            reason,
                            hardwareBacked,
                            message,
                            language,
                            onSelectLanguage,
                            onCreateVault,
                            onJoin,
                            onUnlock,
                            onReset = { confirmingReset = true },
                            onRecoverFromFile = ::chooseRecoveryFile,
                        )
                    }
                }
            }
        } else {
            // Centered inside a box that scrolls: centered when the window has room, and
            // reachable in full when it does not, which a centered column on its own is not
            // — a scrollable column is only as tall as its content.
            Box(
                modifier = Modifier.fillMaxSize().padding(Space.xl),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Space.xl),
                ) {
                    Brand(showTitle = layout.showTitle)
                    Actions(
                        mode,
                        reason,
                        hardwareBacked,
                        message,
                        language,
                        onSelectLanguage,
                        onCreateVault,
                        onJoin,
                        onUnlock,
                        onReset = { confirmingReset = true },
                        onRecoverFromFile = ::chooseRecoveryFile,
                    )
                }
            }
        }
    }

    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            properties = DialogDefaults,
            title = { Text(stringResource(Res.string.dialog_reset_title)) },
            text = { Text(stringResource(Res.string.login_reset_text)) },
            confirmButton = {
                Button(onClick = {
                    confirmingReset = false
                    onReset()
                }) {
                    Text(stringResource(Res.string.action_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingReset = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }

    chosenRecovery?.let { file ->
        AlertDialog(
            onDismissRequest = { chosenRecovery = null },
            properties = DialogDefaults,
            title = { Text(stringResource(Res.string.dialog_import_recovery_title)) },
            text = { Text(stringResource(Res.string.dialog_import_recovery_text)) },
            confirmButton = {
                Button(
                    onClick = {
                        chosenRecovery = null
                        recoveryFile = file
                    },
                ) { Text(stringResource(Res.string.action_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { chosenRecovery = null }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }

    recoveryFile?.let { file ->
        PassphraseDialog(
            title = stringResource(Res.string.action_import_recovery),
            confirmLabel = stringResource(Res.string.action_restore),
            askTwice = false,
            onDismiss = { recoveryFile = null },
            onConfirm = { passphrase ->
                recoveryFile = null
                onRecover(file, passphrase)
            },
        )
    }
}

/** The subtitle, the buttons, the explanation and the way to change the language. */
@Composable
private fun Actions(
    mode: LoginMode,
    reason: Status?,
    hardwareBacked: Boolean,
    message: String?,
    language: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit,
    onCreateVault: () -> Unit,
    onJoin: () -> Unit,
    onUnlock: () -> Unit,
    onReset: () -> Unit,
    onRecoverFromFile: () -> Unit,
) {
    val warningColor = MaterialTheme.colorScheme.error

    Column(
        modifier = Modifier.widthIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        when (mode) {
            LoginMode.FirstRun -> {
                Text(stringResource(Res.string.login_subtitle), style = MaterialTheme.typography.titleMedium)
                Button(onClick = onCreateVault, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.action_create_vault))
                }
                OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.action_join_existing_account))
                }
                OutlinedButton(onClick = onRecoverFromFile, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.action_recover_from_file))
                }
                Hint(stringResource(Res.string.first_run_intro))
                if (!hardwareBacked) {
                    Text(
                        stringResource(Res.string.first_run_no_hardware_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = warningColor,
                    )
                }
                Hint(stringResource(Res.string.first_run_join_explainer))
            }

            LoginMode.Locked -> {
                Text(
                    reason?.text() ?: stringResource(Res.string.status_locked),
                    style = MaterialTheme.typography.titleMedium,
                )
                Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.action_unlock))
                }
                Hint(stringResource(Res.string.login_locked_explainer))
            }

            LoginMode.Unreadable -> {
                Text(stringResource(Res.string.status_locked), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(Res.string.login_key_unreadable),
                    style = MaterialTheme.typography.bodySmall,
                    color = warningColor,
                )
                Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.action_unlock))
                }
                OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.action_join_existing_account))
                }
                OutlinedButton(onClick = onRecoverFromFile, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.action_recover_from_file))
                }
            }

            LoginMode.VaultMissing -> {
                Text(stringResource(Res.string.status_locked), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(Res.string.login_vault_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = warningColor,
                )
                // Logging in from another device of the same account brings back both the
                // master key and, through a sync, the entries. Creating a vault would not:
                // it would draw a new key.
                Button(onClick = onJoin, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.action_join_existing_account))
                }
                OutlinedButton(onClick = onRecoverFromFile, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.action_recover_from_file))
                }
            }
        }

        if (mode.offersReset()) {
            OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.action_reset_master_key))
            }
            Hint(stringResource(Res.string.login_reset_hint))
        }

        MessageLine(message)
        LanguageSelector(language = language, onSelect = onSelectLanguage)
    }
}
