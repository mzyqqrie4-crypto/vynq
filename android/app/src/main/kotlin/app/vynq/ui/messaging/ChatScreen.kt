package app.vynq.ui.messaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.vynq.container
import app.vynq.data.auth.userMessage
import app.vynq.data.crypto.AttachmentCrypto
import app.vynq.data.messaging.AttachmentPointer
import app.vynq.data.messaging.Message
import app.vynq.data.messaging.MessageKind
import app.vynq.data.messaging.MessageStatus
import app.vynq.data.messaging.Messenger
import app.vynq.designsystem.component.VynqAlert
import app.vynq.designsystem.component.VynqIconButton
import app.vynq.designsystem.component.VynqTextField
import app.vynq.designsystem.component.pressable
import app.vynq.designsystem.icon.VynqIcons
import app.vynq.designsystem.theme.VynqRadius
import app.vynq.designsystem.theme.VynqSpacing
import app.vynq.designsystem.theme.VynqTheme
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChatScreen(peer: String, onBack: () -> Unit, onVerify: () -> Unit) {
    val session = messagingSession()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = VynqTheme.colors

    val contact by rememberLocal(session, null, peer) { it.dao.contact(peer) }
    val messages by rememberLocal(session, emptyList(), peer) { it.dao.messages(peer) }
    val verified by rememberLocal(session, false, peer) { it.messenger.isVerified(peer) }
    var draft by rememberSaveable { mutableStateOf("") }
    var alert by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(messages.size, session) {
        session?.let { s -> withContext(Dispatchers.IO) { s.dao.markRead(peer) } }
    }
    LaunchedEffect(session) { context.container.messaging.syncNow() }
    // While this chat is on screen it doesn't notify, and its notification goes away.
    androidx.compose.runtime.DisposableEffect(peer) {
        val messaging = context.container.messaging
        messaging.openChat.value = peer
        messaging.notifier.cancelConversation(peer)
        onDispose { if (messaging.openChat.value == peer) messaging.openChat.value = null }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val s = session ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch {
            val photo = withContext(Dispatchers.IO) { runCatching { preparePhoto(context, uri) }.getOrNull() }
            if (photo == null) alert = "That photo couldn't be read." else
                s.messenger.sendPhoto(peer, photo.bytes, "image/jpeg", photo.width, photo.height)
        }
    }

    fun send() {
        val s = session ?: return
        val text = draft.trim()
        if (text.isEmpty()) return
        draft = ""
        scope.launch { runCatching { s.messenger.sendText(peer, text) }.onFailure { alert = it.userMessage() } }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = VynqSpacing.sm, vertical = VynqSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VynqIconButton(VynqIcons.Back, "Back", onBack, size = 44)
            Row(
                Modifier.weight(1f).pressable(onClick = onVerify).padding(VynqSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(VynqSpacing.sm),
            ) {
                Avatar(contact?.label ?: "?", 36)
                Column {
                    Text(contact?.label ?: "", style = VynqTheme.type.headline, color = c.textPrimary, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (verified) VynqIcons.Shield else VynqIcons.Lock, null, Modifier.size(12.dp),
                            tint = if (verified) c.success else c.textTertiary)
                        Spacer(Modifier.size(4.dp))
                        Text(
                            if (verified) "Verified · end-to-end encrypted" else "End-to-end encrypted · tap to verify",
                            style = VynqTheme.type.caption, color = if (verified) c.success else c.textTertiary,
                        )
                    }
                }
            }
            CallButtons(peer)
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(VynqSpacing.xs),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = VynqSpacing.md, vertical = VynqSpacing.md),
        ) {
            items(messages.asReversed(), key = { it.id }) { m ->
                when (m.kind) {
                    MessageKind.System -> SystemNote(m)
                    else -> Bubble(m, session?.messenger) { scope.launch { session?.messenger?.retry(m.id) } }
                }
            }
            if (messages.isEmpty()) item {
                Text(
                    "Messages here are end-to-end encrypted. Only you and ${contact?.label ?: "them"} can read them, not vynq.",
                    style = VynqTheme.type.footnote, color = c.textTertiary, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(VynqSpacing.xxl),
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = VynqSpacing.sm, vertical = VynqSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VynqSpacing.xs),
        ) {
            VynqIconButton(VynqIcons.Photo, "Send a photo", {
                pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, enabled = session != null, size = 44)
            VynqTextField(
                value = draft,
                onValueChange = { draft = it.take(4000) },
                placeholder = "Message",
                compact = true,
                enabled = session != null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f),
            )
            VynqIconButton(VynqIcons.Send, "Send", ::send, accent = true, enabled = draft.isNotBlank(), size = 40)
        }
    }

    alert?.let { VynqAlert("Couldn't send", it, "OK") { alert = null } }
}

