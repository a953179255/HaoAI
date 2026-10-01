package com.haoai.agent.agent.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import kotlin.coroutines.resume

/**
 * OCR 文字识别（2.4）：ML Kit 中文捆绑版（模型打进 APK，离线可用）。
 * 输入工作区/相册的图片路径，输出全文与逐块文本。
 */
class OcrImageTool : Tool {

    override val name = "ocr_image"
    override val desc =
        "识别图片中的文字（OCR，中文/英文，离线）。输入图片路径（工作区相对路径或 /storage 绝对路径），" +
            "返回全文与按行的文本。适合让代理「看懂」截图和照片里的文字。"
    override val params = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") { put("type", "string") }
        }
        putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("path")) }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val rawPath = args.optString("path")
            if (rawPath.isBlank()) return@withContext ToolResult("path 不能为空", true)
            // 工作区相对路径 → 绝对路径（与 read 工具一致的解析方式）
            val file = resolveFile(ctx, rawPath)
            if (file == null || !file.exists()) {
                return@withContext ToolResult("图片不存在：$rawPath", true)
            }
            if (file.length() > 30L * 1024 * 1024) {
                return@withContext ToolResult("图片超过 30MB 上限", true)
            }
            try {
                val bitmap = loadDownscaled(file) ?: return@withContext ToolResult("无法解码图片文件", true)
                val image = InputImage.fromBitmap(bitmap, 0)
                val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                val text = suspendCancellableCoroutine { cont ->
                    recognizer.process(image)
                        .addOnSuccessListener { cont.resume(it.text) }
                        .addOnFailureListener { cont.resume("") }
                }
                recognizer.close()
                bitmap.recycle()
                if (text.isBlank()) {
                    ToolResult("未在图片中识别到文字")
                } else {
                    ToolResult("识别结果（${text.length} 字）：\n${TextCap.middle(text, 8000)}")
                }
            } catch (e: Exception) {
                ToolResult("OCR 识别失败：${e.message}", true)
            }
        }

    /** 工作区相对路径优先，其次按绝对路径直读（SAF 工作区不支持直读，需绝对路径）。 */
    private fun resolveFile(ctx: ToolContext, path: String): File? {
        val trimmed = path.trim().trim('"')
        val abs = File(trimmed)
        if (abs.isAbsolute && abs.exists()) return abs
        val workdir = ctx.shellDir ?: return abs.takeIf { it.exists() }
        val inWorkspace = File(workdir, trimmed)
        return when {
            inWorkspace.exists() -> inWorkspace
            abs.exists() -> abs
            else -> null
        }
    }

    /** 长边压到 2048 再识别：省内存且 ML Kit 对超大图会直接拒绝。 */
    private fun loadDownscaled(file: File): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > 2048) sample *= 2
        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null
        // EXIF 方向纠正（相册照片常见旋转 90°）
        val rotation = runCatching {
            when (
                ExifInterface(file.absolutePath)
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (rotation == 0f) return bitmap
        val m = android.graphics.Matrix().apply { postRotate(rotation) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }
}
