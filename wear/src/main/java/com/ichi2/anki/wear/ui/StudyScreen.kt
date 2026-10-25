/*
 *  Copyright (c) 2026 Jaehwa Chang <buffmail@gmail.com>
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.anki.wear.ui

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.timeTextCurvedText
import androidx.wear.compose.material3.timeTextSeparator
import com.ichi2.anki.wear.R
import com.ichi2.anki.wear.StudyState
import com.ichi2.anki.wear.StudyViewModel
import com.ichi2.anki.wear.asString
import com.ichi2.anki.wear.protocol.WatchCounts
import com.ichi2.anki.wear.protocol.WatchRating
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val againColor = Color(0xFFC62828)
private val goodColor = Color(0xFF2E7D32)

/** How long the screen stays on after the last tap. Longer than the watch's own timeout. */
private const val KEEP_SCREEN_ON_MILLIS = 60_000L

/**
 * Width of each answer band, as a fraction of the screen: tapping the left band answers Again,
 * the right band Good. Tapping the middle does nothing, so a stray tap there is harmless.
 */
private const val ANSWER_BAND_FRACTION = 0.3f

/** The bands don't answer until the answer has been shown this long: a double tap on Show
 * would otherwise answer the card too. */
private const val ANSWER_LOCK_MILLIS = 400L

/** How long the tapped band lights up. */
private const val FLASH_MILLIS = 350

/** Again buzzes twice, this far apart; Good buzzes once. */
private const val AGAIN_SECOND_BUZZ_MILLIS = 130L

@Composable
fun StudyScreen(viewModel: StudyViewModel) {
    when (val state = viewModel.state) {
        StudyState.Loading -> LoadingScreen()
        is StudyState.Failed ->
            MessageScreen(
                title = stringResource(R.string.error_title),
                message = state.message.asString(),
                buttonText = stringResource(R.string.retry),
                onButtonClick = viewModel::reload,
            )
        is StudyState.Finished ->
            MessageScreen(
                title = state.deckName.substringAfterLast("::"),
                message = stringResource(R.string.finished),
                buttonText = stringResource(R.string.check_again),
                onButtonClick = viewModel::reload,
            )
        is StudyState.Showing -> {
            KeepScreenOn(key = state.sequence to state.revealed)
            CardScreen(state, onReveal = viewModel::reveal, onAnswer = viewModel::answer)
        }
    }
}

/**
 * The card. Tap to show the answer; then tap the left side for Again or the right side for Good.
 * Nothing else takes room from the text: thin colored arcs on the edges show which side is which.
 * Swiping right goes back to the deck list, as everywhere on Wear OS.
 */
@VisibleForTesting
@Composable
internal fun CardScreen(
    state: StudyState.Showing,
    onReveal: () -> Unit,
    onAnswer: (WatchRating) -> Unit,
) {
    val card = state.card
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    // rotary input (the Galaxy Watch bezel) scrolls the card
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(state.sequence) { scrollState.scrollTo(0) }

    // After revealing, scroll so that the answer starts just below the clock.
    var answerTop by remember(state.sequence) { mutableStateOf<Int?>(null) }
    LaunchedEffect(answerTop) {
        val top = answerTop ?: return@LaunchedEffect
        val margin = with(density) { (screenWidth * 0.15f).roundToPx() }
        scrollState.animateScrollTo((top - margin).coerceAtLeast(0))
    }

    // When the answer was shown, in the time base of touch events (SystemClock.uptimeMillis)
    val revealedAt = remember { mutableLongStateOf(0L) }

    // the band last tapped, lit up for a moment (over the next card too)
    var flashRating by remember { mutableStateOf<WatchRating?>(null) }
    val flashAlpha = remember { Animatable(0f) }

    fun answer(rating: WatchRating) {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        if (rating == WatchRating.AGAIN) {
            scope.launch {
                delay(AGAIN_SECOND_BUZZ_MILLIS)
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            }
        }
        flashRating = rating
        scope.launch {
            flashAlpha.snapTo(1f)
            flashAlpha.animateTo(0f, tween(FLASH_MILLIS))
        }
        onAnswer(rating)
    }

    fun reveal(uptimeMillis: Long) {
        revealedAt.longValue = uptimeMillis
        onReveal()
    }

    // One tap handler for the whole card, so nothing inside can swallow the taps: before the
    // answer is shown a tap anywhere shows it, then the sides answer.
    val currentState by rememberUpdatedState(state)
    val revealWith by rememberUpdatedState<(Long) -> Unit> { reveal(it) }
    val answerWith by rememberUpdatedState<(WatchRating) -> Unit> { answer(it) }

    ScreenScaffold(
        scrollState = scrollState,
        timeText = { CountsTimeText(state.counts) },
    ) { _ ->
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        // null when the card scrolled instead, or a button on top (Show) took the tap
                        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                        // a swipe (going back, or old habit) isn't a tap
                        if ((up.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            return@awaitEachGesture
                        }
                        up.consume()
                        val card = currentState
                        when {
                            !card.revealed -> revealWith(up.uptimeMillis)
                            card.sending -> {}
                            up.uptimeMillis - revealedAt.longValue < ANSWER_LOCK_MILLIS -> {}
                            else -> answerAt(up.position.x, size.width.toFloat())?.let(answerWith)
                        }
                    }
                },
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        // dimmed while the answer is on its way to the phone
                        .graphicsLayer { alpha = if (state.sending) 0.5f else 1f }
                        .verticalScroll(scrollState)
                        .requestFocusOnHierarchyActive()
                        .rotaryScrollable(
                            RotaryScrollableDefaults.behavior(scrollableState = scrollState),
                            focusRequester = focusRequester,
                        ).padding(horizontal = screenWidth * 0.12f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(screenWidth * 0.2f))
                if (state.revealed && card.answerIncludesQuestion) {
                    CardText(card.answer)
                } else {
                    CardText(card.question)
                }
                if (state.revealed && !card.answerIncludesQuestion) {
                    Box(
                        Modifier
                            .padding(vertical = 10.dp)
                            .fillMaxWidth(0.5f)
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                            .onGloballyPositioned { answerTop = it.positionInParent().y.roundToInt() },
                    )
                    CardText(card.answer)
                }
                // keeps the last line out of the round screen's narrow bottom
                Spacer(Modifier.height(screenWidth * if (state.revealed) 0.15f else 0.26f))
            }

            if (state.revealed) {
                AnswerHints()
            } else {
                // fades out text that scrolls behind the button
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(screenWidth * 0.26f)
                        .background(Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.Black)),
                )
                EdgeButton(
                    onClick = { reveal(SystemClock.uptimeMillis()) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                    buttonSize = EdgeButtonSize.ExtraSmall,
                ) {
                    Text(stringResource(R.string.show_answer))
                }
            }

            flashRating?.let { AnswerFlash(it, flashAlpha.value) }
        }
    }
}

