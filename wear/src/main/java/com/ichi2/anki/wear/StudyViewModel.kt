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

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ichi2.anki.wear.protocol.WatchCard
import com.ichi2.anki.wear.protocol.WatchCounts
import com.ichi2.anki.wear.protocol.WatchRating
import com.ichi2.anki.wear.protocol.WearRequest
import com.ichi2.anki.wear.protocol.WearResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface EtymologyState {
    data object Idle : EtymologyState

    data object Loading : EtymologyState

    data class Loaded(
        val explanation: String,
    ) : EtymologyState

    data class Failed(
        val message: UiMessage,
    ) : EtymologyState
}

sealed interface StudyState {
    data object Loading : StudyState

    data class Showing(
        val deckName: String,
        val counts: WatchCounts,
        val card: WatchCard,
        val revealed: Boolean,
        /** An answer is on its way to the phone. */
        val sending: Boolean,
        /** Changes for every card shown, even if the same card comes back (Again). */
        val sequence: Int,
        val etymology: EtymologyState = EtymologyState.Idle,
    ) : StudyState

    data class Finished(
        val deckName: String,
    ) : StudyState

    data class Failed(
        val message: UiMessage,
    ) : StudyState
}

class StudyViewModel(
    private val phone: PhoneClient,
    private val deckId: Long,
) : ViewModel() {
    var state: StudyState by mutableStateOf(StudyState.Loading)
        private set

    private var shownAt = 0L
    private var sequence = 0
    private var etymologyJob: Job? = null

    init {
        reload()
    }

    fun reload() = send(WearRequest.NextCard(deckId))

    fun reveal() {
        val current = state as? StudyState.Showing ?: return
        if (!current.revealed) state = current.copy(revealed = true)
    }

    fun answer(rating: WatchRating) {
        val current = state as? StudyState.Showing ?: return
        if (!current.revealed || current.sending) return
        state = current.copy(sending = true)
        send(
            WearRequest.Answer(
                deckId = deckId,
                cardId = current.card.id,
                rating = rating,
                millisTaken = SystemClock.elapsedRealtime() - shownAt,
            ),
        )
    }

    fun loadEtymology() {
        val current = state as? StudyState.Showing ?: return
        if (!current.revealed ||
            current.sending ||
            current.etymology is EtymologyState.Loading ||
            current.etymology is EtymologyState.Loaded
        ) {
            return
        }
        state = current.copy(etymology = EtymologyState.Loading)
        etymologyJob =
            viewModelScope.launch {
                val result =
                    phone.sendForResult<WearResponse.Etymology>(
                        WearRequest.GetEtymology(current.card.id),
                        PhoneClient.LOOKUP_TIMEOUT_MILLIS,
                    )
                val latest = state as? StudyState.Showing ?: return@launch
                // A late reply must never attach to the next card, even if Again brings back the same ID.
                if (latest.sequence != current.sequence || latest.sending) return@launch
                val etymology =
                    result.fold(
                        onSuccess = {
                            if (it.cardId == current.card.id) {
                                EtymologyState.Loaded(it.explanation)
                            } else {
                                EtymologyState.Failed(UiMessage.Resource(R.string.error_app_mismatch))
                            }
                        },
                        onFailure = { EtymologyState.Failed(it.uiMessage) },
                    )
                state = latest.copy(etymology = etymology)
            }
    }

    private fun send(request: WearRequest) {
        etymologyJob?.cancel()
        viewModelScope.launch {
            if (state !is StudyState.Showing) state = StudyState.Loading
            state =
                phone.sendForResult<WearResponse.Study>(request).fold(
                    onSuccess = { it.toState() },
                    onFailure = { StudyState.Failed(it.uiMessage) },
                )
        }
    }

    private fun WearResponse.Study.toState(): StudyState {
        val card = card ?: return StudyState.Finished(deckName)
        shownAt = SystemClock.elapsedRealtime()
        return StudyState.Showing(deckName, counts, card, revealed = false, sending = false, sequence = ++sequence)
    }
}
