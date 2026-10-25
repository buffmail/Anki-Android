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

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ichi2.anki.wear.protocol.WearRequest
import com.ichi2.anki.wear.protocol.WearResponse

/** An error message: either one of ours, or text sent by the phone. */
sealed interface UiMessage {
    data class Resource(
        @StringRes val id: Int,
    ) : UiMessage

    data class Text(
        val text: String,
    ) : UiMessage
}

@Composable
fun UiMessage.asString(): String =
    when (this) {
        is UiMessage.Resource -> stringResource(id)
        is UiMessage.Text -> text
    }

/** The reply to [request] if it has type [T]; otherwise the error to show. */
suspend inline fun <reified T : WearResponse> PhoneClient.sendForResult(
    request: WearRequest,
    timeoutMillis: Long = PhoneClient.DEFAULT_TIMEOUT_MILLIS,
): Result<T> =
    try {
        when (val response = send(request, timeoutMillis)) {
            is T -> Result.success(response)
            is WearResponse.Error -> Result.failure(PhoneError(UiMessage.Text(response.message)))
            else -> Result.failure(PhoneError(UiMessage.Resource(R.string.error_app_mismatch)))
        }
    } catch (e: PhoneUnavailableException) {
        Result.failure(PhoneError(UiMessage.Resource(e.messageRes)))
    }

class PhoneError(
    val uiMessage: UiMessage,
) : Exception()

val Throwable.uiMessage: UiMessage
    get() = (this as? PhoneError)?.uiMessage ?: UiMessage.Text(message ?: javaClass.simpleName)
