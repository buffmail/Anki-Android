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

package com.ichi2.anki.wear

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ichi2.anki.wear.protocol.WatchDeck
import com.ichi2.anki.wear.protocol.WearRequest
import com.ichi2.anki.wear.protocol.WearResponse
import kotlinx.coroutines.launch

sealed interface AddWordState {
    /** The system input screen (keyboard, handwriting, voice) is asking for the word. */
    data object Typing : AddWordState

    data class LookingUp(
        val word: String,
    ) : AddWordState

    data class Preview(
        val word: WearResponse.WordPreview,
        /** Deck to add to: the phone's suggestion until another one is chosen. */
        val deckId: Long,
        val deckName: String,
        /** The note is on its way to the phone. */
        val adding: Boolean = false,
    ) : AddWordState {
        /** The word is already in the collection: adding updates it. */
        val isUpdate: Boolean get() = word.existingNotes > 0
    }

    data class Added(
        val result: WearResponse.WordAdded,
    ) : AddWordState

    data class Failed(
        val message: UiMessage,
    ) : AddWordState
}

sealed interface DeckChoices {
    data object Loading : DeckChoices

    data class Loaded(
        val decks: List<WatchDeck>,
    ) : DeckChoices

    data class Failed(
        val message: UiMessage,
    ) : DeckChoices
}

/**
 * Adds a word to a deck: the phone looks it up with anki-new-card (the watch never sees the
 * Gemini API key), the watch shows what will go on the card, and the phone adds it.
 *
 * The deck added to last is remembered in [prefs] and suggested next time.
 */
class AddWordViewModel(
    private val phone: PhoneClient,
    private val prefs: SharedPreferences,
) : ViewModel() {
    var state: AddWordState by mutableStateOf(AddWordState.Typing)
        private set

    var deckChoices: DeckChoices by mutableStateOf(DeckChoices.Loading)
        private set

    /** The input screen was opened and hasn't returned yet: don't open it twice. */
    var isInputOpen = false
        private set

    private var retryAction: (() -> Unit)? = null

    fun onInputOpened() {
        isInputOpen = true
    }

    /** The input screen returned [text], or `null` if it was cancelled. */
    fun onInputClosed(text: String?) {
        isInputOpen = false
        val word = text?.trim().orEmpty()
        if (word.isNotEmpty()) lookUp(word)
    }

    fun typeAgain() {
        state = AddWordState.Typing
    }

    fun retry() {
        retryAction?.invoke()
    }

    private fun lookUp(word: String) {
        retryAction = { lookUp(word) }
        state = AddWordState.LookingUp(word)
        viewModelScope.launch {
            val request = WearRequest.LookUpWord(word, preferredDeckId = rememberedDeckId())
            state =
                phone.sendForResult<WearResponse.WordPreview>(request, PhoneClient.LOOKUP_TIMEOUT_MILLIS).fold(
                    onSuccess = { AddWordState.Preview(it, it.deckId, it.deckName) },
                    onFailure = { AddWordState.Failed(it.uiMessage) },
                )
        }
    }

    fun loadDeckChoices() {
        deckChoices = DeckChoices.Loading
        viewModelScope.launch {
            deckChoices =
                phone.sendForResult<WearResponse.Decks>(WearRequest.GetDecks).fold(
                    onSuccess = { DeckChoices.Loaded(it.decks) },
                    onFailure = { DeckChoices.Failed(it.uiMessage) },
                )
        }
    }

    fun chooseDeck(deck: WatchDeck) {
        val preview = state as? AddWordState.Preview ?: return
        state = preview.copy(deckId = deck.id, deckName = deck.name)
    }

    fun add() {
        val preview = state as? AddWordState.Preview ?: return
        if (preview.adding) return
        retryAction = {
            state = preview
            add()
        }
        state = preview.copy(adding = true)
        viewModelScope.launch {
            val request = WearRequest.AddWord(preview.deckId, preview.word.word, preview.word.back)
            state =
                phone.sendForResult<WearResponse.WordAdded>(request).fold(
                    onSuccess = {
                        // an update leaves the notes where they are: the deck choice didn't matter
                        if (!preview.isUpdate) rememberDeck(preview.deckId)
                        AddWordState.Added(it)
                    },
                    onFailure = { AddWordState.Failed(it.uiMessage) },
                )
        }
    }

    private fun rememberedDeckId(): Long? = prefs.getLong(PREF_DECK_ID, 0L).takeIf { it != 0L }

    private fun rememberDeck(deckId: Long) = prefs.edit { putLong(PREF_DECK_ID, deckId) }

    companion object {
        const val PREFS_NAME = "add_word"
        private const val PREF_DECK_ID = "deck_id"
    }
}
