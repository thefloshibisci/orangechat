package me.rerere.rikkahub.data.reading

import kotlinx.serialization.Serializable

/** Reading time belongs only to human_reading; assistant_reading is an access receipt. */
@Serializable
data class ReadRecord(
    val id: String,
    val bookId: String,
    val conversationId: String,
    val assistantId: String,
    val kind: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val seconds: Int = 0,
    val chapter: Int = 0,
    val paragraph: Int = 0,
    val start: Int = 0,
    val end: Int = 0,
    val characters: Int = 0,
    val text: String = "",
    val author: String = "human",
)

@Serializable
data class ReadBackup(
    val version: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val books: List<ReadBook>,
    val catalog: List<ReadBookEntry>,
    val annotations: List<ReadAnnotation>,
    val records: List<ReadRecord>,
    val settings: ReadSettings,
)

data class ReadRestoreResult(val imported: Int, val skipped: Int, val warning: String? = null)

internal data class ReadFragment(val paragraph: Int, val start: Int, val end: Int)
internal data class ReadQuoteMatch(val chapter: Int, val paragraph: Int, val start: Int, val end: Int, val quote: String)

internal const val READ_BACKUP_MAX_BYTES = 64_000_000

@Serializable
internal data class ReadRestoreJournal(val bookIds: List<String>)

internal fun validateReadBook(book: ReadBook) {
    require(book.id.matches(Regex("[a-f0-9]{64}")) && book.title.isNotBlank() && book.title.length <= 300 &&
        book.author.length <= 300 && book.format in setOf("txt", "epub") && book.importedAt >= 0 &&
        book.chapters.size in 1..5000 && book.chapters.all { chapter ->
            chapter.title.length <= 300 && chapter.paragraphs.isNotEmpty() &&
                chapter.paragraphs.all { it.isNotBlank() && it.length <= 6000 }
        } && book.chapters.sumOf { chapter -> chapter.paragraphs.sumOf { it.length.toLong() } } <= 10_000_000) {
        "电子书结构无效或正文过大"
    }
    validateReadImages(book)
}

internal fun validateReadProgress(book: ReadBook, progress: ReadProgress) {
    val text = book.chapters.getOrNull(progress.chapter)?.paragraphs?.getOrNull(progress.paragraph)
    require(text != null && progress.offset in 0..text.length && !text.readSplitsPair(progress.offset)) { "阅读位置已失效" }
}

internal fun String.readSplitsPair(offset: Int): Boolean =
    offset > 0 && offset < length && this[offset - 1].isHighSurrogate() && this[offset].isLowSurrogate()

internal fun validateReadAnnotations(book: ReadBook, notes: List<ReadAnnotation>) {
    require(notes.size <= 1000 && notes.map { it.id }.distinct().size == notes.size && notes.all { note ->
        val source = book.chapters.getOrNull(note.chapter)?.paragraphs?.getOrNull(note.paragraph)
        note.id.isNotBlank() && note.id.length <= 100 && note.bookId == book.id && note.createdAt >= 0 &&
            note.conversationId.isNotBlank() && note.conversationId.length <= 200 &&
            note.assistantId.isNotBlank() && note.assistantId.length <= 200 && note.author in setOf("human", "assistant") &&
            source != null && note.start >= 0 && note.end > note.start && note.end <= source.length &&
            !source.readSplitsPair(note.start) && !source.readSplitsPair(note.end) &&
            note.quote.length <= 6000 && source.substring(note.start, note.end) == note.quote &&
            note.note.length <= 4000 && note.replies.size <= 30 && note.replies.all {
                it.text.isNotBlank() && it.text.length <= 4000 && it.assistantId == note.assistantId && it.createdAt >= 0
            }
    }) { "批注记录损坏，原文件已保留" }
}

internal fun validateReadRecords(book: ReadBook, records: List<ReadRecord>) {
    require(records.size <= 10000 && records.map { it.id }.distinct().size == records.size && records.all { record ->
        val source = book.chapters.getOrNull(record.chapter)?.paragraphs?.getOrNull(record.paragraph)
        record.id.isNotBlank() && record.id.length <= 100 && record.bookId == book.id &&
            record.conversationId.isNotBlank() && record.conversationId.length <= 200 &&
            record.assistantId.isNotBlank() && record.assistantId.length <= 200 && record.createdAt >= 0 &&
            record.updatedAt >= record.createdAt && record.text.length <= 4000 && source != null &&
            record.start in 0..source.length && record.end in record.start..source.length &&
            !source.readSplitsPair(record.start) && !source.readSplitsPair(record.end) && record.characters >= 0 &&
            when (record.kind) {
                "human_reading" -> record.author == "human" && record.seconds in 1..86400 && record.text.isEmpty() && record.characters == 0
                "assistant_reading" -> record.author == "assistant" && record.seconds == 0 && record.text.isEmpty() && record.characters == record.end - record.start
                "journal" -> record.author in setOf("human", "assistant") && record.seconds == 0 && record.characters == 0 && record.text.isNotBlank()
                else -> false
            }
    }) { "阅读记录损坏，原文件已保留" }
}

internal fun validateReadSettings(value: ReadSettings): ReadSettings {
    require(value.theme in setOf("paper", "white", "dark", "bluecard", "bluecard-night") &&
        value.mode in setOf("scroll", "page", "spread") &&
        value.pageTurn in setOf("slide", "cover", "curl", "vertical") && value.fontSize in 14..32 &&
        value.lineHeight.isFinite() && value.lineHeight in 1.3f..2.4f && value.paragraphSpacing in 8..36 &&
        value.letterSpacing.isFinite() && value.letterSpacing in 0f..3f) { "阅读设置不正确" }
    return value
}

internal fun validateReadBackup(backup: ReadBackup) {
    require(backup.version == 1 && backup.createdAt >= 0 && backup.books.size <= 100 &&
        backup.books.map { it.id }.distinct().size == backup.books.size &&
        backup.catalog.map { it.id }.distinct().size == backup.catalog.size &&
        backup.books.map { it.id }.toSet() == backup.catalog.map { it.id }.toSet()) { "阅读备份结构不正确" }
    validateReadSettings(backup.settings)
    val ids = backup.books.map { it.id }.toSet()
    require(backup.annotations.all { it.bookId in ids } && backup.records.all { it.bookId in ids }) { "备份中有不属于书架的记录" }
    require(backup.annotations.map { it.id }.distinct().size == backup.annotations.size &&
        backup.records.map { it.id }.distinct().size == backup.records.size) { "备份中有重复记录" }
    backup.books.forEach { book ->
        validateReadBook(book)
        val entry = backup.catalog.single { it.id == book.id }
        validateReadProgress(book, entry.progress)
        require(entry == book.entry(entry.progress)) { "备份书目与正文不一致" }
        validateReadAnnotations(book, backup.annotations.filter { it.bookId == book.id })
        validateReadRecords(book, backup.records.filter { it.bookId == book.id })
    }
}
