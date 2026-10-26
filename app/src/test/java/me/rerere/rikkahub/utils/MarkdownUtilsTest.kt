package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownUtilsTest {
    @Test
    fun `HTML markup is not spoken but block boundaries remain`() {
        assertEquals("标题\n正文", "<details><summary>标题</summary><p>正文</p></details>".stripMarkdown())
        assertEquals("第一句\n第二句", "第一句<br/>第二句".stripMarkdown())
        assertEquals("正文重点结束", "正文<b class='x'>重点</b><span>结束</span>".stripMarkdown())
    }

    @Test
    fun `comments style and scripts are not spoken`() {
        assertEquals("正文", "<!-- hidden --><style>.a{color:red}</style><script>alert(1)</script>正文".stripMarkdown())
    }

    @Test
    fun `comparisons literal tags and bracket cues survive`() {
        assertEquals("比较 x<y && y>0", "比较 x<y && y>0".stripMarkdown())
        assertEquals("1 < 2 且 3 > 2", "1 < 2 且 3 > 2".stripMarkdown())
        assertEquals("List<String> 和 <answer>", "List<String> 和 <answer>".stripMarkdown())
        assertEquals("(轻声) 你好 [笑]", "(轻声) 你好 [笑]".stripMarkdown())
    }

    @Test
    fun `entities decode once and retain literal angle brackets`() {
        assertEquals("a < b & c <tag>", "a &lt; b &amp; c &lt;tag&gt;".stripMarkdown())
    }

    @Test
    fun `Markdown links formatting and image descriptions retain readable text`() {
        assertEquals("标题\n正文重点与链接\n猫咪", "# 标题\n\n正文**重点**与[链接](https://example.com)\n\n![猫咪](cat.png)".stripMarkdown())
    }

    @Test
    fun `Unicode image descriptions survive without reading destinations`() {
        assertEquals("猫咪🐱 & 小狗", "![猫咪🐱 &amp; 小狗](cat.png)".stripMarkdown())
        assertEquals("远方的猫", "![远方的猫][cat]\n\n[cat]: https://example.com/cat.png".stripMarkdown())
        assertEquals("猫咪", "![**猫咪**](https://example.com/cat.png)".stripMarkdown())
    }

    @Test
    fun `whitespace normalization preserves English letters and single tabs`() {
        assertEquals("butter letter\ntabs still\ttick", "butter\t\tletter\n\t tabs  still\ttick".stripMarkdown())
    }

    @Test
    fun `code and formula source are not read aloud`() {
        assertEquals("开始 结束\n正文", "开始 `ls` 结束\n\n```kotlin\nval a = 1\n```\n\n正文".stripMarkdown())
        assertEquals("条件 成立", "条件 ${'$'}x<y${'$'} 成立".stripMarkdown())
        assertEquals("开始", "开始\n\n```\n未闭合代码".stripMarkdown())
    }

    @Test
    fun `tables retain cell and row boundaries`() {
        assertEquals("名称 数量\n苹果 2", "|名称|数量|\n|---|---|\n|苹果|2|".stripMarkdown())
    }
}
