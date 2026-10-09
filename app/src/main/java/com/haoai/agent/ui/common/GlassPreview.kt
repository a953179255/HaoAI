package com.haoai.agent.ui.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import android.graphics.Paint

/**
 * 折射测试背景 v2（2026-10-09 预览卡专用重设计）。
 *
 * 旧版（实验室全屏遗留：斜彩条+同心圆+网格+多行文字）塞进 140dp 预览框的
 * 三根小玻璃条后，同心圆被 blur 糊成"一坨坨弧"，失去判读价值（用户实测反馈）。
 * 新设计围绕小玻璃条的观感重新选择元素：
 * - **横向彩条**：模糊度主标尺——blur 越大，条带边界化开越狠（0dp 利落 / 4dp 柔边 /
 *   15dp 均匀雾带），一眼可读；
 * - **竖线**：折射标尺——折射环带会把竖线在左右边缘掰出 S 弯，环带宽度/强度直接可见；
 * - **一行字**：模拟"正文穿玻璃"——blur+白雾下隐约可读，折射在字上产生位移。
 * （同心圆、斜彩条、多行文字全部移除——小框里全是糊斑，2026-10-09 用户反馈）
 *
 * 由 `CanvasBackdrop` 直用（采样时在玻璃坐标系执行，无挂载依赖）：每行玻璃各自
 * 得到完整图案，blur/白雾正常作用。
 *
 * @param opaqueBase true = 画实色底；false = 只画纹理（预览卡：底下是实底面板的表面色）
 */
fun drawRefractionTestPattern(
    scope: DrawScope,
    dark: Boolean,
    opaqueBase: Boolean = true,
    gridDp: Float = 24f
) {
    with(scope) {
        val w = size.width
        val h = size.height
        if (opaqueBase) {
            drawRect(if (dark) Color(0xFF14171C) else Color(0xFFF4F5F7))
        }
        // ── 横向彩条：模糊度主标尺 ──────────────────────────────
        // 低饱和多彩，blur 后成柔和雾带；条带高度随画布高均分（30~44dp 行高约 6 条）
        val stripes = listOf(
            Color(0x4DE57373), Color(0x4DFFB74D), Color(0x4D81C784),
            Color(0x4D64B5F6), Color(0x4DBA68C8), Color(0x4D4DD0C4)
        )
        val bandH = h / 6f
        stripes.forEachIndexed { i, c ->
            drawRect(c, topLeft = Offset(0f, i * bandH), size = Size(w, bandH))
        }
        // ── 竖线：折射标尺（环带处被掰出 S 弯）──────────────────
        val line = if (dark) Color(0xFF8E97A5) else Color(0xFF2A2F38)
        val grid = gridDp.dp.toPx()
        var gx = grid
        while (gx < w) {
            drawLine(line, Offset(gx, 0f), Offset(gx, h), 2f)
            gx += grid
        }
        // ── 一行字：正文穿玻璃（垂直居中）───────────────────────
        val paint = Paint().apply {
            color = if (dark) android.graphics.Color.parseColor("#D7DEE8")
                    else android.graphics.Color.parseColor("#22262E")
            textSize = 44f
            isAntiAlias = true
        }
        val sample = "折射测试 Refraction 120Hz 玻璃扭曲 ABC 123"
        drawContext.canvas.nativeCanvas.drawText(
            sample, 24f, h / 2f + 44f * 0.35f, paint
        )
    }
}
