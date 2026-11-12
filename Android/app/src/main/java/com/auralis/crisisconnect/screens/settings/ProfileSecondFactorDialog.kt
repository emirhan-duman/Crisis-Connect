package com.auralis.crisisconnect.screens.settings

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.auralis.crisisconnect.R

@Composable
internal fun ProfileSecondFactorDialog(
    state: ProfileSecondFactorState,
    signIn: ProfileSecondFactorSignIn,
    activity: Activity?,
) {
    // OTP stays in memory and is reset when switching factors.
    var code by remember(state.selectedId, state.codeSent) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!state.busy) signIn.cancel() },
        title = { Text(stringResource(R.string.profile_mfa_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(when {
                    state.unsupported -> R.string.profile_mfa_unsupported
                    state.isPhone -> R.string.profile_mfa_sms_message
                    else -> R.string.profile_mfa_totp_message
                }))
                if (state.factors.size > 1) {
                    state.factors.forEachIndexed { index, factor ->
                        TextButton(onClick = { code = ""; signIn.select(factor.id) }, enabled = !state.busy) {
                            val fallback = stringResource(if (factor.isPhone) R.string.profile_mfa_sms else R.string.profile_mfa_authenticator)
                            Text("${if (factor.id == state.selectedId) "✓ " else ""}${factor.name ?: "$fallback ${index + 1}"}")
                        }
                    }
                }
                if (!state.unsupported && (!state.isPhone || state.codeSent)) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter { c -> c in '0'..'9' }.take(6) },
                        label = { Text(stringResource(R.string.profile_mfa_code)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true, enabled = !state.busy,
                    )
                }
                if (state.isPhone) {
                    TextButton(onClick = { code = ""; activity?.let(signIn::sendSms) }, enabled = !state.busy && activity != null) {
                        Text(stringResource(if (state.codeSent) R.string.profile_mfa_resend else R.string.profile_mfa_send))
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.busy) CircularProgressIndicator()
            }
        },
        confirmButton = {
            TextButton(
                onClick = { signIn.verify(code) },
                enabled = !state.busy && !state.unsupported && code.length == 6 && (!state.isPhone || state.codeSent),
            ) { Text(stringResource(R.string.profile_mfa_verify)) }
        },
        dismissButton = {
            TextButton(onClick = signIn::cancel, enabled = !state.busy) {
                Text(stringResource(R.string.profile_mfa_cancel))
            }
        },
    )
}
