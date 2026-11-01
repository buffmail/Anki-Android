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

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.compose.material3.MaterialTheme
import com.ichi2.anki.wear.StudyState
import com.ichi2.anki.wear.protocol.WatchCard
import com.ichi2.anki.wear.protocol.WatchCounts
import com.ichi2.anki.wear.protocol.WatchQueue
import com.ichi2.anki.wear.protocol.WatchRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Local emulator tests of the production study UI; no phone, account or collection needed. */
@RunWith(AndroidJUnit4::class)
class VocabularyCardDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val answers = mutableListOf<WatchRating>()
    private val addedWords = mutableListOf<String>()

    @Test
    fun exampleStandsOutOnRoundWatch() {
        val example = "She felt a wave of dismay at the news."
        val answer =
            exampleHtml(example) +
                "<br>consternation and distress, typically that caused by something unexpected.<br><br>" +
                exampleHtml("The decision to close the school dismayed the parents.") +
                "<br>to distress someone greatly.<br><br>/dɪsˈmeɪ/"
        showCard(answer)

        val text =
            compose
                .onNodeWithText(example, substring = true)
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .single()
        val exampleStyles = text.spanStyles.filter { it.start == 0 && it.end == example.length }
        assertTrue(exampleStyles.any { it.item.color == Color(0xFF4FC3F7) })
        assertTrue(exampleStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(exampleStyles.any { it.item.fontSize == 1.25.em })
        val definitionStart = text.text.indexOf("consternation")
        assertTrue(text.spanStyles.none { it.start <= definitionStart && it.end > definitionStart })
        saveScreenshot("dismay-example.png")

        compose.onRoot().performTouchInput { swipeUp() }
        compose.waitForIdle()
        saveScreenshot("dismay-definition.png")
        compose.onRoot().performTouchInput { swipeDown() }
        assertTrue(answers.isEmpty())
        assertTrue(addedWords.isEmpty())
    }

    @Test
    fun canHoldWordInLargerExampleWithoutRatingCard() {
        checkWordSelection("dismay")
    }

    @Test
    fun canHoldWordInRegularDefinitionWithoutRatingCard() {
        checkWordSelection("distress")
    }

    private fun checkWordSelection(word: String) {
        val example = "She felt dismay."
        showCard(exampleHtml(example) + "<br>sudden distress")
        val node = compose.onNodeWithText(example, substring = true)
        lateinit var layout: TextLayoutResult
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getLayout ->
            val results = mutableListOf<TextLayoutResult>()
            getLayout(results)
            layout = results.single()
        }
        val offset =
            layout.layoutInput.text.text
                .indexOf(word)
        assertTrue(offset >= 0)
        val position = layout.getBoundingBox(offset + word.length / 2).center
        node.performTouchInput { longClick(position) }
        compose.onNodeWithText("Add $word").assertIsDisplayed().performClick()
        assertEquals(listOf(word), addedWords)
        assertTrue(answers.isEmpty())
    }

    private fun showCard(answer: String) {
        val card = WatchCard(1, "dismay", answer, false, "<1m", "1d", WatchQueue.NEW)
        val state = StudyState.Showing("English", WatchCounts(1, 0, 0), card, true, false, 1)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    CardScreen(state, onReveal = {}, onAnswer = answers::add, onAddWord = addedWords::add)
                }
            }
        }
        compose.waitForIdle()
    }

    // The supported tags emitted by the phone's WatchCardFormatter for .anki-example.
    private fun exampleHtml(text: String) = """<font color="#4FC3F7"><b><big>$text</big></b></font>"""

    private fun saveScreenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertEquals(432, bitmap.width)
        assertEquals(432, bitmap.height)
        // Gradle retrieves this directory before uninstalling the test build.
        val outputDirectory =
            InstrumentationRegistry
                .getArguments()
                .getString("additionalTestOutputDir")
                ?.let(::File)
                ?: File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots")
        val file = File(outputDirectory, name)
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
