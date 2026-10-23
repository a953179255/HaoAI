package com.haoai.agent.ui.chat

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 批4b：文件外发动作的共享实现（查看器底栏与浏览页长按菜单同一套，防两处漂移）。
 *
 * 口径（批4a 定稿 + 对标七家兜底链）：
 * - 分享/外开经 FileProvider（file_paths 仅覆盖工作区专属目录与相机/cache 先例，
 *   取不到 uri 一律降级 toast 引导「另存为」，绝不 file:// 直传——收方读不到还泄漏路径）；
 * - 存相册走 MediaStore（Pictures/HaoAI，Q+ 相对路径；预 Q 落默认目录）；
 * - 另存为需 Activity 结果回调，留在各 UI 侧自挂 CreateDocument launcher。
 */
internal object FileExternal {

    fun providerUri(context: Context, absPath: String): Uri? = runCatching {
        androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", java.io.File(absPath)
        )
    }.getOrNull()

    /** ACTION_SEND 分享；uri 不可得回 false（调用方提示走另存为）。 */
    fun share(context: Context, abs: String, mime: String, fileName: String): Boolean {
        val uri = providerUri(context, abs) ?: return false
        return runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(send, "分享文件")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
    }

    /** ACTION_VIEW 交系统「打开方式」；单处理器时系统直达（实测 Google Lens 场景）。 */
    fun openWith(context: Context, abs: String, mime: String): Boolean {
        val uri = providerUri(context, abs) ?: return false
        return runCatching {
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(view, "打开方式")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
    }

    /** 图片入相册（MediaStore，IO 线程安全——内部无主线程调用）；返回是否成功。 */
    suspend fun saveToAlbum(context: Context, abs: String, fileName: String, mime: String): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val values = ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/HaoAI")
                    }
                }
                val u = context.contentResolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                )
                if (u == null) false
                else context.contentResolver.openOutputStream(u)?.use { out ->
                    java.io.File(abs).inputStream().use { it.copyTo(out) }
                    true
                } ?: false
            }.getOrDefault(false)
        }

    /** 复制绝对路径到系统剪贴板。 */
    fun copyPath(context: Context, abs: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("workspace_path", abs))
    }
}
