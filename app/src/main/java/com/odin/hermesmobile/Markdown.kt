package com.odin.hermesmobile

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan

/**
 * Tiny markdown renderer: code fences -> separate blocks; inline supports
 * `code`, **bold**, *italic*, ~~strike~~, [text](url), bare http(s) links,
 * #-headers (bold line), "- " bullets (-> "• ").
 */
object Markdown {

    /** A rendered chunk: either a fenced code block or styled prose. */
    data class Block(val code: Boolean, val text: CharSequence)

    private val INLINE = Regex(
        "(`[^`\\n]+`)|(\\*\\*[^*\\n]+\\*\\*)|(\\*[^*\\n]+\\*)|(~~[^~\\n]+~~)|(\\[[^\\]\\n]+\\]\\([^)\\n]+\\))|(https?://[^\\s)\\]]+)"
    )

    private const val ACCENT = 0xFFF5A623.toInt()

    fun blocks(text: String): List<Block> {
        val out = ArrayList<Block>()
        val parts = text.split("```")
        for ((i, part) in parts.withIndex()) {
            val t = part.trim('\n')
            if (t.isBlank()) continue
            out.add(if (i % 2 == 1) Block(true, t.trim()) else Block(false, span(t)))
        }
        return out
    }

    private class Tok(val start: Int, val end: Int, val inner: String, val url: String?, val kind: Int)

    fun span(src: String): SpannableString {
        // Per-line cleanup: strip header hashes (bold the line), swap bullets.
        val lines = src.split("\n")
        val boldLines = HashSet<Int>()
        val cleaned = StringBuilder()
        for ((i, raw) in lines.withIndex()) {
            var line = raw
            val h = Regex("^#{1,6}\\s+").find(line)
            if (h != null) { line = line.removeRange(h.range); boldLines.add(i) }
            line = Regex("^[-*]\\s+").replace(line, "• ")
            cleaned.append(line)
            if (i < lines.size - 1) cleaned.append('\n')
        }
        val base = cleaned.toString()

        // Collect inline tokens on the cleaned text.
        val toks = ArrayList<Tok>()
        for (m in INLINE.findAll(base)) {
            val t = m.value
            val triple = when {
                t.length > 2 && t.startsWith("`") && t.endsWith("`") ->
                    Triple(t.substring(1, t.length - 1), null as String?, CODE)
                t.length > 4 && t.startsWith("**") && t.endsWith("**") ->
                    Triple(t.substring(2, t.length - 2), null as String?, BOLD)
                t.length > 4 && t.startsWith("~~") && t.endsWith("~~") ->
                    Triple(t.substring(2, t.length - 2), null as String?, STRIKE)
                t.length > 2 && t.startsWith("*") && t.endsWith("*") ->
                    Triple(t.substring(1, t.length - 1), null as String?, ITALIC)
                t.startsWith("[") && "](" in t -> {
                    val sep = t.lastIndexOf("](")
                    Triple(t.substring(1, sep), t.substring(sep + 2, t.length - 1), LINK)
                }
                else -> Triple(t, null as String?, BARE)
            }
            toks.add(Tok(m.range.first, m.range.last + 1, triple.first, triple.second, triple.third))
        }

        // Build the final string forward, recording each token's final offset.
        val sb = StringBuilder()
        val placed = ArrayList<Pair<Tok, Int>>()
        var pos = 0
        for (tok in toks) {
            sb.append(base, pos, tok.start)
            placed.add(tok to sb.length)
            sb.append(tok.inner)
            pos = tok.end
        }
        sb.append(base, pos, base.length)

        val s = SpannableString(sb.toString())
        for ((tok, a) in placed) {
            val b = a + tok.inner.length
            when (tok.kind) {
                CODE -> {
                    s.setSpan(TypefaceSpan("monospace"), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    s.setSpan(BackgroundColorSpan(0xFF3A3A3C.toInt()), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                BOLD -> s.setSpan(StyleSpan(Typeface.BOLD), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ITALIC -> s.setSpan(StyleSpan(Typeface.ITALIC), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                STRIKE -> s.setSpan(StrikethroughSpan(), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                LINK -> {
                    s.setSpan(URLSpan(tok.url!!), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    s.setSpan(ForegroundColorSpan(ACCENT), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                BARE -> {
                    s.setSpan(URLSpan(tok.inner), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    s.setSpan(ForegroundColorSpan(ACCENT), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }

        // Bold header lines. Token replacements never cross a newline, so
        // line N of the final string is line N of the cleaned text.
        if (boldLines.isNotEmpty()) {
            val final = sb.toString()
            var lineStart = 0
            var idx = 0
            while (lineStart <= final.length) {
                if (idx in boldLines) {
                    val nl = final.indexOf('\n', lineStart).let { if (it < 0) final.length else it }
                    if (nl > lineStart) {
                        s.setSpan(StyleSpan(Typeface.BOLD), lineStart, nl, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                val next = final.indexOf('\n', lineStart)
                if (next < 0) break
                lineStart = next + 1
                idx++
            }
        }
        return s
    }

    private const val CODE = 0
    private const val BOLD = 1
    private const val ITALIC = 2
    private const val STRIKE = 3
    private const val LINK = 4
    private const val BARE = 5
}
