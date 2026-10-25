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

import android.annotation.SuppressLint
import java.util.Locale

/**
 * Builds a vocabulary card the way anki-new-card does (its website's `formatBackContent` and
 * `ank.py`'s `build_meaning_html`): the word on the front, the meanings on the back.
 */
object WordCard {
    private val whitespace = Regex("""\s+""")

    /**
     * The word as it goes on the front: trimmed and lowercase, like the website and `ank.py`
     * store it. Punctuation around it is dropped ("Cupboard." from voice input → "cupboard").
     */
    // English words: the phone's locale mustn't change them (Turkish lowercases "I" to "ı")
    @SuppressLint("LocaleRootUsage")
    fun normalize(input: String): String =
        input
            .replace(whitespace, " ")
            .lowercase(Locale.ROOT)
            .trim { !it.isLetterOrDigit() }

    /**
     * ```
     * meaning<br><span style="font-size: small;"><i> - example</i></span><br><br>
     * meaning 2<br>...<br><br><span style="color: #666; font-size: small;">/ipa/</span>
     * ```
     */
    fun back(definition: WordDefinition): String =
        buildString {
            definition.meanings.forEachIndexed { index, meaning ->
                if (index > 0) append("<br><br>")
                append(escape(meaning.meaning))
                if (meaning.example.isNotEmpty()) {
                    append("<br><span style=\"font-size: small;\"><i> - ")
                    append(escape(meaning.example))
                    append("</i></span>")
                }
            }
            definition.pronunciation?.let {
                append("<br><br><span style=\"color: #666; font-size: small;\">")
                append(escape(it))
                append("</span>")
            }
        }

    /** Gemini's text is plain text: keep a stray `<` from turning into HTML. */
    private fun escape(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
}
