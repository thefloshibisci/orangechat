/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localtasks

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.UUID

/**
 * Tiny storage seam so the scheduler/ledger can be unit tested without Android.
 * The production implementation is [SharedPreferencesLocalTaskStore].
 */
interface LocalTaskKeyValueStore {
    fun checkAvailable() = Unit
    fun read(key: String): String?
    fun write(key: String, value: String)
}

class SharedPreferencesLocalTaskStore internal constructor(
    private val preferences: SharedPreferences,
    private val storeName: String,
) : LocalTaskKeyValueStore {
    constructor(context: Context, storeName: String) : this(
        context.applicationContext.getSharedPreferences(storeName, Context.MODE_PRIVATE), storeName,
    )

    override fun checkAvailable() = synchronized(lock) {
        check(storeName !in failedStores) { "Local task storage failed; restart required" }
    }

    override fun read(key: String): String? = synchronized(lock) {
        checkAvailable()
        preferences.getString(key, null)
    }

    override fun write(key: String, value: String) = writeValues(mapOf(key to value))

    internal fun writeValues(values: Map<String, String>) = synchronized(lock) {
        checkAvailable()
        try {
            val editor = preferences.edit()
            values.forEach { (key, value) -> editor.putString(key, value) }
            check(editor.commit()) { "Local task storage write failed" }
        } catch (error: Exception) {
            // commit(false) may still update the process cache; no adapter may trust it.
            failedStores.add(storeName)
            throw error
        }
    }

    companion object {
        private val lock = Any()
        private val failedStores = mutableSetOf<String>()
    }
}

object LocalTaskSettingsCodec {
    fun encode(settings: LocalTaskSettings): String =
        LocalTaskJson.task.encodeToString(LocalTaskSettings.serializer(), settings.normalized())

    /** Corrupt/old JSON must never crash task start; fall back to the safe defaults. */
    fun decode(raw: String?): LocalTaskSettings {
        if (raw.isNullOrBlank()) return LocalTaskSettings()
        return runCatching {
            LocalTaskJson.task.decodeFromString(LocalTaskSettings.serializer(), raw).normalized()
        }.getOrElse { LocalTaskSettings() }
    }
}

/**
 * Native-owned settings holder. There is deliberately no JS path to [set]: a page can read the
 * effective settings but cannot grant itself participants, publishing or image rights.
 */
class LocalTaskSettingsStore(
    private val store: LocalTaskKeyValueStore,
) {
    private var cached: LocalTaskSettings? = null

    @Synchronized
    fun get(): LocalTaskSettings {
        store.checkAvailable()
        cached?.let { return it }
        return LocalTaskSettingsCodec.decode(store.read(LocalTasksApi.KEY_SETTINGS)).also { cached = it }
    }

    @Synchronized
    fun set(settings: LocalTaskSettings): LocalTaskSettings {
        val normalized = settings.normalized()
        store.write(LocalTasksApi.KEY_SETTINGS, LocalTaskSettingsCodec.encode(normalized))
        cached = normalized
        return normalized
    }
}

internal object LocalTaskIds {
    fun newTaskId(): String = LocalTasksApi.TASK_ID_PREFIX + randomHex(24)

    fun newReviewId(): String = LocalTasksApi.REVIEW_ID_PREFIX + randomHex(20)

    /**
     * Deterministic so a retry of the same (post, assistant, reply index) never inserts a second
     * comment: localdata returns the original row for a repeated client key.
     */
    fun commentClientKey(postId: String, assistantId: String, replyIndex: Int): String =
        LocalTasksApi.TASK_ID_PREFIX + sha256Hex("$postId|$assistantId|$replyIndex").take(32)

    fun publishClientKey(taskId: String): String =
        LocalTasksApi.TASK_ID_PREFIX + sha256Hex("publish|$taskId").take(32)

    fun storeName(pluginId: String): String =
        LocalTasksApi.PREFS_PREFIX + sha256Hex(pluginId).take(16)

    private fun randomHex(length: Int): String =
        UUID.randomUUID().toString().replace("-", "").take(length)
}

internal fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
