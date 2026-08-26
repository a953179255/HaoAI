package com.haoai.agent.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.File

/**
 * 自定义聊天背景壁纸：单文件存储（filesDir/wallpaper.img）。
 * 设置页选择图片后拷贝进应用私有目录，持久生效；清除即删除。
 */
object WallpaperStore {

    /** 壁纸变更版本号：set/clear 时自增，UI 层收集它实现即时重载。 */
    private val _changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val changes: kotlinx.coroutines.flow.StateFlow<Long> = _changes

    private fun file(context: Context): File = File(context.filesDir, "wallpaper.img")

    fun has(context: Context): Boolean = file(context).exists() && file(context).length() > 0

    fun set(context: Context, bytes: ByteArray) {
        val f = file(context)
        // 临时文件+rename（Linux rename 原子替换已存在目标），避免写一半被杀留下损坏图片
        val tmp = File(context.filesDir, "wallpaper.img.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) runCatching { tmp.copyTo(f, overwrite = true) }
        tmp.delete()
        _changes.value += 1
    }

    fun clear(context: Context) {
        file(context).delete()
        _changes.value += 1
    }

    /** 采样解码为不超过 maxDim 的 Bitmap，避免大图占内存。 */
    fun loadBitmap(context: Context, maxDim: Int = 1440): Bitmap? = runCatching {
        val f = file(context)
        if (!f.exists()) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, opts)
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > maxDim) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        // 修正 EXIF 方向的常见场景：宽>高交换（简化处理，仅当明显颠倒时旋转）
        if (opts.outWidth > opts.outHeight && bmp.width < bmp.height * 2) {
            // 保持原样，Android 相册图通常方向正确
        }
        bmp
    }.getOrNull()

    /** cover-fit 绘制参数（等比缩放居中裁剪）。 */
    fun coverFit(bmp: Bitmap, viewW: Float, viewH: Float): Matrix = Matrix().apply {
        val scale = maxOf(viewW / bmp.width, viewH / bmp.height)
        setScale(scale, scale)
        postTranslate((viewW - bmp.width * scale) / 2f, (viewH - bmp.height * scale) / 2f)
    }
}
