package com.haoai.agent.ui.common.richtext

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

/**
 * 方案 A 渲染管线第一段：Markdown 源文本 → 标准 HTML（intellij-markdown fork）。
 *
 * 用 上游 同款 com.github.上游:markdown fork：
 * - GFM 完整语法（表格/删除线/任务列表/自动链接）
 * - LaTeX 定界符 \(..\)/\[..\] 归一为 $..$/$$..$$（fork 内建，代码块内跳过）
 *
 * 解析为纯函数（输入决定输出），可在 Dispatchers.Default 后台线程调用。
 * HTML 只是中间表示（IR），后续由 [HtmlNodeRenderer] 映射为 Compose 原生组件。
 */
object MarkdownPipeline {

    private val flavour by lazy {
        GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true)
    }
    private val parser by lazy { MarkdownParser(flavour) }

    /** Markdown → HTML。线程安全（flavour/parser 内部无共享可变状态）。 */
    fun toHtml(markdown: String): String {
        return try {
            val tree = parser.buildMarkdownTreeFromString(markdown)
            HtmlGenerator(markdown, tree, flavour).generateHtml()
        } catch (t: Throwable) {
            // 解析失败兜底：整段按一个段落文本输出，绝不丢内容
            "<p>${escapeHtml(markdown)}</p>"
        }
    }

    /** 流式期快速探测：源文本是否含 HTML 实体风险字符由 fork 处理，此处仅做最外层兜底转义。 */
    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
