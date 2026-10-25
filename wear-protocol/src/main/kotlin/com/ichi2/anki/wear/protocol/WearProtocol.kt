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

package com.ichi2.anki.wear.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Request/response protocol between AnkiDroid on the phone and the AnkiDroid watch app.
 *
 * The watch sends a [WearRequest] to [RPC_PATH] using the Wear OS Data Layer
 * (`MessageClient.sendRequest`); the phone replies with a [WearResponse].
 *
 * The phone owns the collection and the scheduler. The watch never stores cards: it shows
 * what the phone sends and reports the rating back.
 *
 * Bump [VERSION] on incompatible changes. The path changes with it, so a phone and watch
 * running different versions report a clear error instead of misbehaving.
 */
object WearProtocol {
    const val VERSION = 2
    const val PATH_PREFIX = "/ankidroid"
    const val RPC_PATH = "$PATH_PREFIX/v$VERSION"

    /** Declared by the phone app (`android_wear_capabilities`) so the watch can find it. */
    const val PHONE_CAPABILITY = "ankidroid_study_bridge"

    private val json =
        Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
        }

    fun encode(request: WearRequest): ByteArray = json.encodeToString(WearRequest.serializer(), request).encodeToByteArray()

    fun decodeRequest(bytes: ByteArray): WearRequest = json.decodeFromString(WearRequest.serializer(), bytes.decodeToString())

    fun encode(response: WearResponse): ByteArray = json.encodeToString(WearResponse.serializer(), response).encodeToByteArray()

    fun decodeResponse(bytes: ByteArray): WearResponse = json.decodeFromString(WearResponse.serializer(), bytes.decodeToString())
}

@Serializable
sealed interface WearRequest {
    /** List decks with their due counts. Replied with [WearResponse.Decks]. */
    @Serializable
    @SerialName("decks")
    data object GetDecks : WearRequest

    /** Get the next card to study in [deckId]. Replied with [WearResponse.Study]. */
    @Serializable
    @SerialName("next")
    data class NextCard(
        val deckId: Long,
    ) : WearRequest

    /**
     * Answer [cardId], then get the next card. Replied with [WearResponse.Study].
     *
     * The phone ignores the answer if [cardId] is no longer the next card in its queue (for
     * example because it was studied on the phone in the meantime).
     */
    @Serializable
    @SerialName("answer")
    data class Answer(
        val deckId: Long,
        val cardId: Long,
        val rating: WatchRating,
        val millisTaken: Long,
    ) : WearRequest

    /**
     * Sync the phone's collection with AnkiWeb. Replied with [WearResponse.Synced] once the
     * sync has finished, or after a while if it's still running.
     */
    @Serializable
    @SerialName("sync")
    data object Sync : WearRequest
}

@Serializable
enum class WatchRating {
    AGAIN,
    GOOD,
}

@Serializable
sealed interface WearResponse {
    @Serializable
    @SerialName("decks")
    data class Decks(
        /** Decks in deck-picker order. Children of collapsed decks are left out. */
        val decks: List<WatchDeck>,
    ) : WearResponse

    @Serializable
    @SerialName("study")
    data class Study(
        /** Full deck name, e.g. `Japanese::Vocab`. */
        val deckName: String,
        val counts: WatchCounts,
        /** `null` when there is nothing left to study right now. */
        val card: WatchCard?,
        /** True if the phone ignored the answer because the card had changed. */
        val answerSkipped: Boolean = false,
    ) : WearResponse

    @Serializable
    @SerialName("synced")
    data class Synced(
        val status: SyncStatus,
    ) : WearResponse

    @Serializable
    @SerialName("error")
    data class Error(
        val message: String,
    ) : WearResponse
}

@Serializable
enum class SyncStatus {
    DONE,
    FAILED,

    /** Still running when the phone stopped waiting. */
    STILL_RUNNING,
    NOT_LOGGED_IN,
    OFFLINE,
}

@Serializable
data class WatchDeck(
    val id: Long,
    /** Last part of the deck name: `Vocab` for `Japanese::Vocab`. */
    val name: String,
    /** 0 for top-level decks. */
    val depth: Int,
    val counts: WatchCounts,
)

@Serializable
data class WatchCounts(
    val new: Int,
    val learn: Int,
    val review: Int,
) {
    val total: Int get() = new + learn + review
}

@Serializable
data class WatchCard(
    val id: Long,
    /** Question as simplified HTML (see the phone's `WatchCardFormatter`). */
    val question: String,
    /** Answer as simplified HTML, without the part above `<hr id=answer>`. */
    val answer: String,
    /**
     * True when the answer already contains the question (e.g. cloze cards, or templates
     * without `<hr id=answer>`). Show only [answer] after revealing in that case.
     */
    val answerIncludesQuestion: Boolean,
    /** Next interval for Again, e.g. `<1m`. */
    val againInterval: String,
    /** Next interval for Good, e.g. `3d`. */
    val goodInterval: String,
    val queue: WatchQueue,
)

@Serializable
enum class WatchQueue {
    NEW,
    LEARN,
    REVIEW,
}
