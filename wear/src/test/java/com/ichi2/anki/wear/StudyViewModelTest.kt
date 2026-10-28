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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.wear.protocol.WatchCard
import com.ichi2.anki.wear.protocol.WatchCounts
import com.ichi2.anki.wear.protocol.WatchQueue
import com.ichi2.anki.wear.protocol.WatchRating
import com.ichi2.anki.wear.protocol.WearRequest
import com.ichi2.anki.wear.protocol.WearResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudyViewModelTest {
    private val phone = mockk<PhoneClient>()
    private val card = WatchCard(7, "wardrobe", "a cabinet for clothes", false, "<1m", "1d", WatchQueue.NEW)
    private val study = WearResponse.Study("English", WatchCounts(1, 0, 0), card)

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        coEvery { phone.send(WearRequest.NextCard(42), any()) } returns study
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `etymology is fetched once only after revealing`() =
        runTest {
            val response = WearResponse.Etymology(7, "wardrobe", "From Old French garderobe.")
            coEvery { phone.send(WearRequest.GetEtymology(7), any()) } returns response
            val model = StudyViewModel(phone, 42)
            runCurrent()
            model.loadEtymology()
            coVerify(exactly = 0) { phone.send(ofType<WearRequest.GetEtymology>(), any()) }

            model.reveal()
            model.loadEtymology()
            model.loadEtymology()
            assertEquals(EtymologyState.Loading, showing(model).etymology)
            runCurrent()
            model.loadEtymology()
            runCurrent()

            assertEquals(EtymologyState.Loaded(response.explanation), showing(model).etymology)
            assertTrue(showing(model).revealed)
            coVerify(exactly = 1) { phone.send(WearRequest.GetEtymology(7), PhoneClient.LOOKUP_TIMEOUT_MILLIS) }
        }

    @Test
    fun `missing key keeps the card and can be retried`() =
        runTest {
            coEvery { phone.send(WearRequest.GetEtymology(7), any()) } returnsMany
                listOf(
                    WearResponse.NoApiKey,
                    WearResponse.Etymology(7, "wardrobe", "Old French garderobe."),
                )
            val model = StudyViewModel(phone, 42)
            runCurrent()
            model.reveal()
            model.loadEtymology()
            runCurrent()

            assertEquals(EtymologyState.Failed(UiMessage.Resource(R.string.no_api_key)), showing(model).etymology)
            assertTrue(showing(model).revealed)
            model.loadEtymology()
            runCurrent()
            assertEquals(EtymologyState.Loaded("Old French garderobe."), showing(model).etymology)
        }

    @Test
    fun `late reply cannot attach when Again returns the same card`() =
        runTest {
            val pending = CompletableDeferred<WearResponse>()
            coEvery { phone.send(WearRequest.GetEtymology(7), any()) } coAnswers {
                // Emulate a transport finishing a reply even after cancellation.
                withContext(NonCancellable) { pending.await() }
            }
            coEvery { phone.send(ofType<WearRequest.Answer>(), any()) } returns study
            val model = StudyViewModel(phone, 42)
            runCurrent()
            model.reveal()
            val firstSequence = showing(model).sequence
            model.loadEtymology()
            runCurrent()
            model.answer(WatchRating.AGAIN)
            runCurrent()
            pending.complete(WearResponse.Etymology(7, "wardrobe", "Old reply"))
            runCurrent()

            assertTrue(showing(model).sequence > firstSequence)
            assertFalse(showing(model).revealed)
            assertEquals(EtymologyState.Idle, showing(model).etymology)
        }

    private fun showing(model: StudyViewModel) = model.state as StudyState.Showing
}
