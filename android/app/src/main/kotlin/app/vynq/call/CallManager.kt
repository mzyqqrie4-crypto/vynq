package app.vynq.call

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.vynq.data.prefs.CallQuality
import app.vynq.data.prefs.PreferencesRepository
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoCodec
import io.livekit.android.room.track.VideoEncoding
import livekit.org.webrtc.RtpParameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.sync.withLock
import app.vynq.data.auth.BackendApi
import app.vynq.data.messaging.CallSignal
import app.vynq.data.messaging.MessagingService
import com.twilio.audioswitch.AudioDevice
import app.vynq.BuildConfig
import io.livekit.android.LiveKit
import io.livekit.android.util.LoggingLevel
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.e2ee.BaseKeyProvider
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.e2ee.E2EEState
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class CallPhase { Ringing, Connecting, Connected, Reconnecting, Ended }

data class CallState(
    val callId: String,
    val peer: String,
    val peerLabel: String,
    val outgoing: Boolean,
    val video: Boolean,
    val phase: CallPhase,
    val muted: Boolean = false,
    val cameraOn: Boolean = video,
    val speaker: Boolean = video,
    /** True only while every encrypted track (ours and theirs) reports an active key. */
    val endToEnd: Boolean = false,
    val connectedAt: Long? = null,
    val endReason: String? = null,
    val localVideo: VideoTrack? = null,
    val remoteVideo: VideoTrack? = null,
    /** The other person turned their camera off. */
    val remoteCameraOff: Boolean = false,
    /** Set when the phone got too hot and vynq lowered the video quality. */
    val heatNotice: String? = null,
)

/**
 * One 1:1 call at a time over LiveKit.
 *
 * Signalling (invite, answer, decline, end) goes end-to-end encrypted over the
 * Signal channel. The invite carries a fresh random media key; both phones
 * give it to LiveKit's frame encryption, so the LiveKit server forwards
 * frames it can't decrypt. The UI says "end-to-end encrypted" only while
 * every track reports an active key; otherwise "encrypted in transit".
 */
