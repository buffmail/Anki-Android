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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.ichi2.anki.wear.EtymologyState
import com.ichi2.anki.wear.R
import com.ichi2.anki.wear.StudyState
import com.ichi2.anki.wear.StudyViewModel
import com.ichi2.anki.wear.asString
import com.ichi2.anki.wear.protocol.WatchCounts
import com.ichi2.anki.wear.protocol.WatchRating
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.acos
import kotlin.math.roundToInt

private val againColor = Color(0xFFC62828)
private val goodColor = Color(0xFF2E7D32)

/** Space left and right of the card text, as a fraction of the screen width. */
private const val TEXT_SIDE_FRACTION = 0.12f

/**
 * Extra space before the answer, which is left-aligned: keeps its lines clear of the red hint,
 * and less cut off by the round screen near the top and bottom.
 */
private const val ANSWER_EXTRA_START_FRACTION = 0.04f

/**
 * The answer hints stop this far (as a fraction of the screen width) from the screen's rim,
 * leaving the rim to the scroll indicator.
 */
private const val HINT_RIM_GAP_FRACTION = 0.04f

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
fun StudyScreen(
    viewModel: StudyViewModel,
    /** A word held on the card is to be added as a new card. */
    onAddWord: (String) -> Unit,
) {
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
            KeepScreenOn(key = Triple(state.sequence, state.revealed, state.etymology))
            CardScreen(
                state,
                onReveal = viewModel::reveal,
                onAnswer = viewModel::answer,
                onAddWord = onAddWord,
                onEtymology = viewModel::loadEtymology,
            )
        }
    }
}

/**
 * The card. Tap to show the answer; then tap the left side for Again or the right side for Good.
 * Nothing else takes room from the text: tinted sides show which side is which.
 * Hold a word to add it as a new card: it's highlighted and an Add button appears.
 * Swiping right goes back to the deck list, as everywhere on Wear OS.
 */
