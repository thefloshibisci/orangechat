package me.rerere.rikkahub.data.reading

import kotlinx.serialization.Serializable

@Serializable
data class ReadChapter(
    val title: String,
    val paragraphs: List<String>,
    val images: Map<Int, String> = emptyMap(),
)

@Serializable
data class ReadImage(val id: String, val mimeType: String, val data: String, val alt: String = "")

@Serializable
data class ReadBook(
    val id: String,
    val title: String,
    val author: String = "",
    val format: String,
    val chapters: List<ReadChapter>,
    val importedAt: Long = System.currentTimeMillis(),
    val images: Map<String, ReadImage> = emptyMap(),
    val coverImageId: String? = null,
)

@Serializable
data class ReadProgress(val chapter: Int = 0, val paragraph: Int = 0, val offset: Int = 0)

@Serializable
data class ReadBookEntry(
    val id: String,
    val title: String,
    val author: String = "",
    val format: String,
    val chapterCount: Int,
    val paragraphCount: Int,
    val progress: ReadProgress = ReadProgress(),
    val importedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class ReadReply(val text: String, val assistantId: String, val createdAt: Long = System.currentTimeMillis())

@Serializable
data class ReadAnnotation(
    val id: String,
    val bookId: String,
    val chapter: Int,
    val paragraph: Int,
    val start: Int,
    val end: Int,
    val quote: String,
    val note: String,
    val conversationId: String,
    val assistantId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val replies: List<ReadReply> = emptyList(),
    val author: String = "human",
)

@Serializable
data class ReadSettings(
    val theme: String = "bluecard",
    val fontSize: Int = 20,
    val lineHeight: Float = 1.8f,
    val mode: String = "scroll",
    val paragraphSpacing: Int = 18,
    val letterSpacing: Float = 0f,
    val readingRuler: Boolean = false,
    val pageTurn: String = "slide",
)

data class ReadSession(
    val bookId: String = "",
    val conversationId: String = "",
    val assistantId: String = "",
    val authorized: Boolean = false,
    val active: Boolean = false,
)

internal fun ReadBook.entry(progress: ReadProgress = ReadProgress()) = ReadBookEntry(
    id, title, author, format, chapters.size, chapters.sumOf { it.paragraphs.size }, progress, importedAt,
)
