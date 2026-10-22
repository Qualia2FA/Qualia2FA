package dev.qualia.qualia2fa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import dev.qualia.qualia2fa.resources.action_set_password
import dev.qualia.qualia2fa.resources.dialog_set_password_title
import dev.qualia.qualia2fa.resources.error_passphrase_mismatch
import dev.qualia.qualia2fa.resources.field_unlock_password
import dev.qualia.qualia2fa.resources.field_unlock_password_again
import dev.qualia.qualia2fa.resources.hint_unlock_password_minimum
import dev.qualia.qualia2fa.resources.set_password_explainer
import org.jetbrains.compose.resources.stringResource

/**
 * What a device without an unlock password must answer before the vault is usable.
 *
 * It is reached in two situations: a fresh vault, where nothing has been written yet, and a
 * migrated 0.3.0 device, whose master key is still in the legacy platform store. In both, the
 * caller holds the master key in memory and writes the lock only after this screen confirms.
 *
 * Cancelling does not silently leave an unlocked device: the caller drops the in-memory vault
 * and returns to the login screen, so the setup has to be completed before the vault is used.
 */
@Composable
fun SetPasswordScreen(
    message: String?,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
    /**
     * What this password is being set for, when the default sentence is not true.
     *
     * A vault just imported from a backup is not "a device that has no password yet": the
     * password it replaces belongs to the vault that was just replaced, and the user has to
     * be told that the old one no longer applies.
     */
    explainer: String? = null,
) {
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }
    val mismatchText = stringResource(Res.string.error_passphrase_mismatch)

    Box(
        modifier = Modifier.fillMaxSize().padding(Space.xl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text(
                stringResource(Res.string.dialog_set_password_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                explainer ?: stringResource(Res.string.set_password_explainer),
                style = MaterialTheme.typography.bodyMedium,
            )
            SecretField(
                value = password,
                onValueChange = {
                    password = it
                    mismatch = false
                },
                label = stringResource(Res.string.field_unlock_password),
                modifier = Modifier.fillMaxWidth(),
            )
            SecretField(
                value = repeated,
                onValueChange = {
                    repeated = it
                    mismatch = false
                },
                label = stringResource(Res.string.field_unlock_password_again),
                modifier = Modifier.fillMaxWidth(),
            )
            if (mismatch) MessageLine(mismatchText)
            message?.let { MessageLine(it) }
            Hint(stringResource(Res.string.hint_unlock_password_minimum))
            Button(
                onClick = {
                    if (password != repeated) {
                        mismatch = true
                    } else {
                        onConfirm(password)
                    }
                },
                enabled = password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.action_set_password))
            }
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.action_cancel))
            }
        }
    }
}