/** The answer a tap at [x] gives: Again on the left band, Good on the right, nothing in between. */
private fun answerAt(
    x: Float,
    width: Float,
): WatchRating? =
    when {
        x < width * ANSWER_BAND_FRACTION -> WatchRating.AGAIN
        x > width * (1 - ANSWER_BAND_FRACTION) -> WatchRating.GOOD
        else -> null
    }

/** Lights up the band that was tapped and names the answer, fading out as [alpha] goes to 0. */
@Composable
private fun BoxScope.AnswerFlash(
    rating: WatchRating,
    alpha: Float,
) {
    if (alpha <= 0f) return
    val isAgain = rating == WatchRating.AGAIN
    Box(
        Modifier
            .align(if (isAgain) Alignment.CenterStart else Alignment.CenterEnd)
            .fillMaxHeight()
            .fillMaxWidth(ANSWER_BAND_FRACTION)
            .graphicsLayer { this.alpha = alpha }
            .background((if (isAgain) againColor else goodColor).copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(if (isAgain) R.string.again else R.string.good),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Thin arcs on the screen edge: red on the left (tap there for Again), green on the right (Good).
 * They sit in the lower half so the right one stays clear of the scroll indicator at 3 o'clock.
 */
@Composable
private fun BoxScope.AnswerHints() {
    Canvas(Modifier.matchParentSize().padding(3.dp)) {
        val strokeWidth = 4.dp.toPx()
        val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
        val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)
        val stroke = Stroke(strokeWidth, cap = StrokeCap.Round)
        // angles in degrees, clockwise from 3 o'clock: about 4-5 o'clock and 7-8 o'clock
        drawArc(goodColor, startAngle = 25f, sweepAngle = 40f, useCenter = false, topLeft, arcSize, style = stroke)
        drawArc(againColor, startAngle = 115f, sweepAngle = 40f, useCenter = false, topLeft, arcSize, style = stroke)
    }
}

/** Card text, larger when it's short (a word or two). */
@Composable
private fun CardText(html: String) {
    val text = remember(html) { AnnotatedString.fromHtml(html) }
    val style = if (text.length <= 40) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge
    Text(
        text = if (text.isBlank()) AnnotatedString(stringResource(R.string.empty_card)) else text,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        style = style,
    )
}

/** The clock, followed by the number of cards left to study today, in bold accent color. */
@Composable
private fun CountsTimeText(counts: WatchCounts) {
    val totalStyle = CurvedTextStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    TimeText { time ->
        timeTextCurvedText(time)
        timeTextSeparator()
        timeTextCurvedText(counts.total.toString(), totalStyle)
    }
}

/** Keeps the screen on for [KEEP_SCREEN_ON_MILLIS]; the timer restarts when [key] changes. */
@Composable
private fun KeepScreenOn(key: Any) {
    val window = LocalActivity.current?.window ?: return
    LaunchedEffect(key) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        delay(KEEP_SCREEN_ON_MILLIS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    DisposableEffect(window) {
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}
