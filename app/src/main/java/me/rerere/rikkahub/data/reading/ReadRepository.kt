package me.rerere.rikkahub.data.reading

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.SystemClock
import android.util.AtomicFile
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.util.UUID
import java.time.Instant
import java.time.ZoneId

/** Books stay on the device. Only the explicitly bound book may be read by a companion. */
class ReadRepository internal constructor(
    context: Context,
    private val atomicFileFactory: (File) -> AtomicFile,
    private val elapsedTime: () -> Long,
    private val wallTime: () -> Long,
) {
    constructor(context: Context) : this(context, { AtomicFile(it) }, SystemClock::elapsedRealtime, System::currentTimeMillis)
    internal constructor(context: Context, atomicFileFactory: (File) -> AtomicFile) :
        this(context, atomicFileFactory, SystemClock::elapsedRealtime, System::currentTimeMillis)

    private val context = context.applicationContext
    private val directory = File(this.context.filesDir, "gongdu_read").apply { mkdirs() }
    private val prefs = this.context.getSharedPreferences("gongdu_read", Context.MODE_PRIVATE)
    // Time-valued defaults are evaluated again while encoding. Always include them so importing,
    // annotating and replying within the same millisecond cannot discard the original timestamps.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val access = Any()
    private val mutableLibrary = MutableStateFlow<List<ReadBookEntry>>(emptyList())
    private val mutableBook = MutableStateFlow<ReadBook?>(null)
    private val mutableProgress = MutableStateFlow(ReadProgress())
    private val mutableAnnotations = MutableStateFlow<List<ReadAnnotation>>(emptyList())
    private val mutableRecords = MutableStateFlow<List<ReadRecord>>(emptyList())
    private val mutableSession = MutableStateFlow(ReadSession())
    private val mutableError = MutableStateFlow<String?>(null)
    private val mutableSettings = MutableStateFlow(runCatching {
        validateSettings(json.decodeFromString<ReadSettings>(prefs.getString("settings", "").orEmpty()))
    }.getOrDefault(ReadSettings()))
    val library: StateFlow<List<ReadBookEntry>> = mutableLibrary.asStateFlow()
    val currentBook: StateFlow<ReadBook?> = mutableBook.asStateFlow()
    val progress: StateFlow<ReadProgress> = mutableProgress.asStateFlow()
    val annotations: StateFlow<List<ReadAnnotation>> = mutableAnnotations.asStateFlow()
    val records: StateFlow<List<ReadRecord>> = mutableRecords.asStateFlow()
    val settings: StateFlow<ReadSettings> = mutableSettings.asStateFlow()
    val session: StateFlow<ReadSession> = mutableSession.asStateFlow()
    val error: StateFlow<String?> = mutableError.asStateFlow()
    private var revision = 0L
    private var viewportBinding: ReadViewportBinding? = null

    fun updateViewport(bookId: String, conversationId: String, expectedProgress: ReadProgress,
        viewport: ReadViewport?, expectedSettings: ReadSettings? = null) = synchronized(access) {
        val book = currentBook.value
        if (book?.id != bookId || session.value.conversationId != conversationId || progress.value != expectedProgress) return@synchronized
        if (expectedSettings != null && settings.value != expectedSettings) return@synchronized
        viewport?.validate(book, expectedProgress)
        viewportBinding = viewport?.let { ReadViewportBinding(bookId, conversationId, expectedProgress, it, settings.value) }
    }

    fun clearViewport(bookId: String, conversationId: String) = synchronized(access) {
        if (viewportBinding?.let { it.bookId == bookId && it.conversationId == conversationId } == true) viewportBinding = null
    }

    private fun validViewport(): ReadViewport? = viewportBinding?.takeIf {
        session.value.active && it.bookId == currentBook.value?.id && it.conversationId == session.value.conversationId &&
            it.progress == progress.value && it.settings == settings.value
    }?.viewport

    private fun readablePosition(): ReadProgress = validViewport()?.let { view ->
        view.fragments.lastOrNull()?.let { ReadProgress(view.chapter, it.paragraph, it.end) }
    } ?: progress.value
    private var lastReadingTick = 0L
    private val ready = scope.async {
        try {
            val file = File(directory, "catalog.json")
            val entries = readJson<List<ReadBookEntry>>(file, 2_000_000) ?: emptyList()
            require(entries.size <= 100 && entries.map { it.id }.distinct().size == entries.size)
            entries.forEach {
                checkedId(it.id)
                require(it.title.isNotBlank() && it.title.length <= 300 && it.author.length <= 300 &&
                    it.format in setOf("txt", "epub") && it.chapterCount in 1..5000 && it.paragraphCount > 0 &&
                    it.progress.chapter in 0 until it.chapterCount && it.progress.paragraph >= 0 && it.progress.offset >= 0)
            }
            recoverRestore(entries)
            mutableLibrary.value = entries
        } catch (error: Exception) {
            mutableError.value = "书架读取失败，原文件已保留，请检查应用存储"
            throw IllegalStateException(mutableError.value, error)
        }
    }

    suspend fun importBook(uri: Uri): ReadBook = withContext(Dispatchers.IO) {
        ready.await()
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?.take(300) ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
        require(name.substringAfterLast('.', "").lowercase() in setOf("txt", "epub")) { "请选择 TXT 或 EPUB 电子书" }
        val temp = File.createTempFile("read-import-", ".tmp", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("无法打开所选文件，请重新选择")
            input.use { source -> temp.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= ReadImporter.MAX_BYTES) { "电子书不能超过 20 MB" }
                    output.write(buffer, 0, count)
                }
            } }
            importParsed(ReadImporter.parse(temp, name))
        } finally { temp.delete() }
    }

    /** Shared with tests: parsing completes before any catalog or current session is changed. */
    internal suspend fun importParsed(book: ReadBook): ReadBook = withContext(Dispatchers.IO) {
        ready.await()
        validateBook(book)
        mutex.withLock {
            val existing = library.value.firstOrNull { it.id == book.id }
            if (existing != null) return@withLock readBook(book.id)
            check(library.value.size < 100) { "书架最多保存 100 本书" }
            val file = bookFile(book.id)
            // A previous interrupted import may have left a book outside the catalog. Never replace it.
            val saved = readJson<ReadBook>(file, READ_BACKUP_MAX_BYTES)?.also {
                validateBook(it)
                check(it.id == book.id) { "电子书记录不一致，原文件已保留" }
            } ?: book.also { writeJson(file, it, READ_BACKUP_MAX_BYTES) }
            val entries = listOf(saved.entry()) + library.value
            writeJson(File(directory, "catalog.json"), entries)
            mutableLibrary.value = entries
            saved
        }
    }

    suspend fun openBook(bookId: String, conversationId: String): ReadBook = withContext(Dispatchers.IO) {
        ready.await()
        require(conversationId.isNotBlank()) { "请从当前聊天进入共读" }
        mutex.withLock {
            val entry = library.value.firstOrNull { it.id == checkedId(bookId) } ?: error("这本书已不在书架中")
            val book = readBook(bookId)
            val savedNotes = readNotes(bookId)
            val savedRecords = readRecords(bookId)
            val position = entry.progress.also { validateReadProgress(book, it) }
            synchronized(access) {
                viewportBinding = null
                val sameBinding = session.value.bookId == bookId && session.value.conversationId == conversationId
                if (!sameBinding) revision++
                mutableSession.value = if (sameBinding) session.value.copy(active = false)
                    else ReadSession(bookId = bookId, conversationId = conversationId)
                mutableBook.value = book
                mutableProgress.value = position
                mutableAnnotations.value = savedNotes
                mutableRecords.value = savedRecords
                lastReadingTick = elapsedTime()
            }
            book
        }
    }

    fun authorize(conversationId: String, assistantId: String, enabled: Boolean) = synchronized(access) {
        val current = session.value
        if (!enabled && (current.conversationId != conversationId || current.assistantId != assistantId)) return@synchronized
        require(!enabled || (currentBook.value != null && current.conversationId == conversationId && assistantId.isNotBlank())) {
            "请先在当前聊天打开一本书"
        }
        revision++
        if (!enabled) viewportBinding = null
        mutableSession.value = current.copy(assistantId = if (enabled) assistantId else "", authorized = enabled)
    }

    fun setActive(conversationId: String, active: Boolean) = synchronized(access) {
        if (session.value.conversationId == conversationId) {
            if (session.value.active != active) lastReadingTick = elapsedTime()
            if (!active) viewportBinding = null
            mutableSession.value = session.value.copy(active = active)
        }
    }

    fun isAuthorized(conversationId: String, assistantId: String): Boolean = synchronized(access) {
        authorized(conversationId, assistantId)
    }

    private fun authorized(conversationId: String, assistantId: String): Boolean = session.value.let {
        it.authorized && conversationId.isNotBlank() && assistantId.isNotBlank() &&
            it.conversationId == conversationId && it.assistantId == assistantId &&
            currentBook.value?.id == it.bookId
    }

    fun authorizedSnapshot(conversationId: String, assistantId: String): ReadSnapshot? = synchronized(access) {
        if (!authorized(conversationId, assistantId)) return@synchronized null
        val position = progress.value
        val snapshot = ReadSnapshot(checkNotNull(currentBook.value), position, annotations.value,
            records = records.value, viewport = validViewport(), strictViewport = settings.value.mode != "scroll")
        snapshot.copy(annotations = snapshot.visibleAnnotations(conversationId, assistantId),
            records = records.value.filter { it.conversationId == conversationId && it.assistantId == assistantId &&
                snapshot.containsPosition(it.chapter, it.paragraph, it.end) })
    }

    suspend fun updateProgress(
        bookId: String,
        chapter: Int,
        paragraph: Int,
        conversationId: String? = null,
        expectedProgress: ReadProgress? = null,
        offset: Int = 0,
    ) = withContext(Dispatchers.IO) {
        ready.await()
        mutex.withLock {
            if (conversationId != null && session.value.conversationId != conversationId) return@withLock
            val book = currentBook.value?.takeIf { it.id == bookId } ?: return@withLock
            // Debounced scroll events belong to the position that produced them. Explicit
            // navigation omits this guard; a late event must never undo a newer position.
            if (expectedProgress != null && progress.value != expectedProgress) return@withLock
            val next = ReadProgress(chapter, paragraph, offset).also { validateReadProgress(book, it) }
            if (progress.value == next) return@withLock
            val entries = library.value.map { if (it.id == bookId) it.copy(progress = next) else it }
            writeJson(File(directory, "catalog.json"), entries)
            synchronized(access) {
                viewportBinding = null
                mutableLibrary.value = entries
                if (currentBook.value?.id == bookId) mutableProgress.value = next
            }
        }
    }

    suspend fun updateSettings(value: ReadSettings) = withContext(Dispatchers.IO) {
        val checked = validateSettings(value)
        mutex.withLock {
            check(prefs.edit().putString("settings", json.encodeToString(checked)).commit()) { "阅读设置保存失败" }
            synchronized(access) {
                viewportBinding = null
                mutableSettings.value = checked
            }
        }
    }

    suspend fun addAnnotation(
        bookId: String, conversationId: String, assistantId: String, chapter: Int, paragraph: Int,
        start: Int, end: Int, note: String,
    ): ReadAnnotation = withContext(Dispatchers.IO) {
        ready.await()
        require(note.length <= 4000) { "批注最多 4000 字" }
        require(conversationId.isNotBlank() && assistantId.isNotBlank()) { "当前聊天或助手不可用" }
        mutex.withLock {
            val book = readBook(bookId)
            val text = book.chapters.getOrNull(chapter)?.paragraphs?.getOrNull(paragraph) ?: error("批注原文位置已失效")
            require(start >= 0 && end > start && end <= text.length && end - start <= 6000 &&
                !text.readSplitsPair(start) && !text.readSplitsPair(end)) { "请选择原文中最多 6000 字的片段" }
            val saved = readNotes(bookId)
            check(saved.size < 1000) { "每本书最多保留 1000 条批注" }
            val annotation = ReadAnnotation(UUID.randomUUID().toString(), bookId, chapter, paragraph, start, end,
                text.substring(start, end), note, conversationId, assistantId)
            val next = saved + annotation
            writeJson(notesFile(bookId), next, 16_000_000)
            synchronized(access) { if (currentBook.value?.id == bookId) mutableAnnotations.value = next }
            annotation
        }
    }

    suspend fun reply(annotationId: String, text: String, conversationId: String, assistantId: String): ReadAnnotation =
        withContext(Dispatchers.IO) {
            ready.await()
            require(text.isNotBlank() && text.length <= 4000) { "回应须为 1 至 4000 字" }
            val lease = synchronized(access) {
                check(authorized(conversationId, assistantId)) { "当前聊天未授权陪读" }
                revision
            }
            mutex.withLock {
                val expectedViewport = synchronized(access) { validViewport() }
                val expectedStrict = settings.value.mode != "scroll"
                val bookId = synchronized(access) {
                    check(lease == revision && authorized(conversationId, assistantId)) { "陪读授权已改变" }
                    session.value.bookId
                }
                val saved = readNotes(bookId)
                val annotation = saved.firstOrNull { it.id == annotationId } ?: error("批注已不存在")
                check(annotation.conversationId == conversationId && annotation.assistantId == assistantId) { "这条批注不属于当前聊天与助手" }
                check(annotation.replies.size < 30) { "每条批注最多 30 条回应" }
                val updated = annotation.copy(replies = annotation.replies + ReadReply(text.trim(), assistantId))
                val next = saved.map { if (it.id == annotationId) updated else it }
                val bytes = json.encodeToString(next).toByteArray(Charsets.UTF_8)
                require(bytes.size <= 16_000_000) { "批注记录过大，回应未保存" }
                // Read, serialize, stage and verify outside the consent lock. Revocation stays responsive.
                writeBytes(notesFile(bookId), bytes) { atomic, output ->
                    synchronized(access) {
                        check(lease == revision && authorized(conversationId, assistantId) && session.value.bookId == bookId) { "陪读授权已改变" }
                        check(validViewport() == expectedViewport && (settings.value.mode != "scroll") == expectedStrict &&
                            positionVisible(annotation.chapter, annotation.paragraph, annotation.end)) {
                            "这条批注不在当前可见的阅读范围内"
                        }
                        atomic.finishWrite(output)
                    }
                }
                synchronized(access) { mutableAnnotations.value = next }
                updated
            }
        }

    suspend fun deleteAnnotation(id: String, conversationId: String) = withContext(Dispatchers.IO) {
        ready.await()
        mutex.withLock {
            val bookId = currentBook.value?.id ?: error("请先打开一本书")
            val saved = readNotes(bookId)
            val item = saved.firstOrNull { it.id == id } ?: return@withLock
            require(item.conversationId == conversationId) { "只能移除当前聊天的批注" }
            val next = saved.filterNot { it.id == id }
            writeJson(notesFile(bookId), next, 16_000_000)
            mutableAnnotations.value = next
        }
    }

    suspend fun deleteBook(bookId: String) = withContext(Dispatchers.IO) {
        ready.await()
        mutex.withLock {
            checkedId(bookId)
            val entries = library.value.filterNot { it.id == bookId }
            writeJson(File(directory, "catalog.json"), entries)
            mutableLibrary.value = entries
            synchronized(access) {
                if (currentBook.value?.id == bookId) {
                    revision++
                    mutableSession.value = ReadSession()
                    mutableBook.value = null
                    mutableProgress.value = ReadProgress()
                    mutableAnnotations.value = emptyList()
                    mutableRecords.value = emptyList()
                }
            }
            atomicFileFactory(bookFile(bookId)).delete()
            atomicFileFactory(notesFile(bookId)).delete()
            atomicFileFactory(recordsFile(bookId)).delete()
            check(listOf(bookFile(bookId), notesFile(bookId), recordsFile(bookId)).all { file ->
                listOf(file, File(file.path + ".bak"), File(file.path + ".new")).none(File::exists)
            }) { "已移出书架，但本地文件清理失败，请检查存储后重试" }
        }
    }

    suspend fun coverImage(bookId: String): ReadImage? = withContext(Dispatchers.IO) {
        ready.await()
        mutex.withLock {
            if (library.value.none { it.id == bookId }) return@withLock null
            val book = currentBook.value?.takeIf { it.id == bookId } ?: readBook(bookId)
            book.coverImageId?.let(book.images::get)
        }
    }

    /** lastInteractionAt is a monotonic elapsedRealtime timestamp, never a wall-clock time. */
    suspend fun recordReading(
        bookId: String, conversationId: String, assistantId: String, elapsedSeconds: Int,
        foreground: Boolean, lastInteractionAt: Long,
    ): ReadRecord? = withContext(Dispatchers.IO) {
        ready.await()
        require(elapsedSeconds in 1..30) { "阅读心跳须为1至30秒" }
        require(conversationId.isNotBlank() && assistantId.isNotBlank()) { "当前聊天或助手不可用" }
        mutex.withLock {
            val now = elapsedTime()
            val credit = synchronized(access) {
                if (currentBook.value?.id != bookId || session.value.conversationId != conversationId ||
                    !session.value.active || !foreground) {
                    lastReadingTick = now
                    return@synchronized 0
                }
                val previous = lastReadingTick
                lastReadingTick = now
                if (lastInteractionAt < 0 || lastInteractionAt > now || now - lastInteractionAt > 60_000 || now < previous) 0
                else minOf(elapsedSeconds.toLong(), (now - previous) / 1000,
                    ((lastInteractionAt + 60_000 - previous).coerceAtLeast(0)) / 1000).toInt()
            }
            if (credit <= 0) return@withLock null
            val saved = readRecords(bookId)
            val timestamp = wallTime()
            val day = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
            val existing = saved.lastOrNull {
                it.kind == "human_reading" && it.conversationId == conversationId && it.assistantId == assistantId &&
                    Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault()).toLocalDate() == day
            }
            val position = progress.value
            val record = existing?.copy(updatedAt = timestamp.coerceAtLeast(existing.createdAt),
                seconds = (existing.seconds + credit).coerceAtMost(86400), chapter = position.chapter,
                paragraph = position.paragraph, start = position.offset, end = position.offset)
                ?: ReadRecord(UUID.randomUUID().toString(), bookId, conversationId, assistantId, "human_reading",
                    createdAt = timestamp, updatedAt = timestamp, seconds = credit, chapter = position.chapter,
                    paragraph = position.paragraph, start = position.offset, end = position.offset)
            saveRecords(bookId, saved.filterNot { it.id == record.id } + record)
            record
        }
    }

    suspend fun addJournal(bookId: String, conversationId: String, assistantId: String, text: String): ReadRecord =
        withContext(Dispatchers.IO) {
            ready.await()
            require(text.isNotBlank() && text.length <= 4000) { "札记须为1至4000字" }
            require(conversationId.isNotBlank() && assistantId.isNotBlank()) { "当前聊天或助手不可用" }
            mutex.withLock {
                check(currentBook.value?.id == bookId && session.value.conversationId == conversationId) { "请在当前聊天打开这本书" }
                val position = progress.value
                val record = ReadRecord(UUID.randomUUID().toString(), bookId, conversationId, assistantId, "journal",
                    chapter = position.chapter, paragraph = position.paragraph, start = position.offset,
                    end = position.offset, text = text.trim())
                saveRecords(bookId, readRecords(bookId) + record)
                record
            }
        }

    internal suspend fun addCompanionJournal(conversationId: String, assistantId: String, text: String): ReadRecord =
        withContext(Dispatchers.IO) {
            ready.await()
            require(text.isNotBlank() && text.length <= 4000) { "札记须为1至4000字" }
            val lease = accessLease(conversationId, assistantId)
            mutex.withLock {
                val snapshot = authorizedSnapshot(conversationId, assistantId) ?: error("陪读授权已改变")
                val position = snapshot.progress
                val record = ReadRecord(UUID.randomUUID().toString(), snapshot.book.id, conversationId, assistantId,
                    "journal", chapter = position.chapter, paragraph = position.paragraph, start = position.offset,
                    end = position.offset, text = text.trim(), author = "assistant")
                val next = readRecords(snapshot.book.id) + record
                saveRecords(snapshot.book.id, next, lease, conversationId, assistantId, snapshot)
                record
            }
        }

    internal suspend fun recordAssistantReading(
        conversationId: String, assistantId: String, expected: ReadSnapshot, chapter: Int, fragments: List<ReadFragment>,
    ) = withContext(Dispatchers.IO) {
        ready.await()
        val lease = accessLease(conversationId, assistantId)
        mutex.withLock {
            val snapshot = authorizedSnapshot(conversationId, assistantId) ?: error("陪读授权已改变")
            check(snapshot.book.id == expected.book.id && snapshot.progress == expected.progress &&
                snapshot.viewport == expected.viewport && snapshot.strictViewport == expected.strictViewport) { "阅读位置已改变，请重新读取" }
            val available = snapshot.visibleParagraphs(chapter)
            require(fragments.size <= 200 && fragments.all { fragment ->
                available.getOrNull(fragment.paragraph)?.let { source ->
                    fragment.start in 0..source.length && fragment.end in fragment.start..source.length &&
                        !source.readSplitsPair(fragment.start) && !source.readSplitsPair(fragment.end)
                } == true
            }) { "读取片段超出当前授权范围" }
            var next = readRecords(snapshot.book.id)
            val timestamp = wallTime()
            fragments.filter { it.end > it.start }.forEach { fragment ->
                val previous = next.lastOrNull {
                    it.kind == "assistant_reading" && it.conversationId == conversationId && it.assistantId == assistantId &&
                        it.chapter == chapter && it.paragraph == fragment.paragraph && it.start == fragment.start && it.end == fragment.end
                }
                val record = previous?.copy(updatedAt = timestamp.coerceAtLeast(previous.createdAt))
                    ?: ReadRecord(UUID.randomUUID().toString(), snapshot.book.id, conversationId, assistantId, "assistant_reading",
                        createdAt = timestamp, updatedAt = timestamp, chapter = chapter, paragraph = fragment.paragraph,
                        start = fragment.start, end = fragment.end, characters = fragment.end - fragment.start, author = "assistant")
                next = next.filterNot { it.id == record.id } + record
            }
            if (fragments.any { it.end > it.start }) saveRecords(snapshot.book.id, next, lease, conversationId, assistantId, snapshot)
        }
    }

    internal suspend fun addCompanionAnnotation(
        conversationId: String, assistantId: String, chapter: Int, paragraph: Int, quote: String, text: String,
    ): ReadAnnotation = withContext(Dispatchers.IO) {
        ready.await()
        require(text.isNotBlank() && text.length <= 4000 && quote.isNotBlank() && quote.length <= 6000) { "批注或引用长度无效" }
        val lease = accessLease(conversationId, assistantId)
        mutex.withLock {
            val snapshot = authorizedSnapshot(conversationId, assistantId) ?: error("陪读授权已改变")
            val match = snapshot.matchReadQuote(chapter, paragraph, quote)
            val saved = readNotes(snapshot.book.id)
            val annotation = ReadAnnotation(UUID.randomUUID().toString(), snapshot.book.id, match.chapter, match.paragraph,
                match.start, match.end, match.quote, text.trim(), conversationId, assistantId, author = "assistant")
            val next = saved + annotation
            validateReadAnnotations(snapshot.book, next)
            val bytes = json.encodeToString(next).toByteArray(Charsets.UTF_8)
            require(bytes.size <= 16_000_000) { "批注记录过大" }
            writeBytes(notesFile(snapshot.book.id), bytes) { atomic, output -> synchronized(access) {
                checkLease(lease, conversationId, assistantId, snapshot.book.id)
                check(validViewport() == snapshot.viewport && snapshot.strictViewport == (settings.value.mode != "scroll") &&
                    positionVisible(match.chapter, match.paragraph, match.end)) { "引用已不在当前阅读范围" }
                atomic.finishWrite(output)
            } }
            mutableAnnotations.value = next
            annotation
        }
    }

    private fun accessLease(conversationId: String, assistantId: String): Long = synchronized(access) {
        check(authorized(conversationId, assistantId)) { "当前聊天未授权陪读" }
        revision
    }

    private fun checkLease(lease: Long, conversationId: String, assistantId: String, bookId: String) {
        check(lease == revision && authorized(conversationId, assistantId) && session.value.bookId == bookId) { "陪读授权已改变" }
    }

    private fun positionVisible(chapter: Int, paragraph: Int, end: Int): Boolean {
        val position = readablePosition()
        return chapter < position.chapter || chapter == position.chapter &&
            (paragraph < position.paragraph || paragraph == position.paragraph &&
                (settings.value.mode == "scroll" && validViewport() == null || end <= position.offset))
    }

    private fun saveRecords(bookId: String, next: List<ReadRecord>, lease: Long? = null,
        conversationId: String = "", assistantId: String = "", expected: ReadSnapshot? = null) {
        validateReadRecords(readBook(bookId), next)
        val bytes = json.encodeToString(next).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 8_000_000) { "阅读记录已满，请先备份" }
        writeBytes(recordsFile(bookId), bytes) { atomic, output ->
            if (lease == null) atomic.finishWrite(output) else synchronized(access) {
                checkLease(lease, conversationId, assistantId, bookId)
                if (expected != null) check(expected.progress == progress.value && expected.viewport == validViewport() &&
                    expected.strictViewport == (settings.value.mode != "scroll")) {
                    "阅读页面已改变，请重新读取"
                }
                atomic.finishWrite(output)
            }
        }
        mutableRecords.value = next
    }

    suspend fun exportSnapshot(bookId: String? = null): ReadBackup = withContext(Dispatchers.IO) {
        ready.await()
        mutex.withLock {
            val entries = if (bookId == null) library.value else listOf(
                library.value.firstOrNull { it.id == checkedId(bookId) } ?: error("这本书已移出书架")
            )
            var size = 0L
            entries.forEach { entry ->
                listOf(bookFile(entry.id), notesFile(entry.id), recordsFile(entry.id)).forEach { file ->
                    size += listOf(file, File(file.path + ".bak")).maxOf { it.length() }
                    require(size <= READ_BACKUP_MAX_BYTES) { "阅读备份超过64 MB，请分批整理书架" }
                }
            }
            val backup = ReadBackup(books = entries.map { readBook(it.id) }, catalog = entries,
                annotations = entries.flatMap { readNotes(it.id) }, records = entries.flatMap { readRecords(it.id) },
                settings = settings.value)
            validateReadBackup(backup)
            require(json.encodeToString(backup).toByteArray(Charsets.UTF_8).size <= READ_BACKUP_MAX_BYTES) { "阅读备份超过64 MB" }
            backup
        }
    }

    suspend fun restoreSnapshot(backup: ReadBackup): ReadRestoreResult = withContext(Dispatchers.IO) {
        ready.await()
        validateReadBackup(backup)
        require(json.encodeToString(backup).toByteArray(Charsets.UTF_8).size <= READ_BACKUP_MAX_BYTES) { "阅读备份超过64 MB" }
        mutex.withLock {
            recoverRestore(library.value)
            // Also skip interrupted-import orphans; a restore must never replace pre-existing files.
            val existing = library.value.map { it.id }.toSet()
            val imported = backup.books.filter { book -> book.id !in existing &&
                listOf(bookFile(book.id), notesFile(book.id), recordsFile(book.id)).none(::hasAtomicFile) }
            require(library.value.size + imported.size <= 100) { "恢复后书架将超过100本" }
            synchronized(access) {
                revision++
                mutableSession.value = ReadSession()
                mutableBook.value = null
                mutableAnnotations.value = emptyList()
                mutableRecords.value = emptyList()
                mutableProgress.value = ReadProgress()
                lastReadingTick = elapsedTime()
            }
            if (imported.isEmpty()) return@withLock ReadRestoreResult(0, backup.books.size)
            val journal = File(directory, "restore-journal.json")
            writeJson(journal, ReadRestoreJournal(imported.map { it.id }), 16_000)
            try {
                imported.forEach { book ->
                    writeJson(bookFile(book.id), book, READ_BACKUP_MAX_BYTES)
                    writeJson(notesFile(book.id), backup.annotations.filter { it.bookId == book.id }, 16_000_000)
                    writeJson(recordsFile(book.id), backup.records.filter { it.bookId == book.id }, 8_000_000)
                }
                val ids = imported.map { it.id }.toSet()
                val catalog = backup.catalog.filter { it.id in ids } + library.value
                // This is the visibility commit; startup recovery removes incomplete, unlisted files.
                var warning: String? = null
                try { writeJson(File(directory, "catalog.json"), catalog, 2_000_000) }
                catch (commitError: Exception) {
                    // All new book files have already passed byte verification. Only acknowledge
                    // an unusual catalog commit if its recovery read independently confirms it.
                    val confirmed = runCatching {
                        readJson<List<ReadBookEntry>>(File(directory, "catalog.json"), 2_000_000) == catalog
                    }.getOrDefault(false)
                    if (!confirmed) throw commitError
                    warning = "恢复数据已核对，但保存过程出现异常，请保留原备份并检查应用存储"
                }
                mutableLibrary.value = catalog
                runCatching { recoverRestore(catalog) }.onFailure {
                    warning = "书籍已恢复，恢复标记暂未清理；请保留原备份，下次打开时会重试清理"
                }
                ReadRestoreResult(imported.size, backup.books.size - imported.size, warning)
            } catch (failure: Exception) {
                runCatching {
                    val committed = readJson<List<ReadBookEntry>>(File(directory, "catalog.json"), 2_000_000) ?: emptyList()
                    recoverRestore(committed)
                    mutableLibrary.value = committed
                }
                    .onFailure { failure.addSuppressed(it) }
                throw IllegalStateException("恢复未能确认完成，请返回书架检查；原有书籍已保留", failure)
            }
        }
    }

    private fun hasAtomicFile(file: File): Boolean =
        listOf(file, File(file.path + ".bak"), File(file.path + ".new")).any(File::exists)

    private fun recoverRestore(catalog: List<ReadBookEntry>) {
        val file = File(directory, "restore-journal.json")
        val journal = readJson<ReadRestoreJournal>(file, 16_000) ?: return
        require(journal.bookIds.size <= 100 && journal.bookIds.distinct().size == journal.bookIds.size)
        journal.bookIds.forEach(::checkedId)
        val visible = catalog.map { it.id }.toSet()
        journal.bookIds.filterNot { it in visible }.forEach { id ->
            listOf(bookFile(id), notesFile(id), recordsFile(id)).forEach {
                atomicFileFactory(it).delete()
                check(!hasAtomicFile(it)) { "上次阅读恢复未完成，请检查应用存储后重试" }
            }
        }
        atomicFileFactory(file).delete()
        check(!hasAtomicFile(file)) { "阅读恢复标记清理失败，请检查应用存储" }
    }

    private fun readBook(id: String): ReadBook =
        checkNotNull(readJson<ReadBook>(bookFile(id), READ_BACKUP_MAX_BYTES)) { "电子书文件不存在，请重新导入" }.also {
            validateBook(it)
            check(it.id == id) { "电子书记录不一致，原文件已保留" }
        }

    private fun readNotes(id: String): List<ReadAnnotation> {
        val notes = readJson<List<ReadAnnotation>>(notesFile(id), 16_000_000) ?: emptyList()
        if (notes.isEmpty()) return notes
        validateReadAnnotations(readBook(id), notes)
        return notes
    }

    private fun readRecords(id: String): List<ReadRecord> =
        (readJson<List<ReadRecord>>(recordsFile(id), 8_000_000) ?: emptyList()).also {
            if (it.isNotEmpty()) validateReadRecords(readBook(id), it)
        }

    private fun bookFile(id: String) = File(directory, "${checkedId(id)}.book.json")
    private fun notesFile(id: String) = File(directory, "${checkedId(id)}.notes.json")
    private fun recordsFile(id: String) = File(directory, "${checkedId(id)}.records.json")
    private fun checkedId(id: String): String {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "书籍编号不正确" }
        return id
    }

    private fun validateBook(book: ReadBook) = validateReadBook(book)

    private fun validateSettings(value: ReadSettings): ReadSettings {
        return validateReadSettings(value)
    }

    private inline fun <reified T> readJson(file: File, maxBytes: Int): T? {
        val atomic = atomicFileFactory(file)
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return atomic.openRead().use { source ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= maxBytes) { "本地阅读记录过大，原文件已保留" }
                output.write(buffer, 0, count)
            }
            json.decodeFromString<T>(output.toString("UTF-8"))
        }
    }

    private inline fun <reified T> writeJson(file: File, value: T, maxBytes: Int = 40_000_000) {
        val bytes = json.encodeToString(value).toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxBytes) { "阅读记录过大，保存未完成" }
        writeBytes(file, bytes)
    }

    private fun writeBytes(
        file: File,
        bytes: ByteArray,
        commit: (AtomicFile, FileOutputStream) -> Unit = { atomic, output -> atomic.finishWrite(output) },
    ) {
        val atomic = atomicFileFactory(file)
        val output = atomic.startWrite()
        var finished = false
        try {
            output.write(bytes)
            output.fd.sync()
            commit(atomic, output)
            finished = true
            // AtomicFile can log a failed rename/backup cleanup without throwing. Its own read path
            // also recovers legacy backups, so verify the exact bytes callers will see after restart.
            atomic.openRead().use { source ->
                val buffer = ByteArray(8192)
                var offset = 0
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    check(offset.toLong() + count <= bytes.size) { "阅读记录未能确认保存，请检查存储后重试" }
                    for (index in 0 until count) {
                        check(buffer[index] == bytes[offset + index]) { "阅读记录未能确认保存，请检查存储后重试" }
                    }
                    offset += count
                }
                check(offset == bytes.size) { "阅读记录未能确认保存，请检查存储后重试" }
            }
        } catch (error: Exception) {
            // After finishWrite, failWrite could erase the committed base on older Android versions.
            if (!finished) atomic.failWrite(output)
            throw error
        }
    }

    fun close() { scope.cancel() }
}
