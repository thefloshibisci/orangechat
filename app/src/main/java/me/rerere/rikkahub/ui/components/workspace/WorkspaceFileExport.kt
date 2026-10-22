package me.rerere.rikkahub.ui.components.workspace

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FilterOutputStream
import java.io.OutputStream

internal fun availableExportName(name: String, existing: Set<String>): String {
    require(name.isNotBlank() && '/' !in name && '\\' !in name) { "无效文件名" }
    if (name !in existing) return name
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    val stem = name.substring(0, dot)
    val extension = name.substring(dot)
    var number = 1
    while ("$stem ($number)$extension" in existing) number++
    return "$stem ($number)$extension"
}

/** Creates a new document; an existing destination is never truncated, even by a broken provider. */
internal suspend fun exportNewWorkspaceDocument(
    resolver: ContentResolver,
    tree: Uri,
    name: String,
    expectedBytes: Long,
    write: suspend (OutputStream) -> Unit,
): String {
    val rootId = DocumentsContract.getTreeDocumentId(tree)
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
    val existingIds = mutableSetOf<String>()
    val existingNames = mutableSetOf<String>()
    val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
    checkNotNull(resolver.query(children, columns, android.os.Bundle.EMPTY, null)) { "无法读取目标文件夹" }.use { cursor ->
        while (cursor.moveToNext()) {
            existingIds += cursor.getString(0)
            existingNames += cursor.getString(1)
        }
    }
    val chosen = availableExportName(name, existingNames)
    val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
    val document = checkNotNull(DocumentsContract.createDocument(resolver, root, "application/octet-stream", chosen)) {
        "无法创建文件"
    }
    check(DocumentsContract.getDocumentId(document) !in existingIds) { "文件服务返回了已有文件，已停止以防覆盖" }
    var completed = false
    try {
        val output = checkNotNull(resolver.openOutputStream(document, "wt")) { "无法打开保存位置" }
        var count = 0L
        object : FilterOutputStream(output) {
            override fun write(b: Int) { out.write(b); count++ }
            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
        }.use { write(it) }
        check(count == expectedBytes) { "文件写入不完整，请重试" }
        completed = true
        return chosen
    } finally {
        if (!completed) runCatching { DocumentsContract.deleteDocument(resolver, document) }
    }
}
