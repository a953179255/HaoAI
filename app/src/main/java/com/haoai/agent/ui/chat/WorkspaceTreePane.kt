package com.haoai.agent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haoai.agent.platform.FileEntry
import com.haoai.agent.ui.UiFilePath
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 工作区壳·目录 Tab（原 FileTreeDialog 主体，壳色改主题）。
 * 懒加载逐层展开 + 搜索（walk 缓存 200ms 防抖）+「在目录中定位」展开祖先链高亮 2s。
 */
@Composable
internal fun WorkspaceTreePane(
    theme: WorkspaceShellTheme,
    backendReady: Boolean,
    listDir: suspend (String) -> List<String>,
    walk: suspend () -> List<FileEntry>,
    resolve: (String) -> UiFilePath?,
    locateRel: String?,
    onLocateConsumed: () -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var children by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var expanded by remember { mutableStateOf<Set<String>>(setOf("")) }
    var loading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<String>?>(null) }
    var locatedPath by remember { mutableStateOf<String?>(null) }
    var walkCache by remember { mutableStateOf<List<FileEntry>?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        if (backendReady) {
            loading = true
            children = mapOf("" to listDir(""))
            loading = false
        }
    }

    LaunchedEffect(query) {
        val q = query
        if (q.isBlank()) { searchResults = null; return@LaunchedEffect }
        delay(200)
        if (query != q) return@LaunchedEffect
        val entries = walkCache ?: walk().also { walkCache = it }
        if (query != q) return@LaunchedEffect
        searchResults = searchPaths(entries, q)
    }

    // 定位请求：展开祖先链（缺的层现拉）→ 滚动 → 高亮 2s 后熄
    LaunchedEffect(locateRel) {
        val rel = locateRel ?: return@LaunchedEffect
        if (backendReady) {
            val loaded = children.toMutableMap()
            val open = (expanded + "").toMutableSet()
            // ancestorsOf 口径=文件的全部祖先目录含父级（单测钉死："/site/page.html"→["","site"]）。
            // 批3a 旧代码 dropLast(1) 是把它当成"含文件自身"误读——恰好验收目标是根级文件
            // （[""]→剪后为空、根本就展开）才没暴露；嵌套文件永远展不开父级。此处修口径。
            for (dir in ancestorsOf(rel)) {
                if (!loaded.containsKey(dir)) loaded[dir] = listDir(dir)
                open.add(dir)
            }
            children = loaded
            expanded = open
            query = ""; searchResults = null
            val idx = flattenTree(loaded, open).indexOfFirst { it.path == rel }
            if (idx >= 0) listState.scrollToItem(idx)
            locatedPath = rel
            delay(2000)
            if (locatedPath == rel) locatedPath = null
        }
        onLocateConsumed()
    }

    fun toggleDir(path: String) {
        if (path in expanded) {
            expanded = expanded - path
        } else {
            expanded = expanded + path
            if (!children.containsKey(path)) {
                scope.launch { children = children + (path to listDir(path)) }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 搜索框
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(theme.searchBg)
                .padding(horizontal = 10.dp, vertical = 9.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = theme.sub,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.size(8.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = theme.text,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    cursorBrush = SolidColor(theme.accent),
                    modifier = Modifier.weight(1f)
                )
                if (query.isEmpty()) {
                    Text(
                        "搜索工作区文件…",
                        fontSize = 13.sp,
                        color = theme.faint
                    )
                }
            }
        }
        if (!backendReady) {
            Text(
                "未绑定工作空间\n（设置 → 工作区 里选择一个目录）",
                fontSize = 13.sp,
                color = theme.sub,
                lineHeight = 19.sp,
                modifier = Modifier.padding(24.dp)
            )
            return@Column
        }
        val rows = searchResults?.map {
            TreeRow(it, it.substringAfterLast('/'), false, 0)
        } ?: flattenTree(children, expanded)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(rows, key = { it.path }) { row ->
                val located = locatedPath == row.path
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (located) theme.accentSoft else Color.Transparent)
                        .clickable {
                            if (row.isDir) {
                                if (searchResults == null) toggleDir(row.path)
                            } else {
                                if (resolve(row.path) != null) onOpenFile(row.path)
                                else android.widget.Toast
                                    .makeText(ctx, "SAF 工作区暂不支持预览", android.widget.Toast.LENGTH_SHORT)
                                    .show()
                            }
                        }
                        .padding(
                            start = (14 + row.depth * 16).dp,
                            end = 14.dp,
                            top = 7.dp,
                            bottom = 7.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (row.isDir) {
                        Icon(
                            Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = theme.sub,
                            modifier = Modifier
                                .size(14.dp)
                                .rotate(if (row.path in expanded) 0f else -90f)
                        )
                        Spacer(Modifier.size(4.dp))
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = null,
                            tint = theme.folder,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.size(7.dp))
                        Text(
                            row.name + "/",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = theme.text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Spacer(Modifier.size(18.dp))
                        Icon(
                            Icons.Filled.Description,
                            contentDescription = null,
                            tint = theme.faint,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.size(7.dp))
                        Text(
                            if (searchResults != null) row.path else row.name,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = theme.body,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (rows.isEmpty()) {
                item {
                    Text(
                        if (searchResults != null) "没有匹配「$query」的文件"
                        else if (loading) "" else "（目录为空）",
                        fontSize = 12.sp,
                        color = theme.faint,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }
        }
    }
}
