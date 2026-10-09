package com.haoai.agent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.haoai.agent.platform.FileEntry
import com.haoai.agent.ui.UiFilePath
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 批3a：工作区文件树（全屏壳，恒深色——与全屏代码壳同一语言）。
 *
 * 懒加载：children 只存展开过的目录（listDir 单层）→ [flattenTree] 现算扁平行；
 * 大工作区不整树灌内存。搜索：walk 缓存 + 200ms 防抖 + 扫完核对当前词防错位。
 * 点文件 → [resolve]（批2c 安全口径）得绝对路径 → FileViewerDialog 只读查看；
 * 解析为 null（SAF 后端无文件系统根）→ Toast 降级。
 * locateRel：查看器「在文件树中定位」递来的目标——展开祖先链→滚动→高亮 2s。
 */
@Composable
internal fun FileTreeDialog(
    workspaceName: String,
    backendReady: Boolean,
    listDir: suspend (String) -> List<String>,
    walk: suspend () -> List<FileEntry>,
    resolve: (String) -> UiFilePath?,
    locateRel: String?,
    onLocateConsumed: () -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var children by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var expanded by remember { mutableStateOf<Set<String>>(setOf("")) }
    var loading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<String>?>(null) }
    var locatedPath by remember { mutableStateOf<String?>(null) }
    var viewerRel by remember { mutableStateOf<String?>(null) }
    var walkCache by remember { mutableStateOf<List<FileEntry>?>(null) }
    val listState = rememberLazyListState()

    // 打开即载根
    LaunchedEffect(Unit) {
        if (backendReady) {
            loading = true
            children = mapOf("" to listDir(""))
            loading = false
        }
    }

    // 搜索防抖 200ms：walk 可能几秒，逐键起协程会堆叠
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
            for (dir in ancestorsOf(rel).dropLast(1)) {
                if (!loaded.containsKey(dir)) loaded[dir] = listDir(dir)
                open.add(dir)
            }
            children = loaded
            expanded = open
            query = ""; searchResults = null
            val idx = flattenTree(loaded, open).indexOfFirst { it.path == rel }
            if (idx >= 0) listState.scrollToItem(idx + 1) // +1 = 搜索框行
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF10151A))
                .statusBarsPadding()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "文件树",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE8EEEA)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    workspaceName,
                    fontSize = 11.sp,
                    color = Color(0xFF9AA8A0),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = Color(0xFF7BD88F),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.size(10.dp))
                }
                Text(
                    "关闭",
                    fontSize = 12.sp,
                    color = Color(0xFF9AA8A0),
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { onDismiss() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color(0xFF1B232C))
                    .padding(horizontal = 10.dp, vertical = 9.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = Color(0xFF9AA8A0),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = Color(0xFFE8EEEA),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(Color(0xFF7BD88F)),
                        modifier = Modifier.weight(1f)
                    )
                    if (query.isEmpty()) {
                        Text(
                            "搜索工作区文件…",
                            fontSize = 13.sp,
                            color = Color(0xFF5C6A64)
                        )
                    }
                }
            }
            if (!backendReady) {
                Text(
                    "未绑定工作空间\n（设置 → 工作空间 里选择一个目录）",
                    fontSize = 13.sp,
                    color = Color(0xFF9AA8A0),
                    modifier = Modifier.padding(24.dp)
                )
            } else {
                val rows = searchResults?.map {
                    TreeRow(it, it.substringAfterLast('/'), false, 0)
                } ?: flattenTree(children, expanded)
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(rows, key = { it.path }) { row ->
                        val located = locatedPath == row.path
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(if (located) Color(0x337BD88F) else Color.Transparent)
                                .clickable {
                                    if (row.isDir) {
                                        if (searchResults == null) toggleDir(row.path)
                                    } else {
                                        if (resolve(row.path) != null) viewerRel = row.path
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
                                    tint = Color(0xFF9AA8A0),
                                    modifier = Modifier
                                        .size(14.dp)
                                        .rotate(if (row.path in expanded) 0f else -90f)
                                )
                                Spacer(Modifier.size(4.dp))
                                Icon(
                                    Icons.Filled.Folder,
                                    contentDescription = null,
                                    tint = Color(0xFF7BC6A5),
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(Modifier.size(7.dp))
                                Text(
                                    row.name + "/",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFDCE7E1),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            } else {
                                Spacer(Modifier.size(18.dp))
                                Icon(
                                    Icons.Filled.Description,
                                    contentDescription = null,
                                    tint = Color(0xFF5C6A64),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.size(7.dp))
                                Text(
                                    if (searchResults != null) row.path else row.name,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (searchResults != null) Color(0xFFB8C4BF)
                                    else Color(0xFFC8D2CD),
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
                                color = Color(0xFF5C6A64),
                                modifier = Modifier.padding(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // 点文件 → 只读查看器（叠在树之上，关掉回到树）
    viewerRel?.let { rel ->
        resolve(rel)?.let { ref ->
            FileViewerDialog(ref = ref, onDismiss = { viewerRel = null })
        }
    }
}
