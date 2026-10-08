package com.haoai.agent.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import android.graphics.Paint

/**
 * 折射测试背景：纯色底上折射=隐形（没有可弯的东西），这块高对比图案专治
 * "看不出扭曲"。三层纹理各司其职：
 * - **直网格线**：折射环带会把穿过它的直线掰弯/挤出位移，是强度判读的主标尺
 * - **对角彩条**：色块边界错位，看折射影响范围（环带宽度）
 * - **密集文字行**：模拟聊天正文穿玻璃的真实场景
 *
 * 玻璃实验室与设置页「主题外观」共用（2026-10-08 从 GlassLabActivity 提取）。
 *
 * @param opaqueBase true = 画实色底（实验室"测试图案"模式，图案自带背景）；
 *   false = 只画纹理不画底（设置页预览框：底下透出壁纸/吸顶玻璃——原先实色底
 *   在壁纸模式下是一块死白直角块，且白底上磨砂调了也看不出变化，2026-10-08 用户实测）
 */
@Composable
fun RefractionTestPattern(dark: Boolean, modifier: Modifier = Modifier, opaqueBase: Boolean = true) {
    // clipToBounds：同心圆半径按 h 递增（r=140,400,660…），预览框只有 140dp 高时
    // 大圆会溢出画布（Canvas 默认不裁剪）——实验室整屏背景靠窗口裁掉看不出来，
    // 设置页预览框没窗口边界，圆圈就爬到标题和滑杆上了（2026-10-08 用户实测）
    Canvas(modifier.clipToBounds()) {
        val w = size.width
        val h = size.height
        val base = if (dark) Color(0xFF14171C) else Color(0xFFF4F5F7)
        val line = if (dark) Color(0xFF8E97A5) else Color(0xFF2A2F38)
        if (opaqueBase) drawRect(base)
        // 对角彩条带（低饱和，垫在网格下）：边界错位可见折射作用域
        val stripes = listOf(
            Color(0x33FF5C5C), Color(0x33FFB45C), Color(0x3359C77A),
            Color(0x335C9DFF), Color(0x33A85CFF)
        )
        val band = 180f
        var x = -h
        var i = 0
        while (x < w) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(x, 0f); lineTo(x + band, 0f)
                lineTo(x + band - h, h); lineTo(x - h, h); close()
            }
            drawPath(path, stripes[i % stripes.size])
            x += band * 2; i++
        }
        // 直网格：主标尺
        val grid = 96f
        var gx = grid
        while (gx < w) {
            drawLine(line, Offset(gx, 0f), Offset(gx, h), 2f)
            gx += grid
        }
        var gy = grid
        while (gy < h) {
            drawLine(line, Offset(0f, gy), Offset(w, gy), 2f)
            gy += grid
        }
        // 中心十字粗线 + 同心圆：任何位移/缩放都逃不过眼睛
        drawLine(line, Offset(w / 2f, 0f), Offset(w / 2f, h), 5f)
        drawLine(line, Offset(0f, h / 2f), Offset(w, h / 2f), 5f)
        val circleColor = if (dark) Color(0xFF6FA8FF) else Color(0xFF2F6BD7)
        var r = 140f
        while (r < h) {
            drawCircle(circleColor, radius = r, center = Offset(w / 2f, h / 2f), style = Stroke(width = 3f))
            r += 260f
        }
        // 文字带：模拟正文穿玻璃（nativeCanvas.drawText，项目内验证过的路径）
        val paint = Paint().apply {
            color = if (dark) android.graphics.Color.parseColor("#D7DEE8")
                    else android.graphics.Color.parseColor("#22262E")
            textSize = 46f
            isAntiAlias = true
        }
        val sample = "折射测试 Refraction 120Hz 玻璃 Glass 扭曲 输入框"
        var ty = 220f
        var n = 0
        while (ty < h - 40f) {
            // 逐行错位排布，避免与网格线平行造成视觉摩尔纹
            drawContext.canvas.nativeCanvas.drawText(sample, if (n % 2 == 0) 24f else 64f, ty, paint)
            ty += 150f; n++
        }
    }
}
