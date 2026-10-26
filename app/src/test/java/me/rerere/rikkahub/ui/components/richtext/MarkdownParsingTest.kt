package me.rerere.rikkahub.ui.components.richtext

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParsingTest {
    @Test
    fun `math source is escaped before HTML parsing`() {
        val formulas = listOf("a<b", "x<y>z", "a&b", "\\lambda(x)<k")
        val markdown = formulas.joinToString(" and ") { "$" + it + "$" }
        assertEquals(formulas, Jsoup.parse(generateMarkdownHtml(markdown)).select("span.math").map { it.text() })
    }

    @Test
    fun `display math keeps cases comparisons and backslash line breaks`() {
        val formula = """\begin{cases}1/2, & 0<x<1\\ \infty, & 1\le x<2\end{cases}"""
        val span = Jsoup.parse(generateMarkdownHtml("$$\n$formula\n$$")).selectFirst("span.math")!!
        assertEquals(formula, span.text())
        assertEquals("false", span.attr("inline"))
    }

    @Test
    fun `normalization preserves escaped slashes and trims formula delimiters`() {
        assertEquals("${'$'}x${'$'} and ${'$'}${'$'}a b${'$'}${'$'}", preprocessMarkdown("\\( x \\) and \\[a\nb\\]"))
        assertEquals("${'$'}${'$'}a\\\\[4pt]b${'$'}${'$'} and ${'$'}${'$'}c${'$'}${'$'}", preprocessMarkdown("${'$'}${'$'}a\\\\[4pt]b${'$'}${'$'} and \\[c\\]"))
    }

    @Test
    fun `earlier replacements cannot shift code protection`() {
        val code = "`\\[do not change\\]`"
        assertEquals("${'$'}x${'$'} $code ${'$'}${'$'}y${'$'}${'$'}", preprocessMarkdown("\\(x\\) $code \\[y\\]"))
    }

    @Test
    fun `fenced inline indented and unfinished code remain literal`() {
        for (code in listOf("```\n\\[a\\]\n```", "~~~\n\\[a\\]\n~~~", "`\\(a\\)`", "    \\[a\\]", "```\n\\[a\\]")) {
            assertEquals(code, preprocessMarkdown(code))
            assertTrue(Jsoup.parse(generateMarkdownHtml(code)).select("span.math").isEmpty())
        }
    }

    @Test
    fun `CRLF fenced code closes and following formula parses`() {
        val html = Jsoup.parse(generateMarkdownHtml("```\r\ncode\r\n```\r\n\r\n\\( x<y \\)"))
        assertEquals("code\n", html.selectFirst("code")!!.wholeText())
        assertEquals("x<y", html.selectFirst("span.math")!!.text())
    }

    @Test
    fun `LaTeX renderer removes only supported row spacing suffixes`() {
        assertEquals("a\\\\b", processLatex("a\\\\[4pt]b"))
        assertEquals("a\\\\b", processLatex("${'$'}${'$'} a\\\\[-.5em]b ${'$'}${'$'}"))
        assertEquals("[4pt]", processLatex("[4pt]"))
        assertEquals("a\\\\[label]b", processLatex("a\\\\[label]b"))
    }
}
