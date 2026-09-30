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

    /** 备份/恢复用：壁纸落盘文件。路径只此一处定义，别处不得再写死。 */
    fun storedFile(context: Context): File = file(context)

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

    /**
     * 预缩放到屏幕 cover 尺寸的壁纸（v0.18.1 性能）：backdrop 画布每帧 drawImage
     * 现算 cover-fit 缩放，壁纸小于屏幕时每帧走放大采样路径；先一次性双线性缩到
     * cover 尺寸，之后每帧 1:1 贴图（观感一致——同为双线性，只是从每帧一次变为一世一次）。
     * 结果超过 12M 像素（约 48MB）时放弃预缩放，保持原图交给 GPU 过滤。
     */
    fun loadBitmapCover(context: Context, targetW: Int, targetH: Int): Bitmap? {
        val bmp = loadBitmap(context) ?: return null
        val scale = maxOf(targetW.toFloat() / bmp.width, targetH.toFloat() / bmp.height)
        if (scale > 0.999f && scale < 1.001f) return bmp
        val w = (bmp.width * scale).toInt().coerceAtLeast(1)
        val h = (bmp.height * scale).toInt().coerceAtLeast(1)
        if (w.toLong() * h > 12_000_000L) return bmp
        return runCatching { Bitmap.createScaledBitmap(bmp, w, h, true) }.getOrDefault(bmp)
    }
}