@VisibleForTesting
@Composable
internal fun CardScreen(
    state: StudyState.Showing,
    onReveal: () -> Unit,
    onAnswer: (WatchRating) -> Unit,
    onAddWord: (String) -> Unit = {},
    onEtymology: () -> Unit = {},
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

    // After revealing, scroll so that the answer starts near the top.
    var answerTop by remember(state.sequence) { mutableStateOf<Int?>(null) }
    LaunchedEffect(answerTop) {
        val top = answerTop ?: return@LaunchedEffect
        val margin = with(density) { (screenWidth * 0.15f).roundToPx() }
        scrollState.animateScrollTo((top - margin).coerceAtLeast(0))
    }

    var etymologyTop by remember(state.sequence) { mutableStateOf<Int?>(null) }
    LaunchedEffect(state.sequence, state.etymology, etymologyTop) {
        val top = etymologyTop ?: return@LaunchedEffect
        if (state.etymology != EtymologyState.Idle) {
            val margin = with(density) { (screenWidth * 0.15f).roundToPx() }
            scrollState.animateScrollTo((top - margin).coerceAtLeast(0))
        }
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

    // A word held on the card, offered as a new card. Cleared when the card changes.
    var heldWord by remember { mutableStateOf<HeldWord?>(null) }
    LaunchedEffect(state.sequence) { heldWord = null }
    // where the card's text is laid out, to find the word under a finger
    val placements = remember { TextPlacements() }

    fun hold(position: Offset) {
        val word = placements.wordAt(position) ?: return
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        heldWord = word
    }

    fun tap(
        position: Offset,
        uptimeMillis: Long,
        width: Float,
    ) {
        when {
            // the first tap only lets go of the held word, so it can't answer by accident
            heldWord != null -> heldWord = null
            !state.revealed -> reveal(uptimeMillis)
            state.sending -> {}
            uptimeMillis - revealedAt.longValue < ANSWER_LOCK_MILLIS -> {}
            else -> answerAt(position.x, width)?.let(::answer)
        }
    }

    // Before the answer is shown a tap anywhere shows it, then the sides answer; a hold picks
    // a word. Buttons consume their touches before this handler can rate the card.
    val tapWith by rememberUpdatedState<(Offset, Long, Float) -> Unit> { position, time, width -> tap(position, time, width) }
    val holdWith by rememberUpdatedState<(Offset) -> Unit> { hold(it) }

    ScreenScaffold(
        scrollState = scrollState,
        // nothing pinned at the top: it would sit on the text whenever scrolling stops
        timeText = {},
    ) { _ ->
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { placements.card = it }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val holdMillis = viewConfiguration.longPressTimeoutMillis
                        // Follow the finger until it lifts, at most for the hold time.
                        var up: PointerInputChange? = null
                        var ended = false
                        val held =
                            withTimeoutOrNull(holdMillis) {
                                while (!ended) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                    when {
                                        // the card scrolled, or a button (Show, Add, Etymology) took the touch
                                        change == null || change.isConsumed -> ended = true
                                        // a swipe (going back, or old habit) is neither a tap nor a hold
                                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop -> ended = true
                                        !change.pressed -> {
                                            up = change
                                            ended = true
                                        }
                                    }
                                }
                            } == null
                        val lifted = up
                        // also checked on lifting, in case the timer runs late
                        if (held || (lifted != null && lifted.uptimeMillis - down.uptimeMillis >= holdMillis)) {
                            holdWith(down.position)
                            if (held) {
                                // the rest of this touch belongs to the hold: no scrolling, no answer
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                            }
                            return@awaitEachGesture
                        }
                        if (lifted == null) return@awaitEachGesture
                        lifted.consume()
                        tapWith(lifted.position, lifted.uptimeMillis, size.width.toFloat())
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
                        ).padding(horizontal = screenWidth * TEXT_SIDE_FRACTION),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CardsLeft(state.counts, Modifier.fillMaxWidth().height(screenWidth * 0.2f))
                // The question (usually a word) is centered under the clock; the answer reads like
                // a list, so it's left-aligned and starts a little further in.
                val answerModifier = Modifier.padding(start = screenWidth * ANSWER_EXTRA_START_FRACTION)
                val held = heldWord
                if (state.revealed && card.answerIncludesQuestion) {
                    CardText(card.answer, TextAlign.Start, placements.of(CardPart.ANSWER), held?.rangeIn(CardPart.ANSWER), answerModifier)
                } else {
                    CardText(card.question, TextAlign.Center, placements.of(CardPart.QUESTION), held?.rangeIn(CardPart.QUESTION))
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
                    CardText(card.answer, TextAlign.Start, placements.of(CardPart.ANSWER), held?.rangeIn(CardPart.ANSWER), answerModifier)
                }
                if (state.revealed) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp)
                                .onGloballyPositioned { etymologyTop = it.positionInParent().y.roundToInt() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        EtymologySection(state.etymology, enabled = !state.sending) {
                            heldWord = null
                            onEtymology()
                        }
                    }
                }
                // keeps the last line out of the round screen's narrow bottom
                Spacer(Modifier.height(screenWidth * if (state.revealed) 0.15f else 0.26f))
            }

            if (state.revealed) {
                AnswerHints()
            }
            val held = heldWord
            if (!state.revealed || held != null) {
                // fades out text that scrolls behind the button
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(screenWidth * 0.26f)
                        .background(Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.Black)),
                )
            }
            if (held != null) {
                EdgeButton(
                    onClick = {
                        heldWord = null
                        onAddWord(held.word)
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                    buttonSize = EdgeButtonSize.Small,
                ) {
                    AddWordLabel(held.word)
                }
            } else if (!state.revealed) {
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

/** Placed after the answer, so the action is reached by scrolling to the end of the card. */
@Composable
private fun EtymologySection(
    state: EtymologyState,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    if (state != EtymologyState.Idle) {
        Text(stringResource(R.string.etymology), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
    }
    when (state) {
        EtymologyState.Idle ->
            Button(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(),
                label = { Text(stringResource(R.string.etymology)) },
            )
        EtymologyState.Loading ->
            Text(stringResource(R.string.loading_etymology), style = MaterialTheme.typography.bodyMedium)
        is EtymologyState.Loaded ->
            Text(
                state.explanation,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = CondensedFontFamily),
                textAlign = TextAlign.Start,
            )
        is EtymologyState.Failed -> {
            Text(
                state.message.asString(),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Start,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(),
                label = { Text(stringResource(R.string.retry)) },
            )
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
 * Tints the screen outside the card text: red on the left (tap there for Again), green on the
 * right (Good). On the round screen these are two half-moons centered on 9 and 3 o'clock; they
 * never overlap the text, and stop short of the rim so the scroll indicator stays on black.
 * They only hint at the sides: the tap areas are the wider bands of [ANSWER_BAND_FRACTION].
 */
@Composable
private fun BoxScope.AnswerHints() {
    Canvas(Modifier.matchParentSize()) {
        val center = this.center
        val radius = size.minDimension / 2 - size.width * HINT_RIM_GAP_FRACTION
        // distance from the center to the edge of the text, horizontally
        val textHalfWidth = size.width / 2 - size.width * TEXT_SIDE_FRACTION
        if (textHalfWidth >= radius) return@Canvas
        // where the edge of the text meets the circle, as an angle from 3 o'clock
        val halfAngle = Math.toDegrees(acos(textHalfWidth / radius).toDouble()).toFloat()
        val circle = Rect(center, radius)

        val good =
            Path().apply {
                arcTo(circle, startAngleDegrees = -halfAngle, sweepAngleDegrees = 2 * halfAngle, forceMoveTo = true)
                close()
            }
        val again =
            Path().apply {
                arcTo(circle, startAngleDegrees = 180 - halfAngle, sweepAngleDegrees = 2 * halfAngle, forceMoveTo = true)
                close()
            }
        drawPath(good, goodColor.copy(alpha = 0.55f))
        drawPath(again, againColor.copy(alpha = 0.5f))
    }
}

/** "Add **word**", shrunk to fit the button rather than cut off when the word is long. */
@Composable
private fun AddWordLabel(word: String) {
    val style = MaterialTheme.typography.labelLarge
    val label = stringResource(R.string.add_held_word, word)
    val text =
        remember(label, word) {
            buildAnnotatedString {
                append(label)
                val start = label.indexOf(word)
                if (start >= 0) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, start + word.length)
            }
        }
    BasicText(
        text = text,
        style = style.copy(color = LocalContentColor.current),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = style.fontSize),
    )
}

/** The question or the answer: the two pieces of card text a word can be held in. */
private enum class CardPart { QUESTION, ANSWER }

/** A word held on the card: where it is, to highlight it, and the word itself. */
private data class HeldWord(
    val part: CardPart,
    val range: TextRange,
    val word: String,
) {
    fun rangeIn(part: CardPart): TextRange? = range.takeIf { this.part == part }
}

/** Where a piece of card text was laid out on screen. */
private class PlacedText {
    var layout: TextLayoutResult? = null
    var coordinates: LayoutCoordinates? = null
}

/** Where the card's text is laid out, to find the word under a finger. */
private class TextPlacements {
    /** The whole card: touch positions are relative to it. */
    var card: LayoutCoordinates? = null
    private val texts = mutableMapOf<CardPart, PlacedText>()

    fun of(part: CardPart): PlacedText = texts.getOrPut(part) { PlacedText() }

    /** The word at [position] on the card, if a word of the shown text is there. */
    fun wordAt(position: Offset): HeldWord? {
        val card = card ?: return null
        return texts.firstNotNullOfOrNull { (part, placed) ->
            placed.wordAt(card, position)?.let { (range, word) -> HeldWord(part, range, word) }
        }
    }

    private fun PlacedText.wordAt(
        card: LayoutCoordinates,
        position: Offset,
    ): Pair<TextRange, String>? {
        val layout = layout ?: return null
        val coordinates = coordinates?.takeIf { it.isAttached && card.isAttached } ?: return null
        val local = coordinates.localPositionOf(card, position)
        if (local.y < 0 || local.y > layout.size.height) return null
        // beside a line (left-aligned text ends early) isn't on a word
        val line = layout.getLineForVerticalPosition(local.y)
        if (local.x < layout.getLineLeft(line) || local.x > layout.getLineRight(line)) return null
        val text = layout.layoutInput.text.text
        val boundary = layout.getWordBoundary(layout.getOffsetForPosition(local))
        var start = boundary.min
        var end = boundary.max
        while (start < end && !text[start].isLetterOrDigit()) start++
        while (end > start && !text[end - 1].isLetterOrDigit()) end--
        val word = text.substring(start, end)
        // nothing to look up: punctuation, a number, or the pronunciation (IPA, like /ˈkʌbərd/)
        if (word.none { it.isLetter() } || word.any { it.code in IPA_CODE_POINTS }) return null
        return TextRange(start, end) to word
    }

    private companion object {
        /** IPA Extensions and Spacing Modifier Letters (ə, ʌ, ˈ, ː, ...). */
        val IPA_CODE_POINTS = 0x250..0x2FF
    }
}

/**
 * Card text: large when it's short (a word or two), otherwise body text in the condensed font.
 * [highlight] marks a held word; where the text lands on screen is recorded in [placed].
 */
@Composable
private fun CardText(
    html: String,
    align: TextAlign,
    placed: PlacedText,
    highlight: TextRange?,
    modifier: Modifier = Modifier,
) {
    val base = remember(html) { AnnotatedString.fromHtml(html) }
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val text =
        when {
            base.isBlank() -> AnnotatedString(stringResource(R.string.empty_card))
            highlight == null -> base
            else ->
                remember(base, highlight, highlightColor) {
                    buildAnnotatedString {
                        append(base)
                        addStyle(SpanStyle(background = highlightColor), highlight.start, highlight.end)
                    }
                }
        }
    val style =
        if (base.length <= 40) {
            MaterialTheme.typography.titleLarge
        } else {
            MaterialTheme.typography.bodyLarge.copy(fontFamily = CondensedFontFamily)
        }
    Text(
        text = text,
        modifier = modifier.fillMaxWidth().onGloballyPositioned { placed.coordinates = it },
        textAlign = align,
        style = style,
        // "(empty)" isn't a word to add
        onTextLayout = { placed.layout = if (base.isBlank()) null else it },
    )
}

/**
 * The number of cards left to study today, bold in the accent color, above the card. It's part of
 * the card, so it scrolls away with it and never sits on top of the text. No clock while
 * studying: it would only take attention from the card.
 */
@Composable
private fun CardsLeft(
    counts: WatchCounts,
    modifier: Modifier,
) {
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        Text(
            counts.total.toString(),
            modifier = Modifier.padding(top = 10.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
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
