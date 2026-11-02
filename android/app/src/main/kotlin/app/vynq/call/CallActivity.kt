package app.vynq.call

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vynq.BuildConfig
import app.vynq.container
import app.vynq.designsystem.component.pressable
import app.vynq.designsystem.icon.VynqIcons
import app.vynq.designsystem.theme.ThemeMode
import app.vynq.designsystem.theme.VynqSpacing
import app.vynq.designsystem.theme.VynqTheme
import app.vynq.ui.messaging.Avatar
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.delay
import livekit.org.webrtc.RendererCommon

/** Full-screen call UI; shows over the lock screen for incoming calls. */
class CallActivity : ComponentActivity() {
    private val calls get() = container.calls
    private var answerWhenPermitted = false

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val s = calls.state.value ?: return@registerForActivityResult
        val needed = needed(s.video)
        if (needed.all { granted[it] == true || has(it) }) {
            if (answerWhenPermitted) calls.accept()
        } else if (answerWhenPermitted) {
            calls.decline()
        }
        answerWhenPermitted = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handle(intent)
        setContent {
            VynqTheme(ThemeMode.Dark) {
                val state by calls.state.collectAsStateWithLifecycle()
                LaunchedEffect(state == null) { if (state == null) finish() }
                state?.let { CallScreen(it, calls) { answer() } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val s = calls.state.value ?: return finish()
        if (intent?.action == ACTION_ANSWER) answer()
        else if (s.outgoing) ensurePermissions(s.video)
    }

    private fun answer() {
        val s = calls.state.value ?: return
        val needed = needed(s.video)
        if (needed.all(::has)) calls.accept()
        else { answerWhenPermitted = true; permissions.launch(needed.toTypedArray()) }
    }

    private fun ensurePermissions(video: Boolean) {
        val missing = needed(video).filterNot(::has)
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }

    private fun needed(video: Boolean) = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (video) add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_SHOW = "app.vynq.call.SHOW"
        const val ACTION_ANSWER = "app.vynq.call.ANSWER"
    }
}

@Composable
private fun CallScreen(s: CallState, calls: CallManager, onAnswer: () -> Unit) {
    val room = calls.room()
    var showStats by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color(0xFF07070C))) {
        // Remote video fills the screen; otherwise the contact's initial.
        if (s.remoteVideo != null && !s.remoteCameraOff && room != null) {
            VideoView(room, s.remoteVideo, mirror = false, modifier = Modifier.fillMaxSize())
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(s.peerLabel, 112)
                if (s.video && s.phase == CallPhase.Connected && s.remoteCameraOff) {
                    Spacer(Modifier.height(VynqSpacing.md))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(VynqIcons.CameraOff, null, Modifier.size(16.dp), tint = Color.White.copy(alpha = 0.7f))
                        Spacer(Modifier.size(6.dp))
                        Text("${s.peerLabel} turned their camera off", style = VynqTheme.type.callout, color = Color.White.copy(alpha = 0.7f))
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = VynqSpacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(s.peerLabel, style = VynqTheme.type.title2, color = Color.White)
            Spacer(Modifier.height(4.dp))
            Text(statusLine(s), style = VynqTheme.type.callout, color = Color.White.copy(alpha = 0.75f))
            Spacer(Modifier.height(6.dp))
            SecurityBadge(s)
        }

        // Our own camera, small, top right.
        if (s.localVideo != null && s.cameraOn && room != null) {
            VideoView(room, s.localVideo, mirror = true,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(VynqSpacing.lg)
                    .size(104.dp, 156.dp).clip(RoundedCornerShape(16.dp)))
        }

        AnimatedVisibility(showStats && room != null, Modifier.align(Alignment.CenterStart).padding(VynqSpacing.lg)) {
            room?.let { StatsPanel(it) }
        }

        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = VynqSpacing.xxl)) {
            if (s.phase == CallPhase.Ringing && !s.outgoing) {
                Row(horizontalArrangement = Arrangement.spacedBy(72.dp)) {
                    RoundButton(VynqIcons.EndCall, "Decline", Color(0xFFFF5A6E)) { calls.decline() }
                    RoundButton(if (s.video) VynqIcons.Video else VynqIcons.Phone, "Answer", Color(0xFF34D399)) { onAnswer() }
                }
            } else if (s.phase != CallPhase.Ended) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = VynqSpacing.md),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Top,
                ) {
                    ToggleButton(if (s.muted) VynqIcons.MicOff else VynqIcons.Mic, if (s.muted) "Unmute" else "Mute", active = s.muted) { calls.toggleMute() }
                    ToggleButton(if (s.speaker) VynqIcons.Speaker else VynqIcons.Earpiece, if (s.speaker) "Speaker" else "Earpiece", active = s.speaker) { calls.toggleSpeaker() }
                    if (s.video) {
                        ToggleButton(if (s.cameraOn) VynqIcons.Video else VynqIcons.CameraOff, if (s.cameraOn) "Camera" else "Camera off", active = !s.cameraOn) { calls.toggleCamera() }
                        ToggleButton(VynqIcons.FlipCamera, "Flip", active = false) { calls.switchCamera() }
                    }
                    ToggleButton(VynqIcons.Info, "Details", active = showStats) { showStats = !showStats }
                }
                Spacer(Modifier.height(VynqSpacing.xl))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    RoundButton(VynqIcons.EndCall, "End", Color(0xFFFF5A6E)) { calls.hangUp() }
                }
            }
        }
    }
}

