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

import android.app.RemoteInput
import android.content.Intent
import android.view.HapticFeedbackConstants
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.input.WearableRemoteInputExtender
import com.ichi2.anki.wear.AddWordState
import com.ichi2.anki.wear.AddWordViewModel
import com.ichi2.anki.wear.DeckChoices
import com.ichi2.anki.wear.R
import com.ichi2.anki.wear.asString
import com.ichi2.anki.wear.protocol.WatchDeck

private const val WORD_INPUT_KEY = "word"

/**
 * Asks for a word with the system input screen, shows what the phone found for it, and adds it.
 * Going back from any step returns to the deck list.
 */
@Composable
fun AddWordScreen(
    viewModel: AddWordViewModel,
    onChooseDeck: () -> Unit,
    /** A word was added or updated: the deck list syncs when shown again. */
    onWordAdded: () -> Unit,
    onBack: () -> Unit,
) {
    val view = LocalView.current
    val inputLabel = stringResource(R.string.add_word_input_label)
    val inputIntent = remember(inputLabel) { wordInputIntent(inputLabel) }
    val inputLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val text =
                result.data
                    ?.let { RemoteInput.getResultsFromIntent(it) }
                    ?.getCharSequence(WORD_INPUT_KEY)
                    ?.toString()
            viewModel.onInputClosed(text)
            if (text.isNullOrBlank()) onBack()
        }

    val state = viewModel.state
    LaunchedEffect(state) {
        when (state) {
            AddWordState.Typing ->
                if (!viewModel.isInputOpen) {
                    viewModel.onInputOpened()
                    inputLauncher.launch(inputIntent)
                }
            is AddWordState.Added -> {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                onWordAdded()
            }
            else -> {}
        }
    }

    when (state) {
        AddWordState.Typing -> LoadingScreen()
        is AddWordState.LookingUp -> LoadingScreen(stringResource(R.string.looking_up, state.word))
        is AddWordState.Preview ->
            PreviewScreen(
                state = state,
                onChooseDeck = onChooseDeck,
                onTypeAgain = viewModel::typeAgain,
                onAdd = viewModel::add,
            )
        is AddWordState.Added ->
            MessageScreen(
                title = stringResource(if (state.result.updatedNotes > 0) R.string.word_updated else R.string.word_added),
                message = stringResource(R.string.word_added_message, state.result.word, state.result.deckName.shortDeckName()),
                buttonText = stringResource(R.string.add_another),
                onButtonClick = viewModel::typeAgain,
            )
        is AddWordState.Failed ->
            MessageScreen(
                title = stringResource(R.string.add_failed_title),
                message = state.message.asString(),
                buttonText = stringResource(R.string.retry),
                onButtonClick = viewModel::retry,
            )
    }
}

/**
 * The word, its meanings as they'll appear on the card, the deck, and Add (or Update). Styled
 * like the study screen: the word centered, the meanings left-aligned in the condensed font.
 */
@VisibleForTesting
@Composable
internal fun PreviewScreen(
    state: AddWordState.Preview,
    onChooseDeck: () -> Unit,
    onTypeAgain: () -> Unit,
    onAdd: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val word = state.word
    val meanings = remember(word.preview) { AnnotatedString.fromHtml(word.preview) }
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp

    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(onClick = onAdd, enabled = !state.adding, buttonSize = EdgeButtonSize.Small) {
                Text(stringResource(if (state.isUpdate) R.string.update else R.string.add))
            }
        },
    ) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                ListHeader(
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                ) {
                    Text(word.word, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge)
                }
            }
            item {
                Text(
                    meanings,
                    // on top of the list's own padding: about the study screen's margins, with
                    // more room on the left so lines start clear of the round edge
                    modifier = Modifier.fillMaxWidth().padding(start = screenWidth * 0.10f, end = screenWidth * 0.06f),
                    textAlign = TextAlign.Start,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = CondensedFontFamily),
                )
            }
            item {
                if (state.isUpdate) {
                    Text(
                        stringResource(R.string.already_in_deck, word.existingDeckName.orEmpty().shortDeckName()),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Button(
                        onClick = onChooseDeck,
                        enabled = !state.adding,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        label = { Text(stringResource(R.string.deck)) },
                        secondaryLabel = { Text(state.deckName.shortDeckName(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            item {
                Button(
                    onClick = onTypeAgain,
                    enabled = !state.adding,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    colors = ButtonDefaults.childButtonColors(),
                    label = { Text(stringResource(R.string.type_again)) },
                )
            }
        }
    }
}

/** The decks, indented like the deck list; tapping one picks it for the word. */
@Composable
fun ChooseDeckScreen(
    viewModel: AddWordViewModel,
    onChosen: () -> Unit,
) {
    LaunchedEffect(Unit) { viewModel.loadDeckChoices() }
    val selectedDeckId = (viewModel.state as? AddWordState.Preview)?.deckId

    when (val choices = viewModel.deckChoices) {
        DeckChoices.Loading -> LoadingScreen()
        is DeckChoices.Failed ->
            MessageScreen(
                title = stringResource(R.string.error_title),
                message = choices.message.asString(),
                buttonText = stringResource(R.string.retry),
                onButtonClick = viewModel::loadDeckChoices,
            )
        is DeckChoices.Loaded ->
            DeckChoiceList(choices.decks, selectedDeckId) { deck ->
                viewModel.chooseDeck(deck)
                onChosen()
            }
    }
}

@Composable
private fun DeckChoiceList(
    decks: List<WatchDeck>,
    selectedDeckId: Long?,
    onClick: (WatchDeck) -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                ListHeader(
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                ) {
                    Text(stringResource(R.string.choose_deck))
                }
            }
            items(decks, key = { it.id }) { deck ->
                Button(
                    onClick = { onClick(deck) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = (deck.depth * 12).dp)
                            .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    colors =
                        if (deck.id == selectedDeckId) {
                            ButtonDefaults.buttonColors()
                        } else {
                            ButtonDefaults.filledTonalButtonColors()
                        },
                    label = { Text(deck.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}

/** Opens the system input screen: keyboard, handwriting or voice, as the watch offers. */
private fun wordInputIntent(label: String): Intent {
    val remoteInput =
        WearableRemoteInputExtender(RemoteInput.Builder(WORD_INPUT_KEY).setLabel(label))
            .setEmojisAllowed(false)
            .setInputActionType(EditorInfo.IME_ACTION_SEARCH)
            .get()
            .build()
    return RemoteInputIntentHelper.createActionRemoteInputIntent().also { intent ->
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
        RemoteInputIntentHelper.putTitleExtra(intent, label)
    }
}

/** `Vocab` for `English::Vocab`. */
private fun String.shortDeckName(): String = substringAfterLast("::")
