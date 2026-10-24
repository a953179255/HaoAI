package com.haoai.agent.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import com.haoai.agent.ui.theme.haoIsDark

/**
 * 类型化文件图标（2026-10-24 查看器改版）：此前文件列表/查看器所有文件共用同一
 * 文档图标，格式只能靠后缀分辨（用户实测反馈）。按扩展名给出 专属图标 + 明暗
 * 两个主题色；调用方用 [FileTypeBadge] 画「彩底彩图标」徽标。
 */
data class FileVisual(val icon: ImageVector, val light: Color, val dark: Color)

private val CODE_EXTS = setOf(
    "kt", "kts", "java", "py", "ts", "tsx", "js", "jsx", "rs", "go", "c", "h",
    "cpp", "hpp", "cs", "swift", "rb", "php", "sh", "bash", "zsh", "sql", "css", "scss", "dart"
)

fun fileVisualOf(name: String): FileVisual {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        ext == "md" || ext == "markdown" ->
            FileVisual(Icons.Filled.Article, Color(0xFF1EA84F), Color(0xFF4ADE80))
        ext in CODE_EXTS ->
            FileVisual(Icons.Filled.Code, Color(0xFF1B66C9), Color(0xFF9FC3FF))
        ext == "json" || ext == "xml" || ext == "yaml" || ext == "yml" || ext == "toml" ->
            FileVisual(Icons.Filled.DataObject, Color(0xFFC77E1A), Color(0xFFE8B36A))
        ext == "html" || ext == "htm" || ext == "vue" || ext == "svelte" ->
            FileVisual(Icons.Filled.Language, Color(0xFFEA580C), Color(0xFFFB923C))
        ext in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "svg", "ico") ->
            FileVisual(Icons.Filled.Image, Color(0xFF8B5CF6), Color(0xFFC4B5FD))
        ext in setOf("mp3", "wav", "flac", "ogg", "m4a", "aac") ->
            FileVisual(Icons.Filled.MusicNote, Color(0xFFDB2777), Color(0xFFF9A8D4))
        ext in setOf("mp4", "mov", "avi", "mkv", "webm") ->
            FileVisual(Icons.Filled.Movie, Color(0xFFDC2626), Color(0xFFFCA5A5))
        ext in setOf("zip", "7z", "rar", "tar", "gz", "jar", "apk", "aab") ->
            FileVisual(Icons.Filled.Archive, Color(0xFF64748B), Color(0xFF94A3B8))
        ext == "pdf" ->
            FileVisual(Icons.Filled.PictureAsPdf, Color(0xFFDC2626), Color(0xFFFCA5A5))
        else ->
            FileVisual(Icons.Filled.InsertDriveFile, Color(0xFF79839C), Color(0xFF8B97AC))
    }
}

/** 彩底彩图标徽标：文件列表行 / 查看器顶栏 / ZIP 清单行共用一套视觉。 */
@Composable
fun FileTypeBadge(name: String, size: Dp, corner: Dp = size * 0.32f, iconSize: Dp = size * 0.5f) {
    val v = fileVisualOf(name)
    val tint = if (haoIsDark()) v.dark else v.light
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(tint.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.fillMaxSize().padding((size - iconSize) / 2)) {
            Icon(v.icon, contentDescription = null, tint = tint, modifier = Modifier.fillMaxSize())
        }
    }
}
