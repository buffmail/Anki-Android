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

import com.ichi2.anki.wear.WatchCardFormatter.CLOZE_COLOR
import com.ichi2.anki.wear.WatchCardFormatter.toWatchHtml
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchCardFormatterTest {
    @Test
    fun `answer starts after the answer separator`() {
        val formatted = WatchCardFormatter.format("Front", "Front\n\n<hr id=answer>\n\nBack")
        assertEquals("Front", formatted.question)
        assertEquals("Back", formatted.answer)
        assertFalse(formatted.answerIncludesQuestion)
    }

    @Test
    fun `cloze answer has no separator and highlights the deletion`() {
        val formatted =
            WatchCardFormatter.format(
                questionHtml = """<span class="cloze" data-ordinal="1">[...]</span> means dog""",
                answerHtml = """<span class="cloze" data-ordinal="1">Hund</span> means dog<br>""",
            )
        assertTrue(formatted.answerIncludesQuestion)
        assertEquals("""<font color="$CLOZE_COLOR"><b>[...]</b></font> means dog""", formatted.question)
        assertEquals("""<font color="$CLOZE_COLOR"><b>Hund</b></font> means dog""", formatted.answer)
    }

    @Test
    fun `css, scripts, media and av tags are removed`() {
        val html = """<style>.card{}</style><script>alert(1)</script>Hello<img src="a.png">[anki:play:q:0][[type:Back]]"""
        assertEquals("Hello", toWatchHtml(html))
    }

    @Test
    fun `furigana is shown in parentheses`() {
        assertEquals("漢字(かんじ)", toWatchHtml("<ruby><rb>漢字</rb><rt>かんじ</rt></ruby>"))
    }

    @Test
    fun `user colors and inline styles are dropped`() {
        assertEquals("""xy""", toWatchHtml("""<font color="black">x</font><span style="color: black">y</span>"""))
    }

    @Test
    fun `hidden elements are removed`() {
        assertEquals("shown", toWatchHtml("""<div style="display: none">secret</div>shown"""))
    }

    @Test
    fun `basic formatting is kept`() {
        assertEquals("<b>bold</b><br><i>it</i>", toWatchHtml("<b>bold</b><br><i>it</i>"))
    }

    @Test
    fun `table rows end up on separate lines`() {
        val html = "<table><tr><td>a</td><td>b</td></tr><tr><td>c</td></tr></table>"
        assertEquals("a b <br>c", toWatchHtml(html))
    }
}
