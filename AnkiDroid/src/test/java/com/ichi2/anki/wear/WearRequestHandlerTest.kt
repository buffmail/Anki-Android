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

import androidx.test.ext.junit.runners.AndroidJUnit4
import anki.decks.SetDeckCollapsedRequest
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.wear.protocol.WatchQueue
import com.ichi2.anki.wear.protocol.WatchRating
import com.ichi2.anki.wear.protocol.WearProtocol
import com.ichi2.anki.wear.protocol.WearRequest
import com.ichi2.anki.wear.protocol.WearResponse
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class WearRequestHandlerTest : RobolectricTest() {
    @Test
    fun `deck list contains decks with their counts`() =
        runTest {
            val deckId = addDeckWithBasicNotes("Japanese::Vocab", "犬" to "dog")
            val parentId = col.decks.id("Japanese")
            setCollapsed(parentId, false)

            val decks = deckList()

            val parent = decks.single { it.id == parentId }
            val child = decks.single { it.id == deckId }
            assertEquals("Japanese", parent.name)
            assertEquals(0, parent.depth)
            assertEquals(1, parent.counts.new, "parent counts include subdecks")
            assertEquals("Vocab", child.name)
            assertEquals(1, child.depth)
            assertEquals(1, child.counts.new)
            // the empty Default deck is hidden, like in the deck picker
            assertTrue(decks.none { it.name == "Default" })
        }

    @Test
    fun `subdecks of collapsed decks are hidden, like in the deck picker`() =
        runTest {
            val deckId = addDeckWithBasicNotes("Japanese::Vocab", "犬" to "dog")
            setCollapsed(col.decks.id("Japanese"), true)

            val decks = deckList()

            assertEquals(listOf("Japanese"), decks.map { it.name })
            assertTrue(decks.none { it.id == deckId })
        }

    @Test
    fun `next card is rendered for the watch`() =
        runTest {
            val deckId = addDeckWithBasicNotes("Watch", "犬" to "dog")

            val study = nextCard(deckId)

            val card = assertNotNull(study.card)
            assertEquals("Watch", study.deckName)
            assertEquals(1, study.counts.new)
            assertEquals("犬", card.question)
            assertEquals("dog", card.answer)
            assertEquals(false, card.answerIncludesQuestion)
            assertEquals(WatchQueue.NEW, card.queue)
            assertTrue(card.againInterval.isNotEmpty())
            assertTrue(card.goodInterval.isNotEmpty())
            assertEquals(deckId, col.decks.selected(), "studying selects the deck, as on the phone")
        }

    @Test
    fun `answering records a review and returns the next card`() =
        runTest {
            val deckId = addDeckWithBasicNotes("Watch", "one" to "1", "two" to "2")
            val first = assertNotNull(nextCard(deckId).card)

            val response = answer(deckId, first.id, WatchRating.GOOD)

            assertEquals(false, response.answerSkipped)
            assertEquals(1, reviewCount())
            val next = assertNotNull(response.card)
            assertTrue(next.id != first.id)
        }

    @Test
    fun `deck is finished when nothing is left`() =
        runTest {
            val deckId = addDeckWithBasicNotes("Watch", "one" to "1")
            col.decks.select(deckId)
            // graduate the only card so that nothing is due today
            val card = assertNotNull(nextCard(deckId).card)
            col.sched.answerCard(col.getCard(card.id), anki.scheduler.CardAnswer.Rating.EASY)

            assertNull(nextCard(deckId).card)
        }

    @Test
    fun `answer for a card that is no longer next is ignored`() =
        runTest {
            val deckId = addDeckWithBasicNotes("Watch", "one" to "1")

            val response = answer(deckId, cardId = 12345, WatchRating.GOOD)

            assertTrue(response.answerSkipped)
            assertEquals(0, reviewCount())
            assertNotNull(response.card)
        }

    @Test
    fun `wrong protocol version gives a readable error`() =
        runTest {
            val reply = WearRequestHandler.handle("/ankidroid/v999", WearProtocol.encode(WearRequest.GetDecks))
            assertIs<WearResponse.Error>(WearProtocol.decodeResponse(reply))
        }

    @Test
    fun `unknown deck gives a readable error`() =
        runTest {
            val reply = WearRequestHandler.handle(WearProtocol.RPC_PATH, WearProtocol.encode(WearRequest.NextCard(98765)))
            assertIs<WearResponse.Error>(WearProtocol.decodeResponse(reply))
        }

    private suspend fun deckList() = assertIs<WearResponse.Decks>(WearRequestHandler.handle(WearRequest.GetDecks)).decks

    private fun setCollapsed(
        deckId: DeckId,
        collapsed: Boolean,
    ) {
        col.decks.setCollapsed(deckId, collapsed, SetDeckCollapsedRequest.Scope.REVIEWER)
    }

    private suspend fun nextCard(deckId: DeckId) = assertIs<WearResponse.Study>(WearRequestHandler.handle(WearRequest.NextCard(deckId)))

    private suspend fun answer(
        deckId: DeckId,
        cardId: Long,
        rating: WatchRating,
    ) = assertIs<WearResponse.Study>(
        WearRequestHandler.handle(WearRequest.Answer(deckId, cardId, rating, millisTaken = 2000)),
    )

    private fun reviewCount(): Int = col.db.queryScalar("SELECT count() FROM revlog")

    private fun addDeckWithBasicNotes(
        name: String,
        vararg notes: Pair<String, String>,
    ): DeckId {
        val deckId = addDeck(name)
        for ((front, back) in notes) {
            val note = addBasicNote(front, back)
            col.setDeck(note.cardIds(col), deckId)
        }
        return deckId
    }
}
