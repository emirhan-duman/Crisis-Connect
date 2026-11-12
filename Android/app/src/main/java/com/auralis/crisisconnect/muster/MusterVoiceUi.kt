package com.auralis.crisisconnect.muster

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.auralis.crisisconnect.R
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun MusterVoiceComposer(enabled: Boolean, draft: MusterVoiceDraft?, onDraft: (MusterVoiceDraft?) -> Unit, onRecording: (Boolean) -> Unit = {}) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val uid = FirebaseAuth.getInstance().currentUser?.uid
    val recorder = remember(uid) { MusterVoiceRecorder(context) }
    var recording by remember(uid) { mutableStateOf(false) }
    var seconds by remember(uid) { mutableIntStateOf(0) }
    var error by remember(uid) { mutableStateOf(false) }
    var permissionError by remember(uid) { mutableStateOf(false) }
    fun finish() {
        if (!recording) return
        recording = false; onRecording(false)
        runCatching { recorder.finish() }.onSuccess { onDraft(it) }.onFailure { error = true }
    }
    fun begin() {
        if (!enabled || FirebaseAuth.getInstance().currentUser?.uid != uid || lifecycle.currentState < Lifecycle.State.STARTED) return
        error = false; permissionError = false; seconds = 0
        runCatching { recorder.start { finish() } }.onSuccess { recording = true; onRecording(true) }.onFailure { error = true }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else permissionError = true
    }
    DisposableEffect(uid, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { recorder.close(); recording = false; onRecording(false) }
        }
        val account = FirebaseAuth.AuthStateListener { if (it.currentUser?.uid != uid) { recorder.close(); recording = false; onRecording(false); onDraft(null) } }
        FirebaseAuth.getInstance().addAuthStateListener(account)
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); FirebaseAuth.getInstance().removeAuthStateListener(account); recorder.close(); onRecording(false) }
    }
    LaunchedEffect(recording) { while (recording) { delay(1000); seconds = (seconds + 1).coerceAtMost(30) } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (draft == null) OutlinedButton(onClick = {
            if (recording) finish()
            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
            else permission.launch(Manifest.permission.RECORD_AUDIO)
        }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Icon(if (recording) Icons.Outlined.StopCircle else Icons.Outlined.MicNone, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (recording) R.string.muster_voice_stop else R.string.muster_voice_record))
            if (recording) Text(" · " + stringResource(R.string.muster_voice_duration, seconds))
        } else MusterVoicePreview(draft, enabled) { onDraft(null) }
        Text(stringResource(R.string.muster_voice_limit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (permissionError || error) Text(stringResource(if (permissionError) R.string.muster_voice_permission else R.string.muster_voice_error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun MusterVoicePreview(draft: MusterVoiceDraft, enabled: Boolean, autoPlay: Boolean = false, onDiscard: (() -> Unit)? = null) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember(draft) { MusterVoicePlayer(context) }
    var playing by remember(draft) { mutableStateOf(false) }
    var error by remember(draft) { mutableStateOf(false) }
    fun play() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        error = false
        runCatching { player.play(draft) { playing = false }; playing = true }.onFailure { error = true }
    }
    LaunchedEffect(player, autoPlay) { if (autoPlay) play() }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { player.close(); playing = false } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player.close() }
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                error = false
                if (playing) { player.close(); playing = false }
                else play()
            }, enabled = enabled, modifier = Modifier.weight(1f)) {
                Icon(if (playing) Icons.Outlined.StopCircle else Icons.Outlined.PlayArrow, null, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(if (playing) R.string.muster_voice_stop else R.string.muster_voice_play))
                Text(" · " + stringResource(R.string.muster_voice_duration, ((draft.durationMillis + 999) / 1000).toInt()))
            }
            if (onDiscard != null) IconButton(onClick = { player.close(); onDiscard() }, enabled = enabled) {
                Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.muster_voice_discard))
            }
        }
        if (error) Text(stringResource(R.string.muster_voice_error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/** Fetch only after an explicit tap, always authenticated; no attachment/public URL is cached. */
@Composable
internal fun MusterReceivedVoice(row: MusterCardModel) {
    val context = LocalContext.current.applicationContext
    val uid = FirebaseAuth.getInstance().currentUser?.uid
    val scope = rememberCoroutineScope()
    var draft by remember(uid, row.key, row.localVoice, row.remoteVoice) { mutableStateOf<MusterVoiceDraft?>(null) }
    var loading by remember(uid, row.key) { mutableStateOf(false) }
    var error by remember(uid, row.key) { mutableStateOf(false) }
    if (draft != null) MusterVoicePreview(draft!!, true, autoPlay = true)
    else Column {
        OutlinedButton(onClick = {
            if (uid != null && !loading) scope.launch {
                loading = true; error = false
                try { draft = MusterClient.loadVoice(context, uid, row) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { error = true }
                finally { loading = false }
            }
        }, enabled = !loading) {
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Outlined.PlayArrow, null)
            Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.muster_voice_play))
        }
        if (error) Text(stringResource(R.string.muster_voice_error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}
