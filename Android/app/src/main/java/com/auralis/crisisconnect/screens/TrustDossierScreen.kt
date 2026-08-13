package com.auralis.crisisconnect.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.dossier.TrustDossier
import com.auralis.crisisconnect.dossier.TrustDossierApi
import com.auralis.crisisconnect.dossier.TrustDossierPolicyPack
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class DossierUiState(
    val dossiers: List<TrustDossier> = emptyList(),
    val packs: List<TrustDossierPolicyPack> = emptyList(),
    val selectedId: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrustDossierScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { TrustDossierApi() }
    var state by remember { mutableStateOf(DossierUiState()) }
    var showCreate by remember { mutableStateOf(false) }
    var showInk by remember { mutableStateOf(false) }

    fun replace(updated: TrustDossier) {
        state = state.copy(
            dossiers = listOf(updated) + state.dossiers.filterNot { it.dossierId == updated.dossierId },
            selectedId = updated.dossierId,
            busy = false,
            error = null,
        )
    }
    fun runOperation(operation: suspend () -> TrustDossier) {
        if (state.busy) return
        state = state.copy(busy = true, error = null)
        scope.launch {
            runCatching { operation() }
                .onSuccess(::replace)
                .onFailure { state = state.copy(busy = false, error = it.message ?: context.getString(R.string.dossier_error_generic)) }
        }
    }
    fun refresh() {
        if (state.busy) return
        state = state.copy(busy = true, error = null)
        scope.launch {
            runCatching { api.list() }
                .onSuccess { (dossiers, packs) ->
                    val selected = state.selectedId?.takeIf { id -> dossiers.any { it.dossierId == id } }
                        ?: dossiers.firstOrNull()?.dossierId
                    state = state.copy(dossiers = dossiers, packs = packs, selectedId = selected, busy = false)
                }
                .onFailure { state = state.copy(busy = false, error = it.message ?: context.getString(R.string.dossier_error_generic)) }
        }
    }

    val selected = state.dossiers.firstOrNull { it.dossierId == state.selectedId }
        ?: state.dossiers.firstOrNull()
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null || selected == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { readUpload(context, uri) }
                .onSuccess { upload -> runOperation { api.upload(selected, upload.bytes, upload.fileName, upload.mediaType) } }
                .onFailure { state = state.copy(error = it.message ?: context.getString(R.string.dossier_error_generic)) }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dossier_center_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = ::refresh, enabled = !state.busy) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.dossier_refresh))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, stringResource(R.string.dossier_new))
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            if (maxWidth >= 840.dp) {
                Row(Modifier.fillMaxSize()) {
                    DossierList(
                        dossiers = state.dossiers,
                        selectedId = selected?.dossierId,
                        onSelect = { state = state.copy(selectedId = it) },
                        modifier = Modifier.width(340.dp).fillMaxHeight(),
                    )
                    HorizontalDivider(Modifier.fillMaxHeight().width(1.dp))
                    DossierDetail(
                        dossier = selected,
                        packs = state.packs,
                        busy = state.busy,
                        onUpload = { filePicker.launch("*/*") },
                        onInk = { showInk = true },
                        onPolicy = { pack -> selected?.let { runOperation { api.applyPolicy(it, pack) } } },
                        onFreeze = { selected?.let { runOperation { api.freeze(it) } } },
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    DossierList(
                        dossiers = state.dossiers,
                        selectedId = selected?.dossierId,
                        onSelect = { state = state.copy(selectedId = it) },
                        modifier = Modifier.fillMaxWidth().weight(if (selected == null) 1f else .36f),
                    )
                    if (selected != null) {
                        HorizontalDivider()
                        DossierDetail(
                            dossier = selected,
                            packs = state.packs,
                            busy = state.busy,
                            onUpload = { filePicker.launch("*/*") },
                            onInk = { showInk = true },
                            onPolicy = { pack -> runOperation { api.applyPolicy(selected, pack) } },
                            onFreeze = { runOperation { api.freeze(selected) } },
                            modifier = Modifier.fillMaxWidth().weight(.64f),
                        )
                    }
                }
            }
            if (state.busy) {
                Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 8.dp,
                    modifier = Modifier.align(Alignment.Center)) {
                    CircularProgressIndicator(Modifier.padding(24.dp).size(42.dp))
                }
            }
        }
    }

    if (showCreate) {
        CreateDossierDialog(
            onDismiss = { showCreate = false },
            onCreate = { title, description, purpose, classification, jurisdiction, filePlan ->
                showCreate = false
                runOperation { api.create(title, description, purpose, classification, jurisdiction, filePlan) }
            },
        )
    }
    if (showInk && selected != null) {
        InkAnnotationDialog(
            onDismiss = { showInk = false },
            onAttach = { bytes ->
                showInk = false
                runOperation { api.upload(selected, bytes, "handwritten-annotation.png", "image/png") }
            },
        )
    }
    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = { state = state.copy(error = null) },
            title = { Text(stringResource(R.string.dossier_error_title)) },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = { state = state.copy(error = null) }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}

