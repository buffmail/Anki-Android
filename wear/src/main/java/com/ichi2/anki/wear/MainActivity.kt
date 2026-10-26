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
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.ichi2.anki.wear.ui.AddWordScreen
import com.ichi2.anki.wear.ui.ChooseDeckScreen
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
private const val WORD_ARG = "word"

/** `add`, or `add?word=...` to look a word up straight away (a word held on a card). */
private const val ADD_WORD_ROUTE = "add?$WORD_ARG={$WORD_ARG}"
private const val ADD_WORD_DECK_ROUTE = "add/deck"

@Composable
private fun WearApp() {
    val context = LocalContext.current
    val phone = remember { PhoneClient(context.applicationContext) }
    val navController = rememberSwipeDismissableNavController()

    MaterialTheme {
        AppScaffold {
            SwipeDismissableNavHost(
                navController = navController,
                startDestination = DECKS_ROUTE,
            ) {
                composable(DECKS_ROUTE) {
                    DeckListScreen(
                        viewModel = viewModel { DeckListViewModel(phone) },
                        onDeckClick = { deck -> navController.navigate("study/${deck.id}") },
                        onAddWord = { navController.navigate("add") },
                    )
                }
                composable(
                    ADD_WORD_ROUTE,
                    arguments =
                        listOf(
                            navArgument(WORD_ARG) {
                                type = NavType.StringType
                                nullable = true
                            },
                        ),
                ) { entry ->
                    // shared with the deck list, which syncs when shown again after a word was added
                    val decksEntry = remember(entry) { navController.getBackStackEntry(DECKS_ROUTE) }
                    val deckListViewModel = viewModel(decksEntry) { DeckListViewModel(phone) }
                    val word = entry.arguments?.getString(WORD_ARG)
                    AddWordScreen(
                        viewModel = viewModel { addWordViewModel(context, phone, word) },
                        onChooseDeck = { navController.navigate(ADD_WORD_DECK_ROUTE) },
                        onWordAdded = deckListViewModel::onWordAdded,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(ADD_WORD_DECK_ROUTE) { entry ->
                    // the deck is picked for the word shown on the previous screen
                    val addWordEntry = remember(entry) { navController.getBackStackEntry(ADD_WORD_ROUTE) }
                    ChooseDeckScreen(
                        viewModel = viewModel(addWordEntry) { addWordViewModel(context, phone) },
                        onChosen = { navController.popBackStack() },
                    )
                }
                composable(
                    STUDY_ROUTE,
                    arguments = listOf(navArgument(DECK_ID_ARG) { type = NavType.LongType }),
                ) { entry ->
                    val deckId = entry.arguments?.getLong(DECK_ID_ARG) ?: return@composable
                    StudyScreen(
                        viewModel = viewModel { StudyViewModel(phone, deckId) },
                        onAddWord = { word -> navController.navigate("add?$WORD_ARG=${Uri.encode(word)}") },
                    )
                }
            }
        }
    }
}

private fun addWordViewModel(
    context: Context,
    phone: PhoneClient,
    word: String? = null,
) = AddWordViewModel(
    phone,
    context.applicationContext.getSharedPreferences(AddWordViewModel.PREFS_NAME, Context.MODE_PRIVATE),
    initialWord = word,
)
