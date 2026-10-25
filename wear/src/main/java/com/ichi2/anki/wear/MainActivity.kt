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

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.ichi2.anki.wear.ui.DeckListScreen
import com.ichi2.anki.wear.ui.StudyScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WearApp() }
    }
}

private const val DECKS_ROUTE = "decks"
private const val DECK_ID_ARG = "deckId"
private const val STUDY_ROUTE = "study/{$DECK_ID_ARG}"

@Composable
private fun WearApp() {
    val context = LocalContext.current
    val phone = remember { PhoneClient(context.applicationContext) }
    val navController = rememberSwipeDismissableNavController()
    // Off while swiping answers a card: swiping right means Good there, not "back".
    // The watch's back button still goes back.
    var swipeBackEnabled by remember { mutableStateOf(true) }

    MaterialTheme {
        AppScaffold {
            SwipeDismissableNavHost(
                navController = navController,
                startDestination = DECKS_ROUTE,
                userSwipeEnabled = swipeBackEnabled,
            ) {
                composable(DECKS_ROUTE) {
                    DeckListScreen(
                        viewModel = viewModel { DeckListViewModel(phone) },
                        onDeckClick = { deck -> navController.navigate("study/${deck.id}") },
                    )
                }
                composable(
                    STUDY_ROUTE,
                    arguments = listOf(navArgument(DECK_ID_ARG) { type = NavType.LongType }),
                ) { entry ->
                    val deckId = entry.arguments?.getLong(DECK_ID_ARG) ?: return@composable
                    StudyScreen(
                        viewModel = viewModel { StudyViewModel(phone, deckId) },
                        onSwipeToAnswerChanged = { active -> swipeBackEnabled = !active },
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}
