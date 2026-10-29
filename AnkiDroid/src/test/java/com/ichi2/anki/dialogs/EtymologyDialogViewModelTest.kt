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

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.dialogs.EtymologyDialogViewModel.Companion.ARG_CARD_ID
import com.ichi2.anki.dialogs.EtymologyDialogViewModel.State
import com.ichi2.anki.wear.EtymologyLookup
import com.ichi2.anki.wear.MissingApiKeyException
import com.ichi2.anki.wear.WordLookupException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

@RunWith(AndroidJUnit4::class)
class EtymologyDialogViewModelTest : RobolectricTest() {
    @Test
    fun `loading is deduplicated and a result survives recreation without changing the card`() =
        runTest {
            val note = addBasicNote("<b>Wardrobe</b>", "a cabinet for clothes")
            val card = note.firstCard()
            val before = card.toBackendCard()
            val fieldsBefore = note.fields.toList()
            val handle = SavedStateHandle(mapOf(ARG_CARD_ID to card.id))
            val pending = CompletableDeferred<String>()
            val words = mutableListOf<String>()
            val model = EtymologyDialogViewModel(handle)
            model.lookup =
                EtymologyLookup {
                    words += it
                    pending.await()
                }

            model.load()
            model.load()
            advanceUntilIdle()
            assertEquals(State.Loading, model.state.value)
            assertEquals(listOf("wardrobe"), words)

            pending.complete("From Old French garderobe.")
            advanceUntilIdle()
            val result = State.Loaded("wardrobe", "From Old French garderobe.")
            assertEquals(result, model.state.value)
            model.load()
            assertEquals(listOf("wardrobe"), words)

            val restored = EtymologyDialogViewModel(handle)
            restored.lookup = EtymologyLookup { error("A restored explanation must not be fetched again") }
            restored.load()
            assertEquals(result, restored.state.value)
            assertEquals(before, col.getCard(card.id).toBackendCard())
            assertEquals(fieldsBefore, col.getNote(note.id).fields.toList())
        }

    @Test
    fun `missing key and network errors can be retried`() =
        runTest {
            val card = addBasicNote("wardrobe", "clothes cabinet").firstCard()
            val model = EtymologyDialogViewModel(SavedStateHandle(mapOf(ARG_CARD_ID to card.id)))
            model.lookup = EtymologyLookup { throw MissingApiKeyException() }
            model.load()
            advanceUntilIdle()
            assertEquals(State.Failed(R.string.etymology_no_api_key), model.state.value)

            model.lookup = EtymologyLookup { throw WordLookupException("Couldn't reach Gemini.") }
            model.load()
            advanceUntilIdle()
            assertEquals(State.Failed(R.string.etymology_failed, details = "Couldn't reach Gemini."), model.state.value)

            model.lookup = EtymologyLookup { "From Old French garderobe." }
            model.load()
            advanceUntilIdle()
            assertIs<State.Loaded>(model.state.value)
        }

    @Test
    fun `long questions are rejected before a network request`() =
        runTest {
            val card = addBasicNote("word ".repeat(60), "answer").firstCard()
            val model = EtymologyDialogViewModel(SavedStateHandle(mapOf(ARG_CARD_ID to card.id)))
            var requested = false
            model.lookup =
                EtymologyLookup {
                    requested = true
                    "unexpected"
                }
            model.load()
            advanceUntilIdle()

            assertEquals(State.Failed(R.string.etymology_invalid_card, canRetry = false), model.state.value)
            assertFalse(requested)
        }
}
