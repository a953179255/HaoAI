package com.haoai.agent.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** MainActivity 注册系统对话框/相机的启动器后挂进来，供任意线程（含工具执行协程）调用。 */
object PermissionBridge {

    /** 发起运行时权限请求；结果按权限名回传。 */
    @Volatile
    var requestRuntime: ((List<String>, (Map<String, Boolean>) -> Unit) -> Unit)? = null

    /** 发起第三方 Activity（如相机拍照）并在结果返回时回调。 */
    @Volatile
    var startForResult: ((Intent, (Intent?) -> Unit) -> Unit)? = null
}

data class PermSpec(
    val key: String,
    val label: String,
    val description: String,
    val permissions: List<String> = emptyList(),
    /** true=特殊权限（需跳转系统设置开关），false=运行时权限（可弹系统对话框） */
    val special: Boolean = false
)

object PermissionCenter {

    val CAMERA = PermSpec(
        "camera", "相机", "拍照与录像（camera 工具）",
        listOf(Manifest.permission.CAMERA)
    )
    val LOCATION = PermSpec(
        "location", "定位", "获取当前位置（location 工具）",
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )
    val MEDIA = PermSpec(
        "media", "照片与视频", "读取相册媒体文件（壁纸选择、图片识别）",
        mediaPerms()
    )
    val STORAGE = PermSpec(
        "storage", "文件管理", "管理手机存储：读写工作空间之外的任意文件",
        special = true
    )
    val NOTIFICATIONS = PermSpec(
        "notifications", "通知", "后台任务完成与定时提醒的通知推送",
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
    )

    val ALL = listOf(CAMERA, LOCATION, MEDIA, STORAGE, NOTIFICATIONS)

    private fun mediaPerms(): List<String> =
        if (Build.VERSION.SDK_INT >= 33)
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    fun granted(context: Context, spec: PermSpec): Boolean = when {
        spec.key == "storage" -> Environment.isExternalStorageManager()
        spec.permissions.isEmpty() -> true
        else -> spec.permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 确保权限可用：运行时权限弹系统对话框等待用户操作；
     * 特殊权限跳系统设置页并轮询等待用户打开开关。超时或被拒返回 false。
     */
    suspend fun ensure(context: Context, spec: PermSpec, timeoutMs: Long = 90_000): Boolean {
        if (granted(context, spec)) return true
        return if (spec.special) {
            ensureSpecial(context, timeoutMs)
        } else {
            val launcher = PermissionBridge.requestRuntime ?: return false
            val result = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    launcher(spec.permissions) { cont.resume(it) }
                }
            }
            result?.values?.all { it } ?: false
        }
    }

    private suspend fun ensureSpecial(context: Context, timeoutMs: Long): Boolean {
        val ok = runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.recoverCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (!ok) return false
        return runCatching {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (Environment.isExternalStorageManager()) return true
                delay(800)
            }
            false
        }.getOrDefault(false)
    }

    /** 文件类工具的存储门卫：目标在工作空间外时先确保「文件管理」权限。 */
    suspend fun ensureStorageIfOutside(appContext: Context?, target: String, workspaceRoot: String?): Boolean {
        appContext ?: return true
        val p = target.replace('\\', '/')
        val sharedPrefixes = listOf("/sdcard/", "/storage/emulated/", "/mnt/sdcard/")
        val touchesShared = sharedPrefixes.any { prefix ->
            var idx = p.indexOf(prefix)
            while (idx >= 0) {
                if (idx == 0 || !p[idx - 1].isLetterOrDigit()) return true
                idx = p.indexOf(prefix, idx + 1)
            }
            false
        } || p.startsWith("/sdcard") || p.startsWith("/storage/emulated")
        if (!touchesShared) return true
        val root = workspaceRoot?.trimEnd('/')
        if (root != null && root.isNotEmpty() && p.contains(root)) return true
        return ensure(appContext, STORAGE)
    }

    /** 打开「所有文件访问」设置页（设置界面的手动入口用）。 */
    fun openStorageSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
