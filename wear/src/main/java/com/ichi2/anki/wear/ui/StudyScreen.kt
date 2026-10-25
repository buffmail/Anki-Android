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

import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlin.math.abs
import kotlin.math.roundToInt

private val againColor = Color(0xFFC62828)
private val goodColor = Color(0xFF2E7D32)

/** How long the screen stays on after the last tap. Longer than the watch's own timeout. */
private const val KEEP_SCREEN_ON_MILLIS = 60_000L

/** How far to swipe, as a fraction of the screen width, before letting go answers the card. */
private const val SWIPE_TO_ANSWER_FRACTION = 0.25f

@Composable
fun StudyScreen(
    viewModel: StudyViewModel,
    /** Told when swiping answers the card, so the app can turn off swipe-to-go-back meanwhile. */
    onSwipeToAnswerChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val state = viewModel.state
    val swipeToAnswer = state is StudyState.Showing && state.revealed
    DisposableEffect(swipeToAnswer) {
        onSwipeToAnswerChanged(swipeToAnswer)
        onDispose { onSwipeToAnswerChanged(false) }
    }
    // Turning off swipe-to-go-back also turns off the back button on Wear OS 6: keep it working.
    BackHandler(enabled = swipeToAnswer, onBack = onBack)

    when (state) {
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
 * The card. Tap to show the answer; then swipe left for Again or right for Good.
 * Nothing else takes room from the text: thin colored arcs on the edges show which way is which.
 */
@Composable
private fun CardScreen(
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

    val canAnswer = state.revealed && !state.sending
    val screenWidthPx = with(density) { screenWidth.toPx() }
    val swipeThresholdPx = screenWidthPx * SWIPE_TO_ANSWER_FRACTION
    // horizontal position of the card while swiping; negative = Again, positive = Good
    var swipeOffset by remember(state.sequence) { mutableFloatStateOf(0f) }
    val swipeRating = swipeOffset.ratingAt(swipeThresholdPx)

    fun answer(rating: WatchRating) {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        onAnswer(rating)
    }

    ScreenScaffold(
        scrollState = scrollState,
        timeText = { CountsTimeText(state.counts) },
    ) { _ ->
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(canAnswer, state.sequence) {
                    if (!canAnswer) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val rating = swipeOffset.ratingAt(swipeThresholdPx)
                            if (rating != null) {
                                answer(rating)
                                // slide the card out while the next one loads
                                val target = if (rating == WatchRating.GOOD) screenWidthPx else -screenWidthPx
                                scope.launch { animate(swipeOffset, target) { value, _ -> swipeOffset = value } }
                            } else {
                                scope.launch { animate(swipeOffset, 0f) { value, _ -> swipeOffset = value } }
                            }
                        },
                        onDragCancel = {
                            scope.launch { animate(swipeOffset, 0f) { value, _ -> swipeOffset = value } }
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        val before = swipeOffset.ratingAt(swipeThresholdPx)
                        swipeOffset += dragAmount
                        val after = swipeOffset.ratingAt(swipeThresholdPx)
                        // a tick when crossing the point where letting go answers the card
                        if (after != null && after != before) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                    }
                },
        ) {
            SwipeFeedback(swipeOffset / swipeThresholdPx, swipeRating)
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = swipeOffset
                            alpha = 1f - 0.5f * (abs(swipeOffset) / swipeThresholdPx).coerceAtMost(1f)
                        }.verticalScroll(scrollState)
                        .requestFocusOnHierarchyActive()
                        .rotaryScrollable(
                            RotaryScrollableDefaults.behavior(scrollableState = scrollState),
                            focusRequester = focusRequester,
                        ).clickable(
                            enabled = !state.revealed,
                            interactionSource = null,
                            indication = null,
                            onClick = onReveal,
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
                SwipeHints()
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
                    onClick = onReveal,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    buttonSize = EdgeButtonSize.ExtraSmall,
                ) {
                    Text(stringResource(R.string.show_answer))
                }
            }
        }
    }
}

/** The answer that letting go would give: past the threshold on either side. */
private fun Float.ratingAt(threshold: Float): WatchRating? =
    when {
        this <= -threshold -> WatchRating.AGAIN
        this >= threshold -> WatchRating.GOOD
        else -> null
    }

/**
 * Tints the screen red (swiping left, Again) or green (swiping right, Good) and names the answer
 * on the side the card moves away from. [progress] is -1..1 at the threshold.
 */
@Composable
private fun BoxScope.SwipeFeedback(
    progress: Float,
    armed: WatchRating?,
) {
    if (progress == 0f) return
    val isAgain = progress < 0
    val strength = abs(progress).coerceAtMost(1f)
    Box(
        Modifier
            .matchParentSize()
            .background((if (isAgain) againColor else goodColor).copy(alpha = 0.45f * strength)),
    )
    Text(
        stringResource(if (isAgain) R.string.again else R.string.good),
        modifier =
            Modifier
                .align(if (isAgain) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 14.dp)
                .graphicsLayer { alpha = strength },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = if (armed != null) FontWeight.Bold else FontWeight.Normal,
    )
}

/**
 * Thin arcs on the screen edge: red on the left (swipe left, Again), green on the right (Good).
 * They sit in the lower half so the right one stays clear of the scroll indicator at 3 o'clock.
 */
@Composable
private fun BoxScope.SwipeHints() {
    Canvas(Modifier.matchParentSize().padding(3.dp)) {
        val strokeWidth = 4.dp.toPx()
        val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
        val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)
        val stroke = Stroke(strokeWidth, cap = StrokeCap.Round)
        // angles in degrees, clockwise from 3 o'clock: about 4-5 o'clock and 7-8 o'clock
        drawArc(goodColor, startAngle = 28f, sweepAngle = 30f, useCenter = false, topLeft, arcSize, style = stroke)
        drawArc(againColor, startAngle = 122f, sweepAngle = 30f, useCenter = false, topLeft, arcSize, style = stroke)
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
