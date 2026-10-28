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

package com.ichi2.anki.wear.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.compose.material3.MaterialTheme
import com.ichi2.anki.wear.EtymologyState
import com.ichi2.anki.wear.StudyState
import com.ichi2.anki.wear.UiMessage
import com.ichi2.anki.wear.protocol.WatchCard
import com.ichi2.anki.wear.protocol.WatchCounts
import com.ichi2.anki.wear.protocol.WatchQueue
import com.ichi2.anki.wear.protocol.WatchRating
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
// real text measurement, to find the word under a finger
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w227dp-h227dp-round-watch")
class CardScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val card =
        WatchCard(
            id = 1,
            question = "犬",
            answer = "dog",
            answerIncludesQuestion = false,
            againInterval = "<1m",
            goodInterval = "1d",
            queue = WatchQueue.NEW,
        )

    private var state by mutableStateOf(
        StudyState.Showing("Japanese", WatchCounts(1, 0, 0), card, revealed = false, sending = false, sequence = 1),
    )
    private var reveals = 0
    private val answers = mutableListOf<WatchRating>()
    private val addedWords = mutableListOf<String>()
    private var etymologyRequests = 0

    @Before
    fun showCard() {
        compose.setContent {
            MaterialTheme {
                CardScreen(
                    state = state,
                    onReveal = {
                        reveals++
                        state = state.copy(revealed = true)
                    },
                    onAnswer = { answers += it },
                    onAddWord = { addedWords += it },
                    onEtymology = { etymologyRequests++ },
                )
            }
        }
    }

    @Test
    fun `etymology is below the revealed answer and its touch never rates the card`() {
        compose.onAllNodesWithText("Etymology").assertCountEquals(0)
        state = state.copy(card = card.copy(question = "wardrobe", answer = "a cabinet for clothes<br>".repeat(20)))
        tapAt(0.5f)
        compose.mainClock.advanceTimeBy(1_000)
        compose
            .onNodeWithText("Etymology")
            .assertIsNotDisplayed()
            .performScrollTo()
            .assertIsDisplayed()
        // Tap over the left rating band, inside the button, using real pointer input.
        compose.onNodeWithText("Etymology").performTouchInput { click(Offset(width * 0.1f, height / 2f)) }

        assertEquals(1, etymologyRequests)
        assertEquals(emptyList<WatchRating>(), answers)
    }

    @Test
    fun `etymology loading success and retry stay on the revealed card`() {
        state = state.copy(revealed = true, etymology = EtymologyState.Loading)
        compose.onNodeWithText("Looking up word origin…").performScrollTo().assertIsDisplayed()

        val explanation = "From Old French garderobe: guard + clothes. It is unrelated to war."
        state = state.copy(etymology = EtymologyState.Loaded(explanation))
        compose.onNodeWithText(explanation).performScrollTo().assertIsDisplayed()

        state = state.copy(etymology = EtymologyState.Failed(UiMessage.Text("Phone offline")))
        compose.onNodeWithText("Phone offline").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        assertEquals(1, etymologyRequests)
        assertEquals(emptyList<WatchRating>(), answers)
    }

    @Test
    fun `tapping the card shows the answer`() {
        tapAt(0.5f)
        assertEquals(1, reveals)
        assertEquals(emptyList<WatchRating>(), answers)
    }

    @Test
    fun `left side answers Again, right side Good, the middle nothing`() {
        tapAt(0.5f)
        compose.mainClock.advanceTimeBy(1_000)

        tapAt(0.1f)
        tapAt(0.5f)
        tapAt(0.9f)

        assertEquals(listOf(WatchRating.AGAIN, WatchRating.GOOD), answers)
    }

    @Test
    fun `a tap right after showing the answer doesn't answer the card`() {
        // the clock moves only when told: otherwise waiting for animations adds time between taps
        compose.mainClock.autoAdvance = false
        tapAt(0.5f)
        compose.mainClock.advanceTimeBy(100)
        tapAt(0.9f)
        assertEquals(emptyList<WatchRating>(), answers)

        compose.mainClock.advanceTimeBy(500)
        tapAt(0.9f)
        assertEquals(listOf(WatchRating.GOOD), answers)
    }

    @Test
    fun `a swipe doesn't answer the card`() {
        tapAt(0.5f)
        compose.mainClock.advanceTimeBy(1_000)

        compose.onRoot().performTouchInput { swipeRight(startX = width * 0.4f, endX = width * 0.95f) }
        compose.onRoot().performTouchInput { swipeLeft(startX = width * 0.6f, endX = width * 0.05f) }
        compose.mainClock.advanceTimeBy(1_000)

        assertEquals(emptyList<WatchRating>(), answers)
    }

    @Test
    fun `cards left is part of the card, so it scrolls away instead of covering the text`() {
        val longAnswer =
            "a small cabinet with shelves, used for storing food or dishes" +
                "<br><i> - She put the cups in the cupboard.</i><br><br>" +
                "a closet for clothes<br><i> - Hang your coat in the cupboard.</i><br><br>/ˈkʌbərd/"
        state = state.copy(counts = WatchCounts(12, 0, 0), card = card.copy(answer = longAnswer))
        compose.onNodeWithText("12").assertIsDisplayed()

        // showing the answer scrolls the card to it
        tapAt(0.5f)
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("12").assertIsNotDisplayed()

        compose.onRoot().performTouchInput { swipeDown() }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("12").assertIsDisplayed()
    }

    @Test
    fun `holding a word highlights it and offers to add it`() {
        state = state.copy(card = card.copy(answer = "shelves cabinet"))
        tapAt(0.5f)
        compose.mainClock.advanceTimeBy(1_000)

        // on "shelves", which lies over the Again side: a hold must not answer
        compose.onNodeWithText("shelves cabinet").performTouchInput { longClick(Offset(width * 0.1f, height / 2f)) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Add shelves").assertIsDisplayed().performClick()

        assertEquals(listOf("shelves"), addedWords)
        assertEquals(emptyList<WatchRating>(), answers)
    }

    @Test
    fun `holding where there's no word does nothing`() {
        tapAt(0.5f)
        compose.mainClock.advanceTimeBy(1_000)

        compose.onRoot().performTouchInput { longClick(Offset(width * 0.05f, height / 2f)) }
        compose.mainClock.advanceTimeBy(1_000)

        assertEquals(emptyList<WatchRating>(), answers)
        compose.onAllNodesWithText("Add", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a tap lets go of the held word instead of answering`() {
        state = state.copy(card = card.copy(answer = "shelves cabinet"))
        tapAt(0.5f)
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("shelves cabinet").performTouchInput { longClick(Offset(width * 0.1f, height / 2f)) }
        compose.mainClock.advanceTimeBy(500)

        tapAt(0.9f)
        assertEquals(emptyList<WatchRating>(), answers)
        compose.onAllNodesWithText("Add", substring = true).assertCountEquals(0)

        tapAt(0.9f)
        assertEquals(listOf(WatchRating.GOOD), answers)
        assertEquals(emptyList<String>(), addedWords)
    }

    /** Taps at [fraction] of the width, halfway down. */
    private fun tapAt(fraction: Float) {
        compose.onRoot().performTouchInput { click(Offset(width * fraction, height / 2f)) }
        compose.mainClock.advanceTimeByFrame()
    }
}
