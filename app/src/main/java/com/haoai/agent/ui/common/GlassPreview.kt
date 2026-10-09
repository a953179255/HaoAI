package com.haoai.agent.ui.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import android.graphics.Paint

/**
 * 折射测试背景：纯色底上折射=隐形（没有可弯的东西），这块高对比图案专治
 * "看不出扭曲"。三层纹理各司其职：
 * - **直网格线**：折射环带会把穿过它的直线掰弯/挤出位移，是强度判读的主标尺
 * - **对角彩条**：色块边界错位，看折射影响范围（环带宽度）
 * - **文字行**：模拟聊天正文穿玻璃的真实场景
 *
 * ⚠️ 2026-10-09 重构为**纯绘制体**（供 `CanvasBackdrop { }` 直用）：此前图案以
 * `Box.layerBackdrop` 挂载、玻璃采样它——实测玻璃行的 blur/采样整条失效
 * （只画白雾，测试字清晰裸露；连 15dp 档的顶栏行都不糊）。改走 CanvasBackdrop：
 * 玻璃采样时**在玻璃坐标系里直接执行本函数**，无挂载依赖（库 demo 同款路线），
 * 且每行玻璃各自得到完整图案 + 垂直居中一行字 = "每栏背景都有一行字"。
 *
 * @param opaqueBase true = 画实色底（全屏模式，图案自带背景）；
 *   false = 只画纹理不画底（预览框：底下透出实底面板的表面色）
 * @param gridDp 网格间距。实验室全屏 96px 在 2200px 高的屏上显密；140dp 预览框
 *   用同参数只剩 4 条横线（用户实测"没以前密集"），预览框传 20dp。
 */
fun drawRefractionTestPattern(
    scope: DrawScope,
    dark: Boolean,
    opaqueBase: Boolean = true,
    gridDp: Float = 32f
) {
    with(scope) {
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
        val band = 60f.dp.toPx()
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
        val grid = gridDp.dp.toPx()
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
        // 文字行：模拟正文穿玻璃。预览框（行高 30~44dp）每行玻璃只容一行字——
        // 垂直居中画一行；全屏模式（h 高）按 150px 节奏铺多行。
        val paint = Paint().apply {
            color = if (dark) android.graphics.Color.parseColor("#D7DEE8")
                    else android.graphics.Color.parseColor("#22262E")
            textSize = 46f
            isAntiAlias = true
        }
        val sample = "折射测试 Refraction 120Hz 玻璃 Glass 扭曲 输入框"
        if (h < 500f) {
            // 预览框：单行垂直居中（baseline = 中心 + 0.35×字高）
            drawContext.canvas.nativeCanvas.drawText(
                sample, 24f, h / 2f + 46f * 0.35f, paint
            )
        } else {
            var ty = 220f
            var n = 0
            while (ty < h - 40f) {
                // 逐行错位排布，避免与网格线平行造成视觉摩尔纹
                drawContext.canvas.nativeCanvas.drawText(sample, if (n % 2 == 0) 24f else 64f, ty, paint)
                ty += 150f; n++
            }
        }
    }
}
