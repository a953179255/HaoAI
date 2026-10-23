package com.haoai.agent.ui.settings

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haoai.agent.ui.common.GlassAlertDialog
import com.haoai.agent.ui.theme.haoToneMain
import com.haoai.agent.ui.theme.HaoTone
import com.kyant.backdrop.backdrops.LayerBackdrop
import java.io.File

/**
 * App 内真实路径目录浏览器（三档改造 2026-10-23：「自选位置」唯一入口）。
 * 只列目录（文件由工作区浏览页负责）；起点公共存储根，向上止步于该处。
 * ⚠ GlassAlertDialog 是 fillMaxSize 遮罩类弹层——必须页面级挂载（弹窗铁律），
 * LazyColumn item 内只留入口回调。
 */
@Composable
fun WorkspaceDirPickerDialog(
    backdrop: LayerBackdrop,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // 起点固定公共存储根：再往上是 /storage（无普通权限语义），向上键到根即隐藏
    var dir by remember { mutableStateOf(Environment.getExternalStorageDirectory()) }
    var subdirs by remember { mutableStateOf<List<File>>(emptyList()) }
    var readable by remember { mutableStateOf(true) }
    // 切目录时的加载空窗：点 A 后 dir 立刻变，但 subdirs 要等 IO 回来——若这期间仍显示
    // 旧列表，旧 A 行的涟漪动画还挂在屏上，看着就像「下一级默认选中了 A」（2026-10-23 实测）。
    // 解决：进入新目录的瞬间清空旧列表并显 loading，新列表读好再填。
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(dir) {
        loading = true
        subdirs = emptyList() // 立即清屏，绝不让旧列表带着在途按压动画过渡到新目录
        val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching<Pair<Boolean, List<File>>> {
                (dir.canRead() && dir.isDirectory) to
                    (dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
                        ?.sortedWith(compareBy({ !it.name.startsWith("Download") && !it.name.startsWith("Documents") }, { it.name.lowercase() }))
                        .orEmpty())
            }.getOrDefault(true to emptyList())
        }
        readable = loaded.first
        subdirs = loaded.second
        loading = false
    }

    val atStorageRoot = dir.absolutePath == Environment.getExternalStorageDirectory().absolutePath

    GlassAlertDialog(
        backdrop = backdrop,
        title = "选择目录",
        onDismiss = onDismiss,
        confirmLabel = "选这个目录",
        onConfirm = { onPick(dir.absolutePath) },
        contentMaxHeight = 430.dp
    ) {
        // 当前路径 + 向上一级
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!atStorageRoot) {
                Icon(
                    Icons.Filled.ArrowUpward,
                    contentDescription = "向上一级",
                    tint = haoToneMain(HaoTone.Accent),
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { dir = dir.parentFile ?: dir }
                )
                Spacer(Modifier.size(8.dp))
            }
            Text(
                dir.absolutePath,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.size(6.dp))
        if (loading) {
            // 目录切换空窗：静态文案（项目禁常驻无限动画，且 listdir 本就毫秒级）
            Text(
                "读取中…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 4.dp)
            )
        } else if (!readable) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = haoToneMain(HaoTone.Warn),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "无法读取此目录（缺「所有文件管理」权限或目录不可访问）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (subdirs.isEmpty()) {
            Text(
                "此目录下没有子目录",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 4.dp)
            )
        } else {
            // ⚠ 不能用 LazyColumn：GlassAlertDialog 的 content 已在 verticalScroll 列
            // 里（实测：嵌套 lazy 被无限高约束测量直接崩 App），目录数量有限走普通 Column。
            // 每行必须 key(absolutePath)：无 key 时切目录，Compose 按「位置」复用组合槽——
            // 上一级被点行（A）的涟漪动画还没淡出，就被下一级「同一位置」的行继承，
            // 表现为「进下级页面后该行默认带点击效果」（2026-10-23 实测）。
            // 加 key 后每个目录条目独占槽：切入子目录时子项路径全不同 → 旧 A 行随切换销毁、
            // 涟漪一并消失，不再串位到同位置的新行。
            Column(modifier = Modifier.fillMaxWidth()) {
                subdirs.forEach { sub ->
                    key(sub.absolutePath) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { dir = sub }
                                .padding(horizontal = 4.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Folder,
                                contentDescription = null,
                                tint = haoToneMain(HaoTone.Accent),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.size(10.dp))
                            Text(
                                sub.name,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
