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

package com.ichi2.anki.wear.protocol

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class WearProtocolTest {
    @Test
    fun `requests survive a round trip`() {
        val requests =
            listOf(
                WearRequest.GetDecks,
                WearRequest.NextCard(deckId = 42),
                WearRequest.Answer(deckId = 42, cardId = 7, rating = WatchRating.GOOD, millisTaken = 1234),
                WearRequest.Sync,
                WearRequest.LookUpWord("Cupboard"),
                WearRequest.LookUpWord("cupboard", preferredDeckId = 42),
                WearRequest.AddWord(deckId = 42, word = "cupboard", back = "a cabinet<br><i> - in the cupboard</i>"),
            )
        for (request in requests) {
            assertEquals(request, WearProtocol.decodeRequest(WearProtocol.encode(request)))
        }
    }

    @Test
    fun `responses survive a round trip`() {
        val counts = WatchCounts(new = 1, learn = 2, review = 3)
        val card =
            WatchCard(
                id = 7,
                question = "<b>犬</b>",
                answer = "dog",
                answerIncludesQuestion = false,
                againInterval = "<1m",
                goodInterval = "3d",
                queue = WatchQueue.REVIEW,
            )
        val responses =
            listOf(
                WearResponse.Decks(listOf(WatchDeck(id = 1, name = "Vocab", depth = 1, counts = counts, cardCount = 250))),
                WearResponse.Study(deckName = "Japanese::Vocab", counts = counts, card = card),
                WearResponse.Study(deckName = "Japanese", counts = WatchCounts(0, 0, 0), card = null, answerSkipped = true),
                WearResponse.Synced(SyncStatus.NOT_LOGGED_IN),
                WearResponse.Error("boom"),
                WearResponse.WordPreview(
                    word = "cupboard",
                    back = "a cabinet<br><span style=\"font-size: small;\"><i> - in the cupboard</i></span>",
                    preview = "a cabinet<br><i> - in the cupboard</i>",
                    existingNotes = 1,
                    existingDeckName = "English",
                    deckId = 42,
                    deckName = "English",
                ),
                WearResponse.WordAdded(word = "cupboard", deckName = "English", updatedNotes = 0),
                WearResponse.NoApiKey,
            )
        for (response in responses) {
            assertEquals(response, WearProtocol.decodeResponse(WearProtocol.encode(response)))
        }
    }

    @Test
    fun `deck from a phone that doesn't send the card count`() {
        val json = """{"type":"decks","decks":[{"id":1,"name":"Vocab","depth":0,"counts":{"new":1,"learn":0,"review":2}}]}"""
        val deck = (WearProtocol.decodeResponse(json.encodeToByteArray()) as WearResponse.Decks).decks.single()
        assertEquals(null, deck.cardCount)
    }

    @Test
    fun `unknown fields are ignored`() {
        val json = """{"type":"next","deckId":5,"addedInAFutureVersion":true}"""
        assertEquals(WearRequest.NextCard(5), WearProtocol.decodeRequest(json.encodeToByteArray()))
    }
}
