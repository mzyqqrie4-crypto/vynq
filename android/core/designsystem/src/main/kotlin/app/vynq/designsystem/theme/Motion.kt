package app.vynq.designsystem.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable

/**
 * Motion tokens. Springs are critically or near-critically damped: things settle,
 * they don't bounce. When the system "Remove animations" setting is on, every
 * spec collapses to [snap].
 */
@Immutable
class VynqMotion(val reduced: Boolean) {
    /** Press feedback, toggles. */
    fun <T> quick(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1400f)

    /** Element enter/exit, layout changes. */
    fun <T> standard(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)

    /** Cross-fades between screens. */
    fun <T> fade(): FiniteAnimationSpec<T> = if (reduced) snap() else tween(durationMillis = 220)

    companion object {
        const val PRESSED_SCALE = 0.97f
    }
}
