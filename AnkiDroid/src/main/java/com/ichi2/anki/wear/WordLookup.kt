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

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What anki-new-card says about a word: its meanings, each with an example, and the IPA. */
data class WordDefinition(
    val meanings: List<Meaning>,
    val pronunciation: String?,
) {
    data class Meaning(
        val meaning: String,
        val example: String,
    )
}

/** Looks up English words. Swapped for a fake in tests. */
fun interface WordLookup {
    /**
     * @throws MissingApiKeyException if no Gemini API key is set
     * @throws WordLookupException with a message for the user
     */
    suspend fun lookUp(word: String): WordDefinition
}

class MissingApiKeyException : Exception("No Gemini API key")

class WordLookupException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Looks up words with anki-new-card (https://anki-new-card.vercel.app), the service behind the
 * website and `ank.py`, so that cards added from the watch look the same as the others.
 *
 * The Gemini API key goes in the request body, as the website and `ank.py` send it.
 */
class AnkiNewCardLookup(
    private val apiKey: () -> String?,
) : WordLookup {
    override suspend fun lookUp(word: String): WordDefinition {
        val key = apiKey() ?: throw MissingApiKeyException()
        val body =
            buildJsonObject {
                put("word", word)
                put("apiKey", key)
            }.toString().toRequestBody(JSON)
        val request =
            Request
                .Builder()
                .url(URL)
                .post(body)
                .build()
        return withContext(Dispatchers.IO) {
            val (code, reply) =
                try {
                    client.newCall(request).execute().use { it.code to it.body.string() }
                } catch (e: IOException) {
                    Timber.w(e, "anki-new-card request failed")
                    throw WordLookupException("Couldn't reach anki-new-card. Is the phone online?", e)
                }
            parseReply(word, code, reply)
        }
    }

    companion object {
        const val URL = "https://anki-new-card.vercel.app/api/gemini"
        private val JSON = "application/json".toMediaType()

        /** The watch waits 30s: leave time for the reply to get back to it. */
        private val client =
            OkHttpClient
                .Builder()
                .callTimeout(25, TimeUnit.SECONDS)
                .build()

        /**
         * Reads the reply: `{"word", "pronunciation", "meanings": [{"meaning", "example"}]}`, or
         * `{"error"}` on failure. Like `ank.py`, it accepts `meanings` as an object keyed by index,
         * which Gemini sometimes returns.
         */
        @VisibleForTesting
        fun parseReply(
            word: String,
            code: Int,
            body: String,
        ): WordDefinition {
            val json =
                try {
                    Json.parseToJsonElement(body) as? JsonObject
                } catch (e: SerializationException) {
                    null
                }
            val error = json?.get("error")?.stringOrNull()
            if (code !in 200..299 || error != null) {
                Timber.w("anki-new-card replied %d: %s", code, error)
                throw WordLookupException(error ?: "anki-new-card failed (HTTP $code).")
            }
            if (json == null) throw WordLookupException("anki-new-card sent an unreadable reply.")

            val meanings =
                when (val raw = json["meanings"]) {
                    is JsonArray -> raw.toList()
                    is JsonObject -> raw.entries.sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }.map { it.value }
                    else -> emptyList()
                }.mapNotNull { it.toMeaning() }
            if (meanings.isEmpty()) throw WordLookupException("No definition found for \"$word\".")
            return WordDefinition(meanings, json["pronunciation"]?.stringOrNull()?.takeIf { it.isNotBlank() })
        }

        private fun JsonElement.toMeaning(): WordDefinition.Meaning? {
            val meaning: String?
            val example: String?
            when (this) {
                is JsonObject -> {
                    meaning = this["meaning"]?.stringOrNull()
                    example = this["example"]?.stringOrNull()
                }
                is JsonPrimitive -> {
                    meaning = contentOrNull
                    example = null
                }
                else -> return null
            }
            if (meaning.isNullOrBlank()) return null
            return WordDefinition.Meaning(meaning.trim(), example?.trim().orEmpty())
        }

        private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
    }
}
