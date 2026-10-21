package com.haoai.agent.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * HaoAI 手绘线稿图标库（2026-10-14 图标治理，用户选「全手绘线稿」）。
 *
 * 病根：App 此前所有操作图标都是 Material **Filled 实心款**（Close/Add/Send/Stop/Delete/More…），
 * 那是给桌面工具用的中性默认脸，堆在小尺寸移动端 + 玻璃拟态上就是「一眼 AI 模板」。
 * 这里用统一栅格（24×24 viewport）+ 1.9f 圆头描边重画一套线稿，风格与液态玻璃呼应。
 *
 * 实现口径：全部 [ImageVector]，`Icon(icon, tint=…)` 的 ColorFilter 对描边与填充同时生效
 * （SolidColor 的基准色 Black 只是占位，渲染时被 tint 覆盖），因此可无缝替换任何
 * 已接收 `icon: ImageVector` 的按钮件，无需改它们签名、零依赖、每个矢量 < 1KB。
 *
 * ⚠ emoji 也一并收编：[Alert]/[Check] 替代文字里混排的 ⚠✓（另一大 AI 味来源）。
 */
object HaoIcons {

    /** 线稿变体：单色描边、圆头圆角、不填充。[d] 为一段或多段（空格分隔子路径）pathData。 */
    private fun ln(name: String, d: String, w: Float = 1.9f): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).addPath(
            pathData = PathParser().parsePathString(d).toNodes(),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = w,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()

    /** 实心变体：填充、不描边（三点菜单等靠圆点表达的用这个）。 */
    private fun ft(name: String, d: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).addPath(
            pathData = PathParser().parsePathString(d).toNodes(),
            fill = SolidColor(Color.Black),
            stroke = null,
            pathFillType = PathFillType.EvenOdd,
        ).build()

    // ── 导航 / 窗口 ────────────────────────────────────────────────
    /** 返回箭头 ‹（拇指友好的尖角，也用作查看器关闭） */
    val Back = ln("hao_back", "M14.5 5.5 L7.5 12 L14.5 18.5")

    /** 右尖角 ›（版本翻页下一版） */
    val ChevronRight = ln("hao_chevron_right", "M9.5 5.5 L16.5 12 L9.5 18.5")

    /** 上尖角（收起工具栏） */
    val ChevronUp = ln("hao_chevron_up", "M5.5 15 L12 8.5 L18.5 15")

    /** 下尖角（展开） */
    val ChevronDown = ln("hao_chevron_down", "M5.5 9 L12 15.5 L18.5 9")

    /** 关闭 ×（细描边，比实心 Material 轻一档） */
    val Close = ln("hao_close", "M6.6 6.6 L17.4 17.4 M17.4 6.6 L6.6 17.4")
    // 中断横幅徽标（2026-10-22 横幅重设计：旧橙▶徽标与按钮语义抢戏，换琥珀双竖条「暂停」态）
    val Pause = ln("hao_pause", "M9.5 5.8 L9.5 18.2 M14.5 5.8 L14.5 18.2")
    // 压缩交接芯片徽标（信息档：箱体+中缝，表达"历史打包收档"）
    val Archive = ln(
        "hao_archive",
        "M4.5 7.8 L12 4.4 L19.5 7.8 L19.5 16.2 L12 19.6 L4.5 16.2 Z M4.5 7.8 L12 11.2 L19.5 7.8 M12 11.2 L12 19.6"
    )

    /** 加号 ＋ */
    val Add = ln("hao_add", "M12 5.5 L12 18.5 M5.5 12 L18.5 12")

    /** 展开 / 全屏四角括号 */
    val Expand = ln("hao_expand", "M4 9 L4 4 L9 4 M15 4 L20 4 L20 9 M20 15 L20 20 L15 20 M9 20 L4 20 L4 15")

    // ── 消息动作 ──────────────────────────────────────────────────
    /** 发送（纸飞机线稿，替代 AutoMirrored.Filled.Send） */
    val Send = ln("hao_send", "M4.5 12 L20 4.5 L13.5 20 L10.8 14.3 Z")

    /** 停止（生成中，方框线稿） */
    /** 立即发送（排队气泡的抢占动作：向上箭头） */
    val ArrowUp = ln("hao_arrow_up", "M12 19 L12 5.5 M6.5 11 L12 5.5 L17.5 11")
    val Stop = ln("hao_stop", "M7.5 7.5 L16.5 7.5 L16.5 16.5 L7.5 16.5 Z")

    // ── 编辑动作 ──────────────────────────────────────────────────
    /** 删除（垃圾桶线稿） */
    val Delete = ln("hao_delete", "M5 7 L19 7 M9.5 7 L9.5 4.5 L14.5 4.5 L14.5 7 M6.6 7 L7.7 20 L16.3 20 L17.4 7")

    /** 更多（水平三点，实心圆点更稳） */
    val More = ft(
        "hao_more",
        "M5 12 m -1.4 0 a 1.4 1.4 0 1 0 2.8 0 a 1.4 1.4 0 1 0 -2.8 0 Z " +
            "M12 12 m -1.4 0 a 1.4 1.4 0 1 0 2.8 0 a 1.4 1.4 0 1 0 -2.8 0 Z " +
            "M19 12 m -1.4 0 a 1.4 1.4 0 1 0 2.8 0 a 1.4 1.4 0 1 0 -2.8 0 Z",
    )

    /** 复制（前后两框错位线稿） */
    val Copy = ln("hao_copy", "M9 9 L19 9 L19 19 L9 19 Z M5 15 L5 5 L15 5")

    /** 编辑（铅笔斜线，编辑重发用） */
    val Edit = ln("hao_edit", "M4 20 L4.8 16.5 L16 5.3 L18.7 8 L7.5 19.2 Z M13.5 7.8 L16.2 10.5")

    /** 重新生成（270° 环弧 + 右向箭头尖） */
    val Refresh = ln("hao_refresh", "M19 12 A7 7 0 1 1 12 5 L15 5 M12.4 2.6 L15 5 L12.4 7.4")

    /** 保存 / 下载（下箭头入底横，存相册用） */
    val Save = ln("hao_save", "M12 4.5 L12 15 M7 10 L12 15 L17 10 M4.5 19.5 L19.5 19.5")

    /** 分享（上箭头出托盘，iOS 惯例方向） */
    val Share = ln("hao_share", "M12 15.5 L12 4.5 M8 8.5 L12 4.5 L16 8.5 M6 12.5 L6 19 L18 19 L18 12.5")

    // ── 状态 / 媒体 ───────────────────────────────────────────────
    /** 警示三角（替代文字里混排的 ⚠） */
    val Alert = ln("hao_alert", "M12 4.5 L20.5 19.5 L3.5 19.5 Z M12 10 L12 14 M12 16.6 L12 16.61")

    /** 对勾（替代 ✓） */
    val Check = ln("hao_check", "M5 12.5 L10 17.5 L19.5 6.5")

    /** 图片占位（相框 + 山 + 太阳点，替代 🖼） */
    val Image = ln(
        "hao_image",
        "M4 5.5 L20 5.5 L20 18.5 L4 18.5 Z M4 15.5 L9 11.5 L14 16 L17.5 13 L20 15.5 M8.5 9 L8.51 9",
    )

    /** 搜索（圆 + 手柄） */
    val Search = ln(
        "hao_search",
        "M15.5 10.5 A5 5 0 1 1 5.5 10.5 A5 5 0 1 1 15.5 10.5 Z M14.3 14.3 L18.8 18.8",
    )

    /** 附件-图片（相框 + 中加号，输入框「添加图片」用） */
    val AttachImage = ln("hao_attach_image", "M4 6 L20 6 L20 18 L4 18 Z M12 9.5 L12 14.5 M9.5 12 L14.5 12")

    /** 拍照（机身 + 镜头圆） */
    val Camera = ln(
        "hao_camera",
        "M4 8.5 L9 8.5 L10.5 6.5 L13.5 6.5 L15 8.5 L20 8.5 L20 18 L4 18 Z " +
            "M12 11 A3 3 0 1 1 12 17 A3 3 0 1 1 12 11 Z",
    )

    /** 文档（纸张带折角 + 文本行，工具栏「文档」） */
    val Document = ln(
        "hao_document",
        "M6 3.5 L14 3.5 L18 7.5 L18 20.5 L6 20.5 Z M14 3.5 L14 7.5 L18 7.5 " +
            "M8.5 12 L15.5 12 M8.5 15.5 L15.5 15.5 M8.5 18.5 L12.5 18.5",
    )

    /** 视频（摄像机机身 + 取景三角，工具栏「视频」） */
    val Video = ln(
        "hao_video",
        "M3.5 7 L14.5 7 L14.5 17 L3.5 17 Z M14.5 10.5 L20 7.5 L20 16.5 L14.5 13.5",
    )

    /** 音频麦克风（工具栏「音频」/已附音频） */
    val Mic = ln(
        "hao_mic",
        "M12 4 A2.6 2.6 0 1 1 12 9.2 A2.6 2.6 0 1 1 12 4 Z " +
            "M7.5 11.5 A4.5 4.5 0 0 0 16.5 11.5 M12 16 L12 20 M9 20 L15 20",
    )

    /** 引用（双引号，消息菜单「引用」） */
    val Quote = ln(
        "hao_quote",
        "M9.5 6.5 C7 8 5.8 10 5.8 13 L8.8 13 L8.8 17.5 L4.5 17.5 L4.5 12.5 C4.5 8.8 6.4 6.4 9.5 5 Z " +
            "M19.5 6.5 C17 8 15.8 10 15.8 13 L18.8 13 L18.8 17.5 L14.5 17.5 L14.5 12.5 C14.5 8.8 16.4 6.4 19.5 5 Z",
        w = 1.7f,
    )

    /** 网页（地球经纬圈，消息菜单「网页预览」） */
    val Web = ln(
        "hao_web",
        "M12 4 A8 8 0 1 1 12 20 A8 8 0 1 1 12 4 Z " +
            "M4.5 12 L19.5 12 M12 4 C14.5 6.5 15.5 9 15.5 12 C15.5 15 14.5 17.5 12 20 " +
            "M12 4 C9.5 6.5 8.5 9 8.5 12 C8.5 15 9.5 17.5 12 20",
        w = 1.6f,
    )

    /** 代码（`</>` 尖角加斜线，消息菜单「复制代码」） */
    val Code = ln("hao_code", "M8.5 7.5 L4 12 L8.5 16.5 M15.5 7.5 L20 12 L15.5 16.5 M13.6 5.5 L10.4 18.5")

    // ── 中断横幅 / 顶部通知（2026-10-14 横幅治理）──────────────────
    /** 播放三角（实心，中断横幅「继续」与橙圆徽标用） */
    val Play = ft("hao_play", "M8 5.5 L19 12 L8 18.5 Z")

    /** 密钥（钥匙圈+齿，401 密钥失效通知） */
    val Key = ln(
        "hao_key",
        "M12 4 A4 4 0 1 0 12 12 A4 4 0 1 0 12 4 Z M14.8 9.2 L20 14.4 L20 17 L17.4 17 L15.6 15.2 L14 16.8",
        w = 1.8f,
    )

    /** 沙漏（429 限流/等待类通知） */
    val Hourglass = ln(
        "hao_hourglass",
        "M7 4.5 L17 4.5 M7 19.5 L17 19.5 M8 4.5 L8 8 C8 10.2 12 11.2 12 12 C12 13.2 8 14 8 16 L8 19.5 " +
            "M16 4.5 L16 8 C16 10.2 12 11.2 12 12 C12 13.2 16 14 16 16 L16 19.5",
        w = 1.7f,
    )

    /** 断网（Wi-Fi 弧+斜线，域名解析/超时通知） */
    val WifiOff = ln(
        "hao_wifi_off",
        "M4.5 9.2 A11 11 0 0 1 10 6.4 M14 6.4 A11 11 0 0 1 19.5 9.2 " +
            "M7.5 12.3 A7 7 0 0 1 16.5 12.3 M10.2 15.3 A3 3 0 0 1 13.8 15.3 " +
            "M12 18.8 L12 18.81 M4 4 L20 20",
        w = 1.7f,
    )
}
