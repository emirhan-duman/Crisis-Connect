package com.auralis.crisisconnect.screens.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.local.MedicalInfo
import com.auralis.crisisconnect.data.local.MedicalInfoStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Optional emergency medical details (blood type, allergies, medication, note).
 * Stored locally; shared ONLY over the encrypted rescue link during an active SOS —
 * never uploaded to the cloud.
 */
@Composable
fun MedicalInfoCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var bloodType by rememberSaveable { mutableStateOf("") }
    var allergies by rememberSaveable { mutableStateOf("") }
    var medication by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (!loaded) {
            val saved = MedicalInfoStore.observe(context).first()
            bloodType = saved.bloodType
            allergies = saved.allergies
            medication = saved.medication
            notes = saved.notes
            loaded = true
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.MedicalServices,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.medical_info_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.medical_info_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = bloodType,
                        onValueChange = { bloodType = it.take(MedicalInfoStore.MAX_BLOOD_LENGTH) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.medical_info_blood_label)) },
                        placeholder = { Text("A Rh+") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = allergies,
                        onValueChange = { allergies = it.take(MedicalInfoStore.MAX_FIELD_LENGTH) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.medical_info_allergies_label)) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = medication,
                        onValueChange = { medication = it.take(MedicalInfoStore.MAX_FIELD_LENGTH) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.medical_info_medication_label)) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it.take(MedicalInfoStore.MAX_FIELD_LENGTH) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.medical_info_notes_label)) },
                        minLines = 2
                    )
                    val savedText = stringResource(R.string.medical_info_saved)
                    Button(
                        onClick = {
                            scope.launch {
                                MedicalInfoStore.save(
                                    context,
                                    MedicalInfo(
                                        bloodType = bloodType,
                                        allergies = allergies,
                                        medication = medication,
                                        notes = notes
                                    )
                                )
                                Toast.makeText(context, savedText, Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Text(stringResource(R.string.medical_info_save))
                    }
                }
            }
        }
    }
}
