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
import android.graphics.Canvas
import android.graphics.Path
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.compose.material3.MaterialTheme
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
import java.io.File

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

        val bitmap = drawWindow()
        save(bitmap, "card-answer-hints.png")

        val w = bitmap.width
        val mid = bitmap.height / 2
        val left = bitmap.getPixel((w * 0.1f).toInt(), mid)
        val right = bitmap.getPixel((w * 0.9f).toInt(), mid)
        val rim = bitmap.getPixel((w * 0.015f).toInt(), mid)
        assertTrue("left side is red: ${hex(left)}", red(left) > 40 && red(left) > green(left) * 2)
        assertTrue("right side is green: ${hex(right)}", green(right) > 40 && green(right) > red(right) * 2)
        assertTrue("the rim stays black: ${hex(rim)}", red(rim) < 20 && green(rim) < 20)
    }

    /** Draws the activity's window into a bitmap (Compose's captureToImage times out here). */
    private fun drawWindow(): Bitmap {
        lateinit var bitmap: Bitmap
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        return bitmap
    }

    /** Saves [bitmap] cut to the round screen, on gray, the way it looks on the watch. */
    private fun save(
        bitmap: Bitmap,
        name: String,
    ) {
        val round = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(round).apply {
            drawColor(android.graphics.Color.rgb(40, 40, 40))
            clipPath(Path().apply { addCircle(width / 2f, height / 2f, width / 2f, Path.Direction.CW) })
            drawBitmap(bitmap, 0f, 0f, null)
        }
        val file = File("build/screenshots/$name")
        file.parentFile?.mkdirs()
        file.outputStream().use { round.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("Screenshot: ${file.absolutePath}")
    }

    private fun red(pixel: Int) = (pixel shr 16) and 0xFF

    private fun green(pixel: Int) = (pixel shr 8) and 0xFF

    private fun hex(pixel: Int) = "#%08X".format(pixel)
}
