package com.haoai.agent.ui.sessions

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.haoai.agent.data.StoredSession

/** 内容命中：会话 + 首个命中消息的摘要（±radius 字符窗口，命中词高亮由 UI 层处理）。 */
data class SessionContentHit(val session: StoredSession, val snippet: String)

data class SessionSearchResult(
    val titleHits: List<StoredSession>,
    val contentHits: List<SessionContentHit>,
)

/**
 * 定位 [query] 在 [text] 中首个出现位置，返回 ±[radius] 字符窗口：换行折叠为空格，
 * 截断处加省略号；无命中返回 null。（上游 snippetAround 同款）
 */
fun snippetAround(text: String, query: String, radius: Int = 50): String? {
    if (query.isBlank() || text.isEmpty()) return null
    val lower = text.lowercase()
    val pos = lower.indexOf(query.lowercase())
    if (pos < 0) return null
    val start = (pos - radius).coerceAtLeast(0)
    val end = (pos + query.length + radius).coerceAtMost(text.length)
    val core = text.substring(start, end).replace('\n', ' ').replace('\r', ' ')
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < text.length) "…" else ""
    return prefix + core + suffix
}

/**
 * 双模式搜索：标题命中优先（不再扫正文）；标题未命中的会话才扫 user/assistant
 * 消息正文，每会话取首个命中消息的摘要。（上游 同策略：标题已命中无需摘要）
 */
fun searchSessions(sessions: List<StoredSession>, query: String): SessionSearchResult {
    val q = query.trim()
    if (q.isBlank()) return SessionSearchResult(emptyList(), emptyList())
    val titleHits = mutableListOf<StoredSession>()
    val contentHits = mutableListOf<SessionContentHit>()
    for (s in sessions) {
        if (s.title.contains(q, ignoreCase = true)) {
            titleHits += s
            continue
        }
        val hit = s.messages.firstOrNull { m ->
            (m.role == "user" || m.role == "assistant") &&
                m.content.contains(q, ignoreCase = true)
        }?.let { m -> snippetAround(m.content, q)?.let { SessionContentHit(s, it) } }
        if (hit != null) contentHits += hit
    }
    return SessionSearchResult(titleHits, contentHits)
}

/**
 * 构建把 [query] 所有不区分大小写命中处标上 tertiaryContainer 背景的 [AnnotatedString]；
 * 空白 query 原样返回。（上游 highlightedAnnotatedString 同款）
 */
@Composable
fun highlightedAnnotatedString(text: String, query: String): AnnotatedString {
    if (query.isBlank() || text.isEmpty()) return AnnotatedString(text)
    val highlightBg = MaterialTheme.colorScheme.tertiaryContainer
    val highlightFg = MaterialTheme.colorScheme.onTertiaryContainer
    val lower = text.lowercase()
    val q = query.lowercase()
    return buildAnnotatedString {
        var idx = 0
        while (idx < text.length) {
            val match = lower.indexOf(q, idx)
            if (match < 0) {
                append(text.substring(idx))
                break
            }
            if (match > idx) append(text.substring(idx, match))
            withStyle(SpanStyle(background = highlightBg, color = highlightFg)) {
                append(text.substring(match, match + q.length))
            }
            idx = match + q.length
        }
    }
}
