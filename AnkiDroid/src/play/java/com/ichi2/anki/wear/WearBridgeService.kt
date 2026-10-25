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

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * Receives requests from the AnkiDroid watch app over the Wear OS Data Layer.
 *
 * Google Play services starts this service on demand, even when AnkiDroid isn't running.
 * Only the `play` flavor has it, so the F-Droid (`full`) build stays free of Play services.
 *
 * The watch app must have the same application ID and signing key as this app, otherwise
 * Play services never delivers its requests here.
 */
class WearBridgeService : WearableListenerService() {
    /**
     * Called on a background thread owned by Play services, one request at a time.
     * Blocking it keeps the service alive until the reply is ready.
     */
    override fun onRequest(
        nodeId: String,
        path: String,
        request: ByteArray,
    ): Task<ByteArray> {
        Timber.d("Watch request on %s", path)
        val reply = runBlocking { WearRequestHandler.handle(path, request) }
        return Tasks.forResult(reply)
    }
}
