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
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.CurvedModifier
import androidx.wear.compose.foundation.padding
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.curvedText
import androidx.wear.compose.material3.timeTextCurvedText
import androidx.wear.compose.material3.timeTextSeparator
import com.ichi2.anki.wear.R
import com.ichi2.anki.wear.StudyState
import com.ichi2.anki.wear.StudyViewModel
import com.ichi2.anki.wear.asString
import com.ichi2.anki.wear.protocol.WatchCard
import com.ichi2.anki.wear.protocol.WatchCounts
import com.ichi2.anki.wear.protocol.WatchQueue
import com.ichi2.anki.wear.protocol.WatchRating
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val againColor = Color(0xFFC62828)
private val goodColor = Color(0xFF2E7D32)

/** How long the screen stays on after the last tap. Longer than the watch's own timeout. */
private const val KEEP_SCREEN_ON_MILLIS = 60_000L

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

    ScreenScaffold(
        scrollState = scrollState,
        timeText = { CountsTimeText(state.counts, card.queue) },
    ) { _ ->
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
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
                // room for the buttons at the bottom edge
                Spacer(Modifier.height(screenWidth * 0.36f))
            }

            // fades out text that scrolls behind the buttons
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(screenWidth * 0.36f)
                    .background(Brush.verticalGradient(0f to Color.Transparent, 0.4f to Color.Black)),
            )
            if (state.revealed) {
                AnswerButtons(card, enabled = !state.sending, onAnswer = onAnswer, screenWidth = screenWidth)
            } else {
                EdgeButton(
                    onClick = onReveal,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    buttonSize = EdgeButtonSize.Small,
                ) {
                    Text(stringResource(R.string.show_answer))
                }
            }
        }
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

@Composable
private fun BoxScope.AnswerButtons(
    card: WatchCard,
    enabled: Boolean,
    onAnswer: (WatchRating) -> Unit,
    screenWidth: Dp,
) {
    val view = LocalView.current
    // sized to stay inside the round screen's bottom edge
    Row(
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = screenWidth * 0.065f)
                .fillMaxWidth(0.64f),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AnswerButton(
            label = stringResource(R.string.again),
            interval = card.againInterval,
            color = againColor,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        ) {
            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
            onAnswer(WatchRating.AGAIN)
        }
        AnswerButton(
            label = stringResource(R.string.good),
            interval = card.goodInterval,
            color = goodColor,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        ) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            onAnswer(WatchRating.GOOD)
        }
    }
}

@Composable
private fun AnswerButton(
    label: String,
    interval: String,
    color: Color,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Text(interval, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/** The clock, followed by the new / learning / review counts. The current card's queue is bold. */
@Composable
private fun CountsTimeText(
    counts: WatchCounts,
    queue: WatchQueue,
) {
    fun weight(of: WatchQueue) = if (of == queue) FontWeight.Bold else FontWeight.Normal
    TimeText { time ->
        timeTextCurvedText(time)
        timeTextSeparator()
        curvedText(counts.new.toString(), color = CountColors.new, fontWeight = weight(WatchQueue.NEW))
        // curved text drops surrounding spaces: use padding instead
        curvedText(
            counts.learn.toString(),
            modifier = CurvedModifier.padding(angular = 4.dp),
            color = CountColors.learn,
            fontWeight = weight(WatchQueue.LEARN),
        )
        curvedText(counts.review.toString(), color = CountColors.review, fontWeight = weight(WatchQueue.REVIEW))
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
