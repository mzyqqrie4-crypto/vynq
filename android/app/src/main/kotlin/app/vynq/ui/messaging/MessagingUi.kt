package app.vynq.ui.messaging

import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.Image
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vynq.container
import app.vynq.data.messaging.MessagingSession
import app.vynq.designsystem.theme.VynqTheme
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The signed-in messaging session, or null (signed out / not configured). */
@Composable
fun messagingSession(): MessagingSession? {
    val session by LocalContext.current.container.messaging.session.collectAsStateWithLifecycle()
    return session
}

/** Reads from the local store off the main thread, again after every change. */
@Composable
fun <T> rememberLocal(session: MessagingSession?, initial: T, vararg keys: Any?, read: (MessagingSession) -> T): State<T> {
    val tick = session?.dao?.changes?.collectAsStateWithLifecycle()?.value
    return produceState(initial, session, tick, *keys) {
        value = session?.let { s -> withContext(Dispatchers.IO) { read(s) } } ?: initial
    }
}

/**
 * A person's profile picture (decrypted on this phone), or their initial when
 * they have none or it can't be opened. Pass [userId] to show the picture.
 */
@Composable
fun Avatar(label: String, size: Int = 44, userId: String? = null) {
    val c = VynqTheme.colors
    val session = messagingSession()
    val tick = session?.dao?.changes?.collectAsStateWithLifecycle()?.value
    var last by remember { mutableStateOf<ByteArray?>(null) }
    val image by produceState<ImageBitmap?>(null, session, userId, tick) {
        val bytes = if (session != null && userId != null) runCatching { session.messenger.avatar(userId) }.getOrNull() else null
        if (bytes == null) { last = null; value = null; return@produceState }
        if (last?.contentEquals(bytes) == true && value != null) return@produceState
        last = bytes
        value = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
    }
    Box(Modifier.size(size.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
        val picture = image
        if (picture != null) {
            Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                label.trimStart('@').take(1).uppercase().ifEmpty { "?" },
                style = if (size >= 40) VynqTheme.type.headline else VynqTheme.type.subhead,
                color = c.accent,
            )
        }
    }
}

fun timeLabel(millis: Long): String {
    val now = System.currentTimeMillis()
    val sameDay = now / 86_400_000 == millis / 86_400_000 && now - millis < 86_400_000
    return if (sameDay) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
    else DateFormat.getDateInstance(DateFormat.SHORT).format(Date(millis))
}
