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

import androidx.annotation.VisibleForTesting
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import anki.collection.OpChanges
import anki.scheduler.CardAnswer.Rating
import anki.scheduler.copy
import anki.search.SearchNodeKt
import anki.search.searchNode
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.Consts
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.NoteId
import com.ichi2.anki.libanki.sched.Counts
import com.ichi2.anki.libanki.sched.CurrentQueueState
import com.ichi2.anki.libanki.sched.DeckNode
import com.ichi2.anki.observability.ChangeManager
import com.ichi2.anki.shouldFetchMedia
import com.ichi2.anki.syncAuth
import com.ichi2.anki.wear.protocol.SyncStatus
import com.ichi2.anki.wear.protocol.WatchCard
import com.ichi2.anki.wear.protocol.WatchCounts
import com.ichi2.anki.wear.protocol.WatchDeck
import com.ichi2.anki.wear.protocol.WatchQueue
import com.ichi2.anki.wear.protocol.WatchRating
import com.ichi2.anki.wear.protocol.WearProtocol
import com.ichi2.anki.wear.protocol.WearRequest
import com.ichi2.anki.wear.protocol.WearResponse
import com.ichi2.anki.worker.SyncWorker
import com.ichi2.anki.worker.UniqueWorkNames
import com.ichi2.utils.NetworkUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Serves the AnkiDroid watch app: lists decks, hands out the next card, records answers and
 * adds words.
 *
 * Studying on the watch works like studying on the phone: the watch's deck becomes the
 * selected deck, and answers go through the normal scheduler (so undo, sync and stats behave
 * as usual).
 *
 * Words added on the watch are looked up with anki-new-card and saved as Basic notes, like
 * the website and `ank.py` do: the word on the front, lowercase, and its meanings on the back.
 *
 * Transport-independent: `WearBridgeService` (play flavor) passes in what it receives over
 * the Wear OS Data Layer.
 */
object WearRequestHandler {
    /** How long to wait for a sync before replying. The watch waits a little longer. */
    private const val SYNC_WAIT_MILLIS = 45_000L

    /** Note type of vocabulary cards (as in `ank.py`). Existing words are looked for in it. */
    private const val WORD_NOTETYPE = "Basic"
    private const val FRONT = "Front"
    private const val BACK = "Back"

    /** Looks up words added on the watch. Replaced in tests. */
    @VisibleForTesting
    var wordLookup: WordLookup = AnkiNewCardLookup { GeminiApiKey.get(AnkiDroidApp.instance) }

