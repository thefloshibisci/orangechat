/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.content.Context
import java.security.MessageDigest
import org.json.JSONObject

/** Identifies the exact lock and PIN configuration shown by an unlock screen. */
internal data class AppLockUnlockToken(
    val packageName: String,
    val lockRevision: Long,
    val pinRevision: Long,
)

/**
 * App Lock: 被锁定的 App 包名列表 + 解锁 PIN 的本地持久化.
 *
 * 独立于 DataStore/PreferencesStore 体系, 用一个专用的 SharedPreferences 文件保存,
 * 不牵动现有 Settings 的序列化/迁移逻辑 (那套是给"助手配置"用的, App Lock 是设备级全局状态,
 * 语义上不属于单个助手, 单独存更干净)。
 */
object AppLockStore {
    private const val PREFS_NAME = "app_lock_store"
    private const val KEY_LOCKED_PACKAGES = "locked_packages"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_PIN_LENGTH = "pin_length"
    private const val DEFAULT_PIN_LENGTH = 6
    private const val KEY_LOCK_MESSAGES = "lock_messages" // JSON: {"pkg":"message", ...}
    private const val KEY_REQUIRE_PIN_PREFIX = "require_pin_"
    private const val KEY_LOCK_REVISION_PREFIX = "lock_revision_"
    private const val KEY_PIN_REVISION = "pin_revision"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun setRequirePin(context: Context, packageName: String, requirePin: Boolean) {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_REQUIRE_PIN_PREFIX + packageName, true) == requirePin) return
        prefs.edit()
            .putBoolean(KEY_REQUIRE_PIN_PREFIX + packageName, requirePin)
            .putLong(KEY_LOCK_REVISION_PREFIX + packageName, lockRevision(context, packageName) + 1)
            .apply()
    }

    fun getRequirePin(context: Context, packageName: String): Boolean =
        prefs(context).getBoolean(KEY_REQUIRE_PIN_PREFIX + packageName, true)

    fun setLockMessage(context: Context, packageName: String, message: String) {
        val obj = JSONObject(prefs(context).getString(KEY_LOCK_MESSAGES, "{}") ?: "{}")
        obj.put(packageName, message)
        prefs(context).edit().putString(KEY_LOCK_MESSAGES, obj.toString()).apply()
    }

    fun getLockMessage(context: Context, packageName: String): String? {
        val obj = JSONObject(prefs(context).getString(KEY_LOCK_MESSAGES, "{}") ?: "{}")
        return if (obj.has(packageName)) obj.getString(packageName) else null
    }

    fun getLockedPackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_LOCKED_PACKAGES, emptySet())?.toSet() ?: emptySet()

    fun isLocked(context: Context, packageName: String): Boolean =
        packageName in getLockedPackages(context)

    @Synchronized
    fun lockApp(context: Context, packageName: String) {
        val current = getLockedPackages(context).toMutableSet()
        current.add(packageName)
        prefs(context).edit()
            .putStringSet(KEY_LOCKED_PACKAGES, current)
            .putLong(KEY_LOCK_REVISION_PREFIX + packageName, lockRevision(context, packageName) + 1)
            .apply()
    }

    @Synchronized
    fun unlockApp(context: Context, packageName: String) {
        val current = getLockedPackages(context).toMutableSet()
        current.remove(packageName)
        prefs(context).edit()
            .putStringSet(KEY_LOCKED_PACKAGES, current)
            .putLong(KEY_LOCK_REVISION_PREFIX + packageName, lockRevision(context, packageName) + 1)
            .apply()
    }

    private fun lockRevision(context: Context, packageName: String): Long =
        prefs(context).getLong(KEY_LOCK_REVISION_PREFIX + packageName, 0L)

    @Synchronized
    internal fun invalidateUnlockToken(context: Context, packageName: String) {
        prefs(context).edit()
            .putLong(KEY_LOCK_REVISION_PREFIX + packageName, lockRevision(context, packageName) + 1)
            .apply()
    }

    @Synchronized
    internal fun unlockToken(context: Context, packageName: String): AppLockUnlockToken? =
        if (isLocked(context, packageName) && getRequirePin(context, packageName) && hasPin(context)) {
            AppLockUnlockToken(
                packageName,
                lockRevision(context, packageName),
                prefs(context).getLong(KEY_PIN_REVISION, 0L),
            )
        } else null

    fun hasPin(context: Context): Boolean =
        !prefs(context).getString(KEY_PIN_HASH, null).isNullOrBlank()

    /** 实际设置的密码位数 (4-6). 还没设置过密码时返回默认值 6, 仅用于兜底显示. */
    fun getPinLength(context: Context): Int =
        prefs(context).getInt(KEY_PIN_LENGTH, DEFAULT_PIN_LENGTH)

    @Synchronized
    fun setPin(context: Context, pin: String) {
        val prefs = prefs(context)
        prefs.edit()
            .putString(KEY_PIN_HASH, hash(pin))
            .putInt(KEY_PIN_LENGTH, pin.length)
            .putLong(KEY_PIN_REVISION, prefs.getLong(KEY_PIN_REVISION, 0L) + 1)
            .apply()
    }

    fun verifyPin(context: Context, pin: String): Boolean {
        val stored = prefs(context).getString(KEY_PIN_HASH, null) ?: return false
        return stored == hash(pin)
    }

    private fun hash(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
