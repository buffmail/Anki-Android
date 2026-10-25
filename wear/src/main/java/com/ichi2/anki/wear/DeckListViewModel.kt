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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ichi2.anki.wear.protocol.SyncStatus
import com.ichi2.anki.wear.protocol.WatchDeck
import com.ichi2.anki.wear.protocol.WearRequest
import com.ichi2.anki.wear.protocol.WearResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface DeckListState {
    data object Loading : DeckListState

    data class Loaded(
        val decks: List<WatchDeck>,
    ) : DeckListState

    data class Failed(
        val message: UiMessage,
    ) : DeckListState
}

sealed interface SyncState {
    data object Idle : SyncState

    data object Syncing : SyncState

    /** The last sync didn't complete; [message] says why. */
    data class Problem(
        val message: UiMessage,
    ) : SyncState
}

class DeckListViewModel(
    private val phone: PhoneClient,
) : ViewModel() {
    var state: DeckListState by mutableStateOf(DeckListState.Loading)
        private set

    var syncState: SyncState by mutableStateOf(SyncState.Idle)
        private set

    private var job: Job? = null
    private var syncOnResume = false

    /** Called when a deck is opened: coming back to the list then syncs with AnkiWeb. */
    fun onStudyStarted() {
        syncOnResume = true
    }

    /** The deck list is shown again: sync if coming back from studying, then reload. */
    fun onResume() {
        if (syncOnResume) {
            syncOnResume = false
            syncAndRefresh()
        } else {
            refresh()
        }
    }

    /** Reload the decks and their counts. The current list stays visible meanwhile. */
    fun refresh() {
        if (job?.isActive == true) return
        job = viewModelScope.launch { loadDecks() }
    }

    private fun syncAndRefresh() {
        job?.cancel()
        job =
            viewModelScope.launch {
                syncState = SyncState.Syncing
                syncState =
                    phone
                        .sendForResult<WearResponse.Synced>(WearRequest.Sync, PhoneClient.SYNC_TIMEOUT_MILLIS)
                        .fold(
                            onSuccess = { it.status.toSyncState() },
                            onFailure = { SyncState.Problem(it.uiMessage) },
                        )
                loadDecks()
            }
    }

    private suspend fun loadDecks() {
        if (state !is DeckListState.Loaded) state = DeckListState.Loading
        state =
            phone.sendForResult<WearResponse.Decks>(WearRequest.GetDecks).fold(
                onSuccess = { DeckListState.Loaded(it.decks) },
                onFailure = { DeckListState.Failed(it.uiMessage) },
            )
    }

    private fun SyncStatus.toSyncState(): SyncState =
        when (this) {
            SyncStatus.DONE -> SyncState.Idle
            SyncStatus.FAILED -> SyncState.Problem(UiMessage.Resource(R.string.sync_failed))
            SyncStatus.STILL_RUNNING -> SyncState.Problem(UiMessage.Resource(R.string.sync_still_running))
            SyncStatus.NOT_LOGGED_IN -> SyncState.Problem(UiMessage.Resource(R.string.sync_not_logged_in))
            SyncStatus.OFFLINE -> SyncState.Problem(UiMessage.Resource(R.string.sync_offline))
        }
}
