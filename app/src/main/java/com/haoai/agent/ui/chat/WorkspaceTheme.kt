package com.haoai.agent.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.haoai.agent.ui.theme.haoIsDark

/**
 * 工作区浏览页的配色档（批4b 从退役的 WorkspaceShell 抽出独立成文件，
 * 供 WorkspaceBrowser + FileViewerDialog 共用）。一律从 MaterialTheme 派生，
 * 深浅随 App 主题自动翻；folder/del 两枚是语义/装饰色，深浅各一档常量，
 * 保证压在两种底上都有对比。
 */
class WorkspaceShellTheme(
    val bg: Color,
    val text: Color,
    val sub: Color,
    val body: Color,
    val faint: Color,
    val searchBg: Color,
    val folder: Color,
    val accent: Color,
    val accentSoft: Color,
    val del: Color,
    val delBg: Color,
    val info: Color,
    val ctx: Color,
)

@Composable
fun workspaceShellTheme(): WorkspaceShellTheme {
    val cs = MaterialTheme.colorScheme
    val dark = haoIsDark()
    return WorkspaceShellTheme(
        bg = cs.background,
        text = cs.onBackground,
        sub = cs.onSurfaceVariant,
        body = cs.onSurface,
        faint = cs.outline,
        searchBg = cs.surfaceVariant,
        folder = if (dark) Color(0xFF7BC6A5) else Color(0xFF2F7D4F),
        accent = cs.primary,
        accentSoft = cs.primary.copy(alpha = if (dark) 0.20f else 0.12f),
        del = if (dark) Color(0xFFE88A8A) else Color(0xFF8C1D18),
        delBg = if (dark) Color(0x1FE88A8A) else Color(0x14C62828),
        info = if (dark) Color(0xFF8AB4F8) else Color(0xFF1A63C9),
        ctx = cs.onSurfaceVariant,
    )
}
