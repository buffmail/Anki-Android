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
import com.ichi2.anki.libanki.NoteId
import com.ichi2.anki.wear.WordDefinition.Meaning
import com.ichi2.anki.wear.protocol.SyncStatus
import com.ichi2.anki.wear.protocol.WatchQueue
import com.ichi2.anki.wear.protocol.WatchRating
import com.ichi2.anki.wear.protocol.WearProtocol
import com.ichi2.anki.wear.protocol.WearRequest
import com.ichi2.anki.wear.protocol.WearResponse
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class WearRequestHandlerTest : RobolectricTest() {
    private val realWordLookup = WearRequestHandler.wordLookup

    private val cupboard =
        WordDefinition(
            meanings = listOf(Meaning("a small cabinet with shelves", "She put the cups in the cupboard.")),
            pronunciation = "/ˈkʌbərd/",
        )

    /** Words looked up by the handler, as it passes them on. */
    private val lookedUp = mutableListOf<String>()

    @After
    fun restoreWordLookup() {
        WearRequestHandler.wordLookup = realWordLookup
    }

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
    fun `deck list has all cards of each deck, due or not`() =
        runTest {
            val vocab = addDeckWithBasicNotes("Japanese::Vocab", "犬" to "dog", "猫" to "cat")
            val japanese = addDeckWithBasicNotes("Japanese", "水" to "water")
            setCollapsed(japanese, false)
            // not due, but still in the deck
            col.sched.suspendCards(col.findCards("deck:Japanese -deck:Japanese::*"))
            // the Vocab cards move to the filtered deck for now
            val filtered = addDynamicDeck("Filtered", search = "deck:Japanese::Vocab")

            val decks = deckList()

            assertEquals(2, decks.single { it.id == vocab }.cardCount, "cards in a filtered deck count in their home deck")
            assertEquals(3, decks.single { it.id == japanese }.cardCount, "subdecks are included, the filtered deck isn't")
            assertEquals(2, decks.single { it.id == filtered }.cardCount, "a filtered deck has the cards it holds")
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
    fun `sync without an AnkiWeb login reports it`() =
        runTest {
            val response = WearRequestHandler.handle(WearRequest.Sync)
            assertEquals(WearResponse.Synced(SyncStatus.NOT_LOGGED_IN), response)
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

    @Test
    fun `looking up a word returns the card back and the phone's default deck`() =
        runTest {
            useWordLookup { cupboard }

            val preview = lookUp("  Cupboard. ")

            assertEquals(listOf("cupboard"), lookedUp, "the word is normalized before the lookup")
            assertEquals("cupboard", preview.word)
            assertEquals(WordCard.back(cupboard), preview.back)
            assertTrue(preview.preview.contains("a small cabinet with shelves"))
            assertEquals(0, preview.existingNotes)
            assertNull(preview.existingDeckName)
            assertEquals(col.defaultsForAdding().deckId, preview.deckId)
            assertEquals(col.decks.name(preview.deckId), preview.deckName)
        }

    @Test
    fun `looking up suggests the deck added to last, if it still exists`() =
        runTest {
            useWordLookup { cupboard }
            val english = addDeck("English")

            assertEquals(english, lookUp("cupboard", preferredDeckId = english).deckId)
            assertEquals(col.defaultsForAdding().deckId, lookUp("cupboard", preferredDeckId = 98765).deckId)
        }

    @Test
    fun `looking up a word that is already there says so`() =
        runTest {
            useWordLookup { cupboard }
            addDeckWithBasicNotes("English", "Cupboard" to "old meaning")

            val preview = lookUp("cupboard")

            assertEquals(1, preview.existingNotes, "matched regardless of case")
            assertEquals("English", preview.existingDeckName)
        }

    @Test
    fun `looking up without a Gemini API key asks for one`() =
        runTest {
            useWordLookup { throw MissingApiKeyException() }
            assertEquals(WearResponse.NoApiKey, WearRequestHandler.handle(WearRequest.LookUpWord("cupboard")))
        }

    @Test
    fun `lookup failure gives a readable error`() =
        runTest {
            useWordLookup { throw WordLookupException("Couldn't reach anki-new-card. Is the phone online?") }

            val reply = WearRequestHandler.handle(WearProtocol.RPC_PATH, WearProtocol.encode(WearRequest.LookUpWord("cupboard")))

            assertEquals(WearResponse.Error("Couldn't reach anki-new-card. Is the phone online?"), WearProtocol.decodeResponse(reply))
        }

    @Test
    fun `adding a new word creates a Basic note in the chosen deck`() =
        runTest {
            val english = addDeck("English")
            val back = WordCard.back(cupboard)

            val added = addWord(english, "Cupboard", back)

            assertEquals(WearResponse.WordAdded(word = "cupboard", deckName = "English", updatedNotes = 0), added)
            val note = col.getNote(wordNotes("cupboard").single())
            assertEquals("Basic", note.notetype.name)
            assertEquals("cupboard", note.getItem("Front"))
            assertEquals(back, note.getItem("Back"))
            assertEquals(english, note.cards(col).single().did)
        }

    @Test
    fun `adding a word that is already there updates it and keeps its schedule`() =
        runTest {
            val english = addDeckWithBasicNotes("English", "Cupboard" to "old meaning")
            val other = addDeck("Other")
            val noteId = wordNotes("cupboard").single()
            // study it once, so that it has a schedule to keep
            col.decks.select(english)
            col.sched.answerCard(col.getNote(noteId).cards(col).single(), anki.scheduler.CardAnswer.Rating.GOOD)
            val before = col.getNote(noteId).cards(col).single()

            val added = addWord(other, "cupboard", WordCard.back(cupboard))

            assertEquals(WearResponse.WordAdded(word = "cupboard", deckName = "English", updatedNotes = 1), added)
            assertEquals(listOf(noteId), wordNotes("cupboard"), "no new note")
            val note = col.getNote(noteId)
            assertEquals("cupboard", note.getItem("Front"))
            assertEquals(WordCard.back(cupboard), note.getItem("Back"))
            val after = note.cards(col).single()
            assertEquals(english, after.did, "stays in its deck")
            assertEquals(before.type, after.type)
            assertEquals(before.due, after.due)
            assertEquals(1, reviewCount())
        }

    @Test
    fun `adding to a filtered deck is refused`() =
        runTest {
            val filtered = addDynamicDeck("Filtered")

            val request = WearRequest.AddWord(filtered, "cupboard", WordCard.back(cupboard))
            val reply = WearRequestHandler.handle(WearProtocol.RPC_PATH, WearProtocol.encode(request))

            assertIs<WearResponse.Error>(WearProtocol.decodeResponse(reply))
            assertTrue(wordNotes("cupboard").isEmpty())
        }

    private fun useWordLookup(definition: (String) -> WordDefinition) {
        WearRequestHandler.wordLookup =
            WordLookup { word ->
                lookedUp += word
                definition(word)
            }
    }

    private suspend fun lookUp(
        word: String,
        preferredDeckId: DeckId? = null,
    ) = assertIs<WearResponse.WordPreview>(WearRequestHandler.handle(WearRequest.LookUpWord(word, preferredDeckId)))

    private suspend fun addWord(
        deckId: DeckId,
        word: String,
        back: String,
    ) = assertIs<WearResponse.WordAdded>(WearRequestHandler.handle(WearRequest.AddWord(deckId, word, back)))

    private fun wordNotes(word: String): List<NoteId> = col.findNotes("\"front:$word\"")

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
