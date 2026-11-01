package app.vynq.ui.messaging

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

@Composable
fun Avatar(label: String, size: Int = 44) {
    val c = VynqTheme.colors
    Box(Modifier.size(size.dp).background(c.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
        Text(
            label.trimStart('@').take(1).uppercase().ifEmpty { "?" },
            style = if (size >= 40) VynqTheme.type.headline else VynqTheme.type.subhead,
            color = c.accent,
        )
    }
}

fun timeLabel(millis: Long): String {
    val now = System.currentTimeMillis()
    val sameDay = now / 86_400_000 == millis / 86_400_000 && now - millis < 86_400_000
    return if (sameDay) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
    else DateFormat.getDateInstance(DateFormat.SHORT).format(Date(millis))
}
