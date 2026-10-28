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

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.compose.material3.MaterialTheme
import com.ichi2.anki.wear.EtymologyState
import com.ichi2.anki.wear.StudyState
import com.ichi2.anki.wear.protocol.WatchCard
import com.ichi2.anki.wear.protocol.WatchCounts
import com.ichi2.anki.wear.protocol.WatchQueue
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the card screen with the answer shown, checks where the answer hints are drawn, and
 * saves the picture to `build/screenshots/` to look at.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Galaxy Watch 7 40mm: 432 x 432 px
@Config(qualifiers = "w216dp-h216dp-round-watch-xhdpi")
class CardScreenScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `etymology button and explanation fit the round screen`() {
        val card = WatchCard(1, "wardrobe", "a cabinet for clothes", false, "<1m", "1d", WatchQueue.NEW)
        var state by mutableStateOf(StudyState.Showing("English", WatchCounts(12, 0, 0), card, true, false, 1))
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    CardScreen(state, onReveal = {}, onAnswer = {})
                }
            }
        }
        compose.onNodeWithText("Etymology").performScrollTo().assertIsDisplayed()
        compose.mainClock.advanceTimeBy(2_000)
        saveRoundScreenshot(compose.drawWindow(), "card-etymology-button.png")

        val explanation =
            "From Old French garderobe: garder (to guard) + robe (clothes). " +
                "It originally meant a place to keep clothes safe, then a clothes cupboard. It is unrelated to war."
        state = state.copy(etymology = EtymologyState.Loaded(explanation))
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("Etymology").assertIsDisplayed()
        saveRoundScreenshot(compose.drawWindow(), "card-etymology-explanation.png")
    }

    @Test
    fun `answer hints tint the sides outside the text`() {
        val card =
            WatchCard(
                id = 1,
                question = "cupboard",
                answer =
                    "a small cabinet with shelves, used for storing food or dishes" +
                        "<br><i> - She put the cups in the cupboard.</i><br><br>" +
                        "a closet for clothes<br><i> - Hang your coat in the cupboard.</i><br><br>/ˈkʌbərd/",
                answerIncludesQuestion = false,
                againInterval = "<1m",
                goodInterval = "1d",
                queue = WatchQueue.NEW,
            )
        val state = StudyState.Showing("English", WatchCounts(12, 0, 0), card, revealed = true, sending = false, sequence = 1)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    CardScreen(state, onReveal = {}, onAnswer = {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)

        val bitmap = compose.drawWindow()
        saveRoundScreenshot(bitmap, "card-answer-hints.png")

        val w = bitmap.width
        val mid = bitmap.height / 2
        val left = bitmap.getPixel((w * 0.1f).toInt(), mid)
        val right = bitmap.getPixel((w * 0.9f).toInt(), mid)
        val rim = bitmap.getPixel((w * 0.015f).toInt(), mid)
        assertTrue("left side is red: ${hex(left)}", red(left) > 40 && red(left) > green(left) * 2)
        assertTrue("right side is green: ${hex(right)}", green(right) > 40 && green(right) > red(right) * 2)
        assertTrue("the rim stays black: ${hex(rim)}", red(rim) < 20 && green(rim) < 20)
    }

    @Test
    fun `question with the cards left above it`() {
        val card = WatchCard(1, "cupboard", "a small cabinet", false, "<1m", "1d", WatchQueue.NEW)
        val state = StudyState.Showing("English", WatchCounts(12, 0, 0), card, revealed = false, sending = false, sequence = 1)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    CardScreen(state, onReveal = {}, onAnswer = {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        saveRoundScreenshot(compose.drawWindow(), "card-question.png")
    }

    @Test
    fun `held word with its Add button`() {
        val answer =
            "a small cabinet with shelves, used for storing food or dishes" +
                "<br><i> - She put the cups in the cupboard.</i>"
        val card = WatchCard(1, "cupboard", answer, false, "<1m", "1d", WatchQueue.NEW)
        val state = StudyState.Showing("English", WatchCounts(12, 0, 0), card, revealed = true, sending = false, sequence = 1)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    CardScreen(state, onReveal = {}, onAnswer = {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        // the middle of the first line of the meaning
        compose
            .onNodeWithText("a small cabinet", substring = true)
            .performTouchInput { longClick(Offset(width * 0.55f, height * 0.08f)) }
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNodeWithText("Add", substring = true).assertIsDisplayed()
        saveRoundScreenshot(compose.drawWindow(), "card-held-word.png")
    }

    private fun red(pixel: Int) = (pixel shr 16) and 0xFF

    private fun green(pixel: Int) = (pixel shr 8) and 0xFF

    private fun hex(pixel: Int) = "#%08X".format(pixel)
}
