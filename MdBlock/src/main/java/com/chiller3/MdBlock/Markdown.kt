/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.custota.ui

/**
 * A tiny, dependency-free Markdown parser that turns text into a neutral block/span model. It is
 * intentionally pure Kotlin (no Android types) so it can be unit-tested in isolation; the Compose
 * layer renders the model.
 *
 * Supported subset (enough for OTA release notes):
 *   - ATX headings (`#`..`######`)
 *   - Horizontal rules (`---`, `***`, `___`)
 *   - Unordered lists (`-`, `*`, `+`)
 *   - Ordered lists (`1.`, `2.`, ...)
 *   - Fenced code blocks (```)
 *   - Paragraphs (blank-line separated; soft-wrapped lines are joined)
 *   - Inline: **bold**, *italic* or _italic_, `code`, [text](url)
 *
 * Anything not recognized is treated as plain paragraph text.
 */
sealed interface MdBlock
data class MdHeading(val level: Int, val spans: List<MdSpan>) : MdBlock
data class MdParagraph(val spans: List<MdSpan>) : MdBlock
data class MdBullet(val spans: List<MdSpan>) : MdBlock
data class MdNumbered(val number: Int, val spans: List<MdSpan>) : MdBlock
data class MdCodeBlock(val text: String) : MdBlock
data object MdDivider : MdBlock

data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

object Markdown {
    private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
    private val HR = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
    private val BULLET = Regex("""^\s*[-*+]\s+(.*)$""")
    private val NUMBERED = Regex("""^\s*(\d+)[.)]\s+(.*)$""")

    fun parse(text: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val paragraph = StringBuilder()

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks.add(MdParagraph(parseInline(paragraph.toString())))
                paragraph.setLength(0)
            }
        }

        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            // Fenced code block.
            if (line.trimStart().startsWith("```")) {
                flushParagraph()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                // Skip the closing fence if present.
                if (i < lines.size) i++
                blocks.add(MdCodeBlock(code.toString()))
                continue
            }

            when {
                line.isBlank() -> flushParagraph()
                HR.matches(line) -> {
                    flushParagraph()
                    blocks.add(MdDivider)
                }
                HEADING.matchEntire(line) != null -> {
                    flushParagraph()
                    val m = HEADING.matchEntire(line)!!
                    blocks.add(MdHeading(m.groupValues[1].length, parseInline(m.groupValues[2].trim())))
                }
                BULLET.matchEntire(line) != null -> {
                    flushParagraph()
                    val m = BULLET.matchEntire(line)!!
                    blocks.add(MdBullet(parseInline(m.groupValues[1].trim())))
                }
                NUMBERED.matchEntire(line) != null -> {
                    flushParagraph()
                    val m = NUMBERED.matchEntire(line)!!
                    val number = m.groupValues[1].toIntOrNull() ?: 1
                    blocks.add(MdNumbered(number, parseInline(m.groupValues[2].trim())))
                }
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(line.trim())
                }
            }
            i++
        }

        flushParagraph()
        return blocks
    }

    fun parseInline(text: String): List<MdSpan> {
        val spans = mutableListOf<MdSpan>()
        val buf = StringBuilder()
        var i = 0

        fun flush() {
            if (buf.isNotEmpty()) {
                spans.add(MdSpan(buf.toString()))
                buf.setLength(0)
            }
        }

        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length -> {
                    // Escaped character: take the next char literally.
                    buf.append(text[i + 1])
                    i += 2
                }
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        flush()
                        spans.add(MdSpan(text.substring(i + 1, end), code = true))
                        i = end + 1
                    } else {
                        buf.append(c); i++
                    }
                }
                c == '[' -> {
                    val close = text.indexOf(']', i + 1)
                    if (close > i && close + 1 < text.length && text[close + 1] == '(') {
                        val paren = text.indexOf(')', close + 2)
                        if (paren > close + 1) {
                            flush()
                            spans.add(MdSpan(
                                text.substring(i + 1, close),
                                link = text.substring(close + 2, paren),
                            ))
                            i = paren + 1
                        } else {
                            buf.append(c); i++
                        }
                    } else {
                        buf.append(c); i++
                    }
                }
                c == '*' && i + 1 < text.length && text[i + 1] == '*' -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 1) {
                        flush()
                        spans.add(MdSpan(text.substring(i + 2, end), bold = true))
                        i = end + 2
                    } else {
                        buf.append(c); i++
                    }
                }
                c == '*' || c == '_' -> {
                    val end = text.indexOf(c, i + 1)
                    if (end > i + 1) {
                        flush()
                        spans.add(MdSpan(text.substring(i + 1, end), italic = true))
                        i = end + 1
                    } else {
                        buf.append(c); i++
                    }
                }
                else -> {
                    buf.append(c); i++
                }
            }
        }

        flush()
        return spans
    }
}