class CallManager(
    private val context: Context,
    private val api: BackendApi,
    private val messaging: MessagingService,
    private val prefs: PreferencesRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<CallState?>(null)
    val state: StateFlow<CallState?> = _state.asStateFlow()

    val notifier = CallNotifier(context)
    private val ringer = Ringer(context)
    private var room: Room? = null
    private var callKey: String? = null
    /** What the other phone can decode (from its invite or answer); null until known. */
    private var peerCodecs: List<String>? = null
    /** Short side of the other phone's screen (from its invite or answer). */
    private var peerScreen: Int? = null
    private var answered = false
    /** Caller's camera before the answer: shown to the caller, not sent yet. */
    private var preview: LocalVideoTrack? = null
    private val cameraLock = Mutex()
    /** What the camera is sending now (index into CallProfile.Levels). */
    private var sendLevel: Int? = null
    private var metrics: CallMetrics? = null
    private var heatJob: Job? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var roomJob: Job? = null
    private var timeout: Job? = null
    private val trackE2ee = mutableMapOf<String, E2EEState>()

    init {
        LiveKit.init(context)
        // Debug builds only: LiveKit warnings and errors in logcat (no media, no keys).
        if (BuildConfig.DEBUG) LiveKit.loggingLevel = LoggingLevel.WARN
    }

    /** Wire signals from the messenger (call once per messaging session). */
    fun onSignal(s: CallSignal) {
        scope.launch { handle(s) }
    }

    // --- outgoing ---------------------------------------------------------------------------------

    suspend fun startCall(peer: String, video: Boolean): Result<Unit> = runCatching {
        check(_state.value == null) { "Already in a call." }
        val session = messaging.session.value ?: error("Not signed in.")
        val callId = api.rpc("create_call", buildJsonObject { put("p_callee", peer); put("p_video", video) })
            .jsonPrimitive.content
        val key = newKey()
        callKey = key
        _state.value = CallState(callId, peer, session.messenger.contactLabel(peer), outgoing = true, video = video, phase = CallPhase.Ringing)
        val reached = session.messenger.sendCallSignal(peer, CallSignal.INVITE, callId, video = video, callKey = key,
            codecs = VideoCodecs.receivable(), screen = VideoSize.screenShortSide(context))
        if (reached == 0) {
            end("They haven't set up vynq on a phone yet.", notifyPeer = false)
            error("They haven't set up vynq on a phone yet.")
        }
        CallService.start(context, video)
        connect(callId, key, video)
        armTimeout(callId, RING_TIMEOUT_MS) { end("No answer", notifyPeer = true, note = if (video) "Video call · no answer" else "Voice call · no answer") }
    }

    // --- incoming ---------------------------------------------------------------------------------

    private suspend fun handle(s: CallSignal) {
        val current = _state.value
        when (s.type) {
            CallSignal.INVITE -> {
                if (s.self || s.callKey == null) return
                if (System.currentTimeMillis() - s.sentAt > RING_TIMEOUT_MS) {
                    note(s.from, if (s.video) "Missed video call" else "Missed voice call"); return
                }
                if (current != null) {
                    messaging.session.value?.messenger?.sendCallSignal(s.from, CallSignal.DECLINE, s.callId, reason = "busy")
                    note(s.from, if (s.video) "Missed video call (you were on another call)" else "Missed voice call (you were on another call)")
                    return
                }
                callKey = s.callKey
                peerCodecs = s.codecs
                peerScreen = s.screen
                val label = messaging.session.value?.messenger?.contactLabel(s.from) ?: "Someone"
                _state.value = CallState(s.callId, s.from, label, outgoing = false, video = s.video, phase = CallPhase.Ringing)
                notifier.showIncoming(s.callId, label, s.video)
                ringer.start()
                // Already open: show the call screen right away (allowed while vynq is in front).
                if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    runCatching { context.startActivity(callIntent(context)) }
                }
                armTimeout(s.callId, RING_TIMEOUT_MS) {
                    note(s.from, if (s.video) "Missed video call" else "Missed voice call")
                    clear()
                }
            }
            CallSignal.ANSWER -> if (current?.callId == s.callId) {
                if (s.self) { clear("Answered on another phone") }   // our other phone took it
                else {                                                // they answered; media joins in the room
                    timeout?.cancel()
                    peerCodecs = s.codecs
                    peerScreen = s.screen
                    answered = true
                    if (current.video) cameraLock.withLock { publishPreview() }
                }
            }
            CallSignal.DECLINE -> if (current?.callId == s.callId) {
                when {
                    s.self -> clear("Declined on another phone")
                    s.reason == "busy" -> end("Busy", notifyPeer = false, note = "Call · they were busy")
                    else -> end("Declined", notifyPeer = false, note = if (current.video) "Video call · declined" else "Voice call · declined")
                }
            }
            CallSignal.END -> if (current?.callId == s.callId) {
                val note = if (current.phase == CallPhase.Ringing && !current.outgoing) {
                    if (current.video) "Missed video call" else "Missed voice call"
                } else summary(current)
                end("Call ended", notifyPeer = false, note = note)
            }
        }
    }

    /** The user answered (from the call screen, which holds camera/mic permission by now). */
    fun accept() {
        val s = _state.value ?: return
        if (s.outgoing || s.phase != CallPhase.Ringing) return
        timeout?.cancel()
        ringer.stop()
        notifier.cancelIncoming()
        _state.update { it?.copy(phase = CallPhase.Connecting) }
        scope.launch {
            val m = messaging.session.value?.messenger
            runCatching { m?.sendCallSignal(s.peer, CallSignal.ANSWER, s.callId, alsoMine = true,
                codecs = VideoCodecs.receivable(), screen = VideoSize.screenShortSide(context)) }
            runCatching { api.rpc("update_call", buildJsonObject { put("p_call", s.callId); put("p_state", "answered") }) }
            CallService.start(context, s.video)
            connect(s.callId, callKey ?: return@launch end("Missing call key", notifyPeer = true), s.video)
        }
    }

    fun decline() {
        val s = _state.value ?: return
        scope.launch {
            runCatching { messaging.session.value?.messenger?.sendCallSignal(s.peer, CallSignal.DECLINE, s.callId, alsoMine = true) }
            note(s.peer, if (s.video) "Declined video call" else "Declined voice call")
            clear()
        }
    }

    fun hangUp() {
        val s = _state.value ?: return
        scope.launch { end("Call ended", notifyPeer = true, note = summary(s)) }
    }

    // --- in-call controls ---------------------------------------------------------------------------

    fun toggleMute() {
        val s = _state.value ?: return
        scope.launch { room?.localParticipant?.setMicrophoneEnabled(s.muted) }
        _state.update { it?.copy(muted = !s.muted) }
    }

    fun toggleCamera() {
        val s = _state.value ?: return
        scope.launch {
            cameraLock.withLock {
                val p = preview
                if (p != null) {
                    if (s.cameraOn) p.stopCapture() else p.startCapture()
                    _state.update { it?.copy(cameraOn = !s.cameraOn, localVideo = if (!s.cameraOn) p else null) }
                } else {
                    room?.localParticipant?.setCameraEnabled(!s.cameraOn)
                    _state.update { it?.copy(cameraOn = !s.cameraOn, localVideo = if (!s.cameraOn) localVideoTrack() else null) }
                }
            }
        }
    }

    fun switchCamera() {
        (preview ?: localVideoTrack())?.switchCamera()
    }

    fun toggleSpeaker() {
        val s = _state.value ?: return
        val audio = room?.audioHandler as? AudioSwitchHandler ?: return
        val target = audio.availableAudioDevices.firstOrNull {
            if (s.speaker) it is AudioDevice.Earpiece || it is AudioDevice.BluetoothHeadset || it is AudioDevice.WiredHeadset
            else it is AudioDevice.Speakerphone
        } ?: return
        audio.selectDevice(target)
        _state.update { it?.copy(speaker = target is AudioDevice.Speakerphone) }
    }

    fun room(): Room? = room

    // --- media ---------------------------------------------------------------------------------------

    private suspend fun connect(callId: String, key: String, video: Boolean) {
        val creds = runCatching {
            api.function("livekit-token", buildJsonObject { put("call_id", callId); put("device_id", api.deviceId) }).jsonObject
        }.getOrElse {
            end("Couldn't reach the call server.", notifyPeer = true); return
        }
        val url = creds["url"]?.jsonPrimitive?.content ?: return end("Calls aren't set up on the server yet.", notifyPeer = true)
        val token = creds["token"]?.jsonPrimitive?.content ?: return end("Calls aren't set up on the server yet.", notifyPeer = true)

        val keyProvider = BaseKeyProvider().apply { setSharedKey(key, null) }
        val profile = CallProfile.of(prefs.callQuality.first())
        val r = LiveKit.create(
            context,
            // 1:1 calls: one full-quality stream (no simulcast layers or view-size downscaling),
            // frame-encrypted (AES-GCM) with the key from the Signal invite.
            RoomOptions(
                adaptiveStream = false,
                dynacast = false,
                e2eeOptions = E2EEOptions(keyProvider = keyProvider),
                videoTrackCaptureDefaults = LocalVideoTrackOptions(captureParams = profile.capture),
                videoTrackPublishDefaults = VideoTrackPublishDefaults(
                    videoEncoding = profile.encoding,
                    simulcast = false,
                    // H.264 runs on the phone's hardware encoder; LiveKit's default VP8 is usually
                    // software on Android and drops resolution at 1080p.
                    videoCodec = VideoCodec.H264.codecName,   // set per call by VideoCodecs.choose
                    degradationPreference = CallProfile.Degradation,
                ),
            ),
        )
        room = r
        // Headsets first; then speaker for video, earpiece for voice.
        (r.audioHandler as? AudioSwitchHandler)?.preferredDeviceList =
            if (video) listOf(AudioDevice.BluetoothHeadset::class.java, AudioDevice.WiredHeadset::class.java, AudioDevice.Speakerphone::class.java, AudioDevice.Earpiece::class.java)
            else listOf(AudioDevice.BluetoothHeadset::class.java, AudioDevice.WiredHeadset::class.java, AudioDevice.Earpiece::class.java, AudioDevice.Speakerphone::class.java)
        roomJob = scope.launch { r.events.events.collect { onRoomEvent(it) } }
        try {
            r.connect(url, token)
            val mic = r.localParticipant.setMicrophoneEnabled(true)
            val cam = if (video) cameraLock.withLock { startCamera(r) } else false
            // Diagnostics only: no ids, names or content.
            Log.i(TAG, "joined: mic=$mic camera=$cam remotes=${r.remoteParticipants.size}")
            // The other phone may already be in the room (we answered): we're connected.
            if (r.remoteParticipants.isNotEmpty()) markConnected()
        } catch (e: Exception) {
            end("Couldn't connect the call.", notifyPeer = true)
        }
    }

    private fun onRoomEvent(e: RoomEvent) {
        when (e) {
            is RoomEvent.TrackSubscribed -> {
                val track = e.track
                if (track is VideoTrack) _state.update { it?.copy(remoteCameraOff = e.publication.muted) }
                Log.i(TAG, "subscribed: ${track.kind}")
                if (track is VideoTrack) _state.update { it?.copy(remoteVideo = track) }
                markConnected()
            }
            is RoomEvent.ParticipantConnected -> markConnected()
            is RoomEvent.TrackUnsubscribed -> if (e.track is VideoTrack) _state.update { it?.copy(remoteVideo = null) }
            is RoomEvent.TrackMuted -> if (e.participant != room?.localParticipant && e.publication.kind == Track.Kind.VIDEO) {
                _state.update { it?.copy(remoteCameraOff = true) }
            }
            is RoomEvent.TrackUnmuted -> if (e.participant != room?.localParticipant && e.publication.kind == Track.Kind.VIDEO) {
                _state.update { it?.copy(remoteCameraOff = false) }
            }
            is RoomEvent.TrackUnpublished -> if (e.participant != room?.localParticipant && e.publication.kind == Track.Kind.VIDEO) {
                _state.update { it?.copy(remoteVideo = null, remoteCameraOff = true) }
            }
            is RoomEvent.TrackE2EEStateEvent -> {
                Log.i(TAG, "e2ee ${e.track.kind} ${if (e.participant == room?.localParticipant) "local" else "remote"}: ${e.state}")
                trackE2ee[(if (e.participant == room?.localParticipant) "local:" else "remote:") + e.track.sid] = e.state
                // A remote track that decrypts with the call key proves the other phone encrypted it;
                // any failure anywhere means we don't claim end-to-end.
                val good = setOf(E2EEState.OK, E2EEState.KEY_RATCHETED)
                val ok = trackE2ee.any { it.key.startsWith("remote:") && it.value in good } && trackE2ee.values.all { it in good }
                _state.update { it?.copy(endToEnd = ok) }
            }
            is RoomEvent.Reconnecting -> _state.update { it?.copy(phase = CallPhase.Reconnecting) }
            is RoomEvent.Reconnected -> _state.update { it?.copy(phase = if (it.connectedAt != null) CallPhase.Connected else CallPhase.Connecting) }
            is RoomEvent.ParticipantDisconnected -> {
                // The other phone left the room: give it a moment (network switch), then end.
                val s = _state.value ?: return
                scope.launch {
                    delay(PEER_GRACE_MS)
                    if (_state.value?.callId == s.callId && room?.remoteParticipants.isNullOrEmpty()) {
                        end("Call ended", notifyPeer = false, note = summary(_state.value ?: s))
                    }
                }
            }
            is RoomEvent.Disconnected -> {
                val s = _state.value ?: return
                if (s.phase != CallPhase.Ended) scope.launch { end("Connection lost", notifyPeer = true, note = summary(s)) }
            }
            else -> Unit
        }
    }

    private fun markConnected() {
        timeout?.cancel()
        val r = room
        val s = _state.value
        if (r != null && s != null && s.phase != CallPhase.Connected) {
            if (BuildConfig.DEBUG && metrics == null) {
                metrics = CallMetrics(context, r, scope).also { m ->
                    scope.launch {
                        val level = sendLevel?.let { CallProfile.Levels[it].title } ?: prefs.callQuality.first().title
                        m.start(s.video, level, VideoCodecs.choose(peerCodecs))
                    }
                }
            }
            if (s.video) watchHeat()
        }
        _state.update {
            if (it == null || it.phase == CallPhase.Connected) it
            else it.copy(phase = CallPhase.Connected, connectedAt = it.connectedAt ?: System.currentTimeMillis())
        }
        CallService.update(context)
    }

    /**
     * The caller shows its camera but sends nothing until the answer says what the other
     * phone can decode; the callee knows from the invite and sends right away.
     */
    private suspend fun startCamera(r: Room): Boolean {
        val s = _state.value ?: return false
        if (s.outgoing && !answered) {
            val track = r.localParticipant.createVideoTrack()
            track.startCapture()
            preview = track
            _state.update { it?.copy(localVideo = track) }
            return true
        }
        return publishCamera(r)
    }

    /** They answered: swap the caller's preview for a sent camera in the agreed format. */
    private suspend fun publishPreview() {
        val r = room ?: return              // still connecting: startCamera sends once joined
        val p = preview ?: return
        preview = null
        val on = _state.value?.cameraOn ?: true
        runCatching { p.stop(); p.dispose() }
        publishCamera(r)
        if (!on) runCatching { r.localParticipant.setCameraEnabled(false) }
    }

    private suspend fun publishCamera(r: Room): Boolean {
        val codec = VideoCodecs.choose(peerCodecs)
        val quality = prefs.callQuality.first()
        val qhd = VideoSize.send1440(quality == CallQuality.Maximum, peerScreen) { VideoSize.deviceCan1440(context, codec) }
        val level = sendLevel ?: if (qhd) CallProfile.Levels.lastIndex else CallProfile.Levels.indexOfFirst { it.quality == quality }
        sendLevel = level
        val profile = CallProfile.Levels[level].profile
        r.videoTrackCaptureDefaults = r.videoTrackCaptureDefaults.copy(captureParams = profile.capture)
        r.videoTrackPublishDefaults = r.videoTrackPublishDefaults.copy(videoEncoding = profile.encoding, videoCodec = codec)
        val ok = runCatching { r.localParticipant.setCameraEnabled(true) }.getOrDefault(false)
        Log.i(TAG, "camera sent as $codec ${if (qhd) "1440p" else "up to 1080p"} (they decode ${peerCodecs ?: "unknown"}, screen ${peerScreen ?: "unknown"}): $ok")
        _state.update { it?.copy(localVideo = localVideoTrack()) }
        return ok
    }

    /**
     * Owner decision (Phase 5): quality is the user's, except when Android reports
     * severe heat, just before it throttles or shuts the phone down. Then step the
     * camera down one level, say so on the call screen, and step again every 2 min
     * while it stays that hot.
     */
    private fun watchHeat() {
        if (Build.VERSION.SDK_INT < 29 || thermalListener != null) return
        val power = context.getSystemService(PowerManager::class.java)
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            if (status >= PowerManager.THERMAL_STATUS_SEVERE && heatJob?.isActive != true) {
                heatJob = scope.launch {
                    while (power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE && room != null) {
                        if (!cameraLock.withLock { stepDown() }) break
                        delay(120_000)
                    }
                }
            }
        }
        power.addThermalStatusListener(context.mainExecutor, listener)
        thermalListener = listener
    }

    /** One level down (republishes the camera); false when already at the lowest. */
    private suspend fun stepDown(): Boolean {
        val r = room ?: return false
        val now = sendLevel ?: return false
        if (now == 0) return false
        val next = now - 1
        sendLevel = next
        val cameraOn = _state.value?.cameraOn == true
        localVideoTrack()?.let { r.localParticipant.unpublishTrack(it, true) }
        if (cameraOn) publishCamera(r)
        val title = CallProfile.Levels[next].title
        Log.i(TAG, "phone too hot (thermal ${if (Build.VERSION.SDK_INT >= 29) context.getSystemService(PowerManager::class.java).currentThermalStatus else -1}): video lowered to $title")
        _state.update { it?.copy(heatNotice = "Your phone is very hot, so video was lowered to $title.") }
        return true
    }

    private fun localVideoTrack(): LocalVideoTrack? =
        room?.localParticipant?.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack

    // --- teardown --------------------------------------------------------------------------------------

    private suspend fun end(reason: String, notifyPeer: Boolean, note: String? = null) {
        val s = _state.value ?: return
        if (notifyPeer) runCatching { messaging.session.value?.messenger?.sendCallSignal(s.peer, CallSignal.END, s.callId) }
        runCatching { api.rpc("update_call", buildJsonObject { put("p_call", s.callId); put("p_state", "ended") }) }
        note?.let { note(s.peer, it) }
        clear(reason)
    }

    /** Releases everything: room, camera, microphone, service, notifications. */
    private fun clear(reason: String? = null) {
        timeout?.cancel(); timeout = null
        roomJob?.cancel(); roomJob = null
        room?.let { r -> runCatching { r.disconnect() }; runCatching { r.release() } }
        room = null
        callKey = null
        peerCodecs = null
        peerScreen = null
        answered = false
        sendLevel = null
        metrics?.stop(); metrics = null
        heatJob?.cancel(); heatJob = null
        if (Build.VERSION.SDK_INT >= 29) thermalListener?.let { context.getSystemService(PowerManager::class.java).removeThermalStatusListener(it) }
        thermalListener = null
        preview?.let { p -> runCatching { p.stop(); p.dispose() } }
        preview = null
        trackE2ee.clear()
        ringer.stop()
        notifier.cancelIncoming()
        CallService.stop(context)
        _state.update { it?.copy(phase = CallPhase.Ended, endReason = reason, localVideo = null, remoteVideo = null) }
        scope.launch { delay(1_500); if (_state.value?.phase == CallPhase.Ended) _state.value = null }
    }

    private fun armTimeout(callId: String, ms: Long, onTimeout: suspend () -> Unit) {
        timeout?.cancel()
        timeout = scope.launch {
            delay(ms)
            if (_state.value?.callId == callId && _state.value?.phase == CallPhase.Ringing) onTimeout()
        }
    }

    private fun note(peer: String, text: String) {
        val m = messaging.session.value?.messenger ?: return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { m.callNote(peer, text) }
    }

    private fun summary(s: CallState): String {
        val kind = if (s.video) "Video call" else "Voice call"
        val start = s.connectedAt ?: return "$kind · not connected"
        val secs = (System.currentTimeMillis() - start) / 1000
        return "$kind · %d:%02d".format(secs / 60, secs % 60)
    }

    private fun newKey(): String =
        Base64.getEncoder().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

    companion object {
        const val RING_TIMEOUT_MS = 45_000L
        private const val TAG = "vynq.call"
        private const val PEER_GRACE_MS = 15_000L

        fun callIntent(context: Context, action: String? = null): Intent =
            Intent(context, CallActivity::class.java).setAction(action ?: CallActivity.ACTION_SHOW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

/**
 * What a call sends for each Settings → Call quality choice. The resolution is
 * the user's: vynq never lowers it during a call. On a weak connection WebRTC
 * keeps every pixel and sends fewer frames instead (MAINTAIN_RESOLUTION);
 * people who'd rather have smooth video there pick a lower quality.
 * (Phase 5 measures these against real networks and tunes them.)
 */
data class CallProfile(val capture: VideoCaptureParameter, val encoding: VideoEncoding) {
    companion object {
        val Degradation = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION

        fun of(q: CallQuality): CallProfile = when (q) {
            CallQuality.DataSaver -> CallProfile(VideoCaptureParameter(640, 360, 24), VideoEncoding(500_000, 24))
            CallQuality.Automatic -> CallProfile(VideoCaptureParameter(1920, 1080, 30), VideoEncoding(3_500_000, 30))
            CallQuality.High -> CallProfile(VideoCaptureParameter(1920, 1080, 30), VideoEncoding(5_000_000, 30))
            CallQuality.Maximum -> CallProfile(VideoCaptureParameter(1920, 1080, 30), VideoEncoding(8_000_000, 30))
        }

        /** Maximum, when both phones allow it (VideoSize.send1440). */
        val Qhd = CallProfile(VideoCaptureParameter(2560, 1440, 30), VideoEncoding(12_000_000, 30))

        /** Lowest to highest, for the heat step-down. */
        val Levels: List<Level> = listOf(
            Level(CallQuality.DataSaver, CallQuality.DataSaver.title, of(CallQuality.DataSaver)),
            Level(CallQuality.Automatic, CallQuality.Automatic.title, of(CallQuality.Automatic)),
            Level(CallQuality.High, CallQuality.High.title, of(CallQuality.High)),
            Level(CallQuality.Maximum, "Maximum (1080p)", of(CallQuality.Maximum)),
            Level(null, "Maximum (1440p)", Qhd),
        )
    }

    data class Level(val quality: CallQuality?, val title: String, val profile: CallProfile) {
    }
}
