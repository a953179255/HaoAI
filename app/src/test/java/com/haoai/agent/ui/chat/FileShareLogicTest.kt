package com.haoai.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 批4a：查看器外发动作分流与 MIME 表（纯逻辑，无 Android 依赖）。 */
class FileShareLogicTest {

    @Test
    fun `图片走相册口径不带渲染看`() {
        for (ext in listOf("png", "jpg", "jpeg", "gif", "webp")) {
            val acts = viewerActions(ext)
            assertEquals(listOf(
                ViewerAction.Share, ViewerAction.SaveAs, ViewerAction.SaveToAlbum, ViewerAction.OpenWith
            ), acts)
        }
    }

    @Test
    fun `HTML 多一枚渲染看排在首位`() {
        for (ext in listOf("html", "htm")) {
            val acts = viewerActions(ext)
            assertEquals(4, acts.size)
            assertEquals(ViewerAction.RenderView, acts.first())
            assertFalse(ViewerAction.SaveToAlbum in acts)
        }
    }

    @Test
    fun `普通文本三键`() {
        for (ext in listOf("kt", "json", "md", "csv", "py", "")) {
            assertEquals(
                listOf(ViewerAction.Share, ViewerAction.SaveAs, ViewerAction.OpenWith),
                viewerActions(ext)
            )
        }
    }

    @Test
    fun `MIME 表关键点`() {
        assertEquals("text/html", mimeFromViewerExt("html"))
        assertEquals("image/jpeg", mimeFromViewerExt("jpg"))
        assertEquals("image/svg+xml", mimeFromViewerExt("svg"))
        assertEquals("text/plain", mimeFromViewerExt("md"))
        assertEquals("application/octet-stream", mimeFromViewerExt("xyz"))
        // 未识别类型宁可 octet-stream：不可谎报成 text/plain 让收件应用当代码打开
        assertTrue(mimeFromViewerExt("apk") == "application/octet-stream")
    }

    @Test
    fun `动作文案与效果图一致`() {
        assertEquals("渲染看", ViewerAction.RenderView.label())
        assertEquals("另存为", ViewerAction.SaveAs.label())
        assertEquals("其他应用", ViewerAction.OpenWith.label())
        assertEquals("存到相册", ViewerAction.SaveToAlbum.label())
    }
}
