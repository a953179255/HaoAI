package com.haoai.agent.agent.tools

import com.haoai.agent.platform.PermissionBridge
import com.haoai.agent.platform.PermissionCenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

class CameraTool : Tool {

    override val name = "camera"
    override val desc =
        "调用手机摄像头拍一张照片，保存为 JPG 并返回文件路径。首次使用需要相机权限。"
    override val params = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val context = ctx.appContext ?: return ToolResult("此环境不支持相机", true)
        if (!PermissionCenter.ensure(context, PermissionCenter.CAMERA)) {
            return ToolResult(
                "相机权限未授权：请在系统弹窗中允许，或在 设置 → 权限与自动化 中开启后重试",
                true
            )
        }
        return withContext(Dispatchers.IO) {
            val dir = File(
                context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: context.filesDir,
                "camera"
            ).apply { mkdirs() }
            val out = File(dir, "photo-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.jpg")
            val uri = try {
                androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", out
                )
            } catch (e: IllegalArgumentException) {
                // file_paths 未覆盖该路径等配置问题：返回错误而不是崩溃
                return@withContext ToolResult("无法生成照片文件句柄：${e.message}", true)
            }
            val intent = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            val launcher = PermissionBridge.startForResult
                ?: return@withContext ToolResult("当前无法唤起相机（应用不在前台）", true)
            val result = withContext(Dispatchers.Main) {
                withTimeoutOrNull(120_000) {
                    suspendCancellableCoroutine { cont ->
                        launcher(intent) { cont.resume(it) }
                    }
                }
            }
            // 部分相机应用忽略 EXTRA_OUTPUT，只在小图里返回位图
            if (!out.exists()) {
                val bitmap = result?.extras?.get("data") as? android.graphics.Bitmap
                if (bitmap != null) {
                    FileOutputStream(out).use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it)
                    }
                }
            }
            if (out.exists() && out.length() > 0) {
                // 采样压缩出 data URL 回显聊天（原图路径仍返回，供 read/shell 处理）
                val dataUrl = runCatching {
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(out.absolutePath, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1280) sample *= 2
                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                    val bmp = android.graphics.BitmapFactory.decodeFile(out.absolutePath, opts)
                        ?: return@runCatching null
                    val bos = java.io.ByteArrayOutputStream()
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, bos)
                    bmp.recycle()
                    "data:image/jpeg;base64," +
                        android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
                }.getOrNull()
                ToolResult(
                    "已拍照：${out.absolutePath}（${out.length() / 1024}KB）",
                    imageDataUrl = dataUrl
                )
            } else {
                ToolResult("拍照已取消或未生成照片", true)
            }
        }
    }
}
