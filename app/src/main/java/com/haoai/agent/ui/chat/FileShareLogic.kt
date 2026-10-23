package com.haoai.agent.ui.chat

/**
 * 批4a（2026-10-23 文件管理瘦身·对标定稿）：查看器外发动作行的纯逻辑层。
 *
 * 对标结论：7 家移动端 Agent 全部有「把文件带出沙箱」的通道（OpenMinis Open
 * with+CreateDocument、Aether 另存、rikkahub OTHER 交系统、PokeClaw send_file…），
 * HaoAI 此前独缺——FileProvider 只覆盖拍照临时目录，产物只能"模型能看见、
 * 人拿不走"。本批补底部动作行，口径见效果图
 * `HaoAI-文件管理瘦身效果图-2026-10-23.html` 栏③。
 *
 * 本文件只做三件纯事：该显示哪几键、各键叫什么、MIME 是什么；意图构造与
 * 流拷贝在 [FileViewerDialog] 侧（那边是组合态，不可单测）。
 */
enum class ViewerAction { RenderView, Share, SaveAs, OpenWith, SaveToAlbum }

/** 与查看器图片分流同源（FileViewerDialog.isImage 用同一集合，勿漂移）。 */
val IMAGE_VIEWER_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp")
private val HTML_VIEWER_EXTS = setOf("html", "htm")

/**
 * 动作分流（效果图栏③口径）：
 * - 图片=分享/另存为/存到相册/图库打开（渲染看无意义，相册替代）
 * - HTML=多一枚「渲染看」（复用 HtmlPreviewDialog 的 FilePage 通道）
 * - 其余文本=分享/另存为/其他应用
 */
fun viewerActions(extLower: String): List<ViewerAction> = when {
    extLower in IMAGE_VIEWER_EXTS -> listOf(
        ViewerAction.Share, ViewerAction.SaveAs, ViewerAction.SaveToAlbum, ViewerAction.OpenWith
    )
    extLower in HTML_VIEWER_EXTS -> listOf(
        ViewerAction.RenderView, ViewerAction.Share, ViewerAction.SaveAs, ViewerAction.OpenWith
    )
    else -> listOf(ViewerAction.Share, ViewerAction.SaveAs, ViewerAction.OpenWith)
}

fun ViewerAction.label(): String = when (this) {
    ViewerAction.RenderView -> "渲染看"
    ViewerAction.Share -> "分享"
    ViewerAction.SaveAs -> "另存为"
    ViewerAction.OpenWith -> "其他应用"
    ViewerAction.SaveToAlbum -> "存到相册"
}

/**
 * 扩展名 → MIME（另存为契约与外发 intent 共用一份表）。
 * 未知扩展一律 octet-stream：分享出去宁可"当文件存"，不可谎报类型让人打不开。
 */
fun mimeFromViewerExt(extLower: String): String = when (extLower) {
    "html", "htm" -> "text/html"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    "pdf" -> "application/pdf"
    "zip" -> "application/zip"
    "json" -> "application/json"
    "csv" -> "text/csv"
    "md", "txt", "log", "kt", "kts", "java", "py", "js", "ts", "css", "xml",
    "yaml", "yml", "toml", "ini", "sh", "bash", "gradle", "sql" -> "text/plain"
    else -> "application/octet-stream"
}
