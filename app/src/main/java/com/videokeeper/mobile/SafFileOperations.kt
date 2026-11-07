package com.videokeeper.mobile

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/** SAF-only file operations used by explicit user actions. */
object SafFileOperations {
    /**
     * Moves a document without changing its display name. Providers that expose
     * DocumentsContract.moveDocument get an atomic move; other providers use a
     * verified copy followed by source deletion.
     */
    fun move(
        context: Context,
        source: Uri,
        destinationTree: Uri,
        sourceTree: Uri?
    ): Uri {
        val atomic = runCatching {
            val sourceParent = sourceTree?.let { parentDocumentUri(it, source) }
            if (sourceParent == null) {
                null
            } else {
                val targetParent = targetParentDocumentUri(destinationTree)
                DocumentsContract.moveDocument(
                    context.contentResolver,
                    source,
                    sourceParent,
                    targetParent
                )
            }
        }.getOrNull()
        if (atomic != null) return atomic
        return copyThenDelete(context, source, destinationTree)
    }

    private fun copyThenDelete(context: Context, source: Uri, destinationTree: Uri): Uri {
        val resolver = context.contentResolver
        val sourceFile = DocumentFile.fromSingleUri(context, source)
            ?: error("找不到來源影片")
        val name = sourceFile.name?.takeIf { it.isNotBlank() }
            ?: queryDisplayName(context, source)
            ?: error("找不到來源檔名")
        val mime = sourceFile.type?.takeIf { it.isNotBlank() }
            ?: resolver.getType(source)
            ?: "video/mp4"
        val destination = DocumentFile.fromTreeUri(context, destinationTree)
            ?: error("輸出資料夾已失效")
        if (!destination.isDirectory) error("輸出位置不是資料夾")
        val created = destination.createFile(mime, name)
            ?: error("無法在輸出資料夾建立檔案")
        try {
            val copiedBytes = resolver.openInputStream(source)?.use { input ->
                resolver.openOutputStream(created.uri, "w")?.use { output ->
                    input.copyTo(output, COPY_BUFFER_SIZE)
                } ?: error("無法寫入輸出檔案")
            } ?: error("無法讀取來源影片")
            val sourceBytes = sourceFile.length()
            if (sourceBytes > 0L && copiedBytes != sourceBytes) {
                error("移動驗證失敗，檔案大小不一致")
            }
            if (!DocumentsContract.deleteDocument(resolver, source)) {
                error("無法刪除原始檔，未完成移動")
            }
            return created.uri
        } catch (error: Exception) {
            runCatching { created.delete() }
            throw error
        }
    }

    private fun parentDocumentUri(sourceTree: Uri, source: Uri): Uri? {
        if (sourceTree.authority != source.authority) return null
        val documentId = DocumentsContract.getDocumentId(source)
        val separator = documentId.lastIndexOf('/')
        val parentId = if (separator >= 0) {
            documentId.substring(0, separator)
        } else {
            documentId.substringBeforeLast(':', documentId) + ":"
        }
        return DocumentsContract.buildDocumentUriUsingTree(sourceTree, parentId)
    }

    private fun targetParentDocumentUri(destinationTree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(
            destinationTree,
            DocumentsContract.getTreeDocumentId(destinationTree)
        )

    private fun queryDisplayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private const val COPY_BUFFER_SIZE = 1024 * 1024
}