private fun statusLine(s: CallState): String = when (s.phase) {
    CallPhase.Ringing -> if (s.outgoing) "Ringing…" else if (s.video) "Incoming video call" else "Incoming voice call"
    CallPhase.Connecting -> "Connecting…"
    CallPhase.Reconnecting -> "Reconnecting…"
    CallPhase.Connected -> "" // the timer replaces it
    CallPhase.Ended -> s.endReason ?: "Call ended"
}

@Composable
private fun SecurityBadge(s: CallState) {
    if (s.phase == CallPhase.Connected) {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
        val secs = ((now - (s.connectedAt ?: now)) / 1000).coerceAtLeast(0)
        Text("%d:%02d".format(secs / 60, secs % 60), style = VynqTheme.type.callout, color = Color.White)
        Spacer(Modifier.height(4.dp))
    }
    if (s.phase == CallPhase.Connected || s.phase == CallPhase.Reconnecting) {
        // Honest labelling: only claim end-to-end when the frame cryptor confirms it.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (s.endToEnd) VynqIcons.Lock else VynqIcons.Shield, null, Modifier.size(12.dp),
                tint = if (s.endToEnd) Color(0xFF34D399) else Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.size(4.dp))
            Text(if (s.endToEnd) "End-to-end encrypted" else "Encrypted in transit",
                style = VynqTheme.type.caption, color = if (s.endToEnd) Color(0xFF34D399) else Color.White.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun VideoView(room: Room, track: VideoTrack, mirror: Boolean, modifier: Modifier) {
    var renderer by remember { mutableStateOf<TextureViewRenderer?>(null) }
    AndroidView(
        factory = { ctx ->
            TextureViewRenderer(ctx).also { r ->
                room.initVideoRenderer(r)
                r.setMirror(mirror)
                r.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                renderer = r
            }
        },
        modifier = modifier,
    )
    DisposableEffect(track, renderer) {
        val r = renderer
        if (r != null) track.addRenderer(r)
        onDispose { if (r != null) track.removeRenderer(r) }
    }
    DisposableEffect(Unit) { onDispose { renderer?.release() } }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(68.dp).clip(CircleShape).background(color).pressable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, label, Modifier.size(30.dp), tint = Color.White)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = VynqTheme.type.caption, color = Color.White)
    }
}

/** A round control with its name under it, so every button says what it does. */
@Composable
private fun ToggleButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(if (active) Color.White else Color.White.copy(alpha = 0.16f)).pressable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(26.dp), tint = if (active) Color.Black else Color.White) }
        Spacer(Modifier.height(6.dp))
        Text(label, style = VynqTheme.type.caption, color = Color.White)
    }
}

@Composable
private fun StatsPanel(room: Room) {
    var stats by remember { mutableStateOf(CallStats()) }
    LaunchedEffect(room) {
        val sampler = StatsSampler(room)
        var n = 0
        while (true) {
            stats = sampler.sample()
            // Debug builds: what the call really sends and receives, for tuning (numbers only).
            if (BuildConfig.DEBUG && n++ % 10 == 5) android.util.Log.i("vynq.call", "stats: " + stats.lines().joinToString(" | "))
            delay(1000)
        }
    }
    Column(
        Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(VynqSpacing.md),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        stats.lines().forEach { Text(it, style = VynqTheme.type.caption, color = Color.White) }
    }
}
