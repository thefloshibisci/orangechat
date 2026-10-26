package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_35_36 = object : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS serein_scene (
            assistantId TEXT NOT NULL, id TEXT NOT NULL, revision INTEGER NOT NULL,
            title TEXT NOT NULL, body TEXT NOT NULL, cues TEXT NOT NULL, author TEXT NOT NULL,
            createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL,
            PRIMARY KEY(assistantId,id))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_serein_scene_assistantId_deleted_updatedAt ON serein_scene(assistantId,deleted,updatedAt)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS serein_scene_revision (
            assistantId TEXT NOT NULL, sceneId TEXT NOT NULL, revision INTEGER NOT NULL,
            title TEXT NOT NULL, body TEXT NOT NULL, cues TEXT NOT NULL, author TEXT NOT NULL, recordedAt INTEGER NOT NULL,
            PRIMARY KEY(assistantId,sceneId,revision),
            FOREIGN KEY(assistantId,sceneId) REFERENCES serein_scene(assistantId,id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
    }
}
