package com.haoai.agent.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.ui.graphics.vector.ImageVector
data class SlashCommand(
    val name: String,
    val aliases: List<String> = emptyList(),
    val description: String,
    val icon: ImageVector,
    val takesText: Boolean = false,
    val group: String = "通用"
)

object SlashCommands {

    val all = listOf(
        SlashCommand(
            name = "compact",
            aliases = listOf("summarize"),
            description = "压缩会话上下文，释放 token 空间",
            icon = Icons.Filled.Backup,
            group = "会话"
        ),
        SlashCommand(
            name = "clear",
            description = "清空当前会话，开始新对话",
            icon = Icons.Filled.DeleteForever,
            group = "会话"
        ),
        SlashCommand(
            name = "stop",
            description = "停止当前正在运行的任务",
            icon = Icons.Filled.Pause,
            group = "会话"
        ),
        SlashCommand(
            name = "plan",
            description = "计划模式：只产出计划不执行改动，再输一次退出",
            icon = Icons.Filled.FactCheck,
            group = "会话"
        ),
        SlashCommand(
            name = "undo",
            description = "回滚最近一次文件变更（再输一次可往返）",
            icon = Icons.Filled.Undo,
            group = "会话"
        ),
        SlashCommand(
            name = "model",
            description = "查看或切换当前使用的模型",
            icon = Icons.Filled.SwapHoriz,
            group = "模型"
        ),
        SlashCommand(
            name = "btw",
            aliases = listOf("side"),
            description = "附带问题（不污染主上下文）",
            icon = Icons.Filled.MailOutline,
            takesText = true,
            group = "对话"
        ),
        SlashCommand(
            name = "help",
            description = "显示可用的斜杠命令列表",
            icon = Icons.Filled.HelpOutline,
            group = "通用"
        ),
        SlashCommand(
            name = "status",
            description = "显示当前会话状态信息",
            icon = Icons.Filled.AccountTree,
            group = "通用"
        ),
        SlashCommand(
            name = "task",
            aliases = listOf("todo", "tasks"),
            description = "查看任务清单",
            icon = Icons.Filled.AddCircle,
            group = "通用"
        )
    )

    fun parse(input: String): Pair<SlashCommand, String>? {
        val trimmed = input.trim()
        if (!trimmed.startsWith("/")) return null
        val parts = trimmed.split("\\s+".toRegex(), limit = 2)
        val cmdName = parts[0].removePrefix("/").lowercase()
        val arg = parts.getOrElse(1) { "" }
        val cmd = all.find { it.name == cmdName || cmdName in it.aliases }
            ?: return null
        return cmd to arg
    }

    fun filter(query: String): List<SlashCommand> {
        if (query.isBlank()) return all
        val q = query.lowercase().removePrefix("/")
        return all.filter {
            it.name.contains(q) || it.aliases.any { a -> a.contains(q) } ||
                it.description.contains(q)
        }
    }
}