@Composable
private fun DossierList(
    dossiers: List<TrustDossier>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (dossiers.isEmpty()) {
        Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.FolderSpecial, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.dossier_empty_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.dossier_empty_body), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    LazyColumn(modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(dossiers, key = { it.dossierId }) { dossier ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (dossier.dossierId == selectedId) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth().clickable { onSelect(dossier.dossierId) },
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(dossier.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${label(dossier.purpose)} · ${label(dossier.state)}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DossierDetail(
    dossier: TrustDossier?,
    packs: List<TrustDossierPolicyPack>,
    busy: Boolean,
    onUpload: () -> Unit,
    onInk: () -> Unit,
    onPolicy: (TrustDossierPolicyPack) -> Unit,
    onFreeze: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (dossier == null) return
    val editable = dossier.state == "draft" || dossier.state == "ready_to_freeze"
    val now = Instant.now()
    val applicable = packs.filter {
        it.status == "approved" && (it.jurisdiction == dossier.policy.jurisdiction || it.jurisdiction == "GLOBAL") &&
            (it.purpose == dossier.purpose || it.purpose == "any") && it.effectiveFrom <= now &&
            (it.effectiveUntil == null || now < it.effectiveUntil)
    }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = 820.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(dossier.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(dossier.description.ifBlank { stringResource(R.string.dossier_no_description) },
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${label(dossier.classification)} · ${label(dossier.state)}",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)

        SectionCard(Icons.Filled.Description, stringResource(R.string.dossier_step_documents)) {
            Text(stringResource(R.string.dossier_documents_help), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onUpload, enabled = editable && !busy) {
                    Icon(Icons.Filled.AttachFile, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.dossier_choose_file))
                }
                OutlinedButton(onClick = onInk, enabled = editable && !busy) {
                    Icon(Icons.Filled.Create, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.dossier_write_pen))
                }
            }
            if (dossier.components.isEmpty()) Text(stringResource(R.string.dossier_no_documents), style = MaterialTheme.typography.bodySmall)
            dossier.components.forEach { component ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.tertiary)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(component.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${component.bytes / 1024} KB · ${component.sha256.take(16)}…", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        SectionCard(Icons.Filled.Policy, stringResource(R.string.dossier_step_policy)) {
            if (dossier.policy.status == "accepted") {
                Text(dossier.policy.policyId ?: stringResource(R.string.dossier_policy_recorded), fontWeight = FontWeight.SemiBold)
                Text("${label(dossier.policy.signatureRequirement)} · ${label(dossier.policy.deliveryReceipt)} · ${label(dossier.retentionClass)}",
                    style = MaterialTheme.typography.bodySmall)
            } else if (applicable.isEmpty()) {
                Text(stringResource(R.string.dossier_no_policy), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            } else {
                applicable.forEach { pack ->
                    OutlinedButton(onClick = { onPolicy(pack) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(pack.name, fontWeight = FontWeight.SemiBold)
                            Text("${pack.content.retentionDays} ${stringResource(R.string.dossier_days)} · ${label(pack.content.signatureRequirement)}",
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        SectionCard(Icons.Filled.Lock, stringResource(R.string.dossier_step_freeze)) {
            Text(stringResource(R.string.dossier_freeze_help), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (editable) {
                Button(
                    onClick = onFreeze,
                    enabled = !busy && dossier.policy.status == "accepted" && dossier.components.isNotEmpty(),
                ) { Icon(Icons.Filled.Lock, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.dossier_freeze)) }
            } else if (dossier.manifestSha256 != null) {
                Text("${stringResource(R.string.dossier_frozen_manifest)} ${dossier.manifestSha256.take(16)}…",
                    color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold)
            }
        }

        Text(stringResource(R.string.dossier_ink_legal_notice), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(72.dp))
    }
}

@Composable
private fun SectionCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Text(title, Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable
private fun CreateDossierDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String, String, String, String?) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var purpose by remember { mutableStateOf("official_correspondence") }
    var classification by remember { mutableStateOf("internal") }
    var jurisdiction by remember { mutableStateOf("TR") }
    var filePlan by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dossier_create_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.dossier_name)) }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text(stringResource(R.string.dossier_description)) })
                OutlinedTextField(purpose, { purpose = it.trim().lowercase() }, label = { Text(stringResource(R.string.dossier_purpose)) },
                    supportingText = { Text(stringResource(R.string.dossier_purpose_help)) })
                OutlinedTextField(classification, { classification = it.trim().lowercase() }, label = { Text(stringResource(R.string.dossier_classification)) })
                OutlinedTextField(jurisdiction, { jurisdiction = it.uppercase() }, label = { Text(stringResource(R.string.dossier_jurisdiction)) })
                OutlinedTextField(filePlan, { filePlan = it }, label = { Text(stringResource(R.string.dossier_file_plan)) })
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(title, description, purpose, classification, jurisdiction, filePlan.trim().ifEmpty { null }) },
                enabled = title.isNotBlank() && jurisdiction.isNotBlank()) { Text(stringResource(R.string.dossier_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun InkAnnotationDialog(onDismiss: () -> Unit, onAttach: (ByteArray) -> Unit) {
    var strokes by remember { mutableStateOf(emptyList<List<Offset>>()) }
    var canvasSize by remember { mutableStateOf(IntSize(1, 1)) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.dossier_ink_title), fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.dossier_ink_not_signature), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                    Button(onClick = { onAttach(renderInk(strokes, canvasSize)) }, enabled = strokes.any { it.size > 1 }) {
                        Text(stringResource(R.string.dossier_ink_attach))
                    }
                }
                Canvas(
                    Modifier.fillMaxSize().padding(12.dp).background(Color.White, RoundedCornerShape(12.dp))
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { start -> strokes = strokes + listOf(listOf(start)) },
                                onDrag = { change, _ ->
                                    change.consume()
                                    if (strokes.isNotEmpty()) strokes = strokes.dropLast(1) + listOf(strokes.last() + change.position)
                                },
                            )
                        },
                ) {
                    strokes.forEach { points ->
                        if (points.size > 1) {
                            val path = Path().apply {
                                moveTo(points.first().x, points.first().y)
                                points.drop(1).forEach { lineTo(it.x, it.y) }
                            }
                            drawPath(path, Color.Black, style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                }
            }
        }
    }
}

private data class UploadDocument(val bytes: ByteArray, val fileName: String, val mediaType: String)

private suspend fun readUpload(context: Context, uri: Uri): UploadDocument = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val mediaType = resolver.getType(uri)?.lowercase()
        ?.takeIf { it in TrustDossierApi.ALLOWED_MEDIA_TYPES }
        ?: throw IllegalArgumentException(context.getString(R.string.dossier_unsupported_file))
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }?.takeIf(String::isNotBlank) ?: "document"
    val output = ByteArrayOutputStream()
    resolver.openInputStream(uri)?.buffered()?.use { input ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            if (output.size() > TrustDossierApi.MAX_UPLOAD_BYTES) {
                throw IllegalArgumentException(context.getString(R.string.dossier_file_too_large))
            }
        }
    } ?: throw IOException(context.getString(R.string.dossier_file_unreadable))
    UploadDocument(output.toByteArray(), name, mediaType)
}

private fun renderInk(strokes: List<List<Offset>>, size: IntSize): ByteArray {
    val bitmap = Bitmap.createBitmap(size.width.coerceAtLeast(1), size.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    strokes.forEach { points ->
        if (points.size > 1) {
            val path = android.graphics.Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            canvas.drawPath(path, paint)
        }
    }
    return ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
}

private fun label(value: String): String = value.replace('_', ' ').lowercase()
