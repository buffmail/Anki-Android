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

package com.ichi2.anki.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.ichi2.anki.wear.DeckListState
import com.ichi2.anki.wear.DeckListViewModel
import com.ichi2.anki.wear.R
import com.ichi2.anki.wear.SyncState
import com.ichi2.anki.wear.asString
import com.ichi2.anki.wear.protocol.WatchDeck

@Composable
fun DeckListScreen(
    viewModel: DeckListViewModel,
    onDeckClick: (WatchDeck) -> Unit,
    onAddWord: () -> Unit,
) {
    // coming back from studying: sync with AnkiWeb, then refresh the counts
    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        onPauseOrDispose { }
    }

    when (val state = viewModel.state) {
        DeckListState.Loading -> LoadingScreen()
        is DeckListState.Failed ->
            MessageScreen(
                title = stringResource(R.string.error_title),
                message = state.message.asString(),
                buttonText = stringResource(R.string.retry),
                onButtonClick = viewModel::refresh,
            )
        is DeckListState.Loaded ->
            DeckList(
                decks = state.decks,
                syncState = viewModel.syncState,
                onDeckClick = { deck ->
                    viewModel.onStudyStarted()
                    onDeckClick(deck)
                },
                onAddWord = onAddWord,
                onRefresh = viewModel::refresh,
            )
    }
}

@Composable
private fun DeckList(
    decks: List<WatchDeck>,
    syncState: SyncState,
    onDeckClick: (WatchDeck) -> Unit,
    onAddWord: () -> Unit,
    onRefresh: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    // the phone's collection is busy while syncing
    val isSyncing = syncState is SyncState.Syncing

    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(onClick = onRefresh, buttonSize = EdgeButtonSize.ExtraSmall, enabled = !isSyncing) {
                Text(stringResource(R.string.refresh))
            }
        },
    ) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                ListHeader(
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                ) {
                    Text(stringResource(if (isSyncing) R.string.syncing else R.string.decks))
                }
            }
            item {
                Button(
                    onClick = onAddWord,
                    enabled = !isSyncing,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    label = { Text(stringResource(R.string.add_word)) },
                )
            }
            if (syncState is SyncState.Problem) {
                item {
                    Text(
                        syncState.message.asString(),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (decks.isEmpty()) {
                item {
                    Text(stringResource(R.string.no_decks), textAlign = TextAlign.Center)
                }
            }
            items(decks, key = { it.id }) { deck ->
                Button(
                    onClick = { onDeckClick(deck) },
                    enabled = !isSyncing,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = (deck.depth * 12).dp)
                            .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    colors =
                        if (deck.counts.total > 0) {
                            ButtonDefaults.filledTonalButtonColors()
                        } else {
                            ButtonDefaults.childButtonColors()
                        },
                    label = { Text(deck.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = { Text(countsText(deck.counts)) },
                )
            }
        }
    }
}
