package me.rerere.rikkahub.data.sync

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.APP_DATABASE_MIGRATIONS
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.TranslationHistoryEntity
import me.rerere.rikkahub.data.db.entity.UsageRecordEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.file.Files
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
// Short names keep Robolectric's class/method-based temp paths usable by native Windows SQLite.
class TranslationHistoryBackupTest {
    @Test
    fun backupRestoreRetainsHistory() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val suffix = Uuid.random().toString().take(8)
        val sourceName = "ts-$suffix"
        val restoredName = "td-$suffix"
        val source = context.getDatabasePath(sourceName)
        val restored = context.getDatabasePath(restoredName)
        println("translation backup SQLite path length: ${source.absolutePath.length}")
        val history = listOf(
            TranslationHistoryEntity("first", "source\n👩🏽‍💻", "result\n\"原文\"", "zh-Hant-TW", "removed-model", 1234),
            TranslationHistoryEntity("second", "source 2", "result 2", "en", "other-model", 1235),
        )
        val config = " {\"maxTokens\":123,\"future\":true} "
        try {
            val original = open(sourceName)
            try {
                original.openHelper.writableDatabase.execSQL(
                    "INSERT INTO ConversationEntity(id,title,nodes,create_at,update_at,conversation_config) VALUES('keep','title','[]',1,2,?)",
                    arrayOf(config),
                )
                original.usageRecordDao().insert(UsageRecordEntity(createdAt = 1000, source = "TRANSLATION"))
                history.forEach { original.translationHistoryDao().insert(it) }
            } finally { original.close() }
            Files.copy(source.toPath(), restored.toPath())
            val sourceBytes = source.readBytes()
            repeat(2) {
                preflightBackupDatabase(context, restored, FrameworkSQLiteOpenHelperFactory())
                val db = open(restoredName)
                try {
                    assertEquals(42, db.openHelper.writableDatabase.version)
                    assertEquals(history, rows(db))
                    assertEquals(1L, db.usageRecordDao().getTotals().requestCount)
                    db.openHelper.writableDatabase.query("SELECT conversation_config FROM ConversationEntity WHERE id='keep'").use {
                        assertTrue(it.moveToFirst()); assertEquals(config, it.getString(0))
                    }
                    db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                } finally { db.close() }
            }
            val modified = open(restoredName)
            try {
                modified.translationHistoryDao().deleteAll()
                assertTrue(rows(modified).isEmpty())
                assertEquals(1L, modified.usageRecordDao().getTotals().requestCount)
            } finally { modified.close() }
            assertArrayEquals(sourceBytes, source.readBytes())
            val untouched = open(sourceName)
            try { assertEquals(history, rows(untouched)) } finally { untouched.close() }
        } finally {
            context.deleteDatabase(sourceName)
            context.deleteDatabase(restoredName)
        }
    }

    private fun open(name: String) = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java, name)
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).addMigrations(*APP_DATABASE_MIGRATIONS)
        .allowMainThreadQueries().build()

    private fun rows(db: AppDatabase): List<TranslationHistoryEntity> =
        db.openHelper.writableDatabase.query("SELECT * FROM translation_history ORDER BY create_at,id").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(TranslationHistoryEntity(cursor.getString(0), cursor.getString(1),
                    cursor.getString(2), cursor.getString(3), cursor.getString(4), cursor.getLong(5)))
            }
        }
}
