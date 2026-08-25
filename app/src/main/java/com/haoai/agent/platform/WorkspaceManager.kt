package com.haoai.agent.platform

import android.content.Context
import android.net.Uri
import com.haoai.agent.data.HaoJson
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
private data class WorkspacePref(val safUri: String? = null)

class WorkspaceManager(private val context: Context) {

    private val prefFile = File(context.filesDir, "workspace.json")

    private val defaultRoot: File = File(
        context.getExternalFilesDir(null) ?: File(context.filesDir, "ext"),
        "workspace"
    ).apply { mkdirs() }

    var current: FileBackend? = null
        private set

    init {
        restoreOrDefault()
    }

    fun useDefault() {
        current = RawFileBackend(defaultRoot)
        persist(null)
    }

    fun useSaf(uriString: String): Boolean {
        val uri = Uri.parse(uriString)
        return runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val backend = SafFileBackend(context, uri)
            current = backend
            persist(uriString)
            true
        }.getOrDefault(false)
    }

    val workspaceUriForSession: String?
        get() = when (val c = current) {
            is SafFileBackend -> runCatching {
                HaoJson.json.decodeFromString(
                    WorkspacePref.serializer(),
                    prefFile.readText()
                ).safUri
            }.getOrNull()
            else -> null
        }

    private fun persist(safUri: String?) {
        runCatching {
            prefFile.writeText(HaoJson.json.encodeToString(WorkspacePref.serializer(), WorkspacePref(safUri)))
        }
    }

    private fun restoreOrDefault() {
        val saved = runCatching {
            if (prefFile.exists()) {
                HaoJson.json.decodeFromString(WorkspacePref.serializer(), prefFile.readText()).safUri
            } else null
        }.getOrNull()

        if (saved != null) {
            val uri = Uri.parse(saved)
            val granted = runCatching {
                context.contentResolver.persistedUriPermissions.any {
                    it.uri == uri && it.isReadPermission && it.isWritePermission
                }
            }.getOrDefault(false)
            if (granted) {
                runCatching { current = SafFileBackend(context, uri) }
            }
        }
        if (current == null) current = RawFileBackend(defaultRoot)
    }
}
