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

import anki.collection.OpChanges
import anki.scheduler.CardAnswer.Rating
import anki.scheduler.copy
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.Consts
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.sched.Counts
import com.ichi2.anki.libanki.sched.CurrentQueueState
import com.ichi2.anki.libanki.sched.DeckNode
import com.ichi2.anki.observability.ChangeManager
import com.ichi2.anki.wear.protocol.WatchCard
import com.ichi2.anki.wear.protocol.WatchCounts
import com.ichi2.anki.wear.protocol.WatchDeck
import com.ichi2.anki.wear.protocol.WatchQueue
import com.ichi2.anki.wear.protocol.WatchRating
import com.ichi2.anki.wear.protocol.WearProtocol
import com.ichi2.anki.wear.protocol.WearRequest
import com.ichi2.anki.wear.protocol.WearResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Serves the AnkiDroid watch app: lists decks, hands out the next card and records answers.
 *
 * Studying on the watch works like studying on the phone: the watch's deck becomes the
 * selected deck, and answers go through the normal scheduler (so undo, sync and stats behave
 * as usual).
 *
 * Transport-independent: `WearBridgeService` (play flavor) passes in what it receives over
 * the Wear OS Data Layer.
 */
object WearRequestHandler {
    suspend fun handle(
        path: String,
        payload: ByteArray,
    ): ByteArray {
        val response =
            try {
                if (path != WearProtocol.RPC_PATH) {
                    Timber.w("Unsupported watch request path: %s", path)
                    WearResponse.Error("Phone and watch apps don't match. Update both.")
                } else {
                    handle(WearProtocol.decodeRequest(payload))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Watch request failed")
                WearResponse.Error(e.message ?: e.javaClass.simpleName)
            }
        return WearProtocol.encode(response)
    }

    suspend fun handle(request: WearRequest): WearResponse =
        when (request) {
            WearRequest.GetDecks -> withCol { deckList() }
            is WearRequest.NextCard -> withCol { studyState(request.deckId) }
            is WearRequest.Answer -> answer(request)
        }

    private suspend fun answer(request: WearRequest.Answer): WearResponse {
        val (response, changes) =
            withCol<Pair<WearResponse, OpChanges?>> {
                val state = queueStateFor(request.deckId)
                if (state == null || state.topCard.id != request.cardId) {
                    Timber.i("Ignoring watch answer for card %d: no longer the next card", request.cardId)
                    return@withCol studyState(request.deckId, answerSkipped = true) to null
                }
                val card = state.topCard
                val rating =
                    when (request.rating) {
                        WatchRating.AGAIN -> Rating.AGAIN
                        WatchRating.GOOD -> Rating.GOOD
                    }
                val answer =
                    sched.buildAnswer(card, state.states, rating).copy {
                        // capped like on the phone (deck option "Maximum answer seconds")
                        millisecondsTaken = request.millisTaken.coerceIn(0L, card.timeLimit(this@withCol).toLong()).toInt()
                    }
                val changes = sched.answerCard(answer)
                studyState(request.deckId) to changes
            }
        if (changes != null) {
            notifyPhoneUi(changes)
        }
        return response
    }

    /** Refresh the phone's screens (deck list, reviewer, widgets) as after any review. */
    private suspend fun notifyPhoneUi(changes: OpChanges) =
        withContext(Dispatchers.Main) {
            ChangeManager.notifySubscribers(changes, this@WearRequestHandler)
        }

    private fun Collection.deckList(): WearResponse.Decks {
        val result = mutableListOf<WatchDeck>()

        fun visit(node: DeckNode) {
            val isHiddenDefaultDeck =
                node.did == Consts.DEFAULT_DECK_ID && node.children.isEmpty() && decks.isEmpty(node.did)
            if (!isHiddenDefaultDeck) {
                result.add(
                    WatchDeck(
                        id = node.did,
                        name = node.lastDeckNameComponent,
                        depth = node.depth,
                        counts = WatchCounts(new = node.newCount, learn = node.lrnCount, review = node.revCount),
                    ),
                )
            }
            if (!node.collapsed) {
                node.children.forEach(::visit)
            }
        }

        sched.deckDueTree().children.forEach(::visit)
        return WearResponse.Decks(result)
    }

    /** Select [deckId] (like tapping it in the deck list) and return the top of its queue. */
    private fun Collection.queueStateFor(deckId: DeckId): CurrentQueueState? {
        require(decks.have(deckId)) { "Deck not found. Go back and refresh the deck list." }
        if (decks.selected() != deckId) {
            decks.select(deckId)
        }
        return sched.currentQueueState()
    }

    private fun Collection.studyState(
        deckId: DeckId,
        answerSkipped: Boolean = false,
    ): WearResponse.Study {
        val state = queueStateFor(deckId)
        val deckName = decks.name(deckId)
        if (state == null) {
            return WearResponse.Study(deckName, WatchCounts(0, 0, 0), card = null, answerSkipped = answerSkipped)
        }
        val card = state.topCard
        val rendered = card.renderOutput(this, reload = true)
        val formatted = WatchCardFormatter.format(rendered.questionText, rendered.answerText)
        // [again, hard, good, easy]
        val intervals = sched.describeNextStates(state.states)
        return WearResponse.Study(
            deckName = deckName,
            counts = WatchCounts(new = state.counts.new, learn = state.counts.lrn, review = state.counts.rev),
            card =
                WatchCard(
                    id = card.id,
                    question = formatted.question,
                    answer = formatted.answer,
                    answerIncludesQuestion = formatted.answerIncludesQuestion,
                    againInterval = intervals.getOrElse(0) { "" },
                    goodInterval = intervals.getOrElse(2) { "" },
                    queue =
                        when (state.countsIndex) {
                            Counts.Queue.NEW -> WatchQueue.NEW
                            Counts.Queue.LRN -> WatchQueue.LEARN
                            Counts.Queue.REV -> WatchQueue.REVIEW
                        },
                ),
            answerSkipped = answerSkipped,
        )
    }
}
