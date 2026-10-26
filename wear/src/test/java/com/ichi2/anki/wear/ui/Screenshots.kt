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
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import java.io.File

/**
 * Draws the activity's window into a bitmap. Compose's `captureToImage` times out under
 * Robolectric; this needs `@GraphicsMode(NATIVE)`.
 */
fun AndroidComposeTestRule<*, ComponentActivity>.drawWindow(): Bitmap {
    lateinit var bitmap: Bitmap
    runOnUiThread {
        val view = activity.window.decorView
        bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
    }
    return bitmap
}

/** Saves [bitmap] to `build/screenshots/[name]`, cut to the round screen, on gray. */
fun saveRoundScreenshot(
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
