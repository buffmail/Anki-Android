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

import com.ichi2.anki.wear.AnkiNewCardLookup.Companion.parseReply
import com.ichi2.anki.wear.WordDefinition.Meaning
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AnkiNewCardLookupTest {
    @Test
    fun `reads meanings and pronunciation`() {
        val reply =
            """{"word":"cupboard","pronunciation":"/ˈkʌbərd/","meanings":[""" +
                """{"meaning":"a small cabinet","example":"Cups are in the cupboard."},""" +
                """{"meaning":"a closet","example":""}]}"""

        val definition = parseReply("cupboard", 200, reply)

        assertEquals(
            WordDefinition(
                meanings = listOf(Meaning("a small cabinet", "Cups are in the cupboard."), Meaning("a closet", "")),
                pronunciation = "/ˈkʌbərd/",
            ),
            definition,
        )
    }

    @Test
    fun `meanings keyed by index are read in order, as ank_py does`() {
        val reply = """{"meanings":{"1":{"meaning":"second"},"0":{"meaning":"first"}}}"""

        val definition = parseReply("word", 200, reply)

        assertEquals(listOf("first", "second"), definition.meanings.map { it.meaning })
        assertEquals(null, definition.pronunciation)
    }

    @Test
    fun `error message from the service is passed on`() {
        val reply = """{"error":"API 호출 제한에 도달했습니다. 잠시 후 다시 시도해주세요."}"""

        val e = assertThrows<WordLookupException> { parseReply("word", 429, reply) }

        assertEquals("API 호출 제한에 도달했습니다. 잠시 후 다시 시도해주세요.", e.message)
    }

    @Test
    fun `unreadable failure mentions the HTTP status`() {
        val e = assertThrows<WordLookupException> { parseReply("word", 502, "<html>Bad gateway</html>") }
        assertTrue(e.message!!.contains("502"))
    }

    @Test
    fun `no meanings is an error`() {
        val e = assertThrows<WordLookupException> { parseReply("xyzzy", 200, """{"word":"xyzzy","meanings":[]}""") }
        assertTrue(e.message!!.contains("xyzzy"))
    }

    @Test
    fun `nothing is sent without an API key`() =
        runTest {
            val lookup = AnkiNewCardLookup(apiKey = { null })
            assertFailsWith<MissingApiKeyException> { lookup.lookUp("cupboard") }
        }
}
