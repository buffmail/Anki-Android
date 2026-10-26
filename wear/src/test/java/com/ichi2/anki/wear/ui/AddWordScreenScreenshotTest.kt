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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.compose.material3.MaterialTheme
import com.ichi2.anki.wear.AddWordState
import com.ichi2.anki.wear.protocol.WearResponse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the Add word preview and saves the picture to `build/screenshots/` to look at. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Galaxy Watch 7 40mm: 432 x 432 px
@Config(qualifiers = "w216dp-h216dp-round-watch-xhdpi")
class AddWordScreenScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `preview shows the word and its meanings`() {
        val preview =
            WearResponse.WordPreview(
                word = "cupboard",
                back = "",
                preview =
                    "a small cabinet with shelves, used for storing food or dishes" +
                        "<br><i> - She put the cups in the cupboard.</i><br><br>" +
                        "a closet for clothes<br><i> - Hang your coat in the cupboard.</i><br><br>/ˈkʌbərd/",
                existingNotes = 0,
                existingDeckName = null,
                deckId = 1,
                deckName = "English",
            )
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    PreviewScreen(
                        state = AddWordState.Preview(preview, deckId = 1, deckName = "English"),
                        onChooseDeck = {},
                        onTypeAgain = {},
                        onAdd = {},
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)

        compose.onNodeWithText("cupboard").assertIsDisplayed()
        saveRoundScreenshot(compose.drawWindow(), "add-word-preview.png")
    }
}
