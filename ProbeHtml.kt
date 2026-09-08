import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.intellij.markdown.html.HtmlGenerator

object ProbeHtml {
    @JvmStatic
    fun main(args: Array<String>) {
        val flavour = GFMFlavourDescriptor()
        val parser = MarkdownParser(flavour)
        val src = "# Title\n\$\$\\int_{-1}^{1} x\$\$\ninline \$e^{i\\pi}+1=0\$ mix\n- top\n  - sub\n    - subsub\n- top2\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n```python\ndef x(): pass\n```"
        val tree = parser.buildMarkdownTreeFromString(src)
        val html = HtmlGenerator(src, tree, flavour).generateHtml()
        println(html)
    }
}
