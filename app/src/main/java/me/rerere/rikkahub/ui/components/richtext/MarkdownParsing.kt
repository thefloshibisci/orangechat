package me.rerere.rikkahub.ui.components.richtext

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.GeneratingProvider
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.html.ImageGeneratingProvider
import org.intellij.markdown.html.TransparentInlineHolderProvider
import org.intellij.markdown.parser.LinkMap
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.parser.Tag

private val htmlFlavour = GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true)
private val lineEndingRegex = Regex("""\r\n?""")
private val latexDelimiterRegex = Regex("""\\\\|\\\((.+?)\\\)|\\\[([\s\S]+?)\\]""")
private val latexLineBreakRegex = Regex("""[ \t]*\n[ \t]*""")

/** Normalize both render paths in one pass, keeping original code offsets valid. */
internal fun preprocessMarkdown(content: String): String {
    val normalized = content.replace(lineEndingRegex, "\n")
    if ('\\' !in normalized) return normalized
    val codeRanges = mutableListOf<IntRange>()
    fun collectCode(node: ASTNode) {
        if (node.type == MarkdownElementTypes.CODE_FENCE || node.type == MarkdownElementTypes.CODE_BLOCK ||
            node.type == MarkdownElementTypes.CODE_SPAN
        ) {
            codeRanges += node.startOffset until node.endOffset
        } else {
            node.children.forEach(::collectCode)
        }
    }
    collectCode(MarkdownParser(htmlFlavour).buildMarkdownTreeFromString(normalized))
    return latexDelimiterRegex.replace(normalized) { match ->
        if (codeRanges.any { match.range.first <= it.last && match.range.last >= it.first }) {
            match.value
        } else {
            val inline = match.groups[1]?.value?.trim()
            val block = match.groups[2]?.value?.trim()
            when {
                inline != null -> if (inline.isEmpty()) match.value else "$" + inline + "$"
                block != null -> if (block.isEmpty()) match.value
                    else "$$" + block.replace(latexLineBreakRegex, " ") + "$$"
                else -> match.value // A paired backslash is a LaTeX line break, not a delimiter.
            }
        }
    }
}

internal fun generateMarkdownHtml(content: String, imageDescriptionsOnly: Boolean = false): String {
    val text = preprocessMarkdown(content)
    val tree = MarkdownParser(htmlFlavour).buildMarkdownTreeFromString(text)
    val linkMap = LinkMap.buildLinkMap(tree, text)
    val providers = htmlFlavour.createHtmlGeneratingProviders(linkMap, null) + mapOf(
        MarkdownTokenTypes.HTML_TAG to InlineHtmlTagProvider,
        GFMElementTypes.INLINE_MATH to EscapedMathProvider(inline = true),
        GFMElementTypes.BLOCK_MATH to EscapedMathProvider(inline = false),
    ) + if (imageDescriptionsOnly) mapOf(MarkdownElementTypes.IMAGE to SpokenImageProvider(linkMap)) else emptyMap()
    return HtmlGenerator(text, tree, providers, false).generateHtml()
}

/** The bundled image provider strips non-ASCII alt text; speech needs the original label only. */
private class SpokenImageProvider(linkMap: LinkMap) : GeneratingProvider {
    private val imageResolver = ImageGeneratingProvider(linkMap, null)
    private val labelProvider = TransparentInlineHolderProvider(1, -1)

    override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
        val label = imageResolver.getRenderInfo(text, node)?.label ?: return
        labelProvider.processNode(visitor, text, label)
    }
}

private val htmlTagNameRegex = Regex("""^</?([a-zA-Z][a-zA-Z0-9-]*)""")

private object InlineHtmlTagProvider : GeneratingProvider {
    override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
        val tag = node.getTextInNode(text)
        val name = htmlTagNameRegex.find(tag)?.groupValues?.get(1)
        visitor.consumeHtml(if (name != null && !Tag.isKnownTag(name.lowercase())) escapeHtml(tag) else tag)
    }
}

private class EscapedMathProvider(private val inline: Boolean) : GeneratingProvider {
    override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
        val formula = node.children.drop(1).dropLast(1).joinToString("") { it.getTextInNode(text) }
        var backticks = 0
        while (formula.length - 2 * backticks > 2 &&
            formula[backticks] == '`' && formula[formula.lastIndex - backticks] == '`'
        ) backticks++
        val source = formula.substring(backticks, formula.length - backticks).trim()
        visitor.consumeTagOpen(node, "span", "class=\"math\"", "inline=\"$inline\"")
        visitor.consumeHtml(escapeHtml(source))
        visitor.consumeTagClose("span")
    }
}

private fun escapeHtml(text: CharSequence): String =
    text.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