    @VisibleForTesting
    var etymologyLookup: EtymologyLookup = GeminiEtymologyLookup { GeminiApiKey.get(AnkiDroidApp.instance) }

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
            WearRequest.Sync -> sync()
            is WearRequest.LookUpWord -> lookUpWord(request)
            is WearRequest.GetEtymology -> etymology(request)
            is WearRequest.AddWord -> addWord(request)
        }

    private suspend fun etymology(request: WearRequest.GetEtymology): WearResponse {
        val word = etymologyWord(request.cardId)
        // Release the collection before waiting for Gemini, so studying on the phone can continue.
        val explanation =
            try {
                etymologyLookup.lookUp(word)
            } catch (e: MissingApiKeyException) {
                return WearResponse.NoApiKey
            }
        return WearResponse.Etymology(request.cardId, word, explanation)
    }

    /** Looks [WearRequest.LookUpWord.word] up and says whether it's already in the collection. */
    private suspend fun lookUpWord(request: WearRequest.LookUpWord): WearResponse {
        val word = WordCard.normalize(request.word)
        require(word.isNotEmpty()) { "Type a word to look up." }
        val definition =
            try {
                wordLookup.lookUp(word)
            } catch (e: MissingApiKeyException) {
                return WearResponse.NoApiKey
            }
        val back = WordCard.back(definition)
        return withCol {
            val existing = findWordNotes(word)
            val deckId =
                request.preferredDeckId?.takeIf { canAddTo(it) }
                    ?: defaultsForAdding().deckId
            WearResponse.WordPreview(
                word = word,
                back = back,
                preview = WatchCardFormatter.toWatchHtml(back),
                existingNotes = existing.size,
                existingDeckName = existing.firstOrNull()?.let { deckNameOf(it) },
                deckId = deckId,
                deckName = decks.name(deckId),
            )
        }
    }

    /**
     * Adds the word as a new Basic note, or, if notes already have it on the front, updates
     * their back instead (like `ank.py --add`): their deck and scheduling stay as they are.
     */
    private suspend fun addWord(request: WearRequest.AddWord): WearResponse {
        val word = WordCard.normalize(request.word)
        require(word.isNotEmpty()) { "Type a word to add." }
        require(request.back.isNotBlank()) { "Nothing to add. Look the word up again." }
        val (response, changes) =
            withCol<Pair<WearResponse, OpChanges>> {
                val existing = findWordNotes(word)
                if (existing.isNotEmpty()) {
                    val notes =
                        existing.map { id ->
                            getNote(id).apply {
                                setItem(FRONT, word)
                                setItem(BACK, request.back)
                            }
                        }
                    val changes = updateNotes(notes)
                    Timber.i("Watch updated %d note(s)", notes.size)
                    return@withCol WearResponse.WordAdded(word, deckNameOf(existing.first()), notes.size) to changes
                }

                require(canAddTo(request.deckId)) { "Can't add to this deck. Choose another one." }
                val notetype =
                    requireNotNull(notetypes.byName(WORD_NOTETYPE)) {
                        "AnkiDroid on the phone has no \"$WORD_NOTETYPE\" note type."
                    }
                val note = newNote(notetype)
                require(FRONT in note && BACK in note) {
                    "The \"$WORD_NOTETYPE\" note type needs $FRONT and $BACK fields."
                }
                note.setItem(FRONT, word)
                note.setItem(BACK, request.back)
                val changes = addNote(note, request.deckId).changes
                Timber.i("Watch added note %d", note.id)
                WearResponse.WordAdded(word, decks.name(request.deckId), updatedNotes = 0) to changes
            }
        notifyPhoneUi(changes)
        return response
    }

    /**
     * Basic notes with [word] on the front, oldest first. The match is on the field itself and
     * ignores case (unlike Anki's duplicate check, which would treat "Artefact" and "artefact"
     * as different words), as in `ank.py`.
     */
    private fun Collection.findWordNotes(word: String): List<NoteId> {
        val query =
            buildSearchString(
                listOf(
                    searchNode { note = WORD_NOTETYPE },
                    searchNode {
                        field =
                            SearchNodeKt.field {
                                fieldName = FRONT
                                text = word
                            }
                    },
                ),
            )
        return findNotes(query).sorted()
    }

    private fun Collection.deckNameOf(noteId: NoteId): String = decks.name(getNote(noteId).cards(this).first().did)

    private fun Collection.canAddTo(deckId: DeckId): Boolean = decks.have(deckId) && !decks.isFiltered(deckId)

    /**
     * Syncs with AnkiWeb using the same background sync as AnkiDroid's auto sync (one-way syncs
     * are skipped, as they need the user to choose a side), and waits for it to finish.
     */
    private suspend fun sync(): WearResponse.Synced {
        val auth = syncAuth() ?: return WearResponse.Synced(SyncStatus.NOT_LOGGED_IN)
        if (!NetworkUtils.isOnline) return WearResponse.Synced(SyncStatus.OFFLINE)

        val context = AnkiDroidApp.instance
        // joins the sync that is already running, if any
        SyncWorker.start(context, auth, shouldFetchMedia()).await()
        // Runs in the app scope so the phone UI is refreshed even if the watch stops waiting.
        val syncFinished =
            AnkiDroidApp.applicationScope.async {
                val state =
                    WorkManager
                        .getInstance(context)
                        .getWorkInfosForUniqueWorkFlow(UniqueWorkNames.SYNC)
                        .map { infos -> infos.firstOrNull()?.state ?: WorkInfo.State.SUCCEEDED }
                        .first { it.isFinished }
                if (state == WorkInfo.State.SUCCEEDED) {
                    // A background sync doesn't notify the UI by itself: without this, the deck list
                    // keeps showing the "changes to sync" badge set while answering on the watch.
                    withContext(Dispatchers.Main) {
                        ChangeManager.notifySubscribersAllValuesChanged(this@WearRequestHandler)
                    }
                }
                state
            }
        val finalState = withTimeoutOrNull(SYNC_WAIT_MILLIS) { syncFinished.await() }
        Timber.i("Sync requested by the watch: %s", finalState ?: "still running")
        val status =
            when (finalState) {
                null -> SyncStatus.STILL_RUNNING
                WorkInfo.State.SUCCEEDED -> SyncStatus.DONE
                else -> SyncStatus.FAILED
            }
        return WearResponse.Synced(status)
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
        val tree = sched.deckDueTree()
        val cardCounts = totalCardCounts(tree)

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
                        cardCount = cardCounts[node.did] ?: 0,
                    ),
                )
            }
            if (!node.collapsed) {
                node.children.forEach(::visit)
            }
        }

        tree.children.forEach(::visit)
        return WearResponse.Decks(result)
    }

    /**
     * All cards of each deck in [tree], subdecks included, in one query. Like Anki's
     * `decks.cardCount(did, includeSubdecks = true)`, a card moved to a filtered deck still
     * counts in its home deck; a filtered deck counts the cards it holds now, and isn't added
     * to its parent (those cards already count in their home deck).
     */
    private fun Collection.totalCardCounts(tree: DeckNode): Map<DeckId, Int> {
        val own = mutableMapOf<DeckId, Int>()
        db.query("SELECT did, odid, count() FROM cards GROUP BY did, odid").use { cursor ->
            while (cursor.moveToNext()) {
                val count = cursor.getInt(2)
                own.merge(cursor.getLong(0), count, Int::plus)
                val homeDeckId = cursor.getLong(1)
                if (homeDeckId != 0L) own.merge(homeDeckId, count, Int::plus)
            }
        }
        val totals = mutableMapOf<DeckId, Int>()

        fun total(node: DeckNode): Int {
            val childrenTotal = node.children.sumOf { child -> total(child).takeUnless { child.filtered } ?: 0 }
            return ((own[node.did] ?: 0) + childrenTotal).also { totals[node.did] = it }
        }
        total(tree)
        return totals
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
