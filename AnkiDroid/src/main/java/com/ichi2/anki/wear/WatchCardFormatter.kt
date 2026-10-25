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

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

/**
 * Turns rendered card HTML into the small HTML subset the watch can display.
 *
 * Wear OS has no WebView, so the watch renders text with `AnnotatedString.fromHtml`. This
 * keeps text and basic formatting (bold, italic, underline, lists, line breaks), highlights
 * cloze deletions, and drops everything else: CSS, scripts, media, inline colors (they are
 * usually picked for a light background) and hidden elements.
 */
object WatchCardFormatter {
    data class FormattedCard(
        val question: String,
        val answer: String,
        /** No `<hr id=answer>` was found, so [answer] repeats the question. */
        val answerIncludesQuestion: Boolean,
    )

    /** Light blue, readable on the watch's black background. */
    const val CLOZE_COLOR = "#4FC3F7"

    /** Wear OS messages are limited to ~100KB. */
    private const val MAX_LENGTH = 30_000

    private val answerSeparator = Regex("""<hr[^>]*\bid\s*=\s*["']?answer\b["']?[^>]*>""", RegexOption.IGNORE_CASE)
    private val avTag = Regex("""\[anki:play:[qa]:\d+]""")
    private val typeAnswer = Regex("""\[\[type:[^\]]*]]""")
    private val edgeBlankLines = Regex("""^(?:\s|<br\s*/?>)+|(?:\s|<br\s*/?>)+$""", RegexOption.IGNORE_CASE)

    private val allowedTags =
        "b strong i em u s strike del sub sup big small br p div blockquote ul ol li h1 h2 h3 h4 h5 h6 font"
            .split(" ")
            .toTypedArray()

    private val safelist =
        Safelist()
            .addTags(*allowedTags)
            .addAttributes("font", "color")

    fun format(
        questionHtml: String,
        answerHtml: String,
    ): FormattedCard {
        val separator = answerSeparator.find(answerHtml)
        val back = if (separator == null) answerHtml else answerHtml.substring(separator.range.last + 1)
        return FormattedCard(
            question = toWatchHtml(questionHtml),
            answer = toWatchHtml(back),
            answerIncludesQuestion = separator == null,
        )
    }

    fun toWatchHtml(html: String): String {
        val document = Jsoup.parseBodyFragment(html.replace(avTag, "").replace(typeAnswer, ""))
        val body = document.body()

        body.select("style, script, link, meta, audio, video, img, svg, object, iframe, input, button, rp, a.hint").remove()
        body.select("[style~=(?i)display\\s*:\\s*none]").remove()

        // Furigana: 漢字<rt>かんじ</rt> -> 漢字(かんじ)
        for (rt in body.select("rt")) {
            rt.replaceWith(TextNode("(${rt.text()})"))
        }

        // User colors are chosen for a light background: drop them before adding our own.
        body.select("font").unwrap()
        for (cloze in body.select(".cloze")) {
            val highlight = Element("font").attr("color", CLOZE_COLOR)
            val bold = highlight.appendElement("b")
            for (child in cloze.childNodes().toList()) {
                bold.appendChild(child)
            }
            cloze.replaceWith(highlight)
        }

        // Tables are flattened: keep cells apart and rows on their own line.
        body.select("td, th").forEach { it.appendText(" ") }
        body.select("tr").forEach { it.appendElement("br") }

        // Clean the parsed tree directly: re-parsing the HTML would move table contents around.
        val cleanDocument = Cleaner(safelist).clean(document)
        cleanDocument.outputSettings().prettyPrint(false)
        val cleaned = cleanDocument.body().html().replace(edgeBlankLines, "")

        return if (cleaned.length <= MAX_LENGTH) {
            cleaned
        } else {
            Entities.escape(Jsoup.parseBodyFragment(cleaned).text().take(MAX_LENGTH))
        }
    }
}
