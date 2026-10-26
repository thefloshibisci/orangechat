package me.rerere.rikkahub.data.serein

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.serialization.Serializable

/** Authored recollections, never presented as verbatim source messages. Deleted heads prevent retry resurrection. */
@Serializable
@Entity(tableName = "serein_scene", primaryKeys = ["assistantId", "id"],
    indices = [Index(value = ["assistantId", "deleted", "updatedAt"])])
data class LocalScene(
    val assistantId: String,
    val id: String,
    val revision: Int,
    val title: String,
    val body: String,
    val cues: String,
    val author: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Serializable
@Entity(tableName = "serein_scene_revision", primaryKeys = ["assistantId", "sceneId", "revision"],
    foreignKeys = [ForeignKey(entity = LocalScene::class, parentColumns = ["assistantId", "id"],
        childColumns = ["assistantId", "sceneId"], onDelete = ForeignKey.CASCADE)])
data class LocalSceneRevision(
    val assistantId: String,
    val sceneId: String,
    val revision: Int,
    val title: String,
    val body: String,
    val cues: String,
    val author: String,
    val recordedAt: Long,
)

@Dao
interface LocalSceneDAO {
    @Insert suspend fun insert(scene: LocalScene)
    @Update suspend fun update(scene: LocalScene)
    @Insert suspend fun insertRevision(revision: LocalSceneRevision)

    @Query("SELECT * FROM serein_scene WHERE assistantId=:assistantId AND id=:id")
    suspend fun head(assistantId: String, id: String): LocalScene?

    @Query("SELECT * FROM serein_scene_revision WHERE assistantId=:assistantId AND sceneId=:id ORDER BY revision DESC LIMIT :limit")
    suspend fun history(assistantId: String, id: String, limit: Int): List<LocalSceneRevision>

    @Query("DELETE FROM serein_scene_revision WHERE assistantId=:assistantId AND sceneId=:id")
    suspend fun clearHistory(assistantId: String, id: String)

    @Query("DELETE FROM serein_scene_revision WHERE assistantId=:assistantId")
    suspend fun clearAssistantHistory(assistantId: String)

    @Query("""UPDATE serein_scene SET title='', body='', cues='', deleted=1, revision=revision+1, updatedAt=:now
        WHERE assistantId=:assistantId AND deleted=0""")
    suspend fun eraseAssistant(assistantId: String, now: Long)

    @Query("""SELECT * FROM serein_scene WHERE assistantId=:assistantId AND deleted=0
        AND (title LIKE :pattern ESCAPE '\' OR body LIKE :pattern ESCAPE '\' OR cues LIKE :pattern ESCAPE '\' OR id=:query)
        ORDER BY updatedAt DESC, id LIMIT :limit""")
    suspend fun search(assistantId: String, query: String, pattern: String, limit: Int): List<LocalScene>
}
