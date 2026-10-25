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

import com.ichi2.anki.wear.WordDefinition.Meaning
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class WordCardTest {
    @Test
    fun `back is built like anki-new-card builds it`() {
        val definition =
            WordDefinition(
                meanings =
                    listOf(
                        Meaning("a small cabinet with shelves", "She put the cups in the cupboard."),
                        Meaning("a closet", "Hang your coat in the cupboard."),
                    ),
                pronunciation = "/ˈkʌbərd/",
            )

        // what the website's formatBackContent and ank.py's build_meaning_html produce
        val expected =
            "a small cabinet with shelves" +
                "<br><span style=\"font-size: small;\"><i> - She put the cups in the cupboard.</i></span>" +
                "<br><br>a closet" +
                "<br><span style=\"font-size: small;\"><i> - Hang your coat in the cupboard.</i></span>" +
                "<br><br><span style=\"color: #666; font-size: small;\">/ˈkʌbərd/</span>"
        assertEquals(expected, WordCard.back(definition))
    }

    @Test
    fun `meaning without an example and no pronunciation`() {
        val definition = WordDefinition(listOf(Meaning("a cabinet", "")), pronunciation = null)
        assertEquals("a cabinet", WordCard.back(definition))
    }

    @Test
    fun `text from Gemini is not read as HTML`() {
        val definition = WordDefinition(listOf(Meaning("less than (<) & more", "a <b> c")), pronunciation = null)
        assertEquals(
            "less than (&lt;) &amp; more<br><span style=\"font-size: small;\"><i> - a &lt;b&gt; c</i></span>",
            WordCard.back(definition),
        )
    }

    @Test
    fun `words are trimmed and lowercase, without surrounding punctuation`() {
        assertEquals("cupboard", WordCard.normalize("  Cupboard. "))
        assertEquals("give up", WordCard.normalize("Give\n up!"))
        assertEquals("o'clock", WordCard.normalize("O'clock"))
        assertEquals("well-known", WordCard.normalize("\"Well-known\""))
        assertEquals("", WordCard.normalize(" ?! "))
    }
}
