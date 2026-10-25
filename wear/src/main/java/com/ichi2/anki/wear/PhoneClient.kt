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

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.ichi2.anki.wear.protocol.WearProtocol
import com.ichi2.anki.wear.protocol.WearRequest
import com.ichi2.anki.wear.protocol.WearResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException

/** Thrown when the phone can't be reached; [messageRes] explains why to the user. */
class PhoneUnavailableException(
    @StringRes val messageRes: Int,
    cause: Throwable? = null,
) : Exception(cause)

/** Talks to AnkiDroid on the paired phone (its `WearBridgeService`). */
class PhoneClient(
    context: Context,
) {
    private val messageClient = Wearable.getMessageClient(context)
    private val capabilityClient = Wearable.getCapabilityClient(context)
    private val nodeClient = Wearable.getNodeClient(context)

    /** @throws PhoneUnavailableException */
    suspend fun send(request: WearRequest): WearResponse {
        val phoneId = findPhone()
        val reply =
            try {
                withTimeout(TIMEOUT_MILLIS) {
                    messageClient.sendRequest(phoneId, WearProtocol.RPC_PATH, WearProtocol.encode(request)).await()
                }
            } catch (e: TimeoutCancellationException) {
                throw PhoneUnavailableException(R.string.error_phone_timeout, e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Request to phone failed", e)
                throw PhoneUnavailableException(R.string.error_phone_not_connected, e)
            }
        return try {
            WearProtocol.decodeResponse(reply)
        } catch (e: SerializationException) {
            Log.w(TAG, "Unreadable reply from phone", e)
            throw PhoneUnavailableException(R.string.error_app_mismatch, e)
        }
    }

    private suspend fun findPhone(): String {
        val phones =
            try {
                capabilityClient
                    .getCapability(WearProtocol.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                    .await()
                    .nodes
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Capability lookup failed", e)
                throw PhoneUnavailableException(R.string.error_phone_not_connected, e)
            }
        // prefer a Bluetooth connection over a cloud one
        val phone = phones.firstOrNull { it.isNearby } ?: phones.firstOrNull()
        if (phone != null) return phone.id

        val anyPhoneConnected = runCatching { nodeClient.connectedNodes.await() }.getOrNull().orEmpty().isNotEmpty()
        throw PhoneUnavailableException(
            if (anyPhoneConnected) R.string.error_phone_app_missing else R.string.error_phone_not_connected,
        )
    }

    companion object {
        private const val TAG = "AnkiWear"

        /** The first request may have to start AnkiDroid and open the collection. */
        private const val TIMEOUT_MILLIS = 15_000L
    }
}
