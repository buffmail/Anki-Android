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

import com.ichi2.anki.wear.GeminiEtymologyLookup.Companion.parseReply
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeminiEtymologyLookupTest {
    @Test
    fun `only the final text is shown with thoughts excluded`() {
        val reply = """{"candidates":[{"finishReason":"STOP","content":{"parts":[
            {"thought":true,"text":"thinking"},
            {"text":"From Old French garderobe: "},
            {"text":"guard + clothes, unrelated to war."}
        ]}}]}"""

        assertEquals("From Old French garderobe: guard + clothes, unrelated to war.", parseReply(200, reply))
    }

    @Test
    fun `empty blocked truncated and malformed responses can be retried`() {
        for (body in listOf(
            "<html>Bad gateway</html>",
            "{}",
            """{"promptFeedback":{"blockReason":"SAFETY"}}""",
            """{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"From Old"}]}}]}""",
            """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"  "}]}}]}""",
        )) {
            assertFailsWith<WordLookupException> { parseReply(200, body) }
        }
    }

    @Test
    fun `service errors never echo keys or request contents`() {
        for (code in listOf(400, 401, 403, 429, 500)) {
            val error =
                assertFailsWith<WordLookupException> {
                    parseReply(code, """{"error":{"message":"secret-api-key"}}""")
                }
            assertFalse(error.message!!.contains("secret-api-key"))
            assertTrue(error.message!!.contains("Gemini"))
        }
    }

    @Test
    fun `word is JSON escaped separately from the etymology instructions`() {
        val word = "a\"b\nc"
        val json = Json.parseToJsonElement(GeminiEtymologyLookup.requestBody(word)) as JsonObject
        val contents = json["contents"] as JsonArray
        val parts = (contents.single() as JsonObject)["parts"] as JsonArray
        assertEquals(word, ((parts.single() as JsonObject)["text"] as JsonPrimitive).content)
        assertTrue(json["systemInstruction"].toString().contains("Never invent a word split"))
    }

    @Test
    fun `no request is made without a key`() =
        runTest {
            for (key in listOf(null, "", "  ")) {
                assertFailsWith<MissingApiKeyException> { GeminiEtymologyLookup { key }.lookUp("wardrobe") }
            }
        }
}
