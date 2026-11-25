/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.utils

import com.whl.quickjs.android.QuickJSLoader

internal object QuickJsRuntime {
    @Volatile
    private var initialized = false
    private val lock = Any()

    fun ensureInitialized() {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            QuickJSLoader.init()
            initialized = true
        }
    }
}