/** Voice and video call buttons; asks for microphone/camera first, then rings. */
@Composable
private fun CallButtons(peer: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingVideo by remember { mutableStateOf<Boolean?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    fun start(video: Boolean) {
        scope.launch {
            context.container.calls.startCall(peer, video)
                .onSuccess { context.startActivity(app.vynq.call.CallManager.callIntent(context)) }
                .onFailure { failure = it.message ?: it.userMessage() }
        }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val video = pendingVideo ?: return@rememberLauncherForActivityResult
        pendingVideo = null
        if (granted[android.Manifest.permission.RECORD_AUDIO] != false &&
            (!video || granted[android.Manifest.permission.CAMERA] != false)) start(video)
        else failure = if (video) "vynq needs the camera and microphone for video calls." else "vynq needs the microphone for calls."
    }
    fun request(video: Boolean) {
        val perms = buildList {
            add(android.Manifest.permission.RECORD_AUDIO)
            if (video) add(android.Manifest.permission.CAMERA)
            if (android.os.Build.VERSION.SDK_INT >= 31) add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = perms.filter { androidx.core.content.ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) start(video) else { pendingVideo = video; ask.launch(missing.toTypedArray()) }
    }
    VynqIconButton(VynqIcons.Phone, "Voice call", { request(false) }, size = 44)
    VynqIconButton(VynqIcons.Video, "Video call", { request(true) }, size = 44)
    failure?.let { VynqAlert("Couldn't start the call", it, "OK") { failure = null } }
}

@Composable
private fun SystemNote(m: Message) {
    val c = VynqTheme.colors
    val warning = m.text.orEmpty().startsWith("Security warning") || m.text.orEmpty().contains("couldn't be decrypted")
    Row(
        Modifier.fillMaxWidth().padding(vertical = VynqSpacing.sm, horizontal = VynqSpacing.lg),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Top,
    ) {
        Icon(if (warning) VynqIcons.Warning else VynqIcons.Shield, null, Modifier.size(14.dp).padding(top = 2.dp),
            tint = if (warning) c.warning else c.textTertiary)
        Spacer(Modifier.size(6.dp))
        Text(m.text.orEmpty(), style = VynqTheme.type.footnote, color = if (warning) c.warning else c.textTertiary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Bubble(m: Message, messenger: Messenger?, onRetry: () -> Unit) {
    val c = VynqTheme.colors
    val shape = RoundedCornerShape(VynqRadius.lg)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.outgoing) Alignment.End else Alignment.Start) {
        Box(
            Modifier.widthIn(max = 300.dp).clip(shape)
                .background(if (m.outgoing) c.accent else c.surfaceRaised, shape)
                .then(if (m.status == MessageStatus.Failed) Modifier.pressable(onClick = onRetry) else Modifier)
                .padding(if (m.kind == MessageKind.Photo) 3.dp else 0.dp),
        ) {
            if (m.kind == MessageKind.Photo && m.attachment != null) {
                PhotoContent(m.attachment, messenger)
            } else {
                Text(
                    m.text.orEmpty(),
                    style = VynqTheme.type.body,
                    color = if (m.outgoing) c.onAccent else c.textPrimary,
                    modifier = Modifier.padding(horizontal = VynqSpacing.md, vertical = VynqSpacing.sm),
                )
            }
        }
        val status = when (m.status) {
            MessageStatus.Sending -> "Sending…"
            MessageStatus.Failed -> "Not sent · tap to retry"
            else -> timeLabel(m.sentAt)
        }
        Text(status, style = VynqTheme.type.caption,
            color = if (m.status == MessageStatus.Failed) c.danger else c.textTertiary,
            modifier = Modifier.padding(horizontal = VynqSpacing.xs, vertical = 2.dp))
    }
}

private sealed interface PhotoState {
    data object Loading : PhotoState
    data class Ready(val bitmap: Bitmap) : PhotoState
    data class Failed(val reason: String) : PhotoState
}

@Composable
private fun PhotoContent(pointer: AttachmentPointer, messenger: Messenger?) {
    val c = VynqTheme.colors
    val state by produceState<PhotoState>(PhotoState.Loading, pointer.path, messenger) {
        if (messenger == null) return@produceState
        value = try {
            val bytes = messenger.openAttachment(pointer)
            withContext(Dispatchers.Default) { decodeSampled(bytes, 900) }?.let(PhotoState::Ready)
                ?: PhotoState.Failed("This photo couldn't be displayed.")
        } catch (e: AttachmentCrypto.IntegrityException) {
            PhotoState.Failed("This photo failed its integrity check and wasn't shown.")
        } catch (e: Exception) {
            PhotoState.Failed("Couldn't load this photo. Check your connection.")
        }
    }
    val ratio = if (pointer.width != null && pointer.height != null && pointer.height > 0)
        (pointer.width.toFloat() / pointer.height).coerceIn(0.5f, 2f) else 1f
    val shape = RoundedCornerShape(VynqRadius.lg - 3.dp)
    when (val s = state) {
        is PhotoState.Ready -> Image(s.bitmap.asImageBitmap(), "Photo", Modifier.size(260.dp, (260 / ratio).dp).clip(shape), contentScale = ContentScale.Crop)
        PhotoState.Loading -> Box(Modifier.size(260.dp).aspectRatio(ratio).background(c.surface, shape), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(22.dp), color = c.textTertiary, strokeWidth = 2.dp)
        }
        is PhotoState.Failed -> Row(Modifier.padding(VynqSpacing.md), verticalAlignment = Alignment.CenterVertically) {
            Icon(VynqIcons.Warning, null, Modifier.size(16.dp), tint = c.warning)
            Spacer(Modifier.size(6.dp))
            Text(s.reason, style = VynqTheme.type.footnote, color = c.textSecondary)
        }
    }
}

private class PreparedPhoto(val bytes: ByteArray, val width: Int, val height: Int)

/**
 * Re-encodes to JPEG, at most 1600 px on the long side. Re-encoding also drops
 * all metadata (EXIF location, camera serials) before anything is encrypted.
 */
private fun preparePhoto(context: Context, uri: Uri): PreparedPhoto {
    val raw = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    val bitmap = decodeSampled(raw, 1600) ?: error("not an image")
    val scale = minOf(1f, 1600f / maxOf(bitmap.width, bitmap.height))
    val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
    return PreparedPhoto(out.toByteArray(), scaled.width, scaled.height)
}

private fun decodeSampled(bytes: ByteArray, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}
