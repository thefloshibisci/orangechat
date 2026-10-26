package me.rerere.rikkahub.data.serein

import androidx.room.withTransaction
import me.rerere.rikkahub.data.db.AppDatabase
import java.security.MessageDigest

class SceneConflict : IllegalStateException("记忆已被修改或删除，请重新读取后再试。")

class LocalSceneRepository(private val database: AppDatabase) {
    private val dao get() = database.localSceneDao()

    private fun owner(id: String) { require(id.isNotBlank() && id.length <= 128) }

    private fun fields(title: String, body: String, cues: String, author: String): Triple<String, String, String> {
        require(author == "user" || author == "assistant")
        require(title.trim().length in 1..120) { "标题需为 1–120 字。" }
        require(body.trim().length in 1..1500) { "内容需为 1–1500 字。" }
        require(cues.length <= 800) { "线索过长。" }
        val hints = cues.split('\n', ';', '；').map(String::trim).filter(String::isNotEmpty).distinct()
        require(hints.size <= 8 && hints.all { it.length <= 80 }) { "最多 8 条线索，每条最多 80 字。" }
        return Triple(title.trim(), body.trim(), hints.joinToString("\n"))
    }

    suspend fun create(assistantId: String, operationId: String, title: String, body: String, cues: String = "",
                       author: String = "user"): LocalScene {
        owner(assistantId)
        require(operationId.isNotBlank() && operationId.length <= 128)
        val (t, b, c) = fields(title, body, cues, author)
        val digest = MessageDigest.getInstance("SHA-256").digest("$assistantId\u0000$operationId".toByteArray(Charsets.UTF_8))
        val id = "scene_" + digest.joinToString("") { "%02x".format(it) }
        return database.withTransaction {
            val old = dao.head(assistantId, id)
            if (old != null) {
                if (old.deleted || old.revision != 1 || old.title != t || old.body != b || old.cues != c || old.author != author) throw SceneConflict()
                return@withTransaction old
            }
            val now = System.currentTimeMillis()
            LocalScene(assistantId, id, 1, t, b, c, author, now, now).also {
                dao.insert(it)
                dao.insertRevision(it.snapshot())
            }
        }
    }

    suspend fun read(assistantId: String, id: String): LocalScene? {
        owner(assistantId)
        return dao.head(assistantId, id)?.takeUnless { it.deleted }
    }

    suspend fun search(assistantId: String, query: String = "", limit: Int = 50): List<LocalScene> {
        owner(assistantId)
        require(query.length <= 200) { "搜索词最多 200 字。" }
        require(limit in 1..50)
        val q = query.trim()
        val pattern = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return dao.search(assistantId, q, pattern, limit)
    }

    suspend fun edit(assistantId: String, id: String, expectedRevision: Int, title: String, body: String,
                     cues: String = "", author: String = "user"): LocalScene {
        owner(assistantId)
        require(expectedRevision > 0)
        val (t, b, c) = fields(title, body, cues, author)
        return database.withTransaction {
            val old = read(assistantId, id) ?: throw SceneConflict()
            if (old.revision != expectedRevision) throw SceneConflict()
            if (old.title == t && old.body == b && old.cues == c) return@withTransaction old
            old.copy(revision = old.revision + 1, title = t, body = b, cues = c, author = author,
                updatedAt = System.currentTimeMillis()).also {
                dao.update(it)
                dao.insertRevision(it.snapshot())
            }
        }
    }

    suspend fun delete(assistantId: String, id: String, expectedRevision: Int) {
        owner(assistantId)
        require(expectedRevision > 0)
        database.withTransaction {
            val old = dao.head(assistantId, id) ?: throw SceneConflict()
            if (old.deleted && old.revision == expectedRevision + 1) return@withTransaction
            if (old.deleted || old.revision != expectedRevision) throw SceneConflict()
            dao.clearHistory(assistantId, id)
            dao.update(old.copy(revision = old.revision + 1, title = "", body = "", cues = "", deleted = true,
                updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun history(assistantId: String, id: String): List<LocalSceneRevision> {
        owner(assistantId)
        return database.withTransaction {
            if (read(assistantId, id) == null) emptyList() else dao.history(assistantId, id, 50)
        }
    }

    suspend fun deleteAssistantMemories(assistantId: String) {
        owner(assistantId)
        database.withTransaction {
            dao.clearAssistantHistory(assistantId)
            dao.eraseAssistant(assistantId, System.currentTimeMillis())
        }
    }

    private fun LocalScene.snapshot() = LocalSceneRevision(assistantId, id, revision, title, body, cues, author, updatedAt)
}
