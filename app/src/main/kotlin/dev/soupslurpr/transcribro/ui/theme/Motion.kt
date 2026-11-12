package dev.soupslurpr.transcribro.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Motion of docs/design-language.md: 150-250 ms for state changes, 300-400 ms for entering
 * screens (fade + 8 dp slide), no bounce, nothing moving on its own while the user reads.
 */
object VerbaloMotion {
    const val STATE_MS = 200
    const val ENTER_MS = 350
    /** Between cards that enter one after another. */
    const val STAGGER_MS = 60L

    /** A screen enters: fades in and rises 8 dp. */
    fun screenEnter(slidePx: Int): EnterTransition =
        fadeIn(tween(ENTER_MS, easing = FastOutSlowInEasing)) +
            slideInVertically(tween(ENTER_MS, easing = FastOutSlowInEasing)) { slidePx }

    /** The screen underneath only fades, so the two never slide against each other. */
    fun screenExit(): ExitTransition = fadeOut(tween(STATE_MS))
}

/**
 * Fade + 8 dp rise when the element first appears, [index] steps after the first one. Plays once:
 * the flag is saveable, so a card scrolled out of a lazy list and back does not animate again.
 */
@Composable
fun Modifier.enterOnce(index: Int = 0): Modifier {
    var played by rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(if (played) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (played) return@LaunchedEffect
        delay(index.coerceAtMost(8) * VerbaloMotion.STAGGER_MS)
        progress.animateTo(1f, tween(VerbaloMotion.ENTER_MS, easing = FastOutSlowInEasing))
        played = true
    }
    val rise = with(LocalDensity.current) { 8.dp.toPx() }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * rise
    }
}
