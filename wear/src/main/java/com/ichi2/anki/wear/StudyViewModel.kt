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
import kotlinx.coroutines.launch

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

    private fun send(request: WearRequest) {
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
