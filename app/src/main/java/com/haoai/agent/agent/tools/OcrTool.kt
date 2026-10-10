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
            // 读工作区外的共享存储前先引导「文件管理」权限（与 bash / job_output 同口径）。
            com.haoai.agent.platform.PermissionCenter.ensureStorageIfOutside(
                ctx.appContext, rawPath, ctx.shellDir?.absolutePath
            )
            // 工作区相对路径 → 绝对路径（与 read 工具一致的解析方式）
            val file = resolveFile(ctx, rawPath)
            if (file == null) {
                return@withContext ToolResult(
                    "图片读不到：$rawPath（相对路径只允许工作区内，不接受 .. 越界；绝对路径需已授予「文件管理」权限）",
                    true
                )
            }
            if (!file.exists()) {
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

    /**
     * 工作区相对路径优先，其次按绝对路径直读（SAF 工作区不支持直读，需绝对路径）。
     *
     * M2：`read`/`write` 走 FileBackend → PathSafety.normalize（拒绝 `..`）+ canonical
     * 前缀校验，只有 OCR 这一套没有 —— 路径完全来自 LLM 输出，`../../databases/x` 这类
     * 相对越界、或设备上任意绝对路径都会被直读。现补齐同款校验：
     *  ① 相对路径先过 normalize（`..` / 空段 / NUL 一律拒绝）再拼工作区，并做 canonical 前缀校验；
     *  ② 绝对路径原样交给调用方前置的 PermissionCenter 存储门（与 bash / job_output 同口径）。
     */
    private fun resolveFile(ctx: ToolContext, path: String): File? {
        val trimmed = path.trim().trim('"')
        if (trimmed.isEmpty()) return null
        if (File(trimmed).isAbsolute) {
            return File(trimmed).takeIf { it.exists() }
        }
        val workdir = ctx.shellDir ?: return null
        val segs = runCatching { com.haoai.agent.platform.PathSafety.normalize(trimmed) }
            .getOrElse { return null }
        var f = workdir
        for (s in segs) f = File(f, s)
        val root = runCatching { workdir.canonicalFile }.getOrNull() ?: return null
        val canon = runCatching { f.canonicalFile }.getOrNull() ?: return null
        if (canon != root && !canon.path.startsWith(root.path + File.separator)) return null
        return canon.takeIf { it.exists() }
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
