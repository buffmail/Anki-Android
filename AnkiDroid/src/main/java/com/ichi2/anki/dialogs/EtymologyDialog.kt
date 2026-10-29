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

package com.ichi2.anki.dialogs

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ichi2.anki.R
import com.ichi2.anki.dialogs.EtymologyDialogViewModel.State
import com.ichi2.anki.libanki.CardId
import kotlinx.coroutines.launch

/** Shows a word origin separately from the study card and answer buttons. */
class EtymologyDialog : DialogFragment() {
    private val viewModel: EtymologyDialogViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        viewModel.load()
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.etymology)
            .setMessage(R.string.loading_etymology)
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.retry, null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        val alert = requireDialog() as AlertDialog
        alert.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { viewModel.load() }
        render(viewModel.state.value)
    }

    private fun render(state: State) {
        val alert = dialog as? AlertDialog ?: return
        val message =
            when (state) {
                State.Idle, State.Loading -> getString(R.string.loading_etymology)
                is State.Loaded -> "${state.word}\n\n${state.explanation}"
                is State.Failed -> state.details ?: getString(state.messageRes)
            }
        alert.setMessage(message)
        alert.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(state is State.Loaded)
        alert.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility =
            if (state is State.Failed && state.canRetry) View.VISIBLE else View.GONE
    }

    companion object {
        fun newInstance(cardId: CardId): EtymologyDialog =
            EtymologyDialog().apply {
                arguments = bundleOf(EtymologyDialogViewModel.ARG_CARD_ID to cardId)
            }
    }
}
