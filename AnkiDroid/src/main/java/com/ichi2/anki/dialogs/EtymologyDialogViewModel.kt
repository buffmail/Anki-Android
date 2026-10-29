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

package com.ichi2.anki.dialogs

import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.wear.EtymologyLookup
import com.ichi2.anki.wear.GeminiApiKey
import com.ichi2.anki.wear.GeminiEtymologyLookup
import com.ichi2.anki.wear.MissingApiKeyException
import com.ichi2.anki.wear.WordLookupException
import com.ichi2.anki.wear.etymologyWord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A lookup belongs to this dialog's card, and survives screen rotation. */
class EtymologyDialogViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    sealed interface State {
        data object Idle : State

        data object Loading : State

        data class Loaded(
            val word: String,
            val explanation: String,
        ) : State

        data class Failed(
            @StringRes val messageRes: Int,
            val details: String? = null,
            val canRetry: Boolean = true,
        ) : State
    }

    @VisibleForTesting
    var lookup: EtymologyLookup = GeminiEtymologyLookup { GeminiApiKey.get(AnkiDroidApp.instance) }

    private val mutableState =
        MutableStateFlow<State>(
            savedStateHandle.get<String>(KEY_EXPLANATION)?.let {
                State.Loaded(requireNotNull(savedStateHandle[KEY_WORD]), it)
            } ?: State.Idle,
        )
    val state = mutableState.asStateFlow()

    fun load() {
        if (state.value is State.Loading || state.value is State.Loaded) return
        mutableState.value = State.Loading
        viewModelScope.launch {
            mutableState.value =
                try {
                    val word = etymologyWord(requireNotNull(savedStateHandle.get<CardId>(ARG_CARD_ID)))
                    val explanation = lookup.lookUp(word)
                    savedStateHandle[KEY_WORD] = word
                    savedStateHandle[KEY_EXPLANATION] = explanation
                    State.Loaded(word, explanation)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: MissingApiKeyException) {
                    State.Failed(R.string.etymology_no_api_key)
                } catch (e: IllegalArgumentException) {
                    State.Failed(R.string.etymology_invalid_card, canRetry = false)
                } catch (e: WordLookupException) {
                    State.Failed(R.string.etymology_failed, details = e.message)
                } catch (e: Exception) {
                    State.Failed(R.string.etymology_failed)
                }
        }
    }

    companion object {
        const val ARG_CARD_ID = "etymology_card_id"
        private const val KEY_WORD = "etymology_word"
        private const val KEY_EXPLANATION = "etymology_explanation"
    }
}
